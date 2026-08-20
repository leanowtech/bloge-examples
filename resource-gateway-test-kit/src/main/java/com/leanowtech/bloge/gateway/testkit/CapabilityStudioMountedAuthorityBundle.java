package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceKind;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ReferenceKind;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolutionRequest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.channels.SeekableByteChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Loads one immutable, fail-closed enterprise authority bundle from a deployment directory.
 *
 * <p>The loader is deliberately a filesystem boundary, not a trust authority. It validates the
 * signed key-set snapshots and delegates proof validation to the existing authority classes. All
 * referenced JSON is read and defensively copied before this method returns, so later changes to
 * the deployment directory do not change the resolver or policies exposed by this bundle.</p>
 */
public final class CapabilityStudioMountedAuthorityBundle {
    /** Fixed manifest filename for the v1 mounted bundle format. */
    public static final String MANIFEST_FILE = "authority-bundle-v1.json";

    private static final String CODE = "RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE.";
    private static final String SCHEMA_VERSION =
            "resource-gateway.capability-studio.mounted-authority-bundle.v1";
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_ARTIFACT_BYTES = 64 * 1024;
    private static final int MAX_KEY_SET_BYTES = 1024 * 1024;
    private static final long MAX_TOTAL_REFERENCED_BYTES = 32L * 1024 * 1024;
    private static final Pattern SAFE_JSON_FILE = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{0,122}\\.json");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final ObjectMapper JSON = new ObjectMapper(new JsonFactory().rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());

    private static final String EXPIRED_CODE = "AUTHORITY_BUNDLE_EXPIRED";

    private final EvidenceResolver evidenceResolver;
    private final EvidenceIssuerPolicy evidenceIssuerPolicy;
    private final OwnerAuthority ownerAuthority;
    private final String bundleFingerprint;
    private final Instant generatedAt;
    private final Instant expiresAt;
    private final Clock clock;

    private CapabilityStudioMountedAuthorityBundle(
            EvidenceResolver evidenceResolver,
            EvidenceIssuerPolicy evidenceIssuerPolicy,
            OwnerAuthority ownerAuthority,
            String bundleFingerprint,
            Instant generatedAt,
            Instant expiresAt,
            Clock clock) {
        this.evidenceResolver = evidenceResolver;
        this.evidenceIssuerPolicy = evidenceIssuerPolicy;
        this.ownerAuthority = ownerAuthority;
        this.bundleFingerprint = bundleFingerprint;
        this.generatedAt = generatedAt;
        this.expiresAt = expiresAt;
        this.clock = clock;
    }

