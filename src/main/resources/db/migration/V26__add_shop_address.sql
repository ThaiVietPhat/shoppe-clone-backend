ALTER TABLE shops ADD COLUMN address_id UUID REFERENCES addresses(id);
