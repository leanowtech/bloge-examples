package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoritativeOutcomeSelectedPopulationCompatibilityFixtureTest {
    private static final String FIXTURE =
            "authoritative-outcome-selected-population-stage1-v1.fixture.json";
    private final ObjectMapper mapper =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(
                            SerializationFeature
                                    .WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void serverRehydratesEveryPublicFixtureArtifactAndSignature()
            throws Exception {
        JsonNode fixture = mapper.readTree(
                Files.readString(fixturePath()));
        assertThat(fixture.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrderElementsOf(
                        Set.of(
                                "schemaVersion",
                                "verificationTime",
                                "verificationKey",
                                "populationBundle",
                                "observations",
                                "dispositions",
                                "assessment",
                                "sourcePages"));
        assertThat(fixture.path("schemaVersion").asText())
                .isEqualTo(
                        "resourceGateway.authoritativeOutcomeSelectedPopulationCompatibility.v1");
        JsonNode key = fixture.path(
                "verificationKey");
        AuthoritativeOutcomeSelectedPopulationBundle
                bundle = mapper.treeToValue(
                fixture.path("populationBundle"),
                AuthoritativeOutcomeSelectedPopulationBundle
                        .class);

        bundle.manifest().verify(mapper);
        assertSignature(
                key,
                bundle.manifest().manifestSeal(),
                bundle.manifest()
                        .attestationMaterialFingerprint(
                                mapper));
        for (AuthoritativeOutcomeSelectedPopulationChunk
                chunk : bundle.chunks()) {
            chunk.verify(mapper);
        }

        List<AuthoritativeOutcomeObservation>
                observations =
                mapper.readerForListOf(
                                AuthoritativeOutcomeObservation
                                        .class)
                        .readValue(
                                fixture.path("observations"));
        for (AuthoritativeOutcomeObservation
                observation : observations) {
            observation.verify(mapper);
            assertSignature(
                    key,
                    observation.observationSeal(),
                    observation
                            .attestationMaterialFingerprint(
                                    mapper));
        }

        List<AuthoritativeOutcomeSelectedPopulationDisposition>
                dispositions =
                mapper.readerForListOf(
                                AuthoritativeOutcomeSelectedPopulationDisposition
                                        .class)
                        .readValue(
                                fixture.path("dispositions"));
        for (AuthoritativeOutcomeSelectedPopulationDisposition
                disposition : dispositions) {
            disposition.verify(mapper);
            assertSignature(
                    key,
                    disposition.dispositionSeal(),
                    disposition
                            .attestationMaterialFingerprint(
                                    mapper));
        }

        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                assessment = mapper.treeToValue(
                fixture.path("assessment"),
                AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                        .class);
        assessment.verify(mapper);
        assertSignature(
                key,
                assessment.assessmentSeal(),
                assessment
                        .attestationMaterialFingerprint(
                                mapper));
        List<AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage>
                pages =
                mapper.readerForListOf(
                                AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage
                                        .class)
                        .readValue(
                                fixture.path("sourcePages"));
        for (AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage
                page : pages) {
            page.verify(mapper);
        }

        assertThat(bundle.manifest()
                .totalSelectedPopulation())
                .isEqualTo(3);
        assertThat(observations)
                .extracting(
                        AuthoritativeOutcomeObservation
                                ::reconciliation)
                .containsExactly(
                        AuthoritativeOutcomeObservation
                                .Reconciliation.MATCH,
                        AuthoritativeOutcomeObservation
                                .Reconciliation.MISMATCH);
        assertThat(dispositions).hasSize(1);
        assertThat(assessment.totals().matched())
                .isOne();
        assertThat(assessment.totals().mismatched())
                .isOne();
        assertThat(assessment.totals()
                .legallyDeleted()).isOne();
        assertThat(assessment.totals().missing())
                .isZero();
        assertThat(pages).hasSize(1);
        assertThat(pages.getFirst().entries())
                .hasSize(3);
        assertThat(pages.getFirst().complete())
                .isTrue();
    }

    @Test
    void fixtureContainsNoPrivateKeyCredentialEndpointOrBusinessPayload()
            throws Exception {
        String fixture = Files.readString(
                fixturePath());

        assertThat(fixture)
                .doesNotContainIgnoringCase("privateKey")
                .doesNotContainIgnoringCase("credential")
                .doesNotContainIgnoringCase("endpoint")
                .doesNotContain("\"request\"")
                .doesNotContain("\"response\"")
                .doesNotContain("\"payload\"");
    }

    private static void assertSignature(
            JsonNode key,
            VisualRunEvidenceSeal seal,
            String material)
            throws Exception {
        assertThat(seal.keyId())
                .isEqualTo(
                        key.path("keyId").asText());
        assertThat(seal.materialFingerprint())
                .isEqualTo(material);
        java.security.PublicKey publicKey =
                KeyFactory.getInstance("Ed25519")
                        .generatePublic(
                                new X509EncodedKeySpec(
                                        Base64.getDecoder()
                                                .decode(
                                                        key.path(
                                                                "encodedPublicKey")
                                                                .asText())));
        Signature signature =
                Signature.getInstance("Ed25519");
        signature.initVerify(publicKey);
        signature.update(
                material.getBytes(
                        StandardCharsets.UTF_8));
        assertThat(signature.verify(
                Base64.getDecoder().decode(
                        seal.signature())))
                .isTrue();
    }

    private static Path fixturePath() {
        return Path.of(
                "..",
                "docs",
                "schemas",
                "resource-gateway-mirror",
                FIXTURE);
    }
}
