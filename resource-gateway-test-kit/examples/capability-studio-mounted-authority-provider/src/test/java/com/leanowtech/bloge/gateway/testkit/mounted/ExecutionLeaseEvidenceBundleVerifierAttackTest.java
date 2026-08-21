package com.leanowtech.bloge.gateway.testkit.mounted;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioExecutionLeaseEvidenceBundleVerifier;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioExecutionLeaseEvidencePublication;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceFailureKind;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionLeaseEvidenceBundleVerifierAttackTest {
    private static final int MANAGED_FILESYSTEM_CASES = 44;
    private static final int UID_METADATA_CASES = 7;
    private static final int RESIDUE_CASES = 2;
    private static final int COPY_CASES = 3;
    private static final int COMMITMENT_CASES = 3;
    private static final int ATTEMPT_CHAIN_CASES = 3;
    private static final int UNAVAILABLE_CASES = 3;
    private static final int POSITIVE_CASES = 2;
    private static final int EXPECTED_CASES = 67;

    @TempDir
    Path temporaryDirectory;

    @TestFactory
    List<DynamicTest> strictReadOnlyVerifierMatrix() throws Exception {
        assertThat(MANAGED_FILESYSTEM_CASES + UID_METADATA_CASES + RESIDUE_CASES
                + COPY_CASES + COMMITMENT_CASES + ATTEMPT_CHAIN_CASES
                + UNAVAILABLE_CASES + POSITIVE_CASES).isEqualTo(EXPECTED_CASES);
        Context context = committedContext();
        List<DynamicTest> tests = new ArrayList<>();
        AtomicInteger ordinal = new AtomicInteger();

        addManagedCases(tests, context, ordinal);
        addUidCases(tests, context, ordinal);
        tests.add(readOnlyFailure(ordinal, "B-parent-extra-residue", context,
                () -> extraFile(context.parent.resolve("unknown-residue.bin")),
                EvidenceFailureKind.INVALID));
        tests.add(readOnlyFailure(ordinal, "B-wrapper-extra-residue", context,
                () -> extraFile(context.wrapper.resolve("unknown-residue.bin")),
                EvidenceFailureKind.INVALID));
        addCopyCases(tests, context, ordinal);
        addCommitmentCases(tests, context, ordinal);
        addAttemptCases(tests, context, ordinal);
        addUnavailableCases(tests, context, ordinal);
        addPositiveCases(tests, context, ordinal);

        assertThat(tests).hasSize(EXPECTED_CASES);
        assertThat(ordinal.get()).isEqualTo(EXPECTED_CASES);
        return List.copyOf(tests);
    }

    private void addManagedCases(
            List<DynamicTest> tests, Context context, AtomicInteger ordinal) {
        addTargetCases(tests, context, ordinal, "declaration", context.declaration,
                MutationKind.MISSING, MutationKind.WRONG_BYTES, MutationKind.WRONG_MODE,
                MutationKind.HARDLINK);
        addTargetCases(tests, context, ordinal, "lock", context.lock,
                MutationKind.MISSING, MutationKind.WRONG_BYTES, MutationKind.WRONG_TYPE,
                MutationKind.SYMLINK, MutationKind.HARDLINK, MutationKind.WRONG_MODE);
        addTargetCases(tests, context, ordinal, "owner", context.owner,
                MutationKind.MISSING, MutationKind.WRONG_BYTES, MutationKind.SYMLINK,
                MutationKind.HARDLINK, MutationKind.WRONG_MODE);
        addTargetCases(tests, context, ordinal, "final", context.output,
                MutationKind.MISSING, MutationKind.WRONG_BYTES, MutationKind.WRONG_TYPE,
                MutationKind.SYMLINK, MutationKind.HARDLINK, MutationKind.WRONG_MODE);
        addTargetCases(tests, context, ordinal, "before", context.before,
                MutationKind.MISSING, MutationKind.WRONG_BYTES, MutationKind.SYMLINK,
                MutationKind.HARDLINK, MutationKind.WRONG_MODE);
        addTargetCases(tests, context, ordinal, "manifest", context.manifest,
                MutationKind.MISSING, MutationKind.WRONG_BYTES, MutationKind.SYMLINK,
                MutationKind.HARDLINK, MutationKind.WRONG_MODE);
        addTargetCases(tests, context, ordinal, "final-commit", context.finalCommit,
                MutationKind.MISSING, MutationKind.WRONG_BYTES, MutationKind.SYMLINK,
                MutationKind.HARDLINK, MutationKind.WRONG_MODE);
        addTargetCases(tests, context, ordinal, "wrapper", context.wrapper,
                MutationKind.MISSING, MutationKind.WRONG_TYPE, MutationKind.SYMLINK,
                MutationKind.WRONG_MODE);
        addTargetCases(tests, context, ordinal, "retained", context.retained,
                MutationKind.MISSING, MutationKind.WRONG_BYTES, MutationKind.HARDLINK,
                MutationKind.WRONG_MODE);
    }

    private void addTargetCases(
            List<DynamicTest> tests,
            Context context,
            AtomicInteger ordinal,
            String label,
            Path target,
            MutationKind... mutations) {
        for (MutationKind mutation : mutations) {
            tests.add(readOnlyFailure(ordinal, "A-" + label + "-"
                    + mutation.name().toLowerCase(), context,
                    () -> mutate(target, mutation), EvidenceFailureKind.INVALID));
        }
    }

    private void addUidCases(
            List<DynamicTest> tests, Context context, AtomicInteger ordinal) throws Exception {
        Method seam = Class.forName("com.leanowtech.bloge.gateway.testkit."
                        + "CapabilityStudioExecutionLeaseEvidenceCli")
                .getDeclaredMethod("requireVerifierMetadataForTesting", String.class,
                        boolean.class, boolean.class, int.class, long.class, long.class,
                        int.class, long.class, long.class);
        seam.setAccessible(true);
        for (String role : List.of("declaration", "lock", "owner", "final", "before",
                "manifest", "final-commit")) {
            tests.add(DynamicTest.dynamicTest(numbered(ordinal, "A-" + role + "-wrong-uid"),
                    () -> {
                        Snapshot before = snapshot(context.parent);
                        assertThatThrownBy(() -> invokeMetadata(seam, true, true,
                                0400, 1, 42, 0400, 1, 41))
                                .isInstanceOf(Class.forName("com.leanowtech.bloge.gateway."
                                        + "testkit.CapabilityStudioExecutionLeaseEvidenceCli$"
                                        + "EvidenceInvalidException"));
                        assertThat(snapshot(context.parent)).isEqualTo(before);
                    }));
        }
    }

    private void addCopyCases(
            List<DynamicTest> tests, Context context, AtomicInteger ordinal) {
        tests.add(copyFailure(ordinal, "C-copy-final", context, CopyKind.FINAL));
        tests.add(copyFailure(ordinal, "C-copy-wrapper", context, CopyKind.WRAPPER));
        tests.add(copyFailure(ordinal, "C-copy-transaction", context, CopyKind.TRANSACTION));
    }

    private void addCommitmentCases(
            List<DynamicTest> tests, Context context, AtomicInteger ordinal) {
        tests.add(readOnlyFailure(ordinal, "D-recomputed-manifest-self-hash", context,
                () -> rewriteJson(context.manifest, "commitManifestFingerprint", node ->
                        ((ObjectNode) node.withArray("artifacts").get(0))
                                .put("rawFingerprint", fingerprint('a'))),
                EvidenceFailureKind.INVALID));
        tests.add(readOnlyFailure(ordinal, "D-recomputed-final-commit-self-hash", context,
                () -> rewriteJson(context.finalCommit, "bundleCommitmentFingerprint",
                        node -> node.put("commitManifestRawFingerprint", fingerprint('b'))),
                EvidenceFailureKind.INVALID));
        tests.add(readOnlyFailure(ordinal, "D-owner-final-outer-mismatch", context,
                () -> rewriteJson(context.finalCommit, "bundleCommitmentFingerprint",
                        node -> node.put("finalRawFingerprint", fingerprint('c'))),
                EvidenceFailureKind.INVALID));
    }

    private void addAttemptCases(
            List<DynamicTest> tests, Context context, AtomicInteger ordinal) {
        tests.add(readOnlyFailure(ordinal, "E-attempt-fork", context,
                () -> forkAttempt(context), EvidenceFailureKind.INVALID));
        tests.add(readOnlyFailure(ordinal, "E-generation-gap", context,
                () -> gapAttempt(context), EvidenceFailureKind.INVALID));
        tests.add(readOnlyFailure(ordinal, "E-predecessor-mismatch", context,
                () -> predecessorMismatch(context), EvidenceFailureKind.INVALID));
    }

    private void addUnavailableCases(
            List<DynamicTest> tests, Context context, AtomicInteger ordinal) throws Exception {
        Method seam = Class.forName("com.leanowtech.bloge.gateway.testkit."
                        + "CapabilityStudioExecutionLeaseEvidenceCli")
                .getDeclaredMethod("requireVerifierMetadataForTesting", String.class,
                        boolean.class, boolean.class, int.class, long.class, long.class,
                        int.class, long.class, long.class);
        seam.setAccessible(true);
        tests.add(metadataUnavailable(ordinal, "F-null-file-key", context, seam,
                false, true));
        tests.add(metadataUnavailable(ordinal, "F-unix-metadata-unsupported", context, seam,
                true, false));
        tests.add(DynamicTest.dynamicTest(numbered(ordinal, "F-parent-io-unavailable"), () -> {
            Path backup = backup(context.parent, "parent-io");
            Files.move(context.parent, backup, StandardCopyOption.ATOMIC_MOVE);
            try {
                Snapshot before = snapshot(context.parent.getParent());
                assertFailure(context.output, context.stageRaw, context.outer,
                        context.publicationFingerprint, EvidenceFailureKind.UNAVAILABLE);
                assertThat(snapshot(context.parent.getParent())).isEqualTo(before);
            } finally {
                Files.move(backup, context.parent, StandardCopyOption.ATOMIC_MOVE);
            }
            assertPositive(context);
        }));
    }

    private void addPositiveCases(
            List<DynamicTest> tests, Context context, AtomicInteger ordinal) {
        tests.add(DynamicTest.dynamicTest(numbered(ordinal, "G-normal-positive"), () -> {
            Snapshot before = snapshot(context.parent);
            assertPositive(context);
            assertThat(snapshot(context.parent)).isEqualTo(before);
        }));
        tests.add(DynamicTest.dynamicTest(numbered(ordinal, "G-recovery-positive"), () -> {
            Snapshot beforeRecovery = snapshot(context.parent);
            Path childOutput = temporaryDirectory.resolve("verifier-recovery.out");
            assertThat(runEvidence(context.full, context.output, context.outer, childOutput))
                    .isZero();
            assertThat(Files.readString(childOutput))
                    .contains("evidencePublicationStatus=RECOVERED");
            assertThat(snapshot(context.parent)).isEqualTo(beforeRecovery);
            Snapshot beforeVerify = snapshot(context.parent);
            Path verifyOutput = temporaryDirectory.resolve("packaged-verifier-positive.out");
            assertThat(runPackagedVerifier(context, verifyOutput)).isZero();
            assertThat(Files.readString(verifyOutput))
                    .startsWith("VERIFIED status=VERIFIED ");
            assertThat(snapshot(context.parent)).isEqualTo(beforeVerify);
        }));
    }

    private DynamicTest readOnlyFailure(
            AtomicInteger ordinal,
            String name,
            Context context,
            MutationFactory mutation,
            EvidenceFailureKind expected) {
        return DynamicTest.dynamicTest(numbered(ordinal, name), () -> {
            try (Restore restore = mutation.apply()) {
                Snapshot before = snapshot(context.parent);
                assertFailure(context.output, context.stageRaw, context.outer,
                        context.publicationFingerprint, expected);
                assertThat(snapshot(context.parent)).isEqualTo(before);
            }
            assertPositive(context);
        });
    }

    private DynamicTest copyFailure(
            AtomicInteger ordinal, String name, Context source, CopyKind kind) {
        return DynamicTest.dynamicTest(numbered(ordinal, name), () -> {
            Path parent = privateDirectory(temporaryDirectory.resolve(name));
            String publication = CapabilityStudioExecutionLeaseEvidencePublication.provision(
                    parent, fingerprint((char) ('a' + kind.ordinal())))
                    .publicationFingerprint();
            Path output = parent.resolve(source.output.getFileName());
            if (kind == CopyKind.FINAL) {
                copyFile(source.output, output);
            } else if (kind == CopyKind.WRAPPER) {
                copyDirectory(source.wrapper,
                        parent.resolve(source.wrapper.getFileName()));
            } else {
                copyTransaction(source, parent, output);
            }
            Snapshot before = snapshot(parent);
            assertFailure(output, source.stageRaw, source.outer, publication,
                    EvidenceFailureKind.INVALID);
            assertThat(snapshot(parent)).isEqualTo(before);
            assertPositive(source);
        });
    }

    private DynamicTest metadataUnavailable(
            AtomicInteger ordinal,
            String name,
            Context context,
            Method seam,
            boolean fileKey,
            boolean metadata) {
        return DynamicTest.dynamicTest(numbered(ordinal, name), () -> {
            Snapshot before = snapshot(context.parent);
            assertThatThrownBy(() -> invokeMetadata(seam, fileKey, metadata,
                    0400, 1, 41, 0400, 1, 41))
                    .isInstanceOf(Class.forName("com.leanowtech.bloge.gateway."
                            + "testkit.CapabilityStudioExecutionLeaseEvidenceCli$"
                            + "EvidenceUnavailableException"));
            assertThat(snapshot(context.parent)).isEqualTo(before);
        });
    }

    private Context committedContext() throws Exception {
        var full = MountedProviderTestFixtures.writeFullEvidence(
                temporaryDirectory, "bundle-verifier-matrix");
        configure(full.fixture());
        String outer = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalMaterialDeclaration().formalOuterFingerprint();
        Path parent = privateDirectory(temporaryDirectory.resolve("bundle-verifier-output"));
        String publication = CapabilityStudioExecutionLeaseEvidencePublication.provision(
                parent, fingerprint('9')).publicationFingerprint();
        assertThat(CapabilityStudioExecutionLeaseEvidencePublication.verifyExisting(
                parent, publication).publicationFingerprint()).isEqualTo(publication);
        Path provisionOutput = temporaryDirectory.resolve("bundle-verifier-provision.out");
        assertThat(runPackagedProvision(parent, provisionOutput))
                .as(Files.readString(provisionOutput)).isZero();
        Path output = parent.resolve(
                CapabilityStudioExecutionLeaseEvidencePublication.TRANSCRIPT_FILE);
        Path childOutput = temporaryDirectory.resolve("bundle-verifier-commit.out");
        assertThat(runEvidence(full, output, outer, childOutput))
                .as(Files.readString(childOutput)).isZero();
        Path wrapper;
        try (var children = Files.list(parent)) {
            wrapper = children.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().endsWith(".evidence-v3"))
                    .findFirst().orElseThrow();
        }
        String stageRaw = rawFingerprint(full.stageResult());
        Context context = new Context(full, parent, output, wrapper, stageRaw, outer,
                publication,
                parent.resolve(CapabilityStudioExecutionLeaseEvidencePublication
                        .PUBLICATION_DECLARATION_FILE),
                parent.resolve(CapabilityStudioExecutionLeaseEvidencePublication
                        .PUBLICATION_LOCK_FILE),
                wrapper.resolve("owner-v3.json"),
                wrapper.resolve("before-v2-g00000000000000000001.json"),
                wrapper.resolve("committed-transcript-v1.json"),
                wrapper.resolve("commit-manifest-v1.json"),
                wrapper.resolve("final-commit-v1.json"));
        assertPositive(context);
        return context;
    }

    private Restore mutate(Path target, MutationKind kind) throws Exception {
        return switch (kind) {
            case MISSING -> replace(target, null);
            case WRONG_TYPE -> replace(target,
                    Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                            ? Replacement.FILE : Replacement.DIRECTORY);
            case SYMLINK -> replace(target, Replacement.SYMLINK);
            case HARDLINK -> addHardLink(target);
            case WRONG_MODE -> changeMode(target);
            case WRONG_BYTES -> changeBytes(target);
        };
    }

    private Restore replace(Path target, Replacement replacement) throws Exception {
        Path saved = backup(target, "saved");
        Files.move(target, saved, StandardCopyOption.ATOMIC_MOVE);
        if (replacement == Replacement.FILE) {
            Files.writeString(target, "wrong", StandardOpenOption.CREATE_NEW);
            Files.setPosixFilePermissions(target, permissions(0400));
        } else if (replacement == Replacement.DIRECTORY) {
            Files.createDirectory(target);
            Files.setPosixFilePermissions(target, permissions(0700));
        } else if (replacement == Replacement.SYMLINK) {
            Files.createSymbolicLink(target, saved);
        }
        return () -> {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(target)) {
                    Files.delete(target);
                } else {
                    Files.delete(target);
                }
            }
            Files.move(saved, target, StandardCopyOption.ATOMIC_MOVE);
        };
    }

    private Restore addHardLink(Path target) throws Exception {
        Path link = backup(target, "hardlink");
        Files.createLink(link, target);
        return () -> Files.delete(link);
    }

    private Restore changeMode(Path target) throws Exception {
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(
                target, LinkOption.NOFOLLOW_LINKS);
        int current = mode(target);
        int changed = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                ? 0750 : current == 0600 ? 0640 : 0600;
        Files.setPosixFilePermissions(target, permissions(changed));
        return () -> Files.setPosixFilePermissions(target, original);
    }

    private Restore changeBytes(Path target) throws Exception {
        byte[] original = Files.readAllBytes(target);
        Set<PosixFilePermission> mode = Files.getPosixFilePermissions(target);
        writeBytes(target, ("wrong:" + target.getFileName())
                .getBytes(StandardCharsets.UTF_8));
        return () -> {
            writeBytes(target, original);
            Files.setPosixFilePermissions(target, mode);
        };
    }

    private Restore extraFile(Path path) throws Exception {
        Files.writeString(path, "residue", StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(path, permissions(0400));
        return () -> Files.delete(path);
    }

    private Restore rewriteJson(
            Path path, String fingerprintField, Consumer<ObjectNode> mutation)
            throws Exception {
        byte[] original = Files.readAllBytes(path);
        ObjectNode node = (ObjectNode) MountedProviderTestFixtures.JSON.readTree(original);
        mutation.accept(node);
        byte[] changed = fingerprinted(node, fingerprintField);
        writeBytes(path, changed);
        return () -> writeBytes(path, original);
    }

    private Restore forkAttempt(Context context) throws Exception {
        Path second = context.wrapper.resolve("before-v2-g00000000000000000002.json");
        byte[] before = Files.readAllBytes(context.before);
        copyBytes(second, before, 0400);
        Restore documents = rewriteManifestAndOuter(context, manifest -> {
            ObjectNode original = (ObjectNode) manifest.withArray("artifacts").get(0);
            ObjectNode added = original.deepCopy();
            added.put("relativePath", second.getFileName().toString());
            added.put("role", "BEFORE_JOURNAL_G00000000000000000002");
            manifest.withArray("artifacts").add(added);
            sortArtifacts(manifest.withArray("artifacts"));
        });
        return () -> {
            documents.close();
            Files.delete(second);
        };
    }

    private Restore gapAttempt(Context context) throws Exception {
        Path second = context.wrapper.resolve("before-v2-g00000000000000000002.json");
        Files.move(context.before, second, StandardCopyOption.ATOMIC_MOVE);
        Restore documents = rewriteManifestAndOuter(context, manifest -> {
            ObjectNode entry = artifact(manifest, context.before.getFileName().toString());
            entry.put("relativePath", second.getFileName().toString());
            entry.put("role", "BEFORE_JOURNAL_G00000000000000000002");
            sortArtifacts(manifest.withArray("artifacts"));
        });
        return () -> {
            documents.close();
            Files.move(second, context.before, StandardCopyOption.ATOMIC_MOVE);
        };
    }

    private Restore predecessorMismatch(Context context) throws Exception {
        byte[] originalBefore = Files.readAllBytes(context.before);
        ObjectNode before = (ObjectNode) MountedProviderTestFixtures.JSON
                .readTree(originalBefore);
        before.put("previousAttemptClosureFingerprint", fingerprint('d'));
        byte[] changedBefore = fingerprinted(before, "journalFingerprint");
        writeBytes(context.before, changedBefore);
        String journal = before.path("journalFingerprint").asText();
        Restore documents = rewriteManifestAndOuter(context, manifest -> {
            manifest.put("previousAttemptClosureFingerprint", fingerprint('d'));
            manifest.put("beforeJournalFingerprint", journal);
            ObjectNode entry = artifact(manifest, context.before.getFileName().toString());
            entry.put("byteSize", changedBefore.length);
            entry.put("rawFingerprint", sha256(changedBefore));
            entry.put("canonicalFingerprint", journal);
        });
        return () -> {
            documents.close();
            writeBytes(context.before, originalBefore);
        };
    }

    private Restore rewriteManifestAndOuter(
            Context context, Consumer<ObjectNode> mutation) throws Exception {
        byte[] originalManifest = Files.readAllBytes(context.manifest);
        byte[] originalOuter = Files.readAllBytes(context.finalCommit);
        ObjectNode manifest = (ObjectNode) MountedProviderTestFixtures.JSON
                .readTree(originalManifest);
        mutation.accept(manifest);
        byte[] changedManifest = fingerprinted(manifest, "commitManifestFingerprint");
        writeBytes(context.manifest, changedManifest);
        ObjectNode outer = (ObjectNode) MountedProviderTestFixtures.JSON
                .readTree(originalOuter);
        outer.put("commitManifestRawFingerprint", sha256(changedManifest));
        outer.put("commitManifestFingerprint",
                manifest.path("commitManifestFingerprint").asText());
        writeBytes(context.finalCommit,
                fingerprinted(outer, "bundleCommitmentFingerprint"));
        return () -> {
            writeBytes(context.manifest, originalManifest);
            writeBytes(context.finalCommit, originalOuter);
        };
    }

    private static ObjectNode artifact(ObjectNode manifest, String path) {
        for (JsonNode value : manifest.withArray("artifacts")) {
            if (path.equals(value.path("relativePath").asText())) {
                return (ObjectNode) value;
            }
        }
        throw new IllegalStateException("artifact missing");
    }

    private static void sortArtifacts(ArrayNode artifacts) {
        List<JsonNode> values = new ArrayList<>();
        artifacts.forEach(values::add);
        values.sort(Comparator.comparing(value -> value.path("relativePath").asText()));
        artifacts.removeAll();
        values.forEach(artifacts::add);
    }

    private void copyTransaction(Context source, Path parent, Path output) throws Exception {
        Path wrapper = parent.resolve(source.wrapper.getFileName());
        Files.createDirectory(wrapper);
        Files.setPosixFilePermissions(wrapper, permissions(0700));
        Path sourceClaim;
        try (var children = Files.list(source.parent)) {
            sourceClaim = children.filter(path -> path.getFileName().toString()
                            .endsWith(".owner-claim-v3.json"))
                    .findFirst().orElseThrow();
        }
        Path claim = parent.resolve(sourceClaim.getFileName());
        copyFile(sourceClaim, claim);
        Files.createLink(wrapper.resolve("owner-v3.json"), claim);
        copyFile(source.retained, wrapper.resolve("committed-transcript-v1.json"));
        Files.createLink(output, wrapper.resolve("committed-transcript-v1.json"));
        try (var children = Files.list(source.wrapper)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (!Set.of("owner-v3.json", "committed-transcript-v1.json")
                        .contains(name)) {
                    copyFile(child, wrapper.resolve(name));
                }
            }
        }
    }

    private static void copyDirectory(Path source, Path target) throws Exception {
        Files.createDirectory(target);
        Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(source));
        try (var children = Files.list(source)) {
            for (Path child : children.toList()) {
                copyFile(child, target.resolve(child.getFileName()));
            }
        }
    }

    private static void copyFile(Path source, Path target) throws Exception {
        Files.copy(source, target);
        Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(source));
    }

    private static void copyBytes(Path target, byte[] bytes, int mode) throws Exception {
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(target, permissions(mode));
    }

    private static void writeBytes(Path target, byte[] bytes) throws Exception {
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(target);
        Files.setPosixFilePermissions(target, permissions(0600));
        Files.write(target, bytes, StandardOpenOption.TRUNCATE_EXISTING);
        Files.setPosixFilePermissions(target, original);
    }

    private static byte[] fingerprinted(ObjectNode node, String field) throws Exception {
        node.putNull(field);
        String fingerprint = sha256(MountedProviderTestFixtures.JSON.writeValueAsBytes(node));
        node.put(field, fingerprint);
        return MountedProviderTestFixtures.JSON.writeValueAsBytes(node);
    }

    private static void invokeMetadata(
            Method seam,
            boolean fileKey,
            boolean metadata,
            int mode,
            long links,
            long uid,
            int expectedMode,
            long expectedLinks,
            long expectedUid) throws Throwable {
        try {
            seam.invoke(null, "REGULAR", fileKey, metadata, mode, links, uid,
                    expectedMode, expectedLinks, expectedUid);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static void assertFailure(
            Path output,
            String stageRaw,
            String outer,
            String publication,
            EvidenceFailureKind expected) {
        var failure = org.junit.jupiter.api.Assertions.assertThrows(
                CapabilityStudioExecutionLeaseEvidenceBundleVerifier
                        .VerificationException.class,
                () -> CapabilityStudioExecutionLeaseEvidenceBundleVerifier.verify(
                        output, stageRaw, outer, publication));
        assertThat(failure.failureKind()).isEqualTo(expected);
    }

    private static void assertPositive(Context context) {
        assertThat(CapabilityStudioExecutionLeaseEvidenceBundleVerifier.verify(
                context.output, context.stageRaw, context.outer,
                context.publicationFingerprint).evidenceTransactionId())
                .startsWith("sha256:");
    }

    private int runPackagedVerifier(Context context, Path output) throws Exception {
        Process child = new ProcessBuilder(
                javaExecutable(), "-cp", packagedClasspath(),
                "com.leanowtech.bloge.gateway.testkit."
                        + "CapabilityStudioExecutionLeaseEvidenceBundleVerifyCli",
                "--transcript", context.output.toString(),
                "--expected-stage-result-raw-fingerprint", context.stageRaw,
                "--expected-formal-outer-fingerprint", context.outer,
                "--expected-publication-fingerprint", context.publicationFingerprint)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        return await(child);
    }

    private int runPackagedProvision(Path parent, Path output) throws Exception {
        Process child = new ProcessBuilder(
                javaExecutable(), "-cp", packagedClasspath(),
                "com.leanowtech.bloge.gateway.testkit."
                        + "CapabilityStudioExecutionLeaseEvidencePublicationProvisioningCli",
                "--publication-parent", parent.toString(),
                "--publication-nonce", fingerprint('9'))
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        return await(child);
    }

    private int runEvidence(
            MountedProviderTestFixtures.FullEvidenceFixture full,
            Path transcript,
            String outer,
            Path output) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(javaExecutable(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .AUTHORITY_BUNDLE_ROOT_PROPERTY + "=" + full.fixture().authorityRoot(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY + "="
                        + full.fixture().targetRoot(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .EXECUTION_LEASE_STATE_ROOT_PROPERTY + "="
                        + full.fixture().stateRoot(),
                "-cp", packagedClasspath(),
                "com.leanowtech.bloge.gateway.testkit."
                        + "CapabilityStudioExecutionLeaseEvidenceCli",
                full.stageResult().toString(), transcript.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT", outer);
        builder.environment().put(CapabilityStudioExecutionLeaseEvidencePublication
                .EXPECTED_PUBLICATION_FINGERPRINT_ENV,
                CapabilityStudioExecutionLeaseEvidencePublication.provision(
                        transcript.getParent(), fingerprint('9')).publicationFingerprint());
        return await(builder.start());
    }

    private static int await(Process child) throws Exception {
        try {
            assertThat(child.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return child.exitValue();
        } finally {
            if (child.isAlive()) {
                child.destroy();
                if (!child.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    child.destroyForcibly();
                    assertThat(child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                }
            }
        }
    }

    private static String packagedClasspath() {
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        return List.of(project.resolve("target/resolved-test-kit/"
                        + "bloge-resource-gateway-test-kit-1.0.0-cli.jar").normalize(),
                project.resolve("target/bloge-capability-studio-mounted-authority-provider-"
                        + "1.0.0-runtime-under-test.jar"),
                project.resolve("target/bloge-capability-studio-mounted-authority-provider-"
                        + "1.0.0-child-harness.jar")).stream()
                .peek(path -> assertThat(path).isRegularFile())
                .map(Path::toString).collect(java.util.stream.Collectors.joining(
                        System.getProperty("path.separator")));
    }

    private static Snapshot snapshot(Path root) throws Exception {
        Map<String, SnapshotEntry> entries = new TreeMap<>();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return new Snapshot(false, entries);
        }
        capture(root, root, entries, 0);
        return new Snapshot(true, entries);
    }

    private static void capture(
            Path root, Path path, Map<String, SnapshotEntry> entries, int depth)
            throws Exception {
        if (depth > 4 || entries.size() > 4096) {
            throw new IllegalStateException("test inventory exceeded");
        }
        BasicFileAttributes attributes = Files.readAttributes(path,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        String name = path.equals(root) ? "." : root.relativize(path).toString();
        long links = ((Number) Files.getAttribute(path, "unix:nlink",
                LinkOption.NOFOLLOW_LINKS)).longValue();
        long uid = ((Number) Files.getAttribute(path, "unix:uid",
                LinkOption.NOFOLLOW_LINKS)).longValue();
        int mode = ((Number) Files.getAttribute(path, "unix:mode",
                LinkOption.NOFOLLOW_LINKS)).intValue() & 0777;
        byte[] bytes = attributes.isRegularFile() ? Files.readAllBytes(path) : new byte[0];
        entries.put(name, new SnapshotEntry(attributes.fileKey(), attributes.isDirectory(),
                attributes.isRegularFile(), attributes.isSymbolicLink(), links, uid, mode,
                attributes.size(), attributes.lastModifiedTime(), bytes));
        if (attributes.isDirectory()) {
            try (var children = Files.list(path)) {
                for (Path child : children.sorted().toList()) {
                    capture(root, child, entries, depth + 1);
                }
            }
        }
    }

    private static Path privateDirectory(Path path) throws Exception {
        Path absolute = path.toAbsolutePath().normalize();
        Path realPath = absolute.getParent().toRealPath().resolve(absolute.getFileName());
        Files.createDirectory(realPath);
        Files.setPosixFilePermissions(realPath, permissions(0700));
        return realPath;
    }

    private Path backup(Path target, String suffix) {
        return temporaryDirectory.resolve("backup-" + suffix + "-"
                + Integer.toUnsignedString(System.identityHashCode(target)) + "-"
                + Long.toUnsignedString(System.nanoTime()));
    }

    private static int mode(Path path) throws Exception {
        return ((Number) Files.getAttribute(path, "unix:mode",
                LinkOption.NOFOLLOW_LINKS)).intValue() & 0777;
    }

    private static Set<PosixFilePermission> permissions(int mode) {
        StringBuilder value = new StringBuilder(9);
        int[] bits = {0400, 0200, 0100, 0040, 0020, 0010, 0004, 0002, 0001};
        char[] chars = {'r', 'w', 'x', 'r', 'w', 'x', 'r', 'w', 'x'};
        for (int index = 0; index < bits.length; index++) {
            value.append((mode & bits[index]) == 0 ? '-' : chars[index]);
        }
        return PosixFilePermissions.fromString(value.toString());
    }

    private static void configure(MountedProviderTestFixtures.Fixture fixture) {
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .AUTHORITY_BUNDLE_ROOT_PROPERTY, fixture.authorityRoot().toString());
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY, fixture.targetRoot().toString());
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .EXECUTION_LEASE_STATE_ROOT_PROPERTY, fixture.stateRoot().toString());
    }

    private static String rawFingerprint(Path path) throws Exception {
        return sha256(Files.readAllBytes(path));
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String numbered(AtomicInteger ordinal, String name) {
        return String.format("%02d-%s", ordinal.incrementAndGet(), name);
    }

    private enum MutationKind {
        MISSING,
        WRONG_BYTES,
        WRONG_TYPE,
        SYMLINK,
        HARDLINK,
        WRONG_MODE
    }

    private enum Replacement {
        FILE,
        DIRECTORY,
        SYMLINK
    }

    private enum CopyKind {
        FINAL,
        WRAPPER,
        TRANSACTION
    }

    @FunctionalInterface
    private interface MutationFactory {
        Restore apply() throws Exception;
    }

    @FunctionalInterface
    private interface Restore extends AutoCloseable {
        @Override
        void close() throws Exception;
    }

    private record Context(
            MountedProviderTestFixtures.FullEvidenceFixture full,
            Path parent,
            Path output,
            Path wrapper,
            String stageRaw,
            String outer,
            String publicationFingerprint,
            Path declaration,
            Path lock,
            Path owner,
            Path before,
            Path retained,
            Path manifest,
            Path finalCommit) {
    }

    private record Snapshot(boolean exists, Map<String, SnapshotEntry> entries) {
        private Snapshot {
            entries = Map.copyOf(entries);
        }
    }

    private record SnapshotEntry(
            Object fileKey,
            boolean directory,
            boolean regular,
            boolean symlink,
            long links,
            long uid,
            int mode,
            long size,
            java.nio.file.attribute.FileTime modifiedTime,
            byte[] bytes) {
        private SnapshotEntry {
            bytes = bytes.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof SnapshotEntry entry
                    && java.util.Objects.equals(fileKey, entry.fileKey)
                    && directory == entry.directory && regular == entry.regular
                    && symlink == entry.symlink && links == entry.links && uid == entry.uid
                    && mode == entry.mode && size == entry.size
                    && java.util.Objects.equals(modifiedTime, entry.modifiedTime)
                    && Arrays.equals(bytes, entry.bytes);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(fileKey, directory, regular, symlink, links, uid,
                    mode, size, modifiedTime, Arrays.hashCode(bytes));
        }
    }
}
