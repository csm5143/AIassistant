package com.aiproject.aiassitant.common;

import com.aiproject.aiassitant.security.AppPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtil {

    private SecurityUtil() {
    }

    public static Authentication getAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    public static boolean isAuthenticated() {
        Authentication auth = getAuthentication();
        return auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal());
    }

    public static String currentUserId() {
        Object principal = getAuthentication() == null ? null : getAuthentication().getPrincipal();
        if (principal instanceof AppPrincipal appPrincipal) {
            return appPrincipal.getUserId();
        }
        return null;
    }

    public static String getCurrentUserId() {
        try {
            String userId = currentUserId();
            return userId != null ? userId : "anonymous";
        } catch (Exception e) {
            return "anonymous";
        }
    }

    /** Returns the current bearer token string, or null if not authenticated. */
    public static String getCurrentToken() {
        Authentication auth = getAuthentication();
        if (auth != null && auth.getCredentials() instanceof String token) {
            return token;
        }
        return null;
    }
}
