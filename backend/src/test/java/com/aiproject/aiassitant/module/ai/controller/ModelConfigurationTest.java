package com.aiproject.aiassitant.module.ai.controller;

import com.aiproject.aiassitant.ai.config.EmbeddingModelConfig;
import com.aiproject.aiassitant.module.admin.config.ConfigLoader;
import com.aiproject.aiassitant.module.ai.entity.AiModelConfig;
import com.aiproject.aiassitant.module.ai.mapper.AiModelConfigMapper;
import com.aiproject.aiassitant.module.ai.service.ChatModelFactory;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ModelConfigurationTest {
    @Test void administratorsCanRecoverDisabledConfigurationsWithoutReceivingKeys() throws Exception {
        var mapper = mock(AiModelConfigMapper.class);
        var disabled = new AiModelConfig();
        disabled.setId("disabled"); disabled.setEnabled(false); disabled.setApiKeyAlias("sk-unit-test-1");
        when(mapper.selectList(any())).thenReturn(List.of(disabled));
        var controller = new ModelController(mock(ChatModelFactory.class), mapper);
        var rows = controller.listAdminModels().getData();
        assertEquals(1, rows.size());
        assertFalse(rows.get(0).getEnabled());
        assertTrue(rows.get(0).getApiKeyAlias().contains("****"));
        assertNotEquals("sk-unit-test-1", rows.get(0).getApiKeyAlias());
        assertEquals("hasRole('ADMIN')", ModelController.class.getMethod("listAdminModels").getAnnotation(PreAuthorize.class).value());
    }

    @Test void embeddingConfigurationCanReadAnEncryptedKeySavedFromTheAdminForm() {
        Object previousKey = ReflectionTestUtils.getField(ConfigLoader.class, "aesKey");
        try {
            ReflectionTestUtils.setField(ConfigLoader.class, "aesKey", "only-for-configuration-test-key");
            var config = new AiModelConfig();
            config.setApiKeyAlias(ConfigLoader.encrypt("sk-unit-test-0"));
            config.setApiKeyEncrypted(true);
            assertEquals("sk-unit-test-0", ReflectionTestUtils.invokeMethod(new EmbeddingModelConfig(), "resolveApiKey", config));
            config.setApiKeyAlias(ConfigLoader.encrypt("synthetic-key-without-prefix"));
            assertEquals("synthetic-key-without-prefix", ReflectionTestUtils.invokeMethod(new EmbeddingModelConfig(), "resolveApiKey", config));
        } finally { ReflectionTestUtils.setField(ConfigLoader.class, "aesKey", previousKey); }
    }
}
