package com.leanowtech.bloge.gateway.testkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioFormalInputTreeSnapshotter.CommitStatus.COMMITTED;
import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioFormalInputTreeSnapshotter.CommitStatus.RECOVERED;
import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioFormalInputTreeSnapshotter.FailureKind.INVALID;
import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioFormalInputTreeSnapshotter.FailureKind.UNAVAILABLE;
import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioFormalInputTreeSnapshotter.TreeKind.AUTHORITY_BUNDLE;
import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioFormalInputTreeSnapshotter.TreeKind.TARGET_ADMISSION_BUNDLE;
import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioFormalInputTreeTestFixtures.AUTHORITY_SEMANTIC;
import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioFormalInputTreeTestFixtures.PUBLICATION_FINGERPRINT;
import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioFormalInputTreeTestFixtures.TARGET_SEMANTIC;
import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioFormalInputTreeTestFixtures.TRANSACTION_NONCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioFormalInputTreeSnapshotterTest {
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY =
            PosixFilePermissions.fromString("r-x------");
    private static final Set<PosixFilePermission> PRIVATE_FILE =
            PosixFilePermissions.fromString("r--------");

    @TempDir
    Path temporaryDirectory;

    @Test
    void publishesWithManifestCommitMarkerAndRecoversStableReceipt() throws Exception {
        Fixture fixture = fixture("positive", AUTHORITY_BUNDLE);

        var committed = fixture.commit();
        var recovered = fixture.commit();

        assertThat(committed.commitStatus()).isEqualTo(COMMITTED);
        assertThat(recovered.commitStatus()).isEqualTo(RECOVERED);
        assertThat(recovered.receiptFingerprint()).isEqualTo(committed.receiptFingerprint());
        assertThat(recovered.committedManifestFingerprint())
                .isEqualTo(committed.committedManifestFingerprint());
        assertThat(committed.toString()).isEqualTo("SnapshotReceipt[redacted]");
        assertCommittedClosure(fixture.output());
        assertThat(fixture.snapshotter().verify(
                fixture.output(), fixture.kind(), fixture.semantic(),
                fixture.declaration().treeFingerprint(), PUBLICATION_FINGERPRINT,
                committed.transactionId())).isEqualTo(fixture.declaration());
    }

    @Test
    void publicVerifyIsReadOnlyPortableAndIndependentOfSourceAndPublicationLease()
            throws Exception {
        Fixture fixture = committedFixture("offline-verify", AUTHORITY_BUNDLE);
        Path auditParent = CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                fixture.workspace().resolve("audit"));
        Path auditWrapper = auditParent.resolve("copied-snapshot");
        copyCommittedWrapper(fixture.output(), auditWrapper);
        Files.move(fixture.source(), fixture.source().resolveSibling("unmounted"));
        Files.delete(lockFile(fixture));
        Files.setPosixFilePermissions(auditParent,
                PosixFilePermissions.fromString("r-xr-xr-x"));
        Map<String, NodeObservation> before = observeTree(auditParent);

        var verified = new CapabilityStudioFormalInputTreeSnapshotter().verify(
                auditWrapper, fixture.kind(), fixture.semantic(),
                fixture.declaration().treeFingerprint(), PUBLICATION_FINGERPRINT,
                fixture.receipt().transactionId());

        assertThat(verified).isEqualTo(fixture.declaration());
        assertThat(observeTree(auditParent)).isEqualTo(before);
        try (var siblings = Files.list(auditParent)) {
            assertThat(siblings.map(path -> path.getFileName().toString()).toList())
                    .containsExactly("copied-snapshot");
        }
    }

    @Test
    void offlineVerifyKeepsMissingWrapperUnavailable() throws Exception {
        Fixture fixture = fixture("verify-missing", AUTHORITY_BUNDLE);

        assertFailure(UNAVAILABLE, () -> fixture.snapshotter().verify(
                fixture.output(), fixture.kind(), fixture.semantic(),
                fixture.declaration().treeFingerprint(), PUBLICATION_FINGERPRINT,
                transactionId(fixture)));
    }

    @Test
    void offlineVerifyKeepsMidReadUnmountUnavailable() throws Exception {
        Fixture fixture = committedFixture("verify-unmount", AUTHORITY_BUNDLE);
        AtomicBoolean moved = new AtomicBoolean();
        var observer = new CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver() {
            @Override
            public void afterSourceReadChunk(
                    CapabilityStudioFormalInputTreeSnapshotter.InventoryPass pass,
                    Path root,
                    Path file,
                    int index) {
                if (pass == CapabilityStudioFormalInputTreeSnapshotter.InventoryPass.VERIFY
                        && moved.compareAndSet(false, true)) {
                    try {
                        Files.move(fixture.output(), fixture.output().resolveSibling("unmounted"));
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                }
            }
        };
        var verifier = new CapabilityStudioFormalInputTreeSnapshotter(
                observer, localOperations());

        assertFailure(UNAVAILABLE, () -> verifier.verify(
                fixture.output(), fixture.kind(), fixture.semantic(),
                fixture.declaration().treeFingerprint(), PUBLICATION_FINGERPRINT,
                fixture.receipt().transactionId()));
        assertThat(moved).isTrue();
    }

    @Test
    void offlineVerifyClassifiesMalformedManifestJsonAsInvalid() throws Exception {
        Fixture fixture = committedFixture("verify-malformed", AUTHORITY_BUNDLE);
        Path manifest = fixture.output().resolve(
                CapabilityStudioFormalInputTreeSnapshotter.MANIFEST_FILE);
        Files.setPosixFilePermissions(manifest, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(manifest, "{\"messageVersion\":");
        Files.setPosixFilePermissions(manifest, PRIVATE_FILE);

        assertFailure(INVALID, () -> fixture.snapshotter().verify(
                fixture.output(), fixture.kind(), fixture.semantic(),
                fixture.declaration().treeFingerprint(), PUBLICATION_FINGERPRINT,
                fixture.receipt().transactionId()));
    }

    @Test
    void offlineVerifyKeepsRequiredFileMetadataFailuresUnavailableAndReadOnly()
            throws Exception {
        for (MetadataFault fault : List.of(
                MetadataFault.NULL_FILE_KEY,
                MetadataFault.POSIX_UNSUPPORTED,
                MetadataFault.UNIX_UNSUPPORTED,
                MetadataFault.DISAPPEARS_AFTER_READ)) {
            Fixture fixture = committedFixture("verify-metadata-" + fault, AUTHORITY_BUNDLE);
            Path artifact = fixture.output().resolve(
                    CapabilityStudioFormalInputTreeSnapshotter.BUNDLE_ROOT_DIRECTORY)
                    .resolve("artifact-000.json");
            Map<String, NodeObservation> before = observeTree(fixture.output());
            var verifier = new CapabilityStudioFormalInputTreeSnapshotter(
                    CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver.NONE,
                    new MetadataFaultOperations(artifact, fault));

            assertFailure(UNAVAILABLE, () -> verifier.verify(
                    fixture.output(), fixture.kind(), fixture.semantic(),
                    fixture.declaration().treeFingerprint(), PUBLICATION_FINGERPRINT,
                    fixture.receipt().transactionId()));

            assertThat(observeTree(fixture.output())).as(fault.name()).isEqualTo(before);
        }
    }

    @Test
    void offlineVerifyPrioritizesExplicitInvalidTypeOverMissingFileKey() throws Exception {
        for (MetadataFault fault : List.of(
                MetadataFault.SYMLINK_NULL_FILE_KEY,
                MetadataFault.DIRECTORY_NULL_FILE_KEY,
                MetadataFault.SECOND_READ_SYMLINK_NULL_FILE_KEY,
                MetadataFault.SECOND_READ_DIRECTORY_NULL_FILE_KEY)) {
            Fixture fixture = committedFixture("verify-type-" + fault, AUTHORITY_BUNDLE);
            Path artifact = fixture.output().resolve(
                    CapabilityStudioFormalInputTreeSnapshotter.BUNDLE_ROOT_DIRECTORY)
                    .resolve("artifact-000.json");
            Map<String, NodeObservation> before = observeTree(fixture.output());
            var verifier = new CapabilityStudioFormalInputTreeSnapshotter(
                    CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver.NONE,
                    new MetadataFaultOperations(artifact, fault));

            assertFailure(INVALID, () -> verifier.verify(
                    fixture.output(), fixture.kind(), fixture.semantic(),
                    fixture.declaration().treeFingerprint(), PUBLICATION_FINGERPRINT,
                    fixture.receipt().transactionId()));

            assertThat(observeTree(fixture.output())).as(fault.name()).isEqualTo(before);
        }
    }

    @Test
    void targetAndAuthorityDeclarationsArePortableAcrossAbsoluteRoots() throws Exception {
        for (var kind : List.of(AUTHORITY_BUNDLE, TARGET_ADMISSION_BUNDLE)) {
            Path firstParent = privateDirectory("portable-" + kind + "-one");
            Path secondParent = privateDirectory("portable-" + kind + "-two");
            Path first = bundle(kind, firstParent);
            Path second = secondParent.resolve(first.getFileName());
            copyTree(first, second);
            var snapshotter = new CapabilityStudioFormalInputTreeSnapshotter();

            assertThat(snapshotter.declare(kind, first, semantic(kind)))
                    .isEqualTo(snapshotter.declare(kind, second, semantic(kind)));
        }
    }

    @Test
    void identicalContentAcrossRootsProducesTheSameTransactionAndReceipt() throws Exception {
        Fixture first = fixture("portable-receipt-one", AUTHORITY_BUNDLE);
        Path secondWorkspace = privateDirectory("portable-receipt-two");
        Path secondSourceParent = CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                secondWorkspace.resolve("source"));
        Path secondSource = secondSourceParent.resolve(first.source().getFileName());
        copyTree(first.source(), secondSource);
        Path secondParent = CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                secondWorkspace.resolve("publication"));

        var firstReceipt = first.commit();
        var secondReceipt = first.snapshotter().snapshot(
                AUTHORITY_BUNDLE, secondSource, AUTHORITY_SEMANTIC,
                secondParent.resolve("snapshot"), first.declaration().treeFingerprint(),
                PUBLICATION_FINGERPRINT, TRANSACTION_NONCE);

        assertThat(secondReceipt.transactionId()).isEqualTo(firstReceipt.transactionId());
        assertThat(secondReceipt.receiptFingerprint())
                .isEqualTo(firstReceipt.receiptFingerprint());
        assertThat(secondReceipt.committedManifestFingerprint())
                .isEqualTo(firstReceipt.committedManifestFingerprint());
    }

    @Test
    void publicationPinAndNonceAreIndependentTransactionCoordinates() throws Exception {
        Fixture fixture = fixture("coordinates", TARGET_ADMISSION_BUNDLE);
        String otherPublication = CapabilityStudioFormalInputTreeTestFixtures.fingerprint('e');
        String otherNonce = CapabilityStudioFormalInputTreeTestFixtures.fingerprint('f');
        String first = transactionId(fixture);

        assertThat(CapabilityStudioFormalInputTreeSnapshotter.computeTransactionId(
                fixture.kind(), fixture.semantic(), fixture.declaration().treeFingerprint(),
                otherPublication, TRANSACTION_NONCE)).isNotEqualTo(first);
        assertThat(CapabilityStudioFormalInputTreeSnapshotter.computeTransactionId(
                fixture.kind(), fixture.semantic(), fixture.declaration().treeFingerprint(),
                PUBLICATION_FINGERPRINT, otherNonce)).isNotEqualTo(first);

        var firstReceipt = fixture.commit();
        var secondReceipt = fixture.snapshotter().snapshot(
                fixture.kind(), fixture.source(), fixture.semantic(),
                fixture.parent().resolve("second-snapshot"),
                fixture.declaration().treeFingerprint(), otherPublication,
                TRANSACTION_NONCE);
        assertThat(secondReceipt.transactionId()).isNotEqualTo(firstReceipt.transactionId());
        assertThat(secondReceipt.receiptFingerprint())
                .isNotEqualTo(firstReceipt.receiptFingerprint());
        assertThat(secondReceipt.committedManifestFingerprint())
                .isNotEqualTo(firstReceipt.committedManifestFingerprint());
    }

    @Test
    void committedManifestHasExactGoldenCanonicalBytes() {
        String semantic = CapabilityStudioFormalInputTreeTestFixtures.fingerprint('a');
        String raw = CapabilityStudioFormalInputTreeTestFixtures.fingerprint('1');
        String publication = CapabilityStudioFormalInputTreeTestFixtures.fingerprint('c');
        String nonce = CapabilityStudioFormalInputTreeTestFixtures.fingerprint('d');
        var declaration = CapabilityStudioFormalInputTreeSnapshotter.createDeclaration(
                AUTHORITY_BUNDLE, semantic,
                List.of(new CapabilityStudioFormalInputTreeSnapshotter.TreeEntry(
                        "a.json", 1, raw)));
        String transactionId = CapabilityStudioFormalInputTreeSnapshotter.computeTransactionId(
                AUTHORITY_BUNDLE, semantic, declaration.treeFingerprint(), publication, nonce);
        String expected = "{\"messageVersion\":\"resource-gateway.capability-studio."
                + "formal-input-tree.v1\",\"schemaVersion\":\"resource-gateway."
                + "capability-studio.formal-input-tree.v1\",\"treeKind\":"
                + "\"AUTHORITY_BUNDLE\",\"bundleSemanticFingerprint\":\"" + semantic
                + "\",\"entryCount\":1,\"totalByteSize\":1,\"entries\":[{"
                + "\"relativePath\":\"a.json\",\"byteSize\":1,\"rawFingerprint\":\""
                + raw + "\"}],\"publicationFingerprint\":\"" + publication
                + "\",\"transactionNonce\":\"" + nonce + "\",\"transactionId\":\""
                + transactionId + "\",\"treeFingerprint\":\""
                + declaration.treeFingerprint() + "\"}";

        assertThat(declaration.committedManifestCanonicalMessage(
                publication, nonce, transactionId)).isEqualTo(expected);
        assertThat(declaration.treeFingerprint()).isEqualTo(
                "sha256:0e9730b1e690f80ceb5a903ad30c0698597fabceb0cfc73689fb076625de5666");
        assertThat(transactionId).isEqualTo(
                "sha256:e030db0645065053c97e85a89b88dbeaf2575b3ab041c44229d2fbc474464397");
        assertThat(sha256(expected)).isEqualTo(
                "sha256:d38cba50859508d98c49cbfae0eeac44f79191078b0646aaca1abcf2fda3ac94");
        assertThat(strictJsonFieldCount(expected)).isEqualTo(11);
    }

    @Test
    void verifyRejectsEveryWrongOutOfBandCoordinate() throws Exception {
        Fixture fixture = committedFixture("verify-pins", AUTHORITY_BUNDLE);
        String transactionId = fixture.receipt().transactionId();

        assertFailure(INVALID, () -> fixture.snapshotter().verify(
                fixture.output(), TARGET_ADMISSION_BUNDLE, fixture.semantic(),
                fixture.declaration().treeFingerprint(), PUBLICATION_FINGERPRINT,
                transactionId));
        assertFailure(INVALID, () -> fixture.snapshotter().verify(
                fixture.output(), fixture.kind(),
                CapabilityStudioFormalInputTreeTestFixtures.fingerprint('9'),
                fixture.declaration().treeFingerprint(), PUBLICATION_FINGERPRINT,
                transactionId));
        assertFailure(INVALID, () -> fixture.snapshotter().verify(
                fixture.output(), fixture.kind(), fixture.semantic(),
                CapabilityStudioFormalInputTreeTestFixtures.fingerprint('8'),
                PUBLICATION_FINGERPRINT, transactionId));
        assertFailure(INVALID, () -> fixture.snapshotter().verify(
                fixture.output(), fixture.kind(), fixture.semantic(),
                fixture.declaration().treeFingerprint(),
                CapabilityStudioFormalInputTreeTestFixtures.fingerprint('7'), transactionId));
    }

    @Test
    void exactCommittedFinalRecoversBeforeSourceInventory() throws Exception {
        Fixture fixture = committedFixture("source-unmounted", TARGET_ADMISSION_BUNDLE);
        Files.move(fixture.source(), fixture.source().resolveSibling("unmounted"));

        var recovered = fixture.commit();

        assertThat(recovered.commitStatus()).isEqualTo(RECOVERED);
        assertThat(recovered.receiptFingerprint())
                .isEqualTo(fixture.receipt().receiptFingerprint());
    }

    @Test
    void unknownPreparedFinalIsNotModified() throws Exception {
        Fixture fixture = fixture("unknown-final", AUTHORITY_BUNDLE);
        Files.createDirectory(fixture.output(),
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
        Path unknown = fixture.output().resolve("UNKNOWN_CREDENTIAL");
        Files.writeString(unknown, "SECRET_PAYLOAD");
        Observation before = observation(fixture.output(), unknown);

        assertFailure(UNAVAILABLE, fixture::commit);

        assertThat(observation(fixture.output(), unknown)).isEqualTo(before);
        assertThat(Files.readString(unknown)).isEqualTo("SECRET_PAYLOAD");
    }

    @Test
    void unknownStagingWithoutOwnerReceiptIsNotClaimedOrModified() throws Exception {
        Fixture fixture = fixture("unknown-staging", AUTHORITY_BUNDLE);
        Path staging = staging(fixture);
        Files.createDirectory(staging,
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
        Path unknown = staging.resolve("UNKNOWN_TOKEN");
        Files.writeString(unknown, "CREDENTIAL_SECRET");
        Observation before = observation(staging, unknown);

        assertFailure(UNAVAILABLE, fixture::commit);

        assertThat(observation(staging, unknown)).isEqualTo(before);
        assertThat(Files.readString(unknown)).isEqualTo("CREDENTIAL_SECRET");
    }

    @Test
    void unknownOwnerAndBootstrapObjectsAreNeverRewrittenOrDeleted() throws Exception {
        Fixture ownerFixture = fixture("unknown-owner", AUTHORITY_BUNDLE);
        Path owner = owner(ownerFixture);
        Files.writeString(owner, "SECRET_OWNER");
        Files.setPosixFilePermissions(owner, PRIVATE_FILE);
        Observation ownerBefore = observation(ownerFixture.parent(), owner);
        assertFailure(UNAVAILABLE, ownerFixture::commit);
        assertThat(observation(ownerFixture.parent(), owner)).isEqualTo(ownerBefore);

        Fixture bootstrapFixture = fixture("unknown-bootstrap", AUTHORITY_BUNDLE);
        Path bootstrap = ownerBootstrap(bootstrapFixture);
        Files.writeString(bootstrap, "CREDENTIAL_BOOTSTRAP");
        Files.setPosixFilePermissions(bootstrap,
                PosixFilePermissions.fromString("rw-------"));
        Observation bootstrapBefore = observation(bootstrapFixture.parent(), bootstrap);
        assertFailure(UNAVAILABLE, bootstrapFixture::commit);
        assertThat(observation(bootstrapFixture.parent(), bootstrap))
                .isEqualTo(bootstrapBefore);
    }

    @Test
    void unknownSymlinkAtTransactionBundleTargetIsNotFollowedOrModified() throws Exception {
        Path targetParent = privateDirectory("symlink-target");
        Path target = targetParent.resolve("SECRET_TARGET");
        Files.writeString(target, "PAYLOAD_SECRET");
        var observer = new CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver() {
            @Override
            public void afterStagingRoot(Path staging) {
                try {
                    Files.createSymbolicLink(staging.resolve(
                            CapabilityStudioFormalInputTreeSnapshotter.BUNDLE_ROOT_DIRECTORY),
                            target);
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }
        };
        Fixture fixture = fixture("symlink-bundle", AUTHORITY_BUNDLE);
        var snapshotter = new CapabilityStudioFormalInputTreeSnapshotter(
                observer, localOperations());
        byte[] before = Files.readAllBytes(target);

        assertFailure(UNAVAILABLE, () -> snapshotter.snapshot(
                fixture.kind(), fixture.source(), fixture.semantic(), fixture.output(),
                fixture.declaration().treeFingerprint(), PUBLICATION_FINGERPRINT,
                TRANSACTION_NONCE));

        assertThat(Files.readAllBytes(target)).isEqualTo(before);
        assertThat(Files.isSymbolicLink(staging(fixture).resolve(
                CapabilityStudioFormalInputTreeSnapshotter.BUNDLE_ROOT_DIRECTORY))).isTrue();
    }

    @Test
    void lockSymlinkAndHardLinkAreRejectedWithoutChangingTargets() throws Exception {
        Fixture symlinkFixture = fixture("lock-symlink", AUTHORITY_BUNDLE);
        Path target = symlinkFixture.parent().resolve("LOCK_SECRET");
        Files.writeString(target, "SECRET");
        Files.createSymbolicLink(lockFile(symlinkFixture), target);
        byte[] before = Files.readAllBytes(target);
        assertFailure(UNAVAILABLE, symlinkFixture::commit);
        assertThat(Files.readAllBytes(target)).isEqualTo(before);

        Fixture hardlinkFixture = fixture("lock-hardlink", AUTHORITY_BUNDLE);
        Path hardTarget = hardlinkFixture.parent().resolve("LOCK_SECRET");
        Files.write(hardTarget, new byte[0]);
        Files.setPosixFilePermissions(hardTarget,
                PosixFilePermissions.fromString("rw-------"));
        Files.createLink(lockFile(hardlinkFixture), hardTarget);
        assertFailure(UNAVAILABLE, hardlinkFixture::commit);
        assertThat(Files.getAttribute(hardTarget, "unix:nlink")).isEqualTo(2);
    }

    @Test
    void retainedHardLinkCreatedDuringLastFileReadIsRejected() throws Exception {
        Path workspace = privateDirectory("read-hardlink");
        Path sourceParent = CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                workspace.resolve("source"));
        Path source = CapabilityStudioFormalInputTreeTestFixtures.targetBundle(sourceParent);
        List<Path> children;
        try (var stream = Files.list(source)) {
            children = stream.sorted().toList();
        }
        Path last = children.getLast();
        Path retained = sourceParent.resolve("retained-hard-link");
        var observer = new CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver() {
            @Override
            public void afterSourceReadChunk(
                    CapabilityStudioFormalInputTreeSnapshotter.InventoryPass pass,
                    Path ignoredRoot,
                    Path file,
                    int ignoredIndex) {
                if (pass == CapabilityStudioFormalInputTreeSnapshotter.InventoryPass.FIRST
                        && file.equals(last) && !Files.exists(retained)) {
                    try {
                        Files.createLink(retained, file);
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                }
            }
        };

        assertFailure(INVALID, () -> new CapabilityStudioFormalInputTreeSnapshotter(
                observer, localOperations()).declare(
                TARGET_ADMISSION_BUNDLE, source, TARGET_SEMANTIC));
        assertThat(Files.getAttribute(last, "unix:nlink")).isEqualTo(2);
    }

    @Test
    void sourceRootAncestorAndEntrySymlinksAreRejectedWithoutFollowingTargets()
            throws Exception {
        Path workspace = privateDirectory("source-symlinks");
        Path realParent = CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                workspace.resolve("real-parent"));
        Path source = CapabilityStudioFormalInputTreeTestFixtures.authorityBundle(realParent);
        Path rootLink = workspace.resolve("root-link");
        Files.createSymbolicLink(rootLink, source);
        var snapshotter = new CapabilityStudioFormalInputTreeSnapshotter();
        assertFailure(UNAVAILABLE, () -> snapshotter.declare(
                AUTHORITY_BUNDLE, rootLink, AUTHORITY_SEMANTIC));

        Path ancestorLink = workspace.resolve("ancestor-link");
        Files.createSymbolicLink(ancestorLink, realParent);
        assertFailure(UNAVAILABLE, () -> snapshotter.declare(
                AUTHORITY_BUNDLE, ancestorLink.resolve(source.getFileName()),
                AUTHORITY_SEMANTIC));

        Path artifact = source.resolve("artifact-000.json");
        Path target = realParent.resolve("SECRET_TARGET");
        Files.writeString(target, "SECRET_PAYLOAD");
        Files.delete(artifact);
        Files.createSymbolicLink(artifact, target);
        byte[] before = Files.readAllBytes(target);
        assertFailure(INVALID, () -> snapshotter.declare(
                AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC));
        assertThat(Files.readAllBytes(target)).isEqualTo(before);
    }

    @Test
    void nestedEmptyAndFifoSourceEntriesFailClosedWithoutSkippingPosixChecks()
            throws Exception {
        for (String kind : List.of("nested", "empty", "fifo")) {
            Path workspace = privateDirectory("source-type-" + kind);
            Path source = CapabilityStudioFormalInputTreeTestFixtures.authorityBundle(
                    CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                            workspace.resolve("source")));
            Path artifact = source.resolve("artifact-000.json");
            Files.delete(artifact);
            switch (kind) {
                case "nested" -> Files.createDirectory(artifact);
                case "empty" -> Files.write(artifact, new byte[0]);
                case "fifo" -> {
                    Path output = Files.createTempFile(workspace, "mkfifo-", ".out");
                    Process process = new ProcessBuilder("mkfifo", artifact.toString())
                            .redirectErrorStream(true)
                            .redirectOutput(output.toFile())
                            .start();
                    try {
                        assertThat(process.waitFor(5, TimeUnit.SECONDS))
                                .as("mkfifo certification capability").isTrue();
                        assertThat(process.exitValue())
                                .as("mkfifo certification capability").isZero();
                    } finally {
                        terminateAndReap(process);
                    }
                }
                default -> throw new IllegalStateException();
            }

            assertFailure(INVALID, () -> new CapabilityStudioFormalInputTreeSnapshotter().declare(
                    AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC));
        }
    }

    @Test
    void insecurePublicationParentFailsBeforeWriting() throws Exception {
        Path workspace = privateDirectory("unsafe-parent");
        Path sourceParent = CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                workspace.resolve("source"));
        Path source = CapabilityStudioFormalInputTreeTestFixtures.authorityBundle(sourceParent);
        Path publication = Files.createDirectory(workspace.resolve("publication"));
        Files.setPosixFilePermissions(publication,
                PosixFilePermissions.fromString("rwxrwx---"));
        var snapshotter = new CapabilityStudioFormalInputTreeSnapshotter();
        var declaration = snapshotter.declare(AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC);

        assertFailure(UNAVAILABLE, () -> snapshotter.snapshot(
                AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC,
                publication.resolve("snapshot"), declaration.treeFingerprint(),
                PUBLICATION_FINGERPRINT, TRANSACTION_NONCE));
        assertThat(publication).isEmptyDirectory();
    }

    @Test
    void concurrentPublishHasExactlyOneCommitAndAllOthersRecover() throws Exception {
        Fixture fixture = fixture("concurrent", AUTHORITY_BUNDLE);
        List<Callable<CapabilityStudioFormalInputTreeSnapshotter.SnapshotReceipt>> calls =
                java.util.stream.IntStream.range(0, 8)
                        .mapToObj(ignored -> (Callable<CapabilityStudioFormalInputTreeSnapshotter
                                .SnapshotReceipt>) fixture::commit)
                        .toList();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            var futures = executor.invokeAll(calls, 20, TimeUnit.SECONDS);
            assertThat(futures).noneMatch(java.util.concurrent.Future::isCancelled);
            var results = futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }).toList();

            assertThat(results).filteredOn(result -> result.commitStatus() == COMMITTED).hasSize(1);
            assertThat(results).filteredOn(result -> result.commitStatus() == RECOVERED).hasSize(7);
            assertThat(results).extracting(
                    CapabilityStudioFormalInputTreeSnapshotter.SnapshotReceipt::receiptFingerprint)
                    .containsOnly(results.getFirst().receiptFingerprint());
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void sameJvmPublicationLeaseTimesOutWithinOneSharedDeadline() throws Exception {
        Fixture fixture = fixture("same-jvm-timeout", AUTHORITY_BUNDLE);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var observer = new CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver() {
            @Override
            public void beforeStaging(Path ignored) {
                holding.countDown();
                try {
                    release.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }
        };
        var holder = new CapabilityStudioFormalInputTreeSnapshotter(
                observer, localOperations(), Duration.ofSeconds(5));
        var contender = new CapabilityStudioFormalInputTreeSnapshotter(
                CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver.NONE,
                localOperations(), Duration.ofMillis(250));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<CapabilityStudioFormalInputTreeSnapshotter.SnapshotReceipt>
                future = null;
        try {
            future = executor.submit(() -> holder.snapshot(
                    fixture.kind(), fixture.source(), fixture.semantic(), fixture.output(),
                    fixture.declaration().treeFingerprint(), PUBLICATION_FINGERPRINT,
                    TRANSACTION_NONCE));
            assertThat(holding.await(3, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            assertThatThrownBy(() -> contender.snapshot(
                    fixture.kind(), fixture.source(), fixture.semantic(), fixture.output(),
                    fixture.declaration().treeFingerprint(), PUBLICATION_FINGERPRINT,
                    TRANSACTION_NONCE))
                    .isInstanceOf(CapabilityStudioFormalInputTreeSnapshotter
                            .FormalInputTreeException.class)
                    .hasMessage("RG.CAPABILITY_STUDIO.FORMAL_INPUT_TREE.PUBLICATION_LOCK_TIMEOUT");
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(2));
        } finally {
            release.countDown();
            try {
                if (future != null) {
                    assertThat(future.get(10, TimeUnit.SECONDS).commitStatus())
                            .isEqualTo(COMMITTED);
                }
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void independentProcessFileLeaseTimesOutAndHolderIsReaped() throws Exception {
        Fixture fixture = committedFixture("process-lock-timeout", AUTHORITY_BUNDLE);
        ManagedProcess holder = startWorker("HANG_LOCK", fixture);
        try {
            awaitOutput(holder.output(), "LOCKED\n", Duration.ofSeconds(3));
            var contender = new CapabilityStudioFormalInputTreeSnapshotter(
                    CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver.NONE,
                    localOperations(), Duration.ofMillis(300));
            long started = System.nanoTime();
            assertThatThrownBy(() -> contender.snapshot(
                    fixture.kind(), fixture.source(), fixture.semantic(), fixture.output(),
                    fixture.declaration().treeFingerprint(), PUBLICATION_FINGERPRINT,
                    TRANSACTION_NONCE))
                    .isInstanceOf(CapabilityStudioFormalInputTreeSnapshotter
                            .FormalInputTreeException.class)
                    .hasMessage("RG.CAPABILITY_STUDIO.FORMAL_INPUT_TREE.PUBLICATION_LOCK_TIMEOUT");
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(2));
        } finally {
            terminateAndReap(holder.process());
        }
    }

    @Test
    void publicationLockRegistryRemainsFixedAfterManyDistinctParents() throws Exception {
        Path workspace = privateDirectory("striped-lock-registry");
        Path source = CapabilityStudioFormalInputTreeTestFixtures.authorityBundle(
                CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                        workspace.resolve("source")));
        var declaration = new CapabilityStudioFormalInputTreeSnapshotter().declare(
                AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC);
        var stopAfterLease = new CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver() {
            @Override
            public void beforeStaging(Path ignored) {
                throw new IllegalStateException("bounded registry probe");
            }
        };
        var snapshotter = new CapabilityStudioFormalInputTreeSnapshotter(
                stopAfterLease, localOperations(), Duration.ofSeconds(2));

        for (int index = 0; index < 96; index++) {
            Path parent = CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                    workspace.resolve("publication-%03d".formatted(index)));
            assertFailure(UNAVAILABLE, () -> snapshotter.snapshot(
                    AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC,
                    parent.resolve("snapshot"), declaration.treeFingerprint(),
                    PUBLICATION_FINGERPRINT, TRANSACTION_NONCE));
        }

        assertThat(CapabilityStudioFormalInputTreeSnapshotter
                .publicationLockRegistrySizeForTesting()).isEqualTo(64);
    }

    @Test
    void bootstrapDelayConsumesTheSharedDeadlineAndLaterEntrySucceeds() throws Exception {
        Fixture fixture = fixture("bootstrap-deadline", AUTHORITY_BUNDLE);
        var delayed = new CapabilityStudioFormalInputTreeSnapshotter(
                CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver.NONE,
                new DelayedFirstForceOperations(Duration.ofMillis(150)),
                Duration.ofMillis(50));

        assertThatThrownBy(() -> delayed.snapshot(
                fixture.kind(), fixture.source(), fixture.semantic(), fixture.output(),
                fixture.declaration().treeFingerprint(), PUBLICATION_FINGERPRINT,
                TRANSACTION_NONCE))
                .isInstanceOf(CapabilityStudioFormalInputTreeSnapshotter
                        .FormalInputTreeException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.FORMAL_INPUT_TREE.PUBLICATION_LOCK_TIMEOUT");
        assertThat(fixture.commit().commitStatus()).isEqualTo(COMMITTED);
    }

    @Test
    void monotonicLeaseBudgetSurvivesSignedLongWrap() throws Exception {
        Fixture fixture = fixture("deadline-wrap", AUTHORITY_BUNDLE);
        var ticker = new SequenceTicker(
                Long.MAX_VALUE - 4,
                Long.MAX_VALUE - 3,
                Long.MAX_VALUE - 2,
                Long.MAX_VALUE - 1,
                Long.MIN_VALUE,
                Long.MIN_VALUE + 1);
        var snapshotter = new CapabilityStudioFormalInputTreeSnapshotter(
                CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver.NONE,
                localOperations(), Duration.ofNanos(100), ticker);

        assertThat(snapshot(fixture, snapshotter).commitStatus()).isEqualTo(COMMITTED);
    }

    @Test
    void wrappedTimeoutAndBacktrackingTickersFailBoundedlyAndReleaseJvmLock()
            throws Exception {
        Fixture wrapped = fixture("deadline-wrap-timeout", AUTHORITY_BUNDLE);
        var wrappedTimeout = new CapabilityStudioFormalInputTreeSnapshotter(
                CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver.NONE,
                localOperations(), Duration.ofNanos(5),
                new SequenceTicker(
                        Long.MAX_VALUE - 2, Long.MAX_VALUE - 1, Long.MIN_VALUE + 10));
        assertThatThrownBy(() -> snapshot(wrapped, wrappedTimeout))
                .isInstanceOf(CapabilityStudioFormalInputTreeSnapshotter
                        .FormalInputTreeException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.FORMAL_INPUT_TREE.PUBLICATION_LOCK_TIMEOUT");
        assertThat(wrapped.commit().commitStatus()).isEqualTo(COMMITTED);

        Fixture backward = fixture("deadline-backward", AUTHORITY_BUNDLE);
        var invalidTicker = new CapabilityStudioFormalInputTreeSnapshotter(
                CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver.NONE,
                localOperations(), Duration.ofNanos(100),
                new SequenceTicker(100, 150, 140));
        assertThatThrownBy(() -> snapshot(backward, invalidTicker))
                .isInstanceOf(CapabilityStudioFormalInputTreeSnapshotter
                        .FormalInputTreeException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.FORMAL_INPUT_TREE.MONOTONIC_TICK_INVALID");
        assertThat(backward.commit().commitStatus()).isEqualTo(COMMITTED);

        Fixture wrappedBackward = fixture("deadline-wrap-backward", AUTHORITY_BUNDLE);
        var invalidAfterWrap = new CapabilityStudioFormalInputTreeSnapshotter(
                CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver.NONE,
                localOperations(), Duration.ofNanos(100),
                new SequenceTicker(Long.MAX_VALUE - 1, Long.MIN_VALUE, Long.MAX_VALUE));
        assertThatThrownBy(() -> snapshot(wrappedBackward, invalidAfterWrap))
                .isInstanceOf(CapabilityStudioFormalInputTreeSnapshotter
                        .FormalInputTreeException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.FORMAL_INPUT_TREE.MONOTONIC_TICK_INVALID");
        assertThat(wrappedBackward.commit().commitStatus()).isEqualTo(COMMITTED);
    }

    @Test
    void jvmLockWaitInterruptPreservesFlagReleasesWaiterAndAllowsReentry() throws Exception {
        Fixture fixture = fixture("jvm-lock-interrupt", AUTHORITY_BUNDLE);
        CountDownLatch holderEntered = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        var holderObserver = new CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver() {
            @Override
            public void beforeStaging(Path ignored) {
                holderEntered.countDown();
                awaitLatch(releaseHolder);
            }
        };
        CountDownLatch waiterAtLock = new CountDownLatch(1);
        var waiterObserver = new CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver() {
            @Override
            public void beforeJvmPublicationLock(Path ignored) {
                waiterAtLock.countDown();
            }
        };
        var holder = new CapabilityStudioFormalInputTreeSnapshotter(
                holderObserver, localOperations(), Duration.ofSeconds(5));
        var waiter = new CapabilityStudioFormalInputTreeSnapshotter(
                waiterObserver, localOperations(), Duration.ofSeconds(5));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<CapabilityStudioFormalInputTreeSnapshotter.SnapshotReceipt>
                holderFuture = null;
        Thread waiterThread = null;
        AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        try {
            holderFuture = executor.submit(() -> snapshot(fixture, holder));
            assertThat(holderEntered.await(3, TimeUnit.SECONDS)).isTrue();
            waiterThread = new Thread(() -> {
                try {
                    snapshot(fixture, waiter);
                } catch (Throwable failure) {
                    waiterFailure.set(failure);
                    interruptPreserved.set(Thread.currentThread().isInterrupted());
                }
            }, "formal-input-tree-jvm-lock-waiter");
            waiterThread.start();
            assertThat(waiterAtLock.await(3, TimeUnit.SECONDS)).isTrue();
            waiterThread.interrupt();
            waiterThread.join(3_000);
            assertThat(waiterThread.isAlive()).isFalse();
            assertInterruptedWaiter(waiterFailure, interruptPreserved);
        } finally {
            releaseHolder.countDown();
            if (waiterThread != null && waiterThread.isAlive()) {
                waiterThread.interrupt();
                waiterThread.join(3_000);
            }
            try {
                if (holderFuture != null) {
                    assertThat(holderFuture.get(10, TimeUnit.SECONDS).commitStatus())
                            .isEqualTo(COMMITTED);
                }
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
        assertThat(fixture.commit().commitStatus()).isEqualTo(RECOVERED);
    }

    @Test
    void fileLockPollingInterruptPreservesFlagReleasesJvmStripeAndAllowsReentry()
            throws Exception {
        Fixture fixture = committedFixture("file-lock-interrupt", AUTHORITY_BUNDLE);
        ManagedProcess holder = startWorker("HANG_LOCK", fixture);
        CountDownLatch waiterAtFileLock = new CountDownLatch(1);
        AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        Thread waiterThread = null;
        try {
            awaitOutput(holder.output(), "LOCKED\n", Duration.ofSeconds(3));
            var observer = new CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver() {
                @Override
                public void beforeFilePublicationLock(Path ignored) {
                    waiterAtFileLock.countDown();
                }
            };
            var waiter = new CapabilityStudioFormalInputTreeSnapshotter(
                    observer, localOperations(), Duration.ofSeconds(5));
            waiterThread = new Thread(() -> {
                try {
                    snapshot(fixture, waiter);
                } catch (Throwable failure) {
                    waiterFailure.set(failure);
                    interruptPreserved.set(Thread.currentThread().isInterrupted());
                }
            }, "formal-input-tree-file-lock-waiter");
            waiterThread.start();
            assertThat(waiterAtFileLock.await(3, TimeUnit.SECONDS)).isTrue();
            waiterThread.interrupt();
            waiterThread.join(3_000);
            assertThat(waiterThread.isAlive()).isFalse();
            assertInterruptedWaiter(waiterFailure, interruptPreserved);
        } finally {
            if (waiterThread != null && waiterThread.isAlive()) {
                waiterThread.interrupt();
                waiterThread.join(3_000);
            }
            terminateAndReap(holder.process());
        }
        assertThat(fixture.commit().commitStatus()).isEqualTo(RECOVERED);
    }

    @Test
    void oscillatingTickerFailsFileLockPollingBoundedlyAndReleasesJvmStripe()
            throws Exception {
        Fixture fixture = committedFixture("file-lock-oscillating-ticker", AUTHORITY_BUNDLE);
        ManagedProcess holder = startWorker("HANG_LOCK", fixture);
        try {
            awaitOutput(holder.output(), "LOCKED\n", Duration.ofSeconds(3));
            var contender = new CapabilityStudioFormalInputTreeSnapshotter(
                    CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver.NONE,
                    localOperations(), Duration.ofSeconds(1),
                    new SequenceTicker(100, 110, 120, 130, 140, 135));
            long started = System.nanoTime();

            assertThatThrownBy(() -> snapshot(fixture, contender))
                    .isInstanceOf(CapabilityStudioFormalInputTreeSnapshotter
                            .FormalInputTreeException.class)
                    .hasMessage(
                            "RG.CAPABILITY_STUDIO.FORMAL_INPUT_TREE.MONOTONIC_TICK_INVALID");

            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(2));
        } finally {
            terminateAndReap(holder.process());
        }
        assertThat(fixture.commit().commitStatus()).isEqualTo(RECOVERED);
    }

    @Test
    void constantTickerStillBoundsHeldOsFileLockAndReleasesJvmStripe()
            throws Exception {
        Fixture fixture = committedFixture("file-lock-constant-ticker", AUTHORITY_BUNDLE);
        ManagedProcess holder = startWorker("HANG_LOCK", fixture);
        try {
            awaitOutput(holder.output(), "LOCKED\n", Duration.ofSeconds(3));
            var contender = new CapabilityStudioFormalInputTreeSnapshotter(
                    CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver.NONE,
                    localOperations(), Duration.ofMillis(80), new SequenceTicker(100));
            long started = System.nanoTime();

            assertThatThrownBy(() -> snapshot(fixture, contender))
                    .isInstanceOf(CapabilityStudioFormalInputTreeSnapshotter
                            .FormalInputTreeException.class)
                    .hasMessage(
                            "RG.CAPABILITY_STUDIO.FORMAL_INPUT_TREE.PUBLICATION_LOCK_TIMEOUT");

            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(2));
        } finally {
            terminateAndReap(holder.process());
        }
        assertThat(fixture.commit().commitStatus()).isEqualTo(RECOVERED);
    }

    @Test
    void childHarnessTerminatesAndReapsAnIntentionalHang() throws Exception {
        Fixture fixture = fixture("harness-hang", AUTHORITY_BUNDLE);
        ProcessResult result = awaitWorker(
                startWorker("HANG", fixture), Duration.ofMillis(300));
        assertThat(result.timedOut()).isTrue();
        assertThat(result.output()).isEmpty();
    }

    @Test
    void twoRealProcessesSerializeOnThePublicationLease() throws Exception {
        Fixture fixture = fixture("interprocess", AUTHORITY_BUNDLE);
        ManagedProcess first = null;
        ManagedProcess second = null;
        try {
            first = startWorker("COMMIT", fixture);
            second = startWorker("COMMIT", fixture);
            ProcessResult firstResult = awaitWorker(first, Duration.ofSeconds(30));
            ProcessResult secondResult = awaitWorker(second, Duration.ofSeconds(30));

            assertThat(firstResult.timedOut()).isFalse();
            assertThat(secondResult.timedOut()).isFalse();
            assertThat(firstResult.exit()).isZero();
            assertThat(secondResult.exit()).isZero();
            assertThat(firstResult.output()).isEmpty();
            assertThat(secondResult.output()).isEmpty();
        } finally {
            if (first != null) {
                terminateAndReap(first.process());
            }
            if (second != null) {
                terminateAndReap(second.process());
            }
        }
        assertThat(fixture.commit().commitStatus()).isEqualTo(RECOVERED);
        assertCommittedClosure(fixture.output());
        assertNoTransactionResidue(fixture);
    }

    @Test
    void realChildCrashCheckpointsRecoverWithoutWedgedTransaction() throws Exception {
        for (String checkpoint : List.of(
                "CP0", "CP1", "CP2", "CP3", "CP4", "CP5", "CP6", "CP7", "CP8", "CP9",
                "OWNER_PART", "OWNER_CLAIM", "MID_PAYLOAD", "MID_MANIFEST", "OBJECT_FORCE")) {
            assertRealChildCheckpoint(checkpoint);
        }
    }

    private void assertRealChildCheckpoint(String checkpoint) throws Exception {
        Fixture fixture = fixture("crash-" + checkpoint, AUTHORITY_BUNDLE);
        ProcessResult crashed = crashWorker(checkpoint, fixture);
        assertThat(crashed.timedOut()).as(checkpoint).isFalse();
        assertThat(crashed.exit()).as(checkpoint).isEqualTo(77);

        if (Set.of("CP7", "CP8", "CP9").contains(checkpoint)) {
            Files.move(fixture.source(), fixture.source().resolveSibling("unmounted"));
        }
        CapabilityStudioFormalInputTreeSnapshotter.SnapshotReceipt retry;
        try {
            retry = fixture.commit();
        } catch (RuntimeException failure) {
            throw new AssertionError("checkpoint retry failed: " + checkpoint, failure);
        }

        assertThat(retry.commitStatus()).as(checkpoint).isEqualTo(
                Set.of("CP7", "CP8", "CP9").contains(checkpoint)
                        ? RECOVERED : COMMITTED);
        assertCommittedClosure(fixture.output());
        assertNoTransactionResidue(fixture);
    }

    @Test
    void realChildStdoutFailuresRecoverStableReceipt() throws Exception {
        for (String checkpoint : List.of("CP10_PARTIAL", "CP10_FLUSH")) {
            assertRealChildStdoutCheckpoint(checkpoint);
        }
    }

    private void assertRealChildStdoutCheckpoint(String checkpoint) throws Exception {
        Fixture fixture = fixture("stdout-" + checkpoint, AUTHORITY_BUNDLE);
        ProcessResult crashed = crashWorker(checkpoint, fixture);
        assertThat(crashed.timedOut()).as(checkpoint).isFalse();
        assertThat(crashed.exit()).isNotZero();
        if (checkpoint.equals("CP10_PARTIAL")) {
            assertThat(crashed.output()).isNotEmpty();
            assertThat(new String(crashed.output(), StandardCharsets.UTF_8))
                    .doesNotContain("\n", "reasonCode=");
        }

        var retry = fixture.commit();

        assertThat(retry.commitStatus()).isEqualTo(RECOVERED);
        assertCommittedClosure(fixture.output());
        assertNoTransactionResidue(fixture);
    }

    @Test
    void sourceMutationAndManifestTamperFailClosed() throws Exception {
        Path workspace = privateDirectory("source-mutation");
        Path sourceParent = CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                workspace.resolve("source"));
        Path source = CapabilityStudioFormalInputTreeTestFixtures.authorityBundle(sourceParent);
        Path artifact = source.resolve("artifact-000.json");
        java.nio.file.attribute.FileTime originalTime = Files.getLastModifiedTime(artifact);
        var observer = new CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver() {
            @Override
            public void afterInventory(
                    CapabilityStudioFormalInputTreeSnapshotter.InventoryPass pass, Path ignored) {
                if (pass == CapabilityStudioFormalInputTreeSnapshotter.InventoryPass.FIRST) {
                    try {
                        byte[] bytes = Files.readAllBytes(artifact);
                        bytes[0] ^= 1;
                        Files.write(artifact, bytes);
                        Files.setLastModifiedTime(artifact, originalTime);
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                }
            }
        };
        assertFailure(INVALID, () -> new CapabilityStudioFormalInputTreeSnapshotter(
                observer, localOperations()).declare(
                AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC));

        Fixture committed = committedFixture("manifest-tamper", AUTHORITY_BUNDLE);
        Path manifest = committed.output().resolve(
                CapabilityStudioFormalInputTreeSnapshotter.MANIFEST_FILE);
        Files.setPosixFilePermissions(manifest, PosixFilePermissions.fromString("rw-------"));
        assertFailure(INVALID, () -> committed.snapshotter().verify(
                committed.output(), committed.kind(), committed.semantic(),
                committed.declaration().treeFingerprint(), PUBLICATION_FINGERPRINT,
                committed.receipt().transactionId()));
    }

    @Test
    void committedBundleMissingFileIsRejected() throws Exception {
        Fixture fixture = committedFixture("snapshot-missing-file", AUTHORITY_BUNDLE);
        Path bundle = fixture.output().resolve(
                CapabilityStudioFormalInputTreeSnapshotter.BUNDLE_ROOT_DIRECTORY);
        Files.setPosixFilePermissions(bundle,
                PosixFilePermissions.fromString("rwx------"));
        Files.delete(bundle.resolve("artifact-000.json"));
        Files.setPosixFilePermissions(bundle, PRIVATE_DIRECTORY);

        assertFailure(INVALID, () -> fixture.snapshotter().verify(
                fixture.output(), fixture.kind(), fixture.semantic(),
                fixture.declaration().treeFingerprint(), PUBLICATION_FINGERPRINT,
                fixture.receipt().transactionId()));
    }

    @Test
    void committedBundleExtraFileIsRejected() throws Exception {
        Fixture fixture = committedFixture("snapshot-extra-file", AUTHORITY_BUNDLE);
        Path bundle = fixture.output().resolve(
                CapabilityStudioFormalInputTreeSnapshotter.BUNDLE_ROOT_DIRECTORY);
        Files.setPosixFilePermissions(bundle,
                PosixFilePermissions.fromString("rwx------"));
        Path extra = bundle.resolve("extra.json");
        Files.writeString(extra, "{}\n");
        Files.setPosixFilePermissions(extra, PRIVATE_FILE);
        Files.setPosixFilePermissions(bundle, PRIVATE_DIRECTORY);

        assertFailure(INVALID, () -> fixture.snapshotter().verify(
                fixture.output(), fixture.kind(), fixture.semantic(),
                fixture.declaration().treeFingerprint(), PUBLICATION_FINGERPRINT,
                fixture.receipt().transactionId()));
    }

    @Test
    void sameNameSameBytesAndMtimeRecreationIsCaughtByFileIdentity() throws Exception {
        Path workspace = privateDirectory("same-name-recreation");
        Path source = CapabilityStudioFormalInputTreeTestFixtures.authorityBundle(
                CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                        workspace.resolve("source")));
        Path artifact = source.resolve("artifact-000.json");
        byte[] bytes = Files.readAllBytes(artifact);
        var modified = Files.getLastModifiedTime(artifact);
        Object initialKey = Files.readAttributes(
                artifact, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        var observer = new CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver() {
            @Override
            public void afterInventory(
                    CapabilityStudioFormalInputTreeSnapshotter.InventoryPass pass, Path ignored) {
                if (pass == CapabilityStudioFormalInputTreeSnapshotter.InventoryPass.FIRST) {
                    try {
                        Files.delete(artifact);
                        Files.write(artifact, bytes);
                        Files.setLastModifiedTime(artifact, modified);
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                }
            }
        };

        assertFailure(INVALID, () -> new CapabilityStudioFormalInputTreeSnapshotter(
                observer, localOperations()).declare(
                AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC));
        assertThat(Files.readAttributes(
                artifact, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey())
                .isNotEqualTo(initialKey);
    }

    @Test
    void sameContentRootSwapBetweenInventoriesIsCaughtByRootIdentity() throws Exception {
        Path workspace = privateDirectory("root-swap");
        Path sourceParent = CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                workspace.resolve("source-parent"));
        Path source = CapabilityStudioFormalInputTreeTestFixtures.authorityBundle(sourceParent);
        Path replacement = sourceParent.resolve("replacement");
        copyTree(source, replacement);
        Object initialKey = Files.readAttributes(
                source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        var observer = new CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver() {
            @Override
            public void afterInventory(
                    CapabilityStudioFormalInputTreeSnapshotter.InventoryPass pass, Path ignored) {
                if (pass == CapabilityStudioFormalInputTreeSnapshotter.InventoryPass.FIRST) {
                    try {
                        Files.move(source, sourceParent.resolve("old-root"));
                        Files.move(replacement, source);
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                }
            }
        };

        assertFailure(INVALID, () -> new CapabilityStudioFormalInputTreeSnapshotter(
                observer, localOperations()).declare(
                AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC));
        assertThat(Files.readAttributes(
                source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey())
                .isNotEqualTo(initialKey);
    }

    @Test
    void targetRequiresExactClosedEightFileTree() throws Exception {
        Path workspace = privateDirectory("target-closure");
        Path sourceParent = CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                workspace.resolve("source"));
        Path source = CapabilityStudioFormalInputTreeTestFixtures.targetBundle(sourceParent);
        Files.writeString(source.resolve("extra.json"), "{}\n");

        assertFailure(INVALID, () -> new CapabilityStudioFormalInputTreeSnapshotter().declare(
                TARGET_ADMISSION_BUNDLE, source, TARGET_SEMANTIC));
    }

    @Test
    void authorityCapacityAccepts641FilesAndRejectsThe642ndBeforeReading() throws Exception {
        Path workspace = privateDirectory("authority-capacity");
        Path source = CapabilityStudioFormalInputTreeTestFixtures.maximumAuthorityBundle(
                CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                        workspace.resolve("source")));
        var snapshotter = new CapabilityStudioFormalInputTreeSnapshotter();

        assertThat(snapshotter.declare(AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC)
                .entryCount()).isEqualTo(641);
        Files.writeString(source.resolve("extra.json"), "{}\n");
        assertFailure(INVALID, () -> snapshotter.declare(
                AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC));
    }

    @Test
    void authorityReferencedAndCompleteTreeByteBoundariesAreExact() throws Exception {
        Path workspace = privateDirectory("authority-total-boundaries");
        Path source = CapabilityStudioFormalInputTreeTestFixtures
                .exactMaximumReferencedAuthorityBundle(
                        CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                                workspace.resolve("source")));
        Path manifest = source.resolve(CapabilityStudioMountedAuthorityBundle.MANIFEST_FILE);
        byte[] manifestJson = Files.readAllBytes(manifest);
        Files.write(manifest, paddedJson(
                manifestJson,
                CapabilityStudioFormalInputTreeSnapshotter.MAXIMUM_AUTHORITY_MANIFEST_BYTES));
        var snapshotter = new CapabilityStudioFormalInputTreeSnapshotter();

        var exact = snapshotter.declare(AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC);

        assertThat(exact.totalByteSize()).isEqualTo(
                CapabilityStudioFormalInputTreeSnapshotter.MAXIMUM_AUTHORITY_TOTAL_BYTES);
        Path lastArtifact = source.resolve("artifact-511.json");
        byte[] last = Files.readAllBytes(lastArtifact);
        Files.write(lastArtifact, Arrays.copyOf(last, last.length + 1));
        assertFailure(INVALID, () -> snapshotter.declare(
                AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC));
    }

    @Test
    void createDeclarationAccepts33MiBAndRejectsOneAdditionalByte() {
        List<CapabilityStudioFormalInputTreeSnapshotter.TreeEntry> exact =
                java.util.stream.IntStream.range(0, 33)
                        .mapToObj(index -> new CapabilityStudioFormalInputTreeSnapshotter.TreeEntry(
                                "entry-%02d.bin".formatted(index),
                                CapabilityStudioFormalInputTreeSnapshotter.MAXIMUM_FILE_BYTES,
                                CapabilityStudioFormalInputTreeTestFixtures.fingerprint(
                                        Character.forDigit(index % 16, 16))))
                        .toList();
        assertThat(CapabilityStudioFormalInputTreeSnapshotter.createDeclaration(
                AUTHORITY_BUNDLE, AUTHORITY_SEMANTIC, exact).totalByteSize())
                .isEqualTo(CapabilityStudioFormalInputTreeSnapshotter.MAXIMUM_TOTAL_BYTES);
        List<CapabilityStudioFormalInputTreeSnapshotter.TreeEntry> tooLarge =
                new ArrayList<>(exact);
        tooLarge.add(new CapabilityStudioFormalInputTreeSnapshotter.TreeEntry(
                "entry-33.bin", 1,
                CapabilityStudioFormalInputTreeTestFixtures.fingerprint('f')));

        assertFailure(INVALID, () -> CapabilityStudioFormalInputTreeSnapshotter.createDeclaration(
                AUTHORITY_BUNDLE, AUTHORITY_SEMANTIC, tooLarge));
    }

    @Test
    void loaderAlignedManifestArtifactAndProofByteLimitsAreExact() throws Exception {
        Path authorityWorkspace = privateDirectory("authority-byte-limit");
        Path authority = CapabilityStudioFormalInputTreeTestFixtures.authorityBundle(
                CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                        authorityWorkspace.resolve("source")));
        Path artifact = authority.resolve("artifact-000.json");
        Files.write(artifact, repeated(
                CapabilityStudioFormalInputTreeSnapshotter.MAXIMUM_AUTHORITY_ARTIFACT_BYTES,
                (byte) 'A'));
        var snapshotter = new CapabilityStudioFormalInputTreeSnapshotter();
        snapshotter.declare(AUTHORITY_BUNDLE, authority, AUTHORITY_SEMANTIC);
        Files.write(artifact, repeated(
                CapabilityStudioFormalInputTreeSnapshotter.MAXIMUM_AUTHORITY_ARTIFACT_BYTES + 1,
                (byte) 'A'));
        assertFailure(INVALID, () -> snapshotter.declare(
                AUTHORITY_BUNDLE, authority, AUTHORITY_SEMANTIC));

        Path targetWorkspace = privateDirectory("target-byte-limit");
        Path target = CapabilityStudioFormalInputTreeTestFixtures.targetBundle(
                CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                        targetWorkspace.resolve("source")));
        Path proof = target.resolve("candidate-proof.json");
        Files.write(proof, repeated(
                CapabilityStudioFormalInputTreeSnapshotter.MAXIMUM_TARGET_ADMISSION_PROOF_BYTES,
                (byte) 'P'));
        snapshotter.declare(TARGET_ADMISSION_BUNDLE, target, TARGET_SEMANTIC);
        Files.write(proof, repeated(
                CapabilityStudioFormalInputTreeSnapshotter.MAXIMUM_TARGET_ADMISSION_PROOF_BYTES + 1,
                (byte) 'P'));
        assertFailure(INVALID, () -> snapshotter.declare(
                TARGET_ADMISSION_BUNDLE, target, TARGET_SEMANTIC));

        Path manifestTarget = CapabilityStudioFormalInputTreeTestFixtures.targetBundle(
                CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                        targetWorkspace.resolve("manifest-source")));
        Path manifest = manifestTarget.resolve(
                CapabilityStudioMountedTargetAdmissionBundle.MANIFEST_FILE);
        byte[] original = Files.readAllBytes(manifest);
        Files.write(manifest, paddedJson(
                original,
                CapabilityStudioFormalInputTreeSnapshotter
                        .MAXIMUM_TARGET_ADMISSION_MANIFEST_BYTES));
        snapshotter.declare(TARGET_ADMISSION_BUNDLE, manifestTarget, TARGET_SEMANTIC);
        Files.write(manifest, paddedJson(
                original,
                CapabilityStudioFormalInputTreeSnapshotter
                        .MAXIMUM_TARGET_ADMISSION_MANIFEST_BYTES + 1));
        assertFailure(INVALID, () -> snapshotter.declare(
                TARGET_ADMISSION_BUNDLE, manifestTarget, TARGET_SEMANTIC));
    }

    @Test
    void forceChmodAndAtomicInstallFailuresAreUnavailableAndRetryable() throws Exception {
        for (String operation : List.of("force", "chmod", "move")) {
            Fixture fixture = fixture("fault-" + operation, AUTHORITY_BUNDLE);
            var failing = new CapabilityStudioFormalInputTreeSnapshotter(
                    CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver.NONE,
                    new FailOnceOperations(operation));
            assertFailure(UNAVAILABLE, () -> failing.snapshot(
                    fixture.kind(), fixture.source(), fixture.semantic(), fixture.output(),
                    fixture.declaration().treeFingerprint(), PUBLICATION_FINGERPRINT,
                    TRANSACTION_NONCE));

            assertThat(fixture.commit().commitStatus()).isIn(COMMITTED, RECOVERED);
            assertCommittedClosure(fixture.output());
        }
    }

    @Test
    void everyRenameStageRecoversSourceTargetFourStateWithoutUnknownMutation()
            throws Exception {
        for (RenameStage stage : RenameStage.values()) {
            for (InjectedInstallState state : InjectedInstallState.values()) {
                Fixture fixture = fixture("rename-" + stage + "-" + state, AUTHORITY_BUNDLE);
                var interrupted = new CapabilityStudioFormalInputTreeSnapshotter(
                        CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver.NONE,
                        new RenameStateOperations(stage, state), Duration.ofSeconds(5));
                assertFailure(UNAVAILABLE, () -> interrupted.snapshot(
                        fixture.kind(), fixture.source(), fixture.semantic(), fixture.output(),
                        fixture.declaration().treeFingerprint(), PUBLICATION_FINGERPRINT,
                        TRANSACTION_NONCE));
                if (stage == RenameStage.MANIFEST
                        && (state == InjectedInstallState.TARGET_ONLY
                        || state == InjectedInstallState.BOTH)) {
                    Files.move(fixture.source(), fixture.source().resolveSibling("unmounted"));
                }

                CapabilityStudioFormalInputTreeSnapshotter.SnapshotReceipt retry;
                try {
                    retry = fixture.commit();
                } catch (RuntimeException failure) {
                    throw new AssertionError("rename retry failed: " + stage + "/" + state,
                            failure);
                }

                assertThat(retry.commitStatus()).isEqualTo(
                        stage == RenameStage.MANIFEST
                                && (state == InjectedInstallState.TARGET_ONLY
                                || state == InjectedInstallState.BOTH)
                                ? RECOVERED : COMMITTED);
                assertCommittedClosure(fixture.output());
                assertNoTransactionResidue(fixture);
            }
        }
    }

    @Test
    void singleNameInstallStatesRejectExtraLinksWithoutChangingAnyNameOrInode()
            throws Exception {
        for (InjectedInstallState state : List.of(
                InjectedInstallState.SOURCE_ONLY, InjectedInstallState.TARGET_ONLY)) {
            for (int linkCount : List.of(2, 3)) {
                Fixture fixture = fixture("single-link-" + state + "-" + linkCount,
                        AUTHORITY_BUNDLE);
                Path external = CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                        fixture.workspace().resolve("external-links"));
                var setup = new InvalidSingleLinkStateOperations(state, linkCount, external);
                var interrupted = new CapabilityStudioFormalInputTreeSnapshotter(
                        CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver.NONE,
                        setup, Duration.ofSeconds(5));
                assertFailure(UNAVAILABLE, () -> snapshot(fixture, interrupted));
                Map<Path, LinkObservation> before = observeLinks(setup.observedPaths());
                Map<Path, DirectoryInventory> siblingsBefore =
                        observeSiblingInventories(setup.observedPaths());

                assertFailure(UNAVAILABLE, fixture::commit);

                assertThat(observeLinks(setup.observedPaths()))
                        .as(state + "/nlink=" + linkCount).isEqualTo(before);
                assertThat(observeSiblingInventories(setup.observedPaths()))
                        .as(state + "/nlink=" + linkCount + " siblings")
                        .isEqualTo(siblingsBefore);
            }
        }
    }

    @Test
    void distinctInodeBothIsBlockedWithoutChangingEitherReliableCopy() throws Exception {
        Fixture fixture = fixture("distinct-both", AUTHORITY_BUNDLE);
        var setup = new DistinctBothOperations();
        var interrupted = new CapabilityStudioFormalInputTreeSnapshotter(
                CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver.NONE,
                setup, Duration.ofSeconds(5));
        assertFailure(UNAVAILABLE, () -> snapshot(fixture, interrupted));
        Map<Path, LinkObservation> before = observeLinks(setup.observedPaths());
        Map<Path, DirectoryInventory> siblingsBefore =
                observeSiblingInventories(setup.observedPaths());
        assertThat(before.get(setup.source()).linkCount()).isEqualTo(1);
        assertThat(before.get(setup.target()).linkCount()).isEqualTo(1);
        assertThat(before.get(setup.source()).fileKey())
                .isNotEqualTo(before.get(setup.target()).fileKey());

        assertFailure(UNAVAILABLE, fixture::commit);

        assertThat(observeLinks(setup.observedPaths())).isEqualTo(before);
        assertThat(observeSiblingInventories(setup.observedPaths()))
                .isEqualTo(siblingsBefore);
    }

    @Test
    void sameInodeBothForcesTargetBeforeUnlinkAndSourceParentAfter() throws Exception {
        Fixture fixture = fixture("both-barrier-order", AUTHORITY_BUNDLE);
        var setup = new RenameStateOperations(
                RenameStage.BUNDLE_ROOT, InjectedInstallState.BOTH);
        var interrupted = new CapabilityStudioFormalInputTreeSnapshotter(
                CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver.NONE,
                setup, Duration.ofSeconds(5));
        assertFailure(UNAVAILABLE, () -> snapshot(fixture, interrupted));
        var probe = new BothBarrierProbe();

        var receipt = snapshot(fixture, new CapabilityStudioFormalInputTreeSnapshotter(
                probe, probe, Duration.ofSeconds(5)));

        int beforeUnlink = probe.events().indexOf("BEFORE_UNLINK");
        assertThat(receipt.commitStatus()).isEqualTo(COMMITTED);
        assertThat(beforeUnlink).isGreaterThanOrEqualTo(2);
        assertThat(probe.events().subList(beforeUnlink - 2, beforeUnlink + 3))
                .containsExactly(
                        "FORCE_FILE",
                        "FORCE_DIRECTORY:" + setup.target().getParent(),
                        "BEFORE_UNLINK",
                        "AFTER_UNLINK",
                        "FORCE_DIRECTORY:" + setup.source().getParent());
    }

    @Test
    void realChildBothUnlinkCrashPointsRecoverCommittedWithoutLosingTarget()
            throws Exception {
        for (String checkpoint : List.of("BOTH_BEFORE_UNLINK", "BOTH_AFTER_UNLINK")) {
            Fixture fixture = fixture("crash-" + checkpoint, AUTHORITY_BUNDLE);
            var setup = new RenameStateOperations(
                    RenameStage.BUNDLE_ROOT, InjectedInstallState.BOTH);
            var interrupted = new CapabilityStudioFormalInputTreeSnapshotter(
                    CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver.NONE,
                    setup, Duration.ofSeconds(5));
            assertFailure(UNAVAILABLE, () -> snapshot(fixture, interrupted));
            byte[] reliableBytes = Files.readAllBytes(setup.target());

            ProcessResult crashed = crashWorker(checkpoint, fixture);

            assertThat(crashed.timedOut()).as(checkpoint).isFalse();
            assertThat(crashed.exit()).as(checkpoint).isEqualTo(77);
            assertThat(Files.readAllBytes(setup.target())).as(checkpoint)
                    .isEqualTo(reliableBytes);
            assertThat(fixture.commit().commitStatus()).as(checkpoint).isEqualTo(COMMITTED);
            assertCommittedClosure(fixture.output());
            assertNoTransactionResidue(fixture);
        }
    }

    @Test
    void everyAtomicInstallForcesTargetParentThenDistinctSourceParent() throws Exception {
        Fixture fixture = fixture("rename-barriers", AUTHORITY_BUNDLE);
        var operations = new BarrierRecordingOperations();
        var snapshotter = new CapabilityStudioFormalInputTreeSnapshotter(
                CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver.NONE,
                operations, Duration.ofSeconds(5));

        snapshotter.snapshot(
                fixture.kind(), fixture.source(), fixture.semantic(), fixture.output(),
                fixture.declaration().treeFingerprint(), PUBLICATION_FINGERPRINT,
                TRANSACTION_NONCE);

        assertThat(operations.barriers()).isNotEmpty().allSatisfy(barrier -> {
            assertThat(barrier.forcedParents().getFirst()).isEqualTo(barrier.targetParent());
            if (barrier.sourceParent().equals(barrier.targetParent())) {
                assertThat(barrier.forcedParents()).containsExactly(barrier.targetParent());
            } else {
                assertThat(barrier.forcedParents()).containsExactly(
                        barrier.targetParent(), barrier.sourceParent());
            }
        });
        assertThat(operations.barriers()).noneMatch(RenameBarrier::directoryMove);
        assertThat(operations.barriers()).anyMatch(barrier ->
                barrier.targetParent().getFileName().toString().equals(
                        CapabilityStudioFormalInputTreeSnapshotter.BUNDLE_ROOT_DIRECTORY)
                        && barrier.targetParent().getParent().equals(fixture.output()));
        assertThat(operations.barriers()).anyMatch(barrier ->
                barrier.target().getFileName().toString().equals(
                        CapabilityStudioFormalInputTreeSnapshotter.MANIFEST_FILE));
    }

    @Test
    void aliasedBundleDirectoryIdentityBlocksWithoutDeletingEitherRealName() throws Exception {
        Fixture fixture = fixture("bundle-directory-alias", AUTHORITY_BUNDLE);
        var aliased = new CapabilityStudioFormalInputTreeSnapshotter(
                CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver.NONE,
                new AliasedBundleDirectoryOperations(), Duration.ofSeconds(5));

        assertFailure(UNAVAILABLE, () -> aliased.snapshot(
                fixture.kind(), fixture.source(), fixture.semantic(), fixture.output(),
                fixture.declaration().treeFingerprint(), PUBLICATION_FINGERPRINT,
                TRANSACTION_NONCE));

        Path stagedBundle = staging(fixture).resolve(
                CapabilityStudioFormalInputTreeSnapshotter.BUNDLE_ROOT_DIRECTORY);
        Path finalBundle = fixture.output().resolve(
                CapabilityStudioFormalInputTreeSnapshotter.BUNDLE_ROOT_DIRECTORY);
        try (var stagedFiles = Files.list(stagedBundle)) {
            assertThat(stagedFiles.toList()).isNotEmpty().allMatch(path ->
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
        }
        assertThat(finalBundle).isEmptyDirectory();
        assertThat(Files.readAttributes(stagedBundle, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey()).isNotEqualTo(
                Files.readAttributes(finalBundle, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS).fileKey());
        assertThat(fixture.commit().commitStatus()).isEqualTo(COMMITTED);
        assertCommittedClosure(fixture.output());
    }

    @Test
    void relativeAndNonNormalizedApiPathsAreRejectedBeforeFilesystemAccess() throws Exception {
        var snapshotter = new CapabilityStudioFormalInputTreeSnapshotter();
        assertFailure(INVALID, () -> snapshotter.declare(
                AUTHORITY_BUNDLE, Path.of("relative-source"), AUTHORITY_SEMANTIC));
        assertFailure(INVALID, () -> snapshotter.snapshot(
                AUTHORITY_BUNDLE,
                temporaryDirectory.toRealPath().resolve("source/../source"),
                AUTHORITY_SEMANTIC, Path.of("relative-output"),
                CapabilityStudioFormalInputTreeTestFixtures.fingerprint('1'),
                PUBLICATION_FINGERPRINT, TRANSACTION_NONCE));
    }

    private Fixture fixture(String name, CapabilityStudioFormalInputTreeSnapshotter.TreeKind kind)
            throws Exception {
        Path workspace = privateDirectory(name);
        Path sourceParent = CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                workspace.resolve("source"));
        Path source = bundle(kind, sourceParent);
        Path publicationParent = CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                workspace.resolve("publication"));
        var snapshotter = new CapabilityStudioFormalInputTreeSnapshotter();
        String semantic = semantic(kind);
        var declaration = snapshotter.declare(kind, source, semantic);
        return new Fixture(kind, semantic, workspace, publicationParent, source,
                publicationParent.resolve("snapshot"), snapshotter, declaration, null);
    }

    private Fixture committedFixture(
            String name,
            CapabilityStudioFormalInputTreeSnapshotter.TreeKind kind) throws Exception {
        Fixture fixture = fixture(name, kind);
        return fixture.withReceipt(fixture.commit());
    }

    private Path privateDirectory(String name) throws IOException {
        return CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                temporaryDirectory.toRealPath().resolve(name));
    }

    private static Path bundle(
            CapabilityStudioFormalInputTreeSnapshotter.TreeKind kind,
            Path parent) throws IOException {
        return kind == AUTHORITY_BUNDLE
                ? CapabilityStudioFormalInputTreeTestFixtures.authorityBundle(parent)
                : CapabilityStudioFormalInputTreeTestFixtures.targetBundle(parent);
    }

    private static String semantic(CapabilityStudioFormalInputTreeSnapshotter.TreeKind kind) {
        return kind == AUTHORITY_BUNDLE ? AUTHORITY_SEMANTIC : TARGET_SEMANTIC;
    }

    private static String transactionId(Fixture fixture) {
        return CapabilityStudioFormalInputTreeSnapshotter.computeTransactionId(
                fixture.kind(), fixture.semantic(), fixture.declaration().treeFingerprint(),
                PUBLICATION_FINGERPRINT, TRANSACTION_NONCE);
    }

    private static CapabilityStudioFormalInputTreeSnapshotter.SnapshotReceipt snapshot(
            Fixture fixture,
            CapabilityStudioFormalInputTreeSnapshotter snapshotter) {
        return snapshotter.snapshot(
                fixture.kind(), fixture.source(), fixture.semantic(), fixture.output(),
                fixture.declaration().treeFingerprint(), PUBLICATION_FINGERPRINT,
                TRANSACTION_NONCE);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static void assertInterruptedWaiter(
            AtomicReference<Throwable> failure,
            AtomicBoolean interruptPreserved) {
        assertThat(failure.get())
                .isInstanceOf(CapabilityStudioFormalInputTreeSnapshotter
                        .FormalInputTreeException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.FORMAL_INPUT_TREE.PUBLICATION_LOCK_INTERRUPTED");
        assertThat(interruptPreserved).isTrue();
    }

    private static Path staging(Fixture fixture) {
        return fixture.output().resolveSibling("." + fixture.output().getFileName()
                + ".formal-input-tree-v1." + transactionId(fixture).substring(7) + ".staging");
    }

    private static Path lockFile(Fixture fixture) {
        return fixture.parent().resolve(".formal-input-tree-publication-v1.lock");
    }

    private static Path owner(Fixture fixture) {
        return fixture.parent().resolve(".snapshot.formal-input-tree-owner-v1."
                + transactionId(fixture).substring(7) + ".json");
    }

    private static Path ownerBootstrap(Fixture fixture) {
        return fixture.parent().resolve(".snapshot.formal-input-tree-owner-v1."
                + TRANSACTION_NONCE.substring(7) + ".part");
    }

    private static void assertCommittedClosure(Path output) throws IOException {
        assertThat(Files.getPosixFilePermissions(output)).isEqualTo(PRIVATE_DIRECTORY);
        assertThat(Files.getPosixFilePermissions(output.resolve(
                CapabilityStudioFormalInputTreeSnapshotter.BUNDLE_ROOT_DIRECTORY)))
                .isEqualTo(PRIVATE_DIRECTORY);
        assertThat(Files.getPosixFilePermissions(output.resolve(
                CapabilityStudioFormalInputTreeSnapshotter.MANIFEST_FILE)))
                .isEqualTo(PRIVATE_FILE);
        try (var wrapper = Files.list(output)) {
            assertThat(wrapper.map(path -> path.getFileName().toString()).sorted().toList())
                    .containsExactly(
                            CapabilityStudioFormalInputTreeSnapshotter.BUNDLE_ROOT_DIRECTORY,
                            CapabilityStudioFormalInputTreeSnapshotter.MANIFEST_FILE);
        }
        try (var files = Files.list(output.resolve(
                CapabilityStudioFormalInputTreeSnapshotter.BUNDLE_ROOT_DIRECTORY))) {
            assertThat(files.toList()).allSatisfy(file -> {
                try {
                    assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PRIVATE_FILE);
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            });
        }
    }

    private static void assertNoTransactionResidue(Fixture fixture) throws IOException {
        try (var children = Files.list(fixture.parent())) {
            assertThat(children.map(path -> path.getFileName().toString()).sorted().toList())
                    .containsExactly(".formal-input-tree-publication-v1.lock", "snapshot");
        }
    }

    private static void copyCommittedWrapper(Path source, Path target) throws IOException {
        Files.createDirectory(target);
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwx------"));
        Path sourceBundle = source.resolve(
                CapabilityStudioFormalInputTreeSnapshotter.BUNDLE_ROOT_DIRECTORY);
        Path targetBundle = Files.createDirectory(target.resolve(
                CapabilityStudioFormalInputTreeSnapshotter.BUNDLE_ROOT_DIRECTORY));
        Files.setPosixFilePermissions(targetBundle, PosixFilePermissions.fromString("rwx------"));
        try (var files = Files.list(sourceBundle)) {
            for (Path file : files.toList()) {
                Path copy = Files.copy(file, targetBundle.resolve(file.getFileName()),
                        StandardCopyOption.COPY_ATTRIBUTES);
                Files.setPosixFilePermissions(copy, PRIVATE_FILE);
            }
        }
        Path manifest = Files.copy(
                source.resolve(CapabilityStudioFormalInputTreeSnapshotter.MANIFEST_FILE),
                target.resolve(CapabilityStudioFormalInputTreeSnapshotter.MANIFEST_FILE),
                StandardCopyOption.COPY_ATTRIBUTES);
        Files.setPosixFilePermissions(manifest, PRIVATE_FILE);
        Files.setPosixFilePermissions(targetBundle, PRIVATE_DIRECTORY);
        Files.setPosixFilePermissions(target, PRIVATE_DIRECTORY);
    }

    private static Map<String, NodeObservation> observeTree(Path root) throws IOException {
        Map<String, NodeObservation> observations = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                observations.put(root.relativize(path).toString(), new NodeObservation(
                        attributes.fileKey(), attributes.lastModifiedTime().toMillis(),
                        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS),
                        attributes.isRegularFile() ? Files.readAllBytes(path) : new byte[0]));
            }
        }
        return Map.copyOf(observations);
    }

    private static ProcessResult crashWorker(String checkpoint, Fixture fixture) throws Exception {
        return awaitWorker(startWorker(checkpoint, fixture), Duration.ofSeconds(30));
    }

    private static ManagedProcess startWorker(String checkpoint, Fixture fixture)
            throws IOException {
        Path output = Files.createTempFile(fixture.workspace(), "child-", ".out");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                FormalInputTreeCrashWorker.class.getName(), checkpoint,
                fixture.source().toString(), fixture.output().toString(),
                fixture.semantic(), fixture.declaration().treeFingerprint(),
                PUBLICATION_FINGERPRINT, TRANSACTION_NONCE)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        return new ManagedProcess(process, output);
    }

    private static ProcessResult awaitWorker(ManagedProcess managed, Duration timeout)
            throws Exception {
        Process process = managed.process();
        boolean completed = false;
        int exit = Integer.MIN_VALUE;
        try {
            completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (completed) {
                exit = process.exitValue();
            }
        } finally {
            terminateAndReap(process);
        }
        return new ProcessResult(exit, Files.readAllBytes(managed.output()), !completed);
    }

    private static void awaitOutput(Path output, String expected, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.readString(output).contains(expected)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("child readiness timeout");
    }

    private static void terminateAndReap(Process process) {
        boolean interrupted = Thread.interrupted();
        if (process.isAlive()) {
            process.destroy();
            boolean terminated = false;
            try {
                terminated = process.waitFor(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException failure) {
                interrupted = true;
            }
            if (!terminated && process.isAlive()) {
                process.destroyForcibly();
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (process.isAlive() && System.nanoTime() < deadline) {
                    try {
                        process.waitFor(100, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException failure) {
                        interrupted = true;
                    }
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        assertThat(process.isAlive()).isFalse();
    }

    private static void assertFailure(
            CapabilityStudioFormalInputTreeSnapshotter.FailureKind kind,
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(
                        CapabilityStudioFormalInputTreeSnapshotter.FormalInputTreeException.class,
                        failure -> assertThat(failure.failureKind()).isEqualTo(kind))
                .hasMessageNotContaining("SECRET")
                .hasMessageNotContaining("CREDENTIAL")
                .hasMessageNotContaining("PAYLOAD");
    }

    private static CapabilityStudioFormalInputTreeSnapshotter.AtomicOperations localOperations() {
        return new CapabilityStudioFormalInputTreeSnapshotter.AtomicOperations() {
            @Override
            public void forceFile(java.nio.channels.FileChannel channel) throws IOException {
                channel.force(true);
            }

            @Override
            public void chmod(Path path, Set<PosixFilePermission> permissions) throws IOException {
                Files.setPosixFilePermissions(path, permissions);
            }

            @Override
            public void forceDirectory(Path directory) throws IOException {
                try (var channel = java.nio.channels.FileChannel.open(
                        directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    channel.force(true);
                }
            }

            @Override
            public void atomicMove(Path source, Path target) throws IOException {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
        };
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.createDirectory(target);
        try (var children = Files.list(source)) {
            for (Path child : children.toList()) {
                Files.copy(child, target.resolve(child.getFileName()));
            }
        }
    }

    private static byte[] repeated(int size, byte value) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private static byte[] paddedJson(byte[] json, int size) {
        assertThat(json.length).isLessThanOrEqualTo(size);
        byte[] padded = Arrays.copyOf(json, size);
        Arrays.fill(padded, json.length, size, (byte) ' ');
        return padded;
    }

    private static Observation observation(Path directory, Path file) throws IOException {
        BasicFileAttributes directoryAttributes = Files.readAttributes(
                directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        BasicFileAttributes fileAttributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return new Observation(
                directoryAttributes.fileKey(), Files.getPosixFilePermissions(directory),
                fileAttributes.fileKey(), Files.getPosixFilePermissions(file),
                Files.readAllBytes(file));
    }

    private static Map<Path, LinkObservation> observeLinks(List<Path> paths) throws IOException {
        Map<Path, LinkObservation> observations = new LinkedHashMap<>();
        for (Path path : paths) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                observations.put(path, new LinkObservation(
                        true, attributes.fileKey(),
                        ((Number) Files.getAttribute(
                                path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue(),
                        ((Number) Files.getAttribute(
                                path, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue(),
                        attributes.lastModifiedTime().toMillis(),
                        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS),
                        Files.readAllBytes(path)));
            } catch (java.nio.file.NoSuchFileException absent) {
                observations.put(path, new LinkObservation(
                        false, null, 0, -1, -1, Set.of(), new byte[0]));
            }
        }
        return Map.copyOf(observations);
    }

    private static Map<Path, DirectoryInventory> observeSiblingInventories(List<Path> paths)
            throws IOException {
        Map<Path, DirectoryInventory> inventories = new LinkedHashMap<>();
        for (Path path : paths) {
            Path directory = path.getParent();
            if (inventories.containsKey(directory)) {
                continue;
            }
            BasicFileAttributes directoryAttributes = Files.readAttributes(
                    directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Map<String, SiblingObservation> entries = new LinkedHashMap<>();
            try (var children = Files.list(directory)) {
                for (Path child : children.sorted().toList()) {
                    BasicFileAttributes attributes = Files.readAttributes(
                            child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    String type = attributes.isSymbolicLink() ? "SYMLINK"
                            : attributes.isRegularFile() ? "REGULAR"
                            : attributes.isDirectory() ? "DIRECTORY" : "OTHER";
                    entries.put(child.getFileName().toString(), new SiblingObservation(
                            type, attributes.fileKey(), attributes.size(),
                            attributes.lastModifiedTime().toMillis(),
                            ((Number) Files.getAttribute(
                                    child, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue(),
                            ((Number) Files.getAttribute(
                                    child, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue(),
                            Files.getPosixFilePermissions(child, LinkOption.NOFOLLOW_LINKS),
                            attributes.isRegularFile()
                                    ? rawFingerprint(Files.readAllBytes(child)) : null));
                }
            }
            inventories.put(directory, new DirectoryInventory(
                    directoryAttributes.fileKey(),
                    directoryAttributes.lastModifiedTime().toMillis(),
                    ((Number) Files.getAttribute(
                            directory, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue(),
                    Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS), entries));
        }
        return Map.copyOf(inventories);
    }

    private static String rawFingerprint(byte[] bytes) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static int strictJsonFieldCount(String value) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                    com.fasterxml.jackson.core.JsonFactory.builder()
                            .enable(com.fasterxml.jackson.core.StreamReadFeature
                                    .STRICT_DUPLICATE_DETECTION)
                            .build());
            return mapper.readTree(value).size();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record Fixture(
            CapabilityStudioFormalInputTreeSnapshotter.TreeKind kind,
            String semantic,
            Path workspace,
            Path parent,
            Path source,
            Path output,
            CapabilityStudioFormalInputTreeSnapshotter snapshotter,
            CapabilityStudioFormalInputTreeSnapshotter.Declaration declaration,
            CapabilityStudioFormalInputTreeSnapshotter.SnapshotReceipt receipt) {
        private CapabilityStudioFormalInputTreeSnapshotter.SnapshotReceipt commit() {
            return snapshotter.snapshot(kind, source, semantic, output,
                    declaration.treeFingerprint(), PUBLICATION_FINGERPRINT, TRANSACTION_NONCE);
        }

        private Fixture withReceipt(
                CapabilityStudioFormalInputTreeSnapshotter.SnapshotReceipt value) {
            return new Fixture(kind, semantic, workspace, parent, source, output,
                    snapshotter, declaration, value);
        }
    }

    private record ManagedProcess(Process process, Path output) {
    }

    private record ProcessResult(int exit, byte[] output, boolean timedOut) {
        private ProcessResult {
            output = output.clone();
        }
    }

    private record NodeObservation(
            Object fileKey,
            long modifiedMillis,
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
                    && permissions.equals(that.permissions)
                    && Arrays.equals(bytes, that.bytes);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(
                    fileKey, modifiedMillis, permissions, Arrays.hashCode(bytes));
        }
    }

    private record Observation(
            Object directoryKey,
            Set<PosixFilePermission> directoryPermissions,
            Object fileKey,
            Set<PosixFilePermission> filePermissions,
            byte[] bytes) {
        private Observation {
            directoryPermissions = Set.copyOf(directoryPermissions);
            filePermissions = Set.copyOf(filePermissions);
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Observation that
                    && java.util.Objects.equals(directoryKey, that.directoryKey)
                    && directoryPermissions.equals(that.directoryPermissions)
                    && java.util.Objects.equals(fileKey, that.fileKey)
                    && filePermissions.equals(that.filePermissions)
                    && Arrays.equals(bytes, that.bytes);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(
                    directoryKey, directoryPermissions, fileKey, filePermissions,
                    Arrays.hashCode(bytes));
        }
    }

    private record LinkObservation(
            boolean present,
            Object fileKey,
            long linkCount,
            long ownerUid,
            long modifiedMillis,
            Set<PosixFilePermission> permissions,
            byte[] bytes) {
        private LinkObservation {
            permissions = Set.copyOf(permissions);
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof LinkObservation that
                    && present == that.present
                    && java.util.Objects.equals(fileKey, that.fileKey)
                    && linkCount == that.linkCount
                    && ownerUid == that.ownerUid
                    && modifiedMillis == that.modifiedMillis
                    && permissions.equals(that.permissions)
                    && Arrays.equals(bytes, that.bytes);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(
                    present, fileKey, linkCount, ownerUid, modifiedMillis, permissions,
                    Arrays.hashCode(bytes));
        }
    }

    private record DirectoryInventory(
            Object fileKey,
            long modifiedMillis,
            long ownerUid,
            Set<PosixFilePermission> permissions,
            Map<String, SiblingObservation> entries) {
        private DirectoryInventory {
            permissions = Set.copyOf(permissions);
            entries = Map.copyOf(entries);
        }
    }

    private record SiblingObservation(
            String type,
            Object fileKey,
            long size,
            long modifiedMillis,
            long linkCount,
            long ownerUid,
            Set<PosixFilePermission> permissions,
            String rawFingerprint) {
        private SiblingObservation {
            permissions = Set.copyOf(permissions);
        }
    }

    private enum MetadataFault {
        NULL_FILE_KEY,
        POSIX_UNSUPPORTED,
        UNIX_UNSUPPORTED,
        DISAPPEARS_AFTER_READ,
        SYMLINK_NULL_FILE_KEY,
        DIRECTORY_NULL_FILE_KEY,
        SECOND_READ_SYMLINK_NULL_FILE_KEY,
        SECOND_READ_DIRECTORY_NULL_FILE_KEY
    }

    private static final class MetadataFaultOperations
            implements CapabilityStudioFormalInputTreeSnapshotter.AtomicOperations {
        private final Path target;
        private final MetadataFault fault;
        private final CapabilityStudioFormalInputTreeSnapshotter.AtomicOperations delegate =
                localOperations();
        private int targetAttributeReads;

        private MetadataFaultOperations(Path target, MetadataFault fault) {
            this.target = target;
            this.fault = fault;
        }

        @Override
        public void forceFile(java.nio.channels.FileChannel channel) throws IOException {
            delegate.forceFile(channel);
        }

        @Override
        public void chmod(Path path, Set<PosixFilePermission> permissions) throws IOException {
            delegate.chmod(path, permissions);
        }

        @Override
        public void forceDirectory(Path directory) throws IOException {
            delegate.forceDirectory(directory);
        }

        @Override
        public void atomicMove(Path source, Path targetPath) throws IOException {
            delegate.atomicMove(source, targetPath);
        }

        @Override
        public BasicFileAttributes readAttributes(Path path) throws IOException {
            BasicFileAttributes attributes = delegate.readAttributes(path);
            if (!path.equals(target)) {
                return attributes;
            }
            targetAttributeReads++;
            if (fault == MetadataFault.DISAPPEARS_AFTER_READ && targetAttributeReads == 2) {
                throw new java.nio.file.NoSuchFileException("redacted");
            }
            return switch (fault) {
                case NULL_FILE_KEY -> new NullFileKeyAttributes(attributes);
                case SYMLINK_NULL_FILE_KEY ->
                        new NullFileKeyTypeAttributes(attributes, false, false, true);
                case DIRECTORY_NULL_FILE_KEY ->
                        new NullFileKeyTypeAttributes(attributes, false, true, false);
                case SECOND_READ_SYMLINK_NULL_FILE_KEY -> targetAttributeReads == 2
                        ? new NullFileKeyTypeAttributes(attributes, false, false, true)
                        : attributes;
                case SECOND_READ_DIRECTORY_NULL_FILE_KEY -> targetAttributeReads == 2
                        ? new NullFileKeyTypeAttributes(attributes, false, true, false)
                        : attributes;
                default -> attributes;
            };
        }

        @Override
        public long readUnixLong(Path path, String attribute) throws IOException {
            if (path.equals(target) && fault == MetadataFault.UNIX_UNSUPPORTED) {
                throw new UnsupportedOperationException("redacted");
            }
            return delegate.readUnixLong(path, attribute);
        }

        @Override
        public Set<PosixFilePermission> readPosixPermissions(Path path) throws IOException {
            if (path.equals(target) && fault == MetadataFault.POSIX_UNSUPPORTED) {
                throw new UnsupportedOperationException("redacted");
            }
            return delegate.readPosixPermissions(path);
        }
    }

    private record NullFileKeyAttributes(BasicFileAttributes delegate)
            implements BasicFileAttributes {
        @Override
        public java.nio.file.attribute.FileTime lastModifiedTime() {
            return delegate.lastModifiedTime();
        }

        @Override
        public java.nio.file.attribute.FileTime lastAccessTime() {
            return delegate.lastAccessTime();
        }

        @Override
        public java.nio.file.attribute.FileTime creationTime() {
            return delegate.creationTime();
        }

        @Override
        public boolean isRegularFile() {
            return delegate.isRegularFile();
        }

        @Override
        public boolean isDirectory() {
            return delegate.isDirectory();
        }

        @Override
        public boolean isSymbolicLink() {
            return delegate.isSymbolicLink();
        }

        @Override
        public boolean isOther() {
            return delegate.isOther();
        }

        @Override
        public long size() {
            return delegate.size();
        }

        @Override
        public Object fileKey() {
            return null;
        }
    }

    private record NullFileKeyTypeAttributes(
            BasicFileAttributes delegate,
            boolean regularFile,
            boolean directory,
            boolean symbolicLink) implements BasicFileAttributes {
        @Override
        public java.nio.file.attribute.FileTime lastModifiedTime() {
            return delegate.lastModifiedTime();
        }

        @Override
        public java.nio.file.attribute.FileTime lastAccessTime() {
            return delegate.lastAccessTime();
        }

        @Override
        public java.nio.file.attribute.FileTime creationTime() {
            return delegate.creationTime();
        }

        @Override
        public boolean isRegularFile() {
            return regularFile;
        }

        @Override
        public boolean isDirectory() {
            return directory;
        }

        @Override
        public boolean isSymbolicLink() {
            return symbolicLink;
        }

        @Override
        public boolean isOther() {
            return !regularFile && !directory && !symbolicLink;
        }

        @Override
        public long size() {
            return delegate.size();
        }

        @Override
        public Object fileKey() {
            return null;
        }
    }

    private static final class SequenceTicker
            implements CapabilityStudioFormalInputTreeSnapshotter.MonotonicTicker {
        private final long[] ticks;
        private int index;

        private SequenceTicker(long... ticks) {
            this.ticks = ticks.clone();
        }

        @Override
        public long read() {
            if (ticks.length == 0) {
                throw new IllegalStateException("empty ticker");
            }
            return ticks[Math.min(index++, ticks.length - 1)];
        }
    }

    private static final class FailOnceOperations
            implements CapabilityStudioFormalInputTreeSnapshotter.AtomicOperations {
        private final String target;
        private final CapabilityStudioFormalInputTreeSnapshotter.AtomicOperations delegate =
                localOperations();
        private boolean failed;

        private FailOnceOperations(String target) {
            this.target = target;
        }

        @Override
        public void forceFile(java.nio.channels.FileChannel channel) throws IOException {
            fail("force");
            delegate.forceFile(channel);
        }

        @Override
        public void chmod(Path path, Set<PosixFilePermission> permissions) throws IOException {
            fail("chmod");
            delegate.chmod(path, permissions);
        }

        @Override
        public void forceDirectory(Path directory) throws IOException {
            fail("directory-force");
            delegate.forceDirectory(directory);
        }

        @Override
        public void atomicMove(Path source, Path targetPath) throws IOException {
            fail("move");
            delegate.atomicMove(source, targetPath);
        }

        private void fail(String operation) throws IOException {
            if (!failed && target.equals(operation)) {
                failed = true;
                throw new IOException("injected");
            }
        }
    }

    private static final class DelayedFirstForceOperations
            implements CapabilityStudioFormalInputTreeSnapshotter.AtomicOperations {
        private final long delayMillis;
        private final CapabilityStudioFormalInputTreeSnapshotter.AtomicOperations delegate =
                localOperations();
        private boolean delayed;

        private DelayedFirstForceOperations(Duration delay) {
            delayMillis = delay.toMillis();
        }

        @Override
        public void forceFile(java.nio.channels.FileChannel channel) throws IOException {
            if (!delayed) {
                delayed = true;
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", failure);
                }
            }
            delegate.forceFile(channel);
        }

        @Override
        public void chmod(Path path, Set<PosixFilePermission> permissions) throws IOException {
            delegate.chmod(path, permissions);
        }

        @Override
        public void forceDirectory(Path directory) throws IOException {
            delegate.forceDirectory(directory);
        }

        @Override
        public void atomicMove(Path source, Path target) throws IOException {
            delegate.atomicMove(source, target);
        }
    }

    private static final class InvalidSingleLinkStateOperations
            implements CapabilityStudioFormalInputTreeSnapshotter.AtomicOperations {
        private final InjectedInstallState state;
        private final int linkCount;
        private final Path externalDirectory;
        private final CapabilityStudioFormalInputTreeSnapshotter.AtomicOperations delegate =
                localOperations();
        private final List<Path> observedPaths = new ArrayList<>();
        private boolean injected;

        private InvalidSingleLinkStateOperations(
                InjectedInstallState state,
                int linkCount,
                Path externalDirectory) {
            this.state = state;
            this.linkCount = linkCount;
            this.externalDirectory = externalDirectory;
        }

        @Override
        public void forceFile(java.nio.channels.FileChannel channel) throws IOException {
            delegate.forceFile(channel);
        }

        @Override
        public void chmod(Path path, Set<PosixFilePermission> permissions) throws IOException {
            delegate.chmod(path, permissions);
        }

        @Override
        public void forceDirectory(Path directory) throws IOException {
            delegate.forceDirectory(directory);
        }

        @Override
        public void atomicMove(Path source, Path target) throws IOException {
            if (injected || !bundleFileMove(source, target)) {
                delegate.atomicMove(source, target);
                return;
            }
            injected = true;
            if (state == InjectedInstallState.TARGET_ONLY) {
                delegate.atomicMove(source, target);
            }
            Path reliable = state == InjectedInstallState.SOURCE_ONLY ? source : target;
            observedPaths.add(source);
            observedPaths.add(target);
            for (int index = 1; index < linkCount; index++) {
                Path link = externalDirectory.resolve("link-" + index);
                Files.createLink(link, reliable);
                observedPaths.add(link);
            }
            throw new IOException("injected invalid single-name link count");
        }

        private List<Path> observedPaths() {
            return List.copyOf(observedPaths);
        }
    }

    private static final class DistinctBothOperations
            implements CapabilityStudioFormalInputTreeSnapshotter.AtomicOperations {
        private final CapabilityStudioFormalInputTreeSnapshotter.AtomicOperations delegate =
                localOperations();
        private boolean injected;
        private Path source;
        private Path target;

        @Override
        public void forceFile(java.nio.channels.FileChannel channel) throws IOException {
            delegate.forceFile(channel);
        }

        @Override
        public void chmod(Path path, Set<PosixFilePermission> permissions) throws IOException {
            delegate.chmod(path, permissions);
        }

        @Override
        public void forceDirectory(Path directory) throws IOException {
            delegate.forceDirectory(directory);
        }

        @Override
        public void atomicMove(Path movedSource, Path movedTarget) throws IOException {
            if (injected || !bundleFileMove(movedSource, movedTarget)) {
                delegate.atomicMove(movedSource, movedTarget);
                return;
            }
            injected = true;
            source = movedSource;
            target = movedTarget;
            delegate.atomicMove(source, target);
            Files.copy(target, source, StandardCopyOption.COPY_ATTRIBUTES);
            Files.setPosixFilePermissions(source, Files.getPosixFilePermissions(target));
            throw new IOException("injected distinct both");
        }

        private Path source() {
            return source;
        }

        private Path target() {
            return target;
        }

        private List<Path> observedPaths() {
            return List.of(source, target);
        }
    }

    private static final class BothBarrierProbe
            implements CapabilityStudioFormalInputTreeSnapshotter.AtomicOperations,
            CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver {
        private final CapabilityStudioFormalInputTreeSnapshotter.AtomicOperations delegate =
                localOperations();
        private final List<String> events = new ArrayList<>();

        @Override
        public void forceFile(java.nio.channels.FileChannel channel) throws IOException {
            delegate.forceFile(channel);
            events.add("FORCE_FILE");
        }

        @Override
        public void chmod(Path path, Set<PosixFilePermission> permissions) throws IOException {
            delegate.chmod(path, permissions);
        }

        @Override
        public void forceDirectory(Path directory) throws IOException {
            delegate.forceDirectory(directory);
            events.add("FORCE_DIRECTORY:" + directory);
        }

        @Override
        public void atomicMove(Path source, Path target) throws IOException {
            delegate.atomicMove(source, target);
        }

        @Override
        public void beforeSourceUnlink(Path source, Path target) {
            events.add("BEFORE_UNLINK");
        }

        @Override
        public void afterSourceUnlink(Path source, Path target) {
            events.add("AFTER_UNLINK");
        }

        private List<String> events() {
            return List.copyOf(events);
        }
    }

    private static boolean bundleFileMove(Path source, Path target) {
        return source.getParent().getFileName().toString().equals(
                CapabilityStudioFormalInputTreeSnapshotter.BUNDLE_ROOT_DIRECTORY)
                && target.getParent().getFileName().toString().equals(
                CapabilityStudioFormalInputTreeSnapshotter.BUNDLE_ROOT_DIRECTORY)
                && target.getParent().getParent().getFileName().toString().equals("snapshot");
    }

    private enum RenameStage {
        PAYLOAD,
        BUNDLE_ROOT,
        MANIFEST
    }

    private enum InjectedInstallState {
        SOURCE_ONLY,
        TARGET_ONLY,
        BOTH,
        NEITHER
    }

    private static final class RenameStateOperations
            implements CapabilityStudioFormalInputTreeSnapshotter.AtomicOperations {
        private final RenameStage stage;
        private final InjectedInstallState state;
        private final CapabilityStudioFormalInputTreeSnapshotter.AtomicOperations delegate =
                localOperations();
        private boolean injected;
        private Path source;
        private Path target;

        private RenameStateOperations(RenameStage stage, InjectedInstallState state) {
            this.stage = stage;
            this.state = state;
        }

        @Override
        public void forceFile(java.nio.channels.FileChannel channel) throws IOException {
            delegate.forceFile(channel);
        }

        @Override
        public void chmod(Path path, Set<PosixFilePermission> permissions) throws IOException {
            delegate.chmod(path, permissions);
        }

        @Override
        public void forceDirectory(Path directory) throws IOException {
            delegate.forceDirectory(directory);
        }

        @Override
        public void atomicMove(Path source, Path target) throws IOException {
            if (injected || !matches(source, target)) {
                delegate.atomicMove(source, target);
                return;
            }
            injected = true;
            this.source = source;
            this.target = target;
            if (state == InjectedInstallState.SOURCE_ONLY) {
                throw new IOException("injected source-only");
            }
            if (state == InjectedInstallState.NEITHER) {
                deleteTree(source);
                throw new IOException("injected neither");
            }
            delegate.atomicMove(source, target);
            if (state == InjectedInstallState.BOTH) {
                Files.createLink(source, target);
            }
            throw new IOException("injected post-move state");
        }

        private Path source() {
            return source;
        }

        private Path target() {
            return target;
        }

        private boolean matches(Path source, Path target) {
            return switch (stage) {
                case PAYLOAD -> source.getParent().getFileName().toString().equals(".parts");
                case BUNDLE_ROOT -> source.getParent().getFileName().toString().equals(
                        CapabilityStudioFormalInputTreeSnapshotter.BUNDLE_ROOT_DIRECTORY)
                        && target.getParent().getFileName().toString().equals(
                        CapabilityStudioFormalInputTreeSnapshotter.BUNDLE_ROOT_DIRECTORY)
                        && target.getParent().getParent().getFileName().toString()
                        .equals("snapshot");
                case MANIFEST -> target.getFileName().toString().equals(
                        CapabilityStudioFormalInputTreeSnapshotter.MANIFEST_FILE);
            };
        }

        private static void deleteTree(Path path) throws IOException {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                try (var children = Files.list(path)) {
                    for (Path child : children.toList()) {
                        deleteTree(child);
                    }
                }
            }
            Files.delete(path);
        }
    }

    private static final class BarrierRecordingOperations
            implements CapabilityStudioFormalInputTreeSnapshotter.AtomicOperations {
        private final CapabilityStudioFormalInputTreeSnapshotter.AtomicOperations delegate =
                localOperations();
        private final List<RenameBarrier> barriers = new ArrayList<>();
        private Path source;
        private Path target;
        private boolean directoryMove;
        private final List<Path> forced = new ArrayList<>();

        @Override
        public void forceFile(java.nio.channels.FileChannel channel) throws IOException {
            delegate.forceFile(channel);
        }

        @Override
        public void chmod(Path path, Set<PosixFilePermission> permissions) throws IOException {
            delegate.chmod(path, permissions);
        }

        @Override
        public void forceDirectory(Path directory) throws IOException {
            delegate.forceDirectory(directory);
            if (source != null) {
                forced.add(directory);
                int expected = source.getParent().equals(target.getParent()) ? 1 : 2;
                if (forced.size() == expected) {
                    barriers.add(new RenameBarrier(
                            source, target, source.getParent(), target.getParent(),
                            List.copyOf(forced), directoryMove));
                    source = null;
                    target = null;
                    forced.clear();
                }
            }
        }

        @Override
        public void atomicMove(Path movedSource, Path movedTarget) throws IOException {
            assertThat(source).isNull();
            directoryMove = Files.isDirectory(movedSource, LinkOption.NOFOLLOW_LINKS);
            delegate.atomicMove(movedSource, movedTarget);
            source = movedSource;
            target = movedTarget;
        }

        private List<RenameBarrier> barriers() {
            assertThat(source).isNull();
            return List.copyOf(barriers);
        }
    }

    private static final class AliasedBundleDirectoryOperations
            implements CapabilityStudioFormalInputTreeSnapshotter.AtomicOperations {
        private static final Object ALIASED_IDENTITY = "same-directory-inode";
        private final CapabilityStudioFormalInputTreeSnapshotter.AtomicOperations delegate =
                localOperations();

        @Override
        public void forceFile(java.nio.channels.FileChannel channel) throws IOException {
            delegate.forceFile(channel);
        }

        @Override
        public void chmod(Path path, Set<PosixFilePermission> permissions) throws IOException {
            delegate.chmod(path, permissions);
        }

        @Override
        public void forceDirectory(Path directory) throws IOException {
            delegate.forceDirectory(directory);
        }

        @Override
        public void atomicMove(Path source, Path target) throws IOException {
            delegate.atomicMove(source, target);
        }

        @Override
        public Object directoryIdentity(Path directory) throws IOException {
            if (directory.getFileName().toString().equals(
                    CapabilityStudioFormalInputTreeSnapshotter.BUNDLE_ROOT_DIRECTORY)) {
                return ALIASED_IDENTITY;
            }
            return delegate.directoryIdentity(directory);
        }
    }

    private record RenameBarrier(
            Path source,
            Path target,
            Path sourceParent,
            Path targetParent,
            List<Path> forcedParents,
            boolean directoryMove) {
    }
}

final class FormalInputTreeCrashWorker {
    private FormalInputTreeCrashWorker() {
    }

    public static void main(String[] args) {
        String checkpoint = args[0];
        Path source = Path.of(args[1]);
        Path output = Path.of(args[2]);
        String semantic = args[3];
        String tree = args[4];
        String publication = args[5];
        String nonce = args[6];
        if (checkpoint.equals("HANG")) {
            hang();
        }
        if (checkpoint.equals("HANG_LOCK")) {
            Path lock = output.getParent().resolve(
                    ".formal-input-tree-publication-v1.lock");
            try (var channel = java.nio.channels.FileChannel.open(
                    lock, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    var ignored = channel.lock()) {
                System.out.print("LOCKED\n");
                System.out.flush();
                hang();
            } catch (IOException failure) {
                Runtime.getRuntime().halt(78);
            }
        }
        var snapshotter = new CapabilityStudioFormalInputTreeSnapshotter(
                new HaltingObserver(checkpoint), localCrashOperations());
        if (checkpoint.startsWith("CP10_")) {
            String[] cli = {
                    "--mode", "snapshot", "--tree-kind", "AUTHORITY_BUNDLE",
                    "--source-root", source.toString(),
                    "--expected-bundle-semantic-fingerprint", semantic,
                    "--snapshot-output-dir", output.toString(),
                    "--expected-tree-fingerprint", tree,
                    "--expected-publication-fingerprint", publication,
                    "--transaction-nonce", nonce
            };
            PrintStream stream = checkpoint.equals("CP10_PARTIAL")
                    ? partialHaltingOutput() : flushFailingOutput();
            System.exit(CapabilityStudioFormalInputTreeCli.run(cli, stream, snapshotter));
        }
        snapshotter.snapshot(AUTHORITY_BUNDLE, source, semantic, output,
                tree, publication, nonce);
        if (checkpoint.equals("COMMIT")) {
            System.exit(0);
        }
        Runtime.getRuntime().halt(79);
    }

    private static void hang() {
        while (true) {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException ignored) {
                // The parent harness terminates this deliberately blocked process.
            }
        }
    }

    private static CapabilityStudioFormalInputTreeSnapshotter.AtomicOperations
            localCrashOperations() {
        return new CapabilityStudioFormalInputTreeSnapshotter.AtomicOperations() {
            @Override
            public void forceFile(java.nio.channels.FileChannel channel) throws IOException {
                channel.force(true);
            }

            @Override
            public void chmod(Path path, Set<PosixFilePermission> permissions) throws IOException {
                Files.setPosixFilePermissions(path, permissions);
            }

            @Override
            public void forceDirectory(Path directory) throws IOException {
                try (var channel = java.nio.channels.FileChannel.open(
                        directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    channel.force(true);
                }
            }

            @Override
            public void atomicMove(Path source, Path target) throws IOException {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
        };
    }

    private static PrintStream partialHaltingOutput() {
        return new PrintStream(new OutputStream() {
            private int written;

            @Override
            public void write(int value) throws IOException {
                if (written++ == 16) {
                    System.out.flush();
                    Runtime.getRuntime().halt(77);
                }
                System.out.write(value);
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
                throw new IllegalStateException("flush failed");
            }
        }, false, StandardCharsets.UTF_8);
    }

    private static final class HaltingObserver
            implements CapabilityStudioFormalInputTreeSnapshotter.SnapshotObserver {
        private final String checkpoint;

        private HaltingObserver(String checkpoint) {
            this.checkpoint = checkpoint;
        }

        @Override
        public void beforeStaging(Path staging) {
            halt("CP0");
        }

        @Override
        public void afterStagingRoot(Path staging) {
            halt("CP1");
        }

        @Override
        public void afterOwnerClaim(Path owner) {
            halt("OWNER_CLAIM");
        }

        @Override
        public void afterPartFirstChunk(Path part) {
            String name = part.toString();
            if (name.contains("/.parts/")) {
                halt("MID_PAYLOAD");
            } else if (name.contains("manifest")) {
                halt("MID_MANIFEST");
            } else if (name.contains("owner")) {
                halt("OWNER_PART");
            }
        }

        @Override
        public void afterFileForce(Path file) {
            if (file.toString().contains("/.parts/")) {
                halt("CP3");
            }
        }

        @Override
        public void afterBundleFile(Path staging, String relativePath, int index) {
            if (index == 0) {
                halt("CP2");
            }
        }

        @Override
        public void afterClosureForce(Path directory) {
            halt("CP4");
        }

        @Override
        public void afterChmod(Path path) {
            if (path.getFileName().toString().equals(
                    CapabilityStudioFormalInputTreeSnapshotter.BUNDLE_ROOT_DIRECTORY)) {
                halt("CP5");
            }
        }

        @Override
        public void afterObjectForce(Path path) {
            if (path.toString().contains("manifest")) {
                halt("OBJECT_FORCE");
            }
        }

        @Override
        public void beforeSourceUnlink(Path source, Path target) {
            halt("BOTH_BEFORE_UNLINK");
        }

        @Override
        public void afterSourceUnlink(Path source, Path target) {
            halt("BOTH_AFTER_UNLINK");
        }

        @Override
        public void beforePublish(Path staging, Path output) {
            halt("CP6");
        }

        @Override
        public void afterPublish(Path output) {
            halt("CP7");
        }

        @Override
        public void afterParentForce(Path output) {
            halt("CP8");
        }

        @Override
        public void afterPersistedVerify(Path output) {
            halt("CP9");
        }

        private void halt(String point) {
            if (checkpoint.equals(point)) {
                Runtime.getRuntime().halt(77);
            }
        }
    }
}
