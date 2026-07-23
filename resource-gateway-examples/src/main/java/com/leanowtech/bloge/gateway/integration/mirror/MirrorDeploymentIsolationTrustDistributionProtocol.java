package com.leanowtech.bloge.gateway.integration.mirror;

/** Wire-level negotiation constants for deployment-agent trust distribution. */
public final class MirrorDeploymentIsolationTrustDistributionProtocol {
    /** Versioned JSON media type emitted by current trust-distribution reads. */
    public static final String MEDIA_TYPE =
            "application/vnd.bloge.mirror-deployment-isolation-trust.v1+json";
    /** Exact agent request header used to prevent silent protocol downgrade. */
    public static final String REQUEST_HEADER = "X-BLOGE-Mirror-Trust-Protocol";
    /** Exact current request-header value. */
    public static final String VERSION = "mirror-deployment-isolation-trust-v1";

    private MirrorDeploymentIsolationTrustDistributionProtocol() {
    }
}
