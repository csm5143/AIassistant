package com.aiproject.aiassitant.module.admin.controller;

import com.aiproject.aiassitant.common.R;
import com.aiproject.aiassitant.module.admin.config.ConfigHolder;
import com.aiproject.aiassitant.module.admin.config.ConfigLoader;
import com.aiproject.aiassitant.module.admin.entity.SysConfig;
import com.aiproject.aiassitant.module.admin.mapper.SysConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/admin/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SysConfigMapper configMapper;
    private final ConfigHolder configHolder;
    private final ConfigLoader configLoader;

    @GetMapping("/branding")
    public R<Map<String, String>> getBranding() {
        Map<String, String> b = new LinkedHashMap<>();
        b.put("platformName", getVal("branding.platform.name", "Observatory"));
        b.put("platformSubtitle", getVal("branding.platform.subtitle", "企业级 AI 助手平台"));
        b.put("sidebarTitle", getVal("branding.sidebar.title", "AI 助手"));
        b.put("favicon", getVal("branding.favicon", "/vite.svg"));
        b.put("primaryColor", getVal("branding.color.primary", "#c5f28b"));
        b.put("logoUrl", getVal("branding.logo.url", ""));
        b.put("logoType", getVal("branding.logo.type", "observatory"));
        return R.ok(b);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/branding")
    public R<Void> updateBranding(@RequestBody Map<String, String> body) {
        for (Map.Entry<String, String> e : body.entrySet()) {
            String key = "branding." + e.getKey();
            saveVal(key, e.getValue());
        }
        return R.ok();
    }

    @GetMapping("/public")
    public R<Map<String, String>> getPublicBranding() {
        Map<String, String> b = new LinkedHashMap<>();
        b.put("platformName", getVal("branding.platform.name", "Observatory"));
        b.put("platformSubtitle", getVal("branding.platform.subtitle", "企业级 AI 助手平台"));
        b.put("sidebarTitle", getVal("branding.sidebar.title", "AI 助手"));
        b.put("favicon", getVal("branding.favicon", "/vite.svg"));
        b.put("primaryColor", getVal("branding.color.primary", "#c5f28b"));
        b.put("logoUrl", getVal("branding.logo.url", ""));
        return R.ok(b);
    }

    private String getVal(String key, String def) {
        SysConfig row = configMapper.selectOne(
                new LambdaQueryWrapper<SysConfig>().eq(SysConfig::getConfigKey, key));
        return row != null && row.getConfigValue() != null ? row.getConfigValue() : def;
    }

    private void saveVal(String key, String val) {
        SysConfig row = configMapper.selectOne(
                new LambdaQueryWrapper<SysConfig>().eq(SysConfig::getConfigKey, key));
        if (row != null) {
            row.setConfigValue(val);
            configMapper.updateById(row);
        } else {
            row = new SysConfig();
            row.setId(UUID.randomUUID().toString().replace("-", "").substring(0, 32));
            row.setConfigKey(key);
            row.setConfigValue(val);
            configMapper.insert(row);
        }
        log.info("Config saved: {} = {}", key, val);
    }
}
