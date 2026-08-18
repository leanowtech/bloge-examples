package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Payload-free command-line entry point for browser evidence bundle verification.
 *
 * <p>Arguments are either four positional paths in the order normal-result, anomaly-result,
 * artifact-root, manifest-output, or the same four values supplied by the fixed
 * {@code --normal-result}, {@code --anomaly-result}, {@code --artifact-root}, and
 * {@code --manifest-output} options.</p>
 */
public final class CapabilityStudioBrowserEvidenceBundleCli {
    /** Exit code for a verified bundle and atomically written manifest. */
    public static final int EXIT_COMPLETE = 0;
    /** Exit code for usage, verification, or output failure. */
    public static final int EXIT_INVALID = 2;

    private static final ObjectMapper JSON = new ObjectMapper();

    private CapabilityStudioBrowserEvidenceBundleCli() {
    }

    /**
     * Verifies the requested bundle and writes a manifest only after complete verification.
     *
     * @param args four positional paths or the four fixed named options
     * @param out payload-free result stream; standard output is used when null
     * @param err reserved error stream, never used for payload or exception text
     * @return {@link #EXIT_COMPLETE} for success or {@link #EXIT_INVALID} otherwise
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        PrintStream safeOut = out == null ? System.out : out;
        String[] paths = parsePaths(args);
        if (paths == null) {
            printInvalid(safeOut, "CLI_USAGE", 0, 0, null);
            return EXIT_INVALID;
        }

        final byte[] normal;
        final byte[] anomaly;
        final Path root;
        final Path output;
        try {
            normal = Files.readAllBytes(Path.of(paths[0]));
            anomaly = Files.readAllBytes(Path.of(paths[1]));
            root = Path.of(paths[2]);
            output = Path.of(paths[3]);
        } catch (IOException | RuntimeException failure) {
            printInvalid(safeOut, "CLI_READ", 0, 0, null);
            return EXIT_INVALID;
        }

        CapabilityStudioBrowserEvidenceBundleVerifier.VerificationResult result;
        try {
            result = new CapabilityStudioBrowserEvidenceBundleVerifier().verify(
                    normal, anomaly, root);
        } catch (RuntimeException failure) {
            printInvalid(safeOut, "VERIFIER_FAILURE", 0, 0, null);
            return EXIT_INVALID;
        }
        if (!result.verified()) {
            printInvalid(safeOut, suffix(result.errorCode()), result.expectedEntryCount(),
                    result.persistedEntryCount(), result.manifestFingerprint());
            return EXIT_INVALID;
        }

        try {
            atomicWrite(output, JSON.writeValueAsBytes(result.manifest()));
        } catch (IOException | RuntimeException failure) {
            printInvalid(safeOut, "MANIFEST_OUTPUT_FAILURE", result.expectedEntryCount(),
                    result.persistedEntryCount(), result.manifestFingerprint());
            return EXIT_INVALID;
        }
        safeOut.println("VALID status=COMPLETE expectedCount=" + result.expectedEntryCount()
                + " persistedCount=" + result.persistedEntryCount()
                + " manifestFingerprint=" + result.manifestFingerprint());
        return EXIT_COMPLETE;
    }

    /**
     * Standard process entry point.
     *
     * @param args four positional paths or the four fixed named options
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    private static void atomicWrite(Path output, byte[] bytes) throws IOException {
        Path absolute = output.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("manifest output directory is unavailable");
        }
        Path temporary = Files.createTempFile(parent, ".browser-evidence-bundle-", ".tmp");
        boolean moved = false;
        try {
            Files.write(temporary, bytes, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static String[] parsePaths(String[] args) {
        if (args == null) {
            return null;
        }
        if (args.length == 4) {
            return allNonBlank(args) ? args.clone() : null;
        }
        if (args.length != 8
                || !"--normal-result".equals(args[0])
                || !"--anomaly-result".equals(args[2])
                || !"--artifact-root".equals(args[4])
                || !"--manifest-output".equals(args[6])) {
            return null;
        }
        String[] paths = {args[1], args[3], args[5], args[7]};
        return allNonBlank(paths) ? paths : null;
    }

    private static boolean allNonBlank(String[] values) {
        for (String value : values) {
            if (value == null || value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static void printInvalid(
            PrintStream out,
            String code,
            int expected,
            int persisted,
            String fingerprint) {
        out.println("INVALID errorCode=RG.CAPABILITY_STUDIO.BROWSER_EVIDENCE_BUNDLE_"
                + code + " expectedCount=" + expected + " persistedCount=" + persisted
                + " manifestFingerprint=" + (fingerprint == null ? "none" : fingerprint));
    }

    private static String suffix(String code) {
        if (code == null || !code.startsWith(
                "RG.CAPABILITY_STUDIO.BROWSER_EVIDENCE_BUNDLE_")) {
            return "VERIFIER_FAILURE";
        }
        return code.substring("RG.CAPABILITY_STUDIO.BROWSER_EVIDENCE_BUNDLE_".length());
    }
}
