package com.aiproject.aiassitant.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

/**
 * Separate PostgreSQL DataSource for PGVector operations.
 * NOT exposed as a primary DataSource bean — only JdbcTemplate is public,
 * so Flyway + MyBatis-Plus keep using the MySQL primary datasource.
 */
@Configuration
public class PgVectorDatasourceConfig {

    @Value("${spring.datasource-pg.url}")
    private String url;

    @Value("${spring.datasource-pg.username}")
    private String username;

    @Value("${spring.datasource-pg.password}")
    private String password;

    @Value("${spring.datasource-pg.driver-class-name}")
    private String driverClassName;

    private volatile HikariDataSource pgDs;

    private DataSource pgDataSource() {
        if (pgDs == null) {
            synchronized (this) {
                if (pgDs == null) {
                    HikariDataSource ds = new HikariDataSource();
                    ds.setJdbcUrl(url);
                    ds.setUsername(username);
                    ds.setPassword(password);
                    ds.setDriverClassName(driverClassName);
                    ds.setMaximumPoolSize(5);
                    ds.setMinimumIdle(1);
                    ds.setConnectionTimeout(10000);
                    ds.setPoolName("PgVectorPool");
                    this.pgDs = ds;
                }
            }
        }
        return pgDs;
    }

    @Bean("pgJdbcTemplate")
    public JdbcTemplate pgJdbcTemplate() {
        return new JdbcTemplate(pgDataSource());
    }
}
