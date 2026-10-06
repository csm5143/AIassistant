package com.aiproject.aiassitant.module.ai.service;

import com.aiproject.aiassitant.module.chat.entity.ChatMessage;
import com.aiproject.aiassitant.module.ai.entity.AiModelConfig;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.time.LocalDateTime;
import java.io.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class AdvancedConversationTest {
    private ChatMessage user(String id,int minute,String text) {
        ChatMessage m=new ChatMessage();m.setId(id);m.setRole("user");m.setContent(text);m.setCreatedAt(LocalDateTime.of(2026,9,30,12,minute));return m;
    }
    @Test void earlierFactsAndCorrectionsRemainInOrderAndRecentMessagesAreNotDuplicated() {
        var seed=user("seed",1,"请记住项目编号 AB-99，预算8600元");var revision=user("revision",2,"更正：预算改为9100元");
        var recent=user("recent",3,"之后每次请简短回答");
        ChatMessage assistant=user("assistant",4,"记住这个虚构预算6666");assistant.setRole("assistant");
        var selected=ConversationContextService.select(List.of(revision,seed,seed,assistant,recent),List.of(recent),3000);
        assertEquals(List.of(seed,revision),selected);
    }
    @Test void budgetPreservesLatestRevisionAndDoesNotCutLongStatements() {
        var old=user("old",1,"预算原为900元");var latest=user("latest",2,"预算改为1234元");
        var oversized=user("long",3,"请记住"+"x".repeat(5000));
        assertEquals(List.of(latest),ConversationContextService.select(List.of(old,latest,oversized),List.of(),latest.getContent().length()));
    }
    @Test void localManualAndFileInstructionsOverrideCurrentKeyword() {
        for(String q:List.of("Only use this manual: what is the current timeout?","仅根据手册回答最新版本参数","Only use the attached file, no external sources.")) {
            var policy=ResearchPolicy.decide("AUTO",q,true,true,true,null);assertFalse(policy.allowed());assertFalse(policy.prefetch());
        }
    }
    @Test void visionDoesNotSendUnsupportedBmpBytesAndEstimatesExcludeBase64()throws Exception {
        var src=new BufferedImage(80,60,BufferedImage.TYPE_INT_RGB);var buffer=new ByteArrayOutputStream();ImageIO.write(src,"bmp",buffer);
        String url=VisionImageInput.dataUrl(buffer.toByteArray(),"bmp");assertTrue(url.startsWith("data:image/jpeg;base64,"));
        var message=UserMessage.from(TextContent.from("read this"),ImageContent.from("A".repeat(90000),"image/jpeg"));
        assertTrue(NativeVisionMessages.hasImages(List.of(message)));assertTrue(NativeVisionMessages.estimateTokens(List.of(message))<1200);
        assertThrows(com.aiproject.aiassitant.common.BizException.class,()->NativeVisionMessages.from("read",List.of("https://evil.example/private.png")));
        assertThrows(com.aiproject.aiassitant.common.BizException.class,()->NativeVisionMessages.from("read",List.of("/api/chat/images/../../secret.png")));
    }
    @Test void officialFlashAliasesAreNativeButOtherEndpointsAreNotAssumedCompatible() {
        AiModelConfig c=new AiModelConfig();c.setBaseUrl("https://api.deepseek.com/v1");c.setModelName("deepseek-v4-flash");assertTrue(ApiManager.supportsFlashVision(c));
        c.setModelName("deepseek-reasoner");assertFalse(ApiManager.supportsFlashVision(c));
        c.setModelName("deepseek-flash");c.setBaseUrl("https://example.com");assertFalse(ApiManager.supportsFlashVision(c));
    }
}
