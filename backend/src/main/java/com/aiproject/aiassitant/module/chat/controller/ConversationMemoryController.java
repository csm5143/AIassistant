package com.aiproject.aiassitant.module.chat.controller;

import com.aiproject.aiassitant.common.*;
import com.aiproject.aiassitant.module.ai.service.ConversationMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/chat/sessions/{id}/memory")
@RequiredArgsConstructor
public class ConversationMemoryController {
    private final ConversationMemoryService memory;
    private final com.aiproject.aiassitant.module.ai.service.AiChatService chat;
    private final com.aiproject.aiassitant.module.documentqa.service.DocumentQaService documents;
    private void idle(String id){memory.assertOwned(id,SecurityUtil.getCurrentUserId());var document=documents.getSession(id);if(chat.isRunning(id)||document!=null&&document.busy.get())throw new BizException(409,"请先停止回答，再修改记忆");}
    public record Write(String label,String value,String category,Boolean pinned,long version) {}
    public record Toggle(boolean enabled,long version) {}
    @GetMapping public R<Map<String,Object>> inspect(@PathVariable String id){return R.ok(memory.inspect(id,SecurityUtil.getCurrentUserId()));}
    @PostMapping("/preview") public R<Map<String,Object>> preview(@PathVariable String id,@RequestBody Map<String,String> body){return R.ok(memory.preview(id,SecurityUtil.getCurrentUserId(),body.get("query")));}
    @PostMapping public R<Map<String,Object>> add(@PathVariable String id,@RequestBody Write body){idle(id);return R.ok(memory.edit(id,SecurityUtil.getCurrentUserId(),null,new ConversationMemoryService.Edit(body.label(),body.value(),body.category(),body.pinned()),body.version()));}
    @PatchMapping("/{item}") public R<Map<String,Object>> edit(@PathVariable String id,@PathVariable String item,@RequestBody Write body){idle(id);return R.ok(memory.edit(id,SecurityUtil.getCurrentUserId(),item,new ConversationMemoryService.Edit(body.label(),body.value(),body.category(),body.pinned()),body.version()));}
    @DeleteMapping("/{item}") public R<Map<String,Object>> forget(@PathVariable String id,@PathVariable String item,@RequestParam long version){idle(id);return R.ok(memory.forget(id,SecurityUtil.getCurrentUserId(),item,version));}
    @DeleteMapping public R<Map<String,Object>> clear(@PathVariable String id,@RequestParam long version){idle(id);return R.ok(memory.clear(id,SecurityUtil.getCurrentUserId(),version));}
    @PatchMapping public R<Map<String,Object>> toggle(@PathVariable String id,@RequestBody Toggle body){idle(id);return R.ok(memory.enabled(id,SecurityUtil.getCurrentUserId(),body.enabled(),body.version()));}
    @GetMapping("/{item}/source") public R<Map<String,Object>> source(@PathVariable String id,@PathVariable String item){return R.ok(memory.source(id,SecurityUtil.getCurrentUserId(),item));}
}
