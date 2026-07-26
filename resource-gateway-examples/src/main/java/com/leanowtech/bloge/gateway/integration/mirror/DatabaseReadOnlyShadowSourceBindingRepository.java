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
 * Database-backed append-only repository for signed detached Shadow source bindings.
 *
 * <p>Duplicated index columns are never trusted independently: every read verifies the signed JSON
 * and compares all indexed identity fields. The table contains no business payload, credential,
 * endpoint, exception, or free-form source text column.</p>
 */
public class DatabaseReadOnlyShadowSourceBindingRepository
        implements ReadOnlyShadowSourceBindingRepository {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS read_only_shadow_source_binding (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                binding_id VARCHAR(512) NOT NULL,
                binding_revision BIGINT NOT NULL,
                binding_fingerprint VARCHAR(71) NOT NULL,
                baseline_fingerprint VARCHAR(71) NOT NULL,
                candidate_evidence_fingerprint VARCHAR(71) NOT NULL,
                expires_at VARCHAR(64) NOT NULL,
                schema_version VARCHAR(128) NOT NULL,
                binding_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region,
                    binding_id, binding_revision
                )
            )
            """;
    private static final String INSERT = """
            INSERT INTO read_only_shadow_source_binding (
                tenant_id, organization_id, project_id, environment_id, region,
                binding_id, binding_revision, binding_fingerprint,
                baseline_fingerprint, candidate_evidence_fingerprint,
                expires_at, schema_version, binding_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_EXACT = """
            SELECT binding_id, binding_revision, binding_fingerprint,
                   baseline_fingerprint, candidate_evidence_fingerprint,
                   expires_at, schema_version, binding_json
            FROM read_only_shadow_source_binding
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ?
              AND binding_id = ? AND binding_revision = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ReadOnlyShadowSourceBindingIntegrity integrity;

    /**
     * Creates a verified append-only source-binding repository.
     *
     * @param jdbc application JDBC boundary
     * @param mapper canonical protocol mapper
     * @param integrity binding content-address and signature verifier
     */
    public DatabaseReadOnlyShadowSourceBindingRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            ReadOnlyShadowSourceBindingIntegrity integrity) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.integrity = Objects.requireNonNull(integrity, "integrity");
    }

    /** Creates the payload-free append-only table when absent. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_TABLE);
    }

    @Override
    @Transactional
    public ReadOnlyShadowSourceBinding create(
            ReadOnlyShadowSourceBinding binding) {
        ReadOnlyShadowSourceBinding exact =
                integrity.verify(
                        Objects.requireNonNull(binding, "binding"));
        Optional<ReadOnlyShadowSourceBinding> existing =
                find(
                        exact.scope(),
                        exact.bindingId(),
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
                    exact.bindingId(),
                    exact.revision(),
                    exact.bindingFingerprint(),
                    exact.baselineObservationFingerprint(),
                    exact.candidateEvidenceRef().fingerprint(),
                    exact.expiresAt().toString(),
                    exact.schemaVersion(),
                    serialize(exact));
            return exact;
        } catch (DuplicateKeyException duplicate) {
            ReadOnlyShadowSourceBinding concurrent =
                    find(
                            exact.scope(),
                            exact.bindingId(),
                            exact.revision())
                            .orElseThrow(() -> duplicate);
            return sameOrConflict(concurrent, exact);
        }
    }

    @Override
    public Optional<ReadOnlyShadowSourceBinding> find(
            CapabilitySnapshot.Scope scope,
            String bindingId,
            long revision) {
        CapabilitySnapshot.Scope exactScope =
                Objects.requireNonNull(scope, "scope");
        String exactId = required(bindingId, "bindingId");
        if (revision < 1) {
            throw new IllegalArgumentException(
                    "source-binding revision must be positive");
        }
        List<ReadOnlyShadowSourceBinding> rows =
                jdbc.query(
                        SELECT_EXACT,
                        (result, row) -> deserialize(
                                result.getString("binding_json"),
                                exactScope,
                                exactId,
                                revision,
                                result.getString(
                                        "binding_fingerprint"),
                                result.getString(
                                        "baseline_fingerprint"),
                                result.getString(
                                        "candidate_evidence_fingerprint"),
                                result.getString("expires_at"),
                                result.getString("schema_version")),
                        exactScope.tenantId(),
                        exactScope.organizationId(),
                        exactScope.projectId(),
                        exactScope.environmentId(),
                        exactScope.region(),
                        exactId,
                        revision);
        return rows.stream().findFirst();
    }

    private ReadOnlyShadowSourceBinding deserialize(
            String value,
            CapabilitySnapshot.Scope scope,
            String bindingId,
            long revision,
            String bindingFingerprint,
            String baselineFingerprint,
            String candidateFingerprint,
            String expiresAt,
            String schemaVersion) {
        try {
            ReadOnlyShadowSourceBinding binding =
                    integrity.verify(
                            mapper.readValue(
                                    value,
                                    ReadOnlyShadowSourceBinding.class));
            if (!scope.equals(binding.scope())
                    || !bindingId.equals(binding.bindingId())
                    || revision != binding.revision()
                    || !bindingFingerprint.equals(
                    binding.bindingFingerprint())
                    || !baselineFingerprint.equals(
                    binding.baselineObservationFingerprint())
                    || !candidateFingerprint.equals(
                    binding.candidateEvidenceRef().fingerprint())
                    || !expiresAt.equals(
                    binding.expiresAt().toString())
                    || !schemaVersion.equals(
                    binding.schemaVersion())) {
                throw new IllegalArgumentException(
                        "source-binding index differs from signed JSON");
            }
            return binding;
        } catch (JsonProcessingException
                 | IllegalArgumentException
                 | ReadOnlyShadowSourceBindingIntegrity.Violation invalid) {
            throw new IllegalStateException(
                    "Stored source binding failed integrity validation",
                    invalid);
        }
    }

    private String serialize(
            ReadOnlyShadowSourceBinding binding) {
        try {
            return mapper.writeValueAsString(binding);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(
                    "source binding cannot be serialized",
                    failure);
        }
    }

    private static ReadOnlyShadowSourceBinding sameOrConflict(
            ReadOnlyShadowSourceBinding existing,
            ReadOnlyShadowSourceBinding requested) {
        if (existing.bindingFingerprint().equals(
                requested.bindingFingerprint())) {
            return existing;
        }
        throw new IllegalArgumentException(
                "source-binding revision already contains different content");
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
