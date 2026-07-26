package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoritativeOutcomeSelectedPopulationCompatibilityFixtureTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void independentlyVerifiesTheCompleteServerProducedFixture() {
        AuthoritativeOutcomeSelectedPopulationCompatibilityFixture
                fixture =
                CapabilityMirrorProtocol
                        .authoritativeOutcomeSelectedPopulationCompatibilityFixture();

        AuthoritativeOutcomeSelectedPopulationCompatibilityFixture
                .VerificationResult result =
                fixture.verify();

        assertThat(result.verified())
                .as(result.reasonCode())
                .isTrue();
        assertThat(result.populationId())
                .isEqualTo(
                        "refund-selected-population");
        assertThat(result.assessmentId())
                .isEqualTo(
                        "fixture-population-completeness");
        assertThat(result.observationCount())
                .isEqualTo(2);
        assertThat(result.dispositionCount())
                .isOne();
        assertThat(result.sourcePageCount())
                .isOne();
        assertThat(fixture.assessment().path(
                "submissionComplete").asBoolean())
                .isTrue();
        assertThat(fixture.assessment().path(
                "terminalComplete").asBoolean())
                .isTrue();
    }

    @Test
    void returnsDefensiveCopiesAndRejectsPopulationTamper() {
        AuthoritativeOutcomeSelectedPopulationCompatibilityFixture
                fixture =
                CapabilityMirrorProtocol
                        .authoritativeOutcomeSelectedPopulationCompatibilityFixture();
        ObjectNode tamperedManifest =
                (ObjectNode) fixture.populationBundle()
                        .path("manifest");
        tamperedManifest.put(
                "totalSelectedPopulation", 4);

        AuthoritativeOutcomeSelectedPopulationCompatibilityFixture
                tampered =
                new
                        AuthoritativeOutcomeSelectedPopulationCompatibilityFixture(
                        fixture.populationBundle(),
                        fixture.observations(),
                        fixture.dispositions(),
                        fixture.assessment(),
                        fixture.sourcePages(),
                        fixture.verificationKey(),
                        fixture.verificationTime());
        ObjectNode tamperedBundle =
                (ObjectNode) tampered.populationBundle();
        tamperedBundle.set(
                "manifest",
                tamperedManifest);
        AuthoritativeOutcomeSelectedPopulationCompatibilityFixture
                invalid =
                new
                        AuthoritativeOutcomeSelectedPopulationCompatibilityFixture(
                        tamperedBundle,
                        tampered.observations(),
                        tampered.dispositions(),
                        tampered.assessment(),
                        tampered.sourcePages(),
                        tampered.verificationKey(),
                        tampered.verificationTime());

        assertThat(invalid.verify().verified())
                .isFalse();
        assertThat(fixture.verify().verified())
                .isTrue();
    }

    @Test
    void rejectsSourcePageAndSourceDocumentSubstitution() {
        AuthoritativeOutcomeSelectedPopulationCompatibilityFixture
                fixture =
                CapabilityMirrorProtocol
                        .authoritativeOutcomeSelectedPopulationCompatibilityFixture();
        ObjectNode sourcePage =
                (ObjectNode) fixture.sourcePages()
                        .getFirst();
        ((ObjectNode) sourcePage.path("entries")
                .get(0).path("sourceRef"))
                .put("id", "substituted-observation");
        AuthoritativeOutcomeSelectedPopulationCompatibilityFixture
                pageSubstitution =
                new
                        AuthoritativeOutcomeSelectedPopulationCompatibilityFixture(
                        fixture.populationBundle(),
                        fixture.observations(),
                        fixture.dispositions(),
                        fixture.assessment(),
                        java.util.List.of(sourcePage),
                        fixture.verificationKey(),
                        fixture.verificationTime());

        ObjectNode observation =
                (ObjectNode) fixture.observations()
                        .getFirst();
        observation.put(
                "subjectFingerprint",
                "sha256:"
                        + "f".repeat(64));
        java.util.List<JsonNode> observations =
                new java.util.ArrayList<>(
                        fixture.observations());
        observations.set(0, observation);
        AuthoritativeOutcomeSelectedPopulationCompatibilityFixture
                documentSubstitution =
                new
                        AuthoritativeOutcomeSelectedPopulationCompatibilityFixture(
                        fixture.populationBundle(),
                        observations,
                        fixture.dispositions(),
                        fixture.assessment(),
                        fixture.sourcePages(),
                        fixture.verificationKey(),
                        fixture.verificationTime());

        assertThat(pageSubstitution.verify().verified())
                .isFalse();
        assertThat(documentSubstitution.verify().verified())
                .isFalse();
    }

    @Test
    void rejectsUnknownEnvelopeFieldsAndMissingSourceClosure()
            throws Exception {
        ObjectNode envelope =
                (ObjectNode) mapper.readTree(
                        getClass().getResourceAsStream(
                                CapabilityMirrorProtocol
                                        .AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_FIXTURE_RESOURCE));
        envelope.put("callerClaimedComplete", true);

        assertThatThrownBy(() ->
                AuthoritativeOutcomeSelectedPopulationCompatibilityFixture
                        .from(envelope))
                .isInstanceOf(
                        IllegalArgumentException.class);

        envelope.remove("callerClaimedComplete");
        envelope.putArray("sourcePages");
        assertThatThrownBy(() ->
                AuthoritativeOutcomeSelectedPopulationCompatibilityFixture
                        .from(envelope))
                .isInstanceOf(
                        IllegalArgumentException.class);
    }
}
