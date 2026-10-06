package com.aiproject.aiassitant.module.admin.controller;

import com.aiproject.aiassitant.common.R;
import com.aiproject.aiassitant.module.chat.entity.ChatLog;
import com.aiproject.aiassitant.module.chat.entity.ChatSession;
import com.aiproject.aiassitant.module.chat.entity.ToolCallLog;
import com.aiproject.aiassitant.module.chat.entity.TokenUsageLog;
import com.aiproject.aiassitant.module.chat.mapper.TokenUsageLogMapper;
import com.aiproject.aiassitant.module.guard.entity.GuardLog;
import com.aiproject.aiassitant.module.guard.mapper.GuardLogMapper;
import com.aiproject.aiassitant.module.chat.mapper.ChatLogMapper;
import com.aiproject.aiassitant.module.chat.mapper.ChatSessionMapper;
import com.aiproject.aiassitant.module.chat.mapper.ToolCallLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Tag(name = "Admin", description = "Admin dashboard and statistics")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ChatLogMapper chatLogMapper;
    private final ChatSessionMapper sessionMapper;
    private final GuardLogMapper guardLogMapper;
    private final ToolCallLogMapper toolCallLogMapper;
    private final TokenUsageLogMapper tokenUsageLogMapper;

    // These mappers depend on tables that may not exist yet — guarded with null checks

    @Operation(summary = "Dashboard stats with trends and model distribution")
    @GetMapping("/dashboard/stats")
    public R<Map<String, Object>> dashboardStats() {
        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();

        long todaySessions = sessionMapper.selectCount(
                new LambdaQueryWrapper<ChatSession>()
                        .ge(ChatSession::getCreatedAt, todayStart));

        long todayChats = chatLogMapper.selectCount(
                new LambdaQueryWrapper<ChatLog>()
                        .ge(ChatLog::getCreatedAt, todayStart));

        long todayGuardHits = guardLogMapper.selectCount(
                new LambdaQueryWrapper<GuardLog>()
                        .ge(GuardLog::getCreatedAt, todayStart)
                        .eq(GuardLog::getAction, "BLOCK"));

        Long totalSessions = sessionMapper.selectCount(null);
        Long totalMessages = chatLogMapper.selectCount(null);
        Long totalGuardHits = guardLogMapper.selectCount(
                new LambdaQueryWrapper<GuardLog>().eq(GuardLog::getAction, "BLOCK"));

        // Active users today (count distinct userId in chat_log)
        Long todayActiveUsers = Long.valueOf(chatLogMapper.selectList(
                new LambdaQueryWrapper<ChatLog>()
                        .select(ChatLog::getUserId)
                        .ge(ChatLog::getCreatedAt, todayStart))
                .stream()
                .map(ChatLog::getUserId)
                .distinct()
                .count());

        // 7-day session trend
        int[] sessionTrend = new int[7];
        int[] chatTrend = new int[7];
        String[] trendLabels = new String[7];
        for (int i = 6; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            trendLabels[6 - i] = (day.getMonthValue() + "/" + day.getDayOfMonth());
            sessionTrend[6 - i] = sessionMapper.selectCount(
                    new LambdaQueryWrapper<ChatSession>()
                            .ge(ChatSession::getCreatedAt, day.atStartOfDay())
                            .lt(ChatSession::getCreatedAt, day.plusDays(1).atStartOfDay())).intValue();
            chatTrend[6 - i] = chatLogMapper.selectCount(
                    new LambdaQueryWrapper<ChatLog>()
                            .ge(ChatLog::getCreatedAt, day.atStartOfDay())
                            .lt(ChatLog::getCreatedAt, day.plusDays(1).atStartOfDay())).intValue();
        }

        // Model distribution from chat_log
        List<Map<String, Object>> modelDist = chatLogMapper.selectList(
                Wrappers.<ChatLog>lambdaQuery().isNotNull(ChatLog::getModel).select(ChatLog::getModel))
                .stream()
                .filter(l -> l.getModel() != null)
                .collect(Collectors.groupingBy(ChatLog::getModel, Collectors.counting()))
                .entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .sorted((a, b) -> Long.compare((Long) b.get("count"), (Long) a.get("count")))
                .collect(Collectors.toList());

        // Monthly chat count
        Long thisMonthChats = chatLogMapper.selectCount(
                new LambdaQueryWrapper<ChatLog>()
                        .ge(ChatLog::getCreatedAt, today.withDayOfMonth(1).atStartOfDay()));

        Map<String, Object> stats = new HashMap<>();
        stats.put("todaySessions", todaySessions);
        stats.put("todayChats", todayChats);
        stats.put("todayGuardHits", todayGuardHits);
        stats.put("todayActiveUsers", todayActiveUsers != null ? todayActiveUsers : 0);
        stats.put("totalSessions", totalSessions);
        stats.put("totalMessages", totalMessages);
        stats.put("totalGuardHits", totalGuardHits);
        stats.put("thisMonthChats", thisMonthChats);
        stats.put("sessionTrend", sessionTrend);
        stats.put("chatTrend", chatTrend);
        stats.put("trendLabels", trendLabels);
        stats.put("modelDistribution", modelDist);

        // ── Tool call stats (graceful if table does not exist) ──
        long todayToolCalls = 0, todayToolSuccess = 0;
        Map<String, Long> toolByName = Map.of();
        int[] toolTrend = new int[7];
        try {
            todayToolCalls = toolCallLogMapper.selectCount(
                    new LambdaQueryWrapper<ToolCallLog>().ge(ToolCallLog::getCreatedAt, todayStart));
            todayToolSuccess = toolCallLogMapper.selectCount(
                    new LambdaQueryWrapper<ToolCallLog>()
                            .ge(ToolCallLog::getCreatedAt, todayStart).eq(ToolCallLog::getStatus, "success"));
            toolByName = toolCallLogMapper.selectList(
                    new LambdaQueryWrapper<ToolCallLog>()
                            .ge(ToolCallLog::getCreatedAt, todayStart).select(ToolCallLog::getToolName))
                    .stream().collect(Collectors.groupingBy(ToolCallLog::getToolName, Collectors.counting()));
            for (int i = 6; i >= 0; i--) {
                LocalDate day = today.minusDays(i);
                toolTrend[6 - i] = toolCallLogMapper.selectCount(
                        new LambdaQueryWrapper<ToolCallLog>()
                                .ge(ToolCallLog::getCreatedAt, day.atStartOfDay())
                                .lt(ToolCallLog::getCreatedAt, day.plusDays(1).atStartOfDay())).intValue();
            }
        } catch (Exception e) { log.debug("Tool call stats unavailable: {}", e.getMessage()); }

        stats.put("toolByType", toolByName);
        stats.put("todayToolCalls", todayToolCalls);
        stats.put("todayToolSuccess", todayToolSuccess);
        stats.put("toolTrend", toolTrend);

        // API type distribution
        Map<String, String> toolToApiType = Map.of(
            "webSearch", "Search搜索", "knowledgeSearch", "Embedding嵌入",
            "imageRecognition", "Vision视觉", "ocrExtract", "OCR识别",
            "calculator", "LLM对话", "currentTime", "LLM对话", "summarizeUrl", "LLM对话"
        );
        Map<String, Long> apiTypeDist = new LinkedHashMap<>();
        for (var e : toolByName.entrySet()) {
            apiTypeDist.merge(toolToApiType.getOrDefault(e.getKey(), "LLM对话"), e.getValue(), Long::sum);
        }
        for (String t : List.of("LLM对话", "Embedding嵌入", "Search搜索", "Vision视觉", "OCR识别"))
            apiTypeDist.putIfAbsent(t, 0L);
        stats.put("apiTypeDistribution", apiTypeDist);

        // ── Token usage (graceful if table does not exist) ──
        long todayPromptTokens = 0, todayCompletionTokens = 0;
        java.math.BigDecimal todayCost = java.math.BigDecimal.ZERO;
        int[] tokenTrend = new int[7];
        try {
            List<TokenUsageLog> todayTokens = tokenUsageLogMapper.selectList(
                    new LambdaQueryWrapper<TokenUsageLog>().eq(TokenUsageLog::getDateKey, today));
            for (TokenUsageLog t : todayTokens) {
                todayPromptTokens += t.getPromptTokens() != null ? t.getPromptTokens() : 0;
                todayCompletionTokens += t.getCompletionTokens() != null ? t.getCompletionTokens() : 0;
                todayCost = todayCost.add(t.getCostUsd() != null ? t.getCostUsd() : java.math.BigDecimal.ZERO);
            }
            for (int i = 6; i >= 0; i--) {
                LocalDate day = today.minusDays(i);
                List<TokenUsageLog> dayTokens = tokenUsageLogMapper.selectList(
                        new LambdaQueryWrapper<TokenUsageLog>().eq(TokenUsageLog::getDateKey, day));
                tokenTrend[6 - i] = dayTokens.stream().mapToInt(t -> t.getTotalTokens() != null ? t.getTotalTokens().intValue() : 0).sum();
            }
        } catch (Exception e) { log.debug("Token stats unavailable: {}", e.getMessage()); }
        stats.put("todayPromptTokens", todayPromptTokens);
        stats.put("todayCompletionTokens", todayCompletionTokens);
        stats.put("todayCost", todayCost);
        stats.put("tokenTrend", tokenTrend);

        // ── Recent chat logs ──
        List<Map<String, Object>> recentChats = chatLogMapper.selectList(
                new LambdaQueryWrapper<ChatLog>()
                        .orderByDesc(ChatLog::getCreatedAt).last("LIMIT 10"))
                .stream().map(log -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", log.getId());
                    m.put("userName", log.getUserId());
                    m.put("question", log.getQuestion() != null && log.getQuestion().length() > 50
                            ? log.getQuestion().substring(0, 50) + "..." : log.getQuestion());
                    m.put("model", log.getModel());
                    m.put("latencyMs", log.getLatencyMs());
                    m.put("createdAt", log.getCreatedAt());
                    return m;
                }).collect(Collectors.toList());
        stats.put("recentChats", recentChats);

        return R.ok(stats);
    }

    @Operation(summary = "Paginated chat log list")
    @GetMapping("/chat-logs")
    public R<Page<ChatLog>> listChatLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        Page<ChatLog> p = new Page<>(page, size);
        LambdaQueryWrapper<ChatLog> q = new LambdaQueryWrapper<>();
        if (userId != null && !userId.isBlank()) q.eq(ChatLog::getUserId, userId);
        if (startDate != null) q.ge(ChatLog::getCreatedAt, startDate.atStartOfDay());
        if (endDate != null) q.le(ChatLog::getCreatedAt, endDate.plusDays(1).atStartOfDay());
        q.orderByDesc(ChatLog::getCreatedAt);
        return R.ok(chatLogMapper.selectPage(p, q));
    }

    @Operation(summary = "Paginated guard/audit log list (supports INPUT, OUTPUT, LOGIN, REGISTER, ACCESS)")
    @GetMapping("/guard-logs")
    public R<Page<GuardLog>> listGuardLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String stage) {
        Page<GuardLog> p = new Page<>(page, size);
        LambdaQueryWrapper<GuardLog> q = new LambdaQueryWrapper<>();
        if (direction != null && !direction.isBlank()) q.eq(GuardLog::getDirection, direction);
        if (stage != null && !stage.isBlank()) q.eq(GuardLog::getStage, stage);
        q.orderByDesc(GuardLog::getCreatedAt);
        return R.ok(guardLogMapper.selectPage(p, q));
    }

    @Operation(summary = "Daily chat volume for the past N days")
    @GetMapping("/stats/daily")
    public R<Map<String, Object>> dailyStats(@RequestParam(defaultValue = "7") int days) {
        Map<String, Object> result = new HashMap<>();
        result.put("periodDays", days);
        long total = chatLogMapper.selectCount(null);
        result.put("totalConversations", total);
        return R.ok(result);
    }
}
