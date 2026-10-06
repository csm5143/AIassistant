package com.aiproject.aiassitant.module.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import lombok.extern.slf4j.Slf4j;
import org.htmlunit.WebClient;
import org.htmlunit.WebClientOptions;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;

/**
 * Web content scraper.
 * - Primary: playwright-to-md (real browser, clean markdown)
 * - Fallback: HtmlUnit (supports JavaScript-rendered pages like 牛客网)
 * - Last resort: java.net.http.HttpClient (lighter, for static pages)
 * - Strips nav, footer, sidebar, ads, scripts, styles
 * - Extracts title and readable body text
 * - Supports same-domain link discovery for recursive crawling (optional)
 */
@Slf4j
@Service
public class WebScraperService {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${ai.web.scraper.playwright-enabled:false}")
    private boolean playwrightEnabled;

    @Value("${ai.web.scraper.playwright-timeout:30000}")
    private int playwrightTimeoutMs;

    @Value("${ai.web.scraper.playwright-wait:2000}")
    private int playwrightWaitMs;

    @Value("${ai.web.scraper.cookies-file:}")
    private String cookiesFile;

    @Value("${ai.web.scraper.thin-content-threshold:300}")
    private int thinContentThreshold;

    static {
        // HtmlUnit is extremely verbose at INFO level — silence it
        java.util.logging.Logger.getLogger("org.htmlunit").setLevel(Level.SEVERE);
        java.util.logging.Logger.getLogger("com.gargoylesoftware.htmlunit").setLevel(Level.SEVERE);
    }

    /**
     * Create a fresh WebClient instance.
     * WebClient is NOT thread-safe — each fetch gets its own.
     */
    private WebClient createWebClient() {
        WebClient client = new WebClient();
        WebClientOptions opts = client.getOptions();

        opts.setJavaScriptEnabled(true);
        opts.setCssEnabled(false);           // don't waste time on CSS
        opts.setRedirectEnabled(true);
        opts.setTimeout((int) Duration.ofSeconds(25).toMillis());
        opts.setThrowExceptionOnScriptError(false);
        opts.setThrowExceptionOnFailingStatusCode(false);
        opts.setUseInsecureSSL(true);        // tolerate self-signed certs

        // Spoof a real browser
        client.addRequestHeader("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        client.addRequestHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");

        client.setWebConnection(new org.htmlunit.util.WebConnectionWrapper(client.getWebConnection()) {
            @Override
            public org.htmlunit.WebResponse getResponse(org.htmlunit.WebRequest request) throws IOException {
                PublicHttpUrl.validate(request.getUrl().toString());
                return super.getResponse(request);
            }
        });

        return client;
    }

    // ── Public API ──

    public ScrapedPage scrape(String url) {
        return scrape(url, false);
    }

