package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Bounded contiguous cursor page for Shadow authority key-set trusted distribution.
 *
 * <p>The caller durably checkpoints {@code throughGeneration} and the last publication
 * fingerprint only after independently verifying every returned successor. The server freezes one
 * high-water floor while reading the page, so concurrent rotations cannot create an inconsistent
 * page boundary.</p>
 *
 * @param schemaVersion cursor-page protocol version
 * @param generatedAt trusted server observation time
 * @param scope exact enterprise scope
 * @param publicationKind exact authority protocol
 * @param issuer exact delegated authority
 * @param keySetId stable key-set stream
 * @param afterGeneration caller checkpoint generation
 * @param afterPublicationFingerprint caller checkpoint fingerprint, blank only at genesis
 * @param throughGeneration final generation included in this page
 * @param highWaterGeneration floor frozen for this response
 * @param highWaterPublicationFingerprint fingerprint at the frozen floor
 * @param highWaterPublication complete frozen head used to re-evaluate current trust
 * @param hasMore whether another page is required to reach the frozen floor
 * @param publications contiguous successors after the caller checkpoint
 */
public record ReadOnlyShadowAuthorityKeySetPage(
        String schemaVersion,
        Instant generatedAt,
        CapabilitySnapshot.Scope scope,
        ReadOnlyShadowAuthorityIntegrity.PublicationKind publicationKind,
        String issuer,
        String keySetId,
        long afterGeneration,
        String afterPublicationFingerprint,
        long throughGeneration,
        long highWaterGeneration,
        String highWaterPublicationFingerprint,
        ReadOnlyShadowAuthorityKeySetPublication highWaterPublication,
        boolean hasMore,
        List<ReadOnlyShadowAuthorityKeySetPublication> publications
) {
    /** Current trusted-distribution page version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.readOnlyShadowAuthorityKeySetPage.v1";
    /** Artifact kind used by integration envelopes. */
    public static final String ARTIFACT_KIND = "READ_ONLY_SHADOW_AUTHORITY_KEY_SET_PAGE";
    /** Maximum publications in one response. */
    public static final int MAXIMUM_PUBLICATIONS = 128;

    /** Validates exact stream coordinates and page continuity metadata. */
    public ReadOnlyShadowAuthorityKeySetPage {
        schemaVersion = ReadOnlyShadowAuthoritySeal.normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION
                : ReadOnlyShadowAuthoritySeal.required(schemaVersion, "schemaVersion", 128);
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported read-only Shadow authority key-set page schemaVersion");
        }
        generatedAt = ReadOnlyShadowAuthoritySeal.time(generatedAt, "generatedAt");
        scope = ReadOnlyShadowAuthoritySeal.scope(scope, "scope");
        publicationKind = Objects.requireNonNull(publicationKind, "publicationKind");
        issuer = ReadOnlyShadowAuthoritySeal.identifier(issuer, "issuer");
        keySetId = ReadOnlyShadowAuthoritySeal.identifier(keySetId, "keySetId");
        afterPublicationFingerprint =
                ReadOnlyShadowAuthoritySeal.normalized(afterPublicationFingerprint);
        highWaterPublicationFingerprint =
                ReadOnlyShadowAuthoritySeal.normalized(highWaterPublicationFingerprint);
        publications = publications == null ? List.of() : List.copyOf(publications);
        if (afterGeneration < 0 || throughGeneration < afterGeneration
                || highWaterGeneration < throughGeneration
                || publications.size() > MAXIMUM_PUBLICATIONS
                || afterGeneration == 0 && !afterPublicationFingerprint.isBlank()
                || afterGeneration > 0 && !isFingerprint(afterPublicationFingerprint)
                || highWaterGeneration == 0 && !highWaterPublicationFingerprint.isBlank()
                || highWaterGeneration > 0 && !isFingerprint(highWaterPublicationFingerprint)
                || highWaterGeneration == 0 && highWaterPublication != null
                || highWaterGeneration > 0 && (highWaterPublication == null
                || highWaterPublication.material().generation() != highWaterGeneration
                || !highWaterPublication.publicationFingerprint()
                .equals(highWaterPublicationFingerprint)
                || !highWaterPublication.material().scope().equals(scope)
                || highWaterPublication.material().publicationKind() != publicationKind
                || !highWaterPublication.material().issuer().equals(issuer)
                || !highWaterPublication.material().keySetId().equals(keySetId))) {
            throw new IllegalArgumentException("authority key-set cursor page is invalid");
        }
        long expected = afterGeneration + 1;
        for (ReadOnlyShadowAuthorityKeySetPublication publication : publications) {
            ReadOnlyShadowAuthorityKeySetPublication exact =
                    Objects.requireNonNull(publication, "publication");
            var material = exact.material();
            if (material.generation() != expected
                    || !material.scope().equals(scope)
                    || material.publicationKind() != publicationKind
                    || !material.issuer().equals(issuer)
                    || !material.keySetId().equals(keySetId)) {
                throw new IllegalArgumentException(
                        "authority key-set cursor page is not contiguous");
            }
            expected++;
        }
        long expectedThrough = publications.isEmpty()
                ? afterGeneration
                : publications.getLast().material().generation();
        if (throughGeneration != expectedThrough
                || hasMore != (throughGeneration < highWaterGeneration)
                || !hasMore && throughGeneration > 0
                && !(publications.isEmpty()
                ? afterPublicationFingerprint
                : publications.getLast().publicationFingerprint())
                .equals(highWaterPublicationFingerprint)) {
            throw new IllegalArgumentException(
                    "authority key-set cursor metadata is inconsistent");
        }
    }

    private static boolean isFingerprint(String value) {
        return value != null && value.matches("sha256:[a-f0-9]{64}");
    }
}
