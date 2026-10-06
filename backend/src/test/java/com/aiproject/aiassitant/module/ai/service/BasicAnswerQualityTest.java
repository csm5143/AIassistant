package com.aiproject.aiassitant.module.ai.service;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class BasicAnswerQualityTest {
    @Test void systemDateDoesNotTriggerWebButWeatherAndExplicitResearchDo() {
        var budget=new SearchBudgetService();
        for(String q:new String[]{"今天是几月几号？","今天星期几？","What is the date today?"}) {
            assertFalse(ResearchPolicy.explicitWeb(q));
            assertFalse(ResearchPolicy.decide("AUTO",q,false,false,false,null).prefetch());
            assertEquals(SearchBudgetService.Tier.NONE,budget.classify(q));
        }
        for(String q:new String[]{"今天北京天气怎么样？","帮我写最新政策的总结，请联网核实","请联网查阅 PostgreSQL 官方文档"}) {
            assertTrue(ResearchPolicy.decide("AUTO",q,false,false,false,null).prefetch());
            assertNotEquals(SearchBudgetService.Tier.NONE,budget.classify(q));
        }
    }
    @Test void shortVisionAnswerIsContentNotRawJson()throws Exception {
        var r=ApiManager.parseVisionResult("{\"choices\":[{\"message\":{\"content\":\"红色圆形\"}}],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":6}}","vision");
        assertEquals("红色圆形",r.text()); assertEquals(120,r.promptTokens()); assertEquals(6,r.completionTokens());
        assertTrue(r.usageReported());
        assertThrows(IOException.class,()->ApiManager.parseVisionResult("{\"error\":\"bad\"}","vision"));
    }
    @Test void convertedImageMimeMatchesBytesAndKeepsTextResolution()throws Exception {
        var image=new BufferedImage(2600,1200,BufferedImage.TYPE_INT_RGB);
        var out=new ByteArrayOutputStream(); ImageIO.write(image,"bmp",out);
        String url=VisionImageInput.dataUrl(out.toByteArray(),"bmp");
        assertTrue(url.startsWith("data:image/jpeg;base64,"));
        var decoded=ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(url.split(",",2)[1])));
        assertEquals(2048,decoded.getWidth());
    }
}
