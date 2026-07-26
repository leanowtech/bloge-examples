package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.AuthoritativeOutcomeObservationRequestDecoder;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoritativeOutcomeObservationRequestDecoderTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final AuthoritativeOutcomeObservationRequestDecoder decoder =
            new AuthoritativeOutcomeObservationRequestDecoder(mapper);
    private final IntegrationRequestContext identity =
            new IntegrationRequestContext(
                    "tenant-a",
                    "support",
                    "refunds",
                    "staging",
                    "sg",
                    "WORKLOAD",
                    "outcome-connector",
                    "",
                    AuthoritativeOutcomeInboxAccessPolicy
                            .INGESTION_PURPOSE,
                    "correlation-outcome");

    @Test
    void decodesTheExactBoundedAdmissionProtocol() throws Exception {
        AuthoritativeOutcomeObservationAdmissionRequest request =
                new AuthoritativeOutcomeObservationAdmissionRequest(
                        "",
                        "",
                        AuthoritativeOutcomeTestFixtures.pending());

        assertThat(decoder.decode(
                mapper.writeValueAsBytes(request),
                identity)).isEqualTo(request);
    }

    @Test
    void rejectsUnknownDuplicateTrailingAndOversizedCommands() {
        assertMalformed("""
                {
                  "schemaVersion":"resourceGateway.authoritativeOutcomeObservationAdmission.v1",
                  "schemaVersion":"resourceGateway.authoritativeOutcomeObservationAdmission.v1"
                }
                """.getBytes(StandardCharsets.UTF_8));
        assertMalformed("""
                {
                  "schemaVersion":"resourceGateway.authoritativeOutcomeObservationAdmission.v1",
                  "expectedPredecessorFingerprint":"",
                  "observation":{},
                  "unexpected":true
                }
                """.getBytes(StandardCharsets.UTF_8));
        assertMalformed(
                "{} {}".getBytes(StandardCharsets.UTF_8));
        assertMalformed(
                new byte[
                        AuthoritativeOutcomeObservationRequestDecoder
                                .MAXIMUM_REQUEST_BYTES
                                + 1]);
    }

    private void assertMalformed(byte[] value) {
        assertThatThrownBy(() -> decoder.decode(value, identity))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> assertThat(
                                failure.problem().code())
                                .isEqualTo(
                                        "RG.MIRROR.OUTCOME.REQUEST_MALFORMED"));
    }
}
