package com.leanowtech.bloge.gateway.testkit;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Payload-free command-line revalidation entry point for Browser Anomaly Matrix Result v1.
 *
 * <p>The command requires both the anomaly result and the exact normal browser matrix result.
 * The normal matrix is always read and verified independently by the anomaly verifier; a
 * caller-supplied reference alone is never treated as proof of the base artifact.</p>
 */
public final class CapabilityStudioBrowserAnomalyMatrixResultCli {
    /** Exit code for a valid complete result. */
    public static final int EXIT_COMPLETE = 0;
    /** Exit code for usage, read, schema, semantic or base-binding failure. */
    public static final int EXIT_INVALID = 2;
    /** Exit code for a valid failed or not-run result. */
    public static final int EXIT_NOT_COMPLETE = 3;

    private static final String USAGE_ERROR =
            "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_CLI_USAGE";
    private static final String READ_ERROR =
            "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_CLI_READ";
    private static final String INVALID_ERROR =
            "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_CLI_INVALID";

    private CapabilityStudioBrowserAnomalyMatrixResultCli() {
    }

    /**
     * Revalidates an anomaly result against its exact base result without terminating the JVM.
     *
     * @param args exactly two paths: anomaly result, then exact base browser matrix result
     * @param out payload-free result stream
     * @param err reserved error stream; this method never writes payloads, paths or exceptions
     * @return {@link #EXIT_COMPLETE}, {@link #EXIT_NOT_COMPLETE}, or {@link #EXIT_INVALID}
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args == null || args.length != 2 || blank(args[0]) || blank(args[1])) {
            out.println("INVALID errorCode=" + USAGE_ERROR);
            return EXIT_INVALID;
        }

        final byte[] anomalyBytes;
        final byte[] baseBytes;
        try {
            anomalyBytes = Files.readAllBytes(Path.of(args[0]));
            baseBytes = Files.readAllBytes(Path.of(args[1]));
        } catch (IOException | RuntimeException readFailure) {
            out.println("INVALID errorCode=" + READ_ERROR);
            return EXIT_INVALID;
        }

        final CapabilityStudioBrowserAnomalyMatrixResultVerifier.VerificationResult result;
        try {
            result = new CapabilityStudioBrowserAnomalyMatrixResultVerifier()
                    .verify(anomalyBytes, baseBytes);
        } catch (RuntimeException verifierFailure) {
            out.println("INVALID errorCode=" + INVALID_ERROR);
            return EXIT_INVALID;
        }
        if (!result.verified()) {
            out.println("INVALID errorCode="
                    + (result.errorCode() == null ? INVALID_ERROR : result.errorCode()));
            return EXIT_INVALID;
        }

        String status = result.artifactStatus() == null
                ? null : result.artifactStatus().name();
        if (status == null) {
            out.println("INVALID errorCode=" + INVALID_ERROR);
            return EXIT_INVALID;
        }
        out.println("VALID status=" + status);
        return "COMPLETE".equals(status) ? EXIT_COMPLETE : EXIT_NOT_COMPLETE;
    }

    /**
     * Standard process entry point.
     *
     * @param args exactly two paths: anomaly result, then exact base browser matrix result
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
