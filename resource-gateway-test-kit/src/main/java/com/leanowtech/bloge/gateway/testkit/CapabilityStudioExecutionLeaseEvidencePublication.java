package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider
        .EvidenceFailureKind;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Set;

/** Deployment provisioning and existing-only verification for an evidence publication lease. */
public final class CapabilityStudioExecutionLeaseEvidencePublication {
    /** Environment pin required by the production Evidence CLI. */
    public static final String EXPECTED_PUBLICATION_FINGERPRINT_ENV =
            "BLOGE_EXPECTED_CAPABILITY_STUDIO_EVIDENCE_PUBLICATION_FINGERPRINT";
    /** Fixed owner bootstrap direct-child filename. */
    public static final String OWNER_BOOTSTRAP_FILE =
            ".capability-studio-execution-lease-evidence-owner-bootstrap-v1.json";
    /** Fixed inter-process publication lock direct-child filename. */
    public static final String PUBLICATION_LOCK_FILE =
            ".capability-studio-execution-lease-evidence-publication-v1.lock";
    /** Fixed publication declaration direct-child filename. */
    public static final String PUBLICATION_DECLARATION_FILE =
            ".capability-studio-execution-lease-evidence-publication-v1.json";
    /** The only transcript direct-child admitted by one provisioned publication parent. */
    public static final String TRANSCRIPT_FILE = "execution-lease-transcript-v1.json";

    private static final String BOOTSTRAP_VERSION =
            "resource-gateway.capability-studio.execution-lease-evidence-owner-bootstrap.v1";
    private static final String DECLARATION_VERSION =
            "resource-gateway.capability-studio.execution-lease-evidence-publication.v1";
    private static final String TRANSACTION_ID_VERSION =
            "resource-gateway.capability-studio.execution-lease-evidence-transaction-id.v1";
    private static final int MAXIMUM_DOCUMENT_BYTES = 16 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper(new JsonFactory().rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    private CapabilityStudioExecutionLeaseEvidencePublication() {
    }

    /**
     * Payload-free immutable publication preflight coordinates.
     *
     * @param publicationFingerprint complete declaration fingerprint
     * @param publicationNonce deployment-provided stable transaction nonce
     * @param transcriptRelativePath fixed direct-child transcript path
     * @param evidenceTransactionId nonce-derived identity for this one transaction
     * @param ownerBootstrapFingerprint immutable owner bootstrap fingerprint
     * @param lockRawFingerprint exact empty lock-file fingerprint
     */
    public record Declaration(
            String publicationFingerprint,
            String publicationNonce,
            String transcriptRelativePath,
            String evidenceTransactionId,
            String ownerBootstrapFingerprint,
            String lockRawFingerprint) {
        /** Redacted representation. */
        @Override
        public String toString() {
            return "Declaration[material=REDACTED]";
        }
    }

    /** Typed payload-free provisioning or preflight failure. */
    public static final class PublicationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        /** Closed payload-free failure category. */
        private final EvidenceFailureKind failureKind;

        private PublicationException(EvidenceFailureKind failureKind) {
            super("evidence publication preflight failed");
            this.failureKind = failureKind;
        }

        /**
         * Returns the closed failure category.
         *
         * @return invalid or unavailable
         */
        public EvidenceFailureKind failureKind() {
            return failureKind;
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "PublicationException[kind=" + failureKind + "]";
        }
    }

