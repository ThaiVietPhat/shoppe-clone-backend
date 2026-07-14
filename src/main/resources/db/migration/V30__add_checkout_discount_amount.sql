-- V30__add_checkout_discount_amount.sql

ALTER TABLE checkout_sessions ADD COLUMN discount_amount NUMERIC(15,2) NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN discount_amount NUMERIC(15,2) NOT NULL DEFAULT 0;
