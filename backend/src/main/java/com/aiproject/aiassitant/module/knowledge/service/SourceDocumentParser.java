package com.aiproject.aiassitant.module.knowledge.service;

import org.apache.pdfbox.Loader;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Located extraction. Vision additions are labelled separately. Offsets use UTF-16. */
@Service
public class SourceDocumentParser {
    private static final Pattern STRUCTURAL_LINE = Pattern.compile(
            "^(?:#{1,6}\\s|[-*•]\\s|\\d+[.)、]\\s|```|~~~|(?:import|class|def|function)\\s).*");
    private final EnhancedPdfParser enhanced;
    public SourceDocumentParser() { this.enhanced = null; }
    @org.springframework.beans.factory.annotation.Autowired
    public SourceDocumentParser(EnhancedPdfParser enhanced) { this.enhanced = enhanced; }
    public record Block(int start, int end, Integer page, String title) {}
    public record ParseReport(String engine, int totalPages, int nativePages, int enhancedPages, int ocrPages,
                              int tables, int images, int visionCalls, int visionTokens, int cacheHits,
                              long elapsedMs, List<String> warnings) {}
    public record SourceDocument(String text, List<Block> blocks, ParseReport parseReport) {
        public SourceDocument(String text, List<Block> blocks) { this(text, blocks, null); }
    }
    public record LocatedChunk(String content, int start, int end, Integer page, String title) {}
    public record SourceView(String documentId, String fileName, int ordinal, boolean located,
                             Integer page, String title, int firstLine, int startOffset,
                             String text, int highlightStart, int highlightEnd, boolean originalPdf) {}
    public interface ProgressListener {
        default void onPdfPages(int total) {}
        default void onPdfPage(int done) {}
        default void onEnhancedPages(int total) {}
        default void onEnhancedPage(int done) {}
    }

    public static String retrievalText(String text, Integer page, String title) {
        String location = (page == null ? "" : "第 " + page + " 页 · ") + (title == null ? "" : title);
        return location.isBlank() ? text : "【" + location + "】\n" + text;
    }

    public SourceDocument parse(byte[] bytes, String filename) throws IOException {
        return parse(bytes, filename, null);
    }

