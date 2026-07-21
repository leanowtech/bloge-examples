package com.leanowtech.bloge.gateway.testing.api;

import javax.net.ssl.SSLParameters;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

/**
 * Uses the JVM system trust store without client identity or server-key pinning.
 *
 * <p>This adapter preserves the historical HTTPS behavior for test and migration paths. It is not
 * sufficient for a staging or production recovery-fleet source because a public CA compromise or
 * enterprise TLS interception can substitute another valid server key.</p>
 */
public final class SystemTrustRecoveryFleetPublicationTransport
        implements RecoveryFleetPublicationTransport {

    private static final Descriptor DESCRIPTOR = new Descriptor(
            Descriptor.SCHEMA_VERSION, true, false, false, false);

    /** Creates one stateless system-trust adapter. */
    public SystemTrustRecoveryFleetPublicationTransport() {
    }

    /** {@inheritDoc} */
    @Override
    public HttpClient client(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(bounded(connectTimeout))
                .followRedirects(HttpClient.Redirect.NEVER)
                .sslParameters(httpsParameters())
                .build();
    }

    /** {@inheritDoc} */
    @Override
    public Descriptor descriptor() {
        return DESCRIPTOR;
    }

    static Duration bounded(Duration timeout) {
        Duration required = Objects.requireNonNull(timeout, "connectTimeout");
        if (required.compareTo(Duration.ofMillis(100)) < 0
                || required.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException(
                    "Recovery-fleet publication connect timeout is invalid");
        }
        return required;
    }

    static SSLParameters httpsParameters() {
        SSLParameters parameters = new SSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        parameters.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        return parameters;
    }
}
