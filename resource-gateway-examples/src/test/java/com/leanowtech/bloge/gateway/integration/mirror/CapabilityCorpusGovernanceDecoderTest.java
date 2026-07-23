package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.CapabilityCorpusGovernanceDecoder;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityCorpusGovernanceDecoderTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CapabilityCorpusGovernanceDecoder decoder =
            new CapabilityCorpusGovernanceDecoder(mapper);
    private final CapabilityObservationRepository.StoredObservation source =
            CapabilityCorpusTestFixtures.quarantined(
                    mapper,
                    CapabilityObservationTestFixtures.scope("org-a"),
                    "observation-decoder");

    @Test
    void decodesAllThreeClosedGovernanceCommands() throws Exception {
        CapabilityObservationReviewRequest review =
                CapabilityCorpusTestFixtures.reviewRequest(source);
        CapabilityObservationRepository.StoredObservation admitted =
                CapabilityCorpusTestFixtures.admitted(
                        mapper,
                        source.envelope().material().scope(),
                        "observation-candidate");
        CapabilityCorpusCandidateRequest candidate =
                CapabilityCorpusTestFixtures.candidateRequest(
                        "support-corpus", 1, null, List.of(admitted));
        CapabilityCorpusRevision revision =
                CapabilityCorpusTestFixtures.revision(
                        mapper,
                        admitted,
                        "support-corpus",
                        1,
                        null,
                        admitted.admission().decidedAt().plusSeconds(1));
        CapabilityCorpusPublishRequest publication =
                new CapabilityCorpusPublishRequest(
                        "",
                        revision.corpusId(),
                        1,
                        null,
                        revision.artifactRef(),
                        CapabilityObservationTestFixtures.ref(
                                "GOVERNANCE_REVIEW_TICKET",
                                "ticket-publish",
                                1,
                                '7'),
                        "OWNER_APPROVED");

        assertThat(decoder.decodeReview(bytes(review), identity()))
                .isEqualTo(review);
        assertThat(decoder.decodeCandidate(bytes(candidate), identity()))
                .isEqualTo(candidate);
        assertThat(decoder.decodePublication(bytes(publication), identity()))
                .isEqualTo(publication);
    }

    @Test
    void rejectsDuplicateUnknownNestedAndTrailingContent() throws Exception {
        CapabilityObservationReviewRequest review =
                CapabilityCorpusTestFixtures.reviewRequest(source);
        String json = mapper.writeValueAsString(review);
        assertMalformed(() -> decoder.decodeReview(
                json.replaceFirst("\\{", "{\"schemaVersion\":\"duplicate\",")
                        .getBytes(StandardCharsets.UTF_8),
                identity()));

        ObjectNode unknown = mapper.valueToTree(review);
        ((ObjectNode) unknown.path("observationRef"))
                .put("rawPayload", "must-not-enter");
        assertMalformed(() -> decoder.decodeReview(bytes(unknown), identity()));

        assertMalformed(() -> decoder.decodeReview(
                (json + "{}").getBytes(StandardCharsets.UTF_8), identity()));
    }

    @Test
    void rejectsWrongVersionOversizedAndDeepBodies() {
        ObjectNode wrongVersion = mapper.valueToTree(
                CapabilityCorpusTestFixtures.reviewRequest(source));
        wrongVersion.put(
                "schemaVersion",
                "resourceGateway.capabilityObservationReviewRequest.v2");
        assertMalformed(() -> decoder.decodeReview(
                bytes(wrongVersion), identity()));
        assertMalformed(() -> decoder.decodeReview(
                new byte[CapabilityCorpusGovernanceDecoder.MAXIMUM_BYTES + 1],
                identity()));

        ObjectNode deep = mapper.valueToTree(
                CapabilityCorpusTestFixtures.reviewRequest(source));
        ObjectNode cursor = (ObjectNode) deep.path("observationRef");
        for (int index = 0;
             index < CapabilityCorpusGovernanceDecoder.MAXIMUM_DEPTH + 2;
             index++) {
            ObjectNode next = mapper.createObjectNode();
            cursor.set("next", next);
            cursor = next;
        }
        assertMalformed(() -> decoder.decodeReview(bytes(deep), identity()));
    }

    private byte[] bytes(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static com.leanowtech.bloge.gateway.integration.IntegrationRequestContext
            identity() {
        return CapabilityCorpusTestFixtures.identity(
                "org-a", Set.of("corpus-reviewers"));
    }

    private static void assertMalformed(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
                IntegrationProblemException.class,
                failure -> {
                    assertThat(failure.problem().status()).isEqualTo(400);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.MIRROR.CORPUS_REQUEST_MALFORMED");
                    assertThat(failure.problem().details())
                            .containsEntry(
                                    "maximumBytes",
                                    CapabilityCorpusGovernanceDecoder.MAXIMUM_BYTES)
                            .containsEntry(
                                    "maximumDepth",
                                    CapabilityCorpusGovernanceDecoder.MAXIMUM_DEPTH)
                            .containsEntry(
                                    "maximumNodes",
                                    CapabilityCorpusGovernanceDecoder.MAXIMUM_NODES);
                });
    }
}
