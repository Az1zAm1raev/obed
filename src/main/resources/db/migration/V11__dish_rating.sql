-- V11__dish_rating.sql
-- Оценки блюд 1..5. Ставит только тот, кто это блюдо взял в опросе.
-- Одна оценка на человека на опцию опроса: переоценка перезаписывает.

CREATE TABLE dish_rating (
    option_id  BIGINT NOT NULL REFERENCES lunch_poll_option(id) ON DELETE CASCADE,
    user_id    BIGINT NOT NULL,
    score      SMALLINT NOT NULL CHECK (score BETWEEN 1 AND 5),
    rated_at   TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (option_id, user_id)
);

-- Общий балл блюда копится по всем опросам. Блюда из каталога сводим по dish_id,
-- блюда дня — по нормализованному названию (как DishMatcher.normalize).
ALTER TABLE lunch_poll_option ADD COLUMN norm TEXT;

UPDATE lunch_poll_option
SET norm = btrim(regexp_replace(replace(lower(text), 'ё', 'е'), '[^[:alnum:]]+', ' ', 'g'));

CREATE INDEX ix_option_norm ON lunch_poll_option(norm);
CREATE INDEX ix_option_dish ON lunch_poll_option(dish_id);
