package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.AuthoritativeOutcomeSourceCommandDecoder;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoritativeOutcomeSourceCommandDecoderTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AuthoritativeOutcomeSourceCommandDecoder decoder =
            new AuthoritativeOutcomeSourceCommandDecoder(mapper);

    @Test
    void acceptsOneExactCurrentCommand() throws Exception {
        var expected = AuthoritativeOutcomeSourceTestFixtures.backfill(mapper);

        var decoded = decoder.decode(mapper.writeValueAsBytes(expected), identity());

        assertThat(decoded).isEqualTo(expected);
    }

    @Test
    void rejectsUnknownDuplicateAndTrailingMaterial() throws Exception {
        String valid = mapper.writeValueAsString(
                AuthoritativeOutcomeSourceTestFixtures.backfill(mapper));

        assertMalformed(valid.replaceFirst("\\{", "{\"unknown\":true,"));
        assertMalformed(valid.replaceFirst(
                "\"commandId\":", "\"commandId\":\"duplicate\",\"commandId\":"));
        assertMalformed(valid + " {}");
    }

    @Test
    void rejectsWrongVersionAndBodiesOutsideTheBound() throws Exception {
        String valid = mapper.writeValueAsString(
                AuthoritativeOutcomeSourceTestFixtures.backfill(mapper));
        assertMalformed(valid.replace(
                AuthoritativeOutcomeConnectorControlCommand.SCHEMA_VERSION,
                "resourceGateway.authoritativeOutcomeConnectorControlCommand.v0"));

        byte[] oversized = new byte[
                AuthoritativeOutcomeSourceCommandDecoder.MAXIMUM_REQUEST_BYTES + 1];
        assertMalformed(oversized);
        assertMalformed(new byte[0]);
    }

    private void assertMalformed(String value) {
        assertMalformed(value.getBytes(StandardCharsets.UTF_8));
    }

    private void assertMalformed(byte[] value) {
        assertThatThrownBy(() -> decoder.decode(value, identity()))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> {
                            assertThat(failure.problem().status()).isEqualTo(400);
                            assertThat(failure.problem().code())
                                    .isEqualTo("RG.MIRROR.OUTCOME_SOURCE.REQUEST_MALFORMED");
                        });
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "support", "refunds", "staging", "sg",
                "SERVICE", "aneke-control", "",
                AuthoritativeOutcomeSourceControlService.ADMIN_PURPOSE,
                "correlation-source");
    }
}
