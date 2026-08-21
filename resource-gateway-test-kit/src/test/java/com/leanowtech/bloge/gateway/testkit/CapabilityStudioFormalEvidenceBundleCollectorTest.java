package com.leanowtech.bloge.gateway.testkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioFormalEvidenceBundleCollectorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acquiresManifestSealsInventoryAndClosesAReplay() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(temporaryDirectory);
        AtomicInteger inventoryReads = new AtomicInteger();
        AtomicBoolean inventoryObserved = new AtomicBoolean();
        var session = CapabilityStudioFormalEvidenceBundleCollector.Session.open(
                fixture.manifest(), fixture.root(), new CapabilityStudioFormalEvidenceBundleCollector.Observer() {
                    @Override
                    public void inventorySnapshotted(
                            Map<String, CapabilityStudioFormalEvidenceRunManifest.EvidenceEntry>
                                    inventory) {
                        inventoryObserved.set(true);
                    }

                    @Override
                    public void beforeInventoryRead(String relativePath, Path file) {
                        inventoryReads.incrementAndGet();
                    }
                });
        byte[] manifestBytes = session.manifestBytes();
        session.afterManifestCompiled();
        session.sealInventory(inventory(fixture));
        session.beforeReplay("stage-replay", "stage-result.json", true);
        session.afterReplay("stage-replay", "stage-result.json", true);
        session.finish(Set.of());

        assertThat(manifestBytes).isEqualTo(Files.readAllBytes(fixture.manifest()));
        assertThat(session.root()).isEqualTo(fixture.root());
        assertThat(inventoryObserved).isTrue();
        assertThat(inventoryReads).hasValue(1);
        assertThat(session.observation().files()).containsKey("stage-result.json");
        assertThat(session.observation().files().get("stage-result.json").rawFingerprint())
                .isEqualTo(fixture.manifestNode().withArray("evidenceInventory")
                        .get(0).path("rawFingerprint").textValue());
    }

    @Test
    void rejectsUnexpectedFilesAndSymlinksBeforeSealingClosure() throws Exception {
        var unexpected = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(temporaryDirectory);
        Path extra = unexpected.root().resolve("unexpected.json");
        Files.writeString(extra, "{}");
        Files.setPosixFilePermissions(extra,
                PosixFilePermissions.fromString("rw-------"));
        assertInvalidAfterCompile(unexpected, inventory(unexpected));

        var symlink = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(temporaryDirectory);
        Files.createSymbolicLink(symlink.root().resolve("unexpected-link"),
                symlink.root().resolve("stage-result.json"));
        assertInvalidAfterCompile(symlink, inventory(symlink));
    }

    @Test
    void rejectsManifestMutationFromManifestReadHookWithoutLeakingPath() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(temporaryDirectory);
        assertThatThrownBy(() -> CapabilityStudioFormalEvidenceBundleCollector.Session.open(
                fixture.manifest(), fixture.root(), new CapabilityStudioFormalEvidenceBundleCollector.Observer() {
                    @Override
                    public void manifestRead(Path manifestFile) {
                        try {
                            Files.writeString(manifestFile, "{}");
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    }
                }))
                .isInstanceOf(CapabilityStudioFormalEvidenceBundleCollector.CollectorException.class)
                .hasMessage("formal evidence bundle collection failed")
                .satisfies(error -> assertThat(((CapabilityStudioFormalEvidenceBundleCollector.CollectorException)
                        error).failureKind()).isEqualTo(
                        CapabilityStudioFormalEvidenceBundleCollector.FailureKind.UNAVAILABLE));
    }

    @Test
    void classifiesInventoryAndReplayHookMutationAsUnavailable() throws Exception {
        var inventoryMutation = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(
                temporaryDirectory);
        AtomicBoolean mutated = new AtomicBoolean();
        var inventorySession = open(inventoryMutation, new CapabilityStudioFormalEvidenceBundleCollector.Observer() {
            @Override
            public void beforeInventoryRead(String relativePath, Path file) {
                if (mutated.compareAndSet(false, true)) {
                    try {
                        Files.writeString(file, "changed");
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                }
            }
        });
        assertUnavailable(() -> inventorySession.sealInventory(inventory(inventoryMutation)));

        var replayMutation = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(
                temporaryDirectory);
        AtomicBoolean replayMutated = new AtomicBoolean();
        var replaySession = open(replayMutation, new CapabilityStudioFormalEvidenceBundleCollector.Observer() {
            @Override
            public void beforeReplay(String id, String relativePath, boolean fileSubject) {
                if (replayMutated.compareAndSet(false, true)) {
                    try {
                        Path moved = replayMutation.root().resolve("stage-result-moved.json");
                        Files.move(replayMutation.root().resolve(relativePath), moved);
                        Files.createSymbolicLink(replayMutation.root().resolve(relativePath), moved);
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                }
            }
        });
        replaySession.sealInventory(inventory(replayMutation));
        assertUnavailable(() -> replaySession.beforeReplay(
                "stage-replay", "stage-result.json", true));
    }

    @Test
    void rejectsInsecurePermissionsWhenPosixPermissionsAreAvailable() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(temporaryDirectory);
        Files.setPosixFilePermissions(fixture.root().resolve("stage-result.json"),
                PosixFilePermissions.fromString("rw-r--r--"));
        assertInvalidAfterCompile(fixture, inventory(fixture));
    }

    @Test
    void detectsSameSizeFileMutationEvenWhenTimestampIsRestored() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(temporaryDirectory);
        var session = open(fixture, CapabilityStudioFormalEvidenceBundleCollector.Observer.NOOP);
        session.sealInventory(inventory(fixture));
        session.beforeReplay("stage-replay", "stage-result.json", true);

        mutateSameSizeAndRestoreTimestamp(fixture.root().resolve("stage-result.json"));

        assertInvalid(() -> session.afterReplay("stage-replay", "stage-result.json", true));
    }

    @Test
    void detectsSameSizeDescendantMutationAcrossDirectoryReplay() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.formalInputTree(temporaryDirectory);
        var session = open(fixture, CapabilityStudioFormalEvidenceBundleCollector.Observer.NOOP);
        session.sealInventory(inventory(fixture));
        String subject = "tree-subject/input-wrapper";
        session.beforeReplay("tree-replay", subject, false);
        String descendant = inventory(fixture).keySet().stream()
                .filter(path -> path.startsWith(subject + "/"))
                .findFirst().orElseThrow();

        mutateSameSizeAndRestoreTimestamp(fixture.root().resolve(descendant));

        assertInvalid(() -> session.afterReplay("tree-replay", subject, false));
    }

    @Test
    void requiresHardlinkScopeAndAcceptsAClosedInternalScope() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.empty(temporaryDirectory);
        Path scope = fixture.root().resolve("scope");
        Files.createDirectory(scope,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path first = scope.resolve("value.json");
        Path second = scope.resolve("value-copy.json");
        Files.writeString(first, "value");
        Files.setPosixFilePermissions(first,
                PosixFilePermissions.fromString("rw-------"));
        Files.createLink(second, first);
        fixture.addAllInventory();
        fixture.write();

        var rejected = open(fixture, CapabilityStudioFormalEvidenceBundleCollector.Observer.NOOP);
        rejected.sealInventory(inventory(fixture));
        assertThatThrownBy(() -> rejected.finish(Set.of()))
                .isInstanceOf(CapabilityStudioFormalEvidenceBundleCollector.CollectorException.class)
                .satisfies(error -> assertThat(((CapabilityStudioFormalEvidenceBundleCollector.CollectorException)
                        error).failureKind()).isEqualTo(
                        CapabilityStudioFormalEvidenceBundleCollector.FailureKind.INVALID));
        assertInvalid(() -> rejected.finish(Set.of("scope")));

        var session = open(fixture, CapabilityStudioFormalEvidenceBundleCollector.Observer.NOOP);
        session.sealInventory(inventory(fixture));
        session.finish(Set.of("scope"));
        assertThat(session.observation().files().get("scope/value.json").linkCount()).isEqualTo(2);
    }

    @Test
    void rejectsAnExternalHardlinkEvenWhenTheInRootScopeIsNamed() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.empty(temporaryDirectory);
        Path scope = fixture.root().resolve("scope");
        Files.createDirectory(scope,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path inRoot = scope.resolve("value.json");
        Files.writeString(inRoot, "value");
        Files.setPosixFilePermissions(inRoot,
                PosixFilePermissions.fromString("rw-------"));
        Files.createLink(temporaryDirectory.resolve("external-value.json"), inRoot);
        fixture.addAllInventory();
        fixture.write();

        var session = open(fixture, CapabilityStudioFormalEvidenceBundleCollector.Observer.NOOP);
        session.sealInventory(inventory(fixture));
        assertInvalid(() -> session.finish(Set.of("scope")));
    }

    private CapabilityStudioFormalEvidenceBundleCollector.Session open(
            CapabilityStudioFormalEvidenceRunTestFixtures.Fixture fixture,
            CapabilityStudioFormalEvidenceBundleCollector.Observer observer) {
        var session = CapabilityStudioFormalEvidenceBundleCollector.Session.open(
                fixture.manifest(), fixture.root(), observer);
        session.afterManifestCompiled();
        return session;
    }

    private static Map<String, CapabilityStudioFormalEvidenceRunManifest.EvidenceEntry> inventory(
            CapabilityStudioFormalEvidenceRunTestFixtures.Fixture fixture) {
        Map<String, CapabilityStudioFormalEvidenceRunManifest.EvidenceEntry> result =
                new LinkedHashMap<>();
        for (var entry : fixture.manifestNode().withArray("evidenceInventory")) {
            String path = entry.path("relativePath").textValue();
            result.put(path, new CapabilityStudioFormalEvidenceRunManifest.EvidenceEntry(
                    path, entry.path("byteSize").longValue(),
                    entry.path("rawFingerprint").textValue()));
        }
        return result;
    }

    private static void mutateSameSizeAndRestoreTimestamp(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        FileTime modified = Files.getLastModifiedTime(file);
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
        Set<PosixFilePermission> writable = EnumSet.copyOf(permissions);
        writable.add(PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(file, writable);
        bytes[0] ^= 1;
        Files.write(file, bytes);
        Files.setPosixFilePermissions(file, permissions);
        Files.setLastModifiedTime(file, modified);
    }

    private void assertInvalidAfterCompile(
            CapabilityStudioFormalEvidenceRunTestFixtures.Fixture fixture,
            Map<String, CapabilityStudioFormalEvidenceRunManifest.EvidenceEntry> inventory) {
        var session = open(fixture, CapabilityStudioFormalEvidenceBundleCollector.Observer.NOOP);
        assertInvalid(() -> session.sealInventory(inventory));
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOf(CapabilityStudioFormalEvidenceBundleCollector.CollectorException.class)
                .hasMessage("formal evidence bundle collection failed")
                .satisfies(error -> assertThat(((CapabilityStudioFormalEvidenceBundleCollector.CollectorException)
                        error).failureKind()).isEqualTo(
                        CapabilityStudioFormalEvidenceBundleCollector.FailureKind.INVALID));
    }

    private static void assertUnavailable(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOf(CapabilityStudioFormalEvidenceBundleCollector.CollectorException.class)
                .hasMessage("formal evidence bundle collection failed")
                .satisfies(error -> assertThat(((CapabilityStudioFormalEvidenceBundleCollector.CollectorException)
                        error).failureKind()).isEqualTo(
                        CapabilityStudioFormalEvidenceBundleCollector.FailureKind.UNAVAILABLE));
    }
}
