package com.aiproject.aiassitant.security;

import com.aiproject.aiassitant.common.SecurityUtil;
import com.aiproject.aiassitant.module.auth.entity.SysUser;
import com.aiproject.aiassitant.module.auth.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service("authz")
@RequiredArgsConstructor
public class AuthorizationService {

    private final SysUserMapper userMapper;

    public boolean isOwnerOrAdmin(String resourceUserId) {
        if (!SecurityUtil.isAuthenticated()) {
            return false;
        }
        AppPrincipal principal = (AppPrincipal) SecurityUtil.getAuthentication().getPrincipal();
        if (principal.isAdmin()) {
            return true;
        }
        return principal.getUserId().equals(resourceUserId);
    }

    public boolean isAdmin() {
        if (!SecurityUtil.isAuthenticated()) {
            return false;
        }
        AppPrincipal principal = (AppPrincipal) SecurityUtil.getAuthentication().getPrincipal();
        return principal.isAdmin();
    }

    public boolean isOwner(String resourceUserId) {
        if (!SecurityUtil.isAuthenticated()) {
            return false;
        }
        AppPrincipal principal = (AppPrincipal) SecurityUtil.getAuthentication().getPrincipal();
        return principal.getUserId().equals(resourceUserId);
    }
}