    /**
     * Loads, validates, and snapshots a mounted authority bundle.
     *
     * @param root deployment directory containing the manifest and direct-child JSON artifacts
     * @param clock trusted clock used by the existing authority implementations
     * @return immutable mounted bundle
     * @throws IllegalArgumentException for absent arguments
     * @throws IllegalStateException with a stable {@code RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE.*}
     *         code for every invalid or unavailable bundle
     */
    public static CapabilityStudioMountedAuthorityBundle load(Path root, Clock clock) {
        if (root == null || clock == null) {
            throw failure("INPUT_INVALID");
        }
        try {
            Path mountedRoot = root.toAbsolutePath().normalize();
            RootIdentity rootIdentity = requireRoot(mountedRoot);
            byte[] manifestBytes = readBounded(
                    mountedRoot, directChild(mountedRoot, MANIFEST_FILE), MAX_MANIFEST_BYTES,
                    "MANIFEST", rootIdentity);
            ObjectNode manifest = object(parseSingle(manifestBytes), "MANIFEST_INVALID");
            requireSchema(manifest);
            Instant generatedAt = instant(manifest, "generatedAt");
            Instant expiresAt = instant(manifest, "expiresAt");
            Instant now = clock.instant();
            if (generatedAt.isAfter(now)) {
                throw failure("MANIFEST_NOT_YET_VALID");
            }
            if (!expiresAt.isAfter(generatedAt) || !expiresAt.isAfter(now)) {
                throw failure("MANIFEST_EXPIRED");
            }
            String expectedBundleFingerprint = text(manifest, "bundleFingerprint");
            if (!FINGERPRINT.matcher(expectedBundleFingerprint).matches()) {
                throw failure("MANIFEST_FINGERPRINT_INVALID");
            }
            ObjectNode fingerprintInput = manifest.deepCopy();
            fingerprintInput.putNull("bundleFingerprint");
            if (!expectedBundleFingerprint.equals(EvidenceVerificationSupport.sha256(fingerprintInput))) {
                throw failure("MANIFEST_FINGERPRINT_INVALID");
            }

            List<JsonNode> artifacts = orderedArray(manifest, "artifacts", "ARTIFACT_ORDER");
            List<JsonNode> issuerPolicies = orderedArray(
                    manifest, "issuerPolicies", "ISSUER_POLICY_ORDER");
            List<JsonNode> ownerPolicies = orderedArray(
                    manifest, "ownerPolicies", "OWNER_POLICY_ORDER");
            Map<String, FileBinding> fileBindings = new LinkedHashMap<>();
            Map<String, byte[]> files = new LinkedHashMap<>();
            long totalBytes = 0;

            Map<ResolutionRequest, JsonNode> artifactSnapshots = new LinkedHashMap<>();
            for (JsonNode artifact : artifacts) {
                String file = text(artifact, "artifactFile");
                String expectedFileFingerprint = text(artifact, "artifactFileFingerprint");
                registerFile(fileBindings, file, expectedFileFingerprint, FileKind.ARTIFACT);
                byte[] bytes = files.get(file);
                if (bytes == null) {
                    bytes = readReferenced(
                            mountedRoot, file, MAX_ARTIFACT_BYTES, "ARTIFACT", rootIdentity);
                    files.put(file, bytes.clone());
                    totalBytes = addBytes(totalBytes, bytes.length);
                    checkFingerprint(bytes, expectedFileFingerprint, "ARTIFACT_FILE");
                }
                ResolutionRequest request = request(artifact);
                if (artifactSnapshots.putIfAbsent(request, artifactSnapshot(bytes)) != null) {
                    throw failure("BINDING_DUPLICATE");
                }
            }

            Map<String, EvidenceVerificationKeySet> keySets = new HashMap<>();
            for (JsonNode policy : issuerPolicies) {
                String file = text(policy, "keySetFile");
                String pin = text(policy, "pinnedKeySetFingerprint");
                registerFile(fileBindings, file, pin, FileKind.KEY_SET);
                byte[] bytes = files.get(file);
                if (bytes == null) {
                    bytes = readReferenced(
                            mountedRoot, file, MAX_KEY_SET_BYTES, "KEY_SET", rootIdentity);
                    files.put(file, bytes.clone());
                    totalBytes = addBytes(totalBytes, bytes.length);
                }
                EvidenceVerificationKeySet keySet = keySet(keySets, file, bytes);
                if (!pin.equals(keySet.snapshotFingerprint())) {
                    throw failure("KEY_SET_PIN_INVALID");
                }
            }
            for (JsonNode policy : ownerPolicies) {
                String file = text(policy, "keySetFile");
                String pin = text(policy, "pinnedKeySetFingerprint");
                registerFile(fileBindings, file, pin, FileKind.KEY_SET);
                byte[] bytes = files.get(file);
                if (bytes == null) {
                    bytes = readReferenced(
                            mountedRoot, file, MAX_KEY_SET_BYTES, "KEY_SET", rootIdentity);
                    files.put(file, bytes.clone());
                    totalBytes = addBytes(totalBytes, bytes.length);
                }
                EvidenceVerificationKeySet keySet = keySet(keySets, file, bytes);
                if (!pin.equals(keySet.snapshotFingerprint())) {
                    throw failure("KEY_SET_PIN_INVALID");
                }
            }
            if (totalBytes > MAX_TOTAL_REFERENCED_BYTES) {
                throw failure("TOTAL_SIZE_LIMIT");
            }
            ensureRootStable(mountedRoot, rootIdentity);

            CapabilityStudioAuthorityEvidenceResolver resolver =
                    new CapabilityStudioAuthorityEvidenceResolver(request -> {
                        JsonNode snapshot = artifactSnapshots.get(request);
                        return snapshot == null
                                ? CapabilityStudioAuthorityEvidenceResolver.ArtifactRead.notFound()
                                : CapabilityStudioAuthorityEvidenceResolver.ArtifactRead.available(snapshot);
                    });
            for (Map.Entry<ResolutionRequest, JsonNode> artifact : artifactSnapshots.entrySet()) {
                var resolution = resolver.resolve(artifact.getKey());
                if (resolution.status()
                        != CapabilityStudioStageAcceptanceAuthorityVerifier.ResolutionStatus.AVAILABLE) {
                    throw failure("ARTIFACT_INVALID");
                }
                boolean signatureKind = resolution.evidence().evidenceKind() == EvidenceKind.OWNER_SIGNATURE;
                if ((artifact.getKey().kind() == ReferenceKind.SIGNATURE) != signatureKind) {
                    throw failure("ARTIFACT_CATEGORY_INVALID");
                }
            }

            List<CapabilityStudioPinnedEvidenceIssuerPolicy.TrustedIssuer> issuers =
                    issuerPolicies.stream().map(policy -> issuer(policy, keySets)).toList();
            List<CapabilityStudioPinnedOwnerAuthority.TrustedOwnerRole> owners =
                    ownerPolicies.stream().map(policy -> owner(policy, keySets)).toList();
            CapabilityStudioPinnedEvidenceIssuerPolicy issuerPolicy =
                    new CapabilityStudioPinnedEvidenceIssuerPolicy(clock, issuers);
            CapabilityStudioPinnedOwnerAuthority ownerAuthority =
                    new CapabilityStudioPinnedOwnerAuthority(clock, owners);
            EvidenceResolver expiringResolver = request -> {
                if (!usable(generatedAt, expiresAt, clock)) {
                    return CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolution.unavailable();
                }
                return resolver.resolve(request);
            };
            EvidenceIssuerPolicy expiringIssuerPolicy = (reference, evidence, context) -> {
                if (!usable(generatedAt, expiresAt, clock)) {
                    return CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision
                            .rejected(EXPIRED_CODE);
                }
                return issuerPolicy.verify(reference, evidence, context);
            };
            OwnerAuthority expiringOwnerAuthority = (signoff, signature, context) -> {
                if (!usable(generatedAt, expiresAt, clock)) {
                    return CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision
                            .rejected(EXPIRED_CODE);
                }
                return ownerAuthority.verify(signoff, signature, context);
            };
            return new CapabilityStudioMountedAuthorityBundle(
                    expiringResolver,
                    expiringIssuerPolicy,
                    expiringOwnerAuthority,
                    expectedBundleFingerprint,
                    generatedAt,
                    expiresAt,
                    clock);
        } catch (IllegalStateException failure) {
            if (failure.getMessage() != null && failure.getMessage().startsWith(CODE)) {
                throw failure;
            }
            throw failure("LOAD_FAILED");
        } catch (RuntimeException | IOException failure) {
            throw failure("LOAD_FAILED");
        }
    }

