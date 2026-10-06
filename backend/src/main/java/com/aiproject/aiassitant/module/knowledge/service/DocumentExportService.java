package com.aiproject.aiassitant.module.knowledge.service;

import com.aiproject.aiassitant.common.BizException;
import com.aiproject.aiassitant.common.SecurityUtil;
import com.aiproject.aiassitant.module.knowledge.entity.KbDocument;
import com.aiproject.aiassitant.module.knowledge.mapper.KbDocumentMapper;
import com.aiproject.aiassitant.security.AppPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentExportService {

    private final KbDocumentMapper documentMapper;
    private final DocumentParser documentParser;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Path UPLOAD_BASE = Path.of("uploads/knowledge").toAbsolutePath().normalize();

    // ── Public API ──

    public ExportResult exportDocument(String documentId, String format) throws IOException {
        KbDocument doc = getDocumentWithAccessCheck(documentId);
        String content = loadDocumentContent(doc);
        if (content.isBlank()) {
            throw new BizException("文档内容为空，无法导出");
        }

        String ext = toFileExtension(format);
        String safeName = sanitizeFilename(doc.getFilename());
        String filename = safeName + "_" + nowCompact() + "." + ext;

        byte[] data = switch (format.toLowerCase()) {
            case "md"   -> exportMarkdown(doc, content);
            case "pdf"  -> exportPdf(doc, content);
            case "docx" -> exportWord(doc, content);
            default     -> throw new BizException("不支持的格式: " + format + "，支持: md, pdf, docx");
        };

        String mime = switch (ext) {
            case "md"   -> "text/markdown; charset=UTF-8";
            case "pdf"  -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default     -> "application/octet-stream";
        };

        return new ExportResult(filename, mime, data);
    }

    public ExportResult exportBatch(List<String> documentIds, String format) throws IOException {
        if (documentIds == null || documentIds.isEmpty()) {
            throw new BizException("请选择至少一个文档");
        }

        List<KbDocument> docs = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        for (String id : documentIds) {
            KbDocument doc = getDocumentWithAccessCheck(id);
            String content = loadDocumentContent(doc);
            if (content.isBlank()) continue;
            docs.add(doc);
            contents.add(content);
        }

        if (docs.isEmpty()) {
            throw new BizException("所选文档均无内容可导出");
        }

        String ext = toFileExtension(format);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Set<String> usedNames = new HashSet<>();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (int i = 0; i < docs.size(); i++) {
                KbDocument doc = docs.get(i);
                String content = contents.get(i);

                byte[] fileData = switch (format.toLowerCase()) {
                    case "md"   -> exportMarkdown(doc, content);
                    case "pdf"  -> exportPdf(doc, content);
                    case "docx" -> exportWord(doc, content);
                    default     -> throw new BizException("不支持的格式: " + format);
                };

                String safeName = sanitizeFilename(doc.getFilename());
                String baseName = safeName + "." + ext;
                String entryName = baseName;

                // Dedup: if same filename exists, append (2), (3), etc.
                int dup = 2;
                while (usedNames.contains(entryName)) {
                    int dot = baseName.lastIndexOf('.');
                    entryName = baseName.substring(0, dot) + "(" + dup + ")" + baseName.substring(dot);
                    dup++;
                }
                usedNames.add(entryName);

                ZipEntry entry = new ZipEntry(entryName);
                zos.putNextEntry(entry);
                zos.write(fileData);
                zos.closeEntry();
            }
        }

        String zipName = "documents_export_" + nowCompact() + ".zip";
        return new ExportResult(zipName, "application/zip", baos.toByteArray());
    }

    // ── Format generators ──

    private byte[] exportMarkdown(KbDocument doc, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(doc.getFilename()).append("\n\n");
        sb.append("> **文档 ID**: `").append(doc.getId()).append("`  \n");
        sb.append("> **导出时间**: ").append(LocalDateTime.now().format(FMT)).append("  \n");
        sb.append("> **原始格式**: ").append(doc.getMimeType() != null ? doc.getMimeType() : "未知").append("  \n");
        sb.append("> **大小**: ").append(formatBytes(doc.getSizeBytes())).append("\n\n");
        sb.append("---\n\n");
        sb.append(content);
        return addUtf8Bom(sb.toString());
    }

    private byte[] exportPdf(KbDocument doc, String content) throws IOException {
        try (PDDocument pdf = new PDDocument()) {

            PDType0Font font = loadCjkFont(pdf);
            PDType0Font boldFont = loadCjkBoldFont(pdf);
            float fontSize = 11f;
            float smallSize = 8f;
            float titleSize = 16f;
            float margin = 50f;
            float pageW = PDRectangle.A4.getWidth();
            float pageH = PDRectangle.A4.getHeight();
            float leading = fontSize * 1.6f;

            PDPage[] pageHolder = {new PDPage(PDRectangle.A4)};
            pdf.addPage(pageHolder[0]);
            float[] yHolder = {pageH - margin};
            PDPageContentStream[] csHolder = {new PDPageContentStream(pdf, pageHolder[0])};
            csHolder[0].setFont(font, fontSize);

            // Title
            pdfWriteLine(pdf, pageHolder, csHolder, yHolder, boldFont, titleSize,
                    sanitizeForPdf(doc.getFilename()), margin, pageW, pageH, titleSize * 1.6f);
            yHolder[0] -= 8;

            // Metadata
            String[] meta = {
                    "文档 ID: " + doc.getId(),
                    "导出时间: " + LocalDateTime.now().format(FMT),
                    "原始格式: " + (doc.getMimeType() != null ? doc.getMimeType() : "未知"),
                    "大小: " + formatBytes(doc.getSizeBytes()),
            };
            for (String line : meta) {
                pdfWriteLine(pdf, pageHolder, csHolder, yHolder, font, smallSize,
                        line, margin, pageW, pageH, smallSize * 1.4f);
            }
            yHolder[0] -= 4;

            // Separator
            pdfLine(csHolder[0], margin, yHolder[0], pageW - margin);
            yHolder[0] -= 14;

            // Content — strip emoji + control chars the CJK font can't render
            String clean = sanitizeForPdf(content
                    .replaceAll("```[\\s\\S]*?```", "[代码块]")
                    .replaceAll("`([^`]+)`", "$1"));
            for (String para : clean.split("\n\n")) {
                String trimmed = para.trim();
                if (trimmed.isEmpty()) continue;
                for (String line : trimmed.split("\n")) {
                    if (line.trim().isEmpty()) {
                        yHolder[0] -= 4;
                        continue;
                    }
                    pdfWriteLine(pdf, pageHolder, csHolder, yHolder, font, fontSize,
                            line, margin, pageW, pageH, leading);
                }
                yHolder[0] -= 4;
            }

            csHolder[0].close();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pdf.save(baos);
            return baos.toByteArray();
        }
    }

    private byte[] exportWord(KbDocument doc, String content) throws IOException {
        try (XWPFDocument word = new XWPFDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            // Title
            XWPFParagraph titlePara = word.createParagraph();
            titlePara.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = titlePara.createRun();
            titleRun.setText(doc.getFilename());
            titleRun.setBold(true);
            titleRun.setFontSize(18);
            titleRun.setFontFamily("Microsoft YaHei");

            // Metadata table
            XWPFParagraph metaPara = word.createParagraph();
            metaPara.setAlignment(ParagraphAlignment.RIGHT);
            XWPFRun metaRun = metaPara.createRun();
            metaRun.setText("文档 ID: " + doc.getId() + " | 导出时间: " + LocalDateTime.now().format(FMT));
            metaRun.setFontSize(8);
            metaRun.setColor("888888");
            metaRun.setFontFamily("Microsoft YaHei");

            // Separator
            XWPFParagraph hr = word.createParagraph();
            hr.setBorderBottom(Borders.SINGLE);

            // Content — write as styled paragraphs
            String[] lines = content.split("\n");
            boolean inCodeBlock = false;
            StringBuilder codeBuf = new StringBuilder();

            for (String rawLine : lines) {
                String line = rawLine;

                if (line.trim().startsWith("```")) {
                    if (inCodeBlock) {
                        inCodeBlock = false;
                        XWPFParagraph cp = word.createParagraph();
                        ensurePPr(cp).addNewShd().setFill("1e1e1f");
                        XWPFRun cr = cp.createRun();
                        cr.setText(codeBuf.toString().stripTrailing());
                        cr.setFontFamily("Consolas");
                        cr.setFontSize(9);
                        cr.setColor("d4d4d4");
                        codeBuf.setLength(0);
                    } else {
                        inCodeBlock = true;
                    }
                    continue;
                }

                if (inCodeBlock) {
                    codeBuf.append(line).append("\n");
                    continue;
                }

                if (line.trim().isEmpty()) {
                    word.createParagraph();
                    continue;
                }

                // Heading
                if (line.matches("^#{1,6}\\s.*")) {
                    int level = 0;
                    while (level < line.length() && line.charAt(level) == '#') level++;
                    String headingText = line.substring(level).trim();
                    XWPFParagraph hp = word.createParagraph();
                    hp.setStyle("Heading" + Math.min(level, 6));
                    XWPFRun hr2 = hp.createRun();
                    hr2.setText(headingText);
                    hr2.setBold(true);
                    hr2.setFontSize(18 - level * 2);
                    hr2.setFontFamily("Microsoft YaHei");
                    continue;
                }

                // Regular paragraph
                XWPFParagraph pp = word.createParagraph();
                XWPFRun pr = pp.createRun();
                pr.setText(line);
                pr.setFontSize(11);
                pr.setFontFamily("Microsoft YaHei");
            }

            // Unclosed code block
            if (inCodeBlock && !codeBuf.isEmpty()) {
                XWPFParagraph cp = word.createParagraph();
                ensurePPr(cp).addNewShd().setFill("1e1e1f");
                XWPFRun cr = cp.createRun();
                cr.setText(codeBuf.toString().stripTrailing());
                cr.setFontFamily("Consolas");
                cr.setFontSize(9);
                cr.setColor("d4d4d4");
            }

            word.write(baos);
            return baos.toByteArray();
        }
    }

    // ── Content loading ──

    /**
     * Load the full text content of a document.
     * Uses Tika to parse the original file, same as the processing pipeline.
     */
    private String loadDocumentContent(KbDocument doc) {
        File file = resolveDocumentFile(doc);
        if (file != null) {
            String filename = doc.getFilename() != null ? doc.getFilename().toLowerCase() : "";
            // Read plain-text formats directly — more reliable than Tika
            if (filename.endsWith(".md") || filename.endsWith(".txt")) {
                try {
                    String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                    if (text != null && !text.isBlank()) return text;
                } catch (Exception e) {
                    log.warn("Failed to read text file {}: {}", doc.getId(), e.getMessage());
                }
            } else {
                try (InputStream is = new FileInputStream(file)) {
                    String text = documentParser.parse(is, doc.getFilename());
                    if (text != null && !text.isBlank()) return text;
                } catch (Exception e) {
                    log.warn("Failed to parse document file {}: {}", doc.getId(), e.getMessage());
                }
            }
        }

        throw new BizException("文档文件不存在或无法读取: " + doc.getFilename());
    }

    /** Resolve document file safely, same logic as KnowledgeController. */
    private File resolveDocumentFile(KbDocument doc) {
        String docId = doc.getId();
        if (docId == null || !docId.matches("^[a-zA-Z0-9_-]+$")) {
            log.warn("Rejected invalid document ID: {}", docId);
            return null;
        }

        // 1. Try stored path
        String stored = doc.getStoragePath();
        if (stored != null && !stored.isBlank()) {
            Path p = Path.of(stored).toAbsolutePath().normalize();
            if (p.startsWith(UPLOAD_BASE)) {
                File f = p.toFile();
                if (f.exists()) return f;
            }
        }

        // 2. Try uploads/knowledge/{docId}/
        Path safeDir = UPLOAD_BASE.resolve(docId);
        if (safeDir.toFile().isDirectory()) {
            File[] files = safeDir.toFile().listFiles((dir, name) -> !"content.md".equals(name));
            if (files != null && files.length > 0) return files[0];
            File contentMd = safeDir.resolve("content.md").toFile();
            if (contentMd.exists()) return contentMd;
        }

        return null;
    }

    // ── Access control ──

    private KbDocument getDocumentWithAccessCheck(String documentId) {
        KbDocument doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw new BizException("文档不存在");
        }

        String currentUserId = SecurityUtil.getCurrentUserId();
        boolean isAdmin = isCurrentUserAdmin();

        if (!isAdmin && !currentUserId.equals(doc.getUserId())) {
            throw new BizException("无权导出该文档");
        }

        return doc;
    }

    private boolean isCurrentUserAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppPrincipal principal) {
            return principal.isAdmin();
        }
        return false;
    }

    // ── PDF helpers ──

    private void pdfWriteLine(PDDocument doc, PDPage[] page, PDPageContentStream[] cs,
                               float[] y, PDType0Font font, float size, String text,
                               float x, float pageW, float pageH, float leading) throws IOException {
        for (String line : text.split("\n")) {
            List<String> chunks = wrapText(line, font, size, pageW - 2 * x);
            if (chunks.isEmpty()) chunks = List.of("");
            for (String chunk : chunks) {
                if (y[0] < 60) pdfNewPage(doc, page, cs, y, font, pageH);
                cs[0].beginText();
                cs[0].setFont(font, size);
                cs[0].newLineAtOffset(x, y[0]);
                cs[0].showText(chunk);
                cs[0].endText();
                y[0] -= leading;
            }
        }
    }

    private void pdfNewPage(PDDocument doc, PDPage[] page, PDPageContentStream[] cs,
                             float[] y, PDType0Font font, float pageH) throws IOException {
        cs[0].close();
        page[0] = new PDPage(PDRectangle.A4);
        doc.addPage(page[0]);
        y[0] = pageH - 50;
        cs[0] = new PDPageContentStream(doc, page[0]);
        cs[0].setFont(font, 11f);
    }

    private void pdfLine(PDPageContentStream cs, float x1, float y, float x2) throws IOException {
        cs.setLineWidth(0.3f);
        cs.moveTo(x1, y);
        cs.lineTo(x2, y);
        cs.stroke();
    }

    private List<String> wrapText(String text, PDType0Font font, float size, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (char c : text.toCharArray()) {
            String test = current.toString() + c;
            try {
                if (font.getStringWidth(test) / 1000 * size > maxWidth && !current.isEmpty()) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                current.append(c);
            } catch (IllegalArgumentException e) {
                // Glyph missing (emoji etc.) — replace with space
                current.append(' ');
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());
        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    /** Strip emoji (surrogates) and control chars that CJK fonts can't render. */
    private String sanitizeForPdf(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isSurrogate(c)) {
                i++; // skip low surrogate
                sb.append(' ');
            } else if (c < 0x20 && c != '\n' && c != '\r') {
                // Strip control chars (tab, bell, etc.) — no glyph in CJK fonts
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ── CJK Font loading ──

    private PDType0Font loadCjkFont(PDDocument doc) throws IOException {
        for (String path : TTC_PATHS) {
            File f = new File(path);
            if (!f.exists()) continue;
            try (TrueTypeCollection ttc = new TrueTypeCollection(f)) {
                TrueTypeFont[] holder = new TrueTypeFont[1];
                ttc.processAllFonts(tf -> { if (holder[0] == null) holder[0] = tf; });
                if (holder[0] != null) {
                    return PDType0Font.load(doc, holder[0], true);
                }
            } catch (Exception e) {
                log.debug("TTC {}: {}", path, e.getMessage());
            }
        }
        for (String path : TTF_PATHS) {
            File f = new File(path);
            if (!f.exists()) continue;
            try {
                return PDType0Font.load(doc, f);
            } catch (Exception e) {
                log.debug("TTF {}: {}", path, e.getMessage());
            }
        }
        throw new IOException("No CJK font found. Searched: " + String.join(", ", TTC_PATHS) + " and " + String.join(", ", TTF_PATHS));
    }

    private PDType0Font loadCjkBoldFont(PDDocument doc) throws IOException {
        return loadCjkFont(doc);
    }

    private static final String[] TTC_PATHS = {
            "C:/Windows/Fonts/msyh.ttc",
            "C:/Windows/Fonts/simsun.ttc",
            "/System/Library/Fonts/PingFang.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    };
    private static final String[] TTF_PATHS = {
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/arial.ttf",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
    };

    // ── Helpers ──

    private String toFileExtension(String format) {
        return switch (format.toLowerCase()) {
            case "md"   -> "md";
            case "pdf"  -> "pdf";
            case "docx" -> "docx";
            default     -> format;
        };
    }

    private String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "document";
        // Strip extension, then sanitize
        String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        return base.replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_")
                .replaceAll("\\s+", "_")
                .replaceAll("_{2,}", "_")
                .replaceAll("^_|_$", "");
    }

    private String formatBytes(Long bytes) {
        if (bytes == null) return "未知";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private byte[] addUtf8Bom(String text) {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[bom.length + utf8.length];
        System.arraycopy(bom, 0, result, 0, bom.length);
        System.arraycopy(utf8, 0, result, bom.length, utf8.length);
        return result;
    }

    /** Ensure the paragraph has a PPr element, creating one if needed. */
    private org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr ensurePPr(XWPFParagraph p) {
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP ctp = p.getCTP();
        if (!ctp.isSetPPr()) ctp.addNewPPr();
        return ctp.getPPr();
    }

    private String nowCompact() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }

    // ── Result ──

    public record ExportResult(String filename, String mimeType, byte[] data) {}
}
