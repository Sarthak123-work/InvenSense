package com.invensense.inventory;

import java.time.Instant;
import java.util.List;

import com.invensense.inventory.document.StockEvent;
import com.invensense.inventory.document.StockLevel;
import com.invensense.inventory.dto.AsOfDto;
import com.invensense.inventory.dto.ReceiveRequest;
import com.invensense.inventory.dto.AdjustRequest;
import com.invensense.inventory.dto.StockLevelDto;
import com.invensense.inventory.repository.StockEventRepository;
import com.invensense.inventory.repository.StockLevelRepository;
import com.invensense.inventory.service.InventoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@DataMongoTest
@Import(InventoryService.class)
class InventoryServiceTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        MongoTemplate mongoTemplate(MongoDBContainer container) {
            return new MongoTemplate(container.getReplicaSetUrl());
        }
    }

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(
            DockerImageName.parse("mongodb/mongodb-community-server:7.0-ubuntu2204"))
            .withCommand("--replSet", "rs0")
            .withExposedPorts(27017);

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private StockEventRepository eventRepo;

    @Autowired
    private StockLevelRepository levelRepo;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(StockEvent.class);
        mongoTemplate.dropCollection(StockLevel.class);
    }

    @AfterEach
    void tearDown() {
        mongoTemplate.dropCollection(StockEvent.class);
        mongoTemplate.dropCollection(StockLevel.class);
    }

    @Test
    @DisplayName("Receive stock creates STOCK_RECEIVED event and updates snapshot")
    void receiveStock_shouldCreateEventAndUpdateSnapshot() {
        var req = ReceiveRequest.builder()
                .warehouseId("wh-pun")
                .sku("SKU-1001")
                .quantity(100)
                .referenceId("PO-0001")
                .build();

        StockLevelDto result = inventoryService.receive(req);

        assertEquals(100, result.getOnHand());
        assertEquals(0, result.getReserved());
        assertEquals(100, result.getAvailable());
        assertEquals(1, result.getVersion());

        List<StockEvent> events = eventRepo.findByWarehouseIdAndSkuOrderByTimestampDesc("wh-pun", "SKU-1001");
        assertEquals(1, events.size());
        assertEquals("STOCK_RECEIVED", events.get(0).getType());
        assertEquals(100, events.get(0).getQuantity());
    }

    @Test
    @DisplayName("Adjust stock with positive delta increases onHand")
    void adjustPositive_shouldIncreaseOnHand() {
        inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_RECEIVED", 50, "SEED", null, "seed");

        var req = AdjustRequest.builder()
                .warehouseId("wh-pun")
                .sku("SKU-1001")
                .delta(10)
                .reason("COUNT_CORRECTION")
                .note("Found 10 extra units")
                .build();

        StockLevelDto result = inventoryService.adjust(req);

        assertEquals(60, result.getOnHand());
        assertEquals(0, result.getReserved());
        assertEquals(60, result.getAvailable());
    }

    @Test
    @DisplayName("Adjust stock with negative delta decreases onHand")
    void adjustNegative_shouldDecreaseOnHand() {
        inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_RECEIVED", 50, "SEED", null, "seed");

        var req = AdjustRequest.builder()
                .warehouseId("wh-pun")
                .sku("SKU-1001")
                .delta(-5)
                .reason("DAMAGE")
                .note("5 units damaged")
                .build();

        StockLevelDto result = inventoryService.adjust(req);

        assertEquals(45, result.getOnHand());
        assertEquals(45, result.getAvailable());
    }

    @Test
    @DisplayName("Adjust that would make onHand negative is rejected")
    void adjustToNegative_shouldThrow() {
        inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_RECEIVED", 10, "SEED", null, "seed");

        var req = AdjustRequest.builder()
                .warehouseId("wh-pun")
                .sku("SKU-1001")
                .delta(-20)
                .reason("THEFT")
                .note("20 units stolen")
                .build();

        assertThrows(com.invensense.common.exception.InsufficientStockException.class,
                () -> inventoryService.adjust(req));
    }

    @Test
    @DisplayName("Reserve stock increases reserved, not onHand")
    void reserveStock_shouldIncreaseReserved() {
        inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_RECEIVED", 50, "SEED", null, "seed");

        var event = inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_RESERVED", 10, "ORD-001", null, "order");

        assertEquals(50, event.getResultingOnHand());
        assertEquals(10, event.getResultingReserved());
        assertEquals(40, event.getResultingOnHand() - event.getResultingReserved());
    }

    @Test
    @DisplayName("Reserve more than available throws InsufficientStockException")
    void reserveMoreThanAvailable_shouldThrow() {
        inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_RECEIVED", 5, "SEED", null, "seed");

        assertThrows(com.invensense.common.exception.InsufficientStockException.class,
                () -> inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_RESERVED", 10, "ORD-002", null, "order"));
    }

    @Test
    @DisplayName("Ship stock decreases both onHand and reserved")
    void shipStock_shouldDecreaseBoth() {
        inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_RECEIVED", 50, "SEED", null, "seed");
        inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_RESERVED", 10, "ORD-001", null, "order");

        var event = inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_SHIPPED", 10, "ORD-001", null, "ship");

        assertEquals(40, event.getResultingOnHand());
        assertEquals(0, event.getResultingReserved());
    }

    @Test
    @DisplayName("History returns events newest first")
    void history_shouldReturnNewestFirst() throws InterruptedException {
        inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_RECEIVED", 100, "SEED", null, "seed");
        Thread.sleep(5);
        inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_RESERVED", 20, "ORD-001", null, "order");
        Thread.sleep(5);
        inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_ADJUSTED", 5, "ADJ-001", "DAMAGE", "5 damaged");

        List<StockEvent> events = eventRepo.findByWarehouseIdAndSkuOrderByTimestampDesc("wh-pun", "SKU-1001");

        assertEquals(3, events.size());
        assertEquals("STOCK_ADJUSTED", events.get(0).getType());
        assertEquals("STOCK_RESERVED", events.get(1).getType());
        assertEquals("STOCK_RECEIVED", events.get(2).getType());
    }

    @Test
    @DisplayName("History filters by event type")
    void historyWithFilter_shouldReturnOnlyMatchingType() {
        inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_RECEIVED", 100, "SEED", null, "seed");
        inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_RESERVED", 20, "ORD-001", null, "order");
        inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_RESERVED", 10, "ORD-002", null, "order");

        var events = inventoryService.getHistory("wh-pun", "SKU-1001", "STOCK_RESERVED");

        assertEquals(2, events.size());
        events.forEach(e -> assertEquals("STOCK_RESERVED", e.getType()));
    }

    @Test
    @DisplayName("As-of query replays events up to the given timestamp")
    void asOfQuery_shouldReplayCorrectly() throws InterruptedException {
        inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_RECEIVED", 100, "SEED", null, "seed");
        Thread.sleep(10);
        Instant afterFirst = Instant.now();
        Thread.sleep(10);

        inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_RESERVED", 30, "ORD-001", null, "order");
        Thread.sleep(10);
        Instant afterSecond = Instant.now();
        Thread.sleep(10);

        inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_SHIPPED", 30, "ORD-001", null, "ship");

        // As of after STOCK_RECEIVED only: onHand=100, reserved=0
        AsOfDto asOf1 = inventoryService.getAsOf("wh-pun", "SKU-1001", afterFirst);
        assertEquals(100, asOf1.getOnHand());
        assertEquals(0, asOf1.getReserved());
        assertEquals(100, asOf1.getAvailable());
        assertEquals(1, asOf1.getEventCount());

        // As of after STOCK_RESERVED: onHand=100, reserved=30
        AsOfDto asOf2 = inventoryService.getAsOf("wh-pun", "SKU-1001", afterSecond);
        assertEquals(100, asOf2.getOnHand());
        assertEquals(30, asOf2.getReserved());
        assertEquals(70, asOf2.getAvailable());
        assertEquals(2, asOf2.getEventCount());

        // As of after all events: onHand=70, reserved=0
        AsOfDto asOf3 = inventoryService.getAsOf("wh-pun", "SKU-1001", Instant.now());
        assertEquals(70, asOf3.getOnHand());
        assertEquals(0, asOf3.getReserved());
        assertEquals(70, asOf3.getAvailable());
        assertEquals(3, asOf3.getEventCount());
    }

    @Test
    @DisplayName("Event fold: STOCK_ADJUSTED with negative delta decreases onHand")
    void eventFold_negativeAdjust() {
        inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_RECEIVED", 100, "SEED", null, "seed");
        inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_ADJUSTED", -15, "ADJ-001", "DAMAGE", "damaged");

        StockLevel level = levelRepo.findByWarehouseIdAndSku("wh-pun", "SKU-1001").orElseThrow();
        assertEquals(85, level.getOnHand());
        assertEquals(0, level.getReserved());
    }

    @Test
    @DisplayName("Version increments by 1 for each event")
    void versionIncrements() {
        inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_RECEIVED", 100, "SEED", null, "seed");
        StockLevel l1 = levelRepo.findByWarehouseIdAndSku("wh-pun", "SKU-1001").orElseThrow();
        assertEquals(1, l1.getVersion());

        inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_RESERVED", 10, "ORD-001", null, "order");
        StockLevel l2 = levelRepo.findByWarehouseIdAndSku("wh-pun", "SKU-1001").orElseThrow();
        assertEquals(2, l2.getVersion());

        inventoryService.appendEvent("wh-pun", "SKU-1001", "STOCK_SHIPPED", 10, "ORD-001", null, "ship");
        StockLevel l3 = levelRepo.findByWarehouseIdAndSku("wh-pun", "SKU-1001").orElseThrow();
        assertEquals(3, l3.getVersion());
    }
}
