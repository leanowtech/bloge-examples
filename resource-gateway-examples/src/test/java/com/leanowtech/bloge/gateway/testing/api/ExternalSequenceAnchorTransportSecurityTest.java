package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalSequenceAnchorTransportSecurityTest {

    private static final ControlPlaneHttpTransport.Descriptor PINNED_MTLS =
            new ControlPlaneHttpTransport.Descriptor(
                    ControlPlaneHttpTransport.Descriptor.SCHEMA_VERSION,
                    false, true, true, true);

    @Test
    void compatibilityTruthIsExplicitSystemTrustWithoutInventingRemoteSources() {
        ExternalSequenceAnchorTransportSecurity security =
                ExternalSequenceAnchorTransportSecurity.compatibility();

        assertThat(security.notary()).satisfies(notary -> {
            assertThat(notary.systemTrustStore()).isTrue();
            assertThat(notary.serverSpkiPinned()).isFalse();
            assertThat(notary.mutualTls()).isFalse();
        });
        assertThat(security.managedTrustPublication()).isEmpty();
        assertThat(security.bootstrapRootBundle()).isEmpty();
    }

    @Test
    void completeRootSourceCannotExistWithoutManagedTrustPublication() {
        assertThatThrownBy(() -> new ExternalSequenceAnchorTransportSecurity(
                ExternalSequenceAnchorTransportSecurity.SCHEMA_VERSION,
                PINNED_MTLS, Optional.empty(), Optional.of(PINNED_MTLS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transport security");
    }

    @Test
    void threeIndependentLinksCanProjectPinnedMutualTlsWithoutIdentityMaterial() {
        ExternalSequenceAnchorTransportSecurity security =
                new ExternalSequenceAnchorTransportSecurity(
                        ExternalSequenceAnchorTransportSecurity.SCHEMA_VERSION,
                        PINNED_MTLS, Optional.of(PINNED_MTLS), Optional.of(PINNED_MTLS));

        assertThat(security.toString()).doesNotContain(
                "endpoint", "keystore", "secret", "sha256:", "certificate=");
        assertThat(security.managedTrustPublication()).get()
                .extracting(ControlPlaneHttpTransport.Descriptor::mutualTls)
                .isEqualTo(true);
        assertThat(security.bootstrapRootBundle()).get()
                .extracting(ControlPlaneHttpTransport.Descriptor::serverSpkiPinned)
                .isEqualTo(true);
        assertThat(security.asMap().toString())
                .contains("serverSpkiPinned=true", "mutualTls=true")
                .doesNotContain("endpoint", "keystore", "secret", "sha256:",
                        "certificate=");
    }

    @Test
    void descriptorRejectsMalformedTransportProjection() {
        assertThatThrownBy(() -> new TestSuiteStabilityExternalSequenceAnchor.Descriptor(
                TestSuiteStabilityExternalSequenceAnchor.Descriptor.SCHEMA_VERSION,
                true, true, true, true, 4, 3, 1, 4,
                Map.of("transportSecurity", Map.of("mutualTls", true))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid external sequence-anchor descriptor");
    }
}
