-- Ép các cột jsonb lưu thuộc tính phải là JSON object (Map), không phải array/scalar.
-- Lý do: entity ánh xạ products.attributes -> Map<String,Object> và
-- product_variants.option_labels -> Map<String,String>. Nếu DB chứa array (vd seed V21 cũ),
-- Hibernate ném MismatchedInputException khi đọc và làm 500 toàn bộ catalog.
-- CHECK này chặn dữ liệu sai ngay từ tầng ghi: insert/update sai sẽ fail sớm và rõ ràng.
-- Cho phép NULL vì cột nullable; jsonb_typeof(NULL) = NULL nên ràng buộc coi như thỏa.

ALTER TABLE products
    ADD CONSTRAINT products_attributes_is_object
    CHECK (attributes IS NULL OR jsonb_typeof(attributes) = 'object');

ALTER TABLE product_variants
    ADD CONSTRAINT product_variants_option_labels_is_object
    CHECK (option_labels IS NULL OR jsonb_typeof(option_labels) = 'object');
