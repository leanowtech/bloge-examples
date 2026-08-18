package com.leanowtech.bloge.gateway.capabilitystudio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioDeploymentCandidateAuthorityTest {

    @Test
    void retainsTheCompleteDeploymentOwnedCandidateIdentity() {
        CapabilityStudioDeploymentCandidateAuthority authority =
                new CapabilityStudioDeploymentCandidateAuthority(
                        "deployment-launcher",
                        "resource-gateway-local-01",
                        "resource-gateway-examples",
                        "1.0.0",
                        "ABCDEF0123456789",
                        "CLEAN",
                        fingerprint('a'));

        assertThat(authority.current()).hasValueSatisfying(binding -> {
            assertThat(binding.authority()).isEqualTo("deployment-launcher");
            assertThat(binding.instanceId()).isEqualTo("resource-gateway-local-01");
            assertThat(binding.buildRef()).isEqualTo("resource-gateway-examples");
            assertThat(binding.revision()).isEqualTo("1.0.0");
            assertThat(binding.sourceCommit()).isEqualTo("abcdef0123456789");
            assertThat(binding.sourceTreeStatus()).isEqualTo("CLEAN");
            assertThat(binding.artifactFingerprint()).isEqualTo(fingerprint('a'));
        });
    }

    @Test
    void representsACompletelyAbsentCandidateAsUnbound() {
        assertThat(CapabilityStudioDeploymentCandidateAuthority.unbound().current()).isEmpty();
    }

    @Test
    void rejectsPartialCandidateConfiguration() {
        assertThatThrownBy(() -> new CapabilityStudioDeploymentCandidateAuthority(
                "deployment-launcher", "resource-gateway-local-01", "", "1.0.0",
                "abcdef0", "CLEAN", fingerprint('a')))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("completely configured");
    }

    @Test
    void rejectsNonCanonicalArtifactFingerprintAndSourceCommit() {
        assertThatThrownBy(() -> new CapabilityStudioDeploymentCandidateAuthority(
                "deployment-launcher", "resource-gateway-local-01",
                "resource-gateway-examples", "1.0.0", "not-a-commit", "CLEAN",
                fingerprint('a')))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceCommit");

        assertThatThrownBy(() -> new CapabilityStudioDeploymentCandidateAuthority(
                "deployment-launcher", "resource-gateway-local-01",
                "resource-gateway-examples", "1.0.0", "abcdef0", "CLEAN", "sha256:ABC"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("artifactFingerprint");

        assertThatThrownBy(() -> new CapabilityStudioDeploymentCandidateAuthority(
                "deployment-launcher", "resource-gateway-local-01",
                "resource-gateway-examples", "1.0.0", "abcdef0", "DIRTY",
                fingerprint('a')))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceTreeStatus");
    }

    private static String fingerprint(char fill) {
        return "sha256:" + String.valueOf(fill).repeat(64);
    }
}
