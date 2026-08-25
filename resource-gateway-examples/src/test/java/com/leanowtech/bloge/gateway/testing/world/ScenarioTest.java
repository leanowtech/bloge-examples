package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.confirmed;
import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.descriptor;
import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.objectSchema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioTest {
    private static final String TARGET_FINGERPRINT = "sha256:" + "a".repeat(64);

    @Test
    void fingerprintIsStableAcrossTwentyRebuilds() {
        Scenario first = scenario(world("provider-a", "v1"), context("alpha", "beta"));

        for (int attempt = 0; attempt < 20; attempt++) {
            assertThat(scenario(world("provider-a", "v1"), context("alpha", "beta"))
                    .fingerprint()).isEqualTo(first.fingerprint());
        }
    }

    @Test
    void normalizesMapOrderAndExpectationSetOrder() {
        ResourceWorldModel model = world("provider-a", "v1");
        Map<String, Object> firstContext = new LinkedHashMap<>();
        firstContext.put("alpha", 1);
        firstContext.put("beta", Map.of("ready", true));
        Map<String, Object> secondContext = new LinkedHashMap<>();
        secondContext.put("beta", Map.of("ready", true));
        secondContext.put("alpha", 1);
        Scenario left = new Scenario("scenario-a", "tenant-a", 1, target(),
                model, firstContext, Scenario.WorldStateInit.EMPTY,
                List.of(expectation("/z", 2), expectation("/a", 1)), List.of());
        Scenario right = new Scenario("scenario-a", "tenant-a", 1, target(),
                model, secondContext, Scenario.WorldStateInit.EMPTY,
                List.of(expectation("/a", 1), expectation("/z", 2)), List.of());

        assertThat(left.fingerprint()).isEqualTo(right.fingerprint());
        assertThat(left.expect()).extracting(Scenario.Expectation::path)
                .containsExactly("/a", "/z");
    }

    @Test
    void deeplyCopiesContextExpectedAndInputCollections() {
        ResourceWorldModel model = world("provider-a", "v1");
        List<Object> items = new ArrayList<>(List.of("before"));
        Map<String, Object> nested = new LinkedHashMap<>(Map.of("items", items));
        Map<String, Object> context = new LinkedHashMap<>(Map.of("nested", nested));
        Map<String, Object> expected = new LinkedHashMap<>(Map.of("items", items));
        Scenario scenario = new Scenario("scenario-a", "tenant-a", 1, target(), model,
                context, Scenario.WorldStateInit.EMPTY,
                List.of(new Scenario.Expectation("OUTPUT_PATH", "", "/result", "EQUALS",
                        expected, null)), List.of());

        items.add("after");
        nested.put("late", true);
        expected.put("late", true);

        assertThat(scenario.context()).isEqualTo(Map.of("nested", Map.of("items", List.of("before"))));
        assertThat(scenario.expect().getFirst().expected()).isEqualTo(
                Map.of("items", List.of("before")));
        assertThatThrownBy(() -> scenario.context().put("late", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> put((Map<?, ?>) scenario.expect().getFirst().expected()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mapsExpectationWithoutLossToFixtureAssertion() {
        FixtureBundle.Assertion assertion = new FixtureBundle.Assertion(
                "NODE_OUTPUT", "node-1", "/amount", "EQUALS", 7, 0.25);

        Scenario.Expectation expectation = Scenario.Expectation.from(assertion);

        assertThat(expectation.toFixtureAssertion().scope()).isEqualTo(assertion.scope());
        assertThat(expectation.toFixtureAssertion().nodeId()).isEqualTo(assertion.nodeId());
        assertThat(expectation.toFixtureAssertion().path()).isEqualTo(assertion.path());
        assertThat(expectation.toFixtureAssertion().operator()).isEqualTo(assertion.operator());
        assertThat(expectation.toFixtureAssertion().expected()).isEqualTo(assertion.expected());
        assertThat(expectation.toFixtureAssertion().numericTolerance())
                .isEqualTo(assertion.numericTolerance());
    }

    @Test
    void rejectsInvalidTargetWorldRevisionStateAndExpectation() {
        ResourceWorldModel model = world("provider-a", "v1");

        assertScenarioCode(() -> new Scenario.TargetRef("UNKNOWN", "target", TARGET_FINGERPRINT),
                ScenarioException.Code.TARGET_KIND_UNSUPPORTED);
        assertScenarioCode(() -> new Scenario.TargetRef("GRAPH", "latest", TARGET_FINGERPRINT),
                ScenarioException.Code.TARGET_ID_INVALID);
        assertScenarioCode(() -> new Scenario.WorldModelRef("world", 0, TARGET_FINGERPRINT),
                ScenarioException.Code.INVALID_WORLD_REF);
        assertScenarioCode(() -> new Scenario("scenario-a", "tenant-a", 0, target(), model,
                        Map.of(), Scenario.WorldStateInit.EMPTY, List.of()),
                ScenarioException.Code.INVALID_SCENARIO);
        assertScenarioCode(() -> new Scenario("scenario-a", "tenant-a", 1, target(), model,
                        Map.of(), null, List.of()), ScenarioException.Code.STATE_NOT_SUPPORTED);
        assertScenarioCode(() -> new Scenario.Expectation("NOPE", "", "/x", "EQUALS", 1, null),
                ScenarioException.Code.EXPECTATION_SCOPE_UNSUPPORTED);
        assertScenarioCode(() -> new Scenario.Expectation("OUTPUT_PATH", "", "x", "EQUALS", 1, null),
                ScenarioException.Code.EXPECTATION_PATH_INVALID);
        assertScenarioCode(() -> new Scenario.Expectation("OUTPUT_PATH", "", "/x", "EQUALS", "text", 0.1),
                ScenarioException.Code.EXPECTATION_TOLERANCE_INVALID);
    }

    @Test
    void rejectsWorldReferenceAndTenantDrift() {
        ResourceWorldModel model = world("provider-a", "v1");
        Scenario.WorldModelRef wrong = new Scenario.WorldModelRef(
                model.worldModelId(), model.revision(), "sha256:" + "b".repeat(64));

        assertScenarioCode(() -> new Scenario("scenario-a", "tenant-a", 1, target(), wrong, model,
                        Map.of(), Scenario.WorldStateInit.EMPTY, List.of()),
                ScenarioException.Code.WORLD_MODEL_MISMATCH);
        assertScenarioCode(() -> new Scenario("scenario-a", "tenant-b", 1, target(), model,
                        Map.of(), Scenario.WorldStateInit.EMPTY, List.of()),
                ScenarioException.Code.TENANT_DRIFT);
    }

    @Test
    void reconstructedValueWorldReferenceStillBindsExactlyToModel() {
        ResourceWorldModel model = world("provider-a", "v1");
        Scenario.WorldModelRef serialized = new Scenario.WorldModelRef(
                model.worldModelId(), model.revision(), model.fingerprint());

        Scenario scenario = new Scenario("scenario-a", "tenant-a", 1, target(), serialized, model,
                Map.of(), Scenario.WorldStateInit.EMPTY, List.of());

        assertThat(scenario.world()).isEqualTo(serialized);
        assertThat(scenario.world().worldModelId()).isEqualTo(model.worldModelId());
        assertThat(scenario.world().revision()).isEqualTo(model.revision());
        assertThat(scenario.world().fingerprint()).isEqualTo(model.fingerprint());
    }

    @Test
    void compatibleContractRevisionKeepsScenarioValid() {
        LogicalResourceContract baseline = contract();
        LogicalResourceContract compatible = new LogicalResourceContract(
                baseline.contractId(),
                new SchemaEnvelope("json-schema", "2020-12", Map.of(
                        "type", "object",
                        "properties", Map.of("requestId", Map.of("type", "string"),
                                "region", Map.of("type", "string")),
                        "required", List.of("requestId"), "additionalProperties", false)),
                baseline.outputShape(), baseline.semantics());
        Scenario scenario = scenario(world("provider-a", "v1"), Map.of());
        scenario = withDependency(scenario, Scenario.ContractDependency.of(baseline));

        Scenario.CompatibilityValidation result = scenario.validateCompatibility(baseline, compatible);

        assertThat(result.status()).isEqualTo(LogicalResourceContractCompatibility.Status.COMPATIBLE);
        assertThat(result.valid()).isTrue();
        assertThat(scenario.validateCompatibility(compatible).valid()).isFalse();
        assertThat(scenario.validateCompatibility(compatible).status())
                .isEqualTo(LogicalResourceContractCompatibility.Status.REVIEW_REQUIRED);
    }

    @Test
    void breakingAndReviewRequiredContractsInvalidateFailClosed() {
        LogicalResourceContract baseline = contract();
        Scenario scenario = withDependency(scenario(world("provider-a", "v1"), Map.of()),
                Scenario.ContractDependency.of(baseline));
        LogicalResourceContract breaking = new LogicalResourceContract(
                baseline.contractId(), baseline.inputShape(), objectSchema("status", "integer", true),
                baseline.semantics());
        LogicalResourceContract review = new LogicalResourceContract(
                baseline.contractId(), baseline.inputShape(), baseline.outputShape(),
                ResponseSemantics.confirmed("body.ok == true", Map.of("BUSINESS", List.of("NOT_FOUND")),
                        ResponseSemantics.Idempotency.IDEMPOTENT,
                        ResponseSemantics.Retryability.CONDITIONAL));

        assertThat(scenario.validateCompatibility(baseline, breaking).valid()).isFalse();
        assertThat(scenario.validateCompatibility(baseline, breaking).status())
                .isEqualTo(LogicalResourceContractCompatibility.Status.BREAKING);
        assertThat(scenario.validateCompatibility(baseline, review).valid()).isFalse();
        assertThat(scenario.validateCompatibility(baseline, review).status())
                .isEqualTo(LogicalResourceContractCompatibility.Status.REVIEW_REQUIRED);
    }

    @Test
    void providerAndApiBindingSwitchChangesWorldAddressButNotContractCompatibility() {
        LogicalResourceContract baseline = contract();
        Scenario.ContractDependency dependency = Scenario.ContractDependency.of(baseline);
        ResourceWorldModel leftWorld = world("provider-a", "v1");
        ResourceWorldModel rightWorld = world("provider-b", "v9");
        Scenario left = withDependency(scenario(leftWorld, Map.of()), leftWorld, dependency);
        Scenario right = withDependency(scenario(rightWorld, Map.of()), rightWorld, dependency);

        assertThat(left.world().fingerprint()).isNotEqualTo(right.world().fingerprint());
        assertThat(left.fingerprint()).isNotEqualTo(right.fingerprint());
        assertThat(left.validateCompatibility(baseline).valid()).isTrue();
        assertThat(right.validateCompatibility(baseline).valid()).isTrue();
        assertThat(left.contractDependencies()).extracting(Scenario.ContractDependency::contractId)
                .containsExactly("logical.customer");
        assertThat(left.contractDependencies().toString()).doesNotContain("provider-a", "v1");
    }

    @Test
    void serializedValueDependenciesPreserveBothCompatibilityValidationPaths() throws Exception {
        LogicalResourceContract baseline = contract();
        LogicalResourceContract candidate = new LogicalResourceContract(
                baseline.contractId(), baseline.inputShape(), baseline.outputShape(), baseline.semantics());
        Scenario original = withDependency(scenario(world("provider-a", "v1"), Map.of()),
                Scenario.ContractDependency.of(baseline));
        ObjectMapper mapper = new ObjectMapper();
        JsonNode serializedWorld = mapper.readTree(mapper.writeValueAsString(Map.of(
                "worldModelId", original.world().worldModelId(),
                "revision", original.world().revision(),
                "fingerprint", original.world().fingerprint())));
        JsonNode serializedDependency = mapper.readTree(mapper.writeValueAsString(Map.of(
                "contractId", original.contractDependencies().getFirst().contractId(),
                "baselineFingerprint", original.contractDependencies().getFirst().baselineFingerprint())));
        Scenario rebuilt = new Scenario(original.scenarioId(), original.tenantId(), original.revision(),
                original.target(), new Scenario.WorldModelRef(serializedWorld.get("worldModelId").textValue(),
                        serializedWorld.get("revision").intValue(), serializedWorld.get("fingerprint").textValue()),
                world("provider-a", "v1"), original.context(), original.stateInit(), original.expect(),
                List.of(new Scenario.ContractDependency(serializedDependency.get("contractId").textValue(),
                        serializedDependency.get("baselineFingerprint").textValue())));

        assertThat(rebuilt.fingerprint()).isEqualTo(original.fingerprint());
        assertThat(rebuilt.validateCompatibility(candidate)).isEqualTo(original.validateCompatibility(candidate));
        assertThat(rebuilt.validateCompatibility(baseline, candidate))
                .isEqualTo(original.validateCompatibility(baseline, candidate));
    }

    @Test
    void errorsAreStableAndDoNotLeakContextOrExpectedPayload() {
        String secret = "scenario-secret-payload";
        Map<String, Object> context = Map.of("secret", secret);
        Map<String, Object> cyclicExpected = new LinkedHashMap<>();
        cyclicExpected.put("secret", secret);
        cyclicExpected.put("self", cyclicExpected);
        assertThatThrownBy(() -> new Scenario("scenario-a", "tenant-a", 1, target(),
                world("provider-a", "v1"), context, Scenario.WorldStateInit.EMPTY,
                List.of(new Scenario.Expectation("OUTPUT_PATH", "", "/x", "EQUALS",
                        cyclicExpected, null)), List.of()))
                .isInstanceOfSatisfying(ScenarioException.class, error -> {
                    assertThat(error.code()).isEqualTo(ScenarioException.Code.EXPECTATION_INVALID);
                    assertThat(error.getMessage()).isEqualTo("RG.WORLD.SCENARIO.EXPECTATION_INVALID");
                    assertThat(error.getMessage()).doesNotContain(secret);
                    assertThat(error.getCause()).isNull();
                });
    }

    private static Scenario scenario(ResourceWorldModel model, Map<String, Object> context) {
        return new Scenario("scenario-a", "tenant-a", 1, target(), model, context,
                Scenario.WorldStateInit.EMPTY,
                List.of(expectation("/result", 1)), List.of());
    }

    private static Scenario withDependency(Scenario source, Scenario.ContractDependency dependency) {
        return withDependency(source, world("provider-a", "v1"), dependency);
    }

    private static Scenario withDependency(Scenario source, ResourceWorldModel model,
                                           Scenario.ContractDependency dependency) {
        return new Scenario(source.scenarioId(), source.tenantId(), source.revision(), source.target(),
                source.world(), model, source.context(), source.stateInit(),
                source.expect(), List.of(dependency));
    }

    private static Scenario.Expectation expectation(String path, int value) {
        return new Scenario.Expectation("OUTPUT_PATH", "", path, "EQUALS", value, null);
    }

    private static Scenario.TargetRef target() {
        return new Scenario.TargetRef("GRAPH", "customer-graph", TARGET_FINGERPRINT);
    }

    private static Map<String, Object> context(String first, String second) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(first, 1);
        values.put(second, Map.of("ready", true));
        return values;
    }

    private static ResourceWorldModel world(String provider, String apiVersion) {
        LogicalResourceContract contract = contract();
        LogicalResourceBinding binding = LogicalResourceBinding.bind(provider, apiVersion,
                LogicalResourceContractTest.designContract(contract.inputShape(), contract.outputShape()),
                descriptor("customer.lookup"), contract);
        WorldSlice slice = WorldSlice.register(new WorldSlice.Registration(
                        "tenant-a", provider, apiVersion, contract.contractId(),
                        contract.contractFingerprint(), binding.descriptorFingerprint(), true),
                contract, binding, BlogeFragmentRef.frozen("customer-world.bloge", "graph customerWorld {}"),
                StateSpec.empty());
        return new ResourceWorldModel("customer-world", "tenant-a", 1, List.of(slice));
    }

    private static LogicalResourceContract contract() {
        return new LogicalResourceContract("logical.customer",
                objectSchema("requestId", "string", true),
                objectSchema("status", "string", true),
                confirmed(Map.of("BUSINESS", List.of("NOT_FOUND"))));
    }

    private static void assertScenarioCode(Runnable operation, ScenarioException.Code code) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ScenarioException.class,
                        error -> assertThat(error.code()).isEqualTo(code));
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<?, ?> map) {
        ((Map<Object, Object>) map).put("late", true);
    }
}
