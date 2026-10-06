package com.aiproject.aiassitant.module.chat.service;

import com.aiproject.aiassitant.module.chat.entity.ChatMessage;
import com.aiproject.aiassitant.module.chat.entity.ChatSession;
import com.aiproject.aiassitant.module.chat.mapper.ChatMessageMapper;
import com.aiproject.aiassitant.common.SecurityUtil;
import com.aiproject.aiassitant.module.chat.entity.ChatMessage;
import com.aiproject.aiassitant.module.chat.entity.ChatSession;
import com.aiproject.aiassitant.module.chat.mapper.ChatMessageMapper;
import com.aiproject.aiassitant.module.chat.mapper.ChatSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ── Public API ──

    public ExportResult export(String sessionId, String format) throws IOException {
        return export(sessionId, format, null);
    }

    public ExportResult export(String sessionId, String format, List<String> messageIds) throws IOException {
        String userId = SecurityUtil.getCurrentUserId();
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) throw new ExportException(404, "会话不存在");
        if (!userId.equals(session.getUserId())) throw new ExportException(403, "无权访问该会话");

        List<ChatMessage> allMessages = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getCreatedAt));
        if (allMessages.isEmpty()) throw new ExportException(400, "该会话暂无消息可导出");

        // Filter by messageIds if specified
        List<ChatMessage> messages;
        if (messageIds != null && !messageIds.isEmpty()) {
            java.util.Set<String> idSet = new java.util.HashSet<>(messageIds);
            messages = allMessages.stream()
                    .filter(m -> idSet.contains(m.getId()))
                    .toList();
            if (messages.isEmpty()) throw new ExportException(400, "未选中任何消息");
        } else {
            messages = allMessages;
        }

        // Meaningful filename: session title > first user message > fallback
        String rawTitle = session.getTitle();
        if (rawTitle == null || rawTitle.isBlank() || rawTitle.length() < 3) {
            rawTitle = allMessages.stream()
                    .filter(m -> "user".equals(m.getRole()))
                    .findFirst()
                    .map(m -> m.getContent().length() > 40 ? m.getContent().substring(0, 40) : m.getContent())
                    .orElse("对话导出");
        }
        String title = rawTitle;
        String ext = toFileExtension(format);
        // Build filename: clean Chinese-friendly name
        String safeName = rawTitle
                .replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_")  // strip illegal file chars
                .replaceAll("\\s+", "_")                   // spaces → underscores
                .replaceAll("_{2,}", "_")                  // collapse multiple underscores
                .replaceAll("^_|_$", "");                  // trim leading/trailing underscores
        if (safeName.length() > 80) safeName = safeName.substring(0, 80);
        if (safeName.isBlank()) safeName = "export";
        String filename = safeName + "_" + nowCompact() + "." + ext;

        byte[] data = switch (format.toLowerCase()) {
            case "txt"  -> exportTxt(title, messages);
            case "md"   -> exportMarkdown(title, messages);
            case "pdf"  -> exportPdf(title, messages);
            case "word" -> exportWord(title, messages);
            default     -> throw new ExportException(400, "不支持的格式: " + format);
        };

        String mime = switch (ext) {
            case "txt"  -> "text/plain; charset=UTF-8";
            case "md"   -> "text/markdown; charset=UTF-8";
            case "pdf"  -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default     -> "application/octet-stream";
        };

        return new ExportResult(filename, mime, data);
    }

    // ── TXT ──

    private byte[] exportTxt(String title, List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n");
        sb.append("导出时间：").append(LocalDateTime.now().format(FMT)).append("\n\n");

        for (ChatMessage msg : messages) {
            String role = "user".equals(msg.getRole()) ? "用户" : "AI 助手";
            String time = msg.getCreatedAt() != null ? msg.getCreatedAt().format(FMT) : "";
            sb.append("============================================================\n");
            sb.append(role).append(" (").append(time).append(")：\n");
            // Strip basic markdown formatting for plain text readability
            sb.append(stripBasicMd(msg.getContent())).append("\n");
        }
        sb.append("============================================================\n");
        return addUtf8Bom(sb.toString());
    }

    // ── Markdown ──

    private byte[] exportMarkdown(String title, List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        sb.append("> 导出时间：").append(LocalDateTime.now().format(FMT)).append("\n\n");
        sb.append("---\n\n");

        for (ChatMessage msg : messages) {
            String time = msg.getCreatedAt() != null ? msg.getCreatedAt().format(FMT) : "";
            if ("user".equals(msg.getRole())) {
                sb.append("#### 用户 (").append(time).append(")\n\n");
                sb.append("> ").append(msg.getContent().replace("\n", "\n> ")).append("\n\n");
            } else if ("assistant".equals(msg.getRole())) {
                sb.append("#### AI 助手 (").append(time).append(")\n\n");
                sb.append(msg.getContent()).append("\n\n");
            } else if ("system".equals(msg.getRole())) {
                sb.append("#### 系统 (").append(time).append(")\n\n");
                sb.append("*").append(msg.getContent()).append("*\n\n");
            }
            sb.append("---\n\n");
        }
        return addUtf8Bom(sb.toString());
    }

    // ── PDF (Apache PDFBox) ──

    private byte[] exportPdf(String title, List<ChatMessage> messages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            title = stripEmoji(title);

            PDType0Font font = loadCjkFont(doc);
            PDType0Font boldFont = loadCjkBoldFont(doc);
            float fontSize = 11f;
            float smallSize = 8f;
            float margin = 50f;
            float pageW = PDRectangle.A4.getWidth();
            float pageH = PDRectangle.A4.getHeight();
            float textW = pageW - 2 * margin;
            float leading = fontSize * 1.6f;

            // Mutable holders for page and y-position (updated across methods)
            PDPage[] pageHolder = { new PDPage(PDRectangle.A4) };
            doc.addPage(pageHolder[0]);
            float[] yHolder = { pageH - margin };
            PDPageContentStream[] csHolder = { new PDPageContentStream(doc, pageHolder[0]) };
            csHolder[0].setFont(font, fontSize);

            // Header
            pdfWriteLine(doc, pageHolder, csHolder, yHolder, boldFont, 16f, title, margin, pageW, pageH, leading);
            yHolder[0] -= 12;

            pdfWriteLine(doc, pageHolder, csHolder, yHolder, font, smallSize,
                    "导出时间：" + LocalDateTime.now().format(FMT), margin, pageW, pageH, smallSize * 1.4f);
            yHolder[0] -= 8;

            // Separator
            pdfLine(csHolder[0], margin, yHolder[0], pageW - margin);
            yHolder[0] -= 12;

            for (ChatMessage msg : messages) {
                // Check page space before header
                if (yHolder[0] < 80) pdfNewPage(doc, pageHolder, csHolder, yHolder, font, pageH);

                String roleLabel = "user".equals(msg.getRole()) ? "用户" :
                        "assistant".equals(msg.getRole()) ? "AI 助手" : "系统";
                String time = msg.getCreatedAt() != null ? msg.getCreatedAt().format(FMT) : "";

                // Role header
                pdfWriteLine(doc, pageHolder, csHolder, yHolder, boldFont, smallSize,
                        roleLabel + " (" + time + ")", margin, pageW, pageH, smallSize * 1.4f);
                yHolder[0] -= 2;

                // Content
                String content = msg.getContent();
                if (content != null && !content.isBlank()) {
                    String clean = stripEmoji(content
                            .replaceAll("```[\\s\\S]*?```", "[代码]")
                            .replaceAll("`([^`]+)`", "$1")
                            .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                            .replaceAll("\\*([^*]+)\\*", "$1"));
                    for (String para : clean.split("\n\n")) {
                        if (para.trim().isEmpty()) continue;
                        pdfWriteLine(doc, pageHolder, csHolder, yHolder, font, fontSize,
                                para.trim(), margin, pageW, pageH, leading);
                        yHolder[0] -= 2;
                    }
                }

                // Separator
                yHolder[0] -= 6;
                if (yHolder[0] < 60) pdfNewPage(doc, pageHolder, csHolder, yHolder, font, pageH);
                pdfLine(csHolder[0], margin, yHolder[0], pageW - margin);
                yHolder[0] -= 10;
            }

            csHolder[0].close();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    /** Write a single wrapped line of text to the PDF, handling page breaks. */
    private void pdfWriteLine(PDDocument doc, PDPage[] page, PDPageContentStream[] cs,
                               float[] y, PDType0Font font, float size, String text,
                               float x, float pageW, float pageH, float leading) throws IOException {
        for (String line : text.split("\n")) {
            // Wrap long lines
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
        List<String> lines = new java.util.ArrayList<>();
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
                // Glyph missing for this char (emoji etc.), replace with space
                current.append(' ');
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());
        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    // ── CJK Font loading ──

    /**
     * Load a CJK-capable font. Phase 1: TTC via FontBox. Phase 2: TTF/OTF direct.
     */
    private PDType0Font loadCjkFont(PDDocument doc) throws IOException {
        // Phase 1: TTC via FontBox processAllFonts (public API)
        for (String path : TTC_PATHS) {
            java.io.File f = new java.io.File(path);
            if (!f.exists()) continue;
            TrueTypeCollection ttc = null;
            try {
                ttc = new TrueTypeCollection(f);
                TrueTypeFont[] holder = new TrueTypeFont[1];
                ttc.processAllFonts(tf -> { if (holder[0] == null) holder[0] = tf; });
                if (holder[0] != null) {
                    return PDType0Font.load(doc, holder[0], true);
                }
            } catch (Exception e) {
                log.debug("TTC {}: {}", path, e.getMessage());
            } finally {
                if (ttc != null) { try { ttc.close(); } catch (Exception ignored) {} }
            }
        }

        // Phase 2: TTF/OTF direct
        for (String path : TTF_PATHS) {
            java.io.File f = new java.io.File(path);
            if (!f.exists()) continue;
            try {
                return PDType0Font.load(doc, f);
            } catch (Exception e) {
                log.debug("TTF {}: {}", path, e.getMessage());
            }
        }

        throw new IOException("No CJK font found. Searched " +
            String.join(", ", TTC_PATHS) + " and " + String.join(", ", TTF_PATHS));
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

    // ── Word (Apache POI) ──

    private byte[] exportWord(String title, List<ChatMessage> messages) throws IOException {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            // Title
            XWPFParagraph titlePara = doc.createParagraph();
            titlePara.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = titlePara.createRun();
            titleRun.setText(title);
            titleRun.setBold(true);
            titleRun.setFontSize(18);
            titleRun.setFontFamily("Microsoft YaHei");

            // Export time
            XWPFParagraph timePara = doc.createParagraph();
            timePara.setAlignment(ParagraphAlignment.RIGHT);
            XWPFRun timeRun = timePara.createRun();
            timeRun.setText("导出时间：" + LocalDateTime.now().format(FMT));
            timeRun.setFontSize(9);
            timeRun.setColor("888888");

            // Horizontal rule
            XWPFParagraph hr = doc.createParagraph();
            hr.setBorderBottom(Borders.SINGLE);

            for (ChatMessage msg : messages) {
                boolean isUser = "user".equals(msg.getRole());
                String roleLabel = isUser ? "用户" :
                        "assistant".equals(msg.getRole()) ? "AI 助手" : "系统";
                String time = msg.getCreatedAt() != null ? msg.getCreatedAt().format(FMT) : "";

                // Role header
                XWPFParagraph rolePara = doc.createParagraph();
                XWPFRun roleRun = rolePara.createRun();
                roleRun.setText(roleLabel + " (" + time + ")");
                roleRun.setBold(true);
                roleRun.setFontSize(10);
                roleRun.setColor(isUser ? "555555" : "1a56db");

                String content = msg.getContent();
                if (content != null && !content.isBlank()) {
                    writeMdToWord(doc, content, isUser);
                }
            }

            doc.write(baos);
            return baos.toByteArray();
        }
    }

    /** Parse Markdown-ish content into styled Word paragraphs. */
    private void writeMdToWord(XWPFDocument doc, String content, boolean isUser) {
        String[] lines = content.split("\n");
        boolean inCodeBlock = false;
        StringBuilder codeBuf = new StringBuilder();

        for (String rawLine : lines) {
            String line = rawLine;

            // Code fence toggle
            if (line.trim().startsWith("```")) {
                if (inCodeBlock) {
                    // Close code block
                    inCodeBlock = false;
                    XWPFParagraph cp = doc.createParagraph();
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
                doc.createParagraph(); // blank line
                continue;
            }

            // ── Heading detection ──
            if (line.matches("^#{1,6}\\s.*")) {
                int level = line.indexOf(' ');
                String headingText = line.substring(level + 1).replaceAll("^#+\\s*", "");
                XWPFParagraph hp = doc.createParagraph();
                hp.setStyle("Heading" + Math.min(level, 6));
                XWPFRun hr = hp.createRun();
                hr.setText(headingText);
                hr.setBold(true);
                hr.setFontSize(18 - level * 2);
                hr.setFontFamily("Microsoft YaHei");
                continue;
            }

            // ── Horizontal rule ──
            if (line.matches("^[-*_]{3,}$")) {
                XWPFParagraph hrp = doc.createParagraph();
                hrp.setBorderBottom(Borders.SINGLE);
                continue;
            }

            // ── Blockquote ──
            if (line.startsWith("> ")) {
                XWPFParagraph bp = doc.createParagraph();
                bp.setIndentationLeft(400);
                bp.setBorderLeft(Borders.SINGLE);
                XWPFRun br = bp.createRun();
                br.setText(line.substring(2).trim());
                br.setFontSize(10);
                br.setColor("666666");
                br.setItalic(true);
                br.setFontFamily("Microsoft YaHei");
                continue;
            }

            // ── Unordered list ──
            if (line.matches("^\\s*[-*+]\\s.*")) {
                XWPFParagraph lp = doc.createParagraph();
                lp.setIndentationLeft(400);
                XWPFRun lr = lp.createRun();
                lr.setText("• " + line.replaceFirst("^\\s*[-*+]\\s*", ""));
                lr.setFontSize(11);
                lr.setFontFamily("Microsoft YaHei");
                if (isUser) lr.setItalic(true);
                continue;
            }

            // ── Ordered list ──
            if (line.matches("^\\s*\\d+\\.\\s.*")) {
                XWPFParagraph lp = doc.createParagraph();
                lp.setIndentationLeft(400);
                XWPFRun lr = lp.createRun();
                lr.setText(line.trim());
                lr.setFontSize(11);
                lr.setFontFamily("Microsoft YaHei");
                if (isUser) lr.setItalic(true);
                continue;
            }

            // ── Regular paragraph with inline formatting ──
            XWPFParagraph pp = doc.createParagraph();
            writeInlineMd(pp, line, isUser);
        }

        // Unclosed code block
        if (inCodeBlock && !codeBuf.isEmpty()) {
            XWPFParagraph cp = doc.createParagraph();
            ensurePPr(cp).addNewShd().setFill("1e1e1f");
            XWPFRun cr = cp.createRun();
            cr.setText(codeBuf.toString().stripTrailing());
            cr.setFontFamily("Consolas");
            cr.setFontSize(9);
            cr.setColor("d4d4d4");
        }
    }

    /** Write a single line of text with bold/italic/code spans. */
    private void writeInlineMd(XWPFParagraph p, String line, boolean isUser) {
        // Tokenize: bold **...**, italic *...*, inline code `...`
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\*\\*(.+?)\\*\\*)|(\\*(.+?)\\*)|(`(.+?)`)").matcher(line);
        int lastEnd = 0;

        while (m.find()) {
            // Text before this match
            if (m.start() > lastEnd) {
                XWPFRun r = p.createRun();
                r.setText(line.substring(lastEnd, m.start()));
                r.setFontSize(11);
                r.setFontFamily("Microsoft YaHei");
                if (isUser) r.setItalic(true);
            }

            XWPFRun r = p.createRun();
            r.setFontSize(11);
            r.setFontFamily("Microsoft YaHei");

            if (m.group(1) != null) {        // **bold**
                r.setText(m.group(2));
                r.setBold(true);
            } else if (m.group(3) != null) { // *italic*
                r.setText(m.group(4));
                r.setItalic(true);
            } else if (m.group(5) != null) { // `code`
                r.setText(m.group(6));
                r.setFontFamily("Consolas");
                r.setFontSize(10);
                ensurePPr(p).addNewShd().setFill("F0F0F0");
            }
            lastEnd = m.end();
        }

        // Remaining text
        if (lastEnd < line.length()) {
            XWPFRun r = p.createRun();
            r.setText(line.substring(lastEnd));
            r.setFontSize(11);
            r.setFontFamily("Microsoft YaHei");
            if (isUser) r.setItalic(true);
        }
    }

    /** Ensure the paragraph has a PPr element, creating one if needed. */
    private org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr ensurePPr(XWPFParagraph p) {
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP ctp = p.getCTP();
        if (!ctp.isSetPPr()) ctp.addNewPPr();
        return ctp.getPPr();
    }

    // ── Helpers ──

    /** Strip basic Markdown tokens for plain-text readability. */
    private String stripBasicMd(String s) {
        if (s == null) return "";
        return s
            .replaceAll("```[\\s\\S]*?```", "[代码块]")
            .replaceAll("`([^`]+)`", "$1")
            .replaceAll("\\*\\*(.+?)\\*\\*", "$1")
            .replaceAll("\\*(.+?)\\*", "$1")
            .replaceAll("^#{1,6}\\s+", "")
            .replaceAll("^>\\s+", "");
    }

    private String toFileExtension(String format) {
        return switch (format.toLowerCase()) {
            case "txt"  -> "txt";
            case "md"   -> "md";
            case "pdf"  -> "pdf";
            case "word" -> "docx";
            default     -> format;
        };
    }

    /**
     * Strip surrogate pairs, control chars, and BMP symbols that CJK fonts lack.
     * The try-catch in wrapText is the final safety net for any missed glyphs.
     */
    private String stripEmoji(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isSurrogate(c)) {
                i++; // skip low surrogate
                sb.append(' ');
            } else if (c < 0x20 && c != '\n' && c != '\r') {
                sb.append(' '); // control chars — no CJK glyph
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Prepend UTF-8 BOM so Windows Notepad correctly detects the encoding. */
    private byte[] addUtf8Bom(String text) {
        byte[] bom = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[bom.length + utf8.length];
        System.arraycopy(bom, 0, result, 0, bom.length);
        System.arraycopy(utf8, 0, result, bom.length, utf8.length);
        return result;
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_").trim();
    }

    private String nowCompact() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }

    // ── Result ──

    public record ExportResult(String filename, String mimeType, byte[] data) {}

    public static class ExportException extends RuntimeException {
        public final int status;
        public ExportException(int status, String message) { super(message); this.status = status; }
    }
}
