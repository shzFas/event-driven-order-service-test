#!/usr/bin/env python3
"""Continuous mock traffic for order-service.

Places orders at a steady rate for as long as you let it run, so the dashboard
has something to show and the pipeline has something to fall behind on. The
traffic is deliberately not uniform: a share of it asks for the product that can
never be reserved, and another share replays a business key that was already
used. Both are ordinary in production, and both are what the service claims to
handle — the failure path and the idempotency guard end up exercised by the same
run that measures throughput.

Standard library only, so it runs from the host with no install, or in the
`python` image the compose file uses.

    python3 scripts/loadgen.py                       # 10 orders/s, until Ctrl-C
    python3 scripts/loadgen.py --rate 100 --concurrency 32
    python3 scripts/loadgen.py --rate 0              # as fast as it can go
    python3 scripts/loadgen.py --duration 120 --ramp 30

Every option can also come from the environment (LOADGEN_RATE, LOADGEN_URL, …),
which is how docker-compose parameterises the `loadgen` service.
"""

import argparse
import json
import os
import random
import signal
import sys
import threading
import time
from collections import Counter, deque
from http.client import HTTPConnection, HTTPException
from queue import Empty, Queue
from urllib.parse import urlparse

# Keeping every latency sample would grow without bound on a run left going
# overnight; past this many the reservoir replaces a random earlier sample, which
# keeps the distribution representative at a fixed cost.
RESERVOIR_LIMIT = 200_000

TERMINAL_STATUSES = ("PAID", "FAILED")


def percentile(values, quantile):
    if not values:
        return 0.0
    ordered = sorted(values)
    index = int(round(quantile * (len(ordered) - 1)))
    return ordered[max(0, min(len(ordered) - 1, index))]


class Pacer:
    """Hands out send slots at a target rate, shared by every worker.

    Slots are handed out on a schedule rather than by sleeping a fixed interval
    after each request: with the latter the actual rate would be the target minus
    however long the server took, and the load would quietly drop off exactly
    when the service starts struggling.
    """

    def __init__(self, rate, ramp):
        self.rate = rate
        self.ramp = ramp
        self.lock = threading.Lock()
        self.started_at = time.monotonic()
        self.next_slot = self.started_at

    def current_rate(self):
        if self.rate <= 0:
            return 0.0
        if self.ramp <= 0:
            return self.rate
        elapsed = time.monotonic() - self.started_at
        return self.rate * min(1.0, max(0.02, elapsed / self.ramp))

    def acquire(self, stop):
        """Blocks until this caller may send. False means the run is over."""
        if self.rate <= 0:
            return not stop.is_set()

        with self.lock:
            now = time.monotonic()
            # A backlog older than a second is not worth catching up on — bursting
            # to recover it would distort the very rate we are trying to hold.
            if self.next_slot < now - 1.0:
                self.next_slot = now
            slot = self.next_slot
            self.next_slot = slot + 1.0 / self.current_rate()

        remaining = slot - time.monotonic()
        while remaining > 0:
            if stop.wait(min(remaining, 0.25)):
                return False
            remaining = slot - time.monotonic()
        return not stop.is_set()


class Stats:
    """Counters and latencies, cumulative and for the current report window."""

    def __init__(self):
        self.lock = threading.Lock()
        self.total = Counter()
        self.window = Counter()
        self.window_latencies = []
        self.latencies = []
        self.errors = Counter()
        self.terminal = Counter()
        self.end_to_end = []
        self.started_at = time.monotonic()

    def record(self, outcome, latency_ms, error=None):
        with self.lock:
            self.total[outcome] += 1
            self.window[outcome] += 1
            self.total["sent"] += 1
            self.window["sent"] += 1
            if error is not None:
                self.errors[error] += 1
            if latency_ms is None:
                return
            self.window_latencies.append(latency_ms)
            if len(self.latencies) < RESERVOIR_LIMIT:
                self.latencies.append(latency_ms)
            else:
                self.latencies[random.randrange(RESERVOIR_LIMIT)] = latency_ms

    def record_terminal(self, status, seconds):
        with self.lock:
            self.terminal[status] += 1
            if seconds is not None:
                self.end_to_end.append(seconds)

    def take_window(self):
        with self.lock:
            window, latencies = self.window, self.window_latencies
            self.window, self.window_latencies = Counter(), []
            return window, latencies, dict(self.terminal), self.total["follow_open"]

    def snapshot(self):
        with self.lock:
            return (dict(self.total), list(self.latencies), dict(self.errors), dict(self.terminal),
                    list(self.end_to_end))

    def note(self, key, delta=1):
        with self.lock:
            self.total[key] += delta


