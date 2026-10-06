package com.aiproject.aiassitant.module.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.JsonSchemaProperty;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Dynamic LangChain4j ToolSpecification provider that discovers tools from
 * mcporter-managed MCP servers (Exa, etc.) and converts them into
 * ToolSpecifications the LLM can call via function calling.
 *
 * Discovery channels (tried in order):
 *   1. mcporter CLI: {@code mcporter list --json --schema}
 *   2. Direct MCP HTTP: POST tools/list to each server URL
 */
@Slf4j
@Component
public class McpToolProvider {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Map<String, McpToolHandle> toolHandles = new ConcurrentHashMap<>();
    private volatile List<ToolSpecification> cachedSpecs = List.of();

    @Value("${ai.mcp.auto-discover:true}")
    private boolean autoDiscover;

    @Value("${ai.mcp.mcporter-timeout-seconds:20}")
    private int mcporterTimeoutSec;

    @Value("${ai.search.mcporter.config-path:}")
    private String mcporterConfigPath;

    /** mcporter JSON list of servers with tools */
    private record McpServer(String name, String url, List<McpToolDef> tools) {}

    /** Single tool definition from mcporter */
    private record McpToolDef(String name, String description, Map<String, Object> inputSchema,
                              String serverName, String serverUrl) {}

    /** Resolved tool handle — callable at runtime */
    public record McpToolHandle(String toolSelector,    // e.g. "exa.web_search_exa"
                                String serverUrl,       // MCP endpoint URL
                                ToolSpecification spec,
                                boolean isDirect) {}    // true → HTTP, false → CLI

    public McpToolProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    // ═══════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════

    /** Get all discovered tool specifications for LangChain4j function calling. */
    public List<ToolSpecification> getToolSpecifications() {
        return cachedSpecs;
    }

    /** Get all resolved tool handles (for execution routing). */
    public Map<String, McpToolHandle> getToolHandles() {
        return Collections.unmodifiableMap(toolHandles);
    }

    /** Get a specific tool handle by name. */
    public Optional<McpToolHandle> getToolHandle(String toolName) {
        return Optional.ofNullable(toolHandles.get(toolName));
    }

    /** Force re-discovery of tools (called on startup + schedule). */
    @Scheduled(fixedDelayString = "${ai.mcp.refresh-interval-ms:600000}",
               initialDelayString = "${ai.mcp.initial-delay-ms:15000}")
    public void refreshTools() {
        if (!autoDiscover) return;

        log.debug("Discovering mcporter MCP tools...");
        List<McpServer> servers = discoverServers();
        if (servers.isEmpty()) {
            log.debug("No mcporter MCP servers found");
            return;
        }

        Map<String, McpToolHandle> newHandles = new LinkedHashMap<>();
        List<ToolSpecification> newSpecs = new ArrayList<>();

        for (McpServer server : servers) {
            for (McpToolDef tool : server.tools()) {
                String fullName = server.name() + "." + tool.name();
                // DeepSeek/OpenAI require ^[a-zA-Z0-9_-]+$ — dots not allowed
                String safeName = fullName.replace('.', '_');
                ToolSpecification spec = toToolSpecification(safeName, tool);
                McpToolHandle handle = new McpToolHandle(
                        fullName, server.url(), spec, false);
                // Indexed by BOTH names so lookup works regardless of which name the LLM uses
                newHandles.put(fullName, handle);
                newHandles.put(safeName, handle);
                newSpecs.add(spec);
            }
        }

        if (!newHandles.isEmpty()) {
            toolHandles.clear();
            toolHandles.putAll(newHandles);
            cachedSpecs = List.copyOf(newSpecs);
            log.info("Discovered {} MCP tools across {} servers", newSpecs.size(), servers.size());
        }
    }

    /** List currently discovered tool names (for admin/debug). */
    public List<String> listToolNames() {
        return new ArrayList<>(toolHandles.keySet());
    }

    /** Execute a discovered MCP tool by name (accepts both dot and underscore forms). */
    public String execute(String toolName, Map<String, Object> arguments) {
        McpToolHandle handle = toolHandles.get(toolName);
        if (handle == null) {
            return "工具未找到: " + toolName;
        }
        // Use original dot-separated toolSelector for execution (mcporter needs it)
        String originalName = handle.toolSelector();
        if (originalName != null && originalName.startsWith("exa.")) {
            return executeViaMcpDirect(handle.serverUrl(), originalName, arguments);
        }
        return executeViaMcporterCli(originalName, arguments);
    }

    // ═══════════════════════════════════════════════════
    // Discovery
    // ═══════════════════════════════════════════════════

