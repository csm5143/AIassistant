package com.aiproject.aiassitant.module.tools.controller;

import com.aiproject.aiassitant.common.R;
import com.aiproject.aiassitant.module.tools.entity.ToolRegistration;
import com.aiproject.aiassitant.module.tools.service.ToolService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Tools", description = "Tool/function calling management endpoints")
@RestController
@RequestMapping("/tools")
@RequiredArgsConstructor
public class ToolController {

    private final ToolService toolService;

    @Operation(summary = "List all registered tools")
    @GetMapping
    public R<List<ToolRegistration>> listTools() {
        return R.ok(toolService.listTools());
    }

    @Operation(summary = "List enabled tools only")
    @GetMapping("/enabled")
    public R<List<ToolRegistration>> listEnabledTools() {
        return R.ok(toolService.listEnabledTools());
    }

    @Operation(summary = "Get a tool by name")
    @GetMapping("/{name}")
    public R<ToolRegistration> getTool(@PathVariable String name) {
        ToolRegistration tool = toolService.getToolByName(name);
        return tool != null ? R.ok(tool) : R.fail("Tool not found");
    }

    @Operation(summary = "Register a new tool")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public R<ToolRegistration> createTool(@RequestBody ToolRegistration tool) {
        return R.ok(toolService.createTool(tool));
    }

    @Operation(summary = "Update a tool")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> updateTool(@PathVariable String id, @RequestBody ToolRegistration req) {
        ToolRegistration tool = toolService.listTools().stream()
                .filter(t -> t.getId().equals(id))
                .findFirst().orElse(null);
        if (tool == null) return R.fail("Tool not found");
        if (req.getName() != null) tool.setName(req.getName());
        if (req.getDescription() != null) tool.setDescription(req.getDescription());
        if (req.getSchemaJson() != null) tool.setSchemaJson(req.getSchemaJson());
        if (req.getSource() != null) tool.setSource(req.getSource());
        toolService.updateTool(tool);
        return R.ok();
    }

    @Operation(summary = "Delete a tool")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> deleteTool(@PathVariable String id) {
        toolService.deleteTool(id);
        return R.ok();
    }

    @Operation(summary = "Enable or disable a tool")
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> toggleTool(@PathVariable String id, @RequestParam boolean enabled) {
        toolService.toggleToolStatus(id, enabled);
        return R.ok();
    }

    @Operation(summary = "Test a tool execution")
    @PostMapping("/{name}/test")
    @PreAuthorize("hasRole('ADMIN')")
    public R<String> testTool(@PathVariable String name, @RequestBody java.util.Map<String, Object> params) {
        String result = toolService.executeTool(name, params);
        return R.ok(result);
    }
}
