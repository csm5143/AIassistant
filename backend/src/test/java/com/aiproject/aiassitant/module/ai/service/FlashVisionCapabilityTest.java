package com.aiproject.aiassitant.module.ai.service;
import com.aiproject.aiassitant.module.ai.entity.AiModelConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FlashVisionCapabilityTest {
    @Test void recognizesOfficialFlashAliasesAndExcludesUnverifiedModels() {
        var config=new AiModelConfig();config.setBaseUrl("https://api.deepseek.com/v1");
        for(String model: new String[]{"deepseek-flash","deepseek-chat","deepseek-v4-flash","deepseek-v4-flash-vision-exp"}) {
            config.setModelName(model);assertTrue(ApiManager.supportsFlashVision(config));
        }
        config.setModelName("deepseek-reasoner");assertFalse(ApiManager.supportsFlashVision(config));
        config.setBaseUrl("https://api.deepseek.com.attacker.invalid/v1");config.setModelName("deepseek-flash");
        assertFalse(ApiManager.supportsFlashVision(config));
    }
}
