package com.aiproject.aiassitant.module.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@Schema(description = "Login response with JWT pair and profile")
public class LoginResponse {

    @Schema(description = "Access token used in Authorization header")
    private String accessToken;

    @Schema(description = "Refresh token used to renew the access token")
    private String refreshToken;

    @Schema(description = "Access token TTL in seconds")
    private long expiresIn;

    @Schema(description = "Profile of the authenticated user")
    private Profile profile;

    @Data
    @Builder
    public static class Profile {
        private String id;
        private String username;
        private String displayName;
        private String avatar;
        private String email;
        private String scope;
        private List<String> roles;
    }
}
