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
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseCommitStatus;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseReceipt;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseRequest;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseTransitionWitness;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceExecutionLeaseCommitResult;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceExecutionLeaseAttempt;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceExecutionLeaseTransactionResult;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceJournalResult;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceJournalStatus;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceLeaseBudget;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceTransactionJournal;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExistingEvidenceRecoveryResult;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExistingEvidenceRecoveryJournal;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExistingEvidenceRecoveryStatus;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.RevocationAuthoritySnapshot;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioDeploymentStateObservation;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.TimeUnit;
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
    static final String TRANSITION_EVIDENCE_PREFIX =
            "execution-lease-transition-evidence-v1-g";
    static final String TRANSITION_EVIDENCE_SUFFIX = ".json";
    static final int MAX_STATE_BYTES = 4 * 1024 * 1024;
    static final int MAX_CHECKPOINT_BYTES = 16 * 1024;
    static final int MAX_REVOCATION_HEAD_BYTES = 16 * 1024;
    static final int MAX_DESCRIPTOR_BYTES = 16 * 1024;
    static final int MAX_TRANSITION_EVIDENCE_BYTES = 256 * 1024;
    static final long MAX_STORE_CLOSURE_BYTES = 32L * 1024 * 1024;
    static final int MAX_LEASES = 1024;

    private static final String DESCRIPTOR_VERSION =
            "resource-gateway.capability-studio.execution-lease-store-descriptor.v1";
    private static final String STATE_VERSION_V2 =
            "resource-gateway.capability-studio.execution-lease-state.v2";
    private static final String STATE_VERSION_V4 =
            "resource-gateway.capability-studio.execution-lease-state.v4";
    private static final String STATE_CORE_VERSION =
            "resource-gateway.capability-studio.execution-lease-state-core.v1";
    private static final String STATE_COMMITMENT_VERSION =
            "resource-gateway.capability-studio.execution-lease-state-commitment.v4";
    private static final String TRANSITION_EVIDENCE_MATERIAL_VERSION =
            "resource-gateway.capability-studio.execution-lease-transition-evidence-material.v2";
    private static final String TRANSITION_EVIDENCE_VERSION =
            "resource-gateway.capability-studio.execution-lease-transition-evidence.v1";
    private static final String LEASE_INVENTORY_VERSION =
            "resource-gateway.capability-studio.execution-lease-inventory.v1";
    private static final String CHECKPOINT_VERSION =
            "resource-gateway.capability-studio.execution-lease-checkpoint.v1";
    private static final String EVIDENCE_CHECKPOINT_VERSION =
            "resource-gateway.capability-studio.execution-lease-checkpoint.v2";
    static final String REVOCATION_HEAD_VERSION =
            "resource-gateway.capability-studio.admission-revocation-head.v1";
    static final String REVOCATION_HEAD_UPDATE_VERSION =
            "resource-gateway.capability-studio.revocation-head-update.v1";
    private static final String REVOCATION_HEAD_UPDATE_RECEIPT_VERSION =
            "resource-gateway.capability-studio.revocation-head-update-receipt.v1";
    private static final String VERIFIED = "LIFECYCLE_VERIFIED";
    private static final String REJECTED = "LIFECYCLE_REJECTED";
    private static final String UNAVAILABLE = "DEPLOYMENT_STATE_UNAVAILABLE";
    static final String CAPACITY_UNAVAILABLE =
            "DEPLOYMENT_STATE_CAPACITY_UNAVAILABLE";
    private static final String COMMITTED = "LEASE_COMMITTED";
    private static final String RECOVERED = "LEASE_RECOVERED";
    private static final String LEASE_REJECTED = "LEASE_REJECTED";
    private static final String EVIDENCE_JOURNAL_INVALID = "EVIDENCE_JOURNAL_INVALID";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern STORE_ID = Pattern.compile("store:[0-9a-f]{64}");
    private static final Pattern REFERENCE = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,511}");
    private static final ObjectMapper JSON = new ObjectMapper(new JsonFactory().rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());
    private static final int LOCAL_LOCK_STRIPES = 64;
    private static final long OBSERVATION_LOCK_TIMEOUT_NANOS =
            java.time.Duration.ofSeconds(5).toNanos();
    private static final long OBSERVATION_LOCK_RETRY_NANOS =
            java.time.Duration.ofMillis(10).toNanos();
    private static final ReentrantReadWriteLock[] LOCAL_LOCKS = localLockStripes();
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
        return prepareStore(configured, configurationFingerprint, initialRevocationHead,
                durability, metadata, STATE_VERSION_V2);
    }

    static PreparedStore prepareEvidenceStore(
            Path configured,
            String configurationFingerprint,
            RevocationAuthoritySnapshot initialRevocationHead) {
        return prepareStore(configured, configurationFingerprint, initialRevocationHead,
                NIO_DURABILITY, NIO_METADATA, STATE_VERSION_V4);
    }

    private static PreparedStore prepareStore(
            Path configured,
            String configurationFingerprint,
            RevocationAuthoritySnapshot initialRevocationHead,
            Durability durability,
            Metadata metadata,
            String stateVersion) {
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
        Lock localLock = localLock(root).writeLock();
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
                    descriptor, localLock, checkedDurability, checkedMetadata, stateVersion);
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
        return openExistingStore(configured, expectedDescriptorFingerprint,
                NIO_DURABILITY);
    }

    static PreparedStore openExistingEvidenceWriterStoreForTesting(
            Path configured,
            String expectedDescriptorFingerprint,
            Durability durability) {
        PreparedStore store = openExistingStore(configured,
                expectedDescriptorFingerprint, durability);
        if (!store.supportsEvidenceWitness()) {
            throw invalidRoot();
        }
        return store;
    }

    private static PreparedStore openExistingStore(
            Path configured,
            String expectedDescriptorFingerprint,
            Durability durability) {
        Path root = requireStateRoot(configured);
        String expected = requireFingerprint(
                expectedDescriptorFingerprint, "expectedDescriptorFingerprint");
        Path lockFile = directChild(root, LOCK_FILE);
        Lock localLock = localLock(root).writeLock();
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
                    java.util.Objects.requireNonNull(durability, "durability is required"),
                    NIO_METADATA,
                    detectStateVersion(directChild(root, STATE_FILE)));
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

    static PreparedStore openExistingEvidenceRecoveryStore(Path configured) {
        return openExistingEvidenceRecoveryStore(configured, NIO_METADATA);
    }

    static PreparedStore openExistingEvidenceRecoveryStore(
            Path configured, Metadata metadata) {
        Metadata checkedMetadata = java.util.Objects.requireNonNull(
                metadata, "metadata is required");
        Path root = requireStateRoot(configured, checkedMetadata);
        Path lockFile = directChild(root, LOCK_FILE);
        try {
            StoreDescriptor descriptor = parseDescriptor(readSnapshot(
                    lockFile, MAX_DESCRIPTOR_BYTES, checkedMetadata));
            String version = detectStateVersion(directChild(root, STATE_FILE));
            if (!STATE_VERSION_V4.equals(version)) {
                throw new IOException("evidence state version unavailable");
            }
            return new PreparedStore(root, fileKeyForPreparation(root, checkedMetadata),
                    directChild(root, STATE_FILE), directChild(root, CHECKPOINT_FILE),
                    directChild(root, REVOCATION_HEAD_FILE), lockFile,
                    fileKeyForPreparation(lockFile, checkedMetadata),
                    directChild(root, TEMP_FILE), directChild(root, CHECKPOINT_TEMP_FILE),
                    directChild(root, REVOCATION_HEAD_TEMP_FILE),
                    directChild(root, DESCRIPTOR_TEMP_FILE), descriptor,
                    localLock(root).writeLock(), NIO_DURABILITY, checkedMetadata, version);
        } catch (InvalidStoreMaterial invalid) {
            throw invalidRoot();
        } catch (DeploymentUnavailableException unavailable) {
            throw unavailable;
        } catch (IOException | RuntimeException unavailable) {
            throw new DeploymentUnavailableException();
        }
    }

    static String existingConfigurationFingerprint(Path configured) {
        Path root = requireStateRoot(configured);
        Path descriptor = directChild(root, LOCK_FILE);
        try {
            BasicFileAttributes attributes = NIO_METADATA.readAttributes(descriptor);
            if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                throw invalidRoot();
            }
            return parseDescriptor(readSnapshot(
                    descriptor, MAX_DESCRIPTOR_BYTES, NIO_METADATA))
                    .configurationFingerprint;
        } catch (NoSuchFileException missing) {
            return null;
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (IOException | RuntimeException unavailable) {
            throw new DeploymentUnavailableException();
        }
    }

    static CapabilityStudioDeploymentStateObservation.Observation observeExistingStore(
            Path configured,
            String expectedDescriptorFingerprint,
            CapabilityStudioDeploymentStateObservation.Phase phase,
            String evidenceTransactionId) {
        Path root = requireStateRoot(configured);
        String expected = requireFingerprint(expectedDescriptorFingerprint,
                "expectedDescriptorFingerprint");
        requireFingerprint(evidenceTransactionId, "evidenceTransactionId");
        java.util.Objects.requireNonNull(phase, "phase is required");
        return observeExistingStore(root, expected, phase, evidenceTransactionId,
                OBSERVATION_LOCK_TIMEOUT_NANOS, System::nanoTime);
    }

    static CapabilityStudioDeploymentStateObservation.Observation observeExistingStore(
            Path root,
            String expected,
            CapabilityStudioDeploymentStateObservation.Phase phase,
            String evidenceTransactionId,
            long timeoutNanos,
            MonotonicTicker ticker) {
        ObservationLeaseBudget budget = new ObservationLeaseBudget(timeoutNanos, ticker);
        Lock readLock = localLock(root).readLock();
        if (!tryLocalLock(readLock, budget)) {
            throw new DeploymentUnavailableException();
        }
        try {
            List<AncestorIdentity> ancestors = ancestorIdentities(root);
            Object rootKey = fileKeyForPreparation(root, NIO_METADATA);
            long rootUid = ((Number) Files.getAttribute(root, "unix:uid",
                    LinkOption.NOFOLLOW_LINKS)).longValue();
            Path descriptorFile = directChild(root, LOCK_FILE);
            FileIdentity descriptorIdentity = requirePrivateFile(
                    descriptorFile, NIO_METADATA);
            try (FileChannel channel = FileChannel.open(descriptorFile,
                    StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                 FileLock lock = trySharedLock(channel, budget)) {
                if (!lock.isShared()) {
                    throw new IOException("shared lock unavailable");
                }
                ensureRootIdentity(root, rootKey, NIO_METADATA);
                StoreClosure first = readStoreClosure(root, rootUid);
                StoreDescriptor descriptor = parseDescriptor(first.descriptor.bytes);
                if (!expected.equals(descriptor.descriptorFingerprint)
                        || !java.util.Objects.equals(descriptorIdentity.fileKey(),
                        first.descriptor.identity.fileKey())) {
                    throw new InvalidStoreMaterial();
                }
                String stateVersion = stateVersion(first.state.bytes);
                PreparedStore view = new PreparedStore(root, rootKey,
                        directChild(root, STATE_FILE), directChild(root, CHECKPOINT_FILE),
                        directChild(root, REVOCATION_HEAD_FILE), descriptorFile,
                        descriptorIdentity.fileKey(), directChild(root, TEMP_FILE),
                        directChild(root, CHECKPOINT_TEMP_FILE),
                        directChild(root, REVOCATION_HEAD_TEMP_FILE),
                        directChild(root, DESCRIPTOR_TEMP_FILE), descriptor, readLock,
                        NIO_DURABILITY, NIO_METADATA, stateVersion);
                State state = view.parseState(first.state.bytes);
                Checkpoint checkpoint = view.parseCheckpoint(first.checkpoint.bytes);
                RevocationHead head = RevocationHead.parse(first.revocationHead.bytes,
                        descriptor.descriptorFingerprint);
                if (!checkpoint.matchesState(state)
                        || !checkpoint.matchesRevocationHead(head)) {
                    if (singleGenerationCrashIntermediate(state, checkpoint, head)) {
                        throw new IOException("store crash intermediate unavailable");
                    }
                    throw new InvalidStoreMaterial();
                }
                ReplayIndex replay = view.replay(state, checkpoint, budget);
                view.validateTransitionEvidenceFiles(
                        state, checkpoint, head, first, replay, budget);
                StoreClosureSeal second = readStoreClosureSeal(root, rootUid);
                ensureRootIdentity(root, rootKey, NIO_METADATA);
                if (!first.seal.equals(second)
                        || !ancestors.equals(ancestorIdentities(root))) {
                    throw new IOException("store changed");
                }
                return observation(phase, evidenceTransactionId, descriptor,
                        state, checkpoint, head, first);
            }
        } catch (InvalidStoreMaterial invalid) {
            throw new IllegalStateException("deployment state material is invalid");
        } catch (DeploymentUnavailableException unavailable) {
            throw unavailable;
        } catch (IOException | RuntimeException unavailable) {
            throw new DeploymentUnavailableException();
        } finally {
            readLock.unlock();
        }
    }

    private static boolean tryLocalLock(Lock lock, LockBudget budget) {
        try {
            long remaining = budget.remaining();
            return remaining > 0 && lock.tryLock(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static FileLock trySharedLock(
            FileChannel channel, LockBudget budget) throws IOException {
        long attempts = Math.max(1L, Math.min(10_000L,
                ceilingDivide(budget.initialBudget(), OBSERVATION_LOCK_RETRY_NANOS)));
        for (long attempt = 0; attempt < attempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new IOException("shared lock interrupted");
            }
            long remaining = budget.remaining();
            if (remaining <= 0) {
                throw new IOException("shared lock unavailable");
            }
            try {
                FileLock lock = channel.tryLock(0L, Long.MAX_VALUE, true);
                if (lock != null) {
                    if (!lock.isShared()) {
                        lock.release();
                        throw new IOException("shared lock unavailable");
                    }
                    return lock;
                }
            } catch (OverlappingFileLockException unavailable) {
                // A writer in this JVM owns the descriptor; retry within the same budget.
            } catch (UnsupportedOperationException unavailable) {
                throw new IOException("shared lock unavailable", unavailable);
            }
            long pause = Math.min(OBSERVATION_LOCK_RETRY_NANOS, budget.remaining());
            if (pause <= 0) {
                throw new IOException("shared lock unavailable");
            }
            try {
                TimeUnit.NANOSECONDS.sleep(pause);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("shared lock interrupted", interrupted);
            }
        }
        throw new IOException("shared lock unavailable");
    }

    private static FileLock tryExclusiveLock(
            FileChannel channel, LockBudget budget) throws IOException {
        long attempts = Math.max(1L, Math.min(10_000L,
                ceilingDivide(budget.initialBudget(), OBSERVATION_LOCK_RETRY_NANOS)));
        for (long attempt = 0; attempt < attempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new IOException("exclusive lock interrupted");
            }
            if (budget.remaining() <= 0) {
                throw new IOException("exclusive lock unavailable");
            }
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException unavailable) {
                // Another transaction in this JVM owns the descriptor.
            }
            long pause = Math.min(OBSERVATION_LOCK_RETRY_NANOS, budget.remaining());
            if (pause <= 0) {
                throw new IOException("exclusive lock unavailable");
            }
            try {
                TimeUnit.NANOSECONDS.sleep(pause);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("exclusive lock interrupted", interrupted);
            }
        }
        throw new IOException("exclusive lock unavailable");
    }

    private static long ceilingDivide(long value, long divisor) {
        if (value <= 0 || divisor <= 0) {
            return 1;
        }
        return 1L + (value - 1L) / divisor;
    }

    private static StoreClosure readStoreClosure(Path root, long rootUid) throws IOException {
        return readStoreClosure(root, rootUid,
                (path, maximumBytes) -> readSnapshot(path, maximumBytes, NIO_METADATA));
    }

    static void validateStoreClosureForTesting(
            Path root, long rootUid, ClosureContentReader reader) throws IOException {
        readStoreClosure(root, rootUid, reader);
    }

    static void validateStoreClosureForTesting(
            Path root,
            long rootUid,
            ClosureMetadataReader metadataReader,
            ClosureContentReader contentReader) throws IOException {
        readStoreClosure(root, rootUid, metadataReader, contentReader);
    }

    static void validateRecoverableStoreClosureForTesting(
            Path root,
            long rootUid,
            ClosureMetadataReader metadataReader,
            ClosureContentReader contentReader) throws IOException {
        StoreClosureInventory inventory = inventoryStoreClosure(
                root, rootUid, true, metadataReader);
        for (ClosureEntry entry : inventory.entries.values()) {
            observeFile(entry.path, entry.maximumBytes, entry.identity, contentReader);
        }
    }

    private static StoreClosure readStoreClosure(
            Path root, long rootUid, ClosureContentReader reader) throws IOException {
        return readStoreClosure(root, rootUid,
                FilesystemDeploymentAdmissionAuthority::runtimeFileIdentity, reader);
    }

    private static StoreClosure readStoreClosure(
            Path root,
            long rootUid,
            ClosureMetadataReader metadataReader,
            ClosureContentReader reader) throws IOException {
        StoreClosureInventory inventory = inventoryStoreClosure(
                root, rootUid, false, metadataReader);
        TreeMap<String, ObservedFile> observed = new TreeMap<>();
        for (Map.Entry<String, ClosureEntry> entry : inventory.entries.entrySet()) {
            ClosureEntry value = entry.getValue();
            observed.put(entry.getKey(), observeFile(value.path, value.maximumBytes,
                    value.identity, reader));
        }
        TreeMap<String, ObservedFile> transitions = new TreeMap<>();
        observed.forEach((name, value) -> {
            if (transitionEvidenceGeneration(name) > 0) {
                transitions.put(name, value);
            }
        });
        StoreClosureSeal seal = seal(observed);
        return new StoreClosure(observed.get(LOCK_FILE), observed.get(STATE_FILE),
                observed.get(CHECKPOINT_FILE), observed.get(REVOCATION_HEAD_FILE),
                transitions, seal);
    }

    private static StoreClosureSeal readStoreClosureSeal(Path root, long rootUid)
            throws IOException {
        StoreClosureInventory inventory = inventoryStoreClosure(root, rootUid);
        TreeMap<String, SealedFile> files = new TreeMap<>();
        for (Map.Entry<String, ClosureEntry> entry : inventory.entries.entrySet()) {
            ClosureEntry value = entry.getValue();
            files.put(entry.getKey(), digestFile(value.path, value.identity));
        }
        return new StoreClosureSeal(files);
    }

    private static StoreClosureInventory inventoryStoreClosure(Path root, long rootUid)
            throws IOException {
        return inventoryStoreClosure(root, rootUid, false);
    }

    private static StoreClosureInventory inventoryStoreClosure(
            Path root, long rootUid, boolean allowPendingTransition) throws IOException {
        return inventoryStoreClosure(root, rootUid, allowPendingTransition,
                FilesystemDeploymentAdmissionAuthority::runtimeFileIdentity);
    }

    private static StoreClosureInventory inventoryStoreClosure(
            Path root,
            long rootUid,
            boolean allowPendingTransition,
            ClosureMetadataReader metadataReader) throws IOException {
        Set<String> expected = Set.of(LOCK_FILE, STATE_FILE, CHECKPOINT_FILE,
                REVOCATION_HEAD_FILE);
        Set<String> names = new HashSet<>();
        TreeMap<String, ClosureEntry> entries = new TreeMap<>();
        long aggregateBytes = 0;
        boolean pendingTransition = false;
        try (var children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (!names.add(name) || names.size() > MAX_LEASES + expected.size()
                        + (allowPendingTransition ? 1 : 0)) {
                    throw new InvalidStoreMaterial();
                }
                int maximumBytes;
                if (!expected.contains(name)) {
                    long generation = transitionEvidenceGeneration(name);
                    if (generation < 1) {
                        boolean pending = name.startsWith(
                                "." + TRANSITION_EVIDENCE_PREFIX)
                                && name.endsWith(TRANSITION_EVIDENCE_SUFFIX + ".tmp")
                                && transitionEvidenceGeneration(name.substring(
                                1, name.length() - ".tmp".length())) > 0;
                        if (pending && !allowPendingTransition) {
                            throw new IOException(
                                    "transition evidence recovery is required");
                        }
                        if (!pending || pendingTransition) {
                            throw new InvalidStoreMaterial();
                        }
                        pendingTransition = true;
                    }
                    maximumBytes = MAX_TRANSITION_EVIDENCE_BYTES;
                } else {
                    maximumBytes = maximumBytes(name);
                }
                ClosureFileMetadata identity = metadataReader.read(child);
                if (identity.uid != rootUid || identity.size < 1
                        || identity.size > maximumBytes) {
                    throw new IOException("store closure unavailable");
                }
                aggregateBytes = checkedAggregateBytes(aggregateBytes, identity.size);
                if (entries.put(name,
                        new ClosureEntry(child, maximumBytes, identity)) != null) {
                    throw new InvalidStoreMaterial();
                }
            }
        }
        if (!names.containsAll(expected)) {
            throw new IOException("store closure unavailable");
        }
        return new StoreClosureInventory(entries, aggregateBytes);
    }

    private static int maximumBytes(String name) {
        return switch (name) {
            case LOCK_FILE -> MAX_DESCRIPTOR_BYTES;
            case STATE_FILE -> MAX_STATE_BYTES;
            case CHECKPOINT_FILE -> MAX_CHECKPOINT_BYTES;
            case REVOCATION_HEAD_FILE -> MAX_REVOCATION_HEAD_BYTES;
            default -> throw new IllegalArgumentException("unknown store document");
        };
    }

    static long checkedAggregateBytesForTesting(long current, long additional)
            throws IOException {
        return checkedAggregateBytes(current, additional);
    }

    private static long checkedAggregateBytes(long current, long additional)
            throws IOException {
        if (current < 0 || additional < 0 || additional > Long.MAX_VALUE - current) {
            throw new IOException("store closure capacity unavailable");
        }
        long total = current + additional;
        if (total > MAX_STORE_CLOSURE_BYTES) {
            throw new IOException("store closure capacity unavailable");
        }
        return total;
    }

    private static long transitionEvidenceGeneration(String name) {
        if (!name.startsWith(TRANSITION_EVIDENCE_PREFIX)
                || !name.endsWith(TRANSITION_EVIDENCE_SUFFIX)) {
            return -1;
        }
        String digits = name.substring(TRANSITION_EVIDENCE_PREFIX.length(),
                name.length() - TRANSITION_EVIDENCE_SUFFIX.length());
        if (!digits.matches("[0-9]{20}")) {
            return -1;
        }
        try {
            long value = Long.parseLong(digits);
            return value > 0 ? value : -1;
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private static boolean singleGenerationCrashIntermediate(
            State state, Checkpoint checkpoint, RevocationHead head) {
        boolean stateSuccessor = state.generation == checkpoint.generation + 1
                && java.util.Objects.equals(state.previousStateFingerprint,
                checkpoint.stateFingerprint);
        boolean headSuccessor = head.sequence == checkpoint.revocationHeadSequence + 1
                && java.util.Objects.equals(head.previousHeadFingerprint,
                checkpoint.revocationHeadFingerprint);
        return (stateSuccessor && checkpoint.matchesRevocationHead(head))
                || (checkpoint.matchesState(state) && headSuccessor);
    }

    private static Path transitionEvidenceFile(Path root, long generation) {
        if (generation < 1) {
            throw new IllegalArgumentException("transition generation is invalid");
        }
        return directChild(root, TRANSITION_EVIDENCE_PREFIX
                + String.format("%020d", generation) + TRANSITION_EVIDENCE_SUFFIX);
    }

    private static Path transitionEvidenceSource(Path root, long generation) {
        Path target = transitionEvidenceFile(root, generation);
        return directChild(root, "." + target.getFileName() + ".tmp");
    }

    private static CapabilityStudioDeploymentStateObservation.Observation observation(
            CapabilityStudioDeploymentStateObservation.Phase phase,
            String evidenceTransactionId,
            StoreDescriptor descriptor,
            State state,
            Checkpoint checkpoint,
            RevocationHead head,
            StoreClosure closure) {
        return observation(phase, evidenceTransactionId, descriptor, state, checkpoint, head,
                closure.descriptor.bytes, closure.state.bytes, closure.checkpoint.bytes,
                closure.revocationHead.bytes);
    }

    private static CapabilityStudioDeploymentStateObservation.Observation observation(
            CapabilityStudioDeploymentStateObservation.Phase phase,
            String evidenceTransactionId,
            StoreDescriptor descriptor,
            State state,
            Checkpoint checkpoint,
            RevocationHead head,
            byte[] descriptorBytes,
            byte[] stateBytes,
            byte[] checkpointBytes,
            byte[] revocationHeadBytes) {
        String lifecycle = state.lifecycleHead == null
                ? null : state.lifecycleHead.fingerprint();
        return CapabilityStudioDeploymentStateObservation.create(phase,
                evidenceTransactionId, descriptor.descriptorFingerprint,
                sha256(descriptorBytes), state.generation,
                state.previousStateFingerprint, state.stateFingerprint,
                sha256(stateBytes), checkpoint.checkpointFingerprint,
                sha256(checkpointBytes), head.sequence,
                head.headFingerprint, sha256(revocationHeadBytes), lifecycle,
                state.fencingSequence, state.leases.size(),
                leaseInventoryFingerprint(state));
    }

    private static ObservedFile observeFile(
            Path file,
            int maximumBytes,
            ClosureFileMetadata expected,
            ClosureContentReader reader) throws IOException {
        ClosureFileMetadata before = runtimeFileIdentity(file);
        if (!expected.equals(before)) {
            throw new IOException("state document changed");
        }
        byte[] bytes = reader.read(file, maximumBytes);
        ClosureFileMetadata after = runtimeFileIdentity(file);
        if (!before.equals(after) || before.size != bytes.length
                || bytes.length > maximumBytes) {
            throw new IOException("state document changed");
        }
        return new ObservedFile(bytes, before);
    }

    private static SealedFile digestFile(Path file, ClosureFileMetadata expected)
            throws IOException {
        ClosureFileMetadata before = runtimeFileIdentity(file);
        if (!expected.equals(before)) {
            throw new IOException("state document changed");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
        long count = 0;
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (true) {
                int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                count += read;
                if (count > expected.size) {
                    throw new IOException("state document changed");
                }
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }
        ClosureFileMetadata after = runtimeFileIdentity(file);
        if (!before.equals(after) || count != before.size) {
            throw new IOException("state document changed");
        }
        return new SealedFile(before,
                "sha256:" + HexFormat.of().formatHex(digest.digest()));
    }

    private static StoreClosureSeal seal(TreeMap<String, ObservedFile> files) {
        TreeMap<String, SealedFile> sealed = new TreeMap<>();
        files.forEach((name, observed) -> sealed.put(name,
                new SealedFile(observed.identity, sha256(observed.bytes))));
        return new StoreClosureSeal(sealed);
    }

    private static ClosureFileMetadata runtimeFileIdentity(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw new InvalidStoreMaterial();
        }
        if (attributes.fileKey() == null) {
            throw new IOException("state document unavailable");
        }
        long links = ((Number) Files.getAttribute(file, "unix:nlink",
                LinkOption.NOFOLLOW_LINKS)).longValue();
        long uid = ((Number) Files.getAttribute(file, "unix:uid",
                LinkOption.NOFOLLOW_LINKS)).longValue();
        int mode = ((Number) Files.getAttribute(file, "unix:mode",
                LinkOption.NOFOLLOW_LINKS)).intValue() & 0777;
        if (links != 1) {
            throw new InvalidStoreMaterial();
        }
        if (mode != 0600) {
            throw new IOException("state document permissions unavailable");
        }
        return new ClosureFileMetadata(attributes.fileKey(), links, uid, mode,
                attributes.size(), attributes.lastModifiedTime());
    }

    private static List<AncestorIdentity> ancestorIdentities(Path root) throws IOException {
        List<AncestorIdentity> identities = new ArrayList<>();
        for (Path current = root; current != null; current = current.getParent()) {
            BasicFileAttributes attributes = Files.readAttributes(current,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                throw new InvalidStoreMaterial();
            }
            long device = ((Number) Files.getAttribute(current, "unix:dev",
                    LinkOption.NOFOLLOW_LINKS)).longValue();
            long inode = ((Number) Files.getAttribute(current, "unix:ino",
                    LinkOption.NOFOLLOW_LINKS)).longValue();
            long uid = ((Number) Files.getAttribute(current, "unix:uid",
                    LinkOption.NOFOLLOW_LINKS)).longValue();
            int mode = ((Number) Files.getAttribute(current, "unix:mode",
                    LinkOption.NOFOLLOW_LINKS)).intValue() & 07777;
            identities.add(new AncestorIdentity(device, inode, uid, mode));
        }
        long deploymentUid = identities.getFirst().uid;
        Set<Long> allowedOwners = Set.of(0L, deploymentUid);
        for (int index = 0; index < identities.size(); index++) {
            AncestorIdentity identity = identities.get(index);
            if (!allowedOwners.contains(identity.uid)) {
                throw new IOException("state ancestor owner unavailable");
            }
            if ((identity.mode & 0022) != 0
                    && ((identity.mode & 01000) == 0
                    || index == 0
                    || !allowedOwners.contains(identities.get(index - 1).uid))) {
                throw new IOException("state ancestor permissions unavailable");
            }
        }
        return List.copyOf(identities);
    }

    private static String stateVersion(byte[] bytes) throws IOException {
        try {
            ObjectNode node = object(JSON.readTree(bytes));
            String version = text(node, "messageVersion");
            if (!STATE_VERSION_V2.equals(version) && !STATE_VERSION_V4.equals(version)) {
                throw new IllegalArgumentException("state version");
            }
            return version;
        } catch (IOException | RuntimeException invalid) {
            throw new InvalidStoreMaterial();
        }
    }

    private static String leaseInventoryFingerprint(State state) {
        StringBuilder message = new StringBuilder("{\"messageVersion\":\"")
                .append(LEASE_INVENTORY_VERSION).append("\",\"leases\":[");
        boolean first = true;
        for (Map.Entry<String, StoredLease> entry : state.leases.entrySet()) {
            if (!first) {
                message.append(',');
            }
            first = false;
            StoredLease lease = entry.getValue();
            message.append("{\"leaseKey\":\"").append(entry.getKey())
                    .append("\",\"requestFingerprint\":\"")
                    .append(lease.requestFingerprint)
                    .append("\",\"receiptFingerprint\":\"")
                    .append(lease.receipt.fingerprint())
                    .append("\",\"transitionWitnessFingerprint\":");
            if (lease.transitionWitness == null) {
                message.append("null");
            } else {
                message.append('"').append(lease.transitionWitness.fingerprint()).append('"');
            }
            message.append('}');
        }
        return sha256(message.append("]}").toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String transitionEvidenceMaterialFingerprint(
            String evidenceTransactionId,
            String receiptFingerprint,
            String witnessMaterialFingerprint,
            CapabilityStudioDeploymentStateObservation.Observation before,
            long postRevocationHeadSequence,
            String postRevocationHeadFingerprint,
            String postRevocationHeadRawFingerprint,
            long postGeneration,
            long postFencingSequence) {
        ObjectNode node = JSON.createObjectNode();
        node.put("messageVersion", TRANSITION_EVIDENCE_MATERIAL_VERSION);
        node.put("evidenceTransactionId", evidenceTransactionId);
        node.put("receiptFingerprint", receiptFingerprint);
        node.put("witnessMaterialFingerprint", witnessMaterialFingerprint);
        node.put("beforeObservationFingerprint", before.observationFingerprint());
        node.put("postRevocationHeadSequence", postRevocationHeadSequence);
        node.put("postRevocationHeadFingerprint", postRevocationHeadFingerprint);
        node.put("postRevocationHeadRawFingerprint", postRevocationHeadRawFingerprint);
        node.put("postGeneration", postGeneration);
        node.put("postFencingSequence", postFencingSequence);
        return sha256(writeJson(node));
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
            Path real = configured.toRealPath();
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
        } catch (StateUnavailable | InvalidStoreMaterial unavailable) {
            return DeploymentAuthorityDecision.unavailable(UNAVAILABLE);
        }
    }

    ExecutionLeaseCommitResult commit(ExecutionLeaseRequest request) {
        if (!validRequest(request)) {
            return ExecutionLeaseCommitResult.rejected(LEASE_REJECTED);
        }
        try {
            return withState(state -> commitLocked(state, request));
        } catch (StateUnavailable | InvalidStoreMaterial unavailable) {
            return ExecutionLeaseCommitResult.unavailable(UNAVAILABLE);
        }
    }

    EvidenceExecutionLeaseCommitResult commitWithWitness(ExecutionLeaseRequest request) {
        if (!validRequest(request)) {
            return evidenceResult(ExecutionLeaseCommitStatus.REJECTED, null, null,
                    LEASE_REJECTED);
        }
        try {
            return withState(state -> {
                CommitOutcome outcome = commitInternal(
                        state, request, false, null, null, null);
                return evidenceResult(outcome.status, outcome.receipt,
                        outcome.witness, outcome.reasonCode);
            });
        } catch (StateUnavailable unavailable) {
            return evidenceResult(ExecutionLeaseCommitStatus.UNAVAILABLE, null, null,
                    UNAVAILABLE);
        }
    }

    EvidenceExecutionLeaseTransactionResult commitEvidenceTransaction(
            EvidenceExecutionLeaseAttempt attempt,
            EvidenceTransactionJournal journal) {
        if (attempt == null || journal == null || !validRequest(attempt.request())) {
            return new EvidenceExecutionLeaseTransactionResult(null, null,
                    evidenceResult(ExecutionLeaseCommitStatus.REJECTED, null, null,
                            LEASE_REJECTED));
        }
        try {
            return store.withLockedState(state -> {
                DisjointEvidenceIdentity disjoint = requireDisjointEvidenceParent(
                        attempt.evidencePublicationParent());
                StoredLease existing = state.leases.get(
                        leaseKey(attempt.request().executionLeaseId()));
                if (existing != null) {
                    if (!existing.requestFingerprint.equals(
                            attempt.request().commitIdentityFingerprint())
                            || !validStoredReceipt(existing.receipt, attempt.request())
                            || existing.transitionWitness == null
                            || existing.evidenceClosureMaterialFingerprint == null) {
                        return new EvidenceExecutionLeaseTransactionResult(null, null,
                                evidenceResult(ExecutionLeaseCommitStatus.REJECTED,
                                        null, null, LEASE_REJECTED));
                    }
                    TransitionEvidence historical;
                    try {
                        historical = store.readTransitionEvidence(state, existing);
                    } catch (IOException unavailable) {
                        throw new StateUnavailable();
                    }
                    if (!attempt.evidenceTransactionId().equals(
                            historical.evidenceTransactionId)) {
                        return new EvidenceExecutionLeaseTransactionResult(null, null,
                                evidenceResult(ExecutionLeaseCommitStatus.REJECTED,
                                        null, null, LEASE_REJECTED));
                    }
                    EvidenceExecutionLeaseCommitResult recovered = evidenceResult(
                            ExecutionLeaseCommitStatus.RECOVERED, existing.receipt,
                            existing.transitionWitness, RECOVERED);
                    EvidenceExecutionLeaseTransactionResult journalFailure = journalFailure(
                            journal.persistCommittedResult(attempt, historical.before,
                                    historical.after, recovered));
                    if (journalFailure != null) {
                        return journalFailure;
                    }
                    disjoint.recheck();
                    return new EvidenceExecutionLeaseTransactionResult(historical.before,
                            historical.after, recovered);
                }
                CapabilityStudioDeploymentStateObservation.Observation current =
                        store.observeLocked(CapabilityStudioDeploymentStateObservation
                                        .Phase.BEFORE,
                                attempt.evidenceTransactionId(), state,
                                new SharedEvidenceLeaseBudget(attempt.lockBudget()));
                EvidenceJournalResult<CapabilityStudioDeploymentStateObservation.Observation>
                        prepared = journal.prepareBeforeResult(attempt, current);
                EvidenceExecutionLeaseTransactionResult journalFailure =
                        journalFailure(prepared);
                if (journalFailure != null) {
                    return journalFailure;
                }
                CapabilityStudioDeploymentStateObservation.Observation before =
                        prepared.value();
                disjoint.recheck();
                if (before == null
                        || before.phase()
                        != CapabilityStudioDeploymentStateObservation.Phase.BEFORE
                        || !before.evidenceTransactionId().equals(
                        attempt.evidenceTransactionId())
                        || !before.storeDescriptorFingerprint().equals(
                        store.descriptorFingerprint())) {
                    return invalidJournalTransaction();
                }
                if (!before.equals(current)) {
                    return invalidJournalTransaction();
                }
                CommitOutcome outcome = commitInternal(state, attempt.request(), true,
                        attempt.evidenceTransactionId(), before, attempt.lockBudget());
                EvidenceExecutionLeaseCommitResult result = evidenceResult(
                        outcome.status, outcome.receipt, outcome.witness,
                        outcome.reasonCode);
                if (outcome.status != ExecutionLeaseCommitStatus.COMMITTED
                        && outcome.status != ExecutionLeaseCommitStatus.RECOVERED) {
                    return new EvidenceExecutionLeaseTransactionResult(null, null, result);
                }
                if (outcome.transitionEvidence == null
                        || !before.equals(outcome.transitionEvidence.before)) {
                    throw new StateUnavailable();
                }
                CapabilityStudioDeploymentStateObservation.Observation after =
                        outcome.transitionEvidence.after;
                EvidenceExecutionLeaseTransactionResult transaction =
                        new EvidenceExecutionLeaseTransactionResult(before, after, result);
                journalFailure = journalFailure(
                        journal.persistCommittedResult(attempt, before, after, result));
                if (journalFailure != null) {
                    return journalFailure;
                }
                disjoint.recheck();
                return transaction;
            }, attempt.lockBudget());
        } catch (StateUnavailable unavailable) {
            return new EvidenceExecutionLeaseTransactionResult(null, null,
                    evidenceResult(ExecutionLeaseCommitStatus.UNAVAILABLE, null, null,
                            UNAVAILABLE));
        }
    }

    private static EvidenceExecutionLeaseTransactionResult journalFailure(
            EvidenceJournalResult<?> result) {
        if (result == null || result.status() == null) {
            return invalidJournalTransaction();
        }
        return switch (result.status()) {
            case COMPLETED -> null;
            case INVALID -> invalidJournalTransaction();
            case UNAVAILABLE -> new EvidenceExecutionLeaseTransactionResult(null, null,
                    evidenceResult(ExecutionLeaseCommitStatus.UNAVAILABLE, null, null,
                            UNAVAILABLE));
        };
    }

    private static EvidenceExecutionLeaseTransactionResult invalidJournalTransaction() {
        return new EvidenceExecutionLeaseTransactionResult(null, null,
                evidenceResult(ExecutionLeaseCommitStatus.INVALID, null, null,
                        EVIDENCE_JOURNAL_INVALID));
    }

    EvidenceExecutionLeaseCommitResult recoverExistingWithWitness(
            ExecutionLeaseRequest request) {
        if (!validRequest(request)) {
            return evidenceResult(ExecutionLeaseCommitStatus.REJECTED, null, null,
                    LEASE_REJECTED);
        }
        try {
            return store.withLockedExistingState(state -> {
                StoredLease existing = state.leases.get(leaseKey(request.executionLeaseId()));
                if (existing == null) {
                    return evidenceResult(ExecutionLeaseCommitStatus.UNAVAILABLE,
                            null, null, UNAVAILABLE);
                }
                if (!existing.requestFingerprint.equals(
                        request.commitIdentityFingerprint())
                        || !validStoredReceipt(existing.receipt, request)) {
                    return evidenceResult(ExecutionLeaseCommitStatus.REJECTED,
                            null, null, LEASE_REJECTED);
                }
                if (existing.transitionWitness == null) {
                    return evidenceResult(ExecutionLeaseCommitStatus.UNAVAILABLE,
                            null, null, UNAVAILABLE);
                }
                return evidenceResult(ExecutionLeaseCommitStatus.RECOVERED,
                        existing.receipt, existing.transitionWitness, RECOVERED);
            });
        } catch (StateUnavailable unavailable) {
            return evidenceResult(ExecutionLeaseCommitStatus.UNAVAILABLE,
                    null, null, UNAVAILABLE);
        }
    }

    static ExistingEvidenceRecoveryResult recoverExistingOnly(
            PreparedStore store,
            EvidenceExecutionLeaseAttempt attempt,
            ExistingEvidenceRecoveryJournal journal) {
        if (store == null || attempt == null || journal == null) {
            return recovery(ExistingEvidenceRecoveryStatus.CONFLICT,
                    null, null, null, null, LEASE_REJECTED);
        }
        ExecutionLeaseRequest request = attempt.request();
        try {
            return store.withLockedExistingTransaction(state -> recoverLockedExisting(
                    store, state, attempt, journal, request), attempt.lockBudget(),
                    attempt.evidencePublicationParent());
        } catch (StateUnavailable unavailable) {
            return recovery(ExistingEvidenceRecoveryStatus.UNAVAILABLE,
                    null, null, null, null, UNAVAILABLE);
        } catch (RuntimeException invalid) {
            return recovery(ExistingEvidenceRecoveryStatus.CONFLICT,
                    null, null, null, null, LEASE_REJECTED);
        }
    }

    static ExistingEvidenceRecoveryResult recoverInterruptedWriter(
            PreparedStore store,
            EvidenceExecutionLeaseAttempt attempt,
            ExistingEvidenceRecoveryJournal journal) {
        if (store == null || attempt == null || journal == null) {
            return recovery(ExistingEvidenceRecoveryStatus.CONFLICT,
                    null, null, null, null, LEASE_REJECTED);
        }
        ExecutionLeaseRequest request = attempt.request();
        try {
            return store.withLockedState(state -> recoverLockedExisting(
                    store, state, attempt, journal, request), attempt.lockBudget(),
                    attempt.evidencePublicationParent());
        } catch (StateUnavailable unavailable) {
            return recovery(ExistingEvidenceRecoveryStatus.UNAVAILABLE,
                    null, null, null, null, UNAVAILABLE);
        } catch (RuntimeException invalid) {
            return recovery(ExistingEvidenceRecoveryStatus.CONFLICT,
                    null, null, null, null, LEASE_REJECTED);
        }
    }

    private static ExistingEvidenceRecoveryResult recoverLockedExisting(
            PreparedStore store,
            State state,
            EvidenceExecutionLeaseAttempt attempt,
            ExistingEvidenceRecoveryJournal journal,
            ExecutionLeaseRequest request) {
        RecoveryDisjointEvidenceIdentity disjoint = requireRecoveryDisjointEvidenceParent(
                store, attempt.evidencePublicationParent());
        if (attempt.lockBudget().remainingNanos() <= 0) {
            throw new StateUnavailable();
        }
        StoredLease existing = state.leases.get(leaseKey(request.executionLeaseId()));
        if (existing == null) {
            disjoint.recheck();
            EvidenceJournalResult<Void> closed = journal.closeAbsentResult(attempt);
            if (closed == null || closed.status() == EvidenceJournalStatus.INVALID) {
                return recovery(ExistingEvidenceRecoveryStatus.CONFLICT,
                        null, null, null, null, LEASE_REJECTED);
            }
            if (closed.status() == EvidenceJournalStatus.UNAVAILABLE) {
                return recovery(ExistingEvidenceRecoveryStatus.UNAVAILABLE,
                        null, null, null, null, UNAVAILABLE);
            }
            disjoint.recheck();
            if (attempt.lockBudget().remainingNanos() <= 0) {
                throw new StateUnavailable();
            }
            return recovery(ExistingEvidenceRecoveryStatus.ABSENT,
                    null, null, null, null, "LEASE_ABSENT");
        }
        if (!existing.requestFingerprint.equals(request.commitIdentityFingerprint())
                || !validExistingStoredReceipt(existing, request)
                || existing.transitionWitness == null
                || existing.evidenceClosureMaterialFingerprint == null
                || !store.descriptorFingerprint().equals(
                existing.transitionWitness.storeDescriptorFingerprint())) {
            return recovery(ExistingEvidenceRecoveryStatus.CONFLICT,
                    null, null, null, null, LEASE_REJECTED);
        }
        TransitionEvidence historical;
        try {
            historical = store.readTransitionEvidence(state, existing);
        } catch (IOException unavailable) {
            throw new StateUnavailable();
        }
        if (!attempt.evidenceTransactionId().equals(historical.evidenceTransactionId)) {
            return recovery(ExistingEvidenceRecoveryStatus.CONFLICT,
                    null, null, null, null, LEASE_REJECTED);
        }
        disjoint.recheck();
        if (attempt.lockBudget().remainingNanos() <= 0) {
            throw new StateUnavailable();
        }
        return recovery(ExistingEvidenceRecoveryStatus.FOUND,
                existing.receipt, existing.transitionWitness,
                historical.before, historical.after, RECOVERED);
    }

    private static boolean validExistingStoredReceipt(
            StoredLease existing, ExecutionLeaseRequest request) {
        ExecutionLeaseReceipt receipt = existing.receipt;
        AtomicAdmissionLifecycleCommitReceipt lifecycle = receipt.lifecycleCommitReceipt();
        ExecutionLeaseTransitionWitness witness = existing.transitionWitness;
        return receipt.requestFingerprint().equals(request.commitIdentityFingerprint())
                && receipt.lifecycleMaterial().equals(request.lifecycleMaterial())
                && lifecycle.deploymentAdmissionAuthorityMaterialFingerprint().equals(
                request.deploymentAdmissionAuthorityMaterialFingerprint())
                && lifecycle.lifecycleMaterialFingerprint().equals(
                request.lifecycleMaterial().fingerprint())
                && lifecycle.requestFingerprint().equals(
                request.commitIdentityFingerprint())
                && lifecycle.fencingSequence() > 0
                && witness != null
                && witness.requestFingerprint().equals(request.commitIdentityFingerprint())
                && witness.receiptFingerprint().equals(receipt.fingerprint());
    }

    private static ExistingEvidenceRecoveryResult recovery(
            ExistingEvidenceRecoveryStatus status,
            ExecutionLeaseReceipt receipt,
            ExecutionLeaseTransitionWitness witness,
            CapabilityStudioDeploymentStateObservation.Observation before,
            CapabilityStudioDeploymentStateObservation.Observation after,
            String reason) {
        return new ExistingEvidenceRecoveryResult(status, receipt, witness,
                before, after, reason);
    }

    private DisjointEvidenceIdentity requireDisjointEvidenceParent(Path configuredParent) {
        return requireDisjointEvidenceParent(store, configuredParent);
    }

    private static DisjointEvidenceIdentity requireDisjointEvidenceParent(
            PreparedStore preparedStore, Path configuredParent) {
        try {
            BasicFileAttributes direct = Files.readAttributes(configuredParent,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (direct.isSymbolicLink() || !direct.isDirectory()) {
                throw new InvalidStoreMaterial();
            }
            if (direct.fileKey() == null) {
                throw new IOException("evidence parent unavailable");
            }
            List<AncestorIdentity> evidence = ancestorIdentities(configuredParent);
            List<AncestorIdentity> state = ancestorIdentities(preparedStore.root);
            AncestorIdentity evidenceRoot = evidence.getFirst();
            AncestorIdentity stateRoot = state.getFirst();
            if (evidenceRoot.sameObject(stateRoot)
                    || evidence.stream().anyMatch(identity -> identity.sameObject(stateRoot))
                    || state.stream().anyMatch(identity -> identity.sameObject(evidenceRoot))) {
                throw new InvalidStoreMaterial();
            }
            return new DisjointEvidenceIdentity(configuredParent, preparedStore.root,
                    evidence, state);
        } catch (InvalidStoreMaterial invalid) {
            throw new IllegalArgumentException("evidence ancestry is invalid");
        } catch (IOException | RuntimeException unavailable) {
            throw new StateUnavailable();
        }
    }

    private static RecoveryDisjointEvidenceIdentity requireRecoveryDisjointEvidenceParent(
            PreparedStore preparedStore, Path configuredParent) {
        try {
            List<DirectoryIdentity> evidence = directoryIdentities(configuredParent);
            List<DirectoryIdentity> state = directoryIdentities(preparedStore.root);
            DirectoryIdentity evidenceRoot = evidence.getFirst();
            DirectoryIdentity stateRoot = state.getFirst();
            if (evidenceRoot.sameObject(stateRoot)
                    || evidence.stream().anyMatch(identity -> identity.sameObject(stateRoot))
                    || state.stream().anyMatch(identity -> identity.sameObject(evidenceRoot))) {
                throw new InvalidStoreMaterial();
            }
            return new RecoveryDisjointEvidenceIdentity(configuredParent, preparedStore.root,
                    evidence, state);
        } catch (InvalidStoreMaterial invalid) {
            throw invalid;
        } catch (IOException | RuntimeException unavailable) {
            throw new StateUnavailable();
        }
    }

    private static List<DirectoryIdentity> directoryIdentities(Path root) throws IOException {
        List<DirectoryIdentity> identities = new ArrayList<>();
        for (Path current = root; current != null; current = current.getParent()) {
            BasicFileAttributes attributes = Files.readAttributes(current,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                throw new InvalidStoreMaterial();
            }
            if (attributes.fileKey() == null) {
                throw new IOException("directory identity unavailable");
            }
            long device = ((Number) Files.getAttribute(current, "unix:dev",
                    LinkOption.NOFOLLOW_LINKS)).longValue();
            long inode = ((Number) Files.getAttribute(current, "unix:ino",
                    LinkOption.NOFOLLOW_LINKS)).longValue();
            long links = ((Number) Files.getAttribute(current, "unix:nlink",
                    LinkOption.NOFOLLOW_LINKS)).longValue();
            long uid = ((Number) Files.getAttribute(current, "unix:uid",
                    LinkOption.NOFOLLOW_LINKS)).longValue();
            int mode = ((Number) Files.getAttribute(current, "unix:mode",
                    LinkOption.NOFOLLOW_LINKS)).intValue() & 07777;
            if (links < 1) {
                throw new IOException("directory identity unavailable");
            }
            identities.add(new DirectoryIdentity(attributes.fileKey(), device, inode,
                    links, uid, mode));
        }
        long deploymentUid = identities.getFirst().uid;
        Set<Long> allowedOwners = Set.of(0L, deploymentUid);
        for (int index = 0; index < identities.size(); index++) {
            DirectoryIdentity identity = identities.get(index);
            if (!allowedOwners.contains(identity.uid)
                    || ((identity.mode & 0022) != 0
                    && ((identity.mode & 01000) == 0 || index == 0
                    || !allowedOwners.contains(identities.get(index - 1).uid)))) {
                throw new IOException("directory ancestry unavailable");
            }
        }
        return List.copyOf(identities);
    }

    private ExecutionLeaseCommitResult commitLocked(State state, ExecutionLeaseRequest request) {
        CommitOutcome outcome = commitInternal(state, request, false, null, null, null);
        return switch (outcome.status) {
            case COMMITTED -> ExecutionLeaseCommitResult.committed(
                    outcome.receipt, outcome.reasonCode);
            case RECOVERED -> ExecutionLeaseCommitResult.recovered(
                    outcome.receipt, outcome.reasonCode);
            case INVALID -> new ExecutionLeaseCommitResult(
                    ExecutionLeaseCommitStatus.INVALID, null, outcome.reasonCode);
            case REJECTED -> ExecutionLeaseCommitResult.rejected(outcome.reasonCode);
            case UNAVAILABLE -> ExecutionLeaseCommitResult.unavailable(outcome.reasonCode);
        };
    }

    private CommitOutcome commitInternal(
            State state,
            ExecutionLeaseRequest request,
            boolean evidenceRequired,
            String evidenceTransactionId,
            CapabilityStudioDeploymentStateObservation.Observation beforeObservation,
            EvidenceLeaseBudget evidenceBudget) {
        if (evidenceRequired && (evidenceTransactionId == null
                || beforeObservation == null)) {
            return new CommitOutcome(ExecutionLeaseCommitStatus.UNAVAILABLE,
                    null, null, UNAVAILABLE);
        }
        if (evidenceRequired && !STATE_VERSION_V4.equals(state.messageVersion)) {
            return new CommitOutcome(ExecutionLeaseCommitStatus.UNAVAILABLE,
                    null, null, UNAVAILABLE);
        }
        String leaseKey = leaseKey(request.executionLeaseId());
        StoredLease existing = state.leases.get(leaseKey);
        if (existing != null) {
            if (!existing.requestFingerprint.equals(request.commitIdentityFingerprint())
                    || !validStoredReceipt(existing.receipt, request)) {
                return new CommitOutcome(ExecutionLeaseCommitStatus.REJECTED,
                        null, null, LEASE_REJECTED);
            }
            if (evidenceRequired && existing.transitionWitness == null) {
                return new CommitOutcome(ExecutionLeaseCommitStatus.UNAVAILABLE,
                        null, null, UNAVAILABLE);
            }
            TransitionEvidence transition = null;
            if (evidenceRequired) {
                try {
                    transition = store.readTransitionEvidence(state, existing);
                } catch (IOException unavailable) {
                    return new CommitOutcome(ExecutionLeaseCommitStatus.UNAVAILABLE,
                            null, null, UNAVAILABLE);
                }
                if (!evidenceTransactionId.equals(transition.evidenceTransactionId)) {
                    return new CommitOutcome(ExecutionLeaseCommitStatus.REJECTED,
                            null, null, LEASE_REJECTED);
                }
            }
            return new CommitOutcome(ExecutionLeaseCommitStatus.RECOVERED,
                    existing.receipt, existing.transitionWitness, transition, RECOVERED);
        }
        if (capacityExhausted(state.leases.size(), state.fencingSequence,
                state.generation, maxLeases)) {
            return new CommitOutcome(ExecutionLeaseCommitStatus.UNAVAILABLE,
                    null, null, CAPACITY_UNAVAILABLE);
        }

        Instant committedAt;
        try {
            committedAt = clock.instant();
        } catch (RuntimeException unavailable) {
            return new CommitOutcome(ExecutionLeaseCommitStatus.UNAVAILABLE,
                    null, null, UNAVAILABLE);
        }
        if (committedAt.isBefore(request.trustedVerificationTime())
                || !lifecycleAllowed(request.lifecycleMaterial(), state.lifecycleHead,
                committedAt)) {
            return new CommitOutcome(ExecutionLeaseCommitStatus.REJECTED,
                    null, null, LEASE_REJECTED);
        }
        RevocationAuthoritySnapshot currentRevocation = store.readRevocationHead().material;
        if (!currentRevocation.equals(
                request.lifecycleMaterial().revocationAuthority())
                || currentRevocation.observedAt().isAfter(committedAt)
                || !currentRevocation.expiresAt().isAfter(committedAt)) {
            return new CommitOutcome(ExecutionLeaseCommitStatus.REJECTED,
                    null, null, LEASE_REJECTED);
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
        ExecutionLeaseTransitionWitness witness = null;
        StoredLease stored;
        State updated;
        String postRevocationHeadRawFingerprint = null;
        if (STATE_VERSION_V4.equals(state.messageVersion)) {
            Checkpoint before;
            try {
                before = store.readCheckpoint();
            } catch (IOException unavailable) {
                throw new StateUnavailable();
            }
            RevocationHead head = store.readRevocationHead();
            try {
                postRevocationHeadRawFingerprint = sha256(readSnapshot(
                        store.revocationHeadFile, MAX_REVOCATION_HEAD_BYTES,
                        store.metadata));
            } catch (IOException unavailable) {
                throw new StateUnavailable();
            }
            String preStateMaterialFingerprint;
            try {
                preStateMaterialFingerprint = evidenceRequired
                        ? beforeObservation.stateMaterialFingerprint()
                        : store.stateMaterialFingerprint(
                        state, request.commitIdentityFingerprint());
            } catch (IOException | RuntimeException unavailable) {
                throw new StateUnavailable();
            }
            stored = new StoredLease(request.commitIdentityFingerprint(), receipt, null,
                    preStateMaterialFingerprint);
            State core = state.withEvidenceCommitCore(request.lifecycleMaterial(), sequence,
                    leaseKey, stored);
            String materialFingerprint = ExecutionLeaseTransitionWitness.materialFingerprint(
                    store.descriptor.descriptorFingerprint,
                    request.commitIdentityFingerprint(), receipt.fingerprint(),
                    state.stateFingerprint, state.generation, state.fencingSequence,
                    before.checkpointFingerprint, head.sequence, head.headFingerprint,
                    core.stateCoreFingerprint, core.generation, core.fencingSequence,
                    head.sequence, head.headFingerprint);
            String closureMaterial = evidenceRequired
                    ? transitionEvidenceMaterialFingerprint(evidenceTransactionId,
                    receipt.fingerprint(), materialFingerprint, beforeObservation,
                    head.sequence, head.headFingerprint,
                    postRevocationHeadRawFingerprint, core.generation,
                    core.fencingSequence)
                    : null;
            State materialized = core.withEvidenceMaterials(leaseKey, materialFingerprint,
                    closureMaterial, postRevocationHeadRawFingerprint);
            Checkpoint after = Checkpoint.forSnapshot(materialized, head);
            witness = new ExecutionLeaseTransitionWitness(
                    store.descriptor.descriptorFingerprint,
                    request.commitIdentityFingerprint(), receipt.fingerprint(),
                    state.stateFingerprint, state.generation, state.fencingSequence,
                    before.checkpointFingerprint, head.sequence, head.headFingerprint,
                    materialized.stateCoreFingerprint, materialized.stateFingerprint,
                    materialized.generation, materialized.fencingSequence,
                    after.checkpointFingerprint, head.sequence, head.headFingerprint);
            updated = materialized.withWitness(leaseKey, witness);
        } else {
            stored = new StoredLease(request.commitIdentityFingerprint(), receipt, null);
            updated = state.withLegacyCommit(request.lifecycleMaterial(), sequence,
                    leaseKey, stored);
        }
        TransitionEvidence transition = null;
        if (evidenceRequired) {
            try {
                RevocationHead head = store.readRevocationHead();
                Checkpoint checkpoint = Checkpoint.forSnapshot(updated, head);
                byte[] descriptorBytes = readSnapshot(store.lockFile,
                        MAX_DESCRIPTOR_BYTES, store.metadata);
                byte[] headBytes = readSnapshot(store.revocationHeadFile,
                        MAX_REVOCATION_HEAD_BYTES, store.metadata);
                if (!sha256(headBytes).equals(postRevocationHeadRawFingerprint)) {
                    throw new IOException("revocation head changed");
                }
                CapabilityStudioDeploymentStateObservation.Observation predictedAfter =
                        observation(CapabilityStudioDeploymentStateObservation.Phase.AFTER,
                                evidenceTransactionId, store.descriptor, updated, checkpoint,
                                head, descriptorBytes, updated.bytes(), checkpoint.bytes(),
                                headBytes);
                transition = TransitionEvidence.create(evidenceTransactionId, receipt,
                        witness, beforeObservation, predictedAfter);
                StoredLease finalLease = updated.leases.get(leaseKey);
                if (!transition.materialFingerprint.equals(
                        finalLease.evidenceClosureMaterialFingerprint)) {
                    throw new IOException("transition evidence material mismatch");
                }
            } catch (IOException | RuntimeException unavailable) {
                throw new StateUnavailable();
            }
        }
        if (transition != null) {
            try {
                store.prepareTransitionEvidence(transition, updated.generation);
            } catch (IOException | RuntimeException unavailable) {
                throw new StateUnavailable();
            }
        }
        store.writeStateAndCheckpoint(updated);
        if (transition != null) {
            try {
                store.installTransitionEvidence(transition, updated.generation);
                CapabilityStudioDeploymentStateObservation.Observation durableAfter =
                        store.observeLocked(
                                CapabilityStudioDeploymentStateObservation.Phase.AFTER,
                                evidenceTransactionId, updated,
                                new SharedEvidenceLeaseBudget(evidenceBudget));
                if (!durableAfter.equals(transition.after)) {
                    throw new IOException("transition evidence durability mismatch");
                }
            } catch (IOException | RuntimeException unavailable) {
                throw new StateUnavailable();
            }
        }
        return new CommitOutcome(ExecutionLeaseCommitStatus.COMMITTED,
                receipt, witness, transition, COMMITTED);
    }

    private static EvidenceExecutionLeaseCommitResult evidenceResult(
            ExecutionLeaseCommitStatus status,
            ExecutionLeaseReceipt receipt,
            ExecutionLeaseTransitionWitness witness,
            String reason) {
        return new EvidenceExecutionLeaseCommitResult(status, receipt, witness, reason);
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

    private static ReentrantReadWriteLock localLock(Path root) {
        int index = (root.hashCode() & 0x7fffffff) % LOCAL_LOCKS.length;
        return LOCAL_LOCKS[index];
    }

    private static ReentrantReadWriteLock[] localLockStripes() {
        ReentrantReadWriteLock[] locks = new ReentrantReadWriteLock[LOCAL_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantReadWriteLock(true);
        }
        return locks;
    }

    static int localLockRegistrySizeForTesting() {
        return LOCAL_LOCKS.length;
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
        } catch (IOException | RuntimeException failure) {
            throw new InvalidStoreMaterial();
        }
    }

    private static String detectStateVersion(Path stateFile) throws IOException {
        byte[] bytes = readSnapshot(stateFile, MAX_STATE_BYTES, NIO_METADATA);
        try {
            ObjectNode node = object(JSON.readTree(bytes));
            String version = text(node, "messageVersion");
            if (!STATE_VERSION_V2.equals(version) && !STATE_VERSION_V4.equals(version)) {
                throw new IllegalArgumentException("state version");
            }
            return version;
        } catch (IOException | RuntimeException invalid) {
            throw new InvalidStoreMaterial();
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
        return requirePrivateLinkedFile(file, metadata, 1);
    }

    private static FileIdentity requirePrivateLinkedFile(
            Path file, Metadata metadata, long expectedLinks) throws IOException {
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
                || attributes.fileKey() == null || linkCount != expectedLinks) {
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
                attributes.lastModifiedTime());
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

    static boolean capacityExhaustedForTesting(
            int leaseCount, long fencingSequence, long generation) {
        return capacityExhausted(leaseCount, fencingSequence, generation, MAX_LEASES);
    }

    private static boolean capacityExhausted(
            int leaseCount, long fencingSequence, long generation, int maximumLeases) {
        return leaseCount >= maximumLeases || fencingSequence == Long.MAX_VALUE
                || generation == Long.MAX_VALUE;
    }

    private static String sha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static byte[] writeJson(JsonNode value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (IOException impossible) {
            throw new IllegalStateException("state serialization unavailable");
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
        private final Lock localLock;
        private final Durability durability;
        private final Metadata metadata;
        private final String stateVersion;

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
                Lock localLock,
                Durability durability,
                Metadata metadata,
                String stateVersion) {
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
            if (!STATE_VERSION_V2.equals(stateVersion)
                    && !STATE_VERSION_V4.equals(stateVersion)) {
                throw new IllegalArgumentException("stateVersion is invalid");
            }
            this.stateVersion = stateVersion;
        }

        String descriptorFingerprint() {
            return descriptor.descriptorFingerprint;
        }

        boolean supportsEvidenceWitness() {
            return STATE_VERSION_V4.equals(stateVersion);
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
            return withLockedState(operation, new ObservationLeaseBudget(
                    OBSERVATION_LOCK_TIMEOUT_NANOS, System::nanoTime), null);
        }

        private <T> T withLockedState(
                StateOperation<T> operation, EvidenceLeaseBudget sharedBudget) {
            return withLockedState(operation, new SharedEvidenceLeaseBudget(sharedBudget), null);
        }

        private <T> T withLockedState(
                StateOperation<T> operation,
                EvidenceLeaseBudget sharedBudget,
                Path evidencePublicationParent) {
            return withLockedState(operation, new SharedEvidenceLeaseBudget(sharedBudget),
                    evidencePublicationParent);
        }

        private <T> T withLockedState(
                StateOperation<T> operation,
                LockBudget budget,
                Path evidencePublicationParent) {
            if (!tryLocalLock(localLock, budget)) {
                throw new StateUnavailable();
            }
            try {
                ensureRootStable();
                FileIdentity descriptorIdentity = requirePrivateFile(lockFile, metadata);
                if (!java.util.Objects.equals(lockFileKey,
                        descriptorIdentity.fileKey())) {
                    throw new StateUnavailable();
                }
                try (FileChannel channel = FileChannel.open(lockFile,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                     FileLock ignored = tryExclusiveLock(channel, budget)) {
                    RecoveryDisjointEvidenceIdentity disjoint =
                            evidencePublicationParent == null ? null
                                    : requireRecoveryDisjointEvidenceParent(
                                    this, evidencePublicationParent);
                    if (!java.util.Objects.equals(lockFileKey,
                            requirePrivateFile(lockFile, metadata).fileKey())) {
                        throw new StateUnavailable();
                    }
                    ensureRootStable();
                    State state = readConsistentState(budget);
                    forceReadBarrier();
                    if (disjoint != null) {
                        disjoint.recheck();
                    }
                    T result = operation.apply(state);
                    if (disjoint != null) {
                        disjoint.recheck();
                    }
                    ensureRootStable();
                    return result;
                } catch (InvalidStoreMaterial invalid) {
                    throw invalid;
                } catch (IOException | RuntimeException failure) {
                    throw new StateUnavailable();
                }
            } catch (InvalidStoreMaterial invalid) {
                throw invalid;
            } catch (IOException unavailable) {
                throw new StateUnavailable();
            } finally {
                localLock.unlock();
            }
        }

        private <T> T withLockedExistingState(StateOperation<T> operation) {
            ObservationLeaseBudget budget = new ObservationLeaseBudget(
                    OBSERVATION_LOCK_TIMEOUT_NANOS, System::nanoTime);
            Lock readLock = localLock(root).readLock();
            if (!tryLocalLock(readLock, budget)) {
                throw new StateUnavailable();
            }
            try {
                ensureRootStable();
                List<AncestorIdentity> ancestors = ancestorIdentities(root);
                long rootUid = ((Number) Files.getAttribute(root, "unix:uid",
                        LinkOption.NOFOLLOW_LINKS)).longValue();
                try (FileChannel channel = FileChannel.open(lockFile,
                        StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                     FileLock ignored = trySharedLock(channel, budget)) {
                    ensureRootStable();
                    StoreClosure first = readStoreClosure(root, rootUid);
                    StoreDescriptor observedDescriptor = parseDescriptor(
                            first.descriptor.bytes);
                    if (!descriptor.equals(observedDescriptor)
                            || !java.util.Objects.equals(lockFileKey,
                            first.descriptor.identity.fileKey())) {
                        throw new InvalidStoreMaterial();
                    }
                    State state = parseState(first.state.bytes);
                    Checkpoint checkpoint = parseCheckpoint(first.checkpoint.bytes);
                    RevocationHead head = RevocationHead.parse(first.revocationHead.bytes,
                            descriptor.descriptorFingerprint);
                    if (!checkpoint.matchesState(state)
                            || !checkpoint.matchesRevocationHead(head)) {
                        if (singleGenerationCrashIntermediate(state, checkpoint, head)) {
                            throw new IOException("store crash intermediate unavailable");
                        }
                        throw new InvalidStoreMaterial();
                    }
                    ReplayIndex replay = replay(state, checkpoint, budget);
                    validateTransitionEvidenceFiles(
                            state, checkpoint, head, first, replay, budget);
                    T result = operation.apply(state);
                    StoreClosureSeal second = readStoreClosureSeal(root, rootUid);
                    ensureRootStable();
                    if (!first.seal.equals(second)
                            || !ancestors.equals(ancestorIdentities(root))) {
                        throw new IOException("store changed");
                    }
                    return result;
                } catch (InvalidStoreMaterial invalid) {
                    throw invalid;
                } catch (IOException | RuntimeException unavailable) {
                    throw new StateUnavailable();
                }
            } catch (IOException unavailable) {
                throw new StateUnavailable();
            } finally {
                readLock.unlock();
            }
        }

        private <T> T withLockedExistingTransaction(
                StateOperation<T> operation,
                EvidenceLeaseBudget sharedBudget,
                Path evidencePublicationParent) {
            LockBudget budget = new SharedEvidenceLeaseBudget(sharedBudget);
            Lock writeLock = localLock(root).writeLock();
            if (!tryLocalLock(writeLock, budget)) {
                throw new StateUnavailable();
            }
            try {
                ensureRootStable();
                List<AncestorIdentity> ancestors = ancestorIdentities(root);
                long rootUid = ((Number) Files.getAttribute(root, "unix:uid",
                        LinkOption.NOFOLLOW_LINKS)).longValue();
                try (FileChannel channel = FileChannel.open(lockFile,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                     FileLock ignored = tryExclusiveLock(channel, budget)) {
                    RecoveryDisjointEvidenceIdentity disjoint =
                            requireRecoveryDisjointEvidenceParent(
                                    this, evidencePublicationParent);
                    ensureRootStable();
                    StoreClosure first = readStoreClosure(root, rootUid);
                    StoreDescriptor observedDescriptor = parseDescriptor(
                            first.descriptor.bytes);
                    if (!descriptor.equals(observedDescriptor)
                            || !java.util.Objects.equals(lockFileKey,
                            first.descriptor.identity.fileKey())) {
                        throw new InvalidStoreMaterial();
                    }
                    State state = parseState(first.state.bytes);
                    Checkpoint checkpoint = parseCheckpoint(first.checkpoint.bytes);
                    RevocationHead head = RevocationHead.parse(first.revocationHead.bytes,
                            descriptor.descriptorFingerprint);
                    if (!checkpoint.matchesState(state)
                            || !checkpoint.matchesRevocationHead(head)) {
                        if (singleGenerationCrashIntermediate(state, checkpoint, head)) {
                            throw new IOException("store crash intermediate unavailable");
                        }
                        throw new InvalidStoreMaterial();
                    }
                    ReplayIndex replay = replay(state, checkpoint, budget);
                    validateTransitionEvidenceFiles(
                            state, checkpoint, head, first, replay, budget);
                    disjoint.recheck();
                    T result = operation.apply(state);
                    disjoint.recheck();
                    StoreClosureSeal second = readStoreClosureSeal(root, rootUid);
                    ensureRootStable();
                    if (!first.seal.equals(second)
                            || !ancestors.equals(ancestorIdentities(root))) {
                        throw new IOException("store changed");
                    }
                    return result;
                } catch (InvalidStoreMaterial invalid) {
                    throw invalid;
                } catch (IOException | RuntimeException unavailable) {
                    throw new StateUnavailable();
                }
            } catch (InvalidStoreMaterial invalid) {
                throw invalid;
            } catch (IOException unavailable) {
                throw new StateUnavailable();
            } finally {
                writeLock.unlock();
            }
        }

        private State readExactState() throws IOException {
            long rootUid = ((Number) Files.getAttribute(root, "unix:uid",
                    LinkOption.NOFOLLOW_LINKS)).longValue();
            inventoryStoreClosure(root, rootUid);
            State state = readState();
            Checkpoint checkpoint = readCheckpoint();
            RevocationHead head = readRevocationHead();
            if (!checkpoint.matchesState(state)
                    || !checkpoint.matchesRevocationHead(head)) {
                throw new IOException("state checkpoint mismatch");
            }
            LockBudget budget = new ObservationLeaseBudget(
                    OBSERVATION_LOCK_TIMEOUT_NANOS, System::nanoTime);
            StoreClosure closure = readStoreClosure(root, rootUid);
            ReplayIndex replay = replay(state, checkpoint, budget);
            validateTransitionEvidenceFiles(
                    state, checkpoint, head, closure, replay, budget);
            return state;
        }

        private CapabilityStudioDeploymentStateObservation.Observation observeLocked(
                CapabilityStudioDeploymentStateObservation.Phase phase,
                String evidenceTransactionId,
                State expectedState,
                LockBudget budget) {
            try {
                List<AncestorIdentity> ancestors = ancestorIdentities(root);
                long rootUid = ((Number) Files.getAttribute(root, "unix:uid",
                        LinkOption.NOFOLLOW_LINKS)).longValue();
                StoreClosure first = readStoreClosure(root, rootUid);
                State state = parseState(first.state.bytes);
                Checkpoint checkpoint = parseCheckpoint(first.checkpoint.bytes);
                RevocationHead head = RevocationHead.parse(first.revocationHead.bytes,
                        descriptor.descriptorFingerprint);
                if (!state.stateFingerprint.equals(expectedState.stateFingerprint)
                        || !checkpoint.matchesState(state)
                        || !checkpoint.matchesRevocationHead(head)) {
                    throw new IOException("state observation mismatch");
                }
                ReplayIndex replay = replay(state, checkpoint, budget);
                validateTransitionEvidenceFiles(
                        state, checkpoint, head, first, replay, budget);
                StoreClosureSeal second = readStoreClosureSeal(root, rootUid);
                if (!first.seal.equals(second)
                        || !ancestors.equals(ancestorIdentities(root))) {
                    throw new IOException("state changed");
                }
                return observation(phase, evidenceTransactionId, descriptor,
                        state, checkpoint, head, first);
            } catch (IOException | RuntimeException unavailable) {
                throw new StateUnavailable();
            }
        }

        private String stateMaterialFingerprint(
                State expectedState, String evidenceTransactionId) throws IOException {
            byte[] descriptorBytes = readSnapshot(
                    lockFile, MAX_DESCRIPTOR_BYTES, metadata);
            byte[] stateBytes = readSnapshot(stateFile, MAX_STATE_BYTES, metadata);
            byte[] checkpointBytes = readSnapshot(
                    checkpointFile, MAX_CHECKPOINT_BYTES, metadata);
            byte[] headBytes = readSnapshot(
                    revocationHeadFile, MAX_REVOCATION_HEAD_BYTES, metadata);
            State observedState = parseState(stateBytes);
            Checkpoint checkpoint = parseCheckpoint(checkpointBytes);
            RevocationHead head = RevocationHead.parse(
                    headBytes, descriptor.descriptorFingerprint);
            if (!observedState.stateFingerprint.equals(expectedState.stateFingerprint)
                    || !checkpoint.matchesState(observedState)
                    || !checkpoint.matchesRevocationHead(head)) {
                throw new InvalidStoreMaterial();
            }
            return observation(CapabilityStudioDeploymentStateObservation.Phase.BEFORE,
                    evidenceTransactionId, descriptor, observedState, checkpoint, head,
                    descriptorBytes, stateBytes, checkpointBytes, headBytes)
                    .stateMaterialFingerprint();
        }

        private void initializeOrValidate(
                boolean descriptorCreated,
                RevocationAuthoritySnapshot initialRevocationHead) throws IOException {
            try (FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock ignored = tryLock(channel)) {
                ensureRootStable();
                removeStaleWriterTemp(stateTempFile);
                removeStaleWriterTemp(checkpointTempFile);
                removeStaleWriterTemp(revocationHeadTempFile);
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
                    State genesis = State.genesis(
                            descriptor.descriptorFingerprint, stateVersion);
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
                readConsistentState(new ObservationLeaseBudget(
                        OBSERVATION_LOCK_TIMEOUT_NANOS, System::nanoTime));
                forceReadBarrier();
            }
        }

        private State readConsistentState(LockBudget budget) throws IOException {
            if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)
                    || !Files.exists(checkpointFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("initialized store document unavailable");
            }
            long rootUid = ((Number) Files.getAttribute(root, "unix:uid",
                    LinkOption.NOFOLLOW_LINKS)).longValue();
            inventoryStoreClosure(root, rootUid, true);
            State state = readState();
            Checkpoint checkpoint = readCheckpoint();
            RevocationHead revocationHead = readRevocationHead();
            boolean stateMatches = checkpoint.matchesState(state);
            boolean headMatches = checkpoint.matchesRevocationHead(revocationHead);
            if (stateMatches && headMatches) {
                ReplayIndex replay = replay(state, checkpoint, budget);
                recoverTransitionEvidenceJournal(state, checkpoint, revocationHead);
                validateCurrentTransitionEvidence(
                        state, checkpoint, revocationHead, replay, budget);
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
                Checkpoint successor = Checkpoint.forSnapshot(state, revocationHead);
                ReplayIndex replay = replay(state, successor, budget);
                writeCheckpoint(successor);
                recoverTransitionEvidenceJournal(state, successor, revocationHead);
                validateCurrentTransitionEvidence(
                        state, successor, revocationHead, replay, budget);
                return state;
            }
            if (stateMatches && headSuccessor) {
                Checkpoint successor = Checkpoint.forSnapshot(state, revocationHead);
                ReplayIndex replay = replay(state, successor, budget);
                writeCheckpoint(successor);
                recoverTransitionEvidenceJournal(state, successor, revocationHead);
                validateCurrentTransitionEvidence(
                        state, successor, revocationHead, replay, budget);
                return state;
            }
            throw new IOException("state checkpoint mismatch");
        }

        private void recoverTransitionEvidenceJournal(
                State state, Checkpoint checkpoint, RevocationHead head) throws IOException {
            Path pending = null;
            try (var children = Files.newDirectoryStream(root)) {
                for (Path child : children) {
                    String name = child.getFileName().toString();
                    if (name.startsWith("." + TRANSITION_EVIDENCE_PREFIX)
                            && name.endsWith(TRANSITION_EVIDENCE_SUFFIX + ".tmp")) {
                        if (pending != null) {
                            throw new IOException("transition evidence journal conflict");
                        }
                        pending = child;
                    }
                }
            }
            if (pending == null) {
                return;
            }
            TransitionEvidence evidence = transitionEvidence(readSnapshot(pending,
                    MAX_TRANSITION_EVIDENCE_BYTES, metadata));
            long generation = evidence.after.generation();
            Path expectedSource = transitionEvidenceSource(root, generation);
            if (!pending.equals(expectedSource)) {
                throw new InvalidStoreMaterial();
            }
            StoredLease committed = leaseForGeneration(state, generation);
            if (committed != null) {
                requireTransitionEvidenceBinding(committed, evidence);
                installTransitionEvidence(evidence, generation);
                return;
            }
            CapabilityStudioDeploymentStateObservation.Observation current = observation(
                    CapabilityStudioDeploymentStateObservation.Phase.BEFORE,
                    evidence.evidenceTransactionId, descriptor, state, checkpoint, head,
                    readSnapshot(lockFile, MAX_DESCRIPTOR_BYTES, metadata), state.bytes(),
                    checkpoint.bytes(), readSnapshot(revocationHeadFile,
                    MAX_REVOCATION_HEAD_BYTES, metadata));
            if (generation != state.generation + 1
                    || !evidence.before.equals(current)
                    || evidence.after.generation() != generation
                    || evidence.after.fencingSequence() != state.fencingSequence + 1
                    || Files.exists(transitionEvidenceFile(root, generation),
                    LinkOption.NOFOLLOW_LINKS)) {
                throw new InvalidStoreMaterial();
            }
            FileIdentity identity = requirePrivateFile(pending, metadata);
            durability.forceDirectory(root, pending);
            if (!identity.equals(requirePrivateFile(pending, metadata))) {
                throw new IOException("transition evidence journal changed");
            }
            Files.delete(pending);
            durability.forceDirectory(root, pending);
        }

        private StoredLease leaseForGeneration(State state, long generation) {
            for (StoredLease lease : state.leases.values()) {
                if (lease.receipt.lifecycleCommitReceipt().fencingSequence() == generation
                        && lease.evidenceClosureMaterialFingerprint != null) {
                    return lease;
                }
            }
            return null;
        }

        private static void requireTransitionEvidenceBinding(
                StoredLease lease, TransitionEvidence evidence) throws IOException {
            if (!lease.evidenceClosureMaterialFingerprint.equals(
                    evidence.materialFingerprint)
                    || !lease.receipt.fingerprint().equals(evidence.receiptFingerprint)
                    || lease.transitionWitness == null
                    || !lease.transitionWitness.fingerprint().equals(
                    evidence.witnessFingerprint)
                    || !lease.transitionWitness.materialFingerprint().equals(
                    evidence.witnessMaterialFingerprint)) {
                throw new InvalidStoreMaterial();
            }
        }

        private ReplayIndex replay(
                State state, Checkpoint checkpoint, LockBudget budget) throws IOException {
            return state.replay(checkpoint, budget);
        }

        private void validateCurrentTransitionEvidence(
                State state,
                Checkpoint checkpoint,
                RevocationHead head,
                ReplayIndex replay,
                LockBudget budget) throws IOException {
            long rootUid = ((Number) Files.getAttribute(root, "unix:uid",
                    LinkOption.NOFOLLOW_LINKS)).longValue();
            validateTransitionEvidenceFiles(state, checkpoint, head,
                    readStoreClosure(root, rootUid), replay, budget);
        }

        private State readState() throws IOException {
            return parseState(readSnapshot(stateFile, MAX_STATE_BYTES, metadata));
        }

        private State parseState(byte[] bytes) throws IOException {
            try {
                ObjectNode node = object(JSON.readTree(bytes));
                requireFields(node, STATE_VERSION_V4.equals(stateVersion)
                        ? Set.of("messageVersion", "storeDescriptorFingerprint",
                        "generation", "previousStateFingerprint", "stateCoreFingerprint",
                        "stateFingerprint", "lifecycleHead", "fencingSequence", "leases")
                        : Set.of("messageVersion", "storeDescriptorFingerprint",
                        "generation", "previousStateFingerprint", "stateFingerprint",
                        "lifecycleHead", "fencingSequence", "leases"));
                if (!stateVersion.equals(text(node, "messageVersion"))
                        || !descriptor.descriptorFingerprint.equals(
                        fingerprint(node, "storeDescriptorFingerprint"))) {
                    throw new IllegalArgumentException("state version");
                }
                long generation = nonNegativeLong(node, "generation");
                JsonNode previousNode = node.get("previousStateFingerprint");
                String previous = previousNode.isNull() ? null
                        : fingerprint(node, "previousStateFingerprint");
                String stateCoreFingerprint = STATE_VERSION_V4.equals(stateVersion)
                        ? fingerprint(node, "stateCoreFingerprint") : null;
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
                    requireFields(lease, STATE_VERSION_V4.equals(stateVersion)
                            ? Set.of("leaseKey", "requestFingerprint", "receipt",
                            "transitionWitness", "evidenceClosureMaterialFingerprint",
                            "postRevocationHeadRawFingerprint",
                            "preStateMaterialFingerprint")
                            : Set.of("leaseKey", "requestFingerprint", "receipt"));
                    String key = fingerprint(lease, "leaseKey");
                    String requestFingerprint = fingerprint(lease, "requestFingerprint");
                    if (prior != null && prior.compareTo(key) >= 0) {
                        throw new IllegalArgumentException("lease order");
                    }
                    ExecutionLeaseReceipt receipt = receipt(object(lease.get("receipt")));
                    ExecutionLeaseTransitionWitness witness =
                            STATE_VERSION_V4.equals(stateVersion)
                                    ? transitionWitness(object(
                                    lease.get("transitionWitness"))) : null;
                    String closureMaterial = null;
                    String postRevocationHeadRawFingerprint = null;
                    String preStateMaterialFingerprint = null;
                    if (STATE_VERSION_V4.equals(stateVersion)) {
                        JsonNode closureNode = lease.get(
                                "evidenceClosureMaterialFingerprint");
                        closureMaterial = closureNode.isNull() ? null
                                : fingerprint(lease,
                                "evidenceClosureMaterialFingerprint");
                        postRevocationHeadRawFingerprint = fingerprint(lease,
                                "postRevocationHeadRawFingerprint");
                        preStateMaterialFingerprint = fingerprint(lease,
                                "preStateMaterialFingerprint");
                    }
                    if (!requestFingerprint.equals(receipt.requestFingerprint())
                            || (witness != null
                            && (!requestFingerprint.equals(witness.requestFingerprint())
                            || !receipt.fingerprint().equals(witness.receiptFingerprint())))
                            || leases.put(key,
                            new StoredLease(requestFingerprint, receipt,
                                    witness == null ? null : witness.materialFingerprint(),
                                    witness, closureMaterial,
                                    postRevocationHeadRawFingerprint,
                                    preStateMaterialFingerprint)) != null) {
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
                State state = new State(stateVersion, descriptor.descriptorFingerprint, generation,
                        previous, stateCoreFingerprint, stateFingerprint, head, fencing, leases);
                if (maximumSequence != fencing || fencing != leases.size()
                        || generation != fencing
                        || (leases.isEmpty() != (head == null))
                        || (head != null && !head.equals(latestLifecycle))
                        || (STATE_VERSION_V4.equals(stateVersion)
                        && !stateCoreFingerprint.equals(state.computeCoreFingerprint()))
                        || !stateFingerprint.equals(state.computeFingerprint())) {
                    throw new IllegalArgumentException("state invariant");
                }
                return state;
            } catch (IOException | RuntimeException failure) {
                throw new InvalidStoreMaterial();
            }
        }

        private Checkpoint readCheckpoint() throws IOException {
            return parseCheckpoint(readSnapshot(
                    checkpointFile, MAX_CHECKPOINT_BYTES, metadata));
        }

        private Checkpoint parseCheckpoint(byte[] bytes) throws IOException {
            try {
                ObjectNode node = object(JSON.readTree(bytes));
                String checkpointVersion = text(node, "messageVersion");
                boolean evidence = EVIDENCE_CHECKPOINT_VERSION.equals(checkpointVersion);
                requireFields(node, evidence
                        ? Set.of("messageVersion", "storeDescriptorFingerprint",
                        "generation", "stateCoreFingerprint", "stateFingerprint",
                        "revocationHeadSequence", "revocationHeadFingerprint",
                        "latestTransitionWitnessMaterialFingerprint",
                        "checkpointFingerprint")
                        : Set.of("messageVersion", "storeDescriptorFingerprint",
                        "generation", "stateFingerprint", "revocationHeadSequence",
                        "revocationHeadFingerprint", "checkpointFingerprint"));
                if (!(CHECKPOINT_VERSION.equals(checkpointVersion)
                        && STATE_VERSION_V2.equals(stateVersion))
                        && !(evidence && STATE_VERSION_V4.equals(stateVersion))
                        || !descriptor.descriptorFingerprint.equals(
                        fingerprint(node, "storeDescriptorFingerprint"))) {
                    throw new IllegalArgumentException("checkpoint version");
                }
                JsonNode latestMaterialNode = node.get(
                        "latestTransitionWitnessMaterialFingerprint");
                String latestMaterial = evidence
                        ? latestMaterialNode.isNull() ? null
                        : fingerprint(node, "latestTransitionWitnessMaterialFingerprint")
                        : null;
                Checkpoint checkpoint = new Checkpoint(checkpointVersion,
                        descriptor.descriptorFingerprint,
                        nonNegativeLong(node, "generation"),
                        evidence ? fingerprint(node, "stateCoreFingerprint") : null,
                        fingerprint(node, "stateFingerprint"),
                        nonNegativeLong(node, "revocationHeadSequence"),
                        fingerprint(node, "revocationHeadFingerprint"),
                        latestMaterial,
                        fingerprint(node, "checkpointFingerprint"));
                if (!checkpoint.checkpointFingerprint.equals(
                        checkpoint.computeFingerprint())) {
                    throw new IllegalArgumentException("checkpoint fingerprint");
                }
                return checkpoint;
            } catch (IOException | RuntimeException failure) {
                throw new InvalidStoreMaterial();
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

        private TransitionEvidence readTransitionEvidence(State state, StoredLease lease)
                throws IOException {
            long generation = lease.receipt.lifecycleCommitReceipt().fencingSequence();
            TransitionEvidence evidence = transitionEvidence(readSnapshot(
                    transitionEvidenceFile(root, generation),
                    MAX_TRANSITION_EVIDENCE_BYTES, metadata));
            requireTransitionEvidenceBinding(lease, evidence);
            return evidence;
        }

        private void validateTransitionEvidenceFiles(
                State state,
                Checkpoint checkpoint,
                RevocationHead head,
                StoreClosure closure,
                ReplayIndex replay,
                LockBudget budget) throws IOException {
            TreeMap<String, StoredLease> expected = new TreeMap<>();
            for (StoredLease lease : state.leases.values()) {
                if (lease.evidenceClosureMaterialFingerprint != null) {
                    String name = transitionEvidenceFile(root,
                            lease.receipt.lifecycleCommitReceipt().fencingSequence())
                            .getFileName().toString();
                    expected.put(name, lease);
                }
            }
            if (!closure.transitions.keySet().equals(expected.keySet())) {
                throw new InvalidStoreMaterial();
            }
            TransitionEvidence latest = null;
            for (ReplayStep step : replay.steps) {
                requireReplayBudget(budget);
                if (step.lease.evidenceClosureMaterialFingerprint == null) {
                    continue;
                }
                String name = transitionEvidenceFile(root, step.sequence)
                        .getFileName().toString();
                ObservedFile observed = closure.transitions.get(name);
                if (observed == null) {
                    throw new InvalidStoreMaterial();
                }
                TransitionEvidence evidence = transitionEvidence(observed.bytes);
                requireTransitionEvidenceBinding(step.lease, evidence);
                requireTransitionObservationCoordinates(step, evidence);
                if (!evidence.before.stateMaterialFingerprint().equals(
                        step.lease.preStateMaterialFingerprint)) {
                    throw new InvalidStoreMaterial();
                }
                if (step.sequence < state.generation
                        && !evidence.after.stateMaterialFingerprint().equals(
                        replay.steps.get(Math.toIntExact(step.sequence))
                                .lease.preStateMaterialFingerprint)) {
                    throw new InvalidStoreMaterial();
                }
                latest = evidence;
            }
            if (latest != null && latest.after.generation() == state.generation) {
                CapabilityStudioDeploymentStateObservation.Observation current = observation(
                        CapabilityStudioDeploymentStateObservation.Phase.AFTER,
                        latest.evidenceTransactionId, descriptor, state, checkpoint, head,
                        closure);
                if (!latest.after.equals(current)) {
                    throw new InvalidStoreMaterial();
                }
            }
        }

        private static void requireTransitionObservationCoordinates(
                ReplayStep step, TransitionEvidence evidence) {
            ExecutionLeaseTransitionWitness witness = step.lease.transitionWitness;
            if (witness == null
                    || evidence.before.phase()
                    != CapabilityStudioDeploymentStateObservation.Phase.BEFORE
                    || evidence.after.phase()
                    != CapabilityStudioDeploymentStateObservation.Phase.AFTER
                    || !evidence.evidenceTransactionId.equals(
                    evidence.before.evidenceTransactionId())
                    || !evidence.evidenceTransactionId.equals(
                    evidence.after.evidenceTransactionId())
                    || !witness.storeDescriptorFingerprint().equals(
                    evidence.before.storeDescriptorFingerprint())
                    || !witness.storeDescriptorFingerprint().equals(
                    evidence.after.storeDescriptorFingerprint())
                    || evidence.before.generation() != witness.preGeneration()
                    || evidence.before.fencingSequence() != witness.preFencingSequence()
                    || !evidence.before.stateFingerprint().equals(
                    witness.preStateFingerprint())
                    || !evidence.before.checkpointFingerprint().equals(
                    witness.preCheckpointFingerprint())
                    || evidence.before.revocationHeadSequence()
                    != witness.preRevocationHeadSequence()
                    || !evidence.before.revocationHeadFingerprint().equals(
                    witness.preRevocationHeadFingerprint())
                    || evidence.after.generation() != witness.postGeneration()
                    || evidence.after.fencingSequence() != witness.postFencingSequence()
                    || !evidence.after.stateFingerprint().equals(
                    witness.postStateFingerprint())
                    || !evidence.after.checkpointFingerprint().equals(
                    witness.postCheckpointFingerprint())
                    || evidence.after.revocationHeadSequence()
                    != witness.postRevocationHeadSequence()
                    || !evidence.after.revocationHeadFingerprint().equals(
                    witness.postRevocationHeadFingerprint())
                    || !java.util.Objects.equals(evidence.after.previousStateFingerprint(),
                    witness.preStateFingerprint())
                    || evidence.before.leaseCount() != step.sequence - 1
                    || evidence.after.leaseCount() != step.sequence
                    || !step.lease.receipt.lifecycleMaterial().fingerprint().equals(
                    evidence.after.lifecycleHeadFingerprint())) {
                throw new InvalidStoreMaterial();
            }
        }

        private void writeTransitionEvidence(TransitionEvidence evidence, long generation)
                throws IOException {
            prepareTransitionEvidence(evidence, generation);
            installTransitionEvidence(evidence, generation);
        }

        private void prepareTransitionEvidence(TransitionEvidence evidence, long generation)
                throws IOException {
            Path target = transitionEvidenceFile(root, generation);
            Path source = transitionEvidenceSource(root, generation);
            byte[] bytes = evidence.bytes();
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!Arrays.equals(bytes, readSnapshot(target,
                        MAX_TRANSITION_EVIDENCE_BYTES, metadata))) {
                    throw new IOException("transition evidence conflict");
                }
                return;
            }
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                if (!Arrays.equals(bytes, readSnapshot(source,
                        MAX_TRANSITION_EVIDENCE_BYTES, metadata))) {
                    throw new IOException("transition evidence source conflict");
                }
            } else {
                writeFreshFile(root, source, bytes, metadata);
                durability.forceDirectory(root, source);
            }
        }

        private void installTransitionEvidence(TransitionEvidence evidence, long generation)
                throws IOException {
            Path target = transitionEvidenceFile(root, generation);
            Path source = transitionEvidenceSource(root, generation);
            byte[] bytes = evidence.bytes();
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                if (!Arrays.equals(bytes, readSnapshot(target,
                        MAX_TRANSITION_EVIDENCE_BYTES, metadata))) {
                    throw new IOException("transition evidence conflict");
                }
                return;
            }
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("transition evidence source unavailable");
            }
            try {
                Files.createLink(target, source);
            } catch (FileAlreadyExistsException raced) {
                if (!Arrays.equals(bytes, readSnapshot(target,
                        MAX_TRANSITION_EVIDENCE_BYTES, metadata))) {
                    throw new IOException("transition evidence conflict", raced);
                }
            }
            FileIdentity targetIdentity = requirePrivateLinkedFile(target, metadata, 2);
            durability.forceDirectory(root, target);
            FileIdentity sourceIdentity = requirePrivateLinkedFile(source, metadata, 2);
            if (!java.util.Objects.equals(sourceIdentity.fileKey(), targetIdentity.fileKey())
                    || metadata.hardLinkCount(source) != 2
                    || metadata.hardLinkCount(target) != 2) {
                throw new IOException("transition evidence identity mismatch");
            }
            Files.delete(source);
            durability.forceDirectory(root, source);
            if (metadata.hardLinkCount(target) != 1) {
                throw new IOException("transition evidence link mismatch");
            }
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

        private void removeStaleWriterTemp(Path temp) throws IOException {
            if (Files.exists(temp, LinkOption.NOFOLLOW_LINKS)) {
                removeStaleTemp(temp, metadata);
                durability.forceDirectory(root, temp);
            }
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
        private final String messageVersion;
        private final String storeDescriptorFingerprint;
        private final long generation;
        private final String previousStateFingerprint;
        private final String stateCoreFingerprint;
        private final String stateFingerprint;
        private final AdmissionLifecycleMaterial lifecycleHead;
        private final long fencingSequence;
        private final TreeMap<String, StoredLease> leases;

        private State(
                String messageVersion,
                String storeDescriptorFingerprint,
                long generation,
                String previousStateFingerprint,
                String stateCoreFingerprint,
                String stateFingerprint,
                AdmissionLifecycleMaterial lifecycleHead,
                long fencingSequence,
                TreeMap<String, StoredLease> leases) {
            this.messageVersion = messageVersion;
            this.storeDescriptorFingerprint = storeDescriptorFingerprint;
            this.generation = generation;
            this.previousStateFingerprint = previousStateFingerprint;
            this.stateCoreFingerprint = stateCoreFingerprint;
            this.stateFingerprint = stateFingerprint;
            this.lifecycleHead = lifecycleHead;
            this.fencingSequence = fencingSequence;
            this.leases = new TreeMap<>(leases);
        }

        private static State genesis(String descriptorFingerprint, String messageVersion) {
            State unhashed = new State(messageVersion, descriptorFingerprint, 0, null,
                    null, null, null, 0, new TreeMap<>());
            if (STATE_VERSION_V2.equals(messageVersion)) {
                return unhashed.withFingerprints(null,
                        unhashed.computeLegacyFingerprint());
            }
            String core = unhashed.computeCoreFingerprint();
            State withCore = unhashed.withFingerprints(core, null);
            return withCore.withFingerprints(core,
                    withCore.computeCommitmentFingerprint());
        }

        private State withLegacyCommit(
                AdmissionLifecycleMaterial head,
                long sequence,
                String leaseKey,
                StoredLease lease) {
            TreeMap<String, StoredLease> updated = new TreeMap<>(leases);
            updated.put(leaseKey, lease);
            State unhashed = new State(messageVersion, storeDescriptorFingerprint, generation + 1,
                    stateFingerprint, null, null, head, sequence, updated);
            return unhashed.withFingerprints(null,
                    unhashed.computeLegacyFingerprint());
        }

        private State withEvidenceCommitCore(
                AdmissionLifecycleMaterial head,
                long sequence,
                String leaseKey,
                StoredLease lease) {
            TreeMap<String, StoredLease> updated = new TreeMap<>(leases);
            updated.put(leaseKey, lease);
            State unhashed = new State(messageVersion, storeDescriptorFingerprint,
                    generation + 1, stateFingerprint, null, null, head, sequence, updated);
            return unhashed.withFingerprints(unhashed.computeCoreFingerprint(), null);
        }

        private State withEvidenceMaterials(
                String leaseKey,
                String witnessMaterialFingerprint,
                String evidenceClosureMaterialFingerprint,
                String postRevocationHeadRawFingerprint) {
            TreeMap<String, StoredLease> updated = new TreeMap<>(leases);
            StoredLease existing = updated.get(leaseKey);
            if (existing == null) {
                throw new IllegalStateException("lease unavailable");
            }
            updated.put(leaseKey, existing.withEvidenceMaterials(
                    witnessMaterialFingerprint, evidenceClosureMaterialFingerprint,
                    postRevocationHeadRawFingerprint));
            State materialized = new State(messageVersion, storeDescriptorFingerprint,
                    generation, previousStateFingerprint, stateCoreFingerprint, null,
                    lifecycleHead, fencingSequence, updated);
            return materialized.withFingerprints(stateCoreFingerprint,
                    materialized.computeCommitmentFingerprint());
        }

        private State withWitness(
                String leaseKey, ExecutionLeaseTransitionWitness witness) {
            TreeMap<String, StoredLease> updated = new TreeMap<>(leases);
            StoredLease existing = updated.get(leaseKey);
            if (existing == null || !existing.witnessMaterialFingerprint.equals(
                    witness.materialFingerprint())) {
                throw new IllegalStateException("lease witness unavailable");
            }
            updated.put(leaseKey, existing.withWitness(witness));
            return new State(messageVersion, storeDescriptorFingerprint, generation,
                    previousStateFingerprint, stateCoreFingerprint, stateFingerprint,
                    lifecycleHead, fencingSequence, updated);
        }

        private State withFingerprints(String coreFingerprint, String fingerprint) {
            return new State(messageVersion, storeDescriptorFingerprint, generation,
                    previousStateFingerprint, coreFingerprint, fingerprint, lifecycleHead,
                    fencingSequence, leases);
        }

        private boolean isGenesis() {
            return generation == 0 && previousStateFingerprint == null
                    && lifecycleHead == null && fencingSequence == 0 && leases.isEmpty()
                    && (STATE_VERSION_V2.equals(messageVersion)
                    ? stateCoreFingerprint == null
                    && stateFingerprint.equals(computeLegacyFingerprint())
                    : stateCoreFingerprint.equals(computeCoreFingerprint())
                    && stateFingerprint.equals(computeCommitmentFingerprint()));
        }

        private String computeFingerprint() {
            return STATE_VERSION_V2.equals(messageVersion)
                    ? computeLegacyFingerprint() : computeCommitmentFingerprint();
        }

        private String computeLegacyFingerprint() {
            return sha256(legacyBytesWithFingerprint(null));
        }

        private String computeCoreFingerprint() {
            return sha256(writeJson(coreNode()));
        }

        private String computeCommitmentFingerprint() {
            if (stateCoreFingerprint == null) {
                throw new IllegalStateException("state core unavailable");
            }
            ObjectNode node = JSON.createObjectNode();
            node.put("messageVersion", STATE_COMMITMENT_VERSION);
            node.put("stateCoreFingerprint", stateCoreFingerprint);
            ArrayNode witnesses = node.putArray("transitionWitnessMaterials");
            leases.forEach((key, stored) -> {
                if (stored.witnessMaterialFingerprint == null) {
                    throw new IllegalStateException("transition witness material unavailable");
                }
                witnesses.addObject().put("leaseKey", key)
                        .put("materialFingerprint", stored.witnessMaterialFingerprint)
                        .put("evidenceClosureMaterialFingerprint",
                                stored.evidenceClosureMaterialFingerprint)
                        .put("postRevocationHeadRawFingerprint",
                                stored.postRevocationHeadRawFingerprint)
                        .put("preStateMaterialFingerprint",
                                stored.preStateMaterialFingerprint);
            });
            return sha256(writeJson(node));
        }

        private byte[] bytes() {
            return STATE_VERSION_V2.equals(messageVersion)
                    ? legacyBytesWithFingerprint(stateFingerprint)
                    : writeJson(evidenceNode());
        }

        private byte[] legacyBytesWithFingerprint(String fingerprint) {
            ObjectNode node = JSON.createObjectNode();
            node.put("messageVersion", messageVersion);
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
            return writeJson(node);
        }

        private ObjectNode coreNode() {
            ObjectNode node = JSON.createObjectNode();
            node.put("messageVersion", STATE_CORE_VERSION);
            node.put("storeDescriptorFingerprint", storeDescriptorFingerprint);
            node.put("generation", generation);
            if (previousStateFingerprint == null) {
                node.putNull("previousStateFingerprint");
            } else {
                node.put("previousStateFingerprint", previousStateFingerprint);
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
            node.putNull("stateCoreFingerprint");
            return node;
        }

        private ObjectNode evidenceNode() {
            ObjectNode node = JSON.createObjectNode();
            node.put("messageVersion", messageVersion);
            node.put("storeDescriptorFingerprint", storeDescriptorFingerprint);
            node.put("generation", generation);
            if (previousStateFingerprint == null) {
                node.putNull("previousStateFingerprint");
            } else {
                node.put("previousStateFingerprint", previousStateFingerprint);
            }
            node.put("stateCoreFingerprint", stateCoreFingerprint);
            node.put("stateFingerprint", stateFingerprint);
            if (lifecycleHead == null) {
                node.putNull("lifecycleHead");
            } else {
                node.set("lifecycleHead", lifecycleNode(lifecycleHead));
            }
            node.put("fencingSequence", fencingSequence);
            ArrayNode leasesNode = node.putArray("leases");
            leases.forEach((key, stored) -> {
                if (stored.transitionWitness == null) {
                    throw new IllegalStateException("transition witness unavailable");
                }
                ObjectNode lease = leasesNode.addObject();
                lease.put("leaseKey", key);
                lease.put("requestFingerprint", stored.requestFingerprint);
                lease.set("receipt", receiptNode(stored.receipt));
                lease.set("transitionWitness",
                        transitionWitnessNode(stored.transitionWitness));
                if (stored.evidenceClosureMaterialFingerprint == null) {
                    lease.putNull("evidenceClosureMaterialFingerprint");
                } else {
                    lease.put("evidenceClosureMaterialFingerprint",
                            stored.evidenceClosureMaterialFingerprint);
                }
                lease.put("postRevocationHeadRawFingerprint",
                        stored.postRevocationHeadRawFingerprint);
                lease.put("preStateMaterialFingerprint",
                        stored.preStateMaterialFingerprint);
            });
            return node;
        }

        private String latestWitnessMaterialFingerprint() {
            if (STATE_VERSION_V2.equals(messageVersion) || leases.isEmpty()) {
                return null;
            }
            StoredLease latest = null;
            long latestSequence = -1;
            for (StoredLease lease : leases.values()) {
                long sequence = lease.receipt.lifecycleCommitReceipt().fencingSequence();
                if (sequence > latestSequence) {
                    latest = lease;
                    latestSequence = sequence;
                }
            }
            return latest == null ? null : latest.witnessMaterialFingerprint;
        }

        private ReplayIndex replay(
                Checkpoint currentCheckpoint,
                LockBudget budget) {
            requireReplayBudget(budget);
            if (STATE_VERSION_V2.equals(messageVersion)) {
                if (!currentCheckpoint.matchesState(this)) {
                    throw new InvalidStoreMaterial();
                }
                return new ReplayIndex(List.of());
            }
            TreeMap<Long, Map.Entry<String, StoredLease>> bySequence = new TreeMap<>();
            for (Map.Entry<String, StoredLease> entry : leases.entrySet()) {
                requireReplayBudget(budget);
                long sequence = entry.getValue().receipt.lifecycleCommitReceipt()
                        .fencingSequence();
                if (bySequence.put(sequence,
                        Map.entry(entry.getKey(), entry.getValue())) != null) {
                    throw new InvalidStoreMaterial();
                }
            }
            State genesis = genesis(storeDescriptorFingerprint, STATE_VERSION_V4);
            String priorCoreFingerprint = genesis.stateCoreFingerprint;
            String priorStateFingerprint = genesis.stateFingerprint;
            long priorGeneration = 0;
            long priorFencingSequence = 0;
            String priorWitnessMaterialFingerprint = null;
            ArrayList<ReplayStep> steps = new ArrayList<>(bySequence.size());
            for (long sequence = 1; sequence <= generation; sequence++) {
                requireReplayBudget(budget);
                Map.Entry<String, StoredLease> entry = bySequence.get(sequence);
                if (entry == null || entry.getValue().transitionWitness == null) {
                    throw new InvalidStoreMaterial();
                }
                StoredLease stored = entry.getValue();
                ExecutionLeaseTransitionWitness witness = stored.transitionWitness;
                Checkpoint preCheckpoint = Checkpoint.forReplayCoordinates(
                        storeDescriptorFingerprint, priorGeneration,
                        priorCoreFingerprint, priorStateFingerprint,
                        witness.preRevocationHeadSequence(),
                        witness.preRevocationHeadFingerprint(),
                        priorWitnessMaterialFingerprint);
                if (!storeDescriptorFingerprint.equals(witness.storeDescriptorFingerprint())
                        || !stored.requestFingerprint.equals(witness.requestFingerprint())
                        || !stored.receipt.fingerprint().equals(witness.receiptFingerprint())
                        || !stored.witnessMaterialFingerprint.equals(
                        witness.materialFingerprint())
                        || !priorStateFingerprint.equals(witness.preStateFingerprint())
                        || priorGeneration != witness.preGeneration()
                        || priorFencingSequence != witness.preFencingSequence()
                        || witness.postGeneration() != sequence
                        || witness.postFencingSequence() != sequence
                        || !preCheckpoint.checkpointFingerprint.equals(
                        witness.preCheckpointFingerprint())) {
                    throw new InvalidStoreMaterial();
                }
                Checkpoint postCheckpoint = Checkpoint.forReplayCoordinates(
                        storeDescriptorFingerprint, sequence,
                        witness.postStateCoreFingerprint(), witness.postStateFingerprint(),
                        witness.postRevocationHeadSequence(),
                        witness.postRevocationHeadFingerprint(),
                        witness.materialFingerprint());
                if (!postCheckpoint.checkpointFingerprint.equals(
                        witness.postCheckpointFingerprint())) {
                    throw new InvalidStoreMaterial();
                }
                if (stored.preStateMaterialFingerprint == null
                        || sequence < generation && !bySequence.containsKey(sequence + 1)) {
                    throw new InvalidStoreMaterial();
                }
                steps.add(new ReplayStep(sequence, entry.getKey(), stored));
                priorCoreFingerprint = witness.postStateCoreFingerprint();
                priorStateFingerprint = witness.postStateFingerprint();
                priorGeneration = sequence;
                priorFencingSequence = sequence;
                priorWitnessMaterialFingerprint = witness.materialFingerprint();
            }
            if (!priorCoreFingerprint.equals(stateCoreFingerprint)
                    || !priorStateFingerprint.equals(stateFingerprint)
                    || priorGeneration != generation
                    || priorFencingSequence != fencingSequence
                    || !java.util.Objects.equals(previousStateFingerprint,
                    steps.isEmpty() ? null : steps.getLast().lease.transitionWitness
                            .preStateFingerprint())
                    || !currentCheckpoint.matchesState(this)
                    || !currentCheckpoint.checkpointFingerprint.equals(
                    steps.isEmpty()
                            ? Checkpoint.forReplayCoordinates(storeDescriptorFingerprint,
                            0, priorCoreFingerprint, priorStateFingerprint,
                            currentCheckpoint.revocationHeadSequence,
                            currentCheckpoint.revocationHeadFingerprint, null)
                            .checkpointFingerprint
                            : steps.getLast().lease.transitionWitness
                            .postCheckpointFingerprint())) {
                throw new InvalidStoreMaterial();
            }
            return new ReplayIndex(steps);
        }

        @Override
        public String toString() {
            return "State[material=REDACTED]";
        }
    }

    private static final class Checkpoint {
        private final String messageVersion;
        private final String storeDescriptorFingerprint;
        private final long generation;
        private final String stateCoreFingerprint;
        private final String stateFingerprint;
        private final long revocationHeadSequence;
        private final String revocationHeadFingerprint;
        private final String latestTransitionWitnessMaterialFingerprint;
        private final String checkpointFingerprint;

        private Checkpoint(
                String messageVersion,
                String storeDescriptorFingerprint,
                long generation,
                String stateCoreFingerprint,
                String stateFingerprint,
                long revocationHeadSequence,
                String revocationHeadFingerprint,
                String latestTransitionWitnessMaterialFingerprint,
                String checkpointFingerprint) {
            this.messageVersion = messageVersion;
            this.storeDescriptorFingerprint = storeDescriptorFingerprint;
            this.generation = generation;
            this.stateCoreFingerprint = stateCoreFingerprint;
            this.stateFingerprint = stateFingerprint;
            this.revocationHeadSequence = revocationHeadSequence;
            this.revocationHeadFingerprint = revocationHeadFingerprint;
            this.latestTransitionWitnessMaterialFingerprint =
                    latestTransitionWitnessMaterialFingerprint;
            this.checkpointFingerprint = checkpointFingerprint;
        }

        private static Checkpoint forSnapshot(State state, RevocationHead revocationHead) {
            return forCoordinates(state, revocationHead.sequence,
                    revocationHead.headFingerprint);
        }

        private static Checkpoint forCoordinates(
                State state, long revocationHeadSequence,
                String revocationHeadFingerprint) {
            if (STATE_VERSION_V2.equals(state.messageVersion)) {
                Checkpoint unhashed = new Checkpoint(CHECKPOINT_VERSION,
                        state.storeDescriptorFingerprint, state.generation,
                        null, state.stateFingerprint, revocationHeadSequence,
                        revocationHeadFingerprint, null, null);
                return new Checkpoint(unhashed.messageVersion,
                        unhashed.storeDescriptorFingerprint, unhashed.generation,
                        null, unhashed.stateFingerprint,
                        unhashed.revocationHeadSequence, unhashed.revocationHeadFingerprint,
                        null, unhashed.computeFingerprint());
            }
            return forReplayCoordinates(state.storeDescriptorFingerprint, state.generation,
                    state.stateCoreFingerprint, state.stateFingerprint,
                    revocationHeadSequence, revocationHeadFingerprint,
                    state.latestWitnessMaterialFingerprint());
        }

        private static Checkpoint forReplayCoordinates(
                String storeDescriptorFingerprint,
                long generation,
                String stateCoreFingerprint,
                String stateFingerprint,
                long revocationHeadSequence,
                String revocationHeadFingerprint,
                String latestWitnessMaterialFingerprint) {
            Checkpoint unhashed = new Checkpoint(EVIDENCE_CHECKPOINT_VERSION,
                    storeDescriptorFingerprint, generation,
                    stateCoreFingerprint, stateFingerprint,
                    revocationHeadSequence, revocationHeadFingerprint,
                    latestWitnessMaterialFingerprint, null);
            return new Checkpoint(unhashed.messageVersion,
                    unhashed.storeDescriptorFingerprint, unhashed.generation,
                    unhashed.stateCoreFingerprint, unhashed.stateFingerprint,
                    unhashed.revocationHeadSequence, unhashed.revocationHeadFingerprint,
                    unhashed.latestTransitionWitnessMaterialFingerprint,
                    unhashed.computeFingerprint());
        }

        private boolean matchesState(State state) {
            return storeDescriptorFingerprint.equals(
                    state.storeDescriptorFingerprint)
                    && generation == state.generation
                    && stateFingerprint.equals(state.stateFingerprint)
                    && java.util.Objects.equals(stateCoreFingerprint,
                    state.stateCoreFingerprint)
                    && java.util.Objects.equals(
                    latestTransitionWitnessMaterialFingerprint,
                    state.latestWitnessMaterialFingerprint());
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
            node.put("messageVersion", messageVersion);
            node.put("storeDescriptorFingerprint", storeDescriptorFingerprint);
            node.put("generation", generation);
            if (EVIDENCE_CHECKPOINT_VERSION.equals(messageVersion)) {
                node.put("stateCoreFingerprint", stateCoreFingerprint);
            }
            node.put("stateFingerprint", stateFingerprint);
            node.put("revocationHeadSequence", revocationHeadSequence);
            node.put("revocationHeadFingerprint", revocationHeadFingerprint);
            if (EVIDENCE_CHECKPOINT_VERSION.equals(messageVersion)) {
                if (latestTransitionWitnessMaterialFingerprint == null) {
                    node.putNull("latestTransitionWitnessMaterialFingerprint");
                } else {
                    node.put("latestTransitionWitnessMaterialFingerprint",
                            latestTransitionWitnessMaterialFingerprint);
                }
            }
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
            } catch (IOException | RuntimeException failure) {
                throw new InvalidStoreMaterial();
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

    private static ObjectNode transitionWitnessNode(
            ExecutionLeaseTransitionWitness witness) {
        ObjectNode node = JSON.createObjectNode();
        node.put("fingerprint", witness.fingerprint());
        node.put("materialFingerprint", witness.materialFingerprint());
        node.put("storeDescriptorFingerprint", witness.storeDescriptorFingerprint());
        node.put("requestFingerprint", witness.requestFingerprint());
        node.put("receiptFingerprint", witness.receiptFingerprint());
        node.put("preStateFingerprint", witness.preStateFingerprint());
        node.put("preGeneration", witness.preGeneration());
        node.put("preFencingSequence", witness.preFencingSequence());
        node.put("preCheckpointFingerprint", witness.preCheckpointFingerprint());
        node.put("preRevocationHeadSequence", witness.preRevocationHeadSequence());
        node.put("preRevocationHeadFingerprint",
                witness.preRevocationHeadFingerprint());
        node.put("postStateCoreFingerprint", witness.postStateCoreFingerprint());
        node.put("postStateFingerprint", witness.postStateFingerprint());
        node.put("postGeneration", witness.postGeneration());
        node.put("postFencingSequence", witness.postFencingSequence());
        node.put("postCheckpointFingerprint", witness.postCheckpointFingerprint());
        node.put("postRevocationHeadSequence", witness.postRevocationHeadSequence());
        node.put("postRevocationHeadFingerprint",
                witness.postRevocationHeadFingerprint());
        return node;
    }

    private static ExecutionLeaseTransitionWitness transitionWitness(ObjectNode node) {
        requireFields(node, Set.of("fingerprint", "materialFingerprint",
                "storeDescriptorFingerprint",
                "requestFingerprint", "receiptFingerprint", "preStateFingerprint",
                "preGeneration", "preFencingSequence", "preCheckpointFingerprint",
                "preRevocationHeadSequence", "preRevocationHeadFingerprint",
                "postStateCoreFingerprint", "postStateFingerprint", "postGeneration",
                "postFencingSequence", "postCheckpointFingerprint",
                "postRevocationHeadSequence",
                "postRevocationHeadFingerprint"));
        return new ExecutionLeaseTransitionWitness(fingerprint(node, "fingerprint"),
                fingerprint(node, "materialFingerprint"),
                fingerprint(node, "storeDescriptorFingerprint"),
                fingerprint(node, "requestFingerprint"),
                fingerprint(node, "receiptFingerprint"),
                fingerprint(node, "preStateFingerprint"),
                nonNegativeLong(node, "preGeneration"),
                nonNegativeLong(node, "preFencingSequence"),
                fingerprint(node, "preCheckpointFingerprint"),
                nonNegativeLong(node, "preRevocationHeadSequence"),
                fingerprint(node, "preRevocationHeadFingerprint"),
                fingerprint(node, "postStateCoreFingerprint"),
                fingerprint(node, "postStateFingerprint"),
                positiveLong(node, "postGeneration"),
                positiveLong(node, "postFencingSequence"),
                fingerprint(node, "postCheckpointFingerprint"),
                nonNegativeLong(node, "postRevocationHeadSequence"),
                fingerprint(node, "postRevocationHeadFingerprint"));
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

    private static TransitionEvidence transitionEvidence(byte[] bytes) throws IOException {
        try {
            ObjectNode node = object(JSON.readTree(bytes));
            requireFields(node, Set.of("messageVersion", "evidenceTransactionId",
                    "receiptFingerprint", "witnessFingerprint",
                    "witnessMaterialFingerprint", "beforeObservation", "afterObservation",
                    "materialFingerprint", "transitionEvidenceFingerprint"));
            if (!TRANSITION_EVIDENCE_VERSION.equals(text(node, "messageVersion"))) {
                throw new IllegalArgumentException("transition evidence version");
            }
            CapabilityStudioDeploymentStateObservation.Observation before =
                    CapabilityStudioDeploymentStateObservation.verify(Base64.getDecoder()
                            .decode(text(node, "beforeObservation")));
            CapabilityStudioDeploymentStateObservation.Observation after =
                    CapabilityStudioDeploymentStateObservation.verify(Base64.getDecoder()
                            .decode(text(node, "afterObservation")));
            TransitionEvidence evidence = new TransitionEvidence(
                    fingerprint(node, "evidenceTransactionId"),
                    fingerprint(node, "receiptFingerprint"),
                    fingerprint(node, "witnessFingerprint"),
                    fingerprint(node, "witnessMaterialFingerprint"), before, after,
                    fingerprint(node, "materialFingerprint"),
                    fingerprint(node, "transitionEvidenceFingerprint"));
            String expectedMaterial = transitionEvidenceMaterialFingerprint(
                    evidence.evidenceTransactionId, evidence.receiptFingerprint,
                    evidence.witnessMaterialFingerprint, before,
                    after.revocationHeadSequence(), after.revocationHeadFingerprint(),
                    after.revocationHeadRawFingerprint(),
                    after.generation(), after.fencingSequence());
            if (before.phase() != CapabilityStudioDeploymentStateObservation.Phase.BEFORE
                    || after.phase()
                    != CapabilityStudioDeploymentStateObservation.Phase.AFTER
                    || !before.evidenceTransactionId().equals(evidence.evidenceTransactionId)
                    || !after.evidenceTransactionId().equals(evidence.evidenceTransactionId)
                    || !expectedMaterial.equals(evidence.materialFingerprint)
                    || !sha256(evidence.bytesWithFingerprint(null)).equals(
                    evidence.fingerprint)
                    || !Arrays.equals(bytes, evidence.bytes())) {
                throw new IllegalArgumentException("transition evidence binding");
            }
            return evidence;
        } catch (IOException | RuntimeException failure) {
            throw new InvalidStoreMaterial();
        }
    }

    private static final class StoredLease {
        private final String requestFingerprint;
        private final ExecutionLeaseReceipt receipt;
        private final String witnessMaterialFingerprint;
        private final ExecutionLeaseTransitionWitness transitionWitness;
        private final String evidenceClosureMaterialFingerprint;
        private final String postRevocationHeadRawFingerprint;
        private final String preStateMaterialFingerprint;

        private StoredLease(
                String requestFingerprint,
                ExecutionLeaseReceipt receipt,
                ExecutionLeaseTransitionWitness transitionWitness) {
            this(requestFingerprint, receipt,
                    transitionWitness == null ? null : transitionWitness.materialFingerprint(),
                    transitionWitness, null, null, null);
        }

        private StoredLease(
                String requestFingerprint,
                ExecutionLeaseReceipt receipt,
                ExecutionLeaseTransitionWitness transitionWitness,
                String preStateMaterialFingerprint) {
            this(requestFingerprint, receipt,
                    transitionWitness == null ? null : transitionWitness.materialFingerprint(),
                    transitionWitness, null, null, preStateMaterialFingerprint);
        }

        private StoredLease(
                String requestFingerprint,
                ExecutionLeaseReceipt receipt,
                String witnessMaterialFingerprint,
                ExecutionLeaseTransitionWitness transitionWitness,
                String evidenceClosureMaterialFingerprint,
                String postRevocationHeadRawFingerprint,
                String preStateMaterialFingerprint) {
            this.requestFingerprint = requestFingerprint;
            this.receipt = receipt;
            this.witnessMaterialFingerprint = witnessMaterialFingerprint;
            this.transitionWitness = transitionWitness;
            this.evidenceClosureMaterialFingerprint = evidenceClosureMaterialFingerprint;
            this.postRevocationHeadRawFingerprint = postRevocationHeadRawFingerprint;
            this.preStateMaterialFingerprint = preStateMaterialFingerprint;
        }

        private StoredLease withEvidenceMaterials(
                String witnessMaterialFingerprint,
                String evidenceClosureMaterialFingerprint,
                String postRevocationHeadRawFingerprint) {
            return new StoredLease(requestFingerprint, receipt, witnessMaterialFingerprint,
                    null, evidenceClosureMaterialFingerprint,
                    postRevocationHeadRawFingerprint, preStateMaterialFingerprint);
        }

        private StoredLease withWitness(ExecutionLeaseTransitionWitness witness) {
            return new StoredLease(requestFingerprint, receipt,
                    witness.materialFingerprint(), witness,
                    evidenceClosureMaterialFingerprint,
                    postRevocationHeadRawFingerprint, preStateMaterialFingerprint);
        }

        @Override
        public String toString() {
            return "StoredLease[material=REDACTED]";
        }
    }

    private record TransitionEvidence(
            String evidenceTransactionId,
            String receiptFingerprint,
            String witnessFingerprint,
            String witnessMaterialFingerprint,
            CapabilityStudioDeploymentStateObservation.Observation before,
            CapabilityStudioDeploymentStateObservation.Observation after,
            String materialFingerprint,
            String fingerprint) {
        private TransitionEvidence {
            requireFingerprint(evidenceTransactionId, "evidenceTransactionId");
            requireFingerprint(receiptFingerprint, "receiptFingerprint");
            requireFingerprint(witnessFingerprint, "witnessFingerprint");
            requireFingerprint(witnessMaterialFingerprint, "witnessMaterialFingerprint");
            java.util.Objects.requireNonNull(before, "before is required");
            java.util.Objects.requireNonNull(after, "after is required");
            requireFingerprint(materialFingerprint, "materialFingerprint");
            requireFingerprint(fingerprint, "fingerprint");
        }

        private static TransitionEvidence create(
                String evidenceTransactionId,
                ExecutionLeaseReceipt receipt,
                ExecutionLeaseTransitionWitness witness,
                CapabilityStudioDeploymentStateObservation.Observation before,
                CapabilityStudioDeploymentStateObservation.Observation after) {
            String material = transitionEvidenceMaterialFingerprint(evidenceTransactionId,
                    receipt.fingerprint(), witness.materialFingerprint(), before,
                    after.revocationHeadSequence(), after.revocationHeadFingerprint(),
                    after.revocationHeadRawFingerprint(),
                    after.generation(), after.fencingSequence());
            TransitionEvidence unhashed = new TransitionEvidence(evidenceTransactionId,
                    receipt.fingerprint(), witness.fingerprint(), witness.materialFingerprint(),
                    before, after, material, "sha256:" + "0".repeat(64));
            String fingerprint = sha256(unhashed.bytesWithFingerprint(null));
            return new TransitionEvidence(evidenceTransactionId, receipt.fingerprint(),
                    witness.fingerprint(), witness.materialFingerprint(), before, after,
                    material, fingerprint);
        }

        private byte[] bytes() {
            return bytesWithFingerprint(fingerprint);
        }

        private byte[] bytesWithFingerprint(String value) {
            ObjectNode node = JSON.createObjectNode();
            node.put("messageVersion", TRANSITION_EVIDENCE_VERSION);
            node.put("evidenceTransactionId", evidenceTransactionId);
            node.put("receiptFingerprint", receiptFingerprint);
            node.put("witnessFingerprint", witnessFingerprint);
            node.put("witnessMaterialFingerprint", witnessMaterialFingerprint);
            node.put("beforeObservation", Base64.getEncoder().encodeToString(before.bytes()));
            node.put("afterObservation", Base64.getEncoder().encodeToString(after.bytes()));
            node.put("materialFingerprint", materialFingerprint);
            if (value == null) {
                node.putNull("transitionEvidenceFingerprint");
            } else {
                node.put("transitionEvidenceFingerprint", value);
            }
            return writeJson(node);
        }
    }

    private record ReplayStep(
            long sequence,
            String leaseKey,
            StoredLease lease) { }

    private record ReplayIndex(List<ReplayStep> steps) {
        private ReplayIndex {
            steps = List.copyOf(steps);
        }
    }

    private record CommitOutcome(
            ExecutionLeaseCommitStatus status,
            ExecutionLeaseReceipt receipt,
            ExecutionLeaseTransitionWitness witness,
            TransitionEvidence transitionEvidence,
            String reasonCode) {
        private CommitOutcome(
                ExecutionLeaseCommitStatus status,
                ExecutionLeaseReceipt receipt,
                ExecutionLeaseTransitionWitness witness,
                String reasonCode) {
            this(status, receipt, witness, null, reasonCode);
        }
    }

    record ClosureFileMetadata(
            Object fileKey,
            long linkCount,
            long uid,
            int mode,
            long size,
            java.nio.file.attribute.FileTime modifiedTime) {
    }

    @FunctionalInterface
    interface ClosureContentReader {
        byte[] read(Path path, int maximumBytes) throws IOException;
    }

    @FunctionalInterface
    interface ClosureMetadataReader {
        ClosureFileMetadata read(Path path) throws IOException;
    }

    private record ClosureEntry(
            Path path, int maximumBytes, ClosureFileMetadata identity) {
    }

    private record StoreClosureInventory(
            TreeMap<String, ClosureEntry> entries, long aggregateBytes) {
        private StoreClosureInventory {
            entries = new TreeMap<>(entries);
        }
    }

    private record SealedFile(ClosureFileMetadata identity, String rawFingerprint) {
    }

    private record StoreClosureSeal(TreeMap<String, SealedFile> files) {
        private StoreClosureSeal {
            files = new TreeMap<>(files);
        }
    }

    private record ObservedFile(byte[] bytes, ClosureFileMetadata identity) {
        private ObservedFile {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ObservedFile observed
                    && identity.equals(observed.identity)
                    && Arrays.equals(bytes, observed.bytes);
        }

        @Override
        public int hashCode() {
            return 31 * identity.hashCode() + Arrays.hashCode(bytes);
        }
    }

    private record StoreClosure(
            ObservedFile descriptor,
            ObservedFile state,
            ObservedFile checkpoint,
            ObservedFile revocationHead,
            TreeMap<String, ObservedFile> transitions,
            StoreClosureSeal seal) {
        private StoreClosure {
            transitions = new TreeMap<>(transitions);
        }
    }

    private record AncestorIdentity(long device, long inode, long uid, int mode) {
        private boolean sameObject(AncestorIdentity other) {
            return other != null && device == other.device && inode == other.inode;
        }
    }

    private record DirectoryIdentity(
            Object fileKey, long device, long inode, long links, long uid, int mode) {
        private boolean sameObject(DirectoryIdentity other) {
            return other != null && (java.util.Objects.equals(fileKey, other.fileKey)
                    || (device == other.device && inode == other.inode));
        }
    }

    private record DisjointEvidenceIdentity(
            Path evidenceRoot,
            Path stateRoot,
            List<AncestorIdentity> evidenceAncestors,
            List<AncestorIdentity> stateAncestors) {
        private void recheck() {
            try {
                if (!evidenceAncestors.equals(ancestorIdentities(evidenceRoot))
                        || !stateAncestors.equals(ancestorIdentities(stateRoot))) {
                    throw new StateUnavailable();
                }
            } catch (IOException | InvalidStoreMaterial unavailable) {
                throw new StateUnavailable();
            }
        }
    }

    private record RecoveryDisjointEvidenceIdentity(
            Path evidenceRoot,
            Path stateRoot,
            List<DirectoryIdentity> evidenceAncestors,
            List<DirectoryIdentity> stateAncestors) {
        private void recheck() {
            try {
                List<DirectoryIdentity> currentEvidence = directoryIdentities(evidenceRoot);
                List<DirectoryIdentity> currentState = directoryIdentities(stateRoot);
                if (!evidenceAncestors.equals(currentEvidence)
                        || !stateAncestors.equals(currentState)) {
                    throw new StateUnavailable();
                }
                DirectoryIdentity evidence = currentEvidence.getFirst();
                DirectoryIdentity state = currentState.getFirst();
                if (evidence.sameObject(state)
                        || currentEvidence.stream().anyMatch(value -> value.sameObject(state))
                        || currentState.stream().anyMatch(value -> value.sameObject(evidence))) {
                    throw new InvalidStoreMaterial();
                }
            } catch (InvalidStoreMaterial invalid) {
                throw invalid;
            } catch (IOException | RuntimeException unavailable) {
                throw new StateUnavailable();
            }
        }
    }

    private record FileIdentity(
            Object fileKey, long size, java.nio.file.attribute.FileTime modifiedTime) { }

    @FunctionalInterface
    interface MonotonicTicker {
        long read();
    }

    private interface LockBudget {
        long remaining();

        long initialBudget();
    }

    private static void requireReplayBudget(LockBudget budget) {
        if (Thread.currentThread().isInterrupted() || budget.remaining() <= 0) {
            throw new StateUnavailable();
        }
    }

    private static final class SharedEvidenceLeaseBudget implements LockBudget {
        private final EvidenceLeaseBudget shared;

        private SharedEvidenceLeaseBudget(EvidenceLeaseBudget shared) {
            this.shared = java.util.Objects.requireNonNull(shared, "shared is required");
        }

        @Override
        public long remaining() {
            return shared.remainingNanos();
        }

        @Override
        public long initialBudget() {
            return EvidenceLeaseBudget.MAXIMUM_NANOS;
        }
    }

    static final class ObservationLeaseBudget implements LockBudget {
        private final long initialBudget;
        private final MonotonicTicker ticker;
        private long lastTick;
        private long remaining;

        ObservationLeaseBudget(long budgetNanos, MonotonicTicker ticker) {
            if (budgetNanos <= 0) {
                throw new IllegalArgumentException("observation lock budget is invalid");
            }
            this.initialBudget = budgetNanos;
            this.remaining = budgetNanos;
            this.ticker = java.util.Objects.requireNonNull(ticker, "ticker is required");
            this.lastTick = ticker.read();
        }

        @Override
        public synchronized long remaining() {
            long current = ticker.read();
            long elapsed = current - lastTick;
            if (elapsed < 0) {
                throw new IllegalStateException("monotonic ticker moved backwards");
            }
            lastTick = current;
            if (elapsed >= remaining) {
                remaining = 0;
            } else {
                remaining -= elapsed;
            }
            return remaining;
        }

        @Override
        public long initialBudget() {
            return initialBudget;
        }
    }

    private static final class UnsafeStoreException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static final class StateUnavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class InvalidStoreMaterial extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
