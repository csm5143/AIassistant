package com.aiproject.aiassitant.module.auth.controller;

import com.aiproject.aiassitant.common.R;
import com.aiproject.aiassitant.common.SecurityUtil;
import com.aiproject.aiassitant.module.auth.dto.LoginRequest;
import com.aiproject.aiassitant.module.auth.dto.LoginResponse;
import com.aiproject.aiassitant.module.auth.entity.SysUser;
import com.aiproject.aiassitant.module.auth.service.AuthService;
import com.aiproject.aiassitant.module.user.dto.RegisterRequest;
import com.aiproject.aiassitant.module.user.service.UserService;
import com.aiproject.aiassitant.security.AppPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "Auth", description = "Login, register and profile endpoints")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    @Operation(summary = "Login with username and password")
    @PostMapping("/login")
    public R<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        return R.ok(authService.login(request));
    }

    @Operation(summary = "Register a new user account")
    @PostMapping("/register")
    public R<Map<String, Object>> register(@RequestBody @Valid RegisterRequest req) {
        SysUser user = userService.register(req);
        LoginResponse loginResponse = authService.login(
                new LoginRequest() {{
                    setUsername(req.getUsername());
                    setPassword(req.getPassword());
                }}
        );
        Map<String, Object> data = new HashMap<>();
        data.put("user", Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "displayName", user.getDisplayName() != null ? user.getDisplayName() : user.getUsername(),
                "email", user.getEmail() != null ? user.getEmail() : ""
        ));
        data.put("tokens", Map.of(
                "accessToken", loginResponse.getAccessToken(),
                "refreshToken", loginResponse.getRefreshToken(),
                "expiresIn", loginResponse.getExpiresIn()
        ));
        return R.ok(data);
    }

    @Operation(summary = "Get the current authenticated user profile")
    @GetMapping("/me")
    public R<Map<String, Object>> me() {
        AppPrincipal principal = (AppPrincipal) SecurityUtil.getAuthentication().getPrincipal();
        SysUser user = authService.getById(principal.getUserId());
        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("displayName", user.getDisplayName());
        data.put("email", user.getEmail());
        data.put("avatar", user.getAvatar());
        data.put("scope", principal.getScope());
        data.put("roles", principal.getRoles());
        return R.ok(data);
    }

    @Operation(summary = "Refresh access token using a valid refresh token")
    @PostMapping("/refresh")
    public R<LoginResponse> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            return R.fail(400, "refreshToken is required");
        }
        return R.ok(authService.refreshToken(refreshToken));
    }

    @Operation(summary = "Logout — revoke current session tokens")
    @PostMapping("/logout")
    public R<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization,
                          @RequestBody(required = false) Map<String, String> body) {
        String access = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        authService.logout(access, body == null ? null : body.get("refreshToken"));
        return R.ok();
    }
}
