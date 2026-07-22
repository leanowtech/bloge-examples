package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.exception.ResourceNotFoundException;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Projects one visual {@link GraphDraft} and its current external leaves into a sealed capability closure.
 *
 * <p>The draft remains the graph authority. Saved operator snapshots are preserved exactly; a portable
 * unsaved draft may omit them, in which case this service resolves one coherent current catalog view and
 * pins every resolved definition into the projected source material. Resource-backed leaves are projected
 * from the authoritative resource registry. Pure operators stay inside the graph fingerprint rather than
 * becoming false business capabilities.</p>
 *
 * <p>This Stage 0 boundary is deliberately fail closed for nested graph operators. A nested graph requires
 * an exact child closure, not a lookup against a mutable publication registry; that input becomes part of
 * MirrorPlan compilation. Missing operators, duplicate node ids, stale pinned fingerprints, missing resource
 * descriptors, and nested graph boundaries therefore abort before any closure is emitted.</p>
 */
@Service
public class GraphDraftCapabilityClosureService {
    private final VisualOperatorCatalog operatorCatalog;
    private final ResourceRegistry resourceRegistry;
    private final CapabilityProjectionService projectionService;
    private final ObjectMapper mapper;

    /**
     * Creates the graph projection boundary from authoritative registries.
     *
     * @param operatorCatalog current visual operator catalog used only for omitted snapshots
     * @param resourceRegistry authoritative Resource Gateway resource registry
     * @param projectionService snapshot projection and effect aggregation service
     * @param mapper application JSON mapper used to seal the closure
     */
    public GraphDraftCapabilityClosureService(VisualOperatorCatalog operatorCatalog,
                                              ResourceRegistry resourceRegistry,
                                              CapabilityProjectionService projectionService,
                                              ObjectMapper mapper) {
        this.operatorCatalog = Objects.requireNonNull(operatorCatalog, "operatorCatalog");
        this.resourceRegistry = Objects.requireNonNull(resourceRegistry, "resourceRegistry");
        this.projectionService = Objects.requireNonNull(projectionService, "projectionService");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Projects a graph and every directly reachable external resource/operator leaf.
     *
     * @param draft authoritative or portable visual graph draft
     * @param context explicit revision, scope, ownership, policy, and creation time
     * @return sealed root-plus-leaf closure suitable for independent verification
     * @throws CapabilityProjectionException.Failure when an exact closure cannot be produced
     */
    public CapabilityClosure project(GraphDraft draft, CapabilityProjectionContext context) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(context, "context");
        GraphDraft pinnedDraft = pinOperatorSnapshots(draft);
        List<CapabilitySnapshot> children = projectChildren(pinnedDraft, context);
        CapabilitySnapshot root = projectionService.projectGraph(pinnedDraft, context, children);
        List<CapabilitySnapshot> snapshots = new ArrayList<>(children);
        snapshots.add(root);
        return CapabilityClosureIntegrity.seal(mapper, new CapabilityClosure("",
                CapabilityClosureIntegrity.reference(root), snapshots, ""));
    }

    private GraphDraft pinOperatorSnapshots(GraphDraft draft) {
        Set<String> nodeIds = new LinkedHashSet<>();
        Set<String> missingOperatorRefs = new LinkedHashSet<>();
        for (GraphDraft.DraftNode node : draft.nodes()) {
            if (!nodeIds.add(node.id())) {
                throw failure("RG.MIRROR.DUPLICATE_GRAPH_NODE",
                        "Capability projection requires unique graph node ids",
                        Map.of("draftId", draft.draftId(), "nodeId", node.id()));
            }
            if (!draft.operatorSnapshots().containsKey(node.id())) {
                missingOperatorRefs.add(node.operatorRef());
            }
        }
        Map<String, OperatorDefinition> current = operatorCatalog.findAll(missingOperatorRefs);
        Map<String, OperatorDefinition> snapshots = new LinkedHashMap<>();
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (GraphDraft.DraftNode node : draft.nodes()) {
            OperatorDefinition operator = draft.operatorSnapshots().get(node.id());
            if (operator == null) {
                operator = current.get(node.operatorRef());
            }
            if (operator == null) {
                throw failure("RG.MIRROR.OPERATOR_SNAPSHOT_MISSING",
                        "Graph node cannot be resolved to an exact operator definition",
                        Map.of("draftId", draft.draftId(), "nodeId", node.id(),
                                "operatorRef", node.operatorRef()));
            }
            if (!node.operatorRef().equals(operator.operatorRef())) {
                throw failure("RG.MIRROR.OPERATOR_IDENTITY_MISMATCH",
                        "Graph node operator reference does not match its resolved snapshot",
                        Map.of("nodeId", node.id(), "operatorRef", node.operatorRef()));
            }
            String pinned = draft.operatorFingerprints().get(node.id());
            if (pinned != null && !pinned.isBlank() && !pinned.equals(operator.fingerprint())) {
                throw failure("RG.MIRROR.OPERATOR_FINGERPRINT_MISMATCH",
                        "Graph node fingerprint does not match its resolved operator snapshot",
                        Map.of("nodeId", node.id(), "operatorRef", node.operatorRef()));
            }
            snapshots.put(node.id(), operator);
            fingerprints.put(node.id(), operator.fingerprint());
        }
        return new GraphDraft(draft.schemaVersion(), draft.draftId(), draft.revision(), draft.graphName(),
                draft.tenantId(), draft.namespace(), draft.environment(), draft.status(), draft.inputSchema(),
                draft.outputSchema(), draft.nodes(), draft.edges(), draft.visualLayout(), draft.nodeFixtures(),
                draft.output(), fingerprints, snapshots, draft.revisionMetadata());
    }

