package com.invensense.inventory.repository;

import java.util.Optional;

import com.invensense.inventory.document.StockLevel;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StockLevelRepository extends MongoRepository<StockLevel, String> {

    Optional<StockLevel> findByWarehouseIdAndSku(String warehouseId, String sku);
}
