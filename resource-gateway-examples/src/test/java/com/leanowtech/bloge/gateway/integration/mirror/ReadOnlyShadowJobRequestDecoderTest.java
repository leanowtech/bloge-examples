package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.ReadOnlyShadowJobRequestDecoder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadOnlyShadowJobRequestDecoderTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final ReadOnlyShadowJobRequestDecoder decoder =
            new ReadOnlyShadowJobRequestDecoder(mapper);
    private final IntegrationRequestContext identity =
            ReadOnlyShadowJobTestFixtures.identity(
                    "support",
                    ReadOnlyShadowJobService
                            .EXECUTION_PURPOSE);

    @Test
    void decodesTheExactBoundedProtocol() throws Exception {
        ReadOnlyShadowJobRequest request =
                ReadOnlyShadowJobTestFixtures.request(
                        "shadow-decode", 14);

        assertThat(decoder.decode(
                mapper.writeValueAsBytes(request),
                identity)).isEqualTo(request);
    }

    @Test
    void decodesV2DetachedEvidenceWithAnExactSourceBinding() throws Exception {
        ReadOnlyShadowJobRequest online =
                ReadOnlyShadowJobTestFixtures.request(
                        "shadow-detached", 15);
        ReadOnlyShadowJobRequest detached =
                new ReadOnlyShadowJobRequest(
                        ReadOnlyShadowJobRequest.V2_SCHEMA_VERSION,
                        online.requestId(),
                        online.scope(),
                        online.inventoryRef(),
                        online.unitId(),
                        online.scenarioCaseRef(),
                        online.targetCapabilityRef(),
                        online.candidatePlanRef(),
                        online.baselineBindingRef(),
                        online.comparisonPolicyRef(),
                        ReadOnlyShadowJobRequest.SourceMode
                                .DETACHED_EVIDENCE,
                        ReadOnlyShadowJobTestFixtures.ref(
                                "SHADOW_SOURCE_BINDING",
                                "detached-refund-pair",
                                'a'),
                        online.accessGrant(),
                        online.deadlineAt());

        assertThat(decoder.decode(
                mapper.writeValueAsBytes(detached),
                identity)).isEqualTo(detached);
        assertThat(detached.effectiveSourceMode())
                .isEqualTo(
                        ReadOnlyShadowJobRequest.SourceMode
                                .DETACHED_EVIDENCE);
    }

    @Test
    void keepsV1WireShapeImplicitAndRejectsAmbiguousV2Modes() throws Exception {
        ReadOnlyShadowJobRequest online =
                ReadOnlyShadowJobTestFixtures.request(
                        "shadow-online", 16);

        assertThat(mapper.valueToTree(online).has("sourceMode"))
                .isFalse();
        assertThat(mapper.valueToTree(online).has("sourceBindingRef"))
                .isFalse();
        assertThat(online.effectiveSourceMode())
                .isEqualTo(
                        ReadOnlyShadowJobRequest.SourceMode
                                .ONLINE_EXECUTION);
        assertThatThrownBy(() -> new ReadOnlyShadowJobRequest(
                ReadOnlyShadowJobRequest.V2_SCHEMA_VERSION,
                online.requestId(),
                online.scope(),
                online.inventoryRef(),
                online.unitId(),
                online.scenarioCaseRef(),
                online.targetCapabilityRef(),
                online.candidatePlanRef(),
                online.baselineBindingRef(),
                online.comparisonPolicyRef(),
                ReadOnlyShadowJobRequest.SourceMode.ONLINE_EXECUTION,
                null,
                online.accessGrant(),
                online.deadlineAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved for detached");
    }

    @Test
    void rejectsUnknownDuplicateTrailingAndOversizedCommands() {
        assertMalformed("""
                {
                  "schemaVersion":"resourceGateway.readOnlyShadowJobRequest.v1",
                  "schemaVersion":"resourceGateway.readOnlyShadowJobRequest.v1"
                }
                """.getBytes(StandardCharsets.UTF_8));
        assertMalformed("""
                {
                  "schemaVersion":"resourceGateway.readOnlyShadowJobRequest.v1",
                  "unexpected":true
                }
                """.getBytes(StandardCharsets.UTF_8));
        assertMalformed(
                "{} {}".getBytes(
                        StandardCharsets.UTF_8));
        assertMalformed(
                new byte[
                        ReadOnlyShadowJobRequestDecoder
                                .MAXIMUM_REQUEST_BYTES
                                + 1]);
    }

    private void assertMalformed(byte[] value) {
        assertThatThrownBy(() ->
                decoder.decode(value, identity))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> assertThat(
                                failure.problem().code())
                                .isEqualTo(
                                        "RG.MIRROR.SHADOW.REQUEST_MALFORMED"));
    }
}
