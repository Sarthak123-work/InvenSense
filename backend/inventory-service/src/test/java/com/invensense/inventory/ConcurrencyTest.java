package com.invensense.inventory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.invensense.common.exception.InsufficientStockException;
import com.invensense.inventory.document.StockEvent;
import com.invensense.inventory.document.StockLevel;
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

/**
 * Concurrency test from section 15 of the brief.
 *
 * Seeds 5 units of stock, then fires 50 concurrent reserve requests for 1 unit each.
 * Phase 3: NO locking — this test is EXPECTED TO FAIL, demonstrating the oversell
 * that phase 4's Redisson lock + optimistic version check will fix.
 */
@Testcontainers
@DataMongoTest
@Import(InventoryService.class)
class ConcurrencyTest {

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

    private static final String WAREHOUSE_ID = "wh-pun";
    private static final String SKU = "SKU-TEST";
    private static final int STARTING_STOCK = 5;
    private static final int CONCURRENT_REQUESTS = 50;

    @BeforeEach
    void setUp() {
        // Ensure collections are clean
        mongoTemplate.dropCollection(StockEvent.class);
        mongoTemplate.dropCollection(StockLevel.class);

        // Seed initial stock: 5 units of STOCK_RECEIVED
        inventoryService.appendEvent(WAREHOUSE_ID, SKU, "STOCK_RECEIVED",
                STARTING_STOCK, "SEED-" + UUID.randomUUID(), null, "seed");

        // Verify seed
        StockLevel level = levelRepo.findByWarehouseIdAndSku(WAREHOUSE_ID, SKU).orElseThrow();
        assertEquals(STARTING_STOCK, level.getOnHand(), "Seed should set onHand=5");
        assertEquals(STARTING_STOCK, level.getAvailable(), "Seed should set available=5");
    }

    @AfterEach
    void tearDown() {
        mongoTemplate.dropCollection(StockEvent.class);
        mongoTemplate.dropCollection(StockLevel.class);
    }

    @Test
    @DisplayName("50 concurrent reservations for 5 units — EXACTLY 5 should succeed (EXPECTED TO FAIL in phase 3)")
    void concurrentReserve_noLock_shouldOversell() throws InterruptedException {

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(CONCURRENT_REQUESTS);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);
        AtomicInteger oversellCount = new AtomicInteger(0);

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startGate.await(); // all threads wait at the gate

                    inventoryService.appendEvent(
                            WAREHOUSE_ID, SKU, "STOCK_RESERVED", 1,
                            "RES-" + UUID.randomUUID(), null, "test-thread-" + idx);

                    int currentSuccess = successes.incrementAndGet();
                    // Track how many succeeded beyond the 5 that should have been allowed
                    if (currentSuccess > STARTING_STOCK) {
                        oversellCount.incrementAndGet();
                    }

                } catch (InsufficientStockException e) {
                    failures.incrementAndGet();
                } catch (Exception e) {
                    // Other exceptions count as failures too
                    failures.incrementAndGet();
                } finally {
                    endGate.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startGate.countDown();
        endGate.await();
        executor.shutdown();

        // ---- Assertions (these SHOULD pass in phase 4, but FAIL in phase 3) ----

        StockLevel finalLevel = levelRepo.findByWarehouseIdAndSku(WAREHOUSE_ID, SKU).orElseThrow();
        List<StockEvent> reservedEvents = eventRepo.findAll().stream()
                .filter(e -> e.getType().equals("STOCK_RESERVED"))
                .toList();

        System.out.println("============================================================");
        System.out.println("  CONCURRENCY TEST RESULT (Phase 3 — NO LOCKING)");
        System.out.println("============================================================");
        System.out.println("  Starting stock:       " + STARTING_STOCK);
        System.out.println("  Concurrent requests:  " + CONCURRENT_REQUESTS);
        System.out.println("  Successes:            " + successes.get() + " (expected: 5)");
        System.out.println("  Failures (409):       " + failures.get() + " (expected: 45)");
        System.out.println("  Reserved events:      " + reservedEvents.size() + " (expected: 5)");
        System.out.println("  Final onHand:         " + finalLevel.getOnHand());
        System.out.println("  Final reserved:       " + finalLevel.getReserved());
        System.out.println("  Final available:      " + finalLevel.getAvailable() + " (expected: 0)");
        System.out.println("  Oversold units:       " + Math.max(0, successes.get() - STARTING_STOCK));
        System.out.println("============================================================");

        // These assertions WILL FAIL in phase 3 because there is no locking.
        // The test demonstrates overselling that phase 4 will fix.

        assertEquals(STARTING_STOCK, successes.get(),
                "FAIL: Expected exactly 5 successes but got " + successes.get()
                        + " — OVERSOLD by " + (successes.get() - STARTING_STOCK) + " units");

        assertEquals(CONCURRENT_REQUESTS - STARTING_STOCK, failures.get(),
                "FAIL: Expected 45 failures but got " + failures.get());

        assertEquals(STARTING_STOCK, reservedEvents.size(),
                "FAIL: Expected 5 STOCK_RESERVED events but got " + reservedEvents.size());

        assertEquals(STARTING_STOCK, finalLevel.getReserved(),
                "FAIL: Expected reserved=5 but got " + finalLevel.getReserved());

        assertEquals(0, finalLevel.getAvailable(),
                "FAIL: Expected available=0 but got " + finalLevel.getAvailable()
                        + " — reserved > onHand, invariant broken");
    }
}
