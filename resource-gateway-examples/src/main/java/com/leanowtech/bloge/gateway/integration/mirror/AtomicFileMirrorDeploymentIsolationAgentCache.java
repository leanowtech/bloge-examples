package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Crash-safe single-writer file cache for deployment-isolation agent snapshots.
 *
 * <p>Every replacement is written and forced in the destination directory, moved with a required
 * atomic rename, and followed by a directory force. Unsupported atomic moves fail closed. The
 * target must live in an existing absolute non-symlink directory owned and protected by deployment
 * operations; this class never follows a symbolic-link cache target.</p>
 */
public final class AtomicFileMirrorDeploymentIsolationAgentCache
        implements MirrorDeploymentIsolationAgentCache {
    private static final int MAXIMUM_FILE_BYTES =
            MirrorDeploymentIsolationAgentSnapshotIntegrity.MAXIMUM_SNAPSHOT_BYTES;
    private static final Set<PosixFilePermission> OWNER_READ_WRITE = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path path;
    private final Path directory;
    private final ObjectMapper mapper;
    private final MirrorDeploymentIsolationAgentSnapshotIntegrity integrity;

    /**
     * Creates a durable cache at one deployment-owned absolute path.
     *
     * @param path absolute snapshot file path in an existing non-symlink directory
     * @param mapper strict protocol mapper
     * @param integrity independent snapshot fingerprint verifier
     */
    public AtomicFileMirrorDeploymentIsolationAgentCache(
            Path path,
            ObjectMapper mapper,
            MirrorDeploymentIsolationAgentSnapshotIntegrity integrity) {
        Path exact = Objects.requireNonNull(path, "path").normalize();
        Path parent = exact.getParent();
        if (!exact.isAbsolute() || parent == null || !Files.isDirectory(
                parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)
                || Files.exists(exact, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(exact)
                || !Files.isRegularFile(exact, LinkOption.NOFOLLOW_LINKS))) {
            throw new IllegalArgumentException(
                    "deployment isolation agent cache path is invalid");
        }
        this.path = exact;
        this.directory = parent;
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.integrity = Objects.requireNonNull(integrity, "integrity");
        current();
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Optional<MirrorDeploymentIsolationAgentSnapshot> current() {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw unavailable();
        }
        try {
            byte[] bytes = readBounded();
            MirrorDeploymentIsolationAgentSnapshot snapshot = mapper.readValue(
                    bytes, MirrorDeploymentIsolationAgentSnapshot.class);
            if (!integrity.canonicalSnapshotVerified(snapshot)) {
                throw unavailable();
            }
            return Optional.of(snapshot);
        } catch (IOException | RuntimeException invalid) {
            throw unavailable(invalid);
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized MirrorDeploymentIsolationAgentSnapshot replace(
            String expectedSnapshotFingerprint,
            MirrorDeploymentIsolationAgentSnapshot candidate) {
        String expected = normalized(expectedSnapshotFingerprint);
        MirrorDeploymentIsolationAgentSnapshot next = Objects.requireNonNull(
                candidate, "candidate");
        if (!integrity.canonicalSnapshotVerified(next)) {
            throw new IllegalArgumentException(
                    "canonical deployment isolation agent snapshot is required");
        }
        Optional<MirrorDeploymentIsolationAgentSnapshot> previous = current();
        String observed = previous.map(
                MirrorDeploymentIsolationAgentSnapshot::snapshotFingerprint).orElse("");
        long expectedGeneration = previous.map(
                MirrorDeploymentIsolationAgentSnapshot::cacheGeneration).orElse(0L) + 1L;
        if (!observed.equals(expected) || next.cacheGeneration() != expectedGeneration) {
            throw new ConcurrentCacheReplacementException();
        }
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(next);
        } catch (IOException invalid) {
            throw unavailable(invalid);
        }
        if (bytes.length == 0 || bytes.length > MAXIMUM_FILE_BYTES) {
            throw unavailable();
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory,
                    "." + path.getFileName() + ".", ".tmp");
            protect(temporary);
            try (FileChannel output = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                output.force(true);
            }
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(path)) {
                throw unavailable();
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            forceDirectory();
            return current().orElseThrow(AtomicFileMirrorDeploymentIsolationAgentCache::unavailable);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IllegalStateException(
                    "deployment isolation agent cache requires atomic rename", unsupported);
        } catch (IOException | RuntimeException failure) {
            throw unavailable(failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // A same-directory orphan cannot replace the authoritative cache path.
                }
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean durable() {
        return true;
    }

    private byte[] readBounded() throws IOException {
        try (FileChannel input = FileChannel.open(path,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(16 * 1024);
            ByteBuffer buffer = ByteBuffer.allocate(8 * 1024);
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAXIMUM_FILE_BYTES) {
                    throw unavailable();
                }
                buffer.flip();
                output.write(buffer.array(), 0, buffer.remaining());
                buffer.clear();
            }
            if (total == 0) {
                throw unavailable();
            }
            return output.toByteArray();
        }
    }

    private void protect(Path temporary) throws IOException {
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(temporary, OWNER_READ_WRITE);
        }
    }

    private void forceDirectory() throws IOException {
        try (FileChannel parent = FileChannel.open(directory, StandardOpenOption.READ)) {
            parent.force(true);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException(
                "deployment isolation agent cache is unavailable");
    }

    private static IllegalStateException unavailable(Throwable cause) {
        return new IllegalStateException(
                "deployment isolation agent cache is unavailable", cause);
    }

    /** Signals a stale writer or a non-contiguous local generation. */
    public static final class ConcurrentCacheReplacementException
            extends IllegalStateException {
        /** Creates a payload-free optimistic-concurrency failure. */
        public ConcurrentCacheReplacementException() {
            super("deployment isolation agent cache generation changed");
        }
    }
}
