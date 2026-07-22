package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.gateway.GatewayGraphContract;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphContractCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.importer.DslImportPreviewRequest;
import com.leanowtech.bloge.gateway.visual.importer.DslImportService;
import com.leanowtech.bloge.gateway.visual.importer.DslVisualProjection;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Projects the shipped Resource Gateway DSL graphs into exact capability closures.
 *
 * <p>The service deliberately reads the same classpath DSL, graph-contract catalog, operator catalog,
 * and resource registry used by authoring and runtime. It does not maintain a parallel topology. A
 * caller supplies all governance and timestamp coordinates through {@link CapabilityProjectionContext},
 * making repeated projection deterministic for an unchanged asset generation.</p>
 */
@Service
public class BuiltInCapabilityClosureService {
    private static final Map<String, String> DSL_RESOURCES = Map.of(
            "aiEnrichedSearch", "bloge/gateway/ai-enriched-search.bloge",
            "creditScore", "bloge/gateway/credit-score.bloge",
            "enrichOrderList", "bloge/gateway/enrich-order-list.bloge",
            "loanDecisionPolicy", "bloge/gateway/loan-decision-policy.bloge",
            "productDetail", "bloge/gateway/product-detail.bloge",
            "resourceDispatch", "bloge/gateway/resource-dispatch.bloge",
            "userDashboard", "bloge/gateway/user-dashboard.bloge");

    private final DslImportService dslImportService;
    private final GatewayGraphContractCatalog graphContracts;
    private final VisualOperatorCatalog operatorCatalog;
    private final GraphDraftCapabilityClosureService graphClosureService;

    /**
     * Creates the built-in closure projection boundary from authoritative application registries.
     *
     * @param dslImportService BLOGE DSL-to-draft projection service
     * @param graphContracts formal graph input/output contracts
     * @param operatorCatalog current operator definitions and schemas
     * @param graphClosureService shared GraphDraft capability-closure projection boundary
     */
    public BuiltInCapabilityClosureService(DslImportService dslImportService,
                                           GatewayGraphContractCatalog graphContracts,
                                           VisualOperatorCatalog operatorCatalog,
                                           GraphDraftCapabilityClosureService graphClosureService) {
        this.dslImportService = Objects.requireNonNull(dslImportService, "dslImportService");
        this.graphContracts = Objects.requireNonNull(graphContracts, "graphContracts");
        this.operatorCatalog = Objects.requireNonNull(operatorCatalog, "operatorCatalog");
        this.graphClosureService = Objects.requireNonNull(graphClosureService, "graphClosureService");
    }

    /**
     * Projects every shipped resource graph in stable graph-name order.
     *
     * @param context explicit revision, scope, ownership, policy, and creation time
     * @return immutable map from graph name to sealed complete closure
     */
    public Map<String, CapabilityClosure> projectAll(CapabilityProjectionContext context) {
        Objects.requireNonNull(context, "context");
        Map<String, CapabilityClosure> closures = new LinkedHashMap<>();
        graphContracts.all().stream().map(GatewayGraphContract::graphName).sorted()
                .forEach(graphName -> closures.put(graphName, project(graphName, context)));
        if (!closures.keySet().equals(DSL_RESOURCES.keySet())) {
            throw failure("RG.MIRROR.BUILTIN_CATALOG_DRIFT",
                    "Built-in graph contracts and DSL resource inventory have drifted",
                    Map.of("contractCount", closures.size(), "dslResourceCount", DSL_RESOURCES.size()));
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(closures));
    }

    /**
     * Projects one shipped resource graph and its complete external dependency closure.
     *
     * @param graphName graph name from {@link GatewayGraphContractCatalog}
     * @param context explicit revision, scope, ownership, policy, and creation time
     * @return sealed closure containing the graph root and every reachable child snapshot
     */
    public CapabilityClosure project(String graphName, CapabilityProjectionContext context) {
        Objects.requireNonNull(context, "context");
        GatewayGraphContract contract = graphContracts.require(graphName);
        String resource = DSL_RESOURCES.get(graphName);
        if (resource == null) {
            throw failure("RG.MIRROR.BUILTIN_DSL_MISSING",
                    "Built-in graph has no registered DSL resource", Map.of("graphName", graphName));
        }
        GraphDraft draft = authoritativeDraft(contract, resource, context);
        return graphClosureService.project(draft, context);
    }

    private GraphDraft authoritativeDraft(GatewayGraphContract contract,
                                          String resource,
                                          CapabilityProjectionContext context) {
        String dsl = readDsl(resource);
        DslVisualProjection visual = dslImportService.preview(new DslImportPreviewRequest(
                resource, dsl, List.of(), List.of(), "capability-projection", Map.of()));
        List<VisualDiagnostic> errors = visual.diagnostics().stream().filter(VisualDiagnostic::error).toList();
        if (!errors.isEmpty() || !contract.graphName().equals(visual.draft().graphName())) {
            throw failure("RG.MIRROR.BUILTIN_DSL_NOT_PROJECTABLE",
                    "Built-in DSL cannot be projected without blocking diagnostics",
                    Map.of("graphName", contract.graphName(), "errorCount", errors.size(),
                            "projectedGraphName", visual.draft().graphName()));
        }
        GraphDraft source = visual.draft();
        String namespace = context.projectId().isBlank() ? context.organizationId() : context.projectId();
        GraphDraft authoritative = new GraphDraft(source.schemaVersion(), "built-in:" + contract.graphName(), context.revision(),
                contract.graphName(), context.tenantId(), namespace, context.environmentId(),
                GraphDraft.STATUS_DRAFT, contract.inputSchema(), contract.outputSchema(), source.nodes(),
                source.edges(), source.visualLayout(), Map.of(), source.output(), source.operatorFingerprints(),
                source.operatorSnapshots(), source.revisionMetadata());
        return DslCapabilityBoundaryAugmenter.augment(authoritative, dsl, operatorCatalog);
    }

    private static String readDsl(String resource) {
        ClassLoader loader = BuiltInCapabilityClosureService.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(resource)) {
            if (stream == null) {
                throw failure("RG.MIRROR.BUILTIN_DSL_MISSING",
                        "Built-in DSL resource is not available on the classpath", Map.of("resource", resource));
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw failure("RG.MIRROR.BUILTIN_DSL_UNREADABLE",
                    "Built-in DSL resource could not be read", Map.of("resource", resource));
        }
    }

    private static CapabilityProjectionException.Failure failure(String code,
                                                                  String message,
                                                                  Map<String, Object> details) {
        return new CapabilityProjectionException(code, message, details).failure();
    }
}
