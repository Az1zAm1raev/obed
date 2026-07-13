-- V5__settings.sql
-- Реквизит получателя живёт в БД, а не в .env: админ меняет его командой /recipient
-- без пересборки и рестарта.

CREATE TABLE app_setting (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

INSERT INTO app_setting(key, value) VALUES
    ('recipient.phone', '996708760011'),
    ('recipient.name',  'Азиз Амираев'),
    ('recipient.id',    '5758970130'),
    -- Номера, на которые платить БОЛЬШЕ НЕЛЬЗЯ. Через запятую.
    -- Чек на такой номер отклоняется. Список нужен только чтобы объяснить человеку,
    -- что деньги ушли не туда, а не для того, чтобы такой чек засчитать.
    ('recipient.phone.legacy', '996702760896');
