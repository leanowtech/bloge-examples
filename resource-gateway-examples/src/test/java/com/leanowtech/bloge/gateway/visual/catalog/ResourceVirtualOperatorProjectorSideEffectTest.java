package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.resource.VisualResourceDescriptor;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceParameterMapping;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceResponseProtocol;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceVirtualOperatorProjectorSideEffectTest {

    @Test
    void keepsUnmanagedMutationDesignOnlyAndProjectsManagedDescriptorContract() {
        ResourceVirtualOperatorProjector projector = new ResourceVirtualOperatorProjector();
        OperatorDefinition unmanaged = projector.project(descriptor(null), Optional.empty());
        OperatorDefinition managed = projector.project(descriptor(new VisualResourceDescriptor.ExternalWriteContract(
                VisualResourceDescriptor.ResourceDescriptorContract.SCHEMA_VERSION,
                "idempotencyKey", "Idempotency-Key", "lookupRef", "orders.status",
                "X-Commit-Receipt", "X-Transaction-Id", "orders", "", "", false)), Optional.empty());

        assertThat(unmanaged.capabilities().sideEffectProtocol().managedWrite()).isFalse();
        assertThat(unmanaged.runtimeReadiness().state()).isEqualTo("RUNTIME_BLOCKED");
        assertThat(unmanaged.runtimeReadiness().artifactKinds()).containsExactly("DESIGN");
        assertThat(managed.capabilities().sideEffectProtocol().managedWrite()).isTrue();
        assertThat(managed.capabilities().sideEffectProtocol().reconciliationLookupSource())
                .isEqualTo("params.lookupRef");
        assertThat(managed.runtimeReadiness().state()).isEqualTo("GOVERNANCE_REVIEW");
        assertThat(managed.runtimeReadiness().executable()).isTrue();
    }

    private static VisualResourceDescriptor descriptor(
            VisualResourceDescriptor.ExternalWriteContract externalWriteContract) {
        return new VisualResourceDescriptor(
                "orders.create", "https://orders.example.test/orders", "POST", Map.of(), null,
                Duration.ofSeconds(5), VisualResourceParameterMapping.empty(),
                new VisualResourceResponseProtocol.HttpStatus(), "", externalWriteContract);
    }
}
