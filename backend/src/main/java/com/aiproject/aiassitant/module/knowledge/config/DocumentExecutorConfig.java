package com.aiproject.aiassitant.module.knowledge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class DocumentExecutorConfig {
    @Bean("documentProcessor")
    public ThreadPoolTaskExecutor documentProcessor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        // MySQL PENDING rows are the queue. No work is left only in JVM memory.
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("document-process-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