    private List<McpServer> discoverServers() {
        // Try mcporter CLI first
        List<McpServer> servers = discoverViaMcporter();
        if (!servers.isEmpty()) return servers;

        // Fallback: discover via direct HTTP to known servers
        return discoverDirectFallback();
    }

    private List<McpServer> discoverViaMcporter() {
        try {
            List<String> listCmd = mcporterCmd("list", "--json");
            ProcessBuilder pb = new ProcessBuilder(listCmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(mcporterTimeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return List.of();
            }
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (p.exitValue() != 0) return List.of();

            // mcporter list --json returns an array of server objects
            List<McpServer> servers = new ArrayList<>();
            JsonNode root = objectMapper.readTree(out);
            if (!root.isArray()) return List.of();

            for (JsonNode serverNode : root) {
                String name = serverNode.get("name").asText("");
                String url = serverNode.has("url") ? serverNode.get("url").asText("") : "";
                if (name.isBlank()) continue;

                // Fetch tools for each server
                List<McpToolDef> tools = discoverToolsViaMcporter(name);
                if (tools.isEmpty()) {
                    tools = discoverToolsDirect(url);
                }
                servers.add(new McpServer(name, url, tools));
            }
            return servers;
        } catch (Exception e) {
            log.debug("mcporter discovery failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<McpToolDef> discoverToolsViaMcporter(String serverName) {
        try {
            List<String> schemaCmd = mcporterCmd("list", serverName, "--schema", "--json");
            ProcessBuilder pb = new ProcessBuilder(schemaCmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return List.of();
            }
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (p.exitValue() != 0) return List.of();

            JsonNode root = objectMapper.readTree(out);
            return parseMcporterToolList(root, serverName, "");
        } catch (Exception e) {
            log.debug("mcporter tool discovery failed for {}: {}", serverName, e.getMessage());
            return List.of();
        }
    }

    /** Direct JSON-RPC tools/list to an MCP server URL. */
    private List<McpToolDef> discoverToolsDirect(String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) return List.of();
        try {
            ObjectNode req = objectMapper.createObjectNode();
            req.put("jsonrpc", "2.0");
            req.put("id", 1);
            req.put("method", "tools/list");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(req)))
                    .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return List.of();

            JsonNode root = objectMapper.readTree(resp.body());
            return parseJsonRpcToolList(root, "", serverUrl);
        } catch (Exception e) {
            log.debug("Direct MCP tools/list failed for {}: {}", serverUrl, e.getMessage());
            return List.of();
        }
    }

    /** Hard-coded fallback for known servers (when discovery fails). */
    private List<McpServer> discoverDirectFallback() {
        List<McpServer> servers = new ArrayList<>();
        // Exa — hard-coded schema as fallback
        Map<String, Object> exaSchema = new LinkedHashMap<>();
        exaSchema.put("type", "object");
        Map<String, Object> exaProps = new LinkedHashMap<>();
        exaProps.put("query", Map.of("type", "string", "description", "搜索关键词"));
        exaProps.put("numResults", Map.of("type", "integer", "description", "返回结果数量，默认10"));
        exaProps.put("type", Map.of("type", "string", "description", "搜索类型：auto/neural/keyword"));
        exaSchema.put("properties", exaProps);
        exaSchema.put("required", List.of("query"));

        McpToolDef exaSearchTool = new McpToolDef(
                "web_search_exa",
                "【全网语义搜索 - 优先使用】使用 Exa 搜索引擎进行语义搜索。适用于：最新资讯、实时信息、新闻、事实查询、任何需要最新互联网信息的场景。搜索词使用自然语言即可。",
                exaSchema,
                "exa",
                "https://mcp.exa.ai/mcp"
        );
        servers.add(new McpServer("exa", "https://mcp.exa.ai/mcp", List.of(exaSearchTool)));
        log.debug("Using hard-coded Exa search tool schema as fallback");
        return servers;
    }

    // ═══════════════════════════════════════════════════
    // Parsing
    // ═══════════════════════════════════════════════════

    private List<McpToolDef> parseMcporterToolList(JsonNode root, String serverName, String serverUrl) {
        List<McpToolDef> tools = new ArrayList<>();
        JsonNode toolsNode = root.has("tools") ? root.get("tools") : root;
        if (!toolsNode.isArray()) return tools;

        for (JsonNode t : toolsNode) {
            String name = t.has("name") ? t.get("name").asText() : "";
            String desc = t.has("description") ? t.get("description").asText() : "";
            Map<String, Object> schema = parseSchema(t.has("inputSchema") ? t.get("inputSchema") : t.get("schema"));
            if (!name.isBlank()) {
                tools.add(new McpToolDef(name, desc, schema, serverName, serverUrl));
            }
        }
        return tools;
    }

