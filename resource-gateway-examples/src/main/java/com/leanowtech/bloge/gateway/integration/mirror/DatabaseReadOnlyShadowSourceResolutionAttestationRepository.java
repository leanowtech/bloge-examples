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
 * Database-backed append-only repository for signed source-resolution attestations.
 *
 * <p>Every read verifies signed JSON and every redundant index. The table contains no business
 * payload, credential, endpoint, exception, or free-form text.</p>
 */
public class DatabaseReadOnlyShadowSourceResolutionAttestationRepository
        implements ReadOnlyShadowSourceResolutionAttestationRepository {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS read_only_shadow_source_resolution_attestation (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                attestation_id VARCHAR(512) NOT NULL,
                attestation_revision BIGINT NOT NULL,
                attestation_fingerprint VARCHAR(71) NOT NULL,
                source_binding_fingerprint VARCHAR(71) NOT NULL,
                baseline_source_fingerprint VARCHAR(71) NOT NULL,
                candidate_source_fingerprint VARCHAR(71) NOT NULL,
                admission_fingerprint VARCHAR(71) NOT NULL,
                schema_version VARCHAR(128) NOT NULL,
                attestation_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region,
                    attestation_id, attestation_revision
                )
            )
            """;
    private static final String INSERT = """
            INSERT INTO read_only_shadow_source_resolution_attestation (
                tenant_id, organization_id, project_id, environment_id, region,
                attestation_id, attestation_revision, attestation_fingerprint,
                source_binding_fingerprint, baseline_source_fingerprint,
                candidate_source_fingerprint, admission_fingerprint,
                schema_version, attestation_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_EXACT = """
            SELECT attestation_id, attestation_revision,
                   attestation_fingerprint, source_binding_fingerprint,
                   baseline_source_fingerprint, candidate_source_fingerprint,
                   admission_fingerprint, schema_version, attestation_json
            FROM read_only_shadow_source_resolution_attestation
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ?
              AND attestation_id = ? AND attestation_revision = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ReadOnlyShadowSourceResolutionAttestationIntegrity
            integrity;

    /**
     * Creates a verified append-only source-resolution repository.
     *
     * @param jdbc application JDBC boundary
     * @param mapper canonical protocol mapper
     * @param integrity content-address and signature verifier
     */
    public DatabaseReadOnlyShadowSourceResolutionAttestationRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            ReadOnlyShadowSourceResolutionAttestationIntegrity
                    integrity) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.integrity = Objects.requireNonNull(
                integrity, "integrity");
    }

    /** Creates the payload-free append-only table when absent. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_TABLE);
    }

    @Override
    @Transactional
    public ReadOnlyShadowSourceResolutionAttestation create(
            ReadOnlyShadowSourceResolutionAttestation attestation) {
        ReadOnlyShadowSourceResolutionAttestation exact =
                integrity.verify(
                        Objects.requireNonNull(
                                attestation,
                                "attestation"));
        Optional<ReadOnlyShadowSourceResolutionAttestation> existing =
                find(
                        exact.scope(),
                        exact.attestationId(),
                        exact.revision());
        if (existing.isPresent()) {
            return sameOrConflict(existing.get(), exact);
        }
        CapabilitySnapshot.Scope scope = exact.scope();
        try {
            jdbc.update(
                    INSERT,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    exact.attestationId(),
                    exact.revision(),
                    exact.attestationFingerprint(),
                    exact.sourceBindingRef().fingerprint(),
                    exact.baseline().artifactRef().fingerprint(),
                    exact.candidate().artifactRef().fingerprint(),
                    exact.admissionFingerprint(),
                    exact.schemaVersion(),
                    serialize(exact));
            return exact;
        } catch (DuplicateKeyException duplicate) {
            ReadOnlyShadowSourceResolutionAttestation concurrent =
                    find(
                            exact.scope(),
                            exact.attestationId(),
                            exact.revision())
                            .orElseThrow(() -> duplicate);
            return sameOrConflict(concurrent, exact);
        }
    }

    @Override
    public Optional<ReadOnlyShadowSourceResolutionAttestation> find(
            CapabilitySnapshot.Scope scope,
            String attestationId,
            long revision) {
        CapabilitySnapshot.Scope exactScope =
                Objects.requireNonNull(scope, "scope");
        String exactId = required(
                attestationId, "attestationId");
        if (revision < 1) {
            throw new IllegalArgumentException(
                    "source-resolution revision must be positive");
        }
        List<ReadOnlyShadowSourceResolutionAttestation> rows =
                jdbc.query(
                        SELECT_EXACT,
                        (result, row) -> deserialize(
                                result.getString(
                                        "attestation_json"),
                                exactScope,
                                exactId,
                                revision,
                                result.getString(
                                        "attestation_fingerprint"),
                                result.getString(
                                        "source_binding_fingerprint"),
                                result.getString(
                                        "baseline_source_fingerprint"),
                                result.getString(
                                        "candidate_source_fingerprint"),
                                result.getString(
                                        "admission_fingerprint"),
                                result.getString(
                                        "schema_version")),
                        exactScope.tenantId(),
                        exactScope.organizationId(),
                        exactScope.projectId(),
                        exactScope.environmentId(),
                        exactScope.region(),
                        exactId,
                        revision);
        return rows.stream().findFirst();
    }

    private ReadOnlyShadowSourceResolutionAttestation deserialize(
            String value,
            CapabilitySnapshot.Scope scope,
            String attestationId,
            long revision,
            String attestationFingerprint,
            String sourceBindingFingerprint,
            String baselineFingerprint,
            String candidateFingerprint,
            String admissionFingerprint,
            String schemaVersion) {
        try {
            ReadOnlyShadowSourceResolutionAttestation attestation =
                    integrity.verify(
                            mapper.readValue(
                                    value,
                                    ReadOnlyShadowSourceResolutionAttestation
                                            .class));
            if (!scope.equals(attestation.scope())
                    || !attestationId.equals(
                    attestation.attestationId())
                    || revision != attestation.revision()
                    || !attestationFingerprint.equals(
                    attestation.attestationFingerprint())
                    || !sourceBindingFingerprint.equals(
                    attestation.sourceBindingRef()
                            .fingerprint())
                    || !baselineFingerprint.equals(
                    attestation.baseline()
                            .artifactRef().fingerprint())
                    || !candidateFingerprint.equals(
                    attestation.candidate()
                            .artifactRef().fingerprint())
                    || !admissionFingerprint.equals(
                    attestation.admissionFingerprint())
                    || !schemaVersion.equals(
                    attestation.schemaVersion())) {
                throw new IllegalArgumentException(
                        "source-resolution index differs from signed JSON");
            }
            return attestation;
        } catch (JsonProcessingException
                 | IllegalArgumentException
                 | ReadOnlyShadowSourceResolutionAttestationIntegrity
                         .Violation invalid) {
            throw new IllegalStateException(
                    "Stored source-resolution attestation failed integrity validation",
                    invalid);
        }
    }

    private String serialize(
            ReadOnlyShadowSourceResolutionAttestation attestation) {
        try {
            return mapper.writeValueAsString(attestation);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(
                    "source-resolution attestation cannot be serialized",
                    failure);
        }
    }

    private static ReadOnlyShadowSourceResolutionAttestation
    sameOrConflict(
            ReadOnlyShadowSourceResolutionAttestation existing,
            ReadOnlyShadowSourceResolutionAttestation requested) {
        if (existing.attestationFingerprint().equals(
                requested.attestationFingerprint())) {
            return existing;
        }
        throw new IllegalArgumentException(
                "source-resolution revision already contains different content");
    }

    private static String required(
            String value,
            String field) {
        String exact = value == null ? "" : value.trim();
        if (exact.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " must not be blank");
        }
        return exact;
    }
}
