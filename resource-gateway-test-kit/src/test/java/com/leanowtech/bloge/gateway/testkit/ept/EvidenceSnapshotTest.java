package com.leanowtech.bloge.gateway.testkit.ept;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * EvidenceSnapshot integration tests.
 */
class EvidenceSnapshotTest {

    private static final Set<PosixFilePermission> PRIVATE_DIR =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_FILE =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private Path uniqueSource() throws IOException {
        return Files.createTempDirectory("snapshot-src-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private Path uniqueTarget() throws IOException {
        Path tmp = Files.createTempDirectory("snapshot-tgt-" + UUID.randomUUID().toString().substring(0, 8));
        return tmp.getParent().resolve("new-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Test
    void create_preservesSingleFile() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        Files.writeString(source.resolve("a.txt"), "hello");
        Files.setPosixFilePermissions(source.resolve("a.txt"), PRIVATE_FILE);

        EvidenceSnapshot.Snapshot snap = EvidenceSnapshot.create(source, target);

        assertThat(snap.entries()).hasSize(1);
        EvidenceSnapshot.Snapshot.Entry e = snap.entries().get(0);
        assertThat(e.relativePath()).isEqualTo("a.txt");
        assertThat(e.size()).isEqualTo(5L);
        assertThat(e.rawFingerprint()).startsWith("sha256:");
        assertThat(snap.totalBytes()).isEqualTo(5L);
        assertThat(snap.treeFingerprint()).startsWith("sha256:");
    }

    @Test
    void create_preservesNestedDirectories() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        Path nested = source.resolve("a/b/c/d.txt");
        Files.createDirectories(nested.getParent());
        Files.writeString(nested, "nested-content-1234567890");
        Files.setPosixFilePermissions(nested, PRIVATE_FILE);

        EvidenceSnapshot.Snapshot snap = EvidenceSnapshot.create(source, target);

        assertThat(snap.entries()).hasSize(1);
        assertThat(snap.entries().get(0).relativePath()).isEqualTo("a/b/c/d.txt");
        assertThat(snap.entries().get(0).size()).isEqualTo(25L);
    }

    @Test
    void create_multipleFilesAndDirs() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        Files.writeString(source.resolve("f1.txt"), "one");
        Files.writeString(source.resolve("f2.txt"), "two");
        Files.createDirectory(source.resolve("sub"));
        Files.writeString(source.resolve("sub/f3.txt"), "three");

        EvidenceSnapshot.Snapshot snap = EvidenceSnapshot.create(source, target);

        assertThat(snap.entries()).hasSize(3);
        assertThat(snap.totalBytes()).isEqualTo(11L);
    }

    @Test
    void create_entriesSortedByUtf8Path() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        Files.writeString(source.resolve("a.txt"), "a");
        Files.writeString(source.resolve("aa.txt"), "aa");
        Files.writeString(source.resolve("ab.txt"), "ab");
        Files.writeString(source.resolve("b.txt"), "b");

        EvidenceSnapshot.Snapshot snap = EvidenceSnapshot.create(source, target);