    public ScrapedPage scrape(String url, boolean followLinks) {
        try { PublicHttpUrl.validate(url); }
        catch (IOException e) {
            ScrapedPage rejected = new ScrapedPage();
            rejected.url = url;
            rejected.error = e.getMessage();
            return rejected;
        }
        // Path 0: playwright-to-md — real browser, clean markdown output
        if (playwrightEnabled) {
            ScrapedPage playwrightResult = scrapeWithPlaywright(url);
            if (playwrightResult != null && playwrightResult.error == null
                    && playwrightResult.markdown != null && playwrightResult.markdown.length() > 80) {
                log.info("playwright-to-md success: {} ({} chars markdown)", url, playwrightResult.markdown.length());
                if (followLinks) {
                    try {
                        Document doc = Jsoup.parse(fetchWithHttpClient(url), url);
                        playwrightResult.linkedUrls = discoverLinks(doc, url, 10);
                    } catch (Exception e) {
                        log.debug("Link discovery skipped for playwright result: {}", e.getMessage());
                    }
                }
                return playwrightResult;
            }
            if (playwrightResult != null && playwrightResult.error != null) {
                log.info("playwright-to-md failed ({}), falling back to HtmlUnit/Jsoup", playwrightResult.error);
            }
        }

        try {
            String html = fetchHtml(url);
            String title = null;
            String content = null;

            // First attempt: HtmlUnit (JS enabled)
            Document doc = Jsoup.parse(html, url);
            doc.outputSettings().prettyPrint(false);
            title = extractTitle(doc, url);
            content = extractContent(doc);

            // If content is too thin or looks like an SPA shell (JS failed silently),
            // retry with JS disabled to get raw server-rendered HTML.
            boolean isSpaShell = isSpaShellPage(html, url);
            if (content.length() < thinContentThreshold || isSpaShell) {
                if (isSpaShell) {
                    log.info("SPA shell detected ({} chars content, {} script bytes) from {}, retrying with JS disabled",
                            content.length(), countScriptBytes(html), url);
                } else {
                    log.info("Thin content ({} chars) from {}, retrying with JS disabled", content.length(), url);
                }
                try {
                    String noJsHtml = fetchWithHtmlUnitNoJs(url);
                    Document noJsDoc = Jsoup.parse(noJsHtml, url);
                    String noJsContent = extractContent(noJsDoc);
                    if (noJsContent.length() > content.length()) {
                        html = noJsHtml;
                        doc = noJsDoc;
                        title = extractTitle(noJsDoc, url);
                        content = noJsContent;
                        log.info("JS-disabled retry got {} chars from {}", content.length(), url);
                    }
                } catch (Exception retryEx) {
                    log.warn("JS-disabled retry also failed for {}: {}", url, retryEx.getMessage());
                }
            }

            ScrapedPage page = new ScrapedPage();
            page.url = url;
            page.title = title;
            page.content = content;
            page.markdown = toMarkdown(title, content);

            // Optionally follow links on same domain
            if (followLinks) {
                page.linkedUrls = discoverLinks(doc, url, 10);
            }

            log.info("Scraped: {} ({} chars)", url, content.length());
            return page;

        } catch (IOException e) {
            log.warn("Failed to scrape {}: {}", url, e.getMessage());
            ScrapedPage failed = new ScrapedPage();
            failed.url = url;
            failed.error = e.getMessage();
            return failed;
        }
    }

    public List<ScrapedPage> scrapeAndFollow(String url, int maxPages) {
        List<ScrapedPage> results = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(url);

        while (!queue.isEmpty() && results.size() < maxPages) {
            String current = queue.poll();
            if (visited.contains(current)) continue;
            visited.add(current);

            ScrapedPage page = scrape(current, true);
            if (page.error == null) {
                results.add(page);
                if (page.linkedUrls != null) {
                    for (String link : page.linkedUrls) {
                        if (!visited.contains(link)) queue.add(link);
                    }
                }
            }
        }
        return results;
    }

    // ── Playwright-to-md ──

