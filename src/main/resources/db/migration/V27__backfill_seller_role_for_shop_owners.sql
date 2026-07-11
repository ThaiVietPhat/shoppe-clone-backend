-- Backfill: any user who already owns a shop but was never promoted to SELLER
-- (shops created before ShopServiceImpl started calling promoteToSeller on creation).
UPDATE users
SET role = 'SELLER'
WHERE role = 'BUYER'
  AND id IN (SELECT owner_id FROM shops);
