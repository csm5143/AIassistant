package com.aiproject.aiassitant.module.chat.controller;
import com.aiproject.aiassitant.common.BizException;
import com.aiproject.aiassitant.module.ai.service.CitationVerificationService;
import com.aiproject.aiassitant.module.chat.entity.*;
import com.aiproject.aiassitant.module.chat.mapper.*;
import com.aiproject.aiassitant.module.chat.service.TokenUsageService;
import com.aiproject.aiassitant.module.knowledge.service.KnowledgeService;
import com.aiproject.aiassitant.module.documentqa.service.DocumentQaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CitationCheckControllerTest {
    final ChatSessionMapper sessions=mock(ChatSessionMapper.class);final ChatMessageMapper messages=mock(ChatMessageMapper.class);
    final KnowledgeService knowledge=mock(KnowledgeService.class);final DocumentQaService documents=mock(DocumentQaService.class);
    final CitationVerificationService verifier=mock(CitationVerificationService.class);final TokenUsageService tokens=mock(TokenUsageService.class);
    final CitationCheckController controller=new CitationCheckController(sessions,messages,knowledge,documents,verifier,tokens,new ObjectMapper());
    @BeforeEach void auth(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new com.aiproject.aiassitant.security.AppPrincipal("owner","owner","user",List.of("USER")),null,List.of()));}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void foreignSessionIsRejectedBeforeReadingSources(){assertEquals(404,assertThrows(BizException.class,()->controller.check("foreign","msg",new CitationCheckController.Request(true))).getCode());verifyNoInteractions(messages,knowledge,documents,verifier,tokens);}
    @Test void consentIsRequiredBeforeAnyModelOrSourceRead(){when(sessions.selectOne(any())).thenReturn(new ChatSession());when(messages.selectOne(any())).thenReturn(new ChatMessage());assertEquals(400,assertThrows(BizException.class,()->controller.check("owned","msg",new CitationCheckController.Request(false))).getCode());verifyNoInteractions(knowledge,documents,verifier,tokens);}
    @Test void exhaustedQuotaStopsBeforeSourceOrModelCalls(){when(sessions.selectOne(any())).thenReturn(new ChatSession());when(messages.selectOne(any())).thenReturn(new ChatMessage());assertEquals(429,assertThrows(BizException.class,()->controller.check("owned","msg",new CitationCheckController.Request(true))).getCode());verifyNoInteractions(knowledge,documents,verifier);}
}
