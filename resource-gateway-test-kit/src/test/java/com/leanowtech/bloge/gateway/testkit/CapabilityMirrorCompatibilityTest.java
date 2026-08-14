package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityMirrorCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void negotiatesEveryRequiredObjectAndReportsDeferredStageFacts() {
        ObjectNode probe = compatibleProbe();

        CapabilityMirrorCompatibility.Assessment assessment =
                CapabilityMirrorCompatibility.assess(probe);

        assertThat(assessment.compatible()).isTrue();
        assertThat(assessment.baselineVersion())
                .isEqualTo(CapabilityMirrorProtocol.COMPATIBILITY_V1);
        assertThat(assessment.negotiatedProtocolVersion())
                .isEqualTo(CapabilityMirrorProtocol.INTEGRATION_PROTOCOL_CURRENT);
        assertThat(assessment.negotiatedObjectVersions())
                .containsEntry("capabilitySnapshot",
                        CapabilityMirrorProtocol.CAPABILITY_SNAPSHOT_V1)
                .containsEntry("capabilityClosure",
                        CapabilityMirrorProtocol.CAPABILITY_CLOSURE_V1)
                .hasSize(6);
        assertThat(assessment.deferredFeatures()).containsOnly(
                org.assertj.core.api.Assertions.entry("mirrorPlanCompilation", false),
                org.assertj.core.api.Assertions.entry("mirrorExternalLeafInterception", false),
                org.assertj.core.api.Assertions.entry("mirrorServing", false));
        assessment.requireCompatible();
    }

    @Test
    void acceptsMinimumConsumerDuringProtocolElevenRollingUpgrade() {
        ObjectNode probe = compatibleProbe();
        probe.put("protocolVersion", CapabilityMirrorProtocol.INTEGRATION_PROTOCOL_V1);

        CapabilityMirrorCompatibility.Assessment assessment =
                CapabilityMirrorCompatibility.assess(probe);

        assertThat(assessment.compatible()).isTrue();
        assertThat(assessment.negotiatedProtocolVersion())
                .isEqualTo(CapabilityMirrorProtocol.INTEGRATION_PROTOCOL_V1);
    }

    @Test
    void laterMirrorFeaturesAndAdditionalObjectVersionsRemainForwardCompatible() {
        ObjectNode probe = compatibleProbe();
        ((ObjectNode) probe.path("features")).put("mirrorPlanCompilation", true);
        ((ArrayNode) probe.path("supportedObjects").path("capabilityClosure"))
                .add("resourceGateway.capabilityClosure.v2");
        probe.put("futureServerField", "ignored");

        CapabilityMirrorCompatibility.Assessment assessment =
                CapabilityMirrorCompatibility.assess(probe);

        assertThat(assessment.compatible()).isTrue();
        assertThat(assessment.deferredFeatures())
                .containsEntry("mirrorPlanCompilation", true);
    }

    @Test
    void failsClosedWhenRequiredObjectOrFeatureIsUnavailable() {
        ObjectNode probe = compatibleProbe();
        ((ObjectNode) probe.path("supportedObjects")).remove("capabilityClosure");
        ((ObjectNode) probe.path("features")).put("capabilitySnapshotApi", false);

        CapabilityMirrorCompatibility.Assessment assessment =
                CapabilityMirrorCompatibility.assess(probe);

        assertThat(assessment.compatible()).isFalse();
        assertThat(assessment.reasonCodes()).containsExactly(
                "RG.MIRROR.CLIENT.OBJECT_VERSION_UNAVAILABLE.capabilityClosure",
                "RG.MIRROR.CLIENT.FEATURE_UNAVAILABLE.capabilitySnapshotApi");
        assertThatThrownBy(assessment::requireCompatible)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(String.join(",", assessment.reasonCodes()));
    }

    @Test
    void malformedProbeProducesOnlyPayloadFreeReasonCodes() {
        ObjectNode malformed = objectMapper.createObjectNode();
        malformed.put("schemaVersion", "customer-secret-value");
        malformed.put("protocol", 17);
        malformed.putArray("supportedObjects");
        malformed.put("features", "not-an-object");

        CapabilityMirrorCompatibility.Assessment assessment =
                CapabilityMirrorCompatibility.assess(malformed);

        assertThat(assessment.compatible()).isFalse();
        assertThat(assessment.reasonCodes())
                .allMatch(code -> code.startsWith("RG.MIRROR.CLIENT."))
                .noneMatch(code -> code.contains("customer-secret-value"));
    }

    private ObjectNode compatibleProbe() {
        JsonNode baseline = CapabilityMirrorProtocol.compatibilityBaseline();
        ObjectNode probe = objectMapper.createObjectNode();
        probe.put("schemaVersion", "toolStudio.resourceGateway.capabilities.v1");
        probe.put("protocol", baseline.path("protocol").asText());
        probe.put("protocolVersion", baseline.path("protocolVersions").get(0).asText());
        probe.set("supportedObjects", baseline.path("requiredObjects").deepCopy());
        ObjectNode features = probe.putObject("features");
        baseline.path("requiredFeatures").forEach(item -> features.put(item.asText(), true));
        baseline.path("deferredFeatures").forEach(item -> features.put(item.asText(), false));
        return probe;
    }
}