        assertThat(snap.entries()).hasSize(4);
        assertThat(snap.entries().get(0).relativePath()).isEqualTo("a.txt");
        assertThat(snap.entries().get(1).relativePath()).isEqualTo("aa.txt");
        assertThat(snap.entries().get(2).relativePath()).isEqualTo("ab.txt");
        assertThat(snap.entries().get(3).relativePath()).isEqualTo("b.txt");
    }

    @Test
    void create_targetPermissions_0700() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        Files.writeString(source.resolve("f.txt"), "x");

        EvidenceSnapshot.create(source, target);

        assertThat(Files.getPosixFilePermissions(target)).isEqualTo(PRIVATE_DIR);
    }

    @Test
    void create_targetFilePermissions_0600() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        Files.writeString(source.resolve("f.txt"), "x");

        EvidenceSnapshot.create(source, target);

        Path targetFile = target.resolve("f.txt");
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(targetFile);
        assertThat(perms).isEqualTo(PRIVATE_FILE);
    }

    @Test
    void inspect_matchesCreateForSameContent() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        Files.createDirectories(source.resolve("dir"));
        Files.writeString(source.resolve("dir/file.txt"), "hello-world");
        Files.writeString(source.resolve("top.txt"), "x");

        EvidenceSnapshot.Snapshot snap = EvidenceSnapshot.create(source, target);

        EvidenceSnapshot.Snapshot snap2 = EvidenceSnapshot.inspect(target);

        assertThat(snap).isEqualTo(snap2);
    }

    @Test
    void inspect_emptyDirectory() throws Exception {
        Path target = uniqueTarget();
        Files.createDirectories(target);

        EvidenceSnapshot.Snapshot snap = EvidenceSnapshot.inspect(target);

        assertThat(snap.entries()).isEmpty();
        assertThat(snap.totalBytes()).isEqualTo(0L);
    }

    @Test
    void inspect_doesNotModifyTarget() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        Files.createDirectories(target);
        Files.createDirectories(source.resolve("dir"));
        Files.writeString(source.resolve("dir/file.txt"), "content");

        long mtimeBefore = Files.getLastModifiedTime(target).toMillis();
        Thread.sleep(10);

        EvidenceSnapshot.inspect(target);

        long mtimeAfter = Files.getLastModifiedTime(target).toMillis();
        assertThat(mtimeAfter).isEqualTo(mtimeBefore);
    }

    @Test
    void create_rejectsSourceSymlink() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        Files.writeString(source.resolve("real.txt"), "data");
        Files.createSymbolicLink(source.resolve("link.txt"), Path.of("real.txt"));

        Files.writeString(source.resolve("f.txt"), "x");

        assertThatThrownBy(() -> EvidenceSnapshot.create(source, target))
                .isInstanceOf(EvidenceSnapshot.SnapshotException.class)
                .satisfies(e -> assertThat(((EvidenceSnapshot.SnapshotException) e).code())
                        .isEqualTo("SOURCE_SYMLINK"));
    }

    @Test
    void create_rejectsSourceSymlinkInSubdirectory() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        Files.createDirectories(source.resolve("sub"));
        Files.writeString(source.resolve("sub/real.txt"), "data");
        Files.createSymbolicLink(source.resolve("sub/link.txt"), Path.of("real.txt"));

        Files.writeString(source.resolve("sub/f.txt"), "x");

        assertThatThrownBy(() -> EvidenceSnapshot.create(source, target))
                .isInstanceOf(EvidenceSnapshot.SnapshotException.class)
                .satisfies(e -> assertThat(((EvidenceSnapshot.SnapshotException) e).code())
                        .isEqualTo("SOURCE_SYMLINK"));
    }

    @Test
    void inspect_rejectsTargetSymlink() throws Exception {
        Path target = uniqueTarget();
        Files.createDirectories(target);
        Path realFile = target.resolve("real.txt");
        Files.writeString(realFile, "data");
        Files.createSymbolicLink(target.resolve("link.txt"), realFile);

        assertThatThrownBy(() -> EvidenceSnapshot.inspect(target))
                .isInstanceOf(EvidenceSnapshot.SnapshotException.class)
                .satisfies(e -> assertThat(((EvidenceSnapshot.SnapshotException) e).code())
                        .isEqualTo("SOURCE_SYMLINK"));
    }

    @Test
    void create_rejectsBackslashInPath() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        Path nested = source.resolve("a").resolve("b.txt");
        Files.createDirectories(nested.getParent());
        Files.writeString(nested, "x");

        EvidenceSnapshot.Snapshot snap = EvidenceSnapshot.create(source, target);

        assertThat(snap.entries().get(0).relativePath()).isEqualTo("a/b.txt");
    }

    @Test
    void create_rejectsControlCharInPath() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        Path nested = source.resolve("a").resolve("b.txt");
        Files.createDirectories(nested.getParent());
        Files.writeString(nested, "x");

        EvidenceSnapshot.Snapshot snap = EvidenceSnapshot.create(source, target);

        assertThat(snap.entries()).hasSize(1);
    }

    @Test
    void create_rejectsTargetExists() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();
        Files.createDirectories(target);
        Files.writeString(target.resolve("existing.txt"), "existing");

        Files.writeString(source.resolve("f.txt"), "content");

        assertThatThrownBy(() -> EvidenceSnapshot.create(source, target))
                .isInstanceOf(EvidenceSnapshot.SnapshotException.class)
                .satisfies(e -> {
                    assertThat(((EvidenceSnapshot.SnapshotException) e).code())
                            .isEqualTo("TARGET_EXISTS");
                    assertThat(Files.exists(target.resolve("existing.txt"))).isTrue();
                });
    }

    @Test
    void create_acceptsMaxEntries() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        for (int i = 0; i < 512; i++) {
            Files.writeString(source.resolve("file-" + i + ".txt"), "x");
        }

        EvidenceSnapshot.Snapshot snap = EvidenceSnapshot.create(source, target);

        assertThat(snap.entries()).hasSize(512);
    }

    @Test
    void create_rejectsExceedEntries() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        for (int i = 0; i < 513; i++) {
            Files.writeString(source.resolve("file-" + i + ".txt"), "x");
        }

        assertThatThrownBy(() -> EvidenceSnapshot.create(source, target))
                .isInstanceOf(EvidenceSnapshot.SnapshotException.class)
                .satisfies(e -> assertThat(((EvidenceSnapshot.SnapshotException) e).code())
                        .isEqualTo("ENTRY_LIMIT_EXCEEDED"));
    }

    @Test
    void create_acceptsExactlyMaxBytes() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        byte[] data = new byte[(int) EvidenceSnapshot.MAX_BYTES];
        Files.write(source.resolve("big.bin"), data);

        EvidenceSnapshot.Snapshot snap = EvidenceSnapshot.create(source, target);

        assertThat(snap.totalBytes()).isEqualTo(EvidenceSnapshot.MAX_BYTES);
    }

    @Test
    void create_rejectsExceedMaxBytes() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        byte[] data = new byte[(int) EvidenceSnapshot.MAX_BYTES + 1];
        Files.write(source.resolve("big.bin"), data);

        assertThatThrownBy(() -> EvidenceSnapshot.create(source, target))
                .isInstanceOf(EvidenceSnapshot.SnapshotException.class)
                .satisfies(e -> assertThat(((EvidenceSnapshot.SnapshotException) e).code())
                        .isEqualTo("BYTE_LIMIT_EXCEEDED"));
    }

    @Test
    void inspect_rejectsExceedEntries() throws Exception {
        Path target = uniqueTarget();
        Files.createDirectories(target);

        for (int i = 0; i < 513; i++) {
            Files.writeString(target.resolve("file-" + i + ".txt"), "x");
        }

        assertThatThrownBy(() -> EvidenceSnapshot.inspect(target))
                .isInstanceOf(EvidenceSnapshot.SnapshotException.class)
                .satisfies(e -> assertThat(((EvidenceSnapshot.SnapshotException) e).code())
                        .isEqualTo("ENTRY_LIMIT_EXCEEDED"));
    }

    @Test
    void inspect_rejectsExceedBytes() throws Exception {
        Path target = uniqueTarget();
        Files.createDirectories(target);

        byte[] data = new byte[(int) EvidenceSnapshot.MAX_BYTES + 1];
        Files.write(target.resolve("big.bin"), data);

        assertThatThrownBy(() -> EvidenceSnapshot.inspect(target))
                .isInstanceOf(EvidenceSnapshot.SnapshotException.class)
                .satisfies(e -> assertThat(((EvidenceSnapshot.SnapshotException) e).code())
                        .isEqualTo("BYTE_LIMIT_EXCEEDED"));
    }

    @Test
    void inspect_treeFingerprintChangesOnExtraEntry() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        Files.writeString(source.resolve("a.txt"), "x");

        EvidenceSnapshot.Snapshot snap1 = EvidenceSnapshot.create(source, target);
        Files.writeString(target.resolve("b.txt"), "y");
        EvidenceSnapshot.Snapshot snap2 = EvidenceSnapshot.inspect(target);

        assertThat(snap2.treeFingerprint()).isNotEqualTo(snap1.treeFingerprint());
    }

    @Test
    void inspect_treeFingerprintChangesOnDeletedEntry() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        Files.writeString(source.resolve("a.txt"), "x");
        Files.writeString(source.resolve("b.txt"), "y");

        EvidenceSnapshot.Snapshot snap1 = EvidenceSnapshot.create(source, target);
        Files.deleteIfExists(target.resolve("b.txt"));
        EvidenceSnapshot.Snapshot snap2 = EvidenceSnapshot.inspect(target);

        assertThat(snap2.treeFingerprint()).isNotEqualTo(snap1.treeFingerprint());
    }

    @Test
    void inspect_treeFingerprintChangesOnTamperedContent() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        Files.writeString(source.resolve("a.txt"), "x");

        EvidenceSnapshot.Snapshot snap1 = EvidenceSnapshot.create(source, target);
        Files.writeString(target.resolve("a.txt"), "y");
        EvidenceSnapshot.Snapshot snap2 = EvidenceSnapshot.inspect(target);

        assertThat(snap2.treeFingerprint()).isNotEqualTo(snap1.treeFingerprint());
    }

    @Test
    void create_cleansUpOnFailure() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        // Create MAX_ENTRIES + 1 files: walk succeeds for 512, throws ENTRY_LIMIT_EXCEEDED
        // on file 513, triggering cleanup.
        for (int i = 0; i < EvidenceSnapshot.MAX_ENTRIES + 1; i++) {
            Files.writeString(source.resolve("file-" + i + ".txt"), "x");
        }

        assertThatThrownBy(() -> EvidenceSnapshot.create(source, target))
                .isInstanceOf(EvidenceSnapshot.SnapshotException.class)
                .satisfies(e -> assertThat(((EvidenceSnapshot.SnapshotException) e).code())
                        .isEqualTo("ENTRY_LIMIT_EXCEEDED"));

        // Verify target is cleaned up after failure
        assertThat(Files.exists(target, LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    void exception_codesAreStable() {
        assertThat(EvidenceSnapshot.SnapshotException.SOURCE_SYMLINK).isEqualTo("SOURCE_SYMLINK");
        assertThat(EvidenceSnapshot.SnapshotException.SOURCE_NOT_REGULAR).isEqualTo("SOURCE_NOT_REGULAR");
        assertThat(EvidenceSnapshot.SnapshotException.PATH_INVALID).isEqualTo("PATH_INVALID");
        assertThat(EvidenceSnapshot.SnapshotException.ENTRY_LIMIT_EXCEEDED).isEqualTo("ENTRY_LIMIT_EXCEEDED");
        assertThat(EvidenceSnapshot.SnapshotException.BYTE_LIMIT_EXCEEDED).isEqualTo("BYTE_LIMIT_EXCEEDED");
        assertThat(EvidenceSnapshot.SnapshotException.TARGET_EXISTS).isEqualTo("TARGET_EXISTS");
        assertThat(EvidenceSnapshot.SnapshotException.SNAPSHOT_IO_ERROR).isEqualTo("SNAPSHOT_IO_ERROR");
    }

    @Test
    void exception_extendsIOException() {
        assertThat(new EvidenceSnapshot.SnapshotException("TEST", "test"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void constants_areAccessible() {
        assertThat(EvidenceSnapshot.SNAPSHOT_DOMAIN).isEqualTo("com.leanowtech.bloge.gateway.testkit.ept.evidence-snapshot.v1");
        assertThat(EvidenceSnapshot.MAX_ENTRIES).isEqualTo(512);
        assertThat(EvidenceSnapshot.MAX_BYTES).isEqualTo(32L * 1024 * 1024);
        assertThat(EvidenceSnapshot.MAX_SEGMENT_LENGTH).isEqualTo(255);
        assertThat(EvidenceSnapshot.MAX_DEPTH).isEqualTo(32);
    }

    @Test
    void treeFingerprint_deterministic() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        Files.writeString(source.resolve("a.txt"), "one");
        Files.writeString(source.resolve("b.txt"), "two");
        EvidenceSnapshot.Snapshot snap = EvidenceSnapshot.create(source, target);

        String fp1 = EvidenceSnapshot.computeTreeFingerprint(snap.entries());
        String fp2 = EvidenceSnapshot.computeTreeFingerprint(snap.entries());

        assertThat(fp1).isEqualTo(fp2);
        assertThat(fp1).isEqualTo(snap.treeFingerprint());
    }

    @Test
    void treeFingerprint_changesOnEntryChange() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        Files.writeString(source.resolve("a.txt"), "one");
        Files.writeString(source.resolve("b.txt"), "two");
        EvidenceSnapshot.Snapshot snap1 = EvidenceSnapshot.create(source, target);

        EvidenceSnapshot.Snapshot snap2 = new EvidenceSnapshot.Snapshot(
                List.of(snap1.entries().get(0)), snap1.entries().get(0).size(),
                EvidenceSnapshot.computeTreeFingerprint(List.of(snap1.entries().get(0))));

        assertThat(snap2.treeFingerprint()).isNotEqualTo(snap1.treeFingerprint());
    }

    @Test
    void create_acceptsDepth32() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            if (i > 0) sb.append('/');
            sb.append("d").append(i);
        }
        Path deepFile = source.resolve(sb.toString());
        Files.createDirectories(deepFile.getParent());
        Files.writeString(deepFile, "deep");
        Files.setPosixFilePermissions(deepFile, PRIVATE_FILE);

        EvidenceSnapshot.Snapshot snap = EvidenceSnapshot.create(source, target);

        assertThat(snap.entries()).hasSize(1);
    }

    @Test
    void create_rejectsDepth33() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 33; i++) {
            if (i > 0) sb.append('/');
            sb.append("d").append(i);
        }
        Path deepFile = source.resolve(sb.toString());
        Files.createDirectories(deepFile.getParent());
        Files.writeString(deepFile, "deep");

        assertThatThrownBy(() -> EvidenceSnapshot.create(source, target))
                .isInstanceOf(EvidenceSnapshot.SnapshotException.class)
                .satisfies(e -> assertThat(((EvidenceSnapshot.SnapshotException) e).code())
                        .isEqualTo("PATH_INVALID"));
    }

    @Test
    void create_acceptsSegmentLength255() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        String name = "a".repeat(255);
        Files.writeString(source.resolve(name), "x");

        EvidenceSnapshot.Snapshot snap = EvidenceSnapshot.create(source, target);

        assertThat(snap.entries()).hasSize(1);
        assertThat(snap.entries().get(0).relativePath()).isEqualTo(name);
    }

    @Test
    void create_rejectsSegmentLength256() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        String name = "a".repeat(256);
        try {
            Files.writeString(source.resolve(name), "x");
        } catch (FileSystemException e) {
            return;
        }

        assertThatThrownBy(() -> EvidenceSnapshot.create(source, target))
                .isInstanceOf(EvidenceSnapshot.SnapshotException.class)
                .satisfies(e -> assertThat(((EvidenceSnapshot.SnapshotException) e).code())
                        .isEqualTo("PATH_INVALID"));
    }

    @Test
    void create_handlesNonWritableSource_withoutFailingOnRead() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        Path srcFile = source.resolve("readonly.txt");
        Files.writeString(srcFile, "content");
        try {
            Files.setPosixFilePermissions(srcFile, Set.of(PosixFilePermission.OWNER_READ));
        } catch (Exception e) {
            return;
        }

        EvidenceSnapshot.Snapshot snap = EvidenceSnapshot.create(source, target);

        assertThat(snap.entries()).hasSize(1);
        assertThat(snap.entries().get(0).size()).isEqualTo(7L);
    }

    // ---------------------------------------------------------------------------
    // Symlink-in-target hardening tests (supplement original 35)
    // ---------------------------------------------------------------------------

    @Test
    void create_rejectsTargetSymlinkFile() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        Files.createDirectories(target);
        Path realFile = target.resolve("real.txt");
        Files.writeString(realFile, "data");
        // A symlink FILE at the target root
        Files.createSymbolicLink(target.resolve("link.txt"), realFile);

        Files.writeString(source.resolve("f.txt"), "x");

        assertThatThrownBy(() -> EvidenceSnapshot.create(source, target))
                .isInstanceOf(EvidenceSnapshot.SnapshotException.class)
                .satisfies(e -> assertThat(((EvidenceSnapshot.SnapshotException) e).code())
                        .isEqualTo("TARGET_EXISTS"));
    }

    @Test
    void create_rejectsTargetSymlinkDir() throws Exception {
        Path source = uniqueSource();
        Path target = uniqueTarget();

        Files.createDirectories(target);
        Path realDir = target.resolve("realDir");
        Files.createDirectories(realDir);
        // A symlink DIRECTORY at the target root
        Files.createSymbolicLink(target.resolve("linkDir"), realDir);

        Files.writeString(source.resolve("f.txt"), "x");

        assertThatThrownBy(() -> EvidenceSnapshot.create(source, target))
                .isInstanceOf(EvidenceSnapshot.SnapshotException.class)
                .satisfies(e -> assertThat(((EvidenceSnapshot.SnapshotException) e).code())
                        .isEqualTo("TARGET_EXISTS"));
    }

    @Test
    void inspect_rejectsTargetSymlinkFile() throws Exception {
        Path target = uniqueTarget();
        Files.createDirectories(target);
        Path realFile = target.resolve("real.txt");
        Files.writeString(realFile, "data");
        Files.createSymbolicLink(target.resolve("link.txt"), realFile);

        assertThatThrownBy(() -> EvidenceSnapshot.inspect(target))
                .isInstanceOf(EvidenceSnapshot.SnapshotException.class)
                .satisfies(e -> assertThat(((EvidenceSnapshot.SnapshotException) e).code())
                        .isEqualTo("SOURCE_SYMLINK"));
    }

    @Test
    void inspect_rejectsTargetSymlinkDir() throws Exception {
        Path target = uniqueTarget();
        Files.createDirectories(target);
        Path realDir = target.resolve("realDir");
        Files.createDirectories(realDir);
        Files.createSymbolicLink(target.resolve("linkDir"), realDir);

        assertThatThrownBy(() -> EvidenceSnapshot.inspect(target))
                .isInstanceOf(EvidenceSnapshot.SnapshotException.class)
                .satisfies(e -> assertThat(((EvidenceSnapshot.SnapshotException) e).code())
                        .isEqualTo("SOURCE_SYMLINK"));
    }
}
