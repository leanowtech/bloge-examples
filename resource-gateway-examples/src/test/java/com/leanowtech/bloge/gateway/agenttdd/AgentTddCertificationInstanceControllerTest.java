package com.leanowtech.bloge.gateway.agenttdd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Tests the opt-in, payload-free runtime identity used by real Codex certification. */
class AgentTddCertificationInstanceControllerTest {

    @Test
    void exposesTheExactSpawnNonceCommitAndJarDigest() {
        var controller = new AgentTddCertificationInstanceController(
                "2f61cd9e2ca34e169ff50fd64b8d39aa",
                "0123456789abcdef0123456789abcdef01234567",
                "sha256:" + "a".repeat(64));

        assertThat(controller.identity()).isEqualTo(new AgentTddCertificationInstanceController.InstanceIdentity(
                "rg.agentTddCertificationInstance.v1",
                "2f61cd9e2ca34e169ff50fd64b8d39aa",
                "0123456789abcdef0123456789abcdef01234567",
                "sha256:" + "a".repeat(64)));
    }

    @Test
    void rejectsIncompleteOrMalformedLaunchIdentity() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AgentTddCertificationInstanceController(
                "", "0123456789abcdef0123456789abcdef01234567", "sha256:" + "a".repeat(64)));
        assertThatIllegalArgumentException().isThrownBy(() -> new AgentTddCertificationInstanceController(
                "2f61cd9e2ca34e169ff50fd64b8d39aa", "dirty", "sha256:" + "a".repeat(64)));
        assertThatIllegalArgumentException().isThrownBy(() -> new AgentTddCertificationInstanceController(
                "2f61cd9e2ca34e169ff50fd64b8d39aa",
                "0123456789abcdef0123456789abcdef01234567", "a".repeat(64)));
    }
}