    /**
     * Provisions the fixed owner bootstrap, lock, and declaration in one private parent.
     * Existing exact objects are verified; unknown objects are never changed.
     *
     * @param publicationParent absolute normalized owner-only directory
     * @param publicationNonce deployment-generated stable lowercase SHA-256 nonce
     * @return immutable declaration to pin out of band
     */
    public static Declaration provision(Path publicationParent, String publicationNonce) {
        requireFingerprint(publicationNonce);
        try {
            ParentIdentity parent = requireParent(publicationParent);
            Path bootstrapPath = child(publicationParent, OWNER_BOOTSTRAP_FILE);
            Path lockPath = child(publicationParent, PUBLICATION_LOCK_FILE);
            Path declarationPath = child(publicationParent, PUBLICATION_DECLARATION_FILE);
            boolean bootstrapExists = exists(bootstrapPath);
            boolean lockExists = exists(lockPath);
            boolean declarationExists = exists(declarationPath);
            boolean anyExists = bootstrapExists || lockExists || declarationExists;
            boolean allExist = bootstrapExists && lockExists && declarationExists;
            if (anyExists && !allExist) {
                throw invalid();
            }
            if (allExist) {
                DeclarationMaterial material = readDeclaration(declarationPath);
                Declaration verified = verifyExisting(publicationParent,
                        material.publicationFingerprint());
                if (!verified.publicationNonce().equals(publicationNonce)) {
                    throw invalid();
                }
                return verified;
            }
            Bootstrap bootstrap;
            bootstrap = Bootstrap.create(publicationNonce, parent.uid());
            createDocument(bootstrapPath, bootstrap.bytes());
            createLock(lockPath);
            DeclarationMaterial material = createDeclaration(declarationPath, bootstrapPath,
                    lockPath, publicationNonce, parent.uid());
            forceDirectory(publicationParent);
            Declaration verified = verifyExisting(publicationParent,
                    material.publicationFingerprint());
            if (!verified.publicationNonce().equals(publicationNonce)) {
                throw invalid();
            }
            return verified;
        } catch (PublicationException failure) {
            throw failure;
        } catch (FileAlreadyExistsException conflict) {
            throw invalid();
        } catch (IOException | SecurityException | UnsupportedOperationException unavailable) {
            throw unavailable();
        } catch (RuntimeException invalid) {
            throw invalid();
        }
    }

    /**
     * Verifies pre-existing provisioned objects without creating, repairing, chmodding, or forcing.
     *
     * @param publicationParent absolute normalized publication parent
     * @param expectedPublicationFingerprint deployment out-of-band pin
     * @return exact verified declaration
     */
    public static Declaration verifyExisting(
            Path publicationParent, String expectedPublicationFingerprint) {
        return verifyExisting(publicationParent, expectedPublicationFingerprint, true);
    }

    static Declaration verifyExistingWhileLocked(
            Path publicationParent, String expectedPublicationFingerprint) {
        return verifyExisting(publicationParent, expectedPublicationFingerprint, false);
    }

    private static Declaration verifyExisting(
            Path publicationParent,
            String expectedPublicationFingerprint,
            boolean readLockBytes) {
        requireFingerprint(expectedPublicationFingerprint);
        try {
            ParentIdentity parentBefore = requireParent(publicationParent);
            Path bootstrapPath = child(publicationParent, OWNER_BOOTSTRAP_FILE);
            Path lockPath = child(publicationParent, PUBLICATION_LOCK_FILE);
            Path declarationPath = child(publicationParent, PUBLICATION_DECLARATION_FILE);
            if (!exists(bootstrapPath) || !exists(lockPath) || !exists(declarationPath)) {
                throw unavailable();
            }
            StableFile bootstrapFile = requireStableFile(
                    bootstrapPath, 0400, MAXIMUM_DOCUMENT_BYTES);
            Bootstrap bootstrap = parseBootstrap(bootstrapFile.bytes(), parentBefore.uid());
            Identity lockIdentity;
            String lockRawFingerprint;
            if (readLockBytes) {
                StableFile lock = requireStableFile(lockPath, 0600, 0);
                lockIdentity = lock.identity();
                lockRawFingerprint = sha256(lock.bytes());
            } else {
                lockIdentity = identity(lockPath, 0600);
                if (lockIdentity.size() != 0) {
                    throw invalid();
                }
                lockRawFingerprint = sha256(new byte[0]);
            }
            StableFile declarationFile = requireStableFile(
                    declarationPath, 0400, MAXIMUM_DOCUMENT_BYTES);
            DeclarationMaterial material = parseDeclaration(declarationFile.bytes());
            requireMatchingUidForTesting(
                    material.ownerBootstrapUid(), bootstrapFile.identity().uid());
            requireMatchingUidForTesting(material.lockUid(), lockIdentity.uid());
            requireMatchingUidForTesting(
                    material.declarationUid(), declarationFile.identity().uid());
            if (!material.publicationFingerprint().equals(expectedPublicationFingerprint)
                    || !material.publicationNonce().equals(bootstrap.publicationNonce())
                    || material.ownerBootstrapMode() != bootstrapFile.identity().mode()
                    || material.ownerBootstrapLinkCount() != bootstrapFile.identity().links()
                    || !material.ownerBootstrapFingerprint().equals(
                    bootstrap.bootstrapFingerprint())
                    || !material.ownerBootstrapRawFingerprint().equals(
                    sha256(bootstrapFile.bytes()))
                    || !material.ownerBootstrapFileKeyFingerprint().equals(
                    fileKeyFingerprint(bootstrapFile.identity().fileKey()))
                    || material.lockMode() != lockIdentity.mode()
                    || material.lockLinkCount() != lockIdentity.links()
                    || !material.lockRawFingerprint().equals(lockRawFingerprint)
                    || !material.lockFileKeyFingerprint().equals(
                    fileKeyFingerprint(lockIdentity.fileKey()))
                    || material.declarationMode() != declarationFile.identity().mode()
                    || material.declarationLinkCount() != declarationFile.identity().links()
                    || !material.declarationFileKeyFingerprint().equals(
                    fileKeyFingerprint(declarationFile.identity().fileKey()))) {
                throw invalid();
            }
            if (!lockIdentity.equals(identity(lockPath, 0600))) {
                throw unavailable();
            }
            ParentIdentity parentAfter = requireParent(publicationParent);
            if (!parentBefore.equals(parentAfter)) {
                throw unavailable();
            }
            return new Declaration(material.publicationFingerprint(),
                    material.publicationNonce(), material.transcriptRelativePath(),
                    material.evidenceTransactionId(), material.ownerBootstrapFingerprint(),
                    material.lockRawFingerprint());
        } catch (PublicationException failure) {
            throw failure;
        } catch (IOException | SecurityException | UnsupportedOperationException unavailable) {
            throw unavailable();
        } catch (RuntimeException invalid) {
            throw invalid();
        }
    }

