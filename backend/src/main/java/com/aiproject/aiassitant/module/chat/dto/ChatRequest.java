package com.aiproject.aiassitant.module.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Send a new user message into a session and receive a streamed SSE response")
public class ChatRequest {

    @NotBlank
    @Schema(description = "User message content")
    private String content;

    @Schema(description = "Optional knowledge-base collection ids to ground the answer")
    private List<String> collectionIds;
    private List<String> documentIds;
    private String knowledgeMode;

    @Schema(description = "Override the model for this single turn")
    private String model;

    @Schema(description = "Enable web-style streaming; default true")
    private Boolean stream = Boolean.TRUE;

    @Schema(description = "Uploaded image URLs (already stored via /chat/images/upload)")
    private List<String> images;
}
