package com.invensense.inventory.document;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "stock_events")
@CompoundIndex(name = "idx_wh_sku_ts", def = "{'warehouseId': 1, 'sku': 1, 'timestamp': 1}")
@CompoundIndex(name = "idx_wh_type_ref", def = "{'warehouseId': 1, 'type': 1, 'referenceId': 1}", unique = true)
public class StockEvent {

    @Id
    private String eventId;

    @Indexed
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
