package com.leanowtech.bloge.gateway.testkit;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small payload-free command-line revalidation entry point for Browser Matrix Result v1. */
public final class CapabilityStudioBrowserMatrixResultCli {
    private static final int EXIT_COMPLETE = 0;
    private static final int EXIT_INVALID = 2;
    private static final int EXIT_NOT_COMPLETE = 3;

    private CapabilityStudioBrowserMatrixResultCli() {
    }

    /**
     * Revalidates one artifact path and returns a process exit code without terminating the JVM.
     *
     * @param args one artifact path
     * @param out payload-free result stream
     * @param err reserved error stream
     * @return zero for COMPLETE, two for invalid, three for valid non-COMPLETE
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
            out.println("INVALID errorCode=RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_CLI_USAGE");
            return EXIT_INVALID;
        }
        try {
            CapabilityStudioBrowserMatrixResultVerifier.VerificationResult result =
                    new CapabilityStudioBrowserMatrixResultVerifier().verify(
                            Files.readAllBytes(Path.of(args[0])));
            if (!result.verified()) {
                out.println("INVALID errorCode=" + result.errorCode());
                return EXIT_INVALID;
            }
            String status = result.artifactStatus().name();
            if (result.artifactStatus()
                    != CapabilityStudioBrowserMatrixResultVerifier.ArtifactStatus.COMPLETE) {
                out.println("VALID status=" + status);
                return EXIT_NOT_COMPLETE;
            }
            out.println("VALID status=" + status);
            return EXIT_COMPLETE;
        } catch (IOException | RuntimeException failure) {
            out.println("INVALID errorCode=RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_CLI_READ");
            return EXIT_INVALID;
        }
    }

    /**
     * Standard process entry point.
     *
     * @param args one artifact path
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }
}
