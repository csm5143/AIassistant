package com.aiproject.aiassitant.module.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Create a new chat session")
public class CreateSessionRequest {

    @Schema(description = "Display title; auto-generated if blank")
    private String title;

    @Schema(description = "Model id to bind to this session", example = "deepseek-chat")
    private String model;

    @Schema(description = "Optional system prompt override")
    private String systemPrompt;
    private String thinkingEffort;
}
