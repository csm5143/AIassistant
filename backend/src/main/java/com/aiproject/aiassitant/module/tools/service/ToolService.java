package com.aiproject.aiassitant.module.tools.service;

import com.aiproject.aiassitant.module.tools.entity.ToolRegistration;
import com.aiproject.aiassitant.module.tools.mapper.ToolRegistrationMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolService {

    private final ToolRegistrationMapper toolMapper;
    private final ObjectMapper objectMapper;

    private static final Map<String, ToolExecutor> REGISTERED_TOOLS = new LinkedHashMap<>();

    public static void registerTool(String name, ToolExecutor executor) {
        REGISTERED_TOOLS.put(name, executor);
    }

    public static ToolExecutor getTool(String name) {
        return REGISTERED_TOOLS.get(name);
    }

    public static Collection<ToolExecutor> getAllTools() {
        return REGISTERED_TOOLS.values();
    }

    public List<ToolRegistration> listTools() {
        return toolMapper.selectList(new LambdaQueryWrapper<ToolRegistration>()
                .orderByAsc(ToolRegistration::getCreatedAt));
    }

    public List<ToolRegistration> listEnabledTools() {
        return toolMapper.selectList(new LambdaQueryWrapper<ToolRegistration>()
                .eq(ToolRegistration::getStatus, true)
                .orderByAsc(ToolRegistration::getCreatedAt));
    }

    public ToolRegistration getToolByName(String name) {
        return toolMapper.selectOne(new LambdaQueryWrapper<ToolRegistration>()
                .eq(ToolRegistration::getName, name));
    }

    public ToolRegistration createTool(ToolRegistration tool) {
        tool.setStatus(true);
        tool.setSource(tool.getSource() != null ? tool.getSource() : "BUILTIN");
        ToolRegistration existing = getToolByName(tool.getName());
        if (existing != null) {
            tool.setId(existing.getId());
            toolMapper.updateById(tool);
        } else {
            toolMapper.insert(tool);
        }
        return tool;
    }

    public void updateTool(ToolRegistration tool) {
        toolMapper.updateById(tool);
    }

    public void deleteTool(String id) {
        toolMapper.deleteById(id);
    }

    public void toggleToolStatus(String id, boolean enabled) {
        ToolRegistration tool = toolMapper.selectById(id);
        if (tool != null) {
            tool.setStatus(enabled);
            toolMapper.updateById(tool);
        }
    }

    public String executeTool(String toolName, Map<String, Object> params) {
        ToolExecutor executor = REGISTERED_TOOLS.get(toolName);
        if (executor == null) {
            throw new RuntimeException("Tool not found: " + toolName);
        }
        return executor.execute(params);
    }

    public interface ToolExecutor {
        String execute(Map<String, Object> params);
        String getDescription();
        Map<String, String> getParameters();
    }
}
