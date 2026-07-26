package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.AuthoritativeOutcomeSelectedPopulationRequestDecoder;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoritativeOutcomeSelectedPopulationRequestDecoderTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final AuthoritativeOutcomeSelectedPopulationRequestDecoder
            decoder =
            new AuthoritativeOutcomeSelectedPopulationRequestDecoder(
                    mapper);
    private final IntegrationRequestContext identity =
            new IntegrationRequestContext(
                    "tenant-a",
                    "support",
                    "refunds",
                    "staging",
                    "sg",
                    "WORKLOAD",
                    "selection-authority",
                    "",
                    AuthoritativeOutcomeSelectedPopulationAccessPolicy
                            .SELECTION_PURPOSE,
                    "correlation-population",
                    Set.of(
                            AuthoritativeOutcomeSelectedPopulationAccessPolicy
                                    .DEFAULT_SELECTION_GROUP),
                    "CONFIDENTIAL",
                    "");

    private AuthoritativeOutcomeSelectedPopulationTestFixtures
            .Population population;
    private
    AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
            dispositionIntegrity;

    @BeforeEach
    void setUp() {
        InMemoryVisualEvidenceSigner signer =
                InMemoryVisualEvidenceSigner.usingClock(
                        DomainFidelityTestFixtures.CLOCK);
        AuthoritativeOutcomeSelectedPopulationIntegrity
                populationIntegrity =
                new
                        AuthoritativeOutcomeSelectedPopulationIntegrity(
                        mapper,
                        signer,
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .populationAuthority(),
                        DomainFidelityTestFixtures.CLOCK);
        dispositionIntegrity =
                new
                        AuthoritativeOutcomeSelectedPopulationDispositionIntegrity(
                        mapper,
                        signer,
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .dispositionAuthority(),
                        DomainFidelityTestFixtures.CLOCK);
        population =
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .signedPopulation(
                                populationIntegrity);
    }

    @Test
    void decodesEachExactVersionedBoundedCommand()
            throws Exception {
        AuthoritativeOutcomeSelectedPopulationAdmissionRequest
                populationRequest =
                new
                        AuthoritativeOutcomeSelectedPopulationAdmissionRequest(
                        "",
                        "",
                        population.manifest(),
                        population.chunks());
        AuthoritativeOutcomeSelectedPopulationDisposition
                disposition =
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .signedDisposition(
                                dispositionIntegrity,
                                population.manifest(),
                                population.members().getFirst(),
                                "deletion-1");
        AuthoritativeOutcomeSelectedPopulationDispositionAdmissionRequest
                dispositionRequest =
                new
                        AuthoritativeOutcomeSelectedPopulationDispositionAdmissionRequest(
                        "",
                        "",
                        disposition);
        AuthoritativeOutcomeSelectedPopulationAssessmentRequest
                assessmentRequest =
                new
                        AuthoritativeOutcomeSelectedPopulationAssessmentRequest(
                        "",
                        1,
                        "assessment-1",
                        1,
                        "");

        assertThat(decoder.decodePopulation(
                mapper.writeValueAsBytes(
                        populationRequest),
                identity)).isEqualTo(populationRequest);
        assertThat(decoder.decodeDisposition(
                mapper.writeValueAsBytes(
                        dispositionRequest),
                identity)).isEqualTo(dispositionRequest);
        assertThat(decoder.decodeAssessment(
                mapper.writeValueAsBytes(
                        assessmentRequest),
                identity)).isEqualTo(assessmentRequest);
    }

    @Test
    void rejectsUnknownMissingDuplicateAndTrailingFields()
            throws Exception {
        ObjectNode command = mapper.valueToTree(
                new
                        AuthoritativeOutcomeSelectedPopulationAssessmentRequest(
                        "",
                        1,
                        "assessment-1",
                        1,
                        ""));
        command.put("forged", true);
        assertMalformed(
                () -> decoder.decodeAssessment(
                        bytes(command), identity));

        command.remove("forged");
        command.remove("assessmentId");
        assertMalformed(
                () -> decoder.decodeAssessment(
                        bytes(command), identity));

        String duplicate = """
                {
                  "schemaVersion":"resourceGateway.authoritativeOutcomeSelectedPopulationAssessmentRequest.v1",
                  "populationRevision":1,
                  "assessmentId":"assessment-1",
                  "assessmentId":"forged",
                  "assessmentRevision":1,
                  "expectedPredecessorFingerprint":""
                }
                """;
        assertMalformed(
                () -> decoder.decodeAssessment(
                        duplicate.getBytes(
                                StandardCharsets.UTF_8),
                        identity));
        byte[] trailing =
                (mapper.writeValueAsString(
                        new
                                AuthoritativeOutcomeSelectedPopulationAssessmentRequest(
                                "",
                                1,
                                "assessment-1",
                                1,
                                ""))
                        + " {}").getBytes(
                        StandardCharsets.UTF_8);
        assertMalformed(
                () -> decoder.decodeAssessment(
                        trailing,
                        identity));
    }

    @Test
    void rejectsUnsupportedVersionsDeepTreesAndRawByteLimits()
            throws Exception {
        ObjectNode version = mapper.valueToTree(
                new
                        AuthoritativeOutcomeSelectedPopulationAssessmentRequest(
                        "",
                        1,
                        "assessment-1",
                        1,
                        ""));
        version.put("schemaVersion", "future.v2");
        assertMalformed(
                () -> decoder.decodeAssessment(
                        bytes(version), identity));

        ObjectNode deep = mapper.valueToTree(
                new
                        AuthoritativeOutcomeSelectedPopulationAdmissionRequest(
                        "",
                        "",
                        population.manifest(),
                        population.chunks()));
        ObjectNode nested = mapper.createObjectNode();
        ObjectNode cursor = nested;
        for (int index = 0;
             index
                     < AuthoritativeOutcomeSelectedPopulationRequestDecoder
                     .MAXIMUM_DEPTH + 2;
             index++) {
            ObjectNode next = mapper.createObjectNode();
            cursor.set("child", next);
            cursor = next;
        }
        ((ObjectNode) deep.path("manifest"))
                .set("selectionPolicyRef", nested);
        assertMalformed(
                () -> decoder.decodePopulation(
                        bytes(deep), identity));
        assertMalformed(
                () -> decoder.decodeDisposition(
                        new byte[
                                AuthoritativeOutcomeSelectedPopulationRequestDecoder
                                        .MAXIMUM_DISPOSITION_REQUEST_BYTES
                                        + 1],
                        identity));
    }

    private byte[] bytes(ObjectNode value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "test command could not be encoded",
                    failure);
        }
    }

    private static void assertMalformed(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> assertThat(
                                failure.problem().code())
                                .isEqualTo(
                                        "RG.MIRROR.OUTCOME.POPULATION_REQUEST_MALFORMED"));
    }
}
