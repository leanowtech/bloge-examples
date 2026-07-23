package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationRepository.Reason;

/**
 * Database-backed append-only isolation-attestation and irreversible-status repository.
 *
 * <p>The complete enterprise scope and immutable deployment coordinates are indexed in every
 * stream head. Attestation bodies and status publications are immutable. A transaction locks one
 * head, inserts new content-addressed rows, and compare-and-set advances the attestation or status
 * floor. Every read reconstructs and re-verifies the complete atomic bundle from indexed rows.</p>
 */
public final class DatabaseMirrorDeploymentIsolationAttestationRepository
        implements MirrorDeploymentIsolationAttestationRepository {
    private static final String CREATE_HEADS = """
            CREATE TABLE IF NOT EXISTS mirror_isolation_attestation_heads (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                deployment_scope_id VARCHAR(512) NOT NULL,
                key_set_id VARCHAR(512) NOT NULL,
                attestation_id VARCHAR(512) NOT NULL,
                cluster_id VARCHAR(512) NOT NULL,
                namespace_id VARCHAR(512) NOT NULL,
                workload_name VARCHAR(512) NOT NULL,
                service_account VARCHAR(512) NOT NULL,
                image_digest VARCHAR(71) NOT NULL,
                floor_revision BIGINT NOT NULL,
                floor_attestation_fingerprint VARCHAR(71) NOT NULL,
                authority_generation BIGINT NOT NULL,
                authority_publication_fingerprint VARCHAR(71) NOT NULL,
                status_revision BIGINT NOT NULL,
                status_fingerprint VARCHAR(71) NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region,
                    deployment_scope_id, key_set_id, attestation_id
                )
            )
            """;
    private static final String CREATE_ATTESTATIONS = """
            CREATE TABLE IF NOT EXISTS mirror_isolation_attestations (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                deployment_scope_id VARCHAR(512) NOT NULL,
                key_set_id VARCHAR(512) NOT NULL,
                attestation_id VARCHAR(512) NOT NULL,
                revision BIGINT NOT NULL,
                attestation_fingerprint VARCHAR(71) NOT NULL UNIQUE,
                material_fingerprint VARCHAR(71) NOT NULL,
                authority_generation BIGINT NOT NULL,
                authority_publication_fingerprint VARCHAR(71) NOT NULL,
                schema_version VARCHAR(128) NOT NULL,
                attestation_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region,
                    deployment_scope_id, key_set_id, attestation_id, revision
                )
            )
            """;
    private static final String CREATE_STATUSES = """
            CREATE TABLE IF NOT EXISTS mirror_isolation_attestation_statuses (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                deployment_scope_id VARCHAR(512) NOT NULL,
                key_set_id VARCHAR(512) NOT NULL,
                attestation_id VARCHAR(512) NOT NULL,
                attestation_revision BIGINT NOT NULL,
                attestation_fingerprint VARCHAR(71) NOT NULL,
                status_revision BIGINT NOT NULL,
                status_fingerprint VARCHAR(71) NOT NULL UNIQUE,
                previous_status_fingerprint VARCHAR(71) NOT NULL,
                status_state VARCHAR(32) NOT NULL,
                status_reason VARCHAR(64) NOT NULL,
                effective_at VARCHAR(64) NOT NULL,
                schema_version VARCHAR(128) NOT NULL,
                status_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region,
                    deployment_scope_id, key_set_id, attestation_id,
                    attestation_revision, status_revision
                )
            )
            """;
    private static final String SELECT_HEAD = """
            SELECT cluster_id, namespace_id, workload_name, service_account, image_digest,
                   floor_revision, floor_attestation_fingerprint, authority_generation,
                   authority_publication_fingerprint, status_revision, status_fingerprint
            FROM mirror_isolation_attestation_heads
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND deployment_scope_id = ?
              AND key_set_id = ? AND attestation_id = ?
            """;
    private static final String SELECT_ATTESTATION = """
            SELECT revision, attestation_fingerprint, material_fingerprint,
                   authority_generation, authority_publication_fingerprint,
                   schema_version, attestation_json
            FROM mirror_isolation_attestations
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND deployment_scope_id = ?
              AND key_set_id = ? AND attestation_id = ? AND revision = ?
            """;
    private static final String SELECT_STATUS = """
            SELECT attestation_revision, attestation_fingerprint, status_revision,
                   status_fingerprint, previous_status_fingerprint, status_state,
                   status_reason, effective_at, schema_version, status_json
            FROM mirror_isolation_attestation_statuses
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND deployment_scope_id = ?
              AND key_set_id = ? AND attestation_id = ?
              AND attestation_revision = ? AND status_revision = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MirrorDeploymentIsolationAttestationIntegrity attestationIntegrity;
    private final MirrorDeploymentIsolationAttestationBundleIntegrity bundleIntegrity;
    private final TransactionTemplate transactions;

    /**
     * Creates the durable attestation control-plane repository.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @param mapper canonical protocol mapper
     * @param attestationIntegrity external attestation content-addressing verifier
     * @param bundleIntegrity local status and bundle content-addressing verifier
     * @param transactionManager transaction manager shared by Mirror persistence
     */
    public DatabaseMirrorDeploymentIsolationAttestationRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            MirrorDeploymentIsolationAttestationIntegrity attestationIntegrity,
            MirrorDeploymentIsolationAttestationBundleIntegrity bundleIntegrity,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.attestationIntegrity = Objects.requireNonNull(
                attestationIntegrity, "attestationIntegrity");
        this.bundleIntegrity = Objects.requireNonNull(bundleIntegrity, "bundleIntegrity");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    /** Creates additive immutable-body, immutable-status, and mutable-floor tables. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_HEADS);
        jdbc.execute(CREATE_ATTESTATIONS);
        jdbc.execute(CREATE_STATUSES);
    }

    @Override
    public MirrorDeploymentIsolationAttestationBundle append(
            MirrorDeploymentIsolationAttestationBundle candidate,
            long bootstrapRevision) {
        if (bootstrapRevision < 1 || !bundleIntegrity.canonicalBundleVerified(candidate)
                || !candidate.active()) {
            throw violation(Reason.CANONICAL_INVALID);
        }
        StreamIdentity stream = StreamIdentity.from(candidate);
        if (!candidate.authorityKeySetRef().id().equals(stream.keySetId())) {
            throw violation(Reason.IDENTITY_MISMATCH);
        }
        MirrorDeploymentIsolationAttestationBundle stored = transactions.execute(status -> {
            ensureHead(stream);
            return appendLocked(stream, candidate, bootstrapRevision);
        });
        if (stored == null) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        return stored;
    }

    private MirrorDeploymentIsolationAttestationBundle appendLocked(
            StreamIdentity stream,
            MirrorDeploymentIsolationAttestationBundle candidate,
            long bootstrapRevision) {
        Head head = selectHead(stream, true).orElseThrow(
                () -> violation(Reason.STORED_STATE_CORRUPT));
        requireDeployment(stream.deployment(), head.deployment());
        if (head.floorRevision() > 0) {
            MirrorDeploymentIsolationAttestationBundle current = readCurrent(stream, head);
            long candidateRevision = candidate.attestation().material().revision();
            if (candidateRevision < head.floorRevision()) {
                throw violation(Reason.REVISION_ROLLBACK);
            }
            if (candidateRevision == head.floorRevision()) {
                if (candidate.attestation().attestationFingerprint().equals(
                        current.attestation().attestationFingerprint())
                        && candidate.authorityKeySetRef().equals(current.authorityKeySetRef())) {
                    return current;
                }
                throw violation(Reason.REVISION_FORK);
            }
            if (candidateRevision > head.floorRevision() + 1) {
                throw violation(Reason.REVISION_GAP);
            }
        } else if (candidate.attestation().material().revision() != bootstrapRevision) {
            throw violation(Reason.BOOTSTRAP_REVISION_MISMATCH);
        }

        insertAttestation(stream, candidate);
        insertStatus(stream, candidate.status());
        int advanced = updateHeadForAttestation(stream, head, candidate);
        if (advanced != 1) {
            throw violation(Reason.REVISION_FORK);
        }
        return candidate;
    }

    @Override
    public MirrorDeploymentIsolationAttestationBundle revoke(
            StreamIdentity stream,
            CurrentExpectation expected,
            MirrorDeploymentIsolationAttestationStatusPublication.Reason reason,
            Instant revokedAt) {
        StreamIdentity exact = Objects.requireNonNull(stream, "stream");
        CurrentExpectation currentExpected = Objects.requireNonNull(expected, "expected");
        MirrorDeploymentIsolationAttestationStatusPublication.Reason exactReason =
                Objects.requireNonNull(reason, "reason");
        if (exactReason == MirrorDeploymentIsolationAttestationStatusPublication.Reason.ACCEPTED
                || revokedAt == null) {
            throw violation(Reason.CANONICAL_INVALID);
        }
        MirrorDeploymentIsolationAttestationBundle stored = transactions.execute(status -> {
            Head head = selectHead(exact, true).orElseThrow(
                    () -> violation(Reason.STATUS_CONFLICT));
            requireDeployment(exact.deployment(), head.deployment());
            MirrorDeploymentIsolationAttestationBundle current = readCurrent(exact, head);
            if (!attestationMatches(current, currentExpected)) {
                throw violation(Reason.STATUS_CONFLICT);
            }
            if (!current.active()) {
                boolean exactRetry = current.status().material().reason() == exactReason
                        && (statusMatches(current, currentExpected)
                        || currentExpected.statusRevision() == 1
                        && currentExpected.statusFingerprint().equals(
                        current.status().material().previousStatusFingerprint()));
                if (exactRetry) {
                    return current;
                }
                throw violation(Reason.STATUS_CONFLICT);
            }
            if (!statusMatches(current, currentExpected)) {
                throw violation(Reason.STATUS_CONFLICT);
            }
            MirrorDeploymentIsolationAttestationStatusPublication revoked;
            try {
                revoked = bundleIntegrity.revokedStatus(
                        current.status(), exactReason, revokedAt);
            } catch (IllegalArgumentException invalid) {
                throw violation(Reason.CANONICAL_INVALID);
            }
            insertStatus(exact, revoked);
            int advanced = updateHeadForStatus(exact, head, revoked);
            if (advanced != 1) {
                throw violation(Reason.STATUS_CONFLICT);
            }
            return bundleIntegrity.bundle(current.scope(), current.authorityKeySetRef(),
                    current.attestation(), revoked);
        });
        if (stored == null) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        return stored;
    }

    @Override
    public Optional<MirrorDeploymentIsolationAttestationBundle> current(StreamIdentity stream) {
        StreamIdentity exact = Objects.requireNonNull(stream, "stream");
        return selectHead(exact, false).flatMap(head -> {
            requireDeployment(exact.deployment(), head.deployment());
            return head.floorRevision() == 0
                    ? Optional.empty() : Optional.of(readCurrent(exact, head));
        });
    }

    @Override
    public Optional<MirrorDeploymentIsolationAttestationBundle> current(
            StreamIdentity stream, CurrentExpectation expected) {
        CurrentExpectation exactExpected = Objects.requireNonNull(expected, "expected");
        return current(stream).filter(bundle ->
                attestationMatches(bundle, exactExpected)
                        && statusMatches(bundle, exactExpected));
    }

    private MirrorDeploymentIsolationAttestationBundle readCurrent(
            StreamIdentity stream, Head head) {
        if (head.floorRevision() < 1 || head.statusRevision() < 1
                || head.authorityGeneration() < 1) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        StoredAttestation stored = findAttestation(stream, head.floorRevision()).orElseThrow(
                () -> violation(Reason.STORED_STATE_CORRUPT));
        if (!stored.attestation().attestationFingerprint().equals(
                head.attestationFingerprint())
                || stored.authorityKeySetRef().revision() != head.authorityGeneration()
                || !stored.authorityKeySetRef().fingerprint().equals(
                head.authorityPublicationFingerprint())) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        MirrorDeploymentIsolationAttestationStatusPublication status = findStatus(
                stream, head.floorRevision(), head.statusRevision()).orElseThrow(
                        () -> violation(Reason.STORED_STATE_CORRUPT));
        if (!status.statusFingerprint().equals(head.statusFingerprint())) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        try {
            MirrorDeploymentIsolationAttestationBundle bundle = bundleIntegrity.bundle(
                    stream.scope(), stored.authorityKeySetRef(), stored.attestation(), status);
            if (!bundleIntegrity.canonicalBundleVerified(bundle)) {
                throw violation(Reason.STORED_STATE_CORRUPT);
            }
            return bundle;
        } catch (IllegalArgumentException invalid) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
    }

    private void ensureHead(StreamIdentity stream) {
        CapabilitySnapshot.Scope scope = stream.scope();
        var deployment = stream.deployment();
        try {
            jdbc.update("""
                            INSERT INTO mirror_isolation_attestation_heads (
                                tenant_id, organization_id, project_id, environment_id, region,
                                deployment_scope_id, key_set_id, attestation_id,
                                cluster_id, namespace_id, workload_name, service_account,
                                image_digest, floor_revision, floor_attestation_fingerprint,
                                authority_generation, authority_publication_fingerprint,
                                status_revision, status_fingerprint
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, '', 0, '', 0, '')
                            """,
                    scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environmentId(), scope.region(), deployment.deploymentScopeId(),
                    stream.keySetId(), stream.attestationId(), deployment.clusterId(),
                    deployment.namespace(), deployment.workloadName(), deployment.serviceAccount(),
                    deployment.imageDigest());
        } catch (DuplicateKeyException alreadyInitialized) {
            // The same transaction locks and verifies every immutable coordinate below.
        }
    }

    private void insertAttestation(
            StreamIdentity stream, MirrorDeploymentIsolationAttestationBundle bundle) {
        CapabilitySnapshot.Scope scope = stream.scope();
        var attestation = bundle.attestation();
        var authority = bundle.authorityKeySetRef();
        try {
            jdbc.update("""
                            INSERT INTO mirror_isolation_attestations (
                                tenant_id, organization_id, project_id, environment_id, region,
                                deployment_scope_id, key_set_id, attestation_id, revision,
                                attestation_fingerprint, material_fingerprint,
                                authority_generation, authority_publication_fingerprint,
                                schema_version, attestation_json
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environmentId(), scope.region(), stream.deployment().deploymentScopeId(),
                    stream.keySetId(), stream.attestationId(), attestation.material().revision(),
                    attestation.attestationFingerprint(), attestation.seal().materialFingerprint(),
                    authority.revision(), authority.fingerprint(), attestation.schemaVersion(),
                    serialize(attestation));
        } catch (DuplicateKeyException collision) {
            throw violation(Reason.CONTENT_ADDRESS_CONFLICT);
        }
    }

    private void insertStatus(
            StreamIdentity stream,
            MirrorDeploymentIsolationAttestationStatusPublication status) {
        CapabilitySnapshot.Scope scope = stream.scope();
        var material = status.material();
        try {
            jdbc.update("""
                            INSERT INTO mirror_isolation_attestation_statuses (
                                tenant_id, organization_id, project_id, environment_id, region,
                                deployment_scope_id, key_set_id, attestation_id,
                                attestation_revision, attestation_fingerprint,
                                status_revision, status_fingerprint,
                                previous_status_fingerprint, status_state, status_reason,
                                effective_at, schema_version, status_json
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environmentId(), scope.region(), stream.deployment().deploymentScopeId(),
                    stream.keySetId(), stream.attestationId(), material.attestationRef().revision(),
                    material.attestationRef().fingerprint(), material.statusRevision(),
                    status.statusFingerprint(), material.previousStatusFingerprint(),
                    material.state().name(), material.reason().name(),
                    material.effectiveAt().toString(), status.schemaVersion(), serialize(status));
        } catch (DuplicateKeyException collision) {
            throw violation(Reason.CONTENT_ADDRESS_CONFLICT);
        }
    }

    private int updateHeadForAttestation(
            StreamIdentity stream,
            Head head,
            MirrorDeploymentIsolationAttestationBundle bundle) {
        CapabilitySnapshot.Scope scope = stream.scope();
        var attestation = bundle.attestation();
        var authority = bundle.authorityKeySetRef();
        var status = bundle.status();
        return jdbc.update("""
                        UPDATE mirror_isolation_attestation_heads
                        SET floor_revision = ?, floor_attestation_fingerprint = ?,
                            authority_generation = ?, authority_publication_fingerprint = ?,
                            status_revision = ?, status_fingerprint = ?
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region = ? AND deployment_scope_id = ?
                          AND key_set_id = ? AND attestation_id = ?
                          AND floor_revision = ? AND floor_attestation_fingerprint = ?
                          AND status_revision = ? AND status_fingerprint = ?
                        """,
                attestation.material().revision(), attestation.attestationFingerprint(),
                authority.revision(), authority.fingerprint(),
                status.material().statusRevision(), status.statusFingerprint(),
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environmentId(),
                scope.region(), stream.deployment().deploymentScopeId(), stream.keySetId(),
                stream.attestationId(), head.floorRevision(), head.attestationFingerprint(),
                head.statusRevision(), head.statusFingerprint());
    }

    private int updateHeadForStatus(
            StreamIdentity stream,
            Head head,
            MirrorDeploymentIsolationAttestationStatusPublication status) {
        CapabilitySnapshot.Scope scope = stream.scope();
        return jdbc.update("""
                        UPDATE mirror_isolation_attestation_heads
                        SET status_revision = ?, status_fingerprint = ?
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region = ? AND deployment_scope_id = ?
                          AND key_set_id = ? AND attestation_id = ?
                          AND floor_revision = ? AND floor_attestation_fingerprint = ?
                          AND status_revision = ? AND status_fingerprint = ?
                        """,
                status.material().statusRevision(), status.statusFingerprint(),
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environmentId(),
                scope.region(), stream.deployment().deploymentScopeId(), stream.keySetId(),
                stream.attestationId(), head.floorRevision(), head.attestationFingerprint(),
                head.statusRevision(), head.statusFingerprint());
    }

    private Optional<Head> selectHead(StreamIdentity stream, boolean lock) {
        CapabilitySnapshot.Scope scope = stream.scope();
        List<Head> rows = jdbc.query(SELECT_HEAD + (lock ? " FOR UPDATE" : ""),
                (rs, rowNumber) -> new Head(deployment(
                        rs, stream.deployment().deploymentScopeId()),
                        rs.getLong("floor_revision"),
                        rs.getString("floor_attestation_fingerprint"),
                        rs.getLong("authority_generation"),
                        rs.getString("authority_publication_fingerprint"),
                        rs.getLong("status_revision"), rs.getString("status_fingerprint")),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), stream.deployment().deploymentScopeId(),
                stream.keySetId(), stream.attestationId());
        if (rows.size() > 1) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        return rows.stream().findFirst();
    }

    private Optional<StoredAttestation> findAttestation(
            StreamIdentity stream, long revision) {
        CapabilitySnapshot.Scope scope = stream.scope();
        List<StoredAttestation> rows = jdbc.query(SELECT_ATTESTATION,
                (rs, rowNumber) -> deserializeAttestation(rs, stream),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), stream.deployment().deploymentScopeId(),
                stream.keySetId(), stream.attestationId(), revision);
        if (rows.size() > 1) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        return rows.stream().findFirst();
    }

    private Optional<MirrorDeploymentIsolationAttestationStatusPublication> findStatus(
            StreamIdentity stream, long attestationRevision, long statusRevision) {
        CapabilitySnapshot.Scope scope = stream.scope();
        List<MirrorDeploymentIsolationAttestationStatusPublication> rows = jdbc.query(
                SELECT_STATUS, (rs, rowNumber) -> deserializeStatus(rs, stream),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), stream.deployment().deploymentScopeId(),
                stream.keySetId(), stream.attestationId(), attestationRevision, statusRevision);
        if (rows.size() > 1) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        return rows.stream().findFirst();
    }

    private StoredAttestation deserializeAttestation(
            ResultSet rs, StreamIdentity expected) throws SQLException {
        try {
            MirrorDeploymentIsolationAttestation attestation = mapper.readValue(
                    rs.getString("attestation_json"),
                    MirrorDeploymentIsolationAttestation.class);
            MirrorArtifactRef authorityRef = new MirrorArtifactRef(
                    MirrorDeploymentIsolationAuthorityKeySetPublication.ARTIFACT_KIND,
                    expected.keySetId(), rs.getLong("authority_generation"),
                    rs.getString("authority_publication_fingerprint"));
            if (!attestationIntegrity.canonicalFingerprintVerified(attestation)
                    || !attestation.material().deployment().equals(expected.deployment())
                    || !attestation.material().attestationId().equals(expected.attestationId())
                    || attestation.material().revision() != rs.getLong("revision")
                    || !attestation.attestationFingerprint().equals(
                    rs.getString("attestation_fingerprint"))
                    || !attestation.seal().materialFingerprint().equals(
                    rs.getString("material_fingerprint"))
                    || !attestation.schemaVersion().equals(rs.getString("schema_version"))) {
                throw violation(Reason.STORED_STATE_CORRUPT);
            }
            return new StoredAttestation(attestation, authorityRef);
        } catch (JsonProcessingException | IllegalArgumentException malformed) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
    }

    private MirrorDeploymentIsolationAttestationStatusPublication deserializeStatus(
            ResultSet rs, StreamIdentity expected) throws SQLException {
        try {
            var status = mapper.readValue(rs.getString("status_json"),
                    MirrorDeploymentIsolationAttestationStatusPublication.class);
            var material = status.material();
            if (!bundleIntegrity.canonicalStatusVerified(status)
                    || !material.scope().equals(expected.scope())
                    || !material.deployment().equals(expected.deployment())
                    || !material.authorityKeySetRef().id().equals(expected.keySetId())
                    || !material.attestationRef().id().equals(expected.attestationId())
                    || material.attestationRef().revision()
                    != rs.getLong("attestation_revision")
                    || !material.attestationRef().fingerprint().equals(
                    rs.getString("attestation_fingerprint"))
                    || material.statusRevision() != rs.getLong("status_revision")
                    || !status.statusFingerprint().equals(rs.getString("status_fingerprint"))
                    || !material.previousStatusFingerprint().equals(
                    rs.getString("previous_status_fingerprint"))
                    || !material.state().name().equals(rs.getString("status_state"))
                    || !material.reason().name().equals(rs.getString("status_reason"))
                    || !material.effectiveAt().toString().equals(rs.getString("effective_at"))
                    || !status.schemaVersion().equals(rs.getString("schema_version"))) {
                throw violation(Reason.STORED_STATE_CORRUPT);
            }
            return status;
        } catch (JsonProcessingException | IllegalArgumentException malformed) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw violation(Reason.CANONICAL_INVALID);
        }
    }

    private static boolean attestationMatches(
            MirrorDeploymentIsolationAttestationBundle bundle,
            CurrentExpectation expected) {
        return bundle.attestation().material().revision() == expected.attestationRevision()
                && bundle.attestation().attestationFingerprint().equals(
                expected.attestationFingerprint());
    }

    private static boolean statusMatches(
            MirrorDeploymentIsolationAttestationBundle bundle,
            CurrentExpectation expected) {
        return bundle.status().material().statusRevision() == expected.statusRevision()
                && bundle.status().statusFingerprint().equals(expected.statusFingerprint());
    }

    private static void requireDeployment(
            MirrorDeploymentIsolationAttestation.DeploymentIdentity expected,
            MirrorDeploymentIsolationAttestation.DeploymentIdentity indexed) {
        if (!expected.equals(indexed)) {
            throw violation(Reason.IDENTITY_MISMATCH);
        }
    }

    private static MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment(
            ResultSet rs, String deploymentScopeId) throws SQLException {
        return new MirrorDeploymentIsolationAttestation.DeploymentIdentity(
                deploymentScopeId, rs.getString("cluster_id"), rs.getString("namespace_id"),
                rs.getString("workload_name"), rs.getString("service_account"),
                rs.getString("image_digest"));
    }

    private static Violation violation(Reason reason) {
        return new Violation(reason);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record StoredAttestation(
            MirrorDeploymentIsolationAttestation attestation,
            MirrorArtifactRef authorityKeySetRef) {
    }

    private record Head(
            MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment,
            long floorRevision,
            String attestationFingerprint,
            long authorityGeneration,
            String authorityPublicationFingerprint,
            long statusRevision,
            String statusFingerprint) {
        private Head {
            deployment = Objects.requireNonNull(deployment, "deployment");
            attestationFingerprint = normalized(attestationFingerprint);
            authorityPublicationFingerprint = normalized(authorityPublicationFingerprint);
            statusFingerprint = normalized(statusFingerprint);
            boolean empty = floorRevision == 0 && attestationFingerprint.isBlank()
                    && authorityGeneration == 0 && authorityPublicationFingerprint.isBlank()
                    && statusRevision == 0 && statusFingerprint.isBlank();
            boolean populated = floorRevision > 0 && authorityGeneration > 0
                    && (statusRevision == 1 || statusRevision == 2)
                    && attestationFingerprint.matches("sha256:[a-f0-9]{64}")
                    && authorityPublicationFingerprint.matches("sha256:[a-f0-9]{64}")
                    && statusFingerprint.matches("sha256:[a-f0-9]{64}");
            if (!empty && !populated) {
                throw violation(Reason.STORED_STATE_CORRUPT);
            }
        }
    }
}
