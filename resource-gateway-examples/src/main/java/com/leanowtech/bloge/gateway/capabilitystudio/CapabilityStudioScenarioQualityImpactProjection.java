package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Projects the canonical Scenario Dataset into the GP-09 quality and impact read model.
 *
 * <p>This is deliberately a read-only projection. It does not change lifecycle, freshness,
 * coverage, or source facts, and it never exposes fixture or payload material. The projection is
 * built only from the exact refs already emitted by {@link CapabilityStudioScenarioDatasetProjector}.
 * A malformed or internally inconsistent dataset fails closed instead of producing a plausible
 * but unsafe impact view.</p>
 */
public final class CapabilityStudioScenarioQualityImpactProjection {
    public static final String SCHEMA_VERSION =
            "resource-gateway.capability-studio.scenario-quality-impact.v1";
    private static final String FRESHNESS_UNVERIFIED = "UNVERIFIED";
    private static final String PAYLOAD_EXPOSURE_NONE = "NONE";
    private static final String MASKING_PAYLOAD_NOT_EXPORTED = "PAYLOAD_NOT_EXPORTED";
    private static final Set<String> NODE_STATUSES = Set.of(
            "ACTIVE", "DRAFT", "STALE", "READY", "BLOCKED", "ORPHANED", "RETIRED");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final ObjectMapper FINGERPRINT_JSON = new ObjectMapper();

    private final ObjectMapper mapper;
    private final ScenarioQualityImpactProjection projection;

    /** Builds a frozen quality projection from the existing canonical dataset projector. */
    public CapabilityStudioScenarioQualityImpactProjection(
            CapabilityStudioGoldenDemoPack pack,
            CapabilityStudioScenarioDatasetProjector datasetProjector) {
        this(pack, datasetProjector.project(), new ObjectMapper().findAndRegisterModules());
    }

    /** Builds a frozen quality projection with the application mapper as serialization baseline. */
    public CapabilityStudioScenarioQualityImpactProjection(
            CapabilityStudioGoldenDemoPack pack,
            CapabilityStudioScenarioDatasetProjector datasetProjector,
            ObjectMapper mapper) {
        this(pack, datasetProjector.project(), mapper);
    }

    /** Package-private constructor used by tamper-oriented tests. */
    CapabilityStudioScenarioQualityImpactProjection(
            CapabilityStudioGoldenDemoPack pack,
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset,
            ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.projection = project(
                Objects.requireNonNull(pack, "pack"), Objects.requireNonNull(dataset, "dataset"));
    }

    /** Returns the deterministic, payload-free quality and impact projection. */
    public ScenarioQualityImpactProjection project() {
        return projection;
    }

    private ScenarioQualityImpactProjection project(
            CapabilityStudioGoldenDemoPack pack,
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset) {
        validateDataset(pack, dataset);
        Map<PackRefKey, String> capabilityLabels = capabilityLabels(pack);
        Map<PackRefKey, String> contractLabels = contractLabels(pack);
        List<CaseProjection> cases = dataset.cases().stream()
                .sorted(Comparator.comparing(value -> value.caseRef().id()))
                .map(value -> caseProjection(value, dataset.targetRef()))
                .toList();
        Admission admission = admission(dataset, cases);
        Quality quality = quality(dataset, admission);
        ImpactGraph graph = graph(dataset, cases, capabilityLabels, contractLabels, admission);
        Summary summary = summary(cases, graph, dataset.targetRef());
        validateCardinality(dataset, cases, graph, summary);

        ScenarioQualityImpactProjection material = new ScenarioQualityImpactProjection(
                SCHEMA_VERSION,
                dataset.datasetRef(),
                dataset.targetRef(),
                null,
                admission,
                quality,
                summary,
                cases,
                graph);
        return new ScenarioQualityImpactProjection(
                material.schemaVersion(),
                material.datasetRef(),
                material.targetRef(),
                fingerprint(material),
                material.admission(),
                material.quality(),
                material.summary(),
                material.cases(),
                material.impactGraph());
    }

