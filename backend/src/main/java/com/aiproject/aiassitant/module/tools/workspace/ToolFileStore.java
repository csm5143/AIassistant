package com.aiproject.aiassitant.module.tools.workspace;

import com.aiproject.aiassitant.common.BizException;
import com.aiproject.aiassitant.common.SecurityUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Opaque file IDs, immutable bytes, owner-scoped metadata. Never accepts filesystem paths. */
@Service
public class ToolFileStore {
    public static final long MAX_UPLOAD = 100L * 1024 * 1024;
    private final Path root;
    private final ObjectMapper json;
    public record Asset(String id, String sessionId, String name, String mime, long size,
                        String kind, String createdAt) {}
    public ToolFileStore(@Value("${ai.tools.storage-dir:D:/AIassistant-env/runtime/uploads/tools}") String root, ObjectMapper json) {
        this.root = Path.of(root).toAbsolutePath().normalize(); this.json = json;
    }
    private Path ownerDir(String user) throws IOException {
        if (user == null || user.isBlank() || "anonymous".equals(user)) throw BizException.unauthorized("请先登录");
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(user.getBytes(StandardCharsets.UTF_8)));
            Path dir = root.resolve(hash); Files.createDirectories(dir); return dir;
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void id(String value) {
        if (value == null || !value.matches("[a-f0-9]{32}")) throw new BizException(400, "文件编号无效");
    }
    public synchronized Asset upload(String session, MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > MAX_UPLOAD) throw new BizException(413, "文件不能为空，且不能超过 100 MB");
        if (list(session).stream().filter(a -> a.kind().equals("upload")).count() >= 12)
            throw new BizException(400, "每个对话最多保留 12 份工具文件，请移除不需要的文件");
        String name = safeName(file.getOriginalFilename());
        String ext = extension(name);
        if (!Set.of("xlsx","xls","csv","pdf","docx","txt","md").contains(ext)) throw new BizException(400, "支持 XLSX、XLS、CSV、PDF、DOCX、TXT、MD");
        if (Set.of("xlsx","xls","csv").contains(ext) && file.getSize() > 20L * 1024 * 1024)
            throw new BizException(413, "表格文件不能超过 20 MB，请拆分表格后上传");
        byte[] bytes = file.getBytes();
        // Inspect magic, not the untrusted multipart content type.
        if ((ext.equals("pdf") && !new String(bytes, 0, Math.min(bytes.length, 5), StandardCharsets.US_ASCII).equals("%PDF-"))
            || (Set.of("xlsx","docx").contains(ext) && (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K'))
            || (ext.equals("xls") && (bytes.length < 8 || (bytes[0]&255) != 0xD0 || (bytes[1]&255) != 0xCF)))
            throw new BizException(400, "文件内容与扩展名不匹配");
        return save(session, name, mime(ext), bytes, "upload");
    }
    public synchronized Asset save(String session, String name, String mime, byte[] bytes, String kind) throws IOException {
        id(session);
        if (bytes.length > 100L * 1024 * 1024) throw new BizException(413, "生成文件超过 100 MB，请减少处理范围");
        Path dir = ownerDir(SecurityUtil.getCurrentUserId());
        if (list(session).size() >= 180) throw new BizException(400, "本对话成果较多，请使用新对话");
        String key = UUID.randomUUID().toString().replace("-", "");
        Asset asset = new Asset(key, session, safeName(name), mime, bytes.length, kind, Instant.now().toString());
        Path data = dir.resolve(key + ".bin");
        Files.write(data, bytes, StandardOpenOption.CREATE_NEW);
        try { json.writeValue(dir.resolve(key + ".json").toFile(), asset); }
        catch (IOException e) { Files.deleteIfExists(data); throw e; }
        return asset;
    }
    public Asset get(String key, String session) throws IOException {
        id(key); Path metadata = ownerDir(SecurityUtil.getCurrentUserId()).resolve(key + ".json");
        if (!Files.isRegularFile(metadata)) throw BizException.notFound("文件不存在或无权访问");
        Asset asset = json.readValue(metadata.toFile(), Asset.class);
        if (session != null && !session.equals(asset.sessionId())) throw BizException.notFound("文件不属于当前对话");
        return asset;
    }
    public Path path(Asset asset) throws IOException { id(asset.id()); return ownerDir(SecurityUtil.getCurrentUserId()).resolve(asset.id() + ".bin"); }
    public byte[] bytes(Asset asset) throws IOException { return Files.readAllBytes(path(asset)); }
    public List<Asset> list(String session) throws IOException {
        id(session); Path dir = ownerDir(SecurityUtil.getCurrentUserId());
        List<Asset> result = new ArrayList<>();
        try (var paths = Files.newDirectoryStream(dir, "*.json")) {
            for (Path path : paths) { Asset a = json.readValue(path.toFile(), Asset.class); if (session.equals(a.sessionId())) result.add(a); }
        }
        result.sort(Comparator.comparing(Asset::createdAt)); return result;
    }
    public synchronized void delete(String key, String session) throws IOException {
        Asset a = get(key, session); Path dir = ownerDir(SecurityUtil.getCurrentUserId());
        Files.deleteIfExists(dir.resolve(a.id() + ".json")); Files.deleteIfExists(dir.resolve(a.id() + ".bin"));
    }
    public synchronized void deleteSession(String session) throws IOException { for (Asset a : list(session)) delete(a.id(), session); }
    public static String safeName(String name) {
        String s = name == null ? "文件" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (s.isEmpty()) s = "文件"; if (s.length() > 110) s = s.substring(0, 90) + s.substring(Math.max(90, s.lastIndexOf('.'))); return s;
    }
    public static String extension(String name) { int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT); }
    public static String mime(String ext) { return switch (ext) {
        case "pdf" -> "application/pdf"; case "png" -> "image/png";
        case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        case "xls" -> "application/vnd.ms-excel"; case "csv" -> "text/csv; charset=UTF-8";
        default -> "text/plain; charset=UTF-8";
    }; }
}
