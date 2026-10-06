package com.aiproject.aiassitant.module.ai.service;

import com.aiproject.aiassitant.common.BizException;
import dev.langchain4j.data.message.*;
import java.nio.file.*;
import java.util.*;

/** Encodes local uploads as native image blocks; no recognition API call is made here. */
public final class NativeVisionMessages {
    private NativeVisionMessages() {}

    public static UserMessage from(String text, List<String> urls) {
        if (urls == null || urls.isEmpty()) return UserMessage.from(text);
        if (urls.size() > 8) throw new BizException(400, "单次最多分析 8 张图片");
        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from(text));
        Path root = Paths.get("uploads/chat-images").toAbsolutePath().normalize();
        for (String url : urls) {
            if (url == null || !url.matches("/api/chat/images/[a-zA-Z0-9_-]+\\.(png|jpg|jpeg|gif|webp|bmp)"))
                throw new BizException(400, "图片地址无效，请重新上传");
            String name = url.substring(url.lastIndexOf('/') + 1);
            Path file = root.resolve(name).normalize();
            try {
                if (!file.startsWith(root) || !Files.isRegularFile(file) || Files.size(file) > 10 * 1024 * 1024)
                    throw new BizException(400, "图片不存在或超过大小限制，请重新上传");
                String data = VisionImageInput.dataUrl(Files.readAllBytes(file), name.substring(name.lastIndexOf('.') + 1));
                int separator = data.indexOf(";base64,");
                contents.add(ImageContent.from(data.substring(separator + 8), data.substring(5, separator)));
            } catch (java.io.IOException e) {
                throw new BizException(400, "图片读取失败，请重新上传");
            }
        }
        return UserMessage.from(contents);
    }

    public static boolean hasImages(List<ChatMessage> messages) {
        return messages.stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                .anyMatch(m -> m.contents().stream().anyMatch(ImageContent.class::isInstance));
    }

    public static int textLength(ChatMessage message) {
        if(message instanceof UserMessage user)return user.contents().stream().filter(TextContent.class::isInstance).map(TextContent.class::cast).mapToInt(t->t.text().length()).sum();
        if(message instanceof SystemMessage system)return system.text().length();
        if(message instanceof AiMessage assistant)return assistant.text()==null?0:assistant.text().length();
        if(message instanceof ToolExecutionResultMessage tool)return tool.text().length();
        return 0;
    }

    /** Do not count base64 characters as text tokens when provider usage is unavailable. */
    public static long estimateTokens(List<ChatMessage> messages) {
        long tokens = 0;
        for (ChatMessage m : messages) {
            if (m instanceof UserMessage user) {
                for (Content c : user.contents()) {
                    if (c instanceof TextContent text) tokens += Math.max(1, text.text().length() / 2);
                    else if (c instanceof ImageContent) tokens += 1024;
                }
            } else tokens += Math.max(1, m.toString().length() / 2);
        }
        return tokens;
    }
}
