package com.leanowtech.bloge.gateway.testkit;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Deep, read-only filesystem boundary for a formal evidence bundle.
 *
 * <p>The collector deliberately knows nothing about manifest semantics or replay verifiers. It
 * acquires a bounded manifest, seals an exact Evidence Root closure, and proves that the same
 * identities remain in place across compiler, replay, and final-snapshot hooks. All failures are
 * mapped to the two closed categories below; filesystem paths and business payloads are never
 * included in exception messages.</p>
 */
final class CapabilityStudioFormalEvidenceBundleCollector {
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final int STICKY_BIT = 01000;

    private CapabilityStudioFormalEvidenceBundleCollector() {
    }

    /** Closed filesystem failure categories. */
    enum FailureKind {
        INVALID,
        UNAVAILABLE
    }

    /** Payload-free failure emitted by the collector boundary. */
    static final class CollectorException extends RuntimeException {
        private final FailureKind failureKind;

        CollectorException(FailureKind failureKind) {
            super("formal evidence bundle collection failed");
            this.failureKind = failureKind;
        }

        FailureKind failureKind() {
            return failureKind;
        }

        @Override
        public String toString() {
            return "CollectorException[failureKind=" + failureKind + "]";
        }
    }

    /**
     * Mutation seams used by boundary tests and by the future facade's process observer.
     * Implementations must not be treated as an authority for evidence facts.
     */
    interface Observer {
        Observer NOOP = new Observer() {
        };

        default void manifestRead(Path manifestFile) {
        }

        default void inventorySnapshotted(
                Map<String, CapabilityStudioFormalEvidenceRunManifest.EvidenceEntry> inventory) {
        }

        /** Test-only per-file mutation seam retained for the existing verifier facade. */
        default void beforeInventoryRead(String relativePath, Path file) {
        }

        default void beforeReplay(String id, String relativePath, boolean fileSubject) {
        }

        default void beforeFinalSnapshot() {
        }
    }

    /** Immutable facts acquired from one sealed bundle snapshot. */
    record BundleObservation(
            Map<String, EntryObservation> files,
            Map<String, EntryObservation> directories,
            long evidenceByteSize) {
        BundleObservation {
            files = immutableEntries(files);
            directories = immutableEntries(directories);
        }

        private static Map<String, EntryObservation> immutableEntries(
                Map<String, EntryObservation> values) {
            Map<String, EntryObservation> copy = new TreeMap<>();
            values.forEach((path, entry) -> copy.put(path, entry));
            return Map.copyOf(copy);
        }
    }

    /** Payload-free identity and digest facts for one root entry. */
    record EntryObservation(
            String relativePath,
            long size,
            Object fileKey,
            long ownerUid,
            long groupId,
            int mode,
            long linkCount,
            long modifiedMillis,
            String rawFingerprint) {
        private boolean matches(EntryObservation actual) {
            return actual != null && fileKey.equals(actual.fileKey)
                    && size == actual.size
                    && ownerUid == actual.ownerUid
                    && groupId == actual.groupId
                    && mode == actual.mode
                    && linkCount == actual.linkCount
                    && modifiedMillis == actual.modifiedMillis;
        }

        private EntryObservation withRawFingerprint(String fingerprint) {
            return new EntryObservation(relativePath, size, fileKey, ownerUid, groupId,
                    mode, linkCount, modifiedMillis, fingerprint);
        }
    }

    /**
     * Stateful closure session. The intended caller sequence is open, compile, seal, replay, and
     * finish. State transitions are intentionally strict so a facade cannot skip a trust boundary.
     */
    static final class Session {
        private final Path manifestFile;
        private final Path root;
        private final Observer observer;
        private final AncestorChain manifestChain;
        private final ManifestSnapshot manifest;
        private AncestorChain rootChain;
        private BundleSnapshot sealedSnapshot;
        private Map<String, CapabilityStudioFormalEvidenceRunManifest.EvidenceEntry> inventory;
        private State state = State.OPEN;

