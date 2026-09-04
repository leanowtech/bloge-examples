package com.leanowtech.bloge.gateway.agenttdd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/** Tests the opt-in, payload-free runtime identity used by real Codex certification. */
class AgentTddCertificationInstanceControllerTest {
    @TempDir
    Path tempDir;

    @Test
    void exposesTheExactSpawnNonceCommitAndJarDigest() {
        var controller = new AgentTddCertificationInstanceController(
                "2f61cd9e2ca34e169ff50fd64b8d39aa",
                "0123456789abcdef0123456789abcdef01234567",
                "sha256:" + "a".repeat(64),
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
                "", "0123456789abcdef0123456789abcdef01234567",
                "sha256:" + "a".repeat(64), "sha256:" + "a".repeat(64)));
        assertThatIllegalArgumentException().isThrownBy(() -> new AgentTddCertificationInstanceController(
                "2f61cd9e2ca34e169ff50fd64b8d39aa", "dirty",
                "sha256:" + "a".repeat(64), "sha256:" + "a".repeat(64)));
        assertThatIllegalArgumentException().isThrownBy(() -> new AgentTddCertificationInstanceController(
                "2f61cd9e2ca34e169ff50fd64b8d39aa",
                "0123456789abcdef0123456789abcdef01234567",
                "a".repeat(64), "sha256:" + "a".repeat(64)));
    }

    @Test
    void rejectsAReplacementArchiveWhoseActualDigestDiffersFromTheExpectedDigest() {
        assertThatIllegalStateException().isThrownBy(() -> new AgentTddCertificationInstanceController(
                "2f61cd9e2ca34e169ff50fd64b8d39aa",
                "0123456789abcdef0123456789abcdef01234567",
                "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64)))
                .withMessageContaining("does not match");
    }

    @Test
    void hashesOnlyTheActualRegularArchiveBytes() throws Exception {
        Path archive = tempDir.resolve("resource-gateway.jar");
        Files.writeString(archive, "owned certification archive");

        assertThat(AgentTddCertificationInstanceController.archiveSha256(archive))
                .isEqualTo("sha256:f74218420c5a4041dc77ab5390064c738b1025d6ec3f583fe3239a69574f6d4e");
        assertThatIllegalStateException().isThrownBy(() ->
                AgentTddCertificationInstanceController.archiveSha256(tempDir));
    }
}