    private void validateDataset(
            CapabilityStudioGoldenDemoPack pack,
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset) {
        require(SCHEMA_DATASET.equals(dataset.schemaVersion()),
                "DATASET_SCHEMA", "Unexpected Scenario Dataset schema");
        Set<PackRefKey> authoritativeRefs = authoritativeRefs(pack);
        requireRef(dataset.datasetRef(), "datasetRef", "DATASET");
        requireRef(dataset.targetRef(), "targetRef", null);
        require(authoritativeRefs.contains(packKey(dataset.targetRef())),
                "REF_NOT_AUTHORITATIVE", "targetRef");
        require(dataset.quality() != null, "QUALITY_MISSING", "dataset.quality");
        require(!dataset.cases().isEmpty(), "CASES_MISSING", "Scenario Dataset has no cases");
        requireUnique(dataset.contractRefs(), "dataset.contractRefs");
        dataset.contractRefs().forEach(ref -> requireRef(ref, "dataset.contractRefs", "CONTRACT"));

        Set<RefKey> caseIdentities = new HashSet<>();
        for (CapabilityStudioScenarioDatasetProjector.DataCase dataCase : dataset.cases()) {
            requireRef(dataCase.caseRef(), "cases.caseRef", "DATA_CASE");
            require(caseIdentities.add(key(dataCase.caseRef())),
                    "DUPLICATE_CASE_IDENTITY", dataCase.caseRef().id());
            requireRef(dataCase.sourceRef(), "cases.sourceRef", "SOURCE");
            requireRef(dataCase.oracleRef(), "cases.oracleRef", "ORACLE");
            require(authoritativeRefs.contains(packKey(dataCase.sourceRef())),
                    "REF_NOT_AUTHORITATIVE", dataCase.sourceRef().id());
            require(authoritativeRefs.contains(packKey(dataCase.oracleRef())),
                    "REF_NOT_AUTHORITATIVE", dataCase.oracleRef().id());
            require(dataCase.source() != null, "SOURCE_MISSING", dataCase.caseRef().id());
            require(dataCase.oracle() != null, "ORACLE_MISSING", dataCase.caseRef().id());
            requireUnique(dataCase.applicableContractRefs(), "cases.applicableContractRefs");
            dataCase.applicableContractRefs().forEach(ref -> {
                requireRef(ref, "cases.applicableContractRefs", "CONTRACT");
                require(containsRef(dataset.contractRefs(), ref), "REF_NOT_DECLARED", ref.id());
                require(authoritativeRefs.contains(packKey(ref)), "REF_NOT_AUTHORITATIVE", ref.id());
            });
            List<CapabilityStudioScenarioDatasetProjector.BehaviorProfile> controls =
                    dataCase.behaviorProfiles().stream()
                            .filter(value -> "RUNTIME_CONTROL".equals(value.purpose()))
                            .toList();
            require(!controls.isEmpty(), "RUNTIME_CONTROL_MISSING", dataCase.caseRef().id());
            List<CapabilityStudioScenarioDatasetProjector.ExactRef> dependencies = controls.stream()
                    .map(CapabilityStudioScenarioDatasetProjector.BehaviorProfile::dependencyRef)
                    .toList();
            requireUnique(dependencies, "cases.runtimeDependencies");
            controls.forEach(profile -> {
                requireRef(profile.behaviorRef(), "cases.behaviorRef", "BEHAVIOR_PROFILE");
                requireRef(profile.dependencyRef(), "cases.dependencyRef", null);
                require(authoritativeRefs.contains(packKey(profile.dependencyRef())),
                        "REF_NOT_AUTHORITATIVE", profile.dependencyRef().id());
            });
        }
        require(dataset.contractRefs().stream().allMatch(ref -> authoritativeRefs.contains(packKey(ref))),
                "REF_NOT_AUTHORITATIVE", "dataset contractRefs");
        validateScopeClosure(dataset);
        validateCoverage(dataset);
    }

