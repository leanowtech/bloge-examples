package com.leanowtech.bloge.gateway.testkit.mounted;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.AdmissionLifecycleMaterial;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.AdmissionLifecycleRequest;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.AtomicAdmissionLifecycleCommitReceipt;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.DeploymentAuthorityDecision;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.DeploymentUnavailableException;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseCommitResult;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseReceipt;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseRequest;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.RevocationAuthoritySnapshot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/** Package-private durable reference lifecycle and execution-lease authority. */
final class FilesystemDeploymentAdmissionAuthority {
    static final String STATE_FILE = "execution-lease-state-v2.json";
    static final String CHECKPOINT_FILE = "execution-lease-checkpoint-v1.json";
    static final String REVOCATION_HEAD_FILE = "admission-revocation-head-v1.json";
    static final String LOCK_FILE = ".execution-lease-store-v1.lock";
    static final String TEMP_FILE = ".execution-lease-state-v2.tmp";
    static final String CHECKPOINT_TEMP_FILE = ".execution-lease-checkpoint-v1.tmp";
    static final String REVOCATION_HEAD_TEMP_FILE = ".admission-revocation-head-v1.tmp";
    static final String DESCRIPTOR_TEMP_FILE = ".execution-lease-store-v1.tmp";
    static final int MAX_STATE_BYTES = 4 * 1024 * 1024;
    static final int MAX_CHECKPOINT_BYTES = 16 * 1024;
    static final int MAX_REVOCATION_HEAD_BYTES = 16 * 1024;
    static final int MAX_DESCRIPTOR_BYTES = 16 * 1024;
    static final int MAX_LEASES = 1024;

