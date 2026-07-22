package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Projects authoritative Resource Gateway assets into immutable cross-system capability snapshots.
 *
 * <p>This service is intentionally a projection rather than a second registry. Resource descriptors,
 * operator definitions, and graph drafts remain authoritative. Every result freezes the exact source
 * fingerprint and is sealed before it leaves the service. Graph projection closes only over genuine
 * external or nested capability boundaries; internal pure operators remain implementation details
 * protected by the graph source fingerprint.</p>
 *
 * <p>Projection is fail closed. Missing operator snapshots, stale operator fingerprints, ambiguous
 * child capabilities, unsealed children, and unresolved external dependencies are rejected with a
 * stable {@link CapabilityProjectionException} code.</p>
 */
@Service
public class CapabilityProjectionService {
    private static final int MAXIMUM_SOURCE_BYTES = 2 * 1024 * 1024;
    private static final List<String> GRAPH_SOURCE_KINDS = List.of(
            "GRAPH", "COMPOSED_GRAPH", "NESTED_GRAPH");
    private static final List<String> EXTERNAL_SOURCE_KINDS = List.of(
            "REMOTE_WORKER", "AI_TOOL", "EVENT_SOURCE", "MESSAGE_HANDLER", "WEBHOOK");

    private final ObjectMapper mapper;

