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
        AuthoritativeOutcomeSelectedPopulationUploadRequest
                uploadRequest =
                new AuthoritativeOutcomeSelectedPopulationUploadRequest(
                        "",
                        "upload-1",
                        "",
                        population.manifest());
        AuthoritativeOutcomeContinuousAssessmentRequest
                continuousRequest =
                new AuthoritativeOutcomeContinuousAssessmentRequest(
                        "",
                        "refund-completeness",
                        population.manifest().artifactRef());
        AuthoritativeOutcomeContinuousAssessmentRemediationRequest
                remediationRequest =
                new AuthoritativeOutcomeContinuousAssessmentRemediationRequest(
                        "",
                        "remediation-1",
                        "sha256:" + "a".repeat(64),
                        4,
                        "sha256:" + "b".repeat(64),
                        "DEPENDENCY_REPAIRED");

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
        assertThat(decoder.decodeUpload(
                mapper.writeValueAsBytes(
                        uploadRequest),
                identity)).isEqualTo(uploadRequest);
        assertThat(decoder.decodeUploadChunk(
                mapper.writeValueAsBytes(
                        population.chunks().getFirst()),
                identity)).isEqualTo(
                population.chunks().getFirst());
        assertThat(decoder.decodeContinuousAssessment(
                mapper.writeValueAsBytes(
                        continuousRequest),
                identity)).isEqualTo(
                continuousRequest);
        assertThat(decoder.decodeContinuousAssessmentRemediation(
                mapper.writeValueAsBytes(
                        remediationRequest),
                identity)).isEqualTo(
                remediationRequest);
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
    void rejectsMalformedContinuousAssessmentBeforeConstruction()
            throws Exception {
        AuthoritativeOutcomeContinuousAssessmentRequest request =
                new AuthoritativeOutcomeContinuousAssessmentRequest(
                        "",
                        "refund-completeness",
                        population.manifest().artifactRef());
        ObjectNode unknown = mapper.valueToTree(request);
        unknown.put("pollIntervalSeconds", 1);
        assertMalformed(
                () -> decoder.decodeContinuousAssessment(
                        bytes(unknown), identity));

        ObjectNode missing = mapper.valueToTree(request);
        missing.remove("populationRef");
        assertMalformed(
                () -> decoder.decodeContinuousAssessment(
                        bytes(missing), identity));

        String duplicate =
                mapper.writeValueAsString(request)
                        .replaceFirst(
                                "\"projectionId\":\"refund-completeness\"",
                                "\"projectionId\":\"refund-completeness\","
                                        + "\"projectionId\":\"forged\"");
        assertMalformed(
                () -> decoder.decodeContinuousAssessment(
                        duplicate.getBytes(
                                StandardCharsets.UTF_8),
                        identity));

        ObjectNode remediation = mapper.valueToTree(
                new AuthoritativeOutcomeContinuousAssessmentRemediationRequest(
                        "",
                        "remediation-1",
                        "sha256:" + "a".repeat(64),
                        4,
                        "sha256:" + "b".repeat(64),
                        "DEPENDENCY_REPAIRED"));
        remediation.put("resetFailureBudget", true);
        assertMalformed(
                () -> decoder
                        .decodeContinuousAssessmentRemediation(
                                bytes(remediation),
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