        private Session(
                Path manifestFile,
                Path root,
                Observer observer,
                AncestorChain manifestChain,
                ManifestSnapshot manifest) {
            this.manifestFile = manifestFile;
            this.root = root;
            this.observer = observer;
            this.manifestChain = manifestChain;
            this.manifest = manifest;
        }

        /** Opens and reads one exact, bounded manifest without following links. */
        static Session open(Path manifestFile, Path bundleRoot, Observer observer) {
            if (manifestFile == null || bundleRoot == null || observer == null) {
                throw invalid();
            }
            requireAbsoluteNormalized(manifestFile);
            requireAbsoluteNormalized(bundleRoot);
            AncestorChain manifestChain = readAncestorChain(manifestFile, NodeType.FILE);
            long manifestOwnerUid = manifestChain.identities().getLast().ownerUid();
            validateAncestorChain(manifestChain, manifestOwnerUid);
            ManifestSnapshot manifest = readManifest(manifestFile);
            Session session = new Session(
                    manifestFile, bundleRoot, observer, manifestChain, manifest);
            session.notifyManifestRead();
            session.assertStable();
            return session;
        }

        /** Returns a defensive copy of the exact bytes acquired at open. */
        byte[] manifestBytes() {
            requireState(State.OPEN);
            return manifest.bytes().clone();
        }

        /** Closes the compiler-to-filesystem observation boundary. */
        void afterManifestCompiled() {
            requireState(State.OPEN);
            AncestorChain acquiredRoot = readAncestorChain(root, NodeType.DIRECTORY);
            long ownerUid = acquiredRoot.identities().getLast().ownerUid();
            validateAncestorChain(acquiredRoot, ownerUid);
            validateAncestorChain(manifestChain, ownerUid);
            rootChain = acquiredRoot;
            assertStable();
            state = State.COMPILED;
        }

        /** Returns the absolute normalized Evidence Root. */
        Path root() {
            return root;
        }

        /**
         * Captures and seals the exact inventory. The supplied map is copied and is only used as
         * the caller's declared metadata; all filesystem facts are acquired independently here.
         */
        void sealInventory(
                Map<String, CapabilityStudioFormalEvidenceRunManifest.EvidenceEntry>
                        declaredInventory) {
            requireState(State.COMPILED);
            if (declaredInventory == null) {
                throw invalid();
            }
            Map<String, CapabilityStudioFormalEvidenceRunManifest.EvidenceEntry> copy =
                    copyInventory(declaredInventory);
            String excludedManifest = relativeManifest();
            BundleSnapshot snapshot = enumerate(excludedManifest);
            verifyBundleSecurity(snapshot);
            if (!snapshot.files().keySet().equals(copy.keySet())) {
                throw invalid();
            }
            long total = verifyAndReadInventory(copy, snapshot, true);
            this.inventory = copy;
            this.sealedSnapshot = withRawFingerprints(snapshot, copy, total);
            notifyInventorySnapshotted(copy);
            assertStable();
            assertSnapshotStable(sealedSnapshot, true);
            state = State.SEALED;
        }

        /** Checks the manifest, ancestor chain, and subject identity before a replay begins. */
        void beforeReplay(String id, String relativePath, boolean fileSubject) {
            ensureSealed();
            if (!safeReplayId(id) || !safePath(relativePath)) {
                throw invalid();
            }
            EntryObservation subject = fileSubject
                    ? sealedSnapshot.files().get(relativePath)
                    : sealedSnapshot.directories().get(relativePath);
            if (subject == null) {
                throw invalid();
            }
            if (!fileSubject && inventory.keySet().stream()
                    .noneMatch(path -> isWithin(path, relativePath))) {
                throw invalid();
            }
            notifyBeforeReplay(id, relativePath, fileSubject);
            assertStable();
            assertEntryStable(relativePath, fileSubject);
            assertSubjectBytesStable(relativePath, fileSubject);
        }

        /** Checks the same subject and root immediately after a typed replay returns. */
        void afterReplay(String id, String relativePath, boolean fileSubject) {
            ensureSealed();
            if (!safeReplayId(id) || !safePath(relativePath)) {
                throw invalid();
            }
            if (!sealedSnapshot.contains(relativePath, fileSubject)) {
                throw invalid();
            }
            assertStable();
            assertEntryStable(relativePath, fileSubject);
            assertSubjectBytesStable(relativePath, fileSubject);
        }

