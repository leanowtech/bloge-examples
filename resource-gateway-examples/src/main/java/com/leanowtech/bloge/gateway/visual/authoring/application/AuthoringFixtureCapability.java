package com.leanowtech.bloge.gateway.visual.authoring.application;

import java.util.Map;

/**
 * Visual-owned availability port for governed authoring-fixture persistence.
 *
 * <p>The implementation belongs to the isolated testing control plane. Keeping this
 * port in the visual authoring boundary lets the workbench advertise exact runtime
 * availability without importing gateway persistence or security types.</p>
 */
public interface AuthoringFixtureCapability {

    int MAXIMUM_REQUEST_BYTES = 512 * 1024;
    int MAXIMUM_PAYLOAD_BYTES = 256 * 1024;
    int MAXIMUM_PAYLOAD_DEPTH = 64;
    int MAXIMUM_PAYLOAD_NODES = 20_000;
    int MAXIMUM_REDACTION_PATHS = 64;
    int MAXIMUM_RETENTION_DAYS = 30;

    static Map<String, Integer> standardLimits() {
        return Map.of(
                "maximumAuthoringFixtureRequestBytes", MAXIMUM_REQUEST_BYTES,
                "maximumAuthoringFixturePayloadBytes", MAXIMUM_PAYLOAD_BYTES,
                "maximumAuthoringFixturePayloadDepth", MAXIMUM_PAYLOAD_DEPTH,
                "maximumAuthoringFixturePayloadNodes", MAXIMUM_PAYLOAD_NODES,
                "maximumAuthoringFixtureRedactionPaths", MAXIMUM_REDACTION_PATHS,
                "maximumAuthoringFixtureRetentionDays", MAXIMUM_RETENTION_DAYS
        );
    }
}
