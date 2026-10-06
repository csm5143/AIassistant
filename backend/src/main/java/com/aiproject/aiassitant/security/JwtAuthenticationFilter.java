package com.aiproject.aiassitant.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aiproject.aiassitant.common.R;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Pulls the bearer token from the Authorization header, validates it, and builds an
 * authenticated principal for the request. Public endpoints short-circuit early.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final com.aiproject.aiassitant.module.auth.mapper.SysUserMapper userMapper;
    private final com.aiproject.aiassitant.module.auth.mapper.SysRoleMapper roleMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (List.of("/auth/login", "/auth/register", "/auth/refresh", "/auth/logout", "/admin/settings/public").contains(request.getServletPath())) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            chain.doFilter(request, response);
            return;
        }
        String token = header.substring(BEARER.length()).trim();
        try {
            Claims claims = jwtTokenProvider.parse(token);
            String type = Objects.toString(claims.get("type"), "access");
            if (!"access".equals(type)) {
                writeUnauthorized(response, "Invalid token type");
                return;
            }
            if (isBlacklisted(claims.getId())) {
                writeUnauthorized(response, "Token revoked");
                return;
            }
            String userId = claims.getSubject();
            var user = userMapper.selectById(userId);
            if (user == null || user.getStatus() == null || user.getStatus() != 1) {
                writeUnauthorized(response, "Account disabled");
                return;
            }
            String username = Objects.toString(claims.get("username"), "");
            String scope = Objects.toString(claims.get("scope"), "user");
            List<String> roles = roleMapper.selectByUserId(userId).stream().map(com.aiproject.aiassitant.module.auth.entity.SysRole::getCode).toList();
            scope = roles.contains("ADMIN") ? "admin" : "user";

            AppPrincipal principal = new AppPrincipal(userId, username, scope, roles);
            List<SimpleGrantedAuthority> authorities = roles.stream()
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                    .toList();

            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    principal, token, authorities);
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(request, response);
        } catch (JwtException ex) {
            log.debug("Rejecting request with invalid JWT: {}", ex.getMessage());
            writeUnauthorized(response, "Invalid or expired token");
        }
    }

    private boolean isBlacklisted(String jti) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey("jwt:blacklist:" + jti));
        } catch (Exception e) {
            return true;
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(401, message)));
    }
}