    /**
     * Scrape a URL using playwright-to-md (real Chromium browser).
     * Calls {@code to-md <URL> --json} and parses the markdown output.
     *
     * @return ScrapedPage on success, or a page with {@code error} set on failure.
     */
    private ScrapedPage scrapeWithPlaywright(String url) {
        Path tmpFile = null;
        try {
            // Create temp file for JSON output
            tmpFile = Files.createTempFile("playwright-md-", ".json");
            String toMdBin = findToMd();

            List<String> cmd = new ArrayList<>();
            cmd.add(toMdBin);
            cmd.add(url);
            cmd.add("--json");
            cmd.add("--timeout");
            cmd.add(String.valueOf(playwrightTimeoutMs));
            cmd.add("--wait");
            cmd.add(String.valueOf(playwrightWaitMs));
            // Pass cookies file if configured (for authenticated sites like nowcoder)
            if (cookiesFile != null && !cookiesFile.isBlank() && new java.io.File(cookiesFile).exists()) {
                cmd.add("--cookies");
                cmd.add(cookiesFile);
            }
            cmd.add("-o");
            cmd.add(tmpFile.toAbsolutePath().toString());

            log.debug("Running: {}", String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            boolean finished = process.waitFor(playwrightTimeoutMs + 15000, TimeUnit.MILLISECONDS);

            if (!finished) {
                process.destroyForcibly();
                ScrapedPage failed = new ScrapedPage();
                failed.url = url;
                failed.error = "playwright-to-md timed out after " + (playwrightTimeoutMs + 15000) + "ms";
                return failed;
            }

            int exitCode = process.exitValue();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            if (exitCode != 0) {
                log.warn("playwright-to-md exited with {}: {}", exitCode,
                        stdout.length() > 200 ? stdout.substring(0, 200) : stdout);
                ScrapedPage failed = new ScrapedPage();
                failed.url = url;
                failed.error = "playwright-to-md exit code " + exitCode + ": "
                        + (stdout.length() > 100 ? stdout.substring(0, 100) : stdout);
                return failed;
            }

            // Parse JSON output from temp file
            String jsonContent = Files.readString(tmpFile, StandardCharsets.UTF_8);
            if (jsonContent == null || jsonContent.isBlank()) {
                // Try stdout as fallback
                jsonContent = stdout;
                if (jsonContent == null || jsonContent.isBlank()) {
                    ScrapedPage failed = new ScrapedPage();
                    failed.url = url;
                    failed.error = "playwright-to-md returned empty output";
                    return failed;
                }
            }

            // Parse JSON: expected format {"title": "...", "markdown": "...", ...}
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(jsonContent, Map.class);

            String title = (String) result.getOrDefault("title", "");
            String markdown = (String) result.getOrDefault("markdown", "");
            String content = (String) result.getOrDefault("text", markdown);

            if (markdown == null || markdown.isBlank()) {
                // Some versions use different field names — try alternatives
                markdown = (String) result.getOrDefault("content", "");
                if (markdown == null || markdown.isBlank()) {
                    ScrapedPage failed = new ScrapedPage();
                    failed.url = url;
                    failed.error = "playwright-to-md output missing markdown field";
                    return failed;
                }
            }
            if (title == null || title.isBlank()) {
                title = extractTitleFromMarkdown(markdown, url);
            }

            ScrapedPage page = new ScrapedPage();
            page.url = url;
            page.title = title;
            page.markdown = markdown;
            page.content = content != null && !content.isBlank() ? content : markdown;
            return page;

        } catch (IOException | InterruptedException e) {
            log.warn("playwright-to-md failed for {}: {}", url, e.getMessage());
            ScrapedPage failed = new ScrapedPage();
            failed.url = url;
            failed.error = "playwright-to-md error: " + e.getMessage();
            return failed;
        } finally {
            if (tmpFile != null) {
                try { Files.deleteIfExists(tmpFile); } catch (IOException ignored) {}
            }
        }
    }

    /** Extract a title from the first # heading in markdown. */
    private String extractTitleFromMarkdown(String markdown, String fallback) {
        if (markdown == null) return fallback;
        Pattern p = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);
        Matcher m = p.matcher(markdown);
        if (m.find()) {
            return m.group(1).trim();
        }
        return fallback;
    }

    /** Locate the {@code to-md} binary in PATH or common npm global install paths. */
    private String findToMd() {
        String[] candidates = {
            System.getenv("LOCALAPPDATA") + "\\npm\\to-md.cmd",
            System.getenv("APPDATA") + "\\npm\\to-md.cmd",
            System.getenv("ProgramFiles") + "\\nodejs\\to-md.cmd",
            "/usr/local/bin/to-md",
            "/usr/bin/to-md",
            "to-md",
            "to-md.cmd",
        };
        for (String c : candidates) {
            if (c != null && new java.io.File(c).exists()) return c;
        }
        return "to-md"; // fallback — let OS PATH resolve
    }

    // ── HTTP fetch ──

    /**
     * Fetch page HTML.
     * 1. HtmlUnit (JS enabled) — best for SPA / JS-rendered pages
     * 2. HtmlUnit (JS disabled) — retry when ES6+ JS crashes (e.g. nowcoder, Vue apps)
     * 3. HttpClient — last resort for static pages
     */
    private String fetchHtml(String url) throws IOException {
        try {
            return fetchWithHtmlUnit(url);
        } catch (Exception e) {
            // Detect ES6+ incompatibility: HtmlUnit/Rhino can't parse modern JS
            String msg = e.getMessage() != null ? e.getMessage() : "";
            Throwable cause = e.getCause();
            String causeMsg = cause != null && cause.getMessage() != null ? cause.getMessage() : "";
            boolean isJsCrash = msg.contains("ScriptException")
                    || causeMsg.contains("ScriptException")
                    || msg.contains("reserved word")
                    || causeMsg.contains("reserved word")
                    || msg.contains("identifier is a reserved word")
                    || causeMsg.contains("identifier is a reserved word");

            if (isJsCrash) {
                log.info("HtmlUnit JS crash (ES6+ site), retrying with JS disabled: {}", url);
                try {
                    return fetchWithHtmlUnitNoJs(url);
                } catch (Exception retryEx) {
                    log.warn("HtmlUnit (no-JS) also failed for {}: {}", url, retryEx.getMessage());
                }
            }

            log.warn("HtmlUnit failed for {} ({}), falling back to HttpClient", url,
                    msg.length() > 120 ? msg.substring(0, 120) : msg);
            return fetchWithHttpClient(url);
        }
    }