    private void validateScopeClosure(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset) {
        CapabilityStudioScenarioDatasetProjector.Scope scope = dataset.datasetRef().scope();
        List<CapabilityStudioScenarioDatasetProjector.ExactRef> refs = new ArrayList<>();
        refs.add(dataset.datasetRef());
        refs.add(dataset.targetRef());
        refs.addAll(dataset.contractRefs());
        dataset.cases().forEach(dataCase -> {
            refs.add(dataCase.caseRef());
            refs.add(dataCase.sourceRef());
            refs.add(dataCase.oracleRef());
            refs.addAll(dataCase.applicableContractRefs());
            dataCase.behaviorProfiles().forEach(profile -> {
                refs.add(profile.behaviorRef());
                refs.add(profile.dependencyRef());
            });
        });
        require(refs.stream().allMatch(ref -> scope.equals(ref.scope())),
                "REF_SCOPE_MISMATCH", "all quality-impact refs must share the Dataset scope");
    }

    private static final String SCHEMA_DATASET =
            "resource-gateway.capability-studio.scenario-dataset.v1";

    private void validateCoverage(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset) {
        List<CapabilityStudioScenarioDatasetProjector.DataCase> cases = dataset.cases();
        int active = count(cases, value -> "ACTIVE".equals(value.lifecycle()));
        int stale = count(cases, value -> "STALE".equals(value.lifecycle()));
        int total = cases.size();
        CapabilityStudioScenarioDatasetProjector.Quality quality = dataset.quality();
        require(quality.totalCaseCount() == total, "COVERAGE_INCONSISTENT", "totalCaseCount");
        require("BLOCKED".equals(quality.status()), "COVERAGE_INCONSISTENT", "status");
        require(quality.activeCaseCount() == active, "COVERAGE_INCONSISTENT", "activeCaseCount");
        require(quality.staleCaseCount() == stale, "COVERAGE_INCONSISTENT", "staleCaseCount");
        require(quality.ownerCoveragePercent() == coverage(cases,
                value -> value.owner() == null ? null : value), "COVERAGE_INCONSISTENT", "owner");
        require(quality.sourceCoveragePercent() == coverage(cases,
                value -> value.sourceRef()), "COVERAGE_INCONSISTENT", "source");
        require(quality.oracleCoveragePercent() == coverage(cases,
                value -> value.oracleRef()), "COVERAGE_INCONSISTENT", "oracle");
        require(quality.contractCoveragePercent() == coverage(cases,
                value -> value.applicableContractRefs().isEmpty() ? null : value),
                "COVERAGE_INCONSISTENT", "contract");
        require(quality.behaviorClosurePercent() == coverage(cases, value -> value.behaviorProfiles().stream()
                .anyMatch(profile -> "RUNTIME_CONTROL".equals(profile.purpose())) ? value : null),
                "COVERAGE_INCONSISTENT", "behaviorClosure");
    }

    private CaseProjection caseProjection(
            CapabilityStudioScenarioDatasetProjector.DataCase dataCase,
            CapabilityStudioScenarioDatasetProjector.ExactRef targetRef) {
        List<CapabilityStudioScenarioDatasetProjector.ExactRef> dependencies = dataCase.behaviorProfiles().stream()
                .filter(value -> "RUNTIME_CONTROL".equals(value.purpose()))
                .map(CapabilityStudioScenarioDatasetProjector.BehaviorProfile::dependencyRef)
                .sorted(REF_ORDER)
                .toList();
        Set<RefKey> impacted = new HashSet<>();
        dataCase.applicableContractRefs().forEach(value -> impacted.add(key(value)));
        dependencies.forEach(value -> impacted.add(key(value)));
        impacted.add(key(targetRef));
        return new CaseProjection(
                dataCase.caseRef(),
                dataCase.name(),
                dataCase.lifecycle(),
                dataCase.qualityState(),
                dataCase.owner(),
                dataCase.sourceRef(),
                dataCase.source(),
                dataCase.oracleRef(),
                dataCase.oracle(),
                dataCase.applicableContractRefs().stream().sorted(REF_ORDER).toList(),
                dependencies,
                FRESHNESS_UNVERIFIED,
                MASKING_PAYLOAD_NOT_EXPORTED,
                impacted.size());
    }

