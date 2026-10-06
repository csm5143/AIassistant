package com.aiproject.aiassitant.module.documentqa;

import com.aiproject.aiassitant.common.BizException;
import com.aiproject.aiassitant.module.chat.entity.ChatSession;
import com.aiproject.aiassitant.module.chat.mapper.ChatSessionMapper;
import com.aiproject.aiassitant.module.chat.mapper.ChatMessageMapper;
import com.aiproject.aiassitant.module.documentqa.service.DocumentQaService;
import com.aiproject.aiassitant.module.documentqa.service.DocumentQaSessionStore;
import com.aiproject.aiassitant.module.knowledge.service.SourceDocumentParser;
import com.aiproject.aiassitant.security.AppPrincipal;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class DocumentQaOwnershipTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void rejectsForeignChatSessionBeforeReadingOrEmbeddingFile() {
        var principal = new AppPrincipal("user-b", "user-b", "user", List.of("USER"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "token", List.of()));
        ChatSession session = new ChatSession();
        session.setId("session-a");
        session.setUserId("user-a");
        ChatSessionMapper mapper = mock(ChatSessionMapper.class);
        when(mapper.selectById("session-a")).thenReturn(session);
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        var service = new DocumentQaService(embedding, mock(SourceDocumentParser.class), mapper,
                mock(ChatMessageMapper.class), mock(DocumentQaSessionStore.class));
        var file = new MockMultipartFile("file", "note.txt", "text/plain", "secret".getBytes());

        BizException failure = assertThrows(BizException.class, () -> service.upload(file, "session-a"));
        assertEquals(404, failure.getCode());
        verifyNoInteractions(embedding);
    }
}
