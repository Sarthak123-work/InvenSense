package com.invensense.inventory.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "inventory.concurrency")
public class InventoryProperties {

    /**
     * When true, skips the Redisson lock and the version-conditional update,
     * falling back to the unsafe phase-3 read-compute-save path.
     * Demo profile only — NEVER enable in production.
     * Section 9.4.
     */
    private boolean unsafeMode = false;

    /**
     * Maximum number of retry attempts when the version-conditional
     * update fails (another thread won the race).
     */
    private int maxRetries = 3;

    /**
     * Lock lease time in milliseconds (how long the lock is held).
     */
    private long lockLeaseTimeMs = 5000;

    /**
     * Lock wait time in milliseconds (how long to wait to acquire the lock).
     */
    private long lockWaitTimeMs = 3000;

    public void logMode() {
        if (unsafeMode) {
            log.warn(">>> UNSAFE MODE ENABLED — no locking, no version check. "
                    + "Concurrency safety is OFF. Do NOT use in production. (section 9.4)");
        } else {
            log.info("Safe mode: Redisson multi-lock + version-conditional update enabled.");
        }
    }
}
