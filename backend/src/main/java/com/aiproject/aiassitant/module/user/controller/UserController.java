package com.aiproject.aiassitant.module.user.controller;

import com.aiproject.aiassitant.common.R;
import com.aiproject.aiassitant.module.auth.entity.SysUser;
import com.aiproject.aiassitant.module.chat.entity.UserQuota;
import com.aiproject.aiassitant.module.user.dto.ChangePasswordRequest;
import com.aiproject.aiassitant.module.user.dto.RegisterRequest;
import com.aiproject.aiassitant.module.user.dto.UpdateUserRequest;
import com.aiproject.aiassitant.module.user.service.UserService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "User", description = "User management endpoints")
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "Register a new user")
    @PostMapping
    public R<SysUser> register(@RequestBody @Valid RegisterRequest req) {
        SysUser user = userService.register(req);
        user.setPasswordHash(null);
        return R.ok(user);
    }

    @Operation(summary = "Get user profile")
    @GetMapping("/me")
    public R<Map<String, Object>> me() {
        String userId = com.aiproject.aiassitant.common.SecurityUtil.getCurrentUserId();
        SysUser user = userService.getUser(userId);
        UserQuota quota = userService.getUserQuota(userId);
        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("displayName", user.getDisplayName());
        data.put("email", user.getEmail());
        data.put("phone", user.getPhone());
        data.put("avatar", user.getAvatar());
        data.put("status", user.getStatus());
        data.put("createdAt", user.getCreatedAt());
        data.put("quota", quota);
        return R.ok(data);
    }

    @Operation(summary = "Update user profile")
    @PutMapping("/{id}")
    @PreAuthorize("@authz.isOwnerOrAdmin(#id)")
    public R<SysUser> updateUser(@PathVariable String id, @RequestBody @Valid UpdateUserRequest req) {
        SysUser user = userService.updateUser(id, req);
        user.setPasswordHash(null);
        return R.ok(user);
    }

    @Operation(summary = "Change password")
    @PutMapping("/{id}/password")
    @PreAuthorize("@authz.isOwnerOrAdmin(#id)")
    public R<Void> changePassword(@PathVariable String id, @RequestBody @Valid ChangePasswordRequest req) {
        userService.changePassword(id, req);
        return R.ok();
    }

    @Operation(summary = "Get user quota / usage stats (own or admin)")
    @GetMapping("/{id}/quota")
    @PreAuthorize("hasRole('ADMIN') or #id == principal.userId")
    public R<UserQuota> getQuota(@PathVariable String id) {
        return R.ok(userService.getUserQuota(id));
    }

    @Operation(summary = "List all users (admin only)")
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public R<Page<SysUser>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<SysUser> p =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size);
        List<SysUser> records = userService.listUsers(page, size, keyword);
        long total = userService.countUsers(keyword);
        p.setRecords(records);
        p.setTotal(total);
        records.forEach(u -> u.setPasswordHash(null));
        return R.ok(p);
    }

    @Operation(summary = "Get user by ID (admin only)")
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public R<SysUser> getUser(@PathVariable String id) {
        SysUser user = userService.getUser(id);
        if (user != null) user.setPasswordHash(null);
        return R.ok(user);
    }

    @Operation(summary = "Enable a user (admin only)")
    @PatchMapping("/{id}/enable")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> enableUser(@PathVariable String id) {
        userService.enableUser(id);
        return R.ok();
    }

    @Operation(summary = "Disable a user (admin only)")
    @PatchMapping("/{id}/disable")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> disableUser(@PathVariable String id) {
        userService.disableUser(id);
        return R.ok();
    }

    @Operation(summary = "Update user quota (admin only)")
    @PutMapping("/{id}/quota")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> updateQuota(@PathVariable String id, @RequestBody Map<String, Object> quotaData) {
        userService.updateUserQuota(id, quotaData);
        return R.ok();
    }

    @Operation(summary = "Delete a user (admin only)")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> deleteUser(@PathVariable String id) {
        userService.deleteUser(id);
        return R.ok();
    }
}
