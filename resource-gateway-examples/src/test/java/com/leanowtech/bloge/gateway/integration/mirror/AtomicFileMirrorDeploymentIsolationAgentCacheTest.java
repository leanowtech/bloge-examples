package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtomicFileMirrorDeploymentIsolationAgentCacheTest {
    @TempDir
    private Path directory;

    private MirrorDeploymentIsolationAttestationRepositoryTestFixtures fixtures;
    private MirrorDeploymentIsolationAgentSnapshotIntegrity integrity;
    private MirrorDeploymentIsolationAuthorityKeySetPublication authority;
    private MirrorDeploymentIsolationAttestationBundle bundle;

    @BeforeEach
    void setUp() {
        fixtures = new MirrorDeploymentIsolationAttestationRepositoryTestFixtures();
        integrity = new MirrorDeploymentIsolationAgentSnapshotIntegrity(fixtures.mapper,
                fixtures.authorityIntegrity, fixtures.bundleIntegrity);
        authority = fixtures.authorityPublication();
        var attestation = fixtures.attestation(fixtures.BOOTSTRAP_REVISION,
                fixtures.deployment("cluster-a"), fixtures.fingerprint('2'));
        var status = fixtures.bundleIntegrity.activeStatus(fixtures.scope("org-a"),
                authority.artifactRef(), attestation, fixtures.activeClock.instant());
        bundle = fixtures.bundleIntegrity.bundle(fixtures.scope("org-a"),
                authority.artifactRef(), attestation, status);
    }

    @Test
    void atomicallyPersistsAndStrictlyRestoresCompleteGeneration() throws Exception {
        Path path = directory.resolve("mirror-isolation-trust.json");
        var cache = new AtomicFileMirrorDeploymentIsolationAgentCache(
                path, fixtures.mapper, integrity);
        var first = snapshot(1, fixtures.activeClock.instant());

        MirrorDeploymentIsolationAgentSnapshot committed = cache.replace("", first);

        assertThat(committed).isEqualTo(first);
        assertThat(cache.current()).contains(first);
        assertThat(new AtomicFileMirrorDeploymentIsolationAgentCache(
                path, fixtures.mapper, integrity).current()).contains(first);
        assertThat(Files.readString(path, StandardCharsets.UTF_8))
                .contains(first.snapshotFingerprint(), "\"cacheGeneration\":1")
                .doesNotContain(".tmp");
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(path)).containsExactlyInAnyOrder(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
        }
    }

    @Test
    void staleWriterAndNonContiguousGenerationCannotReplaceCurrentBytes() throws Exception {
        Path path = directory.resolve("mirror-isolation-trust.json");
        var cache = new AtomicFileMirrorDeploymentIsolationAgentCache(
                path, fixtures.mapper, integrity);
        var first = cache.replace("", snapshot(1, fixtures.activeClock.instant()));
        byte[] exact = Files.readAllBytes(path);

        assertThatThrownBy(() -> cache.replace("", snapshot(2,
                fixtures.activeClock.instant().plusSeconds(1))))
                .isInstanceOf(AtomicFileMirrorDeploymentIsolationAgentCache
                        .ConcurrentCacheReplacementException.class);
        assertThatThrownBy(() -> cache.replace(first.snapshotFingerprint(), snapshot(3,
                fixtures.activeClock.instant().plusSeconds(1))))
                .isInstanceOf(AtomicFileMirrorDeploymentIsolationAgentCache
                        .ConcurrentCacheReplacementException.class);

        assertThat(Files.readAllBytes(path)).containsExactly(exact);
        assertThat(cache.current()).contains(first);
    }

    @Test
    void corruptedTruncatedUnknownAndOversizedFilesFailClosed() throws Exception {
        Path path = directory.resolve("mirror-isolation-trust.json");
        var cache = new AtomicFileMirrorDeploymentIsolationAgentCache(
                path, fixtures.mapper, integrity);
        var first = cache.replace("", snapshot(1, fixtures.activeClock.instant()));
        String json = fixtures.mapper.writeValueAsString(first);

        Files.writeString(path, json.replace(first.snapshotFingerprint(),
                fixtures.fingerprint('f')), StandardCharsets.UTF_8);
        assertThatThrownBy(cache::current).isInstanceOf(IllegalStateException.class);

        Files.writeString(path, "{", StandardCharsets.UTF_8);
        assertThatThrownBy(cache::current).isInstanceOf(IllegalStateException.class);

        Files.writeString(path, json.replaceFirst("\\{", "{\"unknown\":true,"),
                StandardCharsets.UTF_8);
        assertThatThrownBy(cache::current).isInstanceOf(IllegalStateException.class);

        Files.write(path, new byte[
                MirrorDeploymentIsolationAgentSnapshotIntegrity.MAXIMUM_SNAPSHOT_BYTES + 1]);
        assertThatThrownBy(cache::current).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsRelativeDirectoryAndSymbolicLinkTargets() throws Exception {
        assertThatThrownBy(() -> new AtomicFileMirrorDeploymentIsolationAgentCache(
                Path.of("relative-cache.json"), fixtures.mapper, integrity))
                .isInstanceOf(IllegalArgumentException.class);

        Path target = directory.resolve("target.json");
        Files.writeString(target, "{}", StandardCharsets.UTF_8);
        Path link = directory.resolve("link.json");
        Files.createSymbolicLink(link, target);
        assertThatThrownBy(() -> new AtomicFileMirrorDeploymentIsolationAgentCache(
                link, fixtures.mapper, integrity))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotFingerprintCoversAuthorityBundleDeadlineAndGeneration() {
        Instant now = fixtures.activeClock.instant();
        var first = snapshot(1, now);
        var second = snapshot(2, now);
        var later = integrity.snapshot(1, now, now.plus(Duration.ofSeconds(6)),
                authority, bundle);

        assertThat(integrity.canonicalSnapshotVerified(first)).isTrue();
        assertThat(first.snapshotFingerprint())
                .isNotEqualTo(second.snapshotFingerprint())
                .isNotEqualTo(later.snapshotFingerprint());
        assertThat(first.usableAt(now.plusSeconds(4))).isTrue();
        assertThat(first.usableAt(now.plusSeconds(5))).isFalse();
    }

    private MirrorDeploymentIsolationAgentSnapshot snapshot(long generation, Instant now) {
        return integrity.snapshot(generation, now, now.plus(Duration.ofSeconds(5)),
                authority, bundle);
    }
}