    /**
     * HtmlUnit fetcher — executes JavaScript, waits for dynamic content.
     * Prefers direct DOM extraction of content container over full-page asXml()
     * serialization, which can lose CSS classes on VitePress/VuePress sites.
     */
    private String fetchWithHtmlUnit(String url) throws IOException {
        return fetchWithHtmlUnit(url, 0);
    }

    private String fetchWithHtmlUnit(String url, int retryCount) throws IOException {
        WebClient client = createWebClient();
        try {
            HtmlPage page = client.getPage(url);

            // Wait for background JS — extended to 15s for heavy SPA pages
            client.waitForBackgroundJavaScript(15_000);

            // Wait for content container selector (VitePress .vp-doc, etc.)
            DomNode contentNode = waitForContentNode(page, 12_000);

            // Extra settle time
            try { Thread.sleep(1200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            // ── Path A: Extract content container directly from live DOM ──
            if (contentNode != null) {
                String innerXml = contentNode.asXml();
                if (innerXml != null && innerXml.length() > 100) {
                    String title = page.getTitleText();
                    String clean = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>"
                            + escapeXml(title)
                            + "</title></head><body><div class=\"vp-doc\">"
                            + innerXml
                            + "</div></body></html>";

                    // Retry if content looks too thin (JS didn't fully render)
                    if (clean.length() < 1500 && retryCount < 1) {
                        log.info("Thin content ({} chars) from {}, retrying...", clean.length(), url);
                        client.close();
                        return fetchWithHtmlUnit(url, retryCount + 1);
                    }

                    log.debug("HtmlUnit direct-DOM extracted {} ({} chars)", url, clean.length());
                    return clean;
                }
            }

            // ── Path B: Fallback — full page serialization ──
            String html = page.asXml();
            if (html == null || html.isBlank()) {
                throw new IOException("HtmlUnit returned empty page");
            }
            if (html.length() < 500 && retryCount < 1) {
                log.info("Thin page ({} chars) from {}, retrying...", html.length(), url);
                client.close();
                return fetchWithHtmlUnit(url, retryCount + 1);
            }
            log.debug("HtmlUnit asXml fallback for {} ({} chars)", url, html.length());
            return html;

        } finally {
            client.close();
        }
    }

    /** Escape text for safe embedding in XML/HTML. */
    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * Poll the live DOM for a known content container.
     * Returns the found DomNode, or null if none matched within timeout.
     */
    private DomNode waitForContentNode(HtmlPage page, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        // Ordered by specificity — VitePress first
        String[] selectors = {
                ".vp-doc",                              // VitePress
                ".theme-default-content",               // VuePress v1
                ".page-content",                        // VuePress v2 / Hope
                "article .markdown",                    // Docusaurus
                "article",                              // generic
                "main",                                 // generic
                ".content",                             // generic
        };

        while (System.currentTimeMillis() < deadline) {
            for (String selector : selectors) {
                try {
                    DomNode node = page.querySelector(selector);
                    if (node != null) {
                        // Quick sanity: does it have real text content?
                        String text = node.getTextContent().trim();
                        if (text.length() > 50) {
                            log.debug("Content container '{}' found ({} chars)", selector, text.length());
                            return node;
                        }
                    }
                } catch (Exception ignored) {
                    // querySelector may throw on complex selectors; skip
                }
            }
            try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        }
        log.debug("No content container with >50 chars found after {}ms", timeoutMs);
        return null;
    }

    /**
     * HtmlUnit with JavaScript disabled.
     * Used as fallback when JS execution fails (ES6-incompatible sites like 牛客网).
     * Gets raw server-rendered HTML without executing any scripts.
     */
    private String fetchWithHtmlUnitNoJs(String url) throws IOException {
        WebClient client = createWebClient();
        client.getOptions().setJavaScriptEnabled(false);
        try {
            HtmlPage page = client.getPage(url);
            String html = page.asXml();
            if (html == null || html.isBlank()) {
                throw new IOException("HtmlUnit (no-JS) returned empty page");
            }
            log.debug("HtmlUnit (no-JS) got {} chars from {}", html.length(), url);
            return html;
        } finally {
            client.close();
        }
    }

    /**
     * HttpClient fallback — lighter, for static pages.
     * Kept as a safety net when HtmlUnit fails or times out.
     */
    private String fetchWithHttpClient(String url) throws IOException {
        return fetchWithHttpClient(url, 0);
    }

    private String fetchWithHttpClient(String url, int redirects) throws IOException {
        if (redirects > 5) throw new IOException("网页重定向次数过多");
        PublicHttpUrl.validate(url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Accept-Encoding", "gzip, deflate")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Upgrade-Insecure-Requests", "1")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() == 301 || response.statusCode() == 302
                    || response.statusCode() == 303 || response.statusCode() == 307 || response.statusCode() == 308) {
                String redirect = response.headers().firstValue("Location").orElse(null);
                if (redirect != null) return fetchWithHttpClient(URI.create(url).resolve(redirect).toString(), redirects + 1);
            }
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            if (contentType.contains("text/html") || contentType.contains("application/xhtml") || contentType.contains("text/plain")) {
                return response.body();
            }
            // Accept JSON too — some APIs return content info
            if (contentType.contains("application/json")) {
                return extractTextFromJson(response.body());
            }
            throw new IOException("Unsupported content type: " + contentType);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private String extractTextFromJson(String json) {
        // Try to extract text fields from common JSON API responses
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            StringBuilder sb = new StringBuilder();
            extractTextRecursive(map, sb, 0);
            return sb.toString();
        } catch (Exception e) {
            return json; // return raw JSON as text
        }
    }

    private void extractTextRecursive(Map<String, Object> map, StringBuilder sb, int depth) {
        if (depth > 5) return;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = e.getKey().toLowerCase();
            Object val = e.getValue();
            if (key.equals("content") || key.equals("text") || key.equals("question") || key.equals("answer")
                    || key.equals("title") || key.equals("description") || key.equals("body") || key.equals("summary")) {
                if (val instanceof String s && s.length() > 10) {
                    sb.append("## ").append(key).append("\n\n").append(s).append("\n\n");
                }
            }
            if (val instanceof Map m) {
                extractTextRecursive(m, sb, depth + 1);
            } else if (val instanceof List list) {
                for (Object item : list) {
                    if (item instanceof Map itemMap) extractTextRecursive(itemMap, sb, depth + 1);
                }
            }
        }
    }

