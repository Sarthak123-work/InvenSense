package com.invensense.inventory.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.invensense.common.exception.InsufficientStockException;
import com.invensense.inventory.document.StockEvent;
import com.invensense.inventory.document.StockLevel;
import com.invensense.inventory.dto.AdjustRequest;
import com.invensense.inventory.dto.AsOfDto;
import com.invensense.inventory.dto.ReceiveRequest;
import com.invensense.inventory.dto.StockEventDto;
import com.invensense.inventory.dto.StockLevelDto;
import com.invensense.inventory.repository.StockEventRepository;
import com.invensense.inventory.repository.StockLevelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final StockEventRepository eventRepo;
    private final StockLevelRepository levelRepo;

    // ---- Read queries ----

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
                .map(l -> StockLevelDto.builder()
                        .warehouseId(l.getWarehouseId())
                        .sku(l.getSku())
                        .onHand(l.getOnHand())
                        .reserved(l.getReserved())
                        .available(l.getAvailable())
                        .reorderPoint(0)
                        .version(l.getVersion())
                        .build())
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

    // ---- Write operations (NO locking in phase 3) ----

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

    /**
     * Phase 3 appendEvent — NO Redis lock, NO optimistic version check.
     * Reads the snapshot, computes new values, saves directly.
     * This is deliberately unsafe and the concurrency test will demonstrate overselling.
     */
    public StockEvent appendEvent(String warehouseId, String sku, String type,
                                   int quantity, String referenceId,
                                   String reason, String note) {

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

        // Invariant check
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
                .actor("system")
                .timestamp(Instant.now())
                .build();
        eventRepo.save(event);

        log.debug("Appended event {} for {}:{} onHand={} reserved={} version={}",
                type, warehouseId, sku, newOnHand, newReserved, newVersion);
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
