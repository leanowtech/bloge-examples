package com.leanowtech.bloge.gateway.visualadapter.authoring.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;

/**
 * Installs the versioned authoring schema for repository launchers using local H2.
 *
 * <p>This bootstrap is separately opt-in and deliberately ignores non-H2 data sources;
 * deployed PostgreSQL environments retain the external, audited migration boundary.
 * Applied checksums make repeated local starts idempotent and reject changed history.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.authoring.local-schema-bootstrap",
        name = "enabled", havingValue = "true")
public class LocalAuthoringSchemaBootstrapConfiguration {

    private static final List<String> MIGRATIONS = List.of(
            "V20260830_001__api_resource_authoring.sql",
            "V20260830_002__api_resource_concurrent_staging.sql",
            "V20260830_003__api_connection_secret_staging.sql",
            "V20260830_004__connection_metadata_authority.sql",
            "V20260830_005__pending_secret_store_protocol.sql",
            "V20260830_006__pending_secret_store_hardening.sql",
            "V20260831_007__pending_secret_store_protocol_closure.sql",
            "V20260831_008__pending_secret_store_child_cas_closure.sql",
            "V20260831_009__authoring_command_attempt_authority.sql",
            "V20260831_010__attempt_provenance_closure.sql",
            "V20260831_011__api_resource_connection_snapshot.sql",
            "V20260831_012__api_fixture_set_authority.sql",
            "V20260831_013__authoring_simulation_runs.sql",
            "V20260901_014__reusable_flow_drafts.sql",
            "V20260901_015__reusable_flow_publications.sql",
            "V20260901_016__standalone_flow_fixture_sets.sql",
            "V20260901_017__fixture_share_requests.sql",
            "V20260901_018__fixture_review_completion.sql");

    private static final String MIGRATION_ROOT = "db/postgresql/";

    /** Runs before the authoring readiness beans are instantiated. */
    @Bean
    static BeanFactoryPostProcessor localAuthoringSchemaBootstrap() {
        return beanFactory -> migrate(beanFactory.getBean(JdbcTemplate.class));
    }

    static void migrate(JdbcTemplate jdbc) {
        if (!isH2(jdbc)) {
            return;
        }
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_local_authoring_schema_migrations (
                    version VARCHAR(64) PRIMARY KEY,
                    checksum VARCHAR(71) NOT NULL,
                    applied_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        for (String migration : MIGRATIONS) apply(jdbc, migration);
    }

    private static boolean isH2(JdbcTemplate jdbc) {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            return "H2".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot inspect the local authoring database", exception);
        }
    }

    private static void apply(JdbcTemplate jdbc, String migration) {
        ClassPathResource resource = new ClassPathResource(MIGRATION_ROOT + migration);
        String version = migration.substring(0, migration.indexOf("__"));
        String checksum = checksum(resource);
        List<String> applied = jdbc.queryForList(
                "SELECT checksum FROM rg_local_authoring_schema_migrations WHERE version=?",
                String.class, version);
        if (!applied.isEmpty()) {
            if (!applied.getFirst().equals(checksum)) {
                throw new IllegalStateException("Authoring migration " + version
                        + " checksum does not match the applied local schema");
            }
            return;
        }
        new ResourceDatabasePopulator(resource).execute(jdbc.getDataSource());
        jdbc.update("INSERT INTO rg_local_authoring_schema_migrations(version, checksum) VALUES (?, ?)",
                version, checksum);
    }

    private static String checksum(ClassPathResource resource) {
        try (InputStream input = resource.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(input.readAllBytes()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot fingerprint " + resource.getPath(), exception);
        }
    }
}
