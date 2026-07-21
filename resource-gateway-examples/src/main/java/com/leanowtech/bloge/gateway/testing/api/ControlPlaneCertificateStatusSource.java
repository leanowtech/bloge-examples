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
        PUBLICATION,
        /** The remote source is temporarily unavailable. */
        SOURCE_UNAVAILABLE,
        /** The remote response violates the strict transport or publication protocol. */
        PROTOCOL_REJECTED
    }

    /**
     * One bounded source response.
     *
     * @param status closed source result
     * @param publication candidate only for {@link FetchStatus#PUBLICATION}
     * @param sourceHead exact signed source head for successful v2 responses, otherwise null
     */
    record FetchResult(
            FetchStatus status,
            ControlPlaneCertificateStatusPublication publication,
            ControlPlaneCertificateStatusSourceHead sourceHead,
            String reasonCode) {

        /** Enforces all-or-none status and candidate publication. */
        public FetchResult {
            status = Objects.requireNonNull(status, "status");
            reasonCode = reasonCode == null ? "" : reasonCode.trim();
            boolean successful = status == FetchStatus.PUBLICATION
                    || status == FetchStatus.UNCHANGED;
            if (status == FetchStatus.PUBLICATION && publication == null
                    || status != FetchStatus.PUBLICATION && publication != null
                    || !successful && sourceHead != null
                    || status == FetchStatus.PUBLICATION && !reasonCode.isBlank()
                    || status == FetchStatus.UNCHANGED && !"NO_CHANGE".equals(reasonCode)
                    || !successful && !reasonCode.matches("[A-Z][A-Z0-9_.-]{0,127}")) {
                throw new IllegalArgumentException(
                        "Certificate status source result is invalid");
            }
        }

        /** @return a source response containing no successor */
        public static FetchResult unchanged() {
            return new FetchResult(FetchStatus.UNCHANGED, null, null, "NO_CHANGE");
        }

        /** @return a v2 source response proving the supplied cursor is the exact head */
        public static FetchResult unchanged(ControlPlaneCertificateStatusSourceHead sourceHead) {
            return new FetchResult(FetchStatus.UNCHANGED, null,
                    Objects.requireNonNull(sourceHead, "sourceHead"), "NO_CHANGE");
        }

        /** @return a source response containing one untrusted candidate */
        public static FetchResult publication(
                ControlPlaneCertificateStatusPublication publication) {
            return new FetchResult(FetchStatus.PUBLICATION,
                    Objects.requireNonNull(publication, "publication"), null, "");
        }

        /** @return a v2 source response containing one successor and its exact source head */
        public static FetchResult publication(
                ControlPlaneCertificateStatusPublication publication,
                ControlPlaneCertificateStatusSourceHead sourceHead) {
            return new FetchResult(FetchStatus.PUBLICATION,
                    Objects.requireNonNull(publication, "publication"),
                    Objects.requireNonNull(sourceHead, "sourceHead"), "");
        }

        /** @return a bounded transient source-failure result */
        public static FetchResult unavailable(String reasonCode) {
            return new FetchResult(FetchStatus.SOURCE_UNAVAILABLE, null, null, reasonCode);
        }

        /** @return a bounded strict-protocol rejection result */
        public static FetchResult rejected(String reasonCode) {
            return new FetchResult(FetchStatus.PROTOCOL_REJECTED, null, null, reasonCode);
        }

        /** @return whether this successful result carries an exact signed source head */
        public boolean exactSourceHead() {
            return (status == FetchStatus.PUBLICATION || status == FetchStatus.UNCHANGED)
                    && sourceHead != null;
        }
    }

    /**
     * Fetches at most one candidate immediately after the supplied durable cursor.
     *
     * @param cursor exact durable cursor
     * @return unchanged or one candidate successor
     */
    FetchResult fetch(Cursor cursor);

    /** @return fixed-cardinality transport-security posture */
    default Descriptor descriptor() {
        return Descriptor.unavailable();
    }

    /**
     * Public source transport posture without endpoint or identity material.
     *
     * @param schemaVersion descriptor protocol version
     * @param available whether the source adapter is configured
     * @param privateTrustStore whether JVM system trust is excluded
     * @param serverSpkiPinned whether remote key identity is pinned
     * @param mutualTls whether a client workload certificate is configured
     * @param certificateIdentityBound whether both workload roles are policy bound
     * @param strictProtocol whether media type, version, bounds, and redirects are strict
     */
    record Descriptor(
            String schemaVersion,
            boolean available,
            boolean privateTrustStore,
            boolean serverSpkiPinned,
            boolean mutualTls,
            boolean certificateIdentityBound,
            boolean strictProtocol) {

        /** Current source descriptor protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateStatusSourceDescriptor.v1";

        /** Rejects partial or downgrade-prone source posture. */
        public Descriptor {
            schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || available && (!privateTrustStore || !serverSpkiPinned || !mutualTls
                    || !certificateIdentityBound || !strictProtocol)
                    || !available && (privateTrustStore || serverSpkiPinned || mutualTls
                    || certificateIdentityBound || strictProtocol)) {
                throw new IllegalArgumentException(
                        "Certificate status source descriptor is invalid");
            }
        }

        /** @return canonical unavailable source posture */
        public static Descriptor unavailable() {
            return new Descriptor(SCHEMA_VERSION, false, false, false,
                    false, false, false);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
