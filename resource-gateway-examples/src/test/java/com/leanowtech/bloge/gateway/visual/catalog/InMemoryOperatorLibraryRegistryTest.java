package com.leanowtech.bloge.gateway.visual.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests registry-level operator-function ownership invariants.
 */
class InMemoryOperatorLibraryRegistryTest {

    private final InMemoryOperatorLibraryRegistry registry = new InMemoryOperatorLibraryRegistry();

    @Test
    void storesFunctionOnlyLibrary() {
        OperatorLibrary library = functionLibrary("risk-functions", function("risk.normalize", "risk", "integer"));

        registry.upsert(library);

        assertThat(registry.find("risk-functions")).contains(library);
        assertThat(registry.operators(false)).isEmpty();
    }

    @Test
    void allowsCompatibleCallableContractFromAnotherLibrary() {
        registry.upsert(functionLibrary("risk-functions", function("risk.normalize", "risk", "integer")));
        OperatorLibrary compatible = functionLibrary(
                "shared-functions",
                function("risk.normalize", "shared", "integer")
        );

        registry.upsert(compatible);

        assertThat(registry.all()).hasSize(2);
    }

    @Test
    void rejectsIncompatibleCallableContractFromAnotherLibrary() {
        registry.upsert(functionLibrary("risk-functions", function("risk.normalize", "risk", "integer")));
        OperatorLibrary incompatible = functionLibrary(
                "shared-functions",
                function("risk.normalize", "shared", "string")
        );

        assertThatThrownBy(() -> registry.upsert(incompatible))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("risk.normalize")
                .hasMessageContaining("risk-functions");
        assertThat(registry.find("shared-functions")).isEmpty();
    }

    @Test
    void rejectsIncompatibleCallableContractFromSystemDefaults() {
        OperatorLibrary incompatible = functionLibrary(
                "custom-coalesce",
                function("coalesce", "custom", "string")
        );

        assertThatThrownBy(() -> registry.upsert(incompatible))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coalesce")
                .hasMessageContaining("builtin");
        assertThat(registry.find("custom-coalesce")).isEmpty();
    }

    @Test
    void rejectsDuplicateCallableInsideLibraryWhenControllerIsBypassed() {
        OperatorLibrary duplicate = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "duplicate-functions",
                "Duplicate functions",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(
                        function("risk.normalize", "risk", "integer"),
                        function("risk.normalize", "shared", "integer")
                ),
                List.of()
        );

        assertThatThrownBy(() -> registry.upsert(duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("declared more than once");
    }

    @Test
    void restoresFunctionOnlyRevisionWithItsOriginalCallableContract() {
        OperatorLibrary original = functionLibrary(
                "risk-functions",
                function("risk.normalize", "risk", "integer")
        );
        OperatorLibrary replacement = functionLibrary(
                "risk-functions",
                function("risk.normalize", "risk", "string")
        );
        registry.upsert(original);
        OperatorLibraryRevision originalRevision = registry.findRevision("risk-functions", 1).orElseThrow();
        registry.upsert(replacement);

        registry.restore(originalRevision);

        assertThat(registry.find("risk-functions"))
                .map(library -> library.builtInFunctions().getFirst())
                .map(BuiltInFunctionContract::callableFingerprint)
                .contains(BuiltInFunctionContract.callableFingerprint(original.builtInFunctions().getFirst()));
        assertThat(registry.revisions("risk-functions"))
                .extracting(OperatorLibraryRevision::action)
                .containsExactly(
                        OperatorLibraryRevision.ACTION_RESTORE,
                        OperatorLibraryRevision.ACTION_REPLACE,
                        OperatorLibraryRevision.ACTION_CREATE
                );
    }

    private static OperatorLibrary functionLibrary(String libraryId,
                                                    OperatorLibrary.BuiltInFunction function) {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                libraryId,
                libraryId,
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(function),
                List.of()
        );
    }

    private static OperatorLibrary.BuiltInFunction function(String name,
                                                            String namespace,
                                                            String returnType) {
        return new OperatorLibrary.BuiltInFunction(
                name,
                namespace,
                name,
                "",
                "risk",
                List.of(new OperatorLibrary.Signature(
                        name + "(value)",
                        "",
                        List.of(new OperatorLibrary.Parameter("value", "any", null, false, false, "")),
                        new OperatorLibrary.ReturnValue(returnType, null, "")
                )),
                List.of()
        );
    }
}