    /**
     * Creates a deterministic projection service using the application JSON contract mapper.
     *
     * @param mapper mapper used for canonical source and snapshot fingerprints
     */
    public CapabilityProjectionService(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Projects one HTTP resource descriptor as an external business capability.
     *
     * <p>Safe HTTP methods are read-only and executable. Unsafe methods are executable only when
     * the descriptor declares a conformant managed-write contract; an undeclared write remains a
     * critical external mutation with an unavailable runtime instead of being downgraded.</p>
     *
     * @param descriptor authoritative resource descriptor
     * @param inputSchema formal request input schema
     * @param outputSchema formal successful response schema
     * @param context explicit governance coordinates
     * @return sealed external capability snapshot
     */
    public CapabilitySnapshot projectResource(ResourceDescriptor descriptor,
                                              SchemaEnvelope inputSchema,
                                              SchemaEnvelope outputSchema,
                                              CapabilityProjectionContext context) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(context, "context");
        String sourceFingerprint = sourceFingerprint(descriptor);
        boolean write = descriptor.externalWrite();
        boolean managedWrite = write
                && descriptor.externalWriteContract() != null
                && descriptor.externalWriteContract().conformant();
        EffectContract effect = write
                ? externalWriteEffect("resource:" + descriptor.resourceId(), managedWrite)
                : EffectContract.readOnly(List.of("resource:" + descriptor.resourceId()));
        CapabilityContract contract = new CapabilityContract("", inputSchema, outputSchema,
                standardDependencyErrors(), effect, CapabilityContract.Determinism.CONTROLLED_NONDETERMINISTIC,
                resourceIdempotency(descriptor, managedWrite), null,
                CapabilityContract.CompatibilityPolicy.conservative(),
                security(context, descriptor.authStrategy() != null, List.of()),
                new CapabilityContract.SloContract(descriptor.defaultTimeout(), null, null,
                        context.ownership().team()));
        CapabilitySnapshot.RuntimeBinding runtime = !write || managedWrite
                ? readyRuntime("HTTP_RESOURCE", descriptor.resourceId(), sourceFingerprint)
                : CapabilitySnapshot.RuntimeBinding.unavailable(
                "external write descriptor is missing a conformant managed-write contract");
        CapabilitySnapshot snapshot = new CapabilitySnapshot("", "resource:" + descriptor.resourceId(),
                context.revision(), "", CapabilitySnapshot.Kind.EXTERNAL,
                new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.RESOURCE,
                        descriptor.resourceId(), sourceFingerprint), contract, runtime, List.of(),
                context.ownership(), context.lifecycle(), context.ownerProvenance(), context.createdAt());
        return CapabilitySnapshotIntegrity.seal(mapper, snapshot);
    }

    /**
     * Projects one externally observable operator boundary.
     *
     * <p>Pure transform and decision operators are deliberately rejected as standalone business
     * capabilities. External source metadata paired with a {@code PURE} declaration is admitted only
     * as a critical {@code UNKNOWN} effect, making the contradiction visible to governance.</p>
     *
     * @param operator authoritative operator definition
     * @param context explicit governance coordinates
     * @return sealed external capability snapshot
     * @throws CapabilityProjectionException.Failure when the operator is an internal implementation node
     */
    public CapabilitySnapshot projectOperator(OperatorDefinition operator,
                                              CapabilityProjectionContext context) {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(context, "context");
        if (!capabilityBoundary(operator)) {
            throw failure("RG.MIRROR.NOT_CAPABILITY_BOUNDARY",
                    "Pure internal operators are covered by their parent graph fingerprint",
                    Map.of("operatorRef", operator.operatorRef()));
        }
        EffectContract effect = CapabilityEffectAnalyzer.fromOperator(operator);
        CapabilityContract contract = new CapabilityContract("", portsSchema(operator.ports().inputs()),
                portsSchema(operator.ports().outputs()), standardDependencyErrors(), effect,
                operatorDeterminism(operator), operatorIdempotency(operator), null,
                CapabilityContract.CompatibilityPolicy.conservative(),
                security(context, operator.capabilities().requiresSecrets(), List.of()),
                CapabilityContract.SloContract.unspecified());
        CapabilitySnapshot.RuntimeBinding runtime = operator.runtimeReadiness().executable()
                && effect.mode() != EffectContract.Mode.UNKNOWN
                ? readyRuntime("OPERATOR", operator.operatorRef() + "@" + operator.operatorVersion(),
                operator.fingerprint())
                : CapabilitySnapshot.RuntimeBinding.unavailable(effect.mode() == EffectContract.Mode.UNKNOWN
                ? "effect contract is unresolved" : runtimeLimitation(operator));
        CapabilitySnapshot snapshot = new CapabilitySnapshot("", "operator:" + operator.operatorRef(),
                context.revision(), "", CapabilitySnapshot.Kind.EXTERNAL,
                new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.OPERATOR,
                        operator.operatorRef(), operator.fingerprint()), contract, runtime, List.of(),
                context.ownership(), context.lifecycle(), context.ownerProvenance(), context.createdAt());
        return CapabilitySnapshotIntegrity.seal(mapper, snapshot);
    }

    /**
     * Projects a visual graph into a composed capability with exact child snapshot dependencies.
     *
     * <p>Every node must carry its saved operator snapshot so projection can distinguish internal
     * implementation from an external boundary without consulting mutable catalog state. Child
     * snapshots must already be sealed. Conditions are inherited from incoming route edges and the
     * complete transitive effect is conservatively aggregated.</p>
     *
     * @param draft authoritative graph draft
     * @param context explicit governance coordinates
     * @param childSnapshots exact candidate child capability revisions
     * @return sealed composed capability snapshot
     */
    public CapabilitySnapshot projectGraph(GraphDraft draft,
                                           CapabilityProjectionContext context,
                                           Collection<CapabilitySnapshot> childSnapshots) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(context, "context");
        List<CapabilitySnapshot> children = verifiedChildren(childSnapshots);
        List<CapabilitySnapshot.Dependency> dependencies = new ArrayList<>();
        List<CapabilityEffectAnalyzer.ScopedEffect> effects = new ArrayList<>();
        List<CapabilitySnapshot> resolvedChildren = new ArrayList<>();

        for (GraphDraft.DraftNode node : draft.nodes().stream()
                .sorted(Comparator.comparing(GraphDraft.DraftNode::id)).toList()) {
            OperatorDefinition operator = requiredOperatorSnapshot(draft, node);
            verifyPinnedOperatorFingerprint(draft, node, operator);
            if (!capabilityBoundary(operator)) {
                continue;
            }
            CapabilitySnapshot child = resolveChild(node, operator, children);
            List<String> conditions = conditionsFor(draft, node.id());
            dependencies.add(new CapabilitySnapshot.Dependency(node.id(), capabilityRef(child),
                    conditions.isEmpty(), conditions));
            effects.add(new CapabilityEffectAnalyzer.ScopedEffect(node.id(),
                    String.join(" || ", conditions), child.contract().effect()));
            resolvedChildren.add(child);
        }
        if (dependencies.isEmpty()) {
            throw failure("RG.MIRROR.GRAPH_HAS_NO_CAPABILITY_DEPENDENCIES",
                    "A composed graph capability must cross at least one external or nested capability boundary",
                    Map.of("draftId", draft.draftId(), "graphName", draft.graphName()));
        }

        EffectContract effect = CapabilityEffectAnalyzer.aggregate(effects);
        MirrorArtifactRef stateModelRef = inheritedStateModel(effect, resolvedChildren);
        CapabilityContract contract = new CapabilityContract("", draft.inputSchema(), draft.outputSchema(),
                inheritedErrors(resolvedChildren), effect, inheritedDeterminism(resolvedChildren),
                inheritedIdempotency(effect, resolvedChildren), stateModelRef,
                CapabilityContract.CompatibilityPolicy.conservative(),
                security(context, false, resolvedChildren), CapabilityContract.SloContract.unspecified());
        String sourceFingerprint = graphFingerprint(draft);
        CapabilitySnapshot.RuntimeBinding runtime = graphRuntime(draft, context, sourceFingerprint,
                effect, resolvedChildren);
        String sourceRef = draft.draftId().isBlank() ? draft.graphName() : draft.draftId();
        CapabilitySnapshot snapshot = new CapabilitySnapshot("", "graph:" + draft.graphName(),
                context.revision(), "", CapabilitySnapshot.Kind.COMPOSED,
                new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.GRAPH,
                        sourceRef, sourceFingerprint), contract, runtime, dependencies,
                context.ownership(), context.lifecycle(), context.ownerProvenance(), context.createdAt());
        return CapabilitySnapshotIntegrity.seal(mapper, snapshot);
    }

    private List<CapabilitySnapshot> verifiedChildren(Collection<CapabilitySnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return List.of();
        }
        List<CapabilitySnapshot> verified = new ArrayList<>();
        for (CapabilitySnapshot snapshot : snapshots) {
            if (snapshot == null) {
                throw failure("RG.MIRROR.CHILD_SNAPSHOT_INVALID", "Child snapshot must not be null", Map.of());
            }
            try {
                CapabilitySnapshotIntegrity.verify(mapper, snapshot);
            } catch (IllegalArgumentException exception) {
                throw failure("RG.MIRROR.CHILD_SNAPSHOT_INVALID",
                        "Child capability snapshot is unsealed or has drifted",
                        Map.of("capabilityId", snapshot.capabilityId(), "reason", exception.getMessage()));
            }
            verified.add(snapshot);
        }
        return verified.stream().sorted(Comparator.comparing(CapabilitySnapshot::capabilityId)
                .thenComparingLong(CapabilitySnapshot::revision)).toList();
    }

    private OperatorDefinition requiredOperatorSnapshot(GraphDraft draft, GraphDraft.DraftNode node) {
        OperatorDefinition operator = draft.operatorSnapshots().get(node.id());
        if (operator == null) {
            throw failure("RG.MIRROR.OPERATOR_SNAPSHOT_MISSING",
                    "Graph projection requires a saved operator definition for every node",
                    Map.of("draftId", draft.draftId(), "nodeId", node.id(),
                            "operatorRef", node.operatorRef()));
        }
        if (!node.operatorRef().equals(operator.operatorRef())) {
            throw failure("RG.MIRROR.OPERATOR_IDENTITY_MISMATCH",
                    "Node operator reference does not match its saved operator snapshot",
                    Map.of("nodeId", node.id(), "nodeOperatorRef", node.operatorRef(),
                            "snapshotOperatorRef", operator.operatorRef()));
        }
        return operator;
    }

    private void verifyPinnedOperatorFingerprint(GraphDraft draft,
                                                 GraphDraft.DraftNode node,
                                                 OperatorDefinition operator) {
        String pinned = draft.operatorFingerprints().get(node.id());
        if (pinned != null && !pinned.isBlank() && !pinned.equals(operator.fingerprint())) {
            throw failure("RG.MIRROR.OPERATOR_FINGERPRINT_MISMATCH",
                    "Saved operator fingerprint does not match the saved definition",
                    Map.of("nodeId", node.id(), "pinnedFingerprint", pinned,
                            "snapshotFingerprint", operator.fingerprint()));
        }
    }

    private CapabilitySnapshot resolveChild(GraphDraft.DraftNode node,
                                            OperatorDefinition operator,
                                            List<CapabilitySnapshot> children) {
        CapabilitySnapshot.SourceKind sourceKind = expectedChildSource(operator);
        String sourceRef = expectedChildSourceRef(operator, sourceKind);
        List<CapabilitySnapshot> matches = children.stream()
                .filter(child -> child.source().sourceKind() == sourceKind)
                .filter(child -> child.source().sourceRef().equals(sourceRef))
                .filter(child -> sourceKind != CapabilitySnapshot.SourceKind.OPERATOR
                        || child.source().sourceFingerprint().equals(operator.fingerprint()))
                .toList();
        if (matches.isEmpty()) {
            throw failure("RG.MIRROR.CHILD_CAPABILITY_MISSING",
                    "External graph node has no exact sealed child capability",
                    Map.of("nodeId", node.id(), "sourceKind", sourceKind.name(),
                            "sourceRef", sourceRef));
        }
        if (matches.size() > 1) {
            throw failure("RG.MIRROR.CHILD_CAPABILITY_AMBIGUOUS",
                    "External graph node resolves to multiple child capability revisions",
                    Map.of("nodeId", node.id(), "sourceKind", sourceKind.name(),
                            "sourceRef", sourceRef, "candidateCount", matches.size()));
        }
        CapabilitySnapshot child = matches.getFirst();
        CapabilitySnapshot.Kind expectedKind = sourceKind == CapabilitySnapshot.SourceKind.GRAPH
                ? CapabilitySnapshot.Kind.COMPOSED : CapabilitySnapshot.Kind.EXTERNAL;
        if (child.kind() != expectedKind) {
            throw failure("RG.MIRROR.CHILD_CAPABILITY_KIND_MISMATCH",
                    "Resolved child capability kind is inconsistent with the node source",
                    Map.of("nodeId", node.id(), "expectedKind", expectedKind.name(),
                            "actualKind", child.kind().name()));
        }
        return child;
    }

    private static CapabilitySnapshot.SourceKind expectedChildSource(OperatorDefinition operator) {
        String sourceKind = normalize(operator.source().kind());
        if (GRAPH_SOURCE_KINDS.contains(sourceKind)) {
            return CapabilitySnapshot.SourceKind.GRAPH;
        }
        if (!operator.source().resourceId().isBlank()) {
            return CapabilitySnapshot.SourceKind.RESOURCE;
        }
        return CapabilitySnapshot.SourceKind.OPERATOR;
    }

    private static String expectedChildSourceRef(OperatorDefinition operator,
                                                 CapabilitySnapshot.SourceKind sourceKind) {
        return sourceKind != CapabilitySnapshot.SourceKind.OPERATOR
                && !operator.source().resourceId().isBlank()
                ? operator.source().resourceId()
                : operator.operatorRef();
    }

    private static boolean capabilityBoundary(OperatorDefinition operator) {
        String effect = normalize(operator.capabilities().effect());
        String sourceKind = normalize(operator.source().kind());
        return !"PURE".equals(effect)
                || !operator.source().resourceId().isBlank()
                || GRAPH_SOURCE_KINDS.contains(sourceKind)
                || EXTERNAL_SOURCE_KINDS.contains(sourceKind);
    }

    private static List<String> conditionsFor(GraphDraft draft, String nodeId) {
        return draft.edges().stream()
                .filter(edge -> edge.target().nodeId().equals(nodeId))
                .filter(edge -> "route".equals(edge.kind()) || !edge.condition().isBlank())
                .map(edge -> edge.condition().isBlank() ? "route:" + edge.id() : edge.condition())
                .distinct().sorted().toList();
    }

    private static MirrorArtifactRef capabilityRef(CapabilitySnapshot child) {
        return new MirrorArtifactRef("CAPABILITY", child.capabilityId(), child.revision(), child.fingerprint());
    }

    private static EffectContract externalWriteEffect(String resource, boolean managedWrite) {
        return new EffectContract("", EffectContract.Mode.EXTERNAL_MUTATION, List.of(), List.of(resource),
                List.of(), null, true,
                managedWrite ? EffectContract.RiskLevel.HIGH : EffectContract.RiskLevel.CRITICAL,
                EffectContract.Derivation.DECLARED,
                managedWrite ? List.of() : List.of());
    }

    private static CapabilityContract.IdempotencyContract resourceIdempotency(ResourceDescriptor descriptor,
                                                                                boolean managedWrite) {
        if (!descriptor.externalWrite() || List.of("PUT", "DELETE").contains(descriptor.method())) {
            return new CapabilityContract.IdempotencyContract(
                    CapabilityContract.IdempotencyMode.IDEMPOTENT, "", true);
        }
        if (managedWrite) {
            return new CapabilityContract.IdempotencyContract(CapabilityContract.IdempotencyMode.KEYED,
                    descriptor.externalWriteContract().idempotencyKeyParam(), true);
        }
        return new CapabilityContract.IdempotencyContract(
                CapabilityContract.IdempotencyMode.NON_IDEMPOTENT, "", false);
    }

    private static CapabilityContract.IdempotencyContract operatorIdempotency(OperatorDefinition operator) {
        if (operator.capabilities().externalWrite()
                && operator.capabilities().sideEffectProtocol().managedWrite()) {
            return new CapabilityContract.IdempotencyContract(CapabilityContract.IdempotencyMode.KEYED,
                    operator.capabilities().sideEffectProtocol().idempotencyKeySource(), true);
        }
        return switch (normalize(operator.capabilities().idempotency())) {
            case "DETERMINISTIC" -> new CapabilityContract.IdempotencyContract(
                    CapabilityContract.IdempotencyMode.DETERMINISTIC, "", true);
            case "IDEMPOTENT" -> new CapabilityContract.IdempotencyContract(
                    CapabilityContract.IdempotencyMode.IDEMPOTENT, "", true);
            case "NON_IDEMPOTENT" -> new CapabilityContract.IdempotencyContract(
                    CapabilityContract.IdempotencyMode.NON_IDEMPOTENT, "", false);
            default -> CapabilityContract.IdempotencyContract.unknown();
        };
    }

    private static CapabilityContract.Determinism operatorDeterminism(OperatorDefinition operator) {
        return "DETERMINISTIC".equals(normalize(operator.capabilities().idempotency()))
                ? CapabilityContract.Determinism.DETERMINISTIC
                : CapabilityContract.Determinism.CONTROLLED_NONDETERMINISTIC;
    }

    private static SchemaEnvelope portsSchema(List<OperatorDefinition.Port> ports) {
        Map<String, Object> properties = new TreeMap<>();
        List<String> required = new ArrayList<>();
        for (OperatorDefinition.Port port : ports) {
            if (properties.putIfAbsent(port.name(), port.schema().schema()) != null) {
                throw failure("RG.MIRROR.DUPLICATE_PORT", "Operator ports must have unique names",
                        Map.of("port", port.name()));
            }
            if (port.required()) {
                required.add(port.name());
            }
        }
        return SchemaEnvelope.object(properties, required.stream().sorted().toList());
    }

    private static List<CapabilityContract.ErrorContract> standardDependencyErrors() {
        return List.of(
                new CapabilityContract.ErrorContract("RG.DEPENDENCY.ERROR",
                        CapabilityContract.ErrorCategory.DEPENDENCY, true, SchemaEnvelope.opaque()),
                new CapabilityContract.ErrorContract("RG.DEPENDENCY.TIMEOUT",
                        CapabilityContract.ErrorCategory.TIMEOUT, true, SchemaEnvelope.opaque()));
    }

    private static List<CapabilityContract.ErrorContract> inheritedErrors(List<CapabilitySnapshot> children) {
        Map<String, CapabilityContract.ErrorContract> byCode = new TreeMap<>();
        for (CapabilitySnapshot child : children) {
            for (CapabilityContract.ErrorContract error : child.contract().errorModel()) {
                CapabilityContract.ErrorContract previous = byCode.putIfAbsent(error.code(), error);
                if (previous != null && !previous.equals(error)) {
                    throw failure("RG.MIRROR.ERROR_CONTRACT_CONFLICT",
                            "Child capabilities declare incompatible error contracts with the same code",
                            Map.of("errorCode", error.code(), "capabilityId", child.capabilityId()));
                }
            }
        }
        return List.copyOf(byCode.values());
    }

    private static CapabilityContract.Determinism inheritedDeterminism(List<CapabilitySnapshot> children) {
        if (children.stream().anyMatch(child -> child.contract().determinism()
                == CapabilityContract.Determinism.NONDETERMINISTIC)) {
            return CapabilityContract.Determinism.NONDETERMINISTIC;
        }
        return children.stream().allMatch(child -> child.contract().determinism()
                == CapabilityContract.Determinism.DETERMINISTIC)
                ? CapabilityContract.Determinism.DETERMINISTIC
                : CapabilityContract.Determinism.CONTROLLED_NONDETERMINISTIC;
    }

    private static CapabilityContract.IdempotencyContract inheritedIdempotency(
            EffectContract effect,
            List<CapabilitySnapshot> children) {
        if (children.stream().anyMatch(child -> child.contract().idempotency().mode()
                == CapabilityContract.IdempotencyMode.NON_IDEMPOTENT)) {
            return new CapabilityContract.IdempotencyContract(
                    CapabilityContract.IdempotencyMode.NON_IDEMPOTENT, "", false);
        }
        if (effect.mode() == EffectContract.Mode.READ_ONLY
                && children.stream().allMatch(child -> List.of(
                CapabilityContract.IdempotencyMode.DETERMINISTIC,
                CapabilityContract.IdempotencyMode.IDEMPOTENT).contains(child.contract().idempotency().mode()))) {
            return new CapabilityContract.IdempotencyContract(
                    CapabilityContract.IdempotencyMode.IDEMPOTENT, "", true);
        }
        return CapabilityContract.IdempotencyContract.unknown();
    }

    private static MirrorArtifactRef inheritedStateModel(EffectContract effect,
                                                         List<CapabilitySnapshot> children) {
        LinkedHashSet<MirrorArtifactRef> refs = new LinkedHashSet<>();
        children.stream().map(child -> child.contract().stateModelRef()).filter(Objects::nonNull)
                .forEach(refs::add);
        if (refs.size() > 1) {
            throw failure("RG.MIRROR.STATE_MODEL_AMBIGUOUS",
                    "A graph capability cannot inherit multiple state models without an explicit aggregate model",
                    Map.of("stateModelCount", refs.size()));
        }
        if (effect.mode() == EffectContract.Mode.VIRTUAL_MUTATION && refs.isEmpty()) {
            throw failure("RG.MIRROR.STATE_MODEL_MISSING",
                    "A virtual mutation graph requires an exact state model", Map.of());
        }
        return refs.isEmpty() ? null : refs.getFirst();
    }

    private static CapabilityContract.SecurityContract security(CapabilityProjectionContext context,
                                                                 boolean localRequiresSecrets,
                                                                 List<CapabilitySnapshot> children) {
        CapabilityContract.DataClassification classification = context.classification();
        boolean requiresSecrets = localRequiresSecrets;
        boolean payloadRetention = context.payloadRetentionAllowed();
        List<String> regions = context.allowedRegions();
        for (CapabilitySnapshot child : children) {
            CapabilityContract.SecurityContract childSecurity = child.contract().security();
            if (childSecurity.classification().ordinal() > classification.ordinal()) {
                classification = childSecurity.classification();
            }
            requiresSecrets |= childSecurity.requiresSecrets();
            payloadRetention &= childSecurity.payloadRetentionAllowed();
            regions = intersectRegions(regions, childSecurity.allowedRegions());
        }
        return new CapabilityContract.SecurityContract(classification, requiresSecrets, regions,
                payloadRetention);
    }

    private static List<String> intersectRegions(List<String> left, List<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return List.of();
        }
        return left.stream().filter(right::contains).distinct().sorted().toList();
    }

    private static CapabilitySnapshot.RuntimeBinding graphRuntime(GraphDraft draft,
                                                                  CapabilityProjectionContext context,
                                                                  String sourceFingerprint,
                                                                  EffectContract effect,
                                                                  List<CapabilitySnapshot> children) {
        List<String> limitations = new ArrayList<>();
        if (effect.mode() == EffectContract.Mode.UNKNOWN) {
            limitations.add("composed effect contract is unresolved");
        }
        children.stream().filter(child -> !child.runtime().ready())
                .map(child -> child.capabilityId() + ": " + String.join("; ", child.runtime().limitations()))
                .forEach(limitations::add);
        children.stream().filter(child -> child.lifecycle() == CapabilitySnapshot.Lifecycle.REVOKED
                        || child.lifecycle() == CapabilitySnapshot.Lifecycle.STALE)
                .map(child -> child.capabilityId() + ": child lifecycle is " + child.lifecycle())
                .forEach(limitations::add);
        limitations = limitations.stream().distinct().sorted().toList();
        if (!limitations.isEmpty()) {
            return new CapabilitySnapshot.RuntimeBinding("COMPOSED_GRAPH", "", "", false, limitations);
        }
        String sourceRef = draft.draftId().isBlank() ? draft.graphName() : draft.draftId();
        return readyRuntime("COMPOSED_GRAPH", sourceRef + "@" + context.revision(), sourceFingerprint);
    }

    private static CapabilitySnapshot.RuntimeBinding readyRuntime(String kind,
                                                                  String bindingRef,
                                                                  String fingerprint) {
        return new CapabilitySnapshot.RuntimeBinding(kind, bindingRef, fingerprint, true, List.of());
    }

    private static String runtimeLimitation(OperatorDefinition operator) {
        String state = operator.runtimeReadiness().state();
        String summary = operator.runtimeReadiness().summary();
        return summary.isBlank() ? state : state + ": " + summary;
    }

    private String sourceFingerprint(Object value) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper, value, MAXIMUM_SOURCE_BYTES);
    }

    private static String graphFingerprint(GraphDraft draft) {
        return VisualBundleFingerprint.fromMaterial(Map.of("draft", draft.withNodeFixtures(Map.of())));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static CapabilityProjectionException.Failure failure(String code,
                                                                  String message,
                                                                  Map<String, Object> details) {
        return new CapabilityProjectionException(code, message, details).failure();
    }
}