class Client:
    """One keep-alive connection, reconnecting when the server closes it.

    A fresh TCP connection per request would measure the connection setup as much
    as the service, and at a few hundred requests a second it exhausts local
    ports long before the service breaks a sweat.
    """

    def __init__(self, base_url, timeout):
        parsed = urlparse(base_url)
        if not parsed.hostname:
            raise ValueError("--url must look like http://host:port")
        self.host = parsed.hostname
        self.port = parsed.port or 80
        self.prefix = parsed.path.rstrip("/")
        self.timeout = timeout
        self.connection = None

    def close(self):
        if self.connection is not None:
            try:
                self.connection.close()
            except OSError:
                pass
            self.connection = None

    def request(self, method, path, body=None):
        # A connection that has been sitting idle can be closed at the far end
        # without us noticing until we write to it, so a reused connection gets
        # one retry. A POST replayed that way is safe by construction: the
        # business key makes the second attempt a 409 rather than a second order.
        reused = self.connection is not None
        try:
            return self._send(method, path, body)
        except (HTTPException, OSError):
            self.close()
            if not reused:
                raise
            return self._send(method, path, body)

    def _send(self, method, path, body):
        if self.connection is None:
            self.connection = HTTPConnection(self.host, self.port, timeout=self.timeout)
        headers = {"Accept": "application/json"}
        payload = None
        if body is not None:
            payload = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        self.connection.request(method, self.prefix + path, body=payload, headers=headers)
        response = self.connection.getresponse()
        raw = response.read()
        if response.will_close:
            self.close()
        return response.status, raw


class OrderFactory:
    """Builds the payloads. Thread-safe: workers share one instance."""

    def __init__(self, args):
        self.args = args
        self.lock = threading.Lock()
        self.sequence = 0
        self.run_id = "%06x" % random.randrange(16 ** 6)
        # Recently used business keys, to replay on purpose.
        self.recent_keys = deque(maxlen=512)
        self.random = random.Random(args.seed)

    def next_order(self):
        """Returns (payload, kind) where kind is new / duplicate / unavailable."""
        with self.lock:
            rnd = self.random
            if self.recent_keys and rnd.random() < self.args.duplicate_share:
                customer_id, reference, product_id = rnd.choice(self.recent_keys)
                kind = "duplicate"
            else:
                self.sequence += 1
                customer_id = "cust-%04d" % rnd.randrange(self.args.customers)
                reference = "load-%s-%08d" % (self.run_id, self.sequence)
                if rnd.random() < self.args.unavailable_share:
                    product_id, kind = self.args.unavailable_product, "unavailable"
                else:
                    product_id = "sku-%d" % rnd.randrange(self.args.products)
                    kind = "new"
                self.recent_keys.append((customer_id, reference, product_id))

            payload = {
                "customerId": customer_id,
                "orderReference": reference,
                "productId": product_id,
                "quantity": rnd.randint(1, self.args.max_quantity),
                "amount": round(rnd.uniform(5.0, 500.0), 2),
            }
            return payload, kind


def classify(status):
    if status == 202:
        return "accepted"
    if status == 409:
        return "conflict"
    if 400 <= status < 500:
        return "client_error"
    if status >= 500:
        return "server_error"
    return "other"


def worker(args, pacer, stats, factory, follow_queue, stop):
    client = Client(args.url, args.timeout)
    try:
        while not stop.is_set():
            if not pacer.acquire(stop):
                return
            payload, kind = factory.next_order()
            started = time.perf_counter()
            try:
                status, raw = client.request("POST", "/orders", payload)
            except (HTTPException, OSError) as ex:
                stats.record("network_error", (time.perf_counter() - started) * 1000, error=repr(ex))
                continue

            latency_ms = (time.perf_counter() - started) * 1000
            outcome = classify(status)
            error = None
            if outcome in ("client_error", "server_error"):
                error = "HTTP %d: %s" % (status, raw[:160].decode("utf-8", "replace"))
            stats.record(outcome, latency_ms, error=error)
            if kind == "unavailable":
                stats.note("unavailable_requested")

            if outcome == "accepted" and follow_queue is not None:
                # The factory's generator is under its own lock; this one only
                # decides whether to sample, so the shared module-level RNG does.
                if random.random() < args.follow_share:
                    order_id = order_id_from(raw)
                    if order_id:
                        stats.note("follow_open")
                        follow_queue.put((order_id, time.monotonic(), time.monotonic() + args.follow_delay))
    finally:
        client.close()


def order_id_from(raw):
    try:
        return json.loads(raw.decode("utf-8")).get("id")
    except (ValueError, UnicodeDecodeError):
        return None


