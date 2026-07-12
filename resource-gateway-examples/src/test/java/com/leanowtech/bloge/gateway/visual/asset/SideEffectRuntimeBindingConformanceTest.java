package com.leanowtech.bloge.gateway.visual.asset;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeAdapterActivation;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeAdapterActivationValidation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SideEffectRuntimeBindingConformanceTest {

    @Test
    void rejectsExternalWriteBindingWithoutProtocolCapabilitiesAndFaultEvidence() {
        OperatorDefinition operator = managedWriteOperator();
        VisualRuntimeBindingImplementationValidation.Request request = request(operator,
                List.of("HTTP"),
                List.of(new VisualRuntimeBindingImplementationValidation.Evidence(
                        "unit-test", "ci://orders/42", "Happy-path test passed.")));

        VisualRuntimeBindingImplementationValidation validation =
                VisualRuntimeBindingImplementationValidation.from(request, operator);

        assertThat(validation.valid()).isFalse();
        assertThat(validation.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .contains("visual.runtimeBindingImplementation.sideEffectCapabilitiesMissing",
                        "visual.runtimeBindingImplementation.sideEffectTestEvidenceMissing");
    }

    @Test
    void acceptsCompleteBindingButRequiresLiveReconcilerHealthAtActivation() {
        OperatorDefinition operator = managedWriteOperator();
        List<String> protocolCapabilities = List.of(
                "SIDE_EFFECT_JOURNAL_V1", "COMMIT_RECEIPT_V1", "RECONCILIATION_LOOKUP_V1");
        List<VisualRuntimeBindingImplementationValidation.Evidence> tests = List.of(
                new VisualRuntimeBindingImplementationValidation.Evidence(
                        "side-effect-conformance", "ci://orders/conformance", "Journal and receipt contract passed."),
                new VisualRuntimeBindingImplementationValidation.Evidence(
                        "unknown-commit-fault", "ci://orders/timeout", "Timeout remained unknown and blocked retry."));
        VisualRuntimeBindingImplementationValidation.Request request = request(operator, protocolCapabilities, tests);
        VisualRuntimeBindingImplementationValidation validation =
                VisualRuntimeBindingImplementationValidation.from(request, operator);
        assertThat(validation.valid()).isTrue();
        assertThat(validation.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .doesNotContain("visual.runtimeBindingImplementation.sideEffectCapabilitiesMissing",
                        "visual.runtimeBindingImplementation.sideEffectTestEvidenceMissing");

        VisualRuntimeBindingImplementationBinding binding =
                VisualRuntimeBindingImplementationBinding.from(request, validation)
                        .withIdentity("binding-orders-v1", 1, Instant.parse("2026-07-12T12:00:00Z"),
                                Instant.parse("2026-07-12T12:00:00Z"))
                        .withLifecycleTransition(
                                VisualRuntimeBindingImplementationBinding.STATE_BOUND, "success", "", "",
                                new VisualRuntimeBindingImplementationBinding.LifecycleEvent(
                                        "bound", "ready-to-bind", "bound", "runtime-platform", "test",
                                        "Approved conformance evidence.", "", "",
                                        Instant.parse("2026-07-12T12:01:00Z")),
                                Instant.parse("2026-07-12T12:01:00Z"));

        VisualRuntimeAdapterActivationValidation withoutReconcilerHealth =
                VisualRuntimeAdapterActivationValidation.from(
                        activationRequest(binding, List.of(new VisualRuntimeAdapterActivation.Evidence(
                                "health-check", "deployment://orders/v1", "Runtime is healthy."))),
                        binding, operator);
        VisualRuntimeAdapterActivationValidation withReconcilerHealth =
                VisualRuntimeAdapterActivationValidation.from(
                        activationRequest(binding, List.of(
                                new VisualRuntimeAdapterActivation.Evidence(
                                        "health-check", "deployment://orders/v1", "Runtime is healthy."),
                                new VisualRuntimeAdapterActivation.Evidence(
                                        "reconciler-health", "probe://orders/status", "Status lookup is healthy."))),
                        binding, operator);

        assertThat(withoutReconcilerHealth.valid()).isFalse();
        assertThat(withoutReconcilerHealth.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .contains("visual.runtimeAdapterActivation.reconcilerHealthEvidenceMissing");
        assertThat(withReconcilerHealth.valid()).isTrue();
    }

    private static VisualRuntimeBindingImplementationValidation.Request request(
            OperatorDefinition operator,
            List<String> capabilities,
            List<VisualRuntimeBindingImplementationValidation.Evidence> testEvidence) {
        return new VisualRuntimeBindingImplementationValidation.Request(
                VisualRuntimeBindingImplementationValidation.REQUEST_SCHEMA_VERSION,
                operator.operatorRef(), operator.fingerprint(), "sha256:" + "a".repeat(64), List.of(),
                VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot.from(operator),
                new VisualRuntimeBindingImplementationValidation.ImplementationMetadata(
                        "binding-orders-v1", "native", "orders:create", "orders-runtime", "1.0.0", "", "",
                        capabilities, testEvidence,
                        List.of(new VisualRuntimeBindingImplementationValidation.Evidence(
                                "approval", "change://orders/42", "Risk owner approved.")),
                        "orders:create:v0",
                        new VisualRuntimeBindingImplementationValidation.RolloutPlan(
                                "canary", 10, 100, "orders_error_rate", "PT30M",
                                List.of(new VisualRuntimeBindingImplementationValidation.Evidence(
                                        "canary-plan", "deploy://orders/canary", "Canary plan reviewed.")), ""),
                        ""));
    }

    private static VisualRuntimeAdapterActivationValidation.Request activationRequest(
            VisualRuntimeBindingImplementationBinding binding,
            List<VisualRuntimeAdapterActivation.Evidence> evidence) {
        return new VisualRuntimeAdapterActivationValidation.Request(
                VisualRuntimeAdapterActivationValidation.REQUEST_SCHEMA_VERSION,
                "activation-orders-v1", binding.bindingId(), binding.revision(), binding.operatorRef(),
                binding.operatorFingerprint(), binding.implementation().adapterKind(),
                binding.implementation().entrypoint(), binding.implementation().runtimeOwner(), "prod",
                VisualRuntimeAdapterActivation.HEALTH_HEALTHY, "runtime-platform", "test",
                "Activate managed external write.", evidence);
    }

    private static OperatorDefinition managedWriteOperator() {
        return new OperatorDefinition(
                "bloge.visualOperator.v1", "orders:create", "1.0.0",
                new OperatorDefinition.Display("Create order", "", List.of("orders")),
                new OperatorDefinition.Source("user-library", "", "POST", "", false, "orders"),
                new OperatorDefinition.Ports(List.of(), List.of()), SchemaEnvelope.opaque(),
                new OperatorDefinition.Capabilities(
                        "WRITE_EXTERNAL", "IDEMPOTENT", false, false, false,
                        OperatorDefinition.SideEffectProtocol.journaled(
                                "orders.status", "input.idempotencyKey", "input.lookupRef",
                                "response.headers.X-Commit-Receipt")),
                new OperatorDefinition.Lowering("native", "orders:create", Map.of()), List.of());
    }
}