    public SourceDocument parse(byte[] bytes, String filename, ProgressListener progress) throws IOException {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        StringBuilder text = new StringBuilder();
        List<Block> blocks = new ArrayList<>();
        ParseReport report = null;
        if (name.endsWith(".pdf")) {
            try (var pdf = Loader.loadPDF(bytes)) {
                long started = System.nanoTime();
                if (progress != null) progress.onPdfPages(pdf.getNumberOfPages());
                var stripper = new PdfPageInspector();
                var pages = stripper.extractPages(pdf, progress == null ? null : progress::onPdfPage);
                Map<Integer,String> nativeText = new LinkedHashMap<>();
                Map<Integer,Boolean> difficult = new LinkedHashMap<>();
                int visualPages=0, skippedVisualPages=0, fullPageScans=0;
                for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                    var pageText = pages.get(page-1);
                    String extracted = DocumentText.pdfProse(pageText.text());
                    nativeText.put(page, extracted);
                    if (enhanced != null) {
                        var graphics = new PdfPageInspector.Graphics(pdf.getPage(page-1));
                        graphics.inspect();
                        boolean force = PdfPageInspector.damaged(extracted) || extracted.strip().length()<30 && graphics.largestImageCoverage>=0.65;
                        if (force && extracted.strip().length()<30 && graphics.largestImageCoverage>=0.65)
                            fullPageScans++;
                        boolean structural = graphics.tableGrid() || pageText.complexColumns();
                        if(force || structural) difficult.put(page,force);
                        else if(graphics.substantialImage || graphics.diagram()) {
                            if(visualPages < enhanced.visualPageBudget()) {difficult.put(page,false);visualPages++;}
                            else skippedVisualPages++;
                        }
                    }
                }
                // A mostly image-only book has no layout to preserve from native PDF text.
                // Reuse the local OCR models directly, page by page. Mixed and structured
                // files retain Docling's layout/table path.
                boolean fastScannedBook = pdf.getNumberOfPages() >= 80
                        && fullPageScans >= Math.ceil(pdf.getNumberOfPages() * 0.9);
                if (progress != null && !difficult.isEmpty()) progress.onEnhancedPages(difficult.size());
                var parsed = enhanced != null && !difficult.isEmpty()
                        ? enhanced.enhance(pdf, difficult, fastScannedBook,
                                done -> { if (progress != null) progress.onEnhancedPage(done); }) : null;
                for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                    if (!difficult.containsKey(page)) append(text, blocks, nativeText.get(page), page, "第 " + page + " 页");
                    else {
                        final int current=page;
                        StringBuilder prose = new StringBuilder();
                        String pageTitle = "第 " + page + " 页" + (fastScannedBook ? "" : " · 增强解析");
                        for (var segment : parsed.segments().stream().filter(s->s.page()==current).toList()) {
                            if ("text".equals(segment.kind())) {
                                if (!prose.isEmpty()) prose.append("\n\n");
                                prose.append(DocumentText.pdfProse(segment.text()));
                            } else {
                                append(text, blocks, prose.toString(), page, pageTitle); prose.setLength(0);
                                if("table".equals(segment.kind())) appendTable(text, blocks, segment.text(), page);
                                else append(text, blocks, normalize(segment.text()), page, "第 " + page + " 页 · AI 图表描述（需核验）");
                            }
                        }
                        append(text, blocks, prose.toString(), page, pageTitle);
                    }
                }
                report = parsed == null ? new ParseReport("PDFBox",pdf.getNumberOfPages(),pdf.getNumberOfPages(),0,0,0,0,0,0,0,0,List.of()) : parsed.report();
                List<String> warnings = new ArrayList<>(report.warnings());
                if(skippedVisualPages>0) warnings.add("为控制解析时间和费用，另有 " + skippedVisualPages + " 页保留文字但未补充图表理解；需要时可提高 PDF_VISION_MAX_IMAGES 后重新解析");
                report = new ParseReport(report.engine(),report.totalPages(),report.nativePages(),report.enhancedPages(),report.ocrPages(),
                        report.tables(),report.images(),report.visionCalls(),report.visionTokens(),report.cacheHits(),
                        (System.nanoTime()-started)/1_000_000,List.copyOf(warnings));
            }
        } else if (name.endsWith(".docx")) {
            try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                // bodyElements retains the original paragraph/table order.
                StringBuilder body = new StringBuilder();
                for (IBodyElement element : doc.getBodyElements()) {
                    if (element instanceof XWPFParagraph p) body.append(p.getText()).append('\n');
                    if (element instanceof XWPFTable table) {
                        for (var row : table.getRows()) {
                            body.append(String.join("\t", row.getTableCells().stream()
                                    .map(XWPFTableCell::getText)
                                    .map(com.aiproject.aiassitant.module.documentqa.service.DelimitedTableReader::encode).toList())).append('\n');
                        }
                        body.append('\n');
                    }
                }
                appendHeadings(text, blocks, normalize(body.toString()));
                report = new ParseReport("DOCX/escaped-cells-v1",0,0,0,0,0,0,0,0,0,0,List.of());
            }
        } else if (name.endsWith(".xlsx")) {
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                DataFormatter formatter = new DataFormatter(Locale.ROOT);
                for (var sheet : workbook) {
                    StringBuilder body = new StringBuilder("## " + sheet.getSheetName() + "\n");
                    for (var row : sheet) {
                        for (int column = 0; column < row.getLastCellNum(); column++) {
                            if (column > 0) body.append('\t');
                            body.append(com.aiproject.aiassitant.module.documentqa.service.DelimitedTableReader.encode(
                                    formatter.formatCellValue(row.getCell(column))));
                        }
                        body.append('\n');
                    }
                    append(text, blocks, normalize(body.toString()), null, sheet.getSheetName());
                }
                report = new ParseReport("XLSX/escaped-cells-v1",0,0,0,0,0,0,0,0,0,0,List.of());
            }
        } else if (name.endsWith(".csv")) {
            String raw;
            String encoding = "UTF-8";
            try {
                raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            } catch (java.nio.charset.CharacterCodingException e) {
                encoding = "Windows-1252";
                raw = new String(bytes, java.nio.charset.Charset.forName(encoding));
            }
            var records = com.aiproject.aiassitant.module.documentqa.service.DelimitedTableReader.csv(raw);
            StringBuilder body = new StringBuilder();
            for (var record : records) {
                if (record.size() == 1 && record.get(0).isBlank()) continue;
                body.append(String.join("\t", record.stream().map(
                        com.aiproject.aiassitant.module.documentqa.service.DelimitedTableReader::encode).toList())).append('\n');
            }
            append(text, blocks, body.toString(), null, "CSV records");
            report = new ParseReport("CSV/" + encoding, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0,
                    encoding.equals("UTF-8") ? List.of() : List.of("CSV 非 UTF-8，按 Windows-1252 解码；请核对文字编码"));
        } else {
            String raw = name.endsWith(".md") || name.endsWith(".txt")
                    ? new String(bytes, StandardCharsets.UTF_8)
                    : new DocumentParser().parse(new ByteArrayInputStream(bytes), filename);
            appendHeadings(text, blocks, normalize(raw));
        }
        return new SourceDocument(text.toString(), List.copyOf(blocks), report);
    }

    /** Repeat table headers in each bounded row group so retrieval retains column meaning. */
    void appendTable(StringBuilder text, List<Block> blocks, String table, int page) {
        String[] rows=normalize(table).split("\n");
        int headerIndex = -1;
        for (int i=0; i+1<rows.length; i++) {
            if (rows[i].strip().startsWith("|") && rows[i+1].matches("[\\s|:\\-]+") && rows[i+1].contains("---")) {
                headerIndex=i; break;
            }
        }
        if(headerIndex<0){append(text,blocks,table,page,"第 "+page+" 页 · 表格");return;}
        // Docling may prefix a caption. It is not a column header. Remove padding
        // only, preserve every cell, and repeat caption + actual headers per group.
        for (int i=headerIndex;i<rows.length;i++) {
            if(rows[i].strip().startsWith("|")) {
                String[] cells=rows[i].strip().split("\\|",-1);
                for(int c=0;c<cells.length;c++) {
                    cells[c]=cells[c].strip();
                    if(i==headerIndex+1 && cells[c].matches(":?-{3,}:?")) cells[c]="---";
                }
                rows[i]=String.join(" | ",Arrays.copyOfRange(cells,1,cells.length-1));
                rows[i]="| "+rows[i]+" |";
            }
        }
        String prefix=String.join("\n",Arrays.copyOfRange(rows,0,headerIndex)).strip();
        String header=(prefix.isBlank()?"":prefix+"\n")+rows[headerIndex]+"\n"+rows[headerIndex+1]+"\n";
        StringBuilder group=new StringBuilder(header);
        for(int i=headerIndex+2;i<rows.length;i++) {
            if(group.length()+rows[i].length()>1400 && group.length()>header.length()) {
                append(text,blocks,group.toString(),page,"第 "+page+" 页 · 表格");group=new StringBuilder(header);
            }
            group.append(rows[i]).append('\n');
        }
        append(text,blocks,group.toString(),page,"第 "+page+" 页 · 表格");
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "");
    }

    private void appendHeadings(StringBuilder text, List<Block> blocks, String raw) {
        var matcher = Pattern.compile("^#{1,6}\\s+(.+)$", Pattern.MULTILINE).matcher(raw);
        var fences = Pattern.compile("^\\s*(`{3,}|~{3,}).*$", Pattern.MULTILINE).matcher(raw);
        List<Integer> fencePositions = new ArrayList<>();
        while (fences.find()) fencePositions.add(fences.start());
        int start = 0;
        String title = "正文";
        while (matcher.find()) {
            if (fencePositions.stream().filter(position -> position < matcher.start()).count() % 2 == 1) continue;
            if (matcher.start() > start) append(text, blocks, raw.substring(start, matcher.start()), null, title);
            start = matcher.start();
            title = matcher.group(1).replaceAll("\\s*\\{#[^}]+}\\s*$", "");
        }
        if (start < raw.length()) append(text, blocks, raw.substring(start), null, title);
    }

    private void append(StringBuilder text, List<Block> blocks, String content, Integer page, String title) {
        if (content.isBlank()) return;
        if (!text.isEmpty()) text.append('\n');
        int start = text.length();
        text.append(content);
        blocks.add(new Block(start, text.length(), page, title));
    }

    /** Splits within pages/sections, retaining exact substrings and bounded overlap. */
    public List<LocatedChunk> chunks(SourceDocument document, int size, int overlap) {
        size = Math.max(100, Math.min(size, 4000));
        overlap = Math.max(0, Math.min(overlap, size / 2));
        List<LocatedChunk> result = new ArrayList<>();
        for (Block block : document.blocks()) {
            int blockSize = block.title()!=null && block.title().endsWith(" · 表格") ? Math.max(size, 2000) : size;
            // English prose uses ~4-5 characters per token. A 500-character block
            // frequently separated a definition from its numeric parameters.
            // Keep the public preset but give predominantly Latin prose ~250 tokens.
            if (blockSize == size && size == 500 && overlap == 80 && isEnglishProse(document, block))
                blockSize = 1200;
            // The regular 500/80 preset needlessly divides ordinary book pages in two. Keep
            // explicit non-default settings, tables, enhanced pages and short documents intact.
            boolean adaptive = blockSize == size && size == 500 && overlap == 80
                    && isLongFormProse(document, block);
            if (adaptive)
                blockSize = 1000;
            int blockOverlap = block.title()!=null && block.title().endsWith(" · 表格") ? 0 : overlap;
            int start = block.start();
            while (start < block.end()) {
                int end = Math.min(start + blockSize, block.end());
                if (end < block.end()) {
                    end = DocumentText.end(document.text(), start, end, block.end());
                    if (Character.isHighSurrogate(document.text().charAt(end - 1))) end--;
                }
                String content = document.text().substring(start, end);
                if (!content.isBlank()) result.add(new LocatedChunk(content, start, end, block.page(), block.title()));
                if (end == block.end()) break;
                start = DocumentText.overlapStart(document.text(), Math.max(start + 1, end - blockOverlap), end);
            }
        }
        return result;
    }

    private static boolean isEnglishProse(SourceDocument document, Block block) {
        String content = document.text().substring(block.start(), block.end());
        long latin = content.chars().filter(c -> c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z').count();
        return content.length() >= 550 && latin > content.length() * 0.6 &&
                !content.contains("```") && !content.contains("\n|");
    }

    private static boolean isLongFormProse(SourceDocument document, Block block) {
        ParseReport report = document.parseReport();
        if (report == null || report.totalPages() < 80 || block.page() == null ||
                !Objects.equals(block.title(), "第 " + block.page() + " 页") || block.end() - block.start() < 550)
            return false;
        String page = document.text().substring(block.start(), block.end());
        String[] lines = page.split("\\n");
        int nonBlank = 0, structural = 0, sentenceEnds = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            nonBlank++;
            if (STRUCTURAL_LINE.matcher(trimmed).matches() ||
                    trimmed.indexOf('|') >= 0 || trimmed.indexOf('{') >= 0 || trimmed.indexOf('}') >= 0)
                structural++;
        }
        for (int i = 0; i < page.length(); i++) {
            char c = page.charAt(i);
            if (c == '。' || c == '！' || c == '？' ||
                    ((c == '.' || c == '!' || c == '?') && i + 1 < page.length() && Character.isWhitespace(page.charAt(i + 1))))
                sentenceEnds++;
        }
        return nonBlank >= 6 && structural <= Math.max(1, nonBlank / 10)
                && sentenceEnds >= Math.max(4, page.length() / 180);
    }

    public static SourceView view(String id, String filename, int ordinal, SourceDocument source,
                                  Integer start, Integer end, String fallback) {
        if (source == null || start == null || end == null || start < 0 || end > source.text().length()
                || end <= start || !source.text().substring(start, end).equals(fallback)) {
            return new SourceView(id, filename, ordinal, false, null, null, 1, 0, fallback, 0, fallback.length(), false);
        }
        Block block = source.blocks().stream().filter(b -> start >= b.start() && end <= b.end()).findFirst().orElse(null);
        int from = block == null ? Math.max(0, start - 1500) : Math.max(block.start(), start - 3000);
        int to = block == null ? Math.min(source.text().length(), end + 1500) : Math.min(block.end(), end + 3000);
        if (from > 0 && Character.isLowSurrogate(source.text().charAt(from))) from--;
        if (to < source.text().length() && Character.isHighSurrogate(source.text().charAt(to - 1))) to++;
        int line = 1 + (int) source.text().substring(0, from).chars().filter(c -> c == '\n').count();
        return new SourceView(id, filename, ordinal, true, block == null ? null : block.page(),
                block == null ? null : block.title(), line, from, source.text().substring(from, to),
                start - from, end - from, id != null && filename.toLowerCase(Locale.ROOT).endsWith(".pdf"));
    }
}
