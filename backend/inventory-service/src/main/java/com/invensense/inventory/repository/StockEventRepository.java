package com.invensense.inventory.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.invensense.inventory.document.StockEvent;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StockEventRepository extends MongoRepository<StockEvent, String> {

    List<StockEvent> findByWarehouseIdAndSkuOrderByTimestampDesc(String warehouseId, String sku);

    Optional<StockEvent> findByWarehouseIdAndTypeAndReferenceId(
            String warehouseId, String type, String referenceId);

    default List<StockEvent> findByWarehouseIdAndSkuAndTimestampBeforeOrderByTimestampAsc(
            String warehouseId, String sku, Instant asOf) {
        return findAll().stream()
                .filter(e -> e.getWarehouseId().equals(warehouseId)
                        && e.getSku().equals(sku)
                        && !e.getTimestamp().isAfter(asOf))
                .sorted((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()))
                .toList();
    }
}
