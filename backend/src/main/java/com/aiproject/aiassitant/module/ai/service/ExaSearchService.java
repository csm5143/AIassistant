package com.aiproject.aiassitant.module.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Dual-path search service: Exa semantic search via mcporter CLI (Path A)
 * with direct MCP/JSON-RPC fallback (Path B).
 *
 * Path A — mcporter CLI:   {@code mcporter call exa.web_search_exa query="..." num_results=5}
 * Path B — direct MCP:     POST https://mcp.exa.ai/mcp with JSON-RPC 2.0
 */
@Slf4j
@Service
public class ExaSearchService {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${ai.search.exa.base-url:https://mcp.exa.ai/mcp}")
    private String exaBaseUrl;

    @Value("${ai.search.exa.timeout-seconds:30}")
    private int timeoutSeconds;

    @Value("${ai.search.mcporter.timeout-seconds:45}")
    private int mcporterTimeoutSeconds;

    @Value("${ai.search.mcporter.config-path:}")
    private String mcporterConfigPath;

    @Value("${ai.search.exa.enabled:true}")
    private boolean enabled;

    public ExaSearchService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Search the web via Exa. Tries direct MCP first, falls back to mcporter CLI.
     *
     * @param query      search query string
     * @param numResults number of results (1–50)
     * @param searchType auto | neural | keyword
     * @return formatted search result string for LLM consumption
     */
    public String search(String query, int numResults, String searchType) {
        if (!enabled) return disabledMessage();

        int top = Math.max(1, Math.min(numResults, 50));
        String type = (searchType == null || searchType.isBlank()) ? "auto" : searchType;

        // Path B first: direct MCP HTTP (faster)
        String result = searchDirect(query, top, type);
        if (result != null && !result.startsWith("DIRECT_FAIL:")) {
            return result;
        }
        log.info("Direct Exa MCP failed ({}), falling back to mcporter CLI", result);

        // Path A fallback: mcporter CLI
        result = searchViaMcporter(query, top, type);
        if (result != null) return result;

        return "搜索服务暂时不可用。请稍后重试或使用知识库搜索。";
    }

    /** Bounded path used by automatic research; avoids stacking CLI/API fallback timeouts. */
    public String searchEvidence(String query, int numResults) {
        if (!enabled) return "";
        String result = searchDirect(query, Math.max(1, Math.min(4, numResults)), "auto");
        return result == null || result.startsWith("DIRECT_FAIL:") ? "" : result;
    }

    // ═══════════════════════════════════════════════════════════
    // Path B — Direct MCP / JSON-RPC 2.0 over HTTP
    // ═══════════════════════════════════════════════════════════

