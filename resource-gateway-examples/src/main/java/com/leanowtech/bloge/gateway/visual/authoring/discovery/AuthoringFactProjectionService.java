package com.leanowtech.bloge.gateway.visual.authoring.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.catalog.AsyncApiOperatorLibraryImportRequest;
import com.leanowtech.bloge.gateway.visual.catalog.AsyncApiOperatorLibraryImportResult;
import com.leanowtech.bloge.gateway.visual.catalog.AsyncApiOperatorLibraryImporter;
import com.leanowtech.bloge.gateway.visual.catalog.BuiltInFunctionContract;
import com.leanowtech.bloge.gateway.visual.catalog.CapabilityCatalogVisualAdapter;
import com.leanowtech.bloge.gateway.visual.catalog.CapabilityCatalogVisualAdapterResult;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.ResourceVirtualOperatorProjector;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.importer.DslImportPreviewRequest;
import com.leanowtech.bloge.gateway.visual.importer.DslImportService;
import com.leanowtech.bloge.gateway.visual.importer.DslVisualProjection;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.resource.OpenApiResourceDesignContractImportRequest;
import com.leanowtech.bloge.gateway.visual.resource.OpenApiResourceDesignContractImportResult;
import com.leanowtech.bloge.gateway.visual.resource.OpenApiResourceDesignContractImporter;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Unified source adapter for capability catalogs, API contracts, BLOGE DSL, and runtime inventory.
 */
@Service
public final class AuthoringFactProjectionService {

    public static final int MAXIMUM_SOURCE_BYTES = 10 * 1024 * 1024;

    private final CapabilityCatalogVisualAdapter capabilityCatalogAdapter;
    private final AsyncApiOperatorLibraryImporter asyncApiImporter;
    private final OpenApiResourceDesignContractImporter openApiImporter;
    private final ResourceVirtualOperatorProjector resourceProjector;
    private final DslImportService dslImportService;
    private final RuntimeParityService parityService;
    private final AuthoringDocumentProjector documentProjector;
    private final ObjectMapper objectMapper;

    public AuthoringFactProjectionService(
            CapabilityCatalogVisualAdapter capabilityCatalogAdapter,
            AsyncApiOperatorLibraryImporter asyncApiImporter,
            OpenApiResourceDesignContractImporter openApiImporter,
            ResourceVirtualOperatorProjector resourceProjector,
            DslImportService dslImportService,
            RuntimeParityService parityService,
            AuthoringDocumentProjector documentProjector,
            ObjectMapper objectMapper) {
        this.capabilityCatalogAdapter = capabilityCatalogAdapter;
        this.asyncApiImporter = asyncApiImporter;
        this.openApiImporter = openApiImporter;
        this.resourceProjector = resourceProjector;
        this.dslImportService = dslImportService;
        this.parityService = parityService;
        this.documentProjector = documentProjector;
        this.objectMapper = objectMapper;
    }

    public AuthoringFactProjection capabilityCatalog(
            String sourceId,
            Map<String, Object> catalog) {
        Map<String, Object> source = catalog == null ? Map.of() : new LinkedHashMap<>(catalog);
        enforceSourceLimit(source);
        CapabilityCatalogVisualAdapterResult result = capabilityCatalogAdapter.project(source);
        List<AuthoringFactProjection.ReviewItem> reviews = new ArrayList<>();
        reviews.add(new AuthoringFactProjection.ReviewItem(
                "RG.AUTHORING.DISCOVERY_CAPABILITY_COVERAGE",
                "INFO",
                "SOURCE",
                normalized(sourceId, "capability-catalog"),
                "Projected %d/%d operators and %d/%d functions."
                        .formatted(
                                result.projectionReview().projectedOperatorCount(),
                                result.projectionReview().sourceOperatorCount(),
                                result.projectionReview().projectedFunctionCount(),
                                result.projectionReview().sourceFunctionCount()),
                "Review opaque schemas and source diagnostics before import."
        ));
        return fromLibrary(
                "CAPABILITY_CATALOG",
                normalized(sourceId, result.projectionReview().catalogId()),
                source,
                result.library(),
                result.validation().diagnostics(),
                reviews);
    }

