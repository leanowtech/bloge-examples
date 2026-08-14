package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeCertificationReplayBundleIntegrityTest {
    private final RuntimeCertificationReplayBundleIntegrity integrity =
            new RuntimeCertificationReplayBundleIntegrity(
                    new ObjectMapper().findAndRegisterModules());

    @Test
    void addressesTheCompleteRegionalAndRuntimeClosure() {
        RuntimeCertificationReplayBundle bundle =
                RuntimeCertificationProtocolFixtures.replayBundle();

        assertThat(integrity.canonicalVerified(bundle)).isTrue();
        assertThat(bundle.report().regionalDataPlaneCertificationRef())
                .isEqualTo(bundle.regionalCertification().artifactRef());
        assertThat(bundle.report().isolationDecisionRef())
                .isEqualTo(bundle.isolationDecision().artifactRef());
        assertThat(bundle.report().isolationAttestationRef())
                .isEqualTo(bundle.isolationDecision().attestation().artifactRef());
    }

    @Test
    void tamperedAddressAndCrossScopeAssemblyFailClosed() {
        RuntimeCertificationReplayBundle source =
                RuntimeCertificationProtocolFixtures.replayBundle();
        RuntimeCertificationReplayBundle tampered = new RuntimeCertificationReplayBundle(
                source.schemaVersion(), RuntimeCertificationTestFixtures.fingerprint('0'),
                source.bundleId(), source.revision(), source.manifest(), source.authorization(),
                source.report(), source.regionalContract(), source.regionalCertification(),
                source.isolationDecision(), source.exportedAt(), source.exporter());

        assertThat(integrity.canonicalVerified(tampered)).isFalse();
        assertThatThrownBy(() -> new RuntimeCertificationReplayBundle(
                source.schemaVersion(), source.bundleFingerprint(), source.bundleId(),
                source.revision(), source.manifest(), source.authorization(), source.report(),
                RegionalDataPlaneProtocolFixtures.contract(),
                source.regionalCertification(),
                new RegionalDataPlaneCertificationTestFixtures().isolationV2,
                source.exportedAt(), source.exporter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("closure is invalid");
    }
}
