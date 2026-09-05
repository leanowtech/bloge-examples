package com.leanowtech.bloge.gateway.agenttdd;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Projects catalog contracts into the business-readable first act of the Agent TDD journey. */
@Service
public final class AgentTddLibraryOverviewService {
    private static final Map<String, String> BASE_TITLES = Map.of(
            "httpResource", "调用一个外部服务",
            "bloge:decisionTable", "按规则表判定",
            "bloge:transform", "整理并输出数据");
    private final VisualOperatorCatalog catalog;
    private final FixtureAssetRepository fixtures;

    /** Creates the projection over the authoritative operator catalog without a Fixture listing. */
    public AgentTddLibraryOverviewService(VisualOperatorCatalog catalog) {
        this(catalog, (FixtureAssetRepository) null);
    }

    /** Creates the Spring projection with the optional payload-free Fixture catalog. */
    @Autowired
    public AgentTddLibraryOverviewService(VisualOperatorCatalog catalog,
                                          ObjectProvider<FixtureAssetRepository> fixtureProvider) {
        this(catalog, fixtureProvider.getIfAvailable());
    }

    /** Package-visible constructor used to verify an exact persistence boundary. */
    AgentTddLibraryOverviewService(VisualOperatorCatalog catalog, FixtureAssetRepository fixtures) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.fixtures = fixtures;
    }

    /**
     * Returns platform building blocks and the imported business world model without payload data.
     *
     * <p>Types are derived only from declared output schemas. The response never samples fixtures,
     * provider responses, or draft execution output, so the read remains safe for the board.</p>
     */
    public Map<String, Object> overview(IntegrationRequestContext identity) {
        return overview(identity, true);
    }

    /**
     * Returns a scoped, payload-free business library snapshot for an MCP caller.
     *
     * @param identity authenticated tenant, project and environment scope
     * @param includeSamples whether governed sample descriptors should be included
     * @return sorted business building blocks, world model, optional sample descriptors and a
     *         fingerprint bound to the exact scope and catalog projection
     */
    public Map<String, Object> overview(IntegrationRequestContext identity, boolean includeSamples) {
        Objects.requireNonNull(identity, "identity");
        List<OperatorDefinition> operators = catalog.list(new OperatorCatalogQuery(
                "", List.of(), false, false,
                identity.tenantId(), identity.projectId(), identity.environmentId()));
        List<Map<String, Object>> buildingBlocks = new ArrayList<>();
        List<Map<String, Object>> operations = new ArrayList<>();
        Map<String, Map<String, Object>> types = new LinkedHashMap<>();
        operators.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(OperatorDefinition::operatorRef))
                .forEach(operator -> {
                    if (BASE_TITLES.containsKey(operator.operatorRef())) {
                        buildingBlocks.add(baseBlock(operator));
                    }
                    if (operator.source().libraryId().isBlank()) {
                        return;
                    }
                    boolean bound = !designOnly(operator);
                    buildingBlocks.add(libraryBlock(operator, bound));
                    operations.add(operation(operator, bound));
                    operator.ports().outputs().stream()
                            .filter(Objects::nonNull)
                            .filter(port -> !port.schema().properties().isEmpty())
                            .forEach(port -> {
                                String name = operator.operatorRef() + "." + port.name();
                                types.putIfAbsent(name, Map.of(
                                        "name", name,
                                        "fields", port.schema().properties().keySet().stream().sorted().toList()));
                            });
                });
        List<Map<String, Object>> frozenBlocks = List.copyOf(buildingBlocks);
        Map<String, Object> worldModel = Map.of(
                "types", List.copyOf(types.values()),
                "operations", List.copyOf(operations));
        Map<String, Object> patterns = Map.copyOf(authoringPatterns());
        Map<String, Object> fingerprintMaterial = Map.of(
                "scope", List.of(identity.tenantId(), identity.projectId(), identity.environmentId()),
                "buildingBlocks", frozenBlocks,
                "authoringPatterns", patterns,
                "worldModel", worldModel);
        return Map.of(
                "buildingBlocks", frozenBlocks,
                "authoringPatterns", patterns,
                "authoringPatternsFingerprint", VisualBundleFingerprint.fromMaterial(patterns),
                "samples", includeSamples ? samples(identity) : List.of(),
                "worldModel", worldModel,
                "snapshotFingerprint", VisualBundleFingerprint.fromMaterial(fingerprintMaterial));
    }

    /**
     * Returns compact machine guidance for the coding Agent that translates business intent into
     * four entity YAML contracts. The guidance is not business output and contains no runtime refs.
     */
    private static Map<String, String> authoringPatterns() {
        return Map.of(
                "featureYaml", """
                        fact.example:
                          output: { type: { enum: [VALUE_A, VALUE_B] } }
                          evaluationKind: USER_CONVERSATION
                          determinism: INTERACTIVE
                          inputs: {}
                          promptRef: prompt:fact-example
                          businessSemantics: Ask the responsible business person for this fact.
                          businessDefinition:
                            semanticKey: example.fact
                            intent: State the business question answered by this fact.
                            domain: example-domain
                            businessObject: example-object
                            requiredContext: []
                            resultDomain: { type: enum, values: [VALUE_A, VALUE_B] }
                            asOf: CURRENT
                            unknownPolicy: REQUIRE_HUMAN_REVIEW
                            acquisitionOwner: USER
                            freshness: { mode: CURRENT }
                            effect: PURE
                        """,
                "scenarioYaml", """
                        scn:example:
                          inputs: [fact]
                          hitPolicy: unique
                          rules:
                            - ruleId: R1
                              when: { fact: { eq: VALUE_A } }
                              outlet: { kind: INSTRUCTION, ref: 'ins:action-a', bind: { fact: fact } }
                          otherwise: { kind: INSTRUCTION, ref: 'ins:manual-review', bind: { fact: fact } }
                          businessDefinition:
                            semanticKey: example.decision
                            intent: Decide the business outcome from the governed facts.
                            domain: example-domain
                            businessObject: example-object
                            inputFactKeys: [example.fact]
                            decisionPolicy: UNIQUE
                            outletSemanticKeys: [example.action-a, example.manual-review]
                            otherwisePolicy: EXPLICIT_FALLBACK
                        """,
                "instructionYaml", """
                        ins:action-a:
                          inputs: { fact: string }
                          output:
                            result: { type: { fields: { disposition: string } } }
                            reasoning: required
                          effect: READ
                          businessSemantics: State the business disposition performed by this action.
                          businessDefinition:
                            semanticKey: example.action-a
                            intent: Apply the selected business disposition.
                            domain: example-domain
                            businessObject: example-object
                            requiredFactKeys: [example.fact]
                            resultDomain: { type: object, disposition: string }
                            reasoningPolicy: REQUIRED
                            effect: READ
                            failurePolicy: ESCALATE
                            writeGovernanceClass: NONE
                        """,
                "solutionYaml", """
                        sol:example:
                          problem: State the business problem this solution resolves.
                          inputs: { fact: fact.example }
                          scenarioTree: { root: 'scn:example' }
                          instructions: ['ins:action-a', 'ins:manual-review']
                          golden: 'caseSet:example'
                          businessDefinition:
                            semanticKey: example.solution
                            intent: Resolve the example business problem.
                            domain: example-domain
                            businessObject: example-object
                            problemClass: DECISION_AND_DISPOSITION
                            requiredFactKeys: [example.fact]
                            scenarioSemanticKey: example.decision
                            dispositionSemanticKeys: [example.action-a, example.manual-review]
                            runtimeUse: GOVERNED_DECISION
                        """);
    }

    private List<Map<String, Object>> samples(IntegrationRequestContext identity) {
        if (fixtures == null) return List.of();
        EnterpriseScope scope = new EnterpriseScope(
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.region());
        return fixtures.listHeads(scope, false, 100, 0).stream()
                .filter(Objects::nonNull)
                .map(stored -> stored.descriptor())
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(descriptor -> descriptor.fixtureAssetId()))
                .map(descriptor -> Map.<String, Object>of(
                        "fixtureId", descriptor.fixtureAssetId(),
                        "lifecycle", descriptor.lifecycle().name(),
                        "sourceKind", descriptor.source().kind().name(),
                        "outputPort", descriptor.variantKey()))
                .toList();
    }

    private static Map<String, Object> baseBlock(OperatorDefinition operator) {
        return Map.of(
                "ref", operator.operatorRef(),
                "kind", "BASE",
                "title", BASE_TITLES.get(operator.operatorRef()),
                "effect", operator.capabilities().effect());
    }

    private static Map<String, Object> libraryBlock(OperatorDefinition operator, boolean bound) {
        return Map.of(
                "ref", operator.operatorRef(),
                "kind", "LIBRARY",
                "title", operator.display().name(),
                "effect", operator.capabilities().effect(),
                "bound", bound);
    }

    private static Map<String, Object> operation(OperatorDefinition operator, boolean bound) {
        return Map.of(
                "ref", operator.operatorRef(),
                "title", operator.display().name(),
                "inputs", portNames(operator.ports().inputs()),
                "outputs", portNames(operator.ports().outputs()),
                "bound", bound);
    }

    private static List<String> portNames(List<OperatorDefinition.Port> ports) {
        return ports.stream().filter(Objects::nonNull).map(OperatorDefinition.Port::name).sorted().toList();
    }

    private static boolean designOnly(OperatorDefinition operator) {
        if (operator.source().libraryId().isBlank()) {
            return "design".equals(operator.lowering().mode()) || !operator.runtimeReadiness().executable();
        }
        Object binding = operator.lowering().parameters().get("bindingRef");
        return !(binding instanceof String value) || value.isBlank();
    }
}
