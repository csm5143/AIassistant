package com.aiproject.aiassitant.module.tools.workspace;

import com.aiproject.aiassitant.common.BizException;
import com.aiproject.aiassitant.security.AppPrincipal;
import com.aiproject.aiassitant.module.chat.entity.ChatSession;
import com.aiproject.aiassitant.module.chat.mapper.ChatSessionMapper;
import com.aiproject.aiassitant.module.knowledge.service.SourceDocumentParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceToolsTest {
    @Test void semicolonCsvRetainsQuotedHeadersDecimalCommaAndMultilineCells()throws Exception{
        var t=csv("\uFEFF\"price\";\"note\";\"quality\"\r\n\"1,25\";\"a; b\nmore\";7\r\n0;;5\r\n");
        assertEquals(List.of("price","note","quality"),t.columns());
        assertEquals(List.of("1,25","a; b\nmore","7"),t.rows().get(0));
        assertEquals(2,t.rows().size());
        assertThrows(BizException.class,()->csv("a,b;c\n1,2;3"));
    }
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper();final TableEngine engine=new TableEngine();
    static final String SESSION="a".repeat(32), OTHER="b".repeat(32);
    ToolFileStore files;WorkspaceToolService tools;
    @BeforeEach void setup(){
        login("alice");files=new ToolFileStore(temp.toString(),json);var mapper=mock(ChatSessionMapper.class);var session=new ChatSession();session.setId(SESSION);session.setUserId("alice");when(mapper.selectById(SESSION)).thenReturn(session);
        tools=new WorkspaceToolService(files,engine,new ReportWriter(),new SourceDocumentParser(),mapper,json);
    }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
    static void login(String name){var principal=new AppPrincipal(name,name,"user",List.of("USER"));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,"test",List.of()));}
    TableEngine.Table csv(String data)throws IOException{return engine.read(data.getBytes(StandardCharsets.UTF_8),"数据.csv","");}
    ToolFileStore.Asset upload(String name,byte[] bytes)throws IOException{return files.upload(SESSION,new MockMultipartFile("file",name,"application/octet-stream",bytes));}
    @Test void conversationalDocumentUploadKeepsSeparateTableLimit()throws Exception{
        byte[] largeText=new byte[21*1024*1024];Arrays.fill(largeText,(byte)'a');
        var document=upload("large.txt",largeText);assertEquals(largeText.length,document.size());
        assertThrows(BizException.class,()->upload("large.csv",largeText));
        var oversized=mock(org.springframework.web.multipart.MultipartFile.class);
        when(oversized.isEmpty()).thenReturn(false);when(oversized.getSize()).thenReturn(ToolFileStore.MAX_UPLOAD+1);
        assertThrows(BizException.class,()->files.upload(SESSION,oversized));verify(oversized,never()).getBytes();
    }
    @Test void quotedCsvAndChineseEncoding()throws Exception{
        var t=csv("\uFEFF类别,金额,备注\r\n餐饮,0.1,\"含逗号,和\"\"引号\"\"\"\r\n交通,0.2,\"两行\n文字\"\r\n");assertEquals(2,t.rows().size());assertEquals("含逗号,和\"引号\"",t.rows().get(0).get(2));assertEquals("两行\n文字",t.rows().get(1).get(2));
        var gb=engine.read("项目,金额\n试验,100".getBytes(Charset.forName("GB18030")),"旧表.csv","");assertEquals("试验",gb.rows().get(0).get(0));
    }
    @Test void decimalAndMissingAreNotGuessed()throws Exception{
        var t=csv("类别,金额\n餐饮,0.1\n餐饮,0.2\n交通,\n交通,未知\n");var r=engine.analyze(t,json.readTree("{\"groupBy\":[\"类别\"],\"aggregations\":[{\"column\":\"金额\",\"op\":\"sum\",\"alias\":\"合计\"}]}"));
        assertEquals(new BigDecimal("0.3"),r.rows().get(0).get(1));assertNull(r.rows().get(1).get(1));assertEquals(Map.of("合计",1),r.audit().get("invalidNumericCells"));assertEquals(Map.of("合计",1),r.audit().get("emptyNumericCells"));
    }
    @Test void numericCountIsDistinctFromNonemptyCount()throws Exception {
        var r=engine.analyze(csv("类别,金额\n交通,12.50\n交通,17.50\n交通,未知\n交通,\n"),json.readTree("{\"aggregations\":[{\"column\":\"金额\",\"op\":\"count\",\"alias\":\"非空数\"},{\"column\":\"金额\",\"op\":\"countNumeric\",\"alias\":\"有效数字数\"},{\"column\":\"*\",\"op\":\"count\",\"alias\":\"行数\"}]}"));
        assertEquals(List.of(3,2,4),r.rows().get(0));
    }
    @Test void reportTitleIsNotDuplicatedAndDifferentSectionIsPreserved()throws Exception {
        var writer=new ReportWriter();
        try(var doc=new XWPFDocument(new ByteArrayInputStream(writer.write("验收周报","# 验收周报\n## 本周进展\n已完成3项", "docx",List.of())))) {
            assertEquals(1,doc.getParagraphs().stream().filter(p->p.getText().equals("验收周报")).count());
            assertTrue(doc.getParagraphs().stream().anyMatch(p->p.getText().equals("本周进展")));
        }
        try(var pdf=Loader.loadPDF(writer.write("验收周报","# 验收周报\n## 本周进展\n已完成3项", "pdf",List.of()))) {
            String text=new PDFTextStripper().getText(pdf);
            assertEquals(1,text.split("验收周报",-1).length-1); assertTrue(text.contains("本周进展"));
        }
    }
    @Test void schemasFollowAvailableFileTypes()throws Exception {
        assertTrue(tools.availableTools(SESSION,false).isEmpty());
        assertEquals(Set.of("generateDocument"),tools.availableTools(SESSION,true));
        upload("记录.csv","类别,金额\n交通,12.50".getBytes(StandardCharsets.UTF_8));
        assertTrue(tools.availableTools(SESSION,false).containsAll(Set.of("inspectTable","analyzeTable","generateDocument")));
        assertFalse(tools.availableTools(SESSION,false).contains("pdfTools"));
        assertFalse(tools.availableTools(SESSION,false).contains("readDocuments"));
    }
    @Test void monthFilterDedupeAndSort()throws Exception{
        var t=csv("日期,类别,金额\n2026-01-02,餐饮,10\n2026-01-02,餐饮,10\n2026/2/2,餐饮,20\n2026-02-12,交通,-5\n");var r=engine.analyze(t,json.readTree("{\"dateGroup\":{\"column\":\"日期\",\"unit\":\"month\"},\"filters\":[{\"column\":\"类别\",\"op\":\"eq\",\"value\":\"餐饮\"}],\"deduplicate\":true,\"aggregations\":[{\"column\":\"金额\",\"op\":\"sum\",\"alias\":\"总额\"}],\"sortBy\":\"总额\",\"sortDirection\":\"desc\"}"));
        assertEquals("2026-02",r.rows().get(0).get(0));assertEquals(new BigDecimal("20"),r.rows().get(0).get(1));assertEquals(1,r.audit().get("removedDuplicates"));
    }
    @Test void rejectInvalidCalendarAndUnknownColumns()throws Exception{
        var t=csv("日期,金额\n2026-02-31,4\n");assertThrows(BizException.class,()->engine.analyze(t,json.readTree("{\"dateGroup\":{\"column\":\"日期\",\"unit\":\"month\"},\"aggregations\":[{\"column\":\"金额\",\"op\":\"sum\"}]}")));
        assertThrows(BizException.class,()->engine.analyze(t,json.readTree("{\"select\":[\"不存在\"]}")));assertThrows(BizException.class,()->csv("a,b\n1,2,3"));assertThrows(BizException.class,()->csv("a\n\"broken"));
    }
    @Test void percentCurrencyAndEmptyMean()throws Exception{
        assertEquals(new BigDecimal("1234.50"),TableEngine.number("￥1,234.50"));assertEquals(new BigDecimal("0.20"),TableEngine.number("20%"));assertNull(TableEngine.number("1,2"));
        var r=engine.analyze(csv("数值\n未知"),json.readTree("{\"aggregations\":[{\"column\":\"数值\",\"op\":\"avg\"}]}"));assertNull(r.rows().get(0).get(0));
    }
    @Test void workbookAndChartAreRealFiles()throws Exception{
        var r=engine.analyze(csv("部门,收入\n研发,100\n支持,200"),json.readTree("{\"groupBy\":[\"部门\"],\"aggregations\":[{\"column\":\"收入\",\"op\":\"sum\",\"alias\":\"总额\"}]}"));
        try(var wb=new XSSFWorkbook(new ByteArrayInputStream(engine.workbook(r,"test")))){assertEquals(200,wb.getSheet("结果").getRow(2).getCell(1).getNumericCellValue());assertNotNull(wb.getSheet("处理说明"));}
        var image=ImageIO.read(new ByteArrayInputStream(engine.chart(r,"bar","部门收入")));assertEquals(1100,image.getWidth());assertEquals(600,image.getHeight());
    }
    @Test void multiSheetDatesCachedFormulaAndNoFormulaInjection()throws Exception{
        byte[] bytes;try(var wb=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
            wb.createSheet("首页").createRow(0).createCell(0).setCellValue("空表头");var s=wb.createSheet("记录");var header=s.createRow(0);header.createCell(0).setCellValue("日期");header.createCell(1).setCellValue("金额");var row=s.createRow(1);row.createCell(0).setCellValue(java.time.LocalDateTime.of(2026,3,1,0,0));var style=wb.createCellStyle();style.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));row.getCell(0).setCellStyle(style);row.createCell(1).setCellFormula("1+2");wb.getCreationHelper().createFormulaEvaluator().evaluateAll();wb.write(out);bytes=out.toByteArray();}
        var t=engine.read(bytes,"data.xlsx","记录");assertEquals(2,t.sheets().size());assertTrue(t.rows().get(0).get(0).startsWith("2026-03-01"));assertEquals("3",t.rows().get(0).get(1));
        var r=new TableEngine.Result(List.of("文字"),List.of(List.of("=HYPERLINK(\"bad\")")),Map.of());try(var wb=new XSSFWorkbook(new ByteArrayInputStream(engine.workbook(r,"")))){assertEquals(org.apache.poi.ss.usermodel.CellType.STRING,wb.getSheetAt(0).getRow(1).getCell(0).getCellType());}
    }
    @Test void fileOwnershipAndSessionIsolation()throws Exception{
        var a=upload("测试.csv","项目,金额\nA,1".getBytes(StandardCharsets.UTF_8));assertThrows(BizException.class,()->files.get(a.id(),OTHER));login("bob");assertThrows(BizException.class,()->files.get(a.id(),SESSION));assertThrows(BizException.class,()->tools.ownedSession(SESSION));login("alice");assertThrows(BizException.class,()->files.get("../../secret",SESSION));files.delete(a.id(),SESSION);assertTrue(files.list(SESSION).isEmpty());
    }
    @Test void missingFormulaCacheIsEmptyRatherThanFabricatedZero()throws Exception{
        byte[] bytes;try(var wb=new XSSFWorkbook();var out=new ByteArrayOutputStream()){var s=wb.createSheet("数据");var h=s.createRow(0);h.createCell(0).setCellValue("项目");h.createCell(1).setCellValue("金额");var r=s.createRow(1);r.createCell(0).setCellValue("未计算的公式");r.createCell(1).setCellFormula("100+200");wb.write(out);bytes=out.toByteArray();}
        var t=engine.read(bytes,"公式.xlsx","");assertEquals("",t.rows().get(0).get(1));var r=engine.analyze(t,json.readTree("{\"aggregations\":[{\"column\":\"金额\",\"op\":\"sum\"}]}"));assertNull(r.rows().get(0).get(0));
    }
    @Test void extensionSpoofingRejected()throws Exception{assertThrows(BizException.class,()->upload("假的.pdf","bad".getBytes()));assertThrows(BizException.class,()->upload("脚本.exe","bad".getBytes()));}
    @Test void analysisServiceRegistersArtifacts()throws Exception{
        var a=upload("支出.csv","类别,金额\n餐饮,10\n餐饮,20".getBytes(StandardCharsets.UTF_8));var r=tools.execute(SESSION,"analyzeTable",json.readTree("{\"fileId\":\""+a.id()+"\",\"plan\":{\"groupBy\":[\"类别\"],\"aggregations\":[{\"column\":\"金额\",\"op\":\"sum\"}]}}"));assertEquals(true,r.get("ok"));assertEquals(2,((List<?>)r.get("artifacts")).size());assertEquals(3,files.list(SESSION).size());
    }
    @Test void extractionEvidenceAndMissingRows()throws Exception{
        var a=upload("甲.txt","合同金额：100元。\n签订日期：2026-09-30。".getBytes(StandardCharsets.UTF_8));var b=upload("乙.txt","合同金额：200元。".getBytes(StandardCharsets.UTF_8));
        var request=json.createObjectNode();request.putArray("fileIds").add(a.id()).add(b.id());request.putArray("fields").add("金额").add("日期");var records=request.putArray("records");var row=records.addObject();row.put("fileId",a.id());var values=row.putObject("values");values.putObject("金额").put("value","100").put("page",1).put("quote","合同金额：100元。");values.putObject("日期").put("value","2030-01-01").put("page",1).put("quote","签订日期：2026-09-30。");
        var r=tools.execute(SESSION,"exportExtractedFields",request);var audit=(Map<?,?>)r.get("audit");assertEquals(1,audit.get("verified"));assertEquals(1,audit.get("unverified"));assertEquals(2,audit.get("missing"));var preview=(List<?>)r.get("preview");assertEquals(2,preview.size());assertEquals("100",((List<?>)preview.get(0)).get(1));assertNull(((List<?>)preview.get(0)).get(5));
    }
    @Test void locatedReadReturnsNextPage()throws Exception{
        byte[] pdf;try(var doc=new PDDocument();var out=new ByteArrayOutputStream()){for(int i=0;i<4;i++)doc.addPage(new PDPage());doc.save(out);pdf=out.toByteArray();}var a=upload("四页.pdf",pdf);var request=json.createObjectNode();request.putArray("fileIds").add(a.id());request.put("pageStart",2);request.put("maxPages",2);var r=tools.execute(SESSION,"readDocuments",request);var d=(Map<?,?>)((List<?>)r.get("documents")).get(0);assertEquals(4,d.get("totalPages"));assertEquals(4,d.get("nextPage"));assertEquals(true,d.get("physicalPages"));var evidence=(List<?>)d.get("evidence");assertEquals(2,((Map<?,?>)evidence.get(0)).get("page"));
    }
    @Test void pdfExtractRotateMergeAndSplit()throws Exception{
        byte[] pdf;try(var doc=new PDDocument();var out=new ByteArrayOutputStream()){for(int i=0;i<4;i++){var p=new PDPage();p.setRotation(i==1?90:0);doc.addPage(p);}doc.save(out);pdf=out.toByteArray();}var a=upload("四页.pdf",pdf);
        for(String op:List.of("EXTRACT","ROTATE","SPLIT","MERGE")){
            var args=json.createObjectNode();args.putArray("fileIds").add(a.id());args.put("operation",op);args.put("rotation",90);args.put("pages",op.equals("SPLIT")?"1-2;3-4":"2,4");var r=tools.execute(SESSION,"pdfTools",args);var artifacts=(List<ToolFileStore.Asset>)r.get("artifacts");assertEquals(op.equals("SPLIT")?2:1,artifacts.size());try(var out=Loader.loadPDF(files.bytes(artifacts.get(0)))){assertEquals(op.equals("MERGE")||op.equals("ROTATE")?4:2,out.getNumberOfPages());if(op.equals("ROTATE")){assertEquals(180,out.getPage(1).getRotation());assertEquals(0,out.getPage(0).getRotation());}}
        }
    }
    @Test void pdfRangesAreBoundedAndValidateOrder(){assertEquals(List.of(1,2,3,5),WorkspaceToolService.pageRange("1-3,5",5));for(String range:List.of("0","1001","3-1","1,1","1-501","bad","1,"))assertThrows(BizException.class,()->WorkspaceToolService.pageRange(range,1000));}
    @Test void chineseEnglishReportRendersAndKeepsTables()throws Exception{
        var writer=new ReportWriter();String body="# 结论与范围\n本报告测试中文和 English，金额为 **123.45**。\n\n| 类别 | 金额 |\n| --- | --- |\n| 餐饮 | 123.45 |\n| Travel | 56.78 |\n\n## 说明\n- 数据来自测试样本。\n- 链接：[官方文档](https://example.org/docs)。\n";
        Path dir=Path.of("../eval/results/tool-fixtures").toAbsolutePath().normalize();Files.createDirectories(dir);byte[] word=writer.write("中英文报告验收",body,"docx",List.of());Files.write(dir.resolve("report.docx"),word);
        try(var doc=new XWPFDocument(new ByteArrayInputStream(word))){assertEquals(1,doc.getTables().size());assertEquals("Title",doc.getParagraphs().get(0).getStyle());assertEquals("餐饮",doc.getTables().get(0).getRow(1).getCell(0).getText());}
        byte[] pdf=writer.write("中英文报告验收",body,"pdf",List.of());Files.write(dir.resolve("report.pdf"),pdf);
        try(var doc=Loader.loadPDF(pdf)){String text=new PDFTextStripper().getText(doc);assertTrue(text.contains("中文和 English"));assertTrue(text.contains("123.45"));var renderer=new PDFRenderer(doc);for(int p=0;p<doc.getNumberOfPages();p++)ImageIO.write(renderer.renderImageWithDPI(p,120),"png",dir.resolve("report-page-"+(p+1)+".png").toFile());}
        var longBody="# 长文验收\n"+("中英混排 This paragraph tests page wrapping and pagination.\n".repeat(160));try(var doc=Loader.loadPDF(writer.write("多页排版",longBody,"pdf",List.of()))){assertTrue(doc.getNumberOfPages()>2);assertTrue(new PDFTextStripper().getText(doc).contains("pagination"));}
    }
    @Test void reportEmbedsRealChartInBothFormats()throws Exception{
        var table=engine.analyze(csv("类别,金额\n餐饮,120\n交通,50"),json.readTree("{\"groupBy\":[\"类别\"],\"aggregations\":[{\"column\":\"金额\",\"op\":\"sum\"}]}"));byte[] chart=engine.chart(table,"bar","消费分布");var figures=List.of(new ReportWriter.Figure("消费分布",chart));var writer=new ReportWriter();
        try(var doc=new XWPFDocument(new ByteArrayInputStream(writer.write("报告", "# 结论\n总支出为170。", "docx",figures)))){assertEquals(1,doc.getAllPictures().size());}
        byte[] pdf=writer.write("报告", "# 结论\n总支出为170。", "pdf",figures);try(var doc=Loader.loadPDF(pdf)){assertTrue(doc.getPage(0).getResources().getXObjectNames().iterator().hasNext());}
    }
}
