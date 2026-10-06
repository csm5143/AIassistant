package com.aiproject.aiassitant.module.chat.controller;

import com.aiproject.aiassitant.common.R;
import com.aiproject.aiassitant.module.chat.entity.ToolCallLog;
import com.aiproject.aiassitant.module.chat.mapper.ToolCallLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin/tool-logs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ToolCallLogController {

    private final ToolCallLogMapper mapper;

    @GetMapping
    public R<Page<ToolCallLog>> list(@RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size,
                                      @RequestParam(required = false) String sessionId,
                                      @RequestParam(required = false) String toolName,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String startDate,
                                      @RequestParam(required = false) String endDate) {
        LambdaQueryWrapper<ToolCallLog> q = new LambdaQueryWrapper<>();
        if (sessionId  != null && !sessionId.isBlank())  q.eq(ToolCallLog::getSessionId, sessionId);
        if (toolName   != null && !toolName.isBlank())   q.eq(ToolCallLog::getToolName, toolName);
        if (status     != null && !status.isBlank())     q.eq(ToolCallLog::getStatus, status);
        if (startDate  != null && !startDate.isBlank())  q.ge(ToolCallLog::getCreatedAt, startDate + "T00:00:00");
        if (endDate    != null && !endDate.isBlank())    q.le(ToolCallLog::getCreatedAt, endDate + "T23:59:59");
        q.orderByDesc(ToolCallLog::getCreatedAt);
        return R.ok(mapper.selectPage(new Page<>(page, size), q));
    }

    @GetMapping("/{id}")
    public R<ToolCallLog> detail(@PathVariable String id) {
        return R.ok(mapper.selectById(id));
    }
}