    /**
     * Detect SPA shell pages: HtmlUnit successfully loaded the HTML but JS execution
     * failed silently (e.g. Rhino couldn't parse ES6+), leaving a page with lots of
     * script tags but almost no visible text content.
     */
    private boolean isSpaShellPage(String html, String url) {
        if (html == null || html.length() < 1000) return false;
        int scriptBytes = countScriptBytes(html);
        // If >30% of the page is JavaScript and extracted text is near-zero,
        // this is almost certainly an SPA that didn't render.
        double scriptRatio = (double) scriptBytes / Math.max(1, html.length());
        if (scriptRatio > 0.3) {
            // Quick sanity: how much visible text is there?
            String visible = html.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
            if (visible.length() < 500) {
                return true;
            }
        }
        return false;
    }

    private int countScriptBytes(String html) {
        int count = 0;
        Pattern p = Pattern.compile("<script[^>]*>.*?</script>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        while (m.find()) {
            count += m.group().length();
        }
        return count;
    }

    // ── Noise removal ──

    private static final String[] NOISE_TAGS = {
            "script", "style", "nav", "footer", "header", "aside",
            "noscript", "iframe", "svg", "form", "button"
    };

    private static final Pattern NOISE_CLASS = Pattern.compile(
            "(?i)(nav|menu|sidebar|footer|header|banner|ad-|advertisement|popup|modal" +
            "|comment|share|social|related|recommend|sidebar|widget|pagination|breadcrumb" +
            "|toc|table-of-contents|skip-link|screen-reader|sr-only|visually-hidden" +
            "|cookie|consent|newsletter|intercom|drift|chat-widget)");

