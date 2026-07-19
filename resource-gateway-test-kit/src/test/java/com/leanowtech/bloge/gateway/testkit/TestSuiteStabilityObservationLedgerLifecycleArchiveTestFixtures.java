package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Independently generated receipt-aware lifecycle fixtures with separate signing domains. */
final class TestSuiteStabilityObservationLedgerLifecycleArchiveTestFixtures {
    private static final String ARCHIVE_KEY_ID = "archive-key-a";
    private static final String TRUST_DOMAIN = "archive.example";
    private static final String ARCHIVE_SET_ID = "archive-set-a";

    private TestSuiteStabilityObservationLedgerLifecycleArchiveTestFixtures() {
    }

    static Fixture stableFixture() {
        var base = TestSuiteStabilityObservationLedgerLifecycleTestFixtures.stableFixture();
        return stableFixture(base);
    }

    static Fixture stableFixture(
            TestSuiteStabilityObservationLedgerLifecycleTestFixtures.Fixture base) {
        KeyPair archiveKeyPair = keyPair();
        ObjectNode response = toArchivePage(
                base.copyResponse(), base.keyPair(), archiveKeyPair);
        return fixture(response, base.key(), base.keySet(), base.keyPair(), archiveKeyPair);
    }

    static PagedFixture twoPageFixture() {
        var base = TestSuiteStabilityObservationLedgerLifecycleTestFixtures.twoPageFixture();
        KeyPair archiveKeyPair = keyPair();
        ObjectNode first = toArchivePage(
                base.firstResponse().deepCopy(), base.keyPair(), archiveKeyPair);
        ObjectNode second = toArchivePage(
                base.secondResponse().deepCopy(), base.keyPair(), archiveKeyPair);
        Fixture trust = fixture(first, base.key(), base.keySet(), base.keyPair(), archiveKeyPair);
        return new PagedFixture(first, second, base.key(), base.keySet(), base.keyPair(),
                trust.archiveKey(), archiveKeyPair, trust.archivePolicy());
    }

    static void resealGatewayMaterial(ObjectNode response, KeyPair lifecycleKeyPair) {
        ObjectNode page = (ObjectNode) response.path("page");
        page.path("externalArchiveReceiptSets").forEach(value -> {
            ObjectNode set = (ObjectNode) value;
            set.put("receiptSetId", receiptSetId(set));
            set.put("receiptSetFingerprint",
                    EvidenceVerificationSupport.sha256(
                            without(set, "receiptSetFingerprint")));
        });
        page.put("pageFingerprint",
                EvidenceVerificationSupport.sha256(without(page, "pageFingerprint")));
        ObjectNode identity = EvidenceTrustTestFixtures.JSON.createObjectNode();
        identity.put("schemaVersion", page.path("schemaVersion").asText());
        identity.put("requestFingerprint", page.path("requestFingerprint").asText());
        identity.put("pageFingerprint", page.path("pageFingerprint").asText());
        String pageId = "stability-observation-lifecycle-page-"
                + EvidenceVerificationSupport.sha256(identity).substring("sha256:".length());
        response.put("lifecyclePageId", pageId);
        response.put("pageFingerprint", page.path("pageFingerprint").asText());
        ObjectNode attestation = (ObjectNode) response.path("attestation");
        attestation.put("lifecyclePageId", pageId);
        attestation.put("requestFingerprint", page.path("requestFingerprint").asText());
        attestation.put("pageFingerprint", page.path("pageFingerprint").asText());
        attestation.put("scopeFingerprint", page.path("scopeFingerprint").asText());
        attestation.put("startingFloorFingerprint",
                page.at("/startingFloor/floorFingerprint").asText());
        attestation.put("terminalFloorFingerprint",
                page.at("/terminalFloor/floorFingerprint").asText());
        attestation.put("currentFloorFingerprint",
                page.at("/currentFloor/floorFingerprint").asText());
        attestation.put("headFingerprint", page.at("/head/headFingerprint").asText());
        ArrayNode refs = attestation.putArray("archiveRefs");
        for (int index = 0; index < page.path("retirements").size(); index++) {
            JsonNode retirement = page.path("retirements").path(index);
            JsonNode set = page.path("externalArchiveReceiptSets").path(index);
            ObjectNode ref = refs.addObject();
            ref.put("retirementGeneration",
                    retirement.at("/evidence/retirementGeneration").asLong());
            ref.put("retirementId", retirement.at("/evidence/retirementId").asText());
            ref.put("retirementFingerprint",
                    retirement.path("retirementFingerprint").asText());
            ref.put("receiptSetId", set.path("receiptSetId").asText());
            ref.put("receiptSetFingerprint", set.path("receiptSetFingerprint").asText());
            ref.put("requiredCopies", set.path("requiredCopies").asInt());
            ref.put("receiptCount", set.path("receipts").size());
        }
        attestation.put("signature", sign(
                pageSignatureMaterial(attestation), lifecycleKeyPair));
    }

