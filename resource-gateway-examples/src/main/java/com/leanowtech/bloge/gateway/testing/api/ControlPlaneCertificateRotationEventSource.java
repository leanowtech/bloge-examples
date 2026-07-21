package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Authenticated, bounded source of fingerprint-chained certificate-rotation event pages.
 *
 * <p>The source verifies transport and page protocol only. It does not authorize rotation events
 * and cannot advance a durable cursor. Callers must independently stage the page, apply every event
 * through {@link ControlPlaneCertificateRotationRuntime}, and commit the cursor only after all
 * application results are accepted.</p>
 */
public interface ControlPlaneCertificateRotationEventSource {

    /** Closed fetch outcomes without provider exception or response-body disclosure. */
    enum FetchStatus {
        /** One exact next page was returned and verified. */
        PAGE,
        /** The authenticated source reports no next page. */
        NO_CHANGE,
        /** The source could not be reached or returned a transient service response. */
        SOURCE_UNAVAILABLE,
        /** Response status, headers, body, page chain, or validity policy was invalid. */
        PROTOCOL_REJECTED
    }

    /**
     * Fetches at most one page after the supplied durable cursor position.
     *
     * @param position exact committed page-chain head
     * @return bounded fetch result
     */
    FetchResult fetch(Position position);

    /** @return local transport and protocol facts without remote I/O */
    Descriptor descriptor();

    /**
     * Exact source request cursor.
     *
     * @param deploymentScopeId signed-event deployment scope
     * @param sequence committed page sequence
     * @param pageFingerprint committed page fingerprint
     */
    record Position(String deploymentScopeId, long sequence, String pageFingerprint) {
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT =
                Pattern.compile("sha256:[a-f0-9]{64}");

        /** Rejects ambiguous scope or cursor values before network I/O. */
        public Position {
            deploymentScopeId = normalized(deploymentScopeId);
            pageFingerprint = normalized(pageFingerprint);
            if (!IDENTIFIER.matcher(deploymentScopeId).matches() || sequence < 0
                    || !FINGERPRINT.matcher(pageFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "Certificate rotation event source position is invalid");
            }
        }
    }

    /**
     * Payload-bounded fetch result.
     *
     * @param status closed fetch outcome
     * @param reasonCode stable machine-readable reason
     * @param page verified page only for {@link FetchStatus#PAGE}
     */
    record FetchResult(
            FetchStatus status,
            String reasonCode,
            ControlPlaneCertificateRotationEventPage page) {
        private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

        /** Prevents rejected source results from retaining untrusted page bodies. */
        public FetchResult {
            status = Objects.requireNonNull(status, "status");
            reasonCode = normalized(reasonCode);
            if (!REASON.matcher(reasonCode).matches()
                    || status == FetchStatus.PAGE != (page != null)) {
                throw new IllegalArgumentException(
                        "Certificate rotation event source result is invalid");
            }
        }

        /** Creates one verified-page result. */
        public static FetchResult page(ControlPlaneCertificateRotationEventPage page) {
            return new FetchResult(FetchStatus.PAGE, "PAGE_AVAILABLE",
                    Objects.requireNonNull(page, "page"));
        }

        /** Creates one payload-free non-page result. */
        public static FetchResult withoutPage(FetchStatus status, String reasonCode) {
            if (status == FetchStatus.PAGE) {
                throw new IllegalArgumentException(
                        "A page outcome requires a certificate rotation event page");
            }
            return new FetchResult(status, reasonCode, null);
        }
    }

    /**
     * Fixed-cardinality source security projection.
     *
     * @param schemaVersion descriptor protocol version
     * @param authenticatedProtocol exact media type and version header are required
     * @param privateTrustStore deployment-owned server trust is used
     * @param serverSpkiPinned accepted server chains are SPKI pinned
     * @param mutualTls deployment-owned client identity is presented
     * @param certificateIdentityBound both workload identities are policy bound
     */
    record Descriptor(
            String schemaVersion,
            boolean authenticatedProtocol,
            boolean privateTrustStore,
            boolean serverSpkiPinned,
            boolean mutualTls,
            boolean certificateIdentityBound) {

        /** Current event-source descriptor protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateRotationEventSourceDescriptor.v1";

        /** Rejects a source that could silently fall back to an unauthenticated transport. */
        public Descriptor {
            schemaVersion = normalized(schemaVersion);
            if (!SCHEMA_VERSION.equals(schemaVersion) || !authenticatedProtocol
                    || !privateTrustStore || !serverSpkiPinned || !mutualTls
                    || !certificateIdentityBound) {
                throw new IllegalArgumentException(
                        "Certificate rotation event source descriptor is invalid");
            }
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
