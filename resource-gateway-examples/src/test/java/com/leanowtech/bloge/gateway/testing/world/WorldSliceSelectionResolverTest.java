package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.confirmed;
import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.descriptor;
import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.objectSchema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldSliceSelectionResolverTest {
    private static final String TARGET_FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final String DSL = "graph customerWorld { transform result { value = ctx.id } }";

    @Test
    void selectsTheOnlyEligibleSliceAndReturnsItsAddress() {
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel world = world("customer-world", "tenant-a", 1,
                List.of(slice("tenant-a", "provider-a", "v1", contract)));
        Scenario scenario = scenario(world, List.of(Scenario.ContractDependency.of(contract)));

        Map<String, WorldSliceSelection> selections = resolve(scenario, world);

        assertThat(selections).containsOnlyKeys(contract.contractId());
        assertThat(selections.get(contract.contractId())).isEqualTo(new WorldSliceSelection(
                "provider-a", "v1", world.slices().getFirst().fingerprint()));
    }

    @Test
    void failsClosedWhenNoSliceMatchesTheDependency() {
        LogicalResourceContract required = contract("logical.customer");
        LogicalResourceContract unrelated = contract("logical.order");
        LogicalResourceContract incompatible = new LogicalResourceContract(
                required.contractId(), required.inputShape(), objectSchema("result", "integer", true),
                required.semantics());
        ResourceWorldModel world = world("customer-world", "tenant-a", 1,
                List.of(slice("tenant-a", "provider-a", "v1", unrelated),
                        slice("tenant-a", "provider-b", "v2", incompatible)));

        assertCode(() -> resolve(scenario(world, List.of(Scenario.ContractDependency.of(required))), world),
                WorldScenarioCompilationException.Code.SELECTION_MISSING);
    }

    @Test
    void failsClosedWhenMoreThanOneEligibleSliceExists() {
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel world = world("customer-world", "tenant-a", 1, List.of(
                slice("tenant-a", "provider-a", "v1", contract),
                slice("tenant-a", "provider-b", "v2", contract)));

        assertCode(() -> resolve(scenario(world, List.of(Scenario.ContractDependency.of(contract))), world),
                WorldScenarioCompilationException.Code.SELECTION_NOT_UNIQUE);
    }

    @Test
    void rejectsNullAndWorldIdentityDriftIncludingTenantDrift() {
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel exact = world("customer-world", "tenant-a", 1,
                List.of(slice("tenant-a", "provider-a", "v1", contract)));
        Scenario scenario = scenario(exact, List.of(Scenario.ContractDependency.of(contract)));

        assertCode(() -> resolve(null, exact), WorldScenarioCompilationException.Code.INVALID_INPUT);
        assertCode(() -> resolve(scenario, null), WorldScenarioCompilationException.Code.INVALID_INPUT);
        assertCode(() -> resolve(scenario, world("other-world", "tenant-a", 1,
                        List.of(slice("tenant-a", "provider-a", "v1", contract)))),
                WorldScenarioCompilationException.Code.WORLD_DRIFT);
        assertCode(() -> resolve(scenario, world("customer-world", "tenant-b", 1,
                        List.of(slice("tenant-b", "provider-a", "v1", contract)))),
                WorldScenarioCompilationException.Code.WORLD_DRIFT);
    }

    @Test
    void ignoresAValidSliceForAnUndeclaredContract() {
        LogicalResourceContract required = contract("logical.customer");
        LogicalResourceContract unrelated = contract("logical.order");
        WorldSlice selected = slice("tenant-a", "provider-a", "v1", required);
        ResourceWorldModel world = world("customer-world", "tenant-a", 1, List.of(
                slice("tenant-a", "provider-z", "v9", unrelated), selected));

        Map<String, WorldSliceSelection> selections = resolve(
                scenario(world, List.of(Scenario.ContractDependency.of(required))), world);

        assertThat(selections).containsOnlyKeys(required.contractId());
        assertThat(selections.get(required.contractId()).sliceFingerprint())
                .isEqualTo(selected.fingerprint());
    }

    @Test
    void isIndependentOfWorldSliceInsertionOrderAndCanonicalizesDependencyKeys() {
        LogicalResourceContract customer = contract("logical.customer");
        LogicalResourceContract order = contract("logical.order");
        WorldSlice customerSlice = slice("tenant-a", "provider-a", "v1", customer);
        WorldSlice orderSlice = slice("tenant-a", "provider-b", "v1", order);
        ResourceWorldModel first = world("customer-world", "tenant-a", 1,
                List.of(orderSlice, customerSlice));
        ResourceWorldModel second = world("customer-world", "tenant-a", 1,
                List.of(customerSlice, orderSlice));

        Map<String, WorldSliceSelection> left = resolve(first,
                List.of(Scenario.ContractDependency.of(order), Scenario.ContractDependency.of(customer)));
        Map<String, WorldSliceSelection> right = resolve(second,
                List.of(Scenario.ContractDependency.of(customer), Scenario.ContractDependency.of(order)));

        assertThat(left).isEqualTo(right);
        assertThat(new ArrayList<>(left.keySet())).containsExactly("logical.customer", "logical.order");
    }

    @Test
    void resultMapIsImmutable() {
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel world = world("customer-world", "tenant-a", 1,
                List.of(slice("tenant-a", "provider-a", "v1", contract)));

        Map<String, WorldSliceSelection> selections = resolve(
                scenario(world, List.of(Scenario.ContractDependency.of(contract))), world);

        assertThatThrownBy(() -> selections.put("other", selections.get(contract.contractId())))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static Map<String, WorldSliceSelection> resolve(Scenario scenario,
                                                              ResourceWorldModel world) {
        return new WorldSliceSelectionResolver().resolve(scenario, world);
    }

    private static Map<String, WorldSliceSelection> resolve(ResourceWorldModel world,
                                                              List<Scenario.ContractDependency> dependencies) {
        return resolve(scenario(world, dependencies), world);
    }

    private static Scenario scenario(ResourceWorldModel world,
                                     List<Scenario.ContractDependency> dependencies) {
        return new Scenario("scenario-a", "tenant-a", 1,
                new Scenario.TargetRef("GRAPH", "customer-graph", TARGET_FINGERPRINT), world,
                Map.of(), Scenario.WorldStateInit.EMPTY, List.of(), dependencies);
    }

    private static LogicalResourceContract contract(String id) {
        return new LogicalResourceContract(id, objectSchema("id", "string", true),
                objectSchema("result", "string", true), confirmed(Map.of("NONE", List.of("N/A"))));
    }

    private static ResourceWorldModel world(String worldId, String tenantId, long revision,
                                            List<WorldSlice> slices) {
        return new ResourceWorldModel(worldId, tenantId, revision, slices);
    }

    private static WorldSlice slice(String tenantId, String provider, String apiVersion,
                                    LogicalResourceContract contract) {
        ResourceDesignContract design = new ResourceDesignContract(contract.contractId(),
                contract.contractId(), "Resource", "", List.of(), contract.inputShape(),
                contract.outputShape(), Map.of(), "ACTIVE");
        LogicalResourceBinding binding = LogicalResourceBinding.bind(provider, apiVersion, design,
                descriptor(contract.contractId()), contract);
        return WorldSlice.register(new WorldSlice.Registration(tenantId, provider, apiVersion,
                        contract.contractId(), contract.contractFingerprint(), binding.descriptorFingerprint(), true),
                contract, binding, BlogeFragmentRef.frozen("customer-world.bloge", DSL), StateSpec.empty());
    }

    private static void assertCode(Runnable operation, WorldScenarioCompilationException.Code code) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(WorldScenarioCompilationException.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }
}
