package com.aiproject.aiassitant.module.knowledge.service;
import com.aiproject.aiassitant.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

@Service
public class FolderPathService {
    public static final long MAX_FILE_SIZE = 100L * 1024 * 1024;
    public static final Set<String> EXTENSIONS = Set.of("pdf", "txt", "md", "doc", "docx", "xls", "xlsx");
    public static final Set<String> EXCLUDED = Set.of(".git", ".svn", ".idea", ".vscode", "node_modules", "$recycle.bin", "system volume information");
    @Value("${ai.knowledge.local-folders-enabled:false}") private boolean enabled;
    // Closed by default; only explicitly authorized document roots may be configured.
    @Value("${ai.knowledge.folder-roots:}") private String allowedRoots;
    @Value("${ai.knowledge.storage-dir:D:/AIassistant-env/runtime/uploads/knowledge}") private String storageDir;
    public record DirectoryEntry(String name, String path) {}
    public record DirectoryListing(boolean enabled, String path, String parent, List<DirectoryEntry> roots, List<DirectoryEntry> directories, String storagePath) {}
    public boolean isEnabled() { return enabled && !roots().isEmpty(); }
    public Path storageBase() { return Path.of(storageDir).toAbsolutePath().normalize(); }
    public void requireEnabled() {
        if (!isEnabled()) throw new BizException(403, "请先配置允许绑定的资料目录");
    }
    public List<Path> roots() {
        List<Path> roots = new ArrayList<>();
        for (String value : allowedRoots.split(";")) {
            if (value.isBlank()) continue;
            try { Path p = Path.of(value.trim()).toRealPath(); if (Files.isDirectory(p) && p.getParent() != null) roots.add(p); }
            catch (Exception ignored) { }
        }
        return roots;
    }
    public Path validateDirectory(String raw, boolean binding) {
        requireEnabled();
        if (raw == null || raw.isBlank() || raw.length() > 1024) throw new BizException(400, "请选择有效文件夹");
        try {
            Path input = Path.of(raw);
            if (!input.isAbsolute() || input.toString().startsWith("\\\\")) throw new BizException(400, "请使用本机资料文件夹的绝对路径");
            Path real = input.toRealPath();
            if (!Files.isDirectory(real) || !Files.isReadable(real)) throw new BizException(400, "文件夹不存在或不可读取");
            if (roots().stream().noneMatch(real::startsWith)) throw new BizException(403, "该文件夹不在已允许的资料目录内");
            if (binding) {
                Path storage = storageBase();
                if (Files.exists(storage)) storage = storage.toRealPath();
                if (real.startsWith(storage) || storage.startsWith(real)) throw new BizException(400, "资料文件夹不能包含知识库默认存储目录");
                if (EXCLUDED.contains(real.getFileName() == null ? "" : real.getFileName().toString().toLowerCase(Locale.ROOT))) throw new BizException(400, "请选择资料文件夹");
            }
            return real;
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException(400, "文件夹不存在或不可读取"); }
    }
    public DirectoryListing browse(String raw) {
        boolean available = isEnabled();
        List<DirectoryEntry> rootEntries = available ? roots().stream().map(p -> new DirectoryEntry(p.getFileName().toString(), p.toString())).toList() : List.of();
        if (raw == null || raw.isBlank()) return new DirectoryListing(available, null, null, rootEntries, List.of(), storageBase().toString());
        Path real = validateDirectory(raw, false); List<DirectoryEntry> dirs = new ArrayList<>();
        try (var entries = Files.list(real)) {
            for (Path p : entries.sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))).toList()) {
                if (EXCLUDED.contains(p.getFileName().toString().toLowerCase(Locale.ROOT))) continue;
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(p)) {
                    try { Path target = p.toRealPath(); if (target.startsWith(real) && roots().stream().anyMatch(target::startsWith)) dirs.add(new DirectoryEntry(p.getFileName().toString(), target.toString())); }
                    catch (IOException ignored) { }
                }
            }
        } catch (IOException e) { throw new BizException(400, "无法读取此文件夹"); }
        Path parent = real.getParent();
        String parentPath = parent != null && roots().stream().anyMatch(parent::startsWith) ? parent.toString() : null;
        return new DirectoryListing(true, real.toString(), parentPath, rootEntries, dirs, storageBase().toString());
    }
    public Path resolveSource(String folder, String relative, String stored) throws IOException {
        Path root = validateDirectory(folder, true); String safe = validateRelativePath(relative);
        Path expected = root.resolve(safe).normalize();
        if (!expected.startsWith(root) || !expected.toAbsolutePath().normalize().equals(Path.of(stored).toAbsolutePath().normalize())) throw new BizException(403, "源文件路径无效");
        Path real = expected.toRealPath();
        if (!real.startsWith(root) || !Files.isRegularFile(real) || Files.size(real) > MAX_FILE_SIZE) throw new BizException(403, "源文件不可读取或超出大小限制");
        return real;
    }
    public static boolean supported(Path p) {
        String name = p.getFileName().toString(); int dot = name.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
    public static String validateRelativePath(String relative) {
        if (relative == null || relative.isBlank() || relative.length() > 1024 || relative.indexOf('\0') >= 0) throw new BizException(400, "文件相对路径无效");
        String value = relative.replace('\\', '/');
        if (value.startsWith("/") || value.contains(":")) throw new BizException(400, "文件相对路径无效");
        for (String part : value.split("/", -1)) if (part.isBlank() || part.equals(".") || part.equals("..")) throw new BizException(400, "文件相对路径无效");
        return value;
    }
    public static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String fingerprint(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536]; int n; long total = 0;
                while ((n = in.read(buffer)) != -1) { total += n; if (total > MAX_FILE_SIZE) throw new IOException("文件超过100MB"); digest.update(buffer, 0, n); }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
