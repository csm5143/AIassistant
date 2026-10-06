package com.aiproject.aiassitant.module.knowledge.service;

import com.aiproject.aiassitant.module.ai.service.ApiManager;
import com.aiproject.aiassitant.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnhancedPdfParserTest {
    @TempDir Path temp;
    private EnhancedPdfParser client(ApiManager apis, int port, int budget) {
        var parser=new EnhancedPdfParser(apis,new ObjectMapper());
        ReflectionTestUtils.setField(parser,"url","http://127.0.0.1:"+port);
        ReflectionTestUtils.setField(parser,"token","unit-token");
        ReflectionTestUtils.setField(parser,"visionLimit",budget);
        ReflectionTestUtils.setField(parser,"runtimeDir",temp.toString());
        return parser;
    }
    @Test void restoresOriginalPageNumbersAndCachesVisionWithinBudget() throws Exception {
        var apis=mock(ApiManager.class);
        when(apis.pdfVisionIdentity()).thenReturn("unit");
        when(apis.describePdfFigure(anyString(),anyString())).thenReturn(new ApiManager.PdfVisionResult("季度收入 42 万元",90));
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/parse", exchange -> {
            assertEquals("unit-token",exchange.getRequestHeaders().getFirst("X-Parser-Token"));
            try(var pdf=org.apache.pdfbox.Loader.loadPDF(exchange.getRequestBody().readAllBytes())) {assertEquals(2,pdf.getNumberOfPages());}
            byte[] result=("{\"pageCount\":2,\"cacheHit\":false,\"blocks\":[{\"page\":2,\"kind\":\"table\",\"text\":\"|型号|价格|\"}],"
                    +"\"pictures\":[{\"page\":1,\"image\":\"data:image/png;base64,YQ==\",\"caption\":\"收入\"},{\"page\":2,\"image\":\"other\"}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,result.length);exchange.getResponseBody().write(result);exchange.close();
        });server.start();
        try(var original=new PDDocument()) {
            for(int i=0;i<6;i++) original.addPage(new PDPage());
            var parser=client(apis,server.getAddress().getPort(),1);
            var result=parser.enhance(original,new LinkedHashMap<>(Map.of(2,false,5,false)));
            assertEquals(Set.of(2,5),new HashSet<>(result.segments().stream().map(EnhancedPdfParser.Segment::page).toList()));
            assertEquals(1,result.report().visionCalls());assertEquals(90,result.report().visionTokens());
            assertTrue(result.report().warnings().stream().anyMatch(w->w.contains("上限")));
            assertTrue(result.segments().stream().anyMatch(s->s.kind().equals("vision")&&s.text().contains("需结合原图核验")));
            var warm=parser.enhance(original,new LinkedHashMap<>(Map.of(2,false,5,false)));
            assertEquals(0,warm.report().visionCalls());assertEquals(0,warm.report().visionTokens());
            verify(apis,times(1)).describePdfFigure(anyString(),anyString());
        } finally {server.stop(0);}
    }
    @Test void rejectsIncompletePageResultsAndUnavailableWorker() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/parse",exchange->{byte[] body="{\"pageCount\":0}".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try(var pdf=new PDDocument()) {
            pdf.addPage(new PDPage());var parser=client(mock(ApiManager.class),server.getAddress().getPort(),0);
            assertThrows(BizException.class,()->parser.enhance(pdf,Map.of(1,true)));
            server.stop(0);
            assertThrows(BizException.class,()->parser.enhance(pdf,Map.of(1,true)));
        } finally {server.stop(0);}
    }
    @Test void retriesTimedOutPageGroupAsSmallerGroupsWithoutLosingPageNumbers() throws Exception {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/parse", exchange -> {
            int count;
            try (var selected = org.apache.pdfbox.Loader.loadPDF(exchange.getRequestBody().readAllBytes())) {
                count = selected.getNumberOfPages();
            }
            requests.incrementAndGet();
            if (count > 4) {
                byte[] error = "conversion timeout".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(422, error.length);
                exchange.getResponseBody().write(error);
            } else {
                StringBuilder body = new StringBuilder("{\"pageCount\":").append(count).append(",\"blocks\":[");
                for (int page = 1; page <= count; page++) {
                    if (page > 1) body.append(',');
                    body.append("{\"page\":").append(page).append(",\"text\":\"page-").append(page).append("\"}");
                }
                byte[] result = body.append("]}").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, result.length);
                exchange.getResponseBody().write(result);
            }
            exchange.close();
        });
        server.start();
        try (var pdf = new PDDocument()) {
            Map<Integer, Boolean> pages = new LinkedHashMap<>();
            for (int page = 1; page <= 8; page++) { pdf.addPage(new PDPage()); pages.put(page, false); }
            var parsed = client(mock(ApiManager.class), server.getAddress().getPort(), 0).enhance(pdf, pages);
            assertEquals(3, requests.get());
            assertEquals(8, parsed.report().enhancedPages());
            assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8),
                    parsed.segments().stream().map(EnhancedPdfParser.Segment::page).toList());
        } finally { server.stop(0); }
    }
    @Test void fastScannedBatchSendsExplicitHeaderAndReportsCommittedPages() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/parse", exchange -> {
            assertEquals("true", exchange.getRequestHeaders().getFirst("X-Fast-Scan"));
            assertEquals("true", exchange.getRequestHeaders().getFirst("X-Force-Ocr"));
            try (var selected = org.apache.pdfbox.Loader.loadPDF(exchange.getRequestBody().readAllBytes())) {
                assertEquals(2, selected.getNumberOfPages());
            }
            byte[] body = "{\"pageCount\":2,\"blocks\":[{\"page\":1,\"text\":\"识别第一页\"},{\"page\":2,\"text\":\"识别第二页\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (var original = new PDDocument()) {
            original.addPage(new PDPage()); original.addPage(new PDPage());
            List<Integer> progress = new ArrayList<>();
            var result = client(mock(ApiManager.class), server.getAddress().getPort(), 0)
                    .enhance(original, Map.of(1, true, 2, true), true, progress::add);
            assertEquals(List.of(2), progress);
            assertEquals(2, result.report().ocrPages());
            assertTrue(result.report().engine().contains("快速扫描识别"));
            assertTrue(result.report().warnings().stream().anyMatch(w -> w.contains("表格")));
        } finally { server.stop(0); }
    }
    @Test void selectedPagesRetainInheritedFontResources() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/parse",exchange->{
            try(var selected=org.apache.pdfbox.Loader.loadPDF(exchange.getRequestBody().readAllBytes())) {
                assertTrue(new org.apache.pdfbox.text.PDFTextStripper().getText(selected).contains("Inherited font survives selected-page export"));
            }
            byte[] body="{\"pageCount\":1,\"blocks\":[{\"page\":1,\"text\":\"Inherited font survives selected-page export\"}]}".getBytes();
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try(var original=new PDDocument()) {
            var page=new PDPage();original.addPage(page);
            try(var draw=new PDPageContentStream(original,page)) {
                draw.beginText();draw.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                draw.newLineAtOffset(40,700);draw.showText("Inherited font survives selected-page export");draw.endText();
            }
            original.getPages().getCOSObject().setItem(org.apache.pdfbox.cos.COSName.RESOURCES,page.getResources());
            page.setResources(null);
            var result=client(mock(ApiManager.class),server.getAddress().getPort(),0).enhance(original,Map.of(1,false));
            assertTrue(result.segments().get(0).text().contains("Inherited font"));
        } finally {server.stop(0);}
    }
    @Test void routesScannedPagesOnlyAndKeepsNativeTextOffsets() throws Exception {
        var enhanced=mock(EnhancedPdfParser.class);
        var report=new SourceDocumentParser.ParseReport("unit",2,1,1,1,1,0,0,0,0,1,List.of());
        when(enhanced.enhance(any(),any(),eq(false),any())).thenReturn(new EnhancedPdfParser.Result(List.of(
                new EnhancedPdfParser.Segment(2,"text","扫描页中文：保修期限十二个月。"),
                new EnhancedPdfParser.Segment(2,"table","|型号|价格|\n|---|---|\n"+"|A|100|\n".repeat(400))),report));
        byte[] bytes;
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()) {
            var plain=new PDPage();pdf.addPage(plain);
            try(var draw=new PDPageContentStream(pdf,plain)) {
                draw.beginText();draw.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),14);
                draw.newLineAtOffset(40,700);draw.showText("Normal text page preserves exact offsets and takes the fast path.");draw.endText();
            }
            var scan=new PDPage();pdf.addPage(scan);
            try(var draw=new PDPageContentStream(pdf,scan)) {draw.drawImage(LosslessFactory.createFromImage(pdf,new BufferedImage(600,800,BufferedImage.TYPE_INT_RGB)),0,0,600,780);}
            pdf.save(out);bytes=out.toByteArray();
        }
        var parser=new SourceDocumentParser(enhanced);var source=parser.parse(bytes,"mixed.pdf");
        verify(enhanced).enhance(any(),eq(Map.of(2,true)),eq(false),any());
        assertTrue(source.text().contains("Normal text page"));assertTrue(source.text().contains("十二个月"));
        var chunks=parser.chunks(source,500,80);
        for(var chunk:chunks) {
            assertEquals(chunk.content(),source.text().substring(chunk.start(),chunk.end()));
            if(chunk.title().endsWith(" · 表格")) assertTrue(chunk.content().replace(" ","").startsWith("|型号|价格|"));
            var view=SourceDocumentParser.view("d","mixed.pdf",1,source,chunk.start(),chunk.end(),chunk.content());
            assertTrue(view.located());assertEquals(chunk.page(),view.page());
        }
    }
    @Test void detectsColumnLayoutsAndGarbledTextWithoutTreatingNormalChineseAsDamage() {
        assertTrue(PdfPageInspector.damaged("字�符�错误"));
        assertFalse(PdfPageInspector.damaged("中文文档正常。\n参数 3000 和 0.2"));
    }
    @Test void clippingRectanglesAndCodeBoxesAreNotTablesButGridLinesAre() throws Exception {
        try(var pdf=new PDDocument()) {
            var page=new PDPage();pdf.addPage(page);
            try(var draw=new PDPageContentStream(pdf,page)) {
                for(int i=0;i<20;i++){draw.saveGraphicsState();draw.addRect(30,30,500,700);draw.clip();draw.restoreGraphicsState();}
                draw.addRect(60,400,480,250);draw.stroke();
                draw.addRect(60,100,480,200);draw.stroke();
            }
            var graphics=new PdfPageInspector.Graphics(page);graphics.inspect();
            assertFalse(graphics.tableGrid());assertFalse(graphics.diagram());
            var table=new PDPage();pdf.addPage(table);
            try(var draw=new PDPageContentStream(pdf,table)) {
                for(int y=100;y<=400;y+=60){draw.moveTo(50,y);draw.lineTo(550,y);draw.stroke();}
                for(int x=50;x<=550;x+=250){draw.moveTo(x,100);draw.lineTo(x,400);draw.stroke();}
            }
            var grid=new PdfPageInspector.Graphics(table);grid.inspect();assertTrue(grid.tableGrid());
        }
    }
    @Test void limitsOptionalFigurePagesWithoutSkippingRequiredScanRecognition() throws Exception {
        var enhanced=mock(EnhancedPdfParser.class);when(enhanced.visualPageBudget()).thenReturn(1);
        var report=new SourceDocumentParser.ParseReport("unit",3,1,2,1,0,0,0,0,0,1,List.of());
        when(enhanced.enhance(any(),any(),eq(false),any())).thenReturn(new EnhancedPdfParser.Result(List.of(
                new EnhancedPdfParser.Segment(1,"text","第一张图的文字"),new EnhancedPdfParser.Segment(3,"text","第三页扫描文字")),report));
        byte[] bytes;
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()) {
            var image=LosslessFactory.createFromImage(pdf,new BufferedImage(600,800,BufferedImage.TYPE_INT_RGB));
            for(int i=0;i<3;i++) {
                var page=new PDPage();pdf.addPage(page);
                try(var draw=new PDPageContentStream(pdf,page)) {
                    if(i<2) {draw.beginText();draw.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                        draw.newLineAtOffset(40,700);draw.showText("Native text is retained even after the optional chart budget is used.");draw.endText();}
                    draw.drawImage(image,40,60,500,i==2 ? 700 : 250);
                }
            }
            pdf.save(out);bytes=out.toByteArray();
        }
        var source=new SourceDocumentParser(enhanced).parse(bytes,"budget.pdf");
        verify(enhanced).enhance(any(),eq(Map.of(1,false,3,true)),eq(false),any());
        assertTrue(source.text().contains("Native text is retained"));
        assertTrue(source.parseReport().warnings().stream().anyMatch(w->w.contains("另有 1 页")));
    }
    @Test void longImageOnlyBookUsesFastScanAndReportsEnhancementProgress() throws Exception {
        var enhanced = mock(EnhancedPdfParser.class);
        when(enhanced.enhance(any(), any(), eq(true), any())).thenAnswer(invocation -> {
            Map<Integer, Boolean> selected = invocation.getArgument(1);
            java.util.function.IntConsumer progress = invocation.getArgument(3);
            assertEquals(80, selected.size());
            assertTrue(selected.values().stream().allMatch(Boolean::booleanValue));
            progress.accept(selected.size());
            var segments = selected.keySet().stream().map(page -> new EnhancedPdfParser.Segment(page, "text", "这是扫描图书第 " + page + " 页的正文。")).toList();
            return new EnhancedPdfParser.Result(segments,
                    new SourceDocumentParser.ParseReport("RapidOCR", 80, 0, 80, 80, 0, 0, 0, 0, 0, 1, List.of()));
        });
        byte[] pdfBytes;
        try (var pdf = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var image = LosslessFactory.createFromImage(pdf, new BufferedImage(600, 800, BufferedImage.TYPE_INT_RGB));
            for (int i = 0; i < 80; i++) {
                var page = new PDPage(); pdf.addPage(page);
                try (var stream = new PDPageContentStream(pdf, page)) {
                    stream.drawImage(image, 0, 0, 600, 780);
                }
            }
            pdf.save(out); pdfBytes = out.toByteArray();
        }
        List<String> stages = new ArrayList<>();
        var parsed = new SourceDocumentParser(enhanced).parse(pdfBytes, "scanned-book.pdf",
                new SourceDocumentParser.ProgressListener() {
                    @Override public void onEnhancedPages(int total) { stages.add("total=" + total); }
                    @Override public void onEnhancedPage(int done) { stages.add("done=" + done); }
                });
        verify(enhanced).enhance(any(), any(), eq(true), any());
        assertEquals(List.of("total=80", "done=80"), stages);
        assertEquals(80, parsed.blocks().size());
    }
}
