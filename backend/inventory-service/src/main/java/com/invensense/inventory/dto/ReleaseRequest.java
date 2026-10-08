package com.invensense.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReleaseRequest {
    @NotBlank
    private String warehouseId;
    @NotBlank
    private String sku;
    @NotBlank
    private String referenceId;
    private String reason;
}
