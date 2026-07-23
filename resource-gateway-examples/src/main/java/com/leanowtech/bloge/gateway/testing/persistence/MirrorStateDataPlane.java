package com.leanowtech.bloge.gateway.testing.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Owned connection pool for encrypted stateful mirror payloads.
 *
 * <p>This wrapper deliberately does not publish a Spring {@code DataSource}, {@code JdbcTemplate},
 * or transaction-manager bean. Mirror state therefore cannot be selected accidentally by normal
 * Resource Gateway repositories, and the control-plane database cannot be injected accidentally
 * into the state store.</p>
 */
public final class MirrorStateDataPlane implements AutoCloseable {
    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;

    /**
     * Creates one bounded dedicated state data plane.
     *
     * @param jdbcUrl state-plane JDBC endpoint, distinct from the control database
     * @param username state-plane database identity
     * @param password state-plane database credential
     * @param maximumPoolSize bounded connection pool size
     */
    public MirrorStateDataPlane(
            String jdbcUrl,
            String username,
            String password,
            int maximumPoolSize) {
        String url = required(jdbcUrl, "jdbcUrl");
        if (maximumPoolSize < 1 || maximumPoolSize > 32) {
            throw new IllegalArgumentException(
                    "mirror state maximum pool size must be between 1 and 32");
        }
        HikariConfig configuration = new HikariConfig();
        configuration.setPoolName("resource-gateway-mirror-state");
        configuration.setJdbcUrl(url);
        configuration.setUsername(username == null ? "" : username);
        configuration.setPassword(password == null ? "" : password);
        configuration.setMaximumPoolSize(maximumPoolSize);
        configuration.setMinimumIdle(0);
        configuration.setConnectionTimeout(5_000);
        configuration.setValidationTimeout(2_000);
        configuration.setInitializationFailTimeout(5_000);
        this.dataSource = new HikariDataSource(configuration);
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactionManager = new DataSourceTransactionManager(dataSource);
    }

    /** @return transaction-aware JDBC boundary owned by this state plane */
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /** @return transaction manager bound to the same state-plane pool */
    public PlatformTransactionManager transactionManager() {
        return transactionManager;
    }

    /** Closes every state-plane connection without affecting the control database. */
    @Override
    public void close() {
        dataSource.close();
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