    private void removeNoiseElements(Document doc) {
        // 1. Remove noise by tag name
        for (String tag : NOISE_TAGS) {
            doc.select(tag).remove();
        }

        // 2. Remove noise by class/id pattern
        Elements all = doc.select("[class],[id]");
        for (Element el : all) {
            String cls = el.className();
            String id = el.id();
            if ((cls != null && NOISE_CLASS.matcher(cls).find())
                    || (id != null && NOISE_CLASS.matcher(id).find())) {
                el.remove();
            }
        }

        // 3. Remove empty tags (no visible text, no children)
        boolean changed;
        do {
            changed = false;
            Elements empty = doc.select("*:not(html):not(head):not(body):not(br):not(hr):not(img):not(input):not(meta):not(link)");
            for (Element el : empty) {
                if (el.childrenSize() == 0 && el.ownText().isBlank()) {
                    el.remove();
                    changed = true;
                }
            }
        } while (changed);
    }

    // ── Extractors ──

    private String extractTitle(Document doc, String url) {
        // Try og:title, then <title>, then h1
        String ogTitle = doc.select("meta[property=og:title]").attr("content");
        if (!ogTitle.isBlank()) return ogTitle.trim();

        String title = doc.title();
        if (!title.isBlank()) return title.trim();

        String h1 = doc.select("h1").text();
        if (!h1.isBlank()) return h1.trim();

        return url;
    }

    /** Shared FlexmarkHtmlConverter — thread-safe, stateless. */
    private static final FlexmarkHtmlConverter HTML_MD_CONVERTER = FlexmarkHtmlConverter.builder().build();

    private String extractContent(Document doc) {
        // Priority: article → .vp-doc → main → body
        Element container = doc.selectFirst("article");
        if (container == null) container = doc.selectFirst(".vp-doc");                  // VitePress
        if (container == null) container = doc.selectFirst(".theme-default-content");   // VuePress
        if (container == null) container = doc.selectFirst("article .markdown");        // Docusaurus
        if (container == null) container = doc.selectFirst("main");
        if (container == null) container = doc.selectFirst(".content");
        if (container == null) container = doc.selectFirst(".markdown-body");           // GitHub-like
        if (container == null) container = doc.body();
        if (container == null) return "";

        // Only denoise when working from raw body
        boolean isBody = container.tagName().equalsIgnoreCase("body");
        if (isBody) {
            removeNoiseElements(doc);
            container = doc.body();
            if (container == null) return "";
        }

        // Pre-process code blocks: extract with language + indentation,
        // replace with UUID markers wrapped in <p> so flexmark leaves them alone.
        // UUIDs are guaranteed not to appear in real text.
        Map<String, String> codeMarkers = new LinkedHashMap<>();

        // 1. Standard <pre> blocks
        for (Element pre : container.select("pre")) {
            String codeBlock = extractCodeBlock(pre);
            if (!codeBlock.isBlank()) {
                String uuid = "CB" + UUID.randomUUID().toString().replace("-", "");
                codeMarkers.put(uuid, codeBlock);
                pre.before("<p>" + uuid + "</p>");
                pre.remove();
            }
        }

        // 2. Non-standard containers
        for (String cls : List.of(".highlight", ".code", ".codeBlock", ".code-block",
                "[class*=language-]")) {
            for (Element el : container.select(cls)) {
                String codeBlock = extractCodeBlock(el);
                if (!codeBlock.isBlank()) {
                    String uuid = "CB" + UUID.randomUUID().toString().replace("-", "");
                    codeMarkers.put(uuid, codeBlock);
                    el.before("<p>" + uuid + "</p>");
                    el.remove();
                }
            }
        }

        // 3. Standalone <code> with language class
        for (Element code : container.select("code[class*=language-]")) {
            if (code.parent() != null && code.parent().is("pre")) continue;
            String text = code.text();
            if (text.length() > 80 && text.contains("\n")) {
                String lang = detectLanguage(code);
                String codeBlock = "```" + lang + "\n" + normalizeCodeWhitespace(text) + "\n```";
                if (!codeBlock.isBlank()) {
                    String uuid = "CB" + UUID.randomUUID().toString().replace("-", "");
                    codeMarkers.put(uuid, codeBlock);
                    code.before("<p>" + uuid + "</p>");
                    code.remove();
                }
            }
        }

        // Convert cleaned HTML → Markdown with flexmark
        String bodyHtml = container.html();
        String markdown = HTML_MD_CONVERTER.convert(bodyHtml);

        // Restore code blocks. flexmark may strip <p> tags and add indentation.
        // Match UUIDs with optional surrounding whitespace and HTML wrapper.
        for (var entry : codeMarkers.entrySet()) {
            String uuid = entry.getKey();
            String replacement = "\n\n" + entry.getValue() + "\n\n";
            // Pattern 1: <p>  CBxxx  </p> (flexmark left <p> intact)
            markdown = markdown.replaceAll(
                    "<p>\\s*" + Pattern.quote(uuid) + "\\s*</p>",
                    Matcher.quoteReplacement(replacement));
            // Pattern 2: ^  CBxxx$  (flexmark stripped <p>, may have leading whitespace)
            markdown = markdown.replaceAll(
                    "(?m)^[ \\t]*" + Pattern.quote(uuid) + "[ \\t]*$",
                    Matcher.quoteReplacement(replacement));
            // Pattern 3: inline CBxxx  (any remaining occurrence)
            markdown = markdown.replace(uuid, replacement);
        }

        // Clean up flexmark artifacts
        markdown = markdown
                .replaceAll("\\n{4,}", "\n\n\n")       // collapse excessive blank lines
                .replaceAll("(?m)^[ \\t]+$", "")         // blank lines with trailing whitespace
                .trim();

        // Final pass: deduplicate paragraphs (SSR + CSR dual render)
        return dedupParagraphs(markdown);
    }

