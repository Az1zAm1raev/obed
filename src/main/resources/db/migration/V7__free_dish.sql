-- V7__free_dish.sql
-- Отметка "это блюдо взято бесплатно" (привилегия админа/ведущего обеды).
-- Нужна, чтобы /money исключил его из расчёта, а /summary для столовой — оставил.

ALTER TABLE lunch_poll_vote ADD COLUMN free BOOLEAN NOT NULL DEFAULT FALSE;

-- QR оплаты хранится как Telegram file_id в настройках (app_setting), отдельная колонка не нужна.
