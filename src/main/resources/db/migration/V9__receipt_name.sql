-- V9__receipt_name.sql
-- Имя плательщика прямо в чеке. Нужно для /money: человек мог оплатить,
-- но не проголосовать — тогда в lunch_poll_vote его нет, и показывался голый id.

ALTER TABLE lunch_receipt ADD COLUMN display_name TEXT;