    /**
     * Remove duplicate paragraphs from the final markdown output.
     * SSR + CSR pages often render content twice with minor whitespace diffs.
     */
    private String dedupParagraphs(String markdown) {
        if (markdown == null || markdown.isBlank()) return markdown;
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        StringBuilder out = new StringBuilder();
        for (String block : markdown.split("\n\n")) {
            String normalized = block.trim().replaceAll("\\s+", " ");
            if (normalized.length() < 10) {
                out.append(block).append("\n\n");
                continue;
            }
            // Key: first 80 chars normalized
            String key = normalized.substring(0, Math.min(80, normalized.length()));
            if (seen.add(key)) {
                out.append(block).append("\n\n");
            }
        }
        return out.toString().trim();
    }

    /**
     * Extract a <pre> block, preserving indentation, line breaks, and detecting language.
     * Handles three common syntax-highlighter patterns:
     * - Shiki / VitePress:   <span class="line">…</span>  per logical line
     * - Prism / Docusaurus:   inline <span class="token">…</span> (newlines in text nodes)
     * - Plain:                just text inside <code> or <pre>
     */
    private String extractCodeBlock(Element pre) {
        Element code = pre.selectFirst("code");
        Element source = code != null ? code : pre;

        String lang = detectLanguage(source);

        // ── Strategy A: Shiki-style per-line <span class="line"> ──
        Elements lineSpans = source.select("span.line");
        if (!lineSpans.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lineSpans.size(); i++) {
                if (i > 0) sb.append('\n');
                // Each .line span may contain nested syntax-highlight spans;
                // .text() gets the visible text exactly as rendered
                sb.append(lineSpans.get(i).text());
            }
            String result = sb.toString();
            if (!result.isBlank()) {
                return "```" + lang + "\n" + result + "\n```";
            }
        }

        // ── Strategy B: Prism-style inline tokens ──
        // Text nodes between inline <span> tokens carry actual whitespace.
        // Use html() + strip tags, then reconstruct lines via text node traversal.
        String wholeText = source.wholeText();
        if (wholeText != null && !wholeText.isBlank() && wholeText.contains("\n")) {
            return "```" + lang + "\n" + normalizeCodeWhitespace(wholeText) + "\n```";
        }

