package com.leanowtech.bloge.gateway.businessmirror.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.governance.DomainCapabilityPackageGovernanceProjection;
import com.leanowtech.bloge.gateway.businessmirror.governance.DomainCapabilityPackageGovernanceProjectionIntegrity;
import com.leanowtech.bloge.gateway.businessmirror.governance.PackageGovernanceProjectionRepository;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** H2/PostgreSQL append-only ANEKE Package governance projection log. */
public final class DatabasePackageGovernanceProjectionRepository
        implements PackageGovernanceProjectionRepository {
    private static final String SCOPE_COLUMNS = """
            tenant_id VARCHAR(255) NOT NULL,
            organization_id VARCHAR(255) NOT NULL,
            project_id VARCHAR(255) NOT NULL,
            environment_id VARCHAR(255) NOT NULL,
            region_id VARCHAR(128) NOT NULL,
            """;
    private static final String CREATE_HEADS = """
            CREATE TABLE IF NOT EXISTS business_mirror_package_governance_heads (
                %s
                package_id VARCHAR(512) NOT NULL,
                projection_id VARCHAR(512) NOT NULL,
                issuer VARCHAR(512) NOT NULL,
                external_generation BIGINT NOT NULL,
                projection_fingerprint VARCHAR(80) NOT NULL,
                PRIMARY KEY (tenant_id, organization_id, project_id, environment_id,
                             region_id, package_id)
            )
            """.formatted(SCOPE_COLUMNS);
    private static final String CREATE_PROJECTIONS = """
            CREATE TABLE IF NOT EXISTS business_mirror_package_governance_projections (
                %s
                package_id VARCHAR(512) NOT NULL,
                external_generation BIGINT NOT NULL,
                projection_id VARCHAR(512) NOT NULL,
                projection_revision BIGINT NOT NULL,
                projection_fingerprint VARCHAR(80) NOT NULL,
                package_snapshot_fingerprint VARCHAR(80) NOT NULL,
                registry_bundle_fingerprint VARCHAR(80) NOT NULL,
                evidence_index_fingerprint VARCHAR(80) NOT NULL,
                issuer VARCHAR(512) NOT NULL,
                expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                projection_json TEXT NOT NULL,
                PRIMARY KEY (tenant_id, organization_id, project_id, environment_id,
                             region_id, package_id, external_generation),
                UNIQUE (tenant_id, organization_id, project_id, environment_id,
                        region_id, projection_fingerprint)
            )
            """.formatted(SCOPE_COLUMNS);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final DomainCapabilityPackageGovernanceProjectionIntegrity integrity;
    private final TransactionTemplate transactions;
    private final TransactionTemplate initializationTransactions;

    public DatabasePackageGovernanceProjectionRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            DomainCapabilityPackageGovernanceProjectionIntegrity integrity,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.integrity = Objects.requireNonNull(integrity, "integrity");
        PlatformTransactionManager manager = Objects.requireNonNull(
                transactionManager, "transactionManager");
        this.transactions = new TransactionTemplate(manager);
        this.initializationTransactions = new TransactionTemplate(manager);
        this.initializationTransactions.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @PostConstruct
    public void init() {
        jdbc.execute(CREATE_HEADS);
        jdbc.execute(CREATE_PROJECTIONS);
    }

    @Override
    public AppendResult append(DomainCapabilityPackageGovernanceProjection projection) {
        DomainCapabilityPackageGovernanceProjection exact = Objects.requireNonNull(
                projection, "projection");
        if (!integrity.canonicalVerification(exact).verified()) {
            throw violation(Reason.CANONICAL_INVALID);
        }
        initializeHead(exact);
        AppendResult result = transactions.execute(status -> appendLocked(exact));
        if (result == null) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        return result;
    }

    private AppendResult appendLocked(
            DomainCapabilityPackageGovernanceProjection projection) {
        String packageId = projection.packageSnapshotRef().id();
        Head head = findHead(projection.scope(), packageId, true)
                .orElseThrow(() -> violation(Reason.STORED_STATE_CORRUPT));
        requireStream(head, projection);
        Optional<DomainCapabilityPackageGovernanceProjection> sameGeneration = findGeneration(
                projection.scope(), packageId, projection.externalGeneration());
        if (sameGeneration.isPresent()) {
            DomainCapabilityPackageGovernanceProjection stored = sameGeneration.orElseThrow();
            if (projection.externalGeneration() < head.externalGeneration()) {
                throw violation(Reason.GENERATION_ROLLBACK);
            }
            if (projection.externalGeneration() == head.externalGeneration()
                    && stored.projectionFingerprint().equals(projection.projectionFingerprint())
                    && head.projectionFingerprint().equals(projection.projectionFingerprint())) {
                return new AppendResult(stored, true);
            }
            throw violation(Reason.GENERATION_FORK);
        }
        requireSuccessor(head, projection.externalGeneration());
        try {
            insert(projection);
        } catch (DuplicateKeyException collision) {
            throw violation(Reason.CONTENT_ADDRESS_CONFLICT);
        }
        int advanced = jdbc.update("""
                        UPDATE business_mirror_package_governance_heads
                        SET external_generation = ?, projection_fingerprint = ?
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                          AND external_generation = ? AND projection_fingerprint = ?
                        """, projection.externalGeneration(), projection.projectionFingerprint(),
                projection.scope().tenantId(), projection.scope().organizationId(),
                projection.scope().projectId(), projection.scope().environmentId(),
                projection.scope().region(), packageId, head.externalGeneration(),
                head.projectionFingerprint());
        if (advanced != 1) {
            throw violation(Reason.GENERATION_FORK);
        }
        return new AppendResult(projection, false);
    }

    @Override
    public Optional<DomainCapabilityPackageGovernanceProjection> findCurrent(
            CapabilitySnapshot.Scope scope, String packageId) {
        CapabilitySnapshot.Scope exact = Objects.requireNonNull(scope, "scope");
        String id = required(packageId, "packageId");
        Optional<Head> head = findHead(exact, id, false);
        if (head.isEmpty() || head.orElseThrow().externalGeneration() == 0) {
            return Optional.empty();
        }
        DomainCapabilityPackageGovernanceProjection projection = findGeneration(
                exact, id, head.orElseThrow().externalGeneration())
                .orElseThrow(() -> violation(Reason.STORED_STATE_CORRUPT));
        requireStream(head.orElseThrow(), projection);
        if (!head.orElseThrow().projectionFingerprint()
                .equals(projection.projectionFingerprint())) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        return Optional.of(projection);
    }

    private void initializeHead(DomainCapabilityPackageGovernanceProjection projection) {
        String packageId = projection.packageSnapshotRef().id();
        if (findHead(projection.scope(), packageId, false).isPresent()) {
            return;
        }
        if (projection.externalGeneration() != 1) {
            throw violation(Reason.BOOTSTRAP_GENERATION_INVALID);
        }
        try {
            initializationTransactions.executeWithoutResult(status -> insertHead(projection));
        } catch (DuplicateKeyException alreadyInitialized) {
            // The committed head is locked and its immutable stream identity is checked below.
        }
    }

    private void insertHead(DomainCapabilityPackageGovernanceProjection projection) {
        CapabilitySnapshot.Scope scope = projection.scope();
        jdbc.update("""
                        INSERT INTO business_mirror_package_governance_heads (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            package_id, projection_id, issuer, external_generation,
                            projection_fingerprint
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, '')
                        """, scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), projection.packageSnapshotRef().id(),
                projection.projectionId(), projection.issuer());
    }

    private Optional<Head> findHead(
            CapabilitySnapshot.Scope scope, String packageId, boolean lock) {
        List<Head> rows = jdbc.query("""
                        SELECT projection_id, issuer, external_generation,
                               projection_fingerprint
                        FROM business_mirror_package_governance_heads
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                        """ + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Head(rs.getString("projection_id"),
                        rs.getString("issuer"), rs.getLong("external_generation"),
                        rs.getString("projection_fingerprint")),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), packageId);
        if (rows.size() > 1) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        return rows.stream().findFirst();
    }

    private Optional<DomainCapabilityPackageGovernanceProjection> findGeneration(
            CapabilitySnapshot.Scope scope, String packageId, long generation) {
        List<DomainCapabilityPackageGovernanceProjection> rows = jdbc.query("""
                        SELECT package_id, external_generation, projection_id,
                               projection_revision, projection_fingerprint,
                               package_snapshot_fingerprint, registry_bundle_fingerprint,
                               evidence_index_fingerprint, issuer, expires_at, projection_json
                        FROM business_mirror_package_governance_projections
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                          AND external_generation = ?
                        """, (rs, row) -> read(rs, scope), scope.tenantId(),
                scope.organizationId(), scope.projectId(), scope.environmentId(),
                scope.region(), packageId, generation);
        if (rows.size() > 1) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        return rows.stream().findFirst();
    }

    private void insert(DomainCapabilityPackageGovernanceProjection projection) {
        CapabilitySnapshot.Scope scope = projection.scope();
        jdbc.update("""
                        INSERT INTO business_mirror_package_governance_projections (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            package_id, external_generation, projection_id, projection_revision,
                            projection_fingerprint, package_snapshot_fingerprint,
                            registry_bundle_fingerprint, evidence_index_fingerprint, issuer,
                            expires_at, projection_json
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), projection.packageSnapshotRef().id(),
                projection.externalGeneration(), projection.projectionId(), projection.revision(),
                projection.projectionFingerprint(), projection.packageSnapshotRef().fingerprint(),
                projection.registryIngestBundleRef().fingerprint(),
                projection.evidenceIndexRef().fingerprint(), projection.issuer(),
                Timestamp.from(projection.expiresAt()), serialize(projection));
    }

    private DomainCapabilityPackageGovernanceProjection read(
            ResultSet rs, CapabilitySnapshot.Scope expectedScope) throws SQLException {
        try {
            DomainCapabilityPackageGovernanceProjection value = mapper.readValue(
                    rs.getString("projection_json"),
                    DomainCapabilityPackageGovernanceProjection.class);
            if (!integrity.canonicalVerification(value).verified()
                    || !value.scope().equals(expectedScope)
                    || !value.packageSnapshotRef().id().equals(rs.getString("package_id"))
                    || value.externalGeneration() != rs.getLong("external_generation")
                    || !value.projectionId().equals(rs.getString("projection_id"))
                    || value.revision() != rs.getLong("projection_revision")
                    || !value.projectionFingerprint()
                    .equals(rs.getString("projection_fingerprint"))
                    || !value.packageSnapshotRef().fingerprint()
                    .equals(rs.getString("package_snapshot_fingerprint"))
                    || !value.registryIngestBundleRef().fingerprint()
                    .equals(rs.getString("registry_bundle_fingerprint"))
                    || !value.evidenceIndexRef().fingerprint()
                    .equals(rs.getString("evidence_index_fingerprint"))
                    || !value.issuer().equals(rs.getString("issuer"))
                    || !value.expiresAt().equals(rs.getTimestamp("expires_at").toInstant())) {
                throw violation(Reason.STORED_STATE_CORRUPT);
            }
            return value;
        } catch (JsonProcessingException | IllegalArgumentException malformed) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
    }

    private String serialize(DomainCapabilityPackageGovernanceProjection projection) {
        try {
            return mapper.writeValueAsString(projection);
        } catch (JsonProcessingException failure) {
            throw violation(Reason.CANONICAL_INVALID);
        }
    }

    private static void requireStream(
            Head head, DomainCapabilityPackageGovernanceProjection projection) {
        if (!head.projectionId().equals(projection.projectionId())
                || !head.issuer().equals(projection.issuer())) {
            throw violation(Reason.STREAM_IDENTITY_MISMATCH);
        }
    }

    private static void requireSuccessor(Head head, long generation) {
        if (head.externalGeneration() == 0 && generation != 1) {
            throw violation(Reason.BOOTSTRAP_GENERATION_INVALID);
        }
        if (generation < head.externalGeneration()) {
            throw violation(Reason.GENERATION_ROLLBACK);
        }
        if (generation == head.externalGeneration()) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        if (generation > head.externalGeneration() + 1) {
            throw violation(Reason.GENERATION_GAP);
        }
    }

    private static String required(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!exact.matches("[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,511}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static Violation violation(Reason reason) {
        return new Violation(reason);
    }

    private record Head(
            String projectionId,
            String issuer,
            long externalGeneration,
            String projectionFingerprint) {
        private Head {
            projectionId = required(projectionId, "projectionId");
            issuer = required(issuer, "issuer");
            projectionFingerprint = projectionFingerprint == null
                    ? "" : projectionFingerprint.trim();
            if (externalGeneration < 0
                    || externalGeneration == 0 && !projectionFingerprint.isBlank()
                    || externalGeneration > 0
                    && !projectionFingerprint.matches("sha256:[a-f0-9]{64}")) {
                throw violation(Reason.STORED_STATE_CORRUPT);
            }
        }
    }
}
