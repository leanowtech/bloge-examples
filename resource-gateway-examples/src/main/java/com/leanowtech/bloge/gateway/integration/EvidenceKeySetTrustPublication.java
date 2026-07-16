package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One externally authorized append-only publication of accepted evidence key-set fingerprints.
 *
 * <p>Governance authorities sign {@link #publicationFingerprint()} with keys configured outside
 * Resource Gateway. Authority public keys are intentionally absent from this value, so a producer
 * cannot bootstrap trust by returning a new key next to its own signature.</p>
 *
 * @param schemaVersion publication protocol version
 * @param publicationFingerprint canonical fingerprint of {@link #material()}
 * @param trustDomain organizational trust-policy identity
 * @param logId append-only log identity within the trust domain
 * @param sequence contiguous one-based publication sequence
 * @param previousPublicationFingerprint preceding publication fingerprint, empty only at sequence one
 * @param recoveryEpoch monotonic compromised-pin recovery generation
 * @param publishedAt governance authorization time
 * @param expiresAt exclusive publication policy freshness deadline
 * @param pins bounded accepted and explicitly revoked key-set fingerprints
 * @param signatures detached M-of-N governance authority signatures
 */
public record EvidenceKeySetTrustPublication(
        String schemaVersion,
        String publicationFingerprint,
        String trustDomain,
        String logId,
        long sequence,
        String previousPublicationFingerprint,
        long recoveryEpoch,
        Instant publishedAt,
        Instant expiresAt,
        List<SnapshotPin> pins,
        List<AuthoritySignature> signatures
) {
    /** Current publication protocol version. */
    public static final String SCHEMA_VERSION =
            "toolStudio.resourceGateway.evidenceKeySetTrustPublication.v1";
    /** Maximum pin records retained in one publication. */
    public static final int MAX_PINS = 32;
    /** Maximum detached authority signatures accepted in one publication. */
    public static final int MAX_SIGNATURES = 32;
    /** Maximum canonical material size used for fingerprinting. */
    public static final int MAX_CANONICAL_BYTES = 256 * 1024;
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern MACHINE_CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    /** Pin disposition in the latest authorized policy. */
    public enum PinState {
        /** Exact key-set snapshot expected from the current evidence authority generation. */
        ACTIVE,
        /** Older or staged snapshot temporarily accepted during a controlled rotation window. */
        OVERLAP,
        /** Snapshot explicitly denied after compromise or governance withdrawal. */
        REVOKED
    }

    /**
     * Governed status of one exact evidence key-set snapshot.
     *
     * @param snapshotFingerprint key-set canonical material fingerprint
     * @param state active, overlap, or revoked policy
     * @param validFrom inclusive acceptance start
     * @param validUntil exclusive acceptance end; null means no scheduled end
     * @param revokedAt revocation declaration time, required only for revoked pins
     * @param reasonCode machine-readable revocation reason, empty for accepted pins
     */
    public record SnapshotPin(
            String snapshotFingerprint,
            PinState state,
            Instant validFrom,
            Instant validUntil,
            Instant revokedAt,
            String reasonCode
    ) {
        /** Normalizes and validates one pin without accepting free-form diagnostics. */
        public SnapshotPin {
            snapshotFingerprint = normalized(snapshotFingerprint);
            reasonCode = normalized(reasonCode).toUpperCase(Locale.ROOT);
            if (!fingerprint(snapshotFingerprint) || state == null || validFrom == null
                    || (validUntil != null && !validUntil.isAfter(validFrom))) {
                throw new IllegalArgumentException("Evidence key-set trust pin is invalid");
            }
            boolean revoked = state == PinState.REVOKED;
            if ((revoked && (revokedAt == null || reasonCode.isBlank()
                    || !MACHINE_CODE.matcher(reasonCode).matches()))
                    || (!revoked && (revokedAt != null || !reasonCode.isBlank()))) {
                throw new IllegalArgumentException("Evidence key-set trust pin state is invalid");
            }
        }

        /**
         * Tests whether this pin authorizes a snapshot at a policy observation time.
         *
         * @param observedAt policy observation time
         * @return true only for active/overlap pins inside their validity window
         */
        public boolean acceptedAt(Instant observedAt) {
            return state != PinState.REVOKED && observedAt != null
                    && !observedAt.isBefore(validFrom)
                    && (validUntil == null || observedAt.isBefore(validUntil));
        }
    }

    /**
     * Detached governance signature over the publication fingerprint.
     *
     * @param authorityId externally configured authority key identifier
     * @param algorithm signature algorithm, fixed to Ed25519 in v1
     * @param signature base64 detached signature bytes
     */
    public record AuthoritySignature(String authorityId, String algorithm, String signature) {
        /** Normalizes bounded public signature metadata. */
        public AuthoritySignature {
            authorityId = normalized(authorityId);
            algorithm = normalized(algorithm);
            signature = normalized(signature);
            if (authorityId.isBlank() || authorityId.length() > 255 || containsControl(authorityId)
                    || !"Ed25519".equals(algorithm) || signature.isBlank()
                    || signature.length() > 4096) {
                throw new IllegalArgumentException("Evidence trust authority signature is invalid");
            }
        }
    }

    /** Canonical governance material signed by every authority. */
    public record Material(
            String schemaVersion,
            String trustDomain,
            String logId,
            long sequence,
            String previousPublicationFingerprint,
            long recoveryEpoch,
            Instant publishedAt,
            Instant expiresAt,
            List<SnapshotPin> pins
    ) {
        /** Applies immutable canonical collection semantics. */
        public Material {
            schemaVersion = normalized(schemaVersion).isBlank() ? SCHEMA_VERSION : normalized(schemaVersion);
            trustDomain = normalized(trustDomain);
            logId = normalized(logId);
            previousPublicationFingerprint = normalized(previousPublicationFingerprint);
            pins = pins == null ? List.of() : pins.stream()
                    .sorted(Comparator.comparing(SnapshotPin::snapshotFingerprint)).toList();
        }
    }

    /** Normalizes ordering and validates publication-local invariants. */
    public EvidenceKeySetTrustPublication {
        schemaVersion = normalized(schemaVersion).isBlank() ? SCHEMA_VERSION : normalized(schemaVersion);
        publicationFingerprint = normalized(publicationFingerprint);
        trustDomain = normalized(trustDomain);
        logId = normalized(logId);
        previousPublicationFingerprint = normalized(previousPublicationFingerprint);
        pins = pins == null ? List.of() : pins.stream()
                .sorted(Comparator.comparing(SnapshotPin::snapshotFingerprint)).toList();
        signatures = signatures == null ? List.of() : signatures.stream()
                .sorted(Comparator.comparing(AuthoritySignature::authorityId)).toList();
        requireLocalInvariants(schemaVersion, publicationFingerprint, trustDomain, logId, sequence,
                previousPublicationFingerprint, recoveryEpoch, publishedAt, expiresAt, pins, signatures);
    }

    /**
     * Returns exact canonical material represented by this publication.
     *
     * @return immutable fingerprint and signature material
     */
    public Material material() {
        return new Material(schemaVersion, trustDomain, logId, sequence,
                previousPublicationFingerprint, recoveryEpoch, publishedAt, expiresAt, pins);
    }

    /**
     * Computes the canonical material fingerprint used by governance signers.
     *
     * @param mapper canonical JSON baseline
     * @return exact SHA-256 fingerprint of {@link #material()}
     */
    public String computedFingerprint(ObjectMapper mapper) {
        return fingerprint(mapper, material());
    }

    /**
     * Computes the protocol fingerprint before detached authority signatures are assembled.
     *
     * @param mapper canonical JSON baseline
     * @param material complete unsigned publication material
     * @return exact SHA-256 material fingerprint
     */
    public static String fingerprint(ObjectMapper mapper, Material material) {
        return VisualBundleFingerprint.fromCanonicalValue(
                Objects.requireNonNull(mapper, "mapper"),
                Objects.requireNonNull(material, "material"), MAX_CANONICAL_BYTES);
    }

    /**
     * Checks the publication's claimed fingerprint against its complete canonical material.
     *
     * @param mapper canonical JSON baseline
     * @return true when material and claimed identity are equal
     */
    public boolean fingerprintVerified(ObjectMapper mapper) {
        return publicationFingerprint.equals(computedFingerprint(mapper));
    }

    private static void requireLocalInvariants(
            String schemaVersion, String publicationFingerprint, String trustDomain, String logId,
            long sequence, String previousPublicationFingerprint, long recoveryEpoch,
            Instant publishedAt, Instant expiresAt, List<SnapshotPin> pins,
            List<AuthoritySignature> signatures) {
        if (!SCHEMA_VERSION.equals(schemaVersion) || !fingerprint(publicationFingerprint)
                || trustDomain.isBlank() || trustDomain.length() > 255 || containsControl(trustDomain)
                || logId.isBlank() || logId.length() > 255 || containsControl(logId)
                || sequence < 1 || recoveryEpoch < 0 || publishedAt == null || expiresAt == null
                || !expiresAt.isAfter(publishedAt) || pins.isEmpty() || pins.size() > MAX_PINS
                || signatures.isEmpty() || signatures.size() > MAX_SIGNATURES
                || (sequence == 1 && !previousPublicationFingerprint.isBlank())
                || (sequence > 1 && !fingerprint(previousPublicationFingerprint))) {
            throw new IllegalArgumentException("Evidence key-set trust publication is invalid");
        }
        Set<String> pinFingerprints = new HashSet<>();
        Set<String> authorities = new HashSet<>();
        long activePins = 0;
        for (SnapshotPin pin : pins) {
            if (!pinFingerprints.add(pin.snapshotFingerprint())
                    || pin.validFrom().isAfter(publishedAt)
                    || (pin.state() == PinState.REVOKED && pin.revokedAt().isAfter(publishedAt))) {
                throw new IllegalArgumentException("Evidence key-set trust pin policy is inconsistent");
            }
            if (pin.state() == PinState.ACTIVE) {
                activePins++;
                if (!pin.acceptedAt(publishedAt)) {
                    throw new IllegalArgumentException("Active evidence key-set pin is not currently valid");
                }
            }
        }
        for (AuthoritySignature signature : signatures) {
            if (!authorities.add(signature.authorityId())) {
                throw new IllegalArgumentException("Evidence trust authority signatures must be unique");
            }
        }
        if (activePins != 1) {
            throw new IllegalArgumentException("Exactly one active evidence key-set pin is required");
        }
    }

    private static boolean fingerprint(String value) {
        return FINGERPRINT.matcher(normalized(value)).matches();
    }

    private static boolean containsControl(String value) {
        return normalized(value).chars().anyMatch(Character::isISOControl);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
