package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Fail-closed probe main for A1.3-R03 DEVELOPMENT predecessor fingerprint binding.
 * Shipped in test JAR only; not in the production artifact.
 *
 * <p>Usage:
 * <pre>
 * java -cp ... R03DevelopmentGateProbe \
 *     &lt;binding-path&gt; &lt;authority-path&gt; &lt;repo-root&gt;
 * </pre>
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 - verification succeeded; binding is valid</li>
 *   <li>1 - verification failed; reason code written to stderr</li>
 * </ul>
 *
 * <p>On failure, writes ONLY the stable R03-* reason code to stderr.
 * No payload, stack trace, or cause chain is emitted.
 */
public final class R03DevelopmentGateProbe {

    private static final int EXIT_SUCCESS = 0;
    private static final int EXIT_FAILURE = 1;

    private R03DevelopmentGateProbe() { }

    /**
     * Entry point.
     *
     * @param args expected: [binding-path, authority-path, repo-root]
     */
    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("R03-BINDING-MISSING");
            System.exit(EXIT_FAILURE);
            return;
        }

        Path bindingPath = Path.of(args[0]);
        Path authorityPath = Path.of(args[1]);
        Path repoRoot = Path.of(args[2]);

        int exitCode = probe(bindingPath, authorityPath, repoRoot, System.err);
        System.exit(exitCode);
    }

    /**
     * Probes the binding for verification.
     *
     * @param bindingPath   absolute path to binding JSON file
     * @param authorityPath absolute path to Authority JSON file
     * @param repoRoot      absolute path to repository root
     * @param err           stderr sink
     * @return EXIT_SUCCESS (0) on success, EXIT_FAILURE (1) on failure
     */
    static int probe(Path bindingPath, Path authorityPath, Path repoRoot, PrintStream err) {
        Objects.requireNonNull(bindingPath, "bindingPath");
        Objects.requireNonNull(authorityPath, "authorityPath");
        Objects.requireNonNull(repoRoot, "repoRoot");
        Objects.requireNonNull(err, "err");

        DevelopmentPredecessorBindingVerifier verifier = new DevelopmentPredecessorBindingVerifier();
        try {
            verifier.verify(bindingPath, authorityPath, repoRoot);
            return EXIT_SUCCESS;
        } catch (DevelopmentPredecessorBindingException e) {
            // Emit ONLY stable reason code to stderr; no payload, stack trace, or cause
            err.println(e.reasonCode());
            return EXIT_FAILURE;
        } catch (RuntimeException e) {
            // Unexpected runtime exception - emit fixed R03-BINDING-INVALID code
            err.println("R03-BINDING-INVALID");
            return EXIT_FAILURE;
        }
    }
}
