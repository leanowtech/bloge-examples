package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.agenttdd.InMemoryAgentTddStateRepository;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.importer.DslImportService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that a Solution contract lowers through the production BLOGE projection boundary. */
class SolutionLoweringTest {

    @Test
    void lowersFeatureValueInputsToScenarioThenInstructionCalls() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SolutionEntityRegistry registry = new SolutionEntityRegistry(
                new InMemoryAgentTddStateRepository(), mapper);
        registry.upsertFeature(SCOPE, new FeatureContract(
                "responsibility.party", mapper.valueToTree(Map.of("type", "string")),
                FeatureContract.EvaluationKind.API, FeatureContract.Determinism.DETERMINISTIC,
                mapper.valueToTree(Map.of("orderId", "string")), "resource:party#$.value", "", ""));
        VisualOperatorCatalog catalog = catalog(SolutionOperatorDefinitions.all());
        SolutionLowering lowering = new SolutionLowering(
                registry, new DslImportService(catalog, new OperatorLibraryValidator()));
        SolutionContract solution = new SolutionContract(
                "sol:cancel-dispute", "Resolve cancellation disputes.",
                Map.of("party", "responsibility.party"), "scn:root",
                List.of("ins:uphold"), "caseSet:cancel");

        SolutionLowering.LoweredSolution lowered = lowering.lower(SCOPE, solution);

        assertThat(lowered.precompiled()).isTrue();
        assertThat(lowered.dsl()).contains("graph sol_cancel_dispute")
                .contains("node decide : \"bloge:scenarioCall\"")
                .contains("node dispatch : \"bloge:instructionCall\"")
                .doesNotContain("httpResource", "featureCall");
        assertThat(lowered.draft().nodes()).extracting(node -> node.operatorRef())
                .containsExactly("bloge:scenarioCall", "bloge:instructionCall");
        assertThat(lowered.diagnostics()).noneMatch(VisualDiagnostic::error);
    }

    private static VisualOperatorCatalog catalog(List<OperatorDefinition> definitions) {
        Map<String, OperatorDefinition> byRef = definitions.stream().collect(
                java.util.stream.Collectors.toMap(OperatorDefinition::operatorRef, value -> value));
        return new VisualOperatorCatalog() {
            @Override
            public List<OperatorDefinition> list(OperatorCatalogQuery query) {
                return definitions;
            }

            @Override
            public Optional<OperatorDefinition> find(String operatorRef) {
                return Optional.ofNullable(byRef.get(operatorRef));
            }
        };
    }

    private static final String SCOPE = "tenant-a/project-a/test";
}
