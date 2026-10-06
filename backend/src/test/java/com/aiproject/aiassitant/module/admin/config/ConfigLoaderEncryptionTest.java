package com.aiproject.aiassitant.module.admin.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderEncryptionTest {
    @Test void authenticatedEncryptionDetectsTamperingAndReadsLegacyValue() throws Exception {
        ReflectionTestUtils.setField(ConfigLoader.class, "aesKey", "fresh-local-secret-with-more-than-16-characters");
        String ciphertext = ConfigLoader.encrypt("synthetic-key");
        assertTrue(ciphertext.startsWith("v2:"));
        assertEquals("synthetic-key", ConfigLoader.decrypt(ciphertext));

        byte[] payload = Base64.getDecoder().decode(ciphertext.substring(3));
        payload[payload.length - 1] ^= 1;
        assertThrows(RuntimeException.class,
                () -> ConfigLoader.decrypt("v2:" + Base64.getEncoder().encodeToString(payload)));

        ReflectionTestUtils.setField(ConfigLoader.class, "legacyKey", "test-only-legacy-key-not-for-deployment");
        byte[] legacy = Arrays.copyOf("test-only-legacy-key-not-for-deployment".getBytes(StandardCharsets.UTF_8), 16);
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(legacy, "AES"));
        String oldValue = Base64.getEncoder().encodeToString(cipher.doFinal("old-test-key".getBytes(StandardCharsets.UTF_8)));
        assertEquals("old-test-key", ConfigLoader.decrypt(oldValue));
    }
}