    private Admission admission(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset,
            List<CaseProjection> cases) {
        int active = (int) cases.stream().filter(value -> "ACTIVE".equals(value.lifecycle())).count();
        int draft = (int) cases.stream().filter(value -> "DRAFT".equals(value.lifecycle())).count();
        int stale = (int) cases.stream().filter(value -> "STALE".equals(value.lifecycle())).count();
        List<Blocker> blockers = new ArrayList<>();
        if (active == 0) {
            blockers.add(new Blocker("NO_ACTIVE_CASES", "没有可进入运行或发布门禁的 ACTIVE 案例。"));
        }
        blockers.add(new Blocker(
                "FRESHNESS_EVIDENCE_MISSING", "当前 source 模型没有 review timestamp，无法证明测试数据仍然新鲜。"));
        blockers.sort(Comparator.comparing(Blocker::code));
        return new Admission(
                blockers.isEmpty() ? "READY" : "BLOCKED", active, draft, stale, blockers);
    }

    private Quality quality(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset,
            Admission admission) {
        CapabilityStudioScenarioDatasetProjector.Quality source = dataset.quality();
        return new Quality(
                admission.status(),
                source.ownerCoveragePercent(),
                source.sourceCoveragePercent(),
                source.oracleCoveragePercent(),
                source.contractCoveragePercent(),
                source.behaviorClosurePercent(),
                FRESHNESS_UNVERIFIED,
                PAYLOAD_EXPOSURE_NONE,
                MASKING_PAYLOAD_NOT_EXPORTED);
    }

    private ImpactGraph graph(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset,
            List<CaseProjection> cases,
            Map<PackRefKey, String> capabilityLabels,
            Map<PackRefKey, String> contractLabels,
            Admission admission) {
        Map<NodeKey, Node> nodes = new LinkedHashMap<>();
        List<Edge> edges = new ArrayList<>();
        addNode(nodes, node(dataset.datasetRef(), "DATASET", dataset.name(), admission.status()));
        addNode(nodes, node(dataset.targetRef(), "TARGET", dataset.name(), "DRAFT"));
        for (CaseProjection value : cases) {
            addNode(nodes, node(value.caseRef(), "DATA_CASE", value.name(), value.lifecycle()));
            addEdge(edges, dataset.datasetRef(), value.caseRef(), "CONTAINS");
            addNode(nodes, node(value.sourceRef(), "SOURCE", value.source().displayName(), "BLOCKED"));
            addEdge(edges, value.caseRef(), value.sourceRef(), "SOURCED_BY");
            addNode(nodes, node(value.oracleRef(), "ORACLE", value.oracle().displayName(), "DRAFT"));
            addEdge(edges, value.caseRef(), value.oracleRef(), "CHECKED_BY");
            for (CapabilityStudioScenarioDatasetProjector.ExactRef ref : value.contractRefs()) {
                addNode(nodes, node(ref, "CONTRACT", contractLabels.getOrDefault(packKey(ref), ref.id()), "DRAFT"));
                addEdge(edges, value.caseRef(), ref, "VALIDATES");
            }
            for (CapabilityStudioScenarioDatasetProjector.ExactRef ref : value.dependencyRefs()) {
                addNode(nodes, node(ref, "DEPENDENCY", capabilityLabels.getOrDefault(packKey(ref), ref.id()), "DRAFT"));
                addEdge(edges, value.caseRef(), ref, "CONTROLS");
            }
            addEdge(edges, value.caseRef(), dataset.targetRef(), "VALIDATES_TARGET");
        }
        List<Node> orderedNodes = nodes.values().stream()
                .sorted(Comparator.comparing(Node::kind).thenComparing(Node::id))
                .toList();
        List<Edge> orderedEdges = edges.stream()
                .sorted(Comparator.comparing(Edge::source)
                        .thenComparing(Edge::target)
                        .thenComparing(Edge::relation)
                        .thenComparing(Edge::id))
                .toList();
        return new ImpactGraph(orderedNodes, orderedEdges);
    }

