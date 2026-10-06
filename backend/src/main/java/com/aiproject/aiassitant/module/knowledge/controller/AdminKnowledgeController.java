package com.aiproject.aiassitant.module.knowledge.controller;

import com.aiproject.aiassitant.common.R;
import com.aiproject.aiassitant.common.SecurityUtil;
import com.aiproject.aiassitant.module.knowledge.entity.KbCollection;
import com.aiproject.aiassitant.module.knowledge.service.KnowledgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Knowledge Admin", description = "Knowledge base management")
@RestController
@RequestMapping("/admin/knowledge")
@RequiredArgsConstructor
public class AdminKnowledgeController {

    private final KnowledgeService knowledgeService;

    @Operation(summary = "Admin list all collections")
    @GetMapping("/collections")
    public R<List<KbCollection>> listCollections() {
        String userId = SecurityUtil.getCurrentUserId();
        return R.ok(knowledgeService.listCollections(userId));
    }
}