    static Declaration readExisting(Path publicationParent) {
        try {
            Path declarationPath = child(
                    publicationParent, PUBLICATION_DECLARATION_FILE);
            DeclarationMaterial material = readDeclaration(declarationPath);
            return verifyExisting(publicationParent, material.publicationFingerprint());
        } catch (PublicationException failure) {
            throw failure;
        } catch (IOException | RuntimeException unavailable) {
            throw unavailable();
        }
    }

    static String transactionNonce(Declaration declaration, String transactionId) {
        requireFingerprint(transactionId);
        String canonical = "{\"messageVersion\":\"resource-gateway.capability-studio."
                + "execution-lease-evidence-transaction-nonce.v1\","
                + "\"publicationFingerprint\":\"" + declaration.publicationFingerprint()
                + "\",\"publicationNonce\":\"" + declaration.publicationNonce()
                + "\",\"ownerBootstrapFingerprint\":\""
                + declaration.ownerBootstrapFingerprint()
                + "\",\"evidenceTransactionId\":\"" + transactionId + "\"}";
        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static String evidenceTransactionId(String publicationNonce) {
        ObjectNode node = JSON.createObjectNode();
        node.put("messageVersion", TRANSACTION_ID_VERSION);
        node.put("publicationNonce", publicationNonce);
        return sha256(json(node));
    }

    static void requireMatchingUidForTesting(long declaredUid, long observedUid) {
        if (declaredUid != observedUid) {
            throw invalid();
        }
    }

    private static DeclarationMaterial createDeclaration(
            Path declarationPath,
            Path bootstrapPath,
            Path lockPath,
            String nonce,
            long ownerUid) throws IOException {
        StableFile bootstrap = requireStableFile(
                bootstrapPath, 0400, MAXIMUM_DOCUMENT_BYTES);
        StableFile lock = requireStableFile(lockPath, 0600, 0);
        try (FileChannel channel = FileChannel.open(declarationPath,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")))) {
            Identity identity = identity(declarationPath, 0600);
            DeclarationMaterial material = DeclarationMaterial.create(nonce,
                    ownerUid, bootstrap, lock, identity);
            byte[] bytes = material.bytes();
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
            Files.setPosixFilePermissions(declarationPath,
                    PosixFilePermissions.fromString("r--------"));
            channel.force(true);
            return material;
        }
    }

    private static void createDocument(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")))) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
            Files.setPosixFilePermissions(path,
                    PosixFilePermissions.fromString("r--------"));
            channel.force(true);
        }
        forceDirectory(path.getParent());
    }

    private static void createLock(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")))) {
            channel.force(true);
        }
        forceDirectory(path.getParent());
    }

