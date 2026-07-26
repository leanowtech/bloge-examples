package com.leanowtech.bloge.gateway.integration.mirror;

/**
 * Explicit HTTP negotiation constants for detached Shadow source bindings.
 */
public final class ReadOnlyShadowSourceBindingProtocol {
    /** Exact wire-protocol generation. */
    public static final String VERSION =
            "read-only-shadow-source-binding-v1";
    /** Mandatory request negotiation header. */
    public static final String REQUEST_HEADER =
            "X-BLOGE-Shadow-Source-Binding-Protocol";
    /** Versioned response media type. */
    public static final String MEDIA_TYPE =
            "application/vnd.bloge.read-only-shadow-source-binding.v1+json";

    private ReadOnlyShadowSourceBindingProtocol() {
    }
}
