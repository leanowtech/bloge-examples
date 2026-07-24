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
 * H2-backed append-only registry for payload-free ScenarioPack protocol artifacts.
 */
public class DatabaseScenarioArtifactRepository
        implements ScenarioArtifactRepository {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS mirror_scenario_artifacts (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                artifact_kind VARCHAR(64) NOT NULL,
                artifact_id VARCHAR(512) NOT NULL,
                artifact_revision BIGINT NOT NULL,
                artifact_fingerprint VARCHAR(71) NOT NULL,
                schema_version VARCHAR(128) NOT NULL,
                artifact_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region,
                    artifact_kind, artifact_id, artifact_revision
                )
            )
            """;
    private static final String INSERT = """
            INSERT INTO mirror_scenario_artifacts (
                tenant_id, organization_id, project_id, environment_id, region,
                artifact_kind, artifact_id, artifact_revision,
                artifact_fingerprint, schema_version, artifact_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_EXACT = """
            SELECT artifact_fingerprint, schema_version, artifact_json
            FROM mirror_scenario_artifacts
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ?
              AND artifact_kind = ? AND artifact_id = ? AND artifact_revision = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MirrorSessionCheckpointIntegrityService checkpointIntegrity;

    /**
     * Creates the durable registry.
     *
     * @param jdbc transaction-aware JDBC boundary
     * @param mapper canonical protocol mapper
     * @param checkpointIntegrity signed checkpoint verifier
     */
    public DatabaseScenarioArtifactRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            MirrorSessionCheckpointIntegrityService checkpointIntegrity) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.checkpointIntegrity = Objects.requireNonNull(
                checkpointIntegrity, "checkpointIntegrity");
    }

    /** Creates the append-only artifact table when absent. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_TABLE);
    }

    @Override
    public CaseHandlingAssertion create(CaseHandlingAssertion assertion) {
        ScenarioPackIntegrity.verifyAssertion(mapper, assertion);
        return create(
                Kind.ASSERTION,
                assertion.scope(),
                ScenarioPackIntegrity.reference(assertion),
                assertion.schemaVersion(),
                assertion);
    }

    @Override
    public ScenarioCase create(ScenarioCase scenarioCase) {
        ScenarioPackIntegrity.verifyCase(mapper, scenarioCase);
        return create(
                Kind.CASE,
                scenarioCase.scope(),
                ScenarioPackIntegrity.reference(scenarioCase),
                scenarioCase.schemaVersion(),
                scenarioCase);
    }

    @Override
    public ScenarioPack create(ScenarioPack pack) {
        ScenarioPackIntegrity.verify(mapper, pack);
        return create(
                Kind.PACK,
                pack.scope(),
                ScenarioPackIntegrity.reference(pack),
                pack.schemaVersion(),
                pack);
    }

    @Override
    public MirrorSessionCheckpointBundle create(
            MirrorSessionCheckpointBundle checkpoint) {
        requireVerifiedCheckpoint(checkpoint);
        return create(
                Kind.CHECKPOINT,
                checkpoint.checkpoint().scope(),
                ScenarioPackIntegrity.reference(checkpoint),
                checkpoint.schemaVersion(),
                checkpoint);
    }

    @Override
    public Optional<CaseHandlingAssertion> findAssertion(
            CapabilitySnapshot.Scope scope, String assertionId, long revision) {
        return find(
                Kind.ASSERTION, scope, assertionId, revision,
                CaseHandlingAssertion.class);
    }

    @Override
    public Optional<ScenarioCase> findCase(
            CapabilitySnapshot.Scope scope, String caseId, long revision) {
        return find(Kind.CASE, scope, caseId, revision, ScenarioCase.class);
    }

    @Override
    public Optional<ScenarioPack> findPack(
            CapabilitySnapshot.Scope scope, String packId, long revision) {
        return find(Kind.PACK, scope, packId, revision, ScenarioPack.class);
    }

    @Override
    public Optional<MirrorSessionCheckpointBundle> findCheckpoint(
            CapabilitySnapshot.Scope scope, String checkpointId, long revision) {
        return find(
                Kind.CHECKPOINT, scope, checkpointId, revision,
                MirrorSessionCheckpointBundle.class);
    }

    @Transactional
    private <T> T create(
            Kind kind,
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef ref,
            String schemaVersion,
            T value) {
        Optional<T> existing = find(
                kind, scope, ref.id(), ref.revision(), kind.type());
        if (existing.isPresent()) {
            MirrorArtifactRef existingRef =
                    reference(kind, existing.get());
            if (existingRef.fingerprint().equals(ref.fingerprint())) {
                return existing.get();
            }
            throw new IllegalArgumentException(
                    "scenario artifact revision already exists with different content");
        }
        try {
            jdbc.update(
                    INSERT,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    kind.name(),
                    ref.id(),
                    ref.revision(),
                    ref.fingerprint(),
                    schemaVersion,
                    serialize(value));
            return value;
        } catch (DuplicateKeyException duplicate) {
            Optional<T> raced = this.<T>find(
                    kind, scope, ref.id(), ref.revision(), kind.type());
            T stored = raced.orElseThrow(() -> duplicate);
            if (reference(kind, stored).fingerprint()
                    .equals(ref.fingerprint())) {
                return stored;
            }
            throw new IllegalArgumentException(
                    "scenario artifact revision already exists with different content",
                    duplicate);
        }
    }

    private <T> Optional<T> find(
            Kind kind,
            CapabilitySnapshot.Scope scope,
            String artifactId,
            long revision,
            Class<?> ignoredType) {
        Objects.requireNonNull(scope, "scope");
        String id = required(artifactId, "artifactId");
        if (revision < 1) {
            throw new IllegalArgumentException(
                    "artifact revision must be positive");
        }
        List<T> values = jdbc.query(
                SELECT_EXACT,
                (rs, rowNumber) -> deserialize(
                        kind,
                        rs.getString("artifact_json"),
                        scope,
                        id,
                        revision,
                        rs.getString("artifact_fingerprint"),
                        rs.getString("schema_version")),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                kind.name(),
                id,
                revision);
        return values.stream().findFirst();
    }

    @SuppressWarnings("unchecked")
    private <T> T deserialize(
            Kind kind,
            String json,
            CapabilitySnapshot.Scope expectedScope,
            String expectedId,
            long expectedRevision,
            String expectedFingerprint,
            String expectedSchemaVersion) {
        try {
            Object value = mapper.readValue(json, kind.type());
            verify(kind, value);
            MirrorArtifactRef ref = reference(kind, value);
            CapabilitySnapshot.Scope scope = scope(kind, value);
            String schemaVersion = schemaVersion(kind, value);
            if (!expectedScope.equals(scope)
                    || !expectedId.equals(ref.id())
                    || expectedRevision != ref.revision()
                    || !expectedFingerprint.equals(ref.fingerprint())
                    || !expectedSchemaVersion.equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "scenario artifact indexed identity does not match JSON");
            }
            return (T) value;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new IllegalStateException(
                    "Stored scenario artifact failed integrity validation",
                    failure);
        }
    }

    private void verify(Kind kind, Object value) {
        switch (kind) {
            case ASSERTION -> ScenarioPackIntegrity.verifyAssertion(
                    mapper, (CaseHandlingAssertion) value);
            case CASE -> ScenarioPackIntegrity.verifyCase(
                    mapper, (ScenarioCase) value);
            case PACK -> ScenarioPackIntegrity.verify(
                    mapper, (ScenarioPack) value);
            case CHECKPOINT -> requireVerifiedCheckpoint(
                    (MirrorSessionCheckpointBundle) value);
        }
    }

    private void requireVerifiedCheckpoint(
            MirrorSessionCheckpointBundle checkpoint) {
        if (checkpointIntegrity.verify(checkpoint)
                != MirrorSessionCheckpointIntegrityService.Verification.VERIFIED) {
            throw new IllegalArgumentException(
                    "mirror Session checkpoint failed signature verification");
        }
    }

    private static MirrorArtifactRef reference(Kind kind, Object value) {
        return switch (kind) {
            case ASSERTION -> ScenarioPackIntegrity.reference(
                    (CaseHandlingAssertion) value);
            case CASE -> ScenarioPackIntegrity.reference((ScenarioCase) value);
            case PACK -> ScenarioPackIntegrity.reference((ScenarioPack) value);
            case CHECKPOINT -> ScenarioPackIntegrity.reference(
                    (MirrorSessionCheckpointBundle) value);
        };
    }

    private static CapabilitySnapshot.Scope scope(Kind kind, Object value) {
        return switch (kind) {
            case ASSERTION -> ((CaseHandlingAssertion) value).scope();
            case CASE -> ((ScenarioCase) value).scope();
            case PACK -> ((ScenarioPack) value).scope();
            case CHECKPOINT ->
                    ((MirrorSessionCheckpointBundle) value).checkpoint().scope();
        };
    }

    private static String schemaVersion(Kind kind, Object value) {
        return switch (kind) {
            case ASSERTION -> ((CaseHandlingAssertion) value).schemaVersion();
            case CASE -> ((ScenarioCase) value).schemaVersion();
            case PACK -> ((ScenarioPack) value).schemaVersion();
            case CHECKPOINT ->
                    ((MirrorSessionCheckpointBundle) value).schemaVersion();
        };
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(
                    "scenario artifact cannot be serialized", failure);
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private enum Kind {
        ASSERTION(CaseHandlingAssertion.class),
        CASE(ScenarioCase.class),
        PACK(ScenarioPack.class),
        CHECKPOINT(MirrorSessionCheckpointBundle.class);

        private final Class<?> type;

        Kind(Class<?> type) {
            this.type = type;
        }

        Class<?> type() {
            return type;
        }
    }
}