    static String receiptFingerprint(ObjectNode receipt) {
        return EvidenceVerificationSupport.sha256(
                without(receipt, "receiptFingerprint", "signature"));
    }

    private static Fixture fixture(
            ObjectNode response,
            EvidenceVerificationKey lifecycleKey,
            EvidenceVerificationKeySet lifecycleKeySet,
            KeyPair lifecycleKeyPair,
            KeyPair archiveKeyPair) {
        Instant createdAt = Instant.parse(response.at(
                "/page/externalArchiveReceiptSets/0/request/requestedAt").asText())
                .minusSeconds(600);
        EvidenceVerificationKey archiveKey = new EvidenceVerificationKey(
                TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1, ARCHIVE_KEY_ID, "Ed25519",
                Base64.getEncoder().encodeToString(archiveKeyPair.getPublic().getEncoded()),
                createdAt, "ACTIVE", "test-archive-authority");
        String retentionPolicy = response.at(
                "/page/externalArchiveReceiptSets/0/request/retirement/evidence/"
                        + "retentionPolicyFingerprint").asText();
        Instant requiredUntil = Instant.parse(response.at(
                "/page/externalArchiveReceiptSets/0/request/retainUntil").asText())
                .minusSeconds(1);
        var authority = new TestSuiteStabilityObservationExternalArchiveTrustPolicy
                .TrustedAuthority("archive-a", "region-a",
                Map.of(archiveKey.keyId(), archiveKey));
        var policy = new TestSuiteStabilityObservationExternalArchiveTrustPolicy(
                TestSuiteStabilityObservationExternalArchiveTrustPolicy.SCHEMA_VERSION,
                TRUST_DOMAIN, ARCHIVE_SET_ID, Set.of(retentionPolicy), 1, requiredUntil,
                Map.of(authority.authorityId(), authority));
        return new Fixture(response, lifecycleKey, lifecycleKeySet, lifecycleKeyPair,
                archiveKey, archiveKeyPair, policy);
    }

