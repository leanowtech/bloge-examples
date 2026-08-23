package com.leanowtech.bloge.gateway.testkit.ept;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Immutable snapshot of a file-tree for evidence capture and verification.
 *
 * <p>Two operations:</p>
 * <ul>
 *   <li>{@link #create(Path, Path)} — copy source to a fresh target and snapshot it</li>
 *   <li>{@link #inspect(Path)} — read-only snapshot of an existing target</li>
 * </ul>
 *
 * <p>Invariant: {@code create(s, t).equals(inspect(t))} when source s is regular-files-only
 * and target t is not concurrently modified.</p>
 *
 * <p>Limits: 512 entries max, 32 MiB total, enforced on both create and inspect.</p>
 *
 * <p>Path rules: relative paths use forward slashes, no empty segments, no dot segments,
 * no backslashes, no control chars. Each path segment limited to 255 chars,
 * depth limited to 32 levels.</p>
 *
 * <p>Symlink policy: both create and inspect use NOFOLLOW and stably reject symlinks
 * at any depth in source/target. External symlinks (pointing outside the root) are
 * rejected by the normalised-path startsWith check.</p>
 */
final class EvidenceSnapshot {

    // ---------------------------------------------------------------------------
    // Public package constants (for EPT use)
    // ---------------------------------------------------------------------------

    /** Domain for treeFingerprint computation. */
    public static final String SNAPSHOT_DOMAIN =
            "com.leanowtech.bloge.gateway.testkit.ept.evidence-snapshot.v1";

    /** Maximum entry count (inclusive). */
    public static final int MAX_ENTRIES = 512;

    /** Maximum total bytes (inclusive). */
    public static final long MAX_BYTES = 32L * 1024 * 1024;

    /** Maximum path segment length (inclusive). */
    public static final int MAX_SEGMENT_LENGTH = 255;

    /** Maximum path depth (inclusive). */
    public static final int MAX_DEPTH = 32;

    // ---------------------------------------------------------------------------
    // Snapshot record
    // ---------------------------------------------------------------------------

    /**
     * Immutable snapshot of a file tree.
     *
     * @param entries tree entries sorted by UTF-8 relative path (forward slashes)
     * @param totalBytes sum of all entry sizes
     * @param treeFingerprint SHA-256 domain fingerprint of all entries
     */
    public record Snapshot(
            List<Entry> entries,
            long totalBytes,
            String treeFingerprint) {

        public Snapshot {
            Objects.requireNonNull(entries, "entries");
            Objects.requireNonNull(treeFingerprint, "treeFingerprint");
            entries = List.copyOf(entries);
        }

        /**
         * Single file-system entry within a snapshot.
         *
         * @param relativePath forward-slash relative path, no empty/dot segments
         * @param size file size in bytes
         * @param rawFingerprint SHA-256 "sha256:hex64" of raw content
         */
        public record Entry(
                String relativePath,
                long size,
                String rawFingerprint) {

            public Entry {
                Objects.requireNonNull(relativePath, "relativePath");
                Objects.requireNonNull(rawFingerprint, "rawFingerprint");
                if (size < 0) throw new IllegalArgumentException("size must be >= 0");
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Exception
    // ---------------------------------------------------------------------------

    /**
     * Snapshot operation failure with a stable machine-readable code.
     */
    public static final class SnapshotException extends IOException {

        private static final long serialVersionUID = 1L;

        /** Stable failure code. */
        private final String code;

        public SnapshotException(String code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        public SnapshotException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = Objects.requireNonNull(code, "code");
        }

        /** @return stable failure code */
        public String code() {
            return code;
        }

        // Public stable codes
        public static final String SOURCE_SYMLINK = "SOURCE_SYMLINK";
        public static final String SOURCE_NOT_REGULAR = "SOURCE_NOT_REGULAR";
        public static final String PATH_INVALID = "PATH_INVALID";
        public static final String ENTRY_LIMIT_EXCEEDED = "ENTRY_LIMIT_EXCEEDED";
        public static final String BYTE_LIMIT_EXCEEDED = "BYTE_LIMIT_EXCEEDED";
        public static final String TARGET_EXISTS = "TARGET_EXISTS";
        public static final String SNAPSHOT_IO_ERROR = "SNAPSHOT_IO_ERROR";
    }

    // ---------------------------------------------------------------------------
    // Private constants
    // ---------------------------------------------------------------------------

    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_FILE =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private static final Pattern RELATIVE_PATH_PATTERN =
            Pattern.compile("^[^/]+(/[^/]+)*$"); // forward-slash only, no empty segments

    /** Intermediate source file data. */
    private record SourceEntry(String relativePath, byte[] content) {}

    // ---------------------------------------------------------------------------
    // Public static API: create
    // ---------------------------------------------------------------------------

    /**
     * Copies source tree to a fresh target directory and returns its snapshot.
     *
     * <p>Target must not exist. Permissions are set to 0700 for directories and 0600
     * for files. All regular-file data is forced to storage via
     * {@code FileChannel.force(true)} after write.</p>
     *
     * <p>Cleanup (best-effort) is performed on failure; any cleanup failure is attached
     * as a suppressed exception to the thrown SnapshotException without leaking paths.</p>
     *
     * @param sourceRoot root of the source tree (must contain only regular files)
     * @param targetRoot fresh directory to populate
     * @return snapshot of the copied target
     * @throws SnapshotException on precondition failure, I/O error, or limit violation
     */
    public static Snapshot create(Path sourceRoot, Path targetRoot) throws SnapshotException {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(targetRoot, "targetRoot");

        // Atomic creation: parent must exist, then createDirectory fails on anything already there.
        // Files.createDirectory rejects files, directories, and symlinks atomically (no TOCTOU).
        Path canonicalTarget = targetRoot.toAbsolutePath().normalize();
        try {
            Files.createDirectories(canonicalTarget.getParent());
        } catch (IOException parentCreateEx) {
            SnapshotException se = new SnapshotException(
                    SnapshotException.SNAPSHOT_IO_ERROR,
                    "failed to ensure parent of target root exists", parentCreateEx);
            throw attachCleanup(se, canonicalTarget);
        }

        try {
            Files.createDirectory(canonicalTarget,
                    PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY));
        } catch (FileAlreadyExistsException _) {
            throw new SnapshotException(
                    SnapshotException.TARGET_EXISTS,
                    "target already exists");
        } catch (IOException e) {
            SnapshotException se = new SnapshotException(
                    SnapshotException.SNAPSHOT_IO_ERROR,
                    "failed to create target root", e);
            throw attachCleanup(se, canonicalTarget);
        }

        // Walk source and collect file data (NOFOLLOW)
        Path root = sourceRoot.toAbsolutePath().normalize();
        List<SourceEntry> sourceEntries = new ArrayList<>();
        AtomicLong totalBytes = new AtomicLong(0);
        AtomicInteger entryCount = new AtomicInteger(0);

        try {
            Files.walkFileTree(root, Set.of(), Integer.MAX_VALUE,
                    new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    if (attrs.isSymbolicLink()) {
                        throw new SnapshotException(
                                SnapshotException.SOURCE_SYMLINK,
                                "source contains symlink");
                    }
                    if (!attrs.isDirectory()) {
                        throw new SnapshotException(
                                SnapshotException.SOURCE_NOT_REGULAR,
                                "source entry is not directory or regular file");
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    if (attrs.isSymbolicLink()) {
                        throw new SnapshotException(
                                SnapshotException.SOURCE_SYMLINK,
                                "source contains symlink");
                    }
                    if (!attrs.isRegularFile()) {
                        throw new SnapshotException(
                                SnapshotException.SOURCE_NOT_REGULAR,
                                "source entry is not regular file");
                    }

                    int count = entryCount.incrementAndGet();
                    if (count > MAX_ENTRIES) {
                        throw new SnapshotException(
                                SnapshotException.ENTRY_LIMIT_EXCEEDED,
                                "entry count exceeds limit");
                    }

                    Path rel = root.relativize(file);
                    String rawRelPath = rel.toString();
                    validateRelativePath(rawRelPath); // reject backslash before normalization
                    String relStr = rawRelPath.replace('\\', '/');

                    long size = attrs.size();
                    long newTotal = totalBytes.addAndGet(size);
                    if (newTotal > MAX_BYTES) {
                        throw new SnapshotException(
                                SnapshotException.BYTE_LIMIT_EXCEEDED,
                                "total bytes exceed limit");
                    }

                    // Read content for hashing
                    byte[] content;
                    try (InputStream in = Files.newInputStream(file)) {
                        content = in.readAllBytes();
                    }
                    if (content.length != size) {
                        throw new SnapshotException(
                                SnapshotException.SNAPSHOT_IO_ERROR,
                                "size mismatch during read");
                    }

                    sourceEntries.add(new SourceEntry(relStr, content));

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (SnapshotException e) {
            throw attachCleanup(e, canonicalTarget);
        } catch (IOException e) {
            SnapshotException se = new SnapshotException(
                    SnapshotException.SNAPSHOT_IO_ERROR,
                    "I/O error during source walk", e);
            throw attachCleanup(se, canonicalTarget);
        }

        // List of canonical target dirs for depth-ordered force (deepest first)
        List<Path> createdDirs = new ArrayList<>();
        createdDirs.add(canonicalTarget);

        // Copy to target with CREATE_NEW (rejects existing / symlink) and explicit path containment
        for (SourceEntry src : sourceEntries) {
            Path targetFile = canonicalTarget.resolve(src.relativePath.replace('/', File.separatorChar));

            // Explicit normalised-path containment check
            Path normalised = targetFile.toAbsolutePath().normalize();
            if (!normalised.startsWith(canonicalTarget)) {
                SnapshotException se = new SnapshotException(
                        SnapshotException.SNAPSHOT_IO_ERROR,
                        "path escapes target root");
                throw attachCleanup(se, canonicalTarget);
            }

            // Create parent dirs (if absent)
            Path parent = normalised.getParent();
            if (parent != null && !parent.equals(canonicalTarget)) {
                // Build the full parent chain and create each missing directory
                List<Path> parentChain = new ArrayList<>();
                Path cur = parent;
                while (cur != null && !cur.equals(canonicalTarget)) {
                    parentChain.add(cur);
                    cur = cur.getParent();
                }
                Collections.reverse(parentChain);
                for (Path p : parentChain) {
                    if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                        try {
                            Files.createDirectory(p,
                                    PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY));
                            createdDirs.add(p);
                        } catch (FileAlreadyExistsException _) {
                            // Already there — ok, but must be a regular dir (not symlink)
                            if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                                SnapshotException se = new SnapshotException(
                                        SnapshotException.SNAPSHOT_IO_ERROR,
                                        "parent path is not a directory");
                                throw attachCleanup(se, canonicalTarget);
                            }
                        } catch (IOException e) {
                            SnapshotException se = new SnapshotException(
                                    SnapshotException.SNAPSHOT_IO_ERROR,
                                    "failed to create parent directory", e);
                            throw attachCleanup(se, canonicalTarget);
                        }
                    }
                }
            }

            // Write with CREATE_NEW + WRITE: rejects existing file or symlink
            try (FileChannel fc = FileChannel.open(targetFile,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) {
                fc.write(java.nio.ByteBuffer.wrap(src.content));
                fc.force(true);
            } catch (FileAlreadyExistsException _) {
                SnapshotException se = new SnapshotException(
                        SnapshotException.SNAPSHOT_IO_ERROR,
                        "target file already exists or is a symlink");
                throw attachCleanup(se, canonicalTarget);
            } catch (IOException e) {
                SnapshotException se = new SnapshotException(
                        SnapshotException.SNAPSHOT_IO_ERROR,
                        "failed to write target file", e);
                throw attachCleanup(se, canonicalTarget);
            }

            // Set 0600 (frozen design)
            try {
                Files.setPosixFilePermissions(targetFile, PRIVATE_FILE);
            } catch (IOException e) {
                SnapshotException se = new SnapshotException(
                        SnapshotException.SNAPSHOT_IO_ERROR,
                        "failed to set file permissions", e);
                throw attachCleanup(se, canonicalTarget);
            }
        }

        // Force all directories from deepest to shallowest (to flush parent metadata)
        for (int i = createdDirs.size() - 1; i >= 0; i--) {
            Path dir = createdDirs.get(i);
            try (FileChannel fc = FileChannel.open(dir, StandardOpenOption.READ)) {
                fc.force(true);
            } catch (ClosedChannelException _) {
                // already closed — ignore
            } catch (IOException e) {
                SnapshotException se = new SnapshotException(
                        SnapshotException.SNAPSHOT_IO_ERROR,
                        "failed to force directory to storage", e);
                throw attachCleanup(se, canonicalTarget);
            }
        }

        // Re-read from target to compute fingerprints
        List<Snapshot.Entry> entries = new ArrayList<>(sourceEntries.size());
        for (SourceEntry src : sourceEntries) {
            Path targetFile = canonicalTarget.resolve(src.relativePath.replace('/', File.separatorChar));
            try {
                byte[] content = Files.readAllBytes(targetFile);
                String fp = sha256(content);
                entries.add(new Snapshot.Entry(src.relativePath, content.length, fp));
            } catch (IOException e) {
                SnapshotException se = new SnapshotException(
                        SnapshotException.SNAPSHOT_IO_ERROR,
                        "failed to read target file during fingerprint", e);
                throw attachCleanup(se, canonicalTarget);
            }
        }

        // Sort entries by UTF-8 path
        entries.sort((a, b) -> {
            byte[] aBytes = a.relativePath().getBytes(StandardCharsets.UTF_8);
            byte[] bBytes = b.relativePath().getBytes(StandardCharsets.UTF_8);
            return Arrays.compareUnsigned(aBytes, bBytes);
        });

        String treeFingerprint = computeTreeFingerprint(entries);

        return new Snapshot(entries, totalBytes.get(), treeFingerprint);
    }

    // ---------------------------------------------------------------------------
    // Static factory: inspect
    // ---------------------------------------------------------------------------

    /**
     * Returns a read-only snapshot of an existing target directory.
     *
     * <p>Uses identical rules, limits, and sorting as {@link #create(Path, Path)}.
     * This operation performs zero writes and does not change mtime or permissions.</p>
     *
     * <p>Uses NOFOLLOW; symlinks in the target are stably rejected.</p>
     *
     * @param targetRoot existing directory to inspect
     * @return immutable snapshot
     * @throws SnapshotException on I/O errors or limit violations
     */
    public static Snapshot inspect(Path targetRoot) throws SnapshotException {
        Objects.requireNonNull(targetRoot, "targetRoot");

        Path canonicalRoot = targetRoot.toAbsolutePath().normalize();

        // NOFOLLOW existence and directory check via readAttributes
        try {
            BasicFileAttributes attrs =
                    Files.readAttributes(canonicalRoot, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isDirectory()) {
                throw new SnapshotException(
                        SnapshotException.SNAPSHOT_IO_ERROR,
                        "target is not a directory");
            }
        } catch (IOException e) {
            throw new SnapshotException(
                    SnapshotException.SNAPSHOT_IO_ERROR,
                    "target does not exist or is not accessible");
        }

        List<Snapshot.Entry> entries = new ArrayList<>();
        AtomicLong totalBytes = new AtomicLong(0);
        AtomicInteger entryCount = new AtomicInteger(0);

        try {
            // NOFOLLOW (Set.of() = no options = no FOLLOW_LINKS)
            Files.walkFileTree(canonicalRoot, Set.of(), Integer.MAX_VALUE,
                    new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    if (attrs.isSymbolicLink()) {
                        throw new SnapshotException(
                                SnapshotException.SOURCE_SYMLINK,
                                "target contains symlink");
                    }
                    if (!attrs.isDirectory()) {
                        throw new SnapshotException(
                                SnapshotException.SOURCE_NOT_REGULAR,
                                "target contains non-directory entry");
                    }
                    // Explicit path containment: normalised path must start with canonical root
                    Path normalised = dir.toAbsolutePath().normalize();
                    if (!normalised.startsWith(canonicalRoot)) {
                        throw new SnapshotException(
                                SnapshotException.SOURCE_SYMLINK,
                                "target contains path escaping root");
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    if (attrs.isSymbolicLink()) {
                        throw new SnapshotException(
                                SnapshotException.SOURCE_SYMLINK,
                                "target contains symlink");
                    }
                    if (!attrs.isRegularFile()) {
                        throw new SnapshotException(
                                SnapshotException.SOURCE_NOT_REGULAR,
                                "target contains non-regular file");
                    }
                    // Explicit path containment check
                    Path normalised = file.toAbsolutePath().normalize();
                    if (!normalised.startsWith(canonicalRoot)) {
                        throw new SnapshotException(
                                SnapshotException.SOURCE_SYMLINK,
                                "target contains path escaping root");
                    }

                    int count = entryCount.incrementAndGet();
                    if (count > MAX_ENTRIES) {
                        throw new SnapshotException(
                                SnapshotException.ENTRY_LIMIT_EXCEEDED,
                                "entry count exceeds limit");
                    }

                    Path rel = canonicalRoot.relativize(file);
                    String relStr = rel.toString().replace('\\', '/');
                    validateRelativePath(relStr);

                    long size = attrs.size();
                    long newTotal = totalBytes.addAndGet(size);
                    if (newTotal > MAX_BYTES) {
                        throw new SnapshotException(
                                SnapshotException.BYTE_LIMIT_EXCEEDED,
                                "total bytes exceed limit");
                    }

                    byte[] content = Files.readAllBytes(file);
                    if (content.length != size) {
                        throw new SnapshotException(
                                SnapshotException.SNAPSHOT_IO_ERROR,
                                "size mismatch during inspect");
                    }

                    String fp = sha256(content);
                    entries.add(new Snapshot.Entry(relStr, size, fp));

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (SnapshotException e) {
            throw e;
        } catch (IOException e) {
            throw new SnapshotException(
                    SnapshotException.SNAPSHOT_IO_ERROR,
                    "I/O error during inspect", e);
        }

        // Sort entries by UTF-8 path
        entries.sort((a, b) -> {
            byte[] aBytes = a.relativePath().getBytes(StandardCharsets.UTF_8);
            byte[] bBytes = b.relativePath().getBytes(StandardCharsets.UTF_8);
            return Arrays.compareUnsigned(aBytes, bBytes);
        });

        String treeFingerprint = computeTreeFingerprint(entries);

        return new Snapshot(entries, totalBytes.get(), treeFingerprint);
    }

    // ---------------------------------------------------------------------------
    // Public helper (package-private): compute tree fingerprint
    // ---------------------------------------------------------------------------

    /**
     * Computes tree fingerprint from sorted entries.
     *
     * <p>Domain-framed length-prefixed framing:</p>
     * <pre>
     * treeFingerprint = SHA256(
     *     lp(SNAPSHOT_DOMAIN)
     *   || lp(entry[0].relativePath) || lp(entry[0].size) || lp(entry[0].rawFingerprint)
     *   || ...
     * )
     * </pre>
     *
     * @param entries sorted entries
     * @return SHA-256 "sha256:hex64"
     */
    public static String computeTreeFingerprint(List<Snapshot.Entry> entries) {
        Objects.requireNonNull(entries, "entries");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            digestLp(md, SNAPSHOT_DOMAIN);
            for (Snapshot.Entry e : entries) {
                digestLp(md, e.relativePath());
                digestLp(md, String.valueOf(e.size()));
                digestLp(md, e.rawFingerprint());
            }
            return "sha256:" + bytesToHex(md.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError("SHA-256 not available", ex);
        }
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    private static void validateRelativePath(String relPath) throws SnapshotException {
        if (relPath == null || relPath.isEmpty()) {
            throw new SnapshotException(SnapshotException.PATH_INVALID, "empty path");
        }
        // No backslashes
        if (relPath.indexOf('\\') >= 0) {
            throw new SnapshotException(SnapshotException.PATH_INVALID, "path contains backslash");
        }
        // No ./ or ../
        if (relPath.contains("/./") || relPath.startsWith("./") || relPath.endsWith("/.")
                || relPath.contains("/../") || relPath.startsWith("../") || relPath.endsWith("/..")
                || relPath.equals(".") || relPath.equals("..")) {
            throw new SnapshotException(SnapshotException.PATH_INVALID, "path contains dot segment");
        }
        // No control chars
        for (int i = 0; i < relPath.length(); i++) {
            char c = relPath.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new SnapshotException(SnapshotException.PATH_INVALID,
                        "path contains control character");
            }
        }
        // Check segment length
        int maxSegLen = 0;
        int segStart = 0;
        for (int i = 0; i <= relPath.length(); i++) {
            if (i == relPath.length() || relPath.charAt(i) == '/') {
                int segLen = i - segStart;
                if (segLen > maxSegLen) maxSegLen = segLen;
                segStart = i + 1;
            }
        }
        if (maxSegLen > MAX_SEGMENT_LENGTH) {
            throw new SnapshotException(SnapshotException.PATH_INVALID,
                    "path segment exceeds length limit");
        }
        // Check depth
        int depth = countSegments(relPath);
        if (depth > MAX_DEPTH) {
            throw new SnapshotException(SnapshotException.PATH_INVALID,
                    "path depth exceeds limit");
        }
        // Pattern check: forward-slash only, no empty segments
        if (!RELATIVE_PATH_PATTERN.matcher(relPath).matches()) {
            throw new SnapshotException(SnapshotException.PATH_INVALID,
                    "path format invalid");
        }
    }

    private static int countSegments(String path) {
        if (path == null || path.isEmpty()) return 0;
        int count = 1;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') count++;
        }
        return count;
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return "sha256:" + bytesToHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static void digestLp(MessageDigest md, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        // 4-byte big-endian length prefix
        md.update((byte) ((bytes.length >>> 24) & 0xFF));
        md.update((byte) ((bytes.length >>> 16) & 0xFF));
        md.update((byte) ((bytes.length >>> 8) & 0xFF));
        md.update((byte) (bytes.length & 0xFF));
        md.update(bytes);
    }

    /**
     * Best-effort recursive delete; any failure is suppressed and attached as
     * a suppressed exception to the supplied primary SnapshotException.
     * No payload or path information is leaked in the suppressed exception.
     */
    private static SnapshotException attachCleanup(SnapshotException primary, Path target) {
        SnapshotException cleanupEx = new SnapshotException(
                SnapshotException.SNAPSHOT_IO_ERROR,
                "cleanup failed");
        deleteRecursively(target);
        primary.addSuppressed(cleanupEx);
        return primary;
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Files.walkFileTree(path, Set.of(), Integer.MAX_VALUE,
                    new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try { Files.deleteIfExists(file); } catch (IOException ignored) {}
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    try { Files.deleteIfExists(dir); } catch (IOException ignored) {}
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Best-effort
        }
    }

    private EvidenceSnapshot() {} // sealed
}
