package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.confirmed;
import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.descriptor;
import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.objectSchema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldModelS1BTest {
    private static final String PURE_FRAGMENT = """
            graph customerWorld {
              decision_table response(type = ctx.type) hit=first -> String {
                rule (type: type == "vip") -> "priority"
                otherwise -> "standard"
              }
            }
            """;

    @Test
    void admitsPureFragmentAndKeepsFingerprintStableAcrossTwentyFreezes() {
        BlogeFragmentRef fragment = BlogeFragmentRef.frozen("customer-world.bloge", PURE_FRAGMENT);
        PureBlogeFragmentValidator validator = new PureBlogeFragmentValidator();
        PureBlogeFragmentValidator.ValidationResult first = validator.validate(fragment);

        for (int replay = 0; replay < 20; replay++) {
            assertThat(validator.validate(BlogeFragmentRef.frozen(
                    "customer-world.bloge", PURE_FRAGMENT)).fingerprint())
                    .isEqualTo(first.fingerprint());
        }
        assertThat(first.primitiveCount()).isEqualTo(1);
        assertThat(first.outputNodeId()).isEqualTo("response");
        assertThatThrownBy(() -> first.findings().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void admitsTransformAndBranchWithOneTerminal() {
        String transform = """
                graph transformWorld {
                  decision_table route(type = ctx.type) hit=first -> String {
                    rule (type: type == "vip") -> "priority"
                    otherwise -> "standard"
                  }
                  transform response {
                    value = route.output
                  }
                }
                """;
        String branch = """
                graph branchWorld {
                  decision_table route(type = ctx.type) hit=first -> String {
                    rule (type: type == "vip") -> "vip"
                    otherwise -> "standard"
                  }
                  transform vip {
                    value = "vip"
                  }
                  transform standard {
                    value = "standard"
                  }
                  branch on route.output {
                    "vip" -> vip
                    otherwise -> standard
                  }
                  transform response {
                    value = route.output == "vip" ? vip.output.value : standard.output.value
                  }
                }
                """;

        PureBlogeFragmentValidator validator = new PureBlogeFragmentValidator();
        PureBlogeFragmentValidator.ValidationResult transformResult = validator.validate(
                BlogeFragmentRef.frozen("transform.bloge", transform));
        PureBlogeFragmentValidator.ValidationResult branchResult = validator.validate(
                BlogeFragmentRef.frozen("branch.bloge", branch));

        assertThat(transformResult.primitiveCount()).isEqualTo(2);
        assertThat(transformResult.outputNodeId()).isEqualTo("response");
        assertThat(branchResult.primitiveCount()).isEqualTo(5);
        assertThat(branchResult.outputNodeId()).isEqualTo("response");
    }

    @Test
    void fragmentRevisionIsPositiveAndPartOfTheFingerprint() {
        BlogeFragmentRef first = BlogeFragmentRef.frozen("customer-world.bloge", 1, PURE_FRAGMENT);
        BlogeFragmentRef second = BlogeFragmentRef.frozen("customer-world.bloge", 2, PURE_FRAGMENT);

        assertThat(first.revision()).isEqualTo(1);
        assertThat(second.revision()).isEqualTo(2);
        assertThat(first.fingerprint()).isNotEqualTo(second.fingerprint());
        assertThatThrownBy(() -> BlogeFragmentRef.frozen("customer-world.bloge", 0, PURE_FRAGMENT))
                .isInstanceOfSatisfying(WorldModelException.class, error -> {
                    assertThat(error.code()).isEqualTo(WorldModelException.Code.FRAGMENT_INVALID);
                    assertThat(error.getMessage()).isEqualTo("RG.WORLD.FRAGMENT_INVALID");
                });
        assertThatThrownBy(() -> BlogeFragmentRef.frozen("customer-world.bloge", -1, PURE_FRAGMENT))
                .isInstanceOf(WorldModelException.class);
    }

    @Test
    void rejectsNonPureAstWithoutLeakingDslOrPayload() {
        assertAdmissionRejected("graph bad { node call : httpResource { input { token = ctx.secret } } }",
                WorldModelException.Code.FRAGMENT_NETWORK_FORBIDDEN, "secret");
        assertAdmissionRejected("graph bad { node call : resource:customer { } }",
                WorldModelException.Code.FRAGMENT_RESOURCE_FORBIDDEN, "customer");
        assertAdmissionRejected("graph bad { node call : file:secret { } }",
                WorldModelException.Code.FRAGMENT_FILESYSTEM_FORBIDDEN, "secret");
        assertAdmissionRejected("graph bad { transform result { value = now() } }",
                WorldModelException.Code.FRAGMENT_NONDETERMINISTIC, "now");
        assertAdmissionRejected("graph bad { transform result { value = unknownFunction(ctx.id) } }",
                WorldModelException.Code.FRAGMENT_UNRESOLVED_CAPABILITY, "unknownFunction");
        assertAdmissionRejected("graph bad { import \"other.bloge\" graph nested { } }",
                WorldModelException.Code.FRAGMENT_WORLD_DELEGATION_FORBIDDEN, "other.bloge");
        assertAdmissionRejected("graph bad { script run { code = \"\"\"secret\"\"\" } }",
                WorldModelException.Code.FRAGMENT_UNRESOLVED_CAPABILITY, "script");
        assertAdmissionRejected("graph bad { node call : ordinaryOperator { } }",
                WorldModelException.Code.FRAGMENT_UNRESOLVED_CAPABILITY, "ordinaryOperator");
        assertAdmissionRejected("graph bad { node call : customOperator { } }",
                WorldModelException.Code.FRAGMENT_UNRESOLVED_CAPABILITY, "customOperator");
    }

    @Test
    void stageOneStateSpecOnlyRepresentsEmpty() {
        assertThat(StateSpec.empty().isEmpty()).isTrue();
        assertThat(StateSpec.of(Map.of())).isSameAs(StateSpec.empty());
        assertThatThrownBy(() -> StateSpec.of(Map.of("balance", 0)))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_NOT_SUPPORTED));
        assertThatThrownBy(() -> StateSpec.of(java.util.Set.of("balance"), Map.of()))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_NOT_SUPPORTED));
    }

    @Test
    void worldErrorsHaveOnlyStableSanitizedCodes() {
        WorldModelException error = new WorldModelException(WorldModelException.Code.CONTRACT_DRIFT);
        assertThat(error.getMessage()).isEqualTo("RG.WORLD.CONTRACT_DRIFT");
        assertThat(error.wireCode()).isEqualTo("RG.WORLD.CONTRACT_DRIFT");
        assertThat(new WorldModelException(null).getMessage()).isEqualTo("RG.WORLD.INVALID");

        assertThatThrownBy(() -> BlogeFragmentRef.freeze("customer-world.bloge", 1,
                PURE_FRAGMENT, "", null))
                .isInstanceOfSatisfying(WorldModelException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(WorldModelException.Code.FRAGMENT_INVALID);
                    assertThat(failure.getMessage()).isEqualTo("RG.WORLD.FRAGMENT_INVALID");
                });
    }

    @Test
    void admissionRevalidatesWorldIdentityAndProvenance() {
        LogicalResourceContract contract = contract();
        WorldSlice slice = slice("mobility", "v1", contract);
        ResourceWorldModel model = new ResourceWorldModel("customer-world", "tenant-a", 1,
                List.of(slice));

        WorldModelAdmissionService.Admission admission =
                new WorldModelAdmissionService().admit(model);
        assertThat(admission.fingerprint()).isEqualTo(model.fingerprint());
        assertThat(admission.revision()).isEqualTo(1);

        assertThatThrownBy(() -> WorldSlice.register(new WorldSlice.Registration(
                "tenant-a", "other-provider", "v1", contract.contractId(), contract.contractFingerprint(),
                slice.bindingFingerprint(), true), contract, slice.binding(), fragment(), StateSpec.empty()))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.BINDING_DRIFT));
        assertThatThrownBy(() -> WorldSlice.register(new WorldSlice.Registration(
                "tenant-a", "mobility", "v1", contract.contractId(), "drifted-contract",
                slice.bindingFingerprint(), true), contract, slice.binding(), fragment(), StateSpec.empty()))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.CONTRACT_DRIFT));
        assertThatThrownBy(() -> WorldSlice.register(new WorldSlice.Registration(
                "tenant-a", "mobility", "v1", contract.contractId(), contract.contractFingerprint(),
                slice.bindingFingerprint(), false), contract, slice.binding(), fragment(), StateSpec.empty()))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.BINDING_UNAVAILABLE));
    }

    @Test
    void modelCanonicalizesOrderAndRejectsDuplicateCoordinatesAndTenantDrift() {
        LogicalResourceContract contract = contract();
        WorldSlice first = slice("mobility", "v2", contract);
        WorldSlice second = slice("mobility", "v1", contract);
        ResourceWorldModel left = new ResourceWorldModel("customer-world", "tenant-a", 1,
                List.of(first, second));
        ResourceWorldModel right = new ResourceWorldModel("customer-world", "tenant-a", 1,
                List.of(second, first));

        assertThat(left.fingerprint()).isEqualTo(right.fingerprint());
        assertThat(left.slices()).extracting(WorldSlice::apiVersion).containsExactly("v1", "v2");
        assertThatThrownBy(() -> new ResourceWorldModel("customer-world", "tenant-a", 1,
                List.of(first, first)))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.DUPLICATE_SLICE));
        WorldSlice v3 = slice("mobility", "v3", contract);
        WorldSlice otherTenant = WorldSlice.register(new WorldSlice.Registration(
                "tenant-b", "mobility", "v3", contract.contractId(), contract.contractFingerprint(),
                v3.bindingFingerprint(), true), contract, v3.binding(), fragment(), StateSpec.empty());
        assertThatThrownBy(() -> new ResourceWorldModel("customer-world", "tenant-a", 1,
                List.of(otherTenant)))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.TENANT_DRIFT));
    }

    private static void assertAdmissionRejected(String dsl, WorldModelException.Code code, String secret) {
        assertThatThrownBy(() -> new PureBlogeFragmentValidator()
                .validate(BlogeFragmentRef.frozen("invalid.bloge", dsl)))
                .isInstanceOfSatisfying(WorldModelException.class, error -> {
                    assertThat(error.code()).isEqualTo(code);
                    assertThat(error.getMessage()).doesNotContain(secret).doesNotContain(dsl);
                });
    }

    private static LogicalResourceContract contract() {
        return new LogicalResourceContract("logical.customer", objectSchema("id", "string", true),
                objectSchema("result", "string", true), confirmed(Map.of("NONE", List.of("N/A"))));
    }

    private static WorldSlice slice(String provider, String apiVersion, LogicalResourceContract contract) {
        LogicalResourceBinding binding = binding(provider, apiVersion, contract);
        return WorldSlice.register(new WorldSlice.Registration("tenant-a", provider, apiVersion,
                        contract.contractId(), contract.contractFingerprint(), binding.descriptorFingerprint(), true),
                contract, binding, fragment(), StateSpec.empty());
    }

    private static LogicalResourceBinding binding(String provider, String apiVersion,
                                                  LogicalResourceContract contract) {
        ResourceDesignContract design = new ResourceDesignContract("logical.customer", "customer.lookup",
                "Customer lookup", "", List.of(), contract.inputShape(), contract.outputShape(), Map.of(), "ACTIVE");
        return LogicalResourceBinding.bind(provider, apiVersion, design, descriptor("customer.lookup"), contract);
    }

    private static BlogeFragmentRef fragment() {
        return BlogeFragmentRef.frozen("customer-fragment.bloge", PURE_FRAGMENT);
    }
}
