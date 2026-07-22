package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MirrorEvidenceProtocolSchemaTest {
    private static final String PLAN = fingerprint('1');
    private static final String RUN = "mirror-run-schema";
    private static final MirrorArtifactRef CAPABILITY = new MirrorArtifactRef(
            "CAPABILITY", "resource:customer.lookup", 1, fingerprint('2'));
    private static final MirrorArtifactRef FIXTURE = new MirrorArtifactRef(
            "FIXTURE_BUNDLE", "customer-fixture", 1, fingerprint('3'));

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void strictSchemasCloseEverySerializedEvidenceField() throws Exception {
        MirrorEvidenceBundle bundle = bundle();
        JsonNode bundleValue = mapper.valueToTree(bundle);
        JsonNode evidenceValue = bundleValue.path("evidence");
        JsonNode attestationValue = bundleValue.path("attestation");
        JsonNode bundleSchema = schema("mirror-evidence-bundle-v1.schema.json");
        JsonNode evidenceSchema = schema("mirror-run-evidence-v1.schema.json");
        JsonNode attestationSchema = schema("mirror-evidence-attestation-v1.schema.json");

        assertProperties(bundleValue, bundleSchema.path("properties"));
        assertProperties(evidenceValue, evidenceSchema.path("properties"));
        assertProperties(attestationValue, attestationSchema.path("properties"));
        assertProperties(evidenceValue.path("rootCapability"),
                evidenceSchema.at("/$defs/artifactRef/properties"));
        assertProperties(evidenceValue.path("scope"),
                evidenceSchema.at("/$defs/scope/properties"));
        assertProperties(evidenceValue.path("externalBindings").get(0),
                evidenceSchema.at("/$defs/externalBinding/properties"));
        assertProperties(evidenceValue.path("nodeTraces").get(0),
                evidenceSchema.at("/$defs/nodeTrace/properties"));
        assertProperties(evidenceValue.at("/nodeTraces/0/attempts/0"),
                evidenceSchema.at("/$defs/attemptTrace/properties"));
        assertProperties(evidenceValue.path("edgeTraces").get(0),
                evidenceSchema.at("/$defs/edgeTrace/properties"));
        assertProperties(evidenceValue.path("isolation"),
                evidenceSchema.at("/$defs/isolation/properties"));
        assertThat(bundleSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(evidenceSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(attestationSchema.path("additionalProperties").asBoolean()).isFalse();
        for (String definition : List.of("artifactRef", "scope", "externalBinding", "nodeTrace", "attemptTrace",
                "edgeTrace", "isolation")) {
            assertThat(evidenceSchema.at("/$defs/" + definition + "/additionalProperties")
                    .asBoolean()).as(definition).isFalse();
        }
    }

    @Test
    void schemasFreezePayloadOmissionAndDetachedSignaturePolicy() throws Exception {
        JsonNode bundle = schema("mirror-evidence-bundle-v1.schema.json");
        JsonNode evidence = schema("mirror-run-evidence-v1.schema.json");
        JsonNode attestation = schema("mirror-evidence-attestation-v1.schema.json");

        assertThat(bundle.at("/properties/payloadPolicy/const").asText()).isEqualTo("HASH_ONLY");
        assertThat(bundle.at("/properties/evidence/$ref").asText())
                .isEqualTo("mirror-run-evidence-v1.schema.json");
        assertThat(bundle.at("/properties/attestation/allOf/0/$ref").asText())
                .isEqualTo("mirror-evidence-attestation-v1.schema.json");
        assertThat(evidence.at("/properties/resolutions/items/$ref").asText())
                .isEqualTo("mirror-resolution-v1.schema.json");
        assertThat(attestation.at("/properties/signatureStatus/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("VERIFIED", "VERIFICATION_UNAVAILABLE");
        assertThat(attestation.at("/allOf/0/then/properties/algorithm/const").asText())
                .isEqualTo("Ed25519");
        assertThat(bundle.at("/properties/attestation/allOf/1/properties/signatureStatus/const")
                .asText()).isEqualTo("VERIFIED");
        assertThat(bundle.at("/properties/attestation/allOf/1/properties/independentlyVerifiable/const")
                .asBoolean()).isTrue();
        assertThat(evidence.at("/$defs/nodeTrace/properties").has("input")).isFalse();
        assertThat(evidence.at("/$defs/nodeTrace/properties").has("output")).isFalse();
        assertThat(evidence.at("/$defs/edgeTrace/properties").has("value")).isFalse();
        assertThat(evidence.at("/$defs/nodeTrace/properties").has("inputFingerprint")).isTrue();
        assertThat(evidence.at("/$defs/nodeTrace/properties").has("outputFingerprint")).isTrue();
        assertThat(evidence.at("/$defs/edgeTrace/properties").has("valueFingerprint")).isTrue();
    }

    @Test
    void serverAndIndependentClientShareOneCryptographicallyValidCompatibilityFixture()
            throws Exception {
        JsonNode fixture = mapper.readTree(Files.readString(Path.of("..", "docs", "schemas",
                "resource-gateway-mirror", "mirror-evidence-stage1-v1.fixture.json")));
        MirrorEvidenceBundle bundle = mapper.treeToValue(fixture.path("bundle"),
                MirrorEvidenceBundle.class);
        VisualEvidenceSigner verifier = publicKeyVerifier(
                fixture.path("verificationKey").path("encodedPublicKey").asText());

        assertThat(fixture.path("schemaVersion").asText())
                .isEqualTo("resourceGateway.mirrorEvidenceCompatibility.v1");
        MirrorResolutionIntegrity.verify(mapper, bundle.evidence().resolutions().getFirst());
        assertThat(VisualBundleFingerprint.fromCanonicalValue(mapper, bundle.evidence(),
                MirrorEvidenceIntegrityService.MAXIMUM_EVIDENCE_BYTES))
                .isEqualTo(bundle.attestation().evidenceFingerprint());
        assertThat(new MirrorEvidenceIntegrityService(mapper, verifier, Clock.systemUTC())
                .verify(bundle)).isEqualTo(MirrorEvidenceIntegrityService.Verification.VERIFIED);
    }

    private MirrorEvidenceBundle bundle() {
        Instant started = Instant.parse("2026-07-23T00:00:00Z");
        String request = fingerprint('4');
        String output = fingerprint('5');
        MirrorResolution resolution = MirrorResolutionIntegrity.seal(mapper,
                new MirrorResolution("", "", RUN, PLAN, CAPABILITY,
                        "/root/loadCustomer#PRIMARY", "/root", "C-1", 1, 1, request,
                        MirrorResolution.Status.RESOLVED,
                        MirrorPlan.MirrorSource.OWNER_SPECIFIED,
                        MirrorResolution.PayloadVisibility.HASH_ONLY, false, null, output, null,
                        List.of(FIXTURE), List.of("customer-response"),
                        new ArtifactProvenance.Confidence(1, 1, 1, "owner-rule-v1"), 1,
                        List.of("PAYLOAD_HASH_ONLY")));
        MirrorArtifactRef root = new MirrorArtifactRef(
                "CAPABILITY", "graph:support", 1, fingerprint('9'));
        MirrorRunEvidence evidence = new MirrorRunEvidence("", RUN, "request-1",
                fingerprint('6'), "plan-1", PLAN, fingerprint('7'), fingerprint('8'),
                root, FIXTURE, List.of(new MirrorRunEvidence.ExternalBinding(root,
                "loadCustomer", CAPABILITY, "/root/loadCustomer#PRIMARY", "/root")),
                new CapabilitySnapshot.Scope("tenant-a", "org-a", "support", "test", "sg"),
                "MIRROR_REHEARSAL", MirrorRunEvidence.Status.PASSED,
                MirrorRunEvidence.EvidenceClass.EXPLORATORY, fingerprint('a'), started,
                started.plusSeconds(1),
                List.of(new MirrorRunEvidence.NodeTrace("loadCustomer", "customer.lookup",
                        "MOCKED", "OUTPUT_LEVEL", request, output, "", 1,
                        "/root/loadCustomer#PRIMARY", "/root", "C-1", 1, 1,
                        List.of(new MirrorRunEvidence.AttemptTrace(1, "MOCKED", "OUTPUT_LEVEL",
                                request, output, "", 1)))),
                List.of(new MirrorRunEvidence.EdgeTrace("loadCustomer->format", "TRANSFERRED",
                        output, "/root", "C-1", 1, "/root/loadCustomer#PRIMARY",
                        "/root/format#PRIMARY")),
                List.of(resolution), new MirrorRunEvidence.IsolationFacts(
                        MirrorRunEvidence.IsolationFacts.EngineMode.INDEPENDENT_TEST_ENGINE,
                        List.of(), List.of("InvocationRecorder"), false, false, false,
                        false, false, false, false, null,
                        List.of("DEPLOYMENT_EGRESS_NOT_ATTESTED")),
                List.of("DEPLOYMENT_EGRESS_NOT_ATTESTED"));
        return new MirrorEvidenceIntegrityService(mapper, new InMemoryVisualEvidenceSigner(),
                Clock.fixed(started.plusSeconds(2), ZoneOffset.UTC)).seal(evidence).bundle();
    }

    private JsonNode schema(String file) throws Exception {
        return mapper.readTree(Files.readString(Path.of("..", "docs", "schemas",
                "resource-gateway-mirror", file)));
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        properties.fieldNames().forEachRemaining(expected::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static VisualEvidenceSigner publicKeyVerifier(String encodedPublicKey)
            throws Exception {
        java.security.PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(encodedPublicKey)));
        return new VisualEvidenceSigner() {
            @Override
            public VisualRunEvidenceSeal seal(String materialFingerprint) {
                throw new UnsupportedOperationException("fixture verifier cannot sign");
            }

            @Override
            public Verification verify(
                    VisualRunEvidenceSeal seal, String actualMaterialFingerprint) {
                try {
                    Signature signature = Signature.getInstance("Ed25519");
                    signature.initVerify(publicKey);
                    signature.update(actualMaterialFingerprint.getBytes(StandardCharsets.UTF_8));
                    return new Verification(signature.verify(
                            Base64.getDecoder().decode(seal.signature())), "VERIFIED", "");
                } catch (Exception failure) {
                    return new Verification(false, "INVALID", "fixture verification failed");
                }
            }

            @Override
            public Optional<VerificationKey> key(String keyId) {
                return Optional.empty();
            }

            @Override
            public boolean available() {
                return true;
            }
        };
    }
}