    /**
     * Returns the exact-coordinate resolver backed by the immutable mounted snapshot.
     *
     * @return immutable exact-coordinate evidence resolver
     */
    public EvidenceResolver evidenceResolver() {
        return evidenceResolver;
    }

    /**
     * Returns the issuer policy assembled from the pinned public key-set snapshots.
     *
     * @return immutable pinned evidence issuer policy
     */
    public EvidenceIssuerPolicy evidenceIssuerPolicy() {
        return evidenceIssuerPolicy;
    }

    /**
     * Returns the Owner authority assembled from explicit role and Actor bindings.
     *
     * @return immutable pinned Owner authority
     */
    public OwnerAuthority ownerAuthority() {
        return ownerAuthority;
    }

    /**
     * Returns the verified fingerprint of the complete mounted Bundle Manifest.
     *
     * @return manifest's verified Bundle fingerprint
     */
    public String bundleFingerprint() {
        return bundleFingerprint;
    }

    /** Returns only non-sensitive bundle metadata. */
    @Override
    public String toString() {
        return "CapabilityStudioMountedAuthorityBundle[bundleFingerprint=REDACTED, authorityMaterial=REDACTED]";
    }

    private static RootIdentity requireRoot(Path root) {
        RootIdentity identity = rootIdentity(root);
        if (identity == null) {
            throw failure("ROOT_INVALID");
        }
        return identity;
    }

    private static Path directChild(Path root, String name) {
        Path child = root.resolve(name).normalize();
        if (!root.equals(child.getParent()) || !MANIFEST_FILE.equals(name)) {
            throw failure("PATH_INVALID");
        }
        return child;
    }

    private static byte[] readReferenced(
            Path root, String name, int maximumBytes, String kind, RootIdentity rootIdentity)
            throws IOException {
        if (!SAFE_JSON_FILE.matcher(name).matches()) {
            throw failure("PATH_INVALID");
        }
        Path file = root.resolve(name).normalize();
        if (!root.equals(file.getParent()) || Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("FILE_INVALID");
        }
        return readBounded(root, file, maximumBytes, kind, rootIdentity);
    }

