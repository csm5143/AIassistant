package com.aiproject.aiassitant.module.auth.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.aiproject.aiassitant.common.BizException;
import com.aiproject.aiassitant.common.SecurityUtil;
import com.aiproject.aiassitant.module.auth.dto.LoginRequest;
import com.aiproject.aiassitant.module.auth.dto.LoginResponse;
import com.aiproject.aiassitant.module.auth.entity.SysRole;
import com.aiproject.aiassitant.module.auth.entity.SysUser;
import com.aiproject.aiassitant.module.auth.mapper.SysRoleMapper;
import com.aiproject.aiassitant.module.auth.mapper.SysUserMapper;
import com.aiproject.aiassitant.module.guard.entity.GuardLog;
import com.aiproject.aiassitant.module.guard.mapper.GuardLogMapper;
import com.aiproject.aiassitant.security.JwtTokenProvider;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final GuardLogMapper guardLogMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${jwt.access-ttl-minutes}")
    private long accessTtlMinutes;

    @Value("${jwt.refresh-ttl-days}")
    private long refreshTtlDays;

    @Transactional
    public LoginResponse login(LoginRequest request) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.getUsername())
                .last("LIMIT 1"));
        if (user == null) {
            throw BizException.unauthorized("Invalid username or password");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw BizException.forbidden("Account disabled");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw BizException.unauthorized("Invalid username or password");
        }

        List<SysRole> roles = roleMapper.selectByUserId(user.getId());
        List<String> roleCodes = roles.stream().map(SysRole::getCode).toList();
        String scope = inferScope(roleCodes, request.getScope());

        if ("admin".equalsIgnoreCase(scope) && !roleCodes.contains("ADMIN")) {
            throw BizException.forbidden("Not authorized for admin portal");
        }

        String access = jwtTokenProvider.issueAccessToken(user.getId(), user.getUsername(), scope, roleCodes);
        String refresh = jwtTokenProvider.issueRefreshToken(user.getId(), user.getUsername(), scope, roleCodes);

        user.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(user);

        log.info("User {} logged in (scope={}, roles={})", user.getUsername(), scope, roleCodes);

        // Audit log: record login event
        try {
            GuardLog auditLog = new GuardLog();
            auditLog.setUserId(user.getId());
            auditLog.setDirection("LOGIN");
            auditLog.setStage(scope);
            auditLog.setRule("SUCCESS");
            auditLog.setAction("ALLOW");
            guardLogMapper.insert(auditLog);
        } catch (Exception ignored) {
            // Non-critical audit logging
        }

        return LoginResponse.builder()
                .accessToken(access)
                .refreshToken(refresh)
                .expiresIn(accessTtlMinutes * 60L)
                .profile(LoginResponse.Profile.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .displayName(user.getDisplayName())
                        .avatar(user.getAvatar())
                        .email(user.getEmail())
                        .scope(scope)
                        .roles(roleCodes)
                        .build())
                .build();
    }

    private String inferScope(List<String> roleCodes, String requested) {
        if (StrUtil.isNotBlank(requested)) {
            return requested.toLowerCase(Locale.ROOT);
        }
        return roleCodes.contains("ADMIN") ? "admin" : "user";
    }

    /**
     * Issue new tokens using a valid refresh token.
     * The old refresh token is revoked (added to blacklist).
     */
    public LoginResponse refreshToken(String refreshToken) {
        Claims claims;
        try {
            claims = jwtTokenProvider.parse(refreshToken);
        } catch (Exception e) {
            throw BizException.unauthorized("Invalid refresh token");
        }
        String type = Objects.toString(claims.get("type"), "");
        if (!"refresh".equals(type)) {
            throw BizException.unauthorized("Token is not a refresh token");
        }
        if (isBlacklisted(claims.getId())) {
            throw BizException.unauthorized("Token has been revoked");
        }
        String userId = claims.getSubject();
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw BizException.forbidden("Account disabled");
        }
        List<SysRole> roles = roleMapper.selectByUserId(userId);
        List<String> roleCodes = roles.stream().map(SysRole::getCode).toList();
        String scope = roleCodes.contains("ADMIN") ? "admin" : "user";
        // One refresh token may only be consumed once, even across simultaneous requests.
        Duration remaining = Duration.between(java.time.Instant.now(), claims.getExpiration().toInstant());
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                "jwt:blacklist:" + claims.getId(), "rotated", remaining))) {
            throw BizException.unauthorized("Token has been revoked");
        }

        String newAccess = jwtTokenProvider.issueAccessToken(userId, user.getUsername(), scope, roleCodes);
        String newRefresh = jwtTokenProvider.issueRefreshToken(userId, user.getUsername(), scope, roleCodes);

        return LoginResponse.builder()
                .accessToken(newAccess)
                .refreshToken(newRefresh)
                .expiresIn(accessTtlMinutes * 60L)
                .build();
    }

    /** Revoke the current user's tokens. */
    public void logout(String accessToken, String refreshToken) {
        try {
            String token = accessToken;
            if (token != null) {
                Claims claims = jwtTokenProvider.parse(token);
                revoke(claims);
                log.debug("Token blacklisted: jti={}", claims.getId());
            }
        } catch (io.jsonwebtoken.JwtException e) {
            log.debug("Logout blacklist failed (token already expired?): {}", e.getMessage());
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            try {
                Claims claims = jwtTokenProvider.parse(refreshToken);
                if ("refresh".equals(claims.get("type"))) revoke(claims);
            } catch (io.jsonwebtoken.JwtException ignored) { /* Already expired. */ }
        }
    }

    private void revoke(Claims claims) {
        Duration ttl = Duration.between(java.time.Instant.now(), claims.getExpiration().toInstant());
        if (!ttl.isNegative() && !ttl.isZero()) redisTemplate.opsForValue().set("jwt:blacklist:" + claims.getId(), "revoked", ttl);
    }

    /** Check if a JWT jti is in the revocation blacklist. */
    public boolean isBlacklisted(String jti) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey("jwt:blacklist:" + jti));
        } catch (Exception e) {
            throw new BizException(503, "认证服务暂时不可用，请稍后重试");
        }
    }

    public SysUser getById(String id) {
        return Objects.requireNonNull(userMapper.selectById(id), "User not found");
    }
}
