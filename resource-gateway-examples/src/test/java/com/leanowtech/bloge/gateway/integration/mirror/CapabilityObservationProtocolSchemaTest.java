package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityObservationProtocolSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void fixedFixtureRoundTripsAndVerifiesWithProducerImplementation()
            throws Exception {
        JsonNode fixture = mapper.readTree(Files.readString(
                schemaPath("capability-observation-stage2-v1.fixture.json")));
        JsonNode key = fixture.path("verificationKey");
        CapabilityObservationEnvelope envelope = mapper.treeToValue(
                fixture.path("observation"), CapabilityObservationEnvelope.class);
        CapabilityObservationIntegrity.AuthorityKey authorityKey =
                new CapabilityObservationIntegrity.AuthorityKey(
                        new MirrorArtifactRef(
                                "OBSERVATION_AUTHORITY_KEY",
                                key.path("keyId").asText(),
                                1,
                                CapabilityObservationTestFixtures.fingerprint('e')),
                        key.path("algorithm").asText(),
                        key.path("encodedPublicKey").asText(),
                        key.path("issuer").asText(),
                        Instant.parse(key.path("notBefore").asText()),
                        Instant.parse(key.path("notAfter").asText()),
                        CapabilityObservationIntegrity.KeyState.valueOf(
                                key.path("state").asText()));

        CapabilityObservationIntegrity.VerificationResult verification =
                new CapabilityObservationIntegrity(mapper).verify(
                        envelope, authorityKey);

        assertThat(verification.verified()).isTrue();
        assertThat(envelope.material().scope())
                .isEqualTo(mapper.treeToValue(
                        fixture.path("expectedScope"),
                        CapabilitySnapshot.Scope.class));
        assertThat(envelope.observationFingerprint())
                .isEqualTo(
                        "sha256:1f3b78c8cc7112b6cf4f218b2709339c643b9cac4381ff1e3c4115c2083709b0");
        assertThat(mapper.writeValueAsString(envelope))
                .isEqualTo(mapper.writeValueAsString(
                        fixture.path("observation")));
    }

    @Test
    void strictSchemasExactlyMatchEverySerializedProtocolRecord() throws Exception {
        Protocol protocol = protocol();
        JsonNode observation = mapper.valueToTree(protocol.envelope());
        JsonNode admission = mapper.valueToTree(protocol.admission());
        JsonNode receipt = mapper.valueToTree(
                new CapabilityObservationReceipt(
                        "", protocol.envelope(), protocol.admission()));
        JsonNode observationSchema = schema("capability-observation-v1.schema.json");
        JsonNode admissionSchema =
                schema("capability-observation-admission-v1.schema.json");
        JsonNode receiptSchema =
                schema("capability-observation-receipt-v1.schema.json");

        assertProperties(observation, observationSchema.path("properties"));
        assertProperties(
                observation.path("material"),
                observationSchema.at("/$defs/material/properties"));
        assertProperties(
                observation.at("/material/scope"),
                observationSchema.at("/$defs/scope/properties"));
        assertProperties(
                observation.at("/material/trace"),
                observationSchema.at("/$defs/traceCoordinates/properties"));
        assertProperties(
                observation.at("/material/request"),
                observationSchema.at("/$defs/payloadReference/properties"));
        assertProperties(
                observation.at("/material/stateCorrelation"),
                observationSchema.at("/$defs/stateCorrelation/properties"));
        assertProperties(
                observation.at("/material/dataUseGrant"),
                observationSchema.at("/$defs/dataUseGrant/properties"));
        assertProperties(
                observation.path("seal"),
                observationSchema.at("/$defs/seal/properties"));
        assertProperties(admission, admissionSchema.path("properties"));
        assertProperties(
                admission.path("scope"),
                admissionSchema.at("/$defs/scope/properties"));
        assertProperties(receipt, receiptSchema.path("properties"));

        for (String pointer : Set.of(
                "",
                "/$defs/material",
                "/$defs/scope",
                "/$defs/traceCoordinates",
                "/$defs/payloadReference",
                "/$defs/normalizedError",
                "/$defs/stateCorrelation",
                "/$defs/dataUseGrant",
                "/$defs/artifactRef",
                "/$defs/seal")) {
            assertClosedRequiredObject(observationSchema.at(pointer));
        }
        assertClosedRequiredObject(admissionSchema);
        assertClosedRequiredObject(admissionSchema.at("/$defs/scope"));
        assertClosedRequiredObject(admissionSchema.at("/$defs/artifactRef"));
        assertClosedRequiredObject(receiptSchema);
    }

    @Test
    void modelsRoundTripWithoutLosingNullOrContentAddressedFields() throws Exception {
        Protocol protocol = protocol();

        CapabilityObservationEnvelope envelope = mapper.readValue(
                mapper.writeValueAsBytes(protocol.envelope()),
                CapabilityObservationEnvelope.class);
        CapabilityObservationAdmission admission = mapper.readValue(
                mapper.writeValueAsBytes(protocol.admission()),
                CapabilityObservationAdmission.class);
        CapabilityObservationReceipt receipt = mapper.readValue(
                mapper.writeValueAsBytes(new CapabilityObservationReceipt(
                        "", protocol.envelope(), protocol.admission())),
                CapabilityObservationReceipt.class);

        assertThat(envelope).isEqualTo(protocol.envelope());
        assertThat(admission).isEqualTo(protocol.admission());
        assertThat(receipt.envelope()).isEqualTo(protocol.envelope());
        assertThat(receipt.admission()).isEqualTo(protocol.admission());
    }

    @Test
    void schemaFreezesPayloadExclusionBoundsAndTerminalQuarantineVocabulary()
            throws Exception {
        JsonNode observationSchema = schema("capability-observation-v1.schema.json");
        JsonNode admissionSchema =
                schema("capability-observation-admission-v1.schema.json");
        String observationSource = Files.readString(
                schemaPath("capability-observation-v1.schema.json"));

        assertThat(observationSchema.at(
                "/$defs/payloadReference/properties/sizeBytes/maximum").asLong())
                .isEqualTo(64L * 1024 * 1024);
        assertThat(observationSchema.at(
                "/$defs/material/properties/latencyMillis/maximum").asLong())
                .isEqualTo(Duration.ofDays(1).toMillis());
        assertThat(observationSchema.at(
                "/$defs/dataUseGrant/properties/purpose/const").asText())
                .isEqualTo("MIRROR_CORPUS_INGESTION");
        assertThat(observationSchema.at(
                "/$defs/canonicalInstant/pattern").asText()).endsWith("Z$");
        assertThat(admissionSchema.at(
                "/properties/state/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("ADMITTED", "QUARANTINED");
        for (String forbidden : Set.of(
                "rawPayload", "requestBody", "responseBody", "businessKey",
                "credential", "secret", "password", "stackTrace", "providerMessage")) {
            assertThat(observationSource).doesNotContain("\"" + forbidden + "\"");
        }
    }

    private Protocol protocol() {
        CapabilitySnapshot capability = CapabilityObservationTestFixtures.capability(
                mapper, CapabilityObservationTestFixtures.scope("org-a"));
        InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
        CapabilityObservationEnvelope envelope =
                CapabilityObservationTestFixtures.envelope(
                        mapper, signer, capability, "observation-schema");
        Instant decidedAt = envelope.seal().signedAt().plusSeconds(1);
        CapabilityObservationAdmission admission =
                new CapabilityObservationAdmissionIntegrity(mapper).admitted(
                        envelope,
                        CapabilityObservationTestFixtures.ref(
                                "OBSERVATION_ADMISSION_POLICY",
                                "support-policy",
                                3,
                                'f'),
                        CapabilityObservationTestFixtures.authorityKey(
                                envelope,
                                signer,
                                CapabilityObservationIntegrity.KeyState.ACTIVE)
                                .keyRef(),
                        decidedAt,
                        decidedAt.plus(Duration.ofDays(10)));
        return new Protocol(envelope, admission);
    }

    private JsonNode schema(String filename) throws Exception {
        return mapper.readTree(Files.readString(schemaPath(filename)));
    }

    private static Path schemaPath(String filename) {
        Path moduleRelative = Path.of(
                "..", "docs", "schemas", "resource-gateway-mirror", filename);
        return Files.exists(moduleRelative)
                ? moduleRelative
                : Path.of("docs", "schemas", "resource-gateway-mirror", filename);
    }

    private static void assertClosedRequiredObject(JsonNode schema) {
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
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
            CapabilityObservationEnvelope envelope,
            CapabilityObservationAdmission admission
    ) {
    }
}
