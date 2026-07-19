package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Durable governed-finding lifecycle derived from completed external inventory comparisons.
 *
 * <p>One authority is projected in the strict order established by the comparison control plane.
 * Projection start freezes every current finding, and each bounded page atomically applies exactly
 * one transition for each source classification. Completion independently replays the source,
 * frozen pre-state, transition events, and resulting current findings before publishing evidence.
 * A crash or replica switch resumes the durable cursor without duplicating an event.</p>
 *
 * <p>The control plane stores only topology-free object identities, protocol fingerprints,
 * outcomes, lifecycle counters, and database times. It deliberately exposes no WORM mutation,
 * remediation, delete, purge, legal-hold release, or retention-shortening operation.</p>
 */
public final class DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane {
    /** Domain-separated root before the first frozen finding. */
    public static final String EMPTY_SNAPSHOT_ROOT = ProtocolFingerprint.ofText(
            "bloge.testSuiteStabilityObservationExternalArchiveFindingSnapshotRoot.v1:empty");

    /** Domain-separated root before the first finding transition event. */
    public static final String EMPTY_EVENT_ROOT = ProtocolFingerprint.ofText(
            "bloge.testSuiteStabilityObservationExternalArchiveFindingEventRoot.v1:empty");

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern OBJECT_ID =
            Pattern.compile("stability-observation-worm-[a-f0-9]{64}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Settings settings;

    /**
     * Creates a finding control plane with a transaction manager derived from the JDBC datasource.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical protocol mapper
     * @param settings bounded transition-page settings
     */
    public DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            Settings settings) {
        this(jdbc, localTransactionManager(jdbc), objectMapper, settings);
    }