    private String searchDirect(String query, int numResults, String type) {
        try {
            String jsonRpcBody = buildSearchRequest(query, numResults, type);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(exaBaseUrl))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .timeout(Duration.ofSeconds(Math.max(1, Math.min(timeoutSeconds, 15))))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonRpcBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parseExaResult(response.body(), query);
            }
            if (response.statusCode() == 202) {
                // Accepted — Exa may return SSE stream; try to read body anyway
                String body = response.body();
                if (body != null && !body.isBlank()) {
                    return parseExaResult(body, query);
                }
                return "DIRECT_FAIL:202";
            }
            log.debug("Exa direct returned HTTP {}", response.statusCode());
            return "DIRECT_FAIL:" + response.statusCode();
        } catch (IOException | InterruptedException e) {
            log.debug("Exa direct HTTP failed: {}", e.getMessage());
            return "DIRECT_FAIL:" + e.getMessage();
        }
    }

    private String buildSearchRequest(String query, int numResults, String type) {
        // JSON-RPC 2.0 request
        ObjectNode root = objectMapper.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", 1);
        root.put("method", "tools/call");

        ObjectNode params = root.putObject("params");
        params.put("name", "web_search_exa");

        ObjectNode arguments = params.putObject("arguments");
        arguments.put("query", query);
        arguments.put("numResults", numResults);
        arguments.put("type", type);

        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return root.toString();
        }
    }

    /**
     * Parse Exa / JSON-RPC response into LLM-friendly text.
     * Handles both direct JSON and SSE-wrapped formats.
     */
    private String parseExaResult(String body, String query) {
        try {
            JsonNode root = parseTransport(body);
            if (root.has("error") || root.at("/result/isError").asBoolean(false)) return "DIRECT_FAIL:search_error";

            // Try JSON-RPC result path
            JsonNode resultNode = root.at("/result/content/0/text");
            String resultText = resultNode.asText(null);
            if (resultText == null) resultText = root.at("/result/text").asText(null);
            if (resultText == null) resultText = root.at("/result").asText(null);

            // Try plain search response path
            if (resultText == null || resultText.isBlank()) {
                resultText = body; // raw fallback — will be truncated below
            }

            if (resultText.length() > 24000) {
                resultText = resultText.substring(0, 24000) + "\n...(内容过长已截断)";
            }
            return resultText + "\n\n【来源：Exa 语义搜索】";
        } catch (Exception e) {
            log.warn("Failed to parse Exa result: {}", e.getMessage());
            return "DIRECT_FAIL:invalid_response";
        }
    }

    JsonNode parseTransport(String body) throws IOException {
        if (body.stripLeading().startsWith("{")) return objectMapper.readTree(body);
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\\R")) {
            if (line.startsWith("data:")) data.append(line.substring(5).stripLeading()).append('\n');
            if (line.isBlank() && !data.isEmpty()) {
                JsonNode node = objectMapper.readTree(data.toString());
                if (node.has("result") || node.has("error")) return node;
                data.setLength(0);
            }
        }
        if (!data.isEmpty()) return objectMapper.readTree(data.toString());
        throw new IOException("Empty search response");
    }

    // ═══════════════════════════════════════════════════════════
    // Path A — mcporter CLI via ProcessBuilder
    // ═══════════════════════════════════════════════════════════

    private String searchViaMcporter(String query, int numResults, String type) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(findMcporter());
            // Use project-level config if available
            if (mcporterConfigPath != null && !mcporterConfigPath.isBlank()
                    && new java.io.File(mcporterConfigPath).exists()) {
                cmd.add("--config");
                cmd.add(mcporterConfigPath);
            }
            cmd.add("call");
            cmd.add("exa.web_search_exa");
            cmd.add("query=" + query);
            cmd.add("num_results=" + numResults);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            boolean finished = process.waitFor(mcporterTimeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return null;
            }

            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

            if (process.exitValue() != 0) {
                log.warn("mcporter exited with {}: {}", process.exitValue(), output.substring(0, Math.min(200, output.length())));
                return null;
            }

            if (output.isBlank()) return null;

            // Parse mcporter output (JSON result)
            return parseMcporterOutput(output, query);
        } catch (IOException | InterruptedException e) {
            log.warn("mcporter execution failed: {}", e.getMessage());
            return null;
        }
    }

    private String parseMcporterOutput(String output, String query) {
        try {
            JsonNode root = objectMapper.readTree(output);

            // mcporter returns { "content": [{ "type": "text", "text": "..." }] }
            JsonNode content = root.at("/content/0/text");
            String text = content.asText(null);
            if (text == null || text.isBlank()) {
                text = root.toString();
            }

            if (text.length() > 8000) text = text.substring(0, 8000) + "\n...(截断)";
            return text + "\n\n【来源：Exa 语义搜索 (mcporter)】";
        } catch (Exception e) {
            // If JSON parse fails, return the raw output truncated
            log.debug("mcporter output parse failed, returning raw: {}", e.getMessage());
            return output.length() > 6000 ? output.substring(0, 6000) + "..." : output;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Health check & helpers
    // ═══════════════════════════════════════════════════════════

    /**
     * Check if mcporter + Exa is available and healthy.
     */
    public boolean isHealthy() {
        return enabled && (checkDirectHealth() || checkMcporterHealth());
    }

    private boolean checkDirectHealth() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(exaBaseUrl + "/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkMcporterHealth() {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(findMcporter());
            if (mcporterConfigPath != null && !mcporterConfigPath.isBlank()
                    && new java.io.File(mcporterConfigPath).exists()) {
                cmd.add("--config");
                cmd.add(mcporterConfigPath);
            }
            cmd.add("list");
            cmd.add("exa");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean ok = p.waitFor(10, TimeUnit.SECONDS);
            if (!ok) { p.destroyForcibly(); return false; }
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return p.exitValue() == 0 && out.contains("exa");
        } catch (Exception e) {
            return false;
        }
    }

    private String findMcporter() {
        // Windows: check common paths
        String[] candidates = {
            System.getenv("LOCALAPPDATA") + "\\npm\\mcporter.cmd",
            System.getenv("APPDATA") + "\\npm\\mcporter.cmd",
            "mcporter",
            "mcporter.cmd",
        };
        for (String c : candidates) {
            if (c != null && new java.io.File(c).exists()) return c;
        }
        return "mcporter"; // fallback — let OS PATH resolve it
    }

    private String disabledMessage() {
        return "外部搜索未启用。请确认已配置 Exa (mcporter config add exa https://mcp.exa.ai/mcp)。";
    }
}
