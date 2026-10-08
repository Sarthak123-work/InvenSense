/*
# Create InvenSense inventory management schema

1. Overview
- Creates the durable Supabase data model for the InvenSense frontend.
- The current app is a shared demo workspace without a sign-in screen, so rows are intentionally shared across the anon and authenticated roles.

2. New tables and important columns
- `warehouses`: warehouse identity, city, and storage capacity.
- `products`: SKU catalog, category, price, unit, and reorder threshold.
- `inventory_snapshots`: current on-hand and reserved quantities for each warehouse/SKU pair.
- `stock_events`: immutable event history used to audit inventory changes and rebuild snapshots.
- `orders`: customer orders, fulfillment warehouse, status, totals, and status history.
- `order_items`: products and quantities belonging to an order.
- `transfers`: inter-warehouse transfer requests and lifecycle status.
- `suppliers`: supplier contacts, lead times, and ratings.
- `supplier_products`: products supplied by each supplier and their unit prices.
- `purchase_orders`: procurement headers, destinations, delivery dates, and status.
- `purchase_order_items`: products, ordered quantities, prices, and received quantities.
- `notifications`: low-stock, transfer, purchase-order, and reconciliation alerts.
- `forecasts`: generated demand history and forecast payloads per warehouse/SKU.
- `reorder_suggestions`: forecast-driven replenishment recommendations.

3. Integrity and performance
- Adds primary keys, foreign keys, uniqueness constraints, check constraints, and indexes for common dashboard queries.
- Inventory event quantities support signed adjustments while operational quantities remain non-negative.
- Stock events are append-only by convention; the event table does not expose update or delete behavior in the application contract.

4. Security
- Enables row level security on every new table.
- Adds four explicit policies per table for SELECT, INSERT, UPDATE, and DELETE.
- Policies allow `anon` and `authenticated` because the current frontend is a shared demo without user authentication.

5. Important notes
- This migration is additive and safe to re-run.
- The frontend can continue using its local mock adapter; these tables provide the durable backend foundation for the real API integration.
*/