    private Summary summary(
            List<CaseProjection> cases,
            ImpactGraph graph,
            CapabilityStudioScenarioDatasetProjector.ExactRef targetRef) {
        Set<RefKey> sources = refs(cases, CaseProjection::sourceRef);
        Set<RefKey> oracles = refs(cases, CaseProjection::oracleRef);
        Set<RefKey> contracts = new HashSet<>();
        Set<RefKey> dependencies = new HashSet<>();
        Set<RefKey> impacted = new HashSet<>();
        for (CaseProjection value : cases) {
            value.contractRefs().forEach(ref -> {
                contracts.add(key(ref));
                impacted.add(key(ref));
            });
            value.dependencyRefs().forEach(ref -> {
                dependencies.add(key(ref));
                impacted.add(key(ref));
            });
        }
        impacted.add(key(targetRef));
        int orphanCases = cases.stream()
                .filter(value -> graph.edges().stream().noneMatch(edge ->
                        "CONTAINS".equals(edge.relation()) && edge.target().equals(nodeId(value.caseRef(), "DATA_CASE"))))
                .mapToInt(value -> 1)
                .sum();
        return new Summary(
                cases.size(),
                sources.size(),
                oracles.size(),
                countNodes(graph, "CONTRACT"),
                dependencies.size(),
                1,
                impacted.size(),
                orphanCases);
    }

    private void validateCardinality(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset,
            List<CaseProjection> cases,
            ImpactGraph graph,
            Summary summary) {
        Set<NodeKey> nodeKeys = new HashSet<>();
        graph.nodes().forEach(node -> {
            require(NODE_STATUSES.contains(node.status()), "NODE_STATUS_INVALID", node.id());
            require(node.id().equals(node.kind() + ":" + node.ref().id()),
                    "NODE_ID_INVALID", node.id());
            require(nodeKeys.add(new NodeKey(node.kind(), node.ref().id())),
                    "DUPLICATE_NODE_IDENTITY", node.id());
        });
        Set<String> nodeIds = graph.nodes().stream().map(Node::id).collect(java.util.stream.Collectors.toSet());
        Set<String> edgeIds = new HashSet<>();
        graph.edges().forEach(edge -> {
            require(edgeIds.add(edge.id()), "DUPLICATE_EDGE_IDENTITY", edge.id());
            require(nodeIds.contains(edge.source()) && nodeIds.contains(edge.target()),
                    "GRAPH_REFERENCE_MISSING", edge.id());
        });
        long caseNodes = graph.nodes().stream().filter(node -> "DATA_CASE".equals(node.kind())).count();
        require(caseNodes == cases.size(), "GRAPH_CARDINALITY_INCONSISTENT", "caseNodes");
        require(summary.caseCount() == cases.size(), "GRAPH_CARDINALITY_INCONSISTENT", "caseCount");
        require(summary.sourceCount() == countNodes(graph, "SOURCE"),
                "GRAPH_CARDINALITY_INCONSISTENT", "sourceCount");
        require(summary.oracleCount() == countNodes(graph, "ORACLE"),
                "GRAPH_CARDINALITY_INCONSISTENT", "oracleCount");
        require(summary.contractCount() == countNodes(graph, "CONTRACT"),
                "GRAPH_CARDINALITY_INCONSISTENT", "contractCount");
        require(summary.dependencyCount() == countNodes(graph, "DEPENDENCY"),
                "GRAPH_CARDINALITY_INCONSISTENT", "dependencyCount");
        require(summary.targetCount() == countNodes(graph, "TARGET"),
                "GRAPH_CARDINALITY_INCONSISTENT", "targetCount");
        require(summary.orphanCaseCount() == 0, "ORPHAN_CASE", "case is not contained by dataset");
        require(graph.nodes().stream().anyMatch(node -> "DATASET".equals(node.kind())
                        && dataset.datasetRef().equals(node.ref())),
                "GRAPH_CARDINALITY_INCONSISTENT", "dataset node");
    }

