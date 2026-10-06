package com.aiproject.aiassitant.module.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "User registration request")
public class RegisterRequest {

    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 32, message = "Username must be 3-32 characters")
    @Schema(description = "Username for the new account")
    private String username;

    @NotBlank(message = "Password is required")
    @Size(min = 6, max = 64, message = "Password must be at least 6 characters")
    @Schema(description = "Password")
    private String password;

    @Email(message = "Invalid email format")
    @Schema(description = "Email address")
    private String email;

    @Schema(description = "Display name")
    private String displayName;

    @Schema(description = "Phone number")
    private String phone;
}
