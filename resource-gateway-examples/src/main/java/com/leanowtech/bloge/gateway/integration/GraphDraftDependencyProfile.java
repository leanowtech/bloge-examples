package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.List;
import java.util.Map;

/**
 * Deterministic cross-system dependency metadata for one draft snapshot.
 */
public record GraphDraftDependencyProfile(
        String schemaVersion,
        List<OperatorDependency> operatorDependencies,
        GraphContract graphContract,
        GraphDraftDependencyReport sourceDependencyReport
) {
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.graphDraftDependencyProfile.v1";

    public GraphDraftDependencyProfile {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        operatorDependencies = operatorDependencies == null ? List.of() : List.copyOf(operatorDependencies);
        graphContract = graphContract == null ? new GraphContract("", "") : graphContract;
        sourceDependencyReport = sourceDependencyReport == null
                ? GraphDraftDependencyReport.empty() : sourceDependencyReport;
    }

    public static GraphDraftDependencyProfile from(GraphDraft draft,
                                                   GraphDraftDependencyReport report,
                                                   VisualOperatorCatalog catalog) {
        List<OperatorDependency> dependencies = report.nodes().stream()
                .map(node -> operatorDependency(draft, node, catalog))
                .toList();
        return new GraphDraftDependencyProfile("", dependencies, new GraphContract(
                VisualBundleFingerprint.fromMaterial(Map.of("schema", draft.inputSchema())),
                VisualBundleFingerprint.fromMaterial(Map.of("schema", draft.outputSchema()))
        ), report);
    }

    private static OperatorDependency operatorDependency(GraphDraft draft,
                                                         GraphDraftDependencyReport.NodeDependency node,
                                                         VisualOperatorCatalog catalog) {
        OperatorDefinition operator = catalog == null ? null : catalog.find(node.operatorRef()).orElse(null);
        if (operator == null) {
            operator = draft.operatorSnapshots().get(node.nodeId());
        }
        String schemaFingerprint = operator == null
                ? ""
                : VisualBundleFingerprint.fromMaterial(Map.of(
                        "ports", operator.ports(),
                        "configSchema", operator.configSchema()
                ));
        String fingerprint = !node.currentFingerprint().isBlank()
                ? node.currentFingerprint() : node.savedFingerprint();
        return new OperatorDependency(
                node.nodeId(), node.operatorRef(), node.operatorLibraryId(), fingerprint, schemaFingerprint,
                List.of(), List.of(), new RuntimeReadiness(
                !"catalog-missing".equals(node.runtimeReadinessState()),
                node.executable(), node.executable(), "", "", "", node.runtimeReadinessState()
        ));
    }

    public record OperatorDependency(
            String nodeId,
            String operatorRef,
            String operatorLibraryId,
            String operatorFingerprint,
            String schemaFingerprint,
            List<String> runtimeBindingRefs,
            List<String> contractSuiteRefs,
            RuntimeReadiness readiness
    ) {
        public OperatorDependency {
            nodeId = nodeId == null ? "" : nodeId;
            operatorRef = operatorRef == null ? "" : operatorRef;
            operatorLibraryId = operatorLibraryId == null ? "" : operatorLibraryId;
            operatorFingerprint = operatorFingerprint == null ? "" : operatorFingerprint;
            schemaFingerprint = schemaFingerprint == null ? "" : schemaFingerprint;
            runtimeBindingRefs = runtimeBindingRefs == null ? List.of() : List.copyOf(runtimeBindingRefs);
            contractSuiteRefs = contractSuiteRefs == null ? List.of() : List.copyOf(contractSuiteRefs);
            readiness = readiness == null ? RuntimeReadiness.unknown() : readiness;
        }
    }

    public record RuntimeReadiness(
            boolean designReady,
            boolean runtimeReady,
            boolean executable,
            String risk,
            String owner,
            String sla,
            String state
    ) {
        public RuntimeReadiness {
            risk = risk == null ? "" : risk;
            owner = owner == null ? "" : owner;
            sla = sla == null ? "" : sla;
            state = state == null ? "" : state;
        }

        public static RuntimeReadiness unknown() {
            return new RuntimeReadiness(false, false, false, "", "", "", "unknown");
        }
    }

    public record GraphContract(String inputSchemaFingerprint, String outputSchemaFingerprint) {
        public GraphContract {
            inputSchemaFingerprint = inputSchemaFingerprint == null ? "" : inputSchemaFingerprint;
            outputSchemaFingerprint = outputSchemaFingerprint == null ? "" : outputSchemaFingerprint;
        }
    }
}
