package com.invensense.inventory.document;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "stock_levels")
@CompoundIndex(name = "idx_wh_sku", def = "{'warehouseId': 1, 'sku': 1}", unique = true)
public class StockLevel {

    @Id
    private String id;
    private String warehouseId;
    private String sku;
    private int onHand;
    private int reserved;
    private int version;
    private Instant updatedAt;

    public int getAvailable() {
        return onHand - reserved;
    }
}