    private Map<PackRefKey, String> capabilityLabels(CapabilityStudioGoldenDemoPack pack) {
        Map<PackRefKey, String> labels = new HashMap<>();
        List<CapabilityStudioGoldenDemoPack.Capability> capabilities = new ArrayList<>();
        capabilities.addAll(pack.apiCapabilities());
        capabilities.addAll(pack.featureCapabilities());
        capabilities.addAll(pack.toolCapabilities());
        capabilities.stream().sorted(Comparator.comparing(CapabilityStudioGoldenDemoPack.Capability::id))
                .forEach(capability -> labels.putIfAbsent(packKey(capability.ref()), capability.name()));
        return labels;
    }

    private Map<PackRefKey, String> contractLabels(CapabilityStudioGoldenDemoPack pack) {
        Map<PackRefKey, String> labels = new HashMap<>();
        List<CapabilityStudioGoldenDemoPack.Capability> capabilities = new ArrayList<>();
        capabilities.addAll(pack.apiCapabilities());
        capabilities.addAll(pack.featureCapabilities());
        capabilities.addAll(pack.toolCapabilities());
        capabilities.stream().sorted(Comparator.comparing(CapabilityStudioGoldenDemoPack.Capability::name))
                .forEach(capability -> labels.putIfAbsent(packKey(capability.contractRef()), capability.name()));
        return labels;
    }

    private Set<PackRefKey> authoritativeRefs(CapabilityStudioGoldenDemoPack pack) {
        Set<PackRefKey> refs = new HashSet<>();
        List<CapabilityStudioGoldenDemoPack.Capability> capabilities = new ArrayList<>();
        capabilities.addAll(pack.apiCapabilities());
        capabilities.addAll(pack.featureCapabilities());
        capabilities.addAll(pack.toolCapabilities());
        capabilities.forEach(capability -> {
            refs.add(packKey(capability.ref()));
            refs.add(packKey(capability.contractRef()));
            capability.dependencyRefs().forEach(ref -> refs.add(packKey(ref)));
        });
        pack.scenarios().forEach(scenario -> {
            refs.add(packKey(scenario.ref()));
            refs.add(packKey(scenario.sourceRef()));
            refs.add(packKey(scenario.oracleRef()));
            refs.add(packKey(scenario.contractRef()));
            scenario.applicableContractRefs().forEach(ref -> refs.add(packKey(ref)));
        });
        return refs;
    }

    private static Node node(
            CapabilityStudioScenarioDatasetProjector.ExactRef ref,
            String kind,
            String label,
            String status) {
        return new Node(nodeId(ref, kind), kind, label, ref, status);
    }

    private static void addNode(Map<NodeKey, Node> nodes, Node node) {
        NodeKey key = new NodeKey(node.kind(), node.ref().id());
        Node previous = nodes.putIfAbsent(key, node);
        require(previous == null || previous.equals(node), "DUPLICATE_NODE_IDENTITY", node.id());
    }

    private static void addEdge(
            List<Edge> edges,
            CapabilityStudioScenarioDatasetProjector.ExactRef source,
            CapabilityStudioScenarioDatasetProjector.ExactRef target,
            String relation) {
        String sourceId = nodeId(source, nodeKind(source));
        String targetId = nodeId(target, nodeKind(target));
        edges.add(new Edge(sourceId + "->" + relation + "->" + targetId,
                sourceId, targetId, relation));
    }

    private static String nodeKind(CapabilityStudioScenarioDatasetProjector.ExactRef ref) {
        return switch (ref.kind()) {
            case "DATASET", "DATA_CASE", "SOURCE", "ORACLE", "CONTRACT", "BEHAVIOR_PROFILE" -> ref.kind();
            case "API" -> "DEPENDENCY";
            case "TOOL", "FEATURE" -> "TARGET";
            default -> throw new IllegalStateException("Unsupported graph ref kind: " + ref.kind());
        };
    }

    private static String nodeId(
            CapabilityStudioScenarioDatasetProjector.ExactRef ref,
            String kind) {
        return kind + ":" + ref.id();
    }

