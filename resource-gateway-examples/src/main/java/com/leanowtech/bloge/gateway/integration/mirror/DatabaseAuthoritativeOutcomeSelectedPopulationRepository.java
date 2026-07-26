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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * JDBC append-only selected-population registry and two-phase completeness cut coordinator.
 *
 * <p>The repository shares {@code mirror_outcome_inbox_locks} with the authoritative outcome
 * inbox. Population/disposition writes, outcome-head cuts, and assessment commits therefore
 * serialize on the same region/environment database row across replicas. Customer selection,
 * business-outcome, and deletion-authority calls occur before or between transactions; commit
 * repeats local custody checks and rejects a changed source cut.</p>
 */
public final class
DatabaseAuthoritativeOutcomeSelectedPopulationRepository
        implements AuthoritativeOutcomeSelectedPopulationRepository {
    private static final Pattern IDENTIFIER =
            Pattern.compile(
                    "[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,511}");
    private static final Runnable NO_INITIALIZATION_PROBE =
            () -> {
            };

    private static final String CREATE_LOCKS = """
            CREATE TABLE IF NOT EXISTS mirror_outcome_inbox_locks (
                region VARCHAR(96) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                PRIMARY KEY (region, environment_id)
            )
            """;
    private static final String CREATE_POPULATIONS = """
            CREATE TABLE IF NOT EXISTS mirror_outcome_selected_populations (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                population_id VARCHAR(512) NOT NULL,
                revision BIGINT NOT NULL,
                population_fingerprint VARCHAR(71) NOT NULL,
                predecessor_fingerprint VARCHAR(71) NOT NULL,
                inventory_id VARCHAR(512) NOT NULL,
                inventory_revision BIGINT NOT NULL,
                inventory_fingerprint VARCHAR(71) NOT NULL,
                cohort_id VARCHAR(512) NOT NULL,
                cohort_revision BIGINT NOT NULL,
                cohort_fingerprint VARCHAR(71) NOT NULL,
                selected_at TIMESTAMP WITH TIME ZONE NOT NULL,
                manifest_json TEXT NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, population_id, revision
                ),
                CONSTRAINT uq_mirror_outcome_selected_population_fp UNIQUE (
                    tenant_id, organization_id, project_id,
                    environment_id, region, population_fingerprint
                )
            )
            """;
    private static final String CREATE_POPULATION_HEADS = """
            CREATE TABLE IF NOT EXISTS mirror_outcome_selected_population_heads (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                population_id VARCHAR(512) NOT NULL,
                current_revision BIGINT NOT NULL,
                current_population_fingerprint VARCHAR(71) NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, population_id
                )
            )
            """;
    private static final String CREATE_CHUNKS = """
            CREATE TABLE IF NOT EXISTS mirror_outcome_selected_population_chunks (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                population_id VARCHAR(512) NOT NULL,
                population_revision BIGINT NOT NULL,
                chunk_index INTEGER NOT NULL,
                chunk_fingerprint VARCHAR(71) NOT NULL,
                first_global_ordinal BIGINT NOT NULL,
                member_count INTEGER NOT NULL,
                chunk_json TEXT NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, population_id,
                    population_revision, chunk_index
                ),
                CONSTRAINT uq_mirror_outcome_selected_chunk_fp UNIQUE (
                    tenant_id, organization_id, project_id,
                    environment_id, region, chunk_fingerprint
                )
            )
            """;
    private static final String CREATE_MEMBERS = """
            CREATE TABLE IF NOT EXISTS mirror_outcome_selected_population_members (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                population_id VARCHAR(512) NOT NULL,
                population_revision BIGINT NOT NULL,
                global_ordinal BIGINT NOT NULL,
                unit_id VARCHAR(512) NOT NULL,
                stratum_id VARCHAR(512) NOT NULL,
                sample_ordinal BIGINT NOT NULL,
                inclusion_fingerprint VARCHAR(71) NOT NULL,
                subject_fingerprint VARCHAR(71) NOT NULL,
                attribution_fingerprint VARCHAR(71) NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, population_id,
                    population_revision, global_ordinal
                ),
                CONSTRAINT uq_mirror_outcome_selected_member_position UNIQUE (
                    tenant_id, organization_id, project_id,
                    environment_id, region, population_id,
                    population_revision, unit_id, stratum_id,
                    sample_ordinal
                ),
                CONSTRAINT uq_mirror_outcome_selected_member_inclusion UNIQUE (
                    tenant_id, organization_id, project_id,
                    environment_id, region, population_id,
                    population_revision, inclusion_fingerprint
                ),
                CONSTRAINT uq_mirror_outcome_selected_member_attribution UNIQUE (
                    tenant_id, organization_id, project_id,
                    environment_id, region, population_id,
                    population_revision, attribution_fingerprint
                )
            )
            """;
    private static final String CREATE_DISPOSITIONS = """
            CREATE TABLE IF NOT EXISTS mirror_outcome_selected_dispositions (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                disposition_id VARCHAR(512) NOT NULL,
                revision BIGINT NOT NULL,
                disposition_fingerprint VARCHAR(71) NOT NULL,
                predecessor_fingerprint VARCHAR(71) NOT NULL,
                population_id VARCHAR(512) NOT NULL,
                population_revision BIGINT NOT NULL,
                population_fingerprint VARCHAR(71) NOT NULL,
                unit_id VARCHAR(512) NOT NULL,
                stratum_id VARCHAR(512) NOT NULL,
                sample_ordinal BIGINT NOT NULL,
                effective_at TIMESTAMP WITH TIME ZONE NOT NULL,
                disposition_json TEXT NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, disposition_id, revision
                ),
                CONSTRAINT uq_mirror_outcome_selected_disposition_fp UNIQUE (
                    tenant_id, organization_id, project_id,
                    environment_id, region, disposition_fingerprint
                )
            )
            """;
    private static final String CREATE_DISPOSITION_HEADS = """
            CREATE TABLE IF NOT EXISTS mirror_outcome_selected_disposition_heads (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                disposition_id VARCHAR(512) NOT NULL,
                current_revision BIGINT NOT NULL,
                current_disposition_fingerprint VARCHAR(71) NOT NULL,
                population_id VARCHAR(512) NOT NULL,
                population_revision BIGINT NOT NULL,
                population_fingerprint VARCHAR(71) NOT NULL,
                unit_id VARCHAR(512) NOT NULL,
                stratum_id VARCHAR(512) NOT NULL,
                sample_ordinal BIGINT NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, disposition_id
                ),
                CONSTRAINT uq_mirror_outcome_selected_disposition_member UNIQUE (
                    tenant_id, organization_id, project_id,
                    environment_id, region, population_id,
                    population_revision, population_fingerprint,
                    unit_id, stratum_id, sample_ordinal
                )
            )
            """;
    private static final String CREATE_ASSESSMENTS = """
            CREATE TABLE IF NOT EXISTS mirror_outcome_population_assessments (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                assessment_id VARCHAR(512) NOT NULL,
                revision BIGINT NOT NULL,
                assessment_fingerprint VARCHAR(71) NOT NULL,
                predecessor_fingerprint VARCHAR(71) NOT NULL,
                population_id VARCHAR(512) NOT NULL,
                population_revision BIGINT NOT NULL,
                population_fingerprint VARCHAR(71) NOT NULL,
                observation_set_fingerprint VARCHAR(71) NOT NULL,
                disposition_set_fingerprint VARCHAR(71) NOT NULL,
                assessed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                assessment_json TEXT NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, assessment_id, revision
                ),
                CONSTRAINT uq_mirror_outcome_population_assessment_fp UNIQUE (
                    tenant_id, organization_id, project_id,
                    environment_id, region, assessment_fingerprint
                )
            )
            """;
    private static final String CREATE_ASSESSMENT_HEADS = """
            CREATE TABLE IF NOT EXISTS mirror_outcome_population_assessment_heads (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                assessment_id VARCHAR(512) NOT NULL,
                current_revision BIGINT NOT NULL,
                current_assessment_fingerprint VARCHAR(71) NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, assessment_id
                )
            )
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuthoritativeOutcomeSelectedPopulationIntegrity
            populationIntegrity;
    private final AuthoritativeOutcomeObservationIntegrity
            observationIntegrity;
    private final
    AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
            dispositionIntegrity;
    private final AuthoritativeOutcomeSelectedPopulationCompletenessProjector
            projector;
    private final Supplier<Instant> coordinationClock;
    private final TransactionTemplate mutations;
    private final TransactionTemplate lockRowInitialization;
    private final Runnable beforeLockRowInsert;

    /**
     * Creates a production repository using the application database clock.
     */
    public DatabaseAuthoritativeOutcomeSelectedPopulationRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            AuthoritativeOutcomeSelectedPopulationIntegrity
                    populationIntegrity,
            AuthoritativeOutcomeObservationIntegrity
                    observationIntegrity,
            AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                    dispositionIntegrity,
            AuthoritativeOutcomeSelectedPopulationCompletenessProjector
                    projector,
            PlatformTransactionManager transactionManager) {
        this(
                jdbc,
                mapper,
                populationIntegrity,
                observationIntegrity,
                dispositionIntegrity,
                projector,
                transactionManager,
                null,
                NO_INITIALIZATION_PROBE);
    }

    /** Deterministic database-clock seam for repository tests. */
    DatabaseAuthoritativeOutcomeSelectedPopulationRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            AuthoritativeOutcomeSelectedPopulationIntegrity
                    populationIntegrity,
            AuthoritativeOutcomeObservationIntegrity
                    observationIntegrity,
            AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                    dispositionIntegrity,
            AuthoritativeOutcomeSelectedPopulationCompletenessProjector
                    projector,
            PlatformTransactionManager transactionManager,
            Supplier<Instant> coordinationClock) {
        this(
                jdbc,
                mapper,
                populationIntegrity,
                observationIntegrity,
                dispositionIntegrity,
                projector,
                transactionManager,
                coordinationClock,
                NO_INITIALIZATION_PROBE);
    }

    /** Deterministic clock and lock-row race seams for database certification. */
    DatabaseAuthoritativeOutcomeSelectedPopulationRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            AuthoritativeOutcomeSelectedPopulationIntegrity
                    populationIntegrity,
            AuthoritativeOutcomeObservationIntegrity
                    observationIntegrity,
            AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                    dispositionIntegrity,
            AuthoritativeOutcomeSelectedPopulationCompletenessProjector
                    projector,
            PlatformTransactionManager transactionManager,
            Supplier<Instant> coordinationClock,
            Runnable beforeLockRowInsert) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.populationIntegrity = Objects.requireNonNull(
                populationIntegrity, "populationIntegrity");
        this.observationIntegrity = Objects.requireNonNull(
                observationIntegrity, "observationIntegrity");
        this.dispositionIntegrity = Objects.requireNonNull(
                dispositionIntegrity, "dispositionIntegrity");
        this.projector = Objects.requireNonNull(
                projector, "projector");
        this.coordinationClock = coordinationClock == null
                ? () -> databaseNow(this.jdbc)
                : Objects.requireNonNull(
                coordinationClock, "coordinationClock");
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

    /** Creates immutable evidence, rebuildable head, member index, and shared lock tables. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_LOCKS);
        jdbc.execute(CREATE_POPULATIONS);
        jdbc.execute(CREATE_POPULATION_HEADS);
        jdbc.execute(CREATE_CHUNKS);
        jdbc.execute(CREATE_MEMBERS);
        jdbc.execute(CREATE_DISPOSITIONS);
        jdbc.execute(CREATE_DISPOSITION_HEADS);
        jdbc.execute(CREATE_ASSESSMENTS);
        jdbc.execute(CREATE_ASSESSMENT_HEADS);
    }

    @Override
    public PopulationAdmission register(
            AuthoritativeOutcomeSelectedPopulationManifest
                    manifest,
            List<AuthoritativeOutcomeSelectedPopulationChunk>
                    chunks,
            String expectedPredecessorFingerprint) {
        AuthoritativeOutcomeSelectedPopulationManifest exact =
                populationIntegrity.verify(
                        manifest, chunks);
        return registerPreverified(
                exact,
                chunks,
                expectedPredecessorFingerprint);
    }

    @Override
    public PopulationAdmission registerPreverified(
            AuthoritativeOutcomeSelectedPopulationManifest
                    manifest,
            List<AuthoritativeOutcomeSelectedPopulationChunk>
                    chunks,
            String expectedPredecessorFingerprint) {
        AuthoritativeOutcomeSelectedPopulationManifest exact =
                populationIntegrity.verifyLocally(
                        manifest, chunks);
        List<AuthoritativeOutcomeSelectedPopulationChunk>
                exactChunks = List.copyOf(chunks);
        String expected = optionalFingerprint(
                expectedPredecessorFingerprint);
        return required(
                mutations.execute(ignored -> {
                    lockPartition(
                            exact.scope().region(),
                            exact.scope().environmentId());
                    return appendPopulation(
                            exact,
                            exactChunks,
                            expected);
                }),
                "population admission");
    }

    @Override
    public Optional<Population> findPopulation(
            CapabilitySnapshot.Scope scope,
            String populationId,
            long revision) {
        if (revision < 1) {
            throw new IllegalArgumentException(
                    "population revision must be positive");
        }
        return findStoredPopulation(
                Objects.requireNonNull(scope, "scope"),
                identifier(populationId),
                revision).map(StoredPopulation::population);
    }

    @Override
    public Optional<Population> findLatestPopulation(
            CapabilitySnapshot.Scope scope,
            String populationId) {
        CapabilitySnapshot.Scope exactScope =
                Objects.requireNonNull(scope, "scope");
        String exactId = identifier(populationId);
        Optional<Head> head = findPopulationHead(
                exactScope, exactId);
        if (head.isEmpty()) {
            return Optional.empty();
        }
        Head current = head.orElseThrow();
        StoredPopulation stored = findStoredPopulation(
                exactScope,
                exactId,
                current.revision())
                .orElseThrow(() ->
                        new Violation(
                                Reason
                                        .STORED_STATE_CORRUPT));
        if (!stored.population().manifest()
                .manifestFingerprint()
                .equals(current.fingerprint())) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
        return Optional.of(stored.population());
    }

    @Override
    public DispositionAdmission appendDisposition(
            AuthoritativeOutcomeSelectedPopulationDisposition
                    disposition,
            String expectedPredecessorFingerprint) {
        return appendDispositionPreverified(
                dispositionIntegrity.verify(disposition),
                expectedPredecessorFingerprint);
    }

    @Override
    public DispositionAdmission appendDispositionPreverified(
            AuthoritativeOutcomeSelectedPopulationDisposition
                    disposition,
            String expectedPredecessorFingerprint) {
        AuthoritativeOutcomeSelectedPopulationDisposition exact =
                dispositionIntegrity.verifyLocally(
                        disposition);
        String expected = optionalFingerprint(
                expectedPredecessorFingerprint);
        return required(
                mutations.execute(ignored -> {
                    lockPartition(
                            exact.scope().region(),
                            exact.scope().environmentId());
                    return appendDispositionStored(
                            exact, expected);
                }),
                "disposition admission");
    }

    @Override
    public Optional<AuthoritativeOutcomeSelectedPopulationDisposition>
    findDisposition(
            CapabilitySnapshot.Scope scope,
            String dispositionId,
            long revision) {
        if (revision < 1) {
            throw new IllegalArgumentException(
                    "disposition revision must be positive");
        }
        return findStoredDisposition(
                Objects.requireNonNull(scope, "scope"),
                identifier(dispositionId),
                revision).map(StoredDisposition::disposition);
    }

    @Override
    public Optional<AuthoritativeOutcomeSelectedPopulationDisposition>
    findLatestDisposition(
            CapabilitySnapshot.Scope scope,
            String dispositionId) {
        CapabilitySnapshot.Scope exactScope =
                Objects.requireNonNull(scope, "scope");
        String exactId = identifier(dispositionId);
        Optional<Head> head = findDispositionHead(
                exactScope, exactId);
        if (head.isEmpty()) {
            return Optional.empty();
        }
        Head current = head.orElseThrow();
        StoredDisposition stored = findStoredDisposition(
                exactScope,
                exactId,
                current.revision())
                .orElseThrow(() ->
                        new Violation(
                                Reason
                                        .STORED_STATE_CORRUPT));
        if (!stored.disposition()
                .dispositionFingerprint()
                .equals(current.fingerprint())) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
        return Optional.of(stored.disposition());
    }

    @Override
    public AssessmentCut prepareAssessment(
            CapabilitySnapshot.Scope scope,
            String populationId,
            long populationRevision) {
        if (populationRevision < 1) {
            throw new IllegalArgumentException(
                    "population revision must be positive");
        }
        CapabilitySnapshot.Scope exactScope =
                Objects.requireNonNull(scope, "scope");
        String exactId = identifier(populationId);
        return required(
                mutations.execute(ignored -> {
                    lockPartition(
                            exactScope.region(),
                            exactScope.environmentId());
                    Population population = findStoredPopulation(
                            exactScope,
                            exactId,
                            populationRevision)
                            .map(StoredPopulation::population)
                            .orElseThrow(() ->
                                    new Violation(
                                            Reason.POPULATION_NOT_FOUND));
                    return currentCut(
                            population,
                            coordinationNow());
                }),
                "assessment cut");
    }

    @Override
    public AssessmentAdmission appendAssessment(
            AssessmentCut cut,
            AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                    assessment,
            String expectedPredecessorFingerprint) {
        AssessmentCut prepared = Objects.requireNonNull(
                cut, "cut");
        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                exact = projector.verifyAssessment(
                assessment);
        String expected = optionalFingerprint(
                expectedPredecessorFingerprint);
        CapabilitySnapshot.Scope scope =
                prepared.population().manifest().scope();
        return required(
                mutations.execute(ignored -> {
                    lockPartition(
                            scope.region(),
                            scope.environmentId());
                    Population currentPopulation =
                            findStoredPopulation(
                                    scope,
                                    prepared.population()
                                            .manifest()
                                            .populationId(),
                                    prepared.population()
                                            .manifest()
                                            .revision())
                                    .map(
                                            StoredPopulation
                                                    ::population)
                                    .orElseThrow(() ->
                                            new Violation(
                                                    Reason
                                                            .POPULATION_NOT_FOUND));
                    AssessmentCut current = currentCut(
                            currentPopulation,
                            coordinationNow());
                    if (!sameCut(prepared, current)) {
                        throw new Violation(
                                Reason.CUT_STALE);
                    }
                    verifyAssessmentMatches(
                            current, exact);
                    return appendAssessment(
                            exact, expected);
                }),
                "assessment admission");
    }

    @Override
    public Optional<
            AuthoritativeOutcomeSelectedPopulationCompletenessAssessment>
    findAssessment(
            CapabilitySnapshot.Scope scope,
            String assessmentId,
            long revision) {
        if (revision < 1) {
            throw new IllegalArgumentException(
                    "assessment revision must be positive");
        }
        return findStoredAssessment(
                Objects.requireNonNull(scope, "scope"),
                identifier(assessmentId),
                revision).map(StoredAssessment::assessment);
    }

    @Override
    public Optional<
            AuthoritativeOutcomeSelectedPopulationCompletenessAssessment>
    findLatestAssessment(
            CapabilitySnapshot.Scope scope,
            String assessmentId) {
        CapabilitySnapshot.Scope exactScope =
                Objects.requireNonNull(scope, "scope");
        String exactId = identifier(assessmentId);
        Optional<Head> head = findAssessmentHead(
                exactScope, exactId);
        if (head.isEmpty()) {
            return Optional.empty();
        }
        Head current = head.orElseThrow();
        StoredAssessment stored = findStoredAssessment(
                exactScope,
                exactId,
                current.revision())
                .orElseThrow(() ->
                        new Violation(
                                Reason
                                        .STORED_STATE_CORRUPT));
        if (!stored.assessment()
                .assessmentFingerprint()
                .equals(current.fingerprint())) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
        return Optional.of(stored.assessment());
    }

    private PopulationAdmission appendPopulation(
            AuthoritativeOutcomeSelectedPopulationManifest
                    manifest,
            List<AuthoritativeOutcomeSelectedPopulationChunk>
                    chunks,
            String expectedPredecessor) {
        CapabilitySnapshot.Scope scope = manifest.scope();
        Optional<StoredPopulation> existing =
                findStoredPopulation(
                        scope,
                        manifest.populationId(),
                        manifest.revision());
        if (existing.isPresent()) {
            StoredPopulation stored = existing.orElseThrow();
            if (!stored.population().manifest().equals(manifest)
                    || !stored.population().chunks()
                    .equals(chunks)
                    || !stored.predecessorFingerprint()
                    .equals(expectedPredecessor)) {
                throw new Violation(
                        Reason.CONTENT_CONFLICT);
            }
            return new PopulationAdmission(
                    stored.population(), true);
        }
        Optional<Head> head = findPopulationHead(
                scope, manifest.populationId());
        if (head.isEmpty()) {
            if (manifest.revision() != 1
                    || !expectedPredecessor.isBlank()) {
                throw new Violation(
                        Reason.LINEAGE_CONFLICT);
            }
        } else {
            Head current = head.orElseThrow();
            if (manifest.revision()
                    != Math.addExact(
                    current.revision(), 1L)
                    || !expectedPredecessor.equals(
                    current.fingerprint())) {
                throw new Violation(
                        Reason.LINEAGE_CONFLICT);
            }
        }
        insertPopulation(
                manifest,
                chunks,
                expectedPredecessor);
        if (head.isEmpty()) {
            insertPopulationHead(manifest);
        } else {
            updatePopulationHead(
                    manifest, head.orElseThrow());
        }
        return new PopulationAdmission(
                new Population(
                        manifest,
                        chunks,
                        expectedPredecessor),
                false);
    }

    private void insertPopulation(
            AuthoritativeOutcomeSelectedPopulationManifest
                    manifest,
            List<AuthoritativeOutcomeSelectedPopulationChunk>
                    chunks,
            String predecessorFingerprint) {
        CapabilitySnapshot.Scope scope = manifest.scope();
        try {
            jdbc.update("""
                            INSERT INTO mirror_outcome_selected_populations (
                                tenant_id, organization_id, project_id,
                                environment_id, region, population_id,
                                revision, population_fingerprint,
                                predecessor_fingerprint, inventory_id,
                                inventory_revision, inventory_fingerprint,
                                cohort_id, cohort_revision,
                                cohort_fingerprint, selected_at,
                                manifest_json
                            ) VALUES (
                                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                ?, ?
                            )
                            """,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    manifest.populationId(),
                    manifest.revision(),
                    manifest.manifestFingerprint(),
                    predecessorFingerprint,
                    manifest.inventoryRef().id(),
                    manifest.inventoryRef().revision(),
                    manifest.inventoryRef().fingerprint(),
                    manifest.cohortRef().id(),
                    manifest.cohortRef().revision(),
                    manifest.cohortRef().fingerprint(),
                    timestamp(manifest.selectedAt()),
                    json(manifest));
            for (AuthoritativeOutcomeSelectedPopulationChunk
                    chunk : chunks) {
                insertChunk(scope, chunk);
                for (AuthoritativeOutcomeSelectedPopulationChunk
                        .Member member : chunk.members()) {
                    insertMember(
                            scope,
                            manifest.populationId(),
                            manifest.revision(),
                            member);
                }
            }
        } catch (DuplicateKeyException conflict) {
            throw new Violation(
                    Reason.CONTENT_CONFLICT);
        }
    }

    private void insertChunk(
            CapabilitySnapshot.Scope scope,
            AuthoritativeOutcomeSelectedPopulationChunk
                    chunk) {
        jdbc.update("""
                        INSERT INTO mirror_outcome_selected_population_chunks (
                            tenant_id, organization_id, project_id,
                            environment_id, region, population_id,
                            population_revision, chunk_index,
                            chunk_fingerprint, first_global_ordinal,
                            member_count, chunk_json
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                chunk.populationId(),
                chunk.populationRevision(),
                chunk.chunkIndex(),
                chunk.chunkFingerprint(),
                chunk.firstGlobalOrdinal(),
                chunk.members().size(),
                json(chunk));
    }

    private void insertMember(
            CapabilitySnapshot.Scope scope,
            String populationId,
            long populationRevision,
            AuthoritativeOutcomeSelectedPopulationChunk.Member
                    member) {
        jdbc.update("""
                        INSERT INTO mirror_outcome_selected_population_members (
                            tenant_id, organization_id, project_id,
                            environment_id, region, population_id,
                            population_revision, global_ordinal,
                            unit_id, stratum_id, sample_ordinal,
                            inclusion_fingerprint, subject_fingerprint,
                            attribution_fingerprint
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                populationId,
                populationRevision,
                member.globalOrdinal(),
                member.unitId(),
                member.stratumId(),
                member.sampleOrdinal(),
                member.inclusionFingerprint(),
                member.subjectFingerprint(),
                member.attributionKeyFingerprint());
    }

    private void insertPopulationHead(
            AuthoritativeOutcomeSelectedPopulationManifest
                    manifest) {
        CapabilitySnapshot.Scope scope = manifest.scope();
        jdbc.update("""
                        INSERT INTO mirror_outcome_selected_population_heads (
                            tenant_id, organization_id, project_id,
                            environment_id, region, population_id,
                            current_revision,
                            current_population_fingerprint
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                manifest.populationId(),
                manifest.revision(),
                manifest.manifestFingerprint());
    }

    private void updatePopulationHead(
            AuthoritativeOutcomeSelectedPopulationManifest
                    manifest,
            Head previous) {
        CapabilitySnapshot.Scope scope = manifest.scope();
        int changed = jdbc.update("""
                        UPDATE mirror_outcome_selected_population_heads
                        SET current_revision = ?,
                            current_population_fingerprint = ?
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND population_id = ?
                          AND current_revision = ?
                          AND current_population_fingerprint = ?
                        """,
                manifest.revision(),
                manifest.manifestFingerprint(),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                manifest.populationId(),
                previous.revision(),
                previous.fingerprint());
        if (changed != 1) {
            throw new Violation(
                    Reason.LINEAGE_CONFLICT);
        }
    }

    private DispositionAdmission appendDispositionStored(
            AuthoritativeOutcomeSelectedPopulationDisposition
                    disposition,
            String expectedPredecessor) {
        Population population = findStoredPopulation(
                disposition.scope(),
                disposition.populationRef().id(),
                disposition.populationRef().revision())
                .filter(stored ->
                        stored.population().manifest()
                                .artifactRef()
                                .equals(
                                        disposition
                                                .populationRef()))
                .map(StoredPopulation::population)
                .orElseThrow(() ->
                        new Violation(
                                Reason.POPULATION_NOT_FOUND));
        MemberCoordinate member = members(population)
                .get(new MemberKey(
                        disposition.unitId(),
                        disposition.stratumId(),
                        disposition.sampleOrdinal()));
        if (member == null
                || !member.inclusionFingerprint().equals(
                disposition.inclusionFingerprint())
                || !member.subjectFingerprint().equals(
                disposition.subjectFingerprint())
                || !member.attributionFingerprint().equals(
                disposition.attributionKeyFingerprint())) {
            throw new Violation(
                    Reason.MEMBER_CONFLICT);
        }
        Optional<StoredDisposition> existing =
                findStoredDisposition(
                        disposition.scope(),
                        disposition.dispositionId(),
                        disposition.revision());
        if (existing.isPresent()) {
            StoredDisposition stored =
                    existing.orElseThrow();
            if (!stored.disposition().equals(disposition)
                    || !stored.predecessorFingerprint()
                    .equals(expectedPredecessor)) {
                throw new Violation(
                        Reason.CONTENT_CONFLICT);
            }
            return new DispositionAdmission(
                    stored.disposition(),
                    stored.predecessorFingerprint(),
                    true);
        }
        Optional<Head> head = findDispositionHead(
                disposition.scope(),
                disposition.dispositionId());
        if (head.isEmpty()) {
            if (disposition.revision() != 1
                    || !expectedPredecessor.isBlank()) {
                throw new Violation(
                        Reason.LINEAGE_CONFLICT);
            }
        } else {
            Head current = head.orElseThrow();
            if (disposition.revision()
                    != Math.addExact(
                    current.revision(), 1L)
                    || !expectedPredecessor.equals(
                    current.fingerprint())) {
                throw new Violation(
                        Reason.LINEAGE_CONFLICT);
            }
            AuthoritativeOutcomeSelectedPopulationDisposition
                    previous = findStoredDisposition(
                    disposition.scope(),
                    disposition.dispositionId(),
                    current.revision())
                    .filter(stored ->
                            stored.disposition()
                                    .dispositionFingerprint()
                                    .equals(
                                            current
                                                    .fingerprint()))
                    .map(StoredDisposition::disposition)
                    .orElseThrow(() ->
                            new Violation(
                                    Reason
                                            .STORED_STATE_CORRUPT));
            if (!sameDispositionMember(
                    previous, disposition)
                    || disposition.effectiveAt().isBefore(
                    previous.effectiveAt())) {
                throw new Violation(
                        Reason.LINEAGE_CONFLICT);
            }
        }
        Optional<String> occupying =
                findDispositionHeadByMember(disposition);
        if (occupying.isPresent()
                && !occupying.orElseThrow().equals(
                disposition.dispositionId())) {
            throw new Violation(
                    Reason.MEMBER_CONFLICT);
        }
        insertDisposition(
                disposition, expectedPredecessor);
        if (head.isEmpty()) {
            insertDispositionHead(disposition);
        } else {
            updateDispositionHead(
                    disposition, head.orElseThrow());
        }
        return new DispositionAdmission(
                disposition,
                expectedPredecessor,
                false);
    }

    private AssessmentCut currentCut(
            Population population,
            Instant observedAt) {
        AuthoritativeOutcomeSelectedPopulationManifest
                manifest = population.manifest();
        Map<MemberKey, MemberCoordinate> members =
                members(population);
        Map<String, MemberCoordinate> inclusions =
                new HashMap<>();
        Map<String, MemberCoordinate> attributions =
                new HashMap<>();
        for (MemberCoordinate member : members.values()) {
            inclusions.put(
                    member.inclusionFingerprint(),
                    member);
            attributions.put(
                    member.attributionFingerprint(),
                    member);
        }
        Map<MemberKey, PositionedObservation>
                observations = currentObservations(
                manifest,
                members,
                inclusions,
                attributions);
        Map<MemberKey, PositionedDisposition>
                dispositions = currentDispositions(
                manifest,
                members);
        Set<MemberKey> overlap =
                new HashSet<>(observations.keySet());
        overlap.retainAll(dispositions.keySet());
        if (!overlap.isEmpty()) {
            throw new Violation(
                    Reason.SOURCE_CONFLICT);
        }
        List<PositionedObservation> orderedObservations =
                observations.values().stream()
                        .sorted(Comparator.comparingLong(
                                PositionedObservation
                                        ::globalOrdinal))
                        .toList();
        List<PositionedDisposition> orderedDispositions =
                dispositions.values().stream()
                        .sorted(Comparator.comparingLong(
                                PositionedDisposition
                                        ::globalOrdinal))
                        .toList();
        String observationSet =
                AuthoritativeOutcomeSelectedPopulationSourceSet
                        .fingerprint(
                        mapper,
                        AuthoritativeOutcomeSelectedPopulationSourceSet
                                .OBSERVATION_DOMAIN,
                        manifest.artifactRef(),
                        orderedObservations.stream()
                                .map(value ->
                                        new
                                                AuthoritativeOutcomeSelectedPopulationSourceSet
                                                        .Entry(
                                                value.globalOrdinal(),
                                                value.observation()
                                                        .artifactRef()))
                                .toList());
        String dispositionSet =
                AuthoritativeOutcomeSelectedPopulationSourceSet
                        .fingerprint(
                        mapper,
                        AuthoritativeOutcomeSelectedPopulationSourceSet
                                .DISPOSITION_DOMAIN,
                        manifest.artifactRef(),
                        orderedDispositions.stream()
                                .map(value ->
                                        new
                                                AuthoritativeOutcomeSelectedPopulationSourceSet
                                                        .Entry(
                                                value.globalOrdinal(),
                                                value.disposition()
                                                        .artifactRef()))
                                .toList());
        return new AssessmentCut(
                population,
                orderedObservations.stream()
                        .map(
                                PositionedObservation
                                        ::observation)
                        .toList(),
                orderedDispositions.stream()
                        .map(
                                PositionedDisposition
                                        ::disposition)
                        .toList(),
                observedAt,
                observationSet,
                dispositionSet);
    }

    private Map<MemberKey, PositionedObservation>
    currentObservations(
            AuthoritativeOutcomeSelectedPopulationManifest
                    population,
            Map<MemberKey, MemberCoordinate> members,
            Map<String, MemberCoordinate> inclusions,
            Map<String, MemberCoordinate> attributions) {
        List<StoredCurrentObservation> candidates =
                jdbc.query("""
                        SELECT h.current_observation_fingerprint,
                               o.observation_json
                        FROM mirror_outcome_inbox_heads h
                        JOIN mirror_outcome_observations o
                          ON o.tenant_id = h.tenant_id
                         AND o.organization_id = h.organization_id
                         AND o.project_id = h.project_id
                         AND o.environment_id = h.environment_id
                         AND o.region = h.region
                         AND o.observation_id = h.observation_id
                         AND o.revision = h.current_revision
                        WHERE h.tenant_id = ?
                          AND h.organization_id = ?
                          AND h.project_id = ?
                          AND h.environment_id = ?
                          AND h.region = ?
                          AND h.inventory_id = ?
                          AND h.inventory_revision = ?
                          AND h.inventory_fingerprint = ?
                          AND h.cohort_id = ?
                          AND h.cohort_revision = ?
                          AND h.cohort_fingerprint = ?
                        ORDER BY h.observation_id
                        """,
                        (row, ignored) ->
                                new StoredCurrentObservation(
                                        row.getString(
                                                "current_observation_fingerprint"),
                                        readObservation(
                                                row.getString(
                                                        "observation_json"))),
                        population.scope().tenantId(),
                        population.scope()
                                .organizationId(),
                        population.scope().projectId(),
                        population.scope()
                                .environmentId(),
                        population.scope().region(),
                        population.inventoryRef().id(),
                        population.inventoryRef().revision(),
                        population.inventoryRef()
                                .fingerprint(),
                        population.cohortRef().id(),
                        population.cohortRef().revision(),
                        population.cohortRef()
                                .fingerprint());
        Map<StratumKey,
                AuthoritativeOutcomeSelectedPopulationManifest
                        .Stratum> strata = new HashMap<>();
        for (AuthoritativeOutcomeSelectedPopulationManifest
                .Stratum stratum : population.strata()) {
            strata.put(
                    new StratumKey(
                            stratum.unitId(),
                            stratum.stratumId()),
                    stratum);
        }
        Map<MemberKey, PositionedObservation> result =
                new LinkedHashMap<>();
        Set<String> observationIds = new HashSet<>();
        for (StoredCurrentObservation stored : candidates) {
            AuthoritativeOutcomeObservation observation =
                    stored.observation();
            if (!observation.observationFingerprint()
                    .equals(
                            stored.headFingerprint())) {
                throw new Violation(
                        Reason.STORED_STATE_CORRUPT);
            }
            AuthoritativeOutcomeObservation.SelectionProof
                    proof = observation.selectionProof();
            MemberKey key = new MemberKey(
                    observation.unitId(),
                    proof.stratumId(),
                    proof.sampleOrdinal());
            MemberCoordinate byPosition = members.get(key);
            MemberCoordinate byInclusion = inclusions.get(
                    proof.inclusionFingerprint());
            MemberCoordinate byAttribution =
                    attributions.get(
                            observation
                                    .attributionKeyFingerprint());
            if (byPosition == null
                    && byInclusion == null
                    && byAttribution == null) {
                continue;
            }
            MemberCoordinate member = byPosition;
            AuthoritativeOutcomeSelectedPopulationManifest
                    .Stratum stratum = strata.get(
                    key.stratumKey());
            if (member == null
                    || byInclusion != member
                    || byAttribution != member
                    || stratum == null
                    || !observation.scope().equals(
                    population.scope())
                    || !observation.inventoryRef().equals(
                    population.inventoryRef())
                    || !proof.cohortRef().equals(
                    population.cohortRef())
                    || !proof.samplingFrameRef().equals(
                    population.samplingFrameRef())
                    || !proof.selectedAt().equals(
                    population.selectedAt())
                    || proof.eligiblePopulationSize()
                    != stratum.eligiblePopulationSize()
                    || proof.selectedPopulationSize()
                    != stratum.selectedPopulationSize()
                    || proof.selectionMode()
                    != stratum.selectionMode()
                    || !observation.subjectFingerprint()
                    .equals(member.subjectFingerprint())
                    || !observationIds.add(
                    observation.observationId())
                    || result.putIfAbsent(
                    key,
                    new PositionedObservation(
                            member.globalOrdinal(),
                            observation)) != null) {
                throw new Violation(
                        Reason.SOURCE_CONFLICT);
            }
        }
        return Map.copyOf(result);
    }

    private Map<MemberKey, PositionedDisposition>
    currentDispositions(
            AuthoritativeOutcomeSelectedPopulationManifest
                    population,
            Map<MemberKey, MemberCoordinate> members) {
        List<StoredCurrentDisposition> candidates =
                jdbc.query("""
                        SELECT h.current_disposition_fingerprint,
                               d.disposition_json
                        FROM mirror_outcome_selected_disposition_heads h
                        JOIN mirror_outcome_selected_dispositions d
                          ON d.tenant_id = h.tenant_id
                         AND d.organization_id = h.organization_id
                         AND d.project_id = h.project_id
                         AND d.environment_id = h.environment_id
                         AND d.region = h.region
                         AND d.disposition_id = h.disposition_id
                         AND d.revision = h.current_revision
                        WHERE h.tenant_id = ?
                          AND h.organization_id = ?
                          AND h.project_id = ?
                          AND h.environment_id = ?
                          AND h.region = ?
                          AND h.population_id = ?
                          AND h.population_revision = ?
                          AND h.population_fingerprint = ?
                        ORDER BY h.disposition_id
                        """,
                        (row, ignored) ->
                                new StoredCurrentDisposition(
                                        row.getString(
                                                "current_disposition_fingerprint"),
                                        readDisposition(
                                                row.getString(
                                                        "disposition_json"))),
                        population.scope().tenantId(),
                        population.scope()
                                .organizationId(),
                        population.scope().projectId(),
                        population.scope()
                                .environmentId(),
                        population.scope().region(),
                        population.populationId(),
                        population.revision(),
                        population.manifestFingerprint());
        Map<MemberKey, PositionedDisposition> result =
                new LinkedHashMap<>();
        Set<String> dispositionIds = new HashSet<>();
        for (StoredCurrentDisposition stored : candidates) {
            AuthoritativeOutcomeSelectedPopulationDisposition
                    disposition = stored.disposition();
            MemberKey key = new MemberKey(
                    disposition.unitId(),
                    disposition.stratumId(),
                    disposition.sampleOrdinal());
            MemberCoordinate member = members.get(key);
            if (!disposition.dispositionFingerprint()
                    .equals(stored.headFingerprint())
                    || member == null
                    || !disposition.scope().equals(
                    population.scope())
                    || !disposition.populationRef().equals(
                    population.artifactRef())
                    || !disposition.inclusionFingerprint()
                    .equals(
                            member
                                    .inclusionFingerprint())
                    || !disposition.subjectFingerprint()
                    .equals(member.subjectFingerprint())
                    || !disposition
                    .attributionKeyFingerprint()
                    .equals(
                            member
                                    .attributionFingerprint())
                    || !dispositionIds.add(
                    disposition.dispositionId())
                    || result.putIfAbsent(
                    key,
                    new PositionedDisposition(
                            member.globalOrdinal(),
                            disposition)) != null) {
                throw new Violation(
                        Reason.SOURCE_CONFLICT);
            }
        }
        return Map.copyOf(result);
    }

    private void insertDisposition(
            AuthoritativeOutcomeSelectedPopulationDisposition
                    disposition,
            String predecessorFingerprint) {
        CapabilitySnapshot.Scope scope =
                disposition.scope();
        try {
            jdbc.update("""
                            INSERT INTO mirror_outcome_selected_dispositions (
                                tenant_id, organization_id, project_id,
                                environment_id, region, disposition_id,
                                revision, disposition_fingerprint,
                                predecessor_fingerprint, population_id,
                                population_revision,
                                population_fingerprint, unit_id,
                                stratum_id, sample_ordinal, effective_at,
                                disposition_json
                            ) VALUES (
                                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                ?, ?
                            )
                            """,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    disposition.dispositionId(),
                    disposition.revision(),
                    disposition.dispositionFingerprint(),
                    predecessorFingerprint,
                    disposition.populationRef().id(),
                    disposition.populationRef().revision(),
                    disposition.populationRef()
                            .fingerprint(),
                    disposition.unitId(),
                    disposition.stratumId(),
                    disposition.sampleOrdinal(),
                    timestamp(disposition.effectiveAt()),
                    json(disposition));
        } catch (DuplicateKeyException conflict) {
            throw new Violation(
                    Reason.CONTENT_CONFLICT);
        }
    }

    private void insertDispositionHead(
            AuthoritativeOutcomeSelectedPopulationDisposition
                    disposition) {
        CapabilitySnapshot.Scope scope =
                disposition.scope();
        try {
            jdbc.update("""
                            INSERT INTO mirror_outcome_selected_disposition_heads (
                                tenant_id, organization_id, project_id,
                                environment_id, region, disposition_id,
                                current_revision,
                                current_disposition_fingerprint,
                                population_id, population_revision,
                                population_fingerprint, unit_id,
                                stratum_id, sample_ordinal
                            ) VALUES (
                                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                            )
                            """,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    disposition.dispositionId(),
                    disposition.revision(),
                    disposition.dispositionFingerprint(),
                    disposition.populationRef().id(),
                    disposition.populationRef().revision(),
                    disposition.populationRef()
                            .fingerprint(),
                    disposition.unitId(),
                    disposition.stratumId(),
                    disposition.sampleOrdinal());
        } catch (DuplicateKeyException conflict) {
            throw new Violation(
                    Reason.MEMBER_CONFLICT);
        }
    }

    private void updateDispositionHead(
            AuthoritativeOutcomeSelectedPopulationDisposition
                    disposition,
            Head previous) {
        CapabilitySnapshot.Scope scope =
                disposition.scope();
        int changed = jdbc.update("""
                        UPDATE mirror_outcome_selected_disposition_heads
                        SET current_revision = ?,
                            current_disposition_fingerprint = ?
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND disposition_id = ?
                          AND current_revision = ?
                          AND current_disposition_fingerprint = ?
                        """,
                disposition.revision(),
                disposition.dispositionFingerprint(),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                disposition.dispositionId(),
                previous.revision(),
                previous.fingerprint());
        if (changed != 1) {
            throw new Violation(
                    Reason.LINEAGE_CONFLICT);
        }
    }

    private Optional<String> findDispositionHeadByMember(
            AuthoritativeOutcomeSelectedPopulationDisposition
                    disposition) {
        CapabilitySnapshot.Scope scope =
                disposition.scope();
        return one(jdbc.query("""
                SELECT disposition_id
                FROM mirror_outcome_selected_disposition_heads
                WHERE tenant_id = ? AND organization_id = ?
                  AND project_id = ? AND environment_id = ?
                  AND region = ? AND population_id = ?
                  AND population_revision = ?
                  AND population_fingerprint = ?
                  AND unit_id = ? AND stratum_id = ?
                  AND sample_ordinal = ?
                """,
                (row, ignored) ->
                        row.getString("disposition_id"),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                disposition.populationRef().id(),
                disposition.populationRef().revision(),
                disposition.populationRef().fingerprint(),
                disposition.unitId(),
                disposition.stratumId(),
                disposition.sampleOrdinal()));
    }

    private AssessmentAdmission appendAssessment(
            AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                    assessment,
            String expectedPredecessor) {
        Optional<StoredAssessment> existing =
                findStoredAssessment(
                        assessment.scope(),
                        assessment.assessmentId(),
                        assessment.revision());
        if (existing.isPresent()) {
            StoredAssessment stored =
                    existing.orElseThrow();
            if (!stored.assessment().equals(assessment)
                    || !stored.predecessorFingerprint()
                    .equals(expectedPredecessor)) {
                throw new Violation(
                        Reason.CONTENT_CONFLICT);
            }
            return new AssessmentAdmission(
                    stored.assessment(),
                    stored.predecessorFingerprint(),
                    true);
        }
        Optional<Head> head = findAssessmentHead(
                assessment.scope(),
                assessment.assessmentId());
        if (head.isEmpty()) {
            if (assessment.revision() != 1
                    || !expectedPredecessor.isBlank()) {
                throw new Violation(
                        Reason.LINEAGE_CONFLICT);
            }
        } else {
            Head current = head.orElseThrow();
            if (assessment.revision()
                    != Math.addExact(
                    current.revision(), 1L)
                    || !expectedPredecessor.equals(
                    current.fingerprint())) {
                throw new Violation(
                        Reason.LINEAGE_CONFLICT);
            }
        }
        insertAssessment(
                assessment, expectedPredecessor);
        if (head.isEmpty()) {
            insertAssessmentHead(assessment);
        } else {
            updateAssessmentHead(
                    assessment, head.orElseThrow());
        }
        return new AssessmentAdmission(
                assessment,
                expectedPredecessor,
                false);
    }

    private void insertAssessment(
            AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                    assessment,
            String predecessorFingerprint) {
        CapabilitySnapshot.Scope scope =
                assessment.scope();
        try {
            jdbc.update("""
                            INSERT INTO mirror_outcome_population_assessments (
                                tenant_id, organization_id, project_id,
                                environment_id, region, assessment_id,
                                revision, assessment_fingerprint,
                                predecessor_fingerprint, population_id,
                                population_revision,
                                population_fingerprint,
                                observation_set_fingerprint,
                                disposition_set_fingerprint, assessed_at,
                                assessment_json
                            ) VALUES (
                                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                ?
                            )
                            """,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    assessment.assessmentId(),
                    assessment.revision(),
                    assessment.assessmentFingerprint(),
                    predecessorFingerprint,
                    assessment.populationRef().id(),
                    assessment.populationRef().revision(),
                    assessment.populationRef()
                            .fingerprint(),
                    assessment.observationSetFingerprint(),
                    assessment.dispositionSetFingerprint(),
                    timestamp(assessment.assessedAt()),
                    json(assessment));
        } catch (DuplicateKeyException conflict) {
            throw new Violation(
                    Reason.CONTENT_CONFLICT);
        }
    }

    private void insertAssessmentHead(
            AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                    assessment) {
        CapabilitySnapshot.Scope scope =
                assessment.scope();
        jdbc.update("""
                        INSERT INTO mirror_outcome_population_assessment_heads (
                            tenant_id, organization_id, project_id,
                            environment_id, region, assessment_id,
                            current_revision,
                            current_assessment_fingerprint
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                assessment.assessmentId(),
                assessment.revision(),
                assessment.assessmentFingerprint());
    }

    private void updateAssessmentHead(
            AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                    assessment,
            Head previous) {
        CapabilitySnapshot.Scope scope =
                assessment.scope();
        int changed = jdbc.update("""
                        UPDATE mirror_outcome_population_assessment_heads
                        SET current_revision = ?,
                            current_assessment_fingerprint = ?
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND assessment_id = ?
                          AND current_revision = ?
                          AND current_assessment_fingerprint = ?
                        """,
                assessment.revision(),
                assessment.assessmentFingerprint(),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                assessment.assessmentId(),
                previous.revision(),
                previous.fingerprint());
        if (changed != 1) {
            throw new Violation(
                    Reason.LINEAGE_CONFLICT);
        }
    }

    private Optional<StoredPopulation>
    findStoredPopulation(
            CapabilitySnapshot.Scope scope,
            String populationId,
            long revision) {
        Optional<PopulationRow> row = one(jdbc.query("""
                SELECT *
                FROM mirror_outcome_selected_populations
                WHERE tenant_id = ? AND organization_id = ?
                  AND project_id = ? AND environment_id = ?
                  AND region = ? AND population_id = ?
                  AND revision = ?
                """,
                this::mapPopulationRow,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                populationId,
                revision));
        if (row.isEmpty()) {
            return Optional.empty();
        }
        PopulationRow root = row.orElseThrow();
        List<AuthoritativeOutcomeSelectedPopulationChunk>
                chunks = jdbc.query("""
                        SELECT *
                        FROM mirror_outcome_selected_population_chunks
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND population_id = ?
                          AND population_revision = ?
                        ORDER BY chunk_index
                        """,
                        this::mapChunk,
                        scope.tenantId(),
                        scope.organizationId(),
                        scope.projectId(),
                        scope.environmentId(),
                        scope.region(),
                        populationId,
                        revision);
        try {
            populationIntegrity.verifyLocally(
                    root.manifest(), chunks);
            verifyPopulationIndexes(
                    root, scope, populationId, revision);
            verifyMemberIndex(
                    new Population(
                            root.manifest(),
                            chunks,
                            root.predecessorFingerprint()));
            return Optional.of(
                    new StoredPopulation(
                            new Population(
                                    root.manifest(),
                                    chunks,
                                    root.predecessorFingerprint()),
                            root.predecessorFingerprint()));
        } catch (Violation invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private PopulationRow mapPopulationRow(
            ResultSet row,
            int ignored) throws SQLException {
        AuthoritativeOutcomeSelectedPopulationManifest
                manifest = readManifest(
                row.getString("manifest_json"));
        return new PopulationRow(
                manifest,
                optionalFingerprint(
                        row.getString(
                                "predecessor_fingerprint")),
                row.getString("tenant_id"),
                row.getString("organization_id"),
                row.getString("project_id"),
                row.getString("environment_id"),
                row.getString("region"),
                row.getString("population_id"),
                row.getLong("revision"),
                row.getString(
                        "population_fingerprint"),
                row.getString("inventory_id"),
                row.getLong("inventory_revision"),
                row.getString(
                        "inventory_fingerprint"),
                row.getString("cohort_id"),
                row.getLong("cohort_revision"),
                row.getString(
                        "cohort_fingerprint"),
                instant(row, "selected_at"));
    }

    private AuthoritativeOutcomeSelectedPopulationChunk
    mapChunk(
            ResultSet row,
            int ignored) throws SQLException {
        AuthoritativeOutcomeSelectedPopulationChunk
                chunk = readChunk(
                row.getString("chunk_json"));
        try {
            populationIntegrity.sealChunk(chunk);
            if (!chunk.populationId().equals(
                    row.getString("population_id"))
                    || chunk.populationRevision()
                    != row.getLong(
                    "population_revision")
                    || chunk.chunkIndex()
                    != row.getInt("chunk_index")
                    || !chunk.chunkFingerprint().equals(
                    row.getString(
                            "chunk_fingerprint"))
                    || chunk.firstGlobalOrdinal()
                    != row.getLong(
                    "first_global_ordinal")
                    || chunk.members().size()
                    != row.getInt("member_count")) {
                throw new Violation(
                        Reason.STORED_STATE_CORRUPT);
            }
            return chunk;
        } catch (Violation invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private void verifyPopulationIndexes(
            PopulationRow row,
            CapabilitySnapshot.Scope scope,
            String populationId,
            long revision) {
        AuthoritativeOutcomeSelectedPopulationManifest
                manifest = row.manifest();
        if (!manifest.scope().equals(scope)
                || !manifest.populationId().equals(
                populationId)
                || manifest.revision() != revision
                || !scope.tenantId().equals(
                row.tenantId())
                || !scope.organizationId().equals(
                row.organizationId())
                || !scope.projectId().equals(
                row.projectId())
                || !scope.environmentId().equals(
                row.environmentId())
                || !scope.region().equals(row.region())
                || !manifest.populationId().equals(
                row.populationId())
                || manifest.revision() != row.revision()
                || !manifest.manifestFingerprint().equals(
                row.populationFingerprint())
                || !manifest.inventoryRef().id().equals(
                row.inventoryId())
                || manifest.inventoryRef().revision()
                != row.inventoryRevision()
                || !manifest.inventoryRef()
                .fingerprint().equals(
                        row.inventoryFingerprint())
                || !manifest.cohortRef().id().equals(
                row.cohortId())
                || manifest.cohortRef().revision()
                != row.cohortRevision()
                || !manifest.cohortRef()
                .fingerprint().equals(
                        row.cohortFingerprint())
                || !manifest.selectedAt().equals(
                row.selectedAt())
                || (manifest.revision() == 1)
                != row.predecessorFingerprint().isBlank()) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private void verifyMemberIndex(
            Population population) {
        AuthoritativeOutcomeSelectedPopulationManifest
                manifest = population.manifest();
        List<IndexedMember> stored = jdbc.query("""
                SELECT *
                FROM mirror_outcome_selected_population_members
                WHERE tenant_id = ? AND organization_id = ?
                  AND project_id = ? AND environment_id = ?
                  AND region = ? AND population_id = ?
                  AND population_revision = ?
                ORDER BY global_ordinal
                """,
                this::mapIndexedMember,
                manifest.scope().tenantId(),
                manifest.scope().organizationId(),
                manifest.scope().projectId(),
                manifest.scope().environmentId(),
                manifest.scope().region(),
                manifest.populationId(),
                manifest.revision());
        List<IndexedMember> derived =
                population.chunks().stream()
                        .flatMap(chunk ->
                                chunk.members().stream())
                        .map(IndexedMember::from)
                        .toList();
        if (!stored.equals(derived)) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private IndexedMember mapIndexedMember(
            ResultSet row,
            int ignored) throws SQLException {
        return new IndexedMember(
                row.getLong("global_ordinal"),
                row.getString("unit_id"),
                row.getString("stratum_id"),
                row.getLong("sample_ordinal"),
                row.getString(
                        "inclusion_fingerprint"),
                row.getString("subject_fingerprint"),
                row.getString(
                        "attribution_fingerprint"));
    }

    private Optional<StoredDisposition>
    findStoredDisposition(
            CapabilitySnapshot.Scope scope,
            String dispositionId,
            long revision) {
        return one(jdbc.query("""
                SELECT *
                FROM mirror_outcome_selected_dispositions
                WHERE tenant_id = ? AND organization_id = ?
                  AND project_id = ? AND environment_id = ?
                  AND region = ? AND disposition_id = ?
                  AND revision = ?
                """,
                this::mapDisposition,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                dispositionId,
                revision));
    }

    private StoredDisposition mapDisposition(
            ResultSet row,
            int ignored) throws SQLException {
        try {
            AuthoritativeOutcomeSelectedPopulationDisposition
                    disposition = readDisposition(
                    row.getString(
                            "disposition_json"));
            dispositionIntegrity.verifyLocally(
                    disposition);
            CapabilitySnapshot.Scope scope =
                    disposition.scope();
            MirrorArtifactRef population =
                    disposition.populationRef();
            String predecessor = optionalFingerprint(
                    row.getString(
                            "predecessor_fingerprint"));
            if (!scope.tenantId().equals(
                    row.getString("tenant_id"))
                    || !scope.organizationId().equals(
                    row.getString("organization_id"))
                    || !scope.projectId().equals(
                    row.getString("project_id"))
                    || !scope.environmentId().equals(
                    row.getString("environment_id"))
                    || !scope.region().equals(
                    row.getString("region"))
                    || !disposition.dispositionId().equals(
                    row.getString("disposition_id"))
                    || disposition.revision()
                    != row.getLong("revision")
                    || !disposition
                    .dispositionFingerprint()
                    .equals(row.getString(
                            "disposition_fingerprint"))
                    || !population.id().equals(
                    row.getString("population_id"))
                    || population.revision()
                    != row.getLong(
                    "population_revision")
                    || !population.fingerprint().equals(
                    row.getString(
                            "population_fingerprint"))
                    || !disposition.unitId().equals(
                    row.getString("unit_id"))
                    || !disposition.stratumId().equals(
                    row.getString("stratum_id"))
                    || disposition.sampleOrdinal()
                    != row.getLong("sample_ordinal")
                    || !disposition.effectiveAt().equals(
                    instant(row, "effective_at"))
                    || (disposition.revision() == 1)
                    != predecessor.isBlank()) {
                throw new Violation(
                        Reason.STORED_STATE_CORRUPT);
            }
            return new StoredDisposition(
                    disposition, predecessor);
        } catch (Violation invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private Optional<StoredAssessment>
    findStoredAssessment(
            CapabilitySnapshot.Scope scope,
            String assessmentId,
            long revision) {
        return one(jdbc.query("""
                SELECT *
                FROM mirror_outcome_population_assessments
                WHERE tenant_id = ? AND organization_id = ?
                  AND project_id = ? AND environment_id = ?
                  AND region = ? AND assessment_id = ?
                  AND revision = ?
                """,
                this::mapAssessment,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                assessmentId,
                revision));
    }

    private StoredAssessment mapAssessment(
            ResultSet row,
            int ignored) throws SQLException {
        try {
            AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                    assessment = readAssessment(
                    row.getString(
                            "assessment_json"));
            projector.verifyAssessment(assessment);
            CapabilitySnapshot.Scope scope =
                    assessment.scope();
            MirrorArtifactRef population =
                    assessment.populationRef();
            String predecessor = optionalFingerprint(
                    row.getString(
                            "predecessor_fingerprint"));
            if (!scope.tenantId().equals(
                    row.getString("tenant_id"))
                    || !scope.organizationId().equals(
                    row.getString("organization_id"))
                    || !scope.projectId().equals(
                    row.getString("project_id"))
                    || !scope.environmentId().equals(
                    row.getString("environment_id"))
                    || !scope.region().equals(
                    row.getString("region"))
                    || !assessment.assessmentId().equals(
                    row.getString("assessment_id"))
                    || assessment.revision()
                    != row.getLong("revision")
                    || !assessment
                    .assessmentFingerprint()
                    .equals(row.getString(
                            "assessment_fingerprint"))
                    || !population.id().equals(
                    row.getString("population_id"))
                    || population.revision()
                    != row.getLong(
                    "population_revision")
                    || !population.fingerprint().equals(
                    row.getString(
                            "population_fingerprint"))
                    || !assessment
                    .observationSetFingerprint()
                    .equals(row.getString(
                            "observation_set_fingerprint"))
                    || !assessment
                    .dispositionSetFingerprint()
                    .equals(row.getString(
                            "disposition_set_fingerprint"))
                    || !assessment.assessedAt().equals(
                    instant(row, "assessed_at"))
                    || (assessment.revision() == 1)
                    != predecessor.isBlank()) {
                throw new Violation(
                        Reason.STORED_STATE_CORRUPT);
            }
            return new StoredAssessment(
                    assessment, predecessor);
        } catch (Violation invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private Optional<Head> findPopulationHead(
            CapabilitySnapshot.Scope scope,
            String populationId) {
        return one(jdbc.query("""
                SELECT current_revision,
                       current_population_fingerprint
                FROM mirror_outcome_selected_population_heads
                WHERE tenant_id = ? AND organization_id = ?
                  AND project_id = ? AND environment_id = ?
                  AND region = ? AND population_id = ?
                """,
                (row, ignored) -> new Head(
                        row.getLong(
                                "current_revision"),
                        row.getString(
                                "current_population_fingerprint")),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                populationId));
    }

    private Optional<Head> findDispositionHead(
            CapabilitySnapshot.Scope scope,
            String dispositionId) {
        return one(jdbc.query("""
                SELECT current_revision,
                       current_disposition_fingerprint
                FROM mirror_outcome_selected_disposition_heads
                WHERE tenant_id = ? AND organization_id = ?
                  AND project_id = ? AND environment_id = ?
                  AND region = ? AND disposition_id = ?
                """,
                (row, ignored) -> new Head(
                        row.getLong(
                                "current_revision"),
                        row.getString(
                                "current_disposition_fingerprint")),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                dispositionId));
    }

    private Optional<Head> findAssessmentHead(
            CapabilitySnapshot.Scope scope,
            String assessmentId) {
        return one(jdbc.query("""
                SELECT current_revision,
                       current_assessment_fingerprint
                FROM mirror_outcome_population_assessment_heads
                WHERE tenant_id = ? AND organization_id = ?
                  AND project_id = ? AND environment_id = ?
                  AND region = ? AND assessment_id = ?
                """,
                (row, ignored) -> new Head(
                        row.getLong(
                                "current_revision"),
                        row.getString(
                                "current_assessment_fingerprint")),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                assessmentId));
    }

    private static boolean sameCut(
            AssessmentCut left,
            AssessmentCut right) {
        return left.population().manifest().artifactRef()
                .equals(
                        right.population()
                                .manifest()
                                .artifactRef())
                && left.observationSetFingerprint()
                .equals(
                        right
                                .observationSetFingerprint())
                && left.dispositionSetFingerprint()
                .equals(
                        right
                                .dispositionSetFingerprint());
    }

    private static void verifyAssessmentMatches(
            AssessmentCut cut,
            AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                    assessment) {
        AuthoritativeOutcomeSelectedPopulationManifest
                population = cut.population().manifest();
        if (!assessment.scope().equals(
                population.scope())
                || !assessment.populationRef().equals(
                population.artifactRef())
                || !assessment
                .observationSetFingerprint()
                .equals(
                        cut.observationSetFingerprint())
                || !assessment
                .dispositionSetFingerprint()
                .equals(
                        cut.dispositionSetFingerprint())) {
            throw new Violation(
                    Reason.ASSESSMENT_MISMATCH);
        }
    }

    private static boolean sameDispositionMember(
            AuthoritativeOutcomeSelectedPopulationDisposition
                    previous,
            AuthoritativeOutcomeSelectedPopulationDisposition
                    successor) {
        return previous.scope().equals(
                successor.scope())
                && previous.populationRef().equals(
                successor.populationRef())
                && previous.unitId().equals(
                successor.unitId())
                && previous.stratumId().equals(
                successor.stratumId())
                && previous.sampleOrdinal()
                == successor.sampleOrdinal()
                && previous.inclusionFingerprint()
                .equals(
                        successor
                                .inclusionFingerprint())
                && previous.subjectFingerprint()
                .equals(
                        successor
                                .subjectFingerprint())
                && previous
                .attributionKeyFingerprint()
                .equals(
                        successor
                                .attributionKeyFingerprint());
    }

    private static Map<MemberKey, MemberCoordinate>
    members(Population population) {
        Map<MemberKey, MemberCoordinate> result =
                new LinkedHashMap<>();
        for (AuthoritativeOutcomeSelectedPopulationChunk
                chunk : population.chunks()) {
            for (AuthoritativeOutcomeSelectedPopulationChunk
                    .Member member : chunk.members()) {
                MemberKey key = new MemberKey(
                        member.unitId(),
                        member.stratumId(),
                        member.sampleOrdinal());
                if (result.putIfAbsent(
                        key,
                        new MemberCoordinate(
                                key,
                                member.globalOrdinal(),
                                member.inclusionFingerprint(),
                                member.subjectFingerprint(),
                                member
                                        .attributionKeyFingerprint()))
                        != null) {
                    throw new Violation(
                            Reason.STORED_STATE_CORRUPT);
                }
            }
        }
        return Map.copyOf(result);
    }

    private AuthoritativeOutcomeSelectedPopulationManifest
    readManifest(String json) {
        try {
            return mapper.readValue(
                    json,
                    AuthoritativeOutcomeSelectedPopulationManifest
                            .class);
        } catch (JsonProcessingException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private AuthoritativeOutcomeSelectedPopulationChunk
    readChunk(String json) {
        try {
            return mapper.readValue(
                    json,
                    AuthoritativeOutcomeSelectedPopulationChunk
                            .class);
        } catch (JsonProcessingException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private AuthoritativeOutcomeObservation
    readObservation(String json) {
        try {
            return observationIntegrity.verifyLocally(
                    mapper.readValue(
                            json,
                            AuthoritativeOutcomeObservation
                                    .class));
        } catch (AuthoritativeOutcomeObservationIntegrity
                 .Violation invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        } catch (JsonProcessingException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private AuthoritativeOutcomeSelectedPopulationDisposition
    readDisposition(String json) {
        try {
            return dispositionIntegrity.verifyLocally(
                    mapper.readValue(
                            json,
                            AuthoritativeOutcomeSelectedPopulationDisposition
                                    .class));
        } catch (AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                 .Violation invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        } catch (JsonProcessingException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private
    AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
    readAssessment(String json) {
        try {
            return projector.verifyAssessment(
                    mapper.readValue(
                            json,
                            AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                                    .class));
        } catch (AuthoritativeOutcomeSelectedPopulationCompletenessProjector
                 .Violation invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        } catch (JsonProcessingException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private void lockPartition(
            String region,
            String environmentId) {
        Long existing = jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM mirror_outcome_inbox_locks
                        WHERE region = ? AND environment_id = ?
                        """,
                Long.class,
                region,
                environmentId);
        if (existing == null || existing == 0) {
            beforeLockRowInsert.run();
            try {
                lockRowInitialization.executeWithoutResult(
                        ignored -> jdbc.update("""
                                        INSERT INTO mirror_outcome_inbox_locks (
                                            region, environment_id
                                        ) VALUES (?, ?)
                                        """,
                                region,
                                environmentId));
            } catch (DuplicateKeyException exists) {
                // A concurrent initializer won; savepoint rollback keeps the caller usable.
            }
        }
        jdbc.queryForObject("""
                        SELECT region
                        FROM mirror_outcome_inbox_locks
                        WHERE region = ? AND environment_id = ?
                        FOR UPDATE
                        """,
                String.class,
                region,
                environmentId);
    }

    private Instant coordinationNow() {
        return Objects.requireNonNull(
                coordinationClock.get(),
                "database clock returned null");
    }

    private static Instant databaseNow(
            JdbcTemplate jdbc) {
        Timestamp value = jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP",
                Timestamp.class);
        return Objects.requireNonNull(
                value,
                "database clock returned null").toInstant();
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
                    "Selected-population repository requires one nested-savepoint "
                            + "DataSourceTransactionManager for its JdbcTemplate");
        }
        return exact;
    }

    private static String optionalFingerprint(
            String value) {
        String exact = value == null
                ? "" : value.trim();
        if (!exact.isBlank()
                && !FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    "predecessor fingerprint is invalid");
        }
        return exact;
    }

    private static String identifier(String value) {
        String exact = value == null
                ? "" : value.trim();
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    "identifier is invalid");
        }
        return exact;
    }

    private static Timestamp timestamp(
            Instant value) {
        return Timestamp.from(
                Objects.requireNonNull(value, "value"));
    }

    private static Instant instant(
            ResultSet row,
            String column) throws SQLException {
        return Objects.requireNonNull(
                row.getTimestamp(column),
                column).toInstant();
    }

    private static <T> Optional<T> one(
            List<T> values) {
        if (values.size() > 1) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
        return values.stream().findFirst();
    }

    private static <T> T required(
            T value, String operation) {
        return Objects.requireNonNull(
                value, operation + " returned null");
    }

    private record Head(
            long revision,
            String fingerprint
    ) {
        private Head {
            if (revision < 1
                    || fingerprint == null
                    || !FINGERPRINT.matcher(
                    fingerprint).matches()) {
                throw new Violation(
                        Reason.STORED_STATE_CORRUPT);
            }
        }
    }

    private record StoredPopulation(
            Population population,
            String predecessorFingerprint
    ) {
    }

    private record PopulationRow(
            AuthoritativeOutcomeSelectedPopulationManifest
                    manifest,
            String predecessorFingerprint,
            String tenantId,
            String organizationId,
            String projectId,
            String environmentId,
            String region,
            String populationId,
            long revision,
            String populationFingerprint,
            String inventoryId,
            long inventoryRevision,
            String inventoryFingerprint,
            String cohortId,
            long cohortRevision,
            String cohortFingerprint,
            Instant selectedAt
    ) {
    }

    private record StoredDisposition(
            AuthoritativeOutcomeSelectedPopulationDisposition
                    disposition,
            String predecessorFingerprint
    ) {
    }

    private record StoredAssessment(
            AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                    assessment,
            String predecessorFingerprint
    ) {
    }

    private record StoredCurrentObservation(
            String headFingerprint,
            AuthoritativeOutcomeObservation observation
    ) {
    }

    private record StoredCurrentDisposition(
            String headFingerprint,
            AuthoritativeOutcomeSelectedPopulationDisposition
                    disposition
    ) {
    }

    private record StratumKey(
            String unitId,
            String stratumId
    ) {
    }

    private record MemberKey(
            String unitId,
            String stratumId,
            long sampleOrdinal
    ) {
        private StratumKey stratumKey() {
            return new StratumKey(
                    unitId, stratumId);
        }
    }

    private record MemberCoordinate(
            MemberKey key,
            long globalOrdinal,
            String inclusionFingerprint,
            String subjectFingerprint,
            String attributionFingerprint
    ) {
    }

    private record PositionedObservation(
            long globalOrdinal,
            AuthoritativeOutcomeObservation observation
    ) {
    }

    private record PositionedDisposition(
            long globalOrdinal,
            AuthoritativeOutcomeSelectedPopulationDisposition
                    disposition
    ) {
    }

    private record IndexedMember(
            long globalOrdinal,
            String unitId,
            String stratumId,
            long sampleOrdinal,
            String inclusionFingerprint,
            String subjectFingerprint,
            String attributionFingerprint
    ) {
        private static IndexedMember from(
                AuthoritativeOutcomeSelectedPopulationChunk
                        .Member member) {
            return new IndexedMember(
                    member.globalOrdinal(),
                    member.unitId(),
                    member.stratumId(),
                    member.sampleOrdinal(),
                    member.inclusionFingerprint(),
                    member.subjectFingerprint(),
                    member.attributionKeyFingerprint());
        }
    }
}
