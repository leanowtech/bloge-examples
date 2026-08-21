package com.leanowtech.bloge.gateway.testkit;

import java.io.PrintStream;
import java.nio.file.Path;

/** Payload-free Gate A CLI; no local result is a publishable verification. */
public final class CapabilityStudioFormalEvidenceRunVerifyCli {
    /** Exit code for invalid arguments, evidence, metadata, or output failure. */
    public static final int EXIT_INVALID = 2;
    /** Exit code when required evidence or filesystem metadata is unavailable. */
    public static final int EXIT_UNAVAILABLE = 3;
    /** Non-zero exit code for both structure-verified and incomplete Gate A results. */
    public static final int EXIT_STRUCTURE_VERIFIED = 4;
    private static final String PREFIX =
            "RG.CAPABILITY_STUDIO.FORMAL_EVIDENCE_RUN_VERIFY.";
    private static final String TERMINAL =
            " terminalClass=LOCAL_TYPED_REPLAY_ONLY formalConclusion=INCOMPLETE";

    private CapabilityStudioFormalEvidenceRunVerifyCli() {
    }

    /**
     * Runs payload-free formal evidence verification.
     *
     * @param args {@code --manifest <path> --bundle-root <path>}
     * @param output destination for the single-line summary
     * @return one of the declared non-zero exit codes
     */
    public static int run(String[] args, PrintStream output) {
        if (output == null || args == null || args.length != 4
                || !"--manifest".equals(args[0]) || !"--bundle-root".equals(args[2])
                || args[1] == null || args[1].isEmpty()
                || args[3] == null || args[3].isEmpty()) {
            return write(output, "INVALID reasonCode=" + PREFIX + "INVALID" + TERMINAL)
                    ? EXIT_INVALID : EXIT_INVALID;
        }
        try {
            CapabilityStudioFormalEvidenceRunVerifier.Verification result =
                    CapabilityStudioFormalEvidenceRunVerifier.verify(
                            Path.of(args[1]), Path.of(args[3]));
            String outcome = result.verificationLevel();
            String line = "NOT_VERIFIED outcome=" + outcome
                    + " verificationLevel=" + outcome
                    + " typedReplayCount=" + result.typedReplayCount()
                    + " passed=" + result.passed()
                    + " failed=" + result.failed()
                    + " blocked=" + result.blocked()
                    + " notRun=" + result.notRun()
                    + " evidenceCount=" + result.evidenceCount()
                    + " evidenceBytes=" + result.evidenceByteSize()
                    + " reasonCode=" + PREFIX + outcome
                    + TERMINAL;
            return write(output, line) ? EXIT_STRUCTURE_VERIFIED : EXIT_INVALID;
        } catch (CapabilityStudioFormalEvidenceRunVerifier.VerificationException failure) {
            String line = failure.failureKind()
                    == CapabilityStudioFormalEvidenceRunVerifier.FailureKind.UNAVAILABLE
                    ? "NOT_VERIFIED outcome=UNAVAILABLE reasonCode=" + PREFIX + "UNAVAILABLE"
                    + TERMINAL
                    : "INVALID reasonCode=" + PREFIX + "INVALID" + TERMINAL;
            return write(output, line)
                    ? failure.failureKind()
                    == CapabilityStudioFormalEvidenceRunVerifier.FailureKind.UNAVAILABLE
                    ? EXIT_UNAVAILABLE : EXIT_INVALID : EXIT_INVALID;
        } catch (RuntimeException failure) {
            return write(output, "INVALID reasonCode=" + PREFIX + "INVALID" + TERMINAL)
                    ? EXIT_INVALID : EXIT_INVALID;
        }
    }

    /**
     * Command-line entry point.
     *
     * @param args {@code --manifest <path> --bundle-root <path>}
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    private static boolean write(PrintStream output, String line) {
        if (output == null) {
            return false;
        }
        try {
            output.println(line);
            output.flush();
            return !output.checkError();
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
