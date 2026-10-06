package com.aiproject.aiassitant.module.admin.config;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime configuration holder loaded from sys_config table.
 * Priority: database > application.yml default.
 * Values can be hot-reloaded without app restart.
 */
@Component
public class ConfigHolder {

    private volatile Map<String, String> configMap = new ConcurrentHashMap<>();

    public void refresh(Map<String, String> newConfigs) {
        this.configMap = new ConcurrentHashMap<>(newConfigs);
    }

    /** Get config value, or default if not present. */
    public String get(String key, String defaultValue) {
        return Optional.ofNullable(configMap.get(key)).orElse(defaultValue);
    }

    /** Get config value, or null. */
    public String get(String key) {
        return configMap.get(key);
    }

    /** Get all configs (for admin UI listing). */
    public Map<String, String> getAll() {
        return Map.copyOf(configMap);
    }

    /** Update a single config value in memory. */
    public void put(String key, String value) {
        configMap.put(key, value);
    }

    public int size() {
        return configMap.size();
    }
}