    private static ObjectNode toArchivePage(
            ObjectNode response,
            KeyPair lifecycleKeyPair,
            KeyPair archiveKeyPair) {
        response.put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_LIFECYCLE_RESPONSE_V2);
        ObjectNode page = (ObjectNode) response.path("page");
        page.put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_LIFECYCLE_PAGE_V2);
        ArrayNode sets = page.putArray("externalArchiveReceiptSets");
        page.path("retirements").forEach(retirement ->
                sets.add(receiptSet(retirement, archiveKeyPair)));
        ObjectNode attestation = (ObjectNode) response.path("attestation");
        attestation.put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_LIFECYCLE_ATTESTATION_V2);
        attestation.remove("retirementRefs");
        resealGatewayMaterial(response, lifecycleKeyPair);
        return response;
    }

    private static ObjectNode receiptSet(JsonNode retirement, KeyPair archiveKeyPair) {
        Instant retiredAt = Instant.parse(retirement.at("/evidence/retiredAt").asText());
        Instant requestedAt = retiredAt.plusSeconds(1);
        Instant issuedAt = retiredAt.plusSeconds(1);
        Instant confirmedAt = retiredAt.plusSeconds(2);
        Instant expiresAt = retiredAt.plusSeconds(31);
        Instant retainUntil = retiredAt.plusSeconds(30L * 24 * 60 * 60);
        ObjectNode request = EvidenceTrustTestFixtures.JSON.createObjectNode();
        request.put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_EXTERNAL_ARCHIVE_REQUEST_V1);
        request.put("trustDomain", TRUST_DOMAIN);
        request.put("archiveSetId", ARCHIVE_SET_ID);
        request.set("retirement", retirement.deepCopy());
        request.put("retainUntil", retainUntil.toString());
        request.put("challenge", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[32]));
        request.put("requestedAt", requestedAt.toString());
        request.put("expiresAt", expiresAt.toString());
        request.put("requestFingerprint", EvidenceVerificationSupport.sha256(request));

        JsonNode evidence = retirement.path("evidence");
        JsonNode archive = evidence.path("archiveSegment");
        ObjectNode objectIdentity = EvidenceTrustTestFixtures.JSON.createObjectNode();
        objectIdentity.put("retirementId", evidence.path("retirementId").asText());
        objectIdentity.put("retirementFingerprint",
                retirement.path("retirementFingerprint").asText());
        objectIdentity.put("segmentId", archive.path("segmentId").asText());
        objectIdentity.put("segmentFingerprint", archive.path("segmentFingerprint").asText());
        objectIdentity.put("retentionPolicyFingerprint",
                evidence.path("retentionPolicyFingerprint").asText());
        String objectId = "stability-observation-worm-"
                + EvidenceVerificationSupport.sha256(objectIdentity)
                .substring("sha256:".length());

        ObjectNode receipt = EvidenceTrustTestFixtures.JSON.createObjectNode();
        receipt.put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_EXTERNAL_ARCHIVE_RECEIPT_V1);
        receipt.put("requestFingerprint", request.path("requestFingerprint").asText());
        receipt.put("trustDomain", TRUST_DOMAIN);
        receipt.put("archiveSetId", ARCHIVE_SET_ID);
        receipt.put("authorityId", "archive-a");
        receipt.put("failureDomain", "region-a");
        receipt.put("keyId", ARCHIVE_KEY_ID);
        receipt.put("objectId", objectId);
        receipt.put("retirementId", evidence.path("retirementId").asText());
        receipt.put("retirementFingerprint",
                retirement.path("retirementFingerprint").asText());
        receipt.put("segmentId", archive.path("segmentId").asText());
        receipt.put("segmentFingerprint", archive.path("segmentFingerprint").asText());
        receipt.put("retentionPolicyFingerprint",
                evidence.path("retentionPolicyFingerprint").asText());
        receipt.put("retainUntil", retainUntil.toString());
        receipt.put("storedAt", requestedAt.toString());
        receipt.put("issuedAt", issuedAt.toString());
        receipt.put("expiresAt", expiresAt.toString());
        receipt.put("retentionMode", "COMPLIANCE");
        receipt.put("externallyDurable", true);
        receipt.put("writeOnce", true);
        receipt.put("deleteBeforeRetentionDenied", true);
        receipt.put("algorithm", "Ed25519");
        String receiptFingerprint = EvidenceVerificationSupport.sha256(receipt);
        receipt.put("receiptFingerprint", receiptFingerprint);
        receipt.put("signature", signFingerprint(receiptFingerprint, archiveKeyPair));

        ObjectNode set = EvidenceTrustTestFixtures.JSON.createObjectNode();
        set.put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_EXTERNAL_ARCHIVE_RECEIPT_SET_V1);
        set.set("request", request);
        set.put("requiredCopies", 1);
        set.putArray("receipts").add(receipt);
        set.put("confirmedAt", confirmedAt.toString());
        set.put("receiptSetId", receiptSetId(set));
        set.put("receiptSetFingerprint", EvidenceVerificationSupport.sha256(set));
        return set;
    }

    private static String receiptSetId(JsonNode set) {
        ObjectNode identity = EvidenceTrustTestFixtures.JSON.createObjectNode();
        identity.put("schemaVersion", set.path("schemaVersion").asText());
        identity.put("requestFingerprint",
                set.at("/request/requestFingerprint").asText());
        identity.put("requiredCopies", set.path("requiredCopies").asInt());
        ArrayNode refs = identity.putArray("receipts");
        set.path("receipts").forEach(receipt -> {
            ObjectNode ref = refs.addObject();
            ref.put("authorityId", receipt.path("authorityId").asText());
            ref.put("failureDomain", receipt.path("failureDomain").asText());
            ref.put("receiptFingerprint", receipt.path("receiptFingerprint").asText());
        });
        return "stability-observation-external-archive-receipts-"
                + EvidenceVerificationSupport.sha256(identity).substring("sha256:".length());
    }

    private static JsonNode pageSignatureMaterial(JsonNode attestation) {
        ObjectNode material = EvidenceTrustTestFixtures.JSON.createObjectNode();
        List.of("schemaVersion", "lifecyclePageId", "requestFingerprint", "pageFingerprint",
                "scopeFingerprint", "startingFloorFingerprint", "terminalFloorFingerprint",
                "currentFloorFingerprint", "headFingerprint", "archiveRefs", "signedAt")
                .forEach(field -> material.set(field, attestation.path(field).deepCopy()));
        return material;
    }

    private static ObjectNode without(JsonNode value, String... fields) {
        ObjectNode copy = ((ObjectNode) value).deepCopy();
        for (String field : fields) {
            copy.remove(field);
        }
        return copy;
    }

    private static String sign(JsonNode material, KeyPair keyPair) {
        return signFingerprint(EvidenceVerificationSupport.sha256(material), keyPair);
    }

    private static String signFingerprint(String fingerprint, KeyPair keyPair) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keyPair.getPrivate());
            signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static KeyPair keyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    record Fixture(
            ObjectNode response,
            EvidenceVerificationKey lifecycleKey,
            EvidenceVerificationKeySet lifecycleKeySet,
            KeyPair lifecycleKeyPair,
            EvidenceVerificationKey archiveKey,
            KeyPair archiveKeyPair,
            TestSuiteStabilityObservationExternalArchiveTrustPolicy archivePolicy
    ) {
        TestSuiteStabilityObservationLedgerLifecycleArchivePage page() {
            return TestSuiteStabilityObservationLedgerLifecycleArchivePage.from(response);
        }

        ObjectNode copyResponse() {
            return response.deepCopy();
        }
    }

    record PagedFixture(
            ObjectNode firstResponse,
            ObjectNode secondResponse,
            EvidenceVerificationKey lifecycleKey,
            EvidenceVerificationKeySet lifecycleKeySet,
            KeyPair lifecycleKeyPair,
            EvidenceVerificationKey archiveKey,
            KeyPair archiveKeyPair,
            TestSuiteStabilityObservationExternalArchiveTrustPolicy archivePolicy
    ) {
        TestSuiteStabilityObservationLedgerLifecycleArchivePage firstPage() {
            return TestSuiteStabilityObservationLedgerLifecycleArchivePage.from(firstResponse);
        }

        TestSuiteStabilityObservationLedgerLifecycleArchivePage secondPage() {
            return TestSuiteStabilityObservationLedgerLifecycleArchivePage.from(secondResponse);
        }
    }
}
