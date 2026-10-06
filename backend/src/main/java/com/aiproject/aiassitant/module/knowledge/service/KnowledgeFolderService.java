package com.aiproject.aiassitant.module.knowledge.service;
import com.aiproject.aiassitant.common.BizException;
import com.aiproject.aiassitant.module.knowledge.entity.*;
import com.aiproject.aiassitant.module.knowledge.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
public class KnowledgeFolderService {
    private final FolderPathService paths;
    private final KnowledgeStorageService storage;
    private final KnowledgeService knowledge;
    private final KbCollectionMapper collections;
    private final KbDocumentMapper documents;
    private final DocumentProcessingService processor;
    private final KnowledgeIndexVersion indexVersion;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    public record SyncEntry(String path, String status, String documentId, String error) {}
    public record SyncResult(String folderPath, int added, int updated, int removed, int unchanged, int skipped, LocalDateTime syncedAt, List<SyncEntry> entries) {}
    private record SourceFile(Path file, String relative, String key, String hash, long size) {}
    private record Manifest(List<SourceFile> files, Set<String> seen, List<SyncEntry> skipped) {}
    private boolean busy(KbDocument doc) { return Set.of("PENDING", "PROCESSING", "VECTORIZING").contains(doc.getStatus()); }
    private ReentrantLock acquire(String id) {
        var lock = locks.computeIfAbsent(id, key -> new ReentrantLock());
        if (!lock.tryLock()) throw new BizException(409, "此知识库正在同步，请稍后再试"); return lock;
    }
    public SyncResult bind(String id, String rawPath) {
        var collection = knowledge.getCollection(id); var lock = acquire(id);
        try {
            Path folder = paths.validateDirectory(rawPath, true);
            if (collection.getFolderPath() != null && !Path.of(collection.getFolderPath()).equals(folder)) throw new BizException(409, "请先解除当前绑定，再绑定其他文件夹");
            Manifest manifest = scan(folder);
            collections.update(null, new LambdaUpdateWrapper<KbCollection>().eq(KbCollection::getId, id).eq(KbCollection::getUserId, collection.getUserId()).set(KbCollection::getFolderPath, folder.toString()));
            collection.setFolderPath(folder.toString()); return apply(collection, folder, manifest);
        } finally { lock.unlock(); }
    }
    public SyncResult sync(String id) {
        var collection = knowledge.getCollection(id); var lock = acquire(id);
        try {
            if (collection.getFolderPath() == null) throw new BizException(400, "此知识库尚未绑定文件夹");
            Path folder = paths.validateDirectory(collection.getFolderPath(), true); return apply(collection, folder, scan(folder));
        } finally { lock.unlock(); }
    }
    private Manifest scan(Path folder) {
        List<SourceFile> files = new ArrayList<>(); Set<String> seen = new HashSet<>(); List<SyncEntry> skipped = new ArrayList<>(); int[] entries = {0};
        try {
            Files.walkFileTree(folder, EnumSet.noneOf(FileVisitOption.class), 32, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                    if (++entries[0] > 20000) throw new IOException("文件夹条目过多，请选择更小的资料目录");
                    if (!dir.equals(folder) && (FolderPathService.EXCLUDED.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT)) || !dir.toRealPath().startsWith(folder))) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (++entries[0] > 20000) throw new IOException("文件夹条目过多，请选择更小的资料目录");
                    if (attributes.isDirectory()) throw new IOException("文件夹层级超过32层，请选择较浅的资料目录；原有记录已保留");
                    if (!attributes.isRegularFile() || attributes.isSymbolicLink() || !FolderPathService.supported(file)) return FileVisitResult.CONTINUE;
                    String relative = FolderPathService.validateRelativePath(folder.relativize(file).toString());
                    String normalized = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? relative.toLowerCase(Locale.ROOT) : relative;
                    String key = FolderPathService.digest(normalized.getBytes(StandardCharsets.UTF_8)); seen.add(key);
                    if (seen.size() > 1000) throw new IOException("单次同步最多1000个文档，请拆分资料文件夹");
                    if (!file.toRealPath().startsWith(folder)) { skipped.add(new SyncEntry(relative, "skipped", null, "跳过目录外部链接")); return FileVisitResult.CONTINUE; }
                    if (attributes.size() > FolderPathService.MAX_FILE_SIZE) { skipped.add(new SyncEntry(relative, "skipped", null, "超过单文件100MB限制")); return FileVisitResult.CONTINUE; }
                    files.add(new SourceFile(file.toAbsolutePath().normalize(), relative, key, FolderPathService.fingerprint(file), attributes.size())); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                    throw new IOException("无法完整读取文件夹；原有记录已保留，请检查访问权限");
                }
            }); return new Manifest(files, seen, skipped);
        } catch (IOException e) { throw new BizException(400, e.getMessage()); }
    }
    private SyncResult apply(KbCollection collection, Path folder, Manifest manifest) {
        List<KbDocument> existing = documents.selectList(new LambdaQueryWrapper<KbDocument>().eq(KbDocument::getCollectionId, collection.getId()).eq(KbDocument::getUserId, collection.getUserId()).eq(KbDocument::getSourceKind, "FOLDER"));
        Map<String, KbDocument> byKey = new HashMap<>(); existing.forEach(doc -> byKey.put(doc.getSourceKey(), doc));
        int added=0, updated=0, removed=0, unchanged=0; List<SyncEntry> result = new ArrayList<>(manifest.skipped());
        for (SourceFile source : manifest.files()) {
            var doc = byKey.get(source.key());
            if (doc != null && source.hash().equals(doc.getSourceFingerprint()) && !Set.of("FAILED", "VECTOR_FAILED").contains(doc.getStatus())) { unchanged++; continue; }
            if (doc != null && busy(doc)) { result.add(new SyncEntry(source.relative(), "skipped", doc.getId(), "正在处理，请完成后再次同步")); continue; }
            boolean isNew = doc == null;
            if (isNew) {
                doc = new KbDocument(); doc.setId(UUID.randomUUID().toString().replace("-", "")); doc.setCollectionId(collection.getId()); doc.setUserId(collection.getUserId()); doc.setSourceKind("FOLDER"); doc.setSourceKey(source.key());
                doc.setChunkSize(collection.getChunkSize()); doc.setChunkOverlap(collection.getChunkOverlap());
            }
            doc.setFilename(source.file().getFileName().toString()); doc.setStoragePath(source.file().toString()); doc.setSourceRelativePath(source.relative()); doc.setSourceFingerprint(source.hash()); doc.setSizeBytes(source.size());
            doc.setMimeType(source.file().toString().toLowerCase(Locale.ROOT).endsWith(".pdf") ? "application/pdf" : "application/octet-stream"); doc.setStatus("PENDING"); doc.setErrorMessage(null);
            if (isNew) { documents.insert(doc); added++; } else {
                documents.update(null, new LambdaUpdateWrapper<KbDocument>().eq(KbDocument::getId, doc.getId()).eq(KbDocument::getUserId, collection.getUserId())
                        .set(KbDocument::getFilename, doc.getFilename()).set(KbDocument::getStoragePath, doc.getStoragePath()).set(KbDocument::getSourceRelativePath, doc.getSourceRelativePath()).set(KbDocument::getSourceFingerprint, doc.getSourceFingerprint())
                        .set(KbDocument::getSizeBytes, doc.getSizeBytes()).set(KbDocument::getStatus, "PENDING").set(KbDocument::getErrorMessage, null)); updated++;
            }
            indexVersion.changed(collection.getUserId());
            try { processor.processDocument(doc.getId()); result.add(new SyncEntry(source.relative(), isNew ? "added" : "updated", doc.getId(), null)); }
            catch (org.springframework.core.task.TaskRejectedException e) { processor.markQueueFailure(doc.getId()); result.add(new SyncEntry(source.relative(), "failed", doc.getId(), "处理队列已满，可稍后同步重试")); }
        }
        for (KbDocument doc : existing) if (!manifest.seen().contains(doc.getSourceKey())) {
            if (busy(doc)) { result.add(new SyncEntry(doc.getSourceRelativePath(), "skipped", doc.getId(), "正在处理，请完成后再次同步")); continue; }
            knowledge.deleteDocument(doc.getId()); removed++; result.add(new SyncEntry(doc.getSourceRelativePath(), "removed", doc.getId(), null));
        }
        LocalDateTime now=LocalDateTime.now(); collections.update(null, new LambdaUpdateWrapper<KbCollection>().eq(KbCollection::getId, collection.getId()).eq(KbCollection::getUserId, collection.getUserId()).set(KbCollection::getFolderSyncedAt, now));
        int skipped=(int)result.stream().filter(entry -> Set.of("skipped", "failed").contains(entry.status())).count();
        if(added+updated+removed>0)indexVersion.changed(collection.getUserId());
        return new SyncResult(folder.toString(), added, updated, removed, unchanged, skipped, now, result);
    }
    @Transactional
    public void unbind(String id) {
        var collection=knowledge.getCollection(id); var lock=acquire(id);
        try {
            if (collection.getFolderPath()==null) return;
            List<KbDocument> linked=documents.selectList(new LambdaQueryWrapper<KbDocument>().eq(KbDocument::getCollectionId,id).eq(KbDocument::getUserId,collection.getUserId()).eq(KbDocument::getSourceKind,"FOLDER"));
            if (linked.stream().anyMatch(this::busy)) throw new BizException(409,"文件正在处理，请完成后再解除绑定");
            for(KbDocument doc:linked) {
                Path copy;
                try { copy=storage.saveBytes(doc.getId(),doc.getFilename(),storage.readBytes(doc)); }
                catch(IOException e) { throw new BizException(409,"源文件不可读取，请先同步处理缺失文件再解除绑定"); }
                documents.update(null,new LambdaUpdateWrapper<KbDocument>().eq(KbDocument::getId,doc.getId()).eq(KbDocument::getUserId,collection.getUserId()).set(KbDocument::getStoragePath,copy.toString())
                        .set(KbDocument::getSourceKind,"IMPORT").set(KbDocument::getSourceKey,null).set(KbDocument::getSourceFingerprint,null));
            }
            indexVersion.changed(collection.getUserId());
            collections.update(null,new LambdaUpdateWrapper<KbCollection>().eq(KbCollection::getId,id).eq(KbCollection::getUserId,collection.getUserId()).set(KbCollection::getFolderPath,null).set(KbCollection::getFolderSyncedAt,null));
        } finally { lock.unlock(); }
    }
}
