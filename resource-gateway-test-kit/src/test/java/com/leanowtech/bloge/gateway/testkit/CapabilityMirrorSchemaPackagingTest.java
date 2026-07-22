package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityMirrorSchemaPackagingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testKitPackagesEveryMirrorSchemaAndTheValidatedStageZeroBaseline() throws Exception {
        for (String name : List.of(
                "artifact-provenance-v1.schema.json",
                "effect-contract-v1.schema.json",
                "capability-contract-v1.schema.json",
                "capability-snapshot-v1.schema.json",
                "capability-closure-v1.schema.json",
                "capability-lifecycle-transition-v1.schema.json",
                "capability-mirror-compatibility-v1.schema.json",
                "mirror-resolution-v1.schema.json",
                "mirror-run-evidence-v1.schema.json",
                "mirror-evidence-attestation-v1.schema.json",
                "mirror-evidence-bundle-v1.schema.json")) {
            String resource = CapabilityMirrorProtocol.SCHEMA_RESOURCE_ROOT + name;
            try (InputStream input = getClass().getResourceAsStream(resource)) {
                assertThat(input).as(resource).isNotNull();
                JsonNode schema = objectMapper.readTree(input);
                assertThat(schema.path("$schema").asText())
                        .isEqualTo("https://json-schema.org/draft/2020-12/schema");
                assertThat(schema.path("$id").asText()).endsWith(name);
            }
        }

        JsonNode baseline = CapabilityMirrorProtocol.compatibilityBaseline();
        assertThat(baseline.path("schemaVersion").asText())
                .isEqualTo(CapabilityMirrorProtocol.COMPATIBILITY_V1);
        assertThat(baseline.path("requiredObjects").size()).isEqualTo(6);
        assertThat(baseline.path("requiredFeatures").size()).isEqualTo(6);
        assertThat(baseline.path("deferredFeatures").size()).isEqualTo(3);
    }

    @Test
    void callersCannotMutateTheProcessWideCompatibilityBaseline() {
        JsonNode first = CapabilityMirrorProtocol.compatibilityBaseline();
        ((com.fasterxml.jackson.databind.node.ObjectNode) first).put("protocol", "changed");

        assertThat(CapabilityMirrorProtocol.compatibilityBaseline().path("protocol").asText())
                .isEqualTo(CapabilityMirrorProtocol.INTEGRATION_PROTOCOL);
    }

    @Test
    void packagesOneFixedIndependentlyVerifiableStageOneEvidenceFixture() {
        MirrorEvidenceCompatibilityFixture fixture =
                CapabilityMirrorProtocol.mirrorEvidenceCompatibilityFixture();

        MirrorEvidenceVerifier.VerificationResult verified = new MirrorEvidenceVerifier()
                .verify(fixture.bundle(), fixture.verificationKey());

        assertThat(verified.verified()).isTrue();
        assertThat(verified.runId()).isEqualTo("mirror-run-schema");
        ((com.fasterxml.jackson.databind.node.ObjectNode) fixture.bundle())
                .put("bundleFingerprint", "changed");
        assertThat(new MirrorEvidenceVerifier().verify(
                CapabilityMirrorProtocol.mirrorEvidenceCompatibilityFixture().bundle(),
                fixture.verificationKey()).verified()).isTrue();
    }
}
