package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioRehearsalWorkbookVerifierTest {
    private static final ObjectMapper JSON =
            new ObjectMapper().findAndRegisterModules();
    private static final Instant STARTED =
            Instant.parse("2026-07-24T08:00:00Z");
    private static final Instant COMPLETED =
            STARTED.plusSeconds(1);
    private KeyPair evidencePair;
    private KeyPair retentionPair;
    private EvidenceVerificationKey evidenceKey;
    private EvidenceVerificationKey retentionKey;
    private ScenarioRehearsalWorkbookVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        evidencePair = keyPair();
        retentionPair = keyPair();
        evidenceKey = key(
                evidencePair,
                "scenario-key-1",
                STARTED.minusSeconds(60));
        retentionKey = key(
                retentionPair,
                "scenario-retention-key-1",
                STARTED.minusSeconds(60));
        verifier = new ScenarioRehearsalWorkbookVerifier();
    }

    @Test
    void independentlyReconstructsAGateReadyWorkbook()
            throws Exception {
        Fixture fixture = fixture();

        ScenarioRehearsalWorkbookVerifier.VerificationResult result =
                verifier.verify(
                        fixture.workbook(),
                        fixture.plan(),
                        fixture.bundle(),
                        evidenceKey,
                        retentionKey);

        assertThat(result.verified())
                .as(result.toString())
                .isTrue();
        assertThat(result.gateReady()).isTrue();
        assertThat(result.blockers()).isEmpty();
        assertThat(result.seedFingerprint())
                .isEqualTo(fixture.workbook()
                        .path("seedFingerprint").asText());
    }

    @Test
    void rejectsAResealedWorkbookThatDriftsFromTheCompiledCase()
            throws Exception {
        Fixture fixture = fixture();
        ObjectNode drifted =
                fixture.workbook().deepCopy();
        ((ObjectNode) drifted.path("cases").get(0))
                .put("testCaseId", "different-case");
        sealWorkbook(drifted);

        ScenarioRehearsalWorkbookVerifier.VerificationResult result =
                verifier.verify(
                        drifted,
                        fixture.plan(),
                        fixture.bundle(),
                        evidenceKey,
                        retentionKey);

        assertThat(result.outcome())
                .isEqualTo(
                        ScenarioRehearsalWorkbookVerifier.Outcome.INVALID);
        assertThat(result.reasonCode())
                .isEqualTo(
                        "SCENARIO_WORKBOOK_CASE_CLOSURE_INVALID");
    }

    @Test
    void rejectsAProducerSelectedGateDecision()
            throws Exception {
        Fixture fixture = fixture();
        ObjectNode changed =
                fixture.workbook().deepCopy();
        changed.put("gateReady", false);
        changed.putArray("blockers")
                .add("REHEARSAL_FAILED");
        sealWorkbook(changed);

        ScenarioRehearsalWorkbookVerifier.VerificationResult result =
                verifier.verify(
                        changed,
                        fixture.plan(),
                        fixture.bundle(),
                        evidenceKey,
                        retentionKey);

        assertThat(result.outcome())
                .isEqualTo(
                        ScenarioRehearsalWorkbookVerifier.Outcome.INVALID);
    }

    @Test
    void distinguishesMissingEvidenceAndRetentionKeys()
            throws Exception {
        Fixture fixture = fixture();

        assertThat(verifier.verify(
                fixture.workbook(),
                fixture.plan(),
                fixture.bundle(),
                null,
                retentionKey).outcome())
                .isEqualTo(
                        ScenarioRehearsalWorkbookVerifier
                                .Outcome.KEY_UNAVAILABLE);
        assertThat(verifier.verify(
                fixture.workbook(),
                fixture.plan(),
                fixture.bundle(),
                evidenceKey,
                null).outcome())
                .isEqualTo(
                        ScenarioRehearsalWorkbookVerifier
                                .Outcome.KEY_UNAVAILABLE);
    }

    @Test
    void clientFetchesAndVerifiesTheCompleteWorkbookClosure()
            throws Exception {
        Fixture fixture = fixture();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> requests = new ArrayList<>();
        server.createContext(
                "/", exchange -> serveWorkbookClosure(
                        exchange, fixture, requests));
        server.start();
        try {
            ResourceGatewayTestClient client =
                    ResourceGatewayTestClient.builder(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + server.getAddress()
                                                    .getPort()))
                            .bearerToken(() -> "test-token")
                            .build();

            JsonNode workbook =
                    client.findScenarioRehearsalWorkbookSeed(
                            runId());

            assertThat(workbook)
                    .isEqualTo(fixture.workbook());
            assertThat(requests).containsExactly(
                    "/api/mirror/scenarios/runs/"
                            + runId() + "/workbook-seed",
                    "/api/mirror/scenarios/runs/"
                            + runId() + "/evidence",
                    "/api/mirror/scenarios/compiled-plans/"
                            + fixture.plan().path("planId").asText()
                            .replace("@", "%40")
                            + "?revision=1&fingerprint=sha256%3A"
                            + fixture.plan().path("fingerprint")
                            .asText().substring(7),
                    "/api/integration/evidence-keys/"
                            + evidenceKey.keyId(),
                    "/api/integration/evidence-keys/"
                            + retentionKey.keyId());
        } finally {
            server.stop(0);
        }
    }

    private void serveWorkbookClosure(
            HttpExchange exchange,
            Fixture fixture,
            List<String> requests) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery();
        requests.add(
                path + (query == null ? "" : "?" + query));
        ObjectNode response;
        if (path.endsWith("/workbook-seed")) {
            response = envelope(
                    "SCENARIO_REHEARSAL_WORKBOOK_SEED",
                    CapabilityMirrorProtocol
                            .SCENARIO_REHEARSAL_WORKBOOK_SEED_V1,
                    fixture.workbook());
        } else if (path.endsWith("/evidence")) {
            response = envelope(
                    "SCENARIO_REHEARSAL_EVIDENCE_BUNDLE",
                    CapabilityMirrorProtocol
                            .SCENARIO_REHEARSAL_EVIDENCE_BUNDLE_V1,
                    fixture.bundle());
        } else if (path.contains("/compiled-plans/")) {
            response = envelope(
                    "COMPILED_SCENARIO_REHEARSAL_PLAN",
                    CapabilityMirrorProtocol
                            .COMPILED_SCENARIO_REHEARSAL_PLAN_V1,
                    fixture.plan());
        } else if (path.endsWith(
                "/" + evidenceKey.keyId())) {
            response = keyEnvelope(evidenceKey);
        } else if (path.endsWith(
                "/" + retentionKey.keyId())) {
            response = keyEnvelope(retentionKey);
        } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] body = JSON.writeValueAsBytes(response);
        exchange.getResponseHeaders().add(
                "Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static ObjectNode envelope(
            String kind,
            String version,
            JsonNode payload) {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put(
                "protocol",
                CapabilityMirrorProtocol.INTEGRATION_PROTOCOL);
        envelope.put(
                "protocolVersion",
                CapabilityMirrorProtocol
                        .INTEGRATION_PROTOCOL_V1);
        envelope.put("payloadKind", kind);
        envelope.put("payloadSchemaVersion", version);
        envelope.set("payload", payload);
        return envelope;
    }

    private static ObjectNode keyEnvelope(
            EvidenceVerificationKey key) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("schemaVersion", key.schemaVersion());
        payload.put("keyId", key.keyId());
        payload.put("algorithm", key.algorithm());
        payload.put(
                "encodedPublicKey",
                key.encodedPublicKey());
        payload.put(
                "createdAt", key.createdAt().toString());
        payload.put("state", key.state());
        payload.put("provider", key.provider());
        return envelope(
                "EVIDENCE_VERIFICATION_KEY",
                TestingProtocol
                        .EVIDENCE_VERIFICATION_KEY_V1,
                payload);
    }

    Fixture fixture() throws Exception {
        ObjectNode assertionRef = ref(
                "CASE_HANDLING_ASSERTION",
                "support-certifiable", '2');
        ObjectNode scenarioCaseRef =
                ref("SCENARIO_CASE", "support-golden", 'c');
        ObjectNode testSuiteRef =
                ref("TEST_SUITE", "support-suite", 'd');
        ObjectNode mirrorPlanRef =
                ref("MIRROR_PLAN", "support-plan", 'e');
        ObjectNode fixtureRef =
                ref("FIXTURE_BUNDLE", "support-fixture", 'f');
        ObjectNode scenarioPackRef =
                ref("SCENARIO_PACK", "support-pack", '6');
        ObjectNode targetRef =
                ref("CAPABILITY", "support", '1');
        ObjectNode plan = plan(
                scenarioPackRef, targetRef,
                scenarioCaseRef, testSuiteRef,
                mirrorPlanRef, fixtureRef,
                assertionRef);
        ObjectNode compiledPlanRef =
                ref(
                        "COMPILED_REHEARSAL_PLAN",
                        plan.path("planId").asText(),
                        plan.path("fingerprint").asText());
        ObjectNode assertion = assertion(
                assertionRef,
                mirrorPlanRef.path("fingerprint").asText());
        ObjectNode caseResult = caseResult(
                scenarioCaseRef, testSuiteRef,
                mirrorPlanRef, fixtureRef, assertion);
        ObjectNode aggregate = aggregate(
                compiledPlanRef, targetRef, caseResult);
        ObjectNode bundle = signedBundle(aggregate);
        ObjectNode retention = signedRegistration(
                bundle.path("bundleFingerprint").asText());
        ObjectNode workbook = workbook(
                plan, compiledPlanRef, bundle,
                retention, caseResult);
        return new Fixture(
                workbook, plan, bundle);
    }

    private ObjectNode plan(
            ObjectNode scenarioPackRef,
            ObjectNode targetRef,
            ObjectNode scenarioCaseRef,
            ObjectNode testSuiteRef,
            ObjectNode mirrorPlanRef,
            ObjectNode fixtureRef,
            ObjectNode assertionRef) {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                "resourceGateway.compiledScenarioRehearsalPlan.v1");
        value.put("planId", "support-pack@compiled");
        value.put("revision", 1);
        value.put("fingerprint", "");
        value.set("scope", scope());
        value.set("scenarioPackRef", scenarioPackRef);
        value.set("targetCapabilityRef", targetRef);
        ObjectNode binding =
                value.putArray("cases").addObject();
        binding.set("scenarioCaseRef", scenarioCaseRef);
        binding.put("caseType", "GOLDEN");
        binding.set("testSuiteRef", testSuiteRef);
        binding.put("testCaseId", "golden");
        binding.set("mirrorPlanRef", mirrorPlanRef);
        binding.set("fixtureBundleRef", fixtureRef);
        binding.putNull("sessionCheckpointRef");
        ObjectNode services =
                binding.putObject("executionServices");
        services.put("logicalClock", STARTED.toString());
        services.put("randomSeed", 42);
        services.putNull("identityFixtureRef");
        services.putNull("featureFlagFixtureRef");
        binding.putArray("assertionRefs")
                .add(assertionRef);
        value.putArray("assertionRefs")
                .add(assertionRef);
        ObjectNode policy = value.putObject("policy");
        policy.put("scheduling", "SEQUENTIAL");
        policy.put("isolatedCaseSessions", true);
        policy.put("realExternalCallsAllowed", false);
        policy.put("externalCredentialsAllowed", false);
        policy.put("networkEgressAllowed", false);
        policy.put("evidenceMode", "HASH_ONLY");
        policy.put("maximumCases", 10);
        policy.put("maximumInvocationsPerCase", 100);
        policy.put("caseTimeout", "PT5M");
        policy.put("totalTimeout", "PT30M");
        policy.put("certificationRequired", true);
        policy.put("maximumClassification", "CONFIDENTIAL");
        policy.putArray("allowedRegions").add("sg");
        sealFingerprint(
                value,
                "fingerprint",
                ScenarioRehearsalWorkbookVerifier
                        .MAXIMUM_PLAN_BYTES);
        return value;
    }

    private ObjectNode assertion(
            ObjectNode assertionRef,
            String planFingerprint) {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                "resourceGateway.scenarioHandlingAssertionResult.v1");
        value.put("resultFingerprint", "");
        value.put("runId", "mirror-run-1");
        value.put(
                "evidenceBundleFingerprint", fingerprint('7'));
        value.put("planFingerprint", planFingerprint);
        value.set("assertionRef", assertionRef);
        value.put("observation", "GOVERNANCE_EXPECTATION");
        value.put("outcome", "PASS");
        value.put("severity", "BLOCKER");
        value.put(
                "governanceCode",
                "RG.MIRROR.SCENARIO.CERTIFIABLE");
        value.put("reasonCode", "ASSERTION_MATCHED");
        ObjectNode observed =
                value.putObject("observed");
        observed.putArray("statuses").add("CERTIFIABLE");
        observed.putArray("errorCodes");
        observed.putArray("fingerprints");
        observed.putArray("sources").add("CERTIFIABLE");
        observed.put("booleanValue", true);
        observed.putArray("limitations");
        sealFingerprint(
                value,
                "resultFingerprint",
                ScenarioRehearsalEvidenceVerifier
                        .MAXIMUM_ASSERTION_BYTES);
        return value;
    }

    private ObjectNode caseResult(
            ObjectNode scenarioCaseRef,
            ObjectNode testSuiteRef,
            ObjectNode mirrorPlanRef,
            ObjectNode fixtureRef,
            ObjectNode assertion) {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                "resourceGateway.scenarioCaseRehearsalResult.v1");
        value.put("resultFingerprint", "");
        value.put("caseIndex", 0);
        value.set("scenarioCaseRef", scenarioCaseRef);
        value.put("caseType", "GOLDEN");
        value.set("testSuiteRef", testSuiteRef);
        value.put("testCaseId", "golden");
        value.set("mirrorPlanRef", mirrorPlanRef);
        value.set("fixtureBundleRef", fixtureRef);
        value.putNull("sessionCheckpointRef");
        value.put(
                "childRequestId",
                "scenario-request-1:case:000");
        value.put("outcome", "PASS");
        value.put("runId", "mirror-run-1");
        value.put(
                "evidenceBundleFingerprint", fingerprint('7'));
        value.put("evidenceStatus", "PASSED");
        value.put("evidenceClass", "CERTIFIABLE");
        value.putArray("assertionResults")
                .add(assertion);
        value.put("diagnosticCode", "");
        value.put("startedAt", STARTED.toString());
        value.put("completedAt", COMPLETED.toString());
        sealFingerprint(
                value,
                "resultFingerprint",
                ScenarioRehearsalEvidenceVerifier
                        .MAXIMUM_CASE_BYTES);
        return value;
    }

    private ObjectNode aggregate(
            ObjectNode compiledPlanRef,
            ObjectNode targetRef,
            ObjectNode caseResult) {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                "resourceGateway.scenarioRehearsalResult.v1");
        value.put("resultFingerprint", "");
        value.put("requestId", "scenario-request-1");
        value.set("compiledPlanRef", compiledPlanRef);
        value.set("scope", scope());
        value.set("targetCapabilityRef", targetRef);
        value.put("outcome", "PASS");
        value.putArray("caseResults").add(caseResult);
        value.set("summary", summary());
        value.put("startedAt", STARTED.toString());
        value.put("completedAt", COMPLETED.toString());
        sealFingerprint(
                value,
                "resultFingerprint",
                ScenarioRehearsalEvidenceVerifier
                        .MAXIMUM_RESULT_BYTES);
        return value;
    }

    private ObjectNode signedBundle(
            ObjectNode aggregate) throws Exception {
        ObjectNode attestation =
                JSON.createObjectNode();
        attestation.put(
                "schemaVersion",
                "resourceGateway.scenarioRehearsalEvidenceAttestation.v1");
        attestation.put("signatureStatus", "VERIFIED");
        attestation.put("runId", runId());
        attestation.put("requestId", "scenario-request-1");
        attestation.put(
                "compiledPlanFingerprint",
                aggregate.path("compiledPlanRef")
                        .path("fingerprint").asText());
        attestation.put(
                "resultFingerprint",
                aggregate.path("resultFingerprint").asText());
        attestation.put(
                "signedAt",
                COMPLETED.plusSeconds(1).toString());
        attestation.put("keyId", evidenceKey.keyId());
        attestation.put("algorithm", "Ed25519");
        attestation.put(
                "signature",
                sign(
                        evidencePair,
                        EvidenceVerificationSupport
                                .sha256Bounded(
                                        signatureMaterial(attestation),
                                        8 * 1024)));
        attestation.put(
                "independentlyVerifiable", true);

        ObjectNode bundle =
                JSON.createObjectNode();
        bundle.put(
                "schemaVersion",
                "resourceGateway.scenarioRehearsalEvidenceBundle.v1");
        bundle.put(
                "bundleFingerprint", fingerprint('0'));
        bundle.put("payloadPolicy", "HASH_ONLY");
        bundle.set("attestation", attestation);
        bundle.set("result", aggregate);
        ObjectNode material =
                JSON.createObjectNode();
        material.set(
                "schemaVersion",
                bundle.path("schemaVersion"));
        material.set(
                "payloadPolicy",
                bundle.path("payloadPolicy"));
        material.set(
                "attestation",
                bundle.path("attestation"));
        material.set("result", bundle.path("result"));
        bundle.put(
                "bundleFingerprint",
                EvidenceVerificationSupport.sha256Bounded(
                        material,
                        ScenarioRehearsalEvidenceVerifier
                                .MAXIMUM_BUNDLE_BYTES));
        return bundle;
    }

    private ObjectNode signedRegistration(
            String bundleFingerprint) throws Exception {
        ObjectNode event = JSON.createObjectNode();
        event.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_RETENTION_EVENT_V1);
        event.put("eventId", "retention-event-1");
        event.put("commandId", "retention-register-1");
        event.set("scope", scope());
        event.put("requestId", "scenario-request-1");
        event.put("runId", runId());
        event.put("revision", 1);
        event.put("type", "RETENTION_REGISTERED");
        event.put(
                "retainUntil",
                STARTED.plusSeconds(86400).toString());
        event.put(
                "occurredAt",
                COMPLETED.plusSeconds(2).toString());
        event.put("actorId", "scenario-runtime");
        event.put(
                "reasonCode",
                "RG.MIRROR.RETENTION_REGISTERED");
        event.put("holdId", "");
        event.put(
                "evidenceBundleFingerprint",
                bundleFingerprint);
        event.put("previousEventFingerprint", "");
        event.put("deletedCaseProgressCount", 0);
        event.put(
                "childEvidenceDisposition",
                "NOT_APPLICABLE");
        ObjectNode seal =
                event.putObject("evidenceSeal");
        seal.put(
                "schemaVersion",
                "bloge.visualRunEvidenceSeal.v1");
        seal.put("materialFingerprint", fingerprint('0'));
        seal.put("algorithm", "Ed25519");
        seal.put("keyId", retentionKey.keyId());
        seal.put(
                "signedAt",
                COMPLETED.plusSeconds(3).toString());
        seal.put("signature", "placeholder");
        String eventFingerprint =
                ScenarioRehearsalRetentionVerifier
                        .eventFingerprint(event);
        seal.put(
                "materialFingerprint", eventFingerprint);
        seal.put(
                "signature",
                sign(retentionPair, eventFingerprint));
        return event;
    }

    private ObjectNode workbook(
            ObjectNode plan,
            ObjectNode compiledPlanRef,
            ObjectNode bundle,
            ObjectNode retention,
            ObjectNode caseResult) {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_WORKBOOK_SEED_V1);
        value.put("seedFingerprint", "");
        value.set("scope", scope());
        value.put("runId", runId());
        value.put("requestId", "scenario-request-1");
        value.set(
                "scenarioPackRef",
                plan.path("scenarioPackRef"));
        value.set("compiledPlanRef", compiledPlanRef);
        value.set(
                "targetCapabilityRef",
                plan.path("targetCapabilityRef"));
        value.put(
                "evidenceBundleFingerprint",
                bundle.path("bundleFingerprint").asText());
        value.put(
                "resultFingerprint",
                bundle.path("result")
                        .path("resultFingerprint").asText());
        value.put(
                "evidenceKeyId", evidenceKey.keyId());
        value.set("retentionProof", retention);
        value.put("outcome", "PASS");
        value.set("summary", summary());
        ObjectNode projected =
                value.putArray("cases").addObject();
        projected.put("caseIndex", 0);
        projected.set(
                "scenarioCaseRef",
                caseResult.path("scenarioCaseRef"));
        projected.put("caseType", "GOLDEN");
        projected.set(
                "testSuiteRef",
                caseResult.path("testSuiteRef"));
        projected.put("testCaseId", "golden");
        projected.set(
                "mirrorPlanRef",
                caseResult.path("mirrorPlanRef"));
        projected.set(
                "fixtureBundleRef",
                caseResult.path("fixtureBundleRef"));
        projected.putNull("sessionCheckpointRef");
        projected.put("childRunId", "mirror-run-1");
        projected.put(
                "childEvidenceBundleFingerprint",
                fingerprint('7'));
        projected.put("evidenceStatus", "PASSED");
        projected.put("evidenceClass", "CERTIFIABLE");
        projected.put("outcome", "PASS");
        projected.put("diagnosticCode", "");
        projected.set(
                "assertionResults",
                caseResult.path("assertionResults"));
        value.put("gateReady", true);
        value.putArray("blockers");
        sealWorkbook(value);
        return value;
    }

    private static ObjectNode summary() {
        ObjectNode value = JSON.createObjectNode();
        value.put("totalCases", 1);
        value.put("passedCases", 1);
        value.put("failedCases", 0);
        value.put("indeterminateCases", 0);
        value.put("assertionResults", 1);
        value.put("blockerFailures", 0);
        value.put("blockerIndeterminate", 0);
        value.put("warningFailures", 0);
        value.put("warningIndeterminate", 0);
        return value;
    }

    private static ObjectNode signatureMaterial(
            ObjectNode attestation) {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "domain",
                "RESOURCE_GATEWAY_SCENARIO_REHEARSAL_EVIDENCE_V1");
        value.set(
                "schemaVersion",
                attestation.path("schemaVersion"));
        value.set("runId", attestation.path("runId"));
        value.set(
                "requestId",
                attestation.path("requestId"));
        value.set(
                "compiledPlanFingerprint",
                attestation.path("compiledPlanFingerprint"));
        value.set(
                "resultFingerprint",
                attestation.path("resultFingerprint"));
        value.set(
                "signedAt",
                attestation.path("signedAt"));
        return value;
    }

    private static void sealWorkbook(ObjectNode value) {
        sealFingerprint(
                value,
                "seedFingerprint",
                ScenarioRehearsalWorkbookVerifier
                        .MAXIMUM_WORKBOOK_BYTES);
    }

    private static void sealFingerprint(
            ObjectNode value,
            String field,
            int maximumBytes) {
        value.put(field, "");
        value.put(
                field,
                EvidenceVerificationSupport.sha256Bounded(
                        value, maximumBytes));
    }

    private static EvidenceVerificationKey key(
            KeyPair pair,
            String keyId,
            Instant createdAt) {
        return new EvidenceVerificationKey(
                TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1,
                keyId,
                "Ed25519",
                Base64.getEncoder().encodeToString(
                        pair.getPublic().getEncoded()),
                createdAt,
                "ACTIVE",
                "test");
    }

    private static KeyPair keyPair()
            throws Exception {
        return KeyPairGenerator
                .getInstance("Ed25519")
                .generateKeyPair();
    }

    private static String sign(
            KeyPair pair,
            String fingerprint) throws Exception {
        Signature signer =
                Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(
                fingerprint.getBytes(
                        StandardCharsets.UTF_8));
        return Base64.getEncoder()
                .encodeToString(signer.sign());
    }

    private static ObjectNode ref(
            String kind, String id, char fingerprint) {
        return ref(kind, id, fingerprint(fingerprint));
    }

    private static ObjectNode ref(
            String kind, String id, String fingerprint) {
        ObjectNode value = JSON.createObjectNode();
        value.put("kind", kind);
        value.put("id", id);
        value.put("revision", 1);
        value.put("fingerprint", fingerprint);
        return value;
    }

    private static ObjectNode scope() {
        ObjectNode value = JSON.createObjectNode();
        value.put("tenantId", "tenant-a");
        value.put("organizationId", "org-a");
        value.put("projectId", "support");
        value.put("environmentId", "test");
        value.put("region", "sg");
        return value;
    }

    private static String runId() {
        ObjectNode material = JSON.createObjectNode();
        material.put(
                "domain",
                "RESOURCE_GATEWAY_SCENARIO_REHEARSAL_RUN_ID_V1");
        material.set("scope", scope());
        material.put("requestId", "scenario-request-1");
        return "scenario-"
                + EvidenceVerificationSupport.sha256Bounded(
                material, 16 * 1024).substring(7);
    }

    private static String fingerprint(char value) {
        return "sha256:"
                + String.valueOf(value).repeat(64);
    }

    record Fixture(
            ObjectNode workbook,
            ObjectNode plan,
            ObjectNode bundle
    ) {
    }
}
