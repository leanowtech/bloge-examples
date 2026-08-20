package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioStageAcceptanceTargetBindingVerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CapabilityStudioStageAcceptanceTargetBindingVerifier VERIFIER =
            new CapabilityStudioStageAcceptanceTargetBindingVerifier();
    private static final Instant NOW = Instant.parse("2026-01-01T00:10:00Z");
    private static final String STARTED = "2026-01-01T00:00:00Z";
    private static final String COMPLETED = "2026-01-01T00:05:00Z";
    private static final String DECIDED = "2026-01-01T00:07:00Z";
    private static final String EXPIRES = "2026-01-01T01:00:00Z";
    private static final String SCOPE = "tenant:demo/environment:acceptance";
    private static final String LEASE = "lease:stage-acceptance:1";

    @Test
    void verifiesRawDocumentsAndOnlyPassesTypedFactsToBothAuthorities() throws Exception {
        Fixture fixture = fixture();
        AtomicReference<CapabilityStudioStageAcceptanceTargetBindingVerifier.CandidateAttestationFacts>
                candidateFacts = new AtomicReference<>();
        AtomicReference<CapabilityStudioStageAcceptanceTargetBindingVerifier.EnvironmentAttestationFacts>
                environmentFacts = new AtomicReference<>();
        List<String> callbackOrder = new ArrayList<>();

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verify(fixture,
                        facts -> {
                            callbackOrder.add("candidate");
                            candidateFacts.set(facts);
                            return CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision
                                    .verified();
                        },
                        facts -> {
                            callbackOrder.add("environment");
                            environmentFacts.set(facts);
                            return CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision
                                    .verified();
                        });

        assertThat(result.verified()).isTrue();
        assertThat(result.reasonCode()).isNull();
        assertThat(result.checks()).contains("CANDIDATE_AUTHORITY", "ENVIRONMENT_AUTHORITY");
        assertThat(callbackOrder).containsExactly("candidate", "environment");
        assertThat(candidateFacts.get().coordinate().fingerprint())
                .isEqualTo(VERIFIER.rawAttestationFingerprint(fixture.candidateBytes));
        assertThat(environmentFacts.get().candidateAttestation())
                .isEqualTo(candidateFacts.get().coordinate());
        assertThat(environmentFacts.get().executionLeaseId()).isEqualTo(LEASE);
        assertThat(result.toString()).doesNotContain("candidate:capability", "issuer:");
    }

    @Test
    void computesRawAttestationCoordinatesAndFixedTargetBindingFingerprint() throws Exception {
        Fixture fixture = fixture();
        String message = VERIFIER.canonicalMessage(fixture.targetBinding);

        assertThat(message).endsWith("\"fingerprint\":null}");
        assertThat(VERIFIER.targetBindingFingerprint(fixture.targetBinding))
                .isEqualTo(fixture.targetBinding.path("fingerprint").textValue());
        assertThat(fixture.targetBinding.path("candidateAttestation").path("fingerprint")
                .textValue()).isEqualTo(VERIFIER.rawAttestationFingerprint(fixture.candidateBytes));
        assertThat(fixture.targetBinding.path("environmentAttestation").path("fingerprint")
                .textValue()).isEqualTo(VERIFIER.rawAttestationFingerprint(fixture.environmentBytes));
    }

    @Test
    void canonicalHelpersRejectStrictSchemaInvalidTargetBindings() throws Exception {
        Fixture unknownFixture = fixture();
        unknownFixture.targetBinding.put("unknown", true);

        assertThatThrownBy(() -> VERIFIER.canonicalMessage(unknownFixture.targetBinding))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical message is invalid");
        assertThatThrownBy(() -> VERIFIER.canonicalFingerprint(unknownFixture.targetBinding))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical message is invalid");

        Fixture malformedFixture = fixture();
        malformedFixture.targetBinding.put("fingerprint", "not-a-sha256-fingerprint");
        assertThatThrownBy(() -> VERIFIER.canonicalMessage(malformedFixture.targetBinding))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical message is invalid");
    }

    @Test
    void preservesFeatureFlagsAsPayloadFreeExactReferenceFacts() throws Exception {
        Fixture firstFixture = fixture();
        reference(firstFixture.environment, "featureFlagsRef", "feature-flags:typed:1", '6');
        refreshEnvironmentAttestation(firstFixture);
        AtomicReference<CapabilityStudioStageAcceptanceTargetBindingVerifier.EnvironmentAttestationFacts>
                firstFacts = new AtomicReference<>();
        assertThat(verify(firstFixture, facts -> AuthorityDecision.verified(), facts -> {
            firstFacts.set(facts);
            return AuthorityDecision.verified();
        }).verified()).isTrue();

        Fixture secondFixture = fixture();
        reference(secondFixture.environment, "featureFlagsRef", "feature-flags:typed:2", '7');
        refreshEnvironmentAttestation(secondFixture);
        AtomicReference<CapabilityStudioStageAcceptanceTargetBindingVerifier.EnvironmentAttestationFacts>
                secondFacts = new AtomicReference<>();
        assertThat(verify(secondFixture, facts -> AuthorityDecision.verified(), facts -> {
            secondFacts.set(facts);
            return AuthorityDecision.verified();
        }).verified()).isTrue();

        assertThat(firstFacts.get().featureFlagsRef().exactRef())
                .isNotEqualTo(secondFacts.get().featureFlagsRef().exactRef());
        assertThat(firstFacts.get().featureFlagsRef().fingerprint())
                .isNotEqualTo(secondFacts.get().featureFlagsRef().fingerprint());
        assertThat(firstFacts.get().toString()).doesNotContain("feature-flags:typed", "sha256:");
    }

    @Test
    void rejectsRawFeatureFlagPayloadsAndUnknownFlagFields() throws Exception {
        Fixture fixture = fixture();
        fixture.environment.putObject("featureFlags").put("release", "candidate");

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verifyRaw(fixture, fixture.targetBindingBytes(), fixture.candidateBytes,
                        JSON.writeValueAsBytes(fixture.environment));

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.ENVIRONMENT_ATTESTATION_SCHEMA_INVALID");

        fixture = fixture();
        fixture.environment.with("featureFlagsRef").put("rawFlag", "candidate");
        result = verifyRaw(fixture, fixture.targetBindingBytes(), fixture.candidateBytes,
                JSON.writeValueAsBytes(fixture.environment));

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.ENVIRONMENT_ATTESTATION_SCHEMA_INVALID");
    }

    @Test
    void rejectsDeploymentPinMismatchEvenWhenTargetBindingSelfHashIsValid() throws Exception {
        Fixture fixture = fixture();
        AtomicInteger candidateCalls = new AtomicInteger();
        AtomicInteger environmentCalls = new AtomicInteger();

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                VERIFIER.verify(fixture.stageBytes, fixture.targetBindingBytes(), fixture.candidateBytes,
                        fixture.environmentBytes,
                        context(fixture, fingerprint('a')), NOW,
                        facts -> {
                            candidateCalls.incrementAndGet();
                            return AuthorityDecision.verified();
                        }, facts -> {
                            environmentCalls.incrementAndGet();
                            return AuthorityDecision.verified();
                        });

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.TARGET_BINDING_DEPLOYMENT_FINGERPRINT_MISMATCH");
        assertThat(candidateCalls).hasValue(0);
        assertThat(environmentCalls).hasValue(0);
    }

    @Test
    void rejectsTargetBindingSelfHashTamperBeforeAuthorityCallbacks() throws Exception {
        Fixture fixture = fixture();
        fixture.targetBinding.put("fingerprint", fingerprint('a'));
        AtomicInteger candidateCalls = new AtomicInteger();
        AtomicInteger environmentCalls = new AtomicInteger();

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                VERIFIER.verify(fixture.stageBytes, fixture.targetBindingBytes(), fixture.candidateBytes,
                        fixture.environmentBytes, context(fixture), NOW,
                        facts -> {
                            candidateCalls.incrementAndGet();
                            return AuthorityDecision.verified();
                        }, facts -> {
                            environmentCalls.incrementAndGet();
                            return AuthorityDecision.verified();
                        });

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.TARGET_BINDING_FINGERPRINT_MISMATCH");
        assertThat(candidateCalls).hasValue(0);
        assertThat(environmentCalls).hasValue(0);
    }

    @Test
    void rejectsRawCandidateAndEnvironmentCoordinateTamperBeforeAuthorityCallbacks()
            throws Exception {
        Fixture fixture = fixture();
        AtomicInteger candidateCalls = new AtomicInteger();
        AtomicInteger environmentCalls = new AtomicInteger();
        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                VERIFIER.verify(fixture.stageBytes, fixture.targetBindingBytes(),
                        trailingSpace(fixture.candidateBytes), fixture.environmentBytes,
                        context(fixture), NOW,
                        facts -> {
                            candidateCalls.incrementAndGet();
                            return AuthorityDecision.verified();
                        }, facts -> {
                            environmentCalls.incrementAndGet();
                            return AuthorityDecision.verified();
                        });

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.CANDIDATE_ATTESTATION_COORDINATE_MISMATCH");
        assertThat(candidateCalls).hasValue(0);
        assertThat(environmentCalls).hasValue(0);

        fixture = fixture();
        candidateCalls.set(0);
        environmentCalls.set(0);
        result = VERIFIER.verify(fixture.stageBytes, fixture.targetBindingBytes(),
                fixture.candidateBytes, trailingSpace(fixture.environmentBytes),
                context(fixture), NOW,
                facts -> {
                    candidateCalls.incrementAndGet();
                    return AuthorityDecision.verified();
                }, facts -> {
                    environmentCalls.incrementAndGet();
                    return AuthorityDecision.verified();
                });

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.ENVIRONMENT_ATTESTATION_COORDINATE_MISMATCH");
        assertThat(candidateCalls).hasValue(0);
        assertThat(environmentCalls).hasValue(0);
    }

    @Test
    void rejectsUnknownFieldsAtTheClosedSchemaBoundary() throws Exception {
        Fixture fixture = fixture();
        fixture.targetBinding.put("unknown", true);

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verify(fixture);

        assertThat(result.outcome())
                .isEqualTo(CapabilityStudioStageAcceptanceTargetBindingVerifier.Outcome.REJECTED);
        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.TARGET_BINDING_SCHEMA_INVALID");
    }

    @Test
    void rejectsDuplicateFieldsBeforeSchemaValidation() throws Exception {
        Fixture fixture = fixture();
        byte[] duplicate = duplicateSchemaVersion(
                CapabilityStudioStageAcceptanceTargetBindingVerifier
                        .TARGET_BINDING_SCHEMA_VERSION);

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                VERIFIER.verify(fixture.stageBytes, duplicate, fixture.candidateBytes,
                        fixture.environmentBytes, context(fixture),
                        NOW, facts -> AuthorityDecision.verified(), facts -> AuthorityDecision.verified());

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.TARGET_BINDING_DUPLICATE_FIELD");
    }

    @Test
    void appliesClosedWireAndSizeRulesToCandidateAndEnvironmentDocuments() throws Exception {
        Fixture fixture = fixture();
        fixture.candidate.put("unknown", true);
        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verifyRaw(fixture, fixture.targetBindingBytes(), JSON.writeValueAsBytes(fixture.candidate),
                        fixture.environmentBytes);
        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.CANDIDATE_ATTESTATION_SCHEMA_INVALID");

        fixture = fixture();
        fixture.environment.put("unknown", true);
        fixture.environmentBytes = JSON.writeValueAsBytes(fixture.environment);
        result = verifyRaw(fixture, fixture.targetBindingBytes(), fixture.candidateBytes,
                fixture.environmentBytes);
        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.ENVIRONMENT_ATTESTATION_SCHEMA_INVALID");

        fixture = fixture();
        result = verifyRaw(fixture, fixture.targetBindingBytes(), duplicateSchemaVersion(
                CapabilityStudioStageAcceptanceTargetBindingVerifier
                        .CANDIDATE_ATTESTATION_SCHEMA_VERSION), fixture.environmentBytes);
        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.CANDIDATE_ATTESTATION_DUPLICATE_FIELD");

        fixture = fixture();
        result = verifyRaw(fixture, fixture.targetBindingBytes(), fixture.candidateBytes,
                duplicateSchemaVersion(CapabilityStudioStageAcceptanceTargetBindingVerifier
                        .ENVIRONMENT_ATTESTATION_SCHEMA_VERSION));
        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.ENVIRONMENT_ATTESTATION_DUPLICATE_FIELD");

        fixture = fixture();
        byte[] oversized = new byte[CapabilityStudioStageAcceptanceTargetBindingVerifier
                .MAXIMUM_DOCUMENT_BYTES + 1];
        result = verifyRaw(fixture, oversized, fixture.candidateBytes, fixture.environmentBytes);
        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.TARGET_BINDING_SIZE_LIMIT");

        fixture = fixture();
        result = verifyRaw(fixture, fixture.targetBindingBytes(), oversized, fixture.environmentBytes);
        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.CANDIDATE_ATTESTATION_SIZE_LIMIT");

        fixture = fixture();
        result = verifyRaw(fixture, fixture.targetBindingBytes(), fixture.candidateBytes, oversized);
        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.ENVIRONMENT_ATTESTATION_SIZE_LIMIT");
    }

    @Test
    void rejectsChangedResultContractOrLeaseEvenWhenBindingFingerprintIsRecomputed() throws Exception {
        Fixture fixture = fixture();
        fixture.targetBinding.put("resultRevision", 3);
        fixture.targetBinding.put("fingerprint", VERIFIER.targetBindingFingerprint(fixture.targetBinding));

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verify(fixture);

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.TARGET_BINDING_RESULT_MISMATCH");

        fixture = fixture();
        fixture.targetBinding.put("executionLeaseId", "lease:stage-acceptance:2");
        fixture.targetBinding.put("fingerprint", VERIFIER.targetBindingFingerprint(fixture.targetBinding));
        result = verify(fixture);

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.TARGET_BINDING_EXECUTION_LEASE_MISMATCH");
    }

    @Test
    void rejectsEnvironmentLeaseReplayAgainstTheTargetBindingBeforeAuthorityCallbacks()
            throws Exception {
        Fixture fixture = fixture();
        fixture.environment.put("executionLeaseId", "lease:stage-acceptance:2");
        fixture.environmentBytes = JSON.writeValueAsBytes(fixture.environment);
        fixture.targetBinding.with("environmentAttestation").put(
                "fingerprint", VERIFIER.rawAttestationFingerprint(fixture.environmentBytes));
        fixture.targetBinding.put("fingerprint", VERIFIER.targetBindingFingerprint(fixture.targetBinding));
        AtomicInteger candidateCalls = new AtomicInteger();
        AtomicInteger environmentCalls = new AtomicInteger();

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verify(fixture, facts -> {
                    candidateCalls.incrementAndGet();
                    return AuthorityDecision.verified();
                }, facts -> {
                    environmentCalls.incrementAndGet();
                    return AuthorityDecision.verified();
                });

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.ENVIRONMENT_EXECUTION_LEASE_MISMATCH");
        assertThat(candidateCalls).hasValue(0);
        assertThat(environmentCalls).hasValue(0);
    }

    @Test
    void rejectsResultIdReplayEvenWhenTargetBindingSelfHashIsRecomputed() throws Exception {
        Fixture fixture = fixture();
        fixture.targetBinding.put("resultId", "SAR-replayed-result");
        fixture.targetBinding.put("fingerprint", VERIFIER.targetBindingFingerprint(fixture.targetBinding));

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verify(fixture);

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.TARGET_BINDING_RESULT_MISMATCH");
    }

    @Test
    void rejectsResultRevisionReplayEvenWhenTargetBindingSelfHashIsRecomputed() throws Exception {
        Fixture fixture = fixture();
        fixture.targetBinding.put("resultRevision", 3);
        fixture.targetBinding.put("fingerprint", VERIFIER.targetBindingFingerprint(fixture.targetBinding));

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verify(fixture);

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.TARGET_BINDING_RESULT_MISMATCH");
    }

    @Test
    void rejectsContractReplayEvenWhenTargetBindingSelfHashIsRecomputed() throws Exception {
        Fixture fixture = fixture();
        fixture.targetBinding.put("contractRevision", "2027-01");
        fixture.targetBinding.put("fingerprint", VERIFIER.targetBindingFingerprint(fixture.targetBinding));

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verify(fixture);

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.TARGET_BINDING_RESULT_MISMATCH");
    }

    @Test
    void rejectsAttestationCoordinateDriftAndRoleIssuerCollapse() throws Exception {
        Fixture fixture = fixture();
        fixture.targetBinding.with("candidateAttestation").put("fingerprint", fingerprint('f'));
        fixture.targetBinding.put("fingerprint", VERIFIER.targetBindingFingerprint(fixture.targetBinding));

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verify(fixture);

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.CANDIDATE_ATTESTATION_COORDINATE_MISMATCH");

        fixture = fixture();
        ObjectNode environment = fixture.environment;
        environment.put("issuer", "issuer:candidate-authority");
        fixture.environmentBytes = JSON.writeValueAsBytes(environment);
        fixture.targetBinding.with("environmentAttestation").put(
                "fingerprint", VERIFIER.rawAttestationFingerprint(fixture.environmentBytes));
        fixture.targetBinding.put("fingerprint", VERIFIER.targetBindingFingerprint(fixture.targetBinding));

        result = verify(fixture);

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.ATTESTATION_ISSUER_COLLAPSE");
    }

    @Test
    void rejectsCandidateRoleDriftAtTheClosedSchemaBoundary() throws Exception {
        Fixture fixture = fixture();
        fixture.candidate.put("role", "ENVIRONMENT_AUTHORITY");

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verifyRaw(fixture, fixture.targetBindingBytes(),
                        JSON.writeValueAsBytes(fixture.candidate), fixture.environmentBytes);

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.CANDIDATE_ATTESTATION_SCHEMA_INVALID");
    }

    @Test
    void rejectsEnvironmentRoleDriftAtTheClosedSchemaBoundary() throws Exception {
        Fixture fixture = fixture();
        fixture.environment.put("role", "CANDIDATE_AUTHORITY");
        fixture.environmentBytes = JSON.writeValueAsBytes(fixture.environment);

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verifyRaw(fixture, fixture.targetBindingBytes(), fixture.candidateBytes,
                        fixture.environmentBytes);

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.ENVIRONMENT_ATTESTATION_SCHEMA_INVALID");
    }

    @Test
    void rejectsCandidateIssuerCollapseBeforeAuthorityCallbacks() throws Exception {
        Fixture fixture = fixture();
        fixture.candidate.put("issuer", "issuer:environment-authority");
        refreshCandidateAttestation(fixture);
        AtomicInteger candidateCalls = new AtomicInteger();
        AtomicInteger environmentCalls = new AtomicInteger();

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verify(fixture, facts -> {
                    candidateCalls.incrementAndGet();
                    return AuthorityDecision.verified();
                }, facts -> {
                    environmentCalls.incrementAndGet();
                    return AuthorityDecision.verified();
                });

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.ATTESTATION_ISSUER_COLLAPSE");
        assertThat(candidateCalls).hasValue(0);
        assertThat(environmentCalls).hasValue(0);
    }

    @Test
    void rejectsEnvironmentTrustedIdentityDriftAgainstBindingAndContext() throws Exception {
        Fixture fixture = fixture();
        fixture.environment.putArray("trustedTargetIdentities").add("runtime:other");
        refreshEnvironmentAttestation(fixture);
        AtomicInteger candidateCalls = new AtomicInteger();
        AtomicInteger environmentCalls = new AtomicInteger();

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verify(fixture, facts -> {
                    candidateCalls.incrementAndGet();
                    return AuthorityDecision.verified();
                }, facts -> {
                    environmentCalls.incrementAndGet();
                    return AuthorityDecision.verified();
                });

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.ENVIRONMENT_TARGET_IDENTITIES_MISMATCH");
        assertThat(candidateCalls).hasValue(0);
        assertThat(environmentCalls).hasValue(0);
    }

    @Test
    void rejectsRuntimeIdentityOutsideEnvironmentTrustedIdentities() throws Exception {
        Fixture fixture = fixture();
        fixture.environment.put("runtimeIdentity", "runtime:other");
        refreshEnvironmentAttestation(fixture);

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verify(fixture);

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.RUNTIME_IDENTITY_NOT_TRUSTED");
    }

    @Test
    void rejectsContextIdentityDriftEvenWhenTargetBindingSelfHashIsValid() throws Exception {
        Fixture fixture = fixture();
        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationContext wrongContext =
                new CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationContext(
                        LEASE, Set.of("runtime:other"),
                        fixture.targetBinding.path("fingerprint").textValue());

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                VERIFIER.verify(fixture.stageBytes, fixture.targetBindingBytes(), fixture.candidateBytes,
                        fixture.environmentBytes, wrongContext, NOW,
                        facts -> AuthorityDecision.verified(), facts -> AuthorityDecision.verified());

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.TARGET_BINDING_IDENTITIES_MISMATCH");
    }

    @Test
    void rejectsAdmissionWindowAndProjectionDrift() throws Exception {
        Fixture fixture = fixture();
        fixture.environment.with("admissionWindow").put("through", "2026-01-01T00:04:00Z");
        fixture.environmentBytes = JSON.writeValueAsBytes(fixture.environment);
        String environmentFingerprint = VERIFIER.rawAttestationFingerprint(fixture.environmentBytes);
        fixture.targetBinding.with("environmentAttestation").put("fingerprint", environmentFingerprint);
        fixture.targetBinding.put("fingerprint", VERIFIER.targetBindingFingerprint(fixture.targetBinding));
        fixture.stage.with("environmentAttestation").put("fingerprint", environmentFingerprint);
        ((ObjectNode) fixture.stage.path("evidenceRefs").path(0))
                .put("fingerprint", environmentFingerprint);
        refreshClosure(fixture.stage);
        fixture.stageBytes = JSON.writeValueAsBytes(fixture.stage);

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verify(fixture);

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.ADMISSION_WINDOW_MISMATCH");

        fixture = fixture();
        fixture.stage.with("candidateExecutionBinding").with("candidateBuild")
                .put("buildRef", "build:tampered");
        refreshClosure(fixture.stage);
        fixture.stageBytes = JSON.writeValueAsBytes(fixture.stage);
        result = verify(fixture);

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.CANDIDATE_BUILD_PROJECTION_MISMATCH");
    }

    @Test
    void rejectsEnvironmentNetworkPolicyDriftAgainstStageEgressObservation()
            throws Exception {
        Fixture fixture = fixture();
        fixture.stage.with("deploymentEgressObservation")
                .put("networkPolicyRef", "network-policy:other-v1");
        refreshClosure(fixture.stage);
        fixture.stageBytes = JSON.writeValueAsBytes(fixture.stage);
        AtomicInteger candidateCalls = new AtomicInteger();
        AtomicInteger environmentCalls = new AtomicInteger();

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verify(fixture, facts -> {
                    candidateCalls.incrementAndGet();
                    return AuthorityDecision.verified();
                }, facts -> {
                    environmentCalls.incrementAndGet();
                    return AuthorityDecision.verified();
                });

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.ENVIRONMENT_PROJECTION_MISMATCH");
        assertThat(candidateCalls).hasValue(0);
        assertThat(environmentCalls).hasValue(0);
    }

    @Test
    void acceptsVerificationAfterAdmissionWindowWhenAttestationsRemainCurrent() throws Exception {
        Fixture fixture = fixture();
        fixture.environment.with("admissionWindow")
                .put("through", "2026-01-01T00:06:00Z");
        refreshEnvironmentAttestation(fixture);

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verify(fixture);

        assertThat(result.verified()).isTrue();
    }

    @Test
    void rejectsExpiredCandidateAttestationBeforeAuthorityCallbacks() throws Exception {
        Fixture fixture = fixture();
        fixture.candidate.put("expiresAt", "2026-01-01T00:09:00Z");
        refreshCandidateAttestation(fixture);
        AtomicInteger candidateCalls = new AtomicInteger();
        AtomicInteger environmentCalls = new AtomicInteger();

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verify(fixture, facts -> {
                    candidateCalls.incrementAndGet();
                    return AuthorityDecision.verified();
                }, facts -> {
                    environmentCalls.incrementAndGet();
                    return AuthorityDecision.verified();
                });

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.ATTESTATION_NOT_CURRENT");
        assertThat(candidateCalls).hasValue(0);
        assertThat(environmentCalls).hasValue(0);
    }

    @Test
    void rejectsNotYetValidCandidateAttestationBeforeAuthorityCallbacks() throws Exception {
        Fixture fixture = fixture();
        fixture.candidate.put("issuedAt", "2026-01-01T00:11:00Z");
        refreshCandidateAttestation(fixture);
        AtomicInteger candidateCalls = new AtomicInteger();
        AtomicInteger environmentCalls = new AtomicInteger();

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verify(fixture, facts -> {
                    candidateCalls.incrementAndGet();
                    return AuthorityDecision.verified();
                }, facts -> {
                    environmentCalls.incrementAndGet();
                    return AuthorityDecision.verified();
                });

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.ATTESTATION_NOT_CURRENT");
        assertThat(candidateCalls).hasValue(0);
        assertThat(environmentCalls).hasValue(0);
    }

    @Test
    void rejectsMalformedAttestationTimeAsSchemaInput() throws Exception {
        Fixture fixture = fixture();
        fixture.candidate.put("issuedAt", "not-a-time");

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verifyRaw(fixture, fixture.targetBindingBytes(),
                        JSON.writeValueAsBytes(fixture.candidate), fixture.environmentBytes);

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.CANDIDATE_ATTESTATION_SCHEMA_INVALID");
    }

    @Test
    void mapsNullThrowingAndUnavailableAuthoritiesToBlocked() throws Exception {
        Fixture fixture = fixture();
        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verify(fixture, null, facts -> AuthorityDecision.verified());
        assertThat(result.outcome()).isEqualTo(
                CapabilityStudioStageAcceptanceTargetBindingVerifier.Outcome.BLOCKED);
        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.CANDIDATE_AUTHORITY_UNAVAILABLE");

        result = verify(fixture, facts -> {
            throw new IllegalStateException("secret callback detail");
        }, facts -> AuthorityDecision.verified());
        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.CANDIDATE_AUTHORITY_UNAVAILABLE");
        assertThat(result.toString()).doesNotContain("secret callback detail");

        result = verify(fixture, facts -> AuthorityDecision.verified(),
                facts -> AuthorityDecision.unavailable());
        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.ENVIRONMENT_AUTHORITY_UNAVAILABLE");
    }

    @Test
    void mapsCandidateAndEnvironmentAuthorityRejectionWithoutContinuing() throws Exception {
        Fixture fixture = fixture();
        AtomicInteger environmentCalls = new AtomicInteger();
        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verify(fixture, facts -> AuthorityDecision.rejected(), facts -> {
                    environmentCalls.incrementAndGet();
                    return AuthorityDecision.verified();
                });

        assertThat(result.outcome())
                .isEqualTo(CapabilityStudioStageAcceptanceTargetBindingVerifier.Outcome.REJECTED);
        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.CANDIDATE_AUTHORITY_REJECTED");
        assertThat(environmentCalls).hasValue(0);

        fixture = fixture();
        AtomicInteger candidateCalls = new AtomicInteger();
        AtomicInteger secondEnvironmentCalls = new AtomicInteger();
        result = verify(fixture, facts -> {
            candidateCalls.incrementAndGet();
            return AuthorityDecision.verified();
        }, facts -> {
            secondEnvironmentCalls.incrementAndGet();
            return AuthorityDecision.rejected();
        });

        assertThat(result.outcome())
                .isEqualTo(CapabilityStudioStageAcceptanceTargetBindingVerifier.Outcome.REJECTED);
        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.ENVIRONMENT_AUTHORITY_REJECTED");
        assertThat(candidateCalls).hasValue(1);
        assertThat(secondEnvironmentCalls).hasValue(1);
    }

    @Test
    void mapsCandidateUnavailableAndEnvironmentThrowOrNullToBlocked() throws Exception {
        Fixture fixture = fixture();
        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verify(fixture, facts -> AuthorityDecision.unavailable(),
                        facts -> AuthorityDecision.verified());
        assertThat(result.outcome())
                .isEqualTo(CapabilityStudioStageAcceptanceTargetBindingVerifier.Outcome.BLOCKED);
        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.CANDIDATE_AUTHORITY_UNAVAILABLE");

        fixture = fixture();
        result = verify(fixture, facts -> AuthorityDecision.verified(), facts -> {
            throw new IllegalStateException("environment secret");
        });
        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.ENVIRONMENT_AUTHORITY_UNAVAILABLE");
        assertThat(result.toString()).doesNotContain("environment secret");

        fixture = fixture();
        result = verify(fixture, facts -> AuthorityDecision.verified(), facts -> null);
        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.ENVIRONMENT_AUTHORITY_DECISION_INVALID");
    }

    @Test
    void blocksWhenTheDeploymentLeaseAndIdentityPinIsAbsent() throws Exception {
        Fixture fixture = fixture();

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                VERIFIER.verify(fixture.stageBytes, fixture.targetBindingBytes(),
                        fixture.candidateBytes, fixture.environmentBytes,
                        facts -> AuthorityDecision.verified(), facts -> AuthorityDecision.verified());

        assertThat(result.outcome())
                .isEqualTo(CapabilityStudioStageAcceptanceTargetBindingVerifier.Outcome.BLOCKED);
        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.CONTEXT_UNAVAILABLE");
    }

    @Test
    void rejectsMissingOrMalformedDeploymentFingerprintPinsAtContextBoundary() {
        assertThatThrownBy(() -> new CapabilityStudioStageAcceptanceTargetBindingVerifier
                .VerificationContext(LEASE, Set.of("runtime:capability-studio"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedTargetBindingFingerprint");
        assertThatThrownBy(() -> new CapabilityStudioStageAcceptanceTargetBindingVerifier
                .VerificationContext(LEASE, Set.of("runtime:capability-studio"), "SHA256:bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedTargetBindingFingerprint");
    }

    @Test
    void rejectsMalformedJsonBeforeAnyAuthorityCallback() throws Exception {
        Fixture fixture = fixture();
        AtomicInteger candidateCalls = new AtomicInteger();
        AtomicInteger environmentCalls = new AtomicInteger();
        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                VERIFIER.verify(fixture.stageBytes, fixture.targetBindingBytes(),
                        "{not-json".getBytes(StandardCharsets.UTF_8), fixture.environmentBytes,
                        context(fixture), NOW,
                        facts -> {
                            candidateCalls.incrementAndGet();
                            return AuthorityDecision.verified();
                        }, facts -> {
                            environmentCalls.incrementAndGet();
                            return AuthorityDecision.verified();
                        });

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.CANDIDATE_ATTESTATION_INVALID_JSON");
        assertThat(candidateCalls).hasValue(0);
        assertThat(environmentCalls).hasValue(0);
    }

    @Test
    void rejectsUppercaseAttestationFingerprintInputsToMatchRuntime() throws Exception {
        Fixture fixture = fixture();
        fixture.candidate.put("artifactDigest", fingerprint('A'));
        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verifyRaw(fixture, fixture.targetBindingBytes(),
                        JSON.writeValueAsBytes(fixture.candidate), fixture.environmentBytes);
        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.CANDIDATE_ATTESTATION_SCHEMA_INVALID");

        fixture = fixture();
        fixture.environment.put("environmentFingerprint", fingerprint('A'));
        fixture.environmentBytes = JSON.writeValueAsBytes(fixture.environment);
        result = verifyRaw(fixture, fixture.targetBindingBytes(), fixture.candidateBytes,
                fixture.environmentBytes);
        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.ENVIRONMENT_ATTESTATION_SCHEMA_INVALID");
    }

    @Test
    void localFailuresNeverInvokeEitherAuthority() throws Exception {
        Fixture fixture = fixture();
        AtomicInteger candidateCalls = new AtomicInteger();
        AtomicInteger environmentCalls = new AtomicInteger();
        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verifyWithCounters(fixture, fixture.targetBindingBytes(), fixture.candidateBytes,
                        fixture.environmentBytes, candidateCalls, environmentCalls);
        assertThat(result.verified()).isTrue();
        assertThat(candidateCalls).hasValue(1);
        assertThat(environmentCalls).hasValue(1);

        fixture = fixture();
        candidateCalls.set(0);
        environmentCalls.set(0);
        fixture.targetBinding.put("unknown", true);
        result = verifyWithCounters(fixture, fixture.targetBindingBytes(), fixture.candidateBytes,
                fixture.environmentBytes, candidateCalls, environmentCalls);
        assertThat(result.rejected()).isTrue();
        assertThat(candidateCalls).hasValue(0);
        assertThat(environmentCalls).hasValue(0);

        fixture = fixture();
        result = verifyWithCounters(fixture, fixture.targetBindingBytes(),
                "{not-json".getBytes(StandardCharsets.UTF_8), fixture.environmentBytes,
                candidateCalls, environmentCalls);
        assertThat(result.rejected()).isTrue();
        assertThat(candidateCalls).hasValue(0);
        assertThat(environmentCalls).hasValue(0);
    }

    @Test
    void rejectsNonPassStageResultAndOversizedDocuments() throws Exception {
        Fixture fixture = fixture();
        fixture.stage.put("status", "FAIL");
        fixture.stageBytes = JSON.writeValueAsBytes(fixture.stage);

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                verify(fixture);

        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.STAGE_RESULT_V2_INVALID");

        byte[] oversized = new byte[CapabilityStudioStageAcceptanceTargetBindingVerifier
                .MAXIMUM_STAGE_RESULT_BYTES + 1];
        result = VERIFIER.verify(oversized, fixture.targetBindingBytes(), fixture.candidateBytes,
                fixture.environmentBytes, context(fixture), NOW,
                facts -> AuthorityDecision.verified(), facts -> AuthorityDecision.verified());
        assertThat(result.reasonCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.CANDENV.STAGE_RESULT_V2_SIZE_LIMIT");
    }

    @Test
    void preservesStageResultV2FourMiBLimitForLargeValidWireInput() throws Exception {
        Fixture fixture = fixture();
        byte[] largeStage = withLeadingWhitespace(fixture.stageBytes,
                CapabilityStudioStageAcceptanceTargetBindingVerifier.MAXIMUM_DOCUMENT_BYTES + 1);

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult result =
                VERIFIER.verify(largeStage, fixture.targetBindingBytes(), fixture.candidateBytes,
                        fixture.environmentBytes, context(fixture), NOW,
                        facts -> AuthorityDecision.verified(), facts -> AuthorityDecision.verified());

        assertThat(largeStage.length)
                .isGreaterThan(CapabilityStudioStageAcceptanceTargetBindingVerifier
                        .MAXIMUM_DOCUMENT_BYTES)
                .isLessThanOrEqualTo(CapabilityStudioStageAcceptanceTargetBindingVerifier
                        .MAXIMUM_STAGE_RESULT_BYTES);
        assertThat(result.verified()).isTrue();
    }

    private static CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult verify(
            Fixture fixture) throws Exception {
        return verify(fixture, facts -> AuthorityDecision.verified(),
                facts -> AuthorityDecision.verified());
    }

    private static CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult verify(
            Fixture fixture,
            CapabilityStudioStageAcceptanceTargetBindingVerifier.CandidateAuthority candidate,
            CapabilityStudioStageAcceptanceTargetBindingVerifier.EnvironmentAuthority environment)
            throws Exception {
        return VERIFIER.verify(fixture.stageBytes, fixture.targetBindingBytes(), fixture.candidateBytes,
                fixture.environmentBytes, context(fixture), NOW, candidate, environment);
    }

    private static CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult verifyRaw(
            Fixture fixture, byte[] targetBinding, byte[] candidate, byte[] environment)
            throws Exception {
        return VERIFIER.verify(fixture.stageBytes, targetBinding, candidate, environment,
                context(fixture), NOW, facts -> AuthorityDecision.verified(),
                facts -> AuthorityDecision.verified());
    }

    private static CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult
    verifyWithCounters(
            Fixture fixture, byte[] targetBinding, byte[] candidate, byte[] environment,
            AtomicInteger candidateCalls, AtomicInteger environmentCalls) throws Exception {
        return VERIFIER.verify(fixture.stageBytes, targetBinding, candidate, environment,
                context(fixture), NOW, facts -> {
                    candidateCalls.incrementAndGet();
                    return AuthorityDecision.verified();
                }, facts -> {
                    environmentCalls.incrementAndGet();
                    return AuthorityDecision.verified();
                });
    }

    private static byte[] duplicateSchemaVersion(String version) {
        return ("{\"schemaVersion\":\"" + version + "\",\"schemaVersion\":\""
                + version + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] trailingSpace(byte[] bytes) {
        byte[] result = Arrays.copyOf(bytes, bytes.length + 1);
        result[result.length - 1] = ' ';
        return result;
    }

    private static byte[] withLeadingWhitespace(byte[] bytes, int minimumSize) {
        int prefixLength = Math.max(0, minimumSize - bytes.length);
        byte[] result = new byte[prefixLength + bytes.length];
        Arrays.fill(result, 0, prefixLength, (byte) ' ');
        System.arraycopy(bytes, 0, result, prefixLength, bytes.length);
        return result;
    }

    private static CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationContext context(
            Fixture fixture) {
        return context(fixture, fixture.targetBinding.path("fingerprint").textValue());
    }

    private static CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationContext context(
            Fixture fixture, String expectedTargetBindingFingerprint) {
        return new CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationContext(
                LEASE, Set.of("runtime:capability-studio"),
                expectedTargetBindingFingerprint);
    }

    private static Fixture fixture() throws Exception {
        ObjectNode candidate = JSON.createObjectNode()
                .put("schemaVersion", CapabilityStudioStageAcceptanceTargetBindingVerifier
                        .CANDIDATE_ATTESTATION_SCHEMA_VERSION)
                .put("candidateRef", "candidate:capability-studio:2026-01")
                .put("attestationRevision", 1)
                .put("role", "CANDIDATE_AUTHORITY")
                .put("buildRef", "build:capability-studio")
                .put("revision", "rev-2")
                .put("sourceCommit", "abcdef1234567")
                .put("sourceTreeStatus", "CLEAN")
                .put("artifactDigest", fingerprint('5'))
                .put("executionIntentFingerprint", fingerprint('4'))
                .put("scope", SCOPE)
                .put("issuer", "issuer:candidate-authority")
                .put("issuedAt", STARTED)
                .put("expiresAt", EXPIRES);
        reference(candidate, "baselineRef", "baseline:capability-studio:v2", '1');
        reference(candidate, "demoPackRef", "demo-pack:capability-studio:v2", '2');
        byte[] candidateBytes = JSON.writeValueAsBytes(candidate);
        String candidateFingerprint = VERIFIER.rawAttestationFingerprint(candidateBytes);

        ObjectNode environment = JSON.createObjectNode()
                .put("schemaVersion", CapabilityStudioStageAcceptanceTargetBindingVerifier
                        .ENVIRONMENT_ATTESTATION_SCHEMA_VERSION)
                .put("environmentRef", "environment:stage-acceptance")
                .put("attestationRevision", 1)
                .put("role", "ENVIRONMENT_AUTHORITY")
                .put("executionLeaseId", LEASE)
                .put("environmentFingerprint", fingerprint('3'))
                .put("targetProfile", "capability-studio:stage-acceptance")
                .put("scope", SCOPE)
                .put("region", "region:sg1")
                .put("runtimeIdentity", "runtime:capability-studio")
                .put("networkPolicy", "network-policy:deny-external-v1")
                .put("logicalClock", STARTED)
                .put("issuer", "issuer:environment-authority")
                .put("issuedAt", STARTED)
                .put("expiresAt", EXPIRES);
        environment.putObject("candidateAttestation")
                .put("candidateRef", "candidate:capability-studio:2026-01")
                .put("attestationRevision", 1)
                .put("fingerprint", candidateFingerprint);
        reference(environment, "featureFlagsRef", "feature-flags:capability-studio:v1", '6');
        environment.putObject("admissionWindow")
                .put("from", STARTED)
                .put("through", EXPIRES);
        environment.putArray("trustedTargetIdentities").add("runtime:capability-studio");
        byte[] environmentBytes = JSON.writeValueAsBytes(environment);
        String environmentFingerprint = VERIFIER.rawAttestationFingerprint(environmentBytes);

        ObjectNode stage = stage(environmentFingerprint);
        byte[] stageBytes = JSON.writeValueAsBytes(stage);

        ObjectNode targetBinding = JSON.createObjectNode()
                .put("schemaVersion", CapabilityStudioStageAcceptanceTargetBindingVerifier
                        .TARGET_BINDING_SCHEMA_VERSION)
                .put("resultId", stage.path("resultId").textValue())
                .put("resultRevision", stage.path("revision").intValue())
                .put("contractId", stage.path("contractId").textValue())
                .put("contractRevision", stage.path("contractRevision").textValue())
                .put("executionLeaseId", LEASE);
        targetBinding.putObject("candidateAttestation")
                .put("candidateRef", "candidate:capability-studio:2026-01")
                .put("attestationRevision", 1)
                .put("fingerprint", candidateFingerprint);
        targetBinding.putObject("environmentAttestation")
                .put("environmentRef", "environment:stage-acceptance")
                .put("attestationRevision", 1)
                .put("fingerprint", environmentFingerprint);
        targetBinding.putArray("trustedTargetIdentities").add("runtime:capability-studio");
        targetBinding.put("fingerprint", fingerprint('0'));
        targetBinding.put("fingerprint",
                VERIFIER.targetBindingFingerprint(targetBinding));

        return new Fixture(stage, targetBinding, candidate, environment,
                stageBytes, candidateBytes, environmentBytes);
    }

    private static ObjectNode stage(String environmentFingerprint) {
        ObjectNode result = JSON.createObjectNode()
                .put("schemaVersion", "bloge.capabilityStudioStageAcceptanceResult.v2")
                .put("resultId", "SAR-s0-ac-01-v2-pass")
                .put("revision", 2)
                .put("contractId", "contract:capability-studio-stage-acceptance")
                .put("contractRevision", "2026-01")
                .put("resultKind", "STAGE_EXIT")
                .put("status", "PASS")
                .put("decidedAt", DECIDED);
        result.putObject("candidateExecutionBinding")
                .putObject("candidateBuild")
                .put("buildRef", "build:capability-studio")
                .put("revision", "rev-2")
                .put("sourceCommit", "abcdef1234567")
                .put("sourceTreeStatus", "CLEAN")
                .put("artifactFingerprint", fingerprint('5'));
        ObjectNode binding = (ObjectNode) result.path("candidateExecutionBinding");
        binding.put("candidateIntentFingerprint", fingerprint('4'))
                .put("environmentFingerprint", fingerprint('3'))
                .put("executionStartedAt", STARTED)
                .put("evidenceCompletedAt", COMPLETED);
        reference(binding, "baselineRef", "baseline:capability-studio:v2", '1');
        reference(binding, "demoPackRef", "demo-pack:capability-studio:v2", '2');
        result.putObject("environmentAttestation")
                .put("exactRef", "environment:stage-acceptance")
                .put("fingerprint", environmentFingerprint)
                .put("environmentFingerprint", fingerprint('3'))
                .put("profile", "capability-studio:stage-acceptance")
                .put("scope", SCOPE)
                .put("issuer", "issuer:environment-authority")
                .put("issuedAt", STARTED)
                .put("expiresAt", EXPIRES)
                .put("candidateArtifactFingerprint", fingerprint('5'));
        result.putObject("deploymentEgressObservation")
                .put("exactRef", "egress-observation:deployment:1")
                .put("fingerprint", fingerprint('b'))
                .put("candidateIntentFingerprint", fingerprint('4'))
                .put("observationStartedAt", STARTED)
                .put("observationCompletedAt", COMPLETED)
                .put("networkPolicyRef", "network-policy:deny-external-v1")
                .put("observedExternalCallCount", 0)
                .put("deniedAttemptCount", 0)
                .put("status", "PASS");
        ArrayNode evidence = result.putArray("evidenceRefs");
        evidence.add(evidence("environment", "environment:stage-acceptance", environmentFingerprint));
        evidence.add(evidence("egress", "egress-observation:deployment:1", fingerprint('b')));
        for (int i = 1; i <= 9; i++) {
            evidence.add(evidence("check-" + i, "evidence:check:" + i, fingerprint((char) ('0' + i))));
        }
        ArrayNode checks = result.putArray("acceptanceChecks");
        for (int i = 1; i <= 9; i++) {
            checks.addObject().put("checkId", "AC-STD-0" + i).put("status", "PASS")
                    .putArray("evidenceIds").add("check-" + i);
        }
        ArrayNode signoffs = result.putArray("signoffs");
        signoffs.add(signoff("CORRECTNESS_OWNER", "signature:correctness", 'c'));
        signoffs.add(signoff("RUNTIME_OWNER", "signature:runtime", 'd'));
        signoffs.add(signoff("QA_OWNER", "signature:qa", 'e'));
        result.putArray("diagnostics");
        refreshClosure(result);
        return result;
    }

    private static ObjectNode evidence(String id, String exactRef, String fingerprint) {
        return JSON.createObjectNode().put("evidenceId", id).put("exactRef", exactRef)
                .put("fingerprint", fingerprint).put("status", "AVAILABLE");
    }

    private static ObjectNode signoff(String role, String exactRef, char seed) {
        return JSON.createObjectNode().put("role", role).put("actorRef", "actor:" + role)
                .put("decision", "APPROVED").put("signedAt", "2026-01-01T00:06:00Z")
                .set("signatureRef", JSON.createObjectNode().put("exactRef", exactRef)
                        .put("fingerprint", fingerprint(seed)));
    }

    private static void reference(ObjectNode parent, String field, String exactRef, char seed) {
        parent.set(field, JSON.createObjectNode().put("exactRef", exactRef)
                .put("fingerprint", fingerprint(seed)));
    }

    private static void refreshCandidateAttestation(Fixture fixture) throws Exception {
        fixture.candidateBytes = JSON.writeValueAsBytes(fixture.candidate);
        String candidateFingerprint = VERIFIER.rawAttestationFingerprint(fixture.candidateBytes);
        fixture.environment.with("candidateAttestation").put("fingerprint", candidateFingerprint);
        refreshEnvironmentAttestation(fixture);
        fixture.targetBinding.with("candidateAttestation").put(
                "fingerprint", candidateFingerprint);
        fixture.targetBinding.put("fingerprint", VERIFIER.targetBindingFingerprint(
                fixture.targetBinding));
    }

    private static void refreshEnvironmentAttestation(Fixture fixture) throws Exception {
        fixture.environmentBytes = JSON.writeValueAsBytes(fixture.environment);
        String environmentFingerprint = VERIFIER.rawAttestationFingerprint(fixture.environmentBytes);
        fixture.targetBinding.with("environmentAttestation").put(
                "fingerprint", environmentFingerprint);
        fixture.targetBinding.put("fingerprint", VERIFIER.targetBindingFingerprint(
                fixture.targetBinding));
        fixture.stage.with("environmentAttestation").put("fingerprint", environmentFingerprint);
        ((ObjectNode) fixture.stage.path("evidenceRefs").path(0))
                .put("fingerprint", environmentFingerprint);
        refreshClosure(fixture.stage);
        fixture.stageBytes = JSON.writeValueAsBytes(fixture.stage);
    }

    private static void refreshClosure(ObjectNode result) {
        String closure = CapabilityStudioStageAcceptanceResultV2Verifier.closureFingerprint(result);
        result.put("evidenceClosureFingerprint", closure);
        for (var signoff : result.path("signoffs")) {
            ((ObjectNode) signoff).put("evidenceClosureFingerprint", closure);
        }
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }

    private static final class Fixture {
        private final ObjectNode stage;
        private final ObjectNode targetBinding;
        private final ObjectNode candidate;
        private final ObjectNode environment;
        private byte[] stageBytes;
        private byte[] candidateBytes;
        private byte[] environmentBytes;

        private Fixture(
                ObjectNode stage,
                ObjectNode targetBinding,
                ObjectNode candidate,
                ObjectNode environment,
                byte[] stageBytes,
                byte[] candidateBytes,
                byte[] environmentBytes) {
            this.stage = stage;
            this.targetBinding = targetBinding;
            this.candidate = candidate;
            this.environment = environment;
            this.stageBytes = stageBytes;
            this.candidateBytes = candidateBytes;
            this.environmentBytes = environmentBytes;
        }

        byte[] targetBindingBytes() throws Exception {
            return JSON.writeValueAsBytes(targetBinding);
        }
    }

    private static final class AuthorityDecision {
        private AuthorityDecision() {
        }

        private static CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision
        verified() {
            return CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision.verified();
        }

        private static CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision
        unavailable() {
            return CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision.unavailable();
        }

        private static CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision
        rejected() {
            return CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision.rejected();
        }
    }
}
