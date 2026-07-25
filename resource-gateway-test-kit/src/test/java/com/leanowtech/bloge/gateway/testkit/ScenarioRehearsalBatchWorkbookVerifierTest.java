package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioRehearsalBatchWorkbookVerifierTest {
    private static final ObjectMapper JSON =
            new ObjectMapper().findAndRegisterModules();
    private static final Instant COMPLETED =
            Instant.parse("2026-07-24T08:00:05Z");
    private ScenarioRehearsalBatchEvidenceVerifierTest
            batchFixture;
    private ScenarioRehearsalWorkbookVerifierTest
            childFixture;
    private KeyPair retentionPair;
    private KeyPair workbookPair;
    private EvidenceVerificationKey retentionKey;
    private EvidenceVerificationKey workbookKey;
    private ScenarioRehearsalBatchWorkbookVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        batchFixture =
                new ScenarioRehearsalBatchEvidenceVerifierTest();
        batchFixture.setUp();
        childFixture =
                new ScenarioRehearsalWorkbookVerifierTest();
        childFixture.setUp();
        retentionPair =
                KeyPairGenerator.getInstance("Ed25519")
                        .generateKeyPair();
        retentionKey = verificationKey(
                retentionPair,
                maximumKeyId(
                        "scenario-batch-retention-", 'r'));
        workbookPair =
                KeyPairGenerator.getInstance("Ed25519")
                        .generateKeyPair();
        workbookKey = verificationKey(
                workbookPair,
                maximumKeyId(
                        "scenario-batch-workbook-", 'w'));
        verifier =
                new ScenarioRehearsalBatchWorkbookVerifier();
    }

    @Test
    void independentlyVerifiesSignedBatchAndExactChildClosure()
            throws Exception {
        Fixture fixture = fixture(true);

        ScenarioRehearsalBatchWorkbookVerifier
                .VerificationResult result =
                verify(fixture);

        assertThat(result.verified())
                .as(result.toString())
                .isTrue();
        assertThat(retentionKey.keyId()).hasSize(255);
        assertThat(workbookKey.keyId()).hasSize(255);
        assertThat(result.gateReady()).isTrue();
        assertThat(result.blockers()).isEmpty();
    }

    @Test
    void preservesSuccessfulExecutionButBlocksExploratoryChild()
            throws Exception {
        Fixture fixture = fixture(false);

        ScenarioRehearsalBatchWorkbookVerifier
                .VerificationResult result =
                verify(fixture);

        assertThat(result.verified()).isTrue();
        assertThat(result.gateReady()).isFalse();
        assertThat(result.blockers())
                .containsExactly("CHILD_WORKBOOK_BLOCKED");
        assertThat(fixture.workbook().path("status").asText())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void rejectsProducerSelectedGateDecision()
            throws Exception {
        Fixture fixture = fixture(true);
        ObjectNode changed =
                fixture.workbook().deepCopy();
        changed.put("gateReady", false);
        changed.putArray("blockers")
                .add("CHILD_WORKBOOK_BLOCKED");
        sealAndSignBatchWorkbook(changed);

        ScenarioRehearsalBatchWorkbookVerifier
                .VerificationResult result =
                verifier.verify(
                        changed,
                        fixture.bundle(),
                        batchFixture.verificationKey(),
                        retentionKey,
                        workbookKey);

        assertThat(result.reasonCode())
                .isEqualTo(
                        "SCENARIO_BATCH_WORKBOOK_GATE_DECISION_INVALID");
    }

    @Test
    void rejectsMissingExtraAndSubstitutedChildSeeds()
            throws Exception {
        Fixture fixture = fixture(true);

        assertThat(verifier.verifyWithChildren(
                fixture.workbook(),
                fixture.bundle(),
                batchFixture.verificationKey(),
                retentionKey,
                workbookKey,
                Map.of()).reasonCode())
                .isEqualTo(
                        "SCENARIO_BATCH_WORKBOOK_CHILD_CLOSURE_INVALID");

        ObjectNode extra =
                fixture.children().values().iterator()
                        .next().deepCopy();
        String extraRun =
                "scenario-" + "9".repeat(64);
        extra.put("runId", extraRun);
        ((ObjectNode) extra.path("retentionProof"))
                .put("runId", extraRun);
        sealChildWorkbook(extra);
        Map<String, JsonNode> withExtra =
                new LinkedHashMap<>(fixture.children());
        withExtra.put(extraRun, extra);
        assertThat(verifier.verifyWithChildren(
                fixture.workbook(),
                fixture.bundle(),
                batchFixture.verificationKey(),
                retentionKey,
                workbookKey,
                withExtra).reasonCode())
                .isEqualTo(
                        "SCENARIO_BATCH_WORKBOOK_CHILD_CLOSURE_INVALID");

        ObjectNode substituted =
                fixture.children().values().iterator()
                        .next().deepCopy();
        substituted.put(
                "resultFingerprint", fingerprint('9'));
        sealChildWorkbook(substituted);
        assertThat(verifier.verifyWithChildren(
                fixture.workbook(),
                fixture.bundle(),
                batchFixture.verificationKey(),
                retentionKey,
                workbookKey,
                Map.of(
                        substituted.path("runId").asText(),
                        substituted)).reasonCode())
                .isEqualTo(
                        "SCENARIO_BATCH_WORKBOOK_CHILD_CLOSURE_INVALID");
    }

    @Test
    void rejectsBatchSignatureAndRetentionProofTampering()
            throws Exception {
        Fixture fixture = fixture(true);
        ObjectNode signatureDrift =
                fixture.bundle().deepCopy();
        ((ObjectNode) signatureDrift.path("attestation"))
                .put(
                        "signature",
                        Base64.getEncoder().encodeToString(
                                new byte[64]));
        sealBundle(signatureDrift);
        assertThat(verifier.verify(
                fixture.workbook(),
                signatureDrift,
                batchFixture.verificationKey(),
                retentionKey,
                workbookKey).reasonCode())
                .isEqualTo(
                        "SCENARIO_BATCH_WORKBOOK_EVIDENCE_"
                                + "SCENARIO_BATCH_EVIDENCE_SIGNATURE_INVALID");

        ObjectNode retentionDrift =
                fixture.workbook().deepCopy();
        ((ObjectNode) retentionDrift
                .path("retentionProof")
                .path("evidenceSeal"))
                .put(
                        "signature",
                        Base64.getEncoder().encodeToString(
                                new byte[64]));
        sealAndSignBatchWorkbook(retentionDrift);
        assertThat(verifier.verify(
                retentionDrift,
                fixture.bundle(),
                batchFixture.verificationKey(),
                retentionKey,
                workbookKey).reasonCode())
                .startsWith(
                        "SCENARIO_BATCH_WORKBOOK_RETENTION_");
    }

    @Test
    void rejectsMissingWrongAndTamperedWorkbookAttestation()
            throws Exception {
        Fixture fixture = fixture(true);

        assertThat(verifier.verify(
                fixture.workbook(),
                fixture.bundle(),
                batchFixture.verificationKey(),
                retentionKey,
                null).outcome())
                .isEqualTo(
                        ScenarioRehearsalBatchWorkbookVerifier
                                .Outcome.KEY_UNAVAILABLE);

        assertThat(verifier.verify(
                fixture.workbook(),
                fixture.bundle(),
                batchFixture.verificationKey(),
                retentionKey,
                retentionKey).outcome())
                .isEqualTo(
                        ScenarioRehearsalBatchWorkbookVerifier
                                .Outcome.POLICY_REJECTED);

        ObjectNode tampered =
                fixture.workbook().deepCopy();
        ((ObjectNode) tampered.path("workbookSeal"))
                .put(
                        "signature",
                        Base64.getEncoder().encodeToString(
                                new byte[64]));
        assertThat(verifier.verify(
                tampered,
                fixture.bundle(),
                batchFixture.verificationKey(),
                retentionKey,
                workbookKey).outcome())
                .isEqualTo(
                        ScenarioRehearsalBatchWorkbookVerifier
                                .Outcome.INVALID);
    }

    @Test
    void rejectsSigningKeyIdentifiersThatCannotBePublished()
            throws Exception {
        Fixture fixture = fixture(true);
        ObjectNode workbookKeyTooLong =
                fixture.workbook().deepCopy();
        ((ObjectNode) workbookKeyTooLong
                .path("workbookSeal"))
                .put("keyId", "w".repeat(256));
        assertThat(verifier.verify(
                workbookKeyTooLong,
                fixture.bundle(),
                batchFixture.verificationKey(),
                retentionKey,
                workbookKey).reasonCode())
                .isEqualTo(
                        "SCENARIO_BATCH_WORKBOOK_SCHEMA_INVALID");

        ObjectNode retentionKeyTooLong =
                fixture.workbook().deepCopy();
        ((ObjectNode) retentionKeyTooLong
                .path("retentionProof")
                .path("evidenceSeal"))
                .put("keyId", "r".repeat(256));
        assertThat(verifier.verify(
                retentionKeyTooLong,
                fixture.bundle(),
                batchFixture.verificationKey(),
                retentionKey,
                workbookKey).reasonCode())
                .isEqualTo(
                        "SCENARIO_BATCH_WORKBOOK_SCHEMA_INVALID");
    }

    @Test
    void clientFetchesAndVerifiesCompleteBatchClosure()
            throws Exception {
        Fixture fixture = fixture(true);
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> requests = new ArrayList<>();
        server.createContext(
                "/", exchange -> serveClosure(
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

            JsonNode actual =
                    client.findScenarioRehearsalBatchWorkbookSeed(
                            fixture.workbook()
                                    .path("jobId").asText());

            String jobId =
                    fixture.workbook().path("jobId").asText();
            assertThat(actual).isEqualTo(
                    fixture.workbook());
            assertThat(requests).containsExactly(
                    "/api/mirror/rehearsal-jobs/"
                            + jobId + "/workbook-seed",
                    "/api/mirror/rehearsal-jobs/"
                            + jobId + "/evidence",
                    "/api/integration/evidence-keys/"
                            + batchFixture
                            .verificationKey().keyId(),
                    "/api/integration/evidence-keys/"
                            + retentionKey.keyId(),
                    "/api/integration/evidence-keys/"
                            + workbookKey.keyId());
        } finally {
            server.stop(0);
        }
    }

    private Fixture fixture(
            boolean childGateReady) throws Exception {
        ScenarioRehearsalWorkbookVerifierTest.Fixture
                childSource = childFixture.fixture();
        ObjectNode child =
                childSource.workbook().deepCopy();
        ObjectNode bundle =
                batchFixture.bundle(1, true);
        JsonNode manifestEntry =
                bundle.path("index")
                        .path("manifest")
                        .path("entries").get(0);
        ObjectNode item =
                (ObjectNode) bundle.path("index")
                        .path("items").get(0);
        child.set(
                "compiledPlanRef",
                manifestEntry.path("compiledPlanRef")
                        .deepCopy());
        if (!childGateReady) {
            child.put("gateReady", false);
            child.putArray("blockers")
                    .add("CHILD_EVIDENCE_NOT_CERTIFIABLE");
        }
        sealChildWorkbook(child);
        item.put(
                "evidenceBundleFingerprint",
                child.path("evidenceBundleFingerprint")
                        .asText());
        item.put(
                "workbookSeedFingerprint",
                child.path("seedFingerprint").asText());
        batchFixture.resealIndexAndBundle(bundle);

        ObjectNode retention = signedRegistration(bundle);
        ObjectNode workbook =
                batchWorkbook(bundle, retention, child);
        return new Fixture(
                workbook,
                bundle,
                Map.of(
                        child.path("runId").asText(),
                        child));
    }

    private ObjectNode batchWorkbook(
            ObjectNode bundle,
            ObjectNode retention,
            ObjectNode child) throws Exception {
        JsonNode index = bundle.path("index");
        JsonNode job = index.path("job");
        JsonNode manifestEntry =
                index.path("manifest")
                        .path("entries").get(0);
        JsonNode item = index.path("items").get(0);
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_WORKBOOK_SEED_V1);
        value.put("seedFingerprint", "");
        value.set("scope", job.path("scope").deepCopy());
        value.set("jobId", job.path("jobId").deepCopy());
        value.set(
                "requestId", job.path("requestId").deepCopy());
        value.set(
                "requestFingerprint",
                job.path("requestFingerprint").deepCopy());
        value.set(
                "manifestFingerprint",
                job.path("manifestFingerprint").deepCopy());
        value.set(
                "terminalJobFingerprint",
                job.path("recordFingerprint").deepCopy());
        value.set(
                "evidenceBundleFingerprint",
                bundle.path("bundleFingerprint").deepCopy());
        value.set(
                "evidenceIndexFingerprint",
                index.path("indexFingerprint").deepCopy());
        value.set(
                "evidenceKeyId",
                bundle.path("attestation")
                        .path("keyId").deepCopy());
        value.set(
                "workbookSeal",
                unsignedSeal());
        value.set("retentionProof", retention);
        value.set("status", job.path("status").deepCopy());
        value.set("summary", job.path("summary").deepCopy());
        ObjectNode entry =
                value.putArray("entries").addObject();
        entry.put("entryIndex", 0);
        entry.set(
                "entryId",
                manifestEntry.path("entryId").deepCopy());
        entry.set(
                "compiledPlanRef",
                manifestEntry.path("compiledPlanRef")
                        .deepCopy());
        entry.set(
                "childRequestId",
                manifestEntry.path("aggregateRequestId")
                        .deepCopy());
        entry.set(
                "expectedRunId",
                manifestEntry.path("aggregateRunId")
                        .deepCopy());
        for (String field : List.of(
                "status", "attemptCount", "runId",
                "failureCode")) {
            entry.set(field, item.path(field).deepCopy());
        }
        entry.set(
                "childEvidenceBundleFingerprint",
                item.path("evidenceBundleFingerprint")
                        .deepCopy());
        entry.set(
                "childWorkbookSeedFingerprint",
                item.path("workbookSeedFingerprint")
                        .deepCopy());
        entry.set(
                "childWorkbook",
                childProjection(child));
        boolean gateReady =
                child.path("gateReady").asBoolean();
        value.put("gateReady", gateReady);
        if (gateReady) {
            value.putArray("blockers");
        } else {
            value.putArray("blockers")
                    .add("CHILD_WORKBOOK_BLOCKED");
        }
        sealAndSignBatchWorkbook(value);
        return value;
    }

    private ObjectNode signedRegistration(
            ObjectNode bundle) throws Exception {
        JsonNode index = bundle.path("index");
        JsonNode job = index.path("job");
        ObjectNode event = JSON.createObjectNode();
        event.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_RETENTION_EVENT_V1);
        event.put("eventId", "batch-retention-event-1");
        event.put("commandId", "batch-retention-register-1");
        event.set("scope", job.path("scope").deepCopy());
        event.set(
                "requestId", job.path("requestId").deepCopy());
        event.set("jobId", job.path("jobId").deepCopy());
        event.set(
                "manifestFingerprint",
                job.path("manifestFingerprint").deepCopy());
        event.put("revision", 1);
        event.put("type", "RETENTION_REGISTERED");
        event.put(
                "retainUntil",
                COMPLETED.plusSeconds(86400).toString());
        event.put(
                "occurredAt",
                COMPLETED.plusSeconds(2).toString());
        event.put("actorId", "scenario-batch-runtime");
        event.put(
                "reasonCode",
                "RG.MIRROR.BATCH_RETENTION_REGISTERED");
        event.put("holdId", "");
        event.set(
                "evidenceBundleFingerprint",
                bundle.path("bundleFingerprint").deepCopy());
        event.put("previousEventFingerprint", "");
        event.put("deletedJobCount", 0);
        event.put("deletedItemCount", 0);
        event.put("deletedBatchEvidenceCount", 0);
        event.put(
                "childEvidenceDisposition",
                "NOT_APPLICABLE");
        event.put("auditDisposition", "NOT_APPLICABLE");
        ObjectNode seal = event.putObject("evidenceSeal");
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
        String fingerprint =
                ScenarioRehearsalBatchRetentionVerifier
                        .eventFingerprint(event);
        seal.put("materialFingerprint", fingerprint);
        seal.put(
                "signature",
                sign(retentionPair, fingerprint));
        return event;
    }

    private static ObjectNode childProjection(
            ObjectNode child) {
        ObjectNode value = JSON.createObjectNode();
        for (String field : List.of(
                "schemaVersion", "seedFingerprint",
                "runId", "requestId", "compiledPlanRef",
                "scenarioPackRef", "targetCapabilityRef",
                "evidenceBundleFingerprint",
                "resultFingerprint", "evidenceKeyId")) {
            value.set(field, child.path(field).deepCopy());
        }
        value.put(
                "retentionProofFingerprint",
                ScenarioRehearsalRetentionVerifier
                        .eventFingerprint(
                                child.path("retentionProof")));
        for (String field : List.of(
                "outcome", "summary",
                "gateReady", "blockers")) {
            value.set(field, child.path(field).deepCopy());
        }
        return value;
    }

    private ScenarioRehearsalBatchWorkbookVerifier
            .VerificationResult verify(Fixture fixture) {
        return verifier.verify(
                fixture.workbook(),
                fixture.bundle(),
                batchFixture.verificationKey(),
                retentionKey,
                workbookKey);
    }

    private void serveClosure(
            HttpExchange exchange,
            Fixture fixture,
            List<String> requests) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        requests.add(path);
        ObjectNode response;
        if (path.endsWith("/workbook-seed")
                && path.contains("/rehearsal-jobs/")) {
            response = envelope(
                    "SCENARIO_REHEARSAL_BATCH_WORKBOOK_SEED",
                    CapabilityMirrorProtocol
                            .SCENARIO_REHEARSAL_BATCH_WORKBOOK_SEED_V1,
                    fixture.workbook());
        } else if (path.endsWith("/evidence")) {
            response = envelope(
                    "SCENARIO_REHEARSAL_BATCH_EVIDENCE_BUNDLE",
                    fixture.bundle()
                            .path("schemaVersion").asText(),
                    fixture.bundle());
        } else if (path.endsWith(
                "/" + batchFixture
                        .verificationKey().keyId())) {
            response = keyEnvelope(
                    batchFixture.verificationKey());
        } else if (path.endsWith(
                "/" + retentionKey.keyId())) {
            response = keyEnvelope(retentionKey);
        } else if (path.endsWith(
                "/" + workbookKey.keyId())) {
            response = keyEnvelope(workbookKey);
        } else if (path.endsWith("/workbook-seed")
                && path.contains("/scenarios/runs/")) {
            JsonNode child =
                    fixture.children().values()
                            .iterator().next();
            response = envelope(
                    "SCENARIO_REHEARSAL_WORKBOOK_SEED",
                    CapabilityMirrorProtocol
                            .SCENARIO_REHEARSAL_WORKBOOK_SEED_V1,
                    child);
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
                CapabilityMirrorProtocol
                        .INTEGRATION_PROTOCOL);
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

    private static EvidenceVerificationKey verificationKey(
            KeyPair pair,
            String keyId) {
        return new EvidenceVerificationKey(
                TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1,
                keyId,
                "Ed25519",
                Base64.getEncoder().encodeToString(
                        pair.getPublic().getEncoded()),
                COMPLETED.minusSeconds(60),
                "ACTIVE",
                "test");
    }

    private static String maximumKeyId(
            String prefix,
            char fill) {
        return prefix
                + String.valueOf(fill)
                .repeat(255 - prefix.length());
    }

    private static String sign(
            KeyPair pair,
            String fingerprint) throws Exception {
        Signature signer =
                Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(
                fingerprint.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder()
                .encodeToString(signer.sign());
    }

    private static void sealChildWorkbook(
            ObjectNode value) {
        value.put("seedFingerprint", "");
        value.put(
                "seedFingerprint",
                EvidenceVerificationSupport.sha256Bounded(
                        value,
                        ScenarioRehearsalBatchWorkbookVerifier
                                .MAXIMUM_WORKBOOK_BYTES));
    }

    private void sealAndSignBatchWorkbook(
            ObjectNode value) throws Exception {
        value.set("workbookSeal", unsignedSeal());
        value.put("seedFingerprint", "");
        value.put(
                "seedFingerprint",
                EvidenceVerificationSupport.sha256Bounded(
                        value,
                        ScenarioRehearsalBatchWorkbookVerifier
                                .MAXIMUM_WORKBOOK_BYTES));
        String material =
                workbookAttestationFingerprint(value);
        ObjectNode seal = JSON.createObjectNode();
        seal.put(
                "schemaVersion",
                "bloge.visualRunEvidenceSeal.v1");
        seal.put("materialFingerprint", material);
        seal.put("algorithm", "Ed25519");
        seal.put("keyId", workbookKey.keyId());
        seal.put(
                "signedAt",
                COMPLETED.plusSeconds(4).toString());
        seal.put(
                "signature",
                sign(workbookPair, material));
        value.set("workbookSeal", seal);
    }

    private static String workbookAttestationFingerprint(
            ObjectNode workbook) {
        ObjectNode material = JSON.createObjectNode();
        material.put(
                "domain",
                "RESOURCE_GATEWAY_SCENARIO_REHEARSAL_BATCH_WORKBOOK_V1");
        for (String field : List.of(
                "schemaVersion", "jobId",
                "seedFingerprint",
                "evidenceBundleFingerprint",
                "evidenceIndexFingerprint")) {
            material.set(
                    field,
                    workbook.path(field).deepCopy());
        }
        return EvidenceVerificationSupport.sha256Bounded(
                material, 16 * 1024);
    }

    private static ObjectNode unsignedSeal() {
        ObjectNode seal = JSON.createObjectNode();
        seal.put(
                "schemaVersion",
                "bloge.visualRunEvidenceSeal.v1");
        seal.put("materialFingerprint", "");
        seal.put("algorithm", "");
        seal.put("keyId", "");
        seal.put(
                "signedAt",
                "1970-01-01T00:00:00Z");
        seal.put("signature", "");
        return seal;
    }

    private static void sealBundle(
            ObjectNode bundle) {
        ObjectNode material = JSON.createObjectNode();
        material.set(
                "schemaVersion",
                bundle.path("schemaVersion"));
        material.set(
                "payloadPolicy",
                bundle.path("payloadPolicy"));
        material.set(
                "attestation",
                bundle.path("attestation"));
        material.set("index", bundle.path("index"));
        bundle.put(
                "bundleFingerprint",
                EvidenceVerificationSupport.sha256Bounded(
                        material,
                        ScenarioRehearsalBatchEvidenceVerifier
                                .MAXIMUM_BUNDLE_BYTES));
    }

    private static String fingerprint(char value) {
        return "sha256:"
                + String.valueOf(value).repeat(64);
    }

    private record Fixture(
            ObjectNode workbook,
            ObjectNode bundle,
            Map<String, JsonNode> children
    ) {
    }
}
