package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable M-of-N Ed25519 policy for control-plane certificate rotation events.
 *
 * <p>Only public verification keys and accepted policy fingerprints are configured. Enterprise
 * deployments may replace this adapter with dynamic IAM, JWKS, or KMS-backed trust while retaining
 * the same fail-closed interface and protocol.</p>
 */
public final class ConfiguredControlPlaneCertificateRotationTrustStore
        implements ControlPlaneCertificateRotationTrustStore {

    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Duration MAXIMUM_EVENT_LIFETIME = Duration.ofDays(7);
    private static final int MAXIMUM_AUTHORITIES = 32;
    private static final int MAXIMUM_KEYS = 64;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /**
     * One externally provisioned public rotation key.
     *
     * @param authorityId stable certificate-rotation authority
     * @param keyId rotation-aware key identity within that authority
     * @param publicKey Ed25519 public verification key
     * @param notBefore inclusive key activation time
     * @param expiresAt exclusive key expiry time
     * @param enabled administrative enablement flag
     * @param revoked compromise or withdrawal flag
     */
    public record AuthorityKey(
            String authorityId,
            String keyId,
            PublicKey publicKey,
            Instant notBefore,
            Instant expiresAt,
            boolean enabled,
            boolean revoked) {

        /** Validates bounded identity, Ed25519 key type, and lifecycle ordering. */
        public AuthorityKey {
            authorityId = normalized(authorityId);
            keyId = normalized(keyId);
            notBefore = notBefore == null ? Instant.MIN : notBefore;
            expiresAt = expiresAt == null ? Instant.MAX : expiresAt;
            String algorithm = publicKey == null ? "" : publicKey.getAlgorithm();
            if (!IDENTIFIER.matcher(authorityId).matches()
                    || !IDENTIFIER.matcher(keyId).matches() || publicKey == null
                    || !(algorithm.equalsIgnoreCase("EdDSA")
                    || algorithm.equalsIgnoreCase("Ed25519"))
                    || !expiresAt.isAfter(notBefore)) {
                throw invalid("Control-plane certificate rotation authority key is invalid");
            }
        }

        /** @return true when this key may authorize a signature at the supplied time */
        public boolean activeAt(Instant signedAt) {
            return enabled && !revoked && signedAt != null
                    && !signedAt.isBefore(notBefore) && signedAt.isBefore(expiresAt);
        }

        private String indexKey() {
            return authorityId + '\u0000' + keyId;
        }
    }

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String trustDomain;
    private final Set<String> acceptedPolicies;
    private final int signatureThreshold;
    private final Map<String, AuthorityKey> keys;
    private final int authorityCount;

    /**
     * Creates a static deployment-owned rotation trust policy.
     *
     * @param objectMapper canonical JSON mapper
     * @param trustDomain expected external rotation trust domain
     * @param acceptedPolicyFingerprints exact accepted policy revisions
     * @param signatureThreshold required distinct authority signatures
     * @param authorityKeys public Ed25519 verification keys
     */
    public ConfiguredControlPlaneCertificateRotationTrustStore(
            ObjectMapper objectMapper,
            String trustDomain,
            Set<String> acceptedPolicyFingerprints,
            int signatureThreshold,
            List<AuthorityKey> authorityKeys) {
        this(objectMapper, Clock.systemUTC(), trustDomain, acceptedPolicyFingerprints,
                signatureThreshold, authorityKeys);
    }

    /**
     * Creates a static deployment-owned rotation trust policy with an explicit health clock.
     *
     * @param objectMapper canonical JSON mapper
     * @param clock authoritative key-lifecycle observation clock
     * @param trustDomain expected external rotation trust domain
     * @param acceptedPolicyFingerprints exact accepted policy revisions
     * @param signatureThreshold required distinct authority signatures
     * @param authorityKeys public Ed25519 verification keys
     */
    public ConfiguredControlPlaneCertificateRotationTrustStore(
            ObjectMapper objectMapper,
            Clock clock,
            String trustDomain,
            Set<String> acceptedPolicyFingerprints,
            int signatureThreshold,
            List<AuthorityKey> authorityKeys) {
        this.objectMapper = objectMapper == null
                ? new ObjectMapper().findAndRegisterModules() : objectMapper;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.trustDomain = normalized(trustDomain);
        if (!IDENTIFIER.matcher(this.trustDomain).matches()) {
            throw invalid("Control-plane certificate rotation trust domain is invalid");
        }
        Set<String> policies = new HashSet<>();
        for (String policy : acceptedPolicyFingerprints == null
                ? Set.<String>of() : acceptedPolicyFingerprints) {
            String normalized = normalized(policy);
            if (!FINGERPRINT.matcher(normalized).matches() || !policies.add(normalized)) {
                throw invalid("Accepted control-plane certificate rotation policy is invalid");
            }
        }
        if (policies.isEmpty() || policies.size() > 32) {
            throw invalid("One through 32 certificate rotation policies are required");
        }
        this.acceptedPolicies = Set.copyOf(policies);

        LinkedHashMap<String, AuthorityKey> indexed = new LinkedHashMap<>();
        Set<String> authorities = new HashSet<>();
        for (AuthorityKey key : authorityKeys == null ? List.<AuthorityKey>of() : authorityKeys) {
            if (key == null || indexed.putIfAbsent(key.indexKey(), key) != null) {
                throw invalid("Control-plane certificate rotation authority keys must be unique");
            }
            authorities.add(key.authorityId());
        }
        if (indexed.isEmpty() || indexed.size() > MAXIMUM_KEYS
                || authorities.size() > MAXIMUM_AUTHORITIES
                || signatureThreshold < 1 || signatureThreshold > authorities.size()) {
            throw invalid("Control-plane certificate rotation trust policy is invalid");
        }
        this.keys = Map.copyOf(indexed);
        this.authorityCount = authorities.size();
        this.signatureThreshold = signatureThreshold;
    }

    /** {@inheritDoc} */
    @Override
    public Verification verify(
            ControlPlaneCertificateRotationEvent event,
            ExpectedBinding expected,
            Instant observedAt) {
        if (event == null || expected == null || observedAt == null) {
            return rejected(VerificationStatus.MATERIAL_INVALID,
                    "CERTIFICATE_ROTATION_MATERIAL_INVALID", 0);
        }
        ControlPlaneCertificateRotationEvent.Material material = event.material();
        if (!trustDomain.equals(material.trustDomain())
                || !expected.deploymentScopeId().equals(material.deploymentScopeId())
                || !expected.targetId().equals(material.targetId())) {
            return rejected(VerificationStatus.BINDING_MISMATCH,
                    "CERTIFICATE_ROTATION_BINDING_MISMATCH", 0);
        }
        if (!acceptedPolicies.contains(material.policyFingerprint())) {
            return rejected(VerificationStatus.POLICY_REJECTED,
                    "CERTIFICATE_ROTATION_POLICY_REJECTED", 0);
        }
        if (!validTime(material, event.signatures(), observedAt)) {
            return rejected(VerificationStatus.TIME_INVALID,
                    "CERTIFICATE_ROTATION_TIME_INVALID", 0);
        }
        if (!event.fingerprintVerified(objectMapper)) {
            return rejected(VerificationStatus.MATERIAL_INVALID,
                    "CERTIFICATE_ROTATION_MATERIAL_INVALID", 0);
        }

        int valid = 0;
        Set<String> countedAuthorities = new HashSet<>();
        for (ControlPlaneCertificateRotationEvent.AuthoritySignature supplied
                : event.signatures()) {
            AuthorityKey key = keys.get(supplied.authorityId() + '\u0000' + supplied.keyId());
            if (key == null || !key.activeAt(supplied.signedAt())) {
                continue;
            }
            try {
                if (!verifySignature(key.publicKey(), event.materialFingerprint(),
                        supplied.signature())
                        || !countedAuthorities.add(supplied.authorityId())) {
                    return rejected(VerificationStatus.SIGNATURE_INVALID,
                            "CERTIFICATE_ROTATION_SIGNATURE_INVALID", valid);
                }
                valid++;
            } catch (GeneralSecurityException | IllegalArgumentException invalid) {
                return rejected(VerificationStatus.SIGNATURE_INVALID,
                        "CERTIFICATE_ROTATION_SIGNATURE_INVALID", valid);
            }
        }
        if (valid < signatureThreshold) {
            return rejected(VerificationStatus.QUORUM_NOT_MET,
                    "CERTIFICATE_ROTATION_QUORUM_NOT_MET", valid);
        }
        return new Verification(VerificationStatus.VERIFIED, "VERIFIED",
                material.eventId(), event.materialFingerprint(), material.settingsFingerprint(),
                valid, signatureThreshold);
    }

    /** {@inheritDoc} */
    @Override
    public Descriptor descriptor() {
        Instant now = clock.instant();
        long activeAuthorities = keys.values().stream()
                .filter(key -> key.activeAt(now))
                .map(AuthorityKey::authorityId)
                .distinct()
                .count();
        return new Descriptor(Descriptor.SCHEMA_VERSION,
                activeAuthorities >= signatureThreshold, trustDomain, authorityCount,
                keys.size(), signatureThreshold, acceptedPolicies.size(), Map.of(
                "algorithm", "Ed25519",
                "sourceType", "STATIC_EXTERNAL",
                "privateMaterialPresent", false,
                "activeAuthorityCount", activeAuthorities,
                "maximumEventLifetimeSeconds", MAXIMUM_EVENT_LIFETIME.toSeconds()));
    }

    /**
     * Parses bounded JSON configuration containing public keys only.
     *
     * @param objectMapper JSON decoder and canonical mapper
     * @param trustDomain expected external trust domain
     * @param acceptedPolicies comma-separated exact policy fingerprints
     * @param signatureThreshold required distinct authority signatures
     * @param authorityKeysJson bounded JSON array of public authority keys
     * @return immutable configured trust store
     */
    public static ConfiguredControlPlaneCertificateRotationTrustStore fromJson(
            ObjectMapper objectMapper,
            String trustDomain,
            String acceptedPolicies,
            int signatureThreshold,
            String authorityKeysJson) {
        return fromJson(objectMapper, Clock.systemUTC(), trustDomain, acceptedPolicies,
                signatureThreshold, authorityKeysJson);
    }

    /**
     * Parses bounded public-key JSON with an explicit lifecycle observation clock.
     *
     * @param objectMapper JSON decoder and canonical mapper
     * @param clock authoritative key-lifecycle observation clock
     * @param trustDomain expected external trust domain
     * @param acceptedPolicies comma-separated exact policy fingerprints
     * @param signatureThreshold required distinct authority signatures
     * @param authorityKeysJson bounded JSON array of public authority keys
     * @return immutable configured trust store
     */
    public static ConfiguredControlPlaneCertificateRotationTrustStore fromJson(
            ObjectMapper objectMapper,
            Clock clock,
            String trustDomain,
            String acceptedPolicies,
            int signatureThreshold,
            String authorityKeysJson) {
        try {
            ObjectMapper mapper = objectMapper == null
                    ? new ObjectMapper().findAndRegisterModules() : objectMapper;
            JsonNode root = mapper.readTree(normalized(authorityKeysJson));
            if (root == null || !root.isArray() || root.isEmpty()
                    || root.size() > MAXIMUM_KEYS) {
                throw invalid("Certificate rotation authority key JSON is invalid");
            }
            List<AuthorityKey> keys = new ArrayList<>();
            for (JsonNode node : root) {
                if (!node.isObject() || node.size() != 7) {
                    throw invalid("Certificate rotation authority key JSON is invalid");
                }
                Set<String> names = new HashSet<>();
                node.fieldNames().forEachRemaining(names::add);
                if (!names.equals(Set.of("authorityId", "keyId", "publicKeyBase64",
                        "notBefore", "expiresAt", "enabled", "revoked"))) {
                    throw invalid("Certificate rotation authority key JSON is invalid");
                }
                keys.add(new AuthorityKey(text(node, "authorityId"), text(node, "keyId"),
                        publicKey(text(node, "publicKeyBase64")),
                        Instant.parse(text(node, "notBefore")),
                        Instant.parse(text(node, "expiresAt")),
                        bool(node, "enabled"), bool(node, "revoked")));
            }
            return new ConfiguredControlPlaneCertificateRotationTrustStore(
                    mapper, clock, trustDomain, fingerprints(acceptedPolicies),
                    signatureThreshold, keys);
        } catch (RuntimeException | java.io.IOException invalid) {
            throw new IllegalArgumentException(
                    "Control-plane certificate rotation trust configuration is invalid",
                    invalid);
        }
    }

    private Verification rejected(
            VerificationStatus status, String reasonCode, int validCount) {
        return new Verification(status, reasonCode, "", "", "", validCount,
                signatureThreshold);
    }

    private static boolean validTime(
            ControlPlaneCertificateRotationEvent.Material material,
            List<ControlPlaneCertificateRotationEvent.AuthoritySignature> signatures,
            Instant observedAt) {
        Instant latestAcceptedFuture = observedAt.plus(CLOCK_SKEW);
        if (material.issuedAt().isAfter(latestAcceptedFuture)
                || observedAt.isBefore(material.notBefore())
                || !observedAt.isBefore(material.expiresAt())
                || Duration.between(material.issuedAt(), material.expiresAt())
                .compareTo(MAXIMUM_EVENT_LIFETIME) > 0) {
            return false;
        }
        Instant earliestSignature = material.issuedAt().minus(CLOCK_SKEW);
        for (ControlPlaneCertificateRotationEvent.AuthoritySignature signature : signatures) {
            if (signature.signedAt().isBefore(earliestSignature)
                    || signature.signedAt().isAfter(latestAcceptedFuture)
                    || !signature.signedAt().isBefore(material.expiresAt())) {
                return false;
            }
        }
        return true;
    }

    private static boolean verifySignature(
            PublicKey publicKey, String fingerprint, String encoded)
            throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(fingerprint.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(encoded));
    }

    private static PublicKey publicKey(String encoded) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length < 32 || bytes.length > 128) {
                throw invalid("Certificate rotation public key is invalid");
            }
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(bytes));
        } catch (GeneralSecurityException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Certificate rotation public key is invalid",
                    invalid);
        }
    }

    private static Set<String> fingerprints(String configured) {
        Set<String> values = new HashSet<>();
        for (String value : normalized(configured).split(",", -1)) {
            String normalized = normalized(value);
            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }
        return values;
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid("Certificate rotation authority key JSON is invalid");
        }
        return value.textValue();
    }

    private static boolean bool(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalid("Certificate rotation authority key JSON is invalid");
        }
        return value.booleanValue();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
