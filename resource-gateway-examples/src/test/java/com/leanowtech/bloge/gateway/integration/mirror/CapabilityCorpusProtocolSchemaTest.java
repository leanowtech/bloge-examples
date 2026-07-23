package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityCorpusProtocolSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void fixedFixtureRoundTripsAndVerifiesWithProducerImplementation()
            throws Exception {
        JsonNode fixture = fixture();
        Protocol protocol = protocol(fixture);
        CapabilityCorpusIntegrity integrity = new CapabilityCorpusIntegrity(mapper);

        assertThat(integrity.reviewCommandFingerprint(protocol.reviewRequest()))
                .isEqualTo(protocol.review().sourceCommandFingerprint());
        assertThat(integrity.reviewVerified(protocol.review())).isTrue();
        assertThat(integrity.candidateCommandFingerprint(
                protocol.candidateRequest()))
                .isEqualTo(protocol.revision().sourceCommandFingerprint());
        assertThat(integrity.revisionVerified(protocol.revision())).isTrue();
        assertThat(integrity.publishCommandFingerprint(protocol.publishRequest()))
                .isEqualTo(protocol.publication().sourceCommandFingerprint());
        assertThat(integrity.publicationVerified(protocol.publication())).isTrue();
        assertThat(protocol.revision().artifactRef())
                .isEqualTo(protocol.publishRequest().corpusRevisionRef())
                .isEqualTo(protocol.publication().corpusRevisionRef());
        assertThat(protocol.review().scope())
                .isEqualTo(protocol.revision().scope())
                .isEqualTo(protocol.publication().scope())
                .isEqualTo(mapper.treeToValue(
                        fixture.path("expectedScope"),
                        CapabilitySnapshot.Scope.class));
        assertThat(protocol.publication().publishedAt())
                .isBefore(Instant.parse(fixture.path(
                        "verificationTime").asText()));
        assertRoundTrip(protocol.reviewRequest(), fixture.path("reviewRequest"));
        assertRoundTrip(protocol.review(), fixture.path("review"));
        assertRoundTrip(protocol.candidateRequest(), fixture.path("candidateRequest"));
        assertRoundTrip(protocol.revision(), fixture.path("revision"));
        assertRoundTrip(protocol.publishRequest(), fixture.path("publishRequest"));
        assertRoundTrip(protocol.publication(), fixture.path("publication"));
    }

    @Test
    void strictSchemasExactlyMatchEverySerializedProtocolBoundary()
            throws Exception {
        JsonNode fixture = fixture();
        JsonNode reviewRequest = schema(
                "capability-observation-review-request-v1.schema.json");
        JsonNode review = schema(
                "capability-observation-review-v1.schema.json");
        JsonNode candidate = schema(
                "capability-corpus-candidate-request-v1.schema.json");
        JsonNode revision = schema(
                "capability-corpus-revision-v1.schema.json");
        JsonNode publish = schema(
                "capability-corpus-publish-request-v1.schema.json");
        JsonNode publication = schema(
                "capability-corpus-publication-v1.schema.json");

        assertProperties(fixture.path("reviewRequest"),
                reviewRequest.path("properties"));
        assertProperties(fixture.path("review"), review.path("properties"));
        assertProperties(fixture.path("candidateRequest"),
                candidate.path("properties"));
        assertProperties(fixture.at("/candidateRequest/sources/0"),
                candidate.at("/$defs/sourceCoordinate/properties"));
        assertProperties(fixture.path("revision"), revision.path("properties"));
        assertProperties(fixture.at("/revision/sources/0"),
                revision.at("/$defs/sourceObservation/properties"));
        assertProperties(fixture.at("/revision/riskSummary"),
                revision.at("/$defs/riskSummary/properties"));
        assertProperties(fixture.path("publishRequest"),
                publish.path("properties"));
        assertProperties(fixture.path("publication"),
                publication.path("properties"));

        for (JsonNode strictSchema : Set.of(
                reviewRequest, review, candidate, revision, publish, publication)) {
            assertClosedRequiredObject(strictSchema);
            assertClosedRequiredObject(strictSchema.at("/$defs/artifactRef"));
        }
        assertClosedRequiredObject(review.at("/$defs/scope"));
        assertClosedRequiredObject(candidate.at("/$defs/sourceCoordinate"));
        assertClosedRequiredObject(revision.at("/$defs/scope"));
        assertClosedRequiredObject(revision.at("/$defs/sourceObservation"));
        assertClosedRequiredObject(revision.at("/$defs/riskSummary"));
        assertClosedRequiredObject(publication.at("/$defs/scope"));
    }

    @Test
    void schemasFreezeBoundsLineageAndPayloadExclusion() throws Exception {
        JsonNode candidate = schema(
                "capability-corpus-candidate-request-v1.schema.json");
        JsonNode revision = schema(
                "capability-corpus-revision-v1.schema.json");
        JsonNode reviewRequest = schema(
                "capability-observation-review-request-v1.schema.json");
        String allSchemas = Files.readString(schemaPath(
                "capability-observation-review-request-v1.schema.json"))
                + Files.readString(schemaPath(
                "capability-observation-review-v1.schema.json"))
                + Files.readString(schemaPath(
                "capability-corpus-candidate-request-v1.schema.json"))
                + Files.readString(schemaPath(
                "capability-corpus-revision-v1.schema.json"))
                + Files.readString(schemaPath(
                "capability-corpus-publish-request-v1.schema.json"))
                + Files.readString(schemaPath(
                "capability-corpus-publication-v1.schema.json"));

        assertThat(candidate.at("/properties/sources/maxItems").asInt())
                .isEqualTo(1_000);
        assertThat(revision.at("/properties/sources/maxItems").asInt())
                .isEqualTo(1_000);
        assertThat(revision.at(
                "/$defs/riskSummary/properties/duplicateBasisPoints/maximum")
                .asInt()).isEqualTo(10_000);
        assertThat(reviewRequest.at("/properties/disposition/enum"))
                .extracting(JsonNode::asText)
                .containsExactly(
                        "CONFIRMED_QUARANTINE",
                        "PRODUCER_REMEDIATION_REQUIRED",
                        "POLICY_REMEDIATION_REQUIRED",
                        "SECURITY_INVESTIGATION_REQUIRED",
                        "FALSE_POSITIVE_REINGEST_REQUIRED");
        assertThat(fieldNames(candidate.path("properties")))
                .contains("expectedPredecessorRef");
        assertThat(fieldNames(revision.path("properties")))
                .contains("predecessorRef");
        for (String forbidden : Set.of(
                "rawPayload", "requestBody", "responseBody", "businessKey",
                "credential", "secret", "password", "stackTrace",
                "providerMessage")) {
            assertThat(allSchemas).doesNotContain("\"" + forbidden + "\"");
        }
    }

    private Protocol protocol(JsonNode fixture) throws Exception {
        return new Protocol(
                mapper.treeToValue(fixture.path("reviewRequest"),
                        CapabilityObservationReviewRequest.class),
                mapper.treeToValue(fixture.path("review"),
                        CapabilityObservationReview.class),
                mapper.treeToValue(fixture.path("candidateRequest"),
                        CapabilityCorpusCandidateRequest.class),
                mapper.treeToValue(fixture.path("revision"),
                        CapabilityCorpusRevision.class),
                mapper.treeToValue(fixture.path("publishRequest"),
                        CapabilityCorpusPublishRequest.class),
                mapper.treeToValue(fixture.path("publication"),
                        CapabilityCorpusPublication.class));
    }

    private JsonNode fixture() throws Exception {
        return mapper.readTree(Files.readString(schemaPath(
                "capability-corpus-stage2-v1.fixture.json")));
    }

    private JsonNode schema(String filename) throws Exception {
        return mapper.readTree(Files.readString(schemaPath(filename)));
    }

    private void assertRoundTrip(Object value, JsonNode expected)
            throws Exception {
        assertThat(mapper.writeValueAsString(value))
                .isEqualTo(mapper.writeValueAsString(expected));
    }

    private static Path schemaPath(String filename) {
        Path moduleRelative = Path.of(
                "..", "docs", "schemas", "resource-gateway-mirror", filename);
        return Files.exists(moduleRelative)
                ? moduleRelative
                : Path.of("docs", "schemas", "resource-gateway-mirror", filename);
    }

    private static void assertClosedRequiredObject(JsonNode schema) {
        assertThat(schema.path("additionalProperties").asBoolean(true))
                .isFalse();
        assertThat(fieldNames(schema.path("properties")))
                .containsExactlyInAnyOrderElementsOf(
                        textValues(schema.path("required")));
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        assertThat(fieldNames(value))
                .containsExactlyInAnyOrderElementsOf(fieldNames(properties));
    }

    private static LinkedHashSet<String> fieldNames(JsonNode value) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static LinkedHashSet<String> textValues(JsonNode value) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        value.forEach(item -> values.add(item.asText()));
        return values;
    }

    private record Protocol(
            CapabilityObservationReviewRequest reviewRequest,
            CapabilityObservationReview review,
            CapabilityCorpusCandidateRequest candidateRequest,
            CapabilityCorpusRevision revision,
            CapabilityCorpusPublishRequest publishRequest,
            CapabilityCorpusPublication publication
    ) {
    }
}