def follower(args, stats, follow_queue, stop):
    """Polls a sample of accepted orders until they reach a terminal status.

    This is the only measurement taken from outside the service: everything else
    is the app describing itself. It answers the question the API deliberately
    leaves open by returning 202 — how long until the order is actually done.
    """
    client = Client(args.url, args.timeout)
    try:
        while not stop.is_set():
            try:
                order_id, submitted_at, due_at = follow_queue.get(timeout=0.5)
            except Empty:
                continue

            delay = due_at - time.monotonic()
            if delay > 0 and stop.wait(min(delay, 5.0)):
                return

            try:
                status, raw = client.request("GET", "/orders/%s" % order_id)
            except (HTTPException, OSError):
                stats.record_terminal("unreachable", None)
                stats.note("follow_open", -1)
                continue

            order_status = None
            if status == 200:
                try:
                    order_status = json.loads(raw.decode("utf-8")).get("status")
                except (ValueError, UnicodeDecodeError):
                    order_status = None

            elapsed = time.monotonic() - submitted_at
            if order_status in TERMINAL_STATUSES:
                stats.record_terminal(order_status, elapsed)
                stats.note("follow_open", -1)
            elif elapsed >= args.follow_timeout:
                # Still PENDING or RESERVED well past the deadline: the pipeline
                # is not keeping up, which is a result worth reporting.
                stats.record_terminal("timeout:%s" % (order_status or status), None)
                stats.note("follow_open", -1)
            else:
                follow_queue.put((order_id, submitted_at, time.monotonic() + args.follow_poll))
    finally:
        client.close()


HEADER = ("%8s %10s %9s %8s %8s %7s %7s %7s %7s %8s %8s %8s" %
          ("elapsed", "target", "sent", "rps", "202", "409", "4xx", "5xx", "err", "p50", "p95", "p99"))


def report_loop(args, pacer, stats, stop):
    lines = 0
    last_at = time.monotonic()
    while not stop.wait(args.report_interval):
        now = time.monotonic()
        window, latencies, terminal, open_follows = stats.take_window()
        elapsed_window = max(1e-9, now - last_at)
        last_at = now

        if lines % 20 == 0:
            print(HEADER)
        lines += 1
        print("%7ds %9.1f/s %9d %8.1f %8d %7d %7d %7d %7d %7.0fms %6.0fms %6.0fms" % (
            now - stats.started_at,
            pacer.current_rate(),
            window["sent"],
            window["sent"] / elapsed_window,
            window["accepted"],
            window["conflict"],
            window["client_error"],
            window["server_error"],
            window["network_error"],
            percentile(latencies, 0.50),
            percentile(latencies, 0.95),
            percentile(latencies, 0.99),
        ))
        if terminal:
            print("         followed so far: %s | awaiting %d" % (
                "  ".join("%s %d" % (status, count) for status, count in sorted(terminal.items())),
                max(0, open_follows)))


def print_summary(args, stats):
    total, latencies, errors, terminal, end_to_end = stats.snapshot()
    elapsed = time.monotonic() - stats.started_at
    sent = total.get("sent", 0)

    print("\n" + "─" * 78)
    print("Ran for %.1fs, %d requests, %.1f/s average" % (elapsed, sent, sent / max(elapsed, 1e-9)))
    print("  202 accepted      %d" % total.get("accepted", 0))
    print("  409 duplicate     %d  (replayed business keys, rejected as intended)" % total.get("conflict", 0))
    print("  4xx other         %d" % total.get("client_error", 0))
    print("  5xx               %d" % total.get("server_error", 0))
    print("  network errors    %d" % total.get("network_error", 0))
    print("  POST latency      p50 %.0fms  p95 %.0fms  p99 %.0fms  max %.0fms" % (
        percentile(latencies, 0.50), percentile(latencies, 0.95),
        percentile(latencies, 0.99), max(latencies) if latencies else 0.0))

    if terminal:
        print("  followed orders   %s" % "  ".join(
            "%s %d" % (status, count) for status, count in sorted(terminal.items())))
    if end_to_end:
        print("  accepted → terminal  p50 %.1fs  p95 %.1fs  max %.1fs" % (
            percentile(end_to_end, 0.50), percentile(end_to_end, 0.95), max(end_to_end)))
    if errors:
        print("  errors:")
        for message, count in sorted(errors.items(), key=lambda item: -item[1])[:5]:
            print("    %6d  %s" % (count, message))
    print("─" * 78)


