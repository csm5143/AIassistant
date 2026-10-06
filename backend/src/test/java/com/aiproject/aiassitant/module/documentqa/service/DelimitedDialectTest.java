package com.aiproject.aiassitant.module.documentqa.service;
import com.aiproject.aiassitant.common.BizException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DelimitedDialectTest {
    @Test void semicolonOriginalQuotedHeaderAndBom(){
        var rows=DelimitedTableReader.csv("\uFEFF\"fixed acidity\";\"alcohol\";\"quality\"\r\n7.4;9.4;5\r\n");
        assertEquals(List.of("fixed acidity","alcohol","quality"),rows.get(0));
        assertEquals(List.of("7.4","9.4","5"),rows.get(1));
    }
    @Test void quotedSeparatorsCannotSelectTheDialect(){
        assertEquals(',',DelimitedTableReader.detectDelimiter("\"a;b;c\",d\n1,2"));
        assertEquals(List.of("a;b;c","d"),DelimitedTableReader.csv("\"a;b;c\",d\n1,2").get(0));
    }
    @Test void tabsAndQuotedNewlinesRemainCells(){
        assertEquals(List.of("a,b","x\ny"),DelimitedTableReader.csv("name\tnote\n\"a,b\"\t\"x\ny\"").get(1));
    }
    @Test void singleColumnQuotedSemicolonIsNotSplit(){
        assertEquals(List.of(List.of("a;b"),List.of("c;d")),DelimitedTableReader.csv("\"a;b\"\n\"c;d\""));
    }
    @Test void ambiguousAndBrokenQuotingAreRejected(){
        assertThrows(BizException.class,()->DelimitedTableReader.csv("a,b;c\n1,2;3"));
        assertThrows(BizException.class,()->DelimitedTableReader.csv("a;b\n\"1\"x;2"));
        assertThrows(BizException.class,()->DelimitedTableReader.csv("a;b\n\"1;2"));
        assertThrows(BizException.class,()->DelimitedTableReader.detectDelimiter("a".repeat(65537)));
    }
    @Test void escapeRepresentationIsReversible(){
        String value="a;\t\"b\"\n\\n\r";
        assertEquals(value,DelimitedTableReader.decode(DelimitedTableReader.encode(value)));
    }
}
