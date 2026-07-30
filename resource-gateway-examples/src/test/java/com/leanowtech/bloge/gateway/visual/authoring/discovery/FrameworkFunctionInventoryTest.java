package com.leanowtech.bloge.gateway.visual.authoring.discovery;

import com.leanowtech.bloge.core.spi.ExpressionFunction;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FrameworkFunctionInventoryTest {

    @Test
    void discoversCoreAliasesDeterministicallyWithoutInventingSignatures() {
        FrameworkFunctionInventory inventory = new FrameworkFunctionInventory(
                List.of(new CoreFrameworkFunctionInventoryProvider()));

        FrameworkFunctionInventory.Snapshot first = inventory.snapshot();
        FrameworkFunctionInventory.Snapshot second = inventory.snapshot();

        assertThat(first.inventoryFingerprint()).isEqualTo(second.inventoryFingerprint());
        assertThat(first.functions()).isNotEmpty();
        assertThat(first.resolve("coalesce")).isNotEmpty();
        assertThat(first.functions())
                .allSatisfy(runtime -> {
                    assertThat(runtime.runtimeFingerprint()).isNotBlank();
                    assertThat(runtime.providerId()).isEqualTo("bloge-core");
                    assertThat(runtime.declaredContract()).isNull();
                });
    }

    @Test
    void retainsAuthoritativeContractFromAnEmbeddingProvider() {
        OperatorLibrary.BuiltInFunction contract = functionContract("normalize", "string");
        FrameworkFunctionInventory inventory = new FrameworkFunctionInventory(List.of(
                provider("business-functions", "production", List.of(
                        new FrameworkFunctionInventoryProvider.FunctionBinding(
                                "normalize",
                                function("normalize"),
                                contract)
                ))
        ));

        FrameworkFunctionInventory.FunctionRuntime runtime =
                inventory.snapshot().resolve("normalize").getFirst();

        assertThat(runtime.declaredContract()).isEqualTo(contract);
        assertThat(runtime.runtimeProfile()).isEqualTo("production");
        assertThat(runtime.pure()).isTrue();
        assertThat(runtime.requiredExecutionServices()).isEmpty();
    }

    @Test
    void isolatesOneBrokenProviderFromTheRemainingRuntimeSnapshot() {
        FrameworkFunctionInventoryProvider broken = new FrameworkFunctionInventoryProvider() {
            @Override
            public String providerId() {
                return "broken";
            }

            @Override
            public String runtimeProfile() {
                return "production";
            }

            @Override
            public Collection<FunctionBinding> functions() {
                throw new IllegalStateException("inventory unavailable");
            }
        };
        FrameworkFunctionInventory inventory = new FrameworkFunctionInventory(List.of(
                broken,
                provider("healthy", "production", List.of(
                        new FrameworkFunctionInventoryProvider.FunctionBinding(
                                "normalize",
                                function("normalize"),
                                functionContract("normalize", "string"))
                ))
        ));

        assertThat(inventory.snapshot().functions())
                .extracting(FrameworkFunctionInventory.FunctionRuntime::callableName)
                .containsExactly("normalize");
    }

    static FrameworkFunctionInventoryProvider provider(
            String providerId,
            String profile,
            Collection<FrameworkFunctionInventoryProvider.FunctionBinding> functions) {
        return new FrameworkFunctionInventoryProvider() {
            @Override
            public String providerId() {
                return providerId;
            }

            @Override
            public String runtimeProfile() {
                return profile;
            }

            @Override
            public Collection<FunctionBinding> functions() {
                return functions;
            }
        };
    }

    static ExpressionFunction function(String name) {
        return new ExpressionFunction() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Object apply(Object... arguments) {
                return arguments.length == 0 ? null : arguments[0];
            }

            @Override
            public String returnType(String... argumentTypes) {
                return "String";
            }
        };
    }

    static OperatorLibrary.BuiltInFunction functionContract(String name, String returnType) {
        return new OperatorLibrary.BuiltInFunction(
                name,
                "business",
                name,
                "",
                "text",
                List.of(new OperatorLibrary.Signature(
                        name + "(value)",
                        "",
                        List.of(new OperatorLibrary.Parameter(
                                "value", "string", null, false, false, "")),
                        new OperatorLibrary.ReturnValue(returnType, null, ""))),
                List.of()
        );
    }
}
