package com.aiproject.aiassitant.module.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import lombok.Data;

@Data
@Schema(description = "Update user profile request")
public class UpdateUserRequest {

    @Schema(description = "Display name")
    private String displayName;

    @Email(message = "Invalid email format")
    @Schema(description = "Email address")
    private String email;

    @Schema(description = "Phone number")
    private String phone;

    @Schema(description = "Avatar URL")
    private String avatar;
}
