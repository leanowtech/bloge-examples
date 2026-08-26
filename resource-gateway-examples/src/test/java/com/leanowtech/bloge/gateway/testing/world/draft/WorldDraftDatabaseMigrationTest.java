package com.leanowtech.bloge.gateway.testing.world.draft;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WorldDraftDatabaseMigrationTest {
    private static final String MIGRATION = "db/postgresql/V20260827_001__world_draft_candidates.sql";

    @Test
    void productionMigrationUsesPostgresPayloadTypesAndRetentionIndexes() throws IOException {
        String sql = new String(new ClassPathResource(MIGRATION).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(sql).doesNotContainIgnoringCase("CLOB");
        assertThat(sql).contains("rg_world_draft_candidates", "rg_world_draft_redacted_payloads",
                "rg_world_draft_audit", "rg_world_draft_assets", "rg_world_draft_authority_receipts",
                "JSONB", "TEXT", "expires_at",
                "rg_world_draft_redacted_payloads_expiry_idx",
                "rg_world_draft_redacted_payloads_retention_idx", "retention_status", "PINNED",
                "published_world_fingerprint", "published_rule_fingerprint", "publication_receipt_fingerprint",
                "PRIMARY KEY (tenant_id, candidate_id)");
        assertThat(sql).contains("PRIMARY KEY", "GENERATED ALWAYS AS IDENTITY");
    }

    @Test
    void testSchemaIsASeparateH2Artifact() throws IOException {
        String sql = new String(new ClassPathResource(
                "db/h2/V20260827_001__world_draft_candidates.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(sql).contains("CREATE TABLE rg_world_draft_candidates",
                "CREATE TABLE rg_world_draft_redacted_payloads", "CREATE TABLE rg_world_draft_audit",
                "CREATE TABLE rg_world_draft_assets", "CREATE TABLE rg_world_draft_authority_receipts");
        assertThat(sql).contains("protected_payload TEXT", "payload_commitment", "retention_status",
                "published_world_fingerprint", "published_rule_fingerprint", "publication_receipt_fingerprint");
        assertThat(sql).doesNotContain("payload_json");
    }
}