    private static byte[] readBounded(
            Path root, Path file, int maximumBytes, String kind, RootIdentity rootIdentity)
            throws IOException {
        ensureRootStable(root, rootIdentity);
        BasicFileAttributes before = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.isSymbolicLink() || !before.isRegularFile() || before.fileKey() == null
                || before.size() > maximumBytes) {
            throw failure(kind + "_SIZE_OR_TYPE_INVALID");
        }
        byte[] bytes = new byte[maximumBytes + 1];
        int count = 0;
        try (SeekableByteChannel channel = Files.newByteChannel(
                file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    throw failure(kind + "_READ_INCOMPLETE");
                }
                count += read;
                if (count > maximumBytes) {
                    throw failure(kind + "_SIZE_LIMIT");
                }
            }
        }
        BasicFileAttributes after = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        ensureRootStable(root, rootIdentity);
        if (after.isSymbolicLink() || !after.isRegularFile() || after.size() > maximumBytes
                || after.size() != count
                || after.fileKey() == null
                || !before.fileKey().equals(after.fileKey())) {
            throw failure(kind + "_CHANGED_DURING_READ");
        }
        return java.util.Arrays.copyOf(bytes, count);
    }

    private static JsonNode parseSingle(byte[] bytes) throws IOException {
        try (JsonParser parser = JSON.getFactory().createParser(bytes)) {
            try {
                JsonNode value = JSON.readTree(parser);
                if (value == null || parser.nextToken() != null) {
                    throw failure("JSON_INVALID");
                }
                return value.deepCopy();
            } catch (com.fasterxml.jackson.core.JsonProcessingException duplicateOrInvalid) {
                throw failure("JSON_INVALID");
            }
        }
    }

    private static RootIdentity rootIdentity(Path root) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || !attributes.isDirectory()
                    || attributes.fileKey() == null) {
                return null;
            }
            return new RootIdentity(attributes.fileKey());
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private static void ensureRootStable(Path root, RootIdentity expected) {
        RootIdentity actual = rootIdentity(root);
        if (actual == null) {
            throw failure("ROOT_IDENTITY_UNSTABLE");
        }
        if (!expected.fileKey().equals(actual.fileKey())) {
            throw failure("ROOT_CHANGED_DURING_LOAD");
        }
    }

    private static boolean usable(Instant generatedAt, Instant expiresAt, Clock clock) {
        try {
            Instant now = clock.instant();
            return !now.isBefore(generatedAt) && now.isBefore(expiresAt);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static JsonNode artifactSnapshot(byte[] bytes) {
        try {
            return parseSingle(bytes);
        } catch (IOException | RuntimeException failure) {
            throw failure("ARTIFACT_INVALID");
        }
    }

    private static ObjectNode object(JsonNode value, String code) {
        if (value == null || !value.isObject()) {
            throw failure(code);
        }
        return (ObjectNode) value;
    }

    private static void requireSchema(ObjectNode manifest) {
        if (!SCHEMA_VERSION.equals(manifest.path("schemaVersion").asText())
                || !CapabilityStudioSchemaSupport.validate(
                manifest, CapabilityStudioSchemaSupport.MOUNTED_AUTHORITY_BUNDLE_V1_RESOURCE)
                .isEmpty()) {
            throw failure("MANIFEST_SCHEMA_INVALID");
        }
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).textValue();
        if (value == null || value.isBlank()) {
            throw failure("FIELD_INVALID");
        }
        return value;
    }

    private static Instant instant(JsonNode node, String field) {
        try {
            return Instant.parse(text(node, field));
        } catch (DateTimeParseException failure) {
            throw failure("TIME_INVALID");
        }
    }

    private static List<JsonNode> orderedArray(ObjectNode manifest, String field, String code) {
        List<JsonNode> values = new ArrayList<>();
        manifest.withArray(field).forEach(value -> values.add(value.deepCopy()));
        String previous = null;
        for (JsonNode value : values) {
            String current = sortKey(field, value);
            if (previous != null && previous.compareTo(current) >= 0) {
                throw failure(code);
            }
            previous = current;
        }
        return List.copyOf(values);
    }

    private static String sortKey(String field, JsonNode value) {
        return switch (field) {
            case "artifacts" -> String.join("\u0000", text(value, "referenceKind"),
                    text(value, "referenceKey"), text(value, "exactRef"),
                    text(value, "fingerprint"), text(value, "artifactFile"));
            case "issuerPolicies" -> String.join("\u0000", text(value, "issuerRef"),
                    text(value, "scope"));
            case "ownerPolicies" -> text(value, "role");
            default -> throw failure("FIELD_INVALID");
        };
    }

    private static ResolutionRequest request(JsonNode artifact) {
        try {
            return new ResolutionRequest(ReferenceKind.valueOf(text(artifact, "referenceKind")),
                    text(artifact, "referenceKey"),
                    new CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate(
                            text(artifact, "exactRef"), text(artifact, "fingerprint")));
        } catch (RuntimeException failure) {
            throw failure("ARTIFACT_BINDING_INVALID");
        }
    }

    private static EvidenceVerificationKeySet keySet(
            Map<String, EvidenceVerificationKeySet> keySets, String file, byte[] bytes) {
        EvidenceVerificationKeySet existing = keySets.get(file);
        if (existing != null) {
            return existing;
        }
        try {
            EvidenceVerificationKeySet decoded = EvidenceVerificationKeySet.fromPayload(
                    object(parseSingle(bytes), "KEY_SET_INVALID"));
            keySets.put(file, decoded);
            return decoded;
        } catch (IOException | RuntimeException failure) {
            throw failure("KEY_SET_INVALID");
        }
    }

    private static CapabilityStudioPinnedEvidenceIssuerPolicy.TrustedIssuer issuer(
            JsonNode policy, Map<String, EvidenceVerificationKeySet> keySets) {
        EvidenceVerificationKeySet keySet = keySets.get(text(policy, "keySetFile"));
        Set<EvidenceKind> allowed = EnumSet.noneOf(EvidenceKind.class);
        policy.path("allowedEvidenceKinds").forEach(value -> allowed.add(
                EvidenceKind.valueOf(value.asText())));
        return new CapabilityStudioPinnedEvidenceIssuerPolicy.TrustedIssuer(
                text(policy, "issuerRef"), text(policy, "scope"), allowed,
                text(policy, "pinnedKeySetFingerprint"), keySet,
                Duration.ofSeconds(policy.path("maxProofTtlSeconds").longValue()));
    }

    private static CapabilityStudioPinnedOwnerAuthority.TrustedOwnerRole owner(
            JsonNode policy, Map<String, EvidenceVerificationKeySet> keySets) {
        EvidenceVerificationKeySet keySet = keySets.get(text(policy, "keySetFile"));
        Set<String> actors = new HashSet<>();
        policy.path("allowedActorRefs").forEach(value -> actors.add(value.asText()));
        return new CapabilityStudioPinnedOwnerAuthority.TrustedOwnerRole(
                text(policy, "role"), actors, text(policy, "signatureIssuerRef"),
                text(policy, "scope"), text(policy, "pinnedKeySetFingerprint"), keySet,
                Duration.ofSeconds(policy.path("maxSignatureTtlSeconds").longValue()));
    }

    private static void registerFile(
            Map<String, FileBinding> bindings, String file, String fingerprint, FileKind kind) {
        if (!SAFE_JSON_FILE.matcher(file).matches() || !FINGERPRINT.matcher(fingerprint).matches()) {
            throw failure("FILE_BINDING_INVALID");
        }
        FileBinding prior = bindings.putIfAbsent(file, new FileBinding(fingerprint, kind));
        if (prior != null && (!prior.fingerprint.equals(fingerprint)
                || (prior.kind == FileKind.KEY_SET && kind != FileKind.KEY_SET)
                || (prior.kind == FileKind.ARTIFACT && kind != FileKind.ARTIFACT))) {
            throw failure("DUPLICATE_FILE_BINDING");
        }
    }

    private static long addBytes(long total, long bytes) {
        if (total > MAX_TOTAL_REFERENCED_BYTES - bytes) {
            throw failure("TOTAL_SIZE_LIMIT");
        }
        return total + bytes;
    }

    private static void checkFingerprint(byte[] bytes, String expected, String code) {
        String actual;
        try {
            actual = "sha256:" + java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw failure("HASH_UNAVAILABLE");
        }
        if (!actual.equals(expected)) {
            throw failure(code + "_FINGERPRINT_MISMATCH");
        }
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(CODE + code);
    }

    private enum FileKind { ARTIFACT, KEY_SET }

    private record FileBinding(String fingerprint, FileKind kind) { }

    private record RootIdentity(Object fileKey) { }
}
