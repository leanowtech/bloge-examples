package com.leanowtech.bloge.gateway.integration.mirror;

/** Explicit HTTP negotiation constants for source-resolution attestations. */
public final class ReadOnlyShadowSourceResolutionAttestationProtocol {
    /** Exact wire-protocol generation. */
    public static final String VERSION =
            "read-only-shadow-source-resolution-attestation-v1";
    /** Mandatory request negotiation header. */
    public static final String REQUEST_HEADER =
            "X-BLOGE-Shadow-Source-Resolution-Protocol";
    /** Versioned response media type. */
    public static final String MEDIA_TYPE =
            "application/vnd.bloge.read-only-shadow-source-resolution-attestation.v1+json";

    private ReadOnlyShadowSourceResolutionAttestationProtocol() {
    }
}
