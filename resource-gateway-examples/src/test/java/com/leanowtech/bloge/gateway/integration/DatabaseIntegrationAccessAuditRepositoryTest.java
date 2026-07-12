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

        repository.append(record("token-1", "ALLOWED", ""));
        repository.append(record("token-2", "DENIED", "RG.INTEGRATION.PURPOSE_FORBIDDEN"));

        DatabaseIntegrationAccessAuditRepository restarted = new DatabaseIntegrationAccessAuditRepository(jdbc);
        restarted.init();
        assertThat(restarted.recent(10)).extracting(IntegrationAccessAuditRecord::sequence,
                        IntegrationAccessAuditRecord::credentialId, IntegrationAccessAuditRecord::tokenId,
                        IntegrationAccessAuditRecord::outcome, IntegrationAccessAuditRecord::reasonCode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2L, "key-2026-07", "token-2", "DENIED",
                                "RG.INTEGRATION.PURPOSE_FORBIDDEN"),
                        org.assertj.core.groups.Tuple.tuple(1L, "key-2026-07", "token-1", "ALLOWED", ""));
        assertThat(restarted.recent(10)).allSatisfy(value ->
                assertThat(value.toString()).doesNotContain("secret", "Bearer"));
    }

    private static IntegrationAccessAuditRecord record(String tokenId, String outcome, String reason) {
        return new IntegrationAccessAuditRecord(0, Instant.parse("2026-07-12T00:00:00Z"), "corr-1",
                "aneke", "key-2026-07", tokenId, "tenant-a", "prod", "CHANGE_SYNC", "CHANGE_SYNC",
                outcome, reason);
    }
}
