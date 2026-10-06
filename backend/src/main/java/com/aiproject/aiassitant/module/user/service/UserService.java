package com.aiproject.aiassitant.module.user.service;

import com.aiproject.aiassitant.common.BizException;
import com.aiproject.aiassitant.module.auth.entity.SysUser;
import com.aiproject.aiassitant.module.auth.mapper.SysUserMapper;
import com.aiproject.aiassitant.module.chat.entity.UserQuota;
import com.aiproject.aiassitant.module.chat.mapper.UserQuotaMapper;
import com.aiproject.aiassitant.module.chat.service.TokenUsageService;
import com.aiproject.aiassitant.module.subscription.entity.SubscriptionTier;
import com.aiproject.aiassitant.module.subscription.entity.UserSubscription;
import com.aiproject.aiassitant.module.subscription.mapper.SubscriptionTierMapper;
import com.aiproject.aiassitant.module.subscription.mapper.UserSubscriptionMapper;
import com.aiproject.aiassitant.module.user.dto.ChangePasswordRequest;
import com.aiproject.aiassitant.module.user.dto.RegisterRequest;
import com.aiproject.aiassitant.module.user.dto.UpdateUserRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserMapper userMapper;
    private final com.aiproject.aiassitant.module.auth.mapper.SysRoleMapper roleMapper;
    private final UserQuotaMapper userQuotaMapper;
    private final UserSubscriptionMapper subscriptionMapper;
    private final SubscriptionTierMapper tierMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenUsageService tokenUsageService;
    private final com.aiproject.aiassitant.module.knowledge.service.KnowledgeService knowledgeService;
    private final com.aiproject.aiassitant.ai.memory.RedisChatMemoryStore memoryStore;
    private final com.aiproject.aiassitant.module.documentqa.service.DocumentQaService documentQaService;
    private final javax.sql.DataSource dataSource;

    @Transactional
    public SysUser register(RegisterRequest req) {
        LambdaQueryWrapper<SysUser> q = new LambdaQueryWrapper<>();
        q.eq(SysUser::getUsername, req.getUsername());
        if (userMapper.selectCount(q) > 0) {
            throw new BizException("Username already exists");
        }

        SysUser user = new SysUser();
        user.setId(UUID.randomUUID().toString().replace("-", ""));
        user.setUsername(req.getUsername());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setDisplayName(req.getDisplayName() != null ? req.getDisplayName() : req.getUsername());
        user.setEmail(req.getEmail());
        user.setPhone(req.getPhone());
        user.setStatus(1);
        userMapper.insert(user);
        roleMapper.assignUserRole(user.getId());

        initializeUserQuota(user.getId());

        assignDefaultSubscription(user.getId());

        log.info("User registered: {}", user.getUsername());
        return user;
    }

    private void initializeUserQuota(String userId) {
        UserQuota quota = new UserQuota();
        quota.setId(UUID.randomUUID().toString().replace("-", ""));
        quota.setUserId(userId);
        quota.setTier("free");
        quota.setDailyTokenLimit(100000L);
        quota.setMonthlyTokenLimit(500000L);
        quota.setDailyRequestLimit(100);
        quota.setMonthlyRequestLimit(3000);
        quota.setDailyTokenUsed(0L);
        quota.setMonthlyTokenUsed(0L);
        quota.setDailyRequestUsed(0);
        quota.setMonthlyRequestUsed(0);
        quota.setQuotaResetAt(LocalDateTime.now().plusDays(1).withHour(0).withMinute(0).withSecond(0));
        userQuotaMapper.insert(quota);
    }

    private void assignDefaultSubscription(String userId) {
        LambdaQueryWrapper<SubscriptionTier> q = new LambdaQueryWrapper<>();
        q.eq(SubscriptionTier::getEnabled, true).orderByAsc(SubscriptionTier::getSortOrder).last("LIMIT 1");
        SubscriptionTier freeTier = tierMapper.selectOne(q);

        if (freeTier != null) {
            UserSubscription sub = new UserSubscription();
            sub.setId(UUID.randomUUID().toString().replace("-", ""));
            sub.setUserId(userId);
            sub.setTierId(freeTier.getId());
            sub.setStatus("active");
            sub.setStartedAt(LocalDateTime.now());
            sub.setExpiresAt(null);
            subscriptionMapper.insert(sub);
        }
    }

    public SysUser getUser(String userId) {
        return userMapper.selectById(userId);
    }

    public SysUser getUserByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
    }

    public List<SysUser> listUsers(int page, int size, String keyword) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<SysUser> p =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size);
        LambdaQueryWrapper<SysUser> q = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            q.and(w -> w.like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getEmail, keyword)
                    .or().like(SysUser::getDisplayName, keyword));
        }
        q.orderByDesc(SysUser::getCreatedAt);
        return userMapper.selectPage(p, q).getRecords();
    }

    public long countUsers(String keyword) {
        LambdaQueryWrapper<SysUser> q = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            q.and(w -> w.like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getEmail, keyword));
        }
        return userMapper.selectCount(q);
    }

    @Transactional
    public SysUser updateUser(String userId, UpdateUserRequest req) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("User not found");
        }
        if (req.getDisplayName() != null) user.setDisplayName(req.getDisplayName());
        if (req.getEmail() != null) user.setEmail(req.getEmail());
        if (req.getPhone() != null) user.setPhone(req.getPhone());
        if (req.getAvatar() != null) user.setAvatar(req.getAvatar());
        userMapper.updateById(user);
        return user;
    }

    @Transactional
    public void changePassword(String userId, ChangePasswordRequest req) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("User not found");
        }
        if (!passwordEncoder.matches(req.getOldPassword(), user.getPasswordHash())) {
            throw new BizException("Old password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        userMapper.updateById(user);
        log.info("Password changed for user: {}", user.getUsername());
    }

    @Transactional
    public void enableUser(String userId) {
        SysUser user = userMapper.selectById(userId);
        if (user != null) {
            user.setStatus(1);
            userMapper.updateById(user);
        }
    }

    @Transactional
    public void disableUser(String userId) {
        SysUser user = userMapper.selectById(userId);
        if (user != null) {
            user.setStatus(0);
            userMapper.updateById(user);
        }
    }

    @Transactional
    public void deleteUser(String userId) {
        if (userId.equals(com.aiproject.aiassitant.common.SecurityUtil.getCurrentUserId())) {
            throw new BizException(409, "不能删除当前登录账号");
        }
        if (userMapper.selectById(userId) == null) throw BizException.notFound("用户不存在");
        knowledgeService.deleteAllForUser(userId);
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        List<String> sessionIds = jdbc.queryForList("SELECT id FROM chat_session WHERE user_id = ?", String.class, userId);
        for (String id : sessionIds) memoryStore.clearMemory(id);
        documentQaService.removeAllForUser(userId);
        jdbc.update("DELETE FROM chat_message WHERE session_id IN (SELECT id FROM chat_session WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM tool_call_log WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM chat_log WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM chat_session WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM token_usage_log WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM guard_log WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
        userMapper.deleteById(userId);
        userQuotaMapper.delete(new LambdaQueryWrapper<UserQuota>().eq(UserQuota::getUserId, userId));
        subscriptionMapper.delete(new LambdaQueryWrapper<UserSubscription>().eq(UserSubscription::getUserId, userId));
    }

    public UserQuota getUserQuota(String userId) {
        return tokenUsageService.getQuota(userId);
    }

    @Transactional
    public void updateUserQuota(String userId, Map<String, Object> quotaData) {
        UserQuota quota = tokenUsageService.getQuota(userId);
        if (quota == null) {
            throw new BizException("User quota not found");
        }

        if (quotaData.containsKey("dailyTokenLimit")) {
            quota.setDailyTokenLimit(((Number) quotaData.get("dailyTokenLimit")).longValue());
        }
        if (quotaData.containsKey("monthlyTokenLimit")) {
            quota.setMonthlyTokenLimit(((Number) quotaData.get("monthlyTokenLimit")).longValue());
        }
        if (quotaData.containsKey("dailyTokenUsed")) {
            quota.setDailyTokenUsed(((Number) quotaData.get("dailyTokenUsed")).longValue());
        }
        if (quotaData.containsKey("monthlyTokenUsed")) {
            quota.setMonthlyTokenUsed(((Number) quotaData.get("monthlyTokenUsed")).longValue());
        }
        if (quotaData.containsKey("dailyRequestLimit")) {
            quota.setDailyRequestLimit(((Number) quotaData.get("dailyRequestLimit")).intValue());
        }
        if (quotaData.containsKey("monthlyRequestLimit")) {
            quota.setMonthlyRequestLimit(((Number) quotaData.get("monthlyRequestLimit")).intValue());
        }
        if (quotaData.containsKey("dailyRequestUsed")) {
            quota.setDailyRequestUsed(((Number) quotaData.get("dailyRequestUsed")).intValue());
        }
        if (quotaData.containsKey("monthlyRequestUsed")) {
            quota.setMonthlyRequestUsed(((Number) quotaData.get("monthlyRequestUsed")).intValue());
        }
        if (quotaData.containsKey("quotaResetAt")) {
            String resetAt = (String) quotaData.get("quotaResetAt");
            if (resetAt != null && !resetAt.isBlank()) {
                quota.setQuotaResetAt(LocalDateTime.parse(resetAt.substring(0, 19)));
            }
        }

        userQuotaMapper.updateById(quota);
        log.info("Updated quota for user: {}", userId);
    }
}
