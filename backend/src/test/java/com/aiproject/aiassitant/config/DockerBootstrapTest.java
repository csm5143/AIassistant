package com.aiproject.aiassitant.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DockerBootstrapTest {
    private static final String SECRET = "test-only-secret-long-enough-for-test";
    @Test void rejectsMissingAndPlaceholderSecrets() {
        assertThrows(IllegalStateException.class, () -> DockerBootstrap.requireSecret("", "TEST"));
        assertThrows(IllegalStateException.class, () -> DockerBootstrap.requireSecret("CHANGE_ME_CHANGE_ME_CHANGE_ME", "TEST"));
        assertDoesNotThrow(() -> DockerBootstrap.requireSecret(SECRET, "TEST"));
    }
    @Test void restartDoesNotResetAdministratorPassword() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(jdbc.queryForObject(contains("bootstrap.docker.completed"), eq(Integer.class))).thenReturn(1);
        var encoder = mock(PasswordEncoder.class);
        new DockerBootstrap(jdbc, encoder, manager, SECRET, SECRET, SECRET).afterPropertiesSet();
        verifyNoInteractions(encoder);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
