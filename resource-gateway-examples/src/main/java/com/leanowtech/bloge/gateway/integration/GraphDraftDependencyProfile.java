package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Deterministic cross-system dependency metadata for one draft snapshot.
 */
public record GraphDraftDependencyProfile(
        String schemaVersion,
        List<OperatorDependency> operatorDependencies,
        GraphContract graphContract,
        SnapshotManifest snapshot,
        GraphDraftDependencyReport sourceDependencyReport
) {
    public static final String SCHEMA_VERSION_V1 = "toolStudio.resourceGateway.graphDraftDependencyProfile.v1";
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.graphDraftDependencyProfile.v2";

    public GraphDraftDependencyProfile {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        operatorDependencies = operatorDependencies == null ? List.of() : List.copyOf(operatorDependencies);
        graphContract = graphContract == null ? new GraphContract("", "") : graphContract;
        snapshot = snapshot == null ? SnapshotManifest.unavailable() : snapshot;
        sourceDependencyReport = sourceDependencyReport == null
                ? GraphDraftDependencyReport.empty() : sourceDependencyReport;
    }

    public GraphDraftDependencyProfile(String schemaVersion,
                                       List<OperatorDependency> operatorDependencies,
                                       GraphContract graphContract,
                                       GraphDraftDependencyReport sourceDependencyReport) {
        this(schemaVersion, operatorDependencies, graphContract, SnapshotManifest.unavailable(),
                sourceDependencyReport);
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
        ), SnapshotManifest.unavailable(), report);
    }

    public static GraphDraftDependencyProfile from(GraphDraft draft,
                                                   GraphDraftDependencyReport report,
                                                   GraphDraftDependencySnapshotService.Snapshot snapshot) {
        List<OperatorDependency> dependencies = report.nodes().stream()
                .map(node -> operatorDependency(draft, node, snapshot.catalog(),
                        snapshot.assets().get(node.operatorRef())))
                .toList();
        return new GraphDraftDependencyProfile("", dependencies, new GraphContract(
                VisualBundleFingerprint.fromMaterial(Map.of("schema", draft.inputSchema())),
                VisualBundleFingerprint.fromMaterial(Map.of("schema", draft.outputSchema()))
        ), SnapshotManifest.from(snapshot), report);
    }

    private static OperatorDependency operatorDependency(GraphDraft draft,
                                                         GraphDraftDependencyReport.NodeDependency node,
                                                         VisualOperatorCatalog catalog) {
        return operatorDependency(draft, node, catalog, null);
    }

    private static OperatorDependency operatorDependency(GraphDraft draft,
                                                         GraphDraftDependencyReport.NodeDependency node,
                                                         VisualOperatorCatalog catalog,
                                                         OperatorAssetSnapshot assets) {
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
        OperatorAssetSnapshot resolved = assets == null ? OperatorAssetSnapshot.empty() : assets;
        RuntimeReadiness readiness = resolved.readiness();
        if ("catalog-missing".equals(node.runtimeReadinessState())
                || "scope-mismatch".equals(node.fingerprintState())) {
            readiness = new RuntimeReadiness(false, false, false, readiness.risk(), readiness.owner(),
                    readiness.sla(), "catalog-missing".equals(node.runtimeReadinessState())
                    ? "CATALOG_MISSING" : "SCOPE_MISMATCH");
        }
        List<String> runtimeBindingRefs = resolved.runtimeBindings().stream()
                .map(binding -> binding.bindingId() + "@" + binding.revision()).toList();
        List<String> contractSuiteRefs = resolved.contractSuites().stream()
                .map(suite -> suite.suiteId() + "@" + suite.revision()).toList();
        return new OperatorDependency(
                node.nodeId(), node.operatorRef(), node.operatorLibraryId(), fingerprint, schemaFingerprint,
                runtimeBindingRefs, contractSuiteRefs, readiness, resolved.operatorLibrary(),
                resolved.runtimeBindings(), resolved.contractSuites());
    }

    public record OperatorDependency(
            String nodeId,
            String operatorRef,
            String operatorLibraryId,
            String operatorFingerprint,
            String schemaFingerprint,
            List<String> runtimeBindingRefs,
            List<String> contractSuiteRefs,
            RuntimeReadiness readiness,
            OperatorLibraryRef operatorLibrary,
            List<RuntimeBindingRef> runtimeBindings,
            List<ContractSuiteRef> contractSuites
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
            operatorLibrary = operatorLibrary == null ? OperatorLibraryRef.missing(operatorLibraryId) : operatorLibrary;
            runtimeBindings = runtimeBindings == null ? List.of() : List.copyOf(runtimeBindings);
            contractSuites = contractSuites == null ? List.of() : List.copyOf(contractSuites);
        }

        public OperatorDependency(String nodeId,
                                  String operatorRef,
                                  String operatorLibraryId,
                                  String operatorFingerprint,
                                  String schemaFingerprint,
                                  List<String> runtimeBindingRefs,
                                  List<String> contractSuiteRefs,
                                  RuntimeReadiness readiness) {
            this(nodeId, operatorRef, operatorLibraryId, operatorFingerprint, schemaFingerprint,
                    runtimeBindingRefs, contractSuiteRefs, readiness,
                    OperatorLibraryRef.missing(operatorLibraryId), List.of(), List.of());
        }
    }

    public record SnapshotManifest(String schemaVersion,
                                   String fingerprint,
                                   Instant capturedAt,
                                   String consistencyStatus,
                                   int operatorCount,
                                   int operatorLibraryCount,
                                   int runtimeBindingCount,
                                   int contractSuiteCount) {
        public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.graphDraftDependencySnapshot.v1";

        public SnapshotManifest {
            schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
            fingerprint = fingerprint == null ? "" : fingerprint;
            capturedAt = capturedAt == null ? Instant.EPOCH : capturedAt;
            consistencyStatus = consistencyStatus == null ? "UNAVAILABLE" : consistencyStatus;
            operatorCount = Math.max(0, operatorCount);
            operatorLibraryCount = Math.max(0, operatorLibraryCount);
            runtimeBindingCount = Math.max(0, runtimeBindingCount);
            contractSuiteCount = Math.max(0, contractSuiteCount);
        }

        static SnapshotManifest from(GraphDraftDependencySnapshotService.Snapshot snapshot) {
            int libraryCount = (int) snapshot.assets().values().stream()
                    .map(OperatorAssetSnapshot::operatorLibrary)
                    .filter(OperatorLibraryRef::present)
                    .map(OperatorLibraryRef::libraryId).distinct().count();
            int bindingCount = snapshot.assets().values().stream()
                    .mapToInt(asset -> asset.runtimeBindings().size()).sum();
            int suiteCount = snapshot.assets().values().stream()
                    .mapToInt(asset -> asset.contractSuites().size()).sum();
            return new SnapshotManifest("", snapshot.fingerprint(), snapshot.capturedAt(), "STABLE",
                    snapshot.operators().size(), libraryCount, bindingCount, suiteCount);
        }

        static SnapshotManifest unavailable() {
            return new SnapshotManifest("", "", Instant.EPOCH, "UNAVAILABLE", 0, 0, 0, 0);
        }
    }

    public record OperatorAssetSnapshot(OperatorLibraryRef operatorLibrary,
                                        List<RuntimeBindingRef> runtimeBindings,
                                        List<ContractSuiteRef> contractSuites,
                                        RuntimeReadiness readiness) {
        public OperatorAssetSnapshot {
            operatorLibrary = operatorLibrary == null ? OperatorLibraryRef.missing("") : operatorLibrary;
            runtimeBindings = runtimeBindings == null ? List.of() : List.copyOf(runtimeBindings);
            contractSuites = contractSuites == null ? List.of() : List.copyOf(contractSuites);
            readiness = readiness == null ? RuntimeReadiness.unknown() : readiness;
        }

        static OperatorAssetSnapshot empty() {
            return new OperatorAssetSnapshot(null, null, null, null);
        }
    }

    public record OperatorLibraryRef(String libraryId,
                                     long revision,
                                     String version,
                                     String owner,
                                     String status,
                                     String fingerprint,
                                     boolean present) {
        public OperatorLibraryRef {
            libraryId = libraryId == null ? "" : libraryId;
            revision = Math.max(0, revision);
            version = version == null ? "" : version;
            owner = owner == null ? "" : owner;
            status = status == null ? "MISSING" : status;
            fingerprint = fingerprint == null ? "" : fingerprint;
        }

        static OperatorLibraryRef missing(String libraryId) {
            return new OperatorLibraryRef(libraryId, 0, "", "", "MISSING", "", false);
        }
    }

    public record RuntimeBindingRef(String bindingId,
                                    long revision,
                                    String state,
                                    String operatorFingerprint,
                                    String fingerprint,
                                    String activationId,
                                    long activationRevision,
                                    String activationState,
                                    String activationEnvironment,
                                    String activationHealth,
                                    String activationFingerprint,
                                    boolean ready) {
        public RuntimeBindingRef {
            bindingId = normalize(bindingId);
            revision = Math.max(0, revision);
            state = normalize(state);
            operatorFingerprint = normalize(operatorFingerprint);
            fingerprint = normalize(fingerprint);
            activationId = normalize(activationId);
            activationRevision = Math.max(0, activationRevision);
            activationState = normalize(activationState);
            activationEnvironment = normalize(activationEnvironment);
            activationHealth = normalize(activationHealth);
            activationFingerprint = normalize(activationFingerprint);
        }
    }

    public record ContractSuiteRef(String suiteId,
                                   long revision,
                                   String schemaVersion,
                                   int caseCount,
                                   String fingerprint) {
        public ContractSuiteRef {
            suiteId = normalize(suiteId);
            revision = Math.max(0, revision);
            schemaVersion = normalize(schemaVersion);
            caseCount = Math.max(0, caseCount);
            fingerprint = normalize(fingerprint);
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

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
