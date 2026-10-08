package com.invensense.inventory.dto;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockEventDto {
    private String eventId;
    private String warehouseId;
    private String sku;
    private String type;
    private int quantity;
    private int resultingOnHand;
    private int resultingReserved;
    private Integer version;
    private String referenceId;
    private String reason;
    private String note;
    private String actor;
    private Instant timestamp;
}
