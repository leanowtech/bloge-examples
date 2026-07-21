package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Bounded source of normalized signed certificate-status publications.
 *
 * <p>The source transports untrusted protocol objects only. The durable floor independently
 * verifies signatures, scope, policy, cursor, inventory, and database-time freshness before any
 * publication can affect admission.</p>
 */
public interface ControlPlaneCertificateStatusSource {

    /**
     * Exact durable cursor sent to the source.
     *
     * @param sequence current durable sequence or deployment baseline
     * @param publicationFingerprint current head or baseline fingerprint
     */
    record Cursor(long sequence, String publicationFingerprint) {
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Rejects invalid or unbounded cursor state. */
        public Cursor {
            publicationFingerprint = normalized(publicationFingerprint);
            if (sequence < 0 || !FINGERPRINT.matcher(publicationFingerprint).matches()) {
                throw new IllegalArgumentException("Certificate status source cursor is invalid");
            }
        }
    }

    /** Closed source response state. */
    enum FetchStatus {
        /** No publication follows the supplied durable cursor. */
        UNCHANGED,
        /** One candidate successor publication is present. */
        PUBLICATION
    }

    /**
     * One bounded source response.
     *
     * @param status closed source result
     * @param publication candidate only for {@link FetchStatus#PUBLICATION}
     */
    record FetchResult(
            FetchStatus status,
            ControlPlaneCertificateStatusPublication publication) {

        /** Enforces all-or-none status and candidate publication. */
        public FetchResult {
            status = Objects.requireNonNull(status, "status");
            if (status == FetchStatus.PUBLICATION && publication == null
                    || status == FetchStatus.UNCHANGED && publication != null) {
                throw new IllegalArgumentException(
                        "Certificate status source result is invalid");
            }
        }

        /** @return a source response containing no successor */
        public static FetchResult unchanged() {
            return new FetchResult(FetchStatus.UNCHANGED, null);
        }

        /** @return a source response containing one untrusted candidate */
        public static FetchResult publication(
                ControlPlaneCertificateStatusPublication publication) {
            return new FetchResult(FetchStatus.PUBLICATION,
                    Objects.requireNonNull(publication, "publication"));
        }
    }

    /**
     * Fetches at most one candidate immediately after the supplied durable cursor.
     *
     * @param cursor exact durable cursor
     * @return unchanged or one candidate successor
     */
    FetchResult fetch(Cursor cursor);

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