    private static int countNodes(ImpactGraph graph, String kind) {
        return (int) graph.nodes().stream().filter(node -> kind.equals(node.kind())).count();
    }

    private static Set<RefKey> refs(
            List<CaseProjection> cases,
            Function<CaseProjection, CapabilityStudioScenarioDatasetProjector.ExactRef> selector) {
        Set<RefKey> refs = new HashSet<>();
        cases.forEach(value -> refs.add(key(selector.apply(value))));
        return refs;
    }

    private static boolean containsRef(
            List<CapabilityStudioScenarioDatasetProjector.ExactRef> refs,
            CapabilityStudioScenarioDatasetProjector.ExactRef candidate) {
        return refs.stream().anyMatch(ref -> key(ref).equals(key(candidate)));
    }

    private static void requireUnique(
            List<CapabilityStudioScenarioDatasetProjector.ExactRef> refs,
            String path) {
        Set<RefKey> identities = new HashSet<>();
        for (CapabilityStudioScenarioDatasetProjector.ExactRef ref : refs) {
            require(identities.add(key(ref)), "DUPLICATE_REF_IDENTITY", path);
        }
    }

    private static void requireRef(
            CapabilityStudioScenarioDatasetProjector.ExactRef ref,
            String path,
            String expectedKind) {
        require(ref != null, "REF_MISSING", path);
        if (ref == null) {
            return;
        }
        require(expectedKind == null || expectedKind.equals(ref.kind()), "REF_KIND_INVALID", path);
        require(ref.id() != null && !ref.id().isBlank(), "REF_ID_MISSING", path);
        require(ref.revision() > 0, "REF_REVISION_INVALID", path);
        require(ref.fingerprint() != null && FINGERPRINT.matcher(ref.fingerprint()).matches(),
                "REF_FINGERPRINT_INVALID", path);
        require(ref.authority() != null && !ref.authority().isBlank(), "REF_AUTHORITY_MISSING", path);
        CapabilityStudioScenarioDatasetProjector.Scope scope = ref.scope();
        require(scope != null && present(scope.tenantId()) && present(scope.organizationId())
                        && present(scope.projectId()) && present(scope.environmentId()) && present(scope.region()),
                "REF_SCOPE_MISSING", path);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static int count(
            List<CapabilityStudioScenarioDatasetProjector.DataCase> cases,
            Function<CapabilityStudioScenarioDatasetProjector.DataCase, Boolean> predicate) {
        return (int) cases.stream().filter(value -> Boolean.TRUE.equals(predicate.apply(value))).count();
    }

    private static int coverage(
            List<CapabilityStudioScenarioDatasetProjector.DataCase> cases,
            Function<CapabilityStudioScenarioDatasetProjector.DataCase, Object> selector) {
        if (cases.isEmpty()) {
            return 0;
        }
        long covered = cases.stream().filter(value -> selector.apply(value) != null).count();
        return (int) Math.round(covered * 100.0 / cases.size());
    }

    private String fingerprint(ScenarioQualityImpactProjection material) {
        try {
            ObjectNode tree = mapper.valueToTree(material);
            tree.putNull("projectionFingerprint");
            byte[] canonical = FINGERPRINT_JSON.writeValueAsBytes(canonical(tree));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            StringBuilder result = new StringBuilder("sha256:");
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (IOException | NoSuchAlgorithmException | ClassCastException failure) {
            throw new IllegalStateException("Unable to fingerprint Scenario Quality Impact projection", failure);
        }
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = FINGERPRINT_JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> sorted.set(name, canonical(value.get(name))));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = FINGERPRINT_JSON.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return value.deepCopy();
    }

    private static RefKey key(CapabilityStudioScenarioDatasetProjector.ExactRef ref) {
        require(ref != null, "REF_MISSING", "identity");
        return new RefKey(ref.kind(), ref.id(), ref.revision(), ref.fingerprint(),
                ref.authority(), ref.scope());
    }

    private static PackRefKey packKey(CapabilityStudioScenarioDatasetProjector.ExactRef ref) {
        require(ref != null, "REF_MISSING", "identity");
        return new PackRefKey(ref.kind(), ref.id(), ref.revision(), ref.fingerprint());
    }

    private static PackRefKey packKey(CapabilityStudioGoldenDemoPack.ExactRef ref) {
        require(ref != null, "REF_MISSING", "identity");
        return new PackRefKey(ref.kind(), ref.id(), ref.revision(), ref.fingerprint());
    }

    private static void require(boolean condition, String code, String detail) {
        if (!condition) {
            throw new IllegalStateException("RG.CAPABILITY_STUDIO.SCENARIO_QUALITY_" + code + ": " + detail);
        }
    }

    private static final Comparator<CapabilityStudioScenarioDatasetProjector.ExactRef> REF_ORDER =
            Comparator.comparing(CapabilityStudioScenarioDatasetProjector.ExactRef::kind)
                    .thenComparing(CapabilityStudioScenarioDatasetProjector.ExactRef::id)
                    .thenComparingInt(CapabilityStudioScenarioDatasetProjector.ExactRef::revision)
                    .thenComparing(CapabilityStudioScenarioDatasetProjector.ExactRef::fingerprint);

    private record RefKey(
            String kind,
            String id,
            int revision,
            String fingerprint,
            String authority,
            CapabilityStudioScenarioDatasetProjector.Scope scope) {
    }

    private record PackRefKey(String kind, String id, int revision, String fingerprint) {
    }

    private record NodeKey(String kind, String id) {
    }

    public record ScenarioQualityImpactProjection(
            String schemaVersion,
            CapabilityStudioScenarioDatasetProjector.ExactRef datasetRef,
            CapabilityStudioScenarioDatasetProjector.ExactRef targetRef,
            String projectionFingerprint,
            Admission admission,
            Quality quality,
            Summary summary,
            List<CaseProjection> cases,
            ImpactGraph impactGraph) {
        public ScenarioQualityImpactProjection {
            cases = List.copyOf(cases);
        }
    }

    public record Admission(
            String status,
            int activeCaseCount,
            int draftCaseCount,
            int staleCaseCount,
            List<Blocker> blockers) {
        public Admission {
            blockers = List.copyOf(blockers);
        }
    }

    public record Blocker(String code, String message) {
    }

    public record Quality(
            String status,
            int ownerCoveragePercent,
            int sourceCoveragePercent,
            int oracleCoveragePercent,
            int contractCoveragePercent,
            int behaviorClosurePercent,
            String freshnessStatus,
            String payloadExposure,
            String maskingStatus) {
    }

    public record Summary(
            int caseCount,
            int sourceCount,
            int oracleCount,
            int contractCount,
            int dependencyCount,
            int targetCount,
            int impactedAssetCount,
            int orphanCaseCount) {
    }

    public record CaseProjection(
            CapabilityStudioScenarioDatasetProjector.ExactRef caseRef,
            String name,
            String lifecycle,
            String qualityState,
            CapabilityStudioScenarioDatasetProjector.Owner owner,
            CapabilityStudioScenarioDatasetProjector.ExactRef sourceRef,
            CapabilityStudioScenarioDatasetProjector.Source source,
            CapabilityStudioScenarioDatasetProjector.ExactRef oracleRef,
            CapabilityStudioScenarioDatasetProjector.Oracle oracle,
            List<CapabilityStudioScenarioDatasetProjector.ExactRef> contractRefs,
            List<CapabilityStudioScenarioDatasetProjector.ExactRef> dependencyRefs,
            String freshnessStatus,
            String maskingStatus,
            int impactedAssetCount) {
        public CaseProjection {
            contractRefs = List.copyOf(contractRefs);
            dependencyRefs = List.copyOf(dependencyRefs);
        }
    }

    public record ImpactGraph(List<Node> nodes, List<Edge> edges) {
        public ImpactGraph {
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
        }
    }

    public record Node(
            String id,
            String kind,
            String label,
            CapabilityStudioScenarioDatasetProjector.ExactRef ref,
            String status) {
    }

    public record Edge(String id, String source, String target, String relation) {
    }
}
