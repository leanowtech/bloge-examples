package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.stereotype.Service;

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

    public ToolStudioIntegrationService(GraphDraftRepository draftRepository,
                                        GraphDraftValidator validator,
                                        VisualOperatorCatalog catalog,
                                        VisualGraphRunRepository runRepository) {
        this.draftRepository = draftRepository;
        this.validator = validator;
        this.catalog = catalog;
        this.runRepository = runRepository;
    }

    public IntegrationEnvelope<IntegrationCapabilities> capabilities() {
        return IntegrationEnvelope.of("CAPABILITIES", IntegrationCapabilities.SCHEMA_VERSION,
                IntegrationCapabilities.current());
    }

    public IntegrationEnvelope<GraphDraftIntegrationBundle> exportDraft(String draftId,
                                                                        long revision,
                                                                        IntegrationRequestContext context) {
        context.requireComplete();
        GraphDraft draft = findDraft(draftId, revision, context);
        context.requireDraftScope(draft);
        GraphDraftDependencyReport dependencyReport = GraphDraftDependencyReport.from(draft, catalog);
        VisualValidationResult validation = validator.validate(draft);
        String draftFingerprint = VisualBundleFingerprint.fromMaterial(Map.of(
                "draft", draft.withNodeFixtures(Map.of())
        ));
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
                RunEvidenceBundle.from(record));
    }

    public IntegrationEnvelope<PayloadReplayBundle> replay(String runId,
                                                           IntegrationRequestContext context) {
        VisualGraphRunRecord record = findRun(runId, context);
        return IntegrationEnvelope.of("PAYLOAD_REPLAY_BUNDLE", PayloadReplayBundle.SCHEMA_VERSION,
                PayloadReplayBundle.from(record));
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
}
