CREATE TABLE orders (
    id               UUID           PRIMARY KEY,
    customer_id      VARCHAR(64)    NOT NULL,
    order_reference  VARCHAR(64)    NOT NULL,
    product_id       VARCHAR(64)    NOT NULL,
    quantity         INTEGER        NOT NULL,
    amount           NUMERIC(12, 2) NOT NULL,
    status           VARCHAR(16)    NOT NULL,
    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    CONSTRAINT ck_orders_quantity_positive   CHECK (quantity > 0),
    CONSTRAINT ck_orders_amount_non_negative CHECK (amount >= 0)
);

-- Business key. A customer's order reference identifies an order uniquely, so a
-- retried request or a redelivered event can never create a second row: the
-- insert is rejected by the database rather than by application-level checks.
ALTER TABLE orders
    ADD CONSTRAINT uk_orders_customer_id_order_reference UNIQUE (customer_id, order_reference);

-- Supports lookups of a customer's order history.
CREATE INDEX idx_orders_customer_id ON orders (customer_id);

-- Supports querying orders stuck in a non-terminal state.
CREATE INDEX idx_orders_status ON orders (status);
