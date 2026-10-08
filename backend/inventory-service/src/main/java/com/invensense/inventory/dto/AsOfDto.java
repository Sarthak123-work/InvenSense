package com.invensense.inventory.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsOfDto {
    private int onHand;
    private int reserved;
    private int available;
    private int eventCount;
}