        /**
         * Performs the final closure check, including hard-link scope admission. A scope admits
         * only links whose complete observed group is inside that declared directory.
         */
        void finish(Set<String> hardlinkScopes) {
            ensureSealed();
            state = State.FINISHED;
            if (hardlinkScopes == null || hardlinkScopes.stream()
                    .anyMatch(scope -> !safePath(scope)
                            || !sealedSnapshot.directories().containsKey(scope))) {
                throw invalid();
            }
            notifyBeforeFinalSnapshot();
            assertStable();
            assertHardlinkClosure(sealedSnapshot, hardlinkScopes);
            BundleSnapshot finalSnapshot = enumerate(relativeManifest());
            verifyBundleSecurity(finalSnapshot);
            if (!finalSnapshot.files().keySet().equals(inventory.keySet())
                    || !finalSnapshot.directories().keySet().equals(sealedSnapshot.directories().keySet())) {
                throw invalid();
            }
            long total = verifyAndReadInventory(inventory, finalSnapshot, false);
            BundleSnapshot finalWithRaw = withRawFingerprints(finalSnapshot, inventory, total);
            assertSnapshotEquals(sealedSnapshot, finalWithRaw);
            assertHardlinkClosure(finalWithRaw, hardlinkScopes);
            assertStable();
        }

        /** Returns the sealed observation after inventory acquisition. */
        BundleObservation observation() {
            if ((state != State.SEALED && state != State.FINISHED)
                    || sealedSnapshot == null || inventory == null) {
                throw invalid();
            }
            return sealedSnapshot.observation();
        }

        private void requireState(State expected) {
            if (state != expected) {
                throw invalid();
            }
        }

        private void ensureSealed() {
            requireState(State.SEALED);
            if (sealedSnapshot == null || inventory == null) {
                throw invalid();
            }
        }

        private void notifyManifestRead() {
            try {
                observer.manifestRead(manifestFile);
            } catch (CollectorException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw unavailable();
            }
        }

        private void notifyInventorySnapshotted(
                Map<String, CapabilityStudioFormalEvidenceRunManifest.EvidenceEntry> copy) {
            try {
                observer.inventorySnapshotted(copyInventory(copy));
            } catch (CollectorException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw unavailable();
            }
        }

        private void notifyBeforeReplay(String id, String relativePath, boolean fileSubject) {
            try {
                observer.beforeReplay(id, relativePath, fileSubject);
            } catch (CollectorException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw unavailable();
            }
        }

        private void notifyBeforeFinalSnapshot() {
            try {
                observer.beforeFinalSnapshot();
            } catch (CollectorException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw unavailable();
            }
        }

        private String relativeManifest() {
            return manifestFile.startsWith(root) ? relative(root, manifestFile) : null;
        }

        private void assertStable() {
            assertChainsStable(manifestChain);
            if (rootChain != null) {
                assertChainsStable(rootChain);
            }
            assertManifestStable(manifestFile, manifest);
        }

        private void assertEntryStable(String relativePath, boolean fileSubject) {
            Path current = root;
            StringBuilder prefix = new StringBuilder();
            String[] segments = relativePath.split("/");
            for (int index = 0; index < segments.length; index++) {
                current = current.resolve(segments[index]);
                if (!prefix.isEmpty()) {
                    prefix.append('/');
                }
                prefix.append(segments[index]);
                boolean file = fileSubject && index == segments.length - 1;
                EntryObservation expected = file
                        ? sealedSnapshot.files().get(prefix.toString())
                        : sealedSnapshot.directories().get(prefix.toString());
                if (expected == null) {
                    throw invalid();
                }
                EntryObservation actual;
                try {
                    actual = identity(current, prefix.toString());
                } catch (CollectorException failure) {
                    throw unavailable();
                }
                if (!expected.matches(actual)) {
                    throw unavailable();
                }
            }
        }

