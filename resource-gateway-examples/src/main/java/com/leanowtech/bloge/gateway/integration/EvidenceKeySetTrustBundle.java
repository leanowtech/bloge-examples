package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.runtime.EvidenceVerificationKeySet;

import java.time.Instant;
import java.util.List;

/**
 * Atomic consumer page joining the current evidence key set with an append-only trust proof.
 *
 * <p>A caller advances from its durable {@code afterSequence} checkpoint until {@link #hasMore()}
 * is false. Only the terminal page may authorize the nested key set; intermediate pages exist to
 * validate continuity without returning an unbounded history.</p>
 *
 * @param schemaVersion trust-bundle protocol version
 * @param generatedAt server observation time
 * @param trustDomain externally configured trust domain
 * @param logId append-only trust log identity
 * @param afterSequence caller checkpoint sequence
 * @param throughSequence final sequence included in this page
 * @param highWaterSequence log sequence observed for this response
 * @param headPublicationFingerprint fingerprint at the observed high-water sequence
 * @param headPublication complete observed head used to re-evaluate freshness and current pins
 * @param hasMore whether another page is required before a trust decision
 * @param publications contiguous publications after the caller checkpoint
 * @param keySet current signed evidence verification key-set snapshot
 */
public record EvidenceKeySetTrustBundle(
        String schemaVersion,
        Instant generatedAt,
        String trustDomain,
        String logId,
        long afterSequence,
        long throughSequence,
        long highWaterSequence,
        String headPublicationFingerprint,
        EvidenceKeySetTrustPublication headPublication,
        boolean hasMore,
        List<EvidenceKeySetTrustPublication> publications,
        EvidenceVerificationKeySet keySet
) {
    /** Current bounded trust bundle protocol version. */
    public static final String SCHEMA_VERSION =
            "toolStudio.resourceGateway.evidenceKeySetTrustBundle.v1";
    /** Maximum publications returned in one page. */
    public static final int MAX_PUBLICATIONS = 256;

    /** Normalizes collections and validates page-level continuity metadata. */
    public EvidenceKeySetTrustBundle {
        schemaVersion = normalized(schemaVersion).isBlank() ? SCHEMA_VERSION : normalized(schemaVersion);
        trustDomain = normalized(trustDomain);
        logId = normalized(logId);
        headPublicationFingerprint = normalized(headPublicationFingerprint);
        publications = publications == null ? List.of() : List.copyOf(publications);
        if (!SCHEMA_VERSION.equals(schemaVersion) || generatedAt == null || trustDomain.isBlank()
                || logId.isBlank() || afterSequence < 0 || throughSequence < afterSequence
                || highWaterSequence < throughSequence || publications.size() > MAX_PUBLICATIONS
                || keySet == null || highWaterSequence < 1
                || !headPublicationFingerprint.matches("sha256:[0-9a-f]{64}")
                || headPublication == null
                || headPublication.sequence() != highWaterSequence
                || !headPublicationFingerprint.equals(headPublication.publicationFingerprint())
                || !trustDomain.equals(headPublication.trustDomain())
                || !logId.equals(headPublication.logId())) {
            throw new IllegalArgumentException("Evidence key-set trust bundle is invalid");
        }
        long expected = afterSequence + 1;
        for (EvidenceKeySetTrustPublication publication : publications) {
            if (publication.sequence() != expected || !trustDomain.equals(publication.trustDomain())
                    || !logId.equals(publication.logId())) {
                throw new IllegalArgumentException("Evidence trust publication page is not contiguous");
            }
            expected++;
        }
        long expectedThrough = publications.isEmpty()
                ? afterSequence : publications.getLast().sequence();
        if (throughSequence != expectedThrough || hasMore != (throughSequence < highWaterSequence)) {
            throw new IllegalArgumentException("Evidence trust publication page metadata is inconsistent");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
