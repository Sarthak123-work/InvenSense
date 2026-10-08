# InvenSense — Backend Build Phase 4

## Inventory Service (Redisson Multi-Lock + Version-Conditional Update + Idempotency)

This phase implements section 9.3 (concurrency safety) and 9.4 (demo-profile unsafe mode).

### What's new in phase 4

**1. Redisson distributed multi-lock (section 9.3)**

Every write operation acquires a Redisson lock on `inventory:lock:{warehouseId}:{sku}` before touching the snapshot. Lock wait time is 3 seconds, lease time is 5 seconds (configurable via `inventory.concurrency.*`).

**2. Version-conditional snapshot update (CAS)**

Inside the lock, the service reads the current `StockLevel`, computes new values, then uses MongoDB `findAndModify` with a `version = expectedVersion` condition. If another thread already updated the snapshot (version mismatch), the service retries up to 3 times.

**3. Idempotency**

Before processing any event, the service checks if `(warehouseId, type, referenceId)` already exists in the `stock_events` collection (backed by a unique compound index). If it does, the existing event is returned without creating a duplicate.

**4. New endpoints**

- `POST /api/inventory/reserve` — reserves stock (`STOCK_RESERVED`)
- `POST /api/inventory/release` — releases a reservation (`RESERVATION_RELEASED`)
- `POST /api/inventory/ship` — ships stock (`STOCK_SHIPPED`, decreases both onHand and reserved)

**5. Demo-profile unsafe mode (section 9.4)**

When `inventory.concurrency.unsafe-mode=true`, the service skips the Redisson lock and the version-conditional update, falling back to the phase-3 read-compute-save path. This is for demo purposes only — it recreates the oversell bug so you can demonstrate the value of the locking. **Never enable in production.**

### Full endpoint list

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/inventory?warehouseId=&sku=` | List stock levels |
| GET | `/api/inventory/{wh}/{sku}/history?type=` | Event history, newest first |
| GET | `/api/inventory/{wh}/{sku}/history?asOf=ISO` | Time-travel query |
| POST | `/api/inventory/receive` | Receive stock |
| POST | `/api/inventory/adjust` | Adjust stock (delta may be negative) |
| POST | `/api/inventory/reserve` | Reserve stock for an order |
| POST | `/api/inventory/release` | Release a reservation |
| POST | `/api/inventory/ship` | Ship reserved stock |

### Concurrency test (section 15) — now PASSES

`ConcurrencyTest.java` spins up both MongoDB and Redis via Testcontainers, seeds 5 units, fires 50 concurrent reserve requests, and asserts:

- Exactly 5 successes
- 45 failures (mix of `InsufficientStockException` and `LockBusyException`)
- 5 `STOCK_RESERVED` events in the event store
- `reserved == 5`, `available == 0`, `version == 6` (1 seed + 5 reserves)

### Running the tests

Prerequisites: Docker running (Testcontainers needs it).

```bash
cd backend

# Run all tests (all should PASS now)
mvn test -pl inventory-service -am

# Run only the concurrency test
mvn test -pl inventory-service -Dtest=ConcurrencyTest

# Run only the unit tests
mvn test -pl inventory-service -Dtest=InventoryServiceTest
```

### Expected concurrency test output

```
============================================================
  CONCURRENCY TEST RESULT (Phase 4 — Redisson Lock + CAS)
============================================================
  Starting stock:       5
  Concurrent requests:  50
  Successes:            5  (expected: 5)
  InsufficientStock:    45
  LockBusy:             0
  Version conflicts:    0
  Other errors:         0
  Total failures:       45  (expected: 45)
  Reserved events:      5   (expected: 5)
  Final onHand:         5
  Final reserved:       5   (expected: 5)
  Final available:      0   (expected: 0)
  Oversold units:       0
============================================================
```

### Configuration

```yaml
inventory:
  concurrency:
    unsafe-mode: false        # section 9.4 demo escape hatch
    max-retries: 3            # version conflict retries
    lock-lease-time-ms: 5000  # Redisson lock lease
    lock-wait-time-ms: 3000   # Redisson lock wait
```
