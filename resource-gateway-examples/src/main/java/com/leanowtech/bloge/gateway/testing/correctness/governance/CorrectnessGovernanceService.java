package com.leanowtech.bloge.gateway.testing.correctness.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.ToolStudioResourceGatewayProtocol;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactCaseRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.publication.CorrectnessPublicationRepository;
import com.leanowtech.bloge.gateway.testing.correctness.publication.StoredCorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.correctness.run.CorrectnessEvidenceRepository;
import com.leanowtech.bloge.gateway.testing.correctness.run.CorrectnessRunRequest.PublicationRef;
import com.leanowtech.bloge.gateway.testing.correctness.run.StoredCorrectnessEvidenceCompanion;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Application boundary that closes every proposal and governance decision over exact evidence. */
public final class CorrectnessGovernanceService {

    private final CorrectnessGovernanceRepository governance;
    private final CorrectnessPublicationRepository publications;
    private final CorrectnessEvidenceRepository evidence;
    private final ObjectMapper mapper;
    private final Clock clock;

    public CorrectnessGovernanceService(
            CorrectnessGovernanceRepository governance,
            CorrectnessPublicationRepository publications,
            CorrectnessEvidenceRepository evidence,
            ObjectMapper mapper,
            Clock clock
    ) {
        this.governance = Objects.requireNonNull(governance, "governance");
        this.publications = Objects.requireNonNull(publications, "publications");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public StoredOutcomeCalibrationProposal propose(
            OutcomeCalibrationRequest request, IntegrationRequestContext identity) {
        try {
            if (request == null) throw new IllegalArgumentException("Request body is required");
            EnterpriseScope scope = scope(identity);
            String proposalId = required(request.proposalId(), "proposalId");
            StoredCorrectnessEvidenceCompanion storedEvidence = evidence.find(
                    scope, required(request.suiteRunId(), "suiteRunId")).orElseThrow(() -> failure(
                    404, "RG.CORRECTNESS.EVIDENCE_NOT_FOUND",
                    "Evidence companion was not found in the authorized scope", false));
            if (!storedEvidence.companionFingerprint().equals(
                    request.evidenceCompanionFingerprint())) {
                throw failure(409, "RG.CORRECTNESS.EVIDENCE_DRIFT",
                        "Evidence companion fingerprint no longer matches", false);
            }
            var companion = storedEvidence.companion();
            List<ExactCaseRef> cases = selectCases(companion.caseRefs(), request.affectedCaseIds());
            List<ExactAssetRef> oracles = selectAssets(
                    companion.oracleRefs(), request.affectedOracleIds(), "Oracle");
            Instant now = clock.instant();
            PrincipalRef actor = principal(identity);
            ExactAssetRef evidenceRef = new ExactAssetRef(
                    "CORRECTNESS_EVIDENCE_COMPANION", companion.evidenceCompanionId(), 1,
                    storedEvidence.companionFingerprint());
            OutcomeCalibrationProposal value = new OutcomeCalibrationProposal(
                    "", proposalId, scope, companion.publicationRef(), companion.suiteRunId(),
                    evidenceRef, companion.target(), cases, oracles, request.mismatchKind(),
                    request.reasonCode(), request.businessRationale(),
                    request.proposedRegressionTitle(),
                    OutcomeCalibrationProposal.ProposalStatus.PROPOSED, actor,
                    required(identity.correlationId(), "correlationId"),
                    new AuditMetadata(now, now, actor, actor));
            StoredOutcomeCalibrationProposal stored =
                    StoredOutcomeCalibrationProposal.verified(mapper, value);
            ExactAssetRef proposalRef = new ExactAssetRef(
                    "OUTCOME_CALIBRATION_PROPOSAL", proposalId, 1,
                    stored.proposalFingerprint());
            OutcomeCalibrationProposed event = new OutcomeCalibrationProposed(
                    "", eventId("outcome-calibration-proposed", stored.proposalFingerprint()),
                    scope, proposalRef, companion.publicationRef(), evidenceRef, companion.target(),
                    companion.suiteRunId(), value.mismatchKind(), value.reasonCode(), actor.id(),
                    identity.correlationId(), now);
            return governance.saveProposalIfAbsent(scope, stored, event);
        } catch (CorrectnessGovernanceException failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw failure(400, "RG.CORRECTNESS.CALIBRATION_REQUEST_INVALID",
                    failure.getMessage(), false);
        } catch (IllegalStateException failure) {
            throw failure(409, "RG.CORRECTNESS.CALIBRATION_CONFLICT",
                    failure.getMessage(), false);
        }
    }

    public StoredOutcomeCalibrationProposal findProposal(
            String proposalId, IntegrationRequestContext identity) {
        return governance.findProposal(scope(identity), proposalId).orElseThrow(() -> failure(
                404, "RG.CORRECTNESS.CALIBRATION_NOT_FOUND",
                "Calibration proposal was not found in the authorized scope", false));
    }

    public StoredCorrectnessGovernanceFeedback receiveFeedback(
            String publicationId,
            CorrectnessGovernanceFeedbackRequest request,
            IntegrationRequestContext identity
    ) {
        try {
            if (request == null) throw new IllegalArgumentException("Request body is required");
            EnterpriseScope scope = scope(identity);
            StoredCorrectnessPublication storedPublication = requirePublication(
                    scope, publicationId, request.publicationFingerprint());
            PublicationRef publicationRef = new PublicationRef(
                    storedPublication.publication().publicationId(),
                    1,
                    storedPublication.publicationFingerprint());
            Instant receivedAt = clock.instant();
            CorrectnessGovernanceFeedback value = new CorrectnessGovernanceFeedback(
                    "", request.feedbackId(), scope, publicationRef, request.sourceSystem(),
                    request.sourceProtocolVersion(), request.sourceDecisionId(),
                    request.sourceDecisionRevision(), request.sourceDecisionFingerprint(),
                    request.decision(), request.workbookStatus(), request.ownerApprovalStatus(),
                    request.breakingMigrationStatus(), request.findings(), request.producedAt(),
                    request.expiresAt(), receivedAt, required(identity.actorId(), "actorId"),
                    required(identity.correlationId(), "correlationId"));
            if (!"ANEKE_TOOL_STUDIO".equals(value.sourceSystem())) {
                throw new IllegalArgumentException(
                        "sourceSystem must be ANEKE_TOOL_STUDIO for this integration boundary");
            }
            if (!ToolStudioResourceGatewayProtocol.SUPPORTED_CONSUMER_VERSIONS
                    .contains(value.sourceProtocolVersion())) {
                throw new IllegalArgumentException(
                        "sourceProtocolVersion is outside the supported compatibility window");
            }
            StoredCorrectnessGovernanceFeedback stored =
                    StoredCorrectnessGovernanceFeedback.verified(mapper, value);
            ExactAssetRef feedbackRef = new ExactAssetRef(
                    "CORRECTNESS_GOVERNANCE_FEEDBACK", value.feedbackId(), 1,
                    stored.feedbackFingerprint());
            CorrectnessGovernanceFeedbackReceived event =
                    new CorrectnessGovernanceFeedbackReceived(
                            "", eventId("correctness-governance-feedback", stored.feedbackFingerprint()),
                            scope, feedbackRef, publicationRef, value.decision(), value.sourceSystem(),
                            value.sourceDecisionId(), value.sourceDecisionRevision(),
                            identity.actorId(), identity.correlationId(), receivedAt);
            return governance.saveFeedbackIfAbsent(scope, stored, event);
        } catch (CorrectnessGovernanceException failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw failure(400, "RG.CORRECTNESS.GOVERNANCE_FEEDBACK_INVALID",
                    failure.getMessage(), false);
        } catch (IllegalStateException failure) {
            throw failure(409, "RG.CORRECTNESS.GOVERNANCE_FEEDBACK_CONFLICT",
                    failure.getMessage(), false);
        }
    }

    public StoredCorrectnessGovernanceFeedback latestFeedback(
            String publicationId, IntegrationRequestContext identity) {
        EnterpriseScope scope = scope(identity);
        StoredCorrectnessPublication publication = requirePublication(scope, publicationId, null);
        return governance.findLatestFeedback(
                scope, publication.publication().publicationId(),
                publication.publicationFingerprint()).orElseThrow(() -> failure(
                404, "RG.CORRECTNESS.GOVERNANCE_FEEDBACK_NOT_FOUND",
                "No governance feedback exists for the exact Publication", false));
    }

    private StoredCorrectnessPublication requirePublication(
            EnterpriseScope scope, String publicationId, String requestedFingerprint) {
        StoredCorrectnessPublication publication = publications.findPublication(
                scope, required(publicationId, "publicationId")).orElseThrow(() -> failure(
                404, "RG.CORRECTNESS.PUBLICATION_NOT_FOUND",
                "Correctness Publication was not found in the authorized scope", false));
        if (requestedFingerprint != null && !requestedFingerprint.isBlank()
                && !publication.publicationFingerprint().equals(requestedFingerprint.trim())) {
            throw failure(409, "RG.CORRECTNESS.PUBLICATION_DRIFT",
                    "Governance feedback Publication fingerprint no longer matches", false);
        }
        return publication;
    }

    private static List<ExactCaseRef> selectCases(
            List<ExactCaseRef> available, List<String> selectedIds) {
        if (selectedIds == null || selectedIds.isEmpty()) return available;
        Set<String> selected = Set.copyOf(selectedIds);
        List<ExactCaseRef> result = available.stream()
                .filter(value -> selected.contains(value.caseId())).toList();
        Set<String> resolved = result.stream().map(ExactCaseRef::caseId).collect(Collectors.toSet());
        if (!resolved.equals(selected)) {
            throw new IllegalArgumentException(
                    "Every affectedCaseId must exist in the exact evidence companion");
        }
        return result;
    }

    private static List<ExactAssetRef> selectAssets(
            List<ExactAssetRef> available, List<String> selectedIds, String label) {
        if (selectedIds == null || selectedIds.isEmpty()) return available;
        Set<String> selected = Set.copyOf(selectedIds);
        List<ExactAssetRef> result = available.stream()
                .filter(value -> selected.contains(value.id())).toList();
        Set<String> resolved = result.stream().map(ExactAssetRef::id).collect(Collectors.toSet());
        if (!resolved.equals(selected)) {
            throw new IllegalArgumentException(
                    "Every affected" + label + "Id must exist in the exact evidence companion");
        }
        return result;
    }

    private String eventId(String prefix, String fingerprint) {
        return prefix + "-" + fingerprint.substring("sha256:".length(), 31);
    }

    private static PrincipalRef principal(IntegrationRequestContext identity) {
        PrincipalKind kind;
        try {
            kind = PrincipalKind.valueOf(identity.actorType().toUpperCase(Locale.ROOT));
        } catch (RuntimeException unknownType) {
            kind = PrincipalKind.SERVICE;
        }
        return new PrincipalRef(required(identity.actorId(), "actorId"), kind, "");
    }

    private static EnterpriseScope scope(IntegrationRequestContext identity) {
        if (identity == null) throw new IllegalArgumentException("Identity is required");
        return new EnterpriseScope(
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.region());
    }

    private static CorrectnessGovernanceException failure(
            int status, String code, String message, boolean retryable) {
        return new CorrectnessGovernanceException(status, code, message, retryable);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
