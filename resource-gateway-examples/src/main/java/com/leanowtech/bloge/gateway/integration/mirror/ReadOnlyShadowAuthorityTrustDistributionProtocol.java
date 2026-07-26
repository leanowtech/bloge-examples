package com.leanowtech.bloge.gateway.integration.mirror;

/** Wire negotiation constants for read-only Shadow authority trust distribution. */
public final class ReadOnlyShadowAuthorityTrustDistributionProtocol {
    /** Versioned media type emitted by cursor-page reads. */
    public static final String MEDIA_TYPE =
            "application/vnd.bloge.read-only-shadow-authority-trust.v1+json";
    /** Exact request header preventing silent protocol downgrade. */
    public static final String REQUEST_HEADER = "X-BLOGE-Shadow-Authority-Trust-Protocol";
    /** Current request-header value. */
    public static final String VERSION = "read-only-shadow-authority-trust-v1";

    private ReadOnlyShadowAuthorityTrustDistributionProtocol() {
    }
}
