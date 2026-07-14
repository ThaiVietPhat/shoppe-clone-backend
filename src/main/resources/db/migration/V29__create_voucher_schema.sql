-- V29__create_voucher_schema.sql
-- V1/V10 created placeholder `vouchers`/`voucher_usages` tables for a feature that was never
-- implemented in application code (no entity, repository, service, or controller ever referenced
-- them). Both tables are confirmed empty. Reshape them in place with ALTER statements instead of
-- dropping, so this stays safe even if either table unexpectedly has rows in some environment.

-- vouchers: platform-wide only (shop_id left in place, unused); rename to the new column names,
-- add the columns the richer voucher model needs.
ALTER TABLE vouchers RENAME COLUMN discount_amount TO discount_value;
ALTER TABLE vouchers RENAME COLUMN min_order_value TO min_order_amount;
ALTER TABLE vouchers ALTER COLUMN min_order_amount SET DEFAULT 0;
ALTER TABLE vouchers ALTER COLUMN min_order_amount SET NOT NULL;
ALTER TABLE vouchers ALTER COLUMN status SET DEFAULT 'ACTIVE';
ALTER TABLE vouchers ADD COLUMN discount_type VARCHAR(20) NOT NULL DEFAULT 'FIXED_AMOUNT';
ALTER TABLE vouchers ADD COLUMN max_discount_amount NUMERIC(15,2);
ALTER TABLE vouchers ADD COLUMN usage_limit INTEGER;
ALTER TABLE vouchers ADD COLUMN used_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE vouchers ADD COLUMN starts_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE vouchers ADD COLUMN expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '100 years');

CREATE INDEX idx_vouchers_status ON vouchers(status);

-- voucher_usages (V10 shape): order-keyed usage record with no discount/status tracking.
-- Reshape to a checkout-session-keyed reservation ledger. order_id/created rows (if any) are
-- backfilled from orders.checkout_session_id; the old order_id FK is relaxed rather than dropped.
ALTER TABLE voucher_usages RENAME COLUMN user_id TO buyer_id;
ALTER TABLE voucher_usages ALTER COLUMN order_id DROP NOT NULL;
ALTER TABLE voucher_usages ADD COLUMN checkout_session_id UUID;
UPDATE voucher_usages vu SET checkout_session_id = o.checkout_session_id
    FROM orders o WHERE o.id = vu.order_id AND vu.checkout_session_id IS NULL;
ALTER TABLE voucher_usages ALTER COLUMN checkout_session_id SET NOT NULL;
ALTER TABLE voucher_usages ADD CONSTRAINT uq_voucher_usages_checkout_session_id UNIQUE (checkout_session_id);
ALTER TABLE voucher_usages ADD COLUMN discount_amount NUMERIC(15,2) NOT NULL DEFAULT 0;
ALTER TABLE voucher_usages ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED';
ALTER TABLE voucher_usages ADD COLUMN version INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_voucher_usages_voucher_id ON voucher_usages(voucher_id);
