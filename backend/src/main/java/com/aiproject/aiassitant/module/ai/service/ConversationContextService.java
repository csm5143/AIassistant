package com.aiproject.aiassitant.module.ai.service;

import com.aiproject.aiassitant.module.chat.entity.ChatMessage;
import com.aiproject.aiassitant.module.chat.mapper.ChatMessageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded earlier USER messages preserve requested facts and revisions without a paid summarization call. */
@Service
@RequiredArgsConstructor
public class ConversationContextService {
    private final ChatMessageMapper mapper;
    private static final Pattern IMPORTANT=Pattern.compile("(?i)记住|记得|约定|更正|改为|替换|以后|今后|始终|不要|不得|预算|负责人|代号|工单|偏好|\\b(remember|correction|replace|from now|budget|owner|ticket|project code|always|never)\\b");
    public List<ChatMessage> earlier(String sessionId,List<ChatMessage> recent) {
        List<ChatMessage> start=mapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId,sessionId).eq(ChatMessage::getRole,"user")
                .orderByAsc(ChatMessage::getCreatedAt).orderByAsc(ChatMessage::getId).last("LIMIT 8"));
        List<ChatMessage> latest=mapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId,sessionId).eq(ChatMessage::getRole,"user")
                .orderByDesc(ChatMessage::getCreatedAt).orderByDesc(ChatMessage::getId).last("LIMIT 200"));
        List<ChatMessage> pool=new ArrayList<>(start);pool.addAll(latest);
        return select(pool,recent,3000);
    }
    static List<ChatMessage> select(List<ChatMessage> pool,List<ChatMessage> recent,int budget) {
        Set<String> current=new HashSet<>();recent.forEach(m->current.add(m.getId()));
        Map<String,ChatMessage> unique=new LinkedHashMap<>();
        for(ChatMessage m:pool)if("user".equals(m.getRole()) && m.getContent()!=null && !current.contains(m.getId()) && IMPORTANT.matcher(m.getContent()).find()) unique.put(m.getId(),m);
        List<ChatMessage> candidates=new ArrayList<>(unique.values());
        candidates.sort(Comparator.comparing(ChatMessage::getCreatedAt,Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(ChatMessage::getId));
        List<ChatMessage> chosen=new ArrayList<>();
        // Latest revisions have priority; avoid chopping a statement and changing its meaning.
        for(int i=candidates.size()-1;i>=0 && chosen.size()<8;i--){ChatMessage m=candidates.get(i);if(m.getContent().length()<=budget){chosen.add(m);budget-=m.getContent().length();}}
        Collections.reverse(chosen);return chosen;
    }
}
