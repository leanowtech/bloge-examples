package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestSuiteEvidenceVerifierTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUITE = "sha256:" + "a".repeat(64);
    private static final String TARGET = "sha256:" + "b".repeat(64);
    private static final String FIXTURE = "sha256:" + "c".repeat(64);
    private static final String REQUEST = "sha256:" + "d".repeat(64);
    private static final String CHILD = "sha256:" + "e".repeat(64);
    private static final Instant SIGNED_AT = Instant.parse("2026-07-16T10:15:30Z");

    @Test
    void verifiesPortableBundleWithoutTrustingProducerStatusClaim() throws Exception {
        Fixture fixture = fixture(List.of(child("golden", "child-run-1", CHILD)));

        TestSuiteEvidenceVerifier.VerificationResult result =
                new TestSuiteEvidenceVerifier().verify(fixture.bundle(), fixture.key());

        assertThat(result.verified()).isTrue();
        assertThat(result.reasonCode()).isEqualTo("VERIFIED");
        assertThat(fixture.bundle().rawResponse().toString())
                .doesNotContain("\"input\":", "\"output\":", "\"requestMetadata\":");
    }

    @Test
    void verifiesSchemaAdmissionBundleWithSignedEmptyBusinessChildClosure() throws Exception {
        Fixture fixture = schemaAdmissionFixture();

        TestSuiteEvidenceVerifier.VerificationResult result =
                new TestSuiteEvidenceVerifier().verify(fixture.bundle(), fixture.key());

        assertThat(result.verified()).isTrue();
        assertThat(fixture.bundle().attestation().schemaVersion())
                .isEqualTo(TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V3);
        assertThat(fixture.bundle().attestation().childEvidenceRefs()).isEmpty();
        assertThat(fixture.bundle().rawResponse().path("schemaVersion").asText())
                .isEqualTo(TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V3);
        JsonNode metadata = fixture.bundle().evidence().path("metadata");
        assertThat(metadata.path("businessTargetInvoked").asBoolean()).isFalse();
        assertThat(metadata.path("childRunCount").asInt()).isZero();
    }

    @Test
    void aggregateMutationAndSignatureMutationAreRejected() throws Exception {
        Fixture fixture = fixture(List.of(child("golden", "child-run-1", CHILD)));
        ObjectNode alteredEvidence = (ObjectNode) fixture.bundle().evidence();
        alteredEvidence.withObject("/metadata").put("tampered", true);
        TestSuiteEvidenceBundle altered = new TestSuiteEvidenceBundle(fixture.bundle().suiteRunId(),
                fixture.bundle().bundleFingerprint(), fixture.bundle().payloadPolicy(),
                fixture.bundle().attestation(), alteredEvidence, fixture.bundle().rawResponse());
        TestSuiteRunAttestation signed = fixture.bundle().attestation();
        TestSuiteRunAttestation badSignature = new TestSuiteRunAttestation(signed.schemaVersion(),
                signed.signatureStatus(), signed.scope(), signed.suiteRunId(), signed.suiteRef(),
                signed.requestFingerprint(), signed.aggregateEvidenceFingerprint(),
                signed.childEvidenceRefs(), signed.signedAt(), signed.keyId(), signed.algorithm(),
                Base64.getEncoder().encodeToString(new byte[64]), true);
        TestSuiteEvidenceBundle invalidSignature = new TestSuiteEvidenceBundle(
                fixture.bundle().suiteRunId(), fixture.bundle().bundleFingerprint(),
                fixture.bundle().payloadPolicy(), badSignature, fixture.bundle().evidence(),
                fixture.bundle().rawResponse());

        assertThat(new TestSuiteEvidenceVerifier().verify(altered, fixture.key()).reasonCode())
                .isEqualTo("AGGREGATE_FINGERPRINT_INVALID");
        assertThat(new TestSuiteEvidenceVerifier().verify(invalidSignature, fixture.key()).verified())
                .isFalse();
    }

    @Test
    void signedChildClosureOrderIsCheckedAgainstSuiteCaseOrder() throws Exception {
        Fixture fixture = fixture(List.of(
                child("other", "child-run-2", "sha256:" + "f".repeat(64)),
                child("golden", "child-run-1", CHILD)));

        TestSuiteEvidenceVerifier.VerificationResult result =
                new TestSuiteEvidenceVerifier().verify(fixture.bundle(), fixture.key());

        assertThat(result.outcome()).isEqualTo(TestSuiteEvidenceVerifier.Outcome.INVALID);
        assertThat(result.reasonCode()).isEqualTo("CHILD_EVIDENCE_CLOSURE_INVALID");
    }

    @Test
    void missingOrRevokedKeyCannotProduceVerifiedResult() throws Exception {
        Fixture fixture = fixture(List.of(child("golden", "child-run-1", CHILD)));
        EvidenceVerificationKey revoked = new EvidenceVerificationKey(
                fixture.key().schemaVersion(), fixture.key().keyId(), fixture.key().algorithm(),
                fixture.key().encodedPublicKey(), fixture.key().createdAt(), "REVOKED", "test");

        assertThat(new TestSuiteEvidenceVerifier().verify(fixture.bundle(), null).outcome())
                .isEqualTo(TestSuiteEvidenceVerifier.Outcome.KEY_UNAVAILABLE);
        assertThat(new TestSuiteEvidenceVerifier().verify(fixture.bundle(), revoked).outcome())
                .isEqualTo(TestSuiteEvidenceVerifier.Outcome.POLICY_REJECTED);
    }

    @Test
    void mixedEvidenceAttestationAndBundleGenerationsFailClosed() throws Exception {
        Fixture fixture = fixture(List.of(child("golden", "child-run-1", CHILD)));
        ObjectNode v2Evidence = (ObjectNode) fixture.bundle().evidence();
        v2Evidence.put("schemaVersion", TestingProtocol.TEST_SUITE_RUN_EVIDENCE_V2);
        TestSuiteEvidenceBundle mixed = new TestSuiteEvidenceBundle(
                fixture.bundle().suiteRunId(), fixture.bundle().bundleFingerprint(),
                fixture.bundle().payloadPolicy(), fixture.bundle().attestation(), v2Evidence,
                fixture.bundle().rawResponse());

        assertThat(new TestSuiteEvidenceVerifier().verify(mixed, fixture.key()).reasonCode())
                .isEqualTo("EVIDENCE_GENERATION_MISMATCH");
    }

    @Test
    void verifiesPinnedCompleteKeySetAndZeroDowntimeRotationOverlap() throws Exception {
        Fixture fixture = fixture(List.of(child("golden", "child-run-1", CHILD)));
        EvidenceVerificationKeySet keySet = keySet(fixture,
                EvidenceVerificationKeySet.KeyState.VERIFY_ONLY,
                EvidenceVerificationKeySet.PolicyCompleteness.COMPLETE,
                Instant.parse("2026-07-17T00:00:00Z"), List.of());
        TestSuiteEvidenceVerifier verifier = verifierAt("2026-07-16T11:00:00Z");

        TestSuiteEvidenceVerifier.VerificationResult result = verifier.verify(
                fixture.bundle(), keySet, keySet.snapshotFingerprint());

        assertThat(result.verified()).isTrue();
        assertThat(verifier.verifyKeySet(keySet, keySet.snapshotFingerprint()).verified()).isTrue();
    }

    @Test
    void prospectiveAndRetroactiveRevocationUseEvidenceSigningTime() throws Exception {
        Fixture fixture = fixture(List.of(child("golden", "child-run-1", CHILD)));
        EvidenceVerificationKeySet.LifecycleEvent prospectiveAfterSigning = event(3,
                EvidenceVerificationKeySet.EventType.REVOKED,
                Instant.parse("2026-07-16T10:30:00Z"),
                EvidenceVerificationKeySet.RevocationMode.PROSPECTIVE, null);
        EvidenceVerificationKeySet allowed = keySet(fixture,
                EvidenceVerificationKeySet.KeyState.REVOKED,
                EvidenceVerificationKeySet.PolicyCompleteness.COMPLETE,
                Instant.parse("2026-07-17T00:00:00Z"), List.of(prospectiveAfterSigning));
        EvidenceVerificationKeySet.LifecycleEvent prospectiveBeforeSigning = event(3,
                EvidenceVerificationKeySet.EventType.REVOKED,
                Instant.parse("2026-07-16T10:15:00Z"),
                EvidenceVerificationKeySet.RevocationMode.PROSPECTIVE, null);
        EvidenceVerificationKeySet rejected = keySet(fixture,
                EvidenceVerificationKeySet.KeyState.REVOKED,
                EvidenceVerificationKeySet.PolicyCompleteness.COMPLETE,
                Instant.parse("2026-07-17T00:00:00Z"), List.of(prospectiveBeforeSigning));
        EvidenceVerificationKeySet.LifecycleEvent compromise = event(3,
                EvidenceVerificationKeySet.EventType.COMPROMISE_DECLARED,
                Instant.parse("2026-07-16T11:00:00Z"),
                EvidenceVerificationKeySet.RevocationMode.RETROACTIVE,
                Instant.parse("2026-07-16T10:14:45Z"));
        EvidenceVerificationKeySet retroactive = keySet(fixture,
                EvidenceVerificationKeySet.KeyState.REVOKED,
                EvidenceVerificationKeySet.PolicyCompleteness.COMPLETE,
                Instant.parse("2026-07-17T00:00:00Z"), List.of(compromise));
        TestSuiteEvidenceVerifier verifier = verifierAt("2026-07-16T11:05:00Z");

        assertThat(verifier.verify(fixture.bundle(), allowed, allowed.snapshotFingerprint()).verified())
                .isTrue();
        assertThat(verifier.verify(fixture.bundle(), rejected, rejected.snapshotFingerprint()).reasonCode())
                .isEqualTo("EVIDENCE_KEY_REVOKED_AT_SIGNING_TIME");
        assertThat(verifier.verify(fixture.bundle(), retroactive,
                retroactive.snapshotFingerprint()).reasonCode())
                .isEqualTo("EVIDENCE_KEY_REVOKED_AT_SIGNING_TIME");
    }

    @Test
    void pinPolicyFreshnessAndHistoryCompletenessFailClosedWithDistinctReasons() throws Exception {
        Fixture fixture = fixture(List.of(child("golden", "child-run-1", CHILD)));
        EvidenceVerificationKeySet complete = keySet(fixture,
                EvidenceVerificationKeySet.KeyState.ACTIVE,
                EvidenceVerificationKeySet.PolicyCompleteness.COMPLETE,
                Instant.parse("2026-07-17T00:00:00Z"), List.of());
        EvidenceVerificationKeySet incomplete = keySet(fixture,
                EvidenceVerificationKeySet.KeyState.ACTIVE,
                EvidenceVerificationKeySet.PolicyCompleteness.CURRENT_STATE_ONLY,
                Instant.parse("2026-07-17T00:00:00Z"), List.of());
        EvidenceVerificationKeySet stale = keySet(fixture,
                EvidenceVerificationKeySet.KeyState.ACTIVE,
                EvidenceVerificationKeySet.PolicyCompleteness.COMPLETE,
                Instant.parse("2026-07-16T10:45:00Z"), List.of());
        TestSuiteEvidenceVerifier verifier = verifierAt("2026-07-16T11:00:00Z");

        assertThat(verifier.verifyKeySet(complete, "sha256:" + "0".repeat(64)).reasonCode())
                .isEqualTo("KEY_SET_PIN_MISMATCH");
        assertThat(verifier.verifyKeySet(incomplete, incomplete.snapshotFingerprint()).reasonCode())
                .isEqualTo("KEY_LIFECYCLE_POLICY_INCOMPLETE");
        assertThat(verifier.verifyKeySet(stale, stale.snapshotFingerprint()).reasonCode())
                .isEqualTo("KEY_SET_STALE");
    }

    @Test
    void rejectsEvidenceSignedAfterRetirementAndTamperedPinnedMaterial() throws Exception {
        Fixture fixture = fixture(List.of(child("golden", "child-run-1", CHILD)));
        EvidenceVerificationKeySet retiredBeforeSigning = keySet(fixture,
                EvidenceVerificationKeySet.KeyState.VERIFY_ONLY,
                EvidenceVerificationKeySet.PolicyCompleteness.COMPLETE,
                Instant.parse("2026-07-17T00:00:00Z"), List.of(),
                SIGNED_AT.minusSeconds(1));
        EvidenceVerificationKeySet valid = keySet(fixture,
                EvidenceVerificationKeySet.KeyState.ACTIVE,
                EvidenceVerificationKeySet.PolicyCompleteness.COMPLETE,
                Instant.parse("2026-07-17T00:00:00Z"), List.of());
        ObjectNode tamperedRaw = (ObjectNode) valid.rawSnapshot();
        ((ObjectNode) tamperedRaw.withArray("events").get(0)).put("reasonCode", "TAMPERED");
        EvidenceVerificationKeySet tampered = new EvidenceVerificationKeySet(valid.schemaVersion(),
                valid.snapshotFingerprint(), valid.provider(), valid.generatedAt(), valid.expiresAt(),
                valid.activeKeyId(), valid.policyCompleteness(), valid.keys(), valid.events(),
                valid.attestation(), tamperedRaw);
        EvidenceVerificationKeySet reactivated = keySet(fixture,
                EvidenceVerificationKeySet.KeyState.ACTIVE,
                EvidenceVerificationKeySet.PolicyCompleteness.COMPLETE,
                Instant.parse("2026-07-17T00:00:00Z"), List.of(
                event(3, EvidenceVerificationKeySet.EventType.RETIRED,
                        SIGNED_AT.minusSeconds(20), null, null),
                event(4, EvidenceVerificationKeySet.EventType.ACTIVATED,
                        SIGNED_AT.minusSeconds(10), null, null)));
        TestSuiteEvidenceVerifier verifier = verifierAt("2026-07-16T11:00:00Z");

        assertThat(verifier.verify(fixture.bundle(), retiredBeforeSigning,
                retiredBeforeSigning.snapshotFingerprint()).reasonCode())
                .isEqualTo("EVIDENCE_KEY_RETIRED_AT_SIGNING_TIME");
        assertThat(verifier.verifyKeySet(tampered, tampered.snapshotFingerprint()).reasonCode())
                .isEqualTo("KEY_SET_MATERIAL_INVALID");
        assertThat(verifier.verify(fixture.bundle(), reactivated,
                reactivated.snapshotFingerprint()).verified()).isTrue();
    }

    private static Fixture fixture(List<TestSuiteRunAttestation.ChildEvidenceRef> children)
            throws Exception {
        return fixture(evidence(), TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V1,
                TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V1, children);
    }

    private static Fixture schemaAdmissionFixture() throws Exception {
        return fixture(schemaAdmissionEvidence(), TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V3,
                TestingProtocol.TEST_SUITE_EVIDENCE_BUNDLE_V3, List.of());
    }

    private static Fixture fixture(
            ObjectNode evidence, String attestationVersion, String bundleVersion,
            List<TestSuiteRunAttestation.ChildEvidenceRef> children) throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String aggregateFingerprint = fingerprint(evidence);
        ObjectNode material = signatureMaterial(attestationVersion, aggregateFingerprint, children);
        String materialFingerprint = fingerprint(material);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(materialFingerprint.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        String keyId = "test-ed25519-1";
        TestSuiteRunAttestation attestation = new TestSuiteRunAttestation(
                attestationVersion,
                TestSuiteRunAttestation.SignatureStatus.VERIFIED,
                TestSuiteRunAttestation.Scope.TERMINAL, "suite-run-1",
                new TestSuiteRunAttestation.SuiteRef("suite-a", 3, SUITE), REQUEST,
                aggregateFingerprint, children, SIGNED_AT, keyId, "Ed25519", signature, true);
        ObjectNode attestationJson = attestationJson(attestation);
        ObjectNode bundleMaterial = JSON.createObjectNode();
        bundleMaterial.put("payloadPolicy", "OMITTED");
        bundleMaterial.set("attestation", attestationJson);
        bundleMaterial.set("evidence", evidence);
        ObjectNode response = JSON.createObjectNode();
        response.put("schemaVersion", bundleVersion);
        response.put("suiteRunId", "suite-run-1");
        response.put("bundleFingerprint", fingerprint(bundleMaterial));
        response.put("payloadPolicy", "OMITTED");
        response.set("attestation", attestationJson);
        response.set("evidence", evidence);
        TestSuiteEvidenceBundle bundle = TestSuiteEvidenceBundle.from(response);
        EvidenceVerificationKey key = new EvidenceVerificationKey(
                TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1, keyId, "Ed25519",
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                SIGNED_AT.minusSeconds(60), "ACTIVE", "test");
        return new Fixture(bundle, key, keyPair);
    }

    private static EvidenceVerificationKeySet keySet(
            Fixture fixture, EvidenceVerificationKeySet.KeyState evidenceKeyState,
            EvidenceVerificationKeySet.PolicyCompleteness completeness, Instant expiresAt,
            List<EvidenceVerificationKeySet.LifecycleEvent> policyEvents) throws Exception {
        return keySet(fixture, evidenceKeyState, completeness, expiresAt, policyEvents,
                SIGNED_AT.plusSeconds(1));
    }

    private static EvidenceVerificationKeySet keySet(
            Fixture fixture, EvidenceVerificationKeySet.KeyState evidenceKeyState,
            EvidenceVerificationKeySet.PolicyCompleteness completeness, Instant expiresAt,
            List<EvidenceVerificationKeySet.LifecycleEvent> policyEvents,
            Instant terminalStateAt) throws Exception {
        KeyPair activePair = evidenceKeyState == EvidenceVerificationKeySet.KeyState.ACTIVE
                ? fixture.keyPair() : KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String activeKeyId = evidenceKeyState == EvidenceVerificationKeySet.KeyState.ACTIVE
                ? fixture.key().keyId() : "test-ed25519-2";
        Instant generatedAt = policyEvents.stream()
                .map(EvidenceVerificationKeySet.LifecycleEvent::occurredAt)
                .reduce(SIGNED_AT.plusSeconds(60), (left, right) -> left.isAfter(right) ? left : right);
        ArrayNode keys = JSON.createArrayNode();
        keys.add(keyPolicy(fixture.key().keyId(), fixture.keyPair(), evidenceKeyState,
                SIGNED_AT.minusSeconds(60)));
        if (!activeKeyId.equals(fixture.key().keyId())) {
            keys.add(keyPolicy(activeKeyId, activePair, EvidenceVerificationKeySet.KeyState.ACTIVE,
                    generatedAt));
        }
        ArrayNode events = JSON.createArrayNode();
        long sequence = 0;
        events.add(eventJson(new EvidenceVerificationKeySet.LifecycleEvent(++sequence,
                "created:" + fixture.key().keyId(), fixture.key().keyId(),
                EvidenceVerificationKeySet.EventType.CREATED, fixture.key().createdAt(),
                fixture.key().createdAt(), null, null, "KEY_CREATED")));
        events.add(eventJson(new EvidenceVerificationKeySet.LifecycleEvent(++sequence,
                "activated:" + fixture.key().keyId(), fixture.key().keyId(),
                EvidenceVerificationKeySet.EventType.ACTIVATED, fixture.key().createdAt(),
                fixture.key().createdAt(), null, null, "KEY_ACTIVATED")));
        EvidenceVerificationKeySet.EventType evidenceStateEvent = switch (evidenceKeyState) {
            case ACTIVE, REVOKED -> null;
            case VERIFY_ONLY -> EvidenceVerificationKeySet.EventType.RETIRED;
            case DISABLED -> EvidenceVerificationKeySet.EventType.DISABLED;
        };
        if (evidenceStateEvent != null) {
            events.add(eventJson(new EvidenceVerificationKeySet.LifecycleEvent(++sequence,
                    evidenceStateEvent.name().toLowerCase() + ":" + fixture.key().keyId(),
                    fixture.key().keyId(), evidenceStateEvent, terminalStateAt,
                    terminalStateAt, null, null, "KEY_" + evidenceStateEvent.name())));
        }
        if (!activeKeyId.equals(fixture.key().keyId())) {
            events.add(eventJson(new EvidenceVerificationKeySet.LifecycleEvent(++sequence,
                    "created:" + activeKeyId, activeKeyId, EvidenceVerificationKeySet.EventType.CREATED,
                    generatedAt, generatedAt, null, null, "KEY_CREATED")));
            events.add(eventJson(new EvidenceVerificationKeySet.LifecycleEvent(++sequence,
                    "activated:" + activeKeyId, activeKeyId,
                    EvidenceVerificationKeySet.EventType.ACTIVATED, generatedAt, generatedAt,
                    null, null, "KEY_ACTIVATED")));
        }
        for (EvidenceVerificationKeySet.LifecycleEvent event : policyEvents) {
            events.add(eventJson(new EvidenceVerificationKeySet.LifecycleEvent(++sequence,
                    event.eventId(), fixture.key().keyId(), event.type(), event.occurredAt(),
                    event.effectiveAt(), event.revocationMode(), event.invalidFrom(), event.reasonCode())));
        }
        ObjectNode material = JSON.createObjectNode();
        material.put("schemaVersion", TestingProtocol.EVIDENCE_VERIFICATION_KEY_SET_V1);
        material.put("provider", "test");
        material.put("generatedAt", generatedAt.toString());
        material.put("expiresAt", expiresAt.toString());
        material.put("activeKeyId", activeKeyId);
        material.put("policyCompleteness", completeness.name());
        material.set("keys", keys);
        material.set("events", events);
        String snapshotFingerprint = fingerprint(material);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(activePair.getPrivate());
        signer.update(snapshotFingerprint.getBytes(StandardCharsets.UTF_8));
        ObjectNode snapshot = material.deepCopy();
        snapshot.put("snapshotFingerprint", snapshotFingerprint);
        ObjectNode seal = snapshot.putObject("attestation");
        seal.put("schemaVersion", "bloge.visualRunEvidenceSeal.v1");
        seal.put("materialFingerprint", snapshotFingerprint);
        seal.put("algorithm", "Ed25519");
        seal.put("keyId", activeKeyId);
        seal.put("signedAt", generatedAt.plusSeconds(1).toString());
        seal.put("signature", Base64.getEncoder().encodeToString(signer.sign()));
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("payloadKind", "EVIDENCE_VERIFICATION_KEY_SET");
        envelope.put("payloadSchemaVersion", TestingProtocol.EVIDENCE_VERIFICATION_KEY_SET_V1);
        envelope.set("payload", snapshot);
        return EvidenceVerificationKeySet.fromEnvelope(envelope);
    }

    private static ObjectNode keyPolicy(String keyId, KeyPair keyPair,
                                        EvidenceVerificationKeySet.KeyState state,
                                        Instant createdAt) {
        ObjectNode key = JSON.createObjectNode();
        key.put("keyId", keyId);
        key.put("algorithm", "Ed25519");
        key.put("encodedPublicKey", Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        key.put("createdAt", createdAt.toString());
        key.put("notBefore", createdAt.toString());
        key.putNull("notAfter");
        key.put("state", state.name());
        key.put("providerKeyVersion", "version/" + keyId);
        return key;
    }

    private static EvidenceVerificationKeySet.LifecycleEvent event(
            long sequence, EvidenceVerificationKeySet.EventType type, Instant effectiveAt,
            EvidenceVerificationKeySet.RevocationMode mode, Instant invalidFrom) {
        return new EvidenceVerificationKeySet.LifecycleEvent(sequence, "policy-event-" + sequence,
                "placeholder", type, effectiveAt, effectiveAt, mode, invalidFrom,
                type == EvidenceVerificationKeySet.EventType.COMPROMISE_DECLARED
                        ? "KEY_COMPROMISED" : "KEY_" + type.name());
    }

    private static ObjectNode eventJson(EvidenceVerificationKeySet.LifecycleEvent event) {
        ObjectNode value = JSON.createObjectNode();
        value.put("sequence", event.sequence());
        value.put("eventId", event.eventId());
        value.put("keyId", event.keyId());
        value.put("type", event.type().name());
        value.put("occurredAt", event.occurredAt().toString());
        value.put("effectiveAt", event.effectiveAt().toString());
        if (event.revocationMode() == null) {
            value.putNull("revocationMode");
        } else {
            value.put("revocationMode", event.revocationMode().name());
        }
        if (event.invalidFrom() == null) {
            value.putNull("invalidFrom");
        } else {
            value.put("invalidFrom", event.invalidFrom().toString());
        }
        value.put("reasonCode", event.reasonCode());
        return value;
    }

    private static TestSuiteEvidenceVerifier verifierAt(String instant) {
        return new TestSuiteEvidenceVerifier(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static ObjectNode evidence() {
        ObjectNode value = JSON.createObjectNode();
        value.put("schemaVersion", TestingProtocol.TEST_SUITE_RUN_EVIDENCE_V1);
        value.put("suiteRunId", "suite-run-1");
        value.put("clientRequestId", "request-1");
        value.put("status", "PASSED");
        value.put("executionPurpose", "TEST_SUITE_EXECUTION");
        exactRef(value.putObject("suiteRef"), "suiteId", "suite-a", 3, SUITE);
        ObjectNode target = value.putObject("target");
        target.put("kind", "GRAPH");
        target.put("id", "graph-a");
        target.put("fingerprint", TARGET);
        value.put("startedAt", "2026-07-16T10:15:00Z");
        value.put("completedAt", SIGNED_AT.toString());
        ObjectNode result = value.putArray("caseResults").addObject();
        result.put("caseId", "golden");
        result.put("caseType", "GOLDEN");
        exactRef(result.putObject("fixtureBundleRef"), "fixtureBundleId", "fixture-a", 1, FIXTURE);
        result.put("status", "PASSED");
        result.put("runId", "child-run-1");
        result.put("evidenceStatus", "PASSED");
        result.put("evidenceClass", "CERTIFIABLE");
        result.put("assertionsEvaluated", 1);
        result.put("assertionsPassed", 1);
        result.put("diagnosticCode", "");
        result.put("diagnostic", "");
        ObjectNode coverage = value.putObject("coverage");
        coverage.put("status", "SATISFIED");
        coverage.put("minimumCases", 1);
        coverage.put("completedCases", 1);
        coverage.putArray("requiredCaseTypes").add("GOLDEN");
        coverage.putArray("observedCaseTypes").add("GOLDEN");
        coverage.putArray("missingCaseTypes");
        coverage.putArray("requiredInvocationSiteIds");
        coverage.putArray("observedInvocationSiteIds");
        coverage.putArray("missingInvocationSiteIds");
        coverage.putArray("requiredEdgeTransfers");
        coverage.putArray("observedEdgeTransfers");
        coverage.putArray("missingEdgeTransfers");
        coverage.put("minimumAssertionsPerCase", 1);
        coverage.putArray("assertionDensityViolations");
        coverage.putArray("fixtureConsumptionViolations");
        coverage.put("allCasesCompleted", true);
        ObjectNode promotion = value.putObject("promotion");
        promotion.put("status", "ELIGIBLE");
        promotion.putArray("reasons");
        promotion.put("allCasesPassed", true);
        promotion.put("certifiableCases", 1);
        promotion.put("minimumCertifiableCases", 1);
        promotion.put("targetCertificationEligible", true);
        promotion.put("coverageSatisfied", true);
        promotion.put("allCasesCompleted", true);
        value.putArray("diagnostics");
        value.putObject("metadata").put("requestMetadataFingerprint", REQUEST);
        return value;
    }

    private static ObjectNode schemaAdmissionEvidence() {
        ObjectNode value = evidence();
        value.put("schemaVersion", TestingProtocol.TEST_SUITE_RUN_EVIDENCE_V3);
        value.put("executionPurpose", "SCHEMA_ADMISSION_SUITE_EXECUTION");
        ObjectNode result = (ObjectNode) value.withArray("caseResults").get(0);
        result.put("runId", "");
        result.putNull("evidenceStatus");
        result.putNull("evidenceClass");
        result.put("assertionsEvaluated", 0);
        result.put("assertionsPassed", 0);
        ObjectNode coverage = (ObjectNode) value.path("coverage");
        coverage.put("status", "NOT_EVALUATED");
        coverage.put("minimumCases", 0);
        coverage.put("completedCases", 0);
        coverage.putArray("requiredCaseTypes");
        coverage.putArray("observedCaseTypes");
        coverage.put("minimumAssertionsPerCase", 0);
        coverage.put("allCasesCompleted", false);
        ObjectNode promotion = (ObjectNode) value.path("promotion");
        promotion.put("status", "BLOCKED");
        promotion.putArray("reasons")
                .add("BUSINESS_EXECUTION_NOT_PERFORMED")
                .add("SCHEMA_ADMISSION_ONLY");
        promotion.put("certifiableCases", 0);
        promotion.put("minimumCertifiableCases", 0);
        promotion.put("targetCertificationEligible", false);
        promotion.put("coverageSatisfied", false);
        value.put("evaluationMode", "SCHEMA_ADMISSION");
        value.put("boundaryPlanFingerprint", REQUEST);
        value.put("inputSchemaFingerprint", TARGET);
        value.put("generatorVersion", "boundary-generator.v1");
        value.put("verificationMode", "EXACT_SHARED_VALIDATOR");
        value.put("sourcePlanStatus", "GENERATED");
        value.put("sourceCoverageGapCount", 0);
        value.put("coverageGapsAccepted", false);
        ObjectNode admission = value.putArray("admissionResults").addObject();
        admission.put("caseId", "golden");
        admission.put("status", "MATCHED");
        admission.put("expectedOutcome", "ACCEPTED");
        admission.put("observedOutcome", "ACCEPTED");
        admission.putArray("expectedValidationCodes");
        admission.putArray("observedValidationCodes");
        admission.put("diagnosticCode", "");
        ObjectNode admissionCoverage = value.putObject("admissionCoverage");
        admissionCoverage.put("status", "SATISFIED");
        admissionCoverage.put("requiredCases", 1);
        admissionCoverage.put("evaluatedCases", 1);
        admissionCoverage.put("matchedCases", 1);
        admissionCoverage.putArray("expectationMismatchCaseIds");
        admissionCoverage.putArray("provenanceMismatchCaseIds");
        admissionCoverage.putArray("incompleteCaseIds");
        admissionCoverage.put("allCasesCompleted", true);
        ObjectNode metadata = (ObjectNode) value.path("metadata");
        metadata.put("businessTargetInvoked", false);
        metadata.put("childRunCount", 0);
        return value;
    }

    private static ObjectNode signatureMaterial(
            String schemaVersion, String aggregateFingerprint,
            List<TestSuiteRunAttestation.ChildEvidenceRef> children) {
        ObjectNode value = JSON.createObjectNode();
        value.put("schemaVersion", schemaVersion);
        value.put("scope", "TERMINAL");
        value.put("suiteRunId", "suite-run-1");
        exactRef(value.putObject("suiteRef"), "suiteId", "suite-a", 3, SUITE);
        value.put("requestFingerprint", REQUEST);
        value.put("aggregateEvidenceFingerprint", aggregateFingerprint);
        ArrayNode refs = value.putArray("childEvidenceRefs");
        children.forEach(child -> {
            ObjectNode ref = refs.addObject();
            ref.put("caseId", child.caseId());
            ref.put("runId", child.runId());
            ref.put("evidenceFingerprint", child.evidenceFingerprint());
        });
        value.put("signedAt", SIGNED_AT.toString());
        return value;
    }

    private static ObjectNode attestationJson(TestSuiteRunAttestation value) {
        ObjectNode node = signatureMaterial(value.schemaVersion(), value.aggregateEvidenceFingerprint(),
                value.childEvidenceRefs());
        node.put("signatureStatus", value.signatureStatus().name());
        node.put("keyId", value.keyId());
        node.put("algorithm", value.algorithm());
        node.put("signature", value.signature());
        node.put("independentlyVerifiable", value.independentlyVerifiable());
        return node;
    }

    private static void exactRef(ObjectNode target, String idField, String id,
                                 long revision, String fingerprint) {
        target.put(idField, id);
        target.put("revision", revision);
        target.put("fingerprint", fingerprint);
    }

    private static TestSuiteRunAttestation.ChildEvidenceRef child(
            String caseId, String runId, String evidenceFingerprint) {
        return new TestSuiteRunAttestation.ChildEvidenceRef(caseId, runId, evidenceFingerprint);
    }

    private static String fingerprint(JsonNode value) throws Exception {
        byte[] canonical = JSON.writeValueAsBytes(canonical(value));
        return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical));
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> sorted.set(name, canonical(value.get(name))));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return value.deepCopy();
    }

    private record Fixture(TestSuiteEvidenceBundle bundle, EvidenceVerificationKey key,
                           KeyPair keyPair) {
    }
}