CREATE TABLE IF NOT EXISTS warehouses (
  id text PRIMARY KEY,
  name text NOT NULL UNIQUE,
  city text NOT NULL,
  capacity integer NOT NULL DEFAULT 0 CHECK (capacity >= 0),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS products (
  sku text PRIMARY KEY,
  name text NOT NULL,
  category text NOT NULL,
  unit_price numeric(12,2) NOT NULL DEFAULT 0 CHECK (unit_price >= 0),
  reorder_point integer NOT NULL DEFAULT 0 CHECK (reorder_point >= 0),
  unit text NOT NULL DEFAULT 'pcs',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS inventory_snapshots (
  warehouse_id text NOT NULL REFERENCES warehouses(id) ON UPDATE CASCADE,
  sku text NOT NULL REFERENCES products(sku) ON UPDATE CASCADE,
  on_hand integer NOT NULL DEFAULT 0 CHECK (on_hand >= 0),
  reserved integer NOT NULL DEFAULT 0 CHECK (reserved >= 0),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (warehouse_id, sku),
  CHECK (reserved <= on_hand)
);

CREATE TABLE IF NOT EXISTS stock_events (
  event_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  warehouse_id text NOT NULL REFERENCES warehouses(id) ON UPDATE CASCADE,
  sku text NOT NULL REFERENCES products(sku) ON UPDATE CASCADE,
  event_type text NOT NULL CHECK (event_type IN ('STOCK_RECEIVED','STOCK_RESERVED','RESERVATION_RELEASED','STOCK_SHIPPED','TRANSFER_OUT','TRANSFER_IN','STOCK_ADJUSTED')),
  quantity integer NOT NULL,
  resulting_on_hand integer NOT NULL CHECK (resulting_on_hand >= 0),
  resulting_reserved integer NOT NULL DEFAULT 0 CHECK (resulting_reserved >= 0),
  version bigint NOT NULL CHECK (version >= 0),
  reference_id text,
  actor text,
  note text,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS orders (
  id text PRIMARY KEY,
  customer_name text NOT NULL,
  customer_email text NOT NULL,
  address text NOT NULL,
  warehouse_id text REFERENCES warehouses(id) ON UPDATE CASCADE,
  status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','CONFIRMED','SHIPPED','DELIVERED','REJECTED','CANCELLED')),
  total numeric(12,2) NOT NULL DEFAULT 0 CHECK (total >= 0),
  rejection_reason text,
  status_history jsonb NOT NULL DEFAULT '[]'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS order_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  order_id text NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  sku text NOT NULL REFERENCES products(sku) ON UPDATE CASCADE,
  quantity integer NOT NULL CHECK (quantity > 0),
  unit_price numeric(12,2) NOT NULL CHECK (unit_price >= 0),
  UNIQUE (order_id, sku)
);

CREATE TABLE IF NOT EXISTS transfers (
  id text PRIMARY KEY,
  from_warehouse_id text NOT NULL REFERENCES warehouses(id) ON UPDATE CASCADE,
  to_warehouse_id text NOT NULL REFERENCES warehouses(id) ON UPDATE CASCADE,
  sku text NOT NULL REFERENCES products(sku) ON UPDATE CASCADE,
  quantity integer NOT NULL CHECK (quantity > 0),
  received_quantity integer CHECK (received_quantity IS NULL OR received_quantity >= 0),
  status text NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED','APPROVED','IN_TRANSIT','RECEIVED','REJECTED','CANCELLED')),
  requested_by text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (from_warehouse_id <> to_warehouse_id),
  CHECK (received_quantity IS NULL OR received_quantity <= quantity)
);

CREATE TABLE IF NOT EXISTS suppliers (
  id text PRIMARY KEY,
  name text NOT NULL UNIQUE,
  contact_person text,
  email text,
  phone text,
  lead_time_days integer NOT NULL DEFAULT 0 CHECK (lead_time_days >= 0),
  rating numeric(2,1) CHECK (rating >= 0 AND rating <= 5),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS supplier_products (
  supplier_id text NOT NULL REFERENCES suppliers(id) ON DELETE CASCADE,
  sku text NOT NULL REFERENCES products(sku) ON UPDATE CASCADE,
  unit_price numeric(12,2) NOT NULL CHECK (unit_price >= 0),
  PRIMARY KEY (supplier_id, sku)
);

CREATE TABLE IF NOT EXISTS purchase_orders (
  id text PRIMARY KEY,
  supplier_id text NOT NULL REFERENCES suppliers(id),
  warehouse_id text NOT NULL REFERENCES warehouses(id) ON UPDATE CASCADE,
  status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SENT','PARTIALLY_RECEIVED','RECEIVED','CANCELLED')),
  expected_date date,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS purchase_order_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  purchase_order_id text NOT NULL REFERENCES purchase_orders(id) ON DELETE CASCADE,
  sku text NOT NULL REFERENCES products(sku) ON UPDATE CASCADE,
  quantity integer NOT NULL CHECK (quantity > 0),
  unit_price numeric(12,2) NOT NULL CHECK (unit_price >= 0),
  received_quantity integer NOT NULL DEFAULT 0 CHECK (received_quantity >= 0 AND received_quantity <= quantity),
  UNIQUE (purchase_order_id, sku)
);

CREATE TABLE IF NOT EXISTS notifications (
  id text PRIMARY KEY,
  type text NOT NULL CHECK (type IN ('LOW_STOCK','TRANSFER_UPDATE','PO_OVERDUE','RECONCILIATION_MISMATCH')),
  message text NOT NULL,
  read boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS forecasts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  warehouse_id text NOT NULL REFERENCES warehouses(id) ON UPDATE CASCADE,
  sku text NOT NULL REFERENCES products(sku) ON UPDATE CASCADE,
  model text NOT NULL DEFAULT 'Prophet',
  mape numeric(5,2),
  history jsonb NOT NULL DEFAULT '[]'::jsonb,
  forecast jsonb NOT NULL DEFAULT '[]'::jsonb,
  generated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (warehouse_id, sku)
);

CREATE TABLE IF NOT EXISTS reorder_suggestions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  warehouse_id text NOT NULL REFERENCES warehouses(id) ON UPDATE CASCADE,
  sku text NOT NULL REFERENCES products(sku) ON UPDATE CASCADE,
  available integer NOT NULL DEFAULT 0 CHECK (available >= 0),
  on_order integer NOT NULL DEFAULT 0 CHECK (on_order >= 0),
  avg_daily_demand numeric(10,2) NOT NULL DEFAULT 0 CHECK (avg_daily_demand >= 0),
  lead_time_days integer NOT NULL DEFAULT 0 CHECK (lead_time_days >= 0),
  safety_stock integer NOT NULL DEFAULT 0 CHECK (safety_stock >= 0),
  reorder_point integer NOT NULL DEFAULT 0 CHECK (reorder_point >= 0),
  suggested_quantity integer NOT NULL DEFAULT 0 CHECK (suggested_quantity >= 0),
  days_until_stockout numeric(10,2),
  supplier_id text REFERENCES suppliers(id),
  urgency text NOT NULL CHECK (urgency IN ('CRITICAL','HIGH','NORMAL')),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (warehouse_id, sku)
);

CREATE INDEX IF NOT EXISTS idx_stock_events_item_time ON stock_events (warehouse_id, sku, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_inventory_snapshots_sku ON inventory_snapshots (sku);
CREATE INDEX IF NOT EXISTS idx_orders_status_created ON orders (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_transfers_status_created ON transfers (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_purchase_orders_status_created ON purchase_orders (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notifications_unread_created ON notifications (read, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_reorder_suggestions_urgency ON reorder_suggestions (urgency, updated_at DESC);

ALTER TABLE warehouses ENABLE ROW LEVEL SECURITY;
ALTER TABLE products ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE transfers ENABLE ROW LEVEL SECURITY;
ALTER TABLE suppliers ENABLE ROW LEVEL SECURITY;
ALTER TABLE supplier_products ENABLE ROW LEVEL SECURITY;
ALTER TABLE purchase_orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE purchase_order_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE forecasts ENABLE ROW LEVEL SECURITY;
ALTER TABLE reorder_suggestions ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Shared read warehouses" ON warehouses;
CREATE POLICY "Shared read warehouses" ON warehouses FOR SELECT TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "Shared insert warehouses" ON warehouses;
CREATE POLICY "Shared insert warehouses" ON warehouses FOR INSERT TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "Shared update warehouses" ON warehouses;
CREATE POLICY "Shared update warehouses" ON warehouses FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "Shared delete warehouses" ON warehouses;
CREATE POLICY "Shared delete warehouses" ON warehouses FOR DELETE TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "Shared read products" ON products;
CREATE POLICY "Shared read products" ON products FOR SELECT TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "Shared insert products" ON products;
CREATE POLICY "Shared insert products" ON products FOR INSERT TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "Shared update products" ON products;
CREATE POLICY "Shared update products" ON products FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "Shared delete products" ON products;
CREATE POLICY "Shared delete products" ON products FOR DELETE TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "Shared read inventory snapshots" ON inventory_snapshots;
CREATE POLICY "Shared read inventory snapshots" ON inventory_snapshots FOR SELECT TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "Shared insert inventory snapshots" ON inventory_snapshots;
CREATE POLICY "Shared insert inventory snapshots" ON inventory_snapshots FOR INSERT TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "Shared update inventory snapshots" ON inventory_snapshots;
CREATE POLICY "Shared update inventory snapshots" ON inventory_snapshots FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "Shared delete inventory snapshots" ON inventory_snapshots;
CREATE POLICY "Shared delete inventory snapshots" ON inventory_snapshots FOR DELETE TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "Shared read stock events" ON stock_events;
CREATE POLICY "Shared read stock events" ON stock_events FOR SELECT TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "Shared insert stock events" ON stock_events;
CREATE POLICY "Shared insert stock events" ON stock_events FOR INSERT TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "Shared update stock events" ON stock_events;
CREATE POLICY "Shared update stock events" ON stock_events FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "Shared delete stock events" ON stock_events;
CREATE POLICY "Shared delete stock events" ON stock_events FOR DELETE TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "Shared read orders" ON orders;
CREATE POLICY "Shared read orders" ON orders FOR SELECT TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "Shared insert orders" ON orders;
CREATE POLICY "Shared insert orders" ON orders FOR INSERT TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "Shared update orders" ON orders;
CREATE POLICY "Shared update orders" ON orders FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "Shared delete orders" ON orders;
CREATE POLICY "Shared delete orders" ON orders FOR DELETE TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "Shared read order items" ON order_items;
CREATE POLICY "Shared read order items" ON order_items FOR SELECT TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "Shared insert order items" ON order_items;
CREATE POLICY "Shared insert order items" ON order_items FOR INSERT TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "Shared update order items" ON order_items;
CREATE POLICY "Shared update order items" ON order_items FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "Shared delete order items" ON order_items;
CREATE POLICY "Shared delete order items" ON order_items FOR DELETE TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "Shared read transfers" ON transfers;
CREATE POLICY "Shared read transfers" ON transfers FOR SELECT TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "Shared insert transfers" ON transfers;
CREATE POLICY "Shared insert transfers" ON transfers FOR INSERT TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "Shared update transfers" ON transfers;
CREATE POLICY "Shared update transfers" ON transfers FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "Shared delete transfers" ON transfers;
CREATE POLICY "Shared delete transfers" ON transfers FOR DELETE TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "Shared read suppliers" ON suppliers;
CREATE POLICY "Shared read suppliers" ON suppliers FOR SELECT TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "Shared insert suppliers" ON suppliers;
CREATE POLICY "Shared insert suppliers" ON suppliers FOR INSERT TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "Shared update suppliers" ON suppliers;
CREATE POLICY "Shared update suppliers" ON suppliers FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "Shared delete suppliers" ON suppliers;
CREATE POLICY "Shared delete suppliers" ON suppliers FOR DELETE TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "Shared read supplier products" ON supplier_products;
CREATE POLICY "Shared read supplier products" ON supplier_products FOR SELECT TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "Shared insert supplier products" ON supplier_products;
CREATE POLICY "Shared insert supplier products" ON supplier_products FOR INSERT TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "Shared update supplier products" ON supplier_products;
CREATE POLICY "Shared update supplier products" ON supplier_products FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "Shared delete supplier products" ON supplier_products;
CREATE POLICY "Shared delete supplier products" ON supplier_products FOR DELETE TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "Shared read purchase orders" ON purchase_orders;
CREATE POLICY "Shared read purchase orders" ON purchase_orders FOR SELECT TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "Shared insert purchase orders" ON purchase_orders;
CREATE POLICY "Shared insert purchase orders" ON purchase_orders FOR INSERT TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "Shared update purchase orders" ON purchase_orders;
CREATE POLICY "Shared update purchase orders" ON purchase_orders FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "Shared delete purchase orders" ON purchase_orders;
CREATE POLICY "Shared delete purchase orders" ON purchase_orders FOR DELETE TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "Shared read purchase order items" ON purchase_order_items;
CREATE POLICY "Shared read purchase order items" ON purchase_order_items FOR SELECT TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "Shared insert purchase order items" ON purchase_order_items;
CREATE POLICY "Shared insert purchase order items" ON purchase_order_items FOR INSERT TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "Shared update purchase order items" ON purchase_order_items;
CREATE POLICY "Shared update purchase order items" ON purchase_order_items FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "Shared delete purchase order items" ON purchase_order_items;
CREATE POLICY "Shared delete purchase order items" ON purchase_order_items FOR DELETE TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "Shared read notifications" ON notifications;
CREATE POLICY "Shared read notifications" ON notifications FOR SELECT TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "Shared insert notifications" ON notifications;
CREATE POLICY "Shared insert notifications" ON notifications FOR INSERT TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "Shared update notifications" ON notifications;
CREATE POLICY "Shared update notifications" ON notifications FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "Shared delete notifications" ON notifications;
CREATE POLICY "Shared delete notifications" ON notifications FOR DELETE TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "Shared read forecasts" ON forecasts;
CREATE POLICY "Shared read forecasts" ON forecasts FOR SELECT TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "Shared insert forecasts" ON forecasts;
CREATE POLICY "Shared insert forecasts" ON forecasts FOR INSERT TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "Shared update forecasts" ON forecasts;
CREATE POLICY "Shared update forecasts" ON forecasts FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "Shared delete forecasts" ON forecasts;
CREATE POLICY "Shared delete forecasts" ON forecasts FOR DELETE TO anon, authenticated USING (true);

DROP POLICY IF EXISTS "Shared read reorder suggestions" ON reorder_suggestions;
CREATE POLICY "Shared read reorder suggestions" ON reorder_suggestions FOR SELECT TO anon, authenticated USING (true);
DROP POLICY IF EXISTS "Shared insert reorder suggestions" ON reorder_suggestions;
CREATE POLICY "Shared insert reorder suggestions" ON reorder_suggestions FOR INSERT TO anon, authenticated WITH CHECK (true);
DROP POLICY IF EXISTS "Shared update reorder suggestions" ON reorder_suggestions;
CREATE POLICY "Shared update reorder suggestions" ON reorder_suggestions FOR UPDATE TO anon, authenticated USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS "Shared delete reorder suggestions" ON reorder_suggestions;
CREATE POLICY "Shared delete reorder suggestions" ON reorder_suggestions FOR DELETE TO anon, authenticated USING (true);
