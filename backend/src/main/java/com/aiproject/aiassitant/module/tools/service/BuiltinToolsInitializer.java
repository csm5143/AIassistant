package com.aiproject.aiassitant.module.tools.service;

import com.aiproject.aiassitant.module.knowledge.entity.KbChunk;
import com.aiproject.aiassitant.module.knowledge.service.VectorSearchService;
import com.aiproject.aiassitant.module.tools.entity.ToolRegistration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class BuiltinToolsInitializer {

    private final ToolService toolService;
    private final VectorSearchService vectorSearchService;

    @PostConstruct
    public void init() {
        toolService.createTool(createToolRegistration("knowledge_search",
                "Search the knowledge base for relevant documents",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"Search query\"},\"collection_id\":{\"type\":\"string\",\"description\":\"Optional collection ID to limit search scope\"},\"top_k\":{\"type\":\"integer\",\"description\":\"Number of results to return\",\"default\":5}},\"required\":[\"query\"]}",
                "BUILTIN"));

        toolService.createTool(createToolRegistration("calculator",
                "Perform basic arithmetic calculations",
                "{\"type\":\"object\",\"properties\":{\"expression\":{\"type\":\"string\",\"description\":\"Arithmetic expression, e.g. 2+3*4 or sqrt(16)\"}},\"required\":[\"expression\"]}",
                "BUILTIN"));

        toolService.createTool(createToolRegistration("current_time",
                "Get the current date and time",
                "{\"type\":\"object\",\"properties\":{}}",
                "BUILTIN"));

        toolService.registerTool("knowledge_search", new ToolService.ToolExecutor() {
            @Override
            public String execute(Map<String, Object> params) {
                String query = (String) params.get("query");
                String collectionId = (String) params.get("collection_id");
                int topK = params.get("top_k") != null ? ((Number) params.get("top_k")).intValue() : 5;

                try {
                    var chunks = vectorSearchService.vectorSearch(query, "system", collectionId, topK);
                    StringBuilder sb = new StringBuilder();
                    sb.append("Found ").append(chunks.size()).append(" relevant chunks:\n\n");
                    for (int i = 0; i < chunks.size(); i++) {
                        KbChunk chunk = chunks.get(i);
                        sb.append("[").append(i + 1).append("] ");
                        sb.append(chunk.getContent().substring(0, Math.min(200, chunk.getContent().length())));
                        sb.append("\n\n");
                    }
                    return sb.toString();
                } catch (Exception e) {
                    log.warn("Knowledge search tool failed: {}", e.getMessage());
                    return "Knowledge search failed: " + e.getMessage();
                }
            }

            @Override
            public String getDescription() {
                return "Search the knowledge base for relevant documents";
            }

            @Override
            public Map<String, String> getParameters() {
                Map<String, String> params = new HashMap<>();
                params.put("query", "string");
                params.put("collection_id", "string");
                params.put("top_k", "integer");
                return params;
            }
        });

        toolService.registerTool("calculator", new ToolService.ToolExecutor() {
            @Override
            public String execute(Map<String, Object> params) {
                String expr = (String) params.get("expression");
                if (expr == null) return "Error: missing expression";

                try {
                    double result = evaluateSimpleExpression(expr.trim());
                    return String.format("%.6f", result);
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }

            @Override
            public String getDescription() {
                return "Perform basic arithmetic calculations";
            }

            @Override
            public Map<String, String> getParameters() {
                Map<String, String> params = new HashMap<>();
                params.put("expression", "string");
                return params;
            }

            private double evaluateSimpleExpression(String expr) {
                expr = expr.replaceAll("\\s+", "");
                return evaluate(expr, new int[]{0});
            }

            private double evaluate(String expr, int[] pos) {
                double left = parseTerm(expr, pos);
                while (pos[0] < expr.length()) {
                    char op = expr.charAt(pos[0]);
                    if (op != '+' && op != '-') break;
                    pos[0]++;
                    double right = parseTerm(expr, pos);
                    if (op == '+') left += right;
                    else left -= right;
                }
                return left;
            }

            private double parseTerm(String expr, int[] pos) {
                double left = parseFactor(expr, pos);
                while (pos[0] < expr.length()) {
                    char op = expr.charAt(pos[0]);
                    if (op != '*' && op != '/') break;
                    pos[0]++;
                    double right = parseFactor(expr, pos);
                    if (op == '*') left *= right;
                    else if (right != 0) left /= right;
                    else throw new ArithmeticException("Division by zero");
                }
                return left;
            }

            private double parseFactor(String expr, int[] pos) {
                double val;
                if (pos[0] < expr.length() && expr.charAt(pos[0]) == '(') {
                    pos[0]++;
                    val = evaluate(expr, pos);
                    if (pos[0] < expr.length() && expr.charAt(pos[0]) == ')') pos[0]++;
                } else {
                    StringBuilder num = new StringBuilder();
                    while (pos[0] < expr.length() && (Character.isDigit(expr.charAt(pos[0])) || expr.charAt(pos[0]) == '.')) {
                        num.append(expr.charAt(pos[0]++));
                    }
                    if (num.length() == 0) throw new IllegalArgumentException("Expected number at position " + pos[0]);
                    val = Double.parseDouble(num.toString());
                }
                return val;
            }
        });

        toolService.registerTool("current_time", new ToolService.ToolExecutor() {
            private final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            @Override
            public String execute(Map<String, Object> params) {
                return LocalDateTime.now().format(fmt);
            }

            @Override
            public String getDescription() {
                return "Get the current date and time";
            }

            @Override
            public Map<String, String> getParameters() {
                return new HashMap<>();
            }
        });

        log.info("Registered {} builtin tools", toolService.listTools().size());
    }

    private ToolRegistration createToolRegistration(String name, String description, String schemaJson, String source) {
        ToolRegistration tool = new ToolRegistration();
        tool.setName(name);
        tool.setDescription(description);
        tool.setSchemaJson(schemaJson);
        tool.setSource(source);
        return tool;
    }
}
