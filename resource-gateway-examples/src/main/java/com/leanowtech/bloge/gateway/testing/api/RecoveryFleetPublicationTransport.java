package com.leanowtech.bloge.gateway.testing.api;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

/**
 * Creates bounded HTTP clients for recovery-fleet publication sources.
 *
 * <p>The interface is the transport-trust seam shared by inventory and managed-root consumers.
 * Implementations own TLS material loading, server authentication, client identity, redirect
 * policy, and credential disposal. Callers retain ownership of publication protocol validation and
 * never receive keystore passwords or private-key material.</p>
 */
public interface RecoveryFleetPublicationTransport {

    /**
     * Creates an immutable no-redirect client with the supplied connection deadline.
     *
     * @param connectTimeout finite connection deadline from 100 milliseconds through 30 seconds
     * @return immutable client carrying this transport's trust and identity policy
     */
    HttpClient client(Duration connectTimeout);

    /**
     * Returns a payload-free security projection without paths, aliases, pins, or credentials.
     *
     * @return immutable transport security descriptor
     */
    Descriptor descriptor();

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
                        "Recovery-fleet publication transport descriptor is invalid");
            }
        }
    }
}
