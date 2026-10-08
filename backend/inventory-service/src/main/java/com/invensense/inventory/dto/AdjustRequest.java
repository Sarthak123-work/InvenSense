package com.invensense.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdjustRequest {
    @NotBlank
    private String warehouseId;
    @NotBlank
    private String sku;
    @NotNull
    private Integer delta;
    @NotBlank
    private String reason;
    private String note;
}
