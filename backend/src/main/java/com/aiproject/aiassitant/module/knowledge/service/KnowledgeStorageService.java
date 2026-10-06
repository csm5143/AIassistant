package com.aiproject.aiassitant.module.knowledge.service;
import com.aiproject.aiassitant.common.BizException;
import com.aiproject.aiassitant.module.knowledge.entity.KbDocument;
import com.aiproject.aiassitant.module.knowledge.mapper.KbCollectionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.*;
import java.util.Comparator;

@Service
@RequiredArgsConstructor
public class KnowledgeStorageService {
    private final FolderPathService paths;
    private final KbCollectionMapper collectionMapper;
    public Path base() { return paths.storageBase(); }
    public static String safeName(String name) {
        if (name == null || name.isBlank()) return "untitled";
        String[] parts = name.replace('\\', '/').split("/");
        String value = parts[parts.length - 1].replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return value.isBlank() || value.equals(".") || value.equals("..") ? "untitled" : value;
    }
    private Path documentDirectory(String id) throws IOException {
        if (id == null || !id.matches("[a-zA-Z0-9_-]+")) throw new BizException(400, "文档ID无效");
        Path directory = base().resolve(id).normalize(); Files.createDirectories(directory);
        if (!directory.toRealPath().startsWith(base().toRealPath())) throw new BizException(403, "存储目录无效");
        return directory;
    }
    public Path saveUpload(String id, MultipartFile file) throws IOException {
        Path target = documentDirectory(id).resolve(safeName(file.getOriginalFilename()));
        if (Files.isSymbolicLink(target)) throw new BizException(403, "存储路径无效");
        file.transferTo(target); return target;
    }
    public Path saveBytes(String id, String filename, byte[] bytes) throws IOException {
        Path target = documentDirectory(id).resolve(safeName(filename));
        if (Files.isSymbolicLink(target)) throw new BizException(403, "存储路径无效");
        Files.write(target, bytes); return target;
    }
    public Path resolveFile(KbDocument doc) throws IOException {
        if ("FOLDER".equals(doc.getSourceKind())) {
            var collection = collectionMapper.selectById(doc.getCollectionId());
            if (collection == null || collection.getFolderPath() == null || !doc.getUserId().equals(collection.getUserId())) throw new BizException(403, "绑定来源无效");
            return paths.resolveSource(collection.getFolderPath(), doc.getSourceRelativePath(), doc.getStoragePath());
        }
        if (doc.getStoragePath() != null) {
            Path stored = Path.of(doc.getStoragePath()).toAbsolutePath().normalize();
            Path legacy = Path.of("uploads/knowledge").toAbsolutePath().normalize();
            for (Path allowed : new Path[]{base(), legacy}) if (stored.startsWith(allowed) && Files.isRegularFile(stored) && stored.toRealPath().startsWith(allowed.toRealPath())) return stored;
        }
        throw new FileNotFoundException("文档文件不存在");
    }
    public byte[] readBytes(KbDocument doc) throws IOException {
        Path file = resolveFile(doc);
        if (Files.size(file) > FolderPathService.MAX_FILE_SIZE) throw new IOException("文档超过100MB");
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) { bytes = in.readNBytes((int)FolderPathService.MAX_FILE_SIZE + 1); }
        if (bytes.length > FolderPathService.MAX_FILE_SIZE) throw new IOException("文档超过100MB");
        if ("FOLDER".equals(doc.getSourceKind()) && !FolderPathService.digest(bytes).equals(doc.getSourceFingerprint())) throw new BizException(409, "绑定源文件已变化，请同步文件夹后重试");
        return bytes;
    }
    public Path previewFile(KbDocument doc) throws IOException {
        Path file = resolveFile(doc);
        if ("FOLDER".equals(doc.getSourceKind()) && !FolderPathService.fingerprint(file).equals(doc.getSourceFingerprint())) throw new BizException(409, "源文件已修改，请先同步文件夹再预览");
        return file;
    }
    public void deleteCopy(KbDocument doc) throws IOException {
        if ("FOLDER".equals(doc.getSourceKind())) return;
        if (doc.getId() == null || !doc.getId().matches("[a-zA-Z0-9_-]+")) throw new BizException(400, "文档ID无效");
        for (Path allowed : new Path[]{base(), Path.of("uploads/knowledge").toAbsolutePath().normalize()}) {
            Path directory = allowed.resolve(doc.getId()).normalize();
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            if (Files.isSymbolicLink(directory) || !directory.toRealPath().startsWith(allowed.toRealPath())) continue;
            try (var entries = Files.walk(directory)) {
                for (Path p : entries.sorted(Comparator.reverseOrder()).toList()) {
                    if (!p.toAbsolutePath().normalize().startsWith(directory)) throw new BizException(403, "删除路径无效");
                    Files.deleteIfExists(p);
                }
            }
        }
    }
}
