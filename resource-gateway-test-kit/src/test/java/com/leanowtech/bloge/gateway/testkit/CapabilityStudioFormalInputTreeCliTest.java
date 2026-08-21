package com.leanowtech.bloge.gateway.testkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioFormalInputTreeSnapshotter.TreeKind.AUTHORITY_BUNDLE;
import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioFormalInputTreeSnapshotter.TreeKind.TARGET_ADMISSION_BUNDLE;
import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioFormalInputTreeTestFixtures.AUTHORITY_SEMANTIC;
import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioFormalInputTreeTestFixtures.PUBLICATION_FINGERPRINT;
import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioFormalInputTreeTestFixtures.TARGET_SEMANTIC;
import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioFormalInputTreeTestFixtures.TRANSACTION_NONCE;
import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioFormalInputTreeCliTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void declareEmitsOneExactLineAndWritesNothing() throws Exception {
        Path workspace = privateDirectory("declare");
        Path source = CapabilityStudioFormalInputTreeTestFixtures.authorityBundle(
                privateDirectory(workspace.resolve("source")));
        List<String> before = names(workspace);
        var snapshotter = new CapabilityStudioFormalInputTreeSnapshotter();
        var declaration = snapshotter.declare(AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC);

        Run run = run(declareArguments(AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC));

        assertThat(run).isEqualTo(new Run(0,
                "DECLARED status=DECLARED treeKind=AUTHORITY_BUNDLE"
                        + " bundleSemanticFingerprint=" + AUTHORITY_SEMANTIC
                        + " entryCount=" + declaration.entryCount()
                        + " totalByteSize=" + declaration.totalByteSize()
                        + " treeFingerprint=" + declaration.treeFingerprint()
                        + " reasonCode="
                        + CapabilityStudioFormalInputTreeCli.DECLARED_REASON + "\n"));
        assertThat(names(workspace)).isEqualTo(before);
    }

    @Test
    void snapshotEmitsExactCommittedAndRecoveredLines() throws Exception {
        Path workspace = privateDirectory("snapshot");
        Path source = CapabilityStudioFormalInputTreeTestFixtures.targetBundle(
                privateDirectory(workspace.resolve("source")));
        Path publication = privateDirectory(workspace.resolve("publication"));
        Path output = publication.resolve("snapshot");
        var snapshotter = new CapabilityStudioFormalInputTreeSnapshotter();
        var declaration = snapshotter.declare(
                TARGET_ADMISSION_BUNDLE, source, TARGET_SEMANTIC);
        String[] arguments = snapshotArguments(
                TARGET_ADMISSION_BUNDLE, source, TARGET_SEMANTIC, output,
                declaration.treeFingerprint(), PUBLICATION_FINGERPRINT, TRANSACTION_NONCE);

        Run committed = run(arguments);
        Run recovered = run(arguments);

        String committedLine = committed.output();
        assertThat(committed.exit()).isZero();
        assertThat(committedLine).matches(
                "SNAPSHOT status=COMPLETE commitStatus=COMMITTED"
                        + " transactionId=sha256:[0-9a-f]{64}"
                        + " publicationFingerprint=" + PUBLICATION_FINGERPRINT
                        + " transactionNonce=" + TRANSACTION_NONCE
                        + " committedManifestFingerprint=sha256:[0-9a-f]{64}"
                        + " snapshotReceiptFingerprint=sha256:[0-9a-f]{64}"
                        + " treeKind=TARGET_ADMISSION_BUNDLE"
                        + " bundleSemanticFingerprint=" + TARGET_SEMANTIC
                        + " entryCount=8 totalByteSize=[1-9][0-9]*"
                        + " treeFingerprint=" + declaration.treeFingerprint()
                        + " reasonCode="
                        + CapabilityStudioFormalInputTreeCli.SNAPSHOT_COMPLETE_REASON + "\\n");
        assertThat(recovered.exit()).isZero();
        assertThat(recovered.output()).isEqualTo(
                committedLine.replace("commitStatus=COMMITTED", "commitStatus=RECOVERED"));
    }

    @Test
    void verifyEmitsExactLineForBothTreeKindsAndDoesNotWrite() throws Exception {
        for (var kind : List.of(AUTHORITY_BUNDLE, TARGET_ADMISSION_BUNDLE)) {
            Path workspace = privateDirectory("verify-" + kind);
            Path source = bundle(kind, privateDirectory(workspace.resolve("source")));
            Path publication = privateDirectory(workspace.resolve("publication"));
            Path output = publication.resolve("snapshot");
            String semantic = semantic(kind);
            var snapshotter = new CapabilityStudioFormalInputTreeSnapshotter();
            var declaration = snapshotter.declare(kind, source, semantic);
            var receipt = snapshotter.snapshot(
                    kind, source, semantic, output, declaration.treeFingerprint(),
                    PUBLICATION_FINGERPRINT, TRANSACTION_NONCE);
            Map<String, NodeObservation> before = observeTree(publication);

            Run verified = run(verifyArguments(
                    kind, output, semantic, declaration.treeFingerprint(),
                    PUBLICATION_FINGERPRINT, receipt.transactionId()));

            assertThat(verified).isEqualTo(new Run(0,
                    "VERIFIED status=VERIFIED treeKind=" + kind
                            + " bundleSemanticFingerprint=" + semantic
                            + " entryCount=" + declaration.entryCount()
                            + " totalByteSize=" + declaration.totalByteSize()
                            + " treeFingerprint=" + declaration.treeFingerprint()
                            + " publicationFingerprint=" + PUBLICATION_FINGERPRINT
                            + " transactionId=" + receipt.transactionId()
                            + " reasonCode="
                            + CapabilityStudioFormalInputTreeCli.VERIFIED_REASON + "\n"));
            assertThat(observeTree(publication)).isEqualTo(before);
        }
    }

    @Test
    void verifyRequiresItsClosedArgumentSetWithoutSourceOrNonce() throws Exception {
        Path workspace = privateDirectory("verify-arguments");
        String[] valid = verifyArguments(
                AUTHORITY_BUNDLE, workspace.resolve("snapshot"), AUTHORITY_SEMANTIC,
                CapabilityStudioFormalInputTreeTestFixtures.fingerprint('e'),
                PUBLICATION_FINGERPRINT,
                CapabilityStudioFormalInputTreeTestFixtures.fingerprint('f'));

        for (String[] invalid : List.of(
                removePair(valid, "--expected-transaction-id"),
                append(valid, "--mode", "verify"),
                append(valid, "--unknown", "SECRET"),
                append(valid, "--source-root", workspace.resolve("source").toString()),
                append(valid, "--transaction-nonce", TRANSACTION_NONCE),
                replace(valid, "--snapshot-output-dir", "relative"))) {
            assertThat(run(invalid)).isEqualTo(new Run(2, invalidLine()));
        }
    }

    @Test
    void verifyInvalidWrapperIsClosedAndReadOnlyFailureIsUnavailable() throws Exception {
        Path workspace = privateDirectory("verify-failures");
        Path source = CapabilityStudioFormalInputTreeTestFixtures.authorityBundle(
                privateDirectory(workspace.resolve("source")));
        Path publication = privateDirectory(workspace.resolve("publication"));
        Path output = publication.resolve("snapshot");
        var snapshotter = new CapabilityStudioFormalInputTreeSnapshotter();
        var declaration = snapshotter.declare(AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC);
        var receipt = snapshotter.snapshot(
                AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC, output,
                declaration.treeFingerprint(), PUBLICATION_FINGERPRINT, TRANSACTION_NONCE);
        Path manifest = output.resolve(CapabilityStudioFormalInputTreeSnapshotter.MANIFEST_FILE);
        Files.setPosixFilePermissions(manifest, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        Files.writeString(manifest, "{\"messageVersion\":");
        Files.setPosixFilePermissions(manifest, Set.of(PosixFilePermission.OWNER_READ));

        String[] arguments = verifyArguments(
                AUTHORITY_BUNDLE, output, AUTHORITY_SEMANTIC,
                declaration.treeFingerprint(), PUBLICATION_FINGERPRINT,
                receipt.transactionId());
        Run invalid = run(arguments);

        assertThat(invalid).isEqualTo(new Run(2, invalidLine()));
        assertThat(invalid.output()).doesNotContain(output.toString());

        Path missing = publication.resolve("missing");
        Run unavailable = run(verifyArguments(
                AUTHORITY_BUNDLE, missing, AUTHORITY_SEMANTIC,
                declaration.treeFingerprint(), PUBLICATION_FINGERPRINT,
                receipt.transactionId()));
        assertThat(unavailable).isEqualTo(new Run(3, blockedLine()));
        assertThat(unavailable.output()).doesNotContain(missing.toString());
    }

    @Test
    void verifyOutputFailuresReturnTwoWithoutClaimingSuccess() throws Exception {
        Path workspace = privateDirectory("verify-output-failure");
        Path source = CapabilityStudioFormalInputTreeTestFixtures.targetBundle(
                privateDirectory(workspace.resolve("source")));
        Path publication = privateDirectory(workspace.resolve("publication"));
        Path output = publication.resolve("snapshot");
        var snapshotter = new CapabilityStudioFormalInputTreeSnapshotter();
        var declaration = snapshotter.declare(
                TARGET_ADMISSION_BUNDLE, source, TARGET_SEMANTIC);
        var receipt = snapshotter.snapshot(
                TARGET_ADMISSION_BUNDLE, source, TARGET_SEMANTIC, output,
                declaration.treeFingerprint(), PUBLICATION_FINGERPRINT, TRANSACTION_NONCE);
        String[] arguments = verifyArguments(
                TARGET_ADMISSION_BUNDLE, output, TARGET_SEMANTIC,
                declaration.treeFingerprint(), PUBLICATION_FINGERPRINT,
                receipt.transactionId());

        assertThat(CapabilityStudioFormalInputTreeCli.run(
                arguments, partialOutput(11), snapshotter)).isEqualTo(2);
        assertThat(CapabilityStudioFormalInputTreeCli.run(
                arguments, flushFailingOutput(), snapshotter)).isEqualTo(2);
    }

    @Test
    void malformedOrMissingPublicationCoordinatesFailBeforeSnapshotArtifacts() throws Exception {
        Path workspace = privateDirectory("pins");
        Path source = CapabilityStudioFormalInputTreeTestFixtures.authorityBundle(
                privateDirectory(workspace.resolve("source")));
        Path publication = privateDirectory(workspace.resolve("publication"));
        var snapshotter = new CapabilityStudioFormalInputTreeSnapshotter();
        var declaration = snapshotter.declare(AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC);
        String[] valid = snapshotArguments(
                AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC,
                publication.resolve("snapshot"), declaration.treeFingerprint(),
                PUBLICATION_FINGERPRINT, TRANSACTION_NONCE);

        assertThat(run(replace(valid, "--expected-publication-fingerprint", "SECRET")))
                .isEqualTo(new Run(2, invalidLine()));
        assertThat(run(replace(valid, "--transaction-nonce", "PAYLOAD")))
                .isEqualTo(new Run(2, invalidLine()));
        assertThat(run(removePair(valid, "--transaction-nonce")))
                .isEqualTo(new Run(2, invalidLine()));
        assertThat(run(replace(
                valid, "--expected-tree-fingerprint",
                CapabilityStudioFormalInputTreeTestFixtures.fingerprint('9'))))
                .isEqualTo(new Run(2, invalidLine()));
        assertThat(publication).isEmptyDirectory();
    }

    @Test
    void duplicateUnknownMixedLegacyAndMissingArgumentsAreRejected() throws Exception {
        Path workspace = privateDirectory("arguments");
        Path source = CapabilityStudioFormalInputTreeTestFixtures.authorityBundle(
                privateDirectory(workspace.resolve("source")));
        String[] declaration = declareArguments(AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC);

        for (String[] invalid : List.of(
                append(declaration, "--mode", "declare"),
                append(declaration, "--unknown", "value"),
                append(declaration, "--snapshot-root", workspace.resolve("old").toString()),
                new String[] {"--mode", "declare", "--tree-kind"})) {
            assertThat(run(invalid)).isEqualTo(new Run(2, invalidLine()));
        }
    }

    @Test
    void failuresAreClosedAndRedacted() throws Exception {
        Path workspace = privateDirectory("redaction");
        Path missing = workspace.resolve("CREDENTIAL_SECRET_ROOT");
        Run blocked = run(declareArguments(AUTHORITY_BUNDLE, missing, AUTHORITY_SEMANTIC));
        assertThat(blocked).isEqualTo(new Run(2, blockedLine()));
        assertThat(blocked.output()).doesNotContain("CREDENTIAL", missing.toString());

        Path malformed = Files.createDirectory(workspace.resolve("PAYLOAD_SECRET_ROOT"));
        Files.writeString(malformed.resolve(
                CapabilityStudioMountedAuthorityBundle.MANIFEST_FILE), "SECRET_PAYLOAD");
        Run invalid = run(declareArguments(AUTHORITY_BUNDLE, malformed, AUTHORITY_SEMANTIC));
        assertThat(invalid).isEqualTo(new Run(2, invalidLine()));
        assertThat(invalid.output()).doesNotContain("PAYLOAD", malformed.toString());
    }

    @Test
    void persistedVerifyFailureAndPartialOutputRetryAsRecovered() throws Exception {
        Path workspace = privateDirectory("retry");
        Path source = CapabilityStudioFormalInputTreeTestFixtures.authorityBundle(
                privateDirectory(workspace.resolve("source")));
        Path output = privateDirectory(workspace.resolve("publication")).resolve("snapshot");
        var snapshotter = new CapabilityStudioFormalInputTreeSnapshotter();
        var declaration = snapshotter.declare(AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC);
        String[] arguments = snapshotArguments(
                AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC, output,
                declaration.treeFingerprint(), PUBLICATION_FINGERPRINT, TRANSACTION_NONCE);
        ByteArrayOutputStream failed = new ByteArrayOutputStream();

        int first = CapabilityStudioFormalInputTreeCli.run(
                arguments, new PrintStream(failed, true, StandardCharsets.UTF_8), snapshotter,
                receipt -> {
                    throw new IllegalStateException("SECRET_CP9");
                });
        Run retry = run(arguments);

        assertThat(first).isEqualTo(2);
        assertThat(failed.toString(StandardCharsets.UTF_8)).isEqualTo(invalidLine());
        assertThat(retry.exit()).isZero();
        assertThat(retry.output()).startsWith(
                "SNAPSHOT status=COMPLETE commitStatus=RECOVERED ");
        assertThat(retry.output()).doesNotContain("SECRET_CP9");
    }

    @Test
    void partialAndFlushOutputFailuresReturnTwoWithoutClaimingSuccess() throws Exception {
        Path workspace = privateDirectory("output-failure");
        Path source = CapabilityStudioFormalInputTreeTestFixtures.authorityBundle(
                privateDirectory(workspace.resolve("source")));
        Path output = privateDirectory(workspace.resolve("publication")).resolve("snapshot");
        var snapshotter = new CapabilityStudioFormalInputTreeSnapshotter();
        var declaration = snapshotter.declare(AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC);
        String[] arguments = snapshotArguments(
                AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC, output,
                declaration.treeFingerprint(), PUBLICATION_FINGERPRINT, TRANSACTION_NONCE);

        assertThat(CapabilityStudioFormalInputTreeCli.run(
                arguments, partialOutput(11), snapshotter)).isEqualTo(2);
        assertThat(run(arguments).output()).contains("commitStatus=RECOVERED");
        assertThat(CapabilityStudioFormalInputTreeCli.run(
                new String[] {"--unknown", "SECRET"}, flushFailingOutput(), snapshotter))
                .isEqualTo(2);
    }

    private Run run(String[] arguments) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = CapabilityStudioFormalInputTreeCli.run(
                arguments, new PrintStream(bytes, true, StandardCharsets.UTF_8),
                new CapabilityStudioFormalInputTreeSnapshotter());
        return new Run(exit, bytes.toString(StandardCharsets.UTF_8));
    }

    private Path privateDirectory(String name) throws IOException {
        return privateDirectory(temporaryDirectory.toRealPath().resolve(name));
    }

    private static Path privateDirectory(Path path) throws IOException {
        return CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(path);
    }

    private static String[] declareArguments(
            CapabilityStudioFormalInputTreeSnapshotter.TreeKind kind,
            Path source,
            String semantic) {
        return new String[] {
                "--mode", "declare", "--tree-kind", kind.name(),
                "--source-root", source.toString(),
                "--expected-bundle-semantic-fingerprint", semantic
        };
    }

    private static String[] snapshotArguments(
            CapabilityStudioFormalInputTreeSnapshotter.TreeKind kind,
            Path source,
            String semantic,
            Path output,
            String tree,
            String publication,
            String nonce) {
        return new String[] {
                "--mode", "snapshot", "--tree-kind", kind.name(),
                "--source-root", source.toString(),
                "--expected-bundle-semantic-fingerprint", semantic,
                "--snapshot-output-dir", output.toString(),
                "--expected-tree-fingerprint", tree,
                "--expected-publication-fingerprint", publication,
                "--transaction-nonce", nonce
        };
    }

    private static String[] verifyArguments(
            CapabilityStudioFormalInputTreeSnapshotter.TreeKind kind,
            Path output,
            String semantic,
            String tree,
            String publication,
            String transactionId) {
        return new String[] {
                "--mode", "verify", "--tree-kind", kind.name(),
                "--snapshot-output-dir", output.toString(),
                "--expected-bundle-semantic-fingerprint", semantic,
                "--expected-tree-fingerprint", tree,
                "--expected-publication-fingerprint", publication,
                "--expected-transaction-id", transactionId
        };
    }

    private static Path bundle(
            CapabilityStudioFormalInputTreeSnapshotter.TreeKind kind,
            Path parent) throws IOException {
        return kind == AUTHORITY_BUNDLE
                ? CapabilityStudioFormalInputTreeTestFixtures.authorityBundle(parent)
                : CapabilityStudioFormalInputTreeTestFixtures.targetBundle(parent);
    }

    private static String semantic(
            CapabilityStudioFormalInputTreeSnapshotter.TreeKind kind) {
        return kind == AUTHORITY_BUNDLE ? AUTHORITY_SEMANTIC : TARGET_SEMANTIC;
    }

    private static String[] replace(String[] input, String name, String replacement) {
        String[] copy = input.clone();
        for (int index = 0; index < copy.length; index += 2) {
            if (copy[index].equals(name)) {
                copy[index + 1] = replacement;
            }
        }
        return copy;
    }

    private static String[] removePair(String[] input, String name) {
        java.util.ArrayList<String> copy = new java.util.ArrayList<>(List.of(input));
        int index = copy.indexOf(name);
        copy.remove(index + 1);
        copy.remove(index);
        return copy.toArray(String[]::new);
    }

    private static String[] append(String[] input, String... values) {
        String[] joined = java.util.Arrays.copyOf(input, input.length + values.length);
        System.arraycopy(values, 0, joined, input.length, values.length);
        return joined;
    }

    private static PrintStream partialOutput(int successfulBytes) {
        return new PrintStream(new OutputStream() {
            private int remaining = successfulBytes;

            @Override
            public void write(int value) throws IOException {
                if (remaining-- <= 0) {
                    throw new IOException("broken");
                }
            }
        }, true, StandardCharsets.UTF_8);
    }

    private static PrintStream flushFailingOutput() {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int value) {
            }

            @Override
            public void flush() {
                throw new IllegalStateException("broken");
            }
        }, false, StandardCharsets.UTF_8);
    }

    private static List<String> names(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            return stream.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static Map<String, NodeObservation> observeTree(Path root) throws IOException {
        Map<String, NodeObservation> observations = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                observations.put(root.relativize(path).toString(), new NodeObservation(
                        attributes.fileKey(), attributes.lastModifiedTime().toMillis(),
                        attributes.size(),
                        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS),
                        attributes.isRegularFile() ? Files.readAllBytes(path) : new byte[0]));
            }
        }
        return Map.copyOf(observations);
    }

    private static String invalidLine() {
        return "FAILED status=INVALID reasonCode="
                + CapabilityStudioFormalInputTreeCli.INVALID_REASON + "\n";
    }

    private static String blockedLine() {
        return "FAILED status=BLOCKED reasonCode="
                + CapabilityStudioFormalInputTreeCli.UNAVAILABLE_REASON + "\n";
    }

    private record Run(int exit, String output) {
    }

    private record NodeObservation(
            Object fileKey,
            long modifiedMillis,
            long size,
            Set<PosixFilePermission> permissions,
            byte[] bytes) {
        private NodeObservation {
            permissions = Set.copyOf(permissions);
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof NodeObservation that
                    && java.util.Objects.equals(fileKey, that.fileKey)
                    && modifiedMillis == that.modifiedMillis
                    && size == that.size
                    && permissions.equals(that.permissions)
                    && Arrays.equals(bytes, that.bytes);
        }

        @Override
        public int hashCode() {
            int result = java.util.Objects.hash(fileKey, modifiedMillis, size, permissions);
            return 31 * result + Arrays.hashCode(bytes);
        }
    }
}
