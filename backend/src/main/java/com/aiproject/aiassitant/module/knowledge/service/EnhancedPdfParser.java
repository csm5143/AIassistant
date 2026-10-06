package com.aiproject.aiassitant.module.knowledge.service;

import com.aiproject.aiassitant.module.ai.service.ApiManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.IntConsumer;

/** Selective local OCR/layout with bounded, explicitly labelled vision enrichment. */
@Service
@Slf4j
public class EnhancedPdfParser {
    public record Segment(int page, String kind, String text) {}
    public record Result(List<Segment> segments, SourceDocumentParser.ParseReport report) {}
    private record ParsedBatch(List<Integer> pages, JsonNode body) {}
    private final ApiManager apis;
    private final ObjectMapper json;
    private final Object visionLock = new Object();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(4)).build();
    @Value("${ai.pdf.parser-url:http://127.0.0.1:8741}") private String url;
    @Value("${ai.pdf.parser-token:}") private String token;
    @Value("${ai.pdf.vision-max-images:8}") private int visionLimit;
    @Value("${ai.pdf.runtime-dir:D:/AIassistant-env/runtime/pdf-parser}") private String runtimeDir;

    public EnhancedPdfParser(ApiManager apis, ObjectMapper json) { this.apis = apis; this.json = json; }
    public int visualPageBudget() { return Math.max(0, Math.min(visionLimit, 32)); }

    public Result enhance(PDDocument original, Map<Integer, Boolean> pages) throws IOException {
        return enhance(original, pages, false, ignored -> {});
    }

    public Result enhance(PDDocument original, Map<Integer, Boolean> pages, boolean fastScannedBook,
                          IntConsumer completedPages) throws IOException {
        List<Segment> segments = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int enhanced = 0, ocr = 0, tables = 0, pictures = 0, calls = 0, tokens = 0, hits = 0;
        long started = System.nanoTime();
        int remaining = Math.max(0, Math.min(visionLimit, 32));
        int completed = 0;
        if (fastScannedBook) warnings.add("长篇扫描件使用快速文字识别；表格和图像内容请结合原页核对");
        for (boolean force : List.of(false, true)) {
            List<Integer> group = pages.entrySet().stream().filter(e -> e.getValue() == force).map(Map.Entry::getKey).toList();
            for (int from = 0; from < group.size(); from += 8) {
                List<Integer> requested = group.subList(from, Math.min(from + 8, group.size()));
                for (ParsedBatch parsedBatch : parseBatches(original, requested, force, fastScannedBook && force)) {
                List<Integer> batch = parsedBatch.pages();
                try {
                    var root = parsedBatch.body();
                    enhanced += batch.size();
                    if (force) ocr += batch.size();
                    if (root.path("cacheHit").asBoolean()) hits += batch.size();
                    for (var warning : root.path("warnings")) warnings.add(warning.asText());
                    for (var warning : root.path("pageWarnings")) {
                        int index=warning.path("page").asInt()-1;
                        if(index>=0 && index<batch.size()) warnings.add("第 "+batch.get(index)+" 页"+warning.path("message").asText());
                    }
                    Set<Integer> withText = new HashSet<>();
                    for (var block : root.path("blocks")) {
                        int index = block.path("page").asInt() - 1;
                        if (index < 0 || index >= batch.size()) throw new IOException("解析页码无效");
                        String text = block.path("text").asText("");
                        if (text.isBlank()) continue;
                        String kind = block.path("kind").asText("text");
                        segments.add(new Segment(batch.get(index), kind, text));
                        withText.add(batch.get(index));
                        if ("table".equals(kind)) tables++;
                    }
                    for (var picture : root.path("pictures")) {
                        pictures++;
                        int index = picture.path("page").asInt() - 1;
                        if (index < 0 || index >= batch.size()) throw new IOException("图像页码无效");
                        int page = batch.get(index);
                        if (remaining == 0) { warnings.add("图表理解已达到本次 " + visionLimit + " 张上限，其他图像未补充"); continue; }
                        remaining--;
                        try {
                            synchronized (visionLock) {
                            String image = picture.path("image").asText();
                            String caption = picture.path("caption").asText("");
                            String cacheKey = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(("figure-v2:" + apis.pdfVisionIdentity() + ":" + caption + ":" + image).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                            Path cache = Path.of(runtimeDir, "vision-cache", cacheKey + ".json");
                            ApiManager.PdfVisionResult result;
                            if (Files.exists(cache) && Files.getLastModifiedTime(cache).toMillis() > System.currentTimeMillis() - Duration.ofDays(7).toMillis()) {
                                result = json.readValue(Files.readString(cache), ApiManager.PdfVisionResult.class); hits++;
                            } else {
                                calls++;
                                result = apis.describePdfFigure(image, caption);
                                tokens += result.tokens();
                                Files.createDirectories(cache.getParent());
                                Path partial = cache.resolveSibling(cacheKey + "." + UUID.randomUUID() + ".tmp");
                                Files.writeString(partial, json.writeValueAsString(result));
                                Files.move(partial, cache, StandardCopyOption.REPLACE_EXISTING);
                                pruneVisionCache(cache.getParent());
                            }
                            segments.add(new Segment(page, "vision", "【AI 图表描述，需结合原图核验】\n"
                                    + (caption.isBlank() ? "" : "【同页文字】\n" + caption + "\n") + "【图像理解】\n" + result.text()));
                            withText.add(page);
                            }
                        } catch (Exception e) {
                            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                            warnings.add("第 " + page + " 页图表理解失败，已保留本地提取的内容");
                        }
                    }
                    for (int page : batch) if (!withText.contains(page)) warnings.add("第 " + page + " 页未提取到可用内容，请检查原页");
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    // Required OCR/layout cannot silently fall back to potentially corrupt native text.
                    throw new com.aiproject.aiassitant.common.BizException(503,
                            "第 " + batch.get(0) + "–" + batch.get(batch.size()-1) + " 页增强解析失败，请检查本地 PDF 解析服务后重试");
                }
                completed += batch.size();
                completedPages.accept(completed);
                }
            }
        }
        segments.sort(Comparator.comparingInt(Segment::page));
        return new Result(segments, new SourceDocumentParser.ParseReport(fastScannedBook ? "PDFBox + RapidOCR 快速扫描识别" : "PDFBox + Docling + RapidOCR", original.getNumberOfPages(),
                original.getNumberOfPages() - pages.size(), enhanced, ocr, tables, pictures, calls, tokens, hits,
                (System.nanoTime()-started)/1_000_000, List.copyOf(new LinkedHashSet<>(warnings))));
    }

    /** A slow or malformed multi-page conversion is retried as smaller page groups. */
    private List<ParsedBatch> parseBatches(PDDocument original, List<Integer> batch, boolean force, boolean fastScan) {
        if (token == null || token.isBlank())
            throw new com.aiproject.aiassitant.common.BizException(503, "本地 PDF 解析服务令牌未设置");
        try {
            byte[] bytes;
            try (var subset = new PDDocument(); var out = new ByteArrayOutputStream()) {
                subset.setDocumentId(0L);
                for (int page : batch) {
                    var sourcePage = original.getPage(page - 1);
                    var imported = subset.importPage(sourcePage);
                    if (sourcePage.getResources() != null) imported.setResources(sourcePage.getResources());
                }
                subset.save(out);
                bytes = out.toByteArray();
            }
            var request = HttpRequest.newBuilder(URI.create(url + "/parse"))
                    .header("Content-Type", "application/pdf").header("X-Parser-Token", token)
                    .header("X-Force-Ocr", Boolean.toString(force))
                    .header("X-Fast-Scan", Boolean.toString(fastScan)).timeout(Duration.ofSeconds(300))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
            JsonNode root = json.readTree(response.body());
            if (root.path("pageCount").asInt() != batch.size()) throw new IOException("解析页数不完整");
            return List.of(new ParsedBatch(List.copyOf(batch), root));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new com.aiproject.aiassitant.common.BizException(503, "PDF 增强解析已中断");
        } catch (Exception e) {
            log.warn("PDF parser failed for pages {}–{} ({} pages): {}", batch.get(0),
                    batch.get(batch.size() - 1), batch.size(), e.toString());
            if (batch.size() == 1) throw new com.aiproject.aiassitant.common.BizException(503,
                    "第 " + batch.get(0) + " 页增强解析失败，请检查本地 PDF 解析服务后重试");
            int half = batch.size() / 2;
            List<ParsedBatch> results = new ArrayList<>();
            results.addAll(parseBatches(original, batch.subList(0, half), force, fastScan));
            results.addAll(parseBatches(original, batch.subList(half, batch.size()), force, fastScan));
            return results;
        }
    }

    private void pruneVisionCache(Path folder) throws IOException {
        try (var paths = Files.list(folder)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json")).toList())
                if (Files.getLastModifiedTime(path).toMillis() < System.currentTimeMillis() - Duration.ofDays(7).toMillis()) Files.deleteIfExists(path);
        }
    }
}
