package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Declares, durably snapshots, recovers, and verifies one flat formal-v2 input tree.
 *
 * <p>The portable tree identity contains only fixed versions, the tree kind, Bundle semantic
 * fingerprint, sorted direct-child names, byte sizes, and exact raw SHA-256 fingerprints. Paths,
 * timestamps, file identities, ownership, and permissions are runtime stability controls and do
 * not enter that identity.</p>
 *
 * <p>A committed output is one wrapper directory containing only {@value #BUNDLE_ROOT_DIRECTORY}
 * and {@value #MANIFEST_FILE}. The wrapper is created as PREPARED and the manifest is atomically
 * installed last as the sole logical commit marker. A declaration is not evidence and cannot
 * authenticate its tree or publication pin; Deployment Authority must approve both independently.
 * This local protocol assumes a private POSIX publication parent, the fixed publication lease, and
 * honest filesystem identity metadata. A privileged actor, or a same-UID actor that ignores the
 * lease and performs an exact ABA replacement in the remaining path-based windows, is outside its
 * threat model and requires process or mount isolation.</p>
 */
public final class CapabilityStudioFormalInputTreeSnapshotter {
    /** Fixed canonical tree message and schema version. */
    public static final String MESSAGE_VERSION =
            "resource-gateway.capability-studio.formal-input-tree.v1";
    /** Fixed strict schema version. */
    public static final String SCHEMA_VERSION = MESSAGE_VERSION;
    /** Fixed transaction identity domain. */
    public static final String TRANSACTION_MESSAGE_VERSION =
            "resource-gateway.capability-studio.formal-input-tree-transaction.v1";
    /** Fixed snapshot receipt domain. */
    public static final String RECEIPT_MESSAGE_VERSION =
            "resource-gateway.capability-studio.formal-input-tree-snapshot-receipt.v1";
    /** Fixed transaction-owner receipt domain. */
    public static final String OWNER_MESSAGE_VERSION =
            "resource-gateway.capability-studio.formal-input-tree-owner.v1";
    /** Fixed wrapper child containing the Provider-compatible Bundle files. */
    public static final String BUNDLE_ROOT_DIRECTORY = "bundle-root";
    /** Fixed wrapper child containing the strict external tree manifest. */
    public static final String MANIFEST_FILE = "formal-input-tree-v1.json";

    /** Authority maximum: one manifest, 512 artifacts, and 128 policy key-set bindings. */
    public static final int MAXIMUM_AUTHORITY_ENTRY_COUNT = 641;
    /** Authority Bundle manifest limit inherited from the mounted Authority loader. */
    public static final int MAXIMUM_AUTHORITY_MANIFEST_BYTES = 1024 * 1024;
    /** Authority artifact limit inherited from the mounted Authority loader. */
    public static final int MAXIMUM_AUTHORITY_ARTIFACT_BYTES = 64 * 1024;
    /** Authority key-set limit inherited from the mounted Authority loader. */
    public static final int MAXIMUM_AUTHORITY_KEY_SET_BYTES = 1024 * 1024;
    /** Authority referenced-material limit, excluding its manifest. */
    public static final long MAXIMUM_AUTHORITY_REFERENCED_BYTES = 32L * 1024 * 1024;
    /** Authority complete tree limit, including its manifest. */
    public static final long MAXIMUM_AUTHORITY_TOTAL_BYTES = 33L * 1024 * 1024;

    /** Target Admission has one manifest and exactly seven referenced files. */
    public static final int TARGET_ADMISSION_ENTRY_COUNT = 8;
    /** Target Admission manifest limit inherited from its mounted loader. */
    public static final int MAXIMUM_TARGET_ADMISSION_MANIFEST_BYTES = 1024 * 1024;
    /** Target binding and each Candidate/Environment Attestation limit. */
    public static final int MAXIMUM_TARGET_ADMISSION_DOCUMENT_BYTES =
            CapabilityStudioStageAcceptanceTargetBindingVerifier.MAXIMUM_DOCUMENT_BYTES;
    /** Candidate or Environment key-set limit inherited from the mounted loader. */
    public static final int MAXIMUM_TARGET_ADMISSION_KEY_SET_BYTES = 1024 * 1024;
    /** Candidate or Environment detached-proof limit inherited from the mounted loader. */
    public static final int MAXIMUM_TARGET_ADMISSION_PROOF_BYTES = 64 * 1024;
    /** Complete Target Admission tree limit, including its manifest. */
    public static final long MAXIMUM_TARGET_ADMISSION_TOTAL_BYTES = 8L * 1024 * 1024;

    /** Broad schema/API entry maximum; kind-specific limits remain authoritative. */
    public static final int MAXIMUM_ENTRY_COUNT = MAXIMUM_AUTHORITY_ENTRY_COUNT;
    /** Broad schema/API single-file maximum. */
    public static final int MAXIMUM_FILE_BYTES = MAXIMUM_AUTHORITY_MANIFEST_BYTES;
    /** Broad schema/API complete-tree maximum. */
    public static final long MAXIMUM_TOTAL_BYTES = MAXIMUM_AUTHORITY_TOTAL_BYTES;
    /** Production upper bound for acquiring the process-wide and filesystem publication lease. */
    public static final Duration DEFAULT_PUBLICATION_LEASE_TIMEOUT = Duration.ofSeconds(5);

    private static final String CODE = "RG.CAPABILITY_STUDIO.FORMAL_INPUT_TREE.";
    private static final String PUBLICATION_LOCK_FILE =
            ".formal-input-tree-publication-v1.lock";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern SAFE_NAME = Pattern.compile(
            "(?!\\.{1,2}$)[A-Za-z0-9][A-Za-z0-9._-]{0,254}");
    private static final Pattern SAFE_OUTPUT_NAME = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY =
            PosixFilePermissions.fromString("r-x------");
    private static final Set<PosixFilePermission> BUILD_DIRECTORY =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> PRIVATE_FILE =
            PosixFilePermissions.fromString("r--------");
    private static final Set<PosixFilePermission> BUILD_FILE =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> LOCK_FILE = BUILD_FILE;
    private static final int STICKY_BIT = 01000;
    private static final int PUBLICATION_LOCK_STRIPE_COUNT = 64;
    private static final long PUBLICATION_LOCK_RETRY_NANOS = Duration.ofMillis(20).toNanos();
    private static final int MAXIMUM_PUBLICATION_LOCK_MISSES = 1024;
    private static final ReentrantLock[] JVM_PUBLICATION_LOCKS = publicationLockStripes();
    private static final ObjectMapper JSON = new ObjectMapper(new JsonFactory().rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());

    private final SnapshotObserver observer;
    private final AtomicOperations operations;
    private final long publicationLeaseTimeoutNanos;
    private final MonotonicTicker ticker;

    /** Creates a snapshotter backed by the local fail-closed POSIX implementation. */
    public CapabilityStudioFormalInputTreeSnapshotter() {
        this(SnapshotObserver.NONE, new LocalAtomicOperations(), DEFAULT_PUBLICATION_LEASE_TIMEOUT,
                System::nanoTime);
    }

    CapabilityStudioFormalInputTreeSnapshotter(
            SnapshotObserver observer,
            AtomicOperations operations) {
        this(observer, operations, DEFAULT_PUBLICATION_LEASE_TIMEOUT, System::nanoTime);
    }

    static int publicationLockRegistrySizeForTesting() {
        return JVM_PUBLICATION_LOCKS.length;
    }

    CapabilityStudioFormalInputTreeSnapshotter(
            SnapshotObserver observer,
            AtomicOperations operations,
            Duration publicationLeaseTimeout) {
        this(observer, operations, publicationLeaseTimeout, System::nanoTime);
    }

    CapabilityStudioFormalInputTreeSnapshotter(
            SnapshotObserver observer,
            AtomicOperations operations,
            Duration publicationLeaseTimeout,
            MonotonicTicker ticker) {
        this.observer = Objects.requireNonNull(observer, "observer");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        Duration timeout = Objects.requireNonNull(
                publicationLeaseTimeout, "publicationLeaseTimeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("publicationLeaseTimeout must be positive");
        }
        try {
            this.publicationLeaseTimeoutNanos = timeout.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("publicationLeaseTimeout is too large", failure);
        }
    }

    /** Supported mounted Bundle roles and their closed capacity policy. */
    public enum TreeKind {
        /** Post-run Evidence/Owner Authority Bundle. */
        AUTHORITY_BUNDLE(
                CapabilityStudioMountedAuthorityBundle.MANIFEST_FILE,
                CapabilityStudioSchemaSupport.MOUNTED_AUTHORITY_BUNDLE_V1_RESOURCE,
                MAXIMUM_AUTHORITY_ENTRY_COUNT,
                MAXIMUM_AUTHORITY_MANIFEST_BYTES,
                MAXIMUM_AUTHORITY_TOTAL_BYTES),
        /** Candidate/Environment Target Admission Bundle. */
        TARGET_ADMISSION_BUNDLE(
                CapabilityStudioMountedTargetAdmissionBundle.MANIFEST_FILE,
                CapabilityStudioSchemaSupport.MOUNTED_TARGET_ADMISSION_BUNDLE_V1_RESOURCE,
                TARGET_ADMISSION_ENTRY_COUNT,
                MAXIMUM_TARGET_ADMISSION_MANIFEST_BYTES,
                MAXIMUM_TARGET_ADMISSION_TOTAL_BYTES);

        private final String bundleManifestFile;
        private final String schemaResource;
        private final int maximumEntries;
        private final int maximumManifestBytes;
        private final long maximumTotalBytes;

        TreeKind(
                String bundleManifestFile,
                String schemaResource,
                int maximumEntries,
                int maximumManifestBytes,
                long maximumTotalBytes) {
            this.bundleManifestFile = bundleManifestFile;
            this.schemaResource = schemaResource;
            this.maximumEntries = maximumEntries;
            this.maximumManifestBytes = maximumManifestBytes;
            this.maximumTotalBytes = maximumTotalBytes;
        }

        private boolean validEntryCount(int count) {
            return this == TARGET_ADMISSION_BUNDLE
                    ? count == TARGET_ADMISSION_ENTRY_COUNT
                    : count >= 1 && count <= maximumEntries;
        }
    }

    /** Stable closed failure class used by the CLI without exposing paths or material. */
    public enum FailureKind {
        /** Source bytes, structure, pin, or caller configuration are invalid. */
        INVALID,
        /** Output state, dependency, metadata, or durability capability is unavailable. */
        UNAVAILABLE
    }

    /** Invocation-local durable publication result. */
    public enum CommitStatus {
        /** This invocation atomically published the wrapper. */
        COMMITTED,
        /** This invocation recovered an already published exact wrapper. */
        RECOVERED
    }

    /**
     * One sorted direct-child content coordinate.
     *
     * @param relativePath safe direct-child filename
     * @param byteSize exact raw byte count
     * @param rawFingerprint exact raw SHA-256 fingerprint
     */
    public record TreeEntry(String relativePath, long byteSize, String rawFingerprint) {
        /** Validates one portable entry against the broad schema bounds. */
        public TreeEntry {
            if (relativePath == null || !SAFE_NAME.matcher(relativePath).matches()
                    || byteSize < 1 || byteSize > MAXIMUM_FILE_BYTES
                    || !fingerprint(rawFingerprint)) {
                throw invalid("ENTRY_INVALID");
            }
        }

        @Override
        public String toString() {
            return "TreeEntry[redacted]";
        }
    }

    /**
     * Immutable portable tree declaration.
     *
     * @param messageVersion fixed canonical message version
     * @param schemaVersion fixed strict schema version
     * @param treeKind mounted Bundle role
     * @param bundleSemanticFingerprint exact Bundle semantic coordinate
     * @param entryCount number of sorted direct-child entries
     * @param totalByteSize aggregate raw byte count
     * @param entries sorted immutable content coordinates
     * @param treeFingerprint canonical declaration fingerprint
     */
    public record Declaration(
            String messageVersion,
            String schemaVersion,
            TreeKind treeKind,
            String bundleSemanticFingerprint,
            int entryCount,
            long totalByteSize,
            List<TreeEntry> entries,
            String treeFingerprint) {

        /** Validates all derived fields and the canonical self fingerprint. */
        public Declaration {
            entries = entries == null ? List.of() : List.copyOf(entries);
            if (!MESSAGE_VERSION.equals(messageVersion) || !SCHEMA_VERSION.equals(schemaVersion)
                    || treeKind == null || !fingerprint(bundleSemanticFingerprint)
                    || entryCount != entries.size() || !treeKind.validEntryCount(entryCount)
                    || totalByteSize != totalBytes(entries)
                    || totalByteSize < 1 || totalByteSize > treeKind.maximumTotalBytes
                    || !strictlySorted(entries) || !fingerprint(treeFingerprint)) {
                throw invalid("DECLARATION_INVALID");
            }
            String expected = sha256(treeCanonicalJson(
                    messageVersion, schemaVersion, treeKind, bundleSemanticFingerprint,
                    entryCount, totalByteSize, entries, null));
            if (!expected.equals(treeFingerprint)) {
                throw invalid("TREE_FINGERPRINT_INVALID");
            }
        }

        /**
         * Returns the exact compact tree fingerprint message with its self field set to null.
         *
         * @return canonical UTF-8 JSON text
         */
        public String canonicalMessage() {
            return treeCanonicalJson(messageVersion, schemaVersion, treeKind,
                    bundleSemanticFingerprint, entryCount, totalByteSize, entries, null);
        }

        /**
         * Returns the exact committed manifest message for independently reproducible bytes.
         *
         * @param publicationFingerprint independently approved publication coordinate
         * @param transactionNonce high-entropy stable transaction nonce coordinate
         * @param transactionId transaction identity derived from all publication coordinates
         * @return exact compact UTF-8 JSON text
         */
        public String committedManifestCanonicalMessage(
                String publicationFingerprint,
                String transactionNonce,
                String transactionId) {
            requireFingerprint(publicationFingerprint, "PUBLICATION_FINGERPRINT_INVALID");
            requireFingerprint(transactionNonce, "TRANSACTION_NONCE_INVALID");
            requireFingerprint(transactionId, "TRANSACTION_ID_INVALID");
            String expected = computeTransactionId(
                    treeKind, bundleSemanticFingerprint, treeFingerprint,
                    publicationFingerprint, transactionNonce);
            if (!expected.equals(transactionId)) {
                throw invalid("TRANSACTION_ID_INVALID");
            }
            return manifestCanonicalJson(
                    this, publicationFingerprint, transactionNonce, transactionId);
        }

        private byte[] manifestBytes(
                String publicationFingerprint,
                String transactionNonce,
                String transactionId) {
            return committedManifestCanonicalMessage(
                    publicationFingerprint, transactionNonce, transactionId)
                    .getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String toString() {
            return "Declaration[redacted]";
        }
    }

    /**
     * Reproducible durable snapshot receipt.
     *
     * @param commitStatus invocation-local publication status
     * @param transactionId stable transaction identity
     * @param publicationFingerprint independently approved publication coordinate
     * @param transactionNonce high-entropy stable transaction nonce coordinate
     * @param committedManifestFingerprint exact committed manifest raw fingerprint
     * @param receiptFingerprint deterministic receipt fingerprint
     * @param declaration exact committed declaration
     */
    public record SnapshotReceipt(
            CommitStatus commitStatus,
            String transactionId,
            String publicationFingerprint,
            String transactionNonce,
            String committedManifestFingerprint,
            String receiptFingerprint,
            Declaration declaration) {
        /** Validates the status and reproducible receipt coordinate. */
        public SnapshotReceipt {
            if (commitStatus == null || declaration == null || !fingerprint(transactionId)
                    || !fingerprint(publicationFingerprint)
                    || !fingerprint(transactionNonce)
                    || !fingerprint(committedManifestFingerprint)
                    || !fingerprint(receiptFingerprint)
                    || !transactionId.equals(
                            computeTransactionId(
                                    declaration.treeKind(),
                                    declaration.bundleSemanticFingerprint(),
                                    declaration.treeFingerprint(), publicationFingerprint,
                                    transactionNonce))
                    || !committedManifestFingerprint.equals(sha256(
                            declaration.manifestBytes(
                                    publicationFingerprint, transactionNonce, transactionId)))
                    || !receiptFingerprint.equals(
                            CapabilityStudioFormalInputTreeSnapshotter.receiptFingerprint(
                                    transactionId, publicationFingerprint, transactionNonce,
                                    committedManifestFingerprint, declaration))) {
                throw invalid("SNAPSHOT_RECEIPT_INVALID");
            }
        }

        @Override
        public String toString() {
            return "SnapshotReceipt[redacted]";
        }
    }

    /** Redacted fail-closed exception with a stable machine-readable category. */
    public static final class FormalInputTreeException extends IllegalStateException {
        /** Closed category exposed without material or path detail. */
        private final FailureKind failureKind;

        private FormalInputTreeException(FailureKind failureKind, String code) {
            super(CODE + code);
            this.failureKind = failureKind;
        }

        /**
         * Returns the closed failure category.
         *
         * @return invalid or unavailable
         */
        public FailureKind failureKind() {
            return failureKind;
        }
    }

    /**
     * Computes a read-only declaration after two complete stable source inventories.
     *
     * @param treeKind Bundle role
     * @param sourceRoot absolute normalized mounted Bundle root
     * @param expectedBundleSemanticFingerprint independently supplied Bundle semantic coordinate
     * @return immutable declaration
     */
    public Declaration declare(
            TreeKind treeKind,
            Path sourceRoot,
            String expectedBundleSemanticFingerprint) {
        requireFingerprint(expectedBundleSemanticFingerprint, "SEMANTIC_FINGERPRINT_INVALID");
        Path root = absoluteNormalized(sourceRoot, "SOURCE_ROOT_INVALID");
        Inventory first = inventory(treeKind, root, InventoryPass.FIRST,
                expectedBundleSemanticFingerprint, false);
        observer.afterInventory(InventoryPass.FIRST, root);
        Inventory second = inventory(treeKind, root, InventoryPass.SECOND,
                expectedBundleSemanticFingerprint, false);
        observer.afterInventory(InventoryPass.SECOND, root);
        requireSameInventory(first, second);
        return declarationFromFiles(treeKind, expectedBundleSemanticFingerprint, first.files());
    }

    /**
     * Builds a deterministic declaration for already measured, sorted entries.
     *
     * @param treeKind Bundle role
     * @param bundleSemanticFingerprint exact Bundle semantic coordinate
     * @param entries strictly ascending direct-child entries
     * @return validated declaration with its derived self fingerprint
     */
    public static Declaration createDeclaration(
            TreeKind treeKind,
            String bundleSemanticFingerprint,
            List<TreeEntry> entries) {
        requireFingerprint(bundleSemanticFingerprint, "SEMANTIC_FINGERPRINT_INVALID");
        List<TreeEntry> copy = entries == null ? List.of() : List.copyOf(entries);
        if (treeKind == null || !treeKind.validEntryCount(copy.size())
                || !strictlySorted(copy)) {
            throw invalid("ENTRY_ORDER_INVALID");
        }
        long total = totalBytes(copy);
        String canonical = treeCanonicalJson(
                MESSAGE_VERSION, SCHEMA_VERSION, treeKind, bundleSemanticFingerprint,
                copy.size(), total, copy, null);
        return new Declaration(
                MESSAGE_VERSION, SCHEMA_VERSION, treeKind, bundleSemanticFingerprint,
                copy.size(), total, copy, sha256(canonical));
    }

    /**
     * Durably publishes or recovers one independently pinned publication.
     *
     * <p>The publication parent must be an exact {@code 0700} directory. A process-wide and
     * cross-process lease serializes final inspection, PREPARED recovery, and commit. The final
     * directory is created with create-new semantics; {@value #MANIFEST_FILE} is atomically
     * installed last and is the sole logical commit marker.</p>
     *
     * @param treeKind Bundle role
     * @param sourceRoot absolute normalized mounted Bundle root
     * @param expectedBundleSemanticFingerprint independently supplied Bundle semantic coordinate
     * @param snapshotOutputDirectory final wrapper directory
     * @param expectedTreeFingerprint independently approved portable tree pin
     * @param expectedPublicationFingerprint independently approved publication pin
     * @param transactionNonce high-entropy stable transaction nonce coordinate
     * @return reproducible committed or recovered receipt
     */
    public SnapshotReceipt snapshot(
            TreeKind treeKind,
            Path sourceRoot,
            String expectedBundleSemanticFingerprint,
            Path snapshotOutputDirectory,
            String expectedTreeFingerprint,
            String expectedPublicationFingerprint,
            String transactionNonce) {
        if (treeKind == null) {
            throw invalid("TREE_KIND_INVALID");
        }
        requireFingerprint(expectedBundleSemanticFingerprint, "SEMANTIC_FINGERPRINT_INVALID");
        requireFingerprint(expectedTreeFingerprint, "TREE_PIN_INVALID");
        requireFingerprint(expectedPublicationFingerprint, "PUBLICATION_FINGERPRINT_INVALID");
        requireFingerprint(transactionNonce, "TRANSACTION_NONCE_INVALID");
        Path source = absoluteNormalized(sourceRoot, "SOURCE_ROOT_INVALID");
        Path output = outputPath(snapshotOutputDirectory);
        if (output.equals(source) || output.startsWith(source) || source.startsWith(output)) {
            throw invalid("OUTPUT_LOCATION_INVALID");
        }
        String transactionId = computeTransactionId(
                treeKind, expectedBundleSemanticFingerprint, expectedTreeFingerprint,
                expectedPublicationFingerprint, transactionNonce);
        PublicationCoordinates coordinates = new PublicationCoordinates(
                treeKind, expectedBundleSemanticFingerprint, expectedTreeFingerprint,
                expectedPublicationFingerprint, transactionNonce, transactionId);
        PublicationPaths paths = publicationPaths(output, transactionId, transactionNonce);
        AncestorChain outputChain = capturePublicationChain(output.getParent());
        if (!present(paths.output()) && !present(paths.staging())
                && !present(paths.owner()) && !present(paths.ownerBootstrap())) {
            Declaration preflight = declare(
                    treeKind, source, expectedBundleSemanticFingerprint);
            if (!expectedTreeFingerprint.equals(preflight.treeFingerprint())) {
                throw invalid("TREE_PIN_MISMATCH");
            }
        }
        return withPublicationLease(paths, outputChain, true,
                () -> snapshotLocked(source, paths, coordinates, outputChain));
    }

    /**
     * Independently verifies one committed wrapper against every out-of-band coordinate.
     *
     * <p>This method is a pure read-only offline audit: it does not create or acquire the
     * publication lease and does not issue a durability receipt. The wrapper may therefore be
     * relocated beneath a non-shared-writable verification parent.</p>
     *
     * @param snapshotOutputDirectory absolute normalized wrapper directory
     * @param expectedTreeKind independently supplied Bundle role
     * @param expectedBundleSemanticFingerprint independently supplied Bundle semantic coordinate
     * @param expectedTreeFingerprint independently supplied portable tree pin
     * @param expectedPublicationFingerprint independently supplied publication pin
     * @param expectedTransactionId independently supplied stable transaction identity
     * @return independently reproduced declaration
     */
    public Declaration verify(
            Path snapshotOutputDirectory,
            TreeKind expectedTreeKind,
            String expectedBundleSemanticFingerprint,
            String expectedTreeFingerprint,
            String expectedPublicationFingerprint,
            String expectedTransactionId) {
        if (expectedTreeKind == null) {
            throw invalid("TREE_KIND_INVALID");
        }
        requireFingerprint(expectedBundleSemanticFingerprint, "SEMANTIC_FINGERPRINT_INVALID");
        requireFingerprint(expectedTreeFingerprint, "TREE_PIN_INVALID");
        requireFingerprint(expectedPublicationFingerprint, "PUBLICATION_FINGERPRINT_INVALID");
        requireFingerprint(expectedTransactionId, "TRANSACTION_ID_INVALID");
        Path output = outputPath(snapshotOutputDirectory);
        AncestorChain verificationChain = captureVerificationChain(output.getParent());
        return verifyCommittedWrapper(
                output, expectedTreeKind, expectedBundleSemanticFingerprint,
                expectedTreeFingerprint, expectedPublicationFingerprint,
                expectedTransactionId, verificationChain).declaration();
    }

    /**
     * Computes the stable transaction identity over all governed publication coordinates.
     *
     * @param treeKind Bundle role
     * @param bundleSemanticFingerprint exact Bundle semantic coordinate
     * @param treeFingerprint independently approved portable tree pin
     * @param publicationFingerprint independently approved publication pin
     * @param transactionNonce high-entropy stable transaction nonce coordinate
     * @return strict lowercase SHA-256 transaction identity
     */
    public static String computeTransactionId(
            TreeKind treeKind,
            String bundleSemanticFingerprint,
            String treeFingerprint,
            String publicationFingerprint,
            String transactionNonce) {
        if (treeKind == null) {
            throw invalid("TREE_KIND_INVALID");
        }
        requireFingerprint(bundleSemanticFingerprint, "SEMANTIC_FINGERPRINT_INVALID");
        requireFingerprint(treeFingerprint, "TREE_PIN_INVALID");
        requireFingerprint(publicationFingerprint, "PUBLICATION_FINGERPRINT_INVALID");
        requireFingerprint(transactionNonce, "TRANSACTION_NONCE_INVALID");
        return sha256(transactionCanonicalJson(
                treeKind, bundleSemanticFingerprint, treeFingerprint,
                publicationFingerprint, transactionNonce));
    }

    private SnapshotReceipt snapshotLocked(
            Path source,
            PublicationPaths paths,
            PublicationCoordinates coordinates,
            AncestorChain outputChain) {
        recheckAncestorChain(outputChain);
        SnapshotReceipt recovered = recoverCommittedIfPresent(
                paths, coordinates, outputChain);
        if (recovered != null) {
            return recovered;
        }
        requirePreparedFinalOwner(
                paths, coordinates, outputChain.publicationOwnerUid());

        Inventory first = inventory(
                coordinates.treeKind(), source, InventoryPass.FIRST,
                coordinates.bundleSemanticFingerprint(), false);
        observer.afterInventory(InventoryPass.FIRST, source);
        Declaration declaration = declarationFromFiles(
                coordinates.treeKind(), coordinates.bundleSemanticFingerprint(), first.files());
        if (!coordinates.treeFingerprint().equals(declaration.treeFingerprint())) {
            throw invalid("TREE_PIN_MISMATCH");
        }

        OwnerDescriptor owner = ownerDescriptor(coordinates);
        ensureOwnerDescriptor(paths, owner, outputChain.publicationOwnerUid());
        prepareStaging(paths, coordinates, declaration, first, outputChain);

        Inventory second = inventory(
                coordinates.treeKind(), source, InventoryPass.SECOND,
                coordinates.bundleSemanticFingerprint(), false);
        observer.afterInventory(InventoryPass.SECOND, source);
        requireSameInventory(first, second);
        recheckAncestorChain(outputChain);

        prepareFinalDirectory(paths, owner, declaration, outputChain.publicationOwnerUid());
        installBundleRoot(paths, declaration, outputChain.publicationOwnerUid());
        installCommitManifest(paths, coordinates, declaration, outputChain.publicationOwnerUid());
        finishCommittedDirectory(paths, owner, declaration, outputChain);
        WrapperObservation committed = verifyCommittedWrapper(
                paths.output(), coordinates.treeKind(),
                coordinates.bundleSemanticFingerprint(), coordinates.treeFingerprint(),
                coordinates.publicationFingerprint(), coordinates.transactionId(), outputChain);
        observer.afterPersistedVerify(paths.output());
        cleanupOwnedResidue(paths, owner, outputChain.publicationOwnerUid());
        return receipt(CommitStatus.COMMITTED, coordinates, committed.declaration());
    }

    private SnapshotReceipt recoverCommittedIfPresent(
            PublicationPaths paths,
            PublicationCoordinates coordinates,
            AncestorChain outputChain) {
        if (!present(paths.output())) {
            return null;
        }
        DirectoryState state = directoryState(paths.output());
        if (state.ownerUid() != outputChain.publicationOwnerUid()) {
            throw unavailable("FINAL_OUTPUT_CONFLICT");
        }
        Path manifest = paths.output().resolve(MANIFEST_FILE);
        if (!present(manifest)) {
            return null;
        }
        OwnerDescriptor owner = ownerDescriptor(coordinates);
        if (present(paths.manifestPart())) {
            requireExactOwnerDescriptor(paths, owner, outputChain.publicationOwnerUid());
            byte[] expected = readRecoverableInstalledFile(
                    paths.finalManifest(), coordinates.treeKind().maximumManifestBytes,
                    outputChain.publicationOwnerUid(), "MANIFEST_INSTALL_CONFLICT");
            parseManifest(expected, coordinates.treeKind(),
                    coordinates.bundleSemanticFingerprint(), coordinates.treeFingerprint(),
                    coordinates.publicationFingerprint(), coordinates.transactionId());
            reconcileFileInstall(
                    paths.manifestPart(), paths.finalManifest(), expected,
                    outputChain.publicationOwnerUid(),
                    "MANIFEST_INSTALL_CONFLICT", "MANIFEST_INSTALL_UNAVAILABLE");
        }
        if (state.permissions().equals(BUILD_DIRECTORY)) {
            requireExactOwnerDescriptor(paths, owner, outputChain.publicationOwnerUid());
            requireCommittedClosure(
                    paths.output(), coordinates, outputChain.publicationOwnerUid(), true);
            forceDirectory(paths.output(), "FINAL_DIRECTORY_FORCE_UNAVAILABLE");
            finishDirectory(paths.output(), state.identity(), outputChain.publicationOwnerUid());
        } else if (!state.permissions().equals(PRIVATE_DIRECTORY)) {
            throw unavailable("FINAL_OUTPUT_CONFLICT");
        }
        Declaration declaration = requireCommittedClosure(
                paths.output(), coordinates, outputChain.publicationOwnerUid(), false);
        stabilizeCommittedRenameResidue(
                paths, coordinates, declaration, owner, outputChain.publicationOwnerUid());
        forceDirectory(paths.output(), "FINAL_DIRECTORY_FORCE_UNAVAILABLE");
        forceDirectory(paths.parent(), "OUTPUT_PARENT_FORCE_UNAVAILABLE");
        observer.afterParentForce(paths.output());
        WrapperObservation committed = verifyCommittedWrapper(
                paths.output(), coordinates.treeKind(),
                coordinates.bundleSemanticFingerprint(), coordinates.treeFingerprint(),
                coordinates.publicationFingerprint(), coordinates.transactionId(), outputChain);
        cleanupOwnedResidue(paths, owner, outputChain.publicationOwnerUid());
        return receipt(CommitStatus.RECOVERED, coordinates, committed.declaration());
    }

    private void stabilizeCommittedRenameResidue(
            PublicationPaths paths,
            PublicationCoordinates coordinates,
            Declaration declaration,
            OwnerDescriptor owner,
            long ownerUid) {
        if (!present(paths.staging())) {
            return;
        }
        requireExactOwnerDescriptor(paths, owner, ownerUid);
        requireBuildDirectory(paths.staging(), ownerUid, "STAGING_CONFLICT");
        if (present(paths.stagingBundle())) {
            installBundleRoot(paths, declaration, ownerUid);
        }
        if (present(paths.manifestPart())) {
            byte[] manifest = declaration.manifestBytes(
                    coordinates.publicationFingerprint(), coordinates.transactionNonce(),
                    coordinates.transactionId());
            reconcileFileInstall(paths.manifestPart(), paths.finalManifest(), manifest, ownerUid,
                    "MANIFEST_INSTALL_CONFLICT", "MANIFEST_INSTALL_UNAVAILABLE");
        }
        if (present(paths.stagingParts())) {
            removeEmptyOwnedDirectory(paths.stagingParts(), ownerUid);
        }
        requireOnlyNames(paths.staging(), Set.of(), false);
        forceDirectory(paths.output(), "FINAL_DIRECTORY_FORCE_UNAVAILABLE");
        forceDirectory(paths.staging(), "STAGING_DIRECTORY_FORCE_UNAVAILABLE");
    }

    private void requirePreparedFinalOwner(
            PublicationPaths paths,
            PublicationCoordinates coordinates,
            long publicationOwnerUid) {
        if (!present(paths.output())) {
            return;
        }
        DirectoryState state = directoryState(paths.output());
        if (state.ownerUid() != publicationOwnerUid
                || !state.permissions().equals(BUILD_DIRECTORY)) {
            throw unavailable("FINAL_OUTPUT_CONFLICT");
        }
        OwnerDescriptor owner = ownerDescriptor(coordinates);
        requireExactOwnerDescriptor(paths, owner, publicationOwnerUid);
        requireOnlyNames(paths.output(), Set.of(BUNDLE_ROOT_DIRECTORY), true);
    }

    private WrapperObservation verifyCommittedWrapper(
            Path wrapper,
            TreeKind expectedKind,
            String expectedSemantic,
            String expectedTreeFingerprint,
            String expectedPublicationFingerprint,
            String expectedTransactionId,
            AncestorChain outputChain) {
        WrapperObservation first = observeCompleteWrapper(
                wrapper, expectedKind, expectedSemantic, expectedTreeFingerprint,
                expectedPublicationFingerprint, expectedTransactionId);
        recheckAncestorChain(outputChain);
        WrapperObservation second = observeCompleteWrapper(
                wrapper, expectedKind, expectedSemantic, expectedTreeFingerprint,
                expectedPublicationFingerprint, expectedTransactionId);
        recheckAncestorChain(outputChain);
        if (!first.equals(second)) {
            throw invalid("WRAPPER_CHANGED");
        }
        return first;
    }

    private static PublicationPaths publicationPaths(
            Path output,
            String transactionId,
            String transactionNonce) {
        String outputName = output.getFileName().toString();
        String transactionHex = transactionId.substring("sha256:".length());
        String nonceHex = transactionNonce.substring("sha256:".length());
        Path parent = output.getParent();
        Path staging = parent.resolve("." + outputName
                + ".formal-input-tree-v1." + transactionHex + ".staging");
        return new PublicationPaths(
                parent, output, parent.resolve(PUBLICATION_LOCK_FILE), staging,
                parent.resolve("." + outputName + ".formal-input-tree-owner-v1."
                        + transactionHex + ".json"),
                parent.resolve("." + outputName + ".formal-input-tree-owner-v1."
                        + nonceHex + ".part"),
                staging.resolve(BUNDLE_ROOT_DIRECTORY),
                staging.resolve(".parts"),
                staging.resolve(".formal-input-tree-manifest." + nonceHex + ".part"),
                output.resolve(BUNDLE_ROOT_DIRECTORY), output.resolve(MANIFEST_FILE));
    }

    private <T> T withPublicationLease(
            PublicationPaths paths,
            AncestorChain outputChain,
            boolean create,
            LockedAction<T> action) {
        LeaseBudget budget = LeaseBudget.start(publicationLeaseTimeoutNanos, ticker);
        Path lockKey = paths.parent().toAbsolutePath().normalize();
        ReentrantLock monitor = JVM_PUBLICATION_LOCKS[
                Math.floorMod(lockKey.hashCode(), JVM_PUBLICATION_LOCKS.length)];
        boolean processLease = false;
        try {
            observer.beforeJvmPublicationLock(paths.parent());
            long remaining = budget.remainingNanos();
            if (remaining <= 0) {
                throw unavailable("PUBLICATION_LOCK_TIMEOUT");
            }
            processLease = monitor.tryLock(remaining, TimeUnit.NANOSECONDS);
            if (!processLease || budget.remainingNanos() <= 0) {
                throw unavailable("PUBLICATION_LOCK_TIMEOUT");
            }
            ensurePublicationLockFile(paths, outputChain, create);
            FileIdentity before = fileIdentity(
                    paths.lockFile(), LOCK_FILE, 0, outputChain.publicationOwnerUid(),
                    "PUBLICATION_LOCK_UNAVAILABLE");
            observer.beforeFilePublicationLock(paths.lockFile());
            try (FileChannel channel = FileChannel.open(
                    paths.lockFile(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    FileLock ignored = acquireFileLock(
                            channel, budget, fileLockMissBudget(publicationLeaseTimeoutNanos))) {
                requireSameFileIdentity(paths.lockFile(), before, LOCK_FILE, 0,
                        outputChain.publicationOwnerUid(), "PUBLICATION_LOCK_CHANGED");
                recheckAncestorChain(outputChain);
                if (budget.remainingNanos() <= 0) {
                    throw unavailable("PUBLICATION_LOCK_TIMEOUT");
                }
                T result = action.run();
                recheckAncestorChain(outputChain);
                requireSameFileIdentity(paths.lockFile(), before, LOCK_FILE, 0,
                        outputChain.publicationOwnerUid(), "PUBLICATION_LOCK_CHANGED");
                return result;
            } catch (FormalInputTreeException failure) {
                throw failure;
            } catch (IOException | RuntimeException failure) {
                throw unavailable("PUBLICATION_LOCK_UNAVAILABLE");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unavailable("PUBLICATION_LOCK_INTERRUPTED");
        } finally {
            if (processLease) {
                monitor.unlock();
            }
        }
    }

    private static FileLock acquireFileLock(
            FileChannel channel,
            LeaseBudget budget,
            int missBudget)
            throws IOException, InterruptedException {
        int missesRemaining = missBudget;
        while (true) {
            long remaining = budget.remainingNanos();
            if (remaining <= 0) {
                throw unavailable("PUBLICATION_LOCK_TIMEOUT");
            }
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    if (budget.remainingNanos() <= 0) {
                        lock.release();
                        throw unavailable("PUBLICATION_LOCK_TIMEOUT");
                    }
                    return lock;
                }
            } catch (OverlappingFileLockException ignored) {
                // Another channel in this JVM holds the same OS lock outside this snapshotter.
            }
            if (--missesRemaining <= 0) {
                throw unavailable("PUBLICATION_LOCK_TIMEOUT");
            }
            remaining = budget.remainingNanos();
            if (remaining <= 0) {
                throw unavailable("PUBLICATION_LOCK_TIMEOUT");
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, PUBLICATION_LOCK_RETRY_NANOS));
        }
    }

    private static int fileLockMissBudget(long timeoutNanos) {
        long attempts = 1 + (timeoutNanos - 1) / PUBLICATION_LOCK_RETRY_NANOS;
        return (int) Math.min(attempts, MAXIMUM_PUBLICATION_LOCK_MISSES);
    }

    private static ReentrantLock[] publicationLockStripes() {
        ReentrantLock[] locks = new ReentrantLock[PUBLICATION_LOCK_STRIPE_COUNT];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    private void ensurePublicationLockFile(
            PublicationPaths paths,
            AncestorChain outputChain,
            boolean create) {
        if (present(paths.lockFile())) {
            fileIdentity(paths.lockFile(), LOCK_FILE, 0,
                    outputChain.publicationOwnerUid(), "PUBLICATION_LOCK_INVALID");
            return;
        }
        if (!create) {
            throw unavailable("PUBLICATION_LOCK_UNAVAILABLE");
        }
        try (FileChannel channel = FileChannel.open(
                paths.lockFile(),
                Set.<OpenOption>of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(LOCK_FILE))) {
            operations.forceFile(channel);
        } catch (FormalInputTreeException failure) {
            throw failure;
        } catch (FileAlreadyExistsException race) {
            fileIdentity(paths.lockFile(), LOCK_FILE, 0,
                    outputChain.publicationOwnerUid(), "PUBLICATION_LOCK_INVALID");
            return;
        } catch (IOException | RuntimeException failure) {
            throw unavailable("PUBLICATION_LOCK_UNAVAILABLE");
        }
        fileIdentity(paths.lockFile(), LOCK_FILE, 0,
                outputChain.publicationOwnerUid(), "PUBLICATION_LOCK_INVALID");
        forceDirectory(paths.parent(), "OUTPUT_PARENT_FORCE_UNAVAILABLE");
    }

    private void ensureOwnerDescriptor(
            PublicationPaths paths,
            OwnerDescriptor expected,
            long ownerUid) {
        if (present(paths.owner())) {
            finishOwnerBootstrapIfPresent(paths, expected, ownerUid);
            requireExactOwnerDescriptor(paths, expected, ownerUid);
            return;
        }
        if (present(paths.staging()) || present(paths.output())) {
            throw unavailable("UNOWNED_PUBLICATION_CONFLICT");
        }
        byte[] bytes = ownerCanonicalJson(expected).getBytes(StandardCharsets.UTF_8);
        ensureRecoverablePart(paths.ownerBootstrap(), bytes, ownerUid, "OWNER_CONFLICT");
        try {
            Files.createLink(paths.owner(), paths.ownerBootstrap());
            observer.afterOwnerClaim(paths.owner());
            forceDirectory(paths.parent(), "OUTPUT_PARENT_FORCE_UNAVAILABLE");
        } catch (FormalInputTreeException failure) {
            throw failure;
        } catch (FileAlreadyExistsException race) {
            requireExactOwnerDescriptor(paths, expected, ownerUid);
        } catch (UnsupportedOperationException failure) {
            throw unavailable("OWNER_INSTALL_UNAVAILABLE");
        } catch (IOException | RuntimeException failure) {
            throw unavailable("OWNER_INSTALL_UNAVAILABLE");
        }
        finishOwnerBootstrapIfPresent(paths, expected, ownerUid);
        requireExactOwnerDescriptor(paths, expected, ownerUid);
    }

    private void finishOwnerBootstrapIfPresent(
            PublicationPaths paths,
            OwnerDescriptor expected,
            long ownerUid) {
        if (paths.ownerBootstrap() == null || !present(paths.ownerBootstrap())) {
            return;
        }
        byte[] bytes = ownerCanonicalJson(expected).getBytes(StandardCharsets.UTF_8);
        FileIdentity owner = fileIdentityAllowLinkCount(
                paths.owner(), PRIVATE_FILE, bytes.length, ownerUid, 1, 2,
                "OWNER_CONFLICT");
        FileIdentity bootstrap = fileIdentityAllowLinkCount(
                paths.ownerBootstrap(), PRIVATE_FILE, bytes.length, ownerUid, 1, 2,
                "OWNER_CONFLICT");
        if (!owner.fileKey().equals(bootstrap.fileKey())
                || !Arrays.equals(readExactOwnedAllowLinkCount(
                paths.owner(), bytes.length, ownerUid, 2), bytes)
                || !Arrays.equals(readExactOwnedAllowLinkCount(
                paths.ownerBootstrap(), bytes.length, ownerUid, 2), bytes)) {
            throw unavailable("OWNER_CONFLICT");
        }
        try {
            Files.delete(paths.ownerBootstrap());
            forceDirectory(paths.parent(), "OUTPUT_PARENT_FORCE_UNAVAILABLE");
        } catch (IOException | RuntimeException failure) {
            throw unavailable("OWNER_INSTALL_UNAVAILABLE");
        }
    }

    private void requireExactOwnerDescriptor(
            PublicationPaths paths,
            OwnerDescriptor expected,
            long ownerUid) {
        byte[] expectedBytes = ownerCanonicalJson(expected).getBytes(StandardCharsets.UTF_8);
        byte[] actual = readExactPrivate(paths.owner(), expectedBytes.length, ownerUid);
        if (!Arrays.equals(actual, expectedBytes) || !parseOwner(actual).equals(expected)) {
            throw unavailable("OWNER_CONFLICT");
        }
    }

    private static OwnerDescriptor parseOwner(byte[] bytes) {
        JsonNode value = parseSingle(bytes);
        Set<String> expectedNames = Set.of(
                "messageVersion", "transactionId", "transactionNonce", "treeKind",
                "bundleSemanticFingerprint", "treeFingerprint", "publicationFingerprint",
                "ownerReceiptFingerprint");
        Set<String> names = new HashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        if (!value.isObject() || !names.equals(expectedNames)) {
            throw unavailable("OWNER_CONFLICT");
        }
        try {
            OwnerDescriptor owner = new OwnerDescriptor(
                    value.path("messageVersion").textValue(),
                    value.path("transactionId").textValue(),
                    value.path("transactionNonce").textValue(),
                    TreeKind.valueOf(value.path("treeKind").textValue()),
                    value.path("bundleSemanticFingerprint").textValue(),
                    value.path("treeFingerprint").textValue(),
                    value.path("publicationFingerprint").textValue(),
                    value.path("ownerReceiptFingerprint").textValue());
            if (!Arrays.equals(bytes, ownerCanonicalJson(owner).getBytes(StandardCharsets.UTF_8))) {
                throw unavailable("OWNER_CONFLICT");
            }
            return owner;
        } catch (FormalInputTreeException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable("OWNER_CONFLICT");
        }
    }

    private void prepareStaging(
            PublicationPaths paths,
            PublicationCoordinates coordinates,
            Declaration declaration,
            Inventory source,
            AncestorChain outputChain) {
        observer.beforeStaging(paths.staging());
        if (!present(paths.staging())) {
            createBuildDirectory(paths.staging(), outputChain.publicationOwnerUid(),
                    "STAGING_UNAVAILABLE");
            observer.afterStagingRoot(paths.staging());
        } else {
            requireBuildDirectory(paths.staging(), outputChain.publicationOwnerUid(),
                    "STAGING_CONFLICT");
            requireOnlyNames(paths.staging(), Set.of(
                    BUNDLE_ROOT_DIRECTORY, ".parts",
                    paths.manifestPart().getFileName().toString()), true);
        }
        if (!present(paths.stagingParts())) {
            createBuildDirectory(paths.stagingParts(), outputChain.publicationOwnerUid(),
                    "STAGING_UNAVAILABLE");
        } else {
            requireBuildDirectory(paths.stagingParts(), outputChain.publicationOwnerUid(),
                    "STAGING_CONFLICT");
        }
        if (!present(paths.stagingBundle())) {
            createBuildDirectory(paths.stagingBundle(), outputChain.publicationOwnerUid(),
                    "STAGING_UNAVAILABLE");
        } else {
            requireBundleDirectoryForInstall(
                    paths.stagingBundle(), declaration, outputChain.publicationOwnerUid(),
                    "STAGING_CONFLICT");
        }
        String nonceHex = coordinates.transactionNonce().substring("sha256:".length());
        for (int index = 0; index < source.files().size(); index++) {
            FileSnapshot file = source.files().get(index);
            Path target = paths.stagingBundle().resolve(file.relativePath());
            Path finalTarget = paths.finalBundle().resolve(file.relativePath());
            Path part = paths.stagingParts().resolve(String.format(
                    ".%03d-%s.part", index, nonceHex));
            if (present(target) && present(part)) {
                reconcileFileInstall(part, target, file.bytes(),
                        outputChain.publicationOwnerUid(),
                        "STAGING_CONFLICT", "FILE_INSTALL_UNAVAILABLE");
            } else if (present(target)) {
                requireExactInstalledFile(
                        target, file.bytes(), outputChain.publicationOwnerUid(), true,
                        "STAGING_CONFLICT");
            } else if (present(finalTarget)) {
                // Preserve TARGET_ONLY for the reconciler; recreating source would invent BOTH.
            } else {
                installDurablePart(part, target, file.bytes(),
                        outputChain.publicationOwnerUid(), "STAGING_CONFLICT");
            }
            observer.afterBundleFile(paths.staging(), file.relativePath(), index);
        }
        requireOnlyAllowedNames(paths.stagingBundle(), declaration.entries().stream()
                .map(TreeEntry::relativePath)
                .collect(java.util.stream.Collectors.toSet()), "STAGING_CONFLICT");
        forceDirectory(paths.stagingBundle(), "STAGING_DIRECTORY_FORCE_UNAVAILABLE");
        observer.afterClosureForce(paths.stagingBundle());
        byte[] manifest = declaration.manifestBytes(
                coordinates.publicationFingerprint(), coordinates.transactionNonce(),
                coordinates.transactionId());
        ensureRecoverablePart(paths.manifestPart(), manifest,
                outputChain.publicationOwnerUid(), "STAGING_CONFLICT");
        observer.afterManifest(paths.staging());
        if (present(paths.stagingParts())) {
            removeEmptyOwnedDirectory(paths.stagingParts(), outputChain.publicationOwnerUid());
        }
        forceDirectory(paths.staging(), "STAGING_DIRECTORY_FORCE_UNAVAILABLE");
    }

    private void prepareFinalDirectory(
            PublicationPaths paths,
            OwnerDescriptor owner,
            Declaration declaration,
            long ownerUid) {
        requireExactOwnerDescriptor(paths, owner, ownerUid);
        observer.beforePublish(paths.staging(), paths.output());
        if (!present(paths.output())) {
            createBuildDirectory(paths.output(), ownerUid, "FINAL_OUTPUT_UNAVAILABLE");
            return;
        }
        requireBuildDirectory(paths.output(), ownerUid, "FINAL_OUTPUT_CONFLICT");
        requireOnlyNames(paths.output(), Set.of(BUNDLE_ROOT_DIRECTORY), true);
        if (present(paths.finalBundle())) {
            requireBundleDirectoryForInstall(
                    paths.finalBundle(), declaration, ownerUid, "BUNDLE_INSTALL_UNAVAILABLE");
        }
    }

    private void installBundleRoot(PublicationPaths paths, Declaration declaration, long ownerUid) {
        requireBundleDirectoryForInstall(
                paths.stagingBundle(), declaration, ownerUid, "STAGING_CONFLICT");
        if (!present(paths.finalBundle())) {
            createBuildDirectory(
                    paths.finalBundle(), ownerUid, "BUNDLE_INSTALL_UNAVAILABLE");
        } else {
            requireBundleDirectoryForInstall(
                    paths.finalBundle(), declaration, ownerUid, "BUNDLE_INSTALL_UNAVAILABLE");
        }
        Object sourceIdentity = directoryInstallIdentity(paths.stagingBundle());
        Object targetIdentity = directoryInstallIdentity(paths.finalBundle());
        if (sourceIdentity.equals(targetIdentity)) {
            throw unavailable("BUNDLE_DIRECTORY_IDENTITY_CONFLICT");
        }
        for (TreeEntry entry : declaration.entries()) {
            Path source = paths.stagingBundle().resolve(entry.relativePath());
            Path target = paths.finalBundle().resolve(entry.relativePath());
            byte[] expected = installedEntryBytes(source, target, entry, ownerUid);
            reconcileFileInstall(source, target, expected, ownerUid,
                    "BUNDLE_INSTALL_CONFLICT", "BUNDLE_INSTALL_UNAVAILABLE");
        }
        removeEmptyOwnedDirectory(paths.stagingBundle(), ownerUid);
        requirePreparedBundleOwned(paths.finalBundle(), declaration, ownerUid);
        DirectoryState installed = directoryState(paths.finalBundle());
        if (installed.ownerUid() != ownerUid
                || (!installed.permissions().equals(BUILD_DIRECTORY)
                && !installed.permissions().equals(PRIVATE_DIRECTORY))) {
            throw unavailable("BUNDLE_INSTALL_UNAVAILABLE");
        }
        if (installed.permissions().equals(BUILD_DIRECTORY)) {
            forceDirectory(paths.finalBundle(), "BUNDLE_DIRECTORY_FORCE_UNAVAILABLE");
            finishDirectory(paths.finalBundle(), installed.identity(), ownerUid);
        }
        forceDirectory(paths.output(), "FINAL_DIRECTORY_FORCE_UNAVAILABLE");
    }

    private void installCommitManifest(
            PublicationPaths paths,
            PublicationCoordinates coordinates,
            Declaration declaration,
            long ownerUid) {
        byte[] expected = declaration.manifestBytes(
                coordinates.publicationFingerprint(), coordinates.transactionNonce(),
                coordinates.transactionId());
        reconcileFileInstall(paths.manifestPart(), paths.finalManifest(), expected, ownerUid,
                "MANIFEST_INSTALL_CONFLICT", "MANIFEST_INSTALL_UNAVAILABLE");
        observer.afterPublish(paths.output());
    }

    private void finishCommittedDirectory(
            PublicationPaths paths,
            OwnerDescriptor owner,
            Declaration declaration,
            AncestorChain outputChain) {
        requireExactOwnerDescriptor(paths, owner, outputChain.publicationOwnerUid());
        requireCommittedClosure(paths.output(), new PublicationCoordinates(
                declaration.treeKind(), declaration.bundleSemanticFingerprint(),
                declaration.treeFingerprint(), owner.publicationFingerprint(),
                owner.transactionNonce(), owner.transactionId()),
                outputChain.publicationOwnerUid(), true);
        DirectoryState finalState = directoryState(paths.output());
        forceDirectory(paths.output(), "FINAL_DIRECTORY_FORCE_UNAVAILABLE");
        if (finalState.permissions().equals(BUILD_DIRECTORY)) {
            finishDirectory(paths.output(), finalState.identity(),
                    outputChain.publicationOwnerUid());
        } else if (!finalState.permissions().equals(PRIVATE_DIRECTORY)) {
            throw unavailable("FINAL_OUTPUT_CONFLICT");
        }
        forceDirectory(paths.parent(), "OUTPUT_PARENT_FORCE_UNAVAILABLE");
        observer.afterParentForce(paths.output());
    }

    private Declaration requireCommittedClosure(
            Path output,
            PublicationCoordinates coordinates,
            long ownerUid,
            boolean allowBuildWrapper) {
        DirectoryState wrapper = directoryState(output);
        if (wrapper.ownerUid() != ownerUid
                || (!wrapper.permissions().equals(PRIVATE_DIRECTORY)
                && !(allowBuildWrapper && wrapper.permissions().equals(BUILD_DIRECTORY)))) {
            throw unavailable("FINAL_OUTPUT_CONFLICT");
        }
        requireOnlyNames(output, Set.of(BUNDLE_ROOT_DIRECTORY, MANIFEST_FILE), false);
        FileSnapshot manifest = readStandaloneFile(
                output.resolve(MANIFEST_FILE), coordinates.treeKind().maximumManifestBytes,
                PRIVATE_FILE);
        return parseManifest(manifest.bytes(), coordinates.treeKind(),
                coordinates.bundleSemanticFingerprint(), coordinates.treeFingerprint(),
                coordinates.publicationFingerprint(), coordinates.transactionId()).declaration();
    }

    private void cleanupOwnedResidue(
            PublicationPaths paths,
            OwnerDescriptor owner,
            long ownerUid) {
        try {
            if (present(paths.staging())) {
                requireBuildDirectory(paths.staging(), ownerUid, "STAGING_CONFLICT");
                requireOnlyNames(paths.staging(), Set.of(), false);
                Files.delete(paths.staging());
                forceDirectory(paths.parent(), "OUTPUT_PARENT_FORCE_UNAVAILABLE");
            }
            if (!present(paths.staging()) && present(paths.owner())) {
                requireExactOwnerDescriptor(paths, owner, ownerUid);
                Files.delete(paths.owner());
                forceDirectory(paths.parent(), "OUTPUT_PARENT_FORCE_UNAVAILABLE");
            }
        } catch (FormalInputTreeException failure) {
            throw failure;
        } catch (IOException | RuntimeException ignored) {
            // A committed, independently verified final remains authoritative. Residue is retried.
        }
    }

    private void installDurablePart(
            Path part,
            Path target,
            byte[] expected,
            long ownerUid,
            String conflictCode) {
        if (!present(target)) {
            ensureRecoverablePart(part, expected, ownerUid, conflictCode);
        }
        reconcileFileInstall(part, target, expected, ownerUid,
                conflictCode, "FILE_INSTALL_UNAVAILABLE");
    }

    private void ensureRecoverablePart(
            Path part,
            byte[] expected,
            long ownerUid,
            String conflictCode) {
        if (present(part)) {
            FileIdentity identity = fileIdentityAllowLinkCount(
                    part, permissions(part), -1, ownerUid, 1, 1, conflictCode);
            if (!identity.permissions().equals(BUILD_FILE)
                    && !identity.permissions().equals(PRIVATE_FILE)) {
                throw unavailable(conflictCode);
            }
            byte[] existing = readBounded(part, expected.length, ownerUid,
                    identity.permissions(), conflictCode);
            if (existing.length > expected.length
                    || !Arrays.equals(existing, Arrays.copyOf(expected, existing.length))) {
                throw unavailable(conflictCode);
            }
            if (existing.length == expected.length
                    && identity.permissions().equals(PRIVATE_FILE)) {
                forcePrivateFile(part, identity.fileKey());
                return;
            }
        }
        writeOwnedPart(part, expected, ownerUid, conflictCode);
    }

    private void writeOwnedPart(
            Path part,
            byte[] expected,
            long ownerUid,
            String conflictCode) {
        boolean exists = present(part);
        try (FileChannel channel = exists
                ? FileChannel.open(part, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)
                : FileChannel.open(part,
                        Set.<OpenOption>of(StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(BUILD_FILE))) {
            if (exists) {
                operations.chmod(part, BUILD_FILE);
            }
            ByteBuffer bytes = ByteBuffer.wrap(expected);
            int first = Math.min(bytes.remaining(), 4096);
            ByteBuffer chunk = bytes.slice(0, first);
            while (chunk.hasRemaining()) {
                channel.write(chunk);
            }
            bytes.position(first);
            observer.afterPartFirstChunk(part);
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            operations.forceFile(channel);
            observer.afterFileForce(part);
            FileIdentity identity = fileIdentity(
                    part, BUILD_FILE, expected.length, ownerUid, conflictCode);
            operations.chmod(part, PRIVATE_FILE);
            requireSameFileIdentity(part, identity.withPermissions(PRIVATE_FILE),
                    PRIVATE_FILE, expected.length, ownerUid, conflictCode);
            observer.afterChmod(part);
            operations.forceFile(channel);
            observer.afterObjectForce(part);
        } catch (FormalInputTreeException failure) {
            throw failure;
        } catch (FileAlreadyExistsException failure) {
            throw unavailable(conflictCode);
        } catch (IOException | RuntimeException failure) {
            throw unavailable("FILE_DURABILITY_UNAVAILABLE");
        }
        fileIdentity(part, PRIVATE_FILE, expected.length, ownerUid, conflictCode);
    }

    private void reconcileFileInstall(
            Path source,
            Path target,
            byte[] expected,
            long ownerUid,
            String conflictCode,
            String durabilityCode) {
        InstallState state = installState(source, target);
        if (state == InstallState.NEITHER) {
            throw unavailable(durabilityCode);
        }
        if (state == InstallState.SOURCE_ONLY) {
            requireExactInstalledFile(source, expected, ownerUid, false, conflictCode);
            atomicInstall(source, target, durabilityCode);
        } else if (state == InstallState.TARGET_ONLY) {
            requireExactInstalledFile(target, expected, ownerUid, false, conflictCode);
            forceRenameParents(source, target, durabilityCode);
        } else if (state == InstallState.BOTH) {
            FileIdentity sourceIdentity = requireExactInstalledFile(
                    source, expected, ownerUid, true, conflictCode);
            FileIdentity targetIdentity = requireExactInstalledFile(
                    target, expected, ownerUid, true, conflictCode);
            boolean sameFile = sourceIdentity.fileKey().equals(targetIdentity.fileKey());
            if (!sameFile) {
                throw unavailable(conflictCode);
            }
            if (sourceIdentity.linkCount() != 2 || targetIdentity.linkCount() != 2) {
                throw unavailable(conflictCode);
            }
            forceInstalledFile(target, targetIdentity, ownerUid, 2, durabilityCode);
            forceDirectory(target.getParent(), durabilityCode);
            sourceIdentity = requireExactInstalledFile(
                    source, expected, ownerUid, true, conflictCode);
            targetIdentity = requireExactInstalledFile(
                    target, expected, ownerUid, true, conflictCode);
            if (!sourceIdentity.fileKey().equals(targetIdentity.fileKey())
                    || sourceIdentity.linkCount() != 2 || targetIdentity.linkCount() != 2) {
                throw unavailable(conflictCode);
            }
            observer.beforeSourceUnlink(source, target);
            try {
                Files.delete(source);
            } catch (IOException | RuntimeException failure) {
                throw unavailable(durabilityCode);
            }
            observer.afterSourceUnlink(source, target);
            forceDirectory(source.getParent(), durabilityCode);
        }
        requireExactInstalledFile(target, expected, ownerUid, false, durabilityCode);
    }

    private void forceInstalledFile(
            Path file,
            FileIdentity expected,
            long ownerUid,
            long linkCount,
            String code) {
        try (FileChannel channel = FileChannel.open(
                file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            FileIdentity before = fileIdentityAllowLinkCount(
                    file, PRIVATE_FILE, expected.size(), ownerUid,
                    linkCount, linkCount, code);
            if (!before.fileKey().equals(expected.fileKey())) {
                throw unavailable(code);
            }
            operations.forceFile(channel);
            FileIdentity after = fileIdentityAllowLinkCount(
                    file, PRIVATE_FILE, expected.size(), ownerUid,
                    linkCount, linkCount, code);
            if (!after.fileKey().equals(expected.fileKey())) {
                throw unavailable(code);
            }
        } catch (FormalInputTreeException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw unavailable(code);
        }
    }

    private static FileIdentity requireExactInstalledFile(
            Path file,
            byte[] expected,
            long ownerUid,
            boolean allowTwoLinks,
            String code) {
        FileIdentity before = fileIdentityAllowLinkCount(
                file, PRIVATE_FILE, expected.length, ownerUid, 1,
                allowTwoLinks ? 2 : 1, code);
        byte[] actual;
        try {
            actual = Files.readAllBytes(file);
        } catch (IOException | RuntimeException failure) {
            throw unavailable(code);
        }
        FileIdentity after = fileIdentityAllowLinkCount(
                file, PRIVATE_FILE, expected.length, ownerUid,
                before.linkCount(), before.linkCount(), code);
        if (!before.fileKey().equals(after.fileKey()) || !Arrays.equals(actual, expected)) {
            throw unavailable(code);
        }
        return before;
    }

    private void atomicInstall(Path source, Path target, String code) {
        if (!present(source) || present(target)) {
            throw unavailable(code);
        }
        try {
            operations.atomicMove(source, target);
            observer.afterAtomicMove(source, target);
        } catch (AtomicMoveNotSupportedException failure) {
            throw unavailable(code);
        } catch (IOException | RuntimeException failure) {
            throw unavailable(code);
        }
        forceRenameParents(source, target, code);
    }

    private void forceRenameParents(Path source, Path target, String code) {
        Path sourceParent = source.getParent();
        Path targetParent = target.getParent();
        forceDirectory(targetParent, code);
        if (!sourceParent.equals(targetParent)) {
            forceDirectory(sourceParent, code);
        }
    }

    private void createBuildDirectory(Path directory, long ownerUid, String code) {
        try {
            Files.createDirectory(directory,
                    PosixFilePermissions.asFileAttribute(BUILD_DIRECTORY));
        } catch (FileAlreadyExistsException failure) {
            throw unavailable(code);
        } catch (IOException | RuntimeException failure) {
            throw unavailable(code);
        }
        requireBuildDirectory(directory, ownerUid, code);
        forceDirectory(directory.getParent(), "OUTPUT_PARENT_FORCE_UNAVAILABLE");
    }

    private static void requireBuildDirectory(Path directory, long ownerUid, String code) {
        DirectoryState state = directoryState(directory);
        if (state.ownerUid() != ownerUid || !state.permissions().equals(BUILD_DIRECTORY)) {
            throw unavailable(code);
        }
    }

    private void removeEmptyOwnedDirectory(Path directory, long ownerUid) {
        DirectoryState state = directoryState(directory);
        if (state.ownerUid() != ownerUid
                || (!state.permissions().equals(BUILD_DIRECTORY)
                && !state.permissions().equals(PRIVATE_DIRECTORY))) {
            throw unavailable("STAGING_CONFLICT");
        }
        requireOnlyNames(directory, Set.of(), false);
        try {
            Files.delete(directory);
            forceDirectory(directory.getParent(), "STAGING_DIRECTORY_FORCE_UNAVAILABLE");
        } catch (IOException | RuntimeException failure) {
            throw unavailable("STAGING_DIRECTORY_UNAVAILABLE");
        }
    }

    private void requirePreparedBundle(Path bundleRoot, Declaration declaration) {
        DirectoryState directory = directoryState(bundleRoot);
        if (!directory.permissions().equals(PRIVATE_DIRECTORY)
                && !directory.permissions().equals(BUILD_DIRECTORY)) {
            throw unavailable("STAGING_CONFLICT");
        }
        Inventory inventory = inventory(
                declaration.treeKind(), bundleRoot, InventoryPass.VERIFY,
                declaration.bundleSemanticFingerprint(), true, true);
        Declaration actual = declarationFromFiles(
                declaration.treeKind(), declaration.bundleSemanticFingerprint(), inventory.files());
        if (!actual.equals(declaration)) {
            throw unavailable("STAGING_CONFLICT");
        }
    }

    private void requirePreparedBundleOwned(
            Path bundleRoot,
            Declaration declaration,
            long ownerUid) {
        DirectoryState state = directoryState(bundleRoot);
        if (state.ownerUid() != ownerUid) {
            throw unavailable("STAGING_CONFLICT");
        }
        requirePreparedBundle(bundleRoot, declaration);
    }

    private void requireBundleDirectoryForInstall(
            Path bundleRoot,
            Declaration declaration,
            long ownerUid,
            String code) {
        DirectoryState state = directoryState(bundleRoot);
        if (state.ownerUid() != ownerUid
                || (!state.permissions().equals(BUILD_DIRECTORY)
                && !state.permissions().equals(PRIVATE_DIRECTORY))) {
            throw unavailable(code);
        }
        requireOnlyAllowedNames(bundleRoot, declaration.entries().stream()
                .map(TreeEntry::relativePath).collect(java.util.stream.Collectors.toSet()), code);
        if (state.permissions().equals(PRIVATE_DIRECTORY)) {
            requirePreparedBundleOwned(bundleRoot, declaration, ownerUid);
        }
    }

    private byte[] installedEntryBytes(
            Path source,
            Path target,
            TreeEntry entry,
            long ownerUid) {
        Path available = present(source) ? source : present(target) ? target : null;
        if (available == null) {
            throw unavailable("BUNDLE_INSTALL_UNAVAILABLE");
        }
        FileIdentity before = fileIdentityAllowLinkCount(
                available, PRIVATE_FILE, entry.byteSize(), ownerUid,
                1, 2, "BUNDLE_INSTALL_CONFLICT");
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(available);
        } catch (IOException | RuntimeException failure) {
            throw unavailable("BUNDLE_INSTALL_CONFLICT");
        }
        FileIdentity after = fileIdentityAllowLinkCount(
                available, PRIVATE_FILE, entry.byteSize(), ownerUid,
                before.linkCount(), before.linkCount(), "BUNDLE_INSTALL_CONFLICT");
        if (!before.fileKey().equals(after.fileKey())
                || !sha256(bytes).equals(entry.rawFingerprint())) {
            throw unavailable("BUNDLE_INSTALL_CONFLICT");
        }
        return bytes;
    }

    private Object directoryInstallIdentity(Path directory) {
        try {
            Object identity = operations.directoryIdentity(directory);
            if (identity == null) {
                throw unavailable("BUNDLE_DIRECTORY_IDENTITY_UNAVAILABLE");
            }
            return identity;
        } catch (FormalInputTreeException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw unavailable("BUNDLE_DIRECTORY_IDENTITY_UNAVAILABLE");
        }
    }

    private static InstallState installState(Path source, Path target) {
        boolean sourcePresent = present(source);
        boolean targetPresent = present(target);
        if (sourcePresent && targetPresent) {
            return InstallState.BOTH;
        }
        if (sourcePresent) {
            return InstallState.SOURCE_ONLY;
        }
        return targetPresent ? InstallState.TARGET_ONLY : InstallState.NEITHER;
    }

    private WrapperObservation observeCompleteWrapper(
            Path wrapper,
            TreeKind expectedKind,
            String expectedSemantic,
            String expectedTreeFingerprint,
            String expectedPublicationFingerprint,
            String expectedTransactionId) {
        DirectoryState wrapperBefore = verificationDirectoryState(wrapper, true);
        if (!wrapperBefore.permissions().equals(PRIVATE_DIRECTORY)) {
            throw invalid("WRAPPER_PERMISSIONS_INVALID");
        }
        requireVerificationNames(wrapper, Set.of(BUNDLE_ROOT_DIRECTORY, MANIFEST_FILE));
        Path bundleRoot = wrapper.resolve(BUNDLE_ROOT_DIRECTORY);
        DirectoryState bundleBefore = verificationDirectoryState(bundleRoot, false);
        if (!bundleBefore.permissions().equals(PRIVATE_DIRECTORY)) {
            throw invalid("BUNDLE_ROOT_PERMISSIONS_INVALID");
        }
        FileSnapshot manifestBefore = readStandaloneFile(
                wrapper.resolve(MANIFEST_FILE), expectedKind.maximumManifestBytes, PRIVATE_FILE);
        ParsedManifest parsed = parseManifest(
                manifestBefore.bytes(), expectedKind, expectedSemantic, expectedTreeFingerprint,
                expectedPublicationFingerprint, expectedTransactionId);
        Inventory first = inventory(
                expectedKind, bundleRoot, InventoryPass.VERIFY, expectedSemantic, true);
        Declaration declaration = declarationFromFiles(expectedKind, expectedSemantic, first.files());
        if (!declaration.equals(parsed.declaration())) {
            throw invalid("WRAPPER_CONTENT_INVALID");
        }

        DirectoryState wrapperAfter = verificationDirectoryState(wrapper, true);
        DirectoryState bundleAfter = verificationDirectoryState(bundleRoot, false);
        FileSnapshot manifestAfter = readStandaloneFile(
                wrapper.resolve(MANIFEST_FILE), expectedKind.maximumManifestBytes, PRIVATE_FILE);
        Inventory second = inventory(
                expectedKind, bundleRoot, InventoryPass.VERIFY, expectedSemantic, true);
        requireSameInventory(first, second);
        if (!wrapperBefore.equals(wrapperAfter) || !bundleBefore.equals(bundleAfter)
                || !manifestBefore.sameObservation(manifestAfter)) {
            throw invalid("WRAPPER_CHANGED");
        }
        return new WrapperObservation(
                wrapperBefore, bundleBefore, manifestBefore.observation(), first.observation(),
                declaration, parsed.publicationFingerprint(), parsed.transactionNonce(),
                parsed.transactionId(), manifestBefore.rawFingerprint());
    }

    private Inventory inventory(
            TreeKind treeKind,
            Path root,
            InventoryPass pass,
            String expectedSemanticFingerprint,
            boolean requirePrivatePermissions) {
        return inventory(treeKind, root, pass, expectedSemanticFingerprint,
                requirePrivatePermissions, false);
    }

    private Inventory inventory(
            TreeKind treeKind,
            Path root,
            InventoryPass pass,
            String expectedSemanticFingerprint,
            boolean requirePrivatePermissions,
            boolean allowBuildRoot) {
        if (treeKind == null) {
            throw invalid("TREE_KIND_INVALID");
        }
        AncestorChain chain = captureAncestorChain(root, false);
        Set<PosixFilePermission> rootPermissions = metadataPermissions(root);
        if (requirePrivatePermissions && !rootPermissions.equals(PRIVATE_DIRECTORY)
                && !(allowBuildRoot && rootPermissions.equals(BUILD_DIRECTORY))) {
            throw invalid("BUNDLE_ROOT_PERMISSIONS_INVALID");
        }
        RootIdentity rootBefore = RootIdentity.from(rootAttributes(root), rootPermissions);
        List<Path> children = listChildren(root, treeKind.maximumEntries);
        Map<String, Path> byName = new LinkedHashMap<>();
        for (Path child : children) {
            String name = child.getFileName().toString();
            if (!SAFE_NAME.matcher(name).matches() || byName.putIfAbsent(name, child) != null) {
                throw invalid("ENTRY_NAME_INVALID");
            }
        }
        Path manifestPath = byName.get(treeKind.bundleManifestFile);
        if (manifestPath == null) {
            throw invalid("BUNDLE_MANIFEST_MISSING");
        }
        FileSnapshot manifest = readFile(
                root, manifestPath, pass, 0, treeKind.maximumManifestBytes,
                requirePrivatePermissions ? PRIVATE_FILE : null);
        BundleClosure closure = bundleClosure(
                treeKind, manifest.bytes(), expectedSemanticFingerprint);
        if (!closure.rules().keySet().equals(byName.keySet())) {
            throw invalid("BUNDLE_FILE_SET_INVALID");
        }
        if (!treeKind.validEntryCount(byName.size())) {
            throw invalid("ENTRY_COUNT_LIMIT");
        }

        List<String> names = new ArrayList<>(byName.keySet());
        names.sort(String::compareTo);
        List<FileSnapshot> files = new ArrayList<>(names.size());
        Set<Object> fileKeys = new HashSet<>();
        long total = 0;
        long referenced = 0;
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            FileRule rule = closure.rules().get(name);
            FileSnapshot file = name.equals(treeKind.bundleManifestFile)
                    ? manifest
                    : readFile(root, byName.get(name), pass, index, rule.maximumBytes(),
                            requirePrivatePermissions ? PRIVATE_FILE : null);
            if (!fileKeys.add(file.fileKey())) {
                throw invalid("FILE_IDENTITY_COLLISION");
            }
            total = addBounded(total, file.byteSize(), treeKind.maximumTotalBytes,
                    "TOTAL_SIZE_LIMIT");
            if (rule.referenced()) {
                referenced = addBounded(referenced, file.byteSize(),
                        treeKind == TreeKind.AUTHORITY_BUNDLE
                                ? MAXIMUM_AUTHORITY_REFERENCED_BYTES
                                : MAXIMUM_TARGET_ADMISSION_TOTAL_BYTES,
                        "REFERENCED_SIZE_LIMIT");
            }
            files.add(file);
        }
        RootIdentity rootAfter = RootIdentity.from(
                rootAttributes(root), metadataPermissions(root));
        recheckAncestorChain(chain);
        if (!rootBefore.equals(rootAfter)) {
            throw invalid("ROOT_CHANGED");
        }
        return new Inventory(rootBefore, List.copyOf(files));
    }

    private List<Path> listChildren(Path root, int maximumEntries) {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                children.add(child);
                if (children.size() > maximumEntries) {
                    throw invalid("ENTRY_COUNT_LIMIT");
                }
            }
        } catch (FormalInputTreeException failure) {
            throw failure;
        } catch (NoSuchFileException failure) {
            throw unavailable("ROOT_UNAVAILABLE");
        } catch (IOException | RuntimeException failure) {
            throw unavailable("ROOT_UNAVAILABLE");
        }
        return children;
    }

    private FileSnapshot readFile(
            Path root,
            Path file,
            InventoryPass pass,
            int index,
            int maximumBytes,
        Set<PosixFilePermission> requiredPermissions) {
        try {
            BasicFileAttributes before = operations.readAttributes(file);
            requireRegularFileType(before);
            requireFileIdentityMetadata(before);
            long linkCountBefore = operations.readUnixLong(file, "unix:nlink");
            long ownerUidBefore = operations.readUnixLong(file, "unix:uid");
            long rootOwnerUid = operations.readUnixLong(root, "unix:uid");
            Set<PosixFilePermission> permissionsBefore =
                    Set.copyOf(operations.readPosixPermissions(file));
            requireRegularFile(
                    before, linkCountBefore, maximumBytes,
                    requiredPermissions, permissionsBefore);
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) before.size());
            boolean observed = false;
            try (SeekableByteChannel channel = Files.newByteChannel(
                    file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
                while (true) {
                    int read = channel.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    if (read == 0) {
                        continue;
                    }
                    buffer.flip();
                    byte[] chunk = new byte[read];
                    buffer.get(chunk);
                    output.write(chunk);
                    buffer.clear();
                    if (output.size() > maximumBytes) {
                        throw invalid("FILE_SIZE_LIMIT");
                    }
                    if (!observed) {
                        observed = true;
                        observer.afterSourceReadChunk(pass, root, file, index);
                    }
                }
            }
            byte[] bytes = output.toByteArray();
            BasicFileAttributes after = operations.readAttributes(file);
            requireRegularFileType(after);
            requireFileIdentityMetadata(after);
            long linkCountAfter = operations.readUnixLong(file, "unix:nlink");
            long ownerUidAfter = operations.readUnixLong(file, "unix:uid");
            Set<PosixFilePermission> permissionsAfter =
                    Set.copyOf(operations.readPosixPermissions(file));
            if (!stableFile(before, after, bytes.length)
                    || linkCountBefore != 1 || linkCountAfter != 1
                    || ownerUidBefore != ownerUidAfter
                    || (ownerUidBefore != 0 && ownerUidBefore != rootOwnerUid)
                    || !permissionsBefore.equals(permissionsAfter)
                    || (requiredPermissions != null
                    && !permissionsAfter.equals(requiredPermissions))) {
                throw invalid("FILE_CHANGED_DURING_READ");
            }
            return new FileSnapshot(
                    file.getFileName().toString(), bytes.length, sha256(bytes),
                    before.fileKey(), before.lastModifiedTime(), linkCountBefore,
                    ownerUidBefore, permissionsBefore, bytes);
        } catch (FormalInputTreeException failure) {
            throw failure;
        } catch (UnsupportedOperationException failure) {
            throw unavailable("FILESYSTEM_METADATA_UNSUPPORTED");
        } catch (NoSuchFileException failure) {
            throw unavailable("ENTRY_UNAVAILABLE");
        } catch (IOException | RuntimeException failure) {
            throw unavailable("ENTRY_UNAVAILABLE");
        }
    }

    private FileSnapshot readStandaloneFile(
            Path file,
            int maximumBytes,
            Set<PosixFilePermission> requiredPermissions) {
        return readFile(file.getParent(), file, InventoryPass.VERIFY, 0,
                maximumBytes, requiredPermissions);
    }

    private static void requireRegularFile(
            BasicFileAttributes attributes,
            long linkCount,
            int maximumBytes,
            Set<PosixFilePermission> requiredPermissions,
            Set<PosixFilePermission> actualPermissions) {
        if (attributes.size() < 1 || attributes.size() > maximumBytes) {
            throw invalid(attributes.size() < 1 ? "ENTRY_EMPTY" : "FILE_SIZE_LIMIT");
        }
        if (linkCount != 1) {
            throw invalid("HARD_LINK_UNSAFE");
        }
        if (requiredPermissions != null && !requiredPermissions.equals(actualPermissions)) {
            throw invalid("FILE_PERMISSIONS_INVALID");
        }
    }

    private static void requireRegularFileType(BasicFileAttributes attributes) {
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw invalid("ENTRY_TYPE_INVALID");
        }
    }

    private static void requireFileIdentityMetadata(BasicFileAttributes attributes) {
        if (attributes.fileKey() == null) {
            throw unavailable("FILE_IDENTITY_UNAVAILABLE");
        }
    }

    private static boolean stableFile(
            BasicFileAttributes before,
            BasicFileAttributes after,
            int readSize) {
        return !after.isSymbolicLink() && after.isRegularFile() && after.fileKey() != null
                && before.fileKey().equals(after.fileKey())
                && before.size() == after.size() && after.size() == readSize
                && before.lastModifiedTime().equals(after.lastModifiedTime());
    }

    private static BundleClosure bundleClosure(
            TreeKind treeKind,
            byte[] manifestBytes,
            String expectedSemanticFingerprint) {
        JsonNode manifest = parseSingle(manifestBytes);
        if (!manifest.isObject()
                || !CapabilityStudioSchemaSupport.validate(manifest, treeKind.schemaResource)
                .isEmpty()) {
            throw invalid("BUNDLE_MANIFEST_INVALID");
        }
        if (!expectedSemanticFingerprint.equals(
                manifest.path("bundleFingerprint").asText(null))) {
            throw invalid("BUNDLE_SEMANTIC_FINGERPRINT_MISMATCH");
        }
        Map<String, FileRule> rules = new LinkedHashMap<>();
        registerRule(rules, treeKind.bundleManifestFile,
                new FileRule(treeKind.maximumManifestBytes, false, FileClass.MANIFEST), false);
        if (treeKind == TreeKind.AUTHORITY_BUNDLE) {
            for (JsonNode artifact : manifest.path("artifacts")) {
                registerRule(rules, artifact.path("artifactFile").asText(null),
                        new FileRule(MAXIMUM_AUTHORITY_ARTIFACT_BYTES, true,
                                FileClass.AUTHORITY_ARTIFACT), true);
            }
            for (JsonNode policy : manifest.path("issuerPolicies")) {
                registerRule(rules, policy.path("keySetFile").asText(null),
                        new FileRule(MAXIMUM_AUTHORITY_KEY_SET_BYTES, true,
                                FileClass.AUTHORITY_KEY_SET), true);
            }
            for (JsonNode policy : manifest.path("ownerPolicies")) {
                registerRule(rules, policy.path("keySetFile").asText(null),
                        new FileRule(MAXIMUM_AUTHORITY_KEY_SET_BYTES, true,
                                FileClass.AUTHORITY_KEY_SET), true);
            }
        } else {
            registerTargetRule(rules, manifest.at("/targetBinding/file").asText(null),
                    MAXIMUM_TARGET_ADMISSION_DOCUMENT_BYTES, FileClass.TARGET_BINDING);
            registerTargetRule(rules, manifest.at("/candidate/attestation/file").asText(null),
                    MAXIMUM_TARGET_ADMISSION_DOCUMENT_BYTES, FileClass.CANDIDATE_ATTESTATION);
            registerTargetRule(rules, manifest.at("/environment/attestation/file").asText(null),
                    MAXIMUM_TARGET_ADMISSION_DOCUMENT_BYTES, FileClass.ENVIRONMENT_ATTESTATION);
            registerTargetRule(rules, manifest.at("/candidate/policy/keySetFile").asText(null),
                    MAXIMUM_TARGET_ADMISSION_KEY_SET_BYTES, FileClass.CANDIDATE_KEY_SET);
            registerTargetRule(rules, manifest.at("/environment/policy/keySetFile").asText(null),
                    MAXIMUM_TARGET_ADMISSION_KEY_SET_BYTES, FileClass.ENVIRONMENT_KEY_SET);
            registerTargetRule(rules, manifest.at("/candidate/policy/proofFile").asText(null),
                    MAXIMUM_TARGET_ADMISSION_PROOF_BYTES, FileClass.CANDIDATE_PROOF);
            registerTargetRule(rules, manifest.at("/environment/policy/proofFile").asText(null),
                    MAXIMUM_TARGET_ADMISSION_PROOF_BYTES, FileClass.ENVIRONMENT_PROOF);
            if (rules.size() != TARGET_ADMISSION_ENTRY_COUNT) {
                throw invalid("BUNDLE_FILE_SET_INVALID");
            }
        }
        return new BundleClosure(Map.copyOf(rules));
    }

    private static void registerTargetRule(
            Map<String, FileRule> rules,
            String name,
            int maximumBytes,
            FileClass fileClass) {
        registerRule(rules, name, new FileRule(maximumBytes, true, fileClass), false);
    }

    private static void registerRule(
            Map<String, FileRule> rules,
            String name,
            FileRule rule,
            boolean allowSameClass) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw invalid("BUNDLE_FILE_NAME_INVALID");
        }
        FileRule prior = rules.putIfAbsent(name, rule);
        if (prior != null && (!allowSameClass || prior.fileClass() != rule.fileClass()
                || prior.maximumBytes() != rule.maximumBytes())) {
            throw invalid("DUPLICATE_FILE_BINDING");
        }
    }

    private static ParsedManifest parseManifest(
            byte[] bytes,
            TreeKind expectedKind,
            String expectedSemantic,
            String expectedTreeFingerprint,
            String expectedPublicationFingerprint,
            String expectedTransactionId) {
        JsonNode value = parseSingle(bytes);
        if (!value.isObject()
                || !CapabilityStudioSchemaSupport.validate(
                value, CapabilityStudioSchemaSupport.FORMAL_INPUT_TREE_V1_RESOURCE).isEmpty()) {
            throw invalid("MANIFEST_SCHEMA_INVALID");
        }
        try {
            if (!expectedKind.name().equals(value.path("treeKind").textValue())
                    || !expectedSemantic.equals(
                    value.path("bundleSemanticFingerprint").textValue())
                    || !expectedTreeFingerprint.equals(value.path("treeFingerprint").textValue())
                    || !expectedPublicationFingerprint.equals(
                    value.path("publicationFingerprint").textValue())
                    || !expectedTransactionId.equals(value.path("transactionId").textValue())) {
                throw invalid("MANIFEST_EXPECTATION_MISMATCH");
            }
            List<TreeEntry> entries = new ArrayList<>();
            for (JsonNode entry : value.path("entries")) {
                entries.add(new TreeEntry(
                        entry.path("relativePath").textValue(),
                        entry.path("byteSize").longValue(),
                        entry.path("rawFingerprint").textValue()));
            }
            Declaration declaration = new Declaration(
                    value.path("messageVersion").textValue(),
                    value.path("schemaVersion").textValue(), expectedKind, expectedSemantic,
                    value.path("entryCount").intValue(),
                    value.path("totalByteSize").longValue(), entries, expectedTreeFingerprint);
            String publicationFingerprint = value.path("publicationFingerprint").textValue();
            String transactionNonce = value.path("transactionNonce").textValue();
            String transactionId = value.path("transactionId").textValue();
            if (!computeTransactionId(
                    expectedKind, expectedSemantic, expectedTreeFingerprint,
                    publicationFingerprint, transactionNonce).equals(transactionId)
                    || !Arrays.equals(bytes, declaration.manifestBytes(
                    publicationFingerprint, transactionNonce, transactionId))) {
                throw invalid("MANIFEST_CANONICAL_BYTES_INVALID");
            }
            return new ParsedManifest(
                    declaration, publicationFingerprint, transactionNonce, transactionId);
        } catch (FormalInputTreeException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid("MANIFEST_INVALID");
        }
    }

    private static JsonNode parseSingle(byte[] bytes) {
        try (JsonParser parser = JSON.getFactory().createParser(bytes)) {
            JsonNode value = JSON.readTree(parser);
            if (value == null || parser.nextToken() != null) {
                throw invalid("JSON_INVALID");
            }
            return value;
        } catch (FormalInputTreeException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw invalid("JSON_INVALID");
        }
    }

    private void forcePrivateFile(Path file, Object expectedFileKey) {
        try (FileChannel channel = FileChannel.open(
                file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long ownerUid = unixLong(file, "unix:uid");
            FileIdentity before = fileIdentity(
                    file, PRIVATE_FILE, -1, ownerUid, "STAGING_IDENTITY_INVALID");
            if (!before.fileKey().equals(expectedFileKey)) {
                throw unavailable("STAGING_IDENTITY_INVALID");
            }
            operations.forceFile(channel);
            requireSameFileIdentity(file, before, PRIVATE_FILE, before.size(),
                    ownerUid, "STAGING_IDENTITY_INVALID");
        } catch (FormalInputTreeException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw unavailable("FILE_DURABILITY_UNAVAILABLE");
        }
    }

    private static FileIdentity fileIdentity(
            Path file,
            Set<PosixFilePermission> expectedPermissions,
            long expectedSize,
            long expectedOwnerUid,
            String code) {
        return fileIdentityAllowLinkCount(file, expectedPermissions, expectedSize,
                expectedOwnerUid, 1, 1, code);
    }

    private static FileIdentity fileIdentityAllowLinkCount(
            Path file,
            Set<PosixFilePermission> expectedPermissions,
            long expectedSize,
            long expectedOwnerUid,
            long minimumLinkCount,
            long maximumLinkCount,
            String code) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            long linkCount = unixLong(file, "unix:nlink");
            long ownerUid = unixLong(file, "unix:uid");
            Set<PosixFilePermission> actualPermissions = permissions(file);
            if (attributes.isSymbolicLink() || !attributes.isRegularFile()
                    || attributes.fileKey() == null || ownerUid != expectedOwnerUid
                    || linkCount < minimumLinkCount || linkCount > maximumLinkCount
                    || (expectedSize >= 0 && attributes.size() != expectedSize)
                    || !actualPermissions.equals(expectedPermissions)) {
                throw unavailable(code);
            }
            return new FileIdentity(attributes.fileKey(), attributes.size(), linkCount,
                    ownerUid, actualPermissions);
        } catch (FormalInputTreeException failure) {
            throw failure;
        } catch (NoSuchFileException failure) {
            throw unavailable(code);
        } catch (IOException | RuntimeException failure) {
            throw unavailable(code);
        }
    }

    private static void requireSameFileIdentity(
            Path file,
            FileIdentity expected,
            Set<PosixFilePermission> permissions,
            long size,
            long ownerUid,
            String code) {
        FileIdentity actual = fileIdentity(file, permissions, size, ownerUid, code);
        if (!actual.fileKey().equals(expected.fileKey())) {
            throw unavailable(code);
        }
    }

    private static byte[] readExactPrivate(Path file, int exactBytes, long ownerUid) {
        FileIdentity before = fileIdentity(
                file, PRIVATE_FILE, exactBytes, ownerUid, "OWNED_FILE_INVALID");
        byte[] bytes = readBounded(
                file, exactBytes, ownerUid, PRIVATE_FILE, "OWNED_FILE_INVALID");
        if (bytes.length != exactBytes) {
            throw unavailable("OWNED_FILE_INVALID");
        }
        requireSameFileIdentity(file, before, PRIVATE_FILE, exactBytes,
                ownerUid, "OWNED_FILE_CHANGED");
        return bytes;
    }

    private static byte[] readExactOwnedAllowLinkCount(
            Path file,
            int exactBytes,
            long ownerUid,
            long expectedLinkCount) {
        FileIdentity before = fileIdentityAllowLinkCount(
                file, PRIVATE_FILE, exactBytes, ownerUid,
                expectedLinkCount, expectedLinkCount, "OWNED_FILE_INVALID");
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException | RuntimeException failure) {
            throw unavailable("OWNED_FILE_INVALID");
        }
        FileIdentity after = fileIdentityAllowLinkCount(
                file, PRIVATE_FILE, exactBytes, ownerUid,
                expectedLinkCount, expectedLinkCount, "OWNED_FILE_INVALID");
        if (bytes.length != exactBytes || !before.fileKey().equals(after.fileKey())) {
            throw unavailable("OWNED_FILE_CHANGED");
        }
        return bytes;
    }

    private static byte[] readRecoverableInstalledFile(
            Path file,
            int maximumBytes,
            long ownerUid,
            String code) {
        FileIdentity before = fileIdentityAllowLinkCount(
                file, PRIVATE_FILE, -1, ownerUid, 1, 2, code);
        if (before.size() < 1 || before.size() > maximumBytes) {
            throw unavailable(code);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException | RuntimeException failure) {
            throw unavailable(code);
        }
        FileIdentity after = fileIdentityAllowLinkCount(
                file, PRIVATE_FILE, before.size(), ownerUid,
                before.linkCount(), before.linkCount(), code);
        if (!before.fileKey().equals(after.fileKey()) || bytes.length != before.size()) {
            throw unavailable(code);
        }
        return bytes;
    }

    private static byte[] readBounded(
            Path file,
            int maximumBytes,
            long ownerUid,
            Set<PosixFilePermission> expectedPermissions,
            String code) {
        FileIdentity before = fileIdentityAllowLinkCount(
                file, expectedPermissions, -1, ownerUid, 1, 1, code);
        if (before.size() > maximumBytes) {
            throw unavailable(code);
        }
        try (SeekableByteChannel channel = Files.newByteChannel(
                file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) before.size());
            ByteBuffer buffer = ByteBuffer.allocate(4096);
            while (true) {
                int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                buffer.flip();
                byte[] chunk = new byte[read];
                buffer.get(chunk);
                output.write(chunk);
                buffer.clear();
                if (output.size() > maximumBytes) {
                    throw unavailable(code);
                }
            }
            byte[] bytes = output.toByteArray();
            requireSameFileIdentity(file, before, expectedPermissions, bytes.length,
                    ownerUid, code);
            return bytes;
        } catch (FormalInputTreeException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw unavailable(code);
        }
    }

    private void finishDirectory(
            Path directory,
            DirectoryIdentity expectedIdentity,
            long expectedOwnerUid) {
        DirectoryState before = directoryState(directory);
        if (!before.identity().equals(expectedIdentity) || before.ownerUid() != expectedOwnerUid) {
            throw unavailable("STAGING_IDENTITY_INVALID");
        }
        try {
            operations.chmod(directory, PRIVATE_DIRECTORY);
            DirectoryState afterChmod = directoryState(directory);
            if (!afterChmod.identity().equals(expectedIdentity)
                    || afterChmod.ownerUid() != expectedOwnerUid
                    || !afterChmod.permissions().equals(PRIVATE_DIRECTORY)) {
                throw unavailable("STAGING_IDENTITY_INVALID");
            }
            observer.afterChmod(directory);
            operations.forceDirectory(directory);
            DirectoryState afterForce = directoryState(directory);
            if (!afterForce.equals(afterChmod)) {
                throw unavailable("STAGING_IDENTITY_INVALID");
            }
        } catch (FormalInputTreeException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw unavailable("DIRECTORY_DURABILITY_UNAVAILABLE");
        }
    }

    private void forceDirectory(Path directory, String code) {
        try {
            operations.forceDirectory(directory);
        } catch (IOException | RuntimeException failure) {
            throw unavailable(code);
        }
    }

    private static DirectoryState directoryState(Path directory) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || !attributes.isDirectory()
                    || attributes.fileKey() == null) {
                throw unavailable("DIRECTORY_IDENTITY_INVALID");
            }
            return new DirectoryState(
                    new DirectoryIdentity(attributes.fileKey()),
                    unixLong(directory, "unix:uid"), permissions(directory));
        } catch (FormalInputTreeException failure) {
            throw failure;
        } catch (NoSuchFileException failure) {
            throw unavailable("DIRECTORY_UNAVAILABLE");
        } catch (IOException | RuntimeException failure) {
            throw unavailable("DIRECTORY_UNAVAILABLE");
        }
    }

    private static DirectoryState verificationDirectoryState(
            Path directory,
            boolean missingIsUnavailable) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                throw invalid("WRAPPER_STRUCTURE_INVALID");
            }
            if (attributes.fileKey() == null) {
                throw unavailable("VERIFICATION_METADATA_UNAVAILABLE");
            }
            return new DirectoryState(
                    new DirectoryIdentity(attributes.fileKey()),
                    unixLong(directory, "unix:uid"), permissions(directory));
        } catch (FormalInputTreeException failure) {
            throw failure;
        } catch (NoSuchFileException failure) {
            if (missingIsUnavailable) {
                throw unavailable("WRAPPER_UNAVAILABLE");
            }
            throw invalid("WRAPPER_STRUCTURE_INVALID");
        } catch (IOException | RuntimeException failure) {
            throw unavailable("VERIFICATION_METADATA_UNAVAILABLE");
        }
    }

    private BasicFileAttributes rootAttributes(Path root) {
        try {
            BasicFileAttributes attributes = operations.readAttributes(root);
            if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                throw invalid("ROOT_INVALID");
            }
            if (attributes.fileKey() == null) {
                throw unavailable("ROOT_IDENTITY_UNAVAILABLE");
            }
            return attributes;
        } catch (FormalInputTreeException failure) {
            throw failure;
        } catch (NoSuchFileException failure) {
            throw unavailable("ROOT_UNAVAILABLE");
        } catch (IOException | RuntimeException failure) {
            throw unavailable("ROOT_UNAVAILABLE");
        }
    }

    private Set<PosixFilePermission> metadataPermissions(Path path) {
        try {
            return Set.copyOf(operations.readPosixPermissions(path));
        } catch (UnsupportedOperationException failure) {
            throw unavailable("POSIX_PERMISSIONS_UNSUPPORTED");
        } catch (IOException | RuntimeException failure) {
            throw unavailable("POSIX_PERMISSIONS_UNAVAILABLE");
        }
    }

    private static Set<PosixFilePermission> permissions(Path path) {
        try {
            return Set.copyOf(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
        } catch (UnsupportedOperationException failure) {
            throw unavailable("POSIX_PERMISSIONS_UNSUPPORTED");
        } catch (IOException | RuntimeException failure) {
            throw unavailable("POSIX_PERMISSIONS_UNAVAILABLE");
        }
    }

    private static long unixLong(Path path, String attribute) throws IOException {
        Object value;
        try {
            value = Files.getAttribute(path, attribute, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException failure) {
            throw new IOException("required unix metadata unavailable", failure);
        }
        if (!(value instanceof Number number)) {
            throw new IOException("required unix metadata unavailable");
        }
        return number.longValue();
    }

    private static int unixMode(Path path) throws IOException {
        return (int) (unixLong(path, "unix:mode") & 07777);
    }

    private static AncestorChain capturePublicationChain(Path parent) {
        AncestorChain chain = captureAncestorChain(parent, true);
        AncestorIdentity publicationParent = chain.identities().getLast();
        if (publicationParent.mode() != 0700) {
            throw unavailable("OUTPUT_PARENT_PERMISSIONS_UNSAFE");
        }
        validateSecureChain(chain.identities(), Set.of(0L, publicationParent.ownerUid()));
        return new AncestorChain(
                chain.identities(), true, publicationParent.ownerUid());
    }

    private static AncestorChain captureVerificationChain(Path parent) {
        AncestorChain chain = captureAncestorChain(parent, false);
        AncestorIdentity verificationParent = chain.identities().getLast();
        int mode = verificationParent.mode();
        if ((mode & 0022) != 0 || (mode & 0500) != 0500) {
            throw unavailable("VERIFICATION_PARENT_PERMISSIONS_UNSAFE");
        }
        validateSecureChain(chain.identities(), Set.of(0L, verificationParent.ownerUid()));
        return new AncestorChain(
                chain.identities(), false, verificationParent.ownerUid());
    }

    private static AncestorChain captureAncestorChain(Path path, boolean requirePrivateParent) {
        if (path == null || !path.isAbsolute() || !path.equals(path.normalize())) {
            throw invalid("PATH_INVALID");
        }
        List<AncestorIdentity> identities = new ArrayList<>();
        Path current = path.getRoot();
        identities.add(ancestorIdentity(current));
        for (Path component : path) {
            current = current.resolve(component);
            identities.add(ancestorIdentity(current));
        }
        if (requirePrivateParent) {
            Set<PosixFilePermission> parentPermissions = identities.getLast().permissions();
            if (!parentPermissions.contains(PosixFilePermission.OWNER_READ)
                    || !parentPermissions.contains(PosixFilePermission.OWNER_WRITE)
                    || !parentPermissions.contains(PosixFilePermission.OWNER_EXECUTE)
                    || parentPermissions.contains(PosixFilePermission.GROUP_WRITE)
                    || parentPermissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw unavailable("OUTPUT_PARENT_PERMISSIONS_UNSAFE");
            }
        }
        long pathOwnerUid = identities.getLast().ownerUid();
        validateSecureChain(identities, Set.of(0L, pathOwnerUid));
        return new AncestorChain(
                List.copyOf(identities), requirePrivateParent, pathOwnerUid);
    }

    private static AncestorIdentity ancestorIdentity(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || !attributes.isDirectory()
                    || attributes.fileKey() == null) {
                throw unavailable("ANCESTOR_UNSAFE");
            }
            return new AncestorIdentity(
                    path, attributes.fileKey(), unixLong(path, "unix:uid"),
                    unixMode(path), permissions(path));
        } catch (FormalInputTreeException failure) {
            throw failure;
        } catch (NoSuchFileException failure) {
            throw unavailable("ANCESTOR_UNAVAILABLE");
        } catch (IOException | RuntimeException failure) {
            throw unavailable("ANCESTOR_UNAVAILABLE");
        }
    }

    private static void validateSecureChain(
            List<AncestorIdentity> identities,
            Set<Long> allowedOwners) {
        for (int index = 0; index < identities.size(); index++) {
            AncestorIdentity identity = identities.get(index);
            if (!allowedOwners.contains(identity.ownerUid())) {
                throw unavailable("ANCESTOR_OWNER_UNSAFE");
            }
            boolean writableByGroupOrWorld = (identity.mode() & 0022) != 0;
            if (writableByGroupOrWorld) {
                if ((identity.mode() & STICKY_BIT) == 0 || index + 1 >= identities.size()
                        || !allowedOwners.contains(identities.get(index + 1).ownerUid())) {
                    throw unavailable("ANCESTOR_PERMISSIONS_UNSAFE");
                }
            }
        }
    }

    private static void recheckAncestorChain(AncestorChain chain) {
        for (AncestorIdentity expected : chain.identities()) {
            if (!expected.equals(ancestorIdentity(expected.path()))) {
                throw unavailable("ANCESTOR_CHANGED");
            }
        }
    }

    private static Path outputPath(Path output) {
        Path normalized = absoluteNormalized(output, "SNAPSHOT_OUTPUT_INVALID");
        if (normalized.getParent() == null || normalized.getFileName() == null
                || !SAFE_OUTPUT_NAME.matcher(normalized.getFileName().toString()).matches()) {
            throw invalid("SNAPSHOT_OUTPUT_INVALID");
        }
        return normalized;
    }

    private static Path absoluteNormalized(Path path, String code) {
        if (path == null || !path.isAbsolute() || !path.equals(path.normalize())) {
            throw invalid(code);
        }
        return path;
    }

    private static boolean present(Path path) {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (NoSuchFileException absent) {
            return false;
        } catch (IOException | RuntimeException failure) {
            throw unavailable("OUTPUT_STATE_UNAVAILABLE");
        }
    }

    private static void requireOnlyNames(
            Path directory,
            Set<String> allowed,
            boolean allowMissing) {
        Set<String> remaining = new LinkedHashSet<>(allowed);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                if (!remaining.remove(child.getFileName().toString())) {
                    throw unavailable("STAGING_CONFLICT");
                }
            }
        } catch (FormalInputTreeException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw unavailable("DIRECTORY_UNAVAILABLE");
        }
        if (!allowMissing && !remaining.isEmpty()) {
            throw unavailable("STAGING_CONFLICT");
        }
    }

    private static void requireOnlyAllowedNames(
            Path directory,
            Set<String> allowed,
            String code) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                if (!allowed.contains(child.getFileName().toString())) {
                    throw unavailable(code);
                }
            }
        } catch (FormalInputTreeException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw unavailable(code);
        }
    }

    private static void requireVerificationNames(Path directory, Set<String> expected) {
        Set<String> remaining = new LinkedHashSet<>(expected);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                if (!remaining.remove(child.getFileName().toString())) {
                    throw invalid("WRAPPER_STRUCTURE_INVALID");
                }
            }
        } catch (FormalInputTreeException failure) {
            throw failure;
        } catch (NoSuchFileException failure) {
            throw unavailable("WRAPPER_UNAVAILABLE");
        } catch (IOException | RuntimeException failure) {
            throw unavailable("WRAPPER_READ_UNAVAILABLE");
        }
        if (!remaining.isEmpty()) {
            throw invalid("WRAPPER_STRUCTURE_INVALID");
        }
    }

    private static void requireSameInventory(Inventory first, Inventory second) {
        if (!first.rootIdentity().equals(second.rootIdentity())
                || first.files().size() != second.files().size()) {
            throw invalid("SOURCE_CHANGED");
        }
        for (int index = 0; index < first.files().size(); index++) {
            if (!first.files().get(index).sameObservation(second.files().get(index))) {
                throw invalid("SOURCE_CHANGED");
            }
        }
    }

    private static Declaration declarationFromFiles(
            TreeKind treeKind,
            String semanticFingerprint,
            List<FileSnapshot> files) {
        return createDeclaration(treeKind, semanticFingerprint, files.stream()
                .map(file -> new TreeEntry(
                        file.relativePath(), file.byteSize(), file.rawFingerprint()))
                .toList());
    }

    private static SnapshotReceipt receipt(
            CommitStatus status,
            PublicationCoordinates coordinates,
            Declaration declaration) {
        String manifestFingerprint = sha256(declaration.manifestBytes(
                coordinates.publicationFingerprint(), coordinates.transactionNonce(),
                coordinates.transactionId()));
        return new SnapshotReceipt(
                status, coordinates.transactionId(), coordinates.publicationFingerprint(),
                coordinates.transactionNonce(), manifestFingerprint,
                receiptFingerprint(
                        coordinates.transactionId(), coordinates.publicationFingerprint(),
                        coordinates.transactionNonce(), manifestFingerprint, declaration),
                declaration);
    }

    private static String transactionCanonicalJson(
            TreeKind treeKind,
            String semanticFingerprint,
            String treeFingerprint,
            String publicationFingerprint,
            String transactionNonce) {
        return "{\"messageVersion\":\"" + TRANSACTION_MESSAGE_VERSION
                + "\",\"treeKind\":\"" + treeKind
                + "\",\"bundleSemanticFingerprint\":\"" + semanticFingerprint
                + "\",\"treeFingerprint\":\"" + treeFingerprint
                + "\",\"publicationFingerprint\":\"" + publicationFingerprint
                + "\",\"transactionNonce\":\"" + transactionNonce + "\"}";
    }

    private static String receiptFingerprint(
            String transactionId,
            String publicationFingerprint,
            String transactionNonce,
            String committedManifestFingerprint,
            Declaration declaration) {
        return sha256("{\"messageVersion\":\"" + RECEIPT_MESSAGE_VERSION
                + "\",\"transactionId\":\"" + transactionId
                + "\",\"treeKind\":\"" + declaration.treeKind()
                + "\",\"bundleSemanticFingerprint\":\""
                + declaration.bundleSemanticFingerprint()
                + "\",\"treeFingerprint\":\"" + declaration.treeFingerprint()
                + "\",\"publicationFingerprint\":\"" + publicationFingerprint
                + "\",\"transactionNonce\":\"" + transactionNonce
                + "\",\"committedManifestFingerprint\":\""
                + committedManifestFingerprint + "\"}");
    }

    private static String treeCanonicalJson(
            String messageVersion,
            String schemaVersion,
            TreeKind treeKind,
            String semanticFingerprint,
            int entryCount,
            long totalByteSize,
            List<TreeEntry> entries,
            String treeFingerprint) {
        StringBuilder canonical = new StringBuilder(512);
        canonical.append("{\"messageVersion\":\"").append(messageVersion)
                .append("\",\"schemaVersion\":\"").append(schemaVersion)
                .append("\",\"treeKind\":\"").append(treeKind.name())
                .append("\",\"bundleSemanticFingerprint\":\"")
                .append(semanticFingerprint)
                .append("\",\"entryCount\":").append(entryCount)
                .append(",\"totalByteSize\":").append(totalByteSize)
                .append(",\"entries\":[");
        appendEntries(canonical, entries);
        canonical.append("],\"treeFingerprint\":");
        canonical.append(treeFingerprint == null ? "null" : "\"" + treeFingerprint + "\"");
        return canonical.append('}').toString();
    }

    private static String manifestCanonicalJson(
            Declaration declaration,
            String publicationFingerprint,
            String transactionNonce,
            String transactionId) {
        StringBuilder canonical = new StringBuilder(512);
        canonical.append("{\"messageVersion\":\"").append(declaration.messageVersion())
                .append("\",\"schemaVersion\":\"").append(declaration.schemaVersion())
                .append("\",\"treeKind\":\"").append(declaration.treeKind())
                .append("\",\"bundleSemanticFingerprint\":\"")
                .append(declaration.bundleSemanticFingerprint())
                .append("\",\"entryCount\":").append(declaration.entryCount())
                .append(",\"totalByteSize\":").append(declaration.totalByteSize())
                .append(",\"entries\":[");
        appendEntries(canonical, declaration.entries());
        return canonical.append("],\"publicationFingerprint\":\"")
                .append(publicationFingerprint)
                .append("\",\"transactionNonce\":\"").append(transactionNonce)
                .append("\",\"transactionId\":\"").append(transactionId)
                .append("\",\"treeFingerprint\":\"")
                .append(declaration.treeFingerprint()).append("\"}").toString();
    }

    private static OwnerDescriptor ownerDescriptor(PublicationCoordinates coordinates) {
        String canonical = ownerCanonicalJson(coordinates, null);
        return new OwnerDescriptor(
                OWNER_MESSAGE_VERSION, coordinates.transactionId(),
                coordinates.transactionNonce(), coordinates.treeKind(),
                coordinates.bundleSemanticFingerprint(), coordinates.treeFingerprint(),
                coordinates.publicationFingerprint(), sha256(canonical));
    }

    private static String ownerCanonicalJson(
            PublicationCoordinates coordinates,
            String ownerReceiptFingerprint) {
        return "{\"messageVersion\":\"" + OWNER_MESSAGE_VERSION
                + "\",\"transactionId\":\"" + coordinates.transactionId()
                + "\",\"transactionNonce\":\"" + coordinates.transactionNonce()
                + "\",\"treeKind\":\"" + coordinates.treeKind()
                + "\",\"bundleSemanticFingerprint\":\""
                + coordinates.bundleSemanticFingerprint()
                + "\",\"treeFingerprint\":\"" + coordinates.treeFingerprint()
                + "\",\"publicationFingerprint\":\""
                + coordinates.publicationFingerprint()
                + "\",\"ownerReceiptFingerprint\":"
                + (ownerReceiptFingerprint == null
                ? "null" : "\"" + ownerReceiptFingerprint + "\"") + "}";
    }

    private static String ownerCanonicalJson(OwnerDescriptor owner) {
        PublicationCoordinates coordinates = new PublicationCoordinates(
                owner.treeKind(), owner.bundleSemanticFingerprint(), owner.treeFingerprint(),
                owner.publicationFingerprint(), owner.transactionNonce(), owner.transactionId());
        return ownerCanonicalJson(coordinates, owner.ownerReceiptFingerprint());
    }

    private static void appendEntries(StringBuilder canonical, List<TreeEntry> entries) {
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0) {
                canonical.append(',');
            }
            TreeEntry entry = entries.get(index);
            canonical.append("{\"relativePath\":\"").append(entry.relativePath())
                    .append("\",\"byteSize\":").append(entry.byteSize())
                    .append(",\"rawFingerprint\":\"").append(entry.rawFingerprint())
                    .append("\"}");
        }
    }

    private static long totalBytes(List<TreeEntry> entries) {
        long total = 0;
        for (TreeEntry entry : entries) {
            total = addBounded(total, entry.byteSize(), MAXIMUM_TOTAL_BYTES, "TOTAL_SIZE_LIMIT");
        }
        return total;
    }

    private static long addBounded(long total, long bytes, long maximum, String code) {
        if (bytes < 0 || total > maximum - bytes) {
            throw invalid(code);
        }
        return total + bytes;
    }

    private static boolean strictlySorted(List<TreeEntry> entries) {
        String previous = null;
        for (TreeEntry entry : entries) {
            if (entry == null || (previous != null
                    && previous.compareTo(entry.relativePath()) >= 0)) {
                return false;
            }
            previous = entry.relativePath();
        }
        return true;
    }

    private static boolean fingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }

    private static void requireFingerprint(String value, String code) {
        if (!fingerprint(value)) {
            throw invalid(code);
        }
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw unavailable("SHA256_UNAVAILABLE");
        }
    }

    private static FormalInputTreeException invalid(String code) {
        return new FormalInputTreeException(FailureKind.INVALID, code);
    }

    private static FormalInputTreeException unavailable(String code) {
        return new FormalInputTreeException(FailureKind.UNAVAILABLE, code);
    }

    private enum FileClass {
        MANIFEST, AUTHORITY_ARTIFACT, AUTHORITY_KEY_SET, TARGET_BINDING,
        CANDIDATE_ATTESTATION, ENVIRONMENT_ATTESTATION, CANDIDATE_KEY_SET,
        ENVIRONMENT_KEY_SET, CANDIDATE_PROOF, ENVIRONMENT_PROOF
    }

    private enum InstallState {
        SOURCE_ONLY(true, false),
        TARGET_ONLY(false, true),
        BOTH(true, true),
        NEITHER(false, false);

        private final boolean sourcePresent;
        private final boolean targetPresent;

        InstallState(boolean sourcePresent, boolean targetPresent) {
            this.sourcePresent = sourcePresent;
            this.targetPresent = targetPresent;
        }

        private boolean sourcePresent() {
            return sourcePresent;
        }

        private boolean targetPresent() {
            return targetPresent;
        }
    }

    private record FileRule(int maximumBytes, boolean referenced, FileClass fileClass) {
    }

    private record BundleClosure(Map<String, FileRule> rules) {
    }

    private record ParsedManifest(
            Declaration declaration,
            String publicationFingerprint,
            String transactionNonce,
            String transactionId) {
    }

    private record PublicationCoordinates(
            TreeKind treeKind,
            String bundleSemanticFingerprint,
            String treeFingerprint,
            String publicationFingerprint,
            String transactionNonce,
            String transactionId) {
        private PublicationCoordinates {
            if (treeKind == null) {
                throw invalid("TREE_KIND_INVALID");
            }
            requireFingerprint(bundleSemanticFingerprint, "SEMANTIC_FINGERPRINT_INVALID");
            requireFingerprint(treeFingerprint, "TREE_PIN_INVALID");
            requireFingerprint(publicationFingerprint, "PUBLICATION_FINGERPRINT_INVALID");
            requireFingerprint(transactionNonce, "TRANSACTION_NONCE_INVALID");
            requireFingerprint(transactionId, "TRANSACTION_ID_INVALID");
            if (!computeTransactionId(
                    treeKind, bundleSemanticFingerprint, treeFingerprint,
                    publicationFingerprint, transactionNonce).equals(transactionId)) {
                throw invalid("TRANSACTION_ID_INVALID");
            }
        }
    }

    private record PublicationPaths(
            Path parent,
            Path output,
            Path lockFile,
            Path staging,
            Path owner,
            Path ownerBootstrap,
            Path stagingBundle,
            Path stagingParts,
            Path manifestPart,
            Path finalBundle,
            Path finalManifest) {
    }

    private record OwnerDescriptor(
            String messageVersion,
            String transactionId,
            String transactionNonce,
            TreeKind treeKind,
            String bundleSemanticFingerprint,
            String treeFingerprint,
            String publicationFingerprint,
            String ownerReceiptFingerprint) {
        private OwnerDescriptor {
            if (!OWNER_MESSAGE_VERSION.equals(messageVersion) || treeKind == null) {
                throw unavailable("OWNER_CONFLICT");
            }
            requireFingerprint(transactionId, "OWNER_CONFLICT");
            requireFingerprint(transactionNonce, "OWNER_CONFLICT");
            requireFingerprint(bundleSemanticFingerprint, "OWNER_CONFLICT");
            requireFingerprint(treeFingerprint, "OWNER_CONFLICT");
            requireFingerprint(publicationFingerprint, "OWNER_CONFLICT");
            requireFingerprint(ownerReceiptFingerprint, "OWNER_CONFLICT");
            PublicationCoordinates coordinates = new PublicationCoordinates(
                    treeKind, bundleSemanticFingerprint, treeFingerprint,
                    publicationFingerprint, transactionNonce, transactionId);
            if (!sha256(ownerCanonicalJson(coordinates, null))
                    .equals(ownerReceiptFingerprint)) {
                throw unavailable("OWNER_CONFLICT");
            }
        }
    }

    private record DirectoryIdentity(Object fileKey) {
    }

    private record DirectoryState(
            DirectoryIdentity identity,
            long ownerUid,
            Set<PosixFilePermission> permissions) {
    }

    private record AncestorIdentity(
            Path path,
            Object fileKey,
            long ownerUid,
            int mode,
            Set<PosixFilePermission> permissions) {
    }

    private record AncestorChain(
            List<AncestorIdentity> identities,
            boolean requirePrivateParent,
            long publicationOwnerUid) {
    }

    private record FileIdentity(
            Object fileKey,
            long size,
            long linkCount,
            long ownerUid,
            Set<PosixFilePermission> permissions) {
        private FileIdentity {
            permissions = Set.copyOf(permissions);
        }

        private FileIdentity withPermissions(Set<PosixFilePermission> replacement) {
            return new FileIdentity(fileKey, size, linkCount, ownerUid, replacement);
        }
    }

    private record RootIdentity(
            Object fileKey,
            FileTime modifiedTime,
            long byteSize,
            Set<PosixFilePermission> permissions) {
        private static RootIdentity from(
                BasicFileAttributes attributes,
                Set<PosixFilePermission> permissions) {
            return new RootIdentity(attributes.fileKey(), attributes.lastModifiedTime(),
                    attributes.size(), permissions);
        }
    }

    private record FileObservation(
            String relativePath,
            long byteSize,
            String rawFingerprint,
            Object fileKey,
            FileTime modifiedTime,
            long linkCount,
            long ownerUid,
            Set<PosixFilePermission> permissions) {
    }

    private record FileSnapshot(
            String relativePath,
            long byteSize,
            String rawFingerprint,
            Object fileKey,
            FileTime modifiedTime,
            long linkCount,
            long ownerUid,
            Set<PosixFilePermission> permissions,
            byte[] bytes) {
        private FileSnapshot {
            permissions = Set.copyOf(permissions);
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        private FileObservation observation() {
            return new FileObservation(relativePath, byteSize, rawFingerprint,
                    fileKey, modifiedTime, linkCount, ownerUid, permissions);
        }

        private boolean sameObservation(FileSnapshot other) {
            return other != null && observation().equals(other.observation());
        }
    }

    private record Inventory(RootIdentity rootIdentity, List<FileSnapshot> files) {
        private List<FileObservation> observation() {
            return files.stream().map(FileSnapshot::observation).toList();
        }
    }

    private record WrapperObservation(
            DirectoryState wrapper,
            DirectoryState bundleRoot,
            FileObservation manifest,
            List<FileObservation> files,
            Declaration declaration,
            String publicationFingerprint,
            String transactionNonce,
            String transactionId,
            String committedManifestFingerprint) {
    }

    enum InventoryPass {
        FIRST, SECOND, VERIFY
    }

    interface SnapshotObserver {
        SnapshotObserver NONE = new SnapshotObserver() { };

        default void afterSourceReadChunk(
                InventoryPass pass, Path root, Path file, int index) {
        }

        default void beforeJvmPublicationLock(Path publicationParent) {
        }

        default void beforeFilePublicationLock(Path lockFile) {
        }

        default void afterInventory(InventoryPass pass, Path root) {
        }

        default void beforeStaging(Path staging) {
        }

        default void afterStagingRoot(Path staging) {
        }

        default void afterOwnerClaim(Path owner) {
        }

        default void afterPartFirstChunk(Path part) {
        }

        default void afterFileForce(Path file) {
        }

        default void afterBundleFile(Path staging, String relativePath, int index) {
        }

        default void afterClosureForce(Path directory) {
        }

        default void afterChmod(Path path) {
        }

        default void afterObjectForce(Path path) {
        }

        default void afterAtomicMove(Path source, Path target) {
        }

        default void beforeSourceUnlink(Path source, Path target) {
        }

        default void afterSourceUnlink(Path source, Path target) {
        }

        default void afterManifest(Path staging) {
        }

        default void beforePublish(Path staging, Path output) {
        }

        default void afterPublish(Path output) {
        }

        default void afterParentForce(Path output) {
        }


        default void afterPersistedVerify(Path output) {
        }
    }

    @FunctionalInterface
    private interface LockedAction<T> {
        T run();
    }

    interface AtomicOperations {
        void forceFile(FileChannel channel) throws IOException;

        void chmod(Path path, Set<PosixFilePermission> permissions) throws IOException;

        void forceDirectory(Path directory) throws IOException;

        void atomicMove(Path source, Path target) throws IOException;

        default BasicFileAttributes readAttributes(Path path) throws IOException {
            return Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }

        default long readUnixLong(Path path, String attribute) throws IOException {
            return unixLong(path, attribute);
        }

        default Set<PosixFilePermission> readPosixPermissions(Path path) throws IOException {
            return Set.copyOf(Files.getPosixFilePermissions(
                    path, LinkOption.NOFOLLOW_LINKS));
        }

        default Object directoryIdentity(Path directory) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(
                    directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || !attributes.isDirectory()
                    || attributes.fileKey() == null) {
                throw new IOException("directory identity unavailable");
            }
            return attributes.fileKey();
        }

    }

    @FunctionalInterface
    interface MonotonicTicker {
        long read();
    }

    private static final class LeaseBudget {
        private final MonotonicTicker ticker;
        private long lastTick;
        private long remainingNanos;

        private LeaseBudget(long lastTick, long remainingNanos, MonotonicTicker ticker) {
            this.lastTick = lastTick;
            this.remainingNanos = remainingNanos;
            this.ticker = ticker;
        }

        private static LeaseBudget start(long timeoutNanos, MonotonicTicker ticker) {
            return new LeaseBudget(safeTick(ticker), timeoutNanos, ticker);
        }

        // One lease thread owns the budget; synchronization also makes accidental reuse safe.
        private synchronized long remainingNanos() {
            long currentTick = safeTick(ticker);
            long delta = currentTick - lastTick;
            if (delta < 0) {
                throw unavailable("MONOTONIC_TICK_INVALID");
            }
            lastTick = currentTick;
            if (delta >= remainingNanos) {
                remainingNanos = 0;
            } else {
                remainingNanos -= delta;
            }
            return remainingNanos;
        }

        private static long safeTick(MonotonicTicker ticker) {
            try {
                return ticker.read();
            } catch (RuntimeException failure) {
                throw unavailable("MONOTONIC_TICK_UNAVAILABLE");
            }
        }
    }

    private static final class LocalAtomicOperations implements AtomicOperations {
        @Override
        public void forceFile(FileChannel channel) throws IOException {
            channel.force(true);
        }

        @Override
        public void chmod(Path path, Set<PosixFilePermission> permissions) throws IOException {
            Files.setPosixFilePermissions(path, permissions);
        }

        @Override
        public void forceDirectory(Path directory) throws IOException {
            try (FileChannel channel = FileChannel.open(
                    directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            } catch (UnsupportedOperationException failure) {
                throw new IOException("directory force unavailable", failure);
            }
        }

        @Override
        public void atomicMove(Path source, Path target) throws IOException {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException failure) {
                throw new IOException("atomic move unavailable", failure);
            }
        }

    }
}
