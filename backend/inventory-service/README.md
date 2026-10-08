# InvenSense — Backend Build Phase 3

## Inventory Service (Event Store + Snapshot, NO locking)

This phase implements the core inventory-service per sections 8.2 and 9 of the brief.

### What's implemented

- **MongoDB documents**: `stock_events` (append-only) and `stock_levels` (snapshot/read model)
- **Endpoints**:
  - `GET /api/inventory?warehouseId=&sku=` — list stock levels
  - `GET /api/inventory/{warehouseId}/{sku}/history?type=` — event history, newest first
  - `GET /api/inventory/{warehouseId}/{sku}/history?asOf=ISO-8601` — time-travel query (event fold)
  - `POST /api/inventory/receive` — receive stock (`STOCK_RECEIVED`)
  - `POST /api/inventory/adjust` — adjust stock (`STOCK_ADJUSTED`, delta may be negative)
- **Event types** (section 9.1): `STOCK_RECEIVED`, `STOCK_RESERVED`, `RESERVATION_RELEASED`, `STOCK_SHIPPED`, `TRANSFER_OUT`, `TRANSFER_IN`, `STOCK_ADJUSTED`
- **Snapshot rules**: `available = onHand - reserved`, version increments per event, invariants checked (`onHand >= 0`, `0 <= reserved <= onHand`)
- **As-of query**: folds all events with `timestamp <= asOf` using the section 9.1 rules

### What's deliberately NOT implemented yet (phase 4)

- Redis distributed locking (Redisson)
- Optimistic version-conditional update (`findAndModify` with expected version)
- Idempotency check on `referenceId`
- `reserve`, `release`, `ship` endpoints (these come in phase 4 with locking)

### The concurrency test (section 15)

The test `ConcurrencyTest.java` is the **most important test** in the system. It:

1. Starts a MongoDB 7 replica set via Testcontainers
2. Seeds 5 units of stock (`STOCK_RECEIVED`)
3. Fires 50 threads simultaneously (all waiting at a `CountDownLatch`)
4. Each thread tries to reserve 1 unit (`STOCK_RESERVED`)
5. Asserts: exactly 5 successes, 45 failures, 5 events, `available == 0`

**This test WILL FAIL in phase 3.** Without locking, multiple threads read the same snapshot, all see `available >= 1`, and all succeed — overselling the stock. This is the bug that phase 4 fixes with Redisson locking + optimistic version checks.

### Running the tests

Prerequisites: Docker running (Testcontainers needs it).

```bash
# From the backend/ directory
cd backend

# Run all tests (the concurrency test should FAIL)
mvn test -pl inventory-service -am

# Run only the concurrency test
mvn test -pl inventory-service -Dtest=ConcurrencyTest

# Run only the unit tests (these should PASS)
mvn test -pl inventory-service -Dtest=InventoryServiceTest
```

### Expected test output

**Unit tests** (`InventoryServiceTest`) — all PASS:
- Receive creates event + updates snapshot
- Positive/negative adjust works
- Reserve increases reserved
- Ship decreases both onHand and reserved
- History returns newest first
- As-of query replays correctly for different timestamps
- Version increments by 1 per event
- Invariant violations throw `InsufficientStockException`

**Concurrency test** (`ConcurrencyTest`) — FAILS as expected:

```
============================================================
  CONCURRENCY TEST RESULT (Phase 3 — NO LOCKING)
============================================================
  Starting stock:       5
  Concurrent requests:  50
  Successes:            ~20-50  (expected: 5)
  Failures (409):       ~30-0   (expected: 45)
  Reserved events:      ~20-50  (expected: 5)
  Final onHand:         5
  Final reserved:       ~20-50
  Final available:      ~-45    (expected: 0)
  Oversold units:       ~15-45
============================================================
FAIL: Expected exactly 5 successes but got ~20-50 — OVERSOLD
```

The exact numbers vary per run (it's a race condition), but `successes > 5` and `available < 0` — the invariants are broken. Phase 4 adds the Redisson lock and optimistic version check so exactly 5 succeed.
