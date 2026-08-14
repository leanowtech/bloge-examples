package com.leanowtech.bloge.gateway.integration;

/**
 * Stable protocol identifiers shared by Resource Gateway and Tool Studio.
 */
public final class ToolStudioResourceGatewayProtocol {

    public static final String NAME = "ToolStudioResourceGatewayProtocol";
    /** Current additive protocol version carrying Business Mirror package governance objects. */
    public static final String VERSION = "1.1.0";
    /** Oldest consumer version that remains wire-compatible with the current producer. */
    public static final String MINIMUM_CONSUMER_VERSION = "1.0.0";
    /** Versions covered by mixed-version conformance tests, newest first. */
    public static final java.util.List<String> SUPPORTED_CONSUMER_VERSIONS =
            java.util.List.of(VERSION, MINIMUM_CONSUMER_VERSION);
    public static final String RESOURCE_GATEWAY_VERSION = "resource-gateway-examples/1.0.0";
    public static final String ENVELOPE_SCHEMA_VERSION = "toolStudio.resourceGateway.envelope.v1";

    private ToolStudioResourceGatewayProtocol() {
    }
}
