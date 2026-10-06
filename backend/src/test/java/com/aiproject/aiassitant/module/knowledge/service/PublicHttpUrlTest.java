package com.aiproject.aiassitant.module.knowledge.service;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicHttpUrlTest {
    @Test void blocksInternalAndNonHttpTargets() {
        for (String url : List.of(
                "http://127.0.0.1:8740/api/models",
                "http://10.0.0.4/", "http://169.254.169.254/",
                "http://100.100.100.200/", "http://[::1]/",
                "http://[fc00::1]/", "file:///C:/private",
                "http://user:pass@8.8.8.8/")) {
            assertThrows(IOException.class, () -> PublicHttpUrl.validate(url), url);
        }
    }
}