    private static final String DESCRIPTOR_VERSION =
            "resource-gateway.capability-studio.execution-lease-store-descriptor.v1";
    private static final String STATE_VERSION =
            "resource-gateway.capability-studio.execution-lease-state.v2";
    private static final String CHECKPOINT_VERSION =
            "resource-gateway.capability-studio.execution-lease-checkpoint.v1";
    static final String REVOCATION_HEAD_VERSION =
            "resource-gateway.capability-studio.admission-revocation-head.v1";
    static final String REVOCATION_HEAD_UPDATE_VERSION =
            "resource-gateway.capability-studio.revocation-head-update.v1";
    private static final String REVOCATION_HEAD_UPDATE_RECEIPT_VERSION =
            "resource-gateway.capability-studio.revocation-head-update-receipt.v1";
    private static final String VERIFIED = "LIFECYCLE_VERIFIED";
    private static final String REJECTED = "LIFECYCLE_REJECTED";
    private static final String UNAVAILABLE = "DEPLOYMENT_STATE_UNAVAILABLE";
    private static final String COMMITTED = "LEASE_COMMITTED";
    private static final String RECOVERED = "LEASE_RECOVERED";
    private static final String LEASE_REJECTED = "LEASE_REJECTED";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern STORE_ID = Pattern.compile("store:[0-9a-f]{64}");
    private static final Pattern REFERENCE = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,511}");
    private static final ObjectMapper JSON = new ObjectMapper(new JsonFactory().rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());
    private static final Map<Path, ReentrantLock> LOCAL_LOCKS = new ConcurrentHashMap<>();
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_FILE = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Durability NIO_DURABILITY = new Durability() {
        @Override
        public void atomicReplace(Path source, Path target) throws IOException {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("atomic move unavailable", unsupported);
            }
        }

        @Override
        public void forceDirectory(Path directory, Path installedEntry) throws IOException {
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            } catch (UnsupportedOperationException unsupported) {
                throw new IOException("directory force unavailable", unsupported);
            }
        }
    };
    private static final Metadata NIO_METADATA = new Metadata() {
        @Override
        public BasicFileAttributes readAttributes(Path path) throws IOException {
            return Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public long hardLinkCount(Path path) throws IOException {
            Object value = Files.getAttribute(
                    path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            if (!(value instanceof Number number)) {
                throw new UnsupportedOperationException("link count unavailable");
            }
            return number.longValue();
        }
    };

    private final PreparedStore store;
    private final AdmissionLifecycleMaterial configuredLifecycle;
    private final String targetRawFingerprint;
    private final String targetCanonicalFingerprint;
    private final String deploymentFingerprint;
    private final String providerOuterFingerprint;
    private final String executionLeaseId;
    private final Clock clock;
    private final int maxLeases;

    FilesystemDeploymentAdmissionAuthority(
            PreparedStore store,
            AdmissionLifecycleMaterial configuredLifecycle,
            String targetRawFingerprint,
            String targetCanonicalFingerprint,
            String deploymentFingerprint,
            String providerOuterFingerprint,
            String executionLeaseId,
            Clock clock) {
        this(store, configuredLifecycle, targetRawFingerprint, targetCanonicalFingerprint,
                deploymentFingerprint, providerOuterFingerprint, executionLeaseId,
                clock, MAX_LEASES);
    }

    FilesystemDeploymentAdmissionAuthority(
            PreparedStore store,
            AdmissionLifecycleMaterial configuredLifecycle,
            String targetRawFingerprint,
            String targetCanonicalFingerprint,
            String deploymentFingerprint,
            String providerOuterFingerprint,
            String executionLeaseId,
            Clock clock,
            int maxLeases) {
        this.store = java.util.Objects.requireNonNull(store, "store is required");
        this.configuredLifecycle = java.util.Objects.requireNonNull(
                configuredLifecycle, "configuredLifecycle is required");
        this.targetRawFingerprint = requireFingerprint(
                targetRawFingerprint, "targetRawFingerprint");
        this.targetCanonicalFingerprint = requireFingerprint(
                targetCanonicalFingerprint, "targetCanonicalFingerprint");
        this.deploymentFingerprint = requireFingerprint(
                deploymentFingerprint, "deploymentFingerprint");
        this.providerOuterFingerprint = requireFingerprint(
                providerOuterFingerprint, "providerOuterFingerprint");
        if (executionLeaseId == null || !REFERENCE.matcher(executionLeaseId).matches()) {
            throw new IllegalArgumentException("executionLeaseId is invalid");
        }
        this.executionLeaseId = executionLeaseId;
        this.clock = java.util.Objects.requireNonNull(clock, "clock is required");
        if (maxLeases < 1 || maxLeases > MAX_LEASES) {
            throw new IllegalArgumentException("maxLeases is invalid");
        }
        this.maxLeases = maxLeases;
    }

    static PreparedStore prepareStore(
            Path configured,
            String configurationFingerprint,
            RevocationAuthoritySnapshot initialRevocationHead) {
        return prepareStore(configured, configurationFingerprint,
                initialRevocationHead, NIO_DURABILITY, NIO_METADATA);
    }

    static PreparedStore prepareStore(
            Path configured,
            String configurationFingerprint,
            RevocationAuthoritySnapshot initialRevocationHead,
            Durability durability) {
        return prepareStore(configured, configurationFingerprint,
                initialRevocationHead, durability, NIO_METADATA);
    }

    static PreparedStore prepareStore(
            Path configured,
            String configurationFingerprint,
            RevocationAuthoritySnapshot initialRevocationHead,
            Metadata metadata) {
        return prepareStore(configured, configurationFingerprint,
                initialRevocationHead, NIO_DURABILITY, metadata);
    }

    static PreparedStore prepareStore(
            Path configured,
            String configurationFingerprint,
            RevocationAuthoritySnapshot initialRevocationHead,
            Durability durability,
            Metadata metadata) {
        Metadata checkedMetadata = java.util.Objects.requireNonNull(
                metadata, "metadata is required");
        Path root = requireStateRoot(configured, checkedMetadata);
        String configuration = requireFingerprint(
                configurationFingerprint, "configurationFingerprint");
        Durability checkedDurability = java.util.Objects.requireNonNull(
                durability, "durability is required");
        Path lockFile = directChild(root, LOCK_FILE);
        Path stateFile = directChild(root, STATE_FILE);
        Path checkpointFile = directChild(root, CHECKPOINT_FILE);
        Path revocationHeadFile = directChild(root, REVOCATION_HEAD_FILE);
        Path descriptorTemp = directChild(root, DESCRIPTOR_TEMP_FILE);
        ReentrantLock localLock = LOCAL_LOCKS.computeIfAbsent(root,
                ignored -> new ReentrantLock());
        localLock.lock();
        try {
            Object rootKey = fileKeyForPreparation(root, checkedMetadata);
            boolean created = createDescriptorIfAbsent(root, rootKey, lockFile, stateFile,
                    checkpointFile, descriptorTemp, configuration, checkedDurability,
                    checkedMetadata);
            StoreDescriptor descriptor = parseDescriptor(readSnapshot(
                    lockFile, MAX_DESCRIPTOR_BYTES, checkedMetadata));
            if (!descriptor.configurationFingerprint.equals(configuration)) {
                throw invalidRoot();
            }
            Object lockKey = fileKeyForPreparation(lockFile, checkedMetadata);
            PreparedStore store = new PreparedStore(root, rootKey, stateFile,
                    checkpointFile, revocationHeadFile, lockFile, lockKey,
                    directChild(root, TEMP_FILE),
                    directChild(root, CHECKPOINT_TEMP_FILE),
                    directChild(root, REVOCATION_HEAD_TEMP_FILE), descriptorTemp,
                    descriptor, localLock, checkedDurability, checkedMetadata);
            store.initializeOrValidate(created, java.util.Objects.requireNonNull(
                    initialRevocationHead, "initialRevocationHead is required"));
            return store;
        } catch (IllegalStateException | DeploymentUnavailableException failure) {
            throw failure;
        } catch (UnsafeStoreException unsafe) {
            throw invalidRoot();
        } catch (IOException | RuntimeException unavailable) {
            throw new DeploymentUnavailableException();
        } finally {
            localLock.unlock();
        }
    }

    static PreparedStore openExistingStore(
            Path configured, String expectedDescriptorFingerprint) {
        Path root = requireStateRoot(configured);
        String expected = requireFingerprint(
                expectedDescriptorFingerprint, "expectedDescriptorFingerprint");
        Path lockFile = directChild(root, LOCK_FILE);
        ReentrantLock localLock = LOCAL_LOCKS.computeIfAbsent(root,
                ignored -> new ReentrantLock());
        localLock.lock();
        try {
            StoreDescriptor descriptor = parseDescriptor(readSnapshot(
                    lockFile, MAX_DESCRIPTOR_BYTES, NIO_METADATA));
            if (!expected.equals(descriptor.descriptorFingerprint)) {
                throw invalidRoot();
            }
            PreparedStore store = new PreparedStore(root,
                    fileKeyForPreparation(root, NIO_METADATA),
                    directChild(root, STATE_FILE), directChild(root, CHECKPOINT_FILE),
                    directChild(root, REVOCATION_HEAD_FILE), lockFile,
                    fileKeyForPreparation(lockFile, NIO_METADATA),
                    directChild(root, TEMP_FILE), directChild(root, CHECKPOINT_TEMP_FILE),
                    directChild(root, REVOCATION_HEAD_TEMP_FILE),
                    directChild(root, DESCRIPTOR_TEMP_FILE), descriptor, localLock,
                    NIO_DURABILITY, NIO_METADATA);
            store.initializeOrValidate(false, null);
            return store;
        } catch (IllegalStateException | DeploymentUnavailableException failure) {
            throw failure;
        } catch (UnsafeStoreException unsafe) {
            throw invalidRoot();
        } catch (IOException | RuntimeException unavailable) {
            throw new DeploymentUnavailableException();
        } finally {
            localLock.unlock();
        }
    }

    static byte[] readStrictFile(Path configured, int maximumBytes) {
        if (configured == null || maximumBytes < 1) {
            throw new IllegalArgumentException("input is invalid");
        }
        Path normalized = configured.toAbsolutePath().normalize();
        if (!configured.isAbsolute() || !configured.equals(normalized)) {
            throw new IllegalArgumentException("input is invalid");
        }
        try {
            return readSnapshot(configured, maximumBytes, NIO_METADATA);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalArgumentException("input is invalid");
        }
    }

    static Path requireStateRoot(Path configured) {
        return requireStateRoot(configured, NIO_METADATA);
    }

    static Path requireStateRoot(Path configured, Metadata metadata) {
        if (configured == null) {
            throw invalidRoot();
        }
        Path normalized;
        try {
            normalized = configured.toAbsolutePath().normalize();
        } catch (RuntimeException malformed) {
            throw invalidRoot();
        }
        if (!configured.isAbsolute() || !configured.equals(normalized)) {
            throw invalidRoot();
        }
        try {
            BasicFileAttributes attributes = metadata.readAttributes(configured);
            if (attributes.isSymbolicLink() || !attributes.isDirectory()
                    || attributes.fileKey() == null) {
                throw new UnsafeStoreException();
            }
            Path real = configured.toRealPath(LinkOption.NOFOLLOW_LINKS);
            requirePrivateDirectory(real);
            return real;
        } catch (NoSuchFileException missing) {
            throw new DeploymentUnavailableException();
        } catch (UnsafeStoreException unsafe) {
            throw invalidRoot();
        } catch (IOException | RuntimeException unavailable) {
            throw new DeploymentUnavailableException();
        }
    }

    DeploymentAuthorityDecision verify(AdmissionLifecycleRequest request) {
        if (!validRequest(request)) {
            return DeploymentAuthorityDecision.rejected(REJECTED);
        }
        try {
            return withState(state -> {
                RevocationAuthoritySnapshot current = store.readRevocationHead().material;
                boolean allowed = lifecycleAllowed(request.material(), state.lifecycleHead,
                        request.trustedVerificationTime())
                        && current.equals(request.material().revocationAuthority())
                        && !current.observedAt().isAfter(request.trustedVerificationTime())
                        && current.expiresAt().isAfter(request.trustedVerificationTime());
                return allowed ? DeploymentAuthorityDecision.verified(VERIFIED)
                        : DeploymentAuthorityDecision.rejected(REJECTED);
            });
        } catch (StateUnavailable unavailable) {
            return DeploymentAuthorityDecision.unavailable(UNAVAILABLE);
        }
    }

    ExecutionLeaseCommitResult commit(ExecutionLeaseRequest request) {
        if (!validRequest(request)) {
            return ExecutionLeaseCommitResult.rejected(LEASE_REJECTED);
        }
        try {
            return withState(state -> commitLocked(state, request));
        } catch (StateUnavailable unavailable) {
            return ExecutionLeaseCommitResult.unavailable(UNAVAILABLE);
        }
    }

    private ExecutionLeaseCommitResult commitLocked(State state, ExecutionLeaseRequest request) {
        String leaseKey = leaseKey(request.executionLeaseId());
        StoredLease existing = state.leases.get(leaseKey);
        if (existing != null) {
            if (!existing.requestFingerprint.equals(request.commitIdentityFingerprint())
                    || !validStoredReceipt(existing.receipt, request)) {
                return ExecutionLeaseCommitResult.rejected(LEASE_REJECTED);
            }
            return ExecutionLeaseCommitResult.recovered(existing.receipt, RECOVERED);
        }
        if (state.leases.size() >= maxLeases || state.fencingSequence == Long.MAX_VALUE
                || state.generation == Long.MAX_VALUE) {
            return ExecutionLeaseCommitResult.unavailable(UNAVAILABLE);
        }

        Instant committedAt;
        try {
            committedAt = clock.instant();
        } catch (RuntimeException unavailable) {
            return ExecutionLeaseCommitResult.unavailable(UNAVAILABLE);
        }
        if (committedAt.isBefore(request.trustedVerificationTime())
                || !lifecycleAllowed(request.lifecycleMaterial(), state.lifecycleHead,
                committedAt)) {
            return ExecutionLeaseCommitResult.rejected(LEASE_REJECTED);
        }
        RevocationAuthoritySnapshot currentRevocation = store.readRevocationHead().material;
        if (!currentRevocation.equals(
                request.lifecycleMaterial().revocationAuthority())
                || currentRevocation.observedAt().isAfter(committedAt)
                || !currentRevocation.expiresAt().isAfter(committedAt)) {
            return ExecutionLeaseCommitResult.rejected(LEASE_REJECTED);
        }

        long sequence = state.fencingSequence + 1;
        RevocationAuthoritySnapshot revocation = request.lifecycleMaterial()
                .revocationAuthority();
        AtomicAdmissionLifecycleCommitReceipt lifecycleReceipt =
                new AtomicAdmissionLifecycleCommitReceipt(deploymentFingerprint,
                        request.lifecycleMaterial().fingerprint(), revocation.registryRef(),
                        revocation.revision(), revocation.snapshotFingerprint(), sequence,
                        committedAt, request.commitIdentityFingerprint());
        ExecutionLeaseReceipt receipt = new ExecutionLeaseReceipt(
                request.commitIdentityFingerprint(), request.lifecycleMaterial(),
                lifecycleReceipt);
        State updated = state.withCommit(request.lifecycleMaterial(), sequence,
                leaseKey, new StoredLease(request.commitIdentityFingerprint(), receipt));
        store.writeStateAndCheckpoint(updated);
        return ExecutionLeaseCommitResult.committed(receipt, COMMITTED);
    }

    private boolean validRequest(AdmissionLifecycleRequest request) {
        return request != null
                && configuredLifecycle.equals(request.material())
                && targetRawFingerprint.equals(request.targetRawFingerprint())
                && targetCanonicalFingerprint.equals(request.targetCanonicalFingerprint())
                && deploymentFingerprint.equals(
                request.deploymentAdmissionAuthorityMaterialFingerprint())
                && providerOuterFingerprint.equals(request.providerOuterFingerprint());
    }

    private boolean validRequest(ExecutionLeaseRequest request) {
        return request != null
                && configuredLifecycle.equals(request.lifecycleMaterial())
                && targetRawFingerprint.equals(request.targetRawFingerprint())
                && targetCanonicalFingerprint.equals(request.targetCanonicalFingerprint())
                && deploymentFingerprint.equals(
                request.deploymentAdmissionAuthorityMaterialFingerprint())
                && providerOuterFingerprint.equals(request.providerOuterFingerprint())
                && executionLeaseId.equals(request.executionLeaseId());
    }

    private boolean lifecycleAllowed(
            AdmissionLifecycleMaterial requested,
            AdmissionLifecycleMaterial current,
            Instant trustedTime) {
        RevocationAuthoritySnapshot revocation = requested.revocationAuthority();
        if (trustedTime == null || revocation.observedAt().isAfter(trustedTime)
                || !revocation.expiresAt().isAfter(trustedTime)) {
            return false;
        }
        if (current == null) {
            return requested.revision() == 1
                    && requested.predecessorBundleFingerprint() == null;
        }
        if (current.equals(requested)) {
            return true;
        }
        return requested.revision() == current.revision() + 1
                && current.bundleFingerprint().equals(
                requested.predecessorBundleFingerprint())
                && sameOrMonotonicRevocation(
                requested.revocationAuthority(), current.revocationAuthority());
    }

    private boolean sameOrMonotonicRevocation(
            RevocationAuthoritySnapshot requested,
            RevocationAuthoritySnapshot current) {
        if (!requested.registryRef().equals(current.registryRef())) {
            return false;
        }
        if (requested.revision() == current.revision()) {
            return requested.equals(current);
        }
        return requested.revision() > current.revision()
                && !requested.observedAt().isBefore(current.observedAt());
    }

    private boolean validStoredReceipt(
            ExecutionLeaseReceipt receipt, ExecutionLeaseRequest request) {
        AtomicAdmissionLifecycleCommitReceipt lifecycle = receipt.lifecycleCommitReceipt();
        return receipt.requestFingerprint().equals(request.commitIdentityFingerprint())
                && receipt.lifecycleMaterial().equals(request.lifecycleMaterial())
                && lifecycle.deploymentAdmissionAuthorityMaterialFingerprint().equals(
                request.deploymentAdmissionAuthorityMaterialFingerprint())
                && lifecycle.lifecycleMaterialFingerprint().equals(
                request.lifecycleMaterial().fingerprint())
                && lifecycle.requestFingerprint().equals(
                request.commitIdentityFingerprint())
                && lifecycle.fencingSequence() > 0;
    }

    private <T> T withState(StateOperation<T> operation) {
        return store.withLockedState(operation);
    }

    private static boolean createDescriptorIfAbsent(
            Path root,
            Object rootKey,
            Path lockFile,
            Path stateFile,
            Path checkpointFile,
            Path descriptorTemp,
            String configurationFingerprint,
            Durability durability,
            Metadata metadata) throws IOException {
        if (Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS)) {
            requirePrivateFile(lockFile, metadata);
            return false;
        }
        if (Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(checkpointFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("store descriptor unavailable");
        }
        ensureRootIdentity(root, rootKey, metadata);
        String storeId = "store:" + HexFormat.of().formatHex(
                new SecureRandom().generateSeed(32));
        StoreDescriptor descriptor = StoreDescriptor.create(
                storeId, configurationFingerprint);
        byte[] bytes = descriptorBytes(descriptor);
        if (bytes.length > MAX_DESCRIPTOR_BYTES) {
            throw new IOException("store descriptor unavailable");
        }
        try {
            writeFreshFile(root, lockFile, bytes, metadata);
            requirePrivateFile(lockFile, metadata);
            durability.forceDirectory(root, lockFile);
            return true;
        } catch (FileAlreadyExistsException raced) {
            requirePrivateFile(lockFile, metadata);
            return false;
        } catch (IOException | RuntimeException failure) {
            throw failure;
        }
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new IOException("lock unavailable");
            }
            return lock;
        } catch (OverlappingFileLockException unavailable) {
            throw new IOException("lock unavailable", unavailable);
        }
    }

    private static StoreDescriptor parseDescriptor(byte[] bytes) throws IOException {
        try {
            ObjectNode node = object(JSON.readTree(bytes));
            requireFields(node, Set.of("messageVersion", "storeId",
                    "configurationFingerprint", "descriptorFingerprint"));
            if (!DESCRIPTOR_VERSION.equals(text(node, "messageVersion"))) {
                throw new IllegalArgumentException("descriptor version");
            }
            String storeId = text(node, "storeId");
            if (!STORE_ID.matcher(storeId).matches()) {
                throw new IllegalArgumentException("store id");
            }
            StoreDescriptor descriptor = new StoreDescriptor(storeId,
                    fingerprint(node, "configurationFingerprint"),
                    fingerprint(node, "descriptorFingerprint"));
            if (!descriptor.descriptorFingerprint.equals(
                    StoreDescriptor.fingerprint(storeId,
                            descriptor.configurationFingerprint))) {
                throw new IllegalArgumentException("descriptor fingerprint");
            }
            return descriptor;
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IOException("store descriptor invalid", failure);
        }
    }

    private static byte[] descriptorBytes(StoreDescriptor descriptor) throws IOException {
        ObjectNode node = JSON.createObjectNode();
        node.put("messageVersion", DESCRIPTOR_VERSION);
        node.put("storeId", descriptor.storeId);
        node.put("configurationFingerprint", descriptor.configurationFingerprint);
        node.put("descriptorFingerprint", descriptor.descriptorFingerprint);
        return JSON.writeValueAsBytes(node);
    }

    private static byte[] readSnapshot(
            Path file, int maximumBytes, Metadata metadata) throws IOException {
        FileIdentity before = requirePrivateFile(file, metadata);
        if (before.size < 1 || before.size > maximumBytes) {
            throw new IOException("state document unavailable");
        }
        byte[] bytes = new byte[(int) before.size];
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Read the complete bounded document while holding the deployment lock.
            }
            if (buffer.hasRemaining() || channel.read(ByteBuffer.allocate(1)) != -1) {
                throw new IOException("state document changed");
            }
        }
        FileIdentity after = requirePrivateFile(file, metadata);
        if (!before.equals(after)) {
            throw new IOException("state document changed");
        }
        return bytes;
    }

    private static void writeFreshFile(
            Path root, Path file, byte[] bytes, Metadata metadata) throws IOException {
        try (FileChannel channel = openFreshPrivateFile(root, file)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        requirePrivateFile(file, metadata);
    }

    private static FileChannel openFreshPrivateFile(Path root, Path file) throws IOException {
        if (supportsPosix(root)) {
            return FileChannel.open(file,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS),
                    java.nio.file.attribute.PosixFilePermissions
                            .asFileAttribute(PRIVATE_FILE));
        }
        return FileChannel.open(file, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    private static void removeStaleTemp(Path temp, Metadata metadata) throws IOException {
        if (Files.exists(temp, LinkOption.NOFOLLOW_LINKS)) {
            requirePrivateFile(temp, metadata);
            Files.delete(temp);
        }
    }

    private static void tryRemoveTemp(Path temp, Metadata metadata) {
        try {
            removeStaleTemp(temp, metadata);
        } catch (IOException ignored) {
            // A failed durable operation remains unavailable and exposes no filesystem detail.
        }
    }

    private static void ensureRootIdentity(
            Path root, Object expectedKey, Metadata metadata) throws IOException {
        BasicFileAttributes attributes = metadata.readAttributes(root);
        if (attributes.isSymbolicLink() || !attributes.isDirectory()
                || attributes.fileKey() == null
                || !java.util.Objects.equals(expectedKey, attributes.fileKey())
                || !root.equals(root.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("state root changed");
        }
        requirePrivateDirectory(root);
    }

    private static FileIdentity requirePrivateFile(
            Path file, Metadata metadata) throws IOException {
        if (!file.equals(file.toAbsolutePath().normalize())) {
            throw new UnsafeStoreException();
        }
        BasicFileAttributes attributes = metadata.readAttributes(file);
        long linkCount;
        try {
            linkCount = metadata.hardLinkCount(file);
        } catch (UnsupportedOperationException unsupported) {
            throw new UnsafeStoreException();
        }
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()
                || attributes.fileKey() == null || linkCount != 1) {
            throw new UnsafeStoreException();
        }
        if (supportsPosix(file)) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                    file, LinkOption.NOFOLLOW_LINKS);
            if (!PRIVATE_FILE.containsAll(permissions)
                    || !permissions.contains(PosixFilePermission.OWNER_READ)
                    || !permissions.contains(PosixFilePermission.OWNER_WRITE)) {
                throw new UnsafeStoreException();
            }
        }
        return new FileIdentity(attributes.fileKey(), attributes.size(),
                attributes.lastModifiedTime().toMillis());
    }

    private static void requirePrivateDirectory(Path directory) throws IOException {
        if (supportsPosix(directory)) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                    directory, LinkOption.NOFOLLOW_LINKS);
            if (!PRIVATE_DIRECTORY.containsAll(permissions)
                    || !permissions.contains(PosixFilePermission.OWNER_READ)
                    || !permissions.contains(PosixFilePermission.OWNER_WRITE)
                    || !permissions.contains(PosixFilePermission.OWNER_EXECUTE)) {
                throw new UnsafeStoreException();
            }
        } else if (!Files.isReadable(directory) || !Files.isWritable(directory)) {
            throw new IOException("state root unavailable");
        }
    }

    private static boolean supportsPosix(Path path) {
        return Files.getFileAttributeView(path, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS) != null;
    }

    private static Object fileKeyForPreparation(
            Path path, Metadata metadata) throws IOException {
        try {
            Object fileKey = metadata.readAttributes(path).fileKey();
            if (fileKey == null) {
                throw new UnsafeStoreException();
            }
            return fileKey;
        } catch (RuntimeException failure) {
            throw new IOException("state identity unavailable", failure);
        }
    }

    private static Path directChild(Path root, String name) {
        Path child = root.resolve(name).normalize();
        if (!root.equals(child.getParent())) {
            throw invalidRoot();
        }
        return child;
    }

    private static IllegalStateException invalidRoot() {
        return new IllegalStateException(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .EXECUTION_LEASE_STATE_ROOT_INVALID_CODE);
    }

    private static ObjectNode object(JsonNode value) {
        if (!(value instanceof ObjectNode object)) {
            throw new IllegalArgumentException("object required");
        }
        return object;
    }

    private static void requireFields(ObjectNode node, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("fields invalid");
        }
    }

    private static String text(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("text invalid");
        }
        return value.textValue();
    }

    private static String fingerprint(ObjectNode node, String field) {
        return requireFingerprint(text(node, field), field);
    }

    private static String requireFingerprint(String value, String field) {
        if (value == null || !FINGERPRINT.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " invalid");
        }
        return value;
    }

    private static String reference(ObjectNode node, String field) {
        String value = text(node, field);
        if (!REFERENCE.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " invalid");
        }
        return value;
    }

    private static long positiveLong(ObjectNode node, String field) {
        long value = exactLong(node, field);
        if (value < 1) {
            throw new IllegalArgumentException(field + " invalid");
        }
        return value;
    }

    private static long nonNegativeLong(ObjectNode node, String field) {
        long value = exactLong(node, field);
        if (value < 0) {
            throw new IllegalArgumentException(field + " invalid");
        }
        return value;
    }

    private static long exactLong(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(field + " invalid");
        }
        return value.longValue();
    }

    private static Instant instant(ObjectNode node, String field) {
        try {
            return Instant.parse(text(node, field));
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException(field + " invalid");
        }
    }

    private static String leaseKey(String executionLeaseId) {
        return sha256(("resource-gateway.capability-studio.execution-lease-key.v1\n"
                + executionLeaseId).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    @Override
    public String toString() {
        return "FilesystemDeploymentAdmissionAuthority[material=REDACTED, state=REDACTED]";
    }

    @FunctionalInterface
    private interface StateOperation<T> {
        T apply(State state);
    }

    interface Durability {
        void atomicReplace(Path source, Path target) throws IOException;

        void forceDirectory(Path directory, Path installedEntry) throws IOException;

        default void forceReadBarrier(Path directory, Path checkpoint) throws IOException {
            forceDirectory(directory, checkpoint);
        }
    }

    interface Metadata {
        BasicFileAttributes readAttributes(Path path) throws IOException;

        long hardLinkCount(Path path) throws IOException;
    }

    enum RevocationHeadUpdateStatus {
        UPDATED,
        ALREADY_CURRENT,
        REJECTED,
        UNAVAILABLE
    }

    record RevocationHeadUpdate(
            String storeDescriptorFingerprint,
            RevocationAuthoritySnapshot material,
            String predecessorHeadFingerprint,
            String headFingerprint) {
        RevocationHeadUpdate {
            requireFingerprint(storeDescriptorFingerprint,
                    "storeDescriptorFingerprint");
            material = java.util.Objects.requireNonNull(material, "material is required");
            requireFingerprint(predecessorHeadFingerprint,
                    "predecessorHeadFingerprint");
            requireFingerprint(headFingerprint, "headFingerprint");
            if (!headFingerprint.equals(fingerprint(storeDescriptorFingerprint,
                    material, predecessorHeadFingerprint))) {
                throw new IllegalArgumentException("headFingerprint is invalid");
            }
        }

        static String fingerprint(
                String storeDescriptorFingerprint,
                RevocationAuthoritySnapshot material,
                String predecessorHeadFingerprint) {
            return sha256(canonicalMessage(storeDescriptorFingerprint, material,
                    predecessorHeadFingerprint, null).getBytes(StandardCharsets.UTF_8));
        }

        static String canonicalMessage(
                String storeDescriptorFingerprint,
                RevocationAuthoritySnapshot material,
                String predecessorHeadFingerprint,
                String headFingerprint) {
            requireFingerprint(storeDescriptorFingerprint,
                    "storeDescriptorFingerprint");
            java.util.Objects.requireNonNull(material, "material is required");
            if (predecessorHeadFingerprint != null) {
                requireFingerprint(predecessorHeadFingerprint,
                        "predecessorHeadFingerprint");
            }
            if (headFingerprint != null) {
                requireFingerprint(headFingerprint, "headFingerprint");
            }
            return "{\"messageVersion\":\"" + REVOCATION_HEAD_UPDATE_VERSION
                    + "\",\"storeDescriptorFingerprint\":\""
                    + storeDescriptorFingerprint + "\",\"registryRef\":\""
                    + material.registryRef() + "\",\"revision\":"
                    + material.revision() + ",\"snapshotFingerprint\":\""
                    + material.snapshotFingerprint() + "\",\"observedAt\":\""
                    + material.observedAt() + "\",\"expiresAt\":\""
                    + material.expiresAt() + "\",\"predecessorHeadFingerprint\":"
                    + (predecessorHeadFingerprint == null ? "null" : "\""
                    + predecessorHeadFingerprint + "\"")
                    + ",\"headFingerprint\":"
                    + (headFingerprint == null ? "null" : "\""
                    + headFingerprint + "\"") + "}";
        }

        @Override
        public String toString() {
            return "RevocationHeadUpdate[material=REDACTED]";
        }
    }

    record RevocationHeadUpdateResult(
            RevocationHeadUpdateStatus status,
            String storeDescriptorFingerprint,
            String previousHeadFingerprint,
            String newHeadFingerprint,
            long revision,
            String updateReceiptFingerprint) {
        private static RevocationHeadUpdateResult updated(
                String descriptor, RevocationHead head) {
            return success(RevocationHeadUpdateStatus.UPDATED, descriptor, head);
        }

        private static RevocationHeadUpdateResult alreadyCurrent(
                String descriptor, RevocationHead head) {
            return success(RevocationHeadUpdateStatus.ALREADY_CURRENT,
                    descriptor, head);
        }

        private static RevocationHeadUpdateResult success(
                RevocationHeadUpdateStatus status,
                String descriptor,
                RevocationHead head) {
            return new RevocationHeadUpdateResult(status, descriptor,
                    head.previousHeadFingerprint, head.headFingerprint,
                    head.material.revision(), receiptFingerprint(descriptor, head));
        }

        private static String receiptFingerprint(
                String descriptor, RevocationHead head) {
            return sha256((REVOCATION_HEAD_UPDATE_RECEIPT_VERSION + "\n"
                    + descriptor + "\n"
                    + head.previousHeadFingerprint + "\n"
                    + head.headFingerprint + "\n"
                    + head.material.revision()).getBytes(StandardCharsets.UTF_8));
        }

        private static RevocationHeadUpdateResult rejected() {
            return new RevocationHeadUpdateResult(
                    RevocationHeadUpdateStatus.REJECTED, null, null, null, 0, null);
        }

        private static RevocationHeadUpdateResult unavailable() {
            return new RevocationHeadUpdateResult(
                    RevocationHeadUpdateStatus.UNAVAILABLE, null, null, null, 0, null);
        }

        @Override
        public String toString() {
            return "RevocationHeadUpdateResult[status=" + status
                    + ", material=REDACTED]";
        }
    }

    static final class PreparedStore {
        private final Path root;
        private final Object rootFileKey;
        private final Path stateFile;
        private final Path checkpointFile;
        private final Path revocationHeadFile;
        private final Path lockFile;
        private final Object lockFileKey;
        private final Path stateTempFile;
        private final Path checkpointTempFile;
        private final Path revocationHeadTempFile;
        private final Path descriptorTempFile;
        private final StoreDescriptor descriptor;
        private final ReentrantLock localLock;
        private final Durability durability;
        private final Metadata metadata;

        private PreparedStore(
                Path root,
                Object rootFileKey,
                Path stateFile,
                Path checkpointFile,
                Path revocationHeadFile,
                Path lockFile,
                Object lockFileKey,
                Path stateTempFile,
                Path checkpointTempFile,
                Path revocationHeadTempFile,
                Path descriptorTempFile,
                StoreDescriptor descriptor,
                ReentrantLock localLock,
                Durability durability,
                Metadata metadata) {
            this.root = root;
            this.rootFileKey = rootFileKey;
            this.stateFile = stateFile;
            this.checkpointFile = checkpointFile;
            this.revocationHeadFile = revocationHeadFile;
            this.lockFile = lockFile;
            this.lockFileKey = lockFileKey;
            this.stateTempFile = stateTempFile;
            this.checkpointTempFile = checkpointTempFile;
            this.revocationHeadTempFile = revocationHeadTempFile;
            this.descriptorTempFile = descriptorTempFile;
            this.descriptor = descriptor;
            this.localLock = localLock;
            this.durability = durability;
            this.metadata = metadata;
        }

        String descriptorFingerprint() {
            return descriptor.descriptorFingerprint;
        }

        RevocationHeadUpdateResult advanceRevocationHead(
                RevocationHeadUpdate requested, Instant trustedTime) {
            java.util.Objects.requireNonNull(requested, "requested is required");
            java.util.Objects.requireNonNull(trustedTime, "trustedTime is required");
            try {
                return withLockedState(state -> {
                    RevocationHead current = readRevocationHead();
                    if (!descriptor.descriptorFingerprint.equals(
                            requested.storeDescriptorFingerprint)) {
                        return RevocationHeadUpdateResult.rejected();
                    }
                    if (current.headFingerprint.equals(requested.headFingerprint)
                            && java.util.Objects.equals(current.previousHeadFingerprint,
                            requested.predecessorHeadFingerprint)
                            && current.material.equals(requested.material)) {
                        return RevocationHeadUpdateResult.alreadyCurrent(
                                descriptor.descriptorFingerprint, current);
                    }
                    if (!current.headFingerprint.equals(
                            requested.predecessorHeadFingerprint)
                            || !current.material.registryRef().equals(
                            requested.material.registryRef())
                            || requested.material.revision()
                            != current.material.revision() + 1
                            || requested.material.observedAt().isBefore(
                            current.material.observedAt())
                            || requested.material.observedAt().isAfter(trustedTime)
                            || !requested.material.expiresAt().isAfter(trustedTime)) {
                        return RevocationHeadUpdateResult.rejected();
                    }
                    RevocationHead next = current.successor(requested.material);
                    if (!next.headFingerprint.equals(requested.headFingerprint)) {
                        return RevocationHeadUpdateResult.rejected();
                    }
                    writeRevocationHeadAndCheckpoint(state, next);
                    return RevocationHeadUpdateResult.updated(
                            descriptor.descriptorFingerprint, next);
                });
            } catch (StateUnavailable unavailable) {
                return RevocationHeadUpdateResult.unavailable();
            }
        }

        private <T> T withLockedState(StateOperation<T> operation) {
            localLock.lock();
            try {
                ensureRootStable();
                FileIdentity descriptorIdentity = requirePrivateFile(lockFile, metadata);
                if (!java.util.Objects.equals(lockFileKey,
                        descriptorIdentity.fileKey())) {
                    throw new StateUnavailable();
                }
                try (FileChannel channel = FileChannel.open(lockFile,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                     FileLock ignored = tryLock(channel)) {
                    if (!java.util.Objects.equals(lockFileKey,
                            requirePrivateFile(lockFile, metadata).fileKey())) {
                        throw new StateUnavailable();
                    }
                    ensureRootStable();
                    State state = readConsistentState();
                    forceReadBarrier();
                    T result = operation.apply(state);
                    ensureRootStable();
                    return result;
                } catch (IOException | RuntimeException failure) {
                    throw new StateUnavailable();
                }
            } catch (IOException unavailable) {
                throw new StateUnavailable();
            } finally {
                localLock.unlock();
            }
        }

        private void initializeOrValidate(
                boolean descriptorCreated,
                RevocationAuthoritySnapshot initialRevocationHead) throws IOException {
            try (FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock ignored = tryLock(channel)) {
                ensureRootStable();
                boolean stateExists = Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS);
                boolean checkpointExists = Files.exists(
                        checkpointFile, LinkOption.NOFOLLOW_LINKS);
                boolean revocationHeadExists = Files.exists(
                        revocationHeadFile, LinkOption.NOFOLLOW_LINKS);
                if (!stateExists && !checkpointExists) {
                    if (!descriptorCreated || revocationHeadExists) {
                        throw new IOException("initialized store state unavailable");
                    }
                    RevocationHead revocationHead = RevocationHead.genesis(
                            descriptor.descriptorFingerprint, initialRevocationHead);
                    writeRevocationHead(revocationHead);
                    State genesis = State.genesis(descriptor.descriptorFingerprint);
                    writeState(genesis);
                    writeCheckpoint(Checkpoint.forSnapshot(genesis, revocationHead));
                    return;
                }
                if (stateExists && !checkpointExists) {
                    if (!revocationHeadExists) {
                        throw new IOException("revocation head unavailable");
                    }
                    State state = readState();
                    if (!state.isGenesis()) {
                        throw new IOException("checkpoint unavailable");
                    }
                    writeCheckpoint(Checkpoint.forSnapshot(
                            state, readRevocationHead()));
                    return;
                }
                if (!stateExists) {
                    throw new IOException("state unavailable");
                }
                if (!revocationHeadExists) {
                    throw new IOException("revocation head unavailable");
                }
                readConsistentState();
                forceReadBarrier();
            }
        }

        private State readConsistentState() throws IOException {
            if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)
                    || !Files.exists(checkpointFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("initialized store document unavailable");
            }
            State state = readState();
            Checkpoint checkpoint = readCheckpoint();
            RevocationHead revocationHead = readRevocationHead();
            boolean stateMatches = checkpoint.matchesState(state);
            boolean headMatches = checkpoint.matchesRevocationHead(revocationHead);
            if (stateMatches && headMatches) {
                return state;
            }
            boolean stateSuccessor = state.generation == checkpoint.generation + 1
                    && java.util.Objects.equals(state.previousStateFingerprint,
                    checkpoint.stateFingerprint);
            boolean headSuccessor = revocationHead.sequence
                    == checkpoint.revocationHeadSequence + 1
                    && java.util.Objects.equals(revocationHead.previousHeadFingerprint,
                    checkpoint.revocationHeadFingerprint);
            if (stateSuccessor && headMatches) {
                writeCheckpoint(Checkpoint.forSnapshot(state, revocationHead));
                return state;
            }
            if (stateMatches && headSuccessor) {
                writeCheckpoint(Checkpoint.forSnapshot(state, revocationHead));
                return state;
            }
            throw new IOException("state checkpoint mismatch");
        }

        private State readState() throws IOException {
            try {
                ObjectNode node = object(JSON.readTree(readSnapshot(
                        stateFile, MAX_STATE_BYTES, metadata)));
                requireFields(node, Set.of("messageVersion", "storeDescriptorFingerprint",
                        "generation", "previousStateFingerprint", "stateFingerprint",
                        "lifecycleHead", "fencingSequence", "leases"));
                if (!STATE_VERSION.equals(text(node, "messageVersion"))
                        || !descriptor.descriptorFingerprint.equals(
                        fingerprint(node, "storeDescriptorFingerprint"))) {
                    throw new IllegalArgumentException("state version");
                }
                long generation = nonNegativeLong(node, "generation");
                JsonNode previousNode = node.get("previousStateFingerprint");
                String previous = previousNode.isNull() ? null
                        : fingerprint(node, "previousStateFingerprint");
                String stateFingerprint = fingerprint(node, "stateFingerprint");
                long fencing = nonNegativeLong(node, "fencingSequence");
                AdmissionLifecycleMaterial head = node.get("lifecycleHead").isNull()
                        ? null : lifecycle(object(node.get("lifecycleHead")));
                JsonNode leasesNode = node.get("leases");
                if (!leasesNode.isArray() || leasesNode.size() > MAX_LEASES) {
                    throw new IllegalArgumentException("leases");
                }
                TreeMap<String, StoredLease> leases = new TreeMap<>();
                String prior = null;
                long maximumSequence = 0;
                AdmissionLifecycleMaterial latestLifecycle = null;
                Set<Long> sequences = new HashSet<>();
                for (JsonNode value : leasesNode) {
                    ObjectNode lease = object(value);
                    requireFields(lease, Set.of(
                            "leaseKey", "requestFingerprint", "receipt"));
                    String key = fingerprint(lease, "leaseKey");
                    String requestFingerprint = fingerprint(lease, "requestFingerprint");
                    if (prior != null && prior.compareTo(key) >= 0) {
                        throw new IllegalArgumentException("lease order");
                    }
                    ExecutionLeaseReceipt receipt = receipt(object(lease.get("receipt")));
                    if (!requestFingerprint.equals(receipt.requestFingerprint())
                            || leases.put(key,
                            new StoredLease(requestFingerprint, receipt)) != null) {
                        throw new IllegalArgumentException("lease binding");
                    }
                    long sequence = receipt.lifecycleCommitReceipt().fencingSequence();
                    if (!sequences.add(sequence)) {
                        throw new IllegalArgumentException("receipt sequence");
                    }
                    if (sequence > maximumSequence) {
                        maximumSequence = sequence;
                        latestLifecycle = receipt.lifecycleMaterial();
                    }
                    prior = key;
                }
                State state = new State(descriptor.descriptorFingerprint, generation,
                        previous, stateFingerprint, head, fencing, leases);
                if (maximumSequence != fencing || fencing != leases.size()
                        || generation != fencing
                        || (leases.isEmpty() != (head == null))
                        || (head != null && !head.equals(latestLifecycle))
                        || !stateFingerprint.equals(state.computeFingerprint())) {
                    throw new IllegalArgumentException("state invariant");
                }
                return state;
            } catch (IOException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new IOException("state invalid", failure);
            }
        }

        private Checkpoint readCheckpoint() throws IOException {
            try {
                ObjectNode node = object(JSON.readTree(readSnapshot(
                        checkpointFile, MAX_CHECKPOINT_BYTES, metadata)));
                requireFields(node, Set.of("messageVersion", "storeDescriptorFingerprint",
                        "generation", "stateFingerprint", "revocationHeadSequence",
                        "revocationHeadFingerprint", "checkpointFingerprint"));
                if (!CHECKPOINT_VERSION.equals(text(node, "messageVersion"))
                        || !descriptor.descriptorFingerprint.equals(
                        fingerprint(node, "storeDescriptorFingerprint"))) {
                    throw new IllegalArgumentException("checkpoint version");
                }
                Checkpoint checkpoint = new Checkpoint(
                        descriptor.descriptorFingerprint,
                        nonNegativeLong(node, "generation"),
                        fingerprint(node, "stateFingerprint"),
                        nonNegativeLong(node, "revocationHeadSequence"),
                        fingerprint(node, "revocationHeadFingerprint"),
                        fingerprint(node, "checkpointFingerprint"));
                if (!checkpoint.checkpointFingerprint.equals(
                        checkpoint.computeFingerprint())) {
                    throw new IllegalArgumentException("checkpoint fingerprint");
                }
                return checkpoint;
            } catch (IOException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new IOException("checkpoint invalid", failure);
            }
        }

        private void writeStateAndCheckpoint(State state) {
            try {
                writeState(state);
                writeCheckpoint(Checkpoint.forSnapshot(
                        state, readRevocationHead()));
            } catch (IOException | RuntimeException unavailable) {
                throw new StateUnavailable();
            }
        }

        private void writeState(State state) throws IOException {
            replaceDocument(stateFile, stateTempFile, state.bytes(), MAX_STATE_BYTES);
        }

        private void writeCheckpoint(Checkpoint checkpoint) throws IOException {
            replaceDocument(checkpointFile, checkpointTempFile,
                    checkpoint.bytes(), MAX_CHECKPOINT_BYTES);
        }

        private RevocationHead readRevocationHead() {
            try {
                return RevocationHead.parse(readSnapshot(
                        revocationHeadFile, MAX_REVOCATION_HEAD_BYTES, metadata),
                        descriptor.descriptorFingerprint);
            } catch (IOException | RuntimeException unavailable) {
                throw new StateUnavailable();
            }
        }

        private void writeRevocationHead(RevocationHead head) {
            try {
                replaceDocument(revocationHeadFile, revocationHeadTempFile,
                        head.bytes(), MAX_REVOCATION_HEAD_BYTES);
            } catch (IOException | RuntimeException unavailable) {
                throw new StateUnavailable();
            }
        }

        private void writeRevocationHeadAndCheckpoint(
                State state, RevocationHead head) {
            writeRevocationHead(head);
            try {
                writeCheckpoint(Checkpoint.forSnapshot(state, head));
            } catch (IOException | RuntimeException unavailable) {
                throw new StateUnavailable();
            }
        }

        private void forceReadBarrier() throws IOException {
            durability.forceReadBarrier(root, checkpointFile);
        }

        private void replaceDocument(
                Path target, Path temp, byte[] bytes, int maximumBytes) throws IOException {
            if (bytes.length < 1 || bytes.length > maximumBytes) {
                throw new IOException("state document unavailable");
            }
            removeStaleTemp(temp, metadata);
            try {
                writeFreshFile(root, temp, bytes, metadata);
                durability.atomicReplace(temp, target);
                requirePrivateFile(target, metadata);
                durability.forceDirectory(root, target);
            } catch (IOException | RuntimeException failure) {
                tryRemoveTemp(temp, metadata);
                throw failure;
            }
        }

        private void ensureRootStable() throws IOException {
            ensureRootIdentity(root, rootFileKey, metadata);
            if (!java.util.Objects.equals(lockFileKey,
                    requirePrivateFile(lockFile, metadata).fileKey())) {
                throw new IOException("store descriptor changed");
            }
            StoreDescriptor current = parseDescriptor(readSnapshot(
                    lockFile, MAX_DESCRIPTOR_BYTES, metadata));
            if (!descriptor.equals(current)) {
                throw new IOException("store descriptor changed");
            }
        }

        @Override
        public String toString() {
            return "PreparedStore[material=REDACTED, state=REDACTED]";
        }
    }

    private static final class StoreDescriptor {
        private final String storeId;
        private final String configurationFingerprint;
        private final String descriptorFingerprint;

        private StoreDescriptor(String storeId, String configurationFingerprint,
                String descriptorFingerprint) {
            this.storeId = storeId;
            this.configurationFingerprint = configurationFingerprint;
            this.descriptorFingerprint = descriptorFingerprint;
        }

        private static StoreDescriptor create(
                String storeId, String configurationFingerprint) {
            return new StoreDescriptor(storeId, configurationFingerprint,
                    fingerprint(storeId, configurationFingerprint));
        }

        private static String fingerprint(
                String storeId, String configurationFingerprint) {
            ObjectNode node = JSON.createObjectNode();
            node.put("messageVersion", DESCRIPTOR_VERSION);
            node.put("storeId", storeId);
            node.put("configurationFingerprint", configurationFingerprint);
            node.putNull("descriptorFingerprint");
            try {
                return sha256(JSON.writeValueAsBytes(node));
            } catch (IOException impossible) {
                throw new IllegalStateException("descriptor unavailable");
            }
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof StoreDescriptor descriptor
                    && storeId.equals(descriptor.storeId)
                    && configurationFingerprint.equals(
                    descriptor.configurationFingerprint)
                    && descriptorFingerprint.equals(descriptor.descriptorFingerprint);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(
                    storeId, configurationFingerprint, descriptorFingerprint);
        }
    }

    private static final class State {
        private final String storeDescriptorFingerprint;
        private final long generation;
        private final String previousStateFingerprint;
        private final String stateFingerprint;
        private final AdmissionLifecycleMaterial lifecycleHead;
        private final long fencingSequence;
        private final TreeMap<String, StoredLease> leases;

        private State(
                String storeDescriptorFingerprint,
                long generation,
                String previousStateFingerprint,
                String stateFingerprint,
                AdmissionLifecycleMaterial lifecycleHead,
                long fencingSequence,
                TreeMap<String, StoredLease> leases) {
            this.storeDescriptorFingerprint = storeDescriptorFingerprint;
            this.generation = generation;
            this.previousStateFingerprint = previousStateFingerprint;
            this.stateFingerprint = stateFingerprint;
            this.lifecycleHead = lifecycleHead;
            this.fencingSequence = fencingSequence;
            this.leases = new TreeMap<>(leases);
        }

        private static State genesis(String descriptorFingerprint) {
            State unhashed = new State(descriptorFingerprint, 0, null, null,
                    null, 0, new TreeMap<>());
            return unhashed.withFingerprint(unhashed.computeFingerprint());
        }

        private State withCommit(
                AdmissionLifecycleMaterial head,
                long sequence,
                String leaseKey,
                StoredLease lease) {
            TreeMap<String, StoredLease> updated = new TreeMap<>(leases);
            updated.put(leaseKey, lease);
            State unhashed = new State(storeDescriptorFingerprint, generation + 1,
                    stateFingerprint, null, head, sequence, updated);
            return unhashed.withFingerprint(unhashed.computeFingerprint());
        }

        private State withFingerprint(String fingerprint) {
            return new State(storeDescriptorFingerprint, generation,
                    previousStateFingerprint, fingerprint, lifecycleHead,
                    fencingSequence, leases);
        }

        private boolean isGenesis() {
            return generation == 0 && previousStateFingerprint == null
                    && lifecycleHead == null && fencingSequence == 0 && leases.isEmpty()
                    && stateFingerprint.equals(computeFingerprint());
        }

        private String computeFingerprint() {
            return sha256(bytesWithFingerprint(null));
        }

        private byte[] bytes() {
            return bytesWithFingerprint(stateFingerprint);
        }

        private byte[] bytesWithFingerprint(String fingerprint) {
            ObjectNode node = JSON.createObjectNode();
            node.put("messageVersion", STATE_VERSION);
            node.put("storeDescriptorFingerprint", storeDescriptorFingerprint);
            node.put("generation", generation);
            if (previousStateFingerprint == null) {
                node.putNull("previousStateFingerprint");
            } else {
                node.put("previousStateFingerprint", previousStateFingerprint);
            }
            if (fingerprint == null) {
                node.putNull("stateFingerprint");
            } else {
                node.put("stateFingerprint", fingerprint);
            }
            if (lifecycleHead == null) {
                node.putNull("lifecycleHead");
            } else {
                node.set("lifecycleHead", lifecycleNode(lifecycleHead));
            }
            node.put("fencingSequence", fencingSequence);
            ArrayNode leasesNode = node.putArray("leases");
            leases.forEach((key, stored) -> {
                ObjectNode lease = leasesNode.addObject();
                lease.put("leaseKey", key);
                lease.put("requestFingerprint", stored.requestFingerprint);
                lease.set("receipt", receiptNode(stored.receipt));
            });
            try {
                return JSON.writeValueAsBytes(node);
            } catch (IOException impossible) {
                throw new IllegalStateException("state unavailable");
            }
        }

        @Override
        public String toString() {
            return "State[material=REDACTED]";
        }
    }

    private static final class Checkpoint {
        private final String storeDescriptorFingerprint;
        private final long generation;
        private final String stateFingerprint;
        private final long revocationHeadSequence;
        private final String revocationHeadFingerprint;
        private final String checkpointFingerprint;

        private Checkpoint(
                String storeDescriptorFingerprint,
                long generation,
                String stateFingerprint,
                long revocationHeadSequence,
                String revocationHeadFingerprint,
                String checkpointFingerprint) {
            this.storeDescriptorFingerprint = storeDescriptorFingerprint;
            this.generation = generation;
            this.stateFingerprint = stateFingerprint;
            this.revocationHeadSequence = revocationHeadSequence;
            this.revocationHeadFingerprint = revocationHeadFingerprint;
            this.checkpointFingerprint = checkpointFingerprint;
        }

        private static Checkpoint forSnapshot(State state, RevocationHead revocationHead) {
            Checkpoint unhashed = new Checkpoint(state.storeDescriptorFingerprint,
                    state.generation, state.stateFingerprint, revocationHead.sequence,
                    revocationHead.headFingerprint, null);
            return new Checkpoint(unhashed.storeDescriptorFingerprint,
                    unhashed.generation, unhashed.stateFingerprint,
                    unhashed.revocationHeadSequence, unhashed.revocationHeadFingerprint,
                    unhashed.computeFingerprint());
        }

        private boolean matchesState(State state) {
            return storeDescriptorFingerprint.equals(
                    state.storeDescriptorFingerprint)
                    && generation == state.generation
                    && stateFingerprint.equals(state.stateFingerprint);
        }

        private boolean matchesRevocationHead(RevocationHead head) {
            return storeDescriptorFingerprint.equals(head.storeDescriptorFingerprint)
                    && revocationHeadSequence == head.sequence
                    && revocationHeadFingerprint.equals(head.headFingerprint);
        }

        private String computeFingerprint() {
            return sha256(bytesWithFingerprint(null));
        }

        private byte[] bytes() {
            return bytesWithFingerprint(checkpointFingerprint);
        }

        private byte[] bytesWithFingerprint(String fingerprint) {
            ObjectNode node = JSON.createObjectNode();
            node.put("messageVersion", CHECKPOINT_VERSION);
            node.put("storeDescriptorFingerprint", storeDescriptorFingerprint);
            node.put("generation", generation);
            node.put("stateFingerprint", stateFingerprint);
            node.put("revocationHeadSequence", revocationHeadSequence);
            node.put("revocationHeadFingerprint", revocationHeadFingerprint);
            if (fingerprint == null) {
                node.putNull("checkpointFingerprint");
            } else {
                node.put("checkpointFingerprint", fingerprint);
            }
            try {
                return JSON.writeValueAsBytes(node);
            } catch (IOException impossible) {
                throw new IllegalStateException("checkpoint unavailable");
            }
        }
    }

    private static final class RevocationHead {
        private final String storeDescriptorFingerprint;
        private final long sequence;
        private final String previousHeadFingerprint;
        private final RevocationAuthoritySnapshot material;
        private final String headFingerprint;

        private RevocationHead(
                String storeDescriptorFingerprint,
                long sequence,
                String previousHeadFingerprint,
                RevocationAuthoritySnapshot material,
                String headFingerprint) {
            this.storeDescriptorFingerprint = storeDescriptorFingerprint;
            this.sequence = sequence;
            this.previousHeadFingerprint = previousHeadFingerprint;
            this.material = material;
            this.headFingerprint = headFingerprint;
        }

        private static RevocationHead genesis(
                String storeDescriptorFingerprint,
                RevocationAuthoritySnapshot material) {
            RevocationHead unhashed = new RevocationHead(
                    storeDescriptorFingerprint, 0, null,
                    java.util.Objects.requireNonNull(material, "material is required"), null);
            return new RevocationHead(storeDescriptorFingerprint, 0, null, material,
                    unhashed.computeFingerprint());
        }

        private RevocationHead successor(RevocationAuthoritySnapshot next) {
            if (sequence == Long.MAX_VALUE) {
                throw new StateUnavailable();
            }
            RevocationHead unhashed = new RevocationHead(storeDescriptorFingerprint,
                    sequence + 1, headFingerprint, next, null);
            return new RevocationHead(storeDescriptorFingerprint, sequence + 1,
                    headFingerprint, next, unhashed.computeFingerprint());
        }

        private static RevocationHead parse(
                byte[] bytes, String expectedStoreDescriptorFingerprint) throws IOException {
            try {
                ObjectNode node = object(JSON.readTree(bytes));
                requireFields(node, Set.of("messageVersion", "storeDescriptorFingerprint",
                        "sequence", "previousHeadFingerprint", "registryRef", "revision",
                        "snapshotFingerprint", "observedAt", "expiresAt",
                        "headFingerprint"));
                if (!REVOCATION_HEAD_VERSION.equals(text(node, "messageVersion"))
                        || !expectedStoreDescriptorFingerprint.equals(
                        fingerprint(node, "storeDescriptorFingerprint"))) {
                    throw new IllegalArgumentException("revocation head binding");
                }
                JsonNode previousNode = node.get("previousHeadFingerprint");
                RevocationHead head = new RevocationHead(expectedStoreDescriptorFingerprint,
                        nonNegativeLong(node, "sequence"),
                        previousNode.isNull() ? null
                                : fingerprint(node, "previousHeadFingerprint"),
                        new RevocationAuthoritySnapshot(reference(node, "registryRef"),
                                positiveLong(node, "revision"),
                                fingerprint(node, "snapshotFingerprint"),
                                instant(node, "observedAt"), instant(node, "expiresAt")),
                        fingerprint(node, "headFingerprint"));
                if (!head.headFingerprint.equals(head.computeFingerprint())) {
                    throw new IllegalArgumentException("revocation head fingerprint");
                }
                if ((head.sequence == 0) != (head.previousHeadFingerprint == null)) {
                    throw new IllegalArgumentException("revocation head sequence");
                }
                return head;
            } catch (IOException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new IOException("revocation head invalid", failure);
            }
        }

        private String computeFingerprint() {
            return RevocationHeadUpdate.fingerprint(storeDescriptorFingerprint,
                    material, previousHeadFingerprint);
        }

        private byte[] bytes() {
            return bytesWithFingerprint(headFingerprint);
        }

        private byte[] bytesWithFingerprint(String fingerprint) {
            ObjectNode node = JSON.createObjectNode();
            node.put("messageVersion", REVOCATION_HEAD_VERSION);
            node.put("storeDescriptorFingerprint", storeDescriptorFingerprint);
            node.put("sequence", sequence);
            if (previousHeadFingerprint == null) {
                node.putNull("previousHeadFingerprint");
            } else {
                node.put("previousHeadFingerprint", previousHeadFingerprint);
            }
            node.put("registryRef", material.registryRef());
            node.put("revision", material.revision());
            node.put("snapshotFingerprint", material.snapshotFingerprint());
            node.put("observedAt", material.observedAt().toString());
            node.put("expiresAt", material.expiresAt().toString());
            if (fingerprint == null) {
                node.putNull("headFingerprint");
            } else {
                node.put("headFingerprint", fingerprint);
            }
            try {
                return JSON.writeValueAsBytes(node);
            } catch (IOException impossible) {
                throw new IllegalStateException("revocation head unavailable");
            }
        }
    }

    private static ObjectNode receiptNode(ExecutionLeaseReceipt receipt) {
        ObjectNode node = JSON.createObjectNode();
        node.put("fingerprint", receipt.fingerprint());
        node.put("requestFingerprint", receipt.requestFingerprint());
        node.set("lifecycleMaterial", lifecycleNode(receipt.lifecycleMaterial()));
        AtomicAdmissionLifecycleCommitReceipt lifecycle = receipt.lifecycleCommitReceipt();
        ObjectNode atomic = node.putObject("lifecycleCommitReceipt");
        atomic.put("fingerprint", lifecycle.fingerprint());
        atomic.put("deploymentAdmissionAuthorityMaterialFingerprint",
                lifecycle.deploymentAdmissionAuthorityMaterialFingerprint());
        atomic.put("lifecycleMaterialFingerprint",
                lifecycle.lifecycleMaterialFingerprint());
        atomic.put("revocationRegistryRef", lifecycle.revocationRegistryRef());
        atomic.put("revocationRegistryRevision", lifecycle.revocationRegistryRevision());
        atomic.put("revocationSnapshotFingerprint",
                lifecycle.revocationSnapshotFingerprint());
        atomic.put("fencingSequence", lifecycle.fencingSequence());
        atomic.put("committedAt", lifecycle.committedAt().toString());
        atomic.put("requestFingerprint", lifecycle.requestFingerprint());
        return node;
    }

    private static ExecutionLeaseReceipt receipt(ObjectNode node) {
        requireFields(node, Set.of("fingerprint", "requestFingerprint", "lifecycleMaterial",
                "lifecycleCommitReceipt"));
        ObjectNode atomic = object(node.get("lifecycleCommitReceipt"));
        requireFields(atomic, Set.of("fingerprint",
                "deploymentAdmissionAuthorityMaterialFingerprint",
                "lifecycleMaterialFingerprint", "revocationRegistryRef",
                "revocationRegistryRevision", "revocationSnapshotFingerprint",
                "fencingSequence", "committedAt", "requestFingerprint"));
        AtomicAdmissionLifecycleCommitReceipt lifecycleReceipt =
                new AtomicAdmissionLifecycleCommitReceipt(
                        fingerprint(atomic, "fingerprint"),
                        fingerprint(atomic,
                                "deploymentAdmissionAuthorityMaterialFingerprint"),
                        fingerprint(atomic, "lifecycleMaterialFingerprint"),
                        reference(atomic, "revocationRegistryRef"),
                        positiveLong(atomic, "revocationRegistryRevision"),
                        fingerprint(atomic, "revocationSnapshotFingerprint"),
                        positiveLong(atomic, "fencingSequence"),
                        instant(atomic, "committedAt"),
                        fingerprint(atomic, "requestFingerprint"));
        return new ExecutionLeaseReceipt(fingerprint(node, "fingerprint"),
                fingerprint(node, "requestFingerprint"),
                lifecycle(object(node.get("lifecycleMaterial"))), lifecycleReceipt);
    }

    private static ObjectNode lifecycleNode(AdmissionLifecycleMaterial material) {
        ObjectNode node = JSON.createObjectNode();
        node.put("bundleFingerprint", material.bundleFingerprint());
        node.put("bundleId", material.bundleId());
        node.put("revision", material.revision());
        node.put("lifecycleState", material.lifecycleState());
        if (material.predecessorBundleFingerprint() == null) {
            node.putNull("predecessorBundleFingerprint");
        } else {
            node.put("predecessorBundleFingerprint",
                    material.predecessorBundleFingerprint());
        }
        RevocationAuthoritySnapshot revocation = material.revocationAuthority();
        node.putObject("revocationAuthority")
                .put("registryRef", revocation.registryRef())
                .put("revision", revocation.revision())
                .put("snapshotFingerprint", revocation.snapshotFingerprint())
                .put("observedAt", revocation.observedAt().toString())
                .put("expiresAt", revocation.expiresAt().toString());
        return node;
    }

    private static AdmissionLifecycleMaterial lifecycle(ObjectNode node) {
        requireFields(node, Set.of("bundleFingerprint", "bundleId", "revision",
                "lifecycleState", "predecessorBundleFingerprint", "revocationAuthority"));
        ObjectNode revocation = object(node.get("revocationAuthority"));
        requireFields(revocation, Set.of("registryRef", "revision", "snapshotFingerprint",
                "observedAt", "expiresAt"));
        JsonNode predecessorNode = node.get("predecessorBundleFingerprint");
        String predecessor = predecessorNode.isNull()
                ? null : fingerprint(node, "predecessorBundleFingerprint");
        return new AdmissionLifecycleMaterial(fingerprint(node, "bundleFingerprint"),
                reference(node, "bundleId"), positiveLong(node, "revision"),
                text(node, "lifecycleState"), predecessor,
                new RevocationAuthoritySnapshot(reference(revocation, "registryRef"),
                        positiveLong(revocation, "revision"),
                        fingerprint(revocation, "snapshotFingerprint"),
                        instant(revocation, "observedAt"),
                        instant(revocation, "expiresAt")));
    }

    private static final class StoredLease {
        private final String requestFingerprint;
        private final ExecutionLeaseReceipt receipt;

        private StoredLease(String requestFingerprint, ExecutionLeaseReceipt receipt) {
            this.requestFingerprint = requestFingerprint;
            this.receipt = receipt;
        }

        @Override
        public String toString() {
            return "StoredLease[material=REDACTED]";
        }
    }

    private record FileIdentity(Object fileKey, long size, long modifiedMillis) { }

    private static final class UnsafeStoreException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static final class StateUnavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
