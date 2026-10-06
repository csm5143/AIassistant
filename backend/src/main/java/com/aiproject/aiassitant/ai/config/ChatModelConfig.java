package com.aiproject.aiassitant.ai.config;

import com.aiproject.aiassitant.module.ai.service.ChatModelFactory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Delegates to ChatModelFactory (DB-driven).
 * No hardcoded config — everything comes from the ai_model_config table.
 */
@Configuration
public class ChatModelConfig {

    @Bean
    public ChatLanguageModel chatLanguageModel(ChatModelFactory factory) {
        return DeferredModel.create(ChatLanguageModel.class, factory::getDefaultChatModel);
    }

    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel(ChatModelFactory factory) {
        return DeferredModel.create(StreamingChatLanguageModel.class, factory::getDefaultStreamingModel);
    }
}
