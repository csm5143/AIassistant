package com.aiproject.aiassitant.module.knowledge.service;

import org.junit.jupiter.api.Test;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SourceDocumentParserTest {
    private final SourceDocumentParser parser = new SourceDocumentParser();

    private void fixture(String name, byte[] bytes) throws Exception {
        String dir = System.getProperty("aiassistant.fixture-dir");
        if (dir != null) {
            var folder = java.nio.file.Path.of(dir);
            java.nio.file.Files.createDirectories(folder);
            java.nio.file.Files.write(folder.resolve(name), bytes);
        }
    }

    @Test void exactOffsetsSurviveOverlapRepeatedTextAndSurrogatePairs() throws Exception {
        String raw = "# 章节甲\r\n" + "相同证据。😀".repeat(120) + "\r\n# 章节乙\r\n相同证据。结论。";
        var source = parser.parse(raw.getBytes(StandardCharsets.UTF_8), "资料.md");
        var chunks = parser.chunks(source, 160, 60);
        assertTrue(chunks.size() > 3);
        for (var chunk : chunks) {
            assertEquals(chunk.content(), source.text().substring(chunk.start(), chunk.end()));
            assertFalse(Character.isLowSurrogate(chunk.content().charAt(0)));
            assertFalse(Character.isHighSurrogate(chunk.content().charAt(chunk.content().length() - 1)));
            var view = SourceDocumentParser.view("d", "资料.md", 1, source, chunk.start(), chunk.end(), chunk.content());
            assertTrue(view.located());
            assertEquals(chunk.content(), view.text().substring(view.highlightStart(), view.highlightEnd()));
            assertEquals(chunk.title(), view.title());
        }
        assertFalse(SourceDocumentParser.view("d", "old.md", 1, source, 0, 3, "改写后").located());
        var code = parser.parse("# 正文\n```python\n# 代码注释不是章节\nprint(1)\n```\n## 下一节\n内容".getBytes(StandardCharsets.UTF_8), "code.md");
        assertEquals(2, code.blocks().size());
    }

    @Test void preservesDocxParagraphTableOrderAndEmptySpreadsheetColumns() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("表格之前");
            var table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("岗位"); table.getRow(0).getCell(1).setText("额度");
            table.getRow(1).getCell(0).setText("工程师"); table.getRow(1).getCell(1).setText("800");
            doc.createParagraph().createRun().setText("表格之后");
            doc.write(bytes);
        }
        String text = parser.parse(bytes.toByteArray(), "表格.docx").text();
        fixture("表格.docx", bytes.toByteArray());
        assertTrue(text.indexOf("表格之前") < text.indexOf("工程师\t800"));
        assertTrue(text.indexOf("工程师\t800") < text.indexOf("表格之后"));
        bytes.reset();
        try (var wb = new XSSFWorkbook()) {
            var row = wb.createSheet("费用").createRow(0);
            row.createCell(0).setCellValue("上海"); row.createCell(2).setCellValue(800);
            wb.write(bytes);
        }
        assertTrue(parser.parse(bytes.toByteArray(), "费用.xlsx").text().contains("上海\t\t800"));
        fixture("费用.xlsx", bytes.toByteArray());
    }

    @Test void retainsPdfPageIdentityReadingOrderAndTopBottomText() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var doc = new PDDocument()) {
            for (int i = 1; i <= 2; i++) {
                var page = new PDPage(); doc.addPage(page);
                try (var stream = new PDPageContentStream(doc, page)) {
                    stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 780); stream.showText("Top page " + i);
                    stream.newLineAtOffset(0, -100); stream.showText("Middle page " + i);
                    stream.newLineAtOffset(0, -630); stream.showText("Bottom page " + i);
                    stream.endText();
                }
            }
            doc.save(bytes);
        }
        var source = parser.parse(bytes.toByteArray(), "pages.pdf");
        fixture("pages.pdf", bytes.toByteArray());
        assertEquals(2, source.blocks().size());
        assertEquals(2, source.blocks().get(1).page());
        assertTrue(source.text().indexOf("Top page 1") < source.text().indexOf("Bottom page 1"));
        var chunk = parser.chunks(source, 500, 100).get(1);
        var view = SourceDocumentParser.view("d", "pages.pdf", 2, source, chunk.start(), chunk.end(), chunk.content());
        assertEquals(2, view.page()); assertTrue(view.originalPdf());
    }

    @Test void onePassPdfTextMatchesPageByPageExtractionAndReportsBlankPages() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var doc = new PDDocument()) {
            for (int page = 1; page <= 4; page++) {
                var pdPage = new PDPage(); doc.addPage(pdPage);
                if (page == 2) continue; // A page without a content stream still has a page number.
                try (var stream = new PDPageContentStream(doc, pdPage)) {
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    int rows = page == 4 ? 7 : 1;
                    for (int line = 0; line < rows; line++) {
                        writeAt(stream, 50, 750 - line * 24, "Left page " + page + " row " + line);
                        if (page == 4) writeAt(stream, 360, 750 - line * 24, "Right page " + page + " row " + line);
                    }
                }
            }
            doc.save(bytes);
        }
        List<Integer> totals = new ArrayList<>(), done = new ArrayList<>();
        var source = parser.parse(bytes.toByteArray(), "four-pages.pdf", new SourceDocumentParser.ProgressListener() {
            @Override public void onPdfPages(int total) { totals.add(total); }
            @Override public void onPdfPage(int page) { done.add(page); }
        });
        assertEquals(List.of(4), totals);
        assertEquals(List.of(1, 2, 3, 4), done);
        assertEquals(List.of(1, 3, 4), source.blocks().stream().map(SourceDocumentParser.Block::page).toList());
        assertEquals(4, source.parseReport().totalPages());
        try (var doc = org.apache.pdfbox.Loader.loadPDF(bytes.toByteArray())) {
            var pages = new PdfPageInspector().extractPages(doc);
            assertEquals(4, pages.size());
            for (int i = 1; i <= 4; i++) {
                var reference = new PDFTextStripper();
                reference.setSortByPosition(true);
                reference.setStartPage(i);
                reference.setEndPage(i);
                assertEquals(reference.getText(doc), pages.get(i - 1).text());
            }
            assertEquals("", pages.get(1).text());
            assertFalse(pages.get(0).complexColumns());
            assertTrue(pages.get(3).complexColumns());
        }
        for (var block : source.blocks()) {
            assertFalse(block.end() > source.text().length());
            assertTrue(source.text().substring(block.start(), block.end()).contains("page " + block.page()));
        }
    }

    private static void writeAt(PDPageContentStream stream, float x, float y, String value) throws Exception {
        stream.beginText();
        stream.newLineAtOffset(x, y);
        stream.showText(value);
        stream.endText();
    }

    @Test void englishChunksPreserveWordsAbbreviationsIdentifiersAndExactLocations() throws Exception {
        String paragraph = "Dr. Smith et al. compare model_v4.1 against BGE-M3 at 0.25 accuracy. "
                + "The source URL is https://example.org/models/v4.1?lang=en&limit=10 and the DOI is 10.1234/example. "
                + "This sentence explains the measured results and the assumptions behind the evaluation. ";
        var source = parser.parse(("# English study\n" + paragraph.repeat(20)).getBytes(StandardCharsets.UTF_8), "study.md");
        var chunks = parser.chunks(source, 160, 40);
        for (var chunk : chunks) {
            assertEquals(chunk.content(), source.text().substring(chunk.start(), chunk.end()));
            assertFalse(chunk.content().stripTrailing().endsWith("Dr."));
            assertFalse(chunk.content().stripTrailing().endsWith("al."));
            if (chunk.start() > 0) {
                char before = source.text().charAt(chunk.start()-1), first = source.text().charAt(chunk.start());
                assertFalse(Character.isLetterOrDigit(before) && Character.isLetterOrDigit(first), "overlap starts in a word");
            }
            assertTrue(SourceDocumentParser.view("test", "study.md", 1, source, chunk.start(), chunk.end(), chunk.content()).located());
        }
        assertTrue(chunks.stream().anyMatch(c -> c.content().contains("https://example.org/models/v4.1?lang=en&limit=10")));
        assertTrue(chunks.stream().anyMatch(c -> c.content().contains("model_v4.1")));
        var automatic = parser.chunks(source,500,80);
        assertTrue(automatic.size() < parser.chunks(source,500,81).size());
    }

    @Test void pdfWrappedWordsKeepCompoundsNumbersAndChinese() {
        assertEquals("international information performance multi-head 8,192 中文测试。",
                DocumentText.pdfProse("inter-\nnational informa-\ntion perfor-\nmance multi-\nhead 8,192 中文测试。"));
        assertFalse(DocumentText.sentenceEnd("Dr. Smith",2,9));
        assertFalse(DocumentText.sentenceEnd("et al. reported",5,15));
        assertTrue(DocumentText.sentenceEnd("Results. Next",7,13));
    }

    @Test void paddedTablesRepeatRealHeadersAndPreserveRowValues() {
        StringBuilder table=new StringBuilder("Table 1: MIRACL nDCG@10\n\n| Model          | Avg          | en         |\n|----------------|--------------|------------|\n");
        for(int i=0;i<45;i++)table.append("| Baseline ").append(i).append("          | 40.0          | 30.0          |\n");
        table.append("| Dense          | 69.2          | 56.9          |\n| All          | 71.5          | 59.6          |\n");
        var text=new StringBuilder(); var blocks=new ArrayList<SourceDocumentParser.Block>();
        parser.appendTable(text,blocks,table.toString(),6);
        assertTrue(blocks.size()>1);
        var source=new SourceDocumentParser.SourceDocument(text.toString(),blocks);
        var chunks=parser.chunks(source,500,80);
        for(var chunk:chunks) {
            assertTrue(chunk.content().contains("Table 1: MIRACL nDCG@10"));
            assertTrue(chunk.content().contains("| Model | Avg | en |"));
            assertEquals(chunk.content(),source.text().substring(chunk.start(),chunk.end()));
            assertEquals(6,chunk.page());
        }
        assertTrue(chunks.stream().anyMatch(c->c.content().contains("| Dense | 69.2 | 56.9 |")&&c.content().contains("| All | 71.5 | 59.6 |")));
    }

    @Test void longProsePresetReducesChunksWithoutChangingOffsetsOrExplicitSettings() {
        var text = new StringBuilder();
        List<SourceDocumentParser.Block> blocks = new ArrayList<>();
        String line = "虚构档案记载某个借阅站每周三整理书架并核对记录，工作人员随后确认所有借阅状态😀。\n";
        for (int page = 1; page <= 120; page++) {
            if (!text.isEmpty()) text.append('\n');
            int start = text.length();
            text.append(line.repeat(20));
            blocks.add(new SourceDocumentParser.Block(start, text.length(), page, "第 " + page + " 页"));
        }
        var report = new SourceDocumentParser.ParseReport("PDFBox",120,120,0,0,0,0,0,0,0,0,List.of());
        var source = new SourceDocumentParser.SourceDocument(text.toString(), List.copyOf(blocks), report);
        var oldPreset = parser.chunks(new SourceDocumentParser.SourceDocument(text.toString(), blocks),500,80);
        var optimized = parser.chunks(source,500,80);
        assertTrue(optimized.size() < oldPreset.size() * 0.7,
                "long prose should reduce the number of embedding requests");
        assertEquals(120, optimized.size());
        assertEquals(parser.chunks(new SourceDocumentParser.SourceDocument(text.toString(), blocks),500,100).size(),
                parser.chunks(source,500,100).size());
        for (var chunk : optimized) {
            assertEquals(chunk.content(), source.text().substring(chunk.start(), chunk.end()));
            assertFalse(Character.isLowSurrogate(chunk.content().charAt(0)));
            assertFalse(Character.isHighSurrogate(chunk.content().charAt(chunk.content().length() - 1)));
            var view = SourceDocumentParser.view("book","book.pdf",1,source,chunk.start(),chunk.end(),chunk.content());
            assertTrue(view.located());
            assertEquals(chunk.page(), view.page());
        }
        System.out.println("Synthetic long prose: " + oldPreset.size() + " -> " + optimized.size() + " chunks (120 pages)");
    }

    @Test void onePassPdfExtractionMatchesRepeatedExtractionOnManyPages() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var doc = new PDDocument()) {
            for (int page = 1; page <= 96; page++) {
                var pdPage = new PDPage(); doc.addPage(pdPage);
                try (var stream = new PDPageContentStream(doc, pdPage)) {
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                    for (int line = 0; line < 12; line++)
                        writeAt(stream,50,750-line*22,"Offline benchmark page " + page + " line " + line);
                }
            }
            doc.save(bytes);
        }
        try (var doc = org.apache.pdfbox.Loader.loadPDF(bytes.toByteArray())) {
            var old = new PDFTextStripper(); old.setSortByPosition(true);
            long started = System.nanoTime();
            List<String> reference = new ArrayList<>();
            for (int page = 1; page <= 96; page++) {
                old.setStartPage(page);old.setEndPage(page);
                reference.add(old.getText(doc));
            }
            long repeatedMs = (System.nanoTime()-started)/1_000_000;
            started = System.nanoTime();
            var extracted = new PdfPageInspector().extractPages(doc);
            long onePassMs = (System.nanoTime()-started)/1_000_000;
            assertEquals(reference, extracted.stream().map(PdfPageInspector.PageText::text).toList());
            System.out.println("Synthetic PDF extraction (96 pages): repeated=" + repeatedMs
                    + " ms, one-pass=" + onePassMs + " ms");
        }
    }
}
