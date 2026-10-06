package com.aiproject.aiassitant.module.ai.controller;

import com.aiproject.aiassitant.common.R;
import com.aiproject.aiassitant.module.ai.service.ExaSearchService;
import com.aiproject.aiassitant.module.ai.service.McpToolProvider;
import com.aiproject.aiassitant.module.ai.service.McpToolProvider.McpToolHandle;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin endpoints for managing external MCP tools and skills (agent-reach, Exa, etc.).
 */
@Tag(name = "MCP Admin", description = "External MCP / Skills management")
@RestController
@RequestMapping("/admin/mcp")
@RequiredArgsConstructor
public class McpAdminController {

    private final McpToolProvider mcpToolProvider;
    private final ExaSearchService exaSearchService;

    @Operation(summary = "Get MCP/skills status — discovered tools, Exa health")
    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Map<String, Object>> status() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Exa search health
        result.put("exaEnabled", exaSearchService != null);
        result.put("exaHealthy", exaSearchService.isHealthy());

        // Discovered tools
        List<String> toolNames = mcpToolProvider.listToolNames();
        result.put("mcpToolCount", toolNames.size());
        result.put("mcpTools", toolNames);

        // Tool details
        Map<String, Map<String, Object>> toolDetails = new LinkedHashMap<>();
        for (var entry : mcpToolProvider.getToolHandles().entrySet()) {
            McpToolHandle handle = entry.getValue();
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("selector", handle.toolSelector());
            detail.put("serverUrl", handle.serverUrl());
            detail.put("isDirect", handle.isDirect());
            detail.put("description", handle.spec().description());
            toolDetails.put(entry.getKey(), detail);
        }
        result.put("toolDetails", toolDetails);

        return R.ok(result);
    }

    @Operation(summary = "Force refresh MCP tool discovery")
    @PostMapping("/refresh")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Map<String, Object>> refresh() {
        mcpToolProvider.refreshTools();
        return status();
    }

    @Operation(summary = "Test Exa search directly")
    @PostMapping("/test-search")
    @PreAuthorize("hasRole('ADMIN')")
    public R<String> testSearch(@RequestBody Map<String, String> body) {
        String query = body.getOrDefault("query", "test");
        int numResults = Integer.parseInt(body.getOrDefault("numResults", "5"));
        String type = body.getOrDefault("type", "auto");
        String result = exaSearchService.search(query, numResults, type);
        return R.ok(result);
    }
}
