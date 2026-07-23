package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.integration.CapabilityCorpusTrajectoryController;
import com.leanowtech.bloge.gateway.integration.CapabilityCorpusTrajectoryDecoder;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityCorpusTrajectoryProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void strictSchemasExactlyMatchSerializedCommandAndPublication()
            throws Exception {
        Protocol protocol = protocol();
        JsonNode command = mapper.valueToTree(protocol.request());
        JsonNode publication = mapper.valueToTree(protocol.publication());
        JsonNode commandSchema = schema(
                "capability-corpus-trajectory-publish-request-v1.schema.json");
        JsonNode publicationSchema = schema(
                "capability-corpus-trajectory-publication-v1.schema.json");

        assertThat(fieldNames(command))
                .containsExactlyInAnyOrderElementsOf(
                        fieldNames(commandSchema.path("properties")));
        assertThat(fieldNames(publication))
                .containsExactlyInAnyOrderElementsOf(
                        fieldNames(publicationSchema.path("properties")));
        assertThat(fieldNames(command.path("attempts").path(0)))
                .containsExactlyInAnyOrderElementsOf(
                        fieldNames(commandSchema.at(
                                "/$defs/attemptSource/properties")));
        assertThat(fieldNames(publication.path("attempts").path(0)))
                .containsExactlyInAnyOrderElementsOf(
                        fieldNames(publicationSchema.at(
                                "/$defs/attemptSource/properties")));
        assertClosed(commandSchema);
        assertClosed(publicationSchema);
        assertClosed(commandSchema.at("/$defs/attemptSource"));
        assertClosed(publicationSchema.at("/$defs/attemptSource"));
        assertThat(commandSchema.at("/properties/attempts/minItems").asInt())
                .isEqualTo(2);
        assertThat(commandSchema.at("/properties/attempts/maxItems").asInt())
                .isEqualTo(32);
        assertThat(mapper.treeToValue(
                command,
                CapabilityCorpusTrajectoryPublishRequest.class))
                .isEqualTo(protocol.request());
        assertThat(mapper.treeToValue(
                publication,
                CapabilityCorpusTrajectoryPublication.class))
                .isEqualTo(protocol.publication());
    }

    @Test
    void strictDecoderAcceptsClosedCommandAndRejectsAmbiguousBodies()
            throws Exception {
        CapabilityCorpusTrajectoryDecoder decoder =
                new CapabilityCorpusTrajectoryDecoder(mapper);
        CapabilityCorpusTrajectoryPublishRequest request =
                protocol().request();
        byte[] exact = mapper.writeValueAsBytes(request);

        assertThat(decoder.decode(exact, identity())).isEqualTo(request);

        String json = mapper.writeValueAsString(request);
        assertMalformed(() -> decoder.decode(
                json.replaceFirst(
                                "\\{",
                                "{\"schemaVersion\":\"duplicate\",")
                        .getBytes(StandardCharsets.UTF_8),
                identity()));
        JsonNode unknown = mapper.valueToTree(request);
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                unknown.path("attempts").path(0))
                .put("requestBody", "must-not-enter");
        assertMalformed(() -> decoder.decode(bytes(unknown), identity()));
    }

    @Test
    void modelRejectsDuplicateOrNonConsecutiveAttemptSources() {
        CapabilityCorpusTrajectoryPublishRequest request =
                protocol().request();
        CapabilityCorpusTrajectoryPublishRequest.AttemptSource first =
                request.attempts().getFirst();

        assertThatThrownBy(() ->
                new CapabilityCorpusTrajectoryPublishRequest(
                        request.schemaVersion(),
                        request.trajectoryId(),
                        request.revision(),
                        request.expectedPredecessorRef(),
                        request.capabilityRef(),
                        request.corpusPublicationRef(),
                        request.retryPolicyRef(),
                        List.of(first, new CapabilityCorpusTrajectoryPublishRequest
                                .AttemptSource(
                                2,
                                first.observationRef(),
                                first.admissionRef())),
                        request.reviewTicketRef(),
                        request.reasonCode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distinct observations");
        assertThatThrownBy(() ->
                new CapabilityCorpusTrajectoryPublishRequest(
                        request.schemaVersion(),
                        request.trajectoryId(),
                        request.revision(),
                        request.expectedPredecessorRef(),
                        request.capabilityRef(),
                        request.corpusPublicationRef(),
                        request.retryPolicyRef(),
                        List.of(
                                first,
                                new CapabilityCorpusTrajectoryPublishRequest
                                        .AttemptSource(
                                        3,
                                        request.attempts().getLast()
                                                .observationRef(),
                                        request.attempts().getLast()
                                                .admissionRef())),
                        request.reviewTicketRef(),
                        request.reasonCode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consecutively");
    }

    @Test
    void controllerAuthenticatesBeforeDecodeAndReturnsTypedArtifact()
            throws Exception {
        Protocol protocol = protocol();
        CapabilityCorpusTrajectoryGovernanceService service =
                mock(CapabilityCorpusTrajectoryGovernanceService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        CapabilityCorpusTrajectoryDecoder decoder =
                mock(CapabilityCorpusTrajectoryDecoder.class);
        IntegrationRequestContext identity = identity();
        HttpHeaders headers = new HttpHeaders();
        byte[] body = mapper.writeValueAsBytes(protocol.request());
        when(authenticator.authenticate(
                headers,
                IntegrationOperation.MIRROR_CORPUS_TRAJECTORY_PUBLISH))
                .thenReturn(identity);
        when(decoder.decode(body, identity)).thenReturn(protocol.request());
        when(service.publish(protocol.request(), identity))
                .thenReturn(protocol.publication());
        CapabilityCorpusTrajectoryController controller =
                new CapabilityCorpusTrajectoryController(
                        service, authenticator, decoder);

        var response = controller.publish(body, headers);

        assertThat(response.payload()).isEqualTo(protocol.publication());
        verify(authenticator).authenticate(
                headers,
                IntegrationOperation.MIRROR_CORPUS_TRAJECTORY_PUBLISH);
        assertThat(IntegrationOperation.MIRROR_CORPUS_TRAJECTORY_PUBLISH
                .acceptedPurposes()).containsExactly(
                CapabilityCorpusGovernanceService.AUTHORIZED_PURPOSE);
    }

    private Protocol protocol() {
        CapabilityObservationRepository.StoredObservation source =
                CapabilityCorpusTestFixtures.admitted(
                        mapper,
                        CapabilityObservationTestFixtures.scope("org-a"),
                        "trajectory-protocol-source");
        Instant now = source.admission().decidedAt().plusSeconds(1);
        CapabilityCorpusRevision revision =
                CapabilityCorpusTestFixtures.revision(
                        mapper,
                        source,
                        "trajectory-protocol-corpus",
                        1,
                        null,
                        now);
        CapabilityCorpusPublication corpusPublication =
                CapabilityCorpusTestFixtures.publication(
                        mapper,
                        revision,
                        1,
                        null,
                        now.plusSeconds(1));
        CapabilityCorpusTrajectoryPublishRequest request =
                new CapabilityCorpusTrajectoryPublishRequest(
                        "",
                        "trajectory-protocol",
                        1,
                        null,
                        revision.capabilityRef(),
                        corpusPublication.artifactRef(),
                        CapabilityObservationTestFixtures.ref(
                                "RETRY_POLICY",
                                "trajectory-policy",
                                2,
                                '7'),
                        List.of(
                                new CapabilityCorpusTrajectoryPublishRequest
                                        .AttemptSource(
                                        1,
                                        source.envelope().artifactRef(),
                                        source.admission().artifactRef()),
                                new CapabilityCorpusTrajectoryPublishRequest
                                        .AttemptSource(
                                        2,
                                        new MirrorArtifactRef(
                                                CapabilityObservationEnvelope
                                                        .ARTIFACT_KIND,
                                                "trajectory-protocol-source-2",
                                                1,
                                                CapabilityObservationTestFixtures
                                                        .fingerprint('8')),
                                        new MirrorArtifactRef(
                                                CapabilityObservationAdmission
                                                        .ARTIFACT_KIND,
                                                "trajectory-protocol-source-2:admission",
                                                1,
                                                CapabilityObservationTestFixtures
                                                        .fingerprint('9')))),
                        CapabilityObservationTestFixtures.ref(
                                "GOVERNANCE_REVIEW_TICKET",
                                "trajectory-protocol-ticket",
                                1,
                                '6'),
                        "OWNER_APPROVED");
        CapabilityCorpusTrajectoryPublication publication =
                CapabilityCorpusTestFixtures.trajectoryPublication(
                        mapper,
                        corpusPublication,
                        revision,
                        request,
                        null,
                        now.plusSeconds(2));
        return new Protocol(request, publication);
    }

    private byte[] bytes(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static IntegrationRequestContext identity() {
        return CapabilityCorpusTestFixtures.identity(
                "org-a", Set.of("corpus-publishers"));
    }

    private static JsonNode schema(String filename) throws Exception {
        return new ObjectMapper().readTree(
                Files.readString(schemaPath(filename)));
    }

    private static Path schemaPath(String filename) {
        Path moduleRelative = Path.of(
                "..", "docs", "schemas", "resource-gateway-mirror", filename);
        return Files.exists(moduleRelative)
                ? moduleRelative
                : Path.of(
                "docs", "schemas", "resource-gateway-mirror", filename);
    }

    private static LinkedHashSet<String> fieldNames(JsonNode value) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void assertClosed(JsonNode schema) {
        assertThat(schema.path("additionalProperties").asBoolean(true))
                .isFalse();
        LinkedHashSet<String> required = new LinkedHashSet<>();
        schema.path("required")
                .forEach(value -> required.add(value.asText()));
        assertThat(fieldNames(schema.path("properties")))
                .containsExactlyInAnyOrderElementsOf(required);
    }

    private static void assertMalformed(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
                IntegrationProblemException.class,
                failure -> assertThat(failure.problem().code()).isEqualTo(
                        "RG.MIRROR.CORPUS_TRAJECTORY_REQUEST_MALFORMED"));
    }

    private record Protocol(
            CapabilityCorpusTrajectoryPublishRequest request,
            CapabilityCorpusTrajectoryPublication publication) {
    }
}
