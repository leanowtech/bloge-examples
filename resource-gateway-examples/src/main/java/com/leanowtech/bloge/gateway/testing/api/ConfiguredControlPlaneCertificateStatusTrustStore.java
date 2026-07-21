package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable M-of-N Ed25519 policy for normalized certificate-status publications.
 *
 * <p>Only public verification keys and accepted normalization-policy fingerprints are held here.
 * Enterprise deployments may replace this static adapter with dynamic IAM, JWKS, or KMS-backed
 * trust while preserving the same fail-closed verification contract.</p>
 */
public final class ConfiguredControlPlaneCertificateStatusTrustStore
        implements ControlPlaneCertificateStatusTrustStore {

    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Duration MAXIMUM_PUBLICATION_LIFETIME = Duration.ofHours(24);
    private static final Duration MAXIMUM_EVIDENCE_AGE = Duration.ofDays(7);
    private static final int MAXIMUM_AUTHORITIES = 32;
    private static final int MAXIMUM_KEYS = 64;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /**
     * One externally provisioned certificate-status verification key.
     *
     * @param authorityId stable independent authority identity
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
                throw invalid();
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
     * Creates a static deployment-owned certificate-status trust policy.
     *
     * @param objectMapper canonical JSON mapper
     * @param trustDomain expected external status trust domain
     * @param acceptedPolicyFingerprints exact accepted normalization-policy revisions
     * @param signatureThreshold required distinct authority signatures
     * @param authorityKeys public Ed25519 verification keys
     */
    public ConfiguredControlPlaneCertificateStatusTrustStore(
            ObjectMapper objectMapper,
            String trustDomain,
            Set<String> acceptedPolicyFingerprints,
            int signatureThreshold,
            List<AuthorityKey> authorityKeys) {
        this(objectMapper, Clock.systemUTC(), trustDomain, acceptedPolicyFingerprints,
                signatureThreshold, authorityKeys);
    }

    /**
     * Creates a static trust policy with an explicit lifecycle observation clock.
     *
     * @param objectMapper canonical JSON mapper
     * @param clock authoritative key-lifecycle observation clock
     * @param trustDomain expected external status trust domain
     * @param acceptedPolicyFingerprints exact accepted normalization-policy revisions
     * @param signatureThreshold required distinct authority signatures
     * @param authorityKeys public Ed25519 verification keys
     */
    public ConfiguredControlPlaneCertificateStatusTrustStore(
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
            throw invalid();
        }
        Set<String> policies = new HashSet<>();
        for (String policy : acceptedPolicyFingerprints == null
                ? Set.<String>of() : acceptedPolicyFingerprints) {
            String normalized = normalized(policy);
            if (!FINGERPRINT.matcher(normalized).matches() || !policies.add(normalized)) {
                throw invalid();
            }
        }
        if (policies.isEmpty() || policies.size() > 32) {
            throw invalid();
        }
        acceptedPolicies = Set.copyOf(policies);

        LinkedHashMap<String, AuthorityKey> indexed = new LinkedHashMap<>();
        Set<String> authorities = new HashSet<>();
        for (AuthorityKey key : authorityKeys == null ? List.<AuthorityKey>of() : authorityKeys) {
            if (key == null || indexed.putIfAbsent(key.indexKey(), key) != null) {
                throw invalid();
            }
            authorities.add(key.authorityId());
        }
        if (indexed.isEmpty() || indexed.size() > MAXIMUM_KEYS
                || authorities.size() > MAXIMUM_AUTHORITIES
                || signatureThreshold < 1 || signatureThreshold > authorities.size()) {
            throw invalid();
        }
        keys = Map.copyOf(indexed);
        authorityCount = authorities.size();
        this.signatureThreshold = signatureThreshold;
    }

    /** {@inheritDoc} */
    @Override
    public Verification verify(
            ControlPlaneCertificateStatusPublication publication,
            ExpectedBinding expected,
            Instant observedAt) {
        if (publication == null || expected == null || observedAt == null) {
            return rejected(VerificationStatus.MATERIAL_INVALID,
                    "CERTIFICATE_STATUS_MATERIAL_INVALID", 0);
        }
        ControlPlaneCertificateStatusPublication.Material material = publication.material();
        if (!trustDomain.equals(material.trustDomain())
                || !expected.deploymentScopeId().equals(material.deploymentScopeId())) {
            return rejected(VerificationStatus.BINDING_MISMATCH,
                    "CERTIFICATE_STATUS_BINDING_MISMATCH", 0);
        }
        if (!acceptedPolicies.contains(material.policyFingerprint())) {
            return rejected(VerificationStatus.POLICY_REJECTED,
                    "CERTIFICATE_STATUS_POLICY_REJECTED", 0);
        }
        if (!validTime(material, publication.signatures(), observedAt)) {
            return rejected(VerificationStatus.TIME_INVALID,
                    "CERTIFICATE_STATUS_TIME_INVALID", 0);
        }
        if (!publication.fingerprintVerified(objectMapper)) {
            return rejected(VerificationStatus.MATERIAL_INVALID,
                    "CERTIFICATE_STATUS_MATERIAL_INVALID", 0);
        }

        int valid = 0;
        Set<String> countedAuthorities = new HashSet<>();
        for (ControlPlaneCertificateStatusPublication.AuthoritySignature supplied
                : publication.signatures()) {
            AuthorityKey key = keys.get(supplied.authorityId() + '\u0000' + supplied.keyId());
            if (key == null || !key.activeAt(supplied.signedAt())) {
                continue;
            }
            try {
                if (!verifySignature(key.publicKey(), publication.materialFingerprint(),
                        supplied.signature())
                        || !countedAuthorities.add(supplied.authorityId())) {
                    return rejected(VerificationStatus.SIGNATURE_INVALID,
                            "CERTIFICATE_STATUS_SIGNATURE_INVALID", valid);
                }
                valid++;
            } catch (GeneralSecurityException | IllegalArgumentException invalid) {
                return rejected(VerificationStatus.SIGNATURE_INVALID,
                        "CERTIFICATE_STATUS_SIGNATURE_INVALID", valid);
            }
        }
        if (valid < signatureThreshold) {
            return rejected(VerificationStatus.QUORUM_NOT_MET,
                    "CERTIFICATE_STATUS_QUORUM_NOT_MET", valid);
        }
        return new Verification(VerificationStatus.VERIFIED, "VERIFIED",
                material.publicationId(), publication.materialFingerprint(), material.sequence(),
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
                "maximumPublicationLifetimeSeconds", MAXIMUM_PUBLICATION_LIFETIME.toSeconds(),
                "maximumEvidenceAgeSeconds", MAXIMUM_EVIDENCE_AGE.toSeconds()));
    }

    private Verification rejected(
            VerificationStatus status, String reasonCode, int validCount) {
        return new Verification(status, reasonCode, "", "", 0, validCount,
                signatureThreshold);
    }

    private static boolean validTime(
            ControlPlaneCertificateStatusPublication.Material material,
            List<ControlPlaneCertificateStatusPublication.AuthoritySignature> signatures,
            Instant observedAt) {
        Instant latestAcceptedFuture = observedAt.plus(CLOCK_SKEW);
        if (material.issuedAt().isAfter(latestAcceptedFuture)
                || !observedAt.isBefore(material.expiresAt())
                || Duration.between(material.issuedAt(), material.expiresAt())
                .compareTo(MAXIMUM_PUBLICATION_LIFETIME) > 0) {
            return false;
        }
        for (ControlPlaneCertificateStatusPublication.TargetStatus target :
                material.targets()) {
            for (ControlPlaneCertificateStatusPublication.CertificateEvidence evidence :
                    target.certificates()) {
                if (evidence.thisUpdate().isAfter(latestAcceptedFuture)
                        || evidence.nextUpdate().isBefore(material.expiresAt())
                        || Duration.between(evidence.thisUpdate(), material.issuedAt())
                        .compareTo(MAXIMUM_EVIDENCE_AGE) > 0) {
                    return false;
                }
            }
        }
        Instant earliestSignature = material.issuedAt().minus(CLOCK_SKEW);
        for (ControlPlaneCertificateStatusPublication.AuthoritySignature signature : signatures) {
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

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "Control-plane certificate status trust configuration is invalid");
    }

}
