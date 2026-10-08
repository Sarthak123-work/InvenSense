package com.invensense.inventory.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.invensense.common.exception.BusinessConflictException;
import com.invensense.common.exception.InsufficientStockException;
import com.invensense.common.exception.LockBusyException;
import com.invensense.inventory.config.InventoryProperties;
import com.invensense.inventory.document.StockEvent;
import com.invensense.inventory.document.StockLevel;
import com.invensense.inventory.dto.AdjustRequest;
import com.invensense.inventory.dto.AsOfDto;
import com.invensense.inventory.dto.ReceiveRequest;
import com.invensense.inventory.dto.ReleaseRequest;
import com.invensense.inventory.dto.ReserveRequest;
import com.invensense.inventory.dto.ShipRequest;
import com.invensense.inventory.dto.StockEventDto;
import com.invensense.inventory.dto.StockLevelDto;
import com.invensense.inventory.repository.StockEventRepository;
import com.invensense.inventory.repository.StockLevelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final StockEventRepository eventRepo;
    private final StockLevelRepository levelRepo;
    private final MongoTemplate mongoTemplate;
    private final InventoryProperties props;
    private final RedissonClient redissonClient;

    // ---- Read queries (unchanged) ----

    public List<StockLevelDto> getStockLevels(String warehouseId, String sku) {
        List<StockLevel> levels;
        if (warehouseId != null && sku != null) {
            levels = levelRepo.findByWarehouseIdAndSku(warehouseId, sku)
                    .stream().toList();
        } else if (warehouseId != null) {
            levels = levelRepo.findAll().stream()
                    .filter(l -> l.getWarehouseId().equals(warehouseId))
                    .toList();
        } else if (sku != null) {
            levels = levelRepo.findAll().stream()
                    .filter(l -> l.getSku().equals(sku))
                    .toList();
        } else {
            levels = levelRepo.findAll();
        }
        return levels.stream()
                .map(this::toLevelDto)
                .collect(Collectors.toList());
    }

    public List<StockEventDto> getHistory(String warehouseId, String sku, String type) {
        List<StockEvent> events = eventRepo.findByWarehouseIdAndSkuOrderByTimestampDesc(warehouseId, sku);
        if (type != null && !type.isBlank()) {
            events = events.stream()
                    .filter(e -> e.getType().equals(type))
                    .toList();
        }
        return events.stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public AsOfDto getAsOf(String warehouseId, String sku, Instant asOf) {
        List<StockEvent> events = eventRepo
                .findByWarehouseIdAndSkuAndTimestampBeforeOrderByTimestampAsc(warehouseId, sku, asOf);

        int onHand = 0;
        int reserved = 0;
        for (StockEvent e : events) {
            switch (e.getType()) {
                case "STOCK_RECEIVED", "TRANSFER_IN", "STOCK_ADJUSTED" -> onHand += e.getQuantity();
                case "TRANSFER_OUT", "STOCK_SHIPPED" -> onHand -= e.getQuantity();
                case "STOCK_RESERVED" -> reserved += e.getQuantity();
                case "RESERVATION_RELEASED" -> reserved -= e.getQuantity();
            }
        }
        return AsOfDto.builder()
                .onHand(onHand)
                .reserved(reserved)
                .available(onHand - reserved)
                .eventCount(events.size())
                .build();
    }

    // ---- Write operations (phase 4: lock + version-conditional + idempotency) ----

    public StockLevelDto receive(ReceiveRequest req) {
        StockEvent event = appendEvent(
                req.getWarehouseId(), req.getSku(), "STOCK_RECEIVED",
                req.getQuantity(), req.getReferenceId(), null, null);
        return toLevelDto(event);
    }

    public StockLevelDto adjust(AdjustRequest req) {
        StockEvent event = appendEvent(
                req.getWarehouseId(), req.getSku(), "STOCK_ADJUSTED",
                req.getDelta(), "ADJ-" + UUID.randomUUID().toString().substring(0, 8),
                req.getReason(), req.getNote());
        return toLevelDto(event);
    }

    public StockLevelDto reserve(ReserveRequest req) {
        StockEvent event = appendEvent(
                req.getWarehouseId(), req.getSku(), "STOCK_RESERVED",
                req.getQuantity(), req.getReferenceId(), null, null);
        return toLevelDto(event);
    }

    public StockLevelDto release(ReleaseRequest req) {
        StockEvent event = appendEvent(
                req.getWarehouseId(), req.getSku(), "RESERVATION_RELEASED",
                0, req.getReferenceId(), req.getReason(), null);
        return toLevelDto(event);
    }

    public StockLevelDto ship(ShipRequest req) {
        StockEvent event = appendEvent(
                req.getWarehouseId(), req.getSku(), "STOCK_SHIPPED",
                req.getQuantity(), req.getReferenceId(), null, null);
        return toLevelDto(event);
    }

    // ---- Core appendEvent with lock + version-conditional + retry ----

    /**
     * Phase 4 appendEvent — section 9.3.
     *
     * Flow:
     *   1. Idempotency check: if (warehouseId, type, referenceId) already exists, return the existing event.
     *   2. Acquire Redisson multi-lock on "warehouseId:sku".
     *   3. Read snapshot inside the lock.
     *   4. Compute new onHand/reserved.
     *   5. Validate invariants.
     *   6. Version-conditional findAndModify: only updates if the version matches.
     *   7. If version mismatch → retry from step 3 (up to maxRetries).
     *   8. Append the event.
     *
     * Unsafe mode (section 9.4): skips the lock and the version check,
     * falling back to the phase-3 read-compute-save path.
     */
    public StockEvent appendEvent(String warehouseId, String sku, String type,
                                   int quantity, String referenceId,
                                   String reason, String note) {

        // Step 1: Idempotency check
        if (referenceId != null && !referenceId.isBlank()) {
            var existing = eventRepo.findByWarehouseIdAndTypeAndReferenceId(
                    warehouseId, type, referenceId);
            if (existing.isPresent()) {
                log.info("Idempotent replay: {} / {} / {} already exists, returning cached event",
                        warehouseId, type, referenceId);
                return existing.get();
            }
        }

        if (props.isUnsafeMode()) {
            return appendEventUnsafe(warehouseId, sku, type, quantity, referenceId, reason, note);
        }

        // Step 2: Acquire Redisson lock
        String lockKey = "inventory:lock:" + warehouseId + ":" + sku;
        RLock lock = redissonClient.getLock(lockKey);

        boolean locked;
        try {
            locked = lock.tryLock(props.getLockWaitTimeMs(), props.getLockLeaseTimeMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockBusyException("Interrupted while waiting for lock on " + lockKey);
        }

        if (!locked) {
            throw new LockBusyException("Could not acquire lock on " + lockKey
                    + " within " + props.getLockWaitTimeMs() + "ms");
        }

        try {
            return appendEventWithRetry(warehouseId, sku, type, quantity, referenceId, reason, note);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * Version-conditional append with retry loop.
     * Called inside the Redisson lock.
     */
    private StockEvent appendEventWithRetry(String warehouseId, String sku, String type,
                                             int quantity, String referenceId,
                                             String reason, String note) {

        String levelId = warehouseId + ":" + sku;
        int maxRetries = props.getMaxRetries();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {

            // Step 3: Read snapshot
            StockLevel level = mongoTemplate.findById(levelId, StockLevel.class);
            if (level == null) {
                level = StockLevel.builder()
                        .id(levelId)
                        .warehouseId(warehouseId)
                        .sku(sku)
                        .onHand(0)
                        .reserved(0)
                        .version(0)
                        .updatedAt(Instant.now())
                        .build();
                try {
                    mongoTemplate.insert(level);
                } catch (org.springframework.dao.DuplicateKeyException e) {
                    // Another thread created it first — re-read and retry
                    level = mongoTemplate.findById(levelId, StockLevel.class);
                    if (level == null) continue;
                }
            }

            int expectedVersion = level.getVersion();

            // Step 4: Compute new values
            int newOnHand = level.getOnHand();
            int newReserved = level.getReserved();

            switch (type) {
                case "STOCK_RECEIVED", "TRANSFER_IN", "STOCK_ADJUSTED" -> newOnHand += quantity;
                case "TRANSFER_OUT", "STOCK_SHIPPED" -> newOnHand -= quantity;
                case "STOCK_RESERVED" -> newReserved += quantity;
                case "RESERVATION_RELEASED" -> newReserved -= quantity;
            }

            // Step 5: Validate invariants
            if (newOnHand < 0 || newReserved < 0 || newReserved > newOnHand) {
                int available = level.getOnHand() - level.getReserved();
                throw new InsufficientStockException(sku, available);
            }

            int newVersion = expectedVersion + 1;

            // Step 6: Version-conditional findAndModify
            Query query = Query.query(
                    Criteria.where("_id").is(levelId)
                            .and("version").is(expectedVersion));

            Update update = new Update()
                    .set("onHand", newOnHand)
                    .set("reserved", newReserved)
                    .set("version", newVersion)
                    .set("updatedAt", Instant.now());

            StockLevel updated = mongoTemplate.findAndModify(
                    query, update,
                    FindAndModifyOptions.options().returnNew(true),
                    StockLevel.class);

            if (updated != null) {
                // Step 8: Append the event
                StockEvent event = StockEvent.builder()
                        .eventId("evt-" + UUID.randomUUID())
                        .warehouseId(warehouseId)
                        .sku(sku)
                        .type(type)
                        .quantity(quantity)
                        .resultingOnHand(newOnHand)
                        .resultingReserved(newReserved)
                        .version(newVersion)
                        .referenceId(referenceId)
                        .reason(reason)
                        .note(note)
                        .actor("system")
                        .timestamp(Instant.now())
                        .build();
                eventRepo.save(event);

                log.debug("Appended event {} for {}:{} onHand={} reserved={} version={} (attempt {})",
                        type, warehouseId, sku, newOnHand, newReserved, newVersion, attempt);
                return event;
            }

            // Version mismatch — another thread updated the snapshot
            log.warn("Version conflict on {}:{} expected={} attempt={}/{} — retrying",
                    warehouseId, sku, expectedVersion, attempt + 1, maxRetries);

            if (attempt == maxRetries) {
                throw new BusinessConflictException(
                        "Failed to update stock for " + warehouseId + ":" + sku
                                + " after " + maxRetries + " retries due to version conflicts");
            }
        }

        // Should never reach here
        throw new BusinessConflictException("Unexpected state in appendEvent for " + warehouseId + ":" + sku);
    }

    /**
     * Unsafe mode — section 9.4. No lock, no version check.
     * Direct read-compute-save, same as phase 3.
     */
    private StockEvent appendEventUnsafe(String warehouseId, String sku, String type,
                                          int quantity, String referenceId,
                                          String reason, String note) {

        log.warn("UNSAFE MODE: appending event {} for {}:{} without lock or version check", type, warehouseId, sku);

        String levelId = warehouseId + ":" + sku;
        StockLevel level = levelRepo.findById(levelId).orElseGet(() ->
                StockLevel.builder()
                        .id(levelId)
                        .warehouseId(warehouseId)
                        .sku(sku)
                        .onHand(0)
                        .reserved(0)
                        .version(0)
                        .updatedAt(Instant.now())
                        .build());

        int newOnHand = level.getOnHand();
        int newReserved = level.getReserved();

        switch (type) {
            case "STOCK_RECEIVED", "TRANSFER_IN", "STOCK_ADJUSTED" -> newOnHand += quantity;
            case "TRANSFER_OUT", "STOCK_SHIPPED" -> newOnHand -= quantity;
            case "STOCK_RESERVED" -> newReserved += quantity;
            case "RESERVATION_RELEASED" -> newReserved -= quantity;
        }

        if (newOnHand < 0 || newReserved < 0 || newReserved > newOnHand) {
            int available = level.getOnHand() - level.getReserved();
            throw new InsufficientStockException(sku, available);
        }

        int newVersion = level.getVersion() + 1;

        level.setOnHand(newOnHand);
        level.setReserved(newReserved);
        level.setVersion(newVersion);
        level.setUpdatedAt(Instant.now());
        levelRepo.save(level);

        StockEvent event = StockEvent.builder()
                .eventId("evt-" + UUID.randomUUID())
                .warehouseId(warehouseId)
                .sku(sku)
                .type(type)
                .quantity(quantity)
                .resultingOnHand(newOnHand)
                .resultingReserved(newReserved)
                .version(newVersion)
                .referenceId(referenceId)
                .reason(reason)
                .note(note)
                .actor("system-unsafe")
                .timestamp(Instant.now())
                .build();
        eventRepo.save(event);

        return event;
    }

    // ---- Helpers ----

    private StockLevelDto toLevelDto(StockEvent event) {
        return StockLevelDto.builder()
                .warehouseId(event.getWarehouseId())
                .sku(event.getSku())
                .onHand(event.getResultingOnHand())
                .reserved(event.getResultingReserved())
                .available(event.getResultingOnHand() - event.getResultingReserved())
                .reorderPoint(0)
                .version(event.getVersion())
                .build();
    }

    private StockLevelDto toLevelDto(StockLevel l) {
        return StockLevelDto.builder()
                .warehouseId(l.getWarehouseId())
                .sku(l.getSku())
                .onHand(l.getOnHand())
                .reserved(l.getReserved())
                .available(l.getAvailable())
                .reorderPoint(0)
                .version(l.getVersion())
                .build();
    }

    private StockEventDto toDto(StockEvent e) {
        return StockEventDto.builder()
                .eventId(e.getEventId())
                .warehouseId(e.getWarehouseId())
                .sku(e.getSku())
                .type(e.getType())
                .quantity(e.getQuantity())
                .resultingOnHand(e.getResultingOnHand())
                .resultingReserved(e.getResultingReserved())
                .version(e.getVersion())
                .referenceId(e.getReferenceId())
                .reason(e.getReason())
                .note(e.getNote())
                .actor(e.getActor())
                .timestamp(e.getTimestamp())
                .build();
    }
}
