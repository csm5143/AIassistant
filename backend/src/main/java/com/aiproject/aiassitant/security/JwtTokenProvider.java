package com.aiproject.aiassitant.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Issues and validates HS256 JWTs. Claims:
 *   - sub: user id
 *   - username
 *   - scope: user | admin
 *   - roles: list of role codes (USER, ADMIN)
 *   - jti: random id used for blacklist / refresh tracking
 */
@Slf4j
@Component
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.issuer}")
    private String issuer;

    @Value("${jwt.access-ttl-minutes}")
    private long accessTtlMinutes;

    @Value("${jwt.refresh-ttl-days}")
    private long refreshTtlDays;

    @Getter
    private SecretKey signingKey;

    @PostConstruct
    void init() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("jwt.secret must be at least 32 bytes (256 bits) for HS256");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        log.info("JWT signing key initialised (issuer={}, accessTtl={}m, refreshTtl={}d)",
                issuer, accessTtlMinutes, refreshTtlDays);
    }

    public String issueAccessToken(String userId, String username, String scope, List<String> roles) {
        return buildToken(userId, username, scope, roles, "access", Duration.ofMinutes(accessTtlMinutes));
    }

    public String issueRefreshToken(String userId, String username, String scope, List<String> roles) {
        return buildToken(userId, username, scope, roles, "refresh", Duration.ofDays(refreshTtlDays));
    }

    private String buildToken(String userId, String username, String scope, List<String> roles, String type, Duration ttl) {
        Instant now = Instant.now();
        Map<String, Object> claims = new HashMap<>();
        claims.put("username", username);
        claims.put("scope", scope);
        claims.put("roles", roles);
        claims.put("type", type);
        return Jwts.builder()
                .id(java.util.UUID.randomUUID().toString())
                .issuer(issuer)
                .subject(userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .claims(claims)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .clockSkewSeconds(60) // allow 60s clock drift + SSE streaming overlap
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException ex) {
            log.debug("JWT validation failed: {}", ex.getMessage());
            throw ex;
        }
    }
}
