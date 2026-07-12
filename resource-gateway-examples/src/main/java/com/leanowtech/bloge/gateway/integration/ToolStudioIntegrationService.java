package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable integration projection over existing visual authoring services.
 */
@Service
public class ToolStudioIntegrationService {

    private final GraphDraftRepository draftRepository;
    private final GraphDraftValidator validator;
    private final VisualOperatorCatalog catalog;
    private final VisualGraphRunRepository runRepository;
    private final GovernanceGateResultRepository gateResultRepository;

    @Autowired
    public ToolStudioIntegrationService(GraphDraftRepository draftRepository,
                                        GraphDraftValidator validator,
                                        VisualOperatorCatalog catalog,
                                        VisualGraphRunRepository runRepository,
                                        GovernanceGateResultRepository gateResultRepository) {
        this.draftRepository = draftRepository;
        this.validator = validator;
        this.catalog = catalog;
        this.runRepository = runRepository;
        this.gateResultRepository = gateResultRepository;
    }

    public ToolStudioIntegrationService(GraphDraftRepository draftRepository,
                                        GraphDraftValidator validator,
                                        VisualOperatorCatalog catalog,
                                        VisualGraphRunRepository runRepository) {
        this(draftRepository, validator, catalog, runRepository, new InMemoryGovernanceGateResultRepository());
    }

    public IntegrationEnvelope<IntegrationCapabilities> capabilities() {
        return IntegrationEnvelope.of("CAPABILITIES", IntegrationCapabilities.SCHEMA_VERSION,
                IntegrationCapabilities.current(runRepository != null && runRepository.evidenceSigner().available()));
    }

    public IntegrationEnvelope<GraphDraftIntegrationBundle> exportDraft(String draftId,
                                                                        long revision,
                                                                        IntegrationRequestContext context) {
        context.requireComplete();
        GraphDraft draft = findDraft(draftId, revision, context);
        context.requireDraftScope(draft);
        GraphDraftDependencyReport dependencyReport = GraphDraftDependencyReport.from(draft, catalog);
        VisualValidationResult validation = validator.validate(draft);
        String draftFingerprint = draftFingerprint(draft);
        GraphDraftIntegrationBundle bundle = new GraphDraftIntegrationBundle(
                "", context.tenantId(), context.organizationId(), context.projectId(), context.environmentId(),
                draftFingerprint, draft, operatorSnapshots(draft),
                GraphDraftDependencyProfile.from(draft, dependencyReport, catalog), validation
        );
        return IntegrationEnvelope.of("GRAPH_DRAFT_INTEGRATION_BUNDLE",
                GraphDraftIntegrationBundle.SCHEMA_VERSION, bundle);
    }

    public IntegrationEnvelope<RunEvidenceBundle> runEvidence(String runId,
                                                              IntegrationRequestContext context) {
        VisualGraphRunRecord record = findRun(runId, context);
        return IntegrationEnvelope.of("RUN_EVIDENCE_BUNDLE", RunEvidenceBundle.SCHEMA_VERSION,
                RunEvidenceBundle.from(record, runRepository.evidenceSigner()));
    }

    public IntegrationEnvelope<PayloadReplayBundle> replay(String runId,
                                                           IntegrationRequestContext context) {
        VisualGraphRunRecord record = findRun(runId, context);
        return IntegrationEnvelope.of("PAYLOAD_REPLAY_BUNDLE", PayloadReplayBundle.SCHEMA_VERSION,
                PayloadReplayBundle.from(record));
    }

    public IntegrationEnvelope<VisualEvidenceSigner.VerificationKey> evidenceKey(String keyId) {
        VisualEvidenceSigner signer = runRepository == null
                ? VisualEvidenceSigner.unavailable()
                : runRepository.evidenceSigner();
        VisualEvidenceSigner.VerificationKey key = signer.key(keyId).orElseThrow(() ->
                new IntegrationProblemException(IntegrationProblem.notFound(
                        "RG.INTEGRATION.EVIDENCE_KEY_NOT_FOUND",
                        "Evidence verification key was not found.", "", Map.of("keyId", keyId == null ? "" : keyId)
                )));
        return IntegrationEnvelope.of("EVIDENCE_VERIFICATION_KEY",
                VisualEvidenceSigner.VerificationKey.SCHEMA_VERSION, key);
    }

