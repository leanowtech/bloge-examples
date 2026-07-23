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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        JsonNode bundleSchema = schema("mirror-evidence-bundle-v2.schema.json");
        JsonNode evidenceSchema = schema("mirror-run-evidence-v2.schema.json");
        JsonNode attestationSchema = schema("mirror-evidence-attestation-v2.schema.json");
        JsonNode sharedEvidenceSchema = schema("mirror-run-evidence-v1.schema.json");
        JsonNode runTrustSchema = schema(
                "mirror-deployment-isolation-run-trust-v1.schema.json");

        assertProperties(bundleValue, bundleSchema.path("properties"));
        assertProperties(evidenceValue, evidenceSchema.path("properties"));
        assertProperties(attestationValue, attestationSchema.path("properties"));
        assertProperties(evidenceValue.path("rootCapability"),
                sharedEvidenceSchema.at("/$defs/artifactRef/properties"));
        assertProperties(evidenceValue.path("scope"),
                sharedEvidenceSchema.at("/$defs/scope/properties"));
        assertProperties(evidenceValue.path("externalBindings").get(0),
                sharedEvidenceSchema.at("/$defs/externalBinding/properties"));
        assertProperties(evidenceValue.path("nodeTraces").get(0),
                sharedEvidenceSchema.at("/$defs/nodeTrace/properties"));
        assertProperties(evidenceValue.at("/nodeTraces/0/attempts/0"),
                sharedEvidenceSchema.at("/$defs/attemptTrace/properties"));
        assertProperties(evidenceValue.path("edgeTraces").get(0),
                sharedEvidenceSchema.at("/$defs/edgeTrace/properties"));
        assertProperties(evidenceValue.path("isolation"),
                evidenceSchema.at("/$defs/isolation/properties"));
        assertProperties(evidenceValue.at("/isolation/deploymentTrustBinding"),
                runTrustSchema.path("properties"));
        assertThat(bundleSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(evidenceSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(attestationSchema.path("additionalProperties").asBoolean()).isFalse();
        for (String definition : List.of("artifactRef", "scope", "externalBinding", "nodeTrace", "attemptTrace",
                "edgeTrace", "isolation")) {
            assertThat(sharedEvidenceSchema.at("/$defs/" + definition
                    + "/additionalProperties")
                    .asBoolean()).as(definition).isFalse();
        }
        assertThat(runTrustSchema.path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void schemasFreezePayloadOmissionAndDetachedSignaturePolicy() throws Exception {
        JsonNode bundle = schema("mirror-evidence-bundle-v2.schema.json");
        JsonNode evidence = schema("mirror-run-evidence-v2.schema.json");
        JsonNode attestation = schema("mirror-evidence-attestation-v2.schema.json");
        JsonNode sharedEvidence = schema("mirror-run-evidence-v1.schema.json");

        assertThat(bundle.at("/properties/payloadPolicy/const").asText()).isEqualTo("HASH_ONLY");
        assertThat(bundle.at("/properties/evidence/$ref").asText())
                .isEqualTo("mirror-run-evidence-v2.schema.json");
        assertThat(bundle.at("/properties/attestation/allOf/0/$ref").asText())
                .isEqualTo("mirror-evidence-attestation-v2.schema.json");
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
        assertThat(sharedEvidence.at("/$defs/nodeTrace/properties").has("input")).isFalse();
        assertThat(sharedEvidence.at("/$defs/nodeTrace/properties").has("output")).isFalse();
        assertThat(sharedEvidence.at("/$defs/edgeTrace/properties").has("value")).isFalse();
        assertThat(sharedEvidence.at("/$defs/nodeTrace/properties")
                .has("inputFingerprint")).isTrue();
        assertThat(sharedEvidence.at("/$defs/nodeTrace/properties")
                .has("outputFingerprint")).isTrue();
        assertThat(sharedEvidence.at("/$defs/edgeTrace/properties")
                .has("valueFingerprint")).isTrue();
        assertThat(evidence.at("/$defs/isolation/properties/deploymentTrustBinding/$ref")
                .asText()).isEqualTo(
                        "mirror-deployment-isolation-run-trust-v1.schema.json");
    }

    @Test
    void statefulSchemasCloseServerV3EvidenceAndWorkbookSeedFields()
            throws Exception {
        MirrorPlan plan = MirrorPersistenceTestFixtures.plan(
                mapper,
                MirrorPersistenceTestFixtures.scope("org-a"),
                "stateful-schema-plan", 'c');
        MirrorEvidenceBundle bundle =
                MirrorPersistenceTestFixtures.statefulEvidence(
                        mapper,
                        new InMemoryVisualEvidenceSigner(),
                        plan, "stateful-schema-run", 'd');
        MirrorStateWorkbookSeed seed =
                MirrorStateWorkbookSeed.project(mapper, bundle);
        JsonNode bundleValue = mapper.valueToTree(bundle);
        JsonNode evidenceValue =
                bundleValue.path("evidence");
        JsonNode stateValue =
                evidenceValue.path("stateEvidence");
        JsonNode seedValue = mapper.valueToTree(seed);
        JsonNode bundleSchema = schema(
                "mirror-evidence-bundle-v3.schema.json");
        JsonNode evidenceSchema = schema(
                "mirror-run-evidence-v3.schema.json");
        JsonNode attestationSchema = schema(
                "mirror-evidence-attestation-v3.schema.json");
        JsonNode stateSchema = schema(
                "mirror-state-run-evidence-v1.schema.json");
        JsonNode seedSchema = schema(
                "mirror-state-workbook-seed-v1.schema.json");

        assertProperties(
                bundleValue, bundleSchema.path("properties"));
        assertProperties(
                evidenceValue, evidenceSchema.path("properties"));
        assertProperties(
                bundleValue.path("attestation"),
                attestationSchema.path("properties"));
        assertProperties(
                stateValue, stateSchema.path("properties"));
        assertProperties(
                stateValue.path("statefulBindings").get(0),
                stateSchema.at(
                        "/$defs/statefulBinding/properties"));
        assertProperties(
                seedValue, seedSchema.path("properties"));
        assertThat(bundleSchema.at(
                "/properties/evidence/$ref").asText())
                .isEqualTo(
                        "mirror-run-evidence-v3.schema.json");
        assertThat(evidenceSchema.at(
                "/properties/stateEvidence/$ref").asText())
                .isEqualTo(
                        "mirror-state-run-evidence-v1.schema.json");
        assertThat(stateSchema.path(
                "additionalProperties").asBoolean()).isFalse();
        assertThat(stateSchema.at(
                "/$defs/stateAccess/additionalProperties")
                .asBoolean()).isFalse();
        assertThat(seedSchema.path(
                "additionalProperties").asBoolean()).isFalse();
        seed.verify(mapper);
        assertThatThrownBy(() -> new MirrorStateWorkbookSeed(
                seed.schemaVersion(), seed.seedFingerprint(),
                seed.runId(), seed.planFingerprint(),
                seed.evidenceBundleFingerprint(),
                seed.stateEvidenceRef(), seed.sessionStateRef(),
                seed.stateModelRef(), seed.stateRevision(),
                seed.worldFingerprint(), seed.logicalClock(),
                seed.mode(), seed.runStatus(), seed.evidenceClass(),
                seed.bindingCount(),
                MirrorStateWorkbookSeed.MAXIMUM_ACCESSES + 1,
                MirrorStateWorkbookSeed.MAXIMUM_ACCESSES + 1,
                0, 0, seed.gateReady(), seed.blockers()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("counts are inconsistent");
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
        Instant started = Instant.parse("2026-07-23T00:00:10Z");
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
                MirrorRunEvidence.EvidenceClass.CERTIFIABLE, fingerprint('a'), started,
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
                        false, false, false, true,
                        MirrorPersistenceTestFixtures.trustBinding(
                                new CapabilitySnapshot.Scope(
                                        "tenant-a", "org-a", "support", "test", "sg"))
                                .attestationRef(),
                        MirrorPersistenceTestFixtures.trustBinding(
                                new CapabilitySnapshot.Scope(
                                        "tenant-a", "org-a", "support", "test", "sg")),
                        List.of()), List.of());
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
