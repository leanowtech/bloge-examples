package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceGatewayCapabilitiesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void providerBackedCapabilityRequiresFunctionAssetAndAuthoritativeLimits() {
        ObjectNode payload = payload(true);
        ResourceGatewayCapabilities capabilities = ResourceGatewayCapabilities.from(envelope(payload));

        assertThat(capabilities.functionControlAvailable()).isTrue();
        assertThat(capabilities.functionControlAssetSchemaVersion())
                .isEqualTo(ResourceGatewayCapabilities.FUNCTION_ASSET_SCHEMA);
        assertThat(capabilities.limit("functionRules")).isEqualTo(1);
    }

    @Test
    void missingProviderDegradesWithoutOverAdvertising() {
        ResourceGatewayCapabilities capabilities = ResourceGatewayCapabilities.from(envelope(payload(false)));

        assertThat(capabilities.functionControlAvailable()).isFalse();
        assertThat(capabilities.supportedObjects()).doesNotContainKey("functionControlAsset");
    }

    @Test
    void inconsistentProviderAdvertisementFailsClosed() {
        ObjectNode payload = payload(false);
        payload.with("features").put("functionControlAssetReference", true);
        payload.with("features").put("functionControlGovernedCatalog", true);
        payload.with("features").put("functionControlStateComposition", true);
        payload.with("features").put("functionControlPayloadFreeEvidence", true);
        assertThatThrownBy(() -> ResourceGatewayCapabilities.from(envelope(payload)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void providerWithFunctionAssetButWithoutStateCompositionIsRejected() {
        ObjectNode payload = payload(true);
        payload.with("features").put("functionControlStateComposition", false);

        assertThatThrownBy(() -> ResourceGatewayCapabilities.from(envelope(payload)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ObjectNode envelope(ObjectNode payload) {
        ObjectNode root = JSON.createObjectNode();
        root.set("payload", payload);
        return root;
    }

    private static ObjectNode payload(boolean available) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("schemaVersion", ResourceGatewayCapabilities.SCHEMA_VERSION);
        payload.put("protocol", "ToolStudioResourceGatewayProtocol");
        payload.put("protocolVersion", "1.2.1");
        ObjectNode objects = payload.putObject("supportedObjects");
        if (available) objects.putArray("functionControlAsset")
                .add(ResourceGatewayCapabilities.FUNCTION_ASSET_SCHEMA);
        ObjectNode features = payload.putObject("features");
        features.put("functionControlAssetReference", available);
        features.put("functionControlGovernedCatalog", available);
        features.put("functionControlStateComposition", available);
        features.put("functionControlPayloadFreeEvidence", available);
        ObjectNode limits = payload.putObject("limits");
        for (String key : List.of("testControlEnvelopeDecodedBytes", "functionNameChars",
                "functionDeclarations", "functionRules", "functionDurationMillis",
                "functionConsumption", "functionJsonValueBytes", "functionJsonValueDepth",
                "functionSchemaBytes", "functionSchemaDepth")) limits.put(key, 1);
        return payload;
    }
}
