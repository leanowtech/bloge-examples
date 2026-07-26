package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnlineReadOnlyShadowWorkerCompatibilityFixtureTest {
    private OnlineReadOnlyShadowWorkerCompatibilityFixture
            fixture;

    @BeforeEach
    void setUp() {
        fixture = CapabilityMirrorProtocol
                .onlineReadOnlyShadowWorkerCompatibilityFixture();
    }

    @Test
    void independentlyVerifiesCompleteCrashTakeoverAndSuccessClosure() {
        OnlineReadOnlyShadowWorkerCompatibilityFixture
                .VerificationResult result =
                fixture.verify();

        assertThat(result.verified())
                .as(result.reasonCode())
                .isTrue();
        assertThat(result.zeroWrite()).isTrue();
        assertThat(result.jobId())
                .isEqualTo(
                        fixture.expectedJobId());
        assertThat(result.requestFingerprint())
                .isEqualTo(
                        fixture.expectedRequestFingerprint());
        assertThat(result.lifecycleNextSequence())
                .isEqualTo(
                        fixture.expectedLifecycleNextSequence());
        assertThat(fixture.job()
                .path("status").asText())
                .isEqualTo("SUCCEEDED");
        assertThat(fixture.lifecyclePage()
                .path("events"))
                .extracting(event ->
                        event.path("transition")
                                .asText())
                .contains(
                        "ADMITTED",
                        "CLAIMED",
                        "LEASE_RENEWED",
                        "TAKEN_OVER",
                        "SUCCEEDED");
    }

    @Test
    void rejectsAJobProjectionThatNoLongerMatchesItsFingerprint() {
        ObjectNode job =
                (ObjectNode) fixture.job();
        job.put("attemptCount", 1);

        var result = copy(
                fixture.request(),
                job,
                fixture.lifecyclePage(),
                fixture.comparison(),
                fixture.baselineObservation(),
                fixture.baselineKey(),
                fixture.expectedLifecycleNextSequence())
                .verify();

        assertThat(result.outcome())
                .isEqualTo(
                        OnlineReadOnlyShadowWorkerCompatibilityFixture
                                .Outcome.JOB_INVALID);
        assertThat(result.reasonCode())
                .isEqualTo(
                        "ONLINE_WORKER_SHADOW_JOB_RECORD_FINGERPRINT_INVALID");
    }

    @Test
    void rejectsAValidLifecyclePrefixThatDoesNotCloseTheTerminalHead() {
        ObjectNode page =
                (ObjectNode) fixture.lifecyclePage();
        ArrayNode events =
                page.withArray("events");
        events.remove(events.size() - 1);
        page.put(
                "nextSequence",
                events.get(events.size() - 1)
                        .path("sequence").asLong());
        page.put("hasMore", true);

        var result = copy(
                fixture.request(),
                fixture.job(),
                page,
                fixture.comparison(),
                fixture.baselineObservation(),
                fixture.baselineKey(),
                fixture.expectedLifecycleNextSequence())
                .verify();

        assertThat(result.outcome())
                .isEqualTo(
                        OnlineReadOnlyShadowWorkerCompatibilityFixture
                                .Outcome.LIFECYCLE_INVALID);
        assertThat(result.reasonCode())
                .isEqualTo(
                        "ONLINE_WORKER_VERIFIED_PAGE");
    }

    @Test
    void rejectsAChangedIndependentlySignedOnlineSource() {
        ObjectNode baseline =
                (ObjectNode) fixture
                        .baselineObservation();
        baseline.put(
                "semanticResultFingerprint",
                fingerprint('a'));

        var result = copy(
                fixture.request(),
                fixture.job(),
                fixture.lifecyclePage(),
                fixture.comparison(),
                baseline,
                fixture.baselineKey(),
                fixture.expectedLifecycleNextSequence())
                .verify();

        assertThat(result.outcome())
                .isEqualTo(
                        OnlineReadOnlyShadowWorkerCompatibilityFixture
                                .Outcome.SOURCE_RESOLUTION_INVALID);
        assertThat(result.reasonCode())
                .isEqualTo(
                        "ONLINE_WORKER_ONLINE_SOURCE_RESOLUTION_BASELINE_INVALID");
    }

    @Test
    void rejectsAuthorityRoleAliasingBeforeAnyArtifactCanBeTrusted() {
        var result = copy(
                fixture.request(),
                fixture.job(),
                fixture.lifecyclePage(),
                fixture.comparison(),
                fixture.baselineObservation(),
                fixture.comparisonKey(),
                fixture.expectedLifecycleNextSequence())
                .verify();

        assertThat(result.outcome())
                .isEqualTo(
                        OnlineReadOnlyShadowWorkerCompatibilityFixture
                                .Outcome.CLOSURE_INVALID);
        assertThat(result.reasonCode())
                .isEqualTo(
                        "ONLINE_WORKER_AUTHORITY_ROLES_INVALID");
    }

    @Test
    void rejectsDistinctKeyIdsThatAliasOnePublicKeyMaterial() {
        EvidenceVerificationKey baseline =
                fixture.baselineKey();
        EvidenceVerificationKey aliased =
                new EvidenceVerificationKey(
                        baseline.schemaVersion(),
                        baseline.keyId() + ":alias",
                        baseline.algorithm(),
                        fixture.comparisonKey()
                                .encodedPublicKey(),
                        baseline.createdAt(),
                        baseline.state(),
                        baseline.provider());

        var result = copy(
                fixture.request(),
                fixture.job(),
                fixture.lifecyclePage(),
                fixture.comparison(),
                fixture.baselineObservation(),
                aliased,
                fixture.expectedLifecycleNextSequence())
                .verify();

        assertThat(result.outcome())
                .isEqualTo(
                        OnlineReadOnlyShadowWorkerCompatibilityFixture
                                .Outcome.CLOSURE_INVALID);
        assertThat(result.reasonCode())
                .isEqualTo(
                        "ONLINE_WORKER_AUTHORITY_ROLES_INVALID");
    }

    @Test
    void rejectsAConsumerExpectationFromAnotherLifecycleHead() {
        var result = copy(
                fixture.request(),
                fixture.job(),
                fixture.lifecyclePage(),
                fixture.comparison(),
                fixture.baselineObservation(),
                fixture.baselineKey(),
                fixture.expectedLifecycleNextSequence()
                        + 1)
                .verify();

        assertThat(result.outcome())
                .isEqualTo(
                        OnlineReadOnlyShadowWorkerCompatibilityFixture
                                .Outcome.CLOSURE_INVALID);
        assertThat(result.reasonCode())
                .isEqualTo(
                        "ONLINE_WORKER_ARTIFACT_CLOSURE_INVALID");
    }

    @Test
    void publicJsonAccessorsReturnDetachedCopies() {
        ObjectNode changedRequest =
                (ObjectNode) fixture.request();
        changedRequest.put(
                "requestId", "changed");
        ObjectNode changedScope =
                (ObjectNode) fixture.expectedScope();
        changedScope.put(
                "tenantId", "changed");

        assertThat(fixture.request()
                .path("requestId").asText())
                .isNotEqualTo("changed");
        assertThat(fixture.expectedScope()
                .path("tenantId").asText())
                .isNotEqualTo("changed");
        assertThat(fixture.verify().verified())
                .isTrue();
    }

    @Test
    void rejectsUnknownEnvelopeFieldsAndMalformedConsumerExpectations() {
        ObjectNode envelope =
                (ObjectNode) readPackagedFixture();
        envelope.put(
                "businessPayload",
                "must-not-be-admitted");

        assertThatThrownBy(() ->
                OnlineReadOnlyShadowWorkerCompatibilityFixture
                        .from(envelope))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "fixture fields are invalid");
    }

    private OnlineReadOnlyShadowWorkerCompatibilityFixture
    copy(
            JsonNode request,
            JsonNode job,
            JsonNode lifecyclePage,
            JsonNode comparison,
            JsonNode baselineObservation,
            EvidenceVerificationKey baselineKey,
            long lifecycleNextSequence) {
        return new OnlineReadOnlyShadowWorkerCompatibilityFixture(
                fixture.expectedScope(),
                fixture.expectedJobId(),
                fixture.expectedRequestFingerprint(),
                fixture.expectedComparisonRef(),
                fixture.expectedAttestationRef(),
                lifecycleNextSequence,
                request,
                job,
                lifecyclePage,
                comparison,
                fixture.comparisonKey(),
                fixture.baselineCommand(),
                baselineObservation,
                baselineKey,
                fixture.candidateCommand(),
                fixture.candidateEvidenceBundle(),
                fixture.candidateEvidenceKey(),
                fixture.attestation(),
                fixture.attestationKey(),
                fixture.verificationTime());
    }

    private static String fingerprint(
            char material) {
        return "sha256:"
                + String.valueOf(material)
                .repeat(64);
    }

    private static JsonNode readPackagedFixture() {
        try (java.io.InputStream input =
                     OnlineReadOnlyShadowWorkerCompatibilityFixtureTest
                             .class
                             .getResourceAsStream(
                                     CapabilityMirrorProtocol
                                             .ONLINE_READ_ONLY_SHADOW_WORKER_FIXTURE_RESOURCE)) {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(
                            java.util.Objects.requireNonNull(
                                    input,
                                    "fixture"));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(
                    "Unable to read worker fixture",
                    failure);
        }
    }
}
