package com.leanowtech.bloge.gateway.testkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioFormalEvidenceRunVerifyCliTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void structureVerifiedIsAlwaysNonZeroAndPayloadFree() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(temporaryDirectory);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = CapabilityStudioFormalEvidenceRunVerifyCli.run(
                new String[]{"--manifest", fixture.manifest().toString(),
                        "--bundle-root", fixture.root().toString()},
                new PrintStream(bytes, true, StandardCharsets.UTF_8));

        assertThat(exit).isEqualTo(4);
        assertThat(bytes.toString(StandardCharsets.UTF_8))
                .startsWith("NOT_VERIFIED outcome=STRUCTURE_VERIFIED")
                .contains("typedReplayCount=1", "passed=0", "evidenceCount=1",
                        "terminalClass=LOCAL_TYPED_REPLAY_ONLY",
                        "formalConclusion=INCOMPLETE")
                .doesNotContain("VERIFIED status=", "ACCEPTED", "DEVELOPMENT_VERIFIED");
    }

    @Test
    void incompleteIsNonZeroAndMissingIsUnavailable() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.empty(temporaryDirectory);
        fixture.write();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int incomplete = CapabilityStudioFormalEvidenceRunVerifyCli.run(
                new String[]{"--manifest", fixture.manifest().toString(),
                        "--bundle-root", fixture.root().toString()},
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        assertThat(incomplete).isEqualTo(4);
        assertThat(bytes.toString(StandardCharsets.UTF_8))
                .startsWith("NOT_VERIFIED outcome=INCOMPLETE")
                .contains("terminalClass=LOCAL_TYPED_REPLAY_ONLY",
                        "formalConclusion=INCOMPLETE");

        bytes.reset();
        int unavailable = CapabilityStudioFormalEvidenceRunVerifyCli.run(
                new String[]{"--manifest", temporaryDirectory.toRealPath()
                        .resolve("missing.json").toString(),
                        "--bundle-root", fixture.root().toString()},
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        assertThat(unavailable).isEqualTo(3);
        assertThat(bytes.toString(StandardCharsets.UTF_8))
                .isEqualTo("NOT_VERIFIED outcome=UNAVAILABLE reasonCode="
                        + "RG.CAPABILITY_STUDIO.FORMAL_EVIDENCE_RUN_VERIFY.UNAVAILABLE"
                        + " terminalClass=LOCAL_TYPED_REPLAY_ONLY"
                        + " formalConclusion=INCOMPLETE\n");
    }

    @Test
    void invalidArgumentsAndOutputFailureAreClosed() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int invalid = CapabilityStudioFormalEvidenceRunVerifyCli.run(
                new String[]{"--bundle-root", "secret"},
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        assertThat(invalid).isEqualTo(2);
        assertThat(bytes.toString(StandardCharsets.UTF_8))
                .isEqualTo("INVALID reasonCode=RG.CAPABILITY_STUDIO.FORMAL_EVIDENCE_RUN_VERIFY.INVALID"
                        + " terminalClass=LOCAL_TYPED_REPLAY_ONLY"
                        + " formalConclusion=INCOMPLETE\n");

        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.empty(temporaryDirectory);
        int outputFailure = CapabilityStudioFormalEvidenceRunVerifyCli.run(
                new String[]{"--manifest", fixture.manifest().toString(),
                        "--bundle-root", fixture.root().toString()},
                new PrintStream(new FailingOutputStream(), true, StandardCharsets.UTF_8));
        assertThat(outputFailure).isEqualTo(2);
    }

    private static final class FailingOutputStream extends OutputStream {
        @Override
        public void write(int value) throws IOException {
            throw new IOException("do not expose");
        }
    }
}
