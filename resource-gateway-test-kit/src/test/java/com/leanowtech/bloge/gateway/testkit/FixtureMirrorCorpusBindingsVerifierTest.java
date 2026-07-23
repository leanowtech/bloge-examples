package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureMirrorCorpusBindingsVerifierTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final FixtureMirrorCorpusBindingsVerifier verifier =
            new FixtureMirrorCorpusBindingsVerifier();

    @Test
    void verifiesPackagedCanonicalFixtureWithoutClaimingLiveReadiness()
            throws Exception {
        JsonNode fixture = fixture();

        FixtureMirrorCorpusBindingsVerifier.VerificationResult result =
                verifier.verify(fixture);

        assertThat(result.verified()).isTrue();
        assertThat(result.outcome())
                .isEqualTo(FixtureMirrorCorpusBindingsVerifier.Outcome.VERIFIED);
        assertThat(result.checkedBindings()).isEqualTo(2);
    }

    @Test
    void rejectsUnknownFieldsAndWrongReferenceKindsAtSchemaBoundary()
            throws Exception {
        JsonNode unknown = fixture();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unknown)
                .put("fallbackToLatest", true);
        JsonNode wrongKind = fixture();
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrongKind
                .at("/publications/0/capabilityRef"))
                .put("kind", "CAPABILITY_CORPUS_PUBLICATION");

        assertThat(verifier.verify(unknown).outcome())
                .isEqualTo(
                        FixtureMirrorCorpusBindingsVerifier.Outcome.SCHEMA_INVALID);
        assertThat(verifier.verify(wrongKind).outcome())
                .isEqualTo(
                        FixtureMirrorCorpusBindingsVerifier.Outcome.SCHEMA_INVALID);
    }

    @Test
    void rejectsDuplicateAndNonCanonicalCoordinates() throws Exception {
        JsonNode duplicatePublication = fixture();
        JsonNode firstPublication = duplicatePublication.at(
                "/publications/0/publicationRef").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) duplicatePublication.at(
                "/publications/1")).set("publicationRef", firstPublication);
        JsonNode forkedPublication = fixture();
        com.fasterxml.jackson.databind.node.ObjectNode fork =
                (com.fasterxml.jackson.databind.node.ObjectNode) forkedPublication.at(
                        "/publications/1/publicationRef");
        JsonNode first = forkedPublication.at("/publications/0/publicationRef");
        fork.put("id", first.path("id").asText());
        fork.put("revision", first.path("revision").asLong());
        JsonNode reversed = fixture();
        com.fasterxml.jackson.databind.node.ArrayNode publications =
                (com.fasterxml.jackson.databind.node.ArrayNode) reversed.path(
                        "publications");
        JsonNode firstBinding = publications.get(0);
        JsonNode second = publications.get(1);
        publications.removeAll();
        publications.add(second);
        publications.add(firstBinding);

        assertThat(verifier.verify(duplicatePublication).outcome())
                .isEqualTo(
                        FixtureMirrorCorpusBindingsVerifier.Outcome
                                .DUPLICATE_PUBLICATION);
        assertThat(verifier.verify(forkedPublication).outcome())
                .isEqualTo(
                        FixtureMirrorCorpusBindingsVerifier.Outcome
                                .DUPLICATE_PUBLICATION);
        assertThat(verifier.verify(reversed).outcome())
                .isEqualTo(
                        FixtureMirrorCorpusBindingsVerifier.Outcome.ORDER_INVALID);
    }

    @Test
    void ordersMultiDigitRevisionsNumericallyLikeTheServer() throws Exception {
        JsonNode bindings = fixture();
        com.fasterxml.jackson.databind.node.ObjectNode first =
                (com.fasterxml.jackson.databind.node.ObjectNode) bindings.at(
                        "/publications/0/capabilityRef");
        com.fasterxml.jackson.databind.node.ObjectNode second =
                (com.fasterxml.jackson.databind.node.ObjectNode) bindings.at(
                        "/publications/1/capabilityRef");
        first.put("id", "operator:customer.lookup");
        first.put("revision", 2);
        second.put("id", "operator:customer.lookup");
        second.put("revision", 10);

        assertThat(verifier.verify(bindings).outcome())
                .isEqualTo(FixtureMirrorCorpusBindingsVerifier.Outcome.VERIFIED);
    }

    private JsonNode fixture() throws Exception {
        try (InputStream input = CapabilityMirrorProtocol.class.getResourceAsStream(
                CapabilityMirrorProtocol
                        .FIXTURE_MIRROR_CORPUS_BINDINGS_FIXTURE_RESOURCE)) {
            assertThat(input).isNotNull();
            return mapper.readTree(input);
        }
    }
}
