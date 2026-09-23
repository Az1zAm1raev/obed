-- V12__rating_10_scale.sql
-- Оценки блюд по 10-балльной шкале вместо 5-балльной.
-- Уже поставленные оценки (1..5) переводим в новую шкалу умножением на 2.

ALTER TABLE dish_rating DROP CONSTRAINT dish_rating_score_check;

UPDATE dish_rating SET score = score * 2;

ALTER TABLE dish_rating ADD CONSTRAINT dish_rating_score_check CHECK (score BETWEEN 1 AND 10);