    public AuthoringFactProjection asyncApi(
            AsyncApiOperatorLibraryImportRequest request) {
        enforceSourceLimit(request);
        AsyncApiOperatorLibraryImportResult result = asyncApiImporter.project(request);
        List<AuthoringFactProjection.ReviewItem> reviews = List.of(
                new AuthoringFactProjection.ReviewItem(
                        "RG.AUTHORING.DISCOVERY_ASYNCAPI_COVERAGE",
                        result.projectionReview().omittedOperationCount() > 0 ? "WARNING" : "INFO",
                        "SOURCE",
                        request == null ? "asyncapi" : normalized(request.libraryId(), "asyncapi"),
                        "Selected %d of %d discovered AsyncAPI operations."
                                .formatted(
                                        result.projectionReview().selectedOperationCount(),
                                        result.projectionReview().availableOperationCount()),
                        result.projectionReview().omittedOperationCount() > 0
                                ? "Review omitted operations or adjust the operation selector."
                                : "Review the generated operator contracts."
                )
        );
        return fromLibrary(
                "ASYNC_API",
                request == null ? "asyncapi" : normalized(request.libraryId(), "asyncapi"),
                request,
                result.library(),
                result.validation().diagnostics(),
                reviews);
    }

    public AuthoringFactProjection openApi(
            OpenApiResourceDesignContractImportRequest request) {
        enforceSourceLimit(request);
        OpenApiResourceDesignContractImportResult result = openApiImporter.project(request);
        ResourceDesignContract contract = result.contract();
        OperatorLibrary library = null;
        if (contract != null && result.descriptorSuggestion() != null) {
            OperatorDefinition operator = resourceProjector.project(
                    result.descriptorSuggestion(),
                    Optional.of(contract));
            library = new OperatorLibrary(
                    "bloge.visualOperatorLibrary.v1",
                    "openapi-" + contract.resourceId(),
                    contract.displayName(),
                    "1.0.0",
                    "",
                    OperatorLibrary.STATUS_ACTIVE,
                    List.of(operator)
            );
        }
        List<AuthoringFactProjection.ReviewItem> reviews = contract == null
                ? List.of()
                : List.of(new AuthoringFactProjection.ReviewItem(
                "RG.AUTHORING.DISCOVERY_OPENAPI_OPERATION",
                "INFO",
                "OPERATOR",
                "resource:" + contract.resourceId(),
                "Projected one selected OpenAPI operation and its request/response schemas.",
                "Review authentication placeholders and runtime descriptor binding."
        ));
        return fromLibrary(
                "OPEN_API",
                request == null ? "openapi" : normalized(request.resourceId(), "openapi"),
                request,
                library,
                result.validation().diagnostics(),
                reviews);
    }

