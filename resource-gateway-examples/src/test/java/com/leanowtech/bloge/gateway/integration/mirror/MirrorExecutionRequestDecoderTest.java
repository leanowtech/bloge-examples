package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.MirrorExecutionRequestDecoder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorExecutionRequestDecoderTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MirrorExecutionRequestDecoder decoder =
            new MirrorExecutionRequestDecoder(mapper);

    @Test
    void decodesTheExactVersionedCommandAndKeepsContextOpen() {
        ObjectNode value = valid();
        value.withObject("/context").put("customerId", "C-1")
                .withObject("/filters").put("active", true);
        value.withObject("/context").putArray("optionalValues").addNull().add("present");

        MirrorExecutionRequest decoded = decoder.decode(value, identity());

        assertThat(decoded.requestId()).isEqualTo("request-1");
        assertThat(decoded.planId()).isEqualTo("plan-1");
        assertThat(decoded.context()).containsEntry("customerId", "C-1");
        assertThat(decoded.context().get("filters")).isEqualTo(java.util.Map.of("active", true));
        assertThat(decoded.context().get("optionalValues"))
                .isEqualTo(java.util.Arrays.asList(null, "present"));
        assertThat(decoded.toString()).doesNotContain("C-1", "customerId");
    }

    @Test
    void rejectsUnknownOrMissingTopLevelFieldsAndNonObjectContext() {
        ObjectNode unknown = valid().put("scope", "caller-controlled");
        assertMalformed(unknown);

        ObjectNode missing = valid();
        missing.remove("expectedPlanFingerprint");
        assertMalformed(missing);

        ObjectNode scalarContext = valid();
        scalarContext.put("context", "not-an-object");
        assertMalformed(scalarContext);
    }

    @Test
    void rejectsUnsupportedVersionsAndExcessiveDepth() {
        ObjectNode unsupported = valid().put("schemaVersion", "future.v99");
        assertMalformed(unsupported);

        ObjectNode deep = valid();
        ObjectNode cursor = deep.withObject("/context");
        for (int depth = 0; depth < MirrorExecutionRequestDecoder.MAXIMUM_DEPTH; depth++) {
            cursor = cursor.putObject("nested");
        }
        assertMalformed(deep);
    }

    @Test
    void rejectsValuesThatTypedCoercionOrTrimmingWouldAcceptButTheSchemaRejects() {
        assertMalformed(valid().put("requestId", " request-1"));
        assertMalformed(valid().put("planId", "plan-1 "));
        assertMalformed(valid().put("expectedPlanFingerprint", fingerprint('a') + " "));
        assertMalformed(valid().put("schemaVersion", " "));
        assertMalformed(valid().put("requestId", 1));
    }

    @Test
    void rawTransportRejectsDuplicateKeysBeforeTreeMaterialization() {
        String duplicated = """
                {
                  "schemaVersion":"resourceGateway.mirrorExecutionRequest.v1",
                  "requestId":"request-1",
                  "requestId":"request-2",
                  "planId":"plan-1",
                  "expectedPlanFingerprint":"%s",
                  "context":{}
                }
                """.formatted(fingerprint('a'));

        assertThatThrownBy(() -> decoder.decode(
                duplicated.getBytes(StandardCharsets.UTF_8), identity()))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(400);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.MIRROR.EXECUTION_REQUEST_MALFORMED");
                });
    }

    private ObjectNode valid() {
        return mapper.createObjectNode()
                .put("schemaVersion", MirrorExecutionRequest.SCHEMA_VERSION)
                .put("requestId", "request-1")
                .put("planId", "plan-1")
                .put("expectedPlanFingerprint", fingerprint('a'))
                .set("context", mapper.createObjectNode());
    }

    private void assertMalformed(ObjectNode value) {
        assertThatThrownBy(() -> decoder.decode(value, identity()))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(400);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.MIRROR.EXECUTION_REQUEST_MALFORMED");
                });
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext("tenant-a", "org-a", "support", "test", "sg",
                "SERVICE", "mirror-client", "", "MIRROR_REHEARSAL", "corr-1",
                Set.of(), "CONFIDENTIAL", "");
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