    private static Bootstrap readBootstrap(Path path, String nonce, long uid)
            throws IOException {
        StableFile file = requireStableFile(path, 0400, MAXIMUM_DOCUMENT_BYTES);
        Bootstrap bootstrap = parseBootstrap(file.bytes(), uid);
        if (!bootstrap.publicationNonce().equals(nonce)) {
            throw invalid();
        }
        return bootstrap;
    }

    private static Bootstrap parseBootstrap(byte[] bytes, long uid) throws IOException {
        try {
            ObjectNode node = object(JSON.readTree(bytes));
            fields(node, Set.of("messageVersion", "publicationNonce", "ownerUid",
                    "bootstrapFingerprint"));
            Bootstrap bootstrap = new Bootstrap(text(node, "publicationNonce"),
                    number(node, "ownerUid"), text(node, "bootstrapFingerprint"));
            if (!BOOTSTRAP_VERSION.equals(string(node, "messageVersion"))
                    || bootstrap.ownerUid() != uid
                    || !bootstrap.bootstrapFingerprint().equals(
                    Bootstrap.fingerprint(bootstrap.publicationNonce(), uid))
                    || !Arrays.equals(bytes, bootstrap.bytes())) {
                throw invalid();
            }
            return bootstrap;
        } catch (PublicationException failure) {
            throw failure;
        } catch (IOException | RuntimeException invalid) {
            throw invalid();
        }
    }

    private static DeclarationMaterial readDeclaration(Path path) throws IOException {
        return parseDeclaration(requireStableFile(
                path, 0400, MAXIMUM_DOCUMENT_BYTES).bytes());
    }

    private static DeclarationMaterial parseDeclaration(byte[] bytes) throws IOException {
        try {
            ObjectNode node = object(JSON.readTree(bytes));
            fields(node, Set.of("messageVersion", "publicationNonce",
                    "transcriptRelativePath", "evidenceTransactionId",
                    "ownerBootstrapFingerprint", "ownerBootstrapRawFingerprint",
                    "ownerBootstrapFileKeyFingerprint", "ownerBootstrapUid",
                    "ownerBootstrapMode", "ownerBootstrapLinkCount", "lockRawFingerprint",
                    "lockFileKeyFingerprint", "lockUid", "lockMode", "lockLinkCount",
                    "declarationFileKeyFingerprint", "declarationUid", "declarationMode",
                    "declarationLinkCount", "publicationFingerprint"));
            DeclarationMaterial material = new DeclarationMaterial(
                    text(node, "publicationNonce"),
                    string(node, "transcriptRelativePath"),
                    text(node, "evidenceTransactionId"),
                    text(node, "ownerBootstrapFingerprint"),
                    text(node, "ownerBootstrapRawFingerprint"),
                    text(node, "ownerBootstrapFileKeyFingerprint"),
                    number(node, "ownerBootstrapUid"), integer(node, "ownerBootstrapMode"),
                    number(node, "ownerBootstrapLinkCount"),
                    text(node, "lockRawFingerprint"),
                    text(node, "lockFileKeyFingerprint"), number(node, "lockUid"),
                    integer(node, "lockMode"), number(node, "lockLinkCount"),
                    text(node, "declarationFileKeyFingerprint"),
                    number(node, "declarationUid"), integer(node, "declarationMode"),
                    number(node, "declarationLinkCount"),
                    text(node, "publicationFingerprint"));
            if (!DECLARATION_VERSION.equals(string(node, "messageVersion"))
                    || !TRANSCRIPT_FILE.equals(material.transcriptRelativePath())
                    || !material.evidenceTransactionId().equals(
                    evidenceTransactionId(material.publicationNonce()))
                    || !material.publicationFingerprint().equals(material.fingerprint())
                    || !Arrays.equals(bytes, material.bytes())) {
                throw invalid();
            }
            return material;
        } catch (PublicationException failure) {
            throw failure;
        } catch (IOException | RuntimeException invalid) {
            throw invalid();
        }
    }

    private static StableFile requireStableFile(Path path, int mode, int maximum)
            throws IOException {
        Identity before = identity(path, mode);
        if (before.links() != 1 || before.size() < 0 || before.size() > maximum) {
            throw invalid();
        }
        byte[] bytes = Files.readAllBytes(path);
        Identity after = identity(path, mode);
        if (!before.equals(after) || bytes.length != before.size()) {
            throw unavailable();
        }
        return new StableFile(before, bytes);
    }

