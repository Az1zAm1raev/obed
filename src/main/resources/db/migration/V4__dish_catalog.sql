-- V4__dish_catalog.sql
-- Каталог постоянных блюд + алиасы написаний.
-- Блюда дня в каталог НЕ попадают: из 13 меню 56 названий из 72 встретились однократно.

CREATE EXTENSION IF NOT EXISTS fuzzystrmatch;   -- levenshtein()

-- ---------------------------------------------------------------- блюда

CREATE TABLE dish (
    id              BIGSERIAL PRIMARY KEY,
    name            TEXT NOT NULL,              -- каноническое имя, оно же на кнопке
    photo_file_id   TEXT,                       -- Telegram file_id
    photo_unique_id TEXT,                       -- стабильный id файла, для защиты от дублей фото
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

-- ------------------------------------------------------------- алиасы
-- Единственная точка входа для матчинга. Каноническое имя тоже лежит здесь
-- как алиас, поэтому поиск всегда одним запросом.

CREATE TABLE dish_alias (
    alias   TEXT PRIMARY KEY,                   -- УЖЕ нормализованный ключ
    dish_id BIGINT NOT NULL REFERENCES dish(id) ON DELETE CASCADE,
    added_by BIGINT,
    added_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX ix_dish_alias_dish ON dish_alias(dish_id);

-- ------------------------------------------------- связь с опросом

ALTER TABLE lunch_poll_option ADD COLUMN dish_id BIGINT REFERENCES dish(id);
--  dish_id IS NULL  ->  блюдо дня, живёт только внутри опроса

-- ------------------------------------------- состояние диалога с админом
-- Бот на getUpdates без сессий, поэтому FSM держим в БД.
--  ADD_DISH_NAME  -> ждём текст названия
--  ADD_DISH_PHOTO -> ждём фото, payload = dish_id
--  ADD_ALIAS      -> ждём текст алиаса, payload = dish_id
--  SET_PHOTO      -> ждём фото для замены, payload = dish_id

CREATE TABLE admin_state (
    user_id    BIGINT PRIMARY KEY,
    state      TEXT NOT NULL,
    payload    BIGINT,
    chat_id    BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

-- ---------------------------------------------- вопросы по несовпадениям
-- Строка меню, похожая на существующее блюдо (levenshtein <= 2),
-- но не совпавшая точно. Ждёт решения админа.

CREATE TABLE dish_question (
    id          BIGSERIAL PRIMARY KEY,
    poll_id     BIGINT NOT NULL REFERENCES lunch_poll(id) ON DELETE CASCADE,
    option_id   BIGINT NOT NULL REFERENCES lunch_poll_option(id) ON DELETE CASCADE,
    raw_text    TEXT NOT NULL,
    norm_text   TEXT NOT NULL,
    suggest_id  BIGINT REFERENCES dish(id),
    resolved    BOOLEAN NOT NULL DEFAULT FALSE
);

-- --------------------------------------------------------------- ядро
-- Шесть блюд, встретившихся >= 4 раз за 13 меню.

INSERT INTO dish (name) VALUES
    ('Босо лагман'),
    ('Гюро лагман'),
    ('Бифштекс гарнир'),
    ('Шамси гарнир'),
    ('Курица с гарниром'),
    ('Паста карбонара');

INSERT INTO dish_alias (alias, dish_id)
SELECT lower(name), id FROM dish;