    private List<McpToolDef> parseJsonRpcToolList(JsonNode root, String serverName, String serverUrl) {
        List<McpToolDef> tools = new ArrayList<>();
        JsonNode toolsNode = root.at("/result/tools");
        if (!toolsNode.isArray()) return tools;

        for (JsonNode t : toolsNode) {
            String name = t.has("name") ? t.get("name").asText() : "";
            String desc = t.has("description") ? t.get("description").asText() : "";
            Map<String, Object> schema = parseSchema(t.has("inputSchema") ? t.get("inputSchema") : null);
            if (!name.isBlank()) {
                tools.add(new McpToolDef(name, desc, schema, serverName, serverUrl));
            }
        }
        return tools;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSchema(JsonNode schemaNode) {
        if (schemaNode == null || schemaNode.isNull()) return Map.of("type", "object", "properties", Map.of());
        try {
            return objectMapper.convertValue(schemaNode, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of("type", "object", "properties", Map.of());
        }
    }

    // ═══════════════════════════════════════════════════
    // Conversion: McpToolDef → ToolSpecification
    // ═══════════════════════════════════════════════════

    private ToolSpecification toToolSpecification(String fullName, McpToolDef tool) {
        // Use LangChain4j 0.36.1 builder pattern with JsonSchemaProperty
        return ToolSpecification.builder()
                .name(fullName)
                .description(tool.description())
                .addParameter("query",
                        JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("搜索关键词，使用自然语言描述"))
                .addOptionalParameter("numResults",
                        JsonSchemaProperty.INTEGER,
                        JsonSchemaProperty.description("返回结果数量，默认10，最大50"))
                .addOptionalParameter("type",
                        JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("搜索类型：auto(默认)/neural/keyword"))
                .build();
    }

    // ═══════════════════════════════════════════════════
    // Execution (for non-Exa tools discovered via mcporter)
    // ═══════════════════════════════════════════════════

    private String executeViaMcpDirect(String serverUrl, String toolName, Map<String, Object> arguments) {
        try {
            ObjectNode req = objectMapper.createObjectNode();
            req.put("jsonrpc", "2.0");
            req.put("id", 1);
            req.put("method", "tools/call");
            ObjectNode params = req.putObject("params");
            params.put("name", toolName.substring(toolName.indexOf('.') + 1)); // strip "exa."
            params.set("arguments", objectMapper.valueToTree(arguments));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(req)))
                    .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return extractTextContent(resp.body());
            }
            return "MCP 调用失败: HTTP " + resp.statusCode();
        } catch (Exception e) {
            log.warn("Direct MCP call failed for {}: {}", toolName, e.getMessage());
            return "MCP 调用失败: " + e.getMessage();
        }
    }

    private String executeViaMcporterCli(String toolName, Map<String, Object> arguments) {
        try {
            List<String> cmd = mcporterCmd("call", toolName);
            arguments.forEach((k, v) -> cmd.add(k + "=" + v));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "mcporter 执行超时";
            }
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                return "mcporter 返回错误: " + out.substring(0, Math.min(200, out.length()));
            }
            return out;
        } catch (Exception e) {
            return "mcporter 调用失败: " + e.getMessage();
        }
    }

    private String extractTextContent(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode textNode = root.at("/result/content/0/text");
            String text = textNode.asText(null);
            if (text == null || text.isBlank()) text = root.at("/result/text").asText(null);
            return text != null ? text : json;
        } catch (Exception e) {
            return json;
        }
    }

    private String findMcporter() {
        String[] candidates = {
            System.getenv("LOCALAPPDATA") + "\\npm\\mcporter.cmd",
            System.getenv("APPDATA") + "\\npm\\mcporter.cmd",
            "mcporter",
            "mcporter.cmd",
        };
        for (String c : candidates) {
            if (c != null && new java.io.File(c).exists()) return c;
        }
        return "mcporter";
    }

    /** Build base mcporter command with optional --config flag. */
    private List<String> mcporterCmd(String... subcommand) {
        List<String> cmd = new ArrayList<>();
        cmd.add(findMcporter());
        if (mcporterConfigPath != null && !mcporterConfigPath.isBlank()
                && new java.io.File(mcporterConfigPath).exists()) {
            cmd.add("--config");
            cmd.add(mcporterConfigPath);
        }
        cmd.addAll(java.util.Arrays.asList(subcommand));
        return cmd;
    }
}
