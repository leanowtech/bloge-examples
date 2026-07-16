package com.leanowtech.bloge.gateway.testing.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Objects;

/**
 * Owns the test-runtime datasource without publishing it as a Spring {@code DataSource} bean.
 *
 * <p>The wrapper prevents Boot's production {@code JdbcTemplate} auto-configuration from seeing two
 * candidate datasources. Test runs, fixtures, durable control checkpoints, and test security events
 * therefore have a physically separate pool and database while the rest of Resource Gateway keeps
 * its existing datasource.</p>
 */
public final class TestRuntimeDatabase implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final DataSourceTransactionManager transactionManager;

    public TestRuntimeDatabase(Settings settings) {
        Settings safe = Objects.requireNonNull(settings, "settings");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(safe.jdbcUrl());
        config.setUsername(safe.username());
        config.setPassword(safe.password());
        config.setPoolName("resource-gateway-test-runtime");
        config.setMaximumPoolSize(safe.maximumPoolSize());
        config.setMinimumIdle(0);
        config.setAutoCommit(true);
        this.dataSource = new HikariDataSource(config);
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.transactionManager = new DataSourceTransactionManager(dataSource);
    }

    /** @return JDBC facade backed only by the independent test-runtime pool */
    public JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    /**
     * @return transaction manager bound to the same isolated pool as {@link #jdbc()}
     */
    public PlatformTransactionManager transactionManager() {
        return transactionManager;
    }

    @Override
    public void close() {
        dataSource.close();
    }

    /** Bounded independent datasource settings. */
    public record Settings(String jdbcUrl, String username, String password, int maximumPoolSize) {
        public Settings {
            jdbcUrl = jdbcUrl == null ? "" : jdbcUrl.trim();
            username = username == null ? "" : username;
            password = password == null ? "" : password;
            if (jdbcUrl.isBlank()) {
                throw new IllegalArgumentException("Test-runtime JDBC URL is required");
            }
            maximumPoolSize = Math.max(1, Math.min(16, maximumPoolSize));
        }
    }
}
