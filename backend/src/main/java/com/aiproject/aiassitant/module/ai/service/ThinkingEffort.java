package com.aiproject.aiassitant.module.ai.service;
import com.aiproject.aiassitant.common.BizException;
import java.util.*;
public final class ThinkingEffort {
    private ThinkingEffort() {}
    public static String normalize(String effort) {
        String value=effort==null||effort.isBlank()?"none":effort.toLowerCase(Locale.ROOT);
        if(!Set.of("none","low","high","max").contains(value))throw new BizException(400,"思考强度只能为快速、轻度、深度或极高");
        return value;
    }
}
