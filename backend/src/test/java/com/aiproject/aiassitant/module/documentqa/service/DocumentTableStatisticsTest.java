package com.aiproject.aiassitant.module.documentqa.service;

import com.aiproject.aiassitant.common.BizException;
import com.aiproject.aiassitant.module.knowledge.service.SourceDocumentParser;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DocumentTableStatisticsTest {
    private DocumentQaService.DocSession doc(String text){var source=new SourceDocumentParser.SourceDocument(text,List.of(new SourceDocumentParser.Block(0,text.length(),null,"Ledger")));var locations=new SourceDocumentParser().chunks(source,100,20);return new DocumentQaService.DocSession("id","owner","ledger.md",locations.stream().map(SourceDocumentParser.LocatedChunk::content).toList(),List.of(),0,0,source,locations,"v1");}
    private DocumentTableStatistics.Plan plan(String op,String field,List<DocumentTableStatistics.Filter> filters,List<String> group,String distinct){return new DocumentTableStatistics.Plan("v1",List.of("table-1"),op,field,filters,group,distinct);}
    private DocumentTableStatistics.Filter filter(String field,String op,String value,String type){return new DocumentTableStatistics.Filter(field,op,value,type,"yyyy-MM-dd");}
    @Test void scansFullSourceOnceAndAuditsZeroNegativeDuplicateAndExclusions(){
        var doc=doc("ID | Date | Status | Amount\n--- | --- | --- | ---\nA | 2025-01-01 | Paid | 100.10\nB | 2024-12-31 | Paid | 900\nC | 2025-01-02 | Pending | 800\nD | 2025-02-01 | Paid | -20.10\nA | 2025-01-01 | Paid | 100.10\nE | 2025-12-31 | Paid | 0\n");
        var result=DocumentTableStatistics.calculate(doc,plan("SUM","Amount",List.of(filter("Date","GE","2025-01-01","DATE"),filter("Status","EQ","Paid","TEXT")),List.of(),"ID"));
        assertTrue(result.accepted());assertEquals("80.00",result.groups().get(0).value());assertEquals(3,result.selectedRows());assertEquals(2,result.excludedRows());assertEquals(1,result.duplicateRows());assertEquals(6,result.audit().size());
        for(var row:result.audit())assertTrue(doc.source.text().substring(row.start(),row.end()).contains(row.cells().get("ID")));
    }
    @Test void refusesMissingNumericBadDatesAndColumnLoss(){
        for(String text:List.of("ID\tAmount\nA\t\nB\t100", "ID\tAmount\nA\t100\textra\nB\t200")){
            var result=DocumentTableStatistics.calculate(doc(text),plan("SUM","Amount",List.of(),List.of(),null));assertFalse(result.accepted());assertEquals(1,result.invalidRows());assertEquals(2,result.audit().size());assertTrue(result.groups().isEmpty());
        }
        var result=DocumentTableStatistics.calculate(doc("ID | Date | Amount\nA | 2025-02-30 | 100\n"),plan("SUM","Amount",List.of(filter("Date","GE","2025-01-01","DATE")),List.of(),null));assertFalse(result.accepted());
    }
    @Test void rejectsConflictingDuplicatesAndMixedCurrencyUntilGrouped(){
        var conflict=DocumentTableStatistics.calculate(doc("ID | Amount\nA | 100\nA | 200\n"),plan("SUM","Amount",List.of(),List.of(),"ID"));assertFalse(conflict.accepted());
        var doc=doc("ID | Currency | Amount\nA | CNY | 100\nB | USD | 200\n");assertFalse(DocumentTableStatistics.calculate(doc,plan("SUM","Amount",List.of(),List.of(),null)).accepted());
        var grouped=DocumentTableStatistics.calculate(doc,plan("SUM","Amount",List.of(),List.of("Currency"),null));assertTrue(grouped.accepted());assertEquals(2,grouped.groups().size());
    }
    @Test void enforcesVersionExactFieldsAndSelectedTableScope(){
        var doc=doc("ID | Amount\nA | 100\n\nOther table\nID | Amount\nB | 200\n");assertEquals(2,DocumentTableStatistics.tables(doc).size());assertEquals("100",DocumentTableStatistics.calculate(doc,plan("SUM","Amount",List.of(),List.of(),null)).groups().get(0).value());
        assertThrows(BizException.class,()->DocumentTableStatistics.calculate(doc,plan("SUM","amount",List.of(),List.of(),null)));
        assertThrows(BizException.class,()->DocumentTableStatistics.calculate(doc,new DocumentTableStatistics.Plan("old",List.of("table-1"),"SUM","Amount",List.of(),List.of(),null)));
    }
    @Test void supportsCountDistinctAverageAndEmptySelection(){
        var doc=doc("ID | Amount\nA | 1\nA | 2\nB | 3\n");assertEquals("3",DocumentTableStatistics.calculate(doc,plan("COUNT",null,List.of(),List.of(),null)).groups().get(0).value());assertEquals("2",DocumentTableStatistics.calculate(doc,plan("DISTINCT_COUNT","ID",List.of(),List.of(),null)).groups().get(0).value());assertEquals("2",DocumentTableStatistics.calculate(doc,plan("AVG","Amount",List.of(),List.of(),null)).groups().get(0).value());assertEquals("0",DocumentTableStatistics.calculate(doc,plan("SUM","Amount",List.of(filter("ID","EQ","missing","TEXT")),List.of(),null)).groups().get(0).value());
    }
    @Test void csvPreservesQuotedNewlinesCommasAndRejectsCorruptRecords()throws Exception{
        String csv="ID,Note,Amount\r\nA,\"quoted, value\nsecond line\",\"1,200.50\"\r\nB,\"a\"\"b\",-10\r\n";
        var parser=new SourceDocumentParser();var source=parser.parse(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8),"test.csv");var doc=new DocumentQaService.DocSession("id","o","test.csv",List.of(),List.of(),0,0,source,List.of(),"v1");
        var result=DocumentTableStatistics.calculate(doc,plan("SUM","Amount",List.of(),List.of(),null));assertTrue(result.accepted());assertEquals("1190.50",result.groups().get(0).value());assertEquals("quoted, value\nsecond line",result.audit().get(0).cells().get("Note"));assertThrows(BizException.class,()->DelimitedTableReader.csv("a,b\n\"unclosed,x"));
    }
    @Test void handlesSignedPublicCurrencyAndRequiresExplicitUnitForUnmarkedValues(){
        var doc=doc("ID | Amount\nA | £1,000.10\nB | -£20.10\nC | 0\n");assertFalse(DocumentTableStatistics.calculate(doc,plan("SUM","Amount",List.of(),List.of(),null)).accepted());
        var plan=new DocumentTableStatistics.Plan("v1",List.of("table-1"),"SUM","Amount",List.of(),List.of(),null,"GBP");var result=DocumentTableStatistics.calculate(doc,plan);assertTrue(result.accepted());assertEquals("980.00",result.groups().get(0).value());
        assertFalse(DocumentTableStatistics.calculate(doc("ID | Amount\nA | $10\n"),plan).accepted());assertFalse(DocumentTableStatistics.calculate(doc("ID | Currency | Amount\nA | CNY | 10\n"),plan).accepted());
    }
    @Test void excelMultilineAndTabsStayInsideTheirRecord()throws Exception{
        byte[] bytes;
        try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var out=new java.io.ByteArrayOutputStream()){
            var sheet=workbook.createSheet("Ledger");var header=sheet.createRow(0);header.createCell(0).setCellValue("ID");header.createCell(1).setCellValue("Note");header.createCell(2).setCellValue("Amount");
            var a=sheet.createRow(1);a.createCell(0).setCellValue("A");a.createCell(1).setCellValue("line1\nline2\tsplit");a.createCell(2).setCellValue(1);
            var b=sheet.createRow(2);b.createCell(0).setCellValue("B");b.createCell(1).setCellValue("C:\\temp");b.createCell(2).setCellValue(2);workbook.write(out);bytes=out.toByteArray();
        }
        var source=new SourceDocumentParser().parse(bytes,"test.xlsx");var doc=new DocumentQaService.DocSession("id","o","test.xlsx",List.of(),List.of(),0,0,source,List.of(),"v1");var result=DocumentTableStatistics.calculate(doc,plan("SUM","Amount",List.of(),List.of(),null));
        assertTrue(result.accepted());assertEquals(2,result.totalRows());assertEquals("3",result.groups().get(0).value());assertEquals("line1\nline2\tsplit",result.audit().get(0).cells().get("Note"));assertEquals("C:\\temp",result.audit().get(1).cells().get("Note"));
    }
    @Test void legacyOfficeTextIsNotDecodedAndRequiresRepreparation(){
        var source=doc("ID\tAmount\tNote\nA\t1\tC:\\temp\n").source;var old=new DocumentQaService.DocSession("id","o","old.xlsx",List.of(),List.of(),0,0,source,List.of(),"v1");var result=DocumentTableStatistics.calculate(old,plan("SUM","Amount",List.of(),List.of(),null));
        assertFalse(result.accepted());assertEquals("C:\\temp",result.audit().get(0).cells().get("Note"));
    }
}
