package com.aiproject.aiassitant.module.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Login request")
public class LoginRequest {

    @NotBlank
    @Schema(description = "Username", example = "admin")
    private String username;

    @NotBlank
    @Schema(description = "Plain-text password (transmitted over TLS)", example = "admin123")
    private String password;

    @Schema(description = "Optional portal scope hint: 'user' or 'admin'", example = "admin")
    private String scope;
}
