package com.leanowtech.bloge.gateway.visual.authoring.discovery;

import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.JavaOperatorInventoryProjector;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeParityServiceTest {

    @Test
    void bindsAnExactProcessLocalOperatorAndDetectsContractDrift() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.register("businessEcho", (input, context) -> input);
        JavaOperatorInventoryProjector projector = JavaOperatorInventoryProjector.forRegistry(registry);
        OperatorDefinition exact = projector.project().getFirst();
        RuntimeParityService service = new RuntimeParityService(
                projector,
                new FrameworkFunctionInventory(List.of()));

        RuntimeParityService.Snapshot matching = service.evaluate(library(
                List.of(exact), List.of()));

        assertThat(matching.runtimeReady()).isTrue();
        assertThat(matching.parity()).singleElement().satisfies(parity -> {
            assertThat(parity.state()).isEqualTo("BOUND");
            assertThat(parity.executableReady()).isTrue();
            assertThat(parity.declaredFingerprint()).isEqualTo(parity.runtimeFingerprint());
        });

        OperatorDefinition drifted = new OperatorDefinition(
                exact.schemaVersion(),
                exact.operatorRef(),
                exact.operatorVersion(),
                exact.display(),
                exact.source(),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port(
                                "input",
                                SchemaEnvelope.object(
                                        Map.of("requiredValue", Map.of("type", "string")),
                                        List.of("requiredValue")),
                                true,
                                "")),
                        exact.ports().outputs()),
                exact.configSchema(),
                exact.capabilities(),
                exact.policy(),
                exact.lowering(),
                exact.diagnostics());

        assertThat(service.evaluate(library(List.of(drifted), List.of())).parity())
                .singleElement()
                .satisfies(parity -> {
                    assertThat(parity.state()).isEqualTo("DRIFTED");
                    assertThat(parity.reasonCode()).isEqualTo("RG.AUTHORING.RUNTIME_OPERATOR_DRIFT");
                    assertThat(parity.executableReady()).isFalse();
                });
    }

    @Test
    void requiresAuthoritativeFunctionSignatureMetadataBeforeBinding() {
        OperatorLibrary.BuiltInFunction contract =
                FrameworkFunctionInventoryTest.functionContract("normalize", "string");
        FrameworkFunctionInventory exactInventory = new FrameworkFunctionInventory(List.of(
                FrameworkFunctionInventoryTest.provider(
                        "business-functions",
                        "production",
                        List.of(new FrameworkFunctionInventoryProvider.FunctionBinding(
                                "normalize",
                                FrameworkFunctionInventoryTest.function("normalize"),
                                contract)))
        ));
        RuntimeParityService exactService = new RuntimeParityService(
                JavaOperatorInventoryProjector.forRegistry(null),
                exactInventory);

        assertThat(exactService.evaluate(library(List.of(), List.of(contract))).parity())
                .singleElement()
                .satisfies(parity -> {
                    assertThat(parity.state()).isEqualTo("BOUND");
                    assertThat(parity.executableReady()).isTrue();
                });

        OperatorLibrary.BuiltInFunction drifted =
                FrameworkFunctionInventoryTest.functionContract("normalize", "number");
        assertThat(exactService.evaluate(library(List.of(), List.of(drifted))).parity())
                .singleElement()
                .satisfies(parity -> {
                    assertThat(parity.state()).isEqualTo("DRIFTED");
                    assertThat(parity.reasonCode())
                            .isEqualTo("RG.AUTHORING.RUNTIME_FUNCTION_SIGNATURE_DRIFT");
                });

        FrameworkFunctionInventory implementationOnly = new FrameworkFunctionInventory(List.of(
                FrameworkFunctionInventoryTest.provider(
                        "implementation-only",
                        "production",
                        List.of(new FrameworkFunctionInventoryProvider.FunctionBinding(
                                "normalize",
                                FrameworkFunctionInventoryTest.function("normalize"),
                                null)))
        ));
        RuntimeParityService implementationOnlyService = new RuntimeParityService(
                JavaOperatorInventoryProjector.forRegistry(null),
                implementationOnly);

        assertThat(implementationOnlyService.evaluate(
                library(List.of(), List.of(contract))).parity())
                .singleElement()
                .satisfies(parity -> {
                    assertThat(parity.state()).isEqualTo("RUNTIME_DISCOVERED");
                    assertThat(parity.reasonCode())
                            .isEqualTo("RG.AUTHORING.RUNTIME_FUNCTION_SIGNATURE_UNKNOWN");
                    assertThat(parity.executableReady()).isFalse();
                });
    }

    @Test
    void treatsDslReferencesAsObservationsRatherThanExecutableContracts() {
        RuntimeParityService service = new RuntimeParityService(
                JavaOperatorInventoryProjector.forRegistry(null),
                new FrameworkFunctionInventory(List.of(
                        new CoreFrameworkFunctionInventoryProvider())));

        RuntimeParityService.Snapshot snapshot = service.evaluateReferences(
                Set.of("business:missing"),
                Set.of("coalesce", "businessMissing"));

        assertThat(snapshot.runtimeReady()).isFalse();
        assertThat(snapshot.parity()).anySatisfy(parity -> {
            assertThat(parity.assetRef()).isEqualTo("coalesce");
            assertThat(parity.state()).isEqualTo("RUNTIME_DISCOVERED");
        });
        assertThat(snapshot.parity()).anySatisfy(parity -> {
            assertThat(parity.assetRef()).isEqualTo("business:missing");
            assertThat(parity.state()).isEqualTo("DOCUMENTED_ONLY");
        });
        assertThat(snapshot.parity()).anySatisfy(parity -> {
            assertThat(parity.assetRef()).isEqualTo("businessMissing");
            assertThat(parity.state()).isEqualTo("DOCUMENTED_ONLY");
        });
    }

    private static OperatorLibrary library(
            List<OperatorDefinition> operators,
            List<OperatorLibrary.BuiltInFunction> functions) {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "runtime-parity-test",
                "Runtime parity test",
                "1.0.0",
                "test",
                OperatorLibrary.STATUS_ACTIVE,
                functions,
                operators);
    }
}
