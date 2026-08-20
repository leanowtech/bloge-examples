package com.leanowtech.bloge.gateway.testkit;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Payload-free command line for formal input-tree declaration and pinned snapshot creation.
 *
 * <p>{@code declare} performs no writes and does not issue an evidence or deployment pin.
 * {@code snapshot} requires independent tree and publication fingerprints plus a stable
 * high-entropy nonce, and writes transaction material only after the tree fingerprint matches the
 * stable source declaration. The committed manifest is installed last as the logical publication
 * marker. All output is one closed, fixed-order line.</p>
 */
public final class CapabilityStudioFormalInputTreeCli {
    /** Successful declaration reason. */
    public static final String DECLARED_REASON =
            "RG.CAPABILITY_STUDIO.FORMAL_INPUT_TREE_CLI.DECLARED";
    /** Successful snapshot reason. */
    public static final String SNAPSHOT_COMPLETE_REASON =
            "RG.CAPABILITY_STUDIO.FORMAL_INPUT_TREE_CLI.SNAPSHOT_COMPLETE";
    /** Closed invalid-input failure reason. */
    public static final String INVALID_REASON =
            "RG.CAPABILITY_STUDIO.FORMAL_INPUT_TREE_CLI.INVALID";
    /** Closed local-dependency failure reason. */
    public static final String UNAVAILABLE_REASON =
            "RG.CAPABILITY_STUDIO.FORMAL_INPUT_TREE_CLI.UNAVAILABLE";

    private static final Set<String> DECLARE_ARGUMENTS = Set.of(
            "--mode", "--tree-kind", "--source-root",
            "--expected-bundle-semantic-fingerprint");
    private static final Set<String> SNAPSHOT_ARGUMENTS = Set.of(
            "--mode", "--tree-kind", "--source-root",
            "--expected-bundle-semantic-fingerprint", "--snapshot-output-dir",
            "--expected-tree-fingerprint", "--expected-publication-fingerprint",
            "--transaction-nonce");

    private CapabilityStudioFormalInputTreeCli() {
    }