    /**
     * Creates a finding control plane over the classification datasource.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param transactionManager transaction manager bound to the JDBC datasource
     * @param objectMapper canonical protocol mapper
     * @param settings bounded transition-page settings
     */
    public DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            Settings settings) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.settings = Objects.requireNonNull(settings, "settings");
        transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Creates finding authority, projection, snapshot, current-state, and event tables. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_observation_external_finding_authorities (
                    authority_id VARCHAR(255) PRIMARY KEY,
                    active_projection_id VARCHAR(36) NOT NULL,
                    last_completed_projection_id VARCHAR(36) NOT NULL,
                    last_applied_comparison_id VARCHAR(36) NOT NULL,
                    last_applied_comparison_completed_at TIMESTAMP WITH TIME ZONE,
                    revision BIGINT NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_observation_external_finding_projections (
                    projection_id VARCHAR(36) PRIMARY KEY,
                    comparison_id VARCHAR(36) NOT NULL,
                    authority_id VARCHAR(255) NOT NULL,
                    projection_status VARCHAR(32) NOT NULL,
                    comparison_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    comparison_completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    source_classification_count BIGINT NOT NULL,
                    source_classification_root VARCHAR(71) NOT NULL,
                    snapshot_finding_count BIGINT NOT NULL,
                    snapshot_root VARCHAR(71) NOT NULL,
                    next_after_object_id VARCHAR(255) NOT NULL,
                    next_page_sequence BIGINT NOT NULL,
                    processed_classification_count BIGINT NOT NULL,
                    opened_count BIGINT NOT NULL,
                    observed_count BIGINT NOT NULL,
                    reopened_count BIGINT NOT NULL,
                    resolved_count BIGINT NOT NULL,
                    confirmed_count BIGINT NOT NULL,
                    event_root VARCHAR(71) NOT NULL,
                    revision BIGINT NOT NULL,
                    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    completed_at TIMESTAMP WITH TIME ZONE,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    CONSTRAINT uq_rg_test_suite_stability_observation_external_finding_comparison
                        UNIQUE (comparison_id)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS
                    idx_rg_test_suite_stability_observation_external_finding_projection_authority
                ON rg_test_suite_stability_observation_external_finding_projections (
                    authority_id, comparison_started_at, projection_id
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_observation_external_findings (
                    authority_id VARCHAR(255) NOT NULL,
                    object_id VARCHAR(255) NOT NULL,
                    finding_status VARCHAR(32) NOT NULL,
                    finding_kind VARCHAR(32) NOT NULL,
                    latest_comparison_id VARCHAR(36) NOT NULL,
                    latest_outcome VARCHAR(32) NOT NULL,
                    latest_classification_fingerprint VARCHAR(71) NOT NULL,
                    occurrence_count BIGINT NOT NULL,
                    episode_count BIGINT NOT NULL,
                    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    last_observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    last_evaluated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    resolution VARCHAR(32) NOT NULL,
                    resolved_at TIMESTAMP WITH TIME ZONE,
                    finding_version BIGINT NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    PRIMARY KEY (authority_id, object_id)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS
                    idx_rg_test_suite_stability_observation_external_finding_queue
                ON rg_test_suite_stability_observation_external_findings (
                    authority_id, finding_status, last_evaluated_at, object_id
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_observation_external_finding_snapshots (
                    projection_id VARCHAR(36) NOT NULL,
                    authority_id VARCHAR(255) NOT NULL,
                    object_id VARCHAR(255) NOT NULL,
                    finding_status VARCHAR(32) NOT NULL,
                    finding_kind VARCHAR(32) NOT NULL,
                    latest_comparison_id VARCHAR(36) NOT NULL,
                    latest_outcome VARCHAR(32) NOT NULL,
                    latest_classification_fingerprint VARCHAR(71) NOT NULL,
                    occurrence_count BIGINT NOT NULL,
                    episode_count BIGINT NOT NULL,
                    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    last_observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    last_evaluated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    resolution VARCHAR(32) NOT NULL,
                    resolved_at TIMESTAMP WITH TIME ZONE,
                    finding_version BIGINT NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    PRIMARY KEY (projection_id, object_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_observation_external_finding_events (
                    projection_id VARCHAR(36) NOT NULL,
                    comparison_id VARCHAR(36) NOT NULL,
                    authority_id VARCHAR(255) NOT NULL,
                    object_id VARCHAR(255) NOT NULL,
                    page_sequence BIGINT NOT NULL,
                    classification_outcome VARCHAR(32) NOT NULL,
                    classification_fingerprint VARCHAR(71) NOT NULL,
                    transition VARCHAR(32) NOT NULL,
                    previous_finding_fingerprint VARCHAR(71) NOT NULL,
                    resulting_finding_fingerprint VARCHAR(71) NOT NULL,
                    resulting_finding_version BIGINT NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    event_fingerprint VARCHAR(71) NOT NULL,
                    PRIMARY KEY (projection_id, object_id),
                    CONSTRAINT uq_rg_test_suite_stability_observation_external_finding_event_source
                        UNIQUE (comparison_id, object_id),
                    CONSTRAINT uq_rg_test_suite_stability_observation_external_finding_event_hash
                        UNIQUE (projection_id, event_fingerprint)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS
                    idx_rg_test_suite_stability_observation_external_finding_event_page
                ON rg_test_suite_stability_observation_external_finding_events (
                    projection_id, page_sequence, object_id
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_observation_external_finding_evidence_retirements (
                    projection_id VARCHAR(36) PRIMARY KEY,
                    comparison_id VARCHAR(36) NOT NULL,
                    authority_id VARCHAR(255) NOT NULL,
                    retirement_status VARCHAR(32) NOT NULL,
                    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    completed_at TIMESTAMP WITH TIME ZONE,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    CONSTRAINT
                        uq_rg_test_suite_stability_observation_external_finding_retirement_source
                        UNIQUE (comparison_id)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS
                    idx_rg_test_suite_stability_observation_external_finding_retirement_authority
                ON rg_test_suite_stability_observation_external_finding_evidence_retirements (
                    authority_id, retirement_status, started_at, projection_id
                )
                """);
    }

    /**
     * Starts or advances one bounded finding-projection page for an authority.
     *
     * <p>Completed comparisons are consumed in strict comparison-start order. A
     * {@link ProjectionStatus#CURRENT} result means that no unprojected completed comparison
     * exists and no state changed.</p>
     *
     * @param authorityId exact inventory authority
     * @return payload-free projection progress
     */
    public ProjectionPage projectNextPage(String authorityId) {
        String authority = requiredIdentifier(authorityId, "finding authority");
        ProjectionPage result = transactions.execute(status -> projectInTransaction(authority));
        if (result == null) {
            throw new IllegalStateException("External finding projection returned no result");
        }
        return result;
    }

    /**
     * Exports one verified page of current findings when no projection is partially applied.
     *
     * @param authorityId exact inventory authority
     * @param afterObjectId exclusive object-id cursor, or empty for the first page
     * @param limit maximum findings to return, from 1 through 500
     * @return current payload-free findings in strict object-id order
     */
    public List<Finding> findings(String authorityId, String afterObjectId, int limit) {
        String authority = requiredIdentifier(authorityId, "finding authority");
        String cursor = exportCursor(afterObjectId);
        int bounded = exportLimit(limit);
        List<Finding> result = transactions.execute(status -> {
            StoredAuthority state = readAuthority(authority, false);
            if (!state.activeProjectionId().isEmpty()) {
                throw new IllegalStateException(
                        "External findings are unavailable during an active projection");
            }
            List<Finding> rows = jdbc.query("""
                    SELECT authority_id, object_id, finding_status, finding_kind,
                           latest_comparison_id, latest_outcome,
                           latest_classification_fingerprint, occurrence_count, episode_count,
                           first_seen_at, last_observed_at, last_evaluated_at, resolution,
                           resolved_at, finding_version, record_fingerprint
                    FROM rg_test_suite_stability_observation_external_findings
                    WHERE authority_id = ? AND object_id > ?
                    ORDER BY object_id
                    LIMIT ?
                    """, (rowSet, row) -> finding(rowSet, row), authority, cursor, bounded);
            verifyOrderedFindings(rows, cursor);
            return List.copyOf(rows);
        });
        return requiredResult(result, "finding export");
    }

    /**
     * Exports one verified page of immutable events from a completed projection.
     *
     * @param projectionId completed finding projection identity
     * @param afterObjectId exclusive object-id cursor, or empty for the first page
     * @param limit maximum events to return, from 1 through 500
     * @return transition evidence in strict source object-id order
     */
    public List<FindingEvent> events(String projectionId, String afterObjectId, int limit) {
        String projection = requiredUuid(projectionId, "finding projection ID");
        String cursor = exportCursor(afterObjectId);
        int bounded = exportLimit(limit);
        List<FindingEvent> result = transactions.execute(status -> {
            StoredProjection state = readProjection(projection, true);
            state.requireCompleted();
            requireEvidenceAvailable(state);
            SourceComparison source = readSourceComparison(state.comparisonId());
            if (!state.matches(source)) {
                throw new IllegalStateException("External finding event source drifted");
            }
            verifySource(source);
            EventReplay replay = replayEvents(state);
            if (replay.count() != state.processedCount()
                    || !replay.root().equals(state.eventRoot())
                    || !replay.counts().equals(state.transitionCounts())) {
                throw new IllegalStateException("External finding event export replay failed");
            }
            verifyEventCoverage(state);
            List<FindingEvent> rows = jdbc.query("""
                    SELECT projection_id, comparison_id, authority_id, object_id,
                           classification_outcome, classification_fingerprint, transition,
                           previous_finding_fingerprint, resulting_finding_fingerprint,
                           resulting_finding_version, occurred_at, event_fingerprint
                    FROM rg_test_suite_stability_observation_external_finding_events
                    WHERE projection_id = ? AND object_id > ?
                    ORDER BY object_id
                    LIMIT ?
                    """, (rowSet, row) -> event(rowSet, row), projection, cursor, bounded);
            verifyOrderedEvents(rows, state, cursor);
            return List.copyOf(rows);
        });
        return requiredResult(result, "finding event export");
    }

    private ProjectionPage projectInTransaction(String authorityId) {
        initializeAuthority(authorityId);
        StoredAuthority authority = readAuthority(authorityId, true);
        Instant now = nextDatabaseTime(authority.updatedAt());
        StoredProjection projection;
        if (authority.activeProjectionId().isEmpty()) {
            SourceComparison source = nextComparison(authority);
            if (source == null) {
                return ProjectionPage.current(authority);
            }
            verifySource(source);
            projection = createProjection(authority, source, now);
            activateProjection(authority, projection.projectionId(), now);
        } else {
            projection = readProjection(authority.activeProjectionId(), true);
            projection.requireActiveFor(authorityId);
        }
        return stagePage(readProjection(projection.projectionId(), true), now);
    }

    private StoredProjection createProjection(
            StoredAuthority authority,
            SourceComparison source,
            Instant now) {
        if (authority.lastAppliedComparisonCompletedAt() != null
                && !source.startedAt().isAfter(authority.lastAppliedComparisonCompletedAt())) {
            throw new IllegalStateException(
                    "External finding comparison chronology is ambiguous or stale");
        }
        String projectionId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO
                        rg_test_suite_stability_observation_external_finding_snapshots (
                        projection_id, authority_id, object_id, finding_status, finding_kind,
                        latest_comparison_id, latest_outcome,
                        latest_classification_fingerprint, occurrence_count, episode_count,
                        first_seen_at, last_observed_at, last_evaluated_at, resolution,
                        resolved_at, finding_version, record_fingerprint
                    )
                    SELECT ?, authority_id, object_id, finding_status, finding_kind,
                           latest_comparison_id, latest_outcome,
                           latest_classification_fingerprint, occurrence_count, episode_count,
                           first_seen_at, last_observed_at, last_evaluated_at, resolution,
                           resolved_at, finding_version, record_fingerprint
                    FROM rg_test_suite_stability_observation_external_findings
                    WHERE authority_id = ?
                    """, projectionId, authority.authorityId());
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("External finding snapshot is not unique", duplicate);
        }
        SnapshotReplay snapshot = replaySnapshot(projectionId, authority.authorityId());
        StoredProjection initial = StoredProjection.initial(
                projectionId, source, snapshot.count(), snapshot.root(), now);
        initial = initial.withFingerprint(projectionFingerprint(initial.fingerprintMaterial()));
        try {
            int inserted = jdbc.update("""
                    INSERT INTO
                        rg_test_suite_stability_observation_external_finding_projections (
                        projection_id, comparison_id, authority_id, projection_status,
                        comparison_started_at, comparison_completed_at,
                        source_classification_count, source_classification_root,
                        snapshot_finding_count, snapshot_root, next_after_object_id,
                        next_page_sequence, processed_classification_count, opened_count,
                        observed_count, reopened_count, resolved_count, confirmed_count,
                        event_root, revision, started_at, completed_at, updated_at,
                        record_fingerprint
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                              ?, ?, ?)
                    """, initial.sqlArguments());
            if (inserted != 1) {
                throw new IllegalStateException("External finding projection insert was incomplete");
            }
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException(
                    "External comparison already has a finding projection", duplicate);
        }
        return readProjection(projectionId, true);
    }

    private ProjectionPage stagePage(StoredProjection current, Instant now) {
        current.requireActiveFor(current.authorityId());
        SourceComparison source = readSourceComparison(current.comparisonId());
        if (!current.matches(source)) {
            throw new IllegalStateException("External finding projection source drifted");
        }
        List<SourceClassification> page = classificationPage(current);
        if (page.isEmpty()) {
            if (current.processedCount() != current.sourceCount()) {
                throw new IllegalStateException(
                        "External finding projection source ended before its declared count");
            }
            return completeProjection(current, List.of(), now, current.nextPageSequence());
        }
        String root = current.eventRoot();
        TransitionCounts counts = current.transitionCounts();
        for (SourceClassification classification : page) {
            Finding previous = snapshotFinding(
                    current.projectionId(), classification.objectId());
            Finding live = currentFinding(current.authorityId(), classification.objectId(), true);
            requireSameFinding(previous, live, "frozen finding pre-state");
            TransitionResult transition = transition(
                    current, classification, previous, now);
            applyFinding(previous, transition.resultingFinding());
            insertEvent(current, transition.event(), now);
            root = appendEventRoot(objectMapper, root, transition.event());
            counts = counts.increment(transition.event().transition());
        }
        String cursor = page.getLast().objectId();
        long processed = add(current.processedCount(), page.size(), "processed classification count");
        long sequence = increment(current.nextPageSequence(), "finding projection page sequence");
        StoredProjection successor = current.progressed(
                cursor, sequence, processed, counts, root, now);
        boolean complete = processed == current.sourceCount()
                && !hasClassificationAfter(current.comparisonId(), cursor);
        if (complete) {
            return completeProjection(successor, page, now, current.nextPageSequence());
        }
        if (processed >= current.sourceCount()) {
            throw new IllegalStateException(
                    "External finding projection exceeded its declared source count");
        }
        updateProjection(current, successor);
        return new ProjectionPage(ProjectionStatus.STAGED, current.authorityId(),
                current.projectionId(), current.comparisonId(), current.nextPageSequence(),
                page.size(), successor.processedCount(), successor.actionableTransitions());
    }

    private ProjectionPage completeProjection(
            StoredProjection progressed,
            List<SourceClassification> page,
            Instant now,
            long attemptedPageSequence) {
        SourceComparison source = readSourceComparison(progressed.comparisonId());
        if (!progressed.matches(source)) {
            throw new IllegalStateException("External finding projection source changed");
        }
        verifySource(source);
        SnapshotReplay snapshot = replaySnapshot(
                progressed.projectionId(), progressed.authorityId());
        if (snapshot.count() != progressed.snapshotCount()
                || !snapshot.root().equals(progressed.snapshotRoot())) {
            throw new IllegalStateException("External finding snapshot replay failed");
        }
        EventReplay events = replayEvents(progressed);
        if (events.count() != progressed.processedCount()
                || !events.root().equals(progressed.eventRoot())
                || !events.counts().equals(progressed.transitionCounts())) {
            throw new IllegalStateException("External finding event replay failed");
        }
        if (source.classificationCount() != progressed.processedCount()) {
            throw new IllegalStateException("External finding projection coverage is incomplete");
        }
        verifyEventCoverage(progressed);
        verifyResultingFindings(progressed);
        StoredProjection completed = progressed.completed(now);
        updateProjection(readProjection(progressed.projectionId(), true), completed);
        StoredAuthority authority = readAuthority(progressed.authorityId(), true);
        if (!authority.activeProjectionId().equals(progressed.projectionId())) {
            throw new IllegalStateException("External finding authority fence is stale");
        }
        StoredAuthority successor = authority.completed(completed, now);
        updateAuthority(authority, successor);
        return new ProjectionPage(ProjectionStatus.COMPLETED, progressed.authorityId(),
                progressed.projectionId(), progressed.comparisonId(), attemptedPageSequence,
                page.size(), completed.processedCount(), completed.actionableTransitions());
    }

    private TransitionResult transition(
            StoredProjection projection,
            SourceClassification classification,
            Finding previous,
            Instant now) {
        boolean matched = classification.outcome()
                == DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                .Outcome.MATCHED;
        Transition kind;
        Finding resulting;
        if (matched && previous == null) {
            kind = Transition.CONFIRMED;
            resulting = null;
        } else if (matched && previous.status() == FindingStatus.RESOLVED) {
            kind = Transition.CONFIRMED;
            resulting = previous;
        } else if (matched) {
            kind = Transition.RESOLVED;
            resulting = previous.resolved(
                    projection.comparisonId(), classification, now, objectMapper);
        } else if (previous == null) {
            kind = Transition.OPENED;
            resulting = Finding.opened(projection.authorityId(), projection.comparisonId(),
                    classification, now, objectMapper);
        } else if (previous.status() == FindingStatus.RESOLVED) {
            kind = Transition.REOPENED;
            resulting = previous.reopened(
                    projection.comparisonId(), classification, now, objectMapper);
        } else {
            kind = Transition.OBSERVED;
            resulting = previous.observed(
                    projection.comparisonId(), classification, now, objectMapper);
        }
        FindingEvent material = new FindingEvent(
                projection.projectionId(), projection.comparisonId(), projection.authorityId(),
                classification.objectId(), classification.outcome(),
                classification.fingerprint(), kind,
                previous == null ? "" : previous.recordFingerprint(),
                resulting == null ? "" : resulting.recordFingerprint(),
                resulting == null ? 0 : resulting.version(), now, "");
        FindingEvent event = material.withFingerprint(ProtocolFingerprint.of(
                objectMapper, material.fingerprintMaterial()));
        return new TransitionResult(resulting, event);
    }

    private void applyFinding(Finding previous, Finding resulting) {
        if (previous == null && resulting == null) {
            return;
        }
        if (previous == null) {
            try {
                int inserted = jdbc.update("""
                        INSERT INTO rg_test_suite_stability_observation_external_findings (
                            authority_id, object_id, finding_status, finding_kind,
                            latest_comparison_id, latest_outcome,
                            latest_classification_fingerprint, occurrence_count, episode_count,
                            first_seen_at, last_observed_at, last_evaluated_at, resolution,
                            resolved_at, finding_version, record_fingerprint
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, findingArguments(Objects.requireNonNull(resulting, "resulting")));
                if (inserted != 1) {
                    throw new IllegalStateException("External finding insert was incomplete");
                }
                return;
            } catch (DuplicateKeyException duplicate) {
                throw new IllegalStateException("External finding identity is duplicated", duplicate);
            }
        }
        if (resulting == null) {
            throw new IllegalStateException("Existing external finding cannot disappear");
        }
        if (previous.recordFingerprint().equals(resulting.recordFingerprint())) {
            return;
        }
        int updated = jdbc.update("""
                UPDATE rg_test_suite_stability_observation_external_findings
                SET finding_status = ?, finding_kind = ?, latest_comparison_id = ?,
                    latest_outcome = ?, latest_classification_fingerprint = ?,
                    occurrence_count = ?, episode_count = ?, first_seen_at = ?,
                    last_observed_at = ?, last_evaluated_at = ?, resolution = ?,
                    resolved_at = ?, finding_version = ?, record_fingerprint = ?
                WHERE authority_id = ? AND object_id = ? AND finding_version = ?
                  AND record_fingerprint = ?
                """, resulting.status().name(), resulting.kind().name(),
                resulting.latestComparisonId(), resulting.latestOutcome().name(),
                resulting.latestClassificationFingerprint(), resulting.occurrences(),
                resulting.episodes(), Timestamp.from(resulting.firstSeenAt()),
                Timestamp.from(resulting.lastObservedAt()),
                Timestamp.from(resulting.lastEvaluatedAt()), resulting.resolution().name(),
                timestamp(resulting.resolvedAt()), resulting.version(),
                resulting.recordFingerprint(), previous.authorityId(), previous.objectId(),
                previous.version(), previous.recordFingerprint());
        if (updated != 1) {
            throw new IllegalStateException("External finding state fence was rejected");
        }
    }

    private void insertEvent(StoredProjection projection, FindingEvent event, Instant now) {
        try {
            int inserted = jdbc.update("""
                    INSERT INTO
                        rg_test_suite_stability_observation_external_finding_events (
                        projection_id, comparison_id, authority_id, object_id, page_sequence,
                        classification_outcome, classification_fingerprint, transition,
                        previous_finding_fingerprint, resulting_finding_fingerprint,
                        resulting_finding_version, occurred_at, event_fingerprint
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, projection.projectionId(), projection.comparisonId(),
                    projection.authorityId(), event.objectId(), projection.nextPageSequence(),
                    event.classificationOutcome().name(), event.classificationFingerprint(),
                    event.transition().name(), event.previousFindingFingerprint(),
                    event.resultingFindingFingerprint(), event.resultingFindingVersion(),
                    Timestamp.from(now), event.eventFingerprint());
            if (inserted != 1) {
                throw new IllegalStateException("External finding event insert was incomplete");
            }
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("External finding event identity is duplicated", duplicate);
        }
    }

    private void verifySource(SourceComparison source) {
        ClassificationReplay replay = replayClassifications(source);
        if (replay.count() != source.classificationCount()
                || !replay.root().equals(source.classificationRoot())
                || !replay.counts().equals(source.outcomeCounts())) {
            throw new IllegalStateException("External finding source comparison replay failed");
        }
        verifySourceSemantics(source);
    }

    private void verifySourceSemantics(SourceComparison source) {
        long[] count = {0};
        String[] previous = {""};
        String observedTopology = topologyFingerprint(source.trustDomain(), source.archiveSetId(),
                source.authorityId(), source.failureDomain());
        jdbc.query("""
                SELECT ids.object_id AS source_object_id,
                       expected.authority_id AS e_authority_id,
                       expected.trust_domain AS e_trust_domain,
                       expected.archive_set_id AS e_archive_set_id,
                       expected.failure_domain AS e_failure_domain,
                       expected.item_fingerprint AS e_item_fingerprint,
                       expected.object_commitment AS e_object_commitment,
                       expected.retain_until AS e_retain_until,
                       observed.item_fingerprint AS o_item_fingerprint,
                       observed.object_commitment AS o_object_commitment,
                       observed.retain_until AS o_retain_until,
                       classified.comparison_id AS c_comparison_id,
                       classified.cycle_id AS c_cycle_id,
                       classified.authority_id AS c_authority_id,
                       classified.outcome AS c_outcome,
                       classified.expected_item_fingerprint AS c_expected_item_fingerprint,
                       classified.observed_item_fingerprint AS c_observed_item_fingerprint,
                       classified.expected_object_commitment AS c_expected_object_commitment,
                       classified.observed_object_commitment AS c_observed_object_commitment,
                       classified.expected_topology_fingerprint AS c_expected_topology_fingerprint,
                       classified.observed_topology_fingerprint AS c_observed_topology_fingerprint,
                       classified.expected_retain_until AS c_expected_retain_until,
                       classified.observed_retain_until AS c_observed_retain_until,
                       classified.classification_fingerprint AS c_classification_fingerprint
                FROM (
                    SELECT object_id
                    FROM rg_test_suite_stability_observation_external_expected_snapshots
                    WHERE comparison_id = ?
                    UNION
                    SELECT object_id
                    FROM rg_test_suite_stability_observation_external_inventory_items
                    WHERE cycle_id = ?
                ) ids
                LEFT JOIN rg_test_suite_stability_observation_external_expected_snapshots expected
                  ON expected.comparison_id = ? AND expected.object_id = ids.object_id
                LEFT JOIN rg_test_suite_stability_observation_external_inventory_items observed
                  ON observed.cycle_id = ? AND observed.object_id = ids.object_id
                LEFT JOIN rg_test_suite_stability_observation_external_classifications classified
                  ON classified.comparison_id = ? AND classified.object_id = ids.object_id
                ORDER BY ids.object_id
                """, (RowCallbackHandler) result -> {
                    String objectId = result.getString("source_object_id");
                    if (previous[0].compareTo(objectId) >= 0) {
                        throw new IllegalStateException(
                                "External finding source semantic order is corrupt");
                    }
                    previous[0] = objectId;
                    SourceClassification actual = nullableClassification(result, "c_", objectId);
                    if (actual == null) {
                        throw new IllegalStateException(
                                "External finding source semantic classification is missing");
                    }
                    String expectedItem = normalized(result.getString("e_item_fingerprint"));
                    String observedItem = normalized(result.getString("o_item_fingerprint"));
                    String expectedCommitment = normalized(
                            result.getString("e_object_commitment"));
                    String observedCommitment = normalized(
                            result.getString("o_object_commitment"));
                    Instant expectedRetainUntil = nullableInstant(result, "e_retain_until");
                    Instant observedRetainUntil = nullableInstant(result, "o_retain_until");
                    String expectedTopology = expectedItem.isEmpty() ? "" : topologyFingerprint(
                            result.getString("e_trust_domain"),
                            result.getString("e_archive_set_id"),
                            result.getString("e_authority_id"),
                            result.getString("e_failure_domain"));
                    var outcome = classifySource(expectedItem, observedItem, expectedTopology,
                            observedTopology, expectedRetainUntil, observedRetainUntil);
                    var material = new ClassificationMaterial(
                            ClassificationMaterial.SCHEMA_VERSION, source.comparisonId(),
                            source.cycleId(), source.authorityId(), objectId, outcome, expectedItem,
                            observedItem, expectedCommitment, observedCommitment, expectedTopology,
                            observedTopology, expectedRetainUntil, observedRetainUntil);
                    String fingerprint = ProtocolFingerprint.of(objectMapper, material);
                    var expected = new
                            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                            .Classification(source.comparisonId(), source.cycleId(),
                            source.authorityId(), objectId, outcome, expectedItem, observedItem,
                            expectedCommitment, observedCommitment, expectedTopology,
                            observedTopology, expectedRetainUntil, observedRetainUntil, fingerprint);
                    if (!expected.equals(actual.value())) {
                        throw new IllegalStateException(
                                "External finding source semantic classification drifted");
                    }
                    count[0] = increment(count[0], "semantic classification count");
                }, source.comparisonId(), source.cycleId(), source.comparisonId(),
                source.cycleId(), source.comparisonId());
        if (count[0] != source.classificationCount()) {
            throw new IllegalStateException(
                    "External finding source semantic coverage is incomplete");
        }
    }

    private DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane.Outcome
            classifySource(
            String expectedItem,
            String observedItem,
            String expectedTopology,
            String observedTopology,
            Instant expectedRetainUntil,
            Instant observedRetainUntil) {
        if (expectedItem.isEmpty()) {
            return DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Outcome.UNEXPECTED_REMOTE;
        }
        if (observedItem.isEmpty()) {
            return DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Outcome.MISSING_REMOTE;
        }
        if (!expectedTopology.equals(observedTopology)) {
            return DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Outcome.UNKNOWN;
        }
        if (expectedItem.equals(observedItem)) {
            return DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Outcome.MATCHED;
        }
        if (observedRetainUntil != null && expectedRetainUntil != null
                && observedRetainUntil.isBefore(expectedRetainUntil)) {
            return DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Outcome.RETENTION_SHORTENED;
        }
        return DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                .Outcome.MATERIAL_CONFLICT;
    }

    private ClassificationReplay replayClassifications(SourceComparison source) {
        long[] count = {0};
        String[] root = {DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                .EMPTY_CLASSIFICATION_ROOT};
        String[] previous = {""};
        OutcomeCounts[] counts = {OutcomeCounts.empty()};
        jdbc.query("""
                SELECT comparison_id, cycle_id, authority_id, object_id, outcome,
                       expected_item_fingerprint, observed_item_fingerprint,
                       expected_object_commitment, observed_object_commitment,
                       expected_topology_fingerprint, observed_topology_fingerprint,
                       expected_retain_until, observed_retain_until,
                       classification_fingerprint
                FROM rg_test_suite_stability_observation_external_classifications
                WHERE comparison_id = ?
                ORDER BY object_id
                """, (RowCallbackHandler) result -> {
                    SourceClassification classification = sourceClassification(result);
                    classification.verify(source, objectMapper);
                    if (previous[0].compareTo(classification.objectId()) >= 0) {
                        throw new IllegalStateException(
                                "External finding source order is not strict");
                    }
                    previous[0] = classification.objectId();
                    root[0] = ProtocolFingerprint.of(objectMapper, new ClassificationRootLink(
                            ClassificationRootLink.SCHEMA_VERSION, root[0],
                            classification.fingerprint()));
                    count[0] = increment(count[0], "source classification count");
                    counts[0] = counts[0].increment(classification.outcome());
                }, source.comparisonId());
        return new ClassificationReplay(count[0], root[0], counts[0]);
    }

    private SnapshotReplay replaySnapshot(String projectionId, String authorityId) {
        long[] count = {0};
        String[] root = {EMPTY_SNAPSHOT_ROOT};
        String[] previous = {""};
        jdbc.query("""
                SELECT authority_id, object_id, finding_status, finding_kind,
                       latest_comparison_id, latest_outcome,
                       latest_classification_fingerprint, occurrence_count, episode_count,
                       first_seen_at, last_observed_at, last_evaluated_at, resolution,
                       resolved_at, finding_version, record_fingerprint
                FROM rg_test_suite_stability_observation_external_finding_snapshots
                WHERE projection_id = ?
                ORDER BY object_id
                """, (RowCallbackHandler) result -> {
                    Finding finding = finding(result);
                    finding.verify(objectMapper);
                    if (!finding.authorityId().equals(authorityId)
                            || previous[0].compareTo(finding.objectId()) >= 0) {
                        throw new IllegalStateException("External finding snapshot is corrupt");
                    }
                    previous[0] = finding.objectId();
                    root[0] = appendSnapshotRoot(objectMapper, root[0], finding);
                    count[0] = increment(count[0], "finding snapshot count");
                }, projectionId);
        return new SnapshotReplay(count[0], root[0]);
    }

    private EventReplay replayEvents(StoredProjection projection) {
        long[] count = {0};
        String[] root = {EMPTY_EVENT_ROOT};
        String[] previous = {""};
        TransitionCounts[] counts = {TransitionCounts.empty()};
        jdbc.query("""
                SELECT projection_id, comparison_id, authority_id, object_id,
                       classification_outcome, classification_fingerprint, transition,
                       previous_finding_fingerprint, resulting_finding_fingerprint,
                       resulting_finding_version, occurred_at, event_fingerprint
                FROM rg_test_suite_stability_observation_external_finding_events
                WHERE projection_id = ?
                ORDER BY object_id
                """, (RowCallbackHandler) result -> {
                    FindingEvent event = event(result);
                    event.verify(objectMapper);
                    if (!event.comparisonId().equals(projection.comparisonId())
                            || !event.authorityId().equals(projection.authorityId())
                            || previous[0].compareTo(event.objectId()) >= 0) {
                        throw new IllegalStateException("External finding event history is corrupt");
                    }
                    previous[0] = event.objectId();
                    root[0] = appendEventRoot(objectMapper, root[0], event);
                    count[0] = increment(count[0], "finding event count");
                    counts[0] = counts[0].increment(event.transition());
                }, projection.projectionId());
        return new EventReplay(count[0], root[0], counts[0]);
    }

    private void verifyEventCoverage(StoredProjection projection) {
        Long missing = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_classifications c
                LEFT JOIN rg_test_suite_stability_observation_external_finding_events e
                  ON e.comparison_id = c.comparison_id AND e.object_id = c.object_id
                WHERE c.comparison_id = ? AND e.object_id IS NULL
                """, Long.class, projection.comparisonId());
        Long extra = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_finding_events e
                LEFT JOIN rg_test_suite_stability_observation_external_classifications c
                  ON c.comparison_id = e.comparison_id AND c.object_id = e.object_id
                WHERE e.projection_id = ? AND c.object_id IS NULL
                """, Long.class, projection.projectionId());
        if (missing == null || extra == null || missing != 0 || extra != 0) {
            throw new IllegalStateException("External finding event coverage is incomplete");
        }
    }

    private void verifyResultingFindings(StoredProjection projection) {
        long[] sourceCount = {0};
        String[] previous = {""};
        SourceComparison source = readSourceComparison(projection.comparisonId());
        jdbc.query("""
                SELECT ids.object_id AS source_object_id,
                       c.comparison_id AS c_comparison_id,
                       c.cycle_id AS c_cycle_id,
                       c.authority_id AS c_authority_id,
                       c.outcome AS c_outcome,
                       c.expected_item_fingerprint AS c_expected_item_fingerprint,
                       c.observed_item_fingerprint AS c_observed_item_fingerprint,
                       c.expected_object_commitment AS c_expected_object_commitment,
                       c.observed_object_commitment AS c_observed_object_commitment,
                       c.expected_topology_fingerprint AS c_expected_topology_fingerprint,
                       c.observed_topology_fingerprint AS c_observed_topology_fingerprint,
                       c.expected_retain_until AS c_expected_retain_until,
                       c.observed_retain_until AS c_observed_retain_until,
                       c.classification_fingerprint AS c_classification_fingerprint,
                       s.authority_id AS s_authority_id, s.object_id AS s_object_id,
                       s.finding_status AS s_finding_status, s.finding_kind AS s_finding_kind,
                       s.latest_comparison_id AS s_latest_comparison_id,
                       s.latest_outcome AS s_latest_outcome,
                       s.latest_classification_fingerprint AS s_latest_classification_fingerprint,
                       s.occurrence_count AS s_occurrence_count,
                       s.episode_count AS s_episode_count,
                       s.first_seen_at AS s_first_seen_at,
                       s.last_observed_at AS s_last_observed_at,
                       s.last_evaluated_at AS s_last_evaluated_at,
                       s.resolution AS s_resolution, s.resolved_at AS s_resolved_at,
                       s.finding_version AS s_finding_version,
                       s.record_fingerprint AS s_record_fingerprint,
                       e.projection_id AS e_projection_id, e.comparison_id AS e_comparison_id,
                       e.authority_id AS e_authority_id, e.object_id AS e_object_id,
                       e.classification_outcome AS e_classification_outcome,
                       e.classification_fingerprint AS e_classification_fingerprint,
                       e.transition AS e_transition,
                       e.previous_finding_fingerprint AS e_previous_finding_fingerprint,
                       e.resulting_finding_fingerprint AS e_resulting_finding_fingerprint,
                       e.resulting_finding_version AS e_resulting_finding_version,
                       e.occurred_at AS e_occurred_at,
                       e.event_fingerprint AS e_event_fingerprint,
                       f.authority_id AS f_authority_id, f.object_id AS f_object_id,
                       f.finding_status AS f_finding_status, f.finding_kind AS f_finding_kind,
                       f.latest_comparison_id AS f_latest_comparison_id,
                       f.latest_outcome AS f_latest_outcome,
                       f.latest_classification_fingerprint AS f_latest_classification_fingerprint,
                       f.occurrence_count AS f_occurrence_count,
                       f.episode_count AS f_episode_count,
                       f.first_seen_at AS f_first_seen_at,
                       f.last_observed_at AS f_last_observed_at,
                       f.last_evaluated_at AS f_last_evaluated_at,
                       f.resolution AS f_resolution, f.resolved_at AS f_resolved_at,
                       f.finding_version AS f_finding_version,
                       f.record_fingerprint AS f_record_fingerprint
                FROM (
                    SELECT object_id
                    FROM rg_test_suite_stability_observation_external_finding_snapshots
                    WHERE projection_id = ?
                    UNION
                    SELECT object_id
                    FROM rg_test_suite_stability_observation_external_classifications
                    WHERE comparison_id = ?
                ) ids
                LEFT JOIN rg_test_suite_stability_observation_external_classifications c
                  ON c.comparison_id = ? AND c.object_id = ids.object_id
                LEFT JOIN rg_test_suite_stability_observation_external_finding_snapshots s
                  ON s.projection_id = ? AND s.object_id = ids.object_id
                LEFT JOIN rg_test_suite_stability_observation_external_finding_events e
                  ON e.projection_id = ? AND e.object_id = ids.object_id
                LEFT JOIN rg_test_suite_stability_observation_external_findings f
                  ON f.authority_id = ? AND f.object_id = ids.object_id
                ORDER BY ids.object_id
                """, (RowCallbackHandler) result -> {
                    String objectId = result.getString("source_object_id");
                    if (previous[0].compareTo(objectId) >= 0) {
                        throw new IllegalStateException(
                                "External finding semantic replay order is corrupt");
                    }
                    previous[0] = objectId;
                    Finding snapshot = nullableFinding(result, "s_");
                    FindingEvent event = nullableEvent(result, "e_");
                    Finding current = nullableFinding(result, "f_");
                    SourceClassification classification = nullableClassification(result, "c_",
                            objectId);
                    Finding expected;
                    if (classification == null) {
                        if (event != null) {
                            throw new IllegalStateException(
                                    "External finding event has no source classification");
                        }
                        expected = snapshot;
                    } else {
                        classification.verify(source, objectMapper);
                        if (event == null) {
                            throw new IllegalStateException(
                                    "External finding classification has no transition event");
                        }
                        TransitionResult derived = transition(
                                projection, classification, snapshot, event.occurredAt());
                        if (!derived.event().equals(event)) {
                            throw new IllegalStateException(
                                    "External finding transition semantic replay failed");
                        }
                        expected = derived.resultingFinding();
                        sourceCount[0] = increment(sourceCount[0],
                                "semantic source classification count");
                    }
                    requireSameFinding(expected, current, "resulting finding state");
                }, projection.projectionId(), projection.comparisonId(),
                projection.comparisonId(), projection.projectionId(), projection.projectionId(),
                projection.authorityId());
        Long extra = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_findings f
                LEFT JOIN (
                    SELECT object_id
                    FROM rg_test_suite_stability_observation_external_finding_snapshots
                    WHERE projection_id = ?
                    UNION
                    SELECT object_id
                    FROM rg_test_suite_stability_observation_external_classifications
                    WHERE comparison_id = ?
                ) ids ON ids.object_id = f.object_id
                WHERE f.authority_id = ? AND ids.object_id IS NULL
                """, Long.class, projection.projectionId(), projection.comparisonId(),
                projection.authorityId());
        if (sourceCount[0] != projection.sourceCount() || extra == null || extra != 0) {
            throw new IllegalStateException("External finding resulting-state coverage failed");
        }
    }

    private SourceComparison nextComparison(StoredAuthority authority) {
        List<SourceComparison> rows = jdbc.query(sourceComparisonSelect() + """
                WHERE c.authority_id = ? AND c.comparison_status = 'COMPLETED'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM rg_test_suite_stability_observation_external_finding_projections p
                      WHERE p.comparison_id = c.comparison_id
                  )
                ORDER BY c.started_at, c.comparison_id
                LIMIT 2
                """, this::sourceComparison, authority.authorityId());
        if (rows.isEmpty()) {
            return null;
        }
        SourceComparison first = rows.getFirst();
        if (rows.size() > 1 && rows.get(1).startedAt().equals(first.startedAt())) {
            throw new IllegalStateException(
                    "External finding comparison order contains a timestamp tie");
        }
        if (authority.lastAppliedComparisonCompletedAt() != null
                && !first.startedAt().isAfter(authority.lastAppliedComparisonCompletedAt())) {
            throw new IllegalStateException(
                    "External finding comparison order regressed");
        }
        return first;
    }

    private SourceComparison readSourceComparison(String comparisonId) {
        List<SourceComparison> rows = jdbc.query(sourceComparisonSelect() + """
                WHERE c.comparison_id = ?
                """, this::sourceComparison, comparisonId);
        if (rows.size() != 1) {
            throw new IllegalStateException("External finding source comparison is missing");
        }
        return rows.getFirst();
    }

    private String sourceComparisonSelect() {
        return """
                SELECT c.comparison_id, c.cycle_id, c.authority_id, c.comparison_status,
                       c.trust_domain, c.archive_set_id, c.failure_domain, c.remote_snapshot_id,
                       c.remote_object_count, c.remote_root, c.expected_object_count,
                       c.expected_root, c.next_after_object_id, c.next_page_sequence,
                       c.classified_object_count, c.matched_count, c.missing_remote_count,
                       c.unexpected_remote_count, c.material_conflict_count,
                       c.retention_shortened_count, c.unknown_count, c.classification_root,
                       c.revision, c.started_at, c.completed_at, c.updated_at,
                       c.record_fingerprint
                FROM rg_test_suite_stability_observation_external_comparisons c
                """;
    }

    private SourceComparison sourceComparison(ResultSet result, int row) throws SQLException {
        SourceComparison source = new SourceComparison(
                result.getString("comparison_id"), result.getString("cycle_id"),
                result.getString("authority_id"), result.getString("comparison_status"),
                result.getString("trust_domain"), result.getString("archive_set_id"),
                result.getString("failure_domain"), result.getString("remote_snapshot_id"),
                result.getLong("remote_object_count"), result.getString("remote_root"),
                result.getLong("expected_object_count"), result.getString("expected_root"),
                result.getString("next_after_object_id"),
                result.getLong("next_page_sequence"),
                result.getLong("classified_object_count"), result.getLong("matched_count"),
                result.getLong("missing_remote_count"),
                result.getLong("unexpected_remote_count"),
                result.getLong("material_conflict_count"),
                result.getLong("retention_shortened_count"), result.getLong("unknown_count"),
                result.getString("classification_root"), result.getLong("revision"),
                instant(result, "started_at"), instant(result, "completed_at"),
                instant(result, "updated_at"), result.getString("record_fingerprint"));
        source.verify(objectMapper);
        return source;
    }

    private List<SourceClassification> classificationPage(StoredProjection projection) {
        List<SourceClassification> rows = jdbc.query("""
                SELECT comparison_id, cycle_id, authority_id, object_id, outcome,
                       expected_item_fingerprint, observed_item_fingerprint,
                       expected_object_commitment, observed_object_commitment,
                       expected_topology_fingerprint, observed_topology_fingerprint,
                       expected_retain_until, observed_retain_until,
                       classification_fingerprint
                FROM rg_test_suite_stability_observation_external_classifications
                WHERE comparison_id = ? AND object_id > ?
                ORDER BY object_id
                LIMIT ?
                """, (result, row) -> sourceClassification(result), projection.comparisonId(),
                projection.nextAfterObjectId(), settings.maximumClassificationsPerPage());
        SourceComparison source = readSourceComparison(projection.comparisonId());
        String previous = projection.nextAfterObjectId();
        for (SourceClassification classification : rows) {
            classification.verify(source, objectMapper);
            if (previous.compareTo(classification.objectId()) >= 0) {
                throw new IllegalStateException(
                        "External finding source page order is not strict");
            }
            previous = classification.objectId();
        }
        return List.copyOf(rows);
    }

    private boolean hasClassificationAfter(String comparisonId, String objectId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_classifications
                WHERE comparison_id = ? AND object_id > ?
                """, Integer.class, comparisonId, objectId);
        return count != null && count > 0;
    }

    private SourceClassification sourceClassification(ResultSet result) throws SQLException {
        return sourceClassification(result, "", result.getString("object_id"));
    }

    private SourceClassification nullableClassification(
            ResultSet result,
            String prefix,
            String objectId) throws SQLException {
        return result.getString(prefix + "comparison_id") == null
                ? null : sourceClassification(result, prefix, objectId);
    }

    private SourceClassification sourceClassification(
            ResultSet result,
            String prefix,
            String objectId) throws SQLException {
        try {
            var value = new
                    DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Classification(
                    result.getString(prefix + "comparison_id"),
                    result.getString(prefix + "cycle_id"),
                    result.getString(prefix + "authority_id"), objectId,
                    DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                            .Outcome.valueOf(result.getString(prefix + "outcome")),
                    result.getString(prefix + "expected_item_fingerprint"),
                    result.getString(prefix + "observed_item_fingerprint"),
                    result.getString(prefix + "expected_object_commitment"),
                    result.getString(prefix + "observed_object_commitment"),
                    result.getString(prefix + "expected_topology_fingerprint"),
                    result.getString(prefix + "observed_topology_fingerprint"),
                    nullableInstant(result, prefix + "expected_retain_until"),
                    nullableInstant(result, prefix + "observed_retain_until"),
                    result.getString(prefix + "classification_fingerprint"));
            return new SourceClassification(value.objectId(), value.outcome(),
                    value.classificationFingerprint(), value);
        } catch (IllegalArgumentException corrupt) {
            throw new IllegalStateException(
                    "Stored external finding source classification is corrupt", corrupt);
        }
    }

    private void initializeAuthority(String authorityId) {
        Integer known = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_comparison_authorities
                WHERE authority_id = ?
                """, Integer.class, authorityId);
        if (known == null || known != 1) {
            throw new IllegalArgumentException("External comparison authority is not initialized");
        }
        Instant now = databaseNow();
        StoredAuthority initial = StoredAuthority.initial(authorityId, now);
        initial = initial.withFingerprint(authorityFingerprint(initial.fingerprintMaterial()));
        try {
            jdbc.update("""
                    INSERT INTO
                        rg_test_suite_stability_observation_external_finding_authorities (
                        authority_id, active_projection_id, last_completed_projection_id,
                        last_applied_comparison_id, last_applied_comparison_completed_at,
                        revision, updated_at, record_fingerprint
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, initial.sqlArguments());
        } catch (DuplicateKeyException ignored) {
            // Another replica initialized the same durable authority.
        }
    }

    private void activateProjection(
            StoredAuthority current,
            String projectionId,
            Instant now) {
        StoredAuthority successor = current.activated(projectionId, now);
        updateAuthority(current, successor);
    }

    private StoredAuthority readAuthority(String authorityId, boolean lock) {
        List<StoredAuthority> rows = jdbc.query("""
                SELECT authority_id, active_projection_id, last_completed_projection_id,
                       last_applied_comparison_id, last_applied_comparison_completed_at,
                       revision, updated_at, record_fingerprint
                FROM rg_test_suite_stability_observation_external_finding_authorities
                WHERE authority_id = ?
                """ + (lock ? " FOR UPDATE" : ""), this::authority, authorityId);
        if (rows.size() != 1) {
            throw new IllegalStateException("External finding authority state is missing");
        }
        rows.getFirst().verify(objectMapper);
        return rows.getFirst();
    }

    private StoredAuthority authority(ResultSet result, int row) throws SQLException {
        return new StoredAuthority(result.getString("authority_id"),
                result.getString("active_projection_id"),
                result.getString("last_completed_projection_id"),
                result.getString("last_applied_comparison_id"),
                nullableInstant(result, "last_applied_comparison_completed_at"),
                result.getLong("revision"), instant(result, "updated_at"),
                result.getString("record_fingerprint"));
    }

    private void updateAuthority(StoredAuthority expected, StoredAuthority successor) {
        StoredAuthority material = successor.withFingerprint(
                authorityFingerprint(successor.fingerprintMaterial()));
        int updated = jdbc.update("""
                UPDATE rg_test_suite_stability_observation_external_finding_authorities
                SET active_projection_id = ?, last_completed_projection_id = ?,
                    last_applied_comparison_id = ?,
                    last_applied_comparison_completed_at = ?, revision = ?, updated_at = ?,
                    record_fingerprint = ?
                WHERE authority_id = ? AND revision = ? AND record_fingerprint = ?
                """, material.activeProjectionId(), material.lastCompletedProjectionId(),
                material.lastAppliedComparisonId(),
                timestamp(material.lastAppliedComparisonCompletedAt()), material.revision(),
                Timestamp.from(material.updatedAt()), material.recordFingerprint(),
                expected.authorityId(), expected.revision(), expected.recordFingerprint());
        if (updated != 1) {
            throw new IllegalStateException("External finding authority fence was rejected");
        }
    }

    private StoredProjection readProjection(String projectionId, boolean lock) {
        List<StoredProjection> rows = jdbc.query("""
                SELECT projection_id, comparison_id, authority_id, projection_status,
                       comparison_started_at, comparison_completed_at,
                       source_classification_count, source_classification_root,
                       snapshot_finding_count, snapshot_root, next_after_object_id,
                       next_page_sequence, processed_classification_count, opened_count,
                       observed_count, reopened_count, resolved_count, confirmed_count,
                       event_root, revision, started_at, completed_at, updated_at,
                       record_fingerprint
                FROM rg_test_suite_stability_observation_external_finding_projections
                WHERE projection_id = ?
                """ + (lock ? " FOR UPDATE" : ""), this::projection, projectionId);
        if (rows.size() != 1) {
            throw new IllegalStateException("External finding projection is missing");
        }
        rows.getFirst().verify(objectMapper);
        return rows.getFirst();
    }

    private StoredProjection projection(ResultSet result, int row) throws SQLException {
        return new StoredProjection(result.getString("projection_id"),
                result.getString("comparison_id"), result.getString("authority_id"),
                result.getString("projection_status"),
                instant(result, "comparison_started_at"),
                instant(result, "comparison_completed_at"),
                result.getLong("source_classification_count"),
                result.getString("source_classification_root"),
                result.getLong("snapshot_finding_count"), result.getString("snapshot_root"),
                result.getString("next_after_object_id"),
                result.getLong("next_page_sequence"),
                result.getLong("processed_classification_count"),
                result.getLong("opened_count"), result.getLong("observed_count"),
                result.getLong("reopened_count"), result.getLong("resolved_count"),
                result.getLong("confirmed_count"), result.getString("event_root"),
                result.getLong("revision"), instant(result, "started_at"),
                nullableInstant(result, "completed_at"), instant(result, "updated_at"),
                result.getString("record_fingerprint"));
    }

    private void updateProjection(StoredProjection expected, StoredProjection successor) {
        StoredProjection material = successor.withFingerprint(
                projectionFingerprint(successor.fingerprintMaterial()));
        int updated = jdbc.update("""
                UPDATE rg_test_suite_stability_observation_external_finding_projections
                SET projection_status = ?, next_after_object_id = ?, next_page_sequence = ?,
                    processed_classification_count = ?, opened_count = ?, observed_count = ?,
                    reopened_count = ?, resolved_count = ?, confirmed_count = ?, event_root = ?,
                    revision = ?, completed_at = ?, updated_at = ?, record_fingerprint = ?
                WHERE projection_id = ? AND revision = ? AND record_fingerprint = ?
                """, material.status(), material.nextAfterObjectId(), material.nextPageSequence(),
                material.processedCount(), material.openedCount(), material.observedCount(),
                material.reopenedCount(), material.resolvedCount(), material.confirmedCount(),
                material.eventRoot(), material.revision(), timestamp(material.completedAt()),
                Timestamp.from(material.updatedAt()), material.recordFingerprint(),
                expected.projectionId(), expected.revision(), expected.recordFingerprint());
        if (updated != 1) {
            throw new IllegalStateException("External finding projection fence was rejected");
        }
    }

    private Finding snapshotFinding(String projectionId, String objectId) {
        List<Finding> rows = jdbc.query("""
                SELECT authority_id, object_id, finding_status, finding_kind,
                       latest_comparison_id, latest_outcome,
                       latest_classification_fingerprint, occurrence_count, episode_count,
                       first_seen_at, last_observed_at, last_evaluated_at, resolution,
                       resolved_at, finding_version, record_fingerprint
                FROM rg_test_suite_stability_observation_external_finding_snapshots
                WHERE projection_id = ? AND object_id = ?
                """, (result, row) -> finding(result, row), projectionId, objectId);
        if (rows.size() > 1) {
            throw new IllegalStateException("External finding snapshot identity is duplicated");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Finding currentFinding(String authorityId, String objectId, boolean lock) {
        List<Finding> rows = jdbc.query("""
                SELECT authority_id, object_id, finding_status, finding_kind,
                       latest_comparison_id, latest_outcome,
                       latest_classification_fingerprint, occurrence_count, episode_count,
                       first_seen_at, last_observed_at, last_evaluated_at, resolution,
                       resolved_at, finding_version, record_fingerprint
                FROM rg_test_suite_stability_observation_external_findings
                WHERE authority_id = ? AND object_id = ?
                """ + (lock ? " FOR UPDATE" : ""),
                (result, row) -> finding(result, row), authorityId, objectId);
        if (rows.size() > 1) {
            throw new IllegalStateException("External finding identity is duplicated");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Finding finding(ResultSet result, int row) throws SQLException {
        return finding(result, "");
    }

    private Finding finding(ResultSet result) throws SQLException {
        return finding(result, "");
    }

    private Finding nullableFinding(ResultSet result, String prefix) throws SQLException {
        return result.getString(prefix + "object_id") == null ? null : finding(result, prefix);
    }

    private Finding finding(ResultSet result, String prefix) throws SQLException {
        try {
            Finding finding = new Finding(result.getString(prefix + "authority_id"),
                    result.getString(prefix + "object_id"),
                    FindingStatus.valueOf(result.getString(prefix + "finding_status")),
                    DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                            .Outcome.valueOf(result.getString(prefix + "finding_kind")),
                    result.getString(prefix + "latest_comparison_id"),
                    DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                            .Outcome.valueOf(result.getString(prefix + "latest_outcome")),
                    result.getString(prefix + "latest_classification_fingerprint"),
                    result.getLong(prefix + "occurrence_count"),
                    result.getLong(prefix + "episode_count"),
                    instant(result, prefix + "first_seen_at"),
                    instant(result, prefix + "last_observed_at"),
                    instant(result, prefix + "last_evaluated_at"),
                    Resolution.valueOf(result.getString(prefix + "resolution")),
                    nullableInstant(result, prefix + "resolved_at"),
                    result.getLong(prefix + "finding_version"),
                    result.getString(prefix + "record_fingerprint"));
            finding.verify(objectMapper);
            return finding;
        } catch (IllegalArgumentException corrupt) {
            throw new IllegalStateException("Stored external finding is corrupt", corrupt);
        }
    }

    private FindingEvent event(ResultSet result, int row) throws SQLException {
        return event(result, "");
    }

    private FindingEvent event(ResultSet result) throws SQLException {
        return event(result, "");
    }

    private FindingEvent nullableEvent(ResultSet result, String prefix) throws SQLException {
        return result.getString(prefix + "object_id") == null ? null : event(result, prefix);
    }

    private FindingEvent event(ResultSet result, String prefix) throws SQLException {
        try {
            FindingEvent event = new FindingEvent(result.getString(prefix + "projection_id"),
                    result.getString(prefix + "comparison_id"),
                    result.getString(prefix + "authority_id"),
                    result.getString(prefix + "object_id"),
                    DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                            .Outcome.valueOf(
                            result.getString(prefix + "classification_outcome")),
                    result.getString(prefix + "classification_fingerprint"),
                    Transition.valueOf(result.getString(prefix + "transition")),
                    result.getString(prefix + "previous_finding_fingerprint"),
                    result.getString(prefix + "resulting_finding_fingerprint"),
                    result.getLong(prefix + "resulting_finding_version"),
                    instant(result, prefix + "occurred_at"),
                    result.getString(prefix + "event_fingerprint"));
            event.verify(objectMapper);
            return event;
        } catch (IllegalArgumentException corrupt) {
            throw new IllegalStateException("Stored external finding event is corrupt", corrupt);
        }
    }

    private void verifyOrderedFindings(List<Finding> findings, String cursor) {
        String previous = cursor;
        for (Finding finding : findings) {
            finding.verify(objectMapper);
            if (previous.compareTo(finding.objectId()) >= 0) {
                throw new IllegalStateException("External finding export order is not strict");
            }
            previous = finding.objectId();
        }
    }

    private void verifyOrderedEvents(
            List<FindingEvent> events,
            StoredProjection projection,
            String cursor) {
        String previous = cursor;
        for (FindingEvent event : events) {
            event.verify(objectMapper);
            if (!event.projectionId().equals(projection.projectionId())
                    || !event.comparisonId().equals(projection.comparisonId())
                    || !event.authorityId().equals(projection.authorityId())
                    || previous.compareTo(event.objectId()) >= 0) {
                throw new IllegalStateException("External finding event export is corrupt");
            }
            previous = event.objectId();
        }
    }

    private void requireEvidenceAvailable(StoredProjection projection) {
        List<EvidenceRetirement> rows = jdbc.query("""
                SELECT projection_id, comparison_id, authority_id, retirement_status,
                       started_at, completed_at, record_fingerprint
                FROM
                    rg_test_suite_stability_observation_external_finding_evidence_retirements
                WHERE projection_id = ?
                """, (result, row) -> new EvidenceRetirement(
                result.getString("projection_id"), result.getString("comparison_id"),
                result.getString("authority_id"), result.getString("retirement_status"),
                instant(result, "started_at"), nullableInstant(result, "completed_at"),
                result.getString("record_fingerprint")), projection.projectionId());
        if (rows.size() > 1) {
            throw new IllegalStateException("External finding evidence retirement is not unique");
        }
        if (!rows.isEmpty()) {
            EvidenceRetirement retirement = rows.getFirst();
            retirement.verify(objectMapper);
            if (!retirement.projectionId().equals(projection.projectionId())
                    || !retirement.comparisonId().equals(projection.comparisonId())
                    || !retirement.authorityId().equals(projection.authorityId())) {
                throw new IllegalStateException(
                        "External finding evidence retirement source is corrupt");
            }
            throw new IllegalStateException("External finding event evidence is "
                    + ("ACTIVE".equals(retirement.status())
                    ? "being retired" : "retired"));
        }
    }

    private void requireSameFinding(Finding expected, Finding actual, String name) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException("External " + name + " does not match");
        }
    }

    static String appendSnapshotRoot(ObjectMapper objectMapper, String root, Finding finding) {
        return ProtocolFingerprint.of(Objects.requireNonNull(objectMapper, "objectMapper"),
                new FindingSnapshotRootLink(FindingSnapshotRootLink.SCHEMA_VERSION,
                        requiredFingerprint(root, "finding snapshot root"),
                        Objects.requireNonNull(finding, "finding").recordFingerprint()));
    }

    static String appendEventRoot(ObjectMapper objectMapper, String root, FindingEvent event) {
        return ProtocolFingerprint.of(Objects.requireNonNull(objectMapper, "objectMapper"),
                new FindingEventRootLink(
                        FindingEventRootLink.SCHEMA_VERSION,
                        requiredFingerprint(root, "finding event root"),
                        Objects.requireNonNull(event, "event").eventFingerprint()));
    }

    static String evidenceRetirementFingerprint(
            ObjectMapper objectMapper,
            String projectionId,
            String comparisonId,
            String authorityId,
            String status,
            Instant startedAt,
            Instant completedAt) {
        return ProtocolFingerprint.of(Objects.requireNonNull(objectMapper, "objectMapper"),
                new EvidenceRetirementMaterial(EvidenceRetirementMaterial.SCHEMA_VERSION,
                        projectionId, comparisonId, authorityId, status, startedAt, completedAt));
    }

    private static String requiredFingerprint(String value, String name) {
        String normalized = normalized(value);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid external " + name);
        }
        return normalized;
    }

    private String topologyFingerprint(
            String trustDomain,
            String archiveSetId,
            String authorityId,
            String failureDomain) {
        return ProtocolFingerprint.of(objectMapper, new TopologyMaterial(
                TopologyMaterial.SCHEMA_VERSION,
                requiredIdentifier(trustDomain, "trust domain"),
                requiredIdentifier(archiveSetId, "archive set"),
                requiredIdentifier(authorityId, "inventory authority"),
                requiredIdentifier(failureDomain, "failure domain")));
    }

    private String authorityFingerprint(FindingAuthorityMaterial material) {
        return ProtocolFingerprint.of(objectMapper, material);
    }

    private String projectionFingerprint(FindingProjectionMaterial material) {
        return ProtocolFingerprint.of(objectMapper, material);
    }

    private Instant databaseNow() {
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (value == null) {
            throw new IllegalStateException("Database clock returned no timestamp");
        }
        return value.toInstant();
    }

    private Instant nextDatabaseTime(Instant persistedLowerBound) {
        Instant transactionTime = databaseNow();
        return transactionTime.isAfter(persistedLowerBound)
                ? transactionTime : persistedLowerBound.plusNanos(1_000);
    }

    private static PlatformTransactionManager localTransactionManager(JdbcTemplate jdbc) {
        Objects.requireNonNull(jdbc, "jdbc");
        if (jdbc.getDataSource() == null) {
            throw new IllegalArgumentException("External finding JDBC requires a datasource");
        }
        return new DataSourceTransactionManager(jdbc.getDataSource());
    }

    private static Object[] findingArguments(Finding finding) {
        return new Object[]{finding.authorityId(), finding.objectId(), finding.status().name(),
                finding.kind().name(), finding.latestComparisonId(),
                finding.latestOutcome().name(), finding.latestClassificationFingerprint(),
                finding.occurrences(), finding.episodes(), Timestamp.from(finding.firstSeenAt()),
                Timestamp.from(finding.lastObservedAt()),
                Timestamp.from(finding.lastEvaluatedAt()), finding.resolution().name(),
                timestamp(finding.resolvedAt()), finding.version(), finding.recordFingerprint()};
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        if (value == null) {
            throw new IllegalStateException("Required timestamp column is null: " + column);
        }
        return value.toInstant();
    }

    private static Instant nullableInstant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String requiredIdentifier(String value, String name) {
        String exact = normalized(value);
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException("Complete " + name + " is required");
        }
        return exact;
    }

    private static String requiredUuid(String value, String name) {
        String exact = normalized(value);
        if (!isUuid(exact)) {
            throw new IllegalArgumentException("Valid " + name + " is required");
        }
        return exact;
    }

    private static String exportCursor(String value) {
        String cursor = normalized(value);
        if (!cursor.isEmpty() && !OBJECT_ID.matcher(cursor).matches()) {
            throw new IllegalArgumentException("Invalid external finding export cursor");
        }
        return cursor;
    }

    private static int exportLimit(int limit) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("External finding export limit must be 1 through 500");
        }
        return limit;
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private static long increment(long value, String name) {
        return add(value, 1, name);
    }

    private static long add(long value, long delta, String name) {
        try {
            return Math.addExact(value, delta);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException(name + " overflow", overflow);
        }
    }

    private static <T> T requiredResult(T result, String operation) {
        if (result == null) {
            throw new IllegalStateException("External " + operation + " returned no result");
        }
        return result;
    }

    /** Bounded finding-projection settings. */
    public record Settings(int maximumClassificationsPerPage) {
        /** Enforces one through five hundred classifications per transaction. */
        public Settings {
            if (maximumClassificationsPerPage < 1 || maximumClassificationsPerPage > 500) {
                throw new IllegalArgumentException(
                        "External finding projection page must be 1 through 500");
            }
        }
    }

    /** Durable current-finding lifecycle state. */
    public enum FindingStatus {
        /** A completed comparison currently reports a governed discrepancy. */
        OPEN,
        /** A later completed comparison matched the object again. */
        RESOLVED
    }

    /** Stable automatic resolution vocabulary; no remediation action is implied. */
    public enum Resolution {
        /** The finding remains open. */
        NONE,
        /** A later complete signed snapshot classified the object as matched. */
        MATCHED_ON_RECHECK
    }

    /** Closed transition emitted once for every source classification. */
    public enum Transition {
        /** A discrepancy created its first finding lifecycle. */
        OPENED,
        /** Another comparison observed an already-open discrepancy. */
        OBSERVED,
        /** A discrepancy returned after a resolved episode. */
        REOPENED,
        /** A match closed an open finding without performing remediation. */
        RESOLVED,
        /** A match required no current-finding mutation. */
        CONFIRMED
    }

    /** Progress state returned by one bounded projection invocation. */
    public enum ProjectionStatus {
        /** One bounded page committed and more source classifications remain. */
        STAGED,
        /** The complete projection passed all independent terminal gates. */
        COMPLETED,
        /** No unprojected completed comparison exists. */
        CURRENT
    }

    /**
     * Payload-free progress for one projection transaction.
     *
     * @param status staged, completed, or current
     * @param authorityId exact inventory authority
     * @param projectionId active or last completed projection, possibly empty for initial current
     * @param comparisonId source comparison, possibly empty for initial current
     * @param pageSequence attempted zero-based source page sequence
     * @param processedOnPage classifications committed by this invocation
     * @param totalProcessed cumulative classifications in the projection
     * @param actionableTransitions cumulative open, observe, reopen, and resolve transitions
     */
    public record ProjectionPage(
            ProjectionStatus status,
            String authorityId,
            String projectionId,
            String comparisonId,
            long pageSequence,
            int processedOnPage,
            long totalProcessed,
            long actionableTransitions) {
        /** Validates complete bounded progress without exposing finding material. */
        public ProjectionPage {
            status = Objects.requireNonNull(status, "status");
            authorityId = requiredIdentifier(authorityId, "finding authority");
            projectionId = normalized(projectionId);
            comparisonId = normalized(comparisonId);
            boolean absent = projectionId.isEmpty() && comparisonId.isEmpty();
            if ((!absent && (!isUuid(projectionId) || !isUuid(comparisonId)))
                    || (absent && status != ProjectionStatus.CURRENT)
                    || pageSequence < 0 || processedOnPage < 0 || processedOnPage > 500
                    || totalProcessed < 0 || actionableTransitions < 0
                    || actionableTransitions > totalProcessed) {
                throw new IllegalArgumentException("Invalid external finding projection progress");
            }
        }

        private static ProjectionPage current(StoredAuthority authority) {
            return new ProjectionPage(ProjectionStatus.CURRENT, authority.authorityId(),
                    authority.lastCompletedProjectionId(), authority.lastAppliedComparisonId(),
                    0, 0, 0, 0);
        }
    }

    /**
     * Current payload-free governed finding.
     *
     * @param authorityId exact external inventory authority
     * @param objectId deterministic WORM object identity
     * @param status open or resolved
     * @param kind latest non-matched discrepancy kind
     * @param latestComparisonId comparison that last mutated the finding
     * @param latestOutcome latest outcome that mutated the finding
     * @param latestClassificationFingerprint source classification fingerprint
     * @param occurrences total non-matched observations across all episodes
     * @param episodes number of open/reopen episodes
     * @param firstSeenAt first database observation
     * @param lastObservedAt latest non-matched database observation
     * @param lastEvaluatedAt latest comparison that mutated the finding
     * @param resolution automatic resolution reason
     * @param resolvedAt resolution time, or {@code null} while open
     * @param version exact optimistic lifecycle revision
     * @param recordFingerprint fingerprint over every preceding field
     */
    public record Finding(
            String authorityId,
            String objectId,
            FindingStatus status,
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Outcome kind,
            String latestComparisonId,
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Outcome latestOutcome,
            String latestClassificationFingerprint,
            long occurrences,
            long episodes,
            Instant firstSeenAt,
            Instant lastObservedAt,
            Instant lastEvaluatedAt,
            Resolution resolution,
            Instant resolvedAt,
            long version,
            String recordFingerprint) {
        /** Validates lifecycle shape; {@link #verify(ObjectMapper)} verifies canonical integrity. */
        public Finding {
            authorityId = requiredIdentifier(authorityId, "finding authority");
            if (!OBJECT_ID.matcher(normalized(objectId)).matches()) {
                throw new IllegalArgumentException("Invalid external finding object ID");
            }
            status = Objects.requireNonNull(status, "status");
            kind = Objects.requireNonNull(kind, "kind");
            latestComparisonId = requiredUuid(latestComparisonId, "finding comparison ID");
            latestOutcome = Objects.requireNonNull(latestOutcome, "latestOutcome");
            if (kind == DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Outcome.MATCHED
                    || !FINGERPRINT.matcher(latestClassificationFingerprint).matches()
                    || occurrences < 1 || episodes < 1 || episodes > occurrences
                    || firstSeenAt == null || lastObservedAt == null || lastEvaluatedAt == null
                    || lastObservedAt.isBefore(firstSeenAt)
                    || lastEvaluatedAt.isBefore(lastObservedAt) || version < 1
                    || !FINGERPRINT.matcher(recordFingerprint).matches()) {
                throw new IllegalArgumentException("Invalid external finding lifecycle");
            }
            resolution = Objects.requireNonNull(resolution, "resolution");
            boolean resolved = status == FindingStatus.RESOLVED;
            if (resolved != (resolvedAt != null)
                    || resolved != (resolution == Resolution.MATCHED_ON_RECHECK)
                    || (!resolved && latestOutcome
                    == DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Outcome.MATCHED)
                    || (resolved && latestOutcome
                    != DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Outcome.MATCHED)) {
                throw new IllegalArgumentException("Invalid external finding resolution state");
            }
        }

        /** @param objectMapper canonical mapper used to verify the whole-record fingerprint */
        public void verify(ObjectMapper objectMapper) {
            String expected = ProtocolFingerprint.of(
                    Objects.requireNonNull(objectMapper, "objectMapper"), fingerprintMaterial());
            if (!recordFingerprint.equals(expected)) {
                throw new IllegalStateException("External finding record fingerprint is corrupt");
            }
        }

        private static Finding opened(
                String authorityId,
                String comparisonId,
                SourceClassification classification,
                Instant now,
                ObjectMapper objectMapper) {
            Finding material = new Finding(authorityId, classification.objectId(),
                    FindingStatus.OPEN, classification.outcome(), comparisonId,
                    classification.outcome(), classification.fingerprint(), 1, 1, now, now, now,
                    Resolution.NONE, null, 1, ProtocolFingerprint.ofText("pending"));
            return material.withFingerprint(ProtocolFingerprint.of(
                    objectMapper, material.fingerprintMaterial()));
        }

        private Finding observed(
                String comparisonId,
                SourceClassification classification,
                Instant now,
                ObjectMapper objectMapper) {
            Finding material = new Finding(authorityId, objectId, FindingStatus.OPEN,
                    classification.outcome(), comparisonId, classification.outcome(),
                    classification.fingerprint(), increment(occurrences, "finding occurrences"),
                    episodes, firstSeenAt, now, now, Resolution.NONE, null,
                    increment(version, "finding version"), ProtocolFingerprint.ofText("pending"));
            return material.withFingerprint(ProtocolFingerprint.of(
                    objectMapper, material.fingerprintMaterial()));
        }

        private Finding reopened(
                String comparisonId,
                SourceClassification classification,
                Instant now,
                ObjectMapper objectMapper) {
            Finding material = new Finding(authorityId, objectId, FindingStatus.OPEN,
                    classification.outcome(), comparisonId, classification.outcome(),
                    classification.fingerprint(), increment(occurrences, "finding occurrences"),
                    increment(episodes, "finding episodes"), firstSeenAt, now, now,
                    Resolution.NONE, null, increment(version, "finding version"),
                    ProtocolFingerprint.ofText("pending"));
            return material.withFingerprint(ProtocolFingerprint.of(
                    objectMapper, material.fingerprintMaterial()));
        }

        private Finding resolved(
                String comparisonId,
                SourceClassification classification,
                Instant now,
                ObjectMapper objectMapper) {
            Finding material = new Finding(authorityId, objectId, FindingStatus.RESOLVED, kind,
                    comparisonId, classification.outcome(), classification.fingerprint(),
                    occurrences, episodes, firstSeenAt, lastObservedAt, now,
                    Resolution.MATCHED_ON_RECHECK, now, increment(version, "finding version"),
                    ProtocolFingerprint.ofText("pending"));
            return material.withFingerprint(ProtocolFingerprint.of(
                    objectMapper, material.fingerprintMaterial()));
        }

        private Finding withFingerprint(String fingerprint) {
            return new Finding(authorityId, objectId, status, kind, latestComparisonId,
                    latestOutcome, latestClassificationFingerprint, occurrences, episodes,
                    firstSeenAt, lastObservedAt, lastEvaluatedAt, resolution, resolvedAt, version,
                    fingerprint);
        }

        private FindingMaterial fingerprintMaterial() {
            return new FindingMaterial(FindingMaterial.SCHEMA_VERSION, authorityId, objectId,
                    status, kind, latestComparisonId, latestOutcome,
                    latestClassificationFingerprint, occurrences, episodes, firstSeenAt,
                    lastObservedAt, lastEvaluatedAt, resolution, resolvedAt, version);
        }
    }

    /**
     * Immutable payload-free transition evidence for one source classification.
     *
     * @param projectionId finding projection identity
     * @param comparisonId source comparison identity
     * @param authorityId exact inventory authority
     * @param objectId deterministic WORM object identity
     * @param classificationOutcome source outcome
     * @param classificationFingerprint exact source fingerprint
     * @param transition deterministic lifecycle transition
     * @param previousFindingFingerprint frozen pre-state fingerprint, or empty
     * @param resultingFindingFingerprint resulting current-state fingerprint, or empty
     * @param resultingFindingVersion resulting lifecycle version, or zero when no finding exists
     * @param occurredAt database-clock transition time
     * @param eventFingerprint fingerprint over every preceding field
     */
    public record FindingEvent(
            String projectionId,
            String comparisonId,
            String authorityId,
            String objectId,
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Outcome classificationOutcome,
            String classificationFingerprint,
            Transition transition,
            String previousFindingFingerprint,
            String resultingFindingFingerprint,
            long resultingFindingVersion,
            Instant occurredAt,
            String eventFingerprint) {
        /** Validates event shape; {@link #verify(ObjectMapper)} verifies canonical integrity. */
        public FindingEvent {
            projectionId = requiredUuid(projectionId, "finding projection ID");
            comparisonId = requiredUuid(comparisonId, "finding comparison ID");
            authorityId = requiredIdentifier(authorityId, "finding authority");
            if (!OBJECT_ID.matcher(normalized(objectId)).matches()) {
                throw new IllegalArgumentException("Invalid external finding event object ID");
            }
            classificationOutcome = Objects.requireNonNull(
                    classificationOutcome, "classificationOutcome");
            transition = Objects.requireNonNull(transition, "transition");
            previousFindingFingerprint = normalized(previousFindingFingerprint);
            resultingFindingFingerprint = normalized(resultingFindingFingerprint);
            eventFingerprint = normalized(eventFingerprint);
            if (!FINGERPRINT.matcher(classificationFingerprint).matches()
                    || (!previousFindingFingerprint.isEmpty()
                    && !FINGERPRINT.matcher(previousFindingFingerprint).matches())
                    || (!resultingFindingFingerprint.isEmpty()
                    && !FINGERPRINT.matcher(resultingFindingFingerprint).matches())
                    || (!eventFingerprint.isEmpty()
                    && !FINGERPRINT.matcher(eventFingerprint).matches())
                    || resultingFindingVersion < 0 || occurredAt == null
                    || (resultingFindingFingerprint.isEmpty() != (resultingFindingVersion == 0))) {
                throw new IllegalArgumentException("Invalid external finding event");
            }
        }

        /** @param objectMapper canonical mapper used to verify the whole-event fingerprint */
        public void verify(ObjectMapper objectMapper) {
            if (!eventFingerprint.equals(ProtocolFingerprint.of(
                    Objects.requireNonNull(objectMapper, "objectMapper"), fingerprintMaterial()))) {
                throw new IllegalStateException("External finding event fingerprint is corrupt");
            }
        }

        private FindingEvent withFingerprint(String fingerprint) {
            return new FindingEvent(projectionId, comparisonId, authorityId, objectId,
                    classificationOutcome, classificationFingerprint, transition,
                    previousFindingFingerprint, resultingFindingFingerprint,
                    resultingFindingVersion, occurredAt, fingerprint);
        }

        private FindingEventMaterial fingerprintMaterial() {
            return new FindingEventMaterial(FindingEventMaterial.SCHEMA_VERSION, projectionId,
                    comparisonId, authorityId, objectId, classificationOutcome,
                    classificationFingerprint, transition, previousFindingFingerprint,
                    resultingFindingFingerprint, resultingFindingVersion, occurredAt);
        }
    }

    private record SourceClassification(
            String objectId,
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Outcome outcome,
            String fingerprint,
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Classification value) {
        private void verify(SourceComparison source, ObjectMapper objectMapper) {
            if (!OBJECT_ID.matcher(objectId).matches() || outcome == null
                    || !FINGERPRINT.matcher(fingerprint).matches()
                    || value == null || !value.comparisonId().equals(source.comparisonId())
                    || !value.authorityId().equals(source.authorityId())
                    || !value.fingerprintVerified(objectMapper)
                    || !value.objectId().equals(objectId) || value.outcome() != outcome
                    || !value.classificationFingerprint().equals(fingerprint)) {
                throw new IllegalStateException("External finding source classification is corrupt");
            }
        }
    }

    private record SourceComparison(
            String comparisonId,
            String cycleId,
            String authorityId,
            String status,
            String trustDomain,
            String archiveSetId,
            String failureDomain,
            String remoteSnapshotId,
            long remoteObjectCount,
            String remoteRoot,
            long expectedObjectCount,
            String expectedRoot,
            String nextAfterObjectId,
            long nextPageSequence,
            long classificationCount,
            long matchedCount,
            long missingRemoteCount,
            long unexpectedRemoteCount,
            long materialConflictCount,
            long retentionShortenedCount,
            long unknownCount,
            String classificationRoot,
            long revision,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt,
            String recordFingerprint) {
        private void verify(ObjectMapper objectMapper) {
            long sum = OutcomeCounts.sum(matchedCount, missingRemoteCount, unexpectedRemoteCount,
                    materialConflictCount, retentionShortenedCount, unknownCount);
            if (!isUuid(comparisonId) || !isUuid(cycleId)
                    || !IDENTIFIER.matcher(authorityId).matches() || !"COMPLETED".equals(status)
                    || !IDENTIFIER.matcher(trustDomain).matches()
                    || !IDENTIFIER.matcher(archiveSetId).matches()
                    || !IDENTIFIER.matcher(failureDomain).matches()
                    || remoteSnapshotId.isBlank() || remoteObjectCount < 0
                    || !FINGERPRINT.matcher(remoteRoot).matches() || expectedObjectCount < 0
                    || !FINGERPRINT.matcher(expectedRoot).matches()
                    || (!nextAfterObjectId.isEmpty()
                    && !OBJECT_ID.matcher(nextAfterObjectId).matches())
                    || nextPageSequence < 0 || classificationCount < 0
                    || sum != classificationCount
                    || !FINGERPRINT.matcher(classificationRoot).matches() || revision < 0
                    || startedAt == null || completedAt == null || updatedAt == null
                    || completedAt.isBefore(startedAt) || updatedAt.isBefore(completedAt)
                    || !FINGERPRINT.matcher(recordFingerprint).matches()
                    || !recordFingerprint.equals(ProtocolFingerprint.of(
                    objectMapper, fingerprintMaterial()))) {
                throw new IllegalStateException("External finding source comparison is corrupt");
            }
        }

        private OutcomeCounts outcomeCounts() {
            return new OutcomeCounts(matchedCount, missingRemoteCount, unexpectedRemoteCount,
                    materialConflictCount, retentionShortenedCount, unknownCount);
        }

        private ComparisonStateMaterial fingerprintMaterial() {
            return new ComparisonStateMaterial(ComparisonStateMaterial.SCHEMA_VERSION, comparisonId,
                    cycleId, authorityId, status, trustDomain, archiveSetId, failureDomain,
                    remoteSnapshotId, remoteObjectCount, remoteRoot, expectedObjectCount,
                    expectedRoot, nextAfterObjectId, nextPageSequence, classificationCount,
                    matchedCount, missingRemoteCount, unexpectedRemoteCount, materialConflictCount,
                    retentionShortenedCount, unknownCount, classificationRoot, revision, startedAt,
                    completedAt, updatedAt);
        }
    }

    private record StoredAuthority(
            String authorityId,
            String activeProjectionId,
            String lastCompletedProjectionId,
            String lastAppliedComparisonId,
            Instant lastAppliedComparisonCompletedAt,
            long revision,
            Instant updatedAt,
            String recordFingerprint) {
        private static StoredAuthority initial(String authorityId, Instant now) {
            return new StoredAuthority(authorityId, "", "", "", null, 0, now, "");
        }

        private void verify(ObjectMapper objectMapper) {
            if (!IDENTIFIER.matcher(authorityId).matches()
                    || (!activeProjectionId.isEmpty() && !isUuid(activeProjectionId))
                    || (!lastCompletedProjectionId.isEmpty()
                    && !isUuid(lastCompletedProjectionId))
                    || (!lastAppliedComparisonId.isEmpty()
                    && !isUuid(lastAppliedComparisonId))
                    || (lastAppliedComparisonId.isEmpty()
                    != (lastAppliedComparisonCompletedAt == null))
                    || (lastCompletedProjectionId.isEmpty()
                    != lastAppliedComparisonId.isEmpty())
                    || revision < 0 || updatedAt == null
                    || !FINGERPRINT.matcher(recordFingerprint).matches()
                    || !recordFingerprint.equals(ProtocolFingerprint.of(
                    objectMapper, fingerprintMaterial()))) {
                throw new IllegalStateException("External finding authority state is corrupt");
            }
        }

        private StoredAuthority activated(String projectionId, Instant now) {
            if (!activeProjectionId.isEmpty()) {
                throw new IllegalStateException("External finding authority is already active");
            }
            return new StoredAuthority(authorityId, requiredUuid(projectionId, "projection ID"),
                    lastCompletedProjectionId, lastAppliedComparisonId,
                    lastAppliedComparisonCompletedAt, increment(revision, "authority revision"),
                    now, "");
        }

        private StoredAuthority completed(StoredProjection projection, Instant now) {
            return new StoredAuthority(authorityId, "", projection.projectionId(),
                    projection.comparisonId(), projection.comparisonCompletedAt(),
                    increment(revision, "authority revision"), now, "");
        }

        private StoredAuthority withFingerprint(String fingerprint) {
            return new StoredAuthority(authorityId, activeProjectionId,
                    lastCompletedProjectionId, lastAppliedComparisonId,
                    lastAppliedComparisonCompletedAt, revision, updatedAt, fingerprint);
        }

        private FindingAuthorityMaterial fingerprintMaterial() {
            return new FindingAuthorityMaterial(FindingAuthorityMaterial.SCHEMA_VERSION,
                    authorityId, activeProjectionId, lastCompletedProjectionId,
                    lastAppliedComparisonId, lastAppliedComparisonCompletedAt, revision, updatedAt);
        }

        private Object[] sqlArguments() {
            return new Object[]{authorityId, activeProjectionId, lastCompletedProjectionId,
                    lastAppliedComparisonId, timestamp(lastAppliedComparisonCompletedAt), revision,
                    Timestamp.from(updatedAt), recordFingerprint};
        }
    }

    private record StoredProjection(
            String projectionId,
            String comparisonId,
            String authorityId,
            String status,
            Instant comparisonStartedAt,
            Instant comparisonCompletedAt,
            long sourceCount,
            String sourceRoot,
            long snapshotCount,
            String snapshotRoot,
            String nextAfterObjectId,
            long nextPageSequence,
            long processedCount,
            long openedCount,
            long observedCount,
            long reopenedCount,
            long resolvedCount,
            long confirmedCount,
            String eventRoot,
            long revision,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt,
            String recordFingerprint) {
        private static StoredProjection initial(
                String projectionId,
                SourceComparison source,
                long snapshotCount,
                String snapshotRoot,
                Instant now) {
            return new StoredProjection(projectionId, source.comparisonId(), source.authorityId(),
                    "ACTIVE", source.startedAt(), source.completedAt(),
                    source.classificationCount(), source.classificationRoot(), snapshotCount,
                    snapshotRoot, "", 0, 0, 0, 0, 0, 0, 0, EMPTY_EVENT_ROOT, 0, now, null,
                    now, "");
        }

        private void verify(ObjectMapper objectMapper) {
            boolean knownStatus = "ACTIVE".equals(status) || "COMPLETED".equals(status);
            long sum = TransitionCounts.sum(openedCount, observedCount, reopenedCount,
                    resolvedCount, confirmedCount);
            if (!isUuid(projectionId) || !isUuid(comparisonId)
                    || !IDENTIFIER.matcher(authorityId).matches() || !knownStatus
                    || comparisonStartedAt == null || comparisonCompletedAt == null
                    || comparisonCompletedAt.isBefore(comparisonStartedAt) || sourceCount < 0
                    || !FINGERPRINT.matcher(sourceRoot).matches() || snapshotCount < 0
                    || !FINGERPRINT.matcher(snapshotRoot).matches()
                    || (!nextAfterObjectId.isEmpty()
                    && !OBJECT_ID.matcher(nextAfterObjectId).matches())
                    || nextPageSequence < 0 || processedCount < 0 || sum != processedCount
                    || processedCount > sourceCount || !FINGERPRINT.matcher(eventRoot).matches()
                    || revision < 0 || startedAt == null || updatedAt == null
                    || ("ACTIVE".equals(status) != (completedAt == null))
                    || updatedAt.isBefore(startedAt)
                    || (completedAt != null && (completedAt.isBefore(startedAt)
                    || updatedAt.isBefore(completedAt)))
                    || !FINGERPRINT.matcher(recordFingerprint).matches()
                    || !recordFingerprint.equals(ProtocolFingerprint.of(
                    objectMapper, fingerprintMaterial()))) {
                throw new IllegalStateException("External finding projection state is corrupt");
            }
        }

        private boolean matches(SourceComparison source) {
            return comparisonId.equals(source.comparisonId())
                    && authorityId.equals(source.authorityId())
                    && comparisonStartedAt.equals(source.startedAt())
                    && comparisonCompletedAt.equals(source.completedAt())
                    && sourceCount == source.classificationCount()
                    && sourceRoot.equals(source.classificationRoot());
        }

        private void requireActiveFor(String expectedAuthority) {
            if (!"ACTIVE".equals(status) || !authorityId.equals(expectedAuthority)) {
                throw new IllegalStateException("External finding projection is not active");
            }
        }

        private void requireCompleted() {
            if (!"COMPLETED".equals(status)) {
                throw new IllegalStateException("External finding projection is not complete");
            }
        }

        private StoredProjection progressed(
                String cursor,
                long sequence,
                long processed,
                TransitionCounts counts,
                String root,
                Instant now) {
            return new StoredProjection(projectionId, comparisonId, authorityId, status,
                    comparisonStartedAt, comparisonCompletedAt, sourceCount, sourceRoot,
                    snapshotCount, snapshotRoot, cursor, sequence, processed, counts.opened(),
                    counts.observed(), counts.reopened(), counts.resolved(), counts.confirmed(),
                    root, increment(revision, "projection revision"), startedAt, completedAt, now,
                    "");
        }

        private StoredProjection completed(Instant now) {
            return new StoredProjection(projectionId, comparisonId, authorityId, "COMPLETED",
                    comparisonStartedAt, comparisonCompletedAt, sourceCount, sourceRoot,
                    snapshotCount, snapshotRoot, nextAfterObjectId, nextPageSequence,
                    processedCount, openedCount, observedCount, reopenedCount, resolvedCount,
                    confirmedCount, eventRoot, increment(revision, "projection revision"),
                    startedAt, now, now, "");
        }

        private StoredProjection withFingerprint(String fingerprint) {
            return new StoredProjection(projectionId, comparisonId, authorityId, status,
                    comparisonStartedAt, comparisonCompletedAt, sourceCount, sourceRoot,
                    snapshotCount, snapshotRoot, nextAfterObjectId, nextPageSequence,
                    processedCount, openedCount, observedCount, reopenedCount, resolvedCount,
                    confirmedCount, eventRoot, revision, startedAt, completedAt, updatedAt,
                    fingerprint);
        }

        private TransitionCounts transitionCounts() {
            return new TransitionCounts(openedCount, observedCount, reopenedCount, resolvedCount,
                    confirmedCount);
        }

        private long actionableTransitions() {
            return TransitionCounts.sum(openedCount, observedCount, reopenedCount, resolvedCount);
        }

        private FindingProjectionMaterial fingerprintMaterial() {
            return new FindingProjectionMaterial(FindingProjectionMaterial.SCHEMA_VERSION,
                    projectionId, comparisonId, authorityId, status, comparisonStartedAt,
                    comparisonCompletedAt, sourceCount, sourceRoot, snapshotCount, snapshotRoot,
                    nextAfterObjectId, nextPageSequence, processedCount, openedCount, observedCount,
                    reopenedCount, resolvedCount, confirmedCount, eventRoot, revision, startedAt,
                    completedAt, updatedAt);
        }

        private Object[] sqlArguments() {
            return new Object[]{projectionId, comparisonId, authorityId, status,
                    Timestamp.from(comparisonStartedAt), Timestamp.from(comparisonCompletedAt),
                    sourceCount, sourceRoot, snapshotCount, snapshotRoot, nextAfterObjectId,
                    nextPageSequence, processedCount, openedCount, observedCount, reopenedCount,
                    resolvedCount, confirmedCount, eventRoot, revision, Timestamp.from(startedAt),
                    timestamp(completedAt), Timestamp.from(updatedAt), recordFingerprint};
        }
    }

    private record TransitionResult(Finding resultingFinding, FindingEvent event) {
    }

    private record SnapshotReplay(long count, String root) {
    }

    private record ClassificationReplay(long count, String root, OutcomeCounts counts) {
    }

    private record EventReplay(long count, String root, TransitionCounts counts) {
    }

    private record OutcomeCounts(
            long matched,
            long missingRemote,
            long unexpectedRemote,
            long materialConflict,
            long retentionShortened,
            long unknown) {
        private static OutcomeCounts empty() {
            return new OutcomeCounts(0, 0, 0, 0, 0, 0);
        }

        private OutcomeCounts increment(
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .Outcome outcome) {
            return switch (outcome) {
                case MATCHED -> new OutcomeCounts(
                        DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                .increment(matched, "matched count"),
                        missingRemote, unexpectedRemote, materialConflict, retentionShortened,
                        unknown);
                case MISSING_REMOTE -> new OutcomeCounts(matched,
                        DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                .increment(missingRemote, "missing remote count"), unexpectedRemote,
                        materialConflict, retentionShortened, unknown);
                case UNEXPECTED_REMOTE -> new OutcomeCounts(matched, missingRemote,
                        DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                .increment(unexpectedRemote, "unexpected remote count"), materialConflict,
                        retentionShortened, unknown);
                case MATERIAL_CONFLICT -> new OutcomeCounts(matched, missingRemote,
                        unexpectedRemote,
                        DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                .increment(materialConflict, "material conflict count"),
                        retentionShortened, unknown);
                case RETENTION_SHORTENED -> new OutcomeCounts(matched, missingRemote,
                        unexpectedRemote, materialConflict,
                        DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                .increment(retentionShortened, "retention shortened count"), unknown);
                case UNKNOWN -> new OutcomeCounts(matched, missingRemote, unexpectedRemote,
                        materialConflict, retentionShortened,
                        DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                .increment(unknown, "unknown count"));
            };
        }

        private static long sum(long... values) {
            long result = 0;
            for (long value : values) {
                if (value < 0) {
                    return -1;
                }
                result = add(result, value, "outcome count");
            }
            return result;
        }
    }

    private record TransitionCounts(
            long opened,
            long observed,
            long reopened,
            long resolved,
            long confirmed) {
        private static TransitionCounts empty() {
            return new TransitionCounts(0, 0, 0, 0, 0);
        }

        private TransitionCounts increment(Transition transition) {
            return switch (transition) {
                case OPENED -> new TransitionCounts(
                        DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                .increment(opened, "opened count"), observed,
                        reopened, resolved, confirmed);
                case OBSERVED -> new TransitionCounts(opened,
                        DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                .increment(observed, "observed count"), reopened, resolved, confirmed);
                case REOPENED -> new TransitionCounts(opened, observed,
                        DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                .increment(reopened, "reopened count"), resolved, confirmed);
                case RESOLVED -> new TransitionCounts(opened, observed, reopened,
                        DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                .increment(resolved, "resolved count"), confirmed);
                case CONFIRMED -> new TransitionCounts(opened, observed, reopened, resolved,
                        DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                .increment(confirmed, "confirmed count"));
            };
        }

        private static long sum(long... values) {
            long result = 0;
            for (long value : values) {
                if (value < 0) {
                    return -1;
                }
                result = add(result, value, "transition count");
            }
            return result;
        }
    }

    private record ComparisonStateMaterial(
            String schemaVersion,
            String comparisonId,
            String cycleId,
            String authorityId,
            String status,
            String trustDomain,
            String archiveSetId,
            String failureDomain,
            String remoteSnapshotId,
            long remoteObjectCount,
            String remoteRoot,
            long expectedObjectCount,
            String expectedRoot,
            String nextAfterObjectId,
            long nextPageSequence,
            long classifiedObjectCount,
            long matchedCount,
            long missingRemoteCount,
            long unexpectedRemoteCount,
            long materialConflictCount,
            long retentionShortenedCount,
            long unknownCount,
            String classificationRoot,
            long revision,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt) {
        private static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityObservationExternalArchiveComparisonState.v1";
    }

    private record ClassificationRootLink(
            String schemaVersion,
            String previousRoot,
            String classificationFingerprint) {
        private static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityObservationExternalArchiveClassificationRootLink.v1";
    }

    private record ClassificationMaterial(
            String schemaVersion,
            String comparisonId,
            String cycleId,
            String authorityId,
            String objectId,
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Outcome outcome,
            String expectedItemFingerprint,
            String observedItemFingerprint,
            String expectedObjectCommitment,
            String observedObjectCommitment,
            String expectedTopologyFingerprint,
            String observedTopologyFingerprint,
            Instant expectedRetainUntil,
            Instant observedRetainUntil) {
        private static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityObservationExternalArchiveClassification.v1";
    }

    private record TopologyMaterial(
            String schemaVersion,
            String trustDomain,
            String archiveSetId,
            String authorityId,
            String failureDomain) {
        private static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityObservationExternalArchiveTopology.v1";
    }

    private record FindingMaterial(
            String schemaVersion,
            String authorityId,
            String objectId,
            FindingStatus status,
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Outcome kind,
            String latestComparisonId,
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Outcome latestOutcome,
            String latestClassificationFingerprint,
            long occurrences,
            long episodes,
            Instant firstSeenAt,
            Instant lastObservedAt,
            Instant lastEvaluatedAt,
            Resolution resolution,
            Instant resolvedAt,
            long version) {
        private static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityObservationExternalArchiveFinding.v1";
    }

    private record FindingEventMaterial(
            String schemaVersion,
            String projectionId,
            String comparisonId,
            String authorityId,
            String objectId,
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Outcome classificationOutcome,
            String classificationFingerprint,
            Transition transition,
            String previousFindingFingerprint,
            String resultingFindingFingerprint,
            long resultingFindingVersion,
            Instant occurredAt) {
        private static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityObservationExternalArchiveFindingEvent.v1";
    }

    private record EvidenceRetirement(
            String projectionId,
            String comparisonId,
            String authorityId,
            String status,
            Instant startedAt,
            Instant completedAt,
            String recordFingerprint) {
        private void verify(ObjectMapper objectMapper) {
            boolean active = "ACTIVE".equals(status);
            boolean completed = "COMPLETED".equals(status);
            if (!isUuid(projectionId) || !isUuid(comparisonId)
                    || !IDENTIFIER.matcher(normalized(authorityId)).matches()
                    || (!active && !completed) || startedAt == null
                    || (active != (completedAt == null))
                    || (completedAt != null && completedAt.isBefore(startedAt))
                    || !FINGERPRINT.matcher(normalized(recordFingerprint)).matches()
                    || !recordFingerprint.equals(evidenceRetirementFingerprint(
                    objectMapper, projectionId, comparisonId, authorityId, status,
                    startedAt, completedAt))) {
                throw new IllegalStateException(
                        "External finding evidence retirement is corrupt");
            }
        }
    }

    private record FindingSnapshotRootLink(
            String schemaVersion,
            String previousRoot,
            String findingFingerprint) {
        private static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityObservationExternalArchiveFindingSnapshotRootLink.v1";
    }

    private record FindingEventRootLink(
            String schemaVersion,
            String previousRoot,
            String eventFingerprint) {
        private static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityObservationExternalArchiveFindingEventRootLink.v1";
    }

    private record EvidenceRetirementMaterial(
            String schemaVersion,
            String projectionId,
            String comparisonId,
            String authorityId,
            String status,
            Instant startedAt,
            Instant completedAt) {
        private static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityObservationExternalArchiveFindingEvidenceRetirement.v1";
    }

    private record FindingAuthorityMaterial(
            String schemaVersion,
            String authorityId,
            String activeProjectionId,
            String lastCompletedProjectionId,
            String lastAppliedComparisonId,
            Instant lastAppliedComparisonCompletedAt,
            long revision,
            Instant updatedAt) {
        private static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityObservationExternalArchiveFindingAuthority.v1";
    }

    private record FindingProjectionMaterial(
            String schemaVersion,
            String projectionId,
            String comparisonId,
            String authorityId,
            String status,
            Instant comparisonStartedAt,
            Instant comparisonCompletedAt,
            long sourceClassificationCount,
            String sourceClassificationRoot,
            long snapshotFindingCount,
            String snapshotRoot,
            String nextAfterObjectId,
            long nextPageSequence,
            long processedClassificationCount,
            long openedCount,
            long observedCount,
            long reopenedCount,
            long resolvedCount,
            long confirmedCount,
            String eventRoot,
            long revision,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt) {
        private static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityObservationExternalArchiveFindingProjection.v1";
    }
}