        private void assertSubjectBytesStable(String relativePath, boolean fileSubject) {
            if (fileSubject) {
                assertFileBytesStable(relativePath);
                return;
            }
            boolean observed = false;
            for (String file : sealedSnapshot.files().keySet()) {
                if (isWithin(file, relativePath)) {
                    assertFileBytesStable(file);
                    observed = true;
                }
            }
            if (!observed) {
                throw invalid();
            }
        }

        private void assertFileBytesStable(String relativePath) {
            EntryObservation expected = sealedSnapshot.files().get(relativePath);
            if (expected == null || expected.rawFingerprint() == null) {
                throw invalid();
            }
            byte[] bytes = CapabilityStudioBoundedFileReader.read(
                    root.resolve(relativePath),
                    CapabilityStudioFormalEvidenceRunManifest.MAXIMUM_EVIDENCE_FILE_BYTES);
            if (bytes == null) {
                throw unavailable();
            }
            EntryObservation actual = identity(root.resolve(relativePath), relativePath);
            if (!expected.matches(actual)) {
                throw unavailable();
            }
            if (!CapabilityStudioFormalEvidenceRunManifest.sha256(bytes)
                    .equals(expected.rawFingerprint())) {
                throw invalid();
            }
        }

        private BundleSnapshot enumerate(String excludedManifest) {
            Map<String, EntryObservation> files = new TreeMap<>();
            Map<String, EntryObservation> directories = new TreeMap<>();
            try {
                if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(root)) {
                    throw invalid();
                }
                Files.walkFileTree(root, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory,
                            BasicFileAttributes ignored) {
                        if (Files.isSymbolicLink(directory)) {
                            throw invalid();
                        }
                        String relative = relative(root, directory);
                        directories.put(relative, identity(directory, relative));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String relative = relative(root, file);
                        if (relative.equals(excludedManifest)) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (Files.isSymbolicLink(file) || !attrs.isRegularFile()) {
                            throw invalid();
                        }
                        files.put(relative, identity(file, relative));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException failure) {
                        throw unavailable();
                    }
                });
            } catch (CollectorException failure) {
                throw failure;
            } catch (IOException failure) {
                throw unavailable();
            } catch (RuntimeException failure) {
                if (failure instanceof CollectorException collectorFailure) {
                    throw collectorFailure;
                }
                throw unavailable();
            }
            for (String directory : directories.keySet()) {
                if (directory.isEmpty() || files.keySet().stream().anyMatch(path -> isWithin(path, directory))) {
                    continue;
                }
                throw invalid();
            }
            return new BundleSnapshot(files, directories, 0);
        }

        private long verifyAndReadInventory(
                Map<String, CapabilityStudioFormalEvidenceRunManifest.EvidenceEntry> declared,
                BundleSnapshot snapshot,
                boolean notifyObserver) {
            long total = 0;
            for (Map.Entry<String, CapabilityStudioFormalEvidenceRunManifest.EvidenceEntry>
                    item : declared.entrySet()) {
                EntryObservation expected = snapshot.files().get(item.getKey());
                if (expected == null) {
                    throw invalid();
                }
                CapabilityStudioFormalEvidenceRunManifest.EvidenceEntry entry = item.getValue();
                if (entry == null || !item.getKey().equals(entry.relativePath())) {
                    throw invalid();
                }
                long declaredSize = entry.byteSize();
                String declaredFingerprint = entry.rawFingerprint();
                if (declaredSize < 0
                        || declaredSize > CapabilityStudioFormalEvidenceRunManifest.MAXIMUM_EVIDENCE_FILE_BYTES
                        || !FINGERPRINT.matcher(declaredFingerprint).matches()
                        || total > CapabilityStudioFormalEvidenceRunManifest.MAXIMUM_EVIDENCE_BYTES - declaredSize) {
                    throw invalid();
                }
                if (notifyObserver) {
                    notifyBeforeInventoryRead(item.getKey());
                }
                byte[] bytes = CapabilityStudioBoundedFileReader.read(
                        root.resolve(item.getKey()),
                        CapabilityStudioFormalEvidenceRunManifest.MAXIMUM_EVIDENCE_FILE_BYTES);
                if (bytes == null) {
                    throw unavailable();
                }
                EntryObservation current = identity(root.resolve(item.getKey()), item.getKey());
                if (!expected.matches(current)) {
                    throw unavailable();
                }
                if (bytes.length != declaredSize
                        || !CapabilityStudioFormalEvidenceRunManifest.sha256(bytes)
                        .equals(declaredFingerprint)) {
                    throw invalid();
                }
                total += declaredSize;
            }
            return total;
        }

        private void assertSnapshotStable(BundleSnapshot expected, boolean compareBytes) {
            BundleSnapshot current = enumerate(relativeManifest());
            if (!current.files().keySet().equals(expected.files().keySet())
                    || !current.directories().keySet().equals(expected.directories().keySet())) {
                throw invalid();
            }
            for (Map.Entry<String, EntryObservation> entry : expected.files().entrySet()) {
                EntryObservation actual = current.files().get(entry.getKey());
                if (!entry.getValue().matches(actual)) {
                    throw unavailable();
                }
                if (compareBytes) {
                    byte[] bytes = CapabilityStudioBoundedFileReader.read(
                            root.resolve(entry.getKey()),
                            CapabilityStudioFormalEvidenceRunManifest.MAXIMUM_EVIDENCE_FILE_BYTES);
                    if (bytes == null) {
                        throw unavailable();
                    }
                    if (entry.getValue().rawFingerprint() == null
                            || !CapabilityStudioFormalEvidenceRunManifest.sha256(bytes)
                            .equals(entry.getValue().rawFingerprint())) {
                        throw invalid();
                    }
                }
            }
        }

        private void assertSnapshotEquals(BundleSnapshot expected, BundleSnapshot actual) {
            if (!expected.files().equals(actual.files())
                    || !expected.directories().equals(actual.directories())) {
                throw unavailable();
            }
        }

        private static BundleSnapshot withRawFingerprints(
                BundleSnapshot snapshot,
                Map<String, CapabilityStudioFormalEvidenceRunManifest.EvidenceEntry> declared,
                long total) {
            Map<String, EntryObservation> files = new TreeMap<>();
            for (Map.Entry<String, EntryObservation> entry : snapshot.files().entrySet()) {
                String fingerprint = declared.get(entry.getKey()).rawFingerprint();
                files.put(entry.getKey(), entry.getValue().withRawFingerprint(fingerprint));
            }
            return new BundleSnapshot(files, snapshot.directories(), total);
        }

        private void notifyBeforeInventoryRead(String relativePath) {
            try {
                observer.beforeInventoryRead(relativePath, root.resolve(relativePath));
            } catch (CollectorException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw unavailable();
            }
        }
    }

    private static Map<String, CapabilityStudioFormalEvidenceRunManifest.EvidenceEntry>
            copyInventory(
                    Map<String, CapabilityStudioFormalEvidenceRunManifest.EvidenceEntry>
                            declared) {
        if (declared.size() > CapabilityStudioFormalEvidenceRunManifest.MAXIMUM_EVIDENCE_COUNT) {
            throw invalid();
        }
        Map<String, CapabilityStudioFormalEvidenceRunManifest.EvidenceEntry> copy =
                new TreeMap<>();
        declared.forEach((path, value) -> {
            String declaredPath = value == null ? null : value.relativePath();
            if (!safePath(path) || !path.equals(declaredPath)
                    || value == null || copy.put(path, value) != null) {
                throw invalid();
            }
        });
        return Map.copyOf(copy);
    }

    private static ManifestSnapshot readManifest(Path path) {
        EntryObservation identity = identity(path, "manifest");
        if (!secureFile(identity) || identity.linkCount() != 1
                || identity.size() > CapabilityStudioFormalEvidenceRunManifest.MAXIMUM_MANIFEST_BYTES) {
            throw invalid();
        }
        byte[] bytes = CapabilityStudioBoundedFileReader.read(
                path, CapabilityStudioFormalEvidenceRunManifest.MAXIMUM_MANIFEST_BYTES);
        if (bytes == null) {
            throw unavailable();
        }
        EntryObservation after = identity(path, "manifest");
        if (!identity.matches(after)) {
            throw unavailable();
        }
        return new ManifestSnapshot(identity, bytes);
    }

    private static void verifyBundleSecurity(BundleSnapshot snapshot) {
        EntryObservation root = snapshot.directories().get("");
        if (root == null || !secureDirectory(root)) {
            throw invalid();
        }
        long ownerUid = root.ownerUid();
        for (EntryObservation directory : snapshot.directories().values()) {
            if (directory.ownerUid() != ownerUid || !secureDirectory(directory)) {
                throw invalid();
            }
        }
        for (EntryObservation file : snapshot.files().values()) {
            if (file.ownerUid() != ownerUid || !secureFile(file)) {
                throw invalid();
            }
        }
    }

    private static boolean secureDirectory(EntryObservation identity) {
        int permissions = identity.mode() & 0777;
        return (permissions & 0077) == 0 && (permissions & 0500) == 0500;
    }

    private static boolean secureFile(EntryObservation identity) {
        int permissions = identity.mode() & 0777;
        return (permissions & 0077) == 0 && (permissions & 0400) == 0400;
    }

    private static void assertHardlinkClosure(
            BundleSnapshot snapshot, Set<String> hardlinkScopes) {
        Map<Object, Set<String>> pathsByKey = new HashMap<>();
        for (Map.Entry<String, EntryObservation> entry : snapshot.files().entrySet()) {
            pathsByKey.computeIfAbsent(entry.getValue().fileKey(), ignored -> new HashSet<>())
                    .add(entry.getKey());
        }
        for (Map.Entry<Object, Set<String>> group : pathsByKey.entrySet()) {
            Set<String> paths = group.getValue();
            long linkCount = snapshot.files().get(paths.iterator().next()).linkCount();
            if (linkCount != paths.size()
                    || paths.stream().anyMatch(path -> snapshot.files().get(path).linkCount() != linkCount)) {
                throw invalid();
            }
            if (linkCount > 1 && hardlinkScopes.stream().noneMatch(scope ->
                    paths.stream().allMatch(path -> isWithin(path, scope)))) {
                throw invalid();
            }
        }
    }

    private static EntryObservation identity(Path path, String relativePath) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || (!attrs.isRegularFile() && !attrs.isDirectory())
                    || attrs.fileKey() == null) {
                throw invalid();
            }
            Map<String, Object> unix = Files.readAttributes(
                    path, "unix:uid,gid,mode,nlink", LinkOption.NOFOLLOW_LINKS);
            return new EntryObservation(relativePath, attrs.size(), attrs.fileKey(),
                    ((Number) unix.get("uid")).longValue(),
                    ((Number) unix.get("gid")).longValue(),
                    ((Number) unix.get("mode")).intValue(),
                    ((Number) unix.get("nlink")).longValue(),
                    attrs.lastModifiedTime().toMillis(), null);
        } catch (CollectorException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw unavailable();
        }
    }

    private static AncestorChain readAncestorChain(Path target, NodeType targetType) {
        List<AncestorIdentity> identities = new ArrayList<>();
        Path current = target.getRoot();
        if (current == null) {
            throw invalid();
        }
        identities.add(ancestorIdentity(current,
                current.equals(target) ? targetType : NodeType.DIRECTORY));
        for (Path component : target) {
            current = current.resolve(component);
            identities.add(ancestorIdentity(current,
                    current.equals(target) ? targetType : NodeType.DIRECTORY));
        }
        return new AncestorChain(target, targetType, List.copyOf(identities));
    }

    private static AncestorIdentity ancestorIdentity(Path path, NodeType expectedType) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            NodeType actual = attrs.isDirectory() ? NodeType.DIRECTORY
                    : attrs.isRegularFile() ? NodeType.FILE : null;
            if (attrs.isSymbolicLink() || actual != expectedType || attrs.fileKey() == null) {
                throw invalid();
            }
            Map<String, Object> unix = Files.readAttributes(
                    path, "unix:uid,mode,nlink", LinkOption.NOFOLLOW_LINKS);
            return new AncestorIdentity(path, actual, attrs.fileKey(),
                    ((Number) unix.get("uid")).longValue(),
                    ((Number) unix.get("mode")).intValue(),
                    ((Number) unix.get("nlink")).longValue());
        } catch (CollectorException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw unavailable();
        }
    }

    private static void validateAncestorChain(AncestorChain chain, long bundleOwnerUid) {
        List<AncestorIdentity> identities = chain.identities();
        for (int index = 0; index < identities.size(); index++) {
            AncestorIdentity identity = identities.get(index);
            if (!allowedOwner(identity.ownerUid(), bundleOwnerUid)) {
                throw invalid();
            }
            int permissions = identity.mode() & 07777;
            if ((permissions & 0022) == 0) {
                continue;
            }
            if (identity.type() != NodeType.DIRECTORY || (permissions & STICKY_BIT) == 0
                    || index + 1 >= identities.size()
                    || !allowedOwner(identities.get(index + 1).ownerUid(), bundleOwnerUid)) {
                throw invalid();
            }
        }
    }

    private static boolean allowedOwner(long uid, long bundleOwnerUid) {
        return uid == 0 || uid == bundleOwnerUid;
    }

    private static void assertChainsStable(AncestorChain expected) {
        AncestorChain current;
        try {
            current = readAncestorChain(expected.target(), expected.targetType());
        } catch (CollectorException failure) {
            throw unavailable();
        }
        if (!expected.equals(current)) {
            throw unavailable();
        }
    }

    private static void assertManifestStable(Path path, ManifestSnapshot expected) {
        EntryObservation current;
        try {
            current = identity(path, "manifest");
        } catch (CollectorException failure) {
            throw unavailable();
        }
        if (!expected.identity().matches(current)) {
            throw unavailable();
        }
        byte[] bytes = CapabilityStudioBoundedFileReader.read(
                path, CapabilityStudioFormalEvidenceRunManifest.MAXIMUM_MANIFEST_BYTES);
        if (bytes == null) {
            throw unavailable();
        }
        if (!Arrays.equals(expected.bytes(), bytes)) {
            throw invalid();
        }
    }

    private static void requireAbsoluteNormalized(Path path) {
        if (!path.isAbsolute() || !path.equals(path.normalize())) {
            throw invalid();
        }
    }

    private static boolean safeReplayId(String value) {
        return value != null && value.matches("[a-z0-9][a-z0-9-]{0,127}");
    }

    private static boolean safePath(String value) {
        if (value == null || value.isEmpty() || value.startsWith("/") || value.endsWith("/")) {
            return false;
        }
        for (String segment : value.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)
                    || !SAFE_SEGMENT.matcher(segment).matches()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWithin(String path, String directory) {
        return !directory.isEmpty() && (path.equals(directory) || path.startsWith(directory + "/"));
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    private static CollectorException invalid() {
        return new CollectorException(FailureKind.INVALID);
    }

    private static CollectorException unavailable() {
        return new CollectorException(FailureKind.UNAVAILABLE);
    }

    private enum NodeType {
        FILE,
        DIRECTORY
    }

    private enum State {
        OPEN,
        COMPILED,
        SEALED,
        FINISHED
    }

    private record ManifestSnapshot(EntryObservation identity, byte[] bytes) {
        private ManifestSnapshot {
            bytes = bytes.clone();
        }
    }

    private record BundleSnapshot(
            Map<String, EntryObservation> files,
            Map<String, EntryObservation> directories,
            long evidenceByteSize) {
        private BundleSnapshot {
            files = Map.copyOf(new TreeMap<>(files));
            directories = Map.copyOf(new TreeMap<>(directories));
        }

        private boolean contains(String relativePath, boolean fileSubject) {
            return (fileSubject ? files : directories).containsKey(relativePath);
        }

        private BundleObservation observation() {
            return new BundleObservation(files, directories, evidenceByteSize);
        }
    }

    private record AncestorIdentity(
            Path path, NodeType type, Object fileKey, long ownerUid, int mode, long linkCount) {
    }

    private record AncestorChain(
            Path target, NodeType targetType, List<AncestorIdentity> identities) {
    }

}
