package com.aiproject.aiassitant.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Replace the historical seeded administrator on fresh Docker installs, once only. */
@Component
@Profile("docker")
@DependsOn("flywayInitializer")
public class DockerBootstrap implements InitializingBean {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;
    private final String password;

    @Autowired
    public DockerBootstrap(@Qualifier("dataSource") javax.sql.DataSource dataSource, PasswordEncoder encoder, PlatformTransactionManager manager,
                           @Value("${BOOTSTRAP_ADMIN_PASSWORD:}") String password,
                           @Value("${jwt.secret}") String jwt,
                           @Value("${ai.config.encryption.key}") String encryption) {
        this(new JdbcTemplate(dataSource), encoder, manager, password, jwt, encryption);
    }

    DockerBootstrap(JdbcTemplate jdbc, PasswordEncoder encoder, PlatformTransactionManager manager,
                    String password, String jwt, String encryption) {
        requireSecret(password, "BOOTSTRAP_ADMIN_PASSWORD");
        requireSecret(jwt, "JWT_SECRET");
        requireSecret(encryption, "ENCRYPTION_KEY");
        this.jdbc = jdbc; this.encoder = encoder; this.tx = new TransactionTemplate(manager); this.password = password;
    }

    static void requireSecret(String value, String name) {
        if (value == null || value.length() < 24 || value.contains("CHANGE_ME"))
            throw new IllegalStateException(name + " must contain at least 24 characters; run deployment/init-env.py");
    }

    @Override public void afterPropertiesSet() {
        tx.executeWithoutResult(status -> {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_config WHERE config_key='bootstrap.docker.completed'", Integer.class) > 0) return;
            // This bootstrap is for empty databases, never a password reset on an existing installation.
            if (jdbc.queryForObject("SELECT COUNT(*) FROM chat_session", Integer.class) > 0
                    || jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username<>'admin'", Integer.class) > 0)
                throw new IllegalStateException("Docker bootstrap requires a fresh database; use a separate deployment and volumes");
            int changed = jdbc.update("UPDATE sys_user SET password_hash=?, updated_at=NOW() WHERE username='admin'", encoder.encode(password));
            if (changed != 1) throw new IllegalStateException("Expected exactly one seeded administrator");
            jdbc.update("INSERT INTO sys_config (id,config_key,config_value,description,created_at,updated_at) VALUES (?, 'bootstrap.docker.completed','1','Fresh Docker administrator initialized',NOW(),NOW())",
                    java.util.UUID.randomUUID().toString().replace("-", ""));
        });
    }
}
