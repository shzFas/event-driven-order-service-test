package com.yz.orderservice.service;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StockLedgerTest {

	private final StockLedger stockLedger = new StockLedger(10, List.of("sku-unavailable"));

	@Test
	void reservingTakesUnitsFromStock() {
		assertThat(this.stockLedger.reserve("sku-1", 3)).isTrue();
		assertThat(this.stockLedger.available("sku-1")).isEqualTo(7);
	}

	@Test
	void reservingMoreThanAvailableFailsAndChangesNothing() {
		assertThat(this.stockLedger.reserve("sku-1", 11)).isFalse();
		assertThat(this.stockLedger.available("sku-1")).isEqualTo(10);
	}

	@Test
	void stockCanBeDrainedExactly() {
		assertThat(this.stockLedger.reserve("sku-1", 10)).isTrue();
		assertThat(this.stockLedger.available("sku-1")).isZero();
		assertThat(this.stockLedger.reserve("sku-1", 1)).isFalse();
	}

	@Test
	void productsFlaggedUnavailableAreNeverReserved() {
		assertThat(this.stockLedger.reserve("sku-unavailable", 1)).isFalse();
	}

	/**
	 * Consumers run in parallel across partitions, so reservations really do race.
	 * Exactly as many as there are units may succeed — never one more.
	 */
	@Test
	void concurrentReservationsNeverOversell() throws Exception {
		int units = 50;
		StockLedger ledger = new StockLedger(units, List.of());
		int attempts = 200;

		ExecutorService executor = Executors.newFixedThreadPool(16);
		try {
			List<Callable<Boolean>> tasks = IntStream.range(0, attempts)
				.mapToObj((i) -> (Callable<Boolean>) () -> ledger.reserve("sku-1", 1))
				.toList();

			long granted = 0;
			for (Future<Boolean> result : executor.invokeAll(tasks)) {
				if (result.get()) {
					granted++;
				}
			}

			assertThat(granted).isEqualTo(units);
			assertThat(ledger.available("sku-1")).isZero();
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void resetRestoresInitialStock() {
		this.stockLedger.reserve("sku-1", 4);
		this.stockLedger.reset();

		assertThat(this.stockLedger.available("sku-1")).isEqualTo(10);
	}

}
