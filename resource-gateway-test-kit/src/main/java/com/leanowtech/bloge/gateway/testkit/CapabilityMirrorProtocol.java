package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;

/**
 * Public wire constants and the packaged compatibility baseline for capability-mirror clients.
 *
 * <p>The constants belong to the standalone test kit rather than the Resource Gateway server so a
 * governance consumer can negotiate and verify mirror artifacts without linking Spring or server
 * implementation classes.</p>
 */
public final class CapabilityMirrorProtocol {

    /** Tool Studio integration protocol name required by the Stage 0 baseline. */
    public static final String INTEGRATION_PROTOCOL = "ToolStudioResourceGatewayProtocol";
    /** Tool Studio integration protocol version required by the Stage 0 baseline. */
    public static final String INTEGRATION_PROTOCOL_V1 = "1.0.0";
    /** Capability-mirror compatibility fixture wire version. */
    public static final String COMPATIBILITY_V1 =
            "resourceGateway.capabilityMirrorCompatibility.v1";
    /** Artifact provenance wire version. */
    public static final String ARTIFACT_PROVENANCE_V1 =
            "resourceGateway.artifactProvenance.v1";
    /** Effect contract wire version. */
    public static final String EFFECT_CONTRACT_V1 = "resourceGateway.effectContract.v1";
    /** Capability contract wire version. */
    public static final String CAPABILITY_CONTRACT_V1 =
            "resourceGateway.capabilityContract.v1";
    /** Capability snapshot wire version. */
    public static final String CAPABILITY_SNAPSHOT_V1 =
            "resourceGateway.capabilitySnapshot.v1";
    /** Capability closure wire version. */
    public static final String CAPABILITY_CLOSURE_V1 =
            "resourceGateway.capabilityClosure.v1";
    /** Capability lifecycle transition wire version. */
    public static final String CAPABILITY_LIFECYCLE_TRANSITION_V1 =
            "resourceGateway.capabilityLifecycleTransition.v1";

    /** Classpath root containing the authoritative mirror schemas and fixtures. */
    public static final String SCHEMA_RESOURCE_ROOT = "/schemas/resource-gateway-mirror/";
    /** Packaged Stage 0 compatibility fixture. */
    public static final String COMPATIBILITY_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-mirror-stage0-v1.fixture.json";
    /** Packaged compatibility fixture schema. */
    public static final String COMPATIBILITY_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-mirror-compatibility-v1.schema.json";
    /** Packaged capability snapshot schema. */
    public static final String CAPABILITY_SNAPSHOT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-snapshot-v1.schema.json";
    /** Packaged capability closure schema. */
    public static final String CAPABILITY_CLOSURE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-closure-v1.schema.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    private CapabilityMirrorProtocol() {
    }

    /**
     * Returns an independent copy of the machine-readable Stage 0 compatibility baseline.
     *
     * <p>The fixture is validated against its packaged strict JSON Schema before it is exposed. A
     * deep copy prevents one caller from changing the process-wide baseline seen by another.</p>
     *
     * @return validated mutable copy of the packaged compatibility fixture
     * @throws IllegalStateException when the test-kit artifact is incomplete or corrupt
     */
    public static JsonNode compatibilityBaseline() {
        return BaselineHolder.BASELINE.deepCopy();
    }

    private static final class BaselineHolder {
        private static final JsonNode BASELINE = load();

        private static JsonNode load() {
            try (InputStream input = CapabilityMirrorProtocol.class.getResourceAsStream(
                    COMPATIBILITY_FIXTURE_RESOURCE)) {
                if (input == null) {
                    throw new IOException("Compatibility fixture is absent");
                }
                JsonNode baseline = JSON.readTree(input);
                CapabilityMirrorSchemaValidator.require(baseline, COMPATIBILITY_SCHEMA_RESOURCE,
                        "RG.MIRROR.CLIENT.COMPATIBILITY_BASELINE_INVALID");
                return baseline;
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException(
                        "RG.MIRROR.CLIENT.COMPATIBILITY_BASELINE_UNAVAILABLE");
            }
        }
    }
}
