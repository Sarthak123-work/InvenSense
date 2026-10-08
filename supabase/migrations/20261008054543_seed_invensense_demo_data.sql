/*
# Seed InvenSense demo data

1. Purpose
- Adds the initial shared workspace records used by the inventory dashboard.
- This is additive and safe to re-run because every insert ignores an existing matching key.

2. Seeded data
- Three warehouses: Mumbai, Pune, and Delhi.
- Twelve products across Electronics, Grocery, Apparel, and Home.
- Four suppliers with lead times and ratings.
- Supplier catalog links for the seeded products.
- Inventory snapshots for every product in every warehouse, including low-stock and out-of-stock examples.
- Initial low-stock and reconciliation notifications.

3. Security
- Uses the RLS policies created by the core schema migration.
- No new access grants or policy changes are made.
*/

INSERT INTO warehouses (id, name, city, capacity) VALUES
  ('wh-mum', 'Mumbai', 'Mumbai', 10000),
  ('wh-pun', 'Pune', 'Pune', 8000),
  ('wh-del', 'Delhi', 'Delhi', 12000)
ON CONFLICT (id) DO NOTHING;

INSERT INTO products (sku, name, category, unit_price, reorder_point, unit) VALUES
  ('SKU-1001', 'Wireless Mouse', 'Electronics', 799, 40, 'pcs'),
  ('SKU-1002', 'Mechanical Keyboard', 'Electronics', 2899, 24, 'pcs'),
  ('SKU-1003', 'USB-C Hub 7-in-1', 'Electronics', 1999, 18, 'pcs'),
  ('SKU-1004', 'Organic Green Tea', 'Grocery', 349, 30, 'pcs'),
  ('SKU-1005', 'Arabica Coffee Beans', 'Grocery', 899, 24, 'pcs'),
  ('SKU-1006', 'Cotton Crew T-Shirt', 'Apparel', 699, 36, 'pcs'),
  ('SKU-1007', 'Running Shoes', 'Apparel', 3299, 22, 'pairs'),
  ('SKU-1008', 'Linen Cushion Cover', 'Home', 549, 25, 'pcs'),
  ('SKU-1009', 'Bamboo Storage Box', 'Home', 1199, 18, 'pcs'),
  ('SKU-1010', 'LED Desk Lamp', 'Home', 1599, 20, 'pcs'),
  ('SKU-1011', 'Bluetooth Speaker', 'Electronics', 2499, 28, 'pcs'),
  ('SKU-1012', 'Smart Water Bottle', 'Home', 1299, 20, 'pcs')
ON CONFLICT (sku) DO NOTHING;

INSERT INTO suppliers (id, name, contact_person, email, phone, lead_time_days, rating) VALUES
  ('sup-1', 'TechSource Pvt Ltd', 'Anil Deshmukh', 'anil@techsource.example', '+91 22 4000 1100', 7, 4.8),
  ('sup-2', 'GreenMart Organics', 'Meera Joshi', 'meera@greenmart.example', '+91 20 4100 2200', 5, 4.6),
  ('sup-3', 'Horizon Fabrics', 'Rohan Kapoor', 'rohan@horizon.example', '+91 11 4200 3300', 14, 4.3),
  ('sup-4', 'HomeStyle Imports', 'Tara Menon', 'tara@homestyle.example', '+91 80 4300 4400', 10, 4.5)
ON CONFLICT (id) DO NOTHING;

INSERT INTO supplier_products (supplier_id, sku, unit_price) VALUES
  ('sup-1', 'SKU-1001', 520), ('sup-1', 'SKU-1002', 2040), ('sup-1', 'SKU-1003', 1350), ('sup-1', 'SKU-1011', 1720),
  ('sup-2', 'SKU-1004', 210), ('sup-2', 'SKU-1005', 580),
  ('sup-3', 'SKU-1006', 390), ('sup-3', 'SKU-1007', 2200),
  ('sup-4', 'SKU-1008', 290), ('sup-4', 'SKU-1009', 670), ('sup-4', 'SKU-1010', 920), ('sup-4', 'SKU-1012', 740)
ON CONFLICT (supplier_id, sku) DO NOTHING;

INSERT INTO inventory_snapshots (warehouse_id, sku, on_hand, reserved, version)
SELECT w.id, p.sku,
  CASE
    WHEN p.sku = 'SKU-1012' AND w.id = 'wh-mum' THEN 0
    WHEN p.sku = 'SKU-1011' AND w.id = 'wh-del' THEN 12
    WHEN p.sku = 'SKU-1005' AND w.id = 'wh-pun' THEN 18
    WHEN p.sku = 'SKU-1007' AND w.id = 'wh-mum' THEN 19
    ELSE 55 + ((ascii(right(p.sku, 1)) + length(w.id) * 11) % 125)
  END,
  CASE
    WHEN p.sku IN ('SKU-1012', 'SKU-1011') THEN 0
    ELSE ((ascii(right(p.sku, 1)) + length(w.id)) % 12)
  END,
  1
FROM warehouses w CROSS JOIN products p
ON CONFLICT (warehouse_id, sku) DO NOTHING;

INSERT INTO notifications (id, type, message, read) VALUES
  ('n-1', 'LOW_STOCK', 'SKU-1001 is low in Pune (12 left)', false),
  ('n-2', 'LOW_STOCK', 'Smart Water Bottle is out of stock in Mumbai', false),
  ('n-3', 'PO_OVERDUE', 'PO-2026-0079 is 3 days overdue', false),
  ('n-4', 'RECONCILIATION_MISMATCH', 'Arabica Coffee Beans snapshot needs rebuilding', false),
  ('n-5', 'TRANSFER_UPDATE', 'TRF-0024 is now in transit to Pune', true),
  ('n-6', 'LOW_STOCK', 'Bluetooth Speaker is low in Delhi (18 left)', true),
  ('n-7', 'TRANSFER_UPDATE', 'TRF-0021 was received in Delhi', true),
  ('n-8', 'PO_OVERDUE', 'PO-2026-0076 is due tomorrow', true)
ON CONFLICT (id) DO NOTHING;