    public IntegrationEnvelope<GovernanceGateResult> submitGateResult(GovernanceGateResult result,
                                                                      IntegrationRequestContext context) {
        context.requireComplete();
        requirePurpose(context, "GOVERNANCE_GATE_FEEDBACK");
        validateGateResult(result, context);
        GraphDraft targetDraft = findDraft(result.target().draftId(), result.target().revision(), context);
        context.requireDraftScope(targetDraft);
        String actualFingerprint = draftFingerprint(targetDraft);
        if (!actualFingerprint.equals(result.target().draftFingerprint())) {
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.INTEGRATION.GATE_TARGET_STALE",
                    "Governance gate result targets a different draft snapshot.", context.correlationId(),
                    Map.of("draftId", targetDraft.draftId(), "revision", targetDraft.revision(),
                            "expectedDraftFingerprint", actualFingerprint)
            ));
        }
        GovernanceGateResult existing = gateResultRepository.find(result.gateResultId()).orElse(null);
        if (existing != null) {
            if (existing.resultFingerprint().equals(result.resultFingerprint())) {
                return IntegrationEnvelope.of("GOVERNANCE_GATE_RESULT", GovernanceGateResult.SCHEMA_VERSION,
                        existing);
            }
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.INTEGRATION.GATE_RESULT_ID_CONFLICT",
                    "Gate result id already identifies different immutable content.", context.correlationId(),
                    Map.of("gateResultId", result.gateResultId())
            ));
        }
        GovernanceGateResult stored = gateResultRepository.create(result);
        return IntegrationEnvelope.of("GOVERNANCE_GATE_RESULT", GovernanceGateResult.SCHEMA_VERSION, stored);
    }

    public IntegrationEnvelope<GovernanceGateView> governanceGate(String draftId,
                                                                  IntegrationRequestContext context) {
        context.requireComplete();
        GraphDraft draft = findDraft(draftId, 0, context);
        context.requireDraftScope(draft);
        GovernanceGateView view = governanceGateView(draft);
        return IntegrationEnvelope.of("GOVERNANCE_GATE_VIEW", GovernanceGateView.SCHEMA_VERSION, view);
    }

    public GovernanceGateView authoringGovernanceGate(String draftId) {
        if (draftRepository == null) {
            return new GovernanceGateView("", draftId, 0, "", "MISSING", null);
        }
        GraphDraft draft = draftRepository.find(draftId).orElse(null);
        return draft == null
                ? new GovernanceGateView("", draftId, 0, "", "MISSING", null)
                : governanceGateView(draft);
    }

    private GovernanceGateView governanceGateView(GraphDraft draft) {
        String currentFingerprint = draftFingerprint(draft);
        GovernanceGateResult latest = gateResultRepository == null
                ? null
                : gateResultRepository.forDraft(draft.draftId()).stream().findFirst().orElse(null);
        String freshness = "MISSING";
        if (latest != null) {
            if (latest.expiresAt() != null && !latest.expiresAt().isAfter(Instant.now())) {
                freshness = "EXPIRED";
            } else if (latest.target().revision() != draft.revision()
                    || !latest.target().draftFingerprint().equals(currentFingerprint)) {
                freshness = "STALE";
            } else {
                freshness = "CURRENT";
            }
        }
        return new GovernanceGateView("", draft.draftId(), draft.revision(), currentFingerprint, freshness, latest);
    }

    private static void validateGateResult(GovernanceGateResult result, IntegrationRequestContext context) {
        Map<String, Object> invalid = new LinkedHashMap<>();
        if (result == null) {
            invalid.put("result", "required");
        } else {
            if (!GovernanceGateResult.SCHEMA_VERSION.equals(result.schemaVersion())) {
                invalid.put("schemaVersion", GovernanceGateResult.SCHEMA_VERSION);
            }
            if (result.gateResultId().isBlank()) invalid.put("gateResultId", "required");
            if (!"GRAPH_DRAFT".equals(result.target().kind())) invalid.put("target.kind", "GRAPH_DRAFT");
            if (result.target().draftId().isBlank()) invalid.put("target.draftId", "required");
            if (result.target().revision() <= 0) invalid.put("target.revision", "positive");
            if (result.target().draftFingerprint().isBlank()) invalid.put("target.draftFingerprint", "required");
            if (!result.fingerprintVerified()) invalid.put("resultFingerprint", "does not match content");
        }
        if (!invalid.isEmpty()) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.INTEGRATION.GATE_RESULT_INVALID", "Governance gate result is invalid.",
                    context.correlationId(), invalid));
        }
    }

    private static void requirePurpose(IntegrationRequestContext context, String requiredPurpose) {
        if (!requiredPurpose.equals(context.purpose())) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.INTEGRATION.PURPOSE_NOT_ALLOWED", "Integration purpose is not allowed for this operation.",
                    context.correlationId(), Map.of("requiredPurpose", requiredPurpose)));
        }
    }

    private GraphDraft findDraft(String draftId, long revision, IntegrationRequestContext context) {
        if (draftRepository == null) {
            throw notFound(context);
        }
        return (revision > 0
                ? draftRepository.findRevision(draftId, revision)
                : draftRepository.find(draftId))
                .orElseThrow(() -> notFound(context));
    }

    private static IntegrationProblemException notFound(IntegrationRequestContext context) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                "RG.INTEGRATION.DRAFT_NOT_FOUND",
                "Draft was not found in the authorized integration scope.",
                context == null ? "" : context.correlationId(),
                Map.of()
        ));
    }

    private VisualGraphRunRecord findRun(String runId, IntegrationRequestContext context) {
        context.requireComplete();
        VisualGraphRunRecord record = runRepository == null
                ? null
                : runRepository.find(runId).orElse(null);
        if (record == null
                || !context.tenantId().equals(record.tenantId())
                || !context.environmentId().equals(record.environment())) {
            throw new IntegrationProblemException(IntegrationProblem.notFound(
                    "RG.INTEGRATION.RUN_NOT_FOUND",
                    "Run was not found in the authorized integration scope.",
                    context.correlationId(),
                    Map.of()
            ));
        }
        return record;
    }

    private List<OperatorDefinition> operatorSnapshots(GraphDraft draft) {
        Map<String, OperatorDefinition> snapshots = new LinkedHashMap<>();
        for (GraphDraft.DraftNode node : draft.nodes()) {
            OperatorDefinition operator = catalog == null ? null : catalog.find(node.operatorRef()).orElse(null);
            if (operator == null) {
                operator = draft.operatorSnapshots().get(node.id());
            }
            if (operator != null) {
                snapshots.putIfAbsent(operator.operatorRef() + "@" + operator.fingerprint(), operator);
            }
        }
        return List.copyOf(snapshots.values());
    }

    static String draftFingerprint(GraphDraft draft) {
        return VisualBundleFingerprint.fromMaterial(Map.of("draft", draft.withNodeFixtures(Map.of())));
    }
}
