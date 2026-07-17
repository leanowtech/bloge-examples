package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableWorkerQuarantineControlPlane;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Key-free wire projection of one verified external quarantine change authorization.
 *
 * <p>This projection intentionally excludes authority signatures, verification keys, ticket text,
 * raw scope values, actor values, claim tokens, and business payload. The material fingerprint is
 * sufficient to correlate Resource Gateway evidence with the external governance record.</p>
 *
 * @param schemaVersion projection protocol version
 * @param trustDomain deployment-owned external governance boundary
 * @param authorizationId opaque external work-order or approval identity
 * @param authorizationFingerprint canonical signed-material fingerprint
 * @param policyFingerprint exact external approval-policy revision
 * @param notBefore inclusive authorization activation time
 * @param expiresAt exclusive authorization deadline
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DurableWorkerQuarantineChangeAuthorizationReference(
        String schemaVersion,
        String trustDomain,
        String authorizationId,
        String authorizationFingerprint,
        String policyFingerprint,
        Instant notBefore,
        Instant expiresAt) {

    /** Current key-free external authorization reference protocol version. */
    public static final String SCHEMA_VERSION =
            "bloge.durableWorkerQuarantineChangeAuthorizationReference.v1";

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates the complete bounded payload-free authorization reference. */
    public DurableWorkerQuarantineChangeAuthorizationReference {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        trustDomain = normalized(trustDomain);
        authorizationId = normalized(authorizationId);
        authorizationFingerprint = normalized(authorizationFingerprint);
        policyFingerprint = normalized(policyFingerprint);
        notBefore = Objects.requireNonNull(notBefore, "notBefore");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !IDENTIFIER.matcher(trustDomain).matches()
                || !IDENTIFIER.matcher(authorizationId).matches()
                || !FINGERPRINT.matcher(authorizationFingerprint).matches()
                || !FINGERPRINT.matcher(policyFingerprint).matches()
                || notBefore.getNano() % 1_000 != 0 || expiresAt.getNano() % 1_000 != 0
                || !expiresAt.isAfter(notBefore)) {
            throw new IllegalArgumentException(
                    "A complete external quarantine authorization reference is required");
        }
    }

    /** Creates a key-free wire projection from integrity-verified database evidence. */
    public static DurableWorkerQuarantineChangeAuthorizationReference from(
            DatabaseDurableWorkerQuarantineControlPlane
                    .ExternalChangeAuthorizationReference reference) {
        Objects.requireNonNull(reference, "reference");
        return new DurableWorkerQuarantineChangeAuthorizationReference("",
                reference.trustDomain(), reference.authorizationId(),
                reference.authorizationFingerprint(), reference.policyFingerprint(),
                reference.notBefore(), reference.expiresAt());
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
