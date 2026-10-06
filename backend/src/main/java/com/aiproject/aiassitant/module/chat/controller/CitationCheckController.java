package com.aiproject.aiassitant.module.chat.controller;

import com.aiproject.aiassitant.common.*;
import com.aiproject.aiassitant.module.ai.service.CitationVerificationService;
import com.aiproject.aiassitant.module.chat.entity.*;
import com.aiproject.aiassitant.module.chat.mapper.*;
import com.aiproject.aiassitant.module.chat.service.TokenUsageService;
import com.aiproject.aiassitant.module.knowledge.service.KnowledgeService;
import com.aiproject.aiassitant.module.documentqa.service.DocumentQaService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** No automatic extra model call. Consent is required for each owned-message check. */
@RestController @RequiredArgsConstructor
@RequestMapping("/chat/sessions/{sessionId}/messages/{messageId}")
public class CitationCheckController {
    private final ChatSessionMapper sessions;
    private final ChatMessageMapper messages;
    private final KnowledgeService knowledge;
    private final DocumentQaService documents;
    private final CitationVerificationService verifier;
    private final TokenUsageService tokens;
    private final ObjectMapper json;
    private final Set<String> checking=ConcurrentHashMap.newKeySet();
    public record Request(boolean consent) {}
    @PostMapping("/citation-check")
    public R<Map<String,Object>> check(@PathVariable String sessionId,@PathVariable String messageId,@RequestBody Request request)throws Exception{
        String owner=SecurityUtil.getCurrentUserId();
        ChatSession session=sessions.selectOne(new LambdaQueryWrapper<ChatSession>().eq(ChatSession::getId,sessionId).eq(ChatSession::getUserId,owner));
        if(session==null)throw BizException.notFound("会话不存在");
        ChatMessage message=messages.selectOne(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getId,messageId).eq(ChatMessage::getSessionId,sessionId).eq(ChatMessage::getRole,"assistant"));
        if(message==null)throw BizException.notFound("回答不存在");
        if(request==null||!request.consent())throw new BizException(400,"请先确认：本条回答引用的原文将发送给当前配置的模型进行核验，至多增加一次模型请求。");
        if(!tokens.checkQuota(owner))throw new BizException(429,"当前用量额度不足，请稍后再核验");
        if(!checking.add(owner))throw new BizException(409,"已有引用核验正在进行，请稍后重试");
        try{
            Map<String,Object> extra=message.getExtra()==null?new LinkedHashMap<>():json.readValue(message.getExtra(),new TypeReference<LinkedHashMap<String,Object>>(){});
            List<Map<String,Object>> sources=json.convertValue(extra.getOrDefault("citations",List.of()),new TypeReference<List<Map<String,Object>>>(){});
            for(var cite:sources){
                cite.put("_evidence",""); // Never trust a stored override or fall back after failed ownership checks.
                String type=Objects.toString(cite.get("sourceType"),"knowledge");
                try{
                    if(type.equals("knowledge")){
                        var chunk=knowledge.getChunk(Objects.toString(cite.get("chunkId"),""));
                        if(!Objects.equals(chunk.getDocumentId(),cite.get("documentId")))continue;
                        cite.put("_evidence",chunk.getContent());
                    }else if(type.equals("temporary")){
                        if(!sessionId.equals(cite.get("sessionId"))||!(cite.get("ordinal") instanceof Number ordinal)||cite.get("sourceVersion")==null)continue;
                        var doc=documents.getSessionOrThrow(sessionId);
                        if(!doc.sourceVersion.equals(cite.get("sourceVersion"))||ordinal.intValue()<1||ordinal.intValue()>doc.chunks.size())continue;
                        cite.put("_evidence",doc.chunks.get(ordinal.intValue()-1));
                    }else if(type.equals("web"))cite.put("_evidence",Objects.toString(cite.get("evidenceText"),""));
                }catch(BizException unavailable){/* Deleted/expired/foreign sources stay unverified. */}
            }
            // Source text is only sent here, after ownership and one-message consent checks.
            var result=verifier.verify("",message.getContent(),sources,session.getModel(),()->false);
            var usage=result.usage();if(usage.modelRequests()>0)tokens.recordUsage(owner,session.getModel(),usage.promptTokens(),usage.completionTokens());
            Map<String,Object> audit=new LinkedHashMap<>(result.audit());audit.put("checkedAt",Instant.now().toString());audit.put("model",session.getModel());
            extra.put("citations",result.citations());extra.put("citationVerification",audit);
            extra.put("citationVerificationUsage",usage);
            // The draft is preserved. Only metadata changes; a simultaneous edit wins.
            int changed=messages.update(null,new LambdaUpdateWrapper<ChatMessage>().eq(ChatMessage::getId,messageId).eq(ChatMessage::getSessionId,sessionId)
                .eq(ChatMessage::getContent,message.getContent()).set(ChatMessage::getExtra,json.writeValueAsString(extra)));
            if(changed!=1)throw new BizException(409,"回答已发生变化，请刷新后重新核验");
            return R.ok(Map.of("answer",result.answer(),"citations",result.citations(),"audit",audit,"usage",usage));
        }finally{checking.remove(owner);}
    }
}
