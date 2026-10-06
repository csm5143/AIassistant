package com.aiproject.aiassitant.module.admin.config;

import com.aiproject.aiassitant.module.admin.entity.SysConfig;
import com.aiproject.aiassitant.module.admin.mapper.SysConfigMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Value;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Loads configs from sys_config table on startup into ConfigHolder.
 * Encrypted values (is_encrypted=1) are AES-decrypted before caching.
 * AES key must be at least 16 bytes; set via ENCRYPTION_KEY environment variable.
 */
@Slf4j
@Component
public class ConfigLoader {

    private final SysConfigMapper configMapper;
    private final ConfigHolder configHolder;

    /** Configured independently or derived from JWT_SECRET for local development. */
    private static String aesKey = "";
    private static String legacyKey = loadLegacyKey();

    private static String loadLegacyKey() {
        String configured = System.getenv("LEGACY_ENCRYPTION_KEY");
        if (configured != null && !configured.isBlank()) return configured;
        // An ignored local file preserves existing installations; containers ship no such file.
        try {
            var path = java.nio.file.Path.of("config", "legacy-encryption.key");
            return java.nio.file.Files.isRegularFile(path) ? java.nio.file.Files.readString(path).strip() : "";
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read legacy encryption key file", e);
        }
    }

    public ConfigLoader(SysConfigMapper configMapper, ConfigHolder configHolder,
                        @Value("${ai.config.encryption.key:}") String key) {
        this.configMapper = configMapper;
        this.configHolder = configHolder;
        if (key != null && !key.isBlank()) {
            aesKey = key;
            log.info("Encryption key loaded from config");
        } else {
            log.warn("Encryption key not set; encrypted configuration values cannot be used");
        }
    }

    @PostConstruct
    public void load() {
        List<SysConfig> rows = configMapper.selectList(null);
        Map<String, String> map = new LinkedHashMap<>();

        for (SysConfig row : rows) {
            String val = row.getConfigValue();
            if (val != null) {
                map.put(row.getConfigKey(), val);
            }
        }

        configHolder.refresh(map);
        log.info("Loaded {} configs from database", map.size());
    }

    /** Reload all configs from DB (called after admin updates). */
    public void reload() {
        load();
    }

    /** Encrypt a value for storage. */
    public static String encrypt(String plainText) {
        try {
            SecretKeySpec key = currentKey();
            byte[] nonce = new byte[12];
            new java.security.SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(encrypted, 0, payload, nonce.length, encrypted.length);
            return "v2:" + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /** Decrypt a stored value. */
    public static String decrypt(String encrypted) {
        try {
            if (encrypted.startsWith("v2:")) {
                byte[] payload = Base64.getDecoder().decode(encrypted.substring(3));
                if (payload.length < 29) throw new IllegalArgumentException("Invalid encrypted value");
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, currentKey(), new GCMParameterSpec(128, Arrays.copyOf(payload, 12)));
                return new String(cipher.doFinal(payload, 12, payload.length - 12), StandardCharsets.UTF_8);
            }
            try { return decryptLegacy(encrypted, aesKey); }
            catch (Exception e) {
                if (legacyKey == null || legacyKey.isBlank()) throw e;
                return decryptLegacy(encrypted, legacyKey);
            }
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    private static SecretKeySpec currentKey() throws Exception {
        if (aesKey == null || aesKey.length() < 16) throw new IllegalStateException("ENCRYPTION_KEY or JWT_SECRET must contain at least 16 characters");
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(aesKey.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }

    private static String decryptLegacy(String encrypted, String secret) throws Exception {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(bytes.length >= 16 ? Arrays.copyOf(bytes, 16) : padKey(secret), "AES"));
        return new String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), StandardCharsets.UTF_8);
    }

    private static byte[] padKey(String key) {
        byte[] padded = new byte[16];
        byte[] src = key.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(src, 0, padded, 0, Math.min(src.length, 16));
        return padded;
    }
}
