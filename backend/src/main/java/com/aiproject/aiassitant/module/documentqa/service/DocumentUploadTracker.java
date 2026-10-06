package com.aiproject.aiassitant.module.documentqa.service;

import com.aiproject.aiassitant.common.BizException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Short-lived progress metadata only; document content remains in the normal owned session store. */
@Service
public class DocumentUploadTracker {
    private record Task(String ownerId, long createdAt, DocumentQaService.UploadProgress progress) {}
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    public void start(String id, String ownerId) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{8,64}")) throw new BizException(400, "上传任务编号格式无效");
        prune();
        if (tasks.size() >= 1000) throw new BizException(503, "上传任务繁忙，请稍后重试");
        if (tasks.putIfAbsent(id, new Task(ownerId, System.currentTimeMillis(),
                new DocumentQaService.UploadProgress("PARSING", 0, 0))) != null) throw new BizException(409, "上传任务编号已使用");
    }
    public void update(String id, DocumentQaService.UploadProgress progress) {
        tasks.computeIfPresent(id, (key, task) -> new Task(task.ownerId(), task.createdAt(), progress));
    }
    public DocumentQaService.UploadProgress get(String id, String ownerId) {
        Task task = tasks.get(id);
        if (task == null || !task.ownerId().equals(ownerId)) throw BizException.notFound("上传任务不存在");
        return task.progress();
    }
    @Scheduled(fixedDelay = 60_000)
    public void prune() { tasks.entrySet().removeIf(entry -> System.currentTimeMillis() - entry.getValue().createdAt() > 35 * 60_000L); }
}
