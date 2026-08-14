package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.regex.Pattern;

/** Authenticated, payload-isolated source of bounded production outcome pages. */
public interface AuthoritativeOutcomeSource {
    /** Fetches at most one verified page after the committed checkpoint. */
    FetchResult fetch(Position position);

    /** @return local security and protocol facts without remote I/O */
    Descriptor descriptor();

    /** Closed source outcomes without provider body or exception disclosure. */
    enum FetchStatus {
        PAGE,
        NO_CHANGE,
        STREAM_COMPLETE,
        SOURCE_UNAVAILABLE,
        PROTOCOL_REJECTED,
        GENERATION_REVOKED
    }

    /**
     * Exact payload-free source position.
     *
     * @param scope enterprise namespace
     * @param connectorId source connector identity
     * @param connectorGeneration exact connector generation
     * @param streamKind live or independently authorized backfill
     * @param streamId exact stream identity
     * @param committedSequence committed page sequence, zero at baseline
     * @param committedPageFingerprint committed page-chain head
     * @param committedCursorRef opaque deployment-resolved cursor reference
     */
    record Position(
            CapabilitySnapshot.Scope scope,
            String connectorId,
            long connectorGeneration,
            AuthoritativeOutcomeSourcePage.StreamKind streamKind,
            String streamId,
            long committedSequence,
            String committedPageFingerprint,
            MirrorArtifactRef committedCursorRef
    ) {
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,511}");
        private static final Pattern FINGERPRINT =
                Pattern.compile("sha256:[a-f0-9]{64}");

        /** Rejects ambiguous source positions before customer I/O. */
        public Position {
            scope = Objects.requireNonNull(scope, "scope");
            connectorId = normalized(connectorId);
            streamKind = Objects.requireNonNull(streamKind, "streamKind");
            streamId = normalized(streamId);
            committedPageFingerprint = normalized(committedPageFingerprint);
            committedCursorRef = Objects.requireNonNull(
                    committedCursorRef, "committedCursorRef");
            if (!IDENTIFIER.matcher(connectorId).matches()
                    || connectorGeneration < 1
                    || !IDENTIFIER.matcher(streamId).matches()
                    || streamKind == AuthoritativeOutcomeSourcePage.StreamKind.LIVE
                    && !"live".equals(streamId)
                    || committedSequence < 0
                    || !FINGERPRINT.matcher(committedPageFingerprint).matches()
                    || !AuthoritativeOutcomeSourcePage.CURSOR_KIND.equals(
                    committedCursorRef.kind())) {
                throw new IllegalArgumentException(
                        "authoritative outcome source position is invalid");
            }
        }
    }

    /** Bounded fetch result. */
    record FetchResult(
            FetchStatus status,
            String reasonCode,
            AuthoritativeOutcomeSourcePage page
    ) {
        private static final Pattern REASON =
                Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

        /** Prevents non-page outcomes from retaining untrusted source material. */
        public FetchResult {
            status = Objects.requireNonNull(status, "status");
            reasonCode = normalized(reasonCode);
            if (!REASON.matcher(reasonCode).matches()
                    || status == FetchStatus.PAGE != (page != null)) {
                throw new IllegalArgumentException(
                        "authoritative outcome source result is invalid");
            }
        }

        /** Creates a verified-page result. */
        public static FetchResult page(AuthoritativeOutcomeSourcePage page) {
            return new FetchResult(
                    FetchStatus.PAGE, "PAGE_AVAILABLE",
                    Objects.requireNonNull(page, "page"));
        }

        /** Creates one payload-free non-page result. */
        public static FetchResult withoutPage(
                FetchStatus status, String reasonCode) {
            if (status == FetchStatus.PAGE) {
                throw new IllegalArgumentException("page outcome requires a source page");
            }
            return new FetchResult(status, reasonCode, null);
        }
    }

    /** Fixed-cardinality local security projection for one source adapter. */
    record Descriptor(
            String schemaVersion,
            boolean payloadIsolated,
            boolean authenticatedProtocol,
            boolean privateTrustStore,
            boolean serverSpkiPinned,
            boolean mutualTls,
            boolean certificateIdentityBound
    ) {
        /** Current source descriptor wire version. */
        public static final String SCHEMA_VERSION =
                "resourceGateway.authoritativeOutcomeSourceDescriptor.v1";

        /** Rejects an adapter that can silently weaken its production transport. */
        public Descriptor {
            schemaVersion = normalized(schemaVersion);
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !payloadIsolated || !authenticatedProtocol
                    || !privateTrustStore || !serverSpkiPinned
                    || !mutualTls || !certificateIdentityBound) {
                throw new IllegalArgumentException(
                        "authoritative outcome source descriptor is invalid");
            }
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
