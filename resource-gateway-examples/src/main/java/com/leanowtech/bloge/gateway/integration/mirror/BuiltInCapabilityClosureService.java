package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.exception.ResourceNotFoundException;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphContract;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphContractCatalog;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.importer.DslImportPreviewRequest;
import com.leanowtech.bloge.gateway.visual.importer.DslImportService;
import com.leanowtech.bloge.gateway.visual.importer.DslVisualProjection;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    private final ResourceRegistry resourceRegistry;
    private final CapabilityProjectionService projectionService;
    private final ObjectMapper mapper;

    /**
     * Creates the built-in closure projection boundary from authoritative application registries.
     *
     * @param dslImportService BLOGE DSL-to-draft projection service
     * @param graphContracts formal graph input/output contracts
     * @param operatorCatalog current operator definitions and schemas
     * @param resourceRegistry current HTTP resource descriptors
     * @param projectionService sealed snapshot projection service
     * @param mapper application JSON mapper
     */
    public BuiltInCapabilityClosureService(DslImportService dslImportService,
                                           GatewayGraphContractCatalog graphContracts,
                                           VisualOperatorCatalog operatorCatalog,
                                           ResourceRegistry resourceRegistry,
                                           CapabilityProjectionService projectionService,
                                           ObjectMapper mapper) {
        this.dslImportService = Objects.requireNonNull(dslImportService, "dslImportService");
        this.graphContracts = Objects.requireNonNull(graphContracts, "graphContracts");
        this.operatorCatalog = Objects.requireNonNull(operatorCatalog, "operatorCatalog");
        this.resourceRegistry = Objects.requireNonNull(resourceRegistry, "resourceRegistry");
        this.projectionService = Objects.requireNonNull(projectionService, "projectionService");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
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
        List<CapabilitySnapshot> children = projectChildren(draft, context);
        CapabilitySnapshot root = projectionService.projectGraph(draft, context, children);
        List<CapabilitySnapshot> snapshots = new ArrayList<>(children);
        snapshots.add(root);
        return CapabilityClosureIntegrity.seal(mapper, new CapabilityClosure("",
                CapabilityClosureIntegrity.reference(root), snapshots, ""));
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

    private List<CapabilitySnapshot> projectChildren(GraphDraft draft, CapabilityProjectionContext context) {
        Map<String, CapabilitySnapshot> children = new LinkedHashMap<>();
        for (GraphDraft.DraftNode node : draft.nodes()) {
            OperatorDefinition operator = draft.operatorSnapshots().get(node.id());
            if (operator == null) {
                throw failure("RG.MIRROR.OPERATOR_SNAPSHOT_MISSING",
                        "Built-in graph node has no saved operator definition",
                        Map.of("graphName", draft.graphName(), "nodeId", node.id()));
            }
            String staticResourceId = CapabilityBoundaryResolver.staticResourceId(node, operator);
            CapabilitySnapshot child;
            if (staticResourceId != null) {
                child = projectResource(staticResourceId, context);
            } else {
                if (!CapabilityBoundaryResolver.isBoundary(operator)) {
                    continue;
                }
                child = projectionService.projectOperator(operator, context);
            }
            String key = child.capabilityId() + "@" + child.revision();
            CapabilitySnapshot previous = children.putIfAbsent(key, child);
            if (previous != null && !previous.fingerprint().equals(child.fingerprint())) {
                throw failure("RG.MIRROR.CHILD_CAPABILITY_CONFLICT",
                        "One closure resolved the same child revision to different content",
                        Map.of("graphName", draft.graphName(), "capabilityId", child.capabilityId(),
                                "revision", child.revision()));
            }
        }
        return List.copyOf(children.values());
    }

    private CapabilitySnapshot projectResource(String resourceId, CapabilityProjectionContext context) {
        ResourceDescriptor descriptor;
        try {
            descriptor = resourceRegistry.resolve(resourceId);
        } catch (ResourceNotFoundException exception) {
            throw failure("RG.MIRROR.RESOURCE_DESCRIPTOR_MISSING",
                    "Static httpResource binding has no registered descriptor",
                    Map.of("resourceId", resourceId));
        }
        if (descriptor == null) {
            throw failure("RG.MIRROR.RESOURCE_DESCRIPTOR_MISSING",
                    "Static httpResource binding resolved to no descriptor",
                    Map.of("resourceId", resourceId));
        }
        ResourceSchemas schemas = operatorCatalog.find("resource:" + resourceId)
                .map(BuiltInCapabilityClosureService::resourceSchemas)
                .orElseGet(() -> new ResourceSchemas(SchemaEnvelope.opaque(), SchemaEnvelope.opaque()));
        return projectionService.projectResource(descriptor, schemas.input(), schemas.output(), context);
    }

    private static ResourceSchemas resourceSchemas(OperatorDefinition operator) {
        SchemaEnvelope input = operator.ports().inputs().isEmpty()
                ? SchemaEnvelope.opaque() : operator.ports().inputs().getFirst().schema();
        SchemaEnvelope output = operator.ports().outputs().isEmpty()
                ? SchemaEnvelope.opaque() : operator.ports().outputs().getFirst().schema();
        return new ResourceSchemas(input, output);
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

    private record ResourceSchemas(SchemaEnvelope input, SchemaEnvelope output) {
    }
}
