package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Detached signature attached to one read-only Shadow authority publication.
 *
 * <p>The signature covers a domain-separated canonical material fingerprint rather than transport
 * JSON bytes. Consumers must still verify the complete publication fingerprint, issuer, key
 * lifecycle, scope, current stream head, and validity window before trusting the decision.</p>
 *
 * @param materialFingerprint canonical fingerprint signed by the authority
 * @param algorithm fixed signature algorithm
 * @param keyId exact authority verification key
 * @param signedAt authority signing time
 * @param signature canonical base64 detached signature
 */
public record ReadOnlyShadowAuthoritySeal(
        String materialFingerprint,
        String algorithm,
        String keyId,
        Instant signedAt,
        String signature
) {
    static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}");

    /** Validates bounded detached-signature syntax without claiming authority trust. */
    public ReadOnlyShadowAuthoritySeal {
        materialFingerprint = fingerprint(
                materialFingerprint, "materialFingerprint");
        algorithm = required(algorithm, "algorithm", 32);
        keyId = identifier(keyId, "keyId");
        signedAt = time(signedAt, "signedAt");
        signature = canonicalBase64(
                signature, "signature", 4_096);
        if (!"Ed25519".equals(algorithm)) {
            throw new IllegalArgumentException(
                    "read-only Shadow authority signatures require Ed25519");
        }
    }

    static CapabilitySnapshot.Scope scope(
            CapabilitySnapshot.Scope value,
            String field) {
        CapabilitySnapshot.Scope exact =
                Objects.requireNonNull(value, field);
        identifier(exact.tenantId(), field + ".tenantId");
        identifier(
                exact.organizationId(),
                field + ".organizationId");
        optionalIdentifier(
                exact.projectId(), field + ".projectId");
        identifier(
                exact.environmentId(),
                field + ".environmentId");
        optionalIdentifier(
                exact.region(), field + ".region");
        return exact;
    }

    static String schemaVersion(
            String value,
            String expected,
            String protocol) {
        String exact = value == null || value.isBlank()
                ? expected
                : required(value, "schemaVersion", 128);
        if (!expected.equals(exact)) {
            throw new IllegalArgumentException(
                    "unsupported " + protocol + " schemaVersion");
        }
        return exact;
    }

    static String predecessor(
            String value,
            long revision,
            String field) {
        String exact = normalized(value);
        if (revision < 1
                || revision == 1 && !exact.isBlank()
                || revision > 1
                && !FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " does not match revision semantics");
        }
        return exact;
    }

    static void validityWindow(
            Instant issuedAt,
            Instant validFrom,
            Instant expiresAt,
            Duration maximumActivationDelay,
            Duration maximumLifetime,
            String protocol) {
        if (validFrom.isBefore(issuedAt)
                || Duration.between(issuedAt, validFrom)
                .compareTo(maximumActivationDelay) > 0
                || !expiresAt.isAfter(validFrom)
                || Duration.between(issuedAt, expiresAt)
                .compareTo(maximumLifetime) > 0) {
            throw new IllegalArgumentException(
                    protocol + " validity window is invalid");
        }
    }

    static MirrorArtifactRef kind(
            MirrorArtifactRef value,
            String expected,
            String field) {
        MirrorArtifactRef exact =
                Objects.requireNonNull(value, field);
        if (!expected.equals(exact.kind())) {
            throw new IllegalArgumentException(
                    field + " has an invalid artifact kind");
        }
        return exact;
    }

    static String fingerprint(
            String value,
            String field) {
        String exact = required(value, field, 71);
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a canonical SHA-256 value");
        }
        return exact;
    }

    static String identifier(
            String value,
            String field) {
        String exact = required(value, field, 512);
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " contains unsupported characters");
        }
        return exact;
    }

    static String optionalIdentifier(
            String value,
            String field) {
        String exact = normalized(value);
        if (!exact.isBlank()
                && !IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " contains unsupported characters");
        }
        return exact;
    }

    static String canonicalBase64(
            String value,
            String field,
            int maximum) {
        String exact = required(value, field, maximum);
        try {
            byte[] decoded = Base64.getDecoder().decode(exact);
            if (decoded.length == 0
                    || !exact.equals(
                    Base64.getEncoder()
                            .encodeToString(decoded))) {
                throw new IllegalArgumentException(
                        field + " must be canonical base64");
            }
            return exact;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    field + " must be canonical base64",
                    invalid);
        }
    }

    static Instant time(
            Instant value,
            String field) {
        Instant exact = Objects.requireNonNull(value, field);
        if (Instant.EPOCH.equals(exact)) {
            throw new IllegalArgumentException(
                    field + " must not be epoch");
        }
        return exact;
    }

    static String required(
            String value,
            String field,
            int maximum) {
        String exact = normalized(value);
        if (exact.isBlank() || exact.length() > maximum) {
            throw new IllegalArgumentException(
                    field + " must be bounded and non-blank");
        }
        return exact;
    }

    static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
