package com.leanowtech.bloge.gateway.testing.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.evidence.GraphArtifactFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventoryBuilder;
import com.leanowtech.bloge.gateway.testing.world.access.ResolvedWorldAssetControl;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogKind;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedResourceRef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Pure application planner for an authorized Scenario or ResourceWorldModel reference. */
public final class WorldReferenceExecutionPlanner {
    private static final String LOGICAL_CONTRACT_TAG_PREFIX = WorldScenarioCompiler.LOGICAL_CONTRACT_TAG_PREFIX;

    private final ObjectMapper objectMapper;
    private final OperatorRegistry registry;
    private final WorldSliceSelectionResolver selectionResolver;
    private final WorldScenarioCompiler compiler;

    public WorldReferenceExecutionPlanner(ObjectMapper objectMapper, OperatorRegistry registry) {
        this(objectMapper, registry, new WorldSliceSelectionResolver(), new WorldScenarioCompiler());
    }

    WorldReferenceExecutionPlanner(ObjectMapper objectMapper,
                                   OperatorRegistry registry,
                                   WorldSliceSelectionResolver selectionResolver,
                                   WorldScenarioCompiler compiler) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.selectionResolver = Objects.requireNonNull(selectionResolver, "selectionResolver");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
    }

    /** Plans the exact authorized reference without reading or introducing runtime state. */
    public Plan plan(ResolvedWorldAssetControl authorized,
                     Graph graph,
                     Map<String, Object> businessContext) {
        if (authorized == null || graph == null || businessContext == null) {
            throw failure(WorldScenarioCompilationException.Code.INVALID_INPUT);
        }
        GovernedResourceRef primaryRef = authorized.primaryRef();
        ResourceWorldModel world = authorized.worldModel();
        Optional<Scenario> suppliedScenario = authorized.scenario();
        if (primaryRef == null || world == null || suppliedScenario == null) {
            throw failure(WorldScenarioCompilationException.Code.INVALID_INPUT);
        }

        String graphFingerprint = graphFingerprint(graph);
        Scenario effectiveScenario;
        ProvenanceKind provenance;
        if (primaryRef.kind() == GovernedCatalogKind.SCENARIO) {
            Scenario scenario = suppliedScenario.orElseThrow(() ->
                    failure(WorldScenarioCompilationException.Code.INVALID_INPUT));
            validateScenarioReference(primaryRef, scenario, world, graph, graphFingerprint, businessContext);
            effectiveScenario = scenario;
            provenance = ProvenanceKind.SCENARIO;
        } else if (primaryRef.kind() == GovernedCatalogKind.RESOURCE_WORLD_MODEL) {
            if (suppliedScenario.isPresent() || !sameWorld(primaryRef, world)) {
                throw failure(WorldScenarioCompilationException.Code.INVALID_INPUT);
            }
            effectiveScenario = deriveWorldScenario(graph, graphFingerprint, world, businessContext);
            provenance = ProvenanceKind.RESOURCE_WORLD_MODEL;
        } else {
            throw failure(WorldScenarioCompilationException.Code.INVALID_INPUT);
        }

        Map<String, WorldSliceSelection> selections = selectionResolver.resolve(effectiveScenario, world);
        WorldScenarioCompilation compilation = compiler.compile(
                effectiveScenario, world, graph, registry, selections);
        return new Plan(primaryRef, effectiveScenario, world, compilation, provenance);
    }

    private String graphFingerprint(Graph graph) {
        try {
            return GraphArtifactFingerprint.of(objectMapper, graph);
        } catch (RuntimeException failure) {
            throw failure(WorldScenarioCompilationException.Code.TARGET_DRIFT);
        }
    }

    private void validateScenarioReference(GovernedResourceRef primaryRef,
                                           Scenario scenario,
                                           ResourceWorldModel world,
                                           Graph graph,
                                           String graphFingerprint,
                                           Map<String, Object> businessContext) {
        if (primaryRef.kind() != GovernedCatalogKind.SCENARIO
                || !primaryRef.tenantId().equals(scenario.tenantId())
                || !primaryRef.id().equals(scenario.scenarioId())
                || primaryRef.revision() != scenario.revision()
                || !primaryRef.fingerprint().equals(scenario.fingerprint())
                || !sameWorld(scenario.world(), world)
                || !"GRAPH".equals(scenario.target().kind())
                || !graph.name().equals(scenario.target().id())
                || !graphFingerprint.equals(scenario.target().fingerprint())
                || !deepEquals(scenario.context(), businessContext)) {
            throw failure(WorldScenarioCompilationException.Code.TARGET_DRIFT);
        }
    }

    private Scenario deriveWorldScenario(Graph graph,
                                         String graphFingerprint,
                                         ResourceWorldModel world,
                                         Map<String, Object> businessContext) {
        Map<String, String> contracts = contractFingerprints(graph, graphFingerprint);
        List<Scenario.ContractDependency> dependencies = contracts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new Scenario.ContractDependency(entry.getKey(), entry.getValue()))
                .toList();
        try {
            return new Scenario(
                    worldScenarioId(world, graph), world.tenantId(), world.revision(),
                    new Scenario.TargetRef("GRAPH", graph.name(), graphFingerprint),
                    world, businessContext, Scenario.WorldStateInit.EMPTY, List.of(), dependencies);
        } catch (WorldScenarioCompilationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failure(WorldScenarioCompilationException.Code.INVALID_INPUT);
        }
    }

    private Map<String, String> contractFingerprints(Graph graph, String graphFingerprint) {
        InvocationInventory inventory;
        try {
            inventory = new InvocationInventoryBuilder(registry).build(graph, graphFingerprint);
        } catch (RuntimeException failure) {
            throw failure(WorldScenarioCompilationException.Code.INVOCATION_INVENTORY);
        }

        Map<String, String> contracts = new TreeMap<>();
        for (InvocationInventory.Entry entry : inventory.entries()) {
            InvocationSite.InvocationKind kind = entry.site().invocationKind();
            if (kind != InvocationSite.InvocationKind.PRIMARY
                    && kind != InvocationSite.InvocationKind.RESOURCE) {
                continue;
            }
            List<String> tags = logicalContractTags(entry.node().metadata().attributes().get("tags"));
            if (tags.size() > 1) {
                throw failure(WorldScenarioCompilationException.Code.MULTIPLE_CONTRACT_TAGS);
            }
            if (tags.isEmpty()) {
                continue;
            }
            WorldScenarioContractTagCodec.Decoded decoded =
                    WorldScenarioCompiler.decodeLogicalContractTag(tags.getFirst());
            String prior = contracts.putIfAbsent(decoded.contractId(), decoded.contractFingerprint());
            if (prior != null && !prior.equals(decoded.contractFingerprint())) {
                throw failure(WorldScenarioCompilationException.Code.CONTRACT_DRIFT);
            }
        }
        if (contracts.isEmpty()) {
            throw failure(WorldScenarioCompilationException.Code.CONTRACT_NOT_DECLARED);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(contracts));
    }

    private static List<String> logicalContractTags(String rawTags) {
        if (rawTags == null || rawTags.isBlank()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (String raw : rawTags.split(",")) {
            String tag = raw.trim();
            if (tag.startsWith(LOGICAL_CONTRACT_TAG_PREFIX)) {
                tags.add(tag);
            }
        }
        return List.copyOf(tags);
    }

    private static String worldScenarioId(ResourceWorldModel world, Graph graph) {
        return "world-reference:" + world.tenantId() + ":" + world.worldModelId()
                + "@" + world.revision() + "@" + world.fingerprint() + ":" + graph.name();
    }

    private boolean deepEquals(Object left, Object right) {
        try {
            JsonNode leftNode = objectMapper.valueToTree(left);
            JsonNode rightNode = objectMapper.valueToTree(right);
            return Objects.equals(leftNode, rightNode);
        } catch (RuntimeException failure) {
            throw failure(WorldScenarioCompilationException.Code.INVALID_INPUT);
        }
    }

    private static boolean sameWorld(GovernedResourceRef reference, ResourceWorldModel world) {
        return reference.kind() == GovernedCatalogKind.RESOURCE_WORLD_MODEL
                && reference.tenantId().equals(world.tenantId())
                && reference.id().equals(world.worldModelId())
                && reference.revision() == world.revision()
                && reference.fingerprint().equals(world.fingerprint());
    }

    private static boolean sameWorld(Scenario.WorldModelRef reference, ResourceWorldModel world) {
        return reference != null
                && reference.worldModelId().equals(world.worldModelId())
                && reference.revision() == world.revision()
                && reference.fingerprint().equals(world.fingerprint());
    }

    private static WorldScenarioCompilationException failure(
            WorldScenarioCompilationException.Code code) {
        return new WorldScenarioCompilationException(code);
    }

    public enum ProvenanceKind {
        SCENARIO,
        RESOURCE_WORLD_MODEL
    }

    /** Immutable application output; its string form contains identities, never business payload. */
    public record Plan(
            GovernedResourceRef primaryRef,
            Scenario effectiveScenario,
            ResourceWorldModel worldModel,
            WorldScenarioCompilation compilation,
            ProvenanceKind provenance
    ) {
        public Plan {
            if (primaryRef == null || effectiveScenario == null || worldModel == null
                    || compilation == null || provenance == null) {
                throw failure(WorldScenarioCompilationException.Code.INVALID_INPUT);
            }
            if (!primaryRef.tenantId().equals(worldModel.tenantId())
                    || !sameWorld(effectiveScenario.world(), worldModel)
                    || !"GRAPH".equals(effectiveScenario.target().kind())
                    || !compilation.fingerprintMatches()
                    || !effectiveScenario.target().fingerprint()
                    .equals(compilation.bundle().targetFingerprint())) {
                throw failure(WorldScenarioCompilationException.Code.INVALID_INPUT);
            }
            if (provenance == ProvenanceKind.SCENARIO) {
                if (primaryRef.kind() != GovernedCatalogKind.SCENARIO
                        || !primaryRef.id().equals(effectiveScenario.scenarioId())
                        || primaryRef.revision() != effectiveScenario.revision()
                        || !primaryRef.fingerprint().equals(effectiveScenario.fingerprint())) {
                    throw failure(WorldScenarioCompilationException.Code.INVALID_INPUT);
                }
            } else if (primaryRef.kind() != GovernedCatalogKind.RESOURCE_WORLD_MODEL
                    || !sameWorld(primaryRef, worldModel)) {
                throw failure(WorldScenarioCompilationException.Code.INVALID_INPUT);
            }
        }

        @Override
        public String toString() {
            return "Plan{primaryKind=" + primaryRef.kind()
                    + ", primaryRevision=" + primaryRef.revision()
                    + ", compilationFingerprint='" + compilation.fingerprint() + '\''
                    + ", provenance=" + provenance + "}";
        }
    }
}
