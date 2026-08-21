package com.leanowtech.bloge.gateway.testkit.mounted;

import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.AdmissionLifecycleMaterial;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.AdmissionLifecycleRequest;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.DeploymentDecisionStatus;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseCommitStatus;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseRequest;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.RevocationAuthoritySnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilesystemDeploymentAdmissionAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
    private static final String RAW = fingerprint('a');
    private static final String CANONICAL = fingerprint('b');
    private static final String DEPLOYMENT = fingerprint('c');
    private static final String OUTER = fingerprint('d');
    private static final String LEASE = "lease:one";

    @TempDir
    Path temporaryDirectory;

    @Test
    void commitsOnceAndRecoversIdenticalReceiptAcrossNewInstancesAndTimes() throws Exception {
        Path stateRoot = stateRoot("recovery");
        AdmissionLifecycleMaterial material = lifecycle(1, null, 1,
                NOW.minusSeconds(60), NOW.plusSeconds(600));
        var firstAuthority = authority(stateRoot, material);
        ExecutionLeaseRequest firstRequest = request(material, "lease:one", "1", NOW);

        var first = firstAuthority.commit(firstRequest);
        var restartedAuthority = authority(stateRoot, material);
        var recovered = restartedAuthority.commit(
                request(material, "lease:one", "1", NOW.plusSeconds(30)));

        assertThat(first.status()).isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);
        assertThat(recovered.status()).isEqualTo(ExecutionLeaseCommitStatus.RECOVERED);
        assertThat(recovered.receipt()).isEqualTo(first.receipt());
        assertThat(recovered.receipt().fingerprint()).isEqualTo(first.receipt().fingerprint());
        assertThat(recovered.receipt().lifecycleCommitReceipt().fencingSequence()).isEqualTo(1);
        assertThat(recovered.receipt().lifecycleCommitReceipt()
                .deploymentAdmissionAuthorityMaterialFingerprint()).isEqualTo(DEPLOYMENT);
        assertThat(Files.readString(stateRoot.resolve(
                FilesystemDeploymentAdmissionAuthority.STATE_FILE)))
                .doesNotContain("lease:one")
                .contains("\"fencingSequence\":1");
    }

    @Test
    void sameLeaseWithGovernedDriftIsRejectedWithoutRewritingReceipt() throws Exception {
        Path stateRoot = stateRoot("mismatch");
        AdmissionLifecycleMaterial material = lifecycle(1, null, 1,
                NOW.minusSeconds(60), NOW.plusSeconds(600));
        var authority = authority(stateRoot, material);
        var first = authority.commit(request(material, "lease:one", "1", NOW));
        byte[] before = Files.readAllBytes(stateRoot.resolve(
                FilesystemDeploymentAdmissionAuthority.STATE_FILE));

        var mismatch = authority.commit(request(material, "lease:one", "2", NOW));

        assertThat(mismatch.status()).isEqualTo(ExecutionLeaseCommitStatus.REJECTED);
        assertThat(mismatch.receipt()).isNull();
        assertThat(Files.readAllBytes(stateRoot.resolve(
                FilesystemDeploymentAdmissionAuthority.STATE_FILE))).containsExactly(before);
        assertThat(first.receipt().toString()).doesNotContain(
                "lease:one", first.receipt().fingerprint());
    }

    @Test
    void concurrentExactRequestsProduceOneCommitAndRecoveredDuplicates() throws Exception {
        Path stateRoot = stateRoot("concurrent");
        AdmissionLifecycleMaterial material = lifecycle(1, null, 1,
                NOW.minusSeconds(60), NOW.plusSeconds(600));
        var authority = authority(stateRoot, material);
        ExecutionLeaseRequest request = request(material, LEASE, "1", NOW);

        List<com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider
                .ExecutionLeaseCommitResult> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(ignored -> executor.submit(() -> authority.commit(request)))
                    .toList();
            for (var future : futures) {
                results.add(future.get());
            }
        }

        assertThat(results).filteredOn(result ->
                result.status() == ExecutionLeaseCommitStatus.COMMITTED).hasSize(1);
        assertThat(results).filteredOn(result ->
                result.status() == ExecutionLeaseCommitStatus.RECOVERED).hasSize(7);
        assertThat(results).extracting(result -> result.receipt().fingerprint())
                .containsOnly(results.getFirst().receipt().fingerprint());
    }

    @Test
    void enforcesGenesisPredecessorRollbackAndRevocationCurrentness() throws Exception {
        Path stateRoot = stateRoot("lifecycle");
        AdmissionLifecycleMaterial genesis = lifecycle(1, null, 2,
                NOW.minusSeconds(60), NOW.plusSeconds(600));
        var genesisAuthority = authority(stateRoot, genesis, "lease:genesis",
                Clock.fixed(NOW, ZoneOffset.UTC),
                FilesystemDeploymentAdmissionAuthority.MAX_LEASES);

        assertThat(genesisAuthority.verify(lifecycleRequest(genesis, NOW)).status())
                .isEqualTo(DeploymentDecisionStatus.VERIFIED);
        assertThat(genesisAuthority.commit(request(
                genesis, "lease:genesis", "1", NOW)).status())
                .isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);

        AdmissionLifecycleMaterial wrongPredecessor = lifecycle(
                2, fingerprint('f'), 3, NOW.minusSeconds(30), NOW.plusSeconds(600));
        assertThat(authority(stateRoot, wrongPredecessor)
                .verify(lifecycleRequest(wrongPredecessor, NOW)).status())
                .isEqualTo(DeploymentDecisionStatus.REJECTED);

        AdmissionLifecycleMaterial next = lifecycle(
                2, genesis.bundleFingerprint(), 3,
                NOW.minusSeconds(30), NOW.plusSeconds(600));
        var nextAuthority = authority(stateRoot, next, "lease:next",
                Clock.fixed(NOW, ZoneOffset.UTC),
                FilesystemDeploymentAdmissionAuthority.MAX_LEASES);
        assertThat(advanceHead(stateRoot, next.revocationAuthority()).status())
                .isEqualTo(FilesystemDeploymentAdmissionAuthority
                        .RevocationHeadUpdateStatus.UPDATED);
        assertThat(nextAuthority.commit(request(next, "lease:next", "1", NOW)).status())
                .isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);
        assertThat(genesisAuthority.verify(lifecycleRequest(genesis, NOW)).status())
                .isEqualTo(DeploymentDecisionStatus.REJECTED);

        AdmissionLifecycleMaterial staleRevocation = lifecycle(
                3, next.bundleFingerprint(), 2,
                NOW.minusSeconds(30), NOW.plusSeconds(600));
        assertThat(authority(stateRoot, staleRevocation)
                .verify(lifecycleRequest(staleRevocation, NOW)).status())
                .isEqualTo(DeploymentDecisionStatus.REJECTED);

        AdmissionLifecycleMaterial changedRegistry = new AdmissionLifecycleMaterial(
                fingerprint('3'), "bundle:test", 3, "ACTIVE", next.bundleFingerprint(),
                new RevocationAuthoritySnapshot("registry:other", 4, fingerprint('8'),
                        NOW.minusSeconds(20), NOW.plusSeconds(600)));
        assertThat(authority(stateRoot, changedRegistry)
                .verify(lifecycleRequest(changedRegistry, NOW)).status())
                .isEqualTo(DeploymentDecisionStatus.REJECTED);

        AdmissionLifecycleMaterial expired = lifecycle(
                3, next.bundleFingerprint(), 4,
                NOW.minusSeconds(600), NOW.minusSeconds(1));
        assertThat(authority(stateRoot("expired"), expired)
                .verify(lifecycleRequest(expired, NOW)).status())
                .isEqualTo(DeploymentDecisionStatus.REJECTED);
    }

    @Test
    void corruptedDuplicateAndOversizedStateFailUnavailable() throws Exception {
        AdmissionLifecycleMaterial material = lifecycle(1, null, 1,
                NOW.minusSeconds(60), NOW.plusSeconds(600));
        for (String document : List.of(
                "{\"messageVersion\":\"x\",\"messageVersion\":\"y\"}",
                "{\"unexpected\":true}")) {
            Path stateRoot = stateRoot("corrupt-" + Math.abs(document.hashCode()));
            var authority = authority(stateRoot, material);
            writePrivate(stateRoot.resolve(FilesystemDeploymentAdmissionAuthority.STATE_FILE),
                    document.getBytes(StandardCharsets.UTF_8));
            assertThat(authority.commit(request(
                    material, LEASE, "1", NOW)).status())
                    .isEqualTo(ExecutionLeaseCommitStatus.UNAVAILABLE);
        }

        Path oversizedRoot = stateRoot("oversized");
        var authority = authority(oversizedRoot, material);
        writePrivate(oversizedRoot.resolve(FilesystemDeploymentAdmissionAuthority.STATE_FILE),
                new byte[FilesystemDeploymentAdmissionAuthority.MAX_STATE_BYTES + 1]);
        assertThat(authority.commit(request(
                material, LEASE, "1", NOW)).status())
                .isEqualTo(ExecutionLeaseCommitStatus.UNAVAILABLE);

        Path inconsistentRoot = stateRoot("inconsistent-head");
        var inconsistentAuthority = authority(inconsistentRoot, material);
        assertThat(inconsistentAuthority.commit(request(
                material, LEASE, "1", NOW)).status())
                .isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);
        Path stateFile = inconsistentRoot.resolve(
                FilesystemDeploymentAdmissionAuthority.STATE_FILE);
        var state = (com.fasterxml.jackson.databind.node.ObjectNode)
                MountedProviderTestFixtures.JSON.readTree(stateFile.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.path("lifecycleHead"))
                .put("bundleFingerprint", fingerprint('9'));
        writePrivate(stateFile, MountedProviderTestFixtures.JSON.writeValueAsBytes(state));
        assertThat(inconsistentAuthority.commit(request(
                material, LEASE, "1", NOW.plusSeconds(1))).status())
                .isEqualTo(ExecutionLeaseCommitStatus.UNAVAILABLE);
    }

    @Test
    void closureAggregateLimitIsMetadataFirstAndOverflowSafe() throws Exception {
        AdmissionLifecycleMaterial material = lifecycle(1, null, 1,
                NOW.minusSeconds(60), NOW.plusSeconds(600));
        Path exact = stateRoot("closure-exact");
        authority(exact, material);
        fillClosureTo(exact, FilesystemDeploymentAdmissionAuthority.MAX_STORE_CLOSURE_BYTES);
        AtomicInteger exactReads = new AtomicInteger();

        FilesystemDeploymentAdmissionAuthority.validateStoreClosureForTesting(
                exact, uid(exact), FilesystemDeploymentAdmissionAuthorityTest::metadata,
                (path, maximumBytes) -> {
                    exactReads.incrementAndGet();
                    return Files.readAllBytes(path);
                });

        assertThat(closureByteSize(exact))
                .isEqualTo(FilesystemDeploymentAdmissionAuthority.MAX_STORE_CLOSURE_BYTES);
        assertThat(exactReads).hasValueGreaterThan(4);

        Path over = stateRoot("closure-over");
        authority(over, material);
        fillClosureTo(over,
                FilesystemDeploymentAdmissionAuthority.MAX_STORE_CLOSURE_BYTES + 1);
        AtomicInteger overReads = new AtomicInteger();
        List<String> before = storeInventory(over);

        assertThatThrownBy(() -> FilesystemDeploymentAdmissionAuthority
                .validateStoreClosureForTesting(over, uid(over),
                        FilesystemDeploymentAdmissionAuthorityTest::metadata,
                        (path, maximumBytes) -> {
                            overReads.incrementAndGet();
                            return Files.readAllBytes(path);
                        }))
                .isInstanceOf(java.io.IOException.class);
        assertThat(overReads).hasValue(0);
        assertThat(storeInventory(over)).isEqualTo(before);

        assertThat(FilesystemDeploymentAdmissionAuthority
                .checkedAggregateBytesForTesting(0,
                        FilesystemDeploymentAdmissionAuthority.MAX_STORE_CLOSURE_BYTES))
                .isEqualTo(FilesystemDeploymentAdmissionAuthority.MAX_STORE_CLOSURE_BYTES);
        assertThatThrownBy(() -> FilesystemDeploymentAdmissionAuthority
                .checkedAggregateBytesForTesting(Long.MAX_VALUE, 1))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void closureRejectsReadSizeRaceAndOversizedTransitionWithoutStoreWrites()
            throws Exception {
        AdmissionLifecycleMaterial material = lifecycle(1, null, 1,
                NOW.minusSeconds(60), NOW.plusSeconds(600));
        Path raced = stateRoot("closure-size-race");
        authority(raced, material);
        Path state = raced.resolve(FilesystemDeploymentAdmissionAuthority.STATE_FILE);
        List<String> before = storeInventory(raced);
        AtomicBoolean changed = new AtomicBoolean();

        assertThatThrownBy(() -> FilesystemDeploymentAdmissionAuthority
                .validateStoreClosureForTesting(raced, uid(raced),
                        FilesystemDeploymentAdmissionAuthorityTest::metadata,
                        (path, maximumBytes) -> {
                            byte[] bytes = Files.readAllBytes(path);
                            if (path.equals(state) && changed.compareAndSet(false, true)) {
                                Files.write(path, new byte[]{0}, StandardOpenOption.APPEND);
                            }
                            return bytes;
                        }))
                .isInstanceOf(java.io.IOException.class);
        assertThat(changed).isTrue();
        List<String> after = storeInventory(raced);
        assertThat(after.stream().filter(value -> !value.startsWith(
                FilesystemDeploymentAdmissionAuthority.STATE_FILE + "|")))
                .containsExactlyElementsOf(before.stream().filter(value -> !value.startsWith(
                        FilesystemDeploymentAdmissionAuthority.STATE_FILE + "|")).toList());
        assertThat(raced.resolve(FilesystemDeploymentAdmissionAuthority.TEMP_FILE))
                .doesNotExist();

        Path oversized = stateRoot("oversized-transition");
        authority(oversized, material);
        createSparseTransition(oversized, 1,
                FilesystemDeploymentAdmissionAuthority.MAX_TRANSITION_EVIDENCE_BYTES + 1L);
        AtomicInteger reads = new AtomicInteger();
        List<String> oversizedBefore = storeInventory(oversized);
        assertThatThrownBy(() -> FilesystemDeploymentAdmissionAuthority
                .validateStoreClosureForTesting(oversized, uid(oversized),
                        FilesystemDeploymentAdmissionAuthorityTest::metadata,
                        (path, maximumBytes) -> {
                            reads.incrementAndGet();
                            return Files.readAllBytes(path);
                        }))
                .isInstanceOf(java.io.IOException.class);
        assertThat(reads).hasValue(0);
        assertThat(storeInventory(oversized)).isEqualTo(oversizedBefore);
    }

    @Test
    void transitionCountAndPendingRecoveryMaterialRemainBoundedBeforeReads()
            throws Exception {
        AdmissionLifecycleMaterial material = lifecycle(1, null, 1,
                NOW.minusSeconds(60), NOW.plusSeconds(600));
        Path countRoot = stateRoot("transition-count");
        authority(countRoot, material);
        for (int generation = 1;
                generation <= FilesystemDeploymentAdmissionAuthority.MAX_LEASES;
                generation++) {
            createSparseTransition(countRoot, generation, 1);
        }
        AtomicInteger exactCountReads = new AtomicInteger();
        assertThatThrownBy(() -> FilesystemDeploymentAdmissionAuthority
                .validateStoreClosureForTesting(countRoot, uid(countRoot),
                        FilesystemDeploymentAdmissionAuthorityTest::metadata,
                        (path, maximumBytes) -> {
                            exactCountReads.incrementAndGet();
                            throw new java.io.IOException("TEST_STOP");
                        }))
                .isInstanceOf(java.io.IOException.class);
        assertThat(exactCountReads).hasValue(1);

        createSparseTransition(countRoot,
                FilesystemDeploymentAdmissionAuthority.MAX_LEASES + 1L, 1);
        AtomicInteger overflowCountReads = new AtomicInteger();
        List<String> countBefore = storeInventory(countRoot);
        assertThatThrownBy(() -> FilesystemDeploymentAdmissionAuthority
                .validateStoreClosureForTesting(countRoot, uid(countRoot),
                        FilesystemDeploymentAdmissionAuthorityTest::metadata,
                        (path, maximumBytes) -> {
                            overflowCountReads.incrementAndGet();
                            return Files.readAllBytes(path);
                        }))
                .isInstanceOf(RuntimeException.class);
        assertThat(overflowCountReads).hasValue(0);
        assertThat(storeInventory(countRoot)).isEqualTo(countBefore);

        Path pendingRoot = stateRoot("pending-aggregate");
        authority(pendingRoot, material);
        fillClosureTo(pendingRoot,
                FilesystemDeploymentAdmissionAuthority.MAX_STORE_CLOSURE_BYTES);
        createSparsePendingTransition(pendingRoot, 999, 1);
        AtomicInteger pendingReads = new AtomicInteger();
        List<String> pendingBefore = storeInventory(pendingRoot);
        assertThatThrownBy(() -> FilesystemDeploymentAdmissionAuthority
                .validateRecoverableStoreClosureForTesting(pendingRoot, uid(pendingRoot),
                        FilesystemDeploymentAdmissionAuthorityTest::metadata,
                        (path, maximumBytes) -> {
                            pendingReads.incrementAndGet();
                            return Files.readAllBytes(path);
                        }))
                .isInstanceOf(java.io.IOException.class);
        assertThat(pendingReads).hasValue(0);
        assertThat(storeInventory(pendingRoot)).isEqualTo(pendingBefore);
    }

    @Test
    void protocolCapacityAt1024IsUnavailableAndRetainsCommittedHistory()
            throws Exception {
        assertThat(FilesystemDeploymentAdmissionAuthority.capacityExhaustedForTesting(
                FilesystemDeploymentAdmissionAuthority.MAX_LEASES - 1, 1023, 1023))
                .isFalse();
        assertThat(FilesystemDeploymentAdmissionAuthority.capacityExhaustedForTesting(
                FilesystemDeploymentAdmissionAuthority.MAX_LEASES, 1024, 1024))
                .isTrue();

        AdmissionLifecycleMaterial material = lifecycle(1, null, 1,
                NOW.minusSeconds(60), NOW.plusSeconds(600));
        Path root = stateRoot("capacity-history");
        var first = authority(root, material, "lease:first",
                Clock.fixed(NOW, ZoneOffset.UTC), 1);
        assertThat(first.commit(request(material, "lease:first", "1", NOW)).status())
                .isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);
        List<String> before = storeInventory(root);
        var full = authority(root, material, "lease:second",
                Clock.fixed(NOW, ZoneOffset.UTC), 1);

        var result = full.commit(request(material, "lease:second", "1", NOW));

        assertThat(result.status()).isEqualTo(ExecutionLeaseCommitStatus.UNAVAILABLE);
        assertThat(result.reasonCode()).isEqualTo(
                FilesystemDeploymentAdmissionAuthority.CAPACITY_UNAVAILABLE);
        assertThat(storeInventory(root)).isEqualTo(before);
        assertThat(Files.readString(root.resolve(
                FilesystemDeploymentAdmissionAuthority.STATE_FILE)))
                .contains("\"fencingSequence\":1");
    }

    @Test
    void symlinkHardLinkAndStaleTempAreRejectedWithoutFollowing() throws Exception {
        AdmissionLifecycleMaterial material = lifecycle(1, null, 1,
                NOW.minusSeconds(60), NOW.plusSeconds(600));
        Path symlinkRoot = stateRoot("symlink");
        var symlinkAuthority = authority(symlinkRoot, material);
        Path outside = temporaryDirectory.resolve("outside-state.json");
        writePrivate(outside, "{}".getBytes(StandardCharsets.UTF_8));
        Files.delete(symlinkRoot.resolve(
                FilesystemDeploymentAdmissionAuthority.STATE_FILE));
        Files.createSymbolicLink(symlinkRoot.resolve(
                FilesystemDeploymentAdmissionAuthority.STATE_FILE), outside);
        assertThat(symlinkAuthority.commit(request(
                material, LEASE, "1", NOW)).status())
                .isEqualTo(ExecutionLeaseCommitStatus.UNAVAILABLE);
        assertThat(Files.readString(outside)).isEqualTo("{}");

        Path hardLinkRoot = stateRoot("hard-link");
        var hardLinkAuthority = authority(hardLinkRoot, material);
        Path hardLinkSource = temporaryDirectory.resolve("hard-link-source.json");
        writePrivate(hardLinkSource, "{}".getBytes(StandardCharsets.UTF_8));
        try {
            Files.delete(hardLinkRoot.resolve(
                    FilesystemDeploymentAdmissionAuthority.STATE_FILE));
            Files.createLink(hardLinkRoot.resolve(
                    FilesystemDeploymentAdmissionAuthority.STATE_FILE), hardLinkSource);
            assertThat(hardLinkAuthority.commit(request(
                    material, LEASE, "1", NOW)).status())
                    .isEqualTo(ExecutionLeaseCommitStatus.UNAVAILABLE);
        } catch (UnsupportedOperationException unsupported) {
            assertThat(unsupported).isNotNull();
        }

        Path tempRoot = stateRoot("stale-temp");
        var tempAuthority = authority(tempRoot, material);
        Files.createDirectory(tempRoot.resolve(FilesystemDeploymentAdmissionAuthority.TEMP_FILE));
        assertThat(tempAuthority.commit(request(
                material, LEASE, "1", NOW)).status())
                .isEqualTo(ExecutionLeaseCommitStatus.UNAVAILABLE);
    }

    @Test
    void heldInterprocessLockIsUnavailableAndDoesNotMutateState() throws Exception {
        Path stateRoot = stateRoot("lock");
        AdmissionLifecycleMaterial material = lifecycle(1, null, 1,
                NOW.minusSeconds(60), NOW.plusSeconds(600));
        var authority = authority(stateRoot, material);
        Path lockPath = stateRoot.resolve(FilesystemDeploymentAdmissionAuthority.LOCK_FILE);
        byte[] before = Files.readAllBytes(stateRoot.resolve(
                FilesystemDeploymentAdmissionAuthority.STATE_FILE));

        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            assertThat(authority.commit(request(
                    material, LEASE, "1", NOW)).status())
                    .isEqualTo(ExecutionLeaseCommitStatus.UNAVAILABLE);
        }
        assertThat(Files.readAllBytes(stateRoot.resolve(
                FilesystemDeploymentAdmissionAuthority.STATE_FILE))).containsExactly(before);
    }

    @Test
    void removesSafeCrashTempAndPersistsLeasesInDeterministicKeyOrder() throws Exception {
        Path stateRoot = stateRoot("deterministic");
        AdmissionLifecycleMaterial material = lifecycle(1, null, 1,
                NOW.minusSeconds(60), NOW.plusSeconds(600));
        var authority = authority(stateRoot, material);
        writePrivate(stateRoot.resolve(FilesystemDeploymentAdmissionAuthority.TEMP_FILE),
                "partial-crash-write".getBytes(StandardCharsets.UTF_8));

        for (String lease : List.of("lease:z", "lease:a", "lease:m")) {
            var leaseAuthority = authority(stateRoot, material, lease,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    FilesystemDeploymentAdmissionAuthority.MAX_LEASES);
            assertThat(leaseAuthority.commit(request(material, lease, "1", NOW)).status())
                    .isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);
        }

        var state = MountedProviderTestFixtures.JSON.readTree(stateRoot.resolve(
                FilesystemDeploymentAdmissionAuthority.STATE_FILE).toFile());
        List<String> keys = new ArrayList<>();
        state.path("leases").forEach(value -> keys.add(value.path("leaseKey").textValue()));
        assertThat(keys).isSorted();
        assertThat(state.path("fencingSequence").longValue()).isEqualTo(3);
        assertThat(stateRoot.resolve(FilesystemDeploymentAdmissionAuthority.TEMP_FILE))
                .doesNotExist();
    }

    @Test
    void stateWritePermissionOutageReturnsUnavailableWithoutPartialCommit() throws Exception {
        Path stateRoot = stateRoot("write-outage");
        AdmissionLifecycleMaterial material = lifecycle(1, null, 1,
                NOW.minusSeconds(60), NOW.plusSeconds(600));
        var authority = authority(stateRoot, material);
        byte[] before = Files.readAllBytes(stateRoot.resolve(
                FilesystemDeploymentAdmissionAuthority.STATE_FILE));
        if (!Files.getFileStore(stateRoot).supportsFileAttributeView("posix")) {
            assertThat(authority.toString()).contains("state=REDACTED");
            return;
        }

        Files.setPosixFilePermissions(stateRoot, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        try {
            assertThat(authority.commit(request(
                    material, LEASE, "1", NOW)).status())
                    .isEqualTo(ExecutionLeaseCommitStatus.UNAVAILABLE);
            assertThat(Files.readAllBytes(stateRoot.resolve(
                    FilesystemDeploymentAdmissionAuthority.STATE_FILE)))
                    .containsExactly(before);
        } finally {
            Files.setPosixFilePermissions(stateRoot, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
    }

    @Test
    void rejectsMissingSymlinkAndNonPrivateStateRootsWithStableRedactedCode()
            throws Exception {
        Path missing = temporaryDirectory.resolve("missing").toAbsolutePath();
        assertThatThrownBy(() -> FilesystemDeploymentAdmissionAuthority
                .requireStateRoot(missing))
                .isExactlyInstanceOf(com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceAuthorityProvider
                        .DeploymentUnavailableException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.DEPLOYMENT_UNAVAILABLE")
                .hasMessageNotContaining(missing.toString());

        Path actual = stateRoot("actual");
        Path link = temporaryDirectory.resolve("state-link");
        Files.createSymbolicLink(link, actual);
        assertThatThrownBy(() -> FilesystemDeploymentAdmissionAuthority.requireStateRoot(link))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .EXECUTION_LEASE_STATE_ROOT_INVALID_CODE);

        if (Files.getFileStore(actual).supportsFileAttributeView("posix")) {
            Path open = stateRoot("open");
            Files.setPosixFilePermissions(open, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ));
            assertThatThrownBy(() -> FilesystemDeploymentAdmissionAuthority
                    .requireStateRoot(open))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                            .EXECUTION_LEASE_STATE_ROOT_INVALID_CODE);
        }
    }

    @Test
    void deletionAndStateOnlyRollbackCannotReopenCommittedLeases() throws Exception {
        AdmissionLifecycleMaterial material = lifecycle(1, null, 1,
                NOW.minusSeconds(60), NOW.plusSeconds(600));

        Path deletionRoot = stateRoot("deleted-state");
        var deletionAuthority = authority(deletionRoot, material);
        assertThat(deletionAuthority.commit(request(
                material, LEASE, "1", NOW)).status())
                .isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);
        Files.delete(deletionRoot.resolve(FilesystemDeploymentAdmissionAuthority.STATE_FILE));
        Files.delete(deletionRoot.resolve(
                FilesystemDeploymentAdmissionAuthority.CHECKPOINT_FILE));
        assertThat(deletionAuthority.commit(request(
                material, LEASE, "1", NOW)).status())
                .isEqualTo(ExecutionLeaseCommitStatus.UNAVAILABLE);
        assertThatThrownBy(() -> FilesystemDeploymentAdmissionAuthority.prepareStore(
                deletionRoot, fingerprint('e'), material.revocationAuthority()))
                .isExactlyInstanceOf(com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceAuthorityProvider
                        .DeploymentUnavailableException.class);

        Path rollbackRoot = stateRoot("stale-state");
        var rollbackAuthority = authority(rollbackRoot, material);
        Path state = rollbackRoot.resolve(FilesystemDeploymentAdmissionAuthority.STATE_FILE);
        byte[] genesis = Files.readAllBytes(state);
        assertThat(rollbackAuthority.commit(request(
                material, LEASE, "1", NOW)).status())
                .isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);
        writePrivate(state, genesis);
        assertThat(rollbackAuthority.commit(request(
                material, LEASE, "1", NOW)).status())
                .isEqualTo(ExecutionLeaseCommitStatus.UNAVAILABLE);
    }

    @Test
    void exactStateSuccessorRepairsOneCheckpointPredecessor() throws Exception {
        Path stateRoot = stateRoot("checkpoint-repair");
        AdmissionLifecycleMaterial material = lifecycle(1, null, 1,
                NOW.minusSeconds(60), NOW.plusSeconds(600));
        var authority = authority(stateRoot, material);
        Path checkpoint = stateRoot.resolve(
                FilesystemDeploymentAdmissionAuthority.CHECKPOINT_FILE);
        byte[] genesisCheckpoint = Files.readAllBytes(checkpoint);
        ExecutionLeaseRequest request = request(material, LEASE, "1", NOW);
        assertThat(authority.commit(request).status())
                .isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);

        writePrivate(checkpoint, genesisCheckpoint);
        var restarted = authority(stateRoot, material);

        assertThat(restarted.commit(request).status())
                .isEqualTo(ExecutionLeaseCommitStatus.RECOVERED);
        assertThat(MountedProviderTestFixtures.JSON.readTree(checkpoint.toFile())
                .path("generation").longValue()).isEqualTo(1);
    }

    @Test
    void finalCommitSamplesClockAndHistoricalRecoveryPrecedesNewAdmissionRules()
            throws Exception {
        AdmissionLifecycleMaterial material = lifecycle(1, null, 1,
                NOW.minusSeconds(60), NOW.plusSeconds(10));

        Path expiredRoot = stateRoot("commit-expired");
        var expired = authority(expiredRoot, material,
                Clock.fixed(NOW.plusSeconds(11), ZoneOffset.UTC),
                FilesystemDeploymentAdmissionAuthority.MAX_LEASES);
        assertThat(expired.commit(request(
                material, LEASE, "1", NOW)).status())
                .isEqualTo(ExecutionLeaseCommitStatus.REJECTED);

        Path backwardsRoot = stateRoot("commit-backwards");
        var backwards = authority(backwardsRoot, material,
                Clock.fixed(NOW.minusSeconds(1), ZoneOffset.UTC),
                FilesystemDeploymentAdmissionAuthority.MAX_LEASES);
        assertThat(backwards.commit(request(
                material, LEASE, "1", NOW)).status())
                .isEqualTo(ExecutionLeaseCommitStatus.REJECTED);

        Path recoveryRoot = stateRoot("historical-recovery");
        var firstAuthority = authority(recoveryRoot, material, "lease:historical",
                Clock.fixed(NOW, ZoneOffset.UTC),
                FilesystemDeploymentAdmissionAuthority.MAX_LEASES);
        ExecutionLeaseRequest firstRequest = request(
                material, "lease:historical", "1", NOW);
        var first = firstAuthority.commit(firstRequest);
        AdmissionLifecycleMaterial next = lifecycle(2, material.bundleFingerprint(), 2,
                NOW.minusSeconds(30), NOW.plusSeconds(600));
        var nextAuthority = authority(recoveryRoot, next, "lease:next-head",
                Clock.fixed(NOW, ZoneOffset.UTC),
                FilesystemDeploymentAdmissionAuthority.MAX_LEASES);
        assertThat(advanceHead(recoveryRoot, next.revocationAuthority()).status())
                .isEqualTo(FilesystemDeploymentAdmissionAuthority
                        .RevocationHeadUpdateStatus.UPDATED);
        assertThat(nextAuthority.commit(request(
                next, "lease:next-head", "1", NOW)).status())
                .isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);
        assertThat(firstAuthority.commit(firstRequest).status())
                .isEqualTo(ExecutionLeaseCommitStatus.RECOVERED);
        assertThat(firstAuthority.commit(firstRequest).receipt()).isEqualTo(first.receipt());
    }

    @Test
    void clockOutageCapacityAndRootDisappearanceAreUnavailable() throws Exception {
        AdmissionLifecycleMaterial material = lifecycle(1, null, 1,
                NOW.minusSeconds(60), NOW.plusSeconds(600));
        Clock failedClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                throw new IllegalStateException("CLOCK_PAYLOAD");
            }
        };
        Path clockRoot = stateRoot("clock-outage");
        assertThat(authority(clockRoot, material, failedClock,
                FilesystemDeploymentAdmissionAuthority.MAX_LEASES).commit(
                request(material, LEASE, "1", NOW)).status())
                .isEqualTo(ExecutionLeaseCommitStatus.UNAVAILABLE);

        Path capacityRoot = stateRoot("capacity");
        var capacity = authority(capacityRoot, material, "lease:first",
                Clock.fixed(NOW, ZoneOffset.UTC), 1);
        assertThat(capacity.commit(request(
                material, "lease:first", "1", NOW)).status())
                .isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);
        var exhausted = authority(capacityRoot, material, "lease:second",
                Clock.fixed(NOW, ZoneOffset.UTC), 1);
        assertThat(exhausted.commit(request(
                material, "lease:second", "1", NOW)).status())
                .isEqualTo(ExecutionLeaseCommitStatus.UNAVAILABLE);

        Path vanishedRoot = stateRoot("vanished");
        var vanished = authority(vanishedRoot, material);
        deleteDirectory(vanishedRoot);
        assertThat(vanished.commit(request(
                material, LEASE, "1", NOW)).status())
                .isEqualTo(ExecutionLeaseCommitStatus.UNAVAILABLE);
    }

    @Test
    void failedDirectoryForceMakesStoreInitializationUnavailable() throws Exception {
        Path root = stateRoot("force-outage");
        AdmissionLifecycleMaterial material = lifecycle(1, null, 1,
                NOW.minusSeconds(60), NOW.plusSeconds(600));
        var durability = new FilesystemDeploymentAdmissionAuthority.Durability() {
            @Override
            public void atomicReplace(Path source, Path target) throws java.io.IOException {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            }

            @Override
            public void forceDirectory(Path directory, Path installedEntry)
                    throws java.io.IOException {
                throw new java.io.IOException("FSYNC_PAYLOAD");
            }
        };

        assertThatThrownBy(() -> FilesystemDeploymentAdmissionAuthority.prepareStore(
                root, fingerprint('e'), material.revocationAuthority(), durability))
                .isExactlyInstanceOf(com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceAuthorityProvider
                        .DeploymentUnavailableException.class)
                .hasMessageNotContaining("FSYNC_PAYLOAD");
    }

    @Test
    void wrongOuterAndLeaseAreRejectedBeforeConsumingCapacity() throws Exception {
        Path root = stateRoot("callback-identity");
        AdmissionLifecycleMaterial material = lifecycle(1, null, 1,
                NOW.minusSeconds(60), NOW.plusSeconds(600));
        var authority = authority(root, material, LEASE,
                Clock.fixed(NOW, ZoneOffset.UTC), 1);

        var wrongLifecycle = new AdmissionLifecycleRequest(material, fingerprint('9'),
                RAW, CANONICAL, DEPLOYMENT, NOW);
        assertThat(authority.verify(wrongLifecycle).status())
                .isEqualTo(DeploymentDecisionStatus.REJECTED);
        assertThat(authority.commit(request(
                material, LEASE, "1", NOW, fingerprint('9'))).status())
                .isEqualTo(ExecutionLeaseCommitStatus.REJECTED);
        assertThat(authority.commit(request(
                material, "lease:wrong", "1", NOW)).status())
                .isEqualTo(ExecutionLeaseCommitStatus.REJECTED);

        assertThat(authority.commit(request(material, LEASE, "1", NOW)).status())
                .isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);
    }

    @Test
    void checkpointForceFailureRequiresSuccessfulRetryBarrierBeforeRecovery()
            throws Exception {
        Path root = stateRoot("checkpoint-force-retry");
        AdmissionLifecycleMaterial material = lifecycle(1, null, 1,
                NOW.minusSeconds(60), NOW.plusSeconds(600));
        var durability = new CheckpointFaultDurability();
        var store = FilesystemDeploymentAdmissionAuthority.prepareStore(
                root, fingerprint('e'), material.revocationAuthority(), durability);
        var authority = new FilesystemDeploymentAdmissionAuthority(store, material,
                RAW, CANONICAL, DEPLOYMENT, OUTER, LEASE,
                Clock.fixed(NOW, ZoneOffset.UTC));
        ExecutionLeaseRequest request = request(material, LEASE, "1", NOW);

        durability.failNextCheckpointForce = true;
        var first = authority.commit(request);
        var blockedRetry = authority.commit(request);
        durability.readBarrierAvailable = true;
        var recovered = authority.commit(request);

        assertThat(first.status()).isEqualTo(ExecutionLeaseCommitStatus.UNAVAILABLE);
        assertThat(first.receipt()).isNull();
        assertThat(blockedRetry.status()).isEqualTo(ExecutionLeaseCommitStatus.UNAVAILABLE);
        assertThat(blockedRetry.receipt()).isNull();
        assertThat(recovered.status()).isEqualTo(ExecutionLeaseCommitStatus.RECOVERED);
        assertThat(recovered.receipt()).isNotNull();
        assertThat(durability.successfulReadBarriers).isPositive();
    }

    @Test
    void revocationHeadDriftRejectsNewCommitButExactReceiptStillRecovers()
            throws Exception {
        Path root = stateRoot("revocation-head");
        AdmissionLifecycleMaterial material = lifecycle(1, null, 1,
                NOW.minusSeconds(60), NOW.plusSeconds(600));
        var firstAuthority = authority(root, material, LEASE,
                Clock.fixed(NOW, ZoneOffset.UTC),
                FilesystemDeploymentAdmissionAuthority.MAX_LEASES);
        Path revocationHeadFile = root.resolve(
                FilesystemDeploymentAdmissionAuthority.REVOCATION_HEAD_FILE);
        byte[] initialHead = Files.readAllBytes(revocationHeadFile);
        ExecutionLeaseRequest firstRequest = request(material, LEASE, "1", NOW);
        var committed = firstAuthority.commit(firstRequest);
        RevocationAuthoritySnapshot advanced = new RevocationAuthoritySnapshot(
                material.revocationAuthority().registryRef(), 2, fingerprint('9'),
                NOW.minusSeconds(30), NOW.plusSeconds(600));

        assertThat(advanceHead(root, advanced).status())
                .isEqualTo(FilesystemDeploymentAdmissionAuthority
                        .RevocationHeadUpdateStatus.UPDATED);
        byte[] stateAfterAdvance = Files.readAllBytes(root.resolve(
                FilesystemDeploymentAdmissionAuthority.STATE_FILE));
        byte[] checkpointAfterAdvance = Files.readAllBytes(root.resolve(
                FilesystemDeploymentAdmissionAuthority.CHECKPOINT_FILE));
        byte[] headAfterAdvance = Files.readAllBytes(revocationHeadFile);
        assertThat(firstAuthority.verify(lifecycleRequest(material, NOW)).status())
                .isEqualTo(DeploymentDecisionStatus.REJECTED);
        assertThat(Files.readAllBytes(root.resolve(
                FilesystemDeploymentAdmissionAuthority.STATE_FILE)))
                .containsExactly(stateAfterAdvance);
        assertThat(Files.readAllBytes(root.resolve(
                FilesystemDeploymentAdmissionAuthority.CHECKPOINT_FILE)))
                .containsExactly(checkpointAfterAdvance);
        assertThat(Files.readAllBytes(revocationHeadFile)).containsExactly(headAfterAdvance);
        var newAuthority = authority(root, material, "lease:new",
                Clock.fixed(NOW, ZoneOffset.UTC),
                FilesystemDeploymentAdmissionAuthority.MAX_LEASES);

        assertThat(newAuthority.commit(request(
                material, "lease:new", "1", NOW)).status())
                .isEqualTo(ExecutionLeaseCommitStatus.REJECTED);
        assertThat(firstAuthority.commit(firstRequest).status())
                .isEqualTo(ExecutionLeaseCommitStatus.RECOVERED);
        assertThat(firstAuthority.commit(firstRequest).receipt())
                .isEqualTo(committed.receipt());

        writePrivate(revocationHeadFile, initialHead);
        assertThat(firstAuthority.commit(firstRequest).status())
                .isEqualTo(ExecutionLeaseCommitStatus.UNAVAILABLE);
        Files.delete(revocationHeadFile);
        assertThat(firstAuthority.commit(firstRequest).status())
                .isEqualTo(ExecutionLeaseCommitStatus.UNAVAILABLE);
    }

    @Test
    void unsupportedIdentityMetadataIsInvalidButMissingRootIsUnavailable()
            throws Exception {
        AdmissionLifecycleMaterial material = lifecycle(1, null, 1,
                NOW.minusSeconds(60), NOW.plusSeconds(600));
        Path nullKeyRoot = stateRoot("null-file-key");
        var nullKey = new NativeMetadata() {
            @Override
            public BasicFileAttributes readAttributes(Path path) throws java.io.IOException {
                return new NullFileKeyAttributes(super.readAttributes(path));
            }
        };
        assertThatThrownBy(() -> FilesystemDeploymentAdmissionAuthority.prepareStore(
                nullKeyRoot, fingerprint('e'), material.revocationAuthority(), nullKey))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .EXECUTION_LEASE_STATE_ROOT_INVALID_CODE);

        Path noLinkMetadataRoot = stateRoot("no-link-metadata");
        var noLinks = new NativeMetadata() {
            @Override
            public long hardLinkCount(Path path) {
                throw new UnsupportedOperationException("NLINK_PAYLOAD");
            }
        };
        assertThatThrownBy(() -> FilesystemDeploymentAdmissionAuthority.prepareStore(
                noLinkMetadataRoot, fingerprint('e'), material.revocationAuthority(), noLinks))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .EXECUTION_LEASE_STATE_ROOT_INVALID_CODE)
                .hasMessageNotContaining("NLINK_PAYLOAD");

        Path missing = temporaryDirectory.resolve("metadata-missing").toAbsolutePath();
        var unavailable = new NativeMetadata() {
            @Override
            public BasicFileAttributes readAttributes(Path path)
                    throws java.io.IOException {
                throw new NoSuchFileException(path.toString());
            }
        };
        assertThatThrownBy(() -> FilesystemDeploymentAdmissionAuthority.prepareStore(
                missing, fingerprint('e'), material.revocationAuthority(), unavailable))
                .isExactlyInstanceOf(com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceAuthorityProvider
                        .DeploymentUnavailableException.class)
                .hasMessageNotContaining(missing.toString());

        Path existing = stateRoot("existing-unsupported-metadata");
        FilesystemDeploymentAdmissionAuthority.prepareEvidenceStore(
                existing, fingerprint('e'), material.revocationAuthority());
        var runtimeUnsupported = new NativeMetadata() {
            @Override
            public long hardLinkCount(Path path) {
                throw new UnsupportedOperationException("UPPERCASE_METADATA_PAYLOAD");
            }
        };
        assertThatThrownBy(() -> FilesystemDeploymentAdmissionAuthority
                .openExistingEvidenceRecoveryStore(existing, runtimeUnsupported))
                .isExactlyInstanceOf(com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceAuthorityProvider
                        .DeploymentUnavailableException.class)
                .hasMessageNotContaining("UPPERCASE_METADATA_PAYLOAD")
                .hasMessageNotContaining(existing.toString());
    }

    private FilesystemDeploymentAdmissionAuthority authority(
            Path root, AdmissionLifecycleMaterial material) {
        return authority(root, material, LEASE, Clock.fixed(NOW, ZoneOffset.UTC),
                FilesystemDeploymentAdmissionAuthority.MAX_LEASES);
    }

    private FilesystemDeploymentAdmissionAuthority authority(
            Path root,
            AdmissionLifecycleMaterial material,
            Clock clock,
            int maxLeases) {
        return authority(root, material, LEASE, clock, maxLeases);
    }

    private FilesystemDeploymentAdmissionAuthority authority(
            Path root,
            AdmissionLifecycleMaterial material,
            String executionLeaseId,
            Clock clock,
            int maxLeases) {
        var store = FilesystemDeploymentAdmissionAuthority.prepareStore(
                root, fingerprint('e'), material.revocationAuthority());
        return new FilesystemDeploymentAdmissionAuthority(
                store, material, RAW, CANONICAL, DEPLOYMENT,
                OUTER, executionLeaseId, clock, maxLeases);
    }

    private FilesystemDeploymentAdmissionAuthority.RevocationHeadUpdateResult advanceHead(
            Path root, RevocationAuthoritySnapshot material) throws Exception {
        var store = FilesystemDeploymentAdmissionAuthority.prepareStore(
                root, fingerprint('e'), material);
        String predecessor = MountedProviderTestFixtures.JSON.readTree(root.resolve(
                FilesystemDeploymentAdmissionAuthority.REVOCATION_HEAD_FILE).toFile())
                .path("headFingerprint").textValue();
        String headFingerprint = FilesystemDeploymentAdmissionAuthority.RevocationHeadUpdate
                .fingerprint(store.descriptorFingerprint(), material, predecessor);
        return store.advanceRevocationHead(
                new FilesystemDeploymentAdmissionAuthority.RevocationHeadUpdate(
                        store.descriptorFingerprint(), material, predecessor,
                        headFingerprint), NOW);
    }

    private AdmissionLifecycleRequest lifecycleRequest(
            AdmissionLifecycleMaterial material, Instant time) {
        return new AdmissionLifecycleRequest(material, fingerprint('d'), RAW, CANONICAL,
                DEPLOYMENT, time);
    }

    private ExecutionLeaseRequest request(
            AdmissionLifecycleMaterial material,
            String lease,
            String contractRevision,
            Instant time) {
        return request(material, lease, contractRevision, time, OUTER);
    }

    private ExecutionLeaseRequest request(
            AdmissionLifecycleMaterial material,
            String lease,
            String contractRevision,
            Instant time,
            String providerOuterFingerprint) {
        return new ExecutionLeaseRequest("result:test", 1, fingerprint('1'), fingerprint('2'),
                "contract:test", contractRevision, lease, providerOuterFingerprint,
                RAW, CANONICAL,
                material, DEPLOYMENT, time);
    }

    private AdmissionLifecycleMaterial lifecycle(
            long revision,
            String predecessor,
            long revocationRevision,
            Instant observedAt,
            Instant expiresAt) {
        char seed = (char) ('0' + revision);
        return new AdmissionLifecycleMaterial(fingerprint(seed), "bundle:test", revision,
                "ACTIVE", predecessor, new RevocationAuthoritySnapshot(
                "registry:test", revocationRevision, fingerprint((char) ('5' + revision)),
                observedAt, expiresAt));
    }

    private static void fillClosureTo(Path root, long targetBytes) throws Exception {
        long remaining = targetBytes - closureByteSize(root);
        long generation = 1;
        while (remaining > 0) {
            long size = Math.min(remaining,
                    FilesystemDeploymentAdmissionAuthority.MAX_TRANSITION_EVIDENCE_BYTES);
            createSparseTransition(root, generation++, size);
            remaining -= size;
        }
        assertThat(closureByteSize(root)).isEqualTo(targetBytes);
    }

    private static void createSparseTransition(Path root, long generation, long size)
            throws Exception {
        createSparsePrivate(root.resolve(
                FilesystemDeploymentAdmissionAuthority.TRANSITION_EVIDENCE_PREFIX
                        + String.format("%020d", generation)
                        + FilesystemDeploymentAdmissionAuthority.TRANSITION_EVIDENCE_SUFFIX),
                size);
    }

    private static void createSparsePendingTransition(
            Path root, long generation, long size) throws Exception {
        createSparsePrivate(root.resolve("."
                + FilesystemDeploymentAdmissionAuthority.TRANSITION_EVIDENCE_PREFIX
                + String.format("%020d", generation)
                + FilesystemDeploymentAdmissionAuthority.TRANSITION_EVIDENCE_SUFFIX
                + ".tmp"), size);
    }

    private static void createSparsePrivate(Path path, long size) throws Exception {
        if (size < 1) {
            throw new IllegalArgumentException("size must be positive");
        }
        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(size - 1);
            channel.write(ByteBuffer.wrap(new byte[]{0}));
        }
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
    }

    private static long closureByteSize(Path root) throws Exception {
        long total = 0;
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                total = Math.addExact(total, Files.size(child));
            }
        }
        return total;
    }

    private static long uid(Path path) throws Exception {
        return ((Number) Files.getAttribute(path, "unix:uid",
                java.nio.file.LinkOption.NOFOLLOW_LINKS)).longValue();
    }

    private static FilesystemDeploymentAdmissionAuthority.ClosureFileMetadata metadata(
            Path path) throws java.io.IOException {
        BasicFileAttributes attributes = Files.readAttributes(path,
                BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        return new FilesystemDeploymentAdmissionAuthority.ClosureFileMetadata(
                attributes.fileKey(),
                ((Number) Files.getAttribute(path, "unix:nlink",
                        java.nio.file.LinkOption.NOFOLLOW_LINKS)).longValue(),
                ((Number) Files.getAttribute(path, "unix:uid",
                        java.nio.file.LinkOption.NOFOLLOW_LINKS)).longValue(),
                ((Number) Files.getAttribute(path, "unix:mode",
                        java.nio.file.LinkOption.NOFOLLOW_LINKS)).intValue() & 0777,
                attributes.size(), attributes.lastModifiedTime());
    }

    private static List<String> storeInventory(Path root) throws Exception {
        List<String> inventory = new ArrayList<>();
        try (var children = Files.list(root)) {
            for (Path child : children.sorted().toList()) {
                var observed = metadata(child);
                inventory.add(child.getFileName() + "|" + observed.fileKey()
                        + "|" + observed.linkCount() + "|" + observed.uid()
                        + "|" + observed.mode() + "|" + observed.size()
                        + "|" + observed.modifiedTime() + "|" + digest(child));
            }
        }
        return List.copyOf(inventory);
    }

    private static String digest(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Path stateRoot(String name) throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve(name).toAbsolutePath());
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(root, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
        return root;
    }

    private static void writePrivate(Path path, byte[] bytes) throws Exception {
        Files.write(path, bytes);
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
    }

    private static void deleteDirectory(Path root) throws Exception {
        try (var files = Files.list(root)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        Files.delete(root);
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }

    private static class NativeMetadata
            implements FilesystemDeploymentAdmissionAuthority.Metadata {
        @Override
        public BasicFileAttributes readAttributes(Path path) throws java.io.IOException {
            return Files.readAttributes(path, BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public long hardLinkCount(Path path) throws java.io.IOException {
            return ((Number) Files.getAttribute(path, "unix:nlink",
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)).longValue();
        }
    }

    private record NullFileKeyAttributes(BasicFileAttributes delegate)
            implements BasicFileAttributes {
        @Override
        public FileTime lastModifiedTime() {
            return delegate.lastModifiedTime();
        }

        @Override
        public FileTime lastAccessTime() {
            return delegate.lastAccessTime();
        }

        @Override
        public FileTime creationTime() {
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

    private static final class CheckpointFaultDurability
            implements FilesystemDeploymentAdmissionAuthority.Durability {
        private boolean failNextCheckpointForce;
        private boolean readBarrierAvailable = true;
        private int successfulReadBarriers;

        @Override
        public void atomicReplace(Path source, Path target) throws java.io.IOException {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        }

        @Override
        public void forceDirectory(Path directory, Path installedEntry)
                throws java.io.IOException {
            if (failNextCheckpointForce && installedEntry.getFileName().toString()
                    .equals(FilesystemDeploymentAdmissionAuthority.CHECKPOINT_FILE)) {
                failNextCheckpointForce = false;
                readBarrierAvailable = false;
                throw new java.io.IOException("CHECKPOINT_FORCE_PAYLOAD");
            }
            force(directory);
        }

        @Override
        public void forceReadBarrier(Path directory, Path checkpoint)
                throws java.io.IOException {
            if (!readBarrierAvailable) {
                throw new java.io.IOException("READ_BARRIER_PAYLOAD");
            }
            force(directory);
            successfulReadBarriers++;
        }

        private static void force(Path directory) throws java.io.IOException {
            try (FileChannel channel = FileChannel.open(
                    directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }
    }
}
