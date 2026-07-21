package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Signed, payload-free certificate-status snapshot for every governed control-plane target.
 *
 * <p>An enterprise adapter validates native CA events, OCSP responses, or CRLs before producing
 * this normalized publication. The publication binds status to the exact deployment, target,
 * certificate generation, and TLS-settings fingerprint. A contiguous cursor and predecessor
 * fingerprint prevent rollback or fork, while {@code expiresAt} provides a hard freshness bound.
 * Raw certificates, responder URLs, credentials, and revocation payloads are deliberately absent.
 * </p>
 *
 * @param schemaVersion publication envelope protocol version
 * @param material immutable signed status snapshot
 * @param materialFingerprint canonical SHA-256 fingerprint of {@code material}
 * @param signatures distinct external-authority signatures over the material fingerprint
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ControlPlaneCertificateStatusPublication(
        String schemaVersion,
        Material material,
        String materialFingerprint,
        List<AuthoritySignature> signatures) {

    /** Current certificate-status publication protocol version. */
    public static final String SCHEMA_VERSION =
            "bloge.controlPlaneCertificateStatusPublication.v1";

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates bounded envelope structure independently from deployment trust. */
    public ControlPlaneCertificateStatusPublication {
        schemaVersion = normalized(schemaVersion);
        material = Objects.requireNonNull(material, "material");
        materialFingerprint = normalized(materialFingerprint);
        List<AuthoritySignature> supplied = Objects.requireNonNull(signatures, "signatures");
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !fingerprint(materialFingerprint)
                || supplied.isEmpty() || supplied.size() > 32
                || supplied.stream().anyMatch(Objects::isNull)) {
            throw invalid();
        }
        Set<String> authorities = new HashSet<>();
        if (supplied.stream().anyMatch(signature ->
                !authorities.add(signature.authorityId()))) {
            throw invalid();
        }
        signatures = List.copyOf(supplied);
    }

    /**
     * Recomputes the canonical signed-material fingerprint.
     *
     * @param objectMapper canonical JSON baseline
     * @return whether the supplied fingerprint exactly identifies {@link #material()}
     */
    public boolean fingerprintVerified(ObjectMapper objectMapper) {
        return materialFingerprint.equals(ProtocolFingerprint.of(objectMapper, material));
    }

    /** Certificate role whose status was verified by an external authority. */
    public enum CertificateRole {
        /** Resource Gateway client workload identity. */
        CLIENT,
        /** Remote control-plane server identity. */
        SERVER
    }

    /** Closed status vocabulary normalized from enterprise CA, OCSP, or CRL evidence. */
    public enum CertificateStatus {
        /** Evidence explicitly confirms that the certificate is currently usable. */
        GOOD,
        /** Evidence confirms that the certificate has been revoked. */
        REVOKED,
        /** The authority cannot establish a usable status; request admission must fail closed. */
        UNKNOWN
    }

    /** Native evidence family validated by the external normalization adapter. */
    public enum EvidenceType {
        /** Ordered certificate-authority lifecycle event. */
        CA_EVENT,
        /** Signed Online Certificate Status Protocol response. */
        OCSP,
        /** Signed certificate revocation list. */
        CRL
    }

    /**
     * Immutable complete status snapshot signed by independent external authorities.
     *
     * @param schemaVersion signed-material protocol version
     * @param trustDomain deployment-owned certificate-status trust domain
     * @param publicationId immutable external publication identity
     * @param deploymentScopeId exact Resource Gateway deployment scope
     * @param sequence contiguous publication cursor beginning at one
     * @param previousPublicationFingerprint predecessor material fingerprint, empty at sequence one
     * @param policyFingerprint exact accepted normalization and freshness policy revision
     * @param issuedAt publication issuance time
     * @param expiresAt exclusive hard freshness deadline
     * @param targets complete, target-id-sorted governed target status inventory
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Material(
            String schemaVersion,
            String trustDomain,
            String publicationId,
            String deploymentScopeId,
            long sequence,
            String previousPublicationFingerprint,
            String policyFingerprint,
            Instant issuedAt,
            Instant expiresAt,
            List<TargetStatus> targets) {

        /** Current signed certificate-status material protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateStatusPublicationMaterial.v1";

        /** Validates the complete, canonical, bounded snapshot shape. */
        public Material {
            schemaVersion = normalized(schemaVersion);
            trustDomain = normalized(trustDomain);
            publicationId = normalized(publicationId);
            deploymentScopeId = normalized(deploymentScopeId);
            previousPublicationFingerprint = normalized(previousPublicationFingerprint);
            policyFingerprint = normalized(policyFingerprint);
            issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
            List<TargetStatus> supplied = Objects.requireNonNull(targets, "targets");
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !identifier(trustDomain) || !identifier(publicationId)
                    || !identifier(deploymentScopeId) || sequence < 1
                    || (sequence == 1 && !previousPublicationFingerprint.isBlank())
                    || (sequence > 1 && !fingerprint(previousPublicationFingerprint))
                    || !fingerprint(policyFingerprint)
                    || !databasePrecision(issuedAt) || !databasePrecision(expiresAt)
                    || !expiresAt.isAfter(issuedAt)
                    || supplied.isEmpty() || supplied.size() > 128
                    || supplied.stream().anyMatch(Objects::isNull)) {
                throw invalid();
            }
            String previousTarget = "";
            for (TargetStatus target : supplied) {
                if (target.targetId().compareTo(previousTarget) <= 0
                        || expiresAt.isAfter(target.minimumEvidenceExpiry())) {
                    throw invalid();
                }
                previousTarget = target.targetId();
            }
            targets = List.copyOf(supplied);
        }
    }

    /**
     * Status for one exact TLS-settings generation.
     *
     * @param targetId stable control-plane transport target
     * @param generation positive certificate generation
     * @param settingsFingerprint exact TLS settings fingerprint
     * @param certificates canonical two-element list ordered CLIENT then SERVER
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record TargetStatus(
            String targetId,
            long generation,
            String settingsFingerprint,
            List<CertificateEvidence> certificates) {

        /** Validates exact target binding and complete client/server evidence. */
        public TargetStatus {
            targetId = normalized(targetId);
            settingsFingerprint = normalized(settingsFingerprint);
            List<CertificateEvidence> supplied = Objects.requireNonNull(
                    certificates, "certificates");
            if (!identifier(targetId) || generation < 1 || !fingerprint(settingsFingerprint)
                    || supplied.size() != 2 || supplied.get(0) == null
                    || supplied.get(1) == null
                    || supplied.get(0).role() != CertificateRole.CLIENT
                    || supplied.get(1).role() != CertificateRole.SERVER) {
                throw invalid();
            }
            certificates = List.copyOf(supplied);
        }

        /** @return true only when both workload identities have fresh explicit GOOD evidence */
        public boolean admitted() {
            return certificates.stream().allMatch(evidence ->
                    evidence.status() == CertificateStatus.GOOD);
        }

        private Instant minimumEvidenceExpiry() {
            Instant first = certificates.get(0).nextUpdate();
            Instant second = certificates.get(1).nextUpdate();
            return first.isBefore(second) ? first : second;
        }
    }

    /**
     * Payload-free identity and freshness commitment for one certificate.
     *
     * @param role client or server workload role
     * @param status normalized closed certificate status
     * @param evidenceType externally validated native evidence family
     * @param certificateFingerprint SHA-256 fingerprint of the exact DER certificate
     * @param issuerSpkiFingerprint SHA-256 fingerprint of the issuing CA public key
     * @param evidenceFingerprint SHA-256 fingerprint of the validated native evidence
     * @param reasonCode stable status or revocation reason
     * @param effectiveAt time at which this status became effective
     * @param thisUpdate time represented by the native evidence
     * @param nextUpdate exclusive native-evidence freshness deadline
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CertificateEvidence(
            CertificateRole role,
            CertificateStatus status,
            EvidenceType evidenceType,
            String certificateFingerprint,
            String issuerSpkiFingerprint,
            String evidenceFingerprint,
            String reasonCode,
            Instant effectiveAt,
            Instant thisUpdate,
            Instant nextUpdate) {

        private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

        /** Validates complete identity, source commitment, and monotonic evidence times. */
        public CertificateEvidence {
            role = Objects.requireNonNull(role, "role");
            status = Objects.requireNonNull(status, "status");
            evidenceType = Objects.requireNonNull(evidenceType, "evidenceType");
            certificateFingerprint = normalized(certificateFingerprint);
            issuerSpkiFingerprint = normalized(issuerSpkiFingerprint);
            evidenceFingerprint = normalized(evidenceFingerprint);
            reasonCode = normalized(reasonCode);
            effectiveAt = Objects.requireNonNull(effectiveAt, "effectiveAt");
            thisUpdate = Objects.requireNonNull(thisUpdate, "thisUpdate");
            nextUpdate = Objects.requireNonNull(nextUpdate, "nextUpdate");
            if (!fingerprint(certificateFingerprint) || !fingerprint(issuerSpkiFingerprint)
                    || !fingerprint(evidenceFingerprint) || !REASON.matcher(reasonCode).matches()
                    || !databasePrecision(effectiveAt) || !databasePrecision(thisUpdate)
                    || !databasePrecision(nextUpdate) || effectiveAt.isAfter(thisUpdate)
                    || !nextUpdate.isAfter(thisUpdate)) {
                throw invalid();
            }
        }
    }

    /**
     * One detached Ed25519 signature from an independent certificate-status authority.
     *
     * @param authorityId stable external authority identity
     * @param keyId rotation-aware verification-key identity
     * @param algorithm signature algorithm, fixed to Ed25519
     * @param signedAt signature creation time
     * @param signature base64-encoded 64-byte signature
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AuthoritySignature(
            String authorityId,
            String keyId,
            String algorithm,
            Instant signedAt,
            String signature) {

        /** Validates bounded signer identity and canonical Ed25519 encoding. */
        public AuthoritySignature {
            authorityId = normalized(authorityId);
            keyId = normalized(keyId);
            algorithm = normalized(algorithm);
            signedAt = Objects.requireNonNull(signedAt, "signedAt");
            signature = normalized(signature);
            if (!identifier(authorityId) || !identifier(keyId) || !"Ed25519".equals(algorithm)
                    || !databasePrecision(signedAt) || !validSignature(signature)) {
                throw invalid();
            }
        }

        private static boolean validSignature(String encoded) {
            if (encoded.isBlank() || encoded.length() > 128) {
                return false;
            }
            try {
                return Base64.getDecoder().decode(encoded).length == 64;
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }
    }

    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    }

    private static boolean fingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }

    private static boolean databasePrecision(Instant value) {
        return value.getNano() % 1_000 == 0;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "Control-plane certificate status publication is invalid");
    }
}
