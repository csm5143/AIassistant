package com.aiproject.aiassitant.module.documentqa.controller;
import com.aiproject.aiassitant.common.*;
import com.aiproject.aiassitant.module.documentqa.service.DocumentUploadJobs;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.*;
@RestController @RequestMapping("/document-qa/jobs") @RequiredArgsConstructor
public class DocumentUploadJobsController {
    private final DocumentUploadJobs jobs;
    @PostMapping public R<Map<String,Object>> submit(@RequestParam MultipartFile file,@RequestParam String sessionId,@RequestParam(required=false) String uploadId)throws IOException{return R.ok(jobs.submit(file,sessionId,uploadId,SecurityUtil.getCurrentUserId()));}
    @GetMapping public R<List<Map<String,Object>>> list(@RequestParam String sessionId){return R.ok(jobs.list(sessionId,SecurityUtil.getCurrentUserId()));}
    @GetMapping("/{id}") public R<Map<String,Object>> get(@PathVariable String id){return R.ok(jobs.get(id,SecurityUtil.getCurrentUserId()));}
    @PostMapping("/{id}/retry") public R<Map<String,Object>> retry(@PathVariable String id){return R.ok(jobs.retry(id,SecurityUtil.getCurrentUserId()));}
    @PostMapping("/{id}/cancel") public R<Map<String,Object>> cancel(@PathVariable String id){return R.ok(jobs.cancel(id,SecurityUtil.getCurrentUserId()));}
}
