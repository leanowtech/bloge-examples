package com.leanowtech.bloge.gateway.testing.world.draft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** JDBC vault for redacted values; authorization and retention precede every payload query. */
public final class DatabaseWorldDraftRedactedPayloadVault implements WorldDraftRedactedPayloadVault {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration retention;
    private final WorldDraftAuditSink audit;
    private final WorldDraftPayloadProtector protector;

    public DatabaseWorldDraftRedactedPayloadVault(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock,
                                                   Duration retention, WorldDraftAuditSink audit,
                                                   WorldDraftPayloadProtector protector) {
        if (jdbc == null || jdbc.getDataSource() == null || mapper == null || clock == null || retention == null
                || audit == null || protector == null || retention.isNegative() || retention.isZero()
                || retention.compareTo(Duration.ofDays(365)) > 0) throw invalid();
        this.jdbc = jdbc; this.mapper = mapper.copy(); this.clock = clock; this.retention = retention; this.audit = audit;
        this.protector = protector;
    }

    @Override public StoredPayload put(WorldDraftRedactedPayloadRef ref, WorldDraftRedactedPayload payload,
                                       WorldDraftCandidateService.Access access) {
        authorize(ref, access);
        StoredPayload stored = new StoredPayload(ref, payload);
        String json;
        try {
            LinkedHashMap<String, Object> body = new LinkedHashMap<>();
            body.put("request", payload.request()); body.put("response", payload.response());
            json = mapper.writeValueAsString(body);
        } catch (Exception failure) { throw invalid(); }
        String aad = associatedData(ref);
        String protectedPayload;
        try {
            protectedPayload = protector.protect(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), aad);
        } catch (RuntimeException failure) { throw invalid(); }
        String commitment = payloadCommitment(json);
        try {
            jdbc.update(("INSERT INTO rg_world_draft_redacted_payloads(tenant_id,candidate_id,artifact_revision,request_fingerprint,response_fingerprint,pair_fingerprint,"
                            + "protected_payload,payload_key_id,payload_commitment,expires_at,retention_status) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?)"),
                    ref.tenantId(), ref.candidateId(), ref.artifactRevision(), ref.requestFingerprint(),
                    ref.responseFingerprint(), ref.pairFingerprint(), protectedPayload, protector.activeKeyId(), commitment,
                    Timestamp.from(clock.instant().plus(retention)), "DRAFT");
            audit.record(ref.tenantId(), ref.candidateId(), "VAULT_PUT", ref.artifactRevision(), true);
            return stored;
        } catch (DuplicateKeyException duplicate) { return read(ref, access).orElseThrow(DatabaseWorldDraftRedactedPayloadVault::invalid); }
    }

    @Override public Optional<StoredPayload> read(WorldDraftRedactedPayloadRef ref,
                                                    WorldDraftCandidateService.Access access) {
        authorize(ref, access);
        Optional<Metadata> metadata = jdbc.query("SELECT expires_at,retention_status,payload_key_id,payload_commitment FROM rg_world_draft_redacted_payloads WHERE tenant_id=? AND candidate_id=? AND artifact_revision=? AND request_fingerprint=? AND response_fingerprint=? AND pair_fingerprint=?",
                rs -> rs.next() ? Optional.of(new Metadata(rs.getTimestamp(1), rs.getString(2), rs.getString(3), rs.getString(4))) : Optional.empty(),
                ref.tenantId(), ref.candidateId(), ref.artifactRevision(), ref.requestFingerprint(),
                ref.responseFingerprint(), ref.pairFingerprint());
        if (metadata.isEmpty() || "REVOKED".equals(metadata.get().status())
                || (!"PINNED".equals(metadata.get().status())
                && !metadata.get().expiresAt().toInstant().isAfter(clock.instant()))) {
            audit.record(ref.tenantId(), ref.candidateId(), "VAULT_READ", ref.artifactRevision(), false);
            return Optional.empty();
        }
        Optional<StoredPayload> result = jdbc.query("SELECT protected_payload,payload_key_id,payload_commitment FROM rg_world_draft_redacted_payloads WHERE tenant_id=? AND candidate_id=? AND artifact_revision=? AND request_fingerprint=? AND response_fingerprint=? AND pair_fingerprint=? AND (retention_status='PINNED' OR expires_at > ?)",
                rs -> {
                    if (!rs.next()) return Optional.empty();
                    try {
                        String envelope = rs.getString(1);
                        if (!metadata.get().keyId().equals(rs.getString(2))
                                || !metadata.get().commitment().equals(rs.getString(3))
                                || !metadata.get().keyId().equals(envelopeKeyId(envelope))) throw invalid();
                        byte[] cleartext = protector.unprotect(envelope, associatedData(ref));
                        String json = decodeUtf8(cleartext);
                        if (!metadata.get().commitment().equals(payloadCommitment(json))) throw invalid();
                        JsonNode node = mapper.readTree(json);
                        if (!node.isObject() || !node.has("request") || !node.has("response")) throw invalid();
                        Object request = mapper.convertValue(node.get("request"), Object.class);
                        Object response = mapper.convertValue(node.get("response"), Object.class);
                        return Optional.of(new StoredPayload(ref, new WorldDraftRedactedPayload(request, response)));
                    } catch (WorldDraftCandidateException failure) { throw failure; }
                    catch (Exception failure) { throw invalid(); }
                }, ref.tenantId(), ref.candidateId(), ref.artifactRevision(), ref.requestFingerprint(),
                ref.responseFingerprint(), ref.pairFingerprint(), Timestamp.from(clock.instant()));
        audit.record(ref.tenantId(), ref.candidateId(), "VAULT_READ", ref.artifactRevision(), result.isPresent());
        return result;
    }

    @Override
    public Optional<StoredPayload> readPublished(WorldDraftRedactedPayloadRef ref, PublishedBinding binding,
                                                 WorldDraftCandidateService.Access access) {
        authorize(ref, access);
        if (binding == null) throw invalid();
        binding.requireMatches(ref, access);
        Optional<PinMetadata> metadata = jdbc.query(
                "SELECT retention_status,published_world_fingerprint,published_rule_fingerprint,publication_receipt_fingerprint "
                        + "FROM rg_world_draft_redacted_payloads WHERE tenant_id=? AND candidate_id=? AND artifact_revision=? "
                        + "AND request_fingerprint=? AND response_fingerprint=? AND pair_fingerprint=?",
                rs -> rs.next() ? Optional.of(new PinMetadata(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                        : Optional.empty(), ref.tenantId(), ref.candidateId(), ref.artifactRevision(),
                ref.requestFingerprint(), ref.responseFingerprint(), ref.pairFingerprint());
        if (metadata.isEmpty() || !"PINNED".equals(metadata.get().status())
                || !binding.worldFingerprint().equals(metadata.get().worldFingerprint())
                || !binding.ruleFingerprint().equals(metadata.get().ruleFingerprint())
                || !binding.publicationReceiptFingerprint().equals(metadata.get().receiptFingerprint())) {
            audit.record(ref.tenantId(), ref.candidateId(), "VAULT_READ_PUBLISHED", ref.artifactRevision(), false);
            return Optional.empty();
        }
        return read(ref, access);
    }

    @Override
    public void pin(WorldDraftRedactedPayloadRef ref, PublishedBinding binding,
                    WorldDraftCandidateService.Access access) {
        authorize(ref, access);
        if (binding == null) throw invalid();
        binding.requireMatches(ref, access);
        Optional<PinMetadata> existing = jdbc.query(
                "SELECT retention_status,published_world_fingerprint,published_rule_fingerprint,publication_receipt_fingerprint "
                        + "FROM rg_world_draft_redacted_payloads WHERE tenant_id=? AND candidate_id=? AND artifact_revision=? "
                        + "AND request_fingerprint=? AND response_fingerprint=? AND pair_fingerprint=?",
                rs -> rs.next() ? Optional.of(new PinMetadata(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                        : Optional.empty(), ref.tenantId(), ref.candidateId(), ref.artifactRevision(),
                ref.requestFingerprint(), ref.responseFingerprint(), ref.pairFingerprint());
        if (existing.isEmpty() || "REVOKED".equals(existing.get().status())) throw invalid();
        if ("PINNED".equals(existing.get().status())) {
            if (!binding.worldFingerprint().equals(existing.get().worldFingerprint())
                    || !binding.ruleFingerprint().equals(existing.get().ruleFingerprint())
                    || !binding.publicationReceiptFingerprint().equals(existing.get().receiptFingerprint())) throw invalid();
            return;
        }
        int updated = jdbc.update("UPDATE rg_world_draft_redacted_payloads SET retention_status='PINNED',"
                        + "published_world_fingerprint=?,published_rule_fingerprint=?,publication_receipt_fingerprint=? "
                        + "WHERE tenant_id=? AND candidate_id=? AND artifact_revision=? AND request_fingerprint=? "
                        + "AND response_fingerprint=? AND pair_fingerprint=? AND retention_status='DRAFT' AND expires_at > ?",
                binding.worldFingerprint(), binding.ruleFingerprint(), binding.publicationReceiptFingerprint(),
                ref.tenantId(), ref.candidateId(), ref.artifactRevision(), ref.requestFingerprint(),
                ref.responseFingerprint(), ref.pairFingerprint(), Timestamp.from(clock.instant()));
        if (updated != 1) throw invalid();
        audit.record(ref.tenantId(), ref.candidateId(), "VAULT_PIN", ref.artifactRevision(), true);
    }

    @Override
    public void unpin(WorldDraftRedactedPayloadRef ref, PublishedBinding binding,
                      WorldDraftCandidateService.Access access) {
        authorize(ref, access);
        if (binding == null) return;
        binding.requireMatches(ref, access);
        jdbc.update("UPDATE rg_world_draft_redacted_payloads SET retention_status='DRAFT',"
                        + "published_world_fingerprint=NULL,published_rule_fingerprint=NULL,publication_receipt_fingerprint=NULL "
                        + "WHERE tenant_id=? AND candidate_id=? AND artifact_revision=? AND request_fingerprint=? "
                        + "AND response_fingerprint=? AND pair_fingerprint=? AND retention_status='PINNED' "
                        + "AND published_world_fingerprint=? AND published_rule_fingerprint=? AND publication_receipt_fingerprint=?",
                ref.tenantId(), ref.candidateId(), ref.artifactRevision(), ref.requestFingerprint(),
                ref.responseFingerprint(), ref.pairFingerprint(), binding.worldFingerprint(),
                binding.ruleFingerprint(), binding.publicationReceiptFingerprint());
    }

    @Override
    public void revoke(WorldDraftRedactedPayloadRef ref, WorldDraftCandidateService.Access access) {
        authorize(ref, access);
        jdbc.update("UPDATE rg_world_draft_redacted_payloads SET retention_status='REVOKED',revoked_at=? "
                        + "WHERE tenant_id=? AND candidate_id=? AND artifact_revision=? AND request_fingerprint=? "
                        + "AND response_fingerprint=? AND pair_fingerprint=?",
                Timestamp.from(clock.instant()), ref.tenantId(), ref.candidateId(), ref.artifactRevision(),
                ref.requestFingerprint(), ref.responseFingerprint(), ref.pairFingerprint());
    }

    /** Removes expired payload rows using the same clock as admission and records a payload-free audit fact. */
    public int purgeExpired() {
        int purged = jdbc.update("DELETE FROM rg_world_draft_redacted_payloads WHERE expires_at <= ? AND retention_status <> 'PINNED'",
                Timestamp.from(clock.instant()));
        audit.record("*", "*", "VAULT_PURGE", 0, true);
        return purged;
    }

    private static String associatedData(WorldDraftRedactedPayloadRef ref) {
        return VisualBundleFingerprint.fromMaterial(Map.of(
                "tenantId", ref.tenantId(), "candidateId", ref.candidateId(),
                "artifactRevision", ref.artifactRevision(),
                "requestFingerprint", ref.requestFingerprint(),
                "responseFingerprint", ref.responseFingerprint(),
                "pairFingerprint", ref.pairFingerprint()));
    }

    private static String payloadCommitment(String json) {
        return VisualBundleFingerprint.fromMaterial(Map.of("payload", json));
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw invalid();
        }
    }

    private static String envelopeKeyId(String envelope) {
        if (envelope == null) return "";
        String[] parts = envelope.split("\\.", 4);
        return parts.length == 4 ? parts[1] : "";
    }

    private record Metadata(Timestamp expiresAt, String status, String keyId, String commitment) { }
    private record PinMetadata(String status, String worldFingerprint, String ruleFingerprint,
                               String receiptFingerprint) { }

    private static void authorize(WorldDraftRedactedPayloadRef ref, WorldDraftCandidateService.Access access) {
        if (ref == null || access == null || !ref.tenantId().equals(access.tenantId())
                || !WorldDraftCandidateService.PURPOSE.equals(access.purpose())) throw new WorldDraftCandidateException(
                WorldDraftCandidateException.Code.SOURCE_NOT_AUTHORIZED);
    }
    private static WorldDraftCandidateException invalid() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.SOURCE_INTEGRITY);
    }
}
