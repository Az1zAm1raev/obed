-- V6__receipt_balance.sql
-- Оплата привязана к каждому блюду, а не к опросу целиком.
-- Один одобренный PDF = право на один голос. «Добавить ещё блюдо» = нужен ещё чек.
-- Считаем ЧЕКИ, не сумму: сумма у некоторых людей другая, на неё не смотрим.

-- Старую модель "один платёж на человека" заменяем на список чеков.
DROP TABLE IF EXISTS lunch_payment;

CREATE TABLE lunch_receipt (
    id         BIGSERIAL PRIMARY KEY,
    poll_id    BIGINT NOT NULL REFERENCES lunch_poll(id) ON DELETE CASCADE,
    user_id    BIGINT NOT NULL,
    status     TEXT   NOT NULL,          -- APPROVED / MANUAL  (REJECTED не сохраняем)
    file_id    TEXT,
    tx_id      TEXT,
    spent      BOOLEAN NOT NULL DEFAULT FALSE,   -- уже потрачен на голос?
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX ix_receipt_user ON lunch_receipt(poll_id, user_id);

-- Один и тот же чек нельзя прислать дважды (ни собой, ни другим человеком).
-- Это же не даёт получить два блюда за один чек.
CREATE UNIQUE INDEX ux_receipt_tx ON lunch_receipt(tx_id) WHERE tx_id IS NOT NULL;

-- lunch_pending_receipt из V3 остаётся как есть.
