package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * JDBC resumable staging registry with scope quotas and finalizer epoch fencing.
 *
 * <p>Every mutation locks one upload row in a short transaction. Chunk payloads are immutable,
 * content-addressed protocol documents. External selection-authority verification and population
 * admission occur only after {@link #claimFinalize(CapabilitySnapshot.Scope, String, String)}
 * returns and therefore never hold a staging row lock.</p>
 */
public final class
DatabaseAuthoritativeOutcomeSelectedPopulationUploadRepository
        implements AuthoritativeOutcomeSelectedPopulationUploadRepository {
    private static final Runnable NO_INITIALIZATION_PROBE =
            () -> {
            };
    private static final String CREATE_SCOPE_LOCKS = """
            CREATE TABLE IF NOT EXISTS mirror_outcome_population_upload_scope_locks (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region
                )
            )
            """;
    private static final String CREATE_UPLOADS = """
            CREATE TABLE IF NOT EXISTS mirror_outcome_population_uploads (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                upload_id VARCHAR(512) NOT NULL,
                request_fingerprint VARCHAR(71) NOT NULL,
                population_id VARCHAR(512) NOT NULL,
                population_revision BIGINT NOT NULL,
                request_json TEXT NOT NULL,
                status VARCHAR(32) NOT NULL,
                expected_chunk_count INTEGER NOT NULL,
                received_chunk_count INTEGER NOT NULL,
                received_bytes BIGINT NOT NULL,
                finalize_epoch BIGINT NOT NULL,
                finalize_owner VARCHAR(512) NOT NULL,
                finalize_lease_until TIMESTAMP WITH TIME ZONE,
                finalized_population_fingerprint VARCHAR(71) NOT NULL,
                admission_json TEXT,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, upload_id
                ),
                CONSTRAINT uq_mirror_outcome_population_upload_target UNIQUE (
                    tenant_id, organization_id, project_id,
                    environment_id, region, population_id,
                    population_revision
                )
            )
            """;
    private static final String CREATE_CHUNKS = """
            CREATE TABLE IF NOT EXISTS mirror_outcome_population_upload_chunks (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                upload_id VARCHAR(512) NOT NULL,
                chunk_index INTEGER NOT NULL,
                chunk_fingerprint VARCHAR(71) NOT NULL,
                encoded_bytes BIGINT NOT NULL,
                chunk_json TEXT NOT NULL,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, upload_id, chunk_index
                ),
                CONSTRAINT uq_mirror_outcome_population_upload_chunk UNIQUE (
                    tenant_id, organization_id, project_id,
                    environment_id, region, upload_id,
                    chunk_fingerprint
                ),
                CONSTRAINT fk_mirror_outcome_population_upload_chunk
                    FOREIGN KEY (
                        tenant_id, organization_id, project_id,
                        environment_id, region, upload_id
                    )
                    REFERENCES mirror_outcome_population_uploads (
                        tenant_id, organization_id, project_id,
                        environment_id, region, upload_id
                    )
                    ON DELETE CASCADE
            )
            """;
    private static final String ACTIVE_STATES =
            "('OPEN','FINALIZING')";
    private static final String TERMINAL_STATES =
            "('FINALIZED','ABORTED','EXPIRED')";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuthoritativeOutcomeSelectedPopulationUploadPolicy
            policy;
    private final TransactionTemplate mutations;
    private final TransactionTemplate lockRowInitialization;
    private final Supplier<Instant> databaseClock;
    private final Runnable beforeLockRowInsert;

    /** Creates a production repository using the database clock. */
    public DatabaseAuthoritativeOutcomeSelectedPopulationUploadRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            AuthoritativeOutcomeSelectedPopulationUploadPolicy
                    policy,
            PlatformTransactionManager transactionManager) {
        this(
                jdbc,
                mapper,
                policy,
                transactionManager,
                () -> databaseNow(jdbc),
                NO_INITIALIZATION_PROBE);
    }

    /** Deterministic clock seam for lifecycle and fencing tests. */
    DatabaseAuthoritativeOutcomeSelectedPopulationUploadRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            AuthoritativeOutcomeSelectedPopulationUploadPolicy
                    policy,
            PlatformTransactionManager transactionManager,
            Supplier<Instant> databaseClock) {
        this(
                jdbc,
                mapper,
                policy,
                transactionManager,
                databaseClock,
                NO_INITIALIZATION_PROBE);
    }

    /** Deterministic clock and scope-lock initialization seams for database certification. */
    DatabaseAuthoritativeOutcomeSelectedPopulationUploadRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            AuthoritativeOutcomeSelectedPopulationUploadPolicy
                    policy,
            PlatformTransactionManager transactionManager,
            Supplier<Instant> databaseClock,
            Runnable beforeLockRowInsert) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.policy = Objects.requireNonNull(
                policy, "policy");
        this.databaseClock = Objects.requireNonNull(
                databaseClock, "databaseClock");
        this.beforeLockRowInsert = Objects.requireNonNull(
                beforeLockRowInsert, "beforeLockRowInsert");
        DataSourceTransactionManager transactions =
                requireSavepointTransactions(
                        this.jdbc, transactionManager);
        mutations = new TransactionTemplate(transactions);
        mutations.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRED);
        mutations.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
        lockRowInitialization =
                new TransactionTemplate(transactions);
        lockRowInitialization.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_NESTED);
        lockRowInitialization.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Creates durable upload intent and immutable chunk staging tables. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_SCOPE_LOCKS);
        jdbc.execute(CREATE_UPLOADS);
        jdbc.execute(CREATE_CHUNKS);
    }

    @Override
    public Admission begin(
            AuthoritativeOutcomeSelectedPopulationUploadRequest
                    request) {
        AuthoritativeOutcomeSelectedPopulationUploadRequest exact =
                Objects.requireNonNull(request, "request");
        String fingerprint =
                exact.requestFingerprint(mapper);
        return required(
                mutations.execute(ignored -> {
                    CapabilitySnapshot.Scope scope =
                            exact.manifest().scope();
                    lockScope(scope);
                    Optional<StoredUpload> existing =
                            select(
                                    scope,
                                    exact.uploadId(),
                                    true);
                    if (existing.isPresent()) {
                        StoredUpload stored =
                                existing.orElseThrow();
                        if (!stored.requestFingerprint()
                                .equals(fingerprint)) {
                            throw violation(
                                    Reason.UPLOAD_CONFLICT);
                        }
                        return new Admission(
                                toUpload(stored).status(),
                                true);
                    }
                    if (selectByTarget(
                            scope,
                            exact.manifest().populationId(),
                            exact.manifest().revision())
                            .isPresent()) {
                        throw violation(
                                Reason.UPLOAD_CONFLICT);
                    }
                    long active = countActive(scope);
                    if (active
                            >= policy
                            .maximumActiveUploadsPerScope()) {
                        throw violation(
                                Reason
                                        .ACTIVE_UPLOAD_QUOTA_EXCEEDED);
                    }
                    Instant now = now();
                    try {
                        insertUpload(
                                exact,
                                fingerprint,
                                now);
                    } catch (DuplicateKeyException conflict) {
                        throw violation(
                                Reason.UPLOAD_CONFLICT);
                    }
                    StoredUpload created = select(
                            exact.manifest().scope(),
                            exact.uploadId(),
                            false).orElseThrow(() ->
                            violation(
                                    Reason
                                            .STORED_STATE_CORRUPT));
                    return new Admission(
                            toUpload(created).status(),
                            false);
                }),
                "upload begin");
    }

    @Override
    public ChunkAdmission stageChunk(
            CapabilitySnapshot.Scope scope,
            String uploadId,
            int chunkIndex,
            AuthoritativeOutcomeSelectedPopulationChunk chunk,
            long encodedBytes) {
        CapabilitySnapshot.Scope exactScope =
                Objects.requireNonNull(scope, "scope");
        String exactUploadId = identifier(uploadId);
        AuthoritativeOutcomeSelectedPopulationChunk exactChunk =
                Objects.requireNonNull(chunk, "chunk");
        if (encodedBytes < 1
                || encodedBytes
                > policy.maximumBytesPerUpload()) {
            throw violation(
                    Reason.UPLOAD_BYTE_QUOTA_EXCEEDED);
        }
        return required(
                mutations.execute(ignored -> {
                    lockScope(exactScope);
                    StoredUpload stored = requireLocked(
                            exactScope, exactUploadId);
                    stored = expireIfRequired(stored);
                    requireState(
                            stored,
                            AuthoritativeOutcomeSelectedPopulationUploadStatus
                                    .State.OPEN);
                    verifyChunk(
                            stored.request(),
                            chunkIndex,
                            exactChunk);
                    Optional<StoredChunk> existing =
                            selectChunk(
                                    exactScope,
                                    exactUploadId,
                                    chunkIndex);
                    if (existing.isPresent()) {
                        StoredChunk staged =
                                existing.orElseThrow();
                        if (!staged.fingerprint().equals(
                                exactChunk
                                        .chunkFingerprint())) {
                            throw violation(
                                    Reason.CHUNK_CONFLICT);
                        }
                        return new ChunkAdmission(
                                toUpload(stored).status(),
                                chunkIndex,
                                staged.fingerprint(),
                                true);
                    }
                    if (Math.addExact(
                            stored.receivedBytes(),
                            encodedBytes)
                            > policy.maximumBytesPerUpload()
                            || Math.addExact(
                            scopeStagedBytes(exactScope),
                            encodedBytes)
                            > policy
                            .maximumStagedBytesPerScope()) {
                        throw violation(
                                Reason
                                        .UPLOAD_BYTE_QUOTA_EXCEEDED);
                    }
                    try {
                        insertChunk(
                                exactScope,
                                exactUploadId,
                                chunkIndex,
                                exactChunk,
                                encodedBytes,
                                now());
                    } catch (DuplicateKeyException conflict) {
                        throw violation(
                                Reason.CHUNK_CONFLICT);
                    }
                    int updated = jdbc.update("""
                                    UPDATE mirror_outcome_population_uploads
                                    SET received_chunk_count =
                                            received_chunk_count + 1,
                                        received_bytes =
                                            received_bytes + ?,
                                        updated_at = ?
                                    WHERE tenant_id = ?
                                      AND organization_id = ?
                                      AND project_id = ?
                                      AND environment_id = ?
                                      AND region = ?
                                      AND upload_id = ?
                                      AND status = 'OPEN'
                                    """,
                            encodedBytes,
                            timestamp(now()),
                            exactScope.tenantId(),
                            exactScope.organizationId(),
                            exactScope.projectId(),
                            exactScope.environmentId(),
                            exactScope.region(),
                            exactUploadId);
                    if (updated != 1) {
                        throw violation(
                                Reason
                                        .STORED_STATE_CORRUPT);
                    }
                    StoredUpload current =
                            requireLocked(
                                    exactScope,
                                    exactUploadId);
                    return new ChunkAdmission(
                            toUpload(current).status(),
                            chunkIndex,
                            exactChunk.chunkFingerprint(),
                            false);
                }),
                "chunk staging");
    }

    @Override
    public Optional<Upload> find(
            CapabilitySnapshot.Scope scope,
            String uploadId) {
        CapabilitySnapshot.Scope exactScope =
                Objects.requireNonNull(scope, "scope");
        String exactUploadId = identifier(uploadId);
        return required(
                mutations.execute(ignored -> {
                    Optional<StoredUpload> stored =
                            select(
                                    exactScope,
                                    exactUploadId,
                                    true);
                    if (stored.isEmpty()) {
                        return Optional.empty();
                    }
                    return Optional.of(
                            toUpload(
                                    expireIfRequired(
                                            stored.orElseThrow())));
                }),
                "upload status read");
    }

    @Override
    public FinalizationClaim claimFinalize(
            CapabilitySnapshot.Scope scope,
            String uploadId,
            String owner) {
        CapabilitySnapshot.Scope exactScope =
                Objects.requireNonNull(scope, "scope");
        String exactUploadId = identifier(uploadId);
        String exactOwner = identifier(owner);
        return required(
                mutations.execute(ignored -> {
                    StoredUpload stored = expireIfRequired(
                            requireLocked(
                                    exactScope,
                                    exactUploadId));
                    if (stored.state()
                            == AuthoritativeOutcomeSelectedPopulationUploadStatus
                            .State.FINALIZED) {
                        return new FinalizationClaim(
                                toUpload(stored),
                                "",
                                stored.finalizeEpoch(),
                                Instant.EPOCH,
                                List.of(),
                                false);
                    }
                    if (stored.state()
                            == AuthoritativeOutcomeSelectedPopulationUploadStatus
                            .State.EXPIRED) {
                        throw violation(
                                Reason.UPLOAD_EXPIRED);
                    }
                    if (stored.state()
                            != AuthoritativeOutcomeSelectedPopulationUploadStatus
                            .State.OPEN
                            && stored.state()
                            != AuthoritativeOutcomeSelectedPopulationUploadStatus
                            .State.FINALIZING) {
                        throw violation(
                                Reason.UPLOAD_NOT_OPEN);
                    }
                    if (stored.receivedChunkCount()
                            != stored.expectedChunkCount()) {
                        throw violation(
                                Reason.UPLOAD_INCOMPLETE);
                    }
                    Instant now = now();
                    if (stored.state()
                            == AuthoritativeOutcomeSelectedPopulationUploadStatus
                            .State.FINALIZING
                            && stored.finalizeLeaseUntil()
                            .isAfter(now)) {
                        throw violation(
                                Reason.FINALIZATION_BUSY);
                    }
                    long epoch = Math.addExact(
                            stored.finalizeEpoch(), 1);
                    Instant leaseUntil = now.plus(
                            policy.finalizationLease());
                    int updated = jdbc.update("""
                                    UPDATE mirror_outcome_population_uploads
                                    SET status = 'FINALIZING',
                                        finalize_epoch = ?,
                                        finalize_owner = ?,
                                        finalize_lease_until = ?,
                                        updated_at = ?
                                    WHERE tenant_id = ?
                                      AND organization_id = ?
                                      AND project_id = ?
                                      AND environment_id = ?
                                      AND region = ?
                                      AND upload_id = ?
                                      AND finalize_epoch = ?
                                    """,
                            epoch,
                            exactOwner,
                            timestamp(leaseUntil),
                            timestamp(now),
                            exactScope.tenantId(),
                            exactScope.organizationId(),
                            exactScope.projectId(),
                            exactScope.environmentId(),
                            exactScope.region(),
                            exactUploadId,
                            stored.finalizeEpoch());
                    if (updated != 1) {
                        throw violation(
                                Reason.FINALIZATION_FENCED);
                    }
                    StoredUpload claimed =
                            requireLocked(
                                    exactScope,
                                    exactUploadId);
                    List<AuthoritativeOutcomeSelectedPopulationChunk>
                            chunks = readChunks(
                            exactScope, exactUploadId);
                    if (chunks.size()
                            != claimed.expectedChunkCount()) {
                        throw violation(
                                Reason
                                        .STORED_STATE_CORRUPT);
                    }
                    return new FinalizationClaim(
                            toUpload(claimed),
                            exactOwner,
                            epoch,
                            leaseUntil,
                            chunks,
                            true);
                }),
                "upload finalization claim");
    }

    @Override
    public Upload completeFinalize(
            FinalizationClaim claim,
            AuthoritativeOutcomeSelectedPopulationAdmission
                    admission) {
        FinalizationClaim exactClaim =
                Objects.requireNonNull(claim, "claim");
        AuthoritativeOutcomeSelectedPopulationAdmission
                exactAdmission = Objects.requireNonNull(
                admission, "admission");
        if (!exactClaim.requiresExecution()) {
            return exactClaim.upload();
        }
        CapabilitySnapshot.Scope scope =
                exactClaim.upload().request()
                        .manifest().scope();
        String uploadId =
                exactClaim.upload().request()
                        .uploadId();
        return required(
                mutations.execute(ignored -> {
                    StoredUpload stored =
                            requireLocked(scope, uploadId);
                    Instant now = now();
                    if (stored.state()
                            != AuthoritativeOutcomeSelectedPopulationUploadStatus
                            .State.FINALIZING
                            || stored.finalizeEpoch()
                            != exactClaim.epoch()
                            || !stored.finalizeOwner()
                            .equals(exactClaim.owner())
                            || !stored.finalizeLeaseUntil()
                            .isAfter(now)) {
                        throw violation(
                                Reason.FINALIZATION_FENCED);
                    }
                    verifyAdmission(
                            stored.request(),
                            exactClaim.chunks(),
                            exactAdmission);
                    String populationFingerprint =
                            exactAdmission.population()
                                    .manifest()
                                    .manifestFingerprint();
                    int updated = jdbc.update("""
                                    UPDATE mirror_outcome_population_uploads
                                    SET status = 'FINALIZED',
                                        finalize_owner = '',
                                        finalize_lease_until = NULL,
                                        finalized_population_fingerprint = ?,
                                        admission_json = ?,
                                        updated_at = ?
                                    WHERE tenant_id = ?
                                      AND organization_id = ?
                                      AND project_id = ?
                                      AND environment_id = ?
                                      AND region = ?
                                      AND upload_id = ?
                                      AND finalize_epoch = ?
                                      AND finalize_owner = ?
                                      AND status = 'FINALIZING'
                                    """,
                            populationFingerprint,
                            write(exactAdmission),
                            timestamp(now),
                            scope.tenantId(),
                            scope.organizationId(),
                            scope.projectId(),
                            scope.environmentId(),
                            scope.region(),
                            uploadId,
                            exactClaim.epoch(),
                            exactClaim.owner());
                    if (updated != 1) {
                        throw violation(
                                Reason.FINALIZATION_FENCED);
                    }
                    return toUpload(
                            requireLocked(
                                    scope, uploadId));
                }),
                "upload finalization completion");
    }

    @Override
    public Upload abort(
            CapabilitySnapshot.Scope scope,
            String uploadId) {
        CapabilitySnapshot.Scope exactScope =
                Objects.requireNonNull(scope, "scope");
        String exactUploadId = identifier(uploadId);
        return required(
                mutations.execute(ignored -> {
                    StoredUpload stored = expireIfRequired(
                            requireLocked(
                                    exactScope,
                                    exactUploadId));
                    requireState(
                            stored,
                            AuthoritativeOutcomeSelectedPopulationUploadStatus
                                    .State.OPEN);
                    destroyChunks(
                            exactScope, exactUploadId);
                    jdbc.update("""
                                    UPDATE mirror_outcome_population_uploads
                                    SET status = 'ABORTED',
                                        received_chunk_count = 0,
                                        received_bytes = 0,
                                        updated_at = ?
                                    WHERE tenant_id = ?
                                      AND organization_id = ?
                                      AND project_id = ?
                                      AND environment_id = ?
                                      AND region = ?
                                      AND upload_id = ?
                                    """,
                            timestamp(now()),
                            exactScope.tenantId(),
                            exactScope.organizationId(),
                            exactScope.projectId(),
                            exactScope.environmentId(),
                            exactScope.region(),
                            exactUploadId);
                    return toUpload(
                            requireLocked(
                                    exactScope,
                                    exactUploadId));
                }),
                "upload abort");
    }

    @Override
    public int expireAndPurge(int limit) {
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException(
                    "upload cleanup limit must be between 1 and 10000");
        }
        return required(
                mutations.execute(ignored -> {
                    Instant now = now();
                    List<UploadKey> expired =
                            jdbc.query("""
                                            SELECT tenant_id,
                                                   organization_id,
                                                   project_id,
                                                   environment_id,
                                                   region,
                                                   upload_id
                                            FROM mirror_outcome_population_uploads
                                            WHERE status IN %s
                                              AND expires_at <= ?
                                              AND (
                                                  status = 'OPEN'
                                                  OR finalize_lease_until <= ?
                                              )
                                            ORDER BY expires_at, upload_id
                                            LIMIT ?
                                            """.formatted(
                                            ACTIVE_STATES),
                                    (row, ignoredRow) ->
                                            key(row),
                                    timestamp(now),
                                    timestamp(now),
                                    limit);
                    int transitioned = 0;
                    for (UploadKey key : expired) {
                        Optional<StoredUpload> current =
                                select(
                                        key.scope(),
                                        key.uploadId(),
                                        true);
                        if (current.isEmpty()) {
                            continue;
                        }
                        StoredUpload before =
                                current.orElseThrow();
                        StoredUpload after =
                                expireIfRequired(before);
                        if (before.state()
                                != AuthoritativeOutcomeSelectedPopulationUploadStatus
                                .State.EXPIRED
                                && after.state()
                                == AuthoritativeOutcomeSelectedPopulationUploadStatus
                                .State.EXPIRED) {
                            transitioned++;
                        }
                    }
                    if (transitioned < limit) {
                        purgeTerminals(
                                now.minus(
                                        policy
                                                .terminalRetention()),
                                limit - transitioned);
                    }
                    return transitioned;
                }),
                "upload cleanup");
    }

    private void insertUpload(
            AuthoritativeOutcomeSelectedPopulationUploadRequest
                    request,
            String fingerprint,
            Instant createdAt) {
        CapabilitySnapshot.Scope scope =
                request.manifest().scope();
        jdbc.update("""
                        INSERT INTO mirror_outcome_population_uploads (
                            tenant_id, organization_id, project_id,
                            environment_id, region, upload_id,
                            request_fingerprint, population_id,
                            population_revision, request_json, status,
                            expected_chunk_count, received_chunk_count,
                            received_bytes, finalize_epoch,
                            finalize_owner, finalize_lease_until,
                            finalized_population_fingerprint,
                            admission_json, created_at, updated_at,
                            expires_at
                        ) VALUES (
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN',
                            ?, 0, 0, 0, '', NULL, '', NULL, ?, ?, ?
                        )
                        """,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                request.uploadId(),
                fingerprint,
                request.manifest().populationId(),
                request.manifest().revision(),
                write(request),
                request.manifest().chunks().size(),
                timestamp(createdAt),
                timestamp(createdAt),
                timestamp(createdAt.plus(
                        policy.uploadTtl())));
    }

    private void insertChunk(
            CapabilitySnapshot.Scope scope,
            String uploadId,
            int chunkIndex,
            AuthoritativeOutcomeSelectedPopulationChunk chunk,
            long encodedBytes,
            Instant createdAt) {
        jdbc.update("""
                        INSERT INTO mirror_outcome_population_upload_chunks (
                            tenant_id, organization_id, project_id,
                            environment_id, region, upload_id,
                            chunk_index, chunk_fingerprint,
                            encoded_bytes, chunk_json, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                uploadId,
                chunkIndex,
                chunk.chunkFingerprint(),
                encodedBytes,
                write(chunk),
                timestamp(createdAt));
    }

    private Optional<StoredUpload> select(
            CapabilitySnapshot.Scope scope,
            String uploadId,
            boolean forUpdate) {
        List<StoredUpload> values = jdbc.query(
                """
                        SELECT *
                        FROM mirror_outcome_population_uploads
                        WHERE tenant_id = ?
                          AND organization_id = ?
                          AND project_id = ?
                          AND environment_id = ?
                          AND region = ?
                          AND upload_id = ?
                        """ + (forUpdate ? " FOR UPDATE" : ""),
                (row, ignored) -> stored(row),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                uploadId);
        if (values.size() > 1) {
            throw violation(
                    Reason.STORED_STATE_CORRUPT);
        }
        return values.stream().findFirst();
    }

    private Optional<StoredUpload> selectByTarget(
            CapabilitySnapshot.Scope scope,
            String populationId,
            long populationRevision) {
        List<StoredUpload> values = jdbc.query(
                """
                        SELECT *
                        FROM mirror_outcome_population_uploads
                        WHERE tenant_id = ?
                          AND organization_id = ?
                          AND project_id = ?
                          AND environment_id = ?
                          AND region = ?
                          AND population_id = ?
                          AND population_revision = ?
                        """,
                (row, ignored) -> stored(row),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                populationId,
                populationRevision);
        if (values.size() > 1) {
            throw violation(
                    Reason.STORED_STATE_CORRUPT);
        }
        return values.stream().findFirst();
    }

    private StoredUpload requireLocked(
            CapabilitySnapshot.Scope scope,
            String uploadId) {
        return select(scope, uploadId, true)
                .orElseThrow(() ->
                        violation(
                                Reason.UPLOAD_NOT_FOUND));
    }

    private Optional<StoredChunk> selectChunk(
            CapabilitySnapshot.Scope scope,
            String uploadId,
            int chunkIndex) {
        return jdbc.query("""
                                SELECT chunk_fingerprint,
                                       encoded_bytes
                                FROM mirror_outcome_population_upload_chunks
                                WHERE tenant_id = ?
                                  AND organization_id = ?
                                  AND project_id = ?
                                  AND environment_id = ?
                                  AND region = ?
                                  AND upload_id = ?
                                  AND chunk_index = ?
                                """,
                        (row, ignored) ->
                                new StoredChunk(
                                        row.getString(
                                                "chunk_fingerprint"),
                                        row.getLong(
                                                "encoded_bytes")),
                        scope.tenantId(),
                        scope.organizationId(),
                        scope.projectId(),
                        scope.environmentId(),
                        scope.region(),
                        uploadId,
                        chunkIndex)
                .stream()
                .findFirst();
    }

    private List<AuthoritativeOutcomeSelectedPopulationChunk>
    readChunks(
            CapabilitySnapshot.Scope scope,
            String uploadId) {
        return jdbc.query("""
                        SELECT chunk_index, chunk_json
                        FROM mirror_outcome_population_upload_chunks
                        WHERE tenant_id = ?
                          AND organization_id = ?
                          AND project_id = ?
                          AND environment_id = ?
                          AND region = ?
                          AND upload_id = ?
                        ORDER BY chunk_index
                        """,
                (row, index) -> {
                    if (row.getInt("chunk_index")
                            != index) {
                        throw violation(
                                Reason
                                        .STORED_STATE_CORRUPT);
                    }
                    return read(
                            row.getString("chunk_json"),
                            AuthoritativeOutcomeSelectedPopulationChunk
                                    .class);
                },
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                uploadId);
    }

    private Upload toUpload(StoredUpload stored) {
        Set<Integer> indexes =
                new HashSet<>(jdbc.query("""
                                SELECT chunk_index
                                FROM mirror_outcome_population_upload_chunks
                                WHERE tenant_id = ?
                                  AND organization_id = ?
                                  AND project_id = ?
                                  AND environment_id = ?
                                  AND region = ?
                                  AND upload_id = ?
                                """,
                        (row, ignored) ->
                                row.getInt("chunk_index"),
                        stored.scope().tenantId(),
                        stored.scope().organizationId(),
                        stored.scope().projectId(),
                        stored.scope().environmentId(),
                        stored.scope().region(),
                        stored.request().uploadId()));
        if (indexes.size()
                != stored.receivedChunkCount()) {
            throw violation(
                    Reason.STORED_STATE_CORRUPT);
        }
        int nextMissing = -1;
        for (int index = 0;
             index < stored.expectedChunkCount();
             index++) {
            if (!indexes.contains(index)) {
                nextMissing = index;
                break;
            }
        }
        AuthoritativeOutcomeSelectedPopulationUploadStatus
                status =
                new AuthoritativeOutcomeSelectedPopulationUploadStatus(
                        "",
                        stored.request().uploadId(),
                        stored.requestFingerprint(),
                        stored.request().manifest()
                                .populationId(),
                        stored.request().manifest().revision(),
                        stored.state(),
                        stored.expectedChunkCount(),
                        stored.receivedChunkCount(),
                        stored.receivedBytes(),
                        nextMissing,
                        stored.finalizeEpoch(),
                        stored.createdAt(),
                        stored.updatedAt(),
                        stored.expiresAt(),
                        stored.state()
                                == AuthoritativeOutcomeSelectedPopulationUploadStatus
                                .State.FINALIZING
                                ? stored.finalizeLeaseUntil()
                                : Instant.EPOCH,
                        stored.finalizedPopulationFingerprint());
        Optional<AuthoritativeOutcomeSelectedPopulationAdmission>
                admission = stored.admissionJson() == null
                || stored.admissionJson().isBlank()
                ? Optional.empty()
                : Optional.of(read(
                stored.admissionJson(),
                AuthoritativeOutcomeSelectedPopulationAdmission
                        .class));
        return new Upload(
                stored.request(),
                status,
                admission);
    }

    private StoredUpload stored(ResultSet row)
            throws SQLException {
        CapabilitySnapshot.Scope scope =
                new CapabilitySnapshot.Scope(
                        row.getString("tenant_id"),
                        row.getString("organization_id"),
                        row.getString("project_id"),
                        row.getString("environment_id"),
                        row.getString("region"));
        AuthoritativeOutcomeSelectedPopulationUploadRequest
                request = read(
                row.getString("request_json"),
                AuthoritativeOutcomeSelectedPopulationUploadRequest
                        .class);
        if (!request.manifest().scope().equals(scope)
                || !request.uploadId().equals(
                row.getString("upload_id"))
                || !request.manifest().populationId()
                .equals(row.getString(
                        "population_id"))
                || request.manifest().revision()
                != row.getLong("population_revision")
                || !request.requestFingerprint(mapper)
                .equals(row.getString(
                        "request_fingerprint"))) {
            throw violation(
                    Reason.STORED_STATE_CORRUPT);
        }
        Timestamp lease =
                row.getTimestamp(
                        "finalize_lease_until");
        return new StoredUpload(
                scope,
                request,
                row.getString(
                        "request_fingerprint"),
                AuthoritativeOutcomeSelectedPopulationUploadStatus
                        .State.valueOf(
                                row.getString("status")),
                row.getInt(
                        "expected_chunk_count"),
                row.getInt(
                        "received_chunk_count"),
                row.getLong("received_bytes"),
                row.getLong("finalize_epoch"),
                row.getString("finalize_owner"),
                lease == null
                        ? Instant.EPOCH
                        : lease.toInstant(),
                row.getString(
                        "finalized_population_fingerprint"),
                row.getString("admission_json"),
                row.getTimestamp("created_at")
                        .toInstant(),
                row.getTimestamp("updated_at")
                        .toInstant(),
                row.getTimestamp("expires_at")
                        .toInstant());
    }

    private StoredUpload expireIfRequired(
            StoredUpload stored) {
        Instant now = now();
        if (!stored.expiresAt().isAfter(now)
                && (stored.state()
                == AuthoritativeOutcomeSelectedPopulationUploadStatus
                .State.OPEN
                || stored.state()
                == AuthoritativeOutcomeSelectedPopulationUploadStatus
                .State.FINALIZING
                && !stored.finalizeLeaseUntil()
                .isAfter(now))) {
            destroyChunks(
                    stored.scope(),
                    stored.request().uploadId());
            jdbc.update("""
                            UPDATE mirror_outcome_population_uploads
                            SET status = 'EXPIRED',
                                received_chunk_count = 0,
                                received_bytes = 0,
                                finalize_owner = '',
                                finalize_lease_until = NULL,
                                updated_at = ?
                            WHERE tenant_id = ?
                              AND organization_id = ?
                              AND project_id = ?
                              AND environment_id = ?
                              AND region = ?
                              AND upload_id = ?
                            """,
                    timestamp(now),
                    stored.scope().tenantId(),
                    stored.scope().organizationId(),
                    stored.scope().projectId(),
                    stored.scope().environmentId(),
                    stored.scope().region(),
                    stored.request().uploadId());
            return requireLocked(
                    stored.scope(),
                    stored.request().uploadId());
        }
        return stored;
    }

    private void verifyChunk(
            AuthoritativeOutcomeSelectedPopulationUploadRequest
                    request,
            int chunkIndex,
            AuthoritativeOutcomeSelectedPopulationChunk chunk) {
        try {
            chunk.verify(mapper);
            if (chunkIndex < 0
                    || chunkIndex
                    >= request.manifest()
                    .chunks().size()
                    || chunk.chunkIndex()
                    != chunkIndex
                    || !request.manifest().chunks()
                    .get(chunkIndex)
                    .chunkRef().equals(
                            chunk.artifactRef())
                    || !chunk.populationId()
                    .equals(request.manifest()
                            .populationId())
                    || chunk.populationRevision()
                    != request.manifest().revision()
                    || !chunk.scope().equals(
                    request.manifest().scope())
                    || !chunk.inventoryRef().equals(
                    request.manifest()
                            .inventoryRef())
                    || !chunk.cohortRef().equals(
                    request.manifest()
                            .cohortRef())
                    || !chunk.samplingFrameRef().equals(
                    request.manifest()
                            .samplingFrameRef())
                    || !chunk.selectedAt().equals(
                    request.manifest()
                            .selectedAt())) {
                throw violation(
                        Reason.CHUNK_INVALID);
            }
        } catch (Violation expected) {
            throw expected;
        } catch (RuntimeException invalid) {
            throw violation(
                    Reason.CHUNK_INVALID);
        }
    }

    private void verifyAdmission(
            AuthoritativeOutcomeSelectedPopulationUploadRequest
                    request,
            List<AuthoritativeOutcomeSelectedPopulationChunk>
                    chunks,
            AuthoritativeOutcomeSelectedPopulationAdmission
                    admission) {
        try {
            AuthoritativeOutcomeSelectedPopulationBundle
                    population = admission.population();
            if (!population.predecessorFingerprint()
                    .equals(request
                            .expectedPredecessorFingerprint())
                    || !population.chunks().equals(chunks)
                    || !population.manifest()
                    .ingestionMaterialFingerprint(mapper)
                    .equals(request.manifest()
                            .ingestionMaterialFingerprint(
                                    mapper))) {
                throw violation(
                        Reason.UPLOAD_CONFLICT);
            }
        } catch (Violation expected) {
            throw expected;
        } catch (RuntimeException invalid) {
            throw violation(
                    Reason.UPLOAD_CONFLICT);
        }
    }

    private void requireState(
            StoredUpload stored,
            AuthoritativeOutcomeSelectedPopulationUploadStatus.State
                    expected) {
        if (stored.state()
                == AuthoritativeOutcomeSelectedPopulationUploadStatus
                .State.EXPIRED) {
            throw violation(
                    Reason.UPLOAD_EXPIRED);
        }
        if (stored.state() != expected) {
            throw violation(
                    Reason.UPLOAD_NOT_OPEN);
        }
    }

    private void lockScope(
            CapabilitySnapshot.Scope scope) {
        Long existing = jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM mirror_outcome_population_upload_scope_locks
                        WHERE tenant_id = ?
                          AND organization_id = ?
                          AND project_id = ?
                          AND environment_id = ?
                          AND region = ?
                        """,
                Long.class,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region());
        if (existing == null || existing == 0) {
            beforeLockRowInsert.run();
            try {
                lockRowInitialization.executeWithoutResult(
                        ignored -> jdbc.update("""
                                        INSERT INTO mirror_outcome_population_upload_scope_locks (
                                            tenant_id, organization_id,
                                            project_id, environment_id,
                                            region
                                        ) VALUES (?, ?, ?, ?, ?)
                                        """,
                                scope.tenantId(),
                                scope.organizationId(),
                                scope.projectId(),
                                scope.environmentId(),
                                scope.region()));
            } catch (DuplicateKeyException exists) {
                // A concurrent initializer won; savepoint rollback preserves this transaction.
            }
        }
        jdbc.queryForObject("""
                        SELECT tenant_id
                        FROM mirror_outcome_population_upload_scope_locks
                        WHERE tenant_id = ?
                          AND organization_id = ?
                          AND project_id = ?
                          AND environment_id = ?
                          AND region = ?
                        FOR UPDATE
                        """,
                String.class,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region());
    }

    private long countActive(
            CapabilitySnapshot.Scope scope) {
        Long count = jdbc.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM mirror_outcome_population_uploads
                        WHERE tenant_id = ?
                          AND organization_id = ?
                          AND project_id = ?
                          AND environment_id = ?
                          AND region = ?
                          AND status IN %s
                        """.formatted(ACTIVE_STATES),
                Long.class,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region());
        return count == null ? 0 : count;
    }

    private long scopeStagedBytes(
            CapabilitySnapshot.Scope scope) {
        Long count = jdbc.queryForObject(
                """
                        SELECT COALESCE(SUM(received_bytes), 0)
                        FROM mirror_outcome_population_uploads
                        WHERE tenant_id = ?
                          AND organization_id = ?
                          AND project_id = ?
                          AND environment_id = ?
                          AND region = ?
                          AND status IN %s
                        """.formatted(ACTIVE_STATES),
                Long.class,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region());
        return count == null ? 0 : count;
    }

    private void destroyChunks(
            CapabilitySnapshot.Scope scope,
            String uploadId) {
        jdbc.update("""
                        DELETE FROM mirror_outcome_population_upload_chunks
                        WHERE tenant_id = ?
                          AND organization_id = ?
                          AND project_id = ?
                          AND environment_id = ?
                          AND region = ?
                          AND upload_id = ?
                        """,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                uploadId);
    }

    private void purgeTerminals(
            Instant cutoff,
            int limit) {
        List<UploadKey> values = jdbc.query("""
                                SELECT tenant_id,
                                       organization_id,
                                       project_id,
                                       environment_id,
                                       region,
                                       upload_id
                                FROM mirror_outcome_population_uploads
                                WHERE status IN %s
                                  AND updated_at < ?
                                ORDER BY updated_at, upload_id
                                LIMIT ?
                                """.formatted(
                                TERMINAL_STATES),
                        (row, ignored) -> key(row),
                        timestamp(cutoff),
                        limit);
        for (UploadKey value : values) {
            Optional<StoredUpload> current =
                    select(
                            value.scope(),
                            value.uploadId(),
                            true);
            if (current.isEmpty()
                    || !terminal(
                    current.orElseThrow().state())
                    || !current.orElseThrow()
                    .updatedAt().isBefore(cutoff)) {
                continue;
            }
            destroyChunks(
                    value.scope(), value.uploadId());
            jdbc.update("""
                            DELETE FROM mirror_outcome_population_uploads
                            WHERE tenant_id = ?
                              AND organization_id = ?
                              AND project_id = ?
                              AND environment_id = ?
                              AND region = ?
                              AND upload_id = ?
                              AND status IN %s
                              AND updated_at < ?
                            """.formatted(
                            TERMINAL_STATES),
                    value.scope().tenantId(),
                    value.scope().organizationId(),
                    value.scope().projectId(),
                    value.scope().environmentId(),
                    value.scope().region(),
                    value.uploadId(),
                    timestamp(cutoff));
        }
    }

    private static boolean terminal(
            AuthoritativeOutcomeSelectedPopulationUploadStatus.State
                    state) {
        return state
                == AuthoritativeOutcomeSelectedPopulationUploadStatus
                .State.FINALIZED
                || state
                == AuthoritativeOutcomeSelectedPopulationUploadStatus
                .State.ABORTED
                || state
                == AuthoritativeOutcomeSelectedPopulationUploadStatus
                .State.EXPIRED;
    }

    private static UploadKey key(ResultSet row)
            throws SQLException {
        return new UploadKey(
                new CapabilitySnapshot.Scope(
                        row.getString("tenant_id"),
                        row.getString("organization_id"),
                        row.getString("project_id"),
                        row.getString("environment_id"),
                        row.getString("region")),
                row.getString("upload_id"));
    }

    private Instant now() {
        return Objects.requireNonNull(
                databaseClock.get(),
                "database clock returned null");
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private <T> T read(
            String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException invalid) {
            throw violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private static Timestamp timestamp(
            Instant value) {
        return Timestamp.from(value);
    }

    private static Instant databaseNow(
            JdbcTemplate jdbc) {
        Timestamp value = Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT CURRENT_TIMESTAMP",
                        Timestamp.class),
                "database clock returned null");
        return value.toInstant();
    }

    private static DataSourceTransactionManager
    requireSavepointTransactions(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        if (!(Objects.requireNonNull(
                transactionManager,
                "transactionManager")
                instanceof DataSourceTransactionManager exact)
                || !exact.isNestedTransactionAllowed()
                || exact.getDataSource()
                != jdbc.getDataSource()) {
            throw new IllegalArgumentException(
                    "Selected-population upload repository requires one "
                            + "nested-savepoint DataSourceTransactionManager "
                            + "for its JdbcTemplate");
        }
        return exact;
    }

    private static String identifier(
            String value) {
        String exact = value == null ? "" : value.trim();
        if (!exact.matches(
                "[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}")) {
            throw new IllegalArgumentException(
                    "upload coordinate must be a bounded identifier");
        }
        return exact;
    }

    private static Violation violation(
            Reason reason) {
        return new Violation(reason);
    }

    private static <T> T required(
            T value, String operation) {
        return Objects.requireNonNull(
                value, operation + " returned null");
    }

    private record StoredUpload(
            CapabilitySnapshot.Scope scope,
            AuthoritativeOutcomeSelectedPopulationUploadRequest
                    request,
            String requestFingerprint,
            AuthoritativeOutcomeSelectedPopulationUploadStatus.State
                    state,
            int expectedChunkCount,
            int receivedChunkCount,
            long receivedBytes,
            long finalizeEpoch,
            String finalizeOwner,
            Instant finalizeLeaseUntil,
            String finalizedPopulationFingerprint,
            String admissionJson,
            Instant createdAt,
            Instant updatedAt,
            Instant expiresAt
    ) {
    }

    private record StoredChunk(
            String fingerprint,
            long encodedBytes
    ) {
    }

    private record UploadKey(
            CapabilitySnapshot.Scope scope,
            String uploadId
    ) {
    }
}
