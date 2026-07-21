package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPlaneCertificateRotationConvergencePropertiesTest {

    private static final String ARTIFACT = "sha256:" + "a".repeat(64);
    private static final String MATERIAL = "sha256:" + "b".repeat(64);
    private static final String POLICY = "sha256:" + "c".repeat(64);

    @Test
    void singleReplicaLocalConfigurationMaterializesAnExactAllReplicaPolicy() {
        String startupId = UUID.randomUUID().toString();
        var properties = properties("replica-a", "replica-a", startupId,
                "LOCAL_CONFIGURED", 0, "", "", "", 1);

        var policy = properties.policy("deployment-a");

        assertThat(policy.deploymentScopeId()).isEqualTo("deployment-a");
        assertThat(policy.expectedInstanceIds()).containsExactly("replica-a");
        assertThat(policy.requiredStagedReplicas()).isEqualTo(1);
        assertThat(policy.inventoryAttestation().externallyAttested()).isFalse();
        assertThat(properties.required()).isTrue();
    }

    @Test
    void multiReplicaConfigurationRequiresAndPreservesExternalInventoryAuthority() {
        Instant expiry = Instant.now().plusSeconds(3_600);
        var properties = properties("replica-a,replica-b", "replica-a",
                UUID.randomUUID().toString(), "DEPLOYMENT_SIGNED", 9,
                MATERIAL, POLICY, expiry.toString(), 2);

        var inventory = properties.policy("deployment-a").inventoryAttestation();

        assertThat(inventory.externallyAttested()).isTrue();
        assertThat(inventory.revision()).isEqualTo(9);
        assertThat(inventory.materialFingerprint()).isEqualTo(MATERIAL);
        assertThat(inventory.policyFingerprint()).isEqualTo(POLICY);
        assertThat(inventory.expiresAt()).isEqualTo(expiry);
    }

    @Test
    void disabledConfigurationRejectsResidualStateAndRequiredDowngrade() {
        assertThat(ControlPlaneCertificateRotationConvergenceProperties.disabled().enabled())
                .isFalse();
        assertThatThrownBy(() -> new ControlPlaneCertificateRotationConvergenceProperties(
                false, false, "residual", "", "", "", "", "", "", 0,
                null, null, null, "", null, "", "", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlPlaneCertificateRotationConvergenceProperties(
                false, true, "", "", "", "", "", "", "", 0,
                null, null, null, "", null, "", "", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsignedMultiReplicaQuorumThresholdDriftAndInvalidProcessIdentity() {
        assertThatThrownBy(() -> properties("replica-a,replica-b", "replica-a",
                UUID.randomUUID().toString(), "LOCAL_CONFIGURED", 0,
                "", "", "", 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("replica-a,replica-b", "replica-a",
                UUID.randomUUID().toString(), "DEPLOYMENT_SIGNED", 1,
                MATERIAL, POLICY, Instant.now().plusSeconds(60).toString(), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("replica-a", "replica-a",
                "not-a-process-uuid", "LOCAL_CONFIGURED", 0,
                "", "", "", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlPlaneCertificateRotationConvergenceProperties(
                true, true, "fleet-a", "replica-a", UUID.randomUUID().toString(),
                ARTIFACT, "replica-a", "protocol-v1", "FENCED_QUORUM", 1,
                1L, 3L, 3_600L, "LOCAL_CONFIGURED", 0L, "", "", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ControlPlaneCertificateRotationConvergenceProperties properties(
            String instances,
            String instanceId,
            String startupId,
            String sourceType,
            long revision,
            String materialFingerprint,
            String policyFingerprint,
            String expiresAt,
            int threshold) {
        return new ControlPlaneCertificateRotationConvergenceProperties(
                true, true, "fleet-2026-07", instanceId, startupId, ARTIFACT,
                instances, "protocol-v1", "ALL_REPLICAS", threshold,
                1L, 3L, 3_600L, sourceType, revision, materialFingerprint,
                policyFingerprint, expiresAt);
    }
}
