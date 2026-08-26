package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.confirmed;
import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.descriptor;
import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.objectSchema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceWorldModelTest {
    private static final String PURE_DSL = """
            graph customerWorld {
              decision_table route(customerId = ctx.customerId) hit=first -> String {
                rule (customerId: customerId == "vip") -> "priority"
                otherwise -> "standard"
              }
            }
            """;

    @Test
    void canonicalizesSliceOrderIntoAStableFingerprint() {
        LogicalResourceContract contract = contract();
        WorldSlice first = slice("provider-b", "v2", contract);
        WorldSlice second = slice("provider-a", "v1", contract);

        ResourceWorldModel left = new ResourceWorldModel("customer-world", "tenant-a", 4,
                List.of(first, second));
        ResourceWorldModel right = new ResourceWorldModel("customer-world", "tenant-a", 4,
                List.of(second, first));

        assertThat(left.fingerprint()).isEqualTo(right.fingerprint());
        assertThat(left.slices()).extracting(WorldSlice::provider)
                .containsExactly("provider-a", "provider-b");
    }

    @Test
    void preservesLegacyEmptySliceFingerprintMaterial() {
        LogicalResourceContract contract = contract();
        WorldSlice value = slice("provider-a", "v1", contract);
        assertThat(value.state()).isSameAs(StateSpec.empty());
        String expected = VisualBundleFingerprint.fromMaterial(Map.of(
                "tenantId", value.tenantId(),
                "provider", value.provider(),
                "apiVersion", value.apiVersion(),
                "logicalContractId", value.logicalContractId(),
                "contractFingerprint", value.contractFingerprint(),
                "bindingFingerprint", value.bindingFingerprint(),
                "behaviorFingerprint", value.behavior().fingerprint(),
                "state", "empty"));

        assertThat(value.fingerprint()).isEqualTo(expected);
    }

    @Test
    void ownsDefensiveCopiesOfSliceCollections() {
        LogicalResourceContract contract = contract();
        List<WorldSlice> mutable = new ArrayList<>(List.of(slice("provider-a", "v1", contract)));
        ResourceWorldModel model = new ResourceWorldModel("customer-world", "tenant-a", 1, mutable);
        String fingerprint = model.fingerprint();

        mutable.clear();

        assertThat(model.slices()).hasSize(1);
        assertThatThrownBy(() -> model.slices().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(model.fingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void rejectsDuplicateLogicalCoordinatesAndTenantDrift() {
        LogicalResourceContract contract = contract();
        WorldSlice first = slice("provider-a", "v1", contract);

        assertWorldFailure(() -> new ResourceWorldModel("customer-world", "tenant-a", 1,
                List.of(first, first)), WorldModelException.Code.DUPLICATE_SLICE);
        LogicalResourceBinding otherBinding = binding("provider-b", "v1", contract);
        WorldSlice otherTenant = WorldSlice.register(new WorldSlice.Registration(
                        "tenant-b", "provider-b", "v1", contract.contractId(), contract.contractFingerprint(),
                        otherBinding.descriptorFingerprint(), true), contract, otherBinding,
                BlogeFragmentRef.frozen("other.bloge", PURE_DSL), StateSpec.empty());
        assertWorldFailure(() -> new ResourceWorldModel("customer-world", "tenant-a", 1,
                List.of(otherTenant)), WorldModelException.Code.TENANT_DRIFT);
    }

    @Test
    void rejectsContractBindingAndStateDrift() {
        LogicalResourceContract contract = contract();
        LogicalResourceBinding binding = binding("provider-a", "v1", contract);
        assertWorldFailure(() -> WorldSlice.register(new WorldSlice.Registration(
                        "tenant-a", "provider-b", "v1", contract.contractId(), contract.contractFingerprint(),
                        binding.descriptorFingerprint(), true), contract, binding, fragment(), StateSpec.empty()),
                WorldModelException.Code.BINDING_DRIFT);
        assertWorldFailure(() -> StateSpec.of(Map.of("balance", 100)),
                WorldModelException.Code.STATE_NOT_SUPPORTED);
    }

    @Test
    void stageOneRejectsEveryStateDefault() {
        assertThat(StateSpec.empty().isEmpty()).isTrue();
        assertWorldFailure(() -> StateSpec.of(Map.of("balance", 100)),
                WorldModelException.Code.STATE_NOT_SUPPORTED);
    }

    @Test
    void mergesStateDeclarationsOnlyWithOneWriterAndMatchingSchemaDefaults() {
        LogicalResourceContract contract = contract();
        StateKeySpec read = new StateKeySpec("/balance", StateKeySpec.Access.READ,
                Map.of("type", "integer"), 100);
        StateKeySpec write = new StateKeySpec("/balance", StateKeySpec.Access.WRITE,
                Map.of("type", "integer"), 100);
        ResourceWorldModel model = new ResourceWorldModel("customer-world", "tenant-a", 1,
                List.of(stateSlice("provider-a", "v1", contract, StateSpecV2.of(List.of(write))),
                        stateSlice("provider-b", "v1", contract, StateSpecV2.of(List.of(read)))));
        assertThat(model.stateSpec().declarations()).extracting(StateKeySpec::access)
                .containsExactly(StateKeySpec.Access.READ_WRITE);
        assertThat(model.stateWriterCoordinates()).containsEntry("/balance", "provider-a\u0000v1\u0000logical.customer");
        assertThat(model.stateWriterCoordinate("/balance"))
                .isEqualTo("provider-a\u0000v1\u0000logical.customer");
        StateKeySpec wrongDefault = new StateKeySpec("/balance", StateKeySpec.Access.READ,
                Map.of("type", "integer"), 101);
        assertWorldFailure(() -> new ResourceWorldModel("customer-world", "tenant-a", 1,
                List.of(stateSlice("provider-a", "v1", contract, StateSpecV2.of(List.of(write))),
                        stateSlice("provider-b", "v1", contract, StateSpecV2.of(List.of(wrongDefault))))),
                WorldModelException.Code.STATE_NOT_SUPPORTED);
    }

    @Test
    void registrationErrorsDoNotLeakIdentityOrPayloadValues() {
        String secret = "world-secret-value";
        LogicalResourceContract contract = contract();
        LogicalResourceBinding binding = binding("provider-a", "v1", contract);

        assertThatThrownBy(() -> WorldSlice.register(new WorldSlice.Registration(
                        "tenant-a", "provider-" + secret, "v1", contract.contractId(),
                        contract.contractFingerprint(), binding.descriptorFingerprint(), true),
                contract, binding, fragment(), StateSpec.empty()))
                .isInstanceOfSatisfying(WorldModelException.class, error -> {
                    assertThat(error.code()).isEqualTo(WorldModelException.Code.BINDING_DRIFT);
                    assertThat(error.getMessage()).doesNotContain(secret)
                            .doesNotContain(contract.contractId())
                            .doesNotContain(binding.descriptorFingerprint());
                });
    }

    private static WorldSlice slice(String provider, String apiVersion, LogicalResourceContract contract) {
        LogicalResourceBinding binding = binding(provider, apiVersion, contract);
        return WorldSlice.register(new WorldSlice.Registration("tenant-a", provider, apiVersion,
                        contract.contractId(), contract.contractFingerprint(), binding.descriptorFingerprint(), true),
                contract, binding, fragment(), StateSpec.empty());
    }

    private static WorldSlice stateSlice(String provider, String apiVersion,
                                         LogicalResourceContract contract, WorldStateSpec state) {
        LogicalResourceBinding value = binding(provider, apiVersion, contract);
        return WorldSlice.register(new WorldSlice.Registration("tenant-a", provider, apiVersion,
                        contract.contractId(), contract.contractFingerprint(), value.descriptorFingerprint(), true),
                contract, value, fragment(), state);
    }

    private static LogicalResourceBinding binding(String provider, String apiVersion,
                                                  LogicalResourceContract contract) {
        var design = LogicalResourceContractTest.designContract(
                contract.inputShape(), contract.outputShape());
        return LogicalResourceBinding.bind(provider, apiVersion, design,
                descriptor("customer.lookup"), contract);
    }

    private static LogicalResourceContract contract() {
        return new LogicalResourceContract("logical.customer", objectSchema("customerId", "string", true),
                objectSchema("status", "string", true), confirmed(Map.of("BUSINESS", List.of("NOT_FOUND"))));
    }

    private static BlogeFragmentRef fragment() {
        return BlogeFragmentRef.frozen("customer-fragment.bloge", PURE_DSL);
    }

    private static void assertWorldFailure(Runnable operation, WorldModelException.Code code) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(WorldModelException.class,
                        error -> assertThat(error.code()).isEqualTo(code));
    }
}
