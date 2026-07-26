package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnlineReadOnlyShadowWorkerCompatibilityFixtureTest {
    private static final String FIXTURE =
            "online-read-only-shadow-worker-stage1-v1.fixture.json";
    private final ObjectMapper mapper =
            OnlineReadOnlyShadowBaselineTestFixtures.mapper();

    @Test
    void serverRehydratesAndVerifiesTheCompleteDurableOnlineWorkerChain()
            throws Exception {
        JsonNode fixture = fixture();
        assertThat(fixture.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrderElementsOf(
                        Set.of(
                                "schemaVersion",
                                "verificationTime",
                                "verificationKeys",
                                "expected",
                                "request",
                                "job",
                                "lifecyclePage",
                                "comparison",
                                "baselineCommand",
                                "baselineObservation",
                                "candidateCommand",
                                "candidateEvidenceBundle",
                                "attestation"));
        assertThat(fixture.path("schemaVersion")
                .asText())
                .isEqualTo(
                        "resourceGateway.onlineReadOnlyShadowWorkerCompatibility.v1");
        Instant verificationTime =
                Instant.parse(
                        fixture.path(
                                "verificationTime")
                                .asText());
        Clock verificationClock =
                Clock.fixed(
                        verificationTime,
                        ZoneOffset.UTC);
        JsonNode keys =
                fixture.path("verificationKeys");
        VisualEvidenceSigner comparisonVerifier =
                publicKeyVerifier(
                        keys.path("comparison"));
        VisualEvidenceSigner baselineVerifier =
                publicKeyVerifier(
                        keys.path(
                                "baselineObservation"));
        VisualEvidenceSigner candidateVerifier =
                publicKeyVerifier(
                        keys.path(
                                "candidateEvidence"));
        VisualEvidenceSigner resolutionVerifier =
                publicKeyVerifier(
                        keys.path(
                                "sourceResolution"));

        ReadOnlyShadowJobRequest request =
                mapper.treeToValue(
                        fixture.path("request"),
                        ReadOnlyShadowJobRequest.class);
        ReadOnlyShadowJob job =
                mapper.treeToValue(
                        fixture.path("job"),
                        ReadOnlyShadowJob.class);
        ReadOnlyShadowJobLifecyclePage page =
                mapper.treeToValue(
                        fixture.path("lifecyclePage"),
                        ReadOnlyShadowJobLifecyclePage.class);
        ReadOnlyShadowComparison comparison =
                mapper.treeToValue(
                        fixture.path("comparison"),
                        ReadOnlyShadowComparison.class);
        OnlineReadOnlyShadowBaselineCommand baselineCommand =
                mapper.treeToValue(
                        fixture.path("baselineCommand"),
                        OnlineReadOnlyShadowBaselineCommand.class);
        OnlineReadOnlyShadowBaselineObservation baseline =
                mapper.treeToValue(
                        fixture.path(
                                "baselineObservation"),
                        OnlineReadOnlyShadowBaselineObservation.class);
        OnlineReadOnlyShadowCandidateCommand candidateCommand =
                mapper.treeToValue(
                        fixture.path("candidateCommand"),
                        OnlineReadOnlyShadowCandidateCommand.class);
        MirrorEvidenceBundle candidate =
                mapper.treeToValue(
                        fixture.path(
                                "candidateEvidenceBundle"),
                        MirrorEvidenceBundle.class);
        ReadOnlyShadowSourceResolutionAttestation attestation =
                mapper.treeToValue(
                        fixture.path("attestation"),
                        ReadOnlyShadowSourceResolutionAttestation.class);

        ReadOnlyShadowJobIntegrity.verify(
                mapper, job);
        assertThat(
                ReadOnlyShadowJobIntegrity
                        .requestFingerprint(
                                mapper,
                                request))
                .isEqualTo(
                        job.requestFingerprint());
        assertThat(
                ReadOnlyShadowJobIntegrity.jobId(
                        job.requestFingerprint()))
                .isEqualTo(job.jobId());
        assertThat(page.events())
                .extracting(
                        ReadOnlyShadowJobLifecycleEvent
                                ::transition)
                .contains(
                        ReadOnlyShadowJobLifecycleEvent
                                .Transition.TAKEN_OVER,
                        ReadOnlyShadowJobLifecycleEvent
                                .Transition.SUCCEEDED);
        assertThat(page.events().getLast()
                .recordFingerprint())
                .isEqualTo(job.recordFingerprint());
        assertThat(page.events().getLast()
                .comparisonFingerprint())
                .isEqualTo(
                        comparison
                                .comparisonFingerprint());

        assertThat(
                new ReadOnlyShadowComparisonIntegrity(
                        mapper,
                        comparisonVerifier,
                        verificationClock)
                        .verify(comparison))
                .isEqualTo(comparison);
        assertThat(
                new OnlineReadOnlyShadowBaselineObservationIntegrity(
                        mapper,
                        OnlineReadOnlyShadowBaselineEvidenceAuthority
                                .from(
                                        baselineVerifier),
                        verificationClock)
                        .verify(baseline))
                .isEqualTo(
                        OnlineReadOnlyShadowBaselineObservationIntegrity
                                .Verification.VERIFIED);
        assertThat(
                new MirrorEvidenceIntegrityService(
                        mapper,
                        candidateVerifier,
                        verificationClock)
                        .verify(candidate))
                .isEqualTo(
                        MirrorEvidenceIntegrityService
                                .Verification.VERIFIED);
        assertThat(
                new ReadOnlyShadowSourceResolutionAttestationIntegrity(
                        mapper,
                        resolutionVerifier,
                        verificationClock)
                        .verify(attestation))
                .isEqualTo(attestation);

        JsonNode expected =
                fixture.path("expected");
        CapabilitySnapshot.Scope expectedScope =
                mapper.treeToValue(
                        expected.path("scope"),
                        CapabilitySnapshot.Scope.class);
        MirrorArtifactRef expectedComparisonRef =
                mapper.treeToValue(
                        expected.path(
                                "comparisonRef"),
                        MirrorArtifactRef.class);
        MirrorArtifactRef expectedAttestationRef =
                mapper.treeToValue(
                        expected.path(
                                "sourceResolutionAttestationRef"),
                        MirrorArtifactRef.class);
        assertThat(request.scope())
                .isEqualTo(expectedScope);
        assertThat(job.jobId())
                .isEqualTo(
                        expected.path("jobId")
                                .asText())
                .isEqualTo(
                        comparison.comparisonId())
                .isEqualTo(
                        attestation.executionId())
                .isEqualTo(
                        baselineCommand
                                .executionId())
                .isEqualTo(
                        candidateCommand
                                .executionId());
        assertThat(comparison.artifactRef())
                .isEqualTo(
                        expectedComparisonRef)
                .isEqualTo(job.comparisonRef());
        assertThat(attestation.artifactRef())
                .isEqualTo(
                        expectedAttestationRef)
                .isEqualTo(
                        comparison
                                .sourceResolutionAttestationRef());
        assertThat(attestation.baseline()
                .artifactRef())
                .isEqualTo(
                        baseline.artifactRef())
                .isEqualTo(
                        comparison.baseline()
                                .artifactRef());
        assertThat(attestation.candidate()
                .artifactRef())
                .isEqualTo(
                        new MirrorArtifactRef(
                                "MIRROR_EVIDENCE_BUNDLE",
                                candidate.evidence()
                                        .runId(),
                                1,
                                candidate
                                        .bundleFingerprint()))
                .isEqualTo(
                        comparison.candidate()
                                .artifactRef());
        assertThat(attestation.issuedAt())
                .isBeforeOrEqualTo(
                        comparison.observedAt());
        assertThat(comparison.observedAt())
                .isBeforeOrEqualTo(
                        job.completedAt());
    }

    @Test
    void serverRejectsCrossRolePublicKeys()
            throws Exception {
        JsonNode fixture = fixture();
        Instant verificationTime =
                Instant.parse(
                        fixture.path(
                                "verificationTime")
                                .asText());
        Clock clock = Clock.fixed(
                verificationTime,
                ZoneOffset.UTC);
        JsonNode keys =
                fixture.path("verificationKeys");
        ReadOnlyShadowComparison comparison =
                mapper.treeToValue(
                        fixture.path("comparison"),
                        ReadOnlyShadowComparison.class);
        ReadOnlyShadowSourceResolutionAttestation attestation =
                mapper.treeToValue(
                        fixture.path("attestation"),
                        ReadOnlyShadowSourceResolutionAttestation.class);

        assertThatThrownBy(() ->
                new ReadOnlyShadowComparisonIntegrity(
                        mapper,
                        publicKeyVerifier(
                                keys.path(
                                        "baselineObservation")),
                        clock)
                        .verify(comparison))
                .isInstanceOf(
                        ReadOnlyShadowComparisonIntegrity
                                .Violation.class);
        assertThatThrownBy(() ->
                new ReadOnlyShadowSourceResolutionAttestationIntegrity(
                        mapper,
                        publicKeyVerifier(
                                keys.path(
                                        "comparison")),
                        clock)
                        .verify(attestation))
                .isInstanceOf(
                        ReadOnlyShadowSourceResolutionAttestationIntegrity
                                .Violation.class);
    }

    @Test
    void fixtureContainsNoPrivateKeyEndpointCredentialWorkerOrBusinessPayload()
            throws Exception {
        String fixture =
                Files.readString(fixturePath());

        assertThat(fixture)
                .doesNotContainIgnoringCase(
                        "privateKey")
                .doesNotContain(
                        "\"requestPayload\"")
                .doesNotContain(
                        "\"responsePayload\"")
                .doesNotContain(
                        "\"requestBody\"")
                .doesNotContain(
                        "\"responseBody\"")
                .doesNotContain(
                        "\"endpointUri\"")
                .doesNotContain(
                        "\"credential\"")
                .doesNotContain(
                        "\"ownerId\"");
    }

    private JsonNode fixture()
            throws Exception {
        return mapper.readTree(
                Files.readString(
                        fixturePath()));
    }

    private static Path fixturePath() {
        return Path.of(
                "..",
                "docs",
                "schemas",
                "resource-gateway-mirror",
                FIXTURE);
    }

    private static VisualEvidenceSigner
    publicKeyVerifier(JsonNode key)
            throws Exception {
        java.security.PublicKey publicKey =
                KeyFactory.getInstance("Ed25519")
                        .generatePublic(
                                new X509EncodedKeySpec(
                                        Base64.getDecoder()
                                                .decode(
                                                        key.path(
                                                                "encodedPublicKey")
                                                                .asText())));
        String expectedKeyId =
                key.path("keyId").asText();
        return new VisualEvidenceSigner() {
            @Override
            public VisualRunEvidenceSeal seal(
                    String materialFingerprint) {
                throw new UnsupportedOperationException(
                        "compatibility fixture verifier cannot sign");
            }

            @Override
            public Verification verify(
                    VisualRunEvidenceSeal seal,
                    String actualMaterialFingerprint) {
                if (!expectedKeyId.equals(
                        seal.keyId())) {
                    return new Verification(
                            false,
                            "INVALID",
                            "fixture key role mismatch");
                }
                try {
                    Signature signature =
                            Signature.getInstance(
                                    "Ed25519");
                    signature.initVerify(publicKey);
                    signature.update(
                            actualMaterialFingerprint
                                    .getBytes(
                                            StandardCharsets
                                                    .UTF_8));
                    return new Verification(
                            signature.verify(
                                    Base64.getDecoder()
                                            .decode(
                                                    seal.signature())),
                            "VERIFIED",
                            "");
                } catch (Exception failure) {
                    return new Verification(
                            false,
                            "INVALID",
                            "fixture verification failed");
                }
            }

            @Override
            public Optional<VerificationKey> key(
                    String keyId) {
                return Optional.empty();
            }

            @Override
            public boolean available() {
                return true;
            }
        };
    }
}
