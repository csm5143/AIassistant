package com.aiproject.aiassitant.module.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Change password request")
public class ChangePasswordRequest {

    @NotBlank(message = "Old password is required")
    @Schema(description = "Current password")
    private String oldPassword;

    @NotBlank(message = "New password is required")
    @Schema(description = "New password")
    private String newPassword;
}