    private List<CapabilitySnapshot> projectChildren(GraphDraft draft, CapabilityProjectionContext context) {
        Map<String, CapabilitySnapshot> children = new LinkedHashMap<>();
        for (GraphDraft.DraftNode node : draft.nodes()) {
            OperatorDefinition operator = draft.operatorSnapshots().get(node.id());
            if (!CapabilityBoundaryResolver.isBoundary(operator)) {
                continue;
            }
            CapabilityBoundaryResolver.Target target = CapabilityBoundaryResolver.resolve(node, operator);
            CapabilitySnapshot child = switch (target.sourceKind()) {
                case RESOURCE -> projectResource(target.sourceRef(), operator, context);
                case OPERATOR -> projectionService.projectOperator(operator, context);
                case GRAPH -> throw failure("RG.MIRROR.NESTED_CAPABILITY_CLOSURE_REQUIRED",
                        "Nested graph projection requires an exact child capability closure",
                        Map.of("nodeId", node.id(), "operatorRef", node.operatorRef()));
            };
            String coordinate = child.capabilityId() + "@" + child.revision();
            CapabilitySnapshot previous = children.putIfAbsent(coordinate, child);
            if (previous != null && !previous.fingerprint().equals(child.fingerprint())) {
                throw failure("RG.MIRROR.CHILD_CAPABILITY_CONFLICT",
                        "One graph resolved the same child revision to different content",
                        Map.of("graphName", draft.graphName(), "capabilityId", child.capabilityId(),
                                "revision", child.revision()));
            }
        }
        return List.copyOf(children.values());
    }

    private CapabilitySnapshot projectResource(String resourceId,
                                                OperatorDefinition operator,
                                                CapabilityProjectionContext context) {
        ResourceDescriptor descriptor;
        try {
            descriptor = resourceRegistry.resolve(resourceId);
        } catch (ResourceNotFoundException exception) {
            throw missingResource(resourceId);
        }
        if (descriptor == null) {
            throw missingResource(resourceId);
        }
        ResourceSchemas schemas = resourceSchemas(operator);
        return projectionService.projectResource(descriptor, schemas.input(), schemas.output(), context);
    }

    private static ResourceSchemas resourceSchemas(OperatorDefinition operator) {
        SchemaEnvelope input = operator.ports().inputs().isEmpty()
                ? SchemaEnvelope.opaque() : operator.ports().inputs().getFirst().schema();
        SchemaEnvelope output = operator.ports().outputs().isEmpty()
                ? SchemaEnvelope.opaque() : operator.ports().outputs().getFirst().schema();
        return new ResourceSchemas(input, output);
    }

    private static CapabilityProjectionException.Failure missingResource(String resourceId) {
        return failure("RG.MIRROR.RESOURCE_DESCRIPTOR_MISSING",
                "Resource-backed graph node has no registered descriptor",
                Map.of("resourceId", resourceId));
    }

    private static CapabilityProjectionException.Failure failure(String code,
                                                                  String message,
                                                                  Map<String, Object> details) {
        return new CapabilityProjectionException(code, message, details).failure();
    }

    private record ResourceSchemas(SchemaEnvelope input, SchemaEnvelope output) {
    }
}
