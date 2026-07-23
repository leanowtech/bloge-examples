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
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Database-backed immutable authority-publication log with a durable per-stream CAS floor.
 *
 * <p>The floor row is initialized without advancing trust, then locked with
 * {@code SELECT ... FOR UPDATE}. Canonical publication insertion and a compare-and-set floor
 * advance commit in the same transaction. The full enterprise scope and immutable deployment
 * coordinates are indexed and rechecked against JSON on every read, preventing row movement,
 * scope aliasing, split views, and restart rollback.</p>
 */
public final class DatabaseMirrorDeploymentIsolationAuthorityPublicationRepository
        implements MirrorDeploymentIsolationAuthorityPublicationRepository {
    private static final String CREATE_FLOORS = """
            CREATE TABLE IF NOT EXISTS mirror_isolation_authority_trusted_floors (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                deployment_scope_id VARCHAR(512) NOT NULL,
                key_set_id VARCHAR(512) NOT NULL,
                cluster_id VARCHAR(512) NOT NULL,
                namespace_id VARCHAR(512) NOT NULL,
                workload_name VARCHAR(512) NOT NULL,
                service_account VARCHAR(512) NOT NULL,
                image_digest VARCHAR(71) NOT NULL,
                floor_generation BIGINT NOT NULL,
                floor_publication_fingerprint VARCHAR(71) NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region,
                    deployment_scope_id, key_set_id
                )
            )
            """;
    private static final String CREATE_PUBLICATIONS = """
            CREATE TABLE IF NOT EXISTS mirror_isolation_authority_publications (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                deployment_scope_id VARCHAR(512) NOT NULL,
                key_set_id VARCHAR(512) NOT NULL,
                generation BIGINT NOT NULL,
                publication_fingerprint VARCHAR(71) NOT NULL UNIQUE,
                material_fingerprint VARCHAR(71) NOT NULL,
                schema_version VARCHAR(128) NOT NULL,
                publication_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region,
                    deployment_scope_id, key_set_id, generation
                )
            )
            """;
    private static final String SELECT_FLOOR = """
            SELECT cluster_id, namespace_id, workload_name, service_account, image_digest,
                   floor_generation, floor_publication_fingerprint
            FROM mirror_isolation_authority_trusted_floors
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND deployment_scope_id = ?
              AND key_set_id = ?
            """;
    private static final String SELECT_PUBLICATION = """
            SELECT generation, publication_fingerprint, material_fingerprint,
                   schema_version, publication_json
            FROM mirror_isolation_authority_publications
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND deployment_scope_id = ?
              AND key_set_id = ? AND generation = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MirrorDeploymentIsolationAuthorityKeySetIntegrity integrity;
    private final TransactionTemplate transactions;

    /**
     * Creates the durable trusted-distribution repository.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @param mapper canonical protocol mapper
     * @param integrity canonical publication fingerprint verifier
     * @param transactionManager transaction manager shared by Mirror persistence
     */
    public DatabaseMirrorDeploymentIsolationAuthorityPublicationRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            MirrorDeploymentIsolationAuthorityKeySetIntegrity integrity,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.integrity = Objects.requireNonNull(integrity, "integrity");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    /** Creates additive immutable-publication and mutable-floor tables when absent. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_FLOORS);
        jdbc.execute(CREATE_PUBLICATIONS);
    }

    @Override
    public MirrorDeploymentIsolationAuthorityKeySetPublication append(
            MirrorDeploymentIsolationAuthorityKeySetPublication publication) {
        if (!integrity.canonicalFingerprintVerified(publication)) {
            throw violation(Reason.CANONICAL_INVALID);
        }
        StreamIdentity stream = StreamIdentity.from(publication);
        MirrorDeploymentIsolationAuthorityKeySetPublication stored = transactions.execute(
                status -> {
                    ensureFloor(stream);
                    return appendLocked(stream, publication);
                });
        if (stored == null) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        return stored;
    }

    private MirrorDeploymentIsolationAuthorityKeySetPublication appendLocked(
            StreamIdentity stream,
            MirrorDeploymentIsolationAuthorityKeySetPublication publication) {
        Head head = selectHead(stream, true).orElseThrow(
                () -> violation(Reason.STORED_STATE_CORRUPT));
        requireDeployment(stream.deployment(), head.deployment());
        if (head.generation() > 0) {
            MirrorDeploymentIsolationAuthorityKeySetPublication headPublication =
                    findGeneration(stream, head.generation()).orElseThrow(
                            () -> violation(Reason.STORED_STATE_CORRUPT));
            if (!headPublication.publicationFingerprint()
                    .equals(head.publicationFingerprint())) {
                throw violation(Reason.STORED_STATE_CORRUPT);
            }
        }
        Optional<MirrorDeploymentIsolationAuthorityKeySetPublication> sameGeneration =
                findGeneration(stream, publication.material().generation());
        if (sameGeneration.isPresent()) {
            if (publication.material().generation() < head.generation()) {
                throw violation(Reason.GENERATION_ROLLBACK);
            }
            if (publication.material().generation() == head.generation()
                    && sameGeneration.get().publicationFingerprint()
                    .equals(publication.publicationFingerprint())
                    && head.publicationFingerprint()
                    .equals(publication.publicationFingerprint())) {
                return sameGeneration.get();
            }
            if (publication.material().generation() > head.generation()
                    && sameGeneration.get().publicationFingerprint()
                    .equals(publication.publicationFingerprint())) {
                throw violation(Reason.STORED_STATE_CORRUPT);
            }
            throw violation(Reason.GENERATION_FORK);
        }
        requireSuccessor(head, publication);
        CapabilitySnapshot.Scope scope = stream.scope();
        try {
            jdbc.update("""
                            INSERT INTO mirror_isolation_authority_publications (
                                tenant_id, organization_id, project_id, environment_id, region,
                                deployment_scope_id, key_set_id, generation,
                                publication_fingerprint, material_fingerprint, schema_version,
                                publication_json
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environmentId(), scope.region(), stream.deployment().deploymentScopeId(),
                    stream.keySetId(), publication.material().generation(),
                    publication.publicationFingerprint(), publication.materialFingerprint(),
                    publication.schemaVersion(), serialize(publication));
        } catch (DuplicateKeyException collision) {
            throw violation(Reason.CONTENT_ADDRESS_CONFLICT);
        }
        int advanced = jdbc.update("""
                        UPDATE mirror_isolation_authority_trusted_floors
                        SET floor_generation = ?, floor_publication_fingerprint = ?
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region = ? AND deployment_scope_id = ?
                          AND key_set_id = ? AND floor_generation = ?
                          AND floor_publication_fingerprint = ?
                        """,
                publication.material().generation(), publication.publicationFingerprint(),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), stream.deployment().deploymentScopeId(),
                stream.keySetId(), head.generation(), head.publicationFingerprint());
        if (advanced != 1) {
            throw violation(Reason.GENERATION_FORK);
        }
        return publication;
    }

    @Override
    public Optional<MirrorDeploymentIsolationAuthorityKeySetPublication> latest(
            StreamIdentity stream) {
        StreamIdentity exact = Objects.requireNonNull(stream, "stream");
        Optional<Head> head = selectHead(exact, false);
        if (head.isEmpty() || head.get().generation() == 0) {
            return Optional.empty();
        }
        requireDeployment(exact.deployment(), head.get().deployment());
        MirrorDeploymentIsolationAuthorityKeySetPublication publication = findGeneration(
                exact, head.get().generation()).orElseThrow(
                        () -> violation(Reason.STORED_STATE_CORRUPT));
        if (!publication.publicationFingerprint()
                .equals(head.get().publicationFingerprint())) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        return Optional.of(publication);
    }

    @Override
    public Optional<MirrorDeploymentIsolationAuthorityKeySetPublication> current(
            StreamIdentity stream, long generation, String publicationFingerprint) {
        StreamIdentity exact = Objects.requireNonNull(stream, "stream");
        String fingerprint = normalized(publicationFingerprint);
        if (generation < 1 || !fingerprint.matches("sha256:[a-f0-9]{64}")) {
            return Optional.empty();
        }
        Optional<Head> head = selectHead(exact, false);
        if (head.isEmpty() || head.get().generation() != generation
                || !head.get().publicationFingerprint().equals(fingerprint)) {
            return Optional.empty();
        }
        requireDeployment(exact.deployment(), head.get().deployment());
        MirrorDeploymentIsolationAuthorityKeySetPublication publication = findGeneration(
                exact, generation).orElseThrow(
                        () -> violation(Reason.STORED_STATE_CORRUPT));
        if (!publication.publicationFingerprint().equals(fingerprint)) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        return Optional.of(publication);
    }

    @Override
    public Optional<MirrorDeploymentIsolationAuthorityKeySetIntegrity.TrustedFloor> floor(
            StreamIdentity stream) {
        StreamIdentity exact = Objects.requireNonNull(stream, "stream");
        return selectHead(exact, false).flatMap(head -> {
            requireDeployment(exact.deployment(), head.deployment());
            if (head.generation() == 0) {
                if (!head.publicationFingerprint().isBlank()) {
                    throw violation(Reason.STORED_STATE_CORRUPT);
                }
                return Optional.empty();
            }
            return Optional.of(new MirrorDeploymentIsolationAuthorityKeySetIntegrity.TrustedFloor(
                    exact.keySetId(), head.generation(), head.publicationFingerprint()));
        });
    }

    private void ensureFloor(StreamIdentity stream) {
        CapabilitySnapshot.Scope scope = stream.scope();
        MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment = stream.deployment();
        try {
            jdbc.update("""
                            INSERT INTO mirror_isolation_authority_trusted_floors (
                                tenant_id, organization_id, project_id, environment_id, region,
                                deployment_scope_id, key_set_id, cluster_id, namespace_id,
                                workload_name, service_account, image_digest,
                                floor_generation, floor_publication_fingerprint
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, '')
                            """,
                    scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environmentId(), scope.region(), deployment.deploymentScopeId(),
                    stream.keySetId(), deployment.clusterId(), deployment.namespace(),
                    deployment.workloadName(), deployment.serviceAccount(), deployment.imageDigest());
        } catch (DuplicateKeyException alreadyInitialized) {
            // The locked transaction rechecks all immutable deployment coordinates.
        }
    }

    private Optional<Head> selectHead(StreamIdentity stream, boolean lock) {
        CapabilitySnapshot.Scope scope = stream.scope();
        List<Head> rows = jdbc.query(SELECT_FLOOR + (lock ? " FOR UPDATE" : ""),
                (rs, rowNumber) -> new Head(deployment(rs, stream.deployment().deploymentScopeId()),
                        rs.getLong("floor_generation"),
                        rs.getString("floor_publication_fingerprint")),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), stream.deployment().deploymentScopeId(),
                stream.keySetId());
        if (rows.size() > 1) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        return rows.stream().findFirst();
    }

    private Optional<MirrorDeploymentIsolationAuthorityKeySetPublication> findGeneration(
            StreamIdentity stream, long generation) {
        CapabilitySnapshot.Scope scope = stream.scope();
        List<MirrorDeploymentIsolationAuthorityKeySetPublication> rows = jdbc.query(
                SELECT_PUBLICATION,
                (rs, rowNumber) -> deserialize(rs, stream),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), stream.deployment().deploymentScopeId(),
                stream.keySetId(), generation);
        if (rows.size() > 1) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        return rows.stream().findFirst();
    }

    private MirrorDeploymentIsolationAuthorityKeySetPublication deserialize(
            ResultSet rs, StreamIdentity expected) throws SQLException {
        try {
            MirrorDeploymentIsolationAuthorityKeySetPublication publication = mapper.readValue(
                    rs.getString("publication_json"),
                    MirrorDeploymentIsolationAuthorityKeySetPublication.class);
            if (!integrity.canonicalFingerprintVerified(publication)
                    || !StreamIdentity.from(publication).equals(expected)
                    || publication.material().generation() != rs.getLong("generation")
                    || !publication.publicationFingerprint().equals(
                    rs.getString("publication_fingerprint"))
                    || !publication.materialFingerprint().equals(
                    rs.getString("material_fingerprint"))
                    || !publication.schemaVersion().equals(rs.getString("schema_version"))) {
                throw violation(Reason.STORED_STATE_CORRUPT);
            }
            return publication;
        } catch (JsonProcessingException | IllegalArgumentException malformed) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
    }

    private static void requireSuccessor(
            Head head, MirrorDeploymentIsolationAuthorityKeySetPublication publication) {
        long generation = publication.material().generation();
        if (head.generation() == 0) {
            if (generation != 1) {
                throw violation(Reason.BOOTSTRAP_GENERATION_INVALID);
            }
            return;
        }
        if (generation < head.generation()) {
            throw violation(Reason.GENERATION_ROLLBACK);
        }
        if (generation == head.generation()) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        if (generation > head.generation() + 1) {
            throw violation(Reason.GENERATION_GAP);
        }
        if (!publication.material().previousPublicationFingerprint()
                .equals(head.publicationFingerprint())) {
            throw violation(Reason.PREDECESSOR_MISMATCH);
        }
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

    private String serialize(MirrorDeploymentIsolationAuthorityKeySetPublication publication) {
        try {
            return mapper.writeValueAsString(publication);
        } catch (JsonProcessingException failure) {
            throw violation(Reason.CANONICAL_INVALID);
        }
    }

    private static Violation violation(Reason reason) {
        return new Violation(reason);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record Head(
            MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment,
            long generation,
            String publicationFingerprint
    ) {
        private Head {
            deployment = Objects.requireNonNull(deployment, "deployment");
            publicationFingerprint = normalized(publicationFingerprint);
            if (generation < 0 || generation == 0 && !publicationFingerprint.isBlank()
                    || generation > 0
                    && !publicationFingerprint.matches("sha256:[a-f0-9]{64}")) {
                throw violation(Reason.STORED_STATE_CORRUPT);
            }
        }
    }
}
