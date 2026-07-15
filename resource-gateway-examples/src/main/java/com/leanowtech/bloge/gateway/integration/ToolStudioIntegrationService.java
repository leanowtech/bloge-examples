package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.leanowtech.bloge.gateway.visual.runtime.VisualPayloadGovernanceException;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunPayloadRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunPayloadStatus;
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
    private final IntegrationIdentityResolver identityResolver;
    private final SideEffectReconcilerRegistry sideEffectReconcilers;
    private final GraphDraftDependencySnapshotService dependencySnapshots;
    private final CorrectnessWorkbookProjectionService workbookProjection;
    private boolean testExecutionEndpointEnabled;

    @Autowired
    public ToolStudioIntegrationService(GraphDraftRepository draftRepository,
                                        GraphDraftValidator validator,
                                        VisualOperatorCatalog catalog,
                                        VisualGraphRunRepository runRepository,
                                        GovernanceGateResultRepository gateResultRepository,
                                        ObjectMapper objectMapper,
                                        IntegrationIdentityResolver identityResolver,
                                        SideEffectReconcilerRegistry sideEffectReconcilers,
                                        GraphDraftDependencySnapshotService dependencySnapshots,
                                        CorrectnessWorkbookProjectionService workbookProjection) {
        this.draftRepository = draftRepository;
        this.validator = validator;
        this.catalog = catalog;
        this.runRepository = runRepository;
        this.gateResultRepository = gateResultRepository;
        this.replayAssertionEvaluator = new ReplayAssertionEvaluator(objectMapper);
        this.identityResolver = identityResolver == null
                ? IntegrationIdentityResolver.unavailable()
                : identityResolver;
        this.sideEffectReconcilers = sideEffectReconcilers == null
                ? new SideEffectReconcilerRegistry(List.of()) : sideEffectReconcilers;
        this.dependencySnapshots = dependencySnapshots == null
                ? new GraphDraftDependencySnapshotService(catalog) : dependencySnapshots;
        this.workbookProjection = workbookProjection;
    }

    /** Receives the profile-owned marker only when the isolated test control plane is assembled. */
    @Autowired(required = false)
    void configureTestability(TestabilityAvailability availability) {
        this.testExecutionEndpointEnabled = availability != null && availability.executionEndpointEnabled();
    }

    public ToolStudioIntegrationService(GraphDraftRepository draftRepository,
                                        GraphDraftValidator validator,
                                        VisualOperatorCatalog catalog,
                                        VisualGraphRunRepository runRepository,
                                        GovernanceGateResultRepository gateResultRepository,
                                        ObjectMapper objectMapper,
                                        IntegrationIdentityResolver identityResolver,
                                        SideEffectReconcilerRegistry sideEffectReconcilers,
                                        GraphDraftDependencySnapshotService dependencySnapshots) {
        this(draftRepository, validator, catalog, runRepository, gateResultRepository, objectMapper,
                identityResolver, sideEffectReconcilers, dependencySnapshots, null);
    }

    public ToolStudioIntegrationService(GraphDraftRepository draftRepository,
                                        GraphDraftValidator validator,
                                        VisualOperatorCatalog catalog,
                                        VisualGraphRunRepository runRepository,
                                        GovernanceGateResultRepository gateResultRepository,
                                        ObjectMapper objectMapper,
                                        IntegrationIdentityResolver identityResolver,
                                        SideEffectReconcilerRegistry sideEffectReconcilers) {
        this(draftRepository, validator, catalog, runRepository, gateResultRepository, objectMapper,
                identityResolver, sideEffectReconcilers, null, null);
    }

    public ToolStudioIntegrationService(GraphDraftRepository draftRepository,
                                        GraphDraftValidator validator,
                                        VisualOperatorCatalog catalog,
                                        VisualGraphRunRepository runRepository,
                                        GovernanceGateResultRepository gateResultRepository,
                                        ObjectMapper objectMapper,
                                        IntegrationIdentityResolver identityResolver) {
        this(draftRepository, validator, catalog, runRepository, gateResultRepository, objectMapper,
                identityResolver, new SideEffectReconcilerRegistry(List.of()), null, null);
    }

    public ToolStudioIntegrationService(GraphDraftRepository draftRepository,
                                        GraphDraftValidator validator,
                                        VisualOperatorCatalog catalog,
                                        VisualGraphRunRepository runRepository,
                                        GovernanceGateResultRepository gateResultRepository,
                                        ObjectMapper objectMapper) {
        this(draftRepository, validator, catalog, runRepository, gateResultRepository, objectMapper,
                IntegrationIdentityResolver.unavailable(), new SideEffectReconcilerRegistry(List.of()), null, null);
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
        VisualEvidenceSigner signer = runRepository == null
                ? VisualEvidenceSigner.unavailable() : runRepository.evidenceSigner();
        VisualRunPayloadRepository payloads = runRepository == null ? null : runRepository.payloadRepository();
        return IntegrationEnvelope.of("CAPABILITIES", IntegrationCapabilities.SCHEMA_VERSION,
                IntegrationCapabilities.current(signer.descriptor(), identityResolver.descriptor(),
                        sideEffectReconcilers.available(), payloads == null ? null : payloads.policyDescriptor(),
                        testExecutionEndpointEnabled));
    }

    public IntegrationEnvelope<GraphDraftIntegrationBundle> exportDraft(String draftId,
                                                                        long revision,
                                                                        IntegrationRequestContext context) {
        context.requireComplete();
        GraphDraft draft = findDraft(draftId, revision, context);
        context.requireDraftScope(draft);
        GraphDraftDependencySnapshotService.Snapshot dependencySnapshot = dependencySnapshots.capture(draft);
        GraphDraftDependencyReport dependencyReport = GraphDraftDependencyReport.from(
                draft, dependencySnapshot.catalog());
        VisualValidationResult validation = validator.validate(draft);
        String draftFingerprint = draftFingerprint(draft);
        GraphDraftIntegrationBundle bundle = new GraphDraftIntegrationBundle(
                "", context.tenantId(), context.organizationId(), context.projectId(), context.environmentId(),
                draftFingerprint, draft, dependencySnapshot.operators(),
                GraphDraftDependencyProfile.from(draft, dependencyReport, dependencySnapshot), validation
        );
        verifySnapshotStable(draft, revision, dependencySnapshot, context);
        return IntegrationEnvelope.of("GRAPH_DRAFT_INTEGRATION_BUNDLE",
                GraphDraftIntegrationBundle.SCHEMA_VERSION, bundle);
    }

    public IntegrationEnvelope<CorrectnessWorkbookBundle> correctnessWorkbook(
            String draftId,
            long revision,
            IntegrationRequestContext context) {
        context.requireComplete();
        GraphDraft draft = findDraft(draftId, revision, context);
        context.requireDraftScope(draft);
        if (workbookProjection == null) {
            throw new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                    "RG.INTEGRATION.WORKBOOK_PROJECTION_UNAVAILABLE",
                    "Correctness workbook projection is unavailable.", context.correlationId(), Map.of()));
        }
        GraphDraftDependencySnapshotService.Snapshot snapshot = dependencySnapshots.capture(draft);
        try {
            CorrectnessWorkbookBundle bundle = workbookProjection.project(
                    draft, draftFingerprint(draft), snapshot);
            verifySnapshotStable(draft, revision, snapshot, context);
            return IntegrationEnvelope.of("CORRECTNESS_WORKBOOK_BUNDLE",
                    CorrectnessWorkbookBundle.SCHEMA_VERSION, bundle);
        } catch (CorrectnessWorkbookProjectionService.ProjectionException failure) {
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.INTEGRATION.WORKBOOK_SOURCE_CHANGED",
                    "A workbook source changed while the immutable bundle was being projected.",
                    context.correlationId(), Map.of("reason", failure.code())));
        }
    }

    public IntegrationEnvelope<RunEvidenceBundle> runEvidence(String runId,
                                                              IntegrationRequestContext context) {
        VisualGraphRunRecord record = findRun(runId, context);
        VisualRunPayloadStatus payloadStatus = payloadStatus(record);
        return IntegrationEnvelope.of("RUN_EVIDENCE_BUNDLE", RunEvidenceBundle.SCHEMA_VERSION,
                RunEvidenceBundle.from(record, runRepository.evidenceSigner(), payloadStatus));
    }

    public IntegrationEnvelope<PayloadReplayBundle> replay(String runId,
                                                           IntegrationRequestContext context) {
        VisualGraphRunRecord record = findRun(runId, context);
        GovernedPayload governed = governedPayload(record, context);
        return IntegrationEnvelope.of("PAYLOAD_REPLAY_BUNDLE", PayloadReplayBundle.SCHEMA_VERSION,
                PayloadReplayBundle.from(governed.record(), governed.status()));
    }

    public synchronized IntegrationEnvelope<ReplayExecutionResult> executeReplay(
            String parentRunId,
            ReplayExecutionRequest request,
            IntegrationRequestContext context) {
        context.requireComplete();
        requirePurpose(context, "PAYLOAD_REPLAY");
        VisualGraphRunRecord parent = findRun(parentRunId, context);
        GovernedPayload governedParent = governedPayload(parent, context);
        parent = governedParent.record();
        validateReplayRequest(request, parent, context);
        String requestFingerprint = request.fingerprint();
        VisualGraphRunRecord existing = replayByRequest(request.requestId(), context);
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
        try {
            return replayEnvelope(runRepository.create(replayRecord));
        } catch (RuntimeException concurrentCreate) {
            VisualGraphRunRecord winner = runRepository.find(replayRunId).orElse(null);
            if (winner == null) throw concurrentCreate;
            if (context.tenantId().equals(winner.tenantId())
                    && context.environmentId().equals(winner.environment())
                    && winner.replay().parentRunId().equals(parentRunId)
                    && winner.replay().requestFingerprint().equals(requestFingerprint)) {
                return replayEnvelope(winner);
            }
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.INTEGRATION.REPLAY_REQUEST_ID_CONFLICT",
                    "Replay request id already identifies different immutable content.",
                    context.correlationId(), Map.of("requestId", request.requestId())));
        }
    }

    public IntegrationEnvelope<PayloadRetentionView> payloadRetention(String runId,
                                                                      IntegrationRequestContext context) {
        VisualGraphRunRecord run = findRun(runId, context);
        VisualRunPayloadRepository payloads = requirePayloadRepository(context);
        VisualRunPayloadStatus status = payloads.status(run.runId()).orElseThrow(() -> payloadUnavailable(
                context, "NOT_GOVERNED", run.payloadRetention().classification(), run.payloadRetention().expiresAt()));
        return IntegrationEnvelope.of("PAYLOAD_RETENTION_VIEW", PayloadRetentionView.SCHEMA_VERSION,
                new PayloadRetentionView("", status, payloads.events(run.runId())));
    }

    public IntegrationEnvelope<PayloadRetentionView> placePayloadHold(String runId,
                                                                      PayloadLifecycleCommand command,
                                                                      IntegrationRequestContext context) {
        requirePurpose(context, "LEGAL_HOLD");
        VisualGraphRunRecord run = findRun(runId, context);
        PayloadLifecycleCommand safe = requirePayloadCommand(command, true, context);
        try {
            VisualRunPayloadRepository payloads = requirePayloadRepository(context);
            VisualRunPayloadStatus status = payloads.placeHold(run.runId(), safe.requestId(), safe.holdId(),
                    context.actorId(), safe.reason(), Instant.now());
            return IntegrationEnvelope.of("PAYLOAD_RETENTION_VIEW", PayloadRetentionView.SCHEMA_VERSION,
                    new PayloadRetentionView("", status, payloads.events(run.runId())));
        } catch (VisualPayloadGovernanceException failure) {
            throw mapPayloadFailure(failure, context);
        }
    }

    public IntegrationEnvelope<PayloadRetentionView> releasePayloadHold(String runId,
                                                                        String holdId,
                                                                        PayloadLifecycleCommand command,
                                                                        IntegrationRequestContext context) {
        requirePurpose(context, "LEGAL_HOLD");
        VisualGraphRunRecord run = findRun(runId, context);
        PayloadLifecycleCommand safe = requirePayloadCommand(command, false, context);
        try {
            VisualRunPayloadRepository payloads = requirePayloadRepository(context);
            VisualRunPayloadStatus status = payloads.releaseHold(run.runId(), safe.requestId(), holdId,
                    context.actorId(), safe.reason(), Instant.now());
            return IntegrationEnvelope.of("PAYLOAD_RETENTION_VIEW", PayloadRetentionView.SCHEMA_VERSION,
                    new PayloadRetentionView("", status, payloads.events(run.runId())));
        } catch (VisualPayloadGovernanceException failure) {
            throw mapPayloadFailure(failure, context);
        }
    }

    public IntegrationEnvelope<PayloadRetentionView> purgePayload(String runId,
                                                                  PayloadLifecycleCommand command,
                                                                  IntegrationRequestContext context) {
        requirePurpose(context, "PAYLOAD_RETENTION_ADMIN");
        VisualGraphRunRecord run = findRun(runId, context);
        PayloadLifecycleCommand safe = requirePayloadCommand(command, false, context);
        try {
            VisualRunPayloadRepository payloads = requirePayloadRepository(context);
            VisualRunPayloadStatus status = payloads.purge(run.runId(), safe.requestId(), context.actorId(),
                    safe.reason(), Instant.now());
            return IntegrationEnvelope.of("PAYLOAD_RETENTION_VIEW", PayloadRetentionView.SCHEMA_VERSION,
                    new PayloadRetentionView("", status, payloads.events(run.runId())));
        } catch (VisualPayloadGovernanceException failure) {
            throw mapPayloadFailure(failure, context);
        }
    }

    public IntegrationEnvelope<PayloadRetentionSweepResult> purgeExpiredPayloads(
            IntegrationRequestContext context) {
        context.requireComplete();
        requirePurpose(context, "PAYLOAD_RETENTION_ADMIN");
        Instant observedAt = Instant.now();
        int purged = requirePayloadRepository(context).purgeExpired(observedAt, 500);
        return IntegrationEnvelope.of("PAYLOAD_RETENTION_SWEEP_RESULT",
                PayloadRetentionSweepResult.SCHEMA_VERSION,
                new PayloadRetentionSweepResult("", observedAt, purged));
    }

    public IntegrationEnvelope<VisualEvidenceSigner.VerificationKey> evidenceKey(String keyId) {
        VisualEvidenceSigner signer = runRepository == null
                ? VisualEvidenceSigner.unavailable()
                : runRepository.evidenceSigner();
        VisualEvidenceSigner.KeyResolution resolution = signer.resolveKey(keyId);
        if (resolution.status() == VisualEvidenceSigner.KeyResolutionStatus.PROVIDER_UNAVAILABLE) {
            throw new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                    "RG.INTEGRATION.EVIDENCE_KEY_PROVIDER_UNAVAILABLE",
                    "Evidence verification key provider is unavailable.", "",
                    Map.of("keyId", keyId == null ? "" : keyId, "reason", resolution.reason())
            ));
        }
        if (resolution.status() != VisualEvidenceSigner.KeyResolutionStatus.AVAILABLE) {
            throw new IntegrationProblemException(IntegrationProblem.notFound(
                    "RG.INTEGRATION.EVIDENCE_KEY_NOT_FOUND",
                    "Evidence verification key was not found.", "", Map.of("keyId", keyId == null ? "" : keyId)
            ));
        }
        VisualEvidenceSigner.VerificationKey key = resolution.key();
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
                return IntegrationEnvelope.of("GOVERNANCE_GATE_RESULT", existing.schemaVersion(),
                        existing);
            }
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.INTEGRATION.GATE_RESULT_ID_CONFLICT",
                    "Gate result id already identifies different immutable content.", context.correlationId(),
                    Map.of("gateResultId", result.gateResultId())
            ));
        }
        GraphDraftDependencySnapshotService.Snapshot gateSnapshot =
                GovernanceGateResult.SCHEMA_VERSION.equals(result.schemaVersion())
                        ? dependencySnapshots.capture(targetDraft) : null;
        validateGateDecisionBasis(result, targetDraft, actualFingerprint, gateSnapshot, context);
        if (gateSnapshot != null) {
            verifySnapshotStable(targetDraft, targetDraft.revision(), gateSnapshot, context);
        }
        GovernanceGateResult stored;
        try {
            stored = gateResultRepository.create(result);
        } catch (IllegalArgumentException conflict) {
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.INTEGRATION.GATE_RESULT_ID_CONFLICT",
                    "Gate result id already identifies different immutable content.", context.correlationId(),
                    Map.of("gateResultId", result.gateResultId())));
        }
        return IntegrationEnvelope.of("GOVERNANCE_GATE_RESULT", stored.schemaVersion(), stored);
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
            } else if (!latest.decisionBasis().dependencySnapshotFingerprint().isBlank()
                    && !latest.decisionBasis().dependencySnapshotFingerprint()
                    .equals(dependencySnapshots.capture(draft).fingerprint())) {
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
            if (!Set.of(GovernanceGateResult.SCHEMA_VERSION_V1, GovernanceGateResult.SCHEMA_VERSION)
                    .contains(result.schemaVersion())) {
                invalid.put("schemaVersion", GovernanceGateResult.SCHEMA_VERSION_V1 + "|"
                        + GovernanceGateResult.SCHEMA_VERSION);
            }
            if (result.gateResultId().isBlank()) invalid.put("gateResultId", "required");
            if (!"GRAPH_DRAFT".equals(result.target().kind())) invalid.put("target.kind", "GRAPH_DRAFT");
            if (result.target().draftId().isBlank()) invalid.put("target.draftId", "required");
            if (result.target().revision() <= 0) invalid.put("target.revision", "positive");
            if (result.target().draftFingerprint().isBlank()) invalid.put("target.draftFingerprint", "required");
            if (!Set.of("PASSED", "BLOCKED", "WARNING", "UNKNOWN").contains(result.status())) {
                invalid.put("status", "PASSED|BLOCKED|WARNING|UNKNOWN");
            }
            if (GovernanceGateResult.SCHEMA_VERSION.equals(result.schemaVersion())) {
                if (result.target().tenantId().isBlank()) invalid.put("target.tenantId", "required");
                if (result.target().namespace().isBlank()) invalid.put("target.namespace", "required");
                if (result.target().environment().isBlank()) invalid.put("target.environment", "required");
            }
            if (!result.fingerprintVerified()) invalid.put("resultFingerprint", "does not match content");
            if (GovernanceGateResult.SCHEMA_VERSION_V1.equals(result.schemaVersion())
                    && "PASSED".equals(result.status())) {
                invalid.put("decisionBasis", "gateResult.v2 is required for PASSED decisions");
            }
        }
        if (!invalid.isEmpty()) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.INTEGRATION.GATE_RESULT_INVALID", "Governance gate result is invalid.",
                    context.correlationId(), invalid));
        }
    }

    private void validateGateDecisionBasis(GovernanceGateResult result,
                                           GraphDraft draft,
                                           String draftFingerprint,
                                           GraphDraftDependencySnapshotService.Snapshot snapshot,
                                           IntegrationRequestContext context) {
        if (GovernanceGateResult.SCHEMA_VERSION_V1.equals(result.schemaVersion())) return;
        GovernanceGateResult.DecisionBasis basis = result.decisionBasis();
        boolean passed = "PASSED".equals(result.status());
        if (!draft.tenantId().equals(result.target().tenantId())
                || !draft.namespace().equals(result.target().namespace())
                || !draft.environment().equals(result.target().environment())) {
            throw gateBasisConflict(context, "TARGET_SCOPE_MISMATCH");
        }
        if (!basis.dependencySnapshotFingerprint().isBlank()
                && !basis.dependencySnapshotFingerprint().equals(snapshot.fingerprint())) {
            throw gateBasisConflict(context, "DEPENDENCY_SNAPSHOT_STALE");
        }
        Set<String> currentSuites = currentSuiteKeys(snapshot);
        Set<String> suppliedSuites = basis.contractSuites().stream()
                .map(GovernanceGateResult.SuiteRef::key).collect(java.util.stream.Collectors.toSet());
        if ((!suppliedSuites.isEmpty() && !currentSuites.containsAll(suppliedSuites))
                || passed && basis.policy().requiredChecks().contains("CONTRACT_COVERAGE")
                && !currentSuites.equals(suppliedSuites)) {
            throw gateBasisConflict(context, "CONTRACT_SUITE_STALE");
        }
        CorrectnessWorkbookBundle workbook = null;
        if (!basis.workbook().sourceBundleFingerprint().isBlank() || passed) {
            if (workbookProjection == null) {
                throw new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                        "RG.INTEGRATION.WORKBOOK_PROJECTION_UNAVAILABLE",
                        "Correctness workbook projection is unavailable.", context.correlationId(), Map.of()));
            }
            try {
                workbook = workbookProjection.project(draft, draftFingerprint, snapshot);
            } catch (CorrectnessWorkbookProjectionService.ProjectionException staleProjection) {
                throw gateBasisConflict(context, staleProjection.code());
            }
            if (!basis.workbook().sourceBundleFingerprint().isBlank()
                    && !basis.workbook().sourceBundleFingerprint().equals(workbook.manifest().bundleFingerprint())) {
                throw gateBasisConflict(context, "WORKBOOK_SOURCE_STALE");
            }
        }
        for (GovernanceGateResult.EvidenceRef ref : basis.evidence()) {
            VisualGraphRunRecord run = findRun(ref.runId(), context);
            boolean sameDraft = draft.draftId().equals(run.draftId())
                    && draft.revision() == run.draftRevision()
                    && draftFingerprint.equals(run.draftFingerprint());
            boolean verified = run.evidenceMaterialFingerprint().equals(ref.evidenceFingerprint())
                    && runRepository.evidenceSigner()
                    .verify(run.evidenceSeal(), run.evidenceMaterialFingerprint()).valid();
            if (!sameDraft || !verified) throw gateBasisConflict(context, "EVIDENCE_REF_INVALID");
        }
        if (!passed) return;
        Map<String, Object> incomplete = new LinkedHashMap<>();
        if (!basis.workbook().complete()) incomplete.put("workbook", "complete ref required");
        if (basis.dependencySnapshotFingerprint().isBlank()) {
            incomplete.put("dependencySnapshotFingerprint", "required");
        }
        if (!basis.policy().complete()) incomplete.put("policy", "id, version and requiredChecks required");
        List<String> failedChecks = basis.failedRequiredChecks();
        if (!failedChecks.isEmpty()) incomplete.put("failedRequiredCheckCount", failedChecks.size());
        if (basis.checks().stream().anyMatch(check -> Set.of("BLOCKED", "FAILED").contains(check.status()))) {
            incomplete.put("checks", "blocking result present");
        }
        if (basis.policy().requiredChecks().contains("EVIDENCE") && basis.evidence().isEmpty()) {
            incomplete.put("evidence", "at least one verified run required");
        }
        if (workbook == null || !workbook.fingerprintVerified()) {
            incomplete.put("workbookSource", "unverified");
        }
        if (!incomplete.isEmpty()) {
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.INTEGRATION.GATE_BASIS_INCOMPLETE",
                    "A PASSED gate result must carry a complete, verified decision basis.",
                    context.correlationId(), incomplete));
        }
    }

    private static Set<String> currentSuiteKeys(GraphDraftDependencySnapshotService.Snapshot snapshot) {
        return snapshot.assets().values().stream()
                .flatMap(asset -> asset.contractSuites().stream())
                .map(ref -> ref.suiteId() + "@" + ref.revision() + "#" + ref.fingerprint())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static IntegrationProblemException gateBasisConflict(IntegrationRequestContext context,
                                                                  String reason) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                "RG.INTEGRATION.GATE_BASIS_STALE",
                "Governance gate decision basis no longer matches Resource Gateway facts.",
                context.correlationId(), Map.of("reason", reason)));
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

    private VisualGraphRunRecord replayByRequest(String requestId, IntegrationRequestContext context) {
        if (runRepository == null || requestId == null || requestId.isBlank()) {
            return null;
        }
        return runRepository.all().stream()
                .filter(record -> requestId.equals(record.replay().requestId()))
                .filter(record -> context.tenantId().equals(record.tenantId())
                        && context.environmentId().equals(record.environment()))
                .findFirst()
                .orElse(null);
    }

    private IntegrationEnvelope<ReplayExecutionResult> replayEnvelope(VisualGraphRunRecord replayRecord) {
        RunEvidenceBundle evidence = RunEvidenceBundle.from(replayRecord, runRepository.evidenceSigner(),
                payloadStatus(replayRecord));
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

    private GovernedPayload governedPayload(VisualGraphRunRecord record, IntegrationRequestContext context) {
        VisualRunPayloadRepository payloads = runRepository == null ? null : runRepository.payloadRepository();
        if (payloads == null) {
            if (record.payloadRetention().disposition().equals(
                    com.leanowtech.bloge.gateway.visual.runtime.VisualPayloadRetentionDescriptor.LEGACY_INLINE)
                    && context.hasClearanceAtLeast("RESTRICTED")) {
                return new GovernedPayload(record, null);
            }
            throw payloadUnavailable(context, "NOT_GOVERNED", record.payloadRetention().classification(),
                    record.payloadRetention().expiresAt());
        }
        try {
            VisualRunPayloadRepository.Access access = payloads.access(record.runId(), Instant.now());
            VisualRunPayloadStatus status = access.status();
            if (status == null || !record.tenantId().equals(status.tenantId())
                    || !record.environment().equals(status.environment())) {
                throw new VisualPayloadGovernanceException(VisualPayloadGovernanceException.Reason.CORRUPT,
                        "Payload scope does not match immutable run evidence");
            }
            requirePayloadAuthorization(status, context);
            if (!access.readable()) {
                throw payloadUnavailable(context, status.state(), status.descriptor().classification(),
                        status.descriptor().expiresAt());
            }
            return new GovernedPayload(record.withPayload(access.payload()), status);
        } catch (IntegrationProblemException failure) {
            throw failure;
        } catch (VisualPayloadGovernanceException failure) {
            throw mapPayloadFailure(failure, context);
        }
    }

    private VisualRunPayloadStatus payloadStatus(VisualGraphRunRecord record) {
        VisualRunPayloadRepository payloads = runRepository == null ? null : runRepository.payloadRepository();
        if (payloads == null) {
            return null;
        }
        try {
            return payloads.access(record.runId(), Instant.now()).status();
        } catch (VisualPayloadGovernanceException failure) {
            return payloads.status(record.runId()).orElse(null);
        }
    }

    private static void requirePayloadAuthorization(VisualRunPayloadStatus status,
                                                    IntegrationRequestContext context) {
        if (!context.hasClearanceAtLeast(status.descriptor().requiredClearance())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.INTEGRATION.PAYLOAD_CLEARANCE_REQUIRED",
                    "The verified workload identity does not have sufficient payload clearance.",
                    context.correlationId(), Map.of(
                    "classification", status.descriptor().classification(),
                    "requiredClearance", status.descriptor().requiredClearance())));
        }
        Set<String> missingGroups = new HashSet<>(status.descriptor().requiredGroups());
        missingGroups.removeAll(context.groups());
        if (!missingGroups.isEmpty()) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.INTEGRATION.PAYLOAD_GROUP_REQUIRED",
                    "The verified workload identity is outside the payload policy group boundary.",
                    context.correlationId(), Map.of("missingGroupCount", missingGroups.size())));
        }
    }

    private VisualRunPayloadRepository requirePayloadRepository(IntegrationRequestContext context) {
        VisualRunPayloadRepository payloads = runRepository == null ? null : runRepository.payloadRepository();
        if (payloads == null) {
            throw new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                    "RG.INTEGRATION.PAYLOAD_GOVERNANCE_UNAVAILABLE",
                    "Governed payload storage is unavailable.", context.correlationId(), Map.of()));
        }
        return payloads;
    }

    private static PayloadLifecycleCommand requirePayloadCommand(PayloadLifecycleCommand command,
                                                                 boolean requireHoldId,
                                                                 IntegrationRequestContext context) {
        PayloadLifecycleCommand safe = command == null
                ? new PayloadLifecycleCommand("", "", "", "") : command;
        Map<String, Object> invalid = new LinkedHashMap<>();
        if (safe.requestId().isBlank()) invalid.put("requestId", "required");
        if (requireHoldId && safe.holdId().isBlank()) invalid.put("holdId", "required");
        if (safe.reason().isBlank()) invalid.put("reason", "required");
        if (!invalid.isEmpty()) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.INTEGRATION.PAYLOAD_LIFECYCLE_COMMAND_INVALID",
                    "Payload lifecycle command is invalid.", context.correlationId(), invalid));
        }
        return safe;
    }

    private static IntegrationProblemException payloadUnavailable(IntegrationRequestContext context,
                                                                  String state,
                                                                  String classification,
                                                                  Instant expiresAt) {
        return new IntegrationProblemException(IntegrationProblem.gone(
                "RG.INTEGRATION.PAYLOAD_NOT_AVAILABLE",
                "Governed replay payload is no longer available.", context.correlationId(), Map.of(
                "state", state == null ? "UNKNOWN" : state,
                "classification", classification == null ? "UNKNOWN" : classification,
                "expiresAt", expiresAt == null ? Instant.EPOCH : expiresAt)));
    }

    private static IntegrationProblemException mapPayloadFailure(VisualPayloadGovernanceException failure,
                                                                 IntegrationRequestContext context) {
        return switch (failure.reason()) {
            case NOT_FOUND -> payloadUnavailable(context, "NOT_FOUND", "UNKNOWN", Instant.EPOCH);
            case HOLD_CONFLICT, LEGAL_HOLD_ACTIVE, ALREADY_EXISTS -> new IntegrationProblemException(
                    IntegrationProblem.conflict("RG.INTEGRATION.PAYLOAD_LIFECYCLE_CONFLICT",
                            failure.getMessage(), context.correlationId(), Map.of("reason", failure.reason().name())));
            case SIGNING_UNAVAILABLE, CORRUPT -> new IntegrationProblemException(
                    IntegrationProblem.serviceUnavailable("RG.INTEGRATION.PAYLOAD_GOVERNANCE_UNAVAILABLE",
                            "Governed payload lifecycle verification is unavailable.",
                            context.correlationId(), Map.of("reason", failure.reason().name())));
        };
    }

    private record GovernedPayload(VisualGraphRunRecord record, VisualRunPayloadStatus status) {
    }

    private void verifySnapshotStable(GraphDraft draft,
                                      long requestedRevision,
                                      GraphDraftDependencySnapshotService.Snapshot before,
                                      IntegrationRequestContext context) {
        GraphDraftDependencySnapshotService.Snapshot after = dependencySnapshots.capture(draft);
        GraphDraft persisted = (requestedRevision > 0
                ? draftRepository.findRevision(draft.draftId(), draft.revision())
                : draftRepository.find(draft.draftId())).orElse(null);
        boolean draftStable = persisted != null
                && persisted.revision() == draft.revision()
                && draftFingerprint(persisted).equals(draftFingerprint(draft));
        if (draftStable && before.fingerprint().equals(after.fingerprint())) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("draftId", draft.draftId());
        details.put("observedRevision", draft.revision());
        details.put("requestedRevision", requestedRevision);
        details.put("beforeDependencyFingerprint", before.fingerprint());
        details.put("afterDependencyFingerprint", after.fingerprint());
        details.put("draftStable", draftStable);
        throw new IntegrationProblemException(IntegrationProblem.retryableConflict(
                "RG.INTEGRATION.DRAFT_SNAPSHOT_CHANGED",
                "Draft dependencies changed while the integration snapshot was being assembled; retry the export.",
                context.correlationId(), details));
    }

    static String draftFingerprint(GraphDraft draft) {
        return VisualBundleFingerprint.fromMaterial(Map.of("draft", draft.withNodeFixtures(Map.of())));
    }
}
