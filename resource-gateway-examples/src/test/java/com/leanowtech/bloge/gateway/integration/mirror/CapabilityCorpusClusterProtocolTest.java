package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.CapabilityCorpusClusterController;
import com.leanowtech.bloge.gateway.integration.CapabilityCorpusClusterDecoder;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityCorpusClusterProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void fixedFixtureRoundTripsWithProducerIntegrity() throws Exception {
        JsonNode fixture = mapper.readTree(Files.readString(schemaPath(
                "capability-corpus-cluster-stage2-v1.fixture.json")));
        CapabilityCorpusRevision corpusRevision = mapper.treeToValue(
                fixture.path("corpusRevision"),
                CapabilityCorpusRevision.class);
        CapabilityCorpusPublication corpusPublication = mapper.treeToValue(
                fixture.path("corpusPublication"),
                CapabilityCorpusPublication.class);
        CapabilityCorpusClusterValidation validation = mapper.treeToValue(
                fixture.path("validation"),
                CapabilityCorpusClusterValidation.class);
        CapabilityCorpusClusterPublishRequest request = mapper.treeToValue(
                fixture.path("publishRequest"),
                CapabilityCorpusClusterPublishRequest.class);
        CapabilityCorpusClusterPublication publication = mapper.treeToValue(
                fixture.path("publication"),
                CapabilityCorpusClusterPublication.class);
        CapabilityCorpusIntegrity integrity =
                new CapabilityCorpusIntegrity(mapper);

        assertThat(integrity.revisionVerified(corpusRevision)).isTrue();
        assertThat(integrity.publicationVerified(corpusPublication)).isTrue();
        assertThat(integrity.clusterValidationVerified(validation)).isTrue();
        assertThat(integrity.clusterCommandFingerprint(request))
                .isEqualTo(publication.sourceCommandFingerprint());
        assertThat(integrity.clusterVerified(publication)).isTrue();
        assertThat(validation.members())
                .containsExactlyElementsOf(publication.members());
        assertThat(validation.artifactRef())
                .isEqualTo(request.validationRef())
                .isEqualTo(publication.validationRef());
    }

    @Test
    void strictSchemasExactlyMatchSerializedBoundaries() throws Exception {
        Protocol protocol = protocol();
        JsonNode validation = mapper.valueToTree(protocol.validation());
        JsonNode request = mapper.valueToTree(protocol.request());
        JsonNode publication = mapper.valueToTree(protocol.publication());
        JsonNode validationSchema = schema(
                "capability-corpus-cluster-validation-v1.schema.json");
        JsonNode requestSchema = schema(
                "capability-corpus-cluster-publish-request-v1.schema.json");
        JsonNode publicationSchema = schema(
                "capability-corpus-cluster-publication-v1.schema.json");

        assertProperties(validation, validationSchema.path("properties"));
        assertProperties(request, requestSchema.path("properties"));
        assertProperties(publication, publicationSchema.path("properties"));
        assertProperties(
                validation.path("members").path(0),
                validationSchema.at("/$defs/sourceCoordinate/properties"));
        assertProperties(
                validation.path("identityProjections").path(0),
                validationSchema.at("/$defs/identityProjection/properties"));
        assertProperties(
                validation.path("holdout"),
                validationSchema.at("/$defs/holdout/properties"));
        assertProperties(
                validation.path("confidence"),
                validationSchema.at("/$defs/confidence/properties"));
        for (JsonNode strict : Set.of(
                validationSchema, requestSchema, publicationSchema,
                validationSchema.at("/$defs/sourceCoordinate"),
                validationSchema.at("/$defs/identityProjection"),
                validationSchema.at("/$defs/holdout"),
                validationSchema.at("/$defs/confidence"))) {
            assertClosed(strict);
        }
        assertThat(validationSchema.at("/properties/members/maxItems").asInt())
                .isEqualTo(1_000);
        assertThat(validationSchema.at(
                "/properties/identityCoverageComplete/const").asBoolean())
                .isTrue();
        assertThat(validationSchema.at(
                "/$defs/confidence/properties/method/const").asText())
                .isEqualTo(
                        CapabilityCorpusClusterValidation.CONFIDENCE_METHOD);
        assertThat(mapper.treeToValue(
                validation,
                CapabilityCorpusClusterValidation.class))
                .isEqualTo(protocol.validation());
        assertThat(mapper.treeToValue(
                request,
                CapabilityCorpusClusterPublishRequest.class))
                .isEqualTo(protocol.request());
        assertThat(mapper.treeToValue(
                publication,
                CapabilityCorpusClusterPublication.class))
                .isEqualTo(protocol.publication());
    }

    @Test
    void strictDecoderAcceptsClosedCommandAndRejectsAmbiguousBodies()
            throws Exception {
        CapabilityCorpusClusterDecoder decoder =
                new CapabilityCorpusClusterDecoder(mapper);
        CapabilityCorpusClusterPublishRequest request = protocol().request();
        byte[] exact = mapper.writeValueAsBytes(request);

        assertThat(decoder.decode(exact, identity())).isEqualTo(request);

        String json = mapper.writeValueAsString(request);
        assertMalformed(() -> decoder.decode(
                json.replaceFirst(
                                "\\{",
                                "{\"schemaVersion\":\"duplicate\",")
                        .getBytes(StandardCharsets.UTF_8),
                identity()));
        ObjectNode unknown = mapper.valueToTree(request);
        unknown.put("responseBody", "must-not-enter");
        assertMalformed(() -> decoder.decode(bytes(unknown), identity()));
    }

    @Test
    void modelRejectsIncompleteIdentityCoverageAndAmbiguousPointers() {
        CapabilityCorpusClusterValidation validation = protocol().validation();

        assertThatThrownBy(() -> new CapabilityCorpusClusterValidation(
                validation.schemaVersion(),
                validation.validationFingerprint(),
                validation.scope(),
                validation.validationId(),
                validation.revision(),
                validation.capabilityRef(),
                validation.corpusPublicationRef(),
                validation.corpusRevisionRef(),
                validation.representativeSource(),
                validation.members(),
                validation.matchRequestPointers(),
                validation.identityMode(),
                validation.identityProjections(),
                validation.distinctIdentityCount(),
                validation.holdout(),
                validation.confidence(),
                false,
                validation.validatedBy(),
                validation.validatedAt(),
                validation.expiresAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identityCoverageComplete");

        assertThatThrownBy(() -> new CapabilityCorpusClusterValidation(
                validation.schemaVersion(),
                validation.validationFingerprint(),
                validation.scope(),
                validation.validationId(),
                validation.revision(),
                validation.capabilityRef(),
                validation.corpusPublicationRef(),
                validation.corpusRevisionRef(),
                validation.representativeSource(),
                validation.members(),
                List.of("/customer", "/customer/id"),
                validation.identityMode(),
                validation.identityProjections(),
                validation.distinctIdentityCount(),
                validation.holdout(),
                validation.confidence(),
                true,
                validation.validatedBy(),
                validation.validatedAt(),
                validation.expiresAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlap");
    }

    @Test
    void controllerAuthenticatesBeforeDecodeAndReturnsTypedArtifact()
            throws Exception {
        Protocol protocol = protocol();
        CapabilityCorpusClusterGovernanceService service =
                mock(CapabilityCorpusClusterGovernanceService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        CapabilityCorpusClusterDecoder decoder =
                mock(CapabilityCorpusClusterDecoder.class);
        IntegrationRequestContext identity = identity();
        HttpHeaders headers = new HttpHeaders();
        byte[] body = mapper.writeValueAsBytes(protocol.request());
        when(authenticator.authenticate(
                headers,
                IntegrationOperation.MIRROR_CORPUS_CLUSTER_PUBLISH))
                .thenReturn(identity);
        when(decoder.decode(body, identity)).thenReturn(protocol.request());
        when(service.publish(protocol.request(), identity))
                .thenReturn(protocol.publication());
        CapabilityCorpusClusterController controller =
                new CapabilityCorpusClusterController(
                        service, authenticator, decoder);

        var response = controller.publish(body, headers);

        assertThat(response.payload()).isEqualTo(protocol.publication());
        verify(authenticator).authenticate(
                headers,
                IntegrationOperation.MIRROR_CORPUS_CLUSTER_PUBLISH);
        assertThat(IntegrationOperation.MIRROR_CORPUS_CLUSTER_PUBLISH
                .acceptedPurposes()).containsExactly(
                CapabilityCorpusGovernanceService.AUTHORIZED_PURPOSE);
    }

    private Protocol protocol() {
        CapabilitySnapshot.Scope scope =
                CapabilityObservationTestFixtures.scope("org-a");
        CapabilitySnapshot capability =
                CapabilityObservationTestFixtures.capability(mapper, scope);
        InMemoryVisualEvidenceSigner signer =
                new InMemoryVisualEvidenceSigner();
        Instant occurredAt = Instant.now().minusSeconds(5);
        List<CapabilityObservationRepository.StoredObservation> sources =
                List.of(
                        CapabilityCorpusTestFixtures.clusterObservation(
                                mapper, signer, capability,
                                "protocol-cluster-001", occurredAt, true),
                        CapabilityCorpusTestFixtures.clusterObservation(
                                mapper, signer, capability,
                                "protocol-cluster-002",
                                occurredAt.plusMillis(100), true),
                        CapabilityCorpusTestFixtures.clusterObservation(
                                mapper, signer, capability,
                                "protocol-cluster-003",
                                occurredAt.plusMillis(200), true));
        CapabilityCorpusRevision revision =
                CapabilityCorpusTestFixtures.revision(
                        mapper,
                        sources,
                        "protocol-cluster-corpus",
                        occurredAt.plus(Duration.ofSeconds(2)));
        CapabilityCorpusPublication corpusPublication =
                CapabilityCorpusTestFixtures.publication(
                        mapper,
                        revision,
                        1,
                        null,
                        revision.createdAt().plusMillis(100));
        CapabilityCorpusClusterValidation validation =
                CapabilityCorpusTestFixtures.clusterValidation(
                        mapper,
                        corpusPublication,
                        revision,
                        sources,
                        corpusPublication.publishedAt().plusMillis(100));
        CapabilityCorpusClusterPublishRequest request =
                CapabilityCorpusTestFixtures.clusterRequest(
                        corpusPublication, validation);
        CapabilityCorpusClusterPublication publication =
                CapabilityCorpusTestFixtures.clusterPublication(
                        mapper,
                        corpusPublication,
                        revision,
                        validation,
                        request,
                        null,
                        validation.validatedAt().plusMillis(100));
        return new Protocol(validation, request, publication);
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

    private static void assertProperties(JsonNode value, JsonNode properties) {
        assertThat(fieldNames(value))
                .containsExactlyInAnyOrderElementsOf(fieldNames(properties));
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
                        "RG.MIRROR.CORPUS_CLUSTER_REQUEST_MALFORMED"));
    }

    private record Protocol(
            CapabilityCorpusClusterValidation validation,
            CapabilityCorpusClusterPublishRequest request,
            CapabilityCorpusClusterPublication publication
    ) {
    }
}
