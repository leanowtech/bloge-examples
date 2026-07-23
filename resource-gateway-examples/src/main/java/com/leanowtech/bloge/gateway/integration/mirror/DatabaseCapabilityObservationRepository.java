package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * H2-backed append-only capability-observation repository.
 *
 * <p>Only signed metadata, exact content references, and the payload-free admission decision are
 * stored. Duplicated index columns are compared with canonical JSON on every read. Neither raw nor
 * sanitized request/response bytes can be represented by this table.</p>
 */
public class DatabaseCapabilityObservationRepository
        implements CapabilityObservationRepository {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS mirror_capability_observations (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                observation_id VARCHAR(512) NOT NULL,
                observation_fingerprint VARCHAR(71) NOT NULL,
                capability_id VARCHAR(512) NOT NULL,
                capability_revision BIGINT NOT NULL,
                capability_fingerprint VARCHAR(71) NOT NULL,
                grant_id VARCHAR(512) NOT NULL,
                grant_fingerprint VARCHAR(71) NOT NULL,
                admission_fingerprint VARCHAR(71) NOT NULL,
                admission_state VARCHAR(32) NOT NULL,
                admission_reason VARCHAR(64) NOT NULL,
                occurred_at VARCHAR(64) NOT NULL,
                decided_at VARCHAR(64) NOT NULL,
                usable_until VARCHAR(64) NOT NULL,
                envelope_json CLOB NOT NULL,
                admission_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, observation_id
                )
            )
            """;
    private static final String INSERT = """
            INSERT INTO mirror_capability_observations (
                tenant_id, organization_id, project_id, environment_id, region,
                observation_id, observation_fingerprint,
                capability_id, capability_revision, capability_fingerprint,
                grant_id, grant_fingerprint,
                admission_fingerprint, admission_state, admission_reason,
                occurred_at, decided_at, usable_until,
                envelope_json, admission_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_EXACT = """
            SELECT observation_fingerprint,
                   capability_id, capability_revision, capability_fingerprint,
                   grant_id, grant_fingerprint,
                   admission_fingerprint, admission_state, admission_reason,
                   occurred_at, decided_at, usable_until,
                   envelope_json, admission_json
            FROM mirror_capability_observations
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND observation_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CapabilityObservationIntegrity observationIntegrity;
    private final CapabilityObservationAdmissionIntegrity admissionIntegrity;

    /**
     * Creates a durable payload-free observation repository.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @param mapper canonical protocol mapper
     * @param observationIntegrity observation content-addressing boundary
     * @param admissionIntegrity decision content-addressing boundary
     */
    public DatabaseCapabilityObservationRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            CapabilityObservationIntegrity observationIntegrity,
            CapabilityObservationAdmissionIntegrity admissionIntegrity) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.observationIntegrity = Objects.requireNonNull(
                observationIntegrity, "observationIntegrity");
        this.admissionIntegrity = Objects.requireNonNull(
                admissionIntegrity, "admissionIntegrity");
    }

    /** Creates the append-only observation table when absent. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_TABLE);
    }

    @Override
    @Transactional
    public StoredObservation append(StoredObservation candidate) {
        StoredObservation exact = verifyCandidate(candidate);
        CapabilityObservationEnvelope envelope = exact.envelope();
        CapabilityObservationAdmission admission = exact.admission();
        CapabilitySnapshot.Scope scope = envelope.material().scope();
        Optional<StoredObservation> existing =
                find(scope, envelope.material().observationId());
        if (existing.isPresent()) {
            return sameOrConflict(existing.get(), exact);
        }
        String envelopeJson = serialize(envelope);
        String admissionJson = serialize(admission);
        MirrorArtifactRef capability = envelope.material().capabilityRef();
        MirrorArtifactRef grant = envelope.material().dataUseGrant().grantRef();
        try {
            jdbc.update(
                    INSERT,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    envelope.material().observationId(),
                    envelope.observationFingerprint(),
                    capability.id(),
                    capability.revision(),
                    capability.fingerprint(),
                    grant.id(),
                    grant.fingerprint(),
                    admission.admissionFingerprint(),
                    admission.state().name(),
                    admission.reason().name(),
                    envelope.material().occurredAt().toString(),
                    admission.decidedAt().toString(),
                    admission.usableUntil().toString(),
                    envelopeJson,
                    admissionJson);
            return exact;
        } catch (DuplicateKeyException concurrent) {
            StoredObservation stored = find(
                    scope, envelope.material().observationId())
                    .orElseThrow(() -> concurrent);
            return sameOrConflict(stored, exact);
        }
    }

    @Override
    public Optional<StoredObservation> find(
            CapabilitySnapshot.Scope scope, String observationId) {
        CapabilitySnapshot.Scope exactScope = Objects.requireNonNull(scope, "scope");
        String exactId = identifier(observationId);
        List<StoredObservation> found = jdbc.query(
                SELECT_EXACT,
                (result, rowNumber) -> deserialize(
                        exactScope,
                        exactId,
                        result.getString("observation_fingerprint"),
                        result.getString("capability_id"),
                        result.getLong("capability_revision"),
                        result.getString("capability_fingerprint"),
                        result.getString("grant_id"),
                        result.getString("grant_fingerprint"),
                        result.getString("admission_fingerprint"),
                        result.getString("admission_state"),
                        result.getString("admission_reason"),
                        result.getString("occurred_at"),
                        result.getString("decided_at"),
                        result.getString("usable_until"),
                        result.getString("envelope_json"),
                        result.getString("admission_json")),
                exactScope.tenantId(),
                exactScope.organizationId(),
                exactScope.projectId(),
                exactScope.environmentId(),
                exactScope.region(),
                exactId);
        return found.stream().findFirst();
    }

    private StoredObservation deserialize(
            CapabilitySnapshot.Scope expectedScope,
            String expectedObservationId,
            String expectedObservationFingerprint,
            String expectedCapabilityId,
            long expectedCapabilityRevision,
            String expectedCapabilityFingerprint,
            String expectedGrantId,
            String expectedGrantFingerprint,
            String expectedAdmissionFingerprint,
            String expectedAdmissionState,
            String expectedAdmissionReason,
            String expectedOccurredAt,
            String expectedDecidedAt,
            String expectedUsableUntil,
            String envelopeJson,
            String admissionJson) {
        try {
            CapabilityObservationEnvelope envelope = mapper.readValue(
                    envelopeJson, CapabilityObservationEnvelope.class);
            CapabilityObservationAdmission admission = mapper.readValue(
                    admissionJson, CapabilityObservationAdmission.class);
            StoredObservation stored = verifyCandidate(
                    new StoredObservation(envelope, admission));
            MirrorArtifactRef capability = envelope.material().capabilityRef();
            MirrorArtifactRef grant = envelope.material().dataUseGrant().grantRef();
            if (!expectedScope.equals(envelope.material().scope())
                    || !expectedObservationId.equals(
                    envelope.material().observationId())
                    || !expectedObservationFingerprint.equals(
                    envelope.observationFingerprint())
                    || !expectedCapabilityId.equals(capability.id())
                    || expectedCapabilityRevision != capability.revision()
                    || !expectedCapabilityFingerprint.equals(capability.fingerprint())
                    || !expectedGrantId.equals(grant.id())
                    || !expectedGrantFingerprint.equals(grant.fingerprint())
                    || !expectedAdmissionFingerprint.equals(
                    admission.admissionFingerprint())
                    || !expectedAdmissionState.equals(admission.state().name())
                    || !expectedAdmissionReason.equals(admission.reason().name())
                    || !expectedOccurredAt.equals(
                    envelope.material().occurredAt().toString())
                    || !expectedDecidedAt.equals(admission.decidedAt().toString())
                    || !expectedUsableUntil.equals(admission.usableUntil().toString())) {
                throw new Violation(Reason.STORED_STATE_CORRUPT);
            }
            return stored;
        } catch (Violation expected) {
            throw expected;
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            throw new Violation(Reason.STORED_STATE_CORRUPT);
        }
    }

    private StoredObservation verifyCandidate(StoredObservation candidate) {
        try {
            StoredObservation exact = Objects.requireNonNull(candidate, "candidate");
            if (!observationIntegrity.canonicalFingerprintVerified(exact.envelope())
                    || !admissionIntegrity.verified(exact.admission())) {
                throw new Violation(Reason.CANONICAL_INVALID);
            }
            return exact;
        } catch (Violation expected) {
            throw expected;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new Violation(Reason.IDENTITY_MISMATCH);
        }
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new Violation(Reason.CANONICAL_INVALID);
        }
    }

    private static StoredObservation sameOrConflict(
            StoredObservation stored, StoredObservation candidate) {
        if (stored.envelope().observationFingerprint().equals(
                candidate.envelope().observationFingerprint())
                && stored.admission().admissionFingerprint().equals(
                candidate.admission().admissionFingerprint())) {
            return stored;
        }
        throw new Violation(Reason.OBSERVATION_ID_CONFLICT);
    }

    private static String identifier(String value) {
        String exact = value == null ? "" : value.trim();
        if (!exact.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}")) {
            throw new IllegalArgumentException("observationId is invalid");
        }
        return exact;
    }
}
