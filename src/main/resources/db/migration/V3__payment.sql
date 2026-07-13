-- V3__payment.sql
-- Проверка чеков об оплате перед голосованием.

CREATE TABLE lunch_payment (
    poll_id    BIGINT NOT NULL REFERENCES lunch_poll(id) ON DELETE CASCADE,
    user_id    BIGINT NOT NULL,
    status     TEXT   NOT NULL,          -- APPROVED / REJECTED / MANUAL
    file_id    TEXT,
    tx_id      TEXT,
    receipt_date DATE,
    reason     TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (poll_id, user_id)
);

-- Один и тот же чек нельзя переслать с трёх аккаунтов.
CREATE UNIQUE INDEX ux_payment_tx ON lunch_payment(tx_id) WHERE tx_id IS NOT NULL;

-- Куда именно человек шлёт чек: /start pay_<pollId> в личке.
CREATE TABLE lunch_pending_receipt (
    user_id    BIGINT PRIMARY KEY,
    poll_id    BIGINT NOT NULL REFERENCES lunch_poll(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
