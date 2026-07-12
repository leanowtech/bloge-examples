package com.leanowtech.bloge.gateway.integration;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseIntegrationAccessAuditRepositoryTest {

    @Test
    void persistsCredentialFreeAllowAndDenyFactsAcrossRepositoryRestart() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        DatabaseIntegrationAccessAuditRepository repository = new DatabaseIntegrationAccessAuditRepository(jdbc);
        repository.init();

        repository.append(record("ALLOWED", ""));
        repository.append(record("DENIED", "RG.INTEGRATION.PURPOSE_FORBIDDEN"));

        DatabaseIntegrationAccessAuditRepository restarted = new DatabaseIntegrationAccessAuditRepository(jdbc);
        restarted.init();
        assertThat(restarted.recent(10)).extracting(IntegrationAccessAuditRecord::sequence,
                        IntegrationAccessAuditRecord::outcome, IntegrationAccessAuditRecord::reasonCode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2L, "DENIED", "RG.INTEGRATION.PURPOSE_FORBIDDEN"),
                        org.assertj.core.groups.Tuple.tuple(1L, "ALLOWED", ""));
        assertThat(restarted.recent(10)).allSatisfy(value ->
                assertThat(value.toString()).doesNotContain("secret", "Bearer"));
    }

    private static IntegrationAccessAuditRecord record(String outcome, String reason) {
        return new IntegrationAccessAuditRecord(0, Instant.parse("2026-07-12T00:00:00Z"), "corr-1",
                "aneke", "tenant-a", "prod", "CHANGE_SYNC", "CHANGE_SYNC", outcome, reason);
    }
}