        // ── Strategy C: Walk child text nodes for any inline-highlight pattern ──
        String fromNodes = extractTextPreservingLineBreaks(source);
        if (!fromNodes.isBlank()) {
            return "```" + lang + "\n" + normalizeCodeWhitespace(fromNodes) + "\n```";
        }

        // ── Strategy D: Fallback — raw html strip (last resort) ──
        String html = source.html();
        String raw = html
                .replaceAll("<[^>]+>", "")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");

        String codeText = normalizeCodeWhitespace(raw);
        if (codeText.isBlank()) return "";

        return "```" + lang + "\n" + codeText + "\n```";
    }

    /**
     * Walk child text nodes and inline elements, inserting newlines
     * where block-level or display-block children are found.
     * This handles syntax highlighters that don't use .line wrappers
     * but still have structural children (e.g. <div> per line).
     */
    private String extractTextPreservingLineBreaks(Element source) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;

        for (Node child : source.childNodes()) {
            if (child instanceof TextNode) {
                String text = ((TextNode) child).text();
                if (!first) sb.append(' ');
                sb.append(text);
                first = false;
            } else if (child instanceof Element) {
                Element el = (Element) child;
                String tag = el.tagName().toLowerCase();
                // Block-level elements inside code → newline
                if (isBlockTag(tag)) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(el.text());
                    sb.append('\n');
                    first = true;
                } else {
                    // Inline element — append its text
                    if (!first && !tag.equals("span") && !tag.equals("code") && !tag.equals("em") && !tag.equals("strong")) {
                        sb.append(' ');
                    }
                    sb.append(el.text());
                    first = false;
                }
            }
        }
        return sb.toString();
    }

    private boolean isBlockTag(String tag) {
        return switch (tag) {
            case "div", "p", "br", "section", "article" -> true;
            default -> false;
        };
    }

    /** Detect programming language from class or data attribute. */
    private String detectLanguage(Element code) {
        String cls = code.className();
        if (cls != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("language-(\\w+)").matcher(cls);
            if (m.find()) return m.group(1);
        }
        if (code.hasAttr("data-lang")) {
            return code.attr("data-lang");
        }
        return "";
    }

    /**
     * Normalize code text: collapse excessive blank lines while keeping
     * single newlines (logical line breaks) and indentation intact.
     */
    private String normalizeCodeWhitespace(String raw) {
        // Replace carriage returns
        raw = raw.replace("\r\n", "\n").replace('\r', '\n');

        // Collapse sequences of ONLY whitespace on a line (blank lines)
        // into at most one blank line (two newlines → one empty line)
        raw = raw.replaceAll("\\n[ \\t]*\\n[ \\t]*\\n+", "\n\n");

        // Remove trailing whitespace per line (but preserve leading indentation)
        raw = raw.replaceAll("(?m)[ \\t]+$", "");

        // Strip leading/trailing blank lines
        raw = raw.replaceAll("^\\s*\\n", "").replaceAll("\\n\\s*$", "");

        return raw;
    }

    private String toMarkdown(String title, String content) {
        return "# " + title + "\n\n> 来源：抓取自网页\n\n" + content;
    }

    /** Remove duplicate paragraphs from the final markdown output. */
    // ── Link discovery ──

    private List<String> discoverLinks(Document doc, String baseUrl, int maxLinks) {
        List<String> links = new ArrayList<>();
        try {
            URI base = URI.create(baseUrl);
            String baseHost = base.getHost();

            Elements anchors = doc.select("a[href]");
            for (Element a : anchors) {
                if (links.size() >= maxLinks) break;
                try {
                    String href = a.absUrl("href");
                    if (href.isBlank() || href.startsWith("javascript:") || href.startsWith("#")) continue;
                    URI linkUri = URI.create(href);
                    if (linkUri.getHost() != null && linkUri.getHost().equals(baseHost)) {
                        // Only follow same-domain links that look like content pages
                        String path = linkUri.getPath().toLowerCase();
                        if (!path.matches(".*\\.(jpg|png|gif|pdf|zip|mp4|mp3|css|js|ico|svg)$")) {
                            links.add(href);
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.debug("Link discovery failed: {}", e.getMessage());
        }
        return links;
    }

    // ── Result DTO ──

    public static class ScrapedPage {
        public String url;
        public String title;
        public String content;
        public String markdown;
        public String error;
        public List<String> linkedUrls;
    }
}
