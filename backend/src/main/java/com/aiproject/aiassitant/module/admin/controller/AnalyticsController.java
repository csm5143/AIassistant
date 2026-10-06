package com.aiproject.aiassitant.module.admin.controller;

import com.aiproject.aiassitant.common.R;
import com.aiproject.aiassitant.module.guard.entity.GuardLog;
import com.aiproject.aiassitant.module.guard.mapper.GuardLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Lightweight analytics for guest visits and page views.
 * All events are stored in guard_log for unified audit.
 */
@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final GuardLogMapper guardLogMapper;

    @PostMapping("/visit")
    public R<Void> recordVisit(@RequestBody(required = false) Map<String, Object> body) {
        GuardLog log = new GuardLog();
        log.setUserId(body != null && body.containsKey("userId") ? (String) body.get("userId") : "anonymous");
        log.setDirection("ACCESS");
        log.setStage(body != null && body.containsKey("page") ? (String) body.get("page") : "chat");
        log.setRule("PAGE_VIEW");
        log.setAction("ALLOW");
        log.setMatchedContent(body != null && body.containsKey("referrer") ? (String) body.get("referrer") : "direct");
        guardLogMapper.insert(log);
        return R.ok();
    }
}