    private static Identity identity(Path path, int expectedMode) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw invalid();
        }
        if (attributes.fileKey() == null) {
            throw unavailable();
        }
        long links = numberAttribute(path, "unix:nlink");
        long uid = numberAttribute(path, "unix:uid");
        int mode = (int) numberAttribute(path, "unix:mode") & 0777;
        if (links != 1) {
            throw invalid();
        }
        if (mode != expectedMode) {
            throw invalid();
        }
        return new Identity(attributes.fileKey(), uid, mode, links,
                attributes.size(), attributes.lastModifiedTime());
    }

    private static ParentIdentity requireParent(Path parent) throws IOException {
        if (parent == null || !parent.isAbsolute() || !parent.equals(parent.normalize())) {
            throw invalid();
        }
        BasicFileAttributes attributes = Files.readAttributes(parent,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw invalid();
        }
        if (attributes.fileKey() == null) {
            throw unavailable();
        }
        long uid = numberAttribute(parent, "unix:uid");
        int mode = (int) numberAttribute(parent, "unix:mode") & 0777;
        if (mode != 0700) {
            throw invalid();
        }
        return new ParentIdentity(attributes.fileKey(), uid, mode,
                attributes.lastModifiedTime());
    }

    private static Path child(Path parent, String name) {
        Path child = parent.resolve(name).normalize();
        if (!parent.equals(child.getParent())) {
            throw invalid();
        }
        return child;
    }

    private static boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static long numberAttribute(Path path, String name) throws IOException {
        Object value = Files.getAttribute(path, name, LinkOption.NOFOLLOW_LINKS);
        if (!(value instanceof Number number)) {
            throw unavailable();
        }
        return number.longValue();
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }

    private static ObjectNode object(JsonNode node) {
        if (!(node instanceof ObjectNode object)) {
            throw invalid();
        }
        return object;
    }

    private static void fields(ObjectNode node, Set<String> expected) {
        java.util.HashSet<String> actual = new java.util.HashSet<>();
        Iterator<String> names = node.fieldNames();
        names.forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw invalid();
        }
    }

    private static String text(ObjectNode node, String name) {
        String text = string(node, name);
        requireFingerprint(text);
        return text;
    }

    private static String string(ObjectNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) {
            throw invalid();
        }
        return value.textValue();
    }

    private static long number(ObjectNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalid();
        }
        return value.longValue();
    }

    private static int integer(ObjectNode node, String name) {
        long value = number(node, name);
        if (value > Integer.MAX_VALUE) {
            throw invalid();
        }
        return (int) value;
    }

    private static void requireFingerprint(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw invalid();
        }
    }

    private static String fileKeyFingerprint(Object fileKey) {
        if (fileKey == null) {
            throw unavailable();
        }
        return sha256(String.valueOf(fileKey).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] json(ObjectNode node) {
        try {
            return JSON.writeValueAsBytes(node);
        } catch (IOException impossible) {
            throw new IllegalStateException("JSON serialization failed");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static PublicationException invalid() {
        return new PublicationException(EvidenceFailureKind.INVALID);
    }

    private static PublicationException unavailable() {
        return new PublicationException(EvidenceFailureKind.UNAVAILABLE);
    }

    private record Identity(
            Object fileKey, long uid, int mode, long links, long size,
            java.nio.file.attribute.FileTime modifiedTime) {
    }

    private record ParentIdentity(
            Object fileKey, long uid, int mode,
            java.nio.file.attribute.FileTime modifiedTime) {
    }

    private record StableFile(Identity identity, byte[] bytes) {
        private StableFile {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record Bootstrap(
            String publicationNonce, long ownerUid, String bootstrapFingerprint) {
        private static Bootstrap create(String nonce, long uid) {
            return new Bootstrap(nonce, uid, fingerprint(nonce, uid));
        }

        private static String fingerprint(String nonce, long uid) {
            ObjectNode node = JSON.createObjectNode();
            node.put("messageVersion", BOOTSTRAP_VERSION);
            node.put("publicationNonce", nonce);
            node.put("ownerUid", uid);
            node.putNull("bootstrapFingerprint");
            return sha256(json(node));
        }

        private byte[] bytes() {
            ObjectNode node = JSON.createObjectNode();
            node.put("messageVersion", BOOTSTRAP_VERSION);
            node.put("publicationNonce", publicationNonce);
            node.put("ownerUid", ownerUid);
            node.put("bootstrapFingerprint", bootstrapFingerprint);
            return json(node);
        }
    }

    private record DeclarationMaterial(
            String publicationNonce,
            String transcriptRelativePath,
            String evidenceTransactionId,
            String ownerBootstrapFingerprint,
            String ownerBootstrapRawFingerprint,
            String ownerBootstrapFileKeyFingerprint,
            long ownerBootstrapUid,
            int ownerBootstrapMode,
            long ownerBootstrapLinkCount,
            String lockRawFingerprint,
            String lockFileKeyFingerprint,
            long lockUid,
            int lockMode,
            long lockLinkCount,
            String declarationFileKeyFingerprint,
            long declarationUid,
            int declarationMode,
            long declarationLinkCount,
            String publicationFingerprint) {
        private static DeclarationMaterial create(
                String nonce,
                long uid,
                StableFile bootstrap,
                StableFile lock,
                Identity declaration) {
            DeclarationMaterial unhashed = new DeclarationMaterial(nonce,
                    TRANSCRIPT_FILE,
                    CapabilityStudioExecutionLeaseEvidencePublication
                            .evidenceTransactionId(nonce),
                    parseBootstrapUnchecked(bootstrap.bytes(), uid).bootstrapFingerprint(),
                    sha256(bootstrap.bytes()),
                    fileKeyFingerprint(bootstrap.identity().fileKey()),
                    bootstrap.identity().uid(), bootstrap.identity().mode(),
                    bootstrap.identity().links(), sha256(lock.bytes()),
                    fileKeyFingerprint(lock.identity().fileKey()), lock.identity().uid(),
                    lock.identity().mode(), lock.identity().links(),
                    fileKeyFingerprint(declaration.fileKey()), declaration.uid(), 0400, 1,
                    null);
            return unhashed.withFingerprint(unhashed.fingerprint());
        }

        private DeclarationMaterial withFingerprint(String fingerprint) {
            return new DeclarationMaterial(publicationNonce, transcriptRelativePath,
                    evidenceTransactionId, ownerBootstrapFingerprint,
                    ownerBootstrapRawFingerprint, ownerBootstrapFileKeyFingerprint,
                    ownerBootstrapUid, ownerBootstrapMode, ownerBootstrapLinkCount,
                    lockRawFingerprint, lockFileKeyFingerprint, lockUid, lockMode,
                    lockLinkCount, declarationFileKeyFingerprint, declarationUid,
                    declarationMode, declarationLinkCount, fingerprint);
        }

        private String fingerprint() {
            return sha256(bytesWithFingerprint(null));
        }

        private byte[] bytes() {
            return bytesWithFingerprint(publicationFingerprint);
        }

        private byte[] bytesWithFingerprint(String fingerprint) {
            ObjectNode node = JSON.createObjectNode();
            node.put("messageVersion", DECLARATION_VERSION);
            node.put("publicationNonce", publicationNonce);
            node.put("transcriptRelativePath", transcriptRelativePath);
            node.put("evidenceTransactionId", evidenceTransactionId);
            node.put("ownerBootstrapFingerprint", ownerBootstrapFingerprint);
            node.put("ownerBootstrapRawFingerprint", ownerBootstrapRawFingerprint);
            node.put("ownerBootstrapFileKeyFingerprint", ownerBootstrapFileKeyFingerprint);
            node.put("ownerBootstrapUid", ownerBootstrapUid);
            node.put("ownerBootstrapMode", ownerBootstrapMode);
            node.put("ownerBootstrapLinkCount", ownerBootstrapLinkCount);
            node.put("lockRawFingerprint", lockRawFingerprint);
            node.put("lockFileKeyFingerprint", lockFileKeyFingerprint);
            node.put("lockUid", lockUid);
            node.put("lockMode", lockMode);
            node.put("lockLinkCount", lockLinkCount);
            node.put("declarationFileKeyFingerprint", declarationFileKeyFingerprint);
            node.put("declarationUid", declarationUid);
            node.put("declarationMode", declarationMode);
            node.put("declarationLinkCount", declarationLinkCount);
            if (fingerprint == null) {
                node.putNull("publicationFingerprint");
            } else {
                node.put("publicationFingerprint", fingerprint);
            }
            return json(node);
        }
    }

    private static Bootstrap parseBootstrapUnchecked(byte[] bytes, long uid) {
        try {
            return parseBootstrap(bytes, uid);
        } catch (IOException impossible) {
            throw unavailable();
        }
    }
}
