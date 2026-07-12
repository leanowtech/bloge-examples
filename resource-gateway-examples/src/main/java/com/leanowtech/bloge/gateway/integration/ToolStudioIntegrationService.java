package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualReplayAssertionResult;
import com.leanowtech.bloge.gateway.visual.runtime.VisualReplayMetadata;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
    private final ReplayAssertionEvaluator replayAssertionEvaluator;

    @Autowired
    public ToolStudioIntegrationService(GraphDraftRepository draftRepository,
                                        GraphDraftValidator validator,
                                        VisualOperatorCatalog catalog,
                                        VisualGraphRunRepository runRepository,
                                        GovernanceGateResultRepository gateResultRepository,
                                        ObjectMapper objectMapper) {
        this.draftRepository = draftRepository;
        this.validator = validator;
        this.catalog = catalog;
        this.runRepository = runRepository;
        this.gateResultRepository = gateResultRepository;
        this.replayAssertionEvaluator = new ReplayAssertionEvaluator(objectMapper);
    }

    public ToolStudioIntegrationService(GraphDraftRepository draftRepository,
                                        GraphDraftValidator validator,
                                        VisualOperatorCatalog catalog,
                                        VisualGraphRunRepository runRepository,
                                        GovernanceGateResultRepository gateResultRepository) {
        this(draftRepository, validator, catalog, runRepository, gateResultRepository,
                new ObjectMapper().findAndRegisterModules());
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

    public synchronized IntegrationEnvelope<ReplayExecutionResult> executeReplay(
            String parentRunId,
            ReplayExecutionRequest request,
            IntegrationRequestContext context) {
        context.requireComplete();
        requirePurpose(context, "PAYLOAD_REPLAY");
        VisualGraphRunRecord parent = findRun(parentRunId, context);
        validateReplayRequest(request, parent, context);
        String requestFingerprint = request.fingerprint();
        VisualGraphRunRecord existing = replayByRequest(request.requestId());
        if (existing != null) {
            if (existing.replay().parentRunId().equals(parentRunId)
                    && existing.replay().requestFingerprint().equals(requestFingerprint)) {
                return replayEnvelope(existing);
            }
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.INTEGRATION.REPLAY_REQUEST_ID_CONFLICT",
                    "Replay request id already identifies different immutable content.",
                    context.correlationId(), Map.of("requestId", request.requestId())
            ));
        }

        RunEvidenceBundle parentEvidence = RunEvidenceBundle.from(parent, runRepository.evidenceSigner());
        List<VisualReplayAssertionResult> assertionResults = replayAssertionEvaluator.evaluate(
                request, parent, parentEvidence);
        VisualReplayMetadata replayMetadata = new VisualReplayMetadata(
                "", parentRunId, request.requestId(), requestFingerprint, request.mode(), request.caseType(),
                request.externalSideEffectPolicy(), 0, assertionResults);
        String replayRunId = deterministicReplayRunId(context.tenantId(), parentRunId, request.requestId());
        VisualGraphRunRecord replayRecord = parent.recordedReplay(replayMetadata)
                .withIdentity(replayRunId, Instant.now());
        VisualGraphRunRecord stored = runRepository.create(replayRecord);
        return replayEnvelope(stored);
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

    private static void validateReplayRequest(ReplayExecutionRequest request,
                                              VisualGraphRunRecord parent,
                                              IntegrationRequestContext context) {
        Map<String, Object> invalid = new LinkedHashMap<>();
        if (request == null) {
            invalid.put("request", "required");
        } else {
            if (!ReplayExecutionRequest.SCHEMA_VERSION.equals(request.schemaVersion())) {
                invalid.put("schemaVersion", ReplayExecutionRequest.SCHEMA_VERSION);
            }
            if (request.requestId().isBlank()) invalid.put("requestId", "required");
            if (!"RECORDED_ASSERTIONS".equals(request.mode())) invalid.put("mode", "RECORDED_ASSERTIONS");
            if (!Set.of("GOLDEN", "NEGATIVE", "BOUNDARY", "REGRESSION").contains(request.caseType())) {
                invalid.put("caseType", "GOLDEN|NEGATIVE|BOUNDARY|REGRESSION");
            }
            if (!"DENY".equals(request.externalSideEffectPolicy())) {
                invalid.put("externalSideEffectPolicy", "DENY");
            }
            if (request.assertions().isEmpty()) invalid.put("assertions", "at least one assertion required");
            if (request.assertions().size() > 100) invalid.put("assertions", "maximum 100 assertions");
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < request.assertions().size(); i++) {
                ReplayExecutionRequest.Assertion assertion = request.assertions().get(i);
                String prefix = "assertions[" + i + "]";
                if (assertion.assertionId().isBlank()) invalid.put(prefix + ".assertionId", "required");
                if (!ids.add(assertion.assertionId())) invalid.put(prefix + ".assertionId", "duplicate");
                if (!Set.of("OUTPUT", "NODE", "RUN").contains(assertion.scope())) {
                    invalid.put(prefix + ".scope", "OUTPUT|NODE|RUN");
                }
                if ("NODE".equals(assertion.scope())) {
                    if (assertion.nodeId().isBlank()) {
                        invalid.put(prefix + ".nodeId", "required for NODE scope");
                    } else if (!parent.nodeSnapshots().containsKey(assertion.nodeId())
                            && !parent.resultsPayload().containsKey(assertion.nodeId())) {
                        invalid.put(prefix + ".nodeId", "not captured in parent run");
                    }
                }
                if (!Set.of("EQUALS", "PATH_EQUALS", "PATH_EXISTS", "PATH_ABSENT", "MATCHES_SCHEMA",
                        "ERROR_CONTAINS", "GOVERNANCE_EXPECTATION").contains(assertion.mode())) {
                    invalid.put(prefix + ".mode", "unsupported");
                }
                if (assertion.mode().startsWith("PATH_")
                        && (assertion.path().isBlank() || !assertion.path().startsWith("/"))) {
                    invalid.put(prefix + ".path", "JSON Pointer required");
                }
                if (Set.of("ERROR_CONTAINS", "GOVERNANCE_EXPECTATION").contains(assertion.mode())
                        && !"RUN".equals(assertion.scope())) {
                    invalid.put(prefix + ".scope", "RUN required for " + assertion.mode());
                }
            }
        }
        if (!invalid.isEmpty()) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.INTEGRATION.REPLAY_REQUEST_INVALID", "Replay execution request is invalid.",
                    context.correlationId(), invalid));
        }
    }

    private VisualGraphRunRecord replayByRequest(String requestId) {
        if (runRepository == null || requestId == null || requestId.isBlank()) {
            return null;
        }
        return runRepository.all().stream()
                .filter(record -> requestId.equals(record.replay().requestId()))
                .findFirst()
                .orElse(null);
    }

    private IntegrationEnvelope<ReplayExecutionResult> replayEnvelope(VisualGraphRunRecord replayRecord) {
        RunEvidenceBundle evidence = RunEvidenceBundle.from(replayRecord, runRepository.evidenceSigner());
        VisualReplayMetadata replay = replayRecord.replay();
        ReplayExecutionResult result = new ReplayExecutionResult(
                "", replayRecord.runId(), replay.parentRunId(), replay.requestId(), replay.requestFingerprint(),
                replay.mode(), replay.caseType(), replay.assertionsPassed() ? "PASSED" : "FAILED",
                replay.sideEffectPolicy(), replay.externalInvocationCount(), replay.assertionResults(),
                evidence.manifest().evidenceStatus(), "/api/integration/runs/" + replayRecord.runId() + "/evidence");
        return IntegrationEnvelope.of("REPLAY_EXECUTION_RESULT", ReplayExecutionResult.SCHEMA_VERSION, result);
    }

    private static String deterministicReplayRunId(String tenantId, String parentRunId, String requestId) {
        String material = String.join("\u0000", tenantId, parentRunId, requestId);
        return "replay-" + UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
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
