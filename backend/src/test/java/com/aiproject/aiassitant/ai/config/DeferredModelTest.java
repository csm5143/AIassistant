package com.aiproject.aiassitant.ai.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeferredModelTest {
    @Test void startupDoesNotRequireCredentialsAndCallsAreForwarded() {
        AtomicInteger calls = new AtomicInteger();
        ChatLanguageModel client = mock(ChatLanguageModel.class);
        var messages = List.of(UserMessage.from("test"));
        var answer = Response.from(AiMessage.from("answer"));
        when(client.generate(anyList())).thenReturn(answer);
        var deferred = DeferredModel.create(ChatLanguageModel.class, () -> { calls.incrementAndGet(); return client; });
        assertTrue(deferred.toString().contains("Deferred"));
        assertEquals(0, calls.get());
        assertSame(answer, deferred.generate(List.copyOf(messages)));
        assertEquals(1, calls.get());
    }
    @Test void credentialErrorsAreNotWrappedAsReflectionErrors() {
        var deferred = DeferredModel.create(ChatLanguageModel.class, () -> { throw new IllegalStateException("Not configured"); });
        assertEquals("Not configured", assertThrows(IllegalStateException.class, () -> deferred.generate("test")).getMessage());
    }
}
