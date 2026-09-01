package com.leanowtech.bloge.gateway.visual.authoring.resource.openapi;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Governed host seam for one remote OpenAPI document read.
 *
 * <p>An implementation is a security authority, not a generic HTTP client. Before returning it must
 * authorize the exact scope and destination, resolve any {@code connectionId} only from committed
 * Connection and Secret Store authority, pin the authorized DNS result for the TLS connection, reject
 * redirects, bound connect/read time, require a supported media type, and stop reading at the supplied
 * application limit. It must never place credentials, document bytes, URLs, or provider errors in logs,
 * exceptions, or diagnostics. Deployments without such an implementation use {@link #unavailable()}.</p>
 */
@FunctionalInterface
public interface RemoteOpenApiDocumentGateway {
    /** Fetches one document after applying the complete governed-egress contract. */
    Document fetch(Request request);

    /** @return fail-closed gateway used when no governed host adapter is installed */
    static RemoteOpenApiDocumentGateway unavailable() {
        return request -> {
            throw new OpenApiPreviewFailure(OpenApiPreviewFailure.Code.CAPABILITY_UNAVAILABLE);
        };
    }

    /**
     * Trusted, already-normalized egress request.
     *
     * @param identity authenticated authoring identity
     * @param uri credential-free absolute HTTPS URI
     * @param connectionId optional committed Connection used for authentication
     * @param maximumBytes hard response-body limit; an adapter may choose a lower limit
     * @param timeout total connect-and-read budget; an adapter may choose a lower Connection timeout
     */
    record Request(OpenApiPreviewIdentity identity, URI uri, String connectionId,
                   int maximumBytes, Duration timeout) {
        private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

        /** Revalidates the boundary so a host adapter never receives an unsafe URI. */
        public Request {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(uri, "uri");
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.isOpaque() || uri.toString().length() > 2048
                    || uri.toString().chars().anyMatch(character -> Character.isWhitespace(character)
                    || Character.isISOControl(character))) {
                throw new IllegalArgumentException("remote OpenAPI URI is invalid");
            }
            if (connectionId != null && !IDENTIFIER.matcher(connectionId).matches()) {
                throw new IllegalArgumentException("connectionId is invalid");
            }
            if (maximumBytes < 1 || maximumBytes > 10 * 1024 * 1024) {
                throw new IllegalArgumentException("maximumBytes is invalid");
            }
            if (timeout == null || timeout.isZero() || timeout.isNegative()
                    || timeout.compareTo(Duration.ofSeconds(15)) > 0) {
                throw new IllegalArgumentException("timeout is invalid");
            }
        }

        @Override public String toString() {
            return "Request[scope=" + identity.scope() + ", connectionConfigured="
                    + (connectionId != null) + ", uri=<redacted>]";
        }
    }

    /**
     * Bounded gateway result. The module independently rechecks media type and byte limit.
     *
     * @param mediaType response media type without trusting document content
     * @param bytes response bytes, defensively copied and never rendered by {@link #toString()}
     */
    record Document(String mediaType, byte[] bytes) {
        /** Creates an immutable byte snapshot. */
        public Document {
            mediaType = mediaType == null ? "" : mediaType.trim();
            bytes = bytes == null ? null : bytes.clone();
        }

        /** Returns a defensive byte copy. */
        @Override public byte[] bytes() { return bytes == null ? null : bytes.clone(); }
        @Override public String toString() {
            return "Document[mediaType=" + mediaType + ", length=" + (bytes == null ? 0 : bytes.length) + "]";
        }

        @Override public boolean equals(Object other) {
            return other instanceof Document that && mediaType.equals(that.mediaType)
                    && Arrays.equals(bytes, that.bytes);
        }

        @Override public int hashCode() { return 31 * mediaType.hashCode() + Arrays.hashCode(bytes); }
    }
}
