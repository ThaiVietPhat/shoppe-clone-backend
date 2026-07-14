-- V33__create_wallet_wishlist_and_reshape_return_schema.sql
-- Escape hatch triggered (DESIGN.md §13.9): return/dispute and seller wallet/payout were scope-bank
-- document-only items, now explicitly requested — same pattern as Task 8 (V29) for vouchers.

-- 1. returns / return_evidence: V1/V10 created placeholder tables for a feature that was never
-- implemented in application code (no entity/repository/service/controller ever referenced them).
-- Both confirmed empty in every environment. Reshape in place with ALTER instead of dropping,
-- mirroring how V29 reshaped the voucher placeholder tables.
ALTER TABLE returns ADD COLUMN buyer_id UUID;
UPDATE returns r SET buyer_id = o.buyer_id FROM orders o WHERE o.id = r.order_id AND r.buyer_id IS NULL;
ALTER TABLE returns ALTER COLUMN buyer_id SET NOT NULL;
ALTER TABLE returns ADD CONSTRAINT fk_returns_buyer FOREIGN KEY (buyer_id) REFERENCES users(id);

-- Denormalized shop_id (same style as order_items snapshotting product/variant name) so the
-- seller-scoped return list can query directly without joining through orders.
ALTER TABLE returns ADD COLUMN shop_id UUID;
UPDATE returns r SET shop_id = o.shop_id FROM orders o WHERE o.id = r.order_id AND r.shop_id IS NULL;
ALTER TABLE returns ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE returns ADD CONSTRAINT fk_returns_shop FOREIGN KEY (shop_id) REFERENCES shops(id);

ALTER TABLE returns RENAME COLUMN reason TO description;
ALTER TABLE returns ALTER COLUMN description DROP NOT NULL;
ALTER TABLE returns ADD COLUMN reason_category VARCHAR(30) NOT NULL DEFAULT 'OTHER';
ALTER TABLE returns ADD COLUMN refund_amount NUMERIC(15,2);
ALTER TABLE returns ADD COLUMN resolution_note TEXT;
ALTER TABLE returns ADD COLUMN resolved_by UUID;
ALTER TABLE returns ADD COLUMN resolved_at TIMESTAMPTZ;
ALTER TABLE returns ADD COLUMN requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE returns ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE returns ADD CONSTRAINT uq_returns_order_id UNIQUE (order_id);

CREATE INDEX idx_returns_buyer_id ON returns(buyer_id);
CREATE INDEX idx_returns_status ON returns(status);
CREATE INDEX idx_returns_shop_id ON returns(shop_id);

-- return_evidence (V1 shape: return_id, image_url) — every evidence upload now goes through the
-- media module by ID (no cross-module FK, same as moderation.reports.target_id); image_url is
-- dropped since nothing writes free-form URLs anymore and the table is confirmed empty.
ALTER TABLE return_evidence DROP COLUMN image_url;
ALTER TABLE return_evidence ADD COLUMN media_id UUID NOT NULL;

-- 2. orders.delivered_at — nothing currently timestamps the DELIVERED transition; needed for the
-- return eligibility window (updated_at is not reliable since later mutations could bump it).
ALTER TABLE orders ADD COLUMN delivered_at TIMESTAMPTZ;

-- 3. wallet: brand new, nothing to reshape.
-- ON DELETE CASCADE on every user_id/seller_id FK here (same convention as V5/V8/V9/V28 user-owned
-- child tables) — without it, any unrelated IT test that ships+delivers an order and then does its
-- usual `userRepository.deleteAll()` cleanup would fail on the FK from the wallet silently created
-- by OrderDeliveredWalletListener, corrupting later tests with leftover rows.
CREATE TABLE wallets (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    balance NUMERIC(15,2) NOT NULL DEFAULT 0,
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Idempotency guard mirrors inventory_reservations/voucher_usages ledger pattern: a duplicate
-- credit/debit for the same (reference_type, reference_id, type) is caught by this constraint
-- instead of double-mutating the balance.
CREATE TABLE wallet_transactions (
    id UUID PRIMARY KEY,
    wallet_id UUID NOT NULL REFERENCES wallets(id) ON DELETE CASCADE,
    type VARCHAR(30) NOT NULL,
    amount NUMERIC(15,2) NOT NULL,
    reference_type VARCHAR(30) NOT NULL,
    reference_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_wallet_transactions_reference UNIQUE (reference_type, reference_id, type)
);
CREATE INDEX idx_wallet_transactions_wallet_id ON wallet_transactions(wallet_id);

-- Mock instant payout (no real bank transfer) — status kept for extensibility even though the
-- current flow completes it synchronously in the same request, same "mock" philosophy as COD.
CREATE TABLE payout_requests (
    id UUID PRIMARY KEY,
    wallet_id UUID NOT NULL REFERENCES wallets(id) ON DELETE CASCADE,
    seller_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    amount NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    status VARCHAR(20) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_payout_requests_seller_id ON payout_requests(seller_id);

-- 4. wishlist: brand new. Same ON DELETE CASCADE reasoning as wallets above.
CREATE TABLE wishlist_items (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_wishlist_items_user_product UNIQUE (user_id, product_id)
);
CREATE INDEX idx_wishlist_items_user_id ON wishlist_items(user_id);