    /**
     * Runs the CLI and terminates with its closed exit status.
     *
     * @param args exact declare or snapshot arguments
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, new CapabilityStudioFormalInputTreeSnapshotter()));
    }

    static int run(
            String[] args,
            PrintStream output,
            CapabilityStudioFormalInputTreeSnapshotter snapshotter) {
        return run(args, output, snapshotter, CliObserver.NONE);
    }

    static int run(
            String[] args,
            PrintStream output,
            CapabilityStudioFormalInputTreeSnapshotter snapshotter,
            CliObserver observer) {
        if (output == null || snapshotter == null || observer == null) {
            return 2;
        }
        try {
            Arguments parsed = parse(args);
            String line;
            if (parsed.mode() == Mode.DECLARE) {
                CapabilityStudioFormalInputTreeSnapshotter.Declaration declaration =
                        snapshotter.declare(
                                parsed.treeKind(), parsed.sourceRoot(),
                                parsed.semanticFingerprint());
                line = declarationSuccess(declaration);
            } else {
                CapabilityStudioFormalInputTreeSnapshotter.SnapshotReceipt receipt =
                        snapshotter.snapshot(
                                parsed.treeKind(), parsed.sourceRoot(),
                                parsed.semanticFingerprint(), parsed.snapshotOutputDirectory(),
                                parsed.treeFingerprint(), parsed.publicationFingerprint(),
                                parsed.transactionNonce());
                observer.afterPersistedVerify(receipt);
                line = snapshotSuccess(receipt);
            }
            return writeLine(output, line) ? 0 : 2;
        } catch (CapabilityStudioFormalInputTreeSnapshotter.FormalInputTreeException failure) {
            boolean unavailable = failure.failureKind()
                    == CapabilityStudioFormalInputTreeSnapshotter.FailureKind.UNAVAILABLE;
            String status = unavailable ? "BLOCKED" : "INVALID";
            String reason = unavailable ? UNAVAILABLE_REASON : INVALID_REASON;
            writeLine(output, "FAILED status=" + status + " reasonCode=" + reason);
            return 2;
        } catch (RuntimeException failure) {
            writeLine(output, "FAILED status=INVALID reasonCode=" + INVALID_REASON);
            return 2;
        }
    }

    private static Arguments parse(String[] args) {
        if (args == null || args.length == 0 || args.length % 2 != 0) {
            throw invalid();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            String name = args[index];
            String value = args[index + 1];
            if (name == null || value == null || value.isEmpty()
                    || !name.startsWith("--") || values.putIfAbsent(name, value) != null) {
                throw invalid();
            }
        }
        Mode mode = switch (values.get("--mode")) {
            case "declare" -> Mode.DECLARE;
            case "snapshot" -> Mode.SNAPSHOT;
            default -> throw invalid();
        };
        Set<String> expected = mode == Mode.DECLARE ? DECLARE_ARGUMENTS : SNAPSHOT_ARGUMENTS;
        if (!values.keySet().equals(expected)) {
            throw invalid();
        }
        try {
            return new Arguments(
                    mode,
                    CapabilityStudioFormalInputTreeSnapshotter.TreeKind.valueOf(
                            values.get("--tree-kind")),
                    Path.of(values.get("--source-root")),
                    values.get("--expected-bundle-semantic-fingerprint"),
                    mode == Mode.SNAPSHOT
                            ? Path.of(values.get("--snapshot-output-dir")) : null,
                    mode == Mode.SNAPSHOT ? values.get("--expected-tree-fingerprint") : null,
                    mode == Mode.SNAPSHOT
                            ? values.get("--expected-publication-fingerprint") : null,
                    mode == Mode.SNAPSHOT ? values.get("--transaction-nonce") : null);
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static String declarationSuccess(
            CapabilityStudioFormalInputTreeSnapshotter.Declaration declaration) {
        return "DECLARED status=DECLARED"
                + " treeKind=" + declaration.treeKind()
                + " bundleSemanticFingerprint=" + declaration.bundleSemanticFingerprint()
                + " entryCount=" + declaration.entryCount()
                + " totalByteSize=" + declaration.totalByteSize()
                + " treeFingerprint=" + declaration.treeFingerprint()
                + " reasonCode=" + DECLARED_REASON;
    }

    private static String snapshotSuccess(
            CapabilityStudioFormalInputTreeSnapshotter.SnapshotReceipt receipt) {
        CapabilityStudioFormalInputTreeSnapshotter.Declaration declaration =
                receipt.declaration();
        return "SNAPSHOT status=COMPLETE"
                + " commitStatus=" + receipt.commitStatus()
                + " transactionId=" + receipt.transactionId()
                + " publicationFingerprint=" + receipt.publicationFingerprint()
                + " transactionNonce=" + receipt.transactionNonce()
                + " committedManifestFingerprint="
                + receipt.committedManifestFingerprint()
                + " snapshotReceiptFingerprint=" + receipt.receiptFingerprint()
                + " treeKind=" + declaration.treeKind()
                + " bundleSemanticFingerprint=" + declaration.bundleSemanticFingerprint()
                + " entryCount=" + declaration.entryCount()
                + " totalByteSize=" + declaration.totalByteSize()
                + " treeFingerprint=" + declaration.treeFingerprint()
                + " reasonCode=" + SNAPSHOT_COMPLETE_REASON;
    }

    private static boolean writeLine(PrintStream output, String line) {
        try {
            output.print(line);
            output.print('\n');
            output.flush();
            return !output.checkError();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid formal input tree CLI arguments");
    }

    private enum Mode {
        DECLARE,
        SNAPSHOT
    }

    interface CliObserver {
        CliObserver NONE = receipt -> { };

        void afterPersistedVerify(
                CapabilityStudioFormalInputTreeSnapshotter.SnapshotReceipt receipt);
    }

    private record Arguments(
            Mode mode,
            CapabilityStudioFormalInputTreeSnapshotter.TreeKind treeKind,
            Path sourceRoot,
            String semanticFingerprint,
            Path snapshotOutputDirectory,
            String treeFingerprint,
            String publicationFingerprint,
            String transactionNonce) {
    }
}
