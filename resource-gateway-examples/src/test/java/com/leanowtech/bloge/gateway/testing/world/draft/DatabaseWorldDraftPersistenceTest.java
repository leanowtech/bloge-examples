package com.leanowtech.bloge.gateway.testing.world.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseWorldDraftPersistenceTest {
    private JdbcTemplate jdbc;
    private EmbeddedDatabase database;
    private Clock clock;
    private WorldDraftCandidateRepository candidates;
    private DatabaseWorldDraftRedactedPayloadVault vault;
    private WorldDraftCandidateService.Access access;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("world-draft-" + System.nanoTime()).build();
        jdbc = new JdbcTemplate(database);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/h2/V20260827_001__world_draft_candidates.sql")).execute(database);
        clock = Clock.fixed(WorldDraftTestSupport.NOW, ZoneOffset.UTC);
        DatabaseWorldDraftAuditSink audit = new DatabaseWorldDraftAuditSink(jdbc);
        DatabaseWorldDraftCandidateRepository candidateRepository =
                new DatabaseWorldDraftCandidateRepository(jdbc, new ObjectMapper(), audit);
        candidates = candidateRepository;
        DatabaseWorldDraftRedactedPayloadVault payloadVault = new DatabaseWorldDraftRedactedPayloadVault(
                jdbc, new ObjectMapper(), clock, Duration.ofDays(1), audit,
                WorldDraftPayloadProtector.fromConfiguration("test-v1",
                        "test-v1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="));
        vault = payloadVault;
        access = WorldDraftTestSupport.ACCESS;
    }

    @Test
    void candidateRoundTripsAndCasIsAtomic() {
        WorldDraftCandidate candidate = candidate("db-candidate", 1, WorldDraftState.CAPTURED);
        candidates.create(candidate);
        WorldDraftCandidate sameIdOtherTenant = candidate("tenant-b", "db-candidate", 1, WorldDraftState.CAPTURED);
        candidates.create(sameIdOtherTenant);
        assertThat(candidates.find(candidate.tenantId(), candidate.candidateId())).contains(candidate);
        assertThat(candidates.find("tenant-b", candidate.candidateId())).contains(sameIdOtherTenant);
        WorldDraftCandidate next = candidate.next(WorldDraftState.REDACTION_REQUIRED, "", "", null,
                candidate.redactionReportFingerprint(), candidate.redactionReport());
        assertThat(candidates.compareAndSet(candidate, next)).isTrue();
        assertThat(candidates.compareAndSet(candidate, next)).isFalse();
    }

    @Test
    void vaultRoundTripsRedactedValuesButRejectsForeignTenantBeforeRead() {
        WorldDraftRedactedPayload payload = new WorldDraftRedactedPayload(
                Map.of("safe", "A"), Map.of("result", "response-A"));
        WorldDraftRedactedPayloadRef ref = WorldDraftRedactedPayloadRef.of(
                access.tenantId(), "db-payload", 1, payload);
        vault.put(ref, payload, access);
        String protectedPayload = jdbc.queryForObject("SELECT protected_payload FROM rg_world_draft_redacted_payloads "
                + "WHERE tenant_id=? AND candidate_id=?", String.class, ref.tenantId(), ref.candidateId());
        assertThat(protectedPayload).doesNotContain("response-A", "safe");
        WorldDraftRedactedPayloadVault.StoredPayload restored = vault.read(ref, access).orElseThrow();
        assertThat(restored.payload().requestFingerprint()).isEqualTo(payload.requestFingerprint());
        assertThat(restored.payload().responseFingerprint()).isEqualTo(payload.responseFingerprint());
        WorldDraftCandidateService.Access foreign = new WorldDraftCandidateService.Access(
                "tenant-b", WorldDraftCandidateService.PURPOSE, "reviewer", "foreign");
        assertThatThrownBy(() -> vault.read(ref, foreign))
                .isInstanceOfSatisfying(WorldDraftCandidateException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED));
    }

    @Test
    void expiredPayloadIsRejectedBeforePayloadDeserialization() {
        WorldDraftRedactedPayload payload = new WorldDraftRedactedPayload(
                Map.of("safe", "A"), Map.of("result", "response-A"));
        WorldDraftRedactedPayloadRef ref = WorldDraftRedactedPayloadRef.of(
                access.tenantId(), "expired", 1, payload);
        jdbc.update("INSERT INTO rg_world_draft_redacted_payloads "
                        + "(tenant_id,candidate_id,artifact_revision,request_fingerprint,response_fingerprint,pair_fingerprint,"
                        + "protected_payload,payload_key_id,payload_commitment,expires_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)", ref.tenantId(), ref.candidateId(), ref.artifactRevision(),
                ref.requestFingerprint(), ref.responseFingerprint(), ref.pairFingerprint(), "not-an-envelope", "test-v1",
                WorldDraftTestSupport.fp("expired-commitment"),
                java.sql.Timestamp.from(clock.instant().minusSeconds(1)));

        assertThat(vault.read(ref, access)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_world_draft_audit WHERE operation='VAULT_READ' "
                + "AND candidate_id=? AND success=FALSE", Integer.class, ref.candidateId())).isEqualTo(1);
    }

    @Test
    void purgeExpiredUsesRetentionBoundaryAndWritesPayloadFreeAudit() {
        WorldDraftRedactedPayload payload = new WorldDraftRedactedPayload(
                Map.of("safe", "A"), Map.of("result", "response-A"));
        WorldDraftRedactedPayloadRef expired = WorldDraftRedactedPayloadRef.of(
                access.tenantId(), "expired-retention", 1, payload);
        WorldDraftRedactedPayloadRef live = WorldDraftRedactedPayloadRef.of(
                access.tenantId(), "live-retention", 1, payload);
        vault.put(expired, payload, access);
        vault.put(live, payload, access);
        jdbc.update("UPDATE rg_world_draft_redacted_payloads SET expires_at=? WHERE candidate_id=?",
                java.sql.Timestamp.from(clock.instant().minusSeconds(1)), expired.candidateId());

        assertThat(vault.purgeExpired()).isEqualTo(1);
        assertThat(vault.read(expired, access)).isEmpty();
        assertThat(vault.read(live, access)).isPresent();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_world_draft_audit "
                + "WHERE operation='VAULT_PURGE' AND candidate_id='*'", Integer.class)).isEqualTo(1);
    }

    @Test
    void pinnedBehaviorSurvivesExpiryPurgeAndExplicitRevocation() {
        WorldDraftRedactedPayload payload = new WorldDraftRedactedPayload(
                Map.of("safe", "A"), Map.of("result", "response-A"));
        WorldDraftRedactedPayloadRef ref = WorldDraftRedactedPayloadRef.of(
                access.tenantId(), "pinned", 1, payload);
        vault.put(ref, payload, access);
        WorldDraftRedactedPayloadVault.PublishedBinding binding =
                new WorldDraftRedactedPayloadVault.PublishedBinding(access.tenantId(), ref.candidateId(),
                        ref.artifactRevision(), WorldDraftTestSupport.fp("published-world"),
                        WorldDraftTestSupport.fp("published-rule"), WorldDraftTestSupport.fp("publication"));
        vault.pin(ref, binding, access);
        jdbc.update("UPDATE rg_world_draft_redacted_payloads SET expires_at=? WHERE candidate_id=?",
                java.sql.Timestamp.from(clock.instant().minusSeconds(1)), ref.candidateId());

        assertThat(vault.purgeExpired()).isZero();
        assertThat(vault.readPublished(ref, binding, access)).isPresent();
        vault.revoke(ref, access);
        assertThat(vault.readPublished(ref, binding, access)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT retention_status FROM rg_world_draft_redacted_payloads "
                + "WHERE candidate_id=?", String.class, ref.candidateId())).isEqualTo("REVOKED");
    }

    @Test
    void payloadCommitmentTamperingFailsAfterAuthenticatedDecrypt() {
        WorldDraftRedactedPayload payload = new WorldDraftRedactedPayload(
                Map.of("safe", "A"), Map.of("result", "response-A"));
        WorldDraftRedactedPayloadRef ref = WorldDraftRedactedPayloadRef.of(
                access.tenantId(), "tampered-commitment", 1, payload);
        vault.put(ref, payload, access);
        jdbc.update("UPDATE rg_world_draft_redacted_payloads SET payload_commitment=? WHERE candidate_id=?",
                WorldDraftTestSupport.fp("wrong-commitment"), ref.candidateId());

        assertThatThrownBy(() -> vault.read(ref, access))
                .isInstanceOfSatisfying(WorldDraftCandidateException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldDraftCandidateException.Code.SOURCE_INTEGRITY));
    }

    private static WorldDraftCandidate candidate(String id, long revision, WorldDraftState state) {
        return candidate(WorldDraftTestSupport.TENANT, id, revision, state);
    }

    private static WorldDraftCandidate candidate(String tenant, String id, long revision, WorldDraftState state) {
        WorldDraftSourceRef source = WorldDraftTestSupport.source(WorldDraftSourceRef.Kind.GOLDEN_CAPTURE,
                tenant, id);
        return new WorldDraftCandidate(id, revision, state, tenant, source,
                WorldDraftTestSupport.fp("metadata-" + id), WorldDraftTestSupport.fp("schema-" + id),
                WorldDraftTestSupport.fp("policy-" + id), WorldDraftTestSupport.fp("request-" + id),
                WorldDraftTestSupport.fp("response-" + id), null, "",
                WorldDraftRedactionReport.notProcessed(), "", "");
    }
}
