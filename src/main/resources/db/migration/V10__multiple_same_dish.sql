-- V10__multiple_same_dish.sql
-- Разрешаем одному человеку взять несколько ПОРЦИЙ одного блюда.
-- Раньше первичный ключ (poll_id, user_id, option_id) физически не давал
-- вставить вторую такую же строку.

ALTER TABLE lunch_poll_vote DROP CONSTRAINT lunch_poll_vote_pkey;

ALTER TABLE lunch_poll_vote ADD COLUMN id BIGSERIAL PRIMARY KEY;

-- Поиск голосов человека в опросе остаётся быстрым.
CREATE INDEX ix_vote_user ON lunch_poll_vote(poll_id, user_id);
