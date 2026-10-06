package com.aiproject.aiassitant.module.chat.service;

import com.aiproject.aiassitant.module.ai.mapper.AiModelConfigMapper;
import com.aiproject.aiassitant.module.chat.entity.UserQuota;
import com.aiproject.aiassitant.module.chat.mapper.TokenUsageLogMapper;
import com.aiproject.aiassitant.module.chat.mapper.UserQuotaMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TokenUsageQuotaTest {
    @Test void unlimitedDailyTokensDoesNotBypassMonthlyRequestLimit() {
        UserQuotaMapper mapper = mock(UserQuotaMapper.class);
        UserQuota quota = quota();
        quota.setDailyTokenLimit(-1L);
        quota.setMonthlyRequestLimit(0);
        when(mapper.selectOneForUpdate("owner")).thenReturn(quota);
        var service = new TokenUsageService(mock(TokenUsageLogMapper.class), mapper, mock(AiModelConfigMapper.class));
        assertFalse(service.checkQuota("owner"));
        assertEquals(0, quota.getDailyRequestUsed());
    }

    @Test void requestCountIsReservedBeforeTheModelCall() {
        UserQuotaMapper mapper = mock(UserQuotaMapper.class);
        UserQuota quota = quota();
        quota.setDailyRequestLimit(1);
        when(mapper.selectOneForUpdate("owner")).thenReturn(quota);
        var service = new TokenUsageService(mock(TokenUsageLogMapper.class), mapper, mock(AiModelConfigMapper.class));
        assertTrue(service.checkQuota("owner"));
        assertFalse(service.checkQuota("owner"));
        assertEquals(1, quota.getDailyRequestUsed());
    }

    private UserQuota quota() {
        UserQuota q = new UserQuota();
        q.setDailyTokenLimit(100L); q.setMonthlyTokenLimit(100L);
        q.setDailyTokenUsed(0L); q.setMonthlyTokenUsed(0L);
        q.setDailyRequestLimit(100); q.setMonthlyRequestLimit(100);
        q.setDailyRequestUsed(0); q.setMonthlyRequestUsed(0);
        q.setQuotaResetAt(LocalDateTime.now().plusDays(1));
        q.setMonthlyResetAt(LocalDate.now().withDayOfMonth(1));
        return q;
    }
}
