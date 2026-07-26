package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadOnlyShadowSourceResolutionCompatibilityFixtureTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void packagedServerFixtureVerifiesAcrossAllThreeAuthorityRoles() {
        ReadOnlyShadowSourceResolutionCompatibilityFixture fixture =
                CapabilityMirrorProtocol
                        .readOnlyShadowSourceResolutionCompatibilityFixture();

        ReadOnlyShadowSourceResolutionAttestationVerifier
                .VerificationResult result = fixture.verify();

        assertThat(result.verified()).isTrue();
        assertThat(result.attestationId())
                .startsWith("source-resolution-");
        assertThat(result.requestId())
                .isEqualTo("shadow-compatibility-job");
        assertThat(result.executionId())
                .isEqualTo("shadow-compatibility-execution");
        assertThat(result.attestationKeyId())
                .isEqualTo(
                        fixture.sourceResolutionKey()
                                .keyId());
        assertThat(fixture.candidateEvidenceKey().keyId())
                .isNotEqualTo(
                        fixture.sourceBindingKey().keyId())
                .isNotEqualTo(
                        fixture.sourceResolutionKey().keyId());
    }

    @Test
    void fixtureCopiesCannotMutateTheProcessWideCompatibilityBaseline() {
        ReadOnlyShadowSourceResolutionCompatibilityFixture first =
                CapabilityMirrorProtocol
                        .readOnlyShadowSourceResolutionCompatibilityFixture();
        ((ObjectNode) first.attestation())
                .put("attestationId", "changed");

        ReadOnlyShadowSourceResolutionCompatibilityFixture second =
                CapabilityMirrorProtocol
                        .readOnlyShadowSourceResolutionCompatibilityFixture();

        assertThat(second.verify().verified()).isTrue();
        assertThat(second.attestation()
                .path("attestationId").asText())
                .startsWith("source-resolution-")
                .isNotEqualTo("changed");
    }

    @Test
    void swappedAuthorityRolesAndUnknownEnvelopeFieldsFailClosed()
            throws Exception {
        ObjectNode swapped = (ObjectNode) load();
        ObjectNode keys =
                (ObjectNode) swapped.path("verificationKeys");
        JsonNode candidate =
                keys.path("candidateEvidence").deepCopy();
        JsonNode sourceBinding =
                keys.path("sourceBinding").deepCopy();
        keys.set("candidateEvidence", sourceBinding);
        keys.set("sourceBinding", candidate);

        ReadOnlyShadowSourceResolutionCompatibilityFixture
                wrongRoles =
                ReadOnlyShadowSourceResolutionCompatibilityFixture
                        .from(swapped);
        assertThat(wrongRoles.verify().verified()).isFalse();

        ObjectNode extended = (ObjectNode) load();
        extended.put("futureField", true);
        assertThatThrownBy(() ->
                ReadOnlyShadowSourceResolutionCompatibilityFixture
                        .from(extended))
                .isInstanceOf(
                        IllegalArgumentException.class);
    }

    @Test
    void fixtureCarriesNoPrivateKeyOrBusinessPayload()
            throws Exception {
        String value = load().toString();

        assertThat(value)
                .doesNotContainIgnoringCase("privateKey")
                .doesNotContain("\"requestBody\"")
                .doesNotContain("\"responseBody\"")
                .doesNotContain("\"payload\"")
                .contains("\"payloadPolicy\":\"HASH_ONLY\"");
    }

    private JsonNode load() throws Exception {
        try (InputStream input =
                     getClass().getResourceAsStream(
                             CapabilityMirrorProtocol
                                     .READ_ONLY_SHADOW_SOURCE_RESOLUTION_FIXTURE_RESOURCE)) {
            assertThat(input).isNotNull();
            return mapper.readTree(input);
        }
    }
}
