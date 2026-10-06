package com.aiproject.aiassitant.module.chat.service;

import com.aiproject.aiassitant.module.ai.entity.AiModelConfig;
import com.aiproject.aiassitant.module.ai.mapper.AiModelConfigMapper;
import com.aiproject.aiassitant.module.chat.entity.TokenUsageLog;
import com.aiproject.aiassitant.module.chat.entity.UserQuota;
import com.aiproject.aiassitant.module.chat.mapper.TokenUsageLogMapper;
import com.aiproject.aiassitant.module.chat.mapper.UserQuotaMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenUsageService {

    private final TokenUsageLogMapper tokenUsageLogMapper;
    private final UserQuotaMapper userQuotaMapper;
    private final AiModelConfigMapper modelConfigMapper;

    @Transactional
    public void recordUsage(String userId, String modelName, long promptTokens, long completionTokens) {
        LocalDate today = LocalDate.now();
        tokenUsageLogMapper.addUsage(java.util.UUID.randomUUID().toString().replace("-", ""),
                userId, today, modelName, promptTokens, completionTokens,
                promptTokens + completionTokens, calculateCost(modelName, promptTokens, completionTokens));
        updateUserQuotaUsage(userId, promptTokens + completionTokens);
    }

    private void updateUserQuotaUsage(String userId, long tokensUsed) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime resetBoundary = now.plusDays(1).withHour(0).withMinute(0).withSecond(0);

        // SELECT ... FOR UPDATE to prevent concurrent quota bypass
        UserQuota quota = userQuotaMapper.selectOneForUpdate(userId);

        if (quota == null) {
            quota = createDefaultQuota(userId);
            userQuotaMapper.insert(quota);
            quota = userQuotaMapper.selectOneForUpdate(userId); // re-fetch for lock
        }
        if (quota == null) { quota = createDefaultQuota(userId); }

        // Check and handle daily reset
        if (quota.getQuotaResetAt() == null || now.isAfter(quota.getQuotaResetAt())) {
            quota.setDailyTokenUsed(0L);
            quota.setDailyRequestUsed(0);
            quota.setQuotaResetAt(resetBoundary);
        }
        // Check and handle monthly reset
        LocalDate monthStart = now.toLocalDate().withDayOfMonth(1);
        if (quota.getMonthlyResetAt() == null || quota.getMonthlyResetAt().isBefore(monthStart)) {
            quota.setMonthlyTokenUsed(0L);
            quota.setMonthlyRequestUsed(0);
            quota.setMonthlyResetAt(monthStart);
        }

        // Increment AFTER reset check (fixes H11: no more lost usage on boundary)
        quota.setDailyTokenUsed(quota.getDailyTokenUsed() + tokensUsed);
        quota.setMonthlyTokenUsed(quota.getMonthlyTokenUsed() + tokensUsed);

        userQuotaMapper.updateById(quota);
    }

    private UserQuota createDefaultQuota(String userId) {
        UserQuota quota = new UserQuota();
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
        quota.setQuotaResetAt(LocalDateTime.now().plusDays(1).withHour(0).withMinute(0));
        quota.setMonthlyResetAt(LocalDate.now().withDayOfMonth(1));
        return quota;
    }

    private BigDecimal calculateCost(String modelName, long promptTokens, long completionTokens) {
        LambdaQueryWrapper<AiModelConfig> q = new LambdaQueryWrapper<>();
        q.eq(AiModelConfig::getEnabled, true)
                .and(w -> w.eq(AiModelConfig::getName, modelName)
                        .or().eq(AiModelConfig::getModelName, modelName))
                .orderByDesc(AiModelConfig::getIsDefault)
                .orderByAsc(AiModelConfig::getSortOrder)
                .last("LIMIT 1");
        AiModelConfig model = modelConfigMapper.selectOne(q);
        if (model == null || model.getPricePer1kInput() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal promptCost = model.getPricePer1kInput()
                .multiply(BigDecimal.valueOf(promptTokens))
                .divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP);
        BigDecimal completionCost = model.getPricePer1kOutput()
                .multiply(BigDecimal.valueOf(completionTokens))
                .divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP);
        return promptCost.add(completionCost);
    }

    @Transactional
    public boolean checkQuota(String userId) {
        UserQuota quota = userQuotaMapper.selectOneForUpdate(userId);
        if (quota == null) {
            quota = createDefaultQuota(userId);
            userQuotaMapper.insert(quota);
        }
        LocalDateTime now = LocalDateTime.now();
        if (quota.getQuotaResetAt() == null || !now.isBefore(quota.getQuotaResetAt())) {
            quota.setDailyTokenUsed(0L);
            quota.setDailyRequestUsed(0);
            quota.setQuotaResetAt(now.toLocalDate().plusDays(1).atStartOfDay());
        }
        LocalDate monthStart = now.toLocalDate().withDayOfMonth(1);
        if (quota.getMonthlyResetAt() == null || quota.getMonthlyResetAt().isBefore(monthStart)) {
            quota.setMonthlyTokenUsed(0L);
            quota.setMonthlyRequestUsed(0);
            quota.setMonthlyResetAt(monthStart);
        }
        boolean allowed = under(quota.getDailyTokenUsed(), quota.getDailyTokenLimit())
                && under(quota.getMonthlyTokenUsed(), quota.getMonthlyTokenLimit())
                && under(quota.getDailyRequestUsed(), quota.getDailyRequestLimit())
                && under(quota.getMonthlyRequestUsed(), quota.getMonthlyRequestLimit());
        if (allowed) {
            quota.setDailyRequestUsed(quota.getDailyRequestUsed() + 1);
            quota.setMonthlyRequestUsed(quota.getMonthlyRequestUsed() + 1);
        }
        userQuotaMapper.updateById(quota);
        return allowed;
    }

    private boolean under(Number used, Number limit) {
        return limit != null && (limit.longValue() < 0 || used.longValue() < limit.longValue());
    }

    @Transactional
    public UserQuota getQuota(String userId) {
        UserQuota quota = userQuotaMapper.selectOneForUpdate(userId);
        if (quota == null) return createDefaultQuota(userId);
        LocalDateTime now = LocalDateTime.now();
        boolean changed = false;
        if (quota.getQuotaResetAt() == null || !now.isBefore(quota.getQuotaResetAt())) {
            quota.setDailyTokenUsed(0L);
            quota.setDailyRequestUsed(0);
            quota.setQuotaResetAt(now.toLocalDate().plusDays(1).atStartOfDay());
            changed = true;
        }
        LocalDate monthStart = now.toLocalDate().withDayOfMonth(1);
        if (quota.getMonthlyResetAt() == null || quota.getMonthlyResetAt().isBefore(monthStart)) {
            quota.setMonthlyTokenUsed(0L);
            quota.setMonthlyRequestUsed(0);
            quota.setMonthlyResetAt(monthStart);
            changed = true;
        }
        if (changed) userQuotaMapper.updateById(quota);
        return quota;
    }
}
