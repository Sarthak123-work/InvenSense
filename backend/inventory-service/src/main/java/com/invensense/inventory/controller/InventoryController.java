package com.invensense.inventory.controller;

import java.time.Instant;
import java.util.List;

import com.invensense.inventory.dto.AdjustRequest;
import com.invensense.inventory.dto.AsOfDto;
import com.invensense.inventory.dto.ReceiveRequest;
import com.invensense.inventory.dto.StockEventDto;
import com.invensense.inventory.dto.StockLevelDto;
import com.invensense.inventory.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping
    public ResponseEntity<List<StockLevelDto>> getStockLevels(
            @RequestParam(required = false) String warehouseId,
            @RequestParam(required = false) String sku) {
        return ResponseEntity.ok(inventoryService.getStockLevels(warehouseId, sku));
    }

    @GetMapping("/{warehouseId}/{sku}/history")
    public ResponseEntity<?> getHistory(
            @PathVariable String warehouseId,
            @PathVariable String sku,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String asOf) {

        if (asOf != null && !asOf.isBlank()) {
            AsOfDto result = inventoryService.getAsOf(warehouseId, sku, Instant.parse(asOf));
            return ResponseEntity.ok(result);
        }
        List<StockEventDto> events = inventoryService.getHistory(warehouseId, sku, type);
        return ResponseEntity.ok(events);
    }

    @PostMapping("/receive")
    public ResponseEntity<StockLevelDto> receive(@Valid @RequestBody ReceiveRequest req) {
        return ResponseEntity.ok(inventoryService.receive(req));
    }

    @PostMapping("/adjust")
    public ResponseEntity<StockLevelDto> adjust(@Valid @RequestBody AdjustRequest req) {
        return ResponseEntity.ok(inventoryService.adjust(req));
    }
}