def env_default(name, cast, fallback):
    raw = os.environ.get(name, "").strip()
    if not raw:
        return fallback
    try:
        return cast(raw)
    except ValueError:
        print("Ignoring %s=%r: not a valid value" % (name, raw), file=sys.stderr)
        return fallback


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Continuous mock order traffic for order-service.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument("--url", default=env_default("LOADGEN_URL", str, "http://localhost:8080"),
                        help="base URL of the service")
    parser.add_argument("--rate", type=float, default=env_default("LOADGEN_RATE", float, 10.0),
                        help="orders per second; 0 means as fast as the workers manage")
    parser.add_argument("--concurrency", type=int, default=env_default("LOADGEN_CONCURRENCY", int, 8),
                        help="parallel senders; the ceiling on --rate 0")
    parser.add_argument("--duration", type=float, default=env_default("LOADGEN_DURATION", float, 0.0),
                        help="seconds to run; 0 runs until interrupted")
    parser.add_argument("--ramp", type=float, default=env_default("LOADGEN_RAMP", float, 0.0),
                        help="seconds to climb from near-zero to --rate")
    parser.add_argument("--products", type=int, default=env_default("LOADGEN_PRODUCTS", int, 8),
                        help="distinct SKUs to order; each has its own stock in the ledger")
    parser.add_argument("--customers", type=int, default=env_default("LOADGEN_CUSTOMERS", int, 200),
                        help="distinct customers; also the Kafka partition key, so this caps ordering parallelism")
    parser.add_argument("--max-quantity", type=int, default=env_default("LOADGEN_MAX_QUANTITY", int, 3),
                        help="largest quantity to order")
    parser.add_argument("--unavailable-share", type=float,
                        default=env_default("LOADGEN_UNAVAILABLE_SHARE", float, 0.02),
                        help="share of orders for the product that can never be reserved")
    parser.add_argument("--unavailable-product", default=env_default("LOADGEN_UNAVAILABLE_PRODUCT", str,
                                                                     "sku-unavailable"),
                        help="the SKU app.stock.unavailable-products refuses")
    parser.add_argument("--duplicate-share", type=float, default=env_default("LOADGEN_DUPLICATE_SHARE", float, 0.02),
                        help="share of requests that replay an already used business key")
    parser.add_argument("--follow-share", type=float, default=env_default("LOADGEN_FOLLOW_SHARE", float, 0.05),
                        help="share of accepted orders to poll until they finish")
    parser.add_argument("--follow-delay", type=float, default=env_default("LOADGEN_FOLLOW_DELAY", float, 0.25),
                        help="seconds to wait before the first status check")
    parser.add_argument("--follow-poll", type=float, default=env_default("LOADGEN_FOLLOW_POLL", float, 0.5),
                        help="seconds between further status checks")
    parser.add_argument("--follow-timeout", type=float, default=env_default("LOADGEN_FOLLOW_TIMEOUT", float, 60.0),
                        help="give up on an order that has not finished within this many seconds")
    parser.add_argument("--report-interval", type=float, default=env_default("LOADGEN_REPORT_INTERVAL", float, 5.0),
                        help="seconds between progress lines")
    parser.add_argument("--timeout", type=float, default=env_default("LOADGEN_TIMEOUT", float, 10.0),
                        help="per-request timeout in seconds")
    parser.add_argument("--seed", type=int, default=env_default("LOADGEN_SEED", int, None),
                        help="seed for reproducible traffic")
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    if args.concurrency < 1:
        print("--concurrency must be at least 1", file=sys.stderr)
        return 2

    stop = threading.Event()
    for name in ("SIGINT", "SIGTERM"):
        if hasattr(signal, name):
            signal.signal(getattr(signal, name), lambda signum, frame: stop.set())

    pacer = Pacer(args.rate, args.ramp)
    stats = Stats()
    factory = OrderFactory(args)
    follow_queue = Queue() if args.follow_share > 0 else None

    print("POST %s/orders — %s, %d workers%s" % (
        args.url.rstrip("/"),
        ("%.1f orders/s" % args.rate) if args.rate > 0 else "unthrottled",
        args.concurrency,
        (", ramping over %.0fs" % args.ramp) if args.ramp > 0 else ""))
    print("%d products, %d customers, %.0f%% unavailable SKU, %.0f%% replayed keys, %.0f%% followed to completion" % (
        args.products, args.customers, args.unavailable_share * 100,
        args.duplicate_share * 100, args.follow_share * 100))
    print("Stop with Ctrl-C.\n")

    threads = [threading.Thread(target=worker, name="send-%d" % index, daemon=True,
                                args=(args, pacer, stats, factory, follow_queue, stop))
               for index in range(args.concurrency)]
    if follow_queue is not None:
        threads.append(threading.Thread(target=follower, name="follow", daemon=True,
                                        args=(args, stats, follow_queue, stop)))
    for thread in threads:
        thread.start()

    reporter = threading.Thread(target=report_loop, name="report", daemon=True,
                                args=(args, pacer, stats, stop))
    reporter.start()

    try:
        if args.duration > 0:
            stop.wait(args.duration)
        else:
            while not stop.wait(0.5):
                pass
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()

    for thread in threads:
        thread.join(timeout=args.timeout + 1)
    print_summary(args, stats)
    total, _, _, _, _ = stats.snapshot()
    # A run that saw a server error or could not reach the service failed, even
    # though it produced numbers — worth a non-zero exit in CI.
    return 1 if total.get("server_error", 0) or total.get("network_error", 0) else 0


if __name__ == "__main__":
    sys.exit(main())