    public AuthoringFactProjection dsl(DslImportPreviewRequest request) {
        enforceSourceLimit(request);
        DslVisualProjection projection = dslImportService.preview(request);
        GraphDraft draft = projection.draft();
        List<AuthoringFactProjection.Fact> facts = new ArrayList<>();
        Set<String> operatorRefs = new LinkedHashSet<>();
        Set<String> functionRefs = new LinkedHashSet<>(importStringList(draft, "functionNames"));
        Map<String, List<GraphDraft.DraftNode>> nodesByOperator = draft.nodes().stream()
                .filter(java.util.Objects::nonNull)
                .filter(node -> !node.operatorRef().isBlank())
                .peek(node -> operatorRefs.add(node.operatorRef()))
                .collect(Collectors.groupingBy(
                        GraphDraft.DraftNode::operatorRef,
                        LinkedHashMap::new,
                        Collectors.toList()));
        Map<String, Set<String>> dependencies = dependenciesByOperator(draft, nodesByOperator);
        nodesByOperator.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> facts.add(new AuthoringFactProjection.Fact(
                        factId("DSL", "OPERATOR", entry.getKey(), "USAGE"),
                        "OPERATOR",
                        entry.getKey(),
                        "USAGE",
                        "OBSERVED",
                        "",
                        "/draft/nodes",
                        entry.getValue().size(),
                        dependencies.getOrDefault(entry.getKey(), Set.of()).stream().sorted().toList(),
                        Map.of(
                                "nodeIds", entry.getValue().stream()
                                        .map(GraphDraft.DraftNode::id)
                                        .sorted()
                                        .toList(),
                                "schemaAvailable", entry.getValue().stream()
                                        .allMatch(node -> draft.operatorSnapshots().containsKey(node.id()))
                        )
                )));
        functionRefs.stream().sorted().forEach(ref -> facts.add(
                new AuthoringFactProjection.Fact(
                        factId("DSL", "FUNCTION", ref, "USAGE"),
                        "FUNCTION",
                        ref,
                        "USAGE",
                        "OBSERVED",
                        "",
                        "/expressions",
                        1,
                        List.of(),
                        Map.of("signatureAvailable", false)
                )));
        facts.add(new AuthoringFactProjection.Fact(
                factId("DSL", "GRAPH", draft.graphName(), "TOPOLOGY"),
                "GRAPH",
                draft.graphName(),
                "TOPOLOGY",
                "DECLARED",
                VisualBundleFingerprint.fromCanonicalValue(
                        objectMapper, draft, MAXIMUM_SOURCE_BYTES),
                "/draft",
                1,
                draft.edges().stream()
                        .map(edge -> edge.source().nodeId() + "->" + edge.target().nodeId())
                        .distinct()
                        .sorted()
                        .toList(),
                Map.of(
                        "nodeCount", draft.nodes().size(),
                        "edgeCount", draft.edges().size(),
                        "topologyOnly", importBoolean(draft, "topologyOnly")
                )
        ));
        RuntimeParityService.Snapshot parity = parityService.evaluateReferences(operatorRefs, functionRefs);
        List<AuthoringFactProjection.ReviewItem> reviews =
                new ArrayList<>(parityReviewItems(parity.parity()));
        if (importBoolean(draft, "topologyOnly")) {
            reviews.add(new AuthoringFactProjection.ReviewItem(
                    "RG.AUTHORING.DISCOVERY_DSL_TOPOLOGY_ONLY",
                    "WARNING",
                    "GRAPH",
                    draft.graphName(),
                    "The DSL topology is complete enough to render, but one or more contracts are absent.",
                    "Open the graph in Author, then enrich reusable contracts in Library Workbench."
            ));
        }
        AuthoringFactProjection candidate = new AuthoringFactProjection(
                AuthoringFactProjection.SCHEMA_VERSION,
                "DSL",
                projection.sourceId(),
                fingerprint("DSL", projection.sourceId(), request),
                "",
                projection.diagnostics().stream().noneMatch(VisualDiagnostic::error),
                summary(facts, parity.parity()),
                facts,
                parity.parity(),
                reviews,
                projection.diagnostics(),
                null
        );
        return fingerprint(candidate);
    }

    public AuthoringFactProjection runtimeInventory() {
        RuntimeParityService.Inventory inventory = parityService.inventory();
        List<AuthoringFactProjection.Fact> facts = new ArrayList<>();
        inventory.operators().forEach(operator -> facts.add(
                operatorFact("RUNTIME_INVENTORY", operator, "RUNTIME", "DECLARED", "/operators")));
        inventory.functions().forEach(function -> facts.add(
                new AuthoringFactProjection.Fact(
                        factId(
                                "RUNTIME_INVENTORY",
                                "FUNCTION",
                                function.callableName()
                                        + "@"
                                        + function.providerId()
                                        + "/"
                                        + function.runtimeProfile(),
                                "RUNTIME"),
                        "FUNCTION",
                        function.callableName(),
                        "RUNTIME",
                        function.declaredContract() == null ? "UNKNOWN" : "DECLARED",
                        function.declaredContract() == null
                                ? ""
                                : BuiltInFunctionContract.callableFingerprint(function.declaredContract()),
                        "/functions",
                        1,
                        List.of(),
                        Map.of(
                                "runtimeName", function.runtimeName(),
                                "providerId", function.providerId(),
                                "runtimeProfile", function.runtimeProfile(),
                                "pure", function.pure(),
                                "requiredExecutionServices", function.requiredExecutionServices(),
                                "returnTypeHint", function.returnTypeHint(),
                                "runtimeFingerprint", function.runtimeFingerprint(),
                                "signatureMetadataAvailable", function.declaredContract() != null
                        )
                )));
        List<AuthoringFactProjection.RuntimeParity> parity = new ArrayList<>();
        inventory.operators().forEach(operator -> parity.add(
                new AuthoringFactProjection.RuntimeParity(
                        "OPERATOR",
                        operator.operatorRef(),
                        "process-local",
                        "BOUND",
                        true,
                        operator.fingerprint(),
                        operator.fingerprint(),
                        "",
                        "The operator contract was projected directly from the process-local registry."
                )));
        inventory.functions().forEach(function -> parity.add(
                new AuthoringFactProjection.RuntimeParity(
                        "FUNCTION",
                        function.callableName(),
                        function.runtimeProfile(),
                        function.declaredContract() == null ? "RUNTIME_DISCOVERED" : "BOUND",
                        function.declaredContract() != null,
                        function.declaredContract() == null
                                ? ""
                                : BuiltInFunctionContract.callableFingerprint(function.declaredContract()),
                        function.runtimeFingerprint(),
                        function.declaredContract() == null
                                ? "RG.AUTHORING.RUNTIME_FUNCTION_SIGNATURE_UNKNOWN"
                                : "",
                        function.declaredContract() == null
                                ? "Runtime callable discovered without authoritative signature metadata."
                                : "Runtime callable and signature metadata were supplied by the same provider."
                )));
        List<OperatorLibrary.BuiltInFunction> authoritativeFunctions =
                authoritativeRuntimeFunctions(inventory.functions());
        OperatorLibrary runtimeLibrary = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "runtime-discovery",
                "Process Runtime Inventory",
                "1.0.0",
                "runtime",
                OperatorLibrary.STATUS_ACTIVE,
                authoritativeFunctions,
                inventory.operators());
        AuthoringDocumentProjector.Result document =
                inventory.operators().isEmpty() && authoritativeFunctions.isEmpty()
                        ? new AuthoringDocumentProjector.Result(null, List.of())
                        : documentProjector.project(runtimeLibrary);
        List<AuthoringFactProjection.ReviewItem> reviews = new ArrayList<>(document.reviewItems());
        reviews.addAll(parityReviewItems(parity));
        if (document.document() == null && !inventory.functions().isEmpty()) {
            reviews.add(new AuthoringFactProjection.ReviewItem(
                    "RG.AUTHORING.RUNTIME_SIGNATURES_REQUIRED",
                    "WARNING",
                    "SOURCE",
                    "process-local",
                    "Runtime functions were discovered, but none exposes an authoritative signature contract.",
                    "Register a FrameworkFunctionInventoryProvider with declaredContract metadata."
            ));
        }
        AuthoringFactProjection candidate = new AuthoringFactProjection(
                AuthoringFactProjection.SCHEMA_VERSION,
                "RUNTIME_INVENTORY",
                "process-local",
                inventory.inventoryFingerprint(),
                "",
                true,
                summary(facts, parity),
                facts,
                parity,
                reviews,
                List.of(),
                document.document()
        );
        return fingerprint(candidate);
    }

    private static List<OperatorLibrary.BuiltInFunction> authoritativeRuntimeFunctions(
            List<FrameworkFunctionInventory.FunctionRuntime> functions) {
        Map<String, List<FrameworkFunctionInventory.FunctionRuntime>> byCallable = functions.stream()
                .filter(java.util.Objects::nonNull)
                .filter(function -> function.declaredContract() != null)
                .collect(Collectors.groupingBy(
                        FrameworkFunctionInventory.FunctionRuntime::callableName,
                        LinkedHashMap::new,
                        Collectors.toList()));
        List<OperatorLibrary.BuiltInFunction> authoritative = new ArrayList<>();
        byCallable.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    Set<String> fingerprints = entry.getValue().stream()
                            .map(FrameworkFunctionInventory.FunctionRuntime::declaredContract)
                            .map(BuiltInFunctionContract::callableFingerprint)
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                    if (fingerprints.size() == 1) {
                        authoritative.add(entry.getValue().getFirst().declaredContract());
                    }
                });
        return List.copyOf(authoritative);
    }

    private AuthoringFactProjection fromLibrary(
            String sourceKind,
            String sourceId,
            Object source,
            OperatorLibrary library,
            List<VisualDiagnostic> diagnostics,
            List<AuthoringFactProjection.ReviewItem> sourceReviews) {
        List<VisualDiagnostic> safeDiagnostics = diagnostics == null ? List.of() : diagnostics;
        List<AuthoringFactProjection.Fact> facts = libraryFacts(sourceKind, library);
        RuntimeParityService.Snapshot parity = parityService.evaluate(library);
        AuthoringDocumentProjector.Result document = documentProjector.project(library);
        List<AuthoringFactProjection.ReviewItem> reviews = new ArrayList<>(
                sourceReviews == null ? List.of() : sourceReviews);
        reviews.addAll(document.reviewItems());
        reviews.addAll(parityReviewItems(parity.parity()));
        boolean accepted = library != null
                && safeDiagnostics.stream().noneMatch(VisualDiagnostic::error);
        AuthoringFactProjection candidate = new AuthoringFactProjection(
                AuthoringFactProjection.SCHEMA_VERSION,
                sourceKind,
                sourceId,
                fingerprint(sourceKind, sourceId, source),
                "",
                accepted,
                summary(facts, parity.parity()),
                facts,
                parity.parity(),
                reviews,
                safeDiagnostics,
                accepted ? document.document() : null
        );
        return fingerprint(candidate);
    }

    private List<AuthoringFactProjection.Fact> libraryFacts(
            String sourceKind,
            OperatorLibrary library) {
        if (library == null) {
            return List.of();
        }
        List<AuthoringFactProjection.Fact> facts = new ArrayList<>();
        library.operators().stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(OperatorDefinition::operatorRef))
                .map(operator -> operatorFact(
                        sourceKind, operator, "DECLARATION", "DECLARED",
                        "/operators/" + pointer(operator.operatorRef())))
                .forEach(facts::add);
        library.builtInFunctions().stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(OperatorLibrary.BuiltInFunction::name))
                .map(function -> new AuthoringFactProjection.Fact(
                        factId(sourceKind, "FUNCTION", function.name(), "DECLARATION"),
                        "FUNCTION",
                        function.name(),
                        "DECLARATION",
                        "DECLARED",
                        BuiltInFunctionContract.callableFingerprint(function),
                        "/functions/" + pointer(function.name()),
                        1,
                        List.of(),
                        Map.of(
                                "namespace", function.namespace(),
                                "signatureCount", function.signatures().size(),
                                "category", function.category()
                        )
                ))
                .forEach(facts::add);
        return List.copyOf(facts);
    }

    private static AuthoringFactProjection.Fact operatorFact(
            String sourceKind,
            OperatorDefinition operator,
            String factKind,
            String evidenceLevel,
            String sourcePath) {
        return new AuthoringFactProjection.Fact(
                factId(sourceKind, "OPERATOR", operator.operatorRef(), factKind),
                "OPERATOR",
                operator.operatorRef(),
                factKind,
                evidenceLevel,
                operator.fingerprint(),
                sourcePath,
                1,
                List.of(),
                Map.of(
                        "operatorVersion", operator.operatorVersion(),
                        "sourceKind", operator.source().kind(),
                        "loweringMode", operator.lowering().mode(),
                        "inputPortCount", operator.ports().inputs().size(),
                        "outputPortCount", operator.ports().outputs().size(),
                        "effect", operator.capabilities().effect(),
                        "idempotency", operator.capabilities().idempotency()
                )
        );
    }

    private static List<AuthoringFactProjection.ReviewItem> parityReviewItems(
            List<AuthoringFactProjection.RuntimeParity> parity) {
        return parity.stream()
                .filter(AuthoringFactProjection.RuntimeParity::unresolved)
                .map(item -> new AuthoringFactProjection.ReviewItem(
                        item.reasonCode(),
                        "DRIFTED".equals(item.state()) || "BLOCKED_BY_POLICY".equals(item.state())
                                ? "ERROR" : "WARNING",
                        item.assetKind(),
                        item.assetRef(),
                        item.message(),
                        switch (item.state()) {
                            case "DOCUMENTED_ONLY" -> "Bind the asset to an exact target runtime.";
                            case "RUNTIME_DISCOVERED" ->
                                    "Supply authoritative contract metadata before executable promotion.";
                            case "DRIFTED" -> "Reconcile the declared and runtime fingerprints.";
                            case "BLOCKED_BY_POLICY" -> "Move side effects to an operator or approve a sandbox profile.";
                            default -> "Review runtime parity evidence.";
                        }
                ))
                .toList();
    }

    private static AuthoringFactProjection.Summary summary(
            List<AuthoringFactProjection.Fact> facts,
            List<AuthoringFactProjection.RuntimeParity> parity) {
        int operators = (int) facts.stream().filter(fact -> "OPERATOR".equals(fact.assetKind())).count();
        int functions = (int) facts.stream().filter(fact -> "FUNCTION".equals(fact.assetKind())).count();
        int graphs = (int) facts.stream().filter(fact -> "GRAPH".equals(fact.assetKind())).count();
        int bound = (int) parity.stream().filter(item -> "BOUND".equals(item.state())).count();
        int drifted = (int) parity.stream()
                .filter(item -> "DRIFTED".equals(item.state())
                        || "BLOCKED_BY_POLICY".equals(item.state()))
                .count();
        int unresolved = (int) parity.stream()
                .filter(AuthoringFactProjection.RuntimeParity::unresolved)
                .count();
        boolean ready = !parity.isEmpty()
                && parity.stream().allMatch(AuthoringFactProjection.RuntimeParity::executableReady);
        return new AuthoringFactProjection.Summary(
                operators, functions, graphs, bound, drifted, unresolved, ready);
    }

    private AuthoringFactProjection fingerprint(AuthoringFactProjection projection) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", projection.schemaVersion());
        material.put("sourceKind", projection.sourceKind());
        material.put("sourceId", projection.sourceId());
        material.put("sourceFingerprint", projection.sourceFingerprint());
        material.put("accepted", projection.accepted());
        material.put("summary", projection.summary());
        material.put("facts", projection.facts());
        material.put("runtimeParity", projection.runtimeParity());
        material.put("reviewItems", projection.reviewItems());
        material.put("diagnostics", projection.diagnostics());
        material.put("authoringDocument", projection.authoringDocument());
        return projection.withProjectionFingerprint(
                VisualBundleFingerprint.fromCanonicalValue(
                        objectMapper, material, MAXIMUM_SOURCE_BYTES));
    }

    private String fingerprint(String sourceKind, String sourceId, Object source) {
        return VisualBundleFingerprint.fromCanonicalValue(
                objectMapper,
                Map.of(
                        "sourceKind", normalized(sourceKind, "UNKNOWN"),
                        "sourceId", normalized(sourceId, ""),
                        "source", source == null ? Map.of() : source),
                MAXIMUM_SOURCE_BYTES);
    }

    private void enforceSourceLimit(Object source) {
        try {
            if (objectMapper.writeValueAsBytes(source).length > MAXIMUM_SOURCE_BYTES) {
                throw new SourceLimitExceededException(MAXIMUM_SOURCE_BYTES);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("Discovery source cannot be serialized.", failure);
        }
    }

    private static Map<String, Set<String>> dependenciesByOperator(
            GraphDraft draft,
            Map<String, List<GraphDraft.DraftNode>> nodesByOperator) {
        Map<String, String> operatorByNode = nodesByOperator.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toMap(
                        GraphDraft.DraftNode::id,
                        GraphDraft.DraftNode::operatorRef,
                        (left, right) -> left,
                        LinkedHashMap::new));
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (GraphDraft.DraftEdge edge : draft.edges()) {
            String source = operatorByNode.get(edge.source().nodeId());
            String target = operatorByNode.get(edge.target().nodeId());
            if (source == null || target == null || source.equals(target)) {
                continue;
            }
            dependencies.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(source);
        }
        return dependencies;
    }

    private static List<String> importStringList(GraphDraft draft, String key) {
        Object value = importMetadata(draft).get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(String::valueOf)
                .filter(item -> !item.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static boolean importBoolean(GraphDraft draft, String key) {
        return Boolean.TRUE.equals(importMetadata(draft).get(key));
    }

    private static Map<String, Object> importMetadata(GraphDraft draft) {
        Object value = draft.visualLayout().get("import");
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String factId(
            String sourceKind,
            String assetKind,
            String assetRef,
            String factKind) {
        return String.join(":",
                normalized(sourceKind, "unknown").toLowerCase(),
                normalized(assetKind, "unknown").toLowerCase(),
                normalized(assetRef, "unknown"),
                normalized(factKind, "declaration").toLowerCase());
    }

    private static String pointer(String value) {
        return normalized(value, "").replace("~", "~0").replace("/", "~1");
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public static final class SourceLimitExceededException extends RuntimeException {
        private final int maximumBytes;

        public SourceLimitExceededException(int maximumBytes) {
            super("Discovery source exceeds the synchronous limit of " + maximumBytes + " bytes.");
            this.maximumBytes = maximumBytes;
        }

        public int maximumBytes() {
            return maximumBytes;
        }
    }
}
