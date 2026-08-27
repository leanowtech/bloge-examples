package com.leanowtech.bloge.gateway.testing.correctness.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/** PostgreSQL/H2 immutable repository with canonical-document and indexed-column verification. */
public class DatabaseCorrectnessGovernanceRepository
        implements CorrectnessGovernanceRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DatabaseCorrectnessGovernanceRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public Optional<StoredOutcomeCalibrationProposal> findProposal(
            EnterpriseScope scope, String proposalId) {
        EnterpriseScope exactScope = Objects.requireNonNull(scope, "scope");
        String exactId = required(proposalId, "proposalId");
        return jdbc.query("""
                        SELECT * FROM rg_outcome_calibration_proposals
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND proposal_id = ?
                        """,
                (result, row) -> readProposal(result, exactScope, exactId),
                scopeArgs(exactScope, exactId)).stream().flatMap(Optional::stream).findFirst();
    }

    @Override
    @Transactional
    public StoredOutcomeCalibrationProposal saveProposalIfAbsent(
            EnterpriseScope scope,
            StoredOutcomeCalibrationProposal candidate,
            OutcomeCalibrationProposed event
    ) {
        EnterpriseScope exactScope = Objects.requireNonNull(scope, "scope");
        requireProposal(exactScope, candidate, event);
        OutcomeCalibrationProposal value = candidate.proposal();
        try {
            jdbc.update("""
                            INSERT INTO rg_outcome_calibration_proposals (
                                tenant_id, organization_id, project_id, environment_id, region_id,
                                proposal_id, proposal_fingerprint, status, target_kind, target_id,
                                publication_id, publication_fingerprint, suite_run_id,
                                evidence_companion_id, evidence_companion_fingerprint,
                                mismatch_kind, reason_code, owner_id, canonical_json,
                                created_at, created_by
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    exactScope.tenantId(), exactScope.organizationId(), exactScope.projectId(),
                    exactScope.environment(), exactScope.region(), value.proposalId(),
                    candidate.proposalFingerprint(), value.status().name(),
                    value.target().kind().name(), value.target().id(),
                    value.publicationRef().publicationId(), value.publicationRef().fingerprint(),
                    value.suiteRunId(), value.evidenceCompanionRef().id(),
                    value.evidenceCompanionRef().fingerprint(), value.mismatchKind().name(),
                    value.reasonCode(), value.owner().id(), serialize(candidate),
                    value.metadata().createdAt(), value.metadata().createdBy().id());
            insertEvent(event, event.proposalRef().kind(), event.proposalRef().id(), 1);
        } catch (DuplicateKeyException duplicate) {
            StoredOutcomeCalibrationProposal existing = findProposal(
                    exactScope, value.proposalId()).orElse(null);
            if (candidate.equals(existing)) return existing;
            throw new IllegalStateException(
                    "Outcome calibration proposal immutable identity conflicts with stored content");
        }
        StoredOutcomeCalibrationProposal persisted = findProposal(
                exactScope, value.proposalId()).orElseThrow(() -> new IllegalStateException(
                "Outcome calibration proposal failed read-after-write verification"));
        if (!candidate.equals(persisted)) {
            throw new IllegalStateException("Outcome calibration proposal changed during persistence");
        }
        return persisted;
    }

    @Override
    public Optional<StoredCorrectnessGovernanceFeedback> findFeedback(
            EnterpriseScope scope, String feedbackId) {
        EnterpriseScope exactScope = Objects.requireNonNull(scope, "scope");
        String exactId = required(feedbackId, "feedbackId");
        return jdbc.query("""
                        SELECT * FROM rg_correctness_governance_feedback
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND feedback_id = ?
                        """,
                (result, row) -> readFeedback(result, exactScope, exactId),
                scopeArgs(exactScope, exactId)).stream().flatMap(Optional::stream).findFirst();
    }

    @Override
    public Optional<StoredCorrectnessGovernanceFeedback> findLatestFeedback(
            EnterpriseScope scope, String publicationId, String publicationFingerprint) {
        EnterpriseScope exactScope = Objects.requireNonNull(scope, "scope");
        String exactPublicationId = required(publicationId, "publicationId");
        String exactPublicationFingerprint = fingerprint(
                publicationFingerprint, "publicationFingerprint");
        return jdbc.query("""
                        SELECT * FROM rg_correctness_governance_feedback
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                          AND publication_id = ? AND publication_fingerprint = ?
                        ORDER BY produced_at DESC, source_decision_revision DESC, feedback_id DESC
                        LIMIT 1
                        """,
                (result, row) -> readFeedback(
                        result, exactScope, result.getString("feedback_id")),
                exactScope.tenantId(), exactScope.organizationId(), exactScope.projectId(),
                exactScope.environment(), exactScope.region(), exactPublicationId,
                exactPublicationFingerprint).stream().flatMap(Optional::stream).findFirst();
    }

    @Override
    @Transactional
    public StoredCorrectnessGovernanceFeedback saveFeedbackIfAbsent(
            EnterpriseScope scope,
            StoredCorrectnessGovernanceFeedback candidate,
            CorrectnessGovernanceFeedbackReceived event
    ) {
        EnterpriseScope exactScope = Objects.requireNonNull(scope, "scope");
        requireFeedback(exactScope, candidate, event);
        CorrectnessGovernanceFeedback value = candidate.feedback();
        try {
            jdbc.update("""
                            INSERT INTO rg_correctness_governance_feedback (
                                tenant_id, organization_id, project_id, environment_id, region_id,
                                feedback_id, feedback_fingerprint, publication_id,
                                publication_fingerprint, source_system, source_decision_id,
                                source_decision_revision, source_decision_fingerprint, decision,
                                canonical_json, produced_at, expires_at, received_at, received_by
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    exactScope.tenantId(), exactScope.organizationId(), exactScope.projectId(),
                    exactScope.environment(), exactScope.region(), value.feedbackId(),
                    candidate.feedbackFingerprint(), value.publicationRef().publicationId(),
                    value.publicationRef().fingerprint(), value.sourceSystem(),
                    value.sourceDecisionId(), value.sourceDecisionRevision(),
                    value.sourceDecisionFingerprint(), value.decision().name(),
                    serialize(candidate), value.producedAt(), value.expiresAt(), value.receivedAt(),
                    value.receivedBy());
            insertEvent(event, event.feedbackRef().kind(), event.feedbackRef().id(), 1);
        } catch (DuplicateKeyException duplicate) {
            StoredCorrectnessGovernanceFeedback existing = findFeedback(
                    exactScope, value.feedbackId()).orElse(null);
            if (candidate.equals(existing)) return existing;
            throw new IllegalStateException(
                    "Correctness governance feedback immutable identity conflicts with stored content");
        }
        StoredCorrectnessGovernanceFeedback persisted = findFeedback(
                exactScope, value.feedbackId()).orElseThrow(() -> new IllegalStateException(
                "Correctness governance feedback failed read-after-write verification"));
        if (!candidate.equals(persisted)) {
            throw new IllegalStateException("Correctness governance feedback changed during persistence");
        }
        return persisted;
    }

    private Optional<StoredOutcomeCalibrationProposal> readProposal(
            ResultSet result, EnterpriseScope scope, String proposalId) throws SQLException {
        try {
            StoredOutcomeCalibrationProposal stored = mapper.readValue(
                    result.getString("canonical_json"), StoredOutcomeCalibrationProposal.class);
            OutcomeCalibrationProposal value = stored.proposal();
            String computed = CorrectnessProtocolFingerprint.derivedFingerprint(mapper, value);
            boolean valid = value.scope().equals(scope)
                    && value.proposalId().equals(proposalId)
                    && stored.proposalFingerprint().equals(computed)
                    && stored.proposalFingerprint().equals(result.getString("proposal_fingerprint"))
                    && value.status().name().equals(result.getString("status"))
                    && value.publicationRef().publicationId().equals(
                    result.getString("publication_id"))
                    && value.publicationRef().fingerprint().equals(
                    result.getString("publication_fingerprint"))
                    && value.evidenceCompanionRef().id().equals(
                    result.getString("evidence_companion_id"))
                    && value.evidenceCompanionRef().fingerprint().equals(
                    result.getString("evidence_companion_fingerprint"));
            if (!valid) throw new IllegalStateException(
                    "Stored outcome calibration proposal integrity check failed");
            return Optional.of(stored);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to decode outcome calibration proposal", failure);
        }
    }

    private Optional<StoredCorrectnessGovernanceFeedback> readFeedback(
            ResultSet result, EnterpriseScope scope, String feedbackId) throws SQLException {
        try {
            StoredCorrectnessGovernanceFeedback stored = mapper.readValue(
                    result.getString("canonical_json"),
                    StoredCorrectnessGovernanceFeedback.class);
            CorrectnessGovernanceFeedback value = stored.feedback();
            String computed = CorrectnessProtocolFingerprint.derivedFingerprint(mapper, value);
            boolean valid = value.scope().equals(scope)
                    && value.feedbackId().equals(feedbackId)
                    && stored.feedbackFingerprint().equals(computed)
                    && stored.feedbackFingerprint().equals(result.getString("feedback_fingerprint"))
                    && value.publicationRef().publicationId().equals(
                    result.getString("publication_id"))
                    && value.publicationRef().fingerprint().equals(
                    result.getString("publication_fingerprint"))
                    && value.sourceDecisionId().equals(result.getString("source_decision_id"))
                    && value.sourceDecisionRevision() == result.getLong("source_decision_revision")
                    && value.sourceDecisionFingerprint().equals(
                    result.getString("source_decision_fingerprint"))
                    && value.decision().name().equals(result.getString("decision"));
            if (!valid) throw new IllegalStateException(
                    "Stored correctness governance feedback integrity check failed");
            return Optional.of(stored);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to decode correctness governance feedback", failure);
        }
    }

    private void insertEvent(Object event, String aggregateKind, String aggregateId, long revision) {
        EnterpriseScope scope;
        String eventId;
        String eventType;
        java.time.Instant occurredAt;
        if (event instanceof OutcomeCalibrationProposed value) {
            scope = value.scope();
            eventId = value.eventId();
            eventType = OutcomeCalibrationProposed.SCHEMA_VERSION;
            occurredAt = value.occurredAt();
        } else if (event instanceof CorrectnessGovernanceFeedbackReceived value) {
            scope = value.scope();
            eventId = value.eventId();
            eventType = CorrectnessGovernanceFeedbackReceived.SCHEMA_VERSION;
            occurredAt = value.occurredAt();
        } else {
            throw new IllegalArgumentException("Unsupported correctness governance event");
        }
        jdbc.update("""
                        INSERT INTO rg_correctness_outbox (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            event_id, aggregate_kind, aggregate_id, aggregate_revision,
                            event_type, event_json, occurred_at, published_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                        """,
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environment(),
                scope.region(), eventId, aggregateKind, aggregateId, revision, eventType,
                serialize(event), occurredAt);
    }

    private static void requireProposal(
            EnterpriseScope scope,
            StoredOutcomeCalibrationProposal candidate,
            OutcomeCalibrationProposed event
    ) {
        if (candidate == null || event == null || !scope.equals(candidate.proposal().scope())
                || !scope.equals(event.scope())
                || !candidate.proposal().proposalId().equals(event.proposalRef().id())
                || !candidate.proposalFingerprint().equals(event.proposalRef().fingerprint())
                || !candidate.proposal().publicationRef().equals(event.publicationRef())
                || !candidate.proposal().evidenceCompanionRef().equals(
                event.evidenceCompanionRef())) {
            throw new IllegalArgumentException("Exact calibration proposal and event closure is required");
        }
    }

    private static void requireFeedback(
            EnterpriseScope scope,
            StoredCorrectnessGovernanceFeedback candidate,
            CorrectnessGovernanceFeedbackReceived event
    ) {
        if (candidate == null || event == null || !scope.equals(candidate.feedback().scope())
                || !scope.equals(event.scope())
                || !candidate.feedback().feedbackId().equals(event.feedbackRef().id())
                || !candidate.feedbackFingerprint().equals(event.feedbackRef().fingerprint())
                || !candidate.feedback().publicationRef().equals(event.publicationRef())
                || candidate.feedback().decision() != event.decision()) {
            throw new IllegalArgumentException("Exact governance feedback and event closure is required");
        }
    }

    private Object[] scopeArgs(EnterpriseScope scope, String value) {
        return new Object[] {
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environment(),
                scope.region(), value
        };
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to encode correctness governance value", failure);
        }
    }

    private static String fingerprint(String value, String field) {
        String normalized = required(value, field);
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be an exact SHA-256 fingerprint");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
