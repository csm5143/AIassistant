package com.aiproject.aiassitant.security;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * Minimal principal stored in SecurityContext. Carries the user id, scope, and roles
 * so downstream code (especially @PreAuthorize) can authorize without touching the DB.
 */
@Getter
@AllArgsConstructor
public class AppPrincipal {

    private final String userId;
    private final String username;
    private final String scope;
    private final List<String> roles;

    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }

    public boolean isAdmin() {
        return "admin".equalsIgnoreCase(scope) || hasRole("ADMIN");
    }

    @Override
    public String toString() {
        return userId;
    }
}
