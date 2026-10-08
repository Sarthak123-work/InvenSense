package com.invensense.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {
    @NotBlank @Size(max = 100)
    private String name;

    @NotBlank @Email @Size(max = 150)
    private String email;

    @NotBlank @Size(min = 6, max = 50)
    private String password;

    @NotBlank
    private String role;

    private String warehouseId;
}
