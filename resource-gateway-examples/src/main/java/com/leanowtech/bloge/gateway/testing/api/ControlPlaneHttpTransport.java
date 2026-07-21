package com.leanowtech.bloge.gateway.testing.api;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

/**
 * Creates bounded HTTP clients for authenticated testing-control-plane exchanges.
 *
 * <p>This transport owns TLS material loading, server authentication, client identity, redirect
 * policy, and credential disposal. Protocol adapters retain responsibility for request and response
 * validation and never receive keystore passwords or private-key material. One transport instance
 * represents one governed client/server trust domain. Static adapters freeze that policy; rotating
 * adapters may replace it only through an authenticated, monotonic generation protocol. Callers
 * must not reuse either form across independently governed control-plane authorities.</p>
 */
public interface ControlPlaneHttpTransport {

    /**
     * Creates a no-redirect client with the supplied connection deadline.
     *
     * <p>Static transports return an immutable TLS client. A rotating transport may return a stable
     * proxy whose request methods atomically select the current immutable TLS generation.</p>
     *
     * @param connectTimeout finite connection deadline from 100 milliseconds through 30 seconds
     * @return client carrying this transport's governed trust and identity policy
     */
    HttpClient client(Duration connectTimeout);

    /**
     * Returns a payload-free security projection without paths, aliases, pins, or credentials.
     *
     * @return immutable transport security descriptor
     */
    Descriptor descriptor();

    /**
     * Reports whether exact X.509 workload identities are enforced in addition to TLS trust.
     *
     * @return true only when both client and server identities are certificate-policy bound
     */
    default boolean certificateIdentityBound() {
        return false;
    }

    /** Resolves one opaque credential reference into caller-owned characters. */
    @FunctionalInterface
    interface SecretResolver {

        /**
         * Resolves a credential that the transport will erase after use.
         *
         * @param reference validated opaque secret reference
         * @return non-empty caller-owned characters; never a shared cache value
         */
        char[] resolve(String reference);
    }

    /**
     * Aggregate transport security facts safe for capability and health projection.
     *
     * <p>The v1 schema identifier is retained from the original recovery-fleet-only contract so
     * existing capability consumers remain wire compatible while the Java ownership boundary is
     * generalized.</p>
     *
     * @param schemaVersion descriptor protocol version
     * @param systemTrustStore whether the JVM system trust store authenticates the server chain
     * @param privateTrustStore whether a deployment-owned trust store authenticates the chain
     * @param serverSpkiPinned whether every accepted chain must intersect an exact SPKI pin set
     * @param mutualTls whether a deployment-owned client certificate is presented
     */
    record Descriptor(
            String schemaVersion,
            boolean systemTrustStore,
            boolean privateTrustStore,
            boolean serverSpkiPinned,
            boolean mutualTls) {

        /** Current descriptor protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.recoveryFleetPublicationTransportDescriptor.v1";

        /** Rejects contradictory or unauthenticated transport projections. */
        public Descriptor {
            schemaVersion = Objects.requireNonNullElse(schemaVersion, "").trim();
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || systemTrustStore == privateTrustStore
                    || mutualTls && !serverSpkiPinned) {
                throw new IllegalArgumentException(
                        "Control-plane HTTP transport descriptor is invalid");
            }
        }
    }
}
