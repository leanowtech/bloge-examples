package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/** Independently generated and re-sealable signed lifecycle fixtures. */
final class TestSuiteStabilityObservationLedgerLifecycleTestFixtures {
    private static final String KEY_ID = "evidence-key-a";
    private static final String POLICY_FINGERPRINT = fingerprint('e');
    private static final Instant RETIRED_AT = EvidenceTrustTestFixtures.NOW.plusSeconds(120);

    private TestSuiteStabilityObservationLedgerLifecycleTestFixtures() {
    }

    static Fixture stableFixture() {
        return stableFixture(TestSuiteStabilityCrossRetentionTrendTestFixtures.stableFixture());
    }

    static Fixture stableFixture(
            TestSuiteStabilityCrossRetentionTrendTestFixtures.Fixture cross) {
        JsonNode range = cross.response().at("/evidence/range");
        TestSuiteStabilityObservationLedgerLifecycleRequest request =
                TestSuiteStabilityObservationLedgerLifecycleRequest.firstPage(
                        TestSuiteStabilityTestFixtures.SUITE_ID,
                        TestSuiteStabilityTestFixtures.SUITE_REVISION,
                        TestSuiteStabilityTestFixtures.SUITE_FINGERPRINT, 10);
        ObjectNode response = EvidenceTrustTestFixtures.JSON.createObjectNode();
        response.put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_LIFECYCLE_RESPONSE_V1);
        ObjectNode page = response.putObject("page");
        page.put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_LIFECYCLE_PAGE_V1);
        page.put("requestFingerprint", request.requestFingerprint());
        page.set("request", request.toJson().deepCopy());
        page.put("scopeFingerprint", range.path("scopeFingerprint").asText());
        ObjectNode rollout = rolloutFloor(range);
        page.set("startingFloor", rollout.deepCopy());

        ObjectNode retirement = page.putArray("retirements").addObject();
        ObjectNode evidence = retirement.putObject("evidence");
        evidence.put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_RETIREMENT_EVIDENCE_V1);
        evidence.put("scopeFingerprint", range.path("scopeFingerprint").asText());
        evidence.set("suiteRef", request.toJson().path("suiteRef").deepCopy());
        evidence.put("retirementGeneration", 1);
        evidence.set("previousFloor", rollout.deepCopy());
        evidence.set("pinnedHead", range.path("head").deepCopy());
        ObjectNode archive = evidence.putObject("archiveSegment");
        archive.put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_ARCHIVE_V1);
        archive.put("scopeFingerprint", range.path("scopeFingerprint").asText());
        archive.set("suiteRef", request.toJson().path("suiteRef").deepCopy());
        archive.put("retirementGeneration", 1);
        archive.put("previousObservationId", "");
        archive.put("previousEntryFingerprint", "");
        ArrayNode retired = archive.putArray("retiredEntries");
        retired.add(range.path("entries").path(0).deepCopy());
        archive.set("successorEntry", range.path("entries").path(1).deepCopy());
        archive.put("archivedAt", RETIRED_AT.toString());
        evidence.put("cutoffExclusive",
                range.path("entries").path(1).path("appendedAt").asText());
        evidence.put("minimumRetainedEntries", 1);
        evidence.put("maximumRetiredEntries", 1);
        evidence.put("retentionPolicyFingerprint", POLICY_FINGERPRINT);
        evidence.put("reason", "RETENTION_POLICY");
        evidence.put("retiredAt", RETIRED_AT.toString());
        ObjectNode retirementAttestation = retirement.putObject("attestation");
        retirementAttestation.put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_RETIREMENT_ATTESTATION_V1);
        retirementAttestation.put("signatureStatus", "VERIFIED");
        retirementAttestation.put("signedAt", RETIRED_AT.plusSeconds(1).toString());
        retirementAttestation.put("keyId", KEY_ID);
        retirementAttestation.put("algorithm", "Ed25519");
        retirementAttestation.put("independentlyVerifiable", true);

        ObjectNode pageAttestation = response.putObject("attestation");
        pageAttestation.put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_LIFECYCLE_ATTESTATION_V1);
        pageAttestation.put("signatureStatus", "VERIFIED");
        pageAttestation.put("signedAt", RETIRED_AT.plusSeconds(3).toString());
        pageAttestation.put("keyId", KEY_ID);
        pageAttestation.put("algorithm", "Ed25519");
        pageAttestation.put("independentlyVerifiable", true);
        page.put("hasMore", false);
        page.put("observedAt", RETIRED_AT.plusSeconds(2).toString());
        resealRetirement(response, cross.keyPair(), true);
        resealPage(response, cross.keyPair());
        return new Fixture(response, cross.key(), cross.keySet(), cross.keyPair());
    }

    static PagedFixture twoPageFixture() {
        Fixture base = stableFixture();
        ObjectNode second = base.copyResponse();
        ObjectNode secondPage = (ObjectNode) second.path("page");
        ObjectNode firstRetirement = (ObjectNode) secondPage.path("retirements").path(0);
        ObjectNode firstFloor = ((ObjectNode) secondPage.path("terminalFloor")).deepCopy();
        ObjectNode retiredEntry = ((ObjectNode) firstRetirement.at(
                "/evidence/archiveSegment/successorEntry")).deepCopy();
        ObjectNode successorEntry = continuationEntry(retiredEntry, base.keyPair());
        Instant secondRetiredAt = RETIRED_AT.plusSeconds(60);

        ObjectNode evidence = (ObjectNode) firstRetirement.path("evidence");
        evidence.put("retirementGeneration", 2);
        evidence.set("previousFloor", firstFloor.deepCopy());
        ObjectNode pinnedHead = ((ObjectNode) evidence.path("pinnedHead")).deepCopy();
        pinnedHead.put("coverageFrom", successorEntry.path("appendedAt").asText());
        pinnedHead.put("latestSequence", successorEntry.path("sequence").asLong());
        pinnedHead.put("latestObservationId",
                successorEntry.at("/observation/evidence/observationId").asText());
        pinnedHead.put("latestEntryFingerprint",
                successorEntry.path("entryFingerprint").asText());
        pinnedHead.put("updatedAt", successorEntry.path("appendedAt").asText());
        pinnedHead.put("headFingerprint",
                EvidenceVerificationSupport.sha256(without(pinnedHead, "headFingerprint")));
        evidence.set("pinnedHead", pinnedHead);
        ObjectNode archive = (ObjectNode) evidence.path("archiveSegment");
        archive.put("retirementGeneration", 2);
        archive.put("previousObservationId", firstFloor.path("previousObservationId").asText());
        archive.put("previousEntryFingerprint",
                firstFloor.path("previousEntryFingerprint").asText());
        ArrayNode retiredEntries = archive.putArray("retiredEntries");
        retiredEntries.add(retiredEntry);
        archive.set("successorEntry", successorEntry);
        archive.put("archivedAt", secondRetiredAt.toString());
        evidence.put("cutoffExclusive", successorEntry.path("appendedAt").asText());
        evidence.put("retiredAt", secondRetiredAt.toString());
        ((ObjectNode) firstRetirement.path("attestation"))
                .put("signedAt", secondRetiredAt.plusSeconds(1).toString());
        secondPage.set("startingFloor", firstFloor.deepCopy());
        secondPage.put("hasMore", false);
        secondPage.put("observedAt", secondRetiredAt.plusSeconds(2).toString());
        ((ObjectNode) second.path("attestation"))
                .put("signedAt", secondRetiredAt.plusSeconds(3).toString());
        resealRetirement(second, base.keyPair(), true);
        TestSuiteStabilityObservationLedgerLifecycleRequest secondRequest =
                new TestSuiteStabilityObservationLedgerLifecycleRequest(
                        TestSuiteStabilityTestFixtures.SUITE_ID,
                        TestSuiteStabilityTestFixtures.SUITE_REVISION,
                        TestSuiteStabilityTestFixtures.SUITE_FINGERPRINT, 1, 1,
                        secondPage.at("/currentFloor/floorFingerprint").asText(),
                        secondPage.at("/head/headFingerprint").asText());
        secondPage.set("request", secondRequest.toJson());
        secondPage.put("requestFingerprint", secondRequest.requestFingerprint());
        resealPage(second, base.keyPair());

        ObjectNode first = base.copyResponse();
        ObjectNode firstPage = (ObjectNode) first.path("page");
        TestSuiteStabilityObservationLedgerLifecycleRequest firstRequest =
                TestSuiteStabilityObservationLedgerLifecycleRequest.firstPage(
                        TestSuiteStabilityTestFixtures.SUITE_ID,
                        TestSuiteStabilityTestFixtures.SUITE_REVISION,
                        TestSuiteStabilityTestFixtures.SUITE_FINGERPRINT, 1);
        firstPage.set("request", firstRequest.toJson());
        firstPage.put("requestFingerprint", firstRequest.requestFingerprint());
        firstPage.set("currentFloor", secondPage.path("currentFloor").deepCopy());
        firstPage.set("head", secondPage.path("head").deepCopy());
        firstPage.put("hasMore", true);
        firstPage.put("observedAt", secondPage.path("observedAt").asText());
        ((ObjectNode) first.path("attestation"))
                .put("signedAt", secondRetiredAt.plusSeconds(4).toString());
        resealPage(first, base.keyPair());
        return new PagedFixture(first, second, base.key(), base.keySet(), base.keyPair());
    }

    static void resealRetirement(
            ObjectNode response,
            KeyPair keyPair,
            boolean signRetirement) {
        ObjectNode page = (ObjectNode) response.path("page");
        ObjectNode retirement = (ObjectNode) page.path("retirements").path(0);
        ObjectNode evidence = (ObjectNode) retirement.path("evidence");
        ObjectNode archive = (ObjectNode) evidence.path("archiveSegment");
        archive.put("segmentId", archiveId(archive));
        archive.put("segmentFingerprint",
                EvidenceVerificationSupport.sha256(without(archive, "segmentFingerprint")));
        evidence.put("retirementId", retirementId(evidence));
        String evidenceFingerprint = EvidenceVerificationSupport.sha256(evidence);
        retirement.put("evidenceFingerprint", evidenceFingerprint);
        ObjectNode attestation = (ObjectNode) retirement.path("attestation");
        attestation.put("retirementId", evidence.path("retirementId").asText());
        attestation.put("evidenceFingerprint", evidenceFingerprint);
        attestation.put("archiveSegmentFingerprint",
                archive.path("segmentFingerprint").asText());
        attestation.put("previousFloorFingerprint",
                evidence.at("/previousFloor/floorFingerprint").asText());
        attestation.put("pinnedHeadFingerprint",
                evidence.at("/pinnedHead/headFingerprint").asText());
        if (signRetirement) {
            attestation.put("signature", sign(retirementSignatureMaterial(attestation), keyPair));
        }
        retirement.put("attestationFingerprint",
                EvidenceVerificationSupport.sha256(attestation));
        retirement.put("retirementFingerprint",
                EvidenceVerificationSupport.sha256(
                        without(retirement, "retirementFingerprint")));
        ObjectNode successorFloor = successorFloor(retirement);
        page.set("terminalFloor", successorFloor.deepCopy());
        page.set("currentFloor", successorFloor.deepCopy());
        ObjectNode head = ((ObjectNode) evidence.path("pinnedHead")).deepCopy();
        head.put("coverageFrom", successorFloor.path("coverageFrom").asText());
        head.put("headFingerprint",
                EvidenceVerificationSupport.sha256(without(head, "headFingerprint")));
        page.set("head", head);
    }

    static void resealPage(ObjectNode response, KeyPair keyPair) {
        ObjectNode page = (ObjectNode) response.path("page");
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
        ArrayNode refs = attestation.putArray("retirementRefs");
        page.path("retirements").forEach(retirement -> {
            ObjectNode ref = refs.addObject();
            ref.put("retirementGeneration",
                    retirement.at("/evidence/retirementGeneration").asLong());
            ref.put("retirementId", retirement.at("/evidence/retirementId").asText());
            ref.put("retirementFingerprint",
                    retirement.path("retirementFingerprint").asText());
        });
        attestation.put("signature", sign(pageSignatureMaterial(attestation), keyPair));
    }

    private static ObjectNode rolloutFloor(JsonNode range) {
        JsonNode first = range.path("entries").path(0);
        ObjectNode floor = EvidenceTrustTestFixtures.JSON.createObjectNode();
        floor.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_FLOOR_V1);
        floor.put("scopeFingerprint", range.path("scopeFingerprint").asText());
        floor.set("suiteRef", range.path("suiteRef").deepCopy());
        floor.put("floorSequence", 1);
        floor.put("previousObservationId", "");
        floor.put("previousEntryFingerprint", "");
        floor.put("floorObservationId",
                first.at("/observation/evidence/observationId").asText());
        floor.put("floorEntryFingerprint", first.path("entryFingerprint").asText());
        floor.put("coverageFrom", first.path("appendedAt").asText());
        floor.put("retirementGeneration", 0);
        floor.put("latestRetirementId", "");
        floor.put("latestRetirementFingerprint", "");
        floor.put("updatedAt", first.path("appendedAt").asText());
        floor.put("floorFingerprint",
                EvidenceVerificationSupport.sha256(without(floor, "floorFingerprint")));
        return floor;
    }

    private static ObjectNode continuationEntry(ObjectNode predecessor, KeyPair keyPair) {
        ObjectNode entry = predecessor.deepCopy();
        String previousObservationId = predecessor.at(
                "/observation/evidence/observationId").asText();
        entry.put("sequence", predecessor.path("sequence").asLong() + 1);
        entry.put("previousObservationId", previousObservationId);
        entry.put("appendedAt", Instant.parse(predecessor.path("appendedAt").asText())
                .plusSeconds(1).toString());
        ObjectNode observation = (ObjectNode) entry.path("observation");
        ObjectNode evidence = (ObjectNode) observation.path("evidence");
        ObjectNode source = (ObjectNode) evidence.path("source");
        source.put("stabilityRunId", "stability-" + "4".repeat(64));
        ObjectNode identity = EvidenceTrustTestFixtures.JSON.createObjectNode();
        identity.put("schemaVersion", evidence.path("schemaVersion").asText());
        identity.put("scopeFingerprint", evidence.path("scopeFingerprint").asText());
        identity.set("suiteRef", evidence.path("suiteRef").deepCopy());
        identity.put("sourceRequestFingerprint",
                evidence.path("sourceRequestFingerprint").asText());
        identity.put("stabilityRunId", source.path("stabilityRunId").asText());
        identity.put("sourceEvidenceFingerprint", source.path("evidenceFingerprint").asText());
        identity.put("sourceAttestationFingerprint",
                source.path("attestationFingerprint").asText());
        evidence.put("observationId", "stability-observation-"
                + EvidenceVerificationSupport.sha256(identity).substring("sha256:".length()));
        String evidenceFingerprint = EvidenceVerificationSupport.sha256(evidence);
        observation.put("evidenceFingerprint", evidenceFingerprint);
        ObjectNode attestation = (ObjectNode) observation.path("attestation");
        attestation.put("observationId", evidence.path("observationId").asText());
        attestation.put("observationFingerprint", evidenceFingerprint);
        attestation.put("signedAt", RETIRED_AT.plusSeconds(30).toString());
        attestation.put("signature", sign(observationSignatureMaterial(attestation), keyPair));
        observation.put("attestationFingerprint",
                EvidenceVerificationSupport.sha256(attestation));
        entry.put("entryFingerprint",
                EvidenceVerificationSupport.sha256(without(entry, "entryFingerprint")));
        return entry;
    }

    private static String archiveId(JsonNode archive) {
        ObjectNode identity = EvidenceTrustTestFixtures.JSON.createObjectNode();
        identity.put("schemaVersion", archive.path("schemaVersion").asText());
        identity.put("scopeFingerprint", archive.path("scopeFingerprint").asText());
        identity.set("suiteRef", archive.path("suiteRef").deepCopy());
        identity.put("retirementGeneration", archive.path("retirementGeneration").asLong());
        identity.put("previousObservationId", archive.path("previousObservationId").asText());
        identity.put("previousEntryFingerprint",
                archive.path("previousEntryFingerprint").asText());
        ArrayNode refs = identity.putArray("retiredEntries");
        archive.path("retiredEntries").forEach(value -> refs.add(entryRef(value)));
        identity.set("successorEntry", entryRef(archive.path("successorEntry")));
        identity.put("archivedAt", archive.path("archivedAt").asText());
        return "stability-observation-archive-"
                + EvidenceVerificationSupport.sha256(identity).substring("sha256:".length());
    }

    private static ObjectNode entryRef(JsonNode entry) {
        ObjectNode ref = EvidenceTrustTestFixtures.JSON.createObjectNode();
        ref.put("sequence", entry.path("sequence").asLong());
        ref.put("observationId",
                entry.at("/observation/evidence/observationId").asText());
        ref.put("entryFingerprint", entry.path("entryFingerprint").asText());
        return ref;
    }

    private static String retirementId(JsonNode evidence) {
        ObjectNode identity = ((ObjectNode) evidence).deepCopy();
        identity.remove("retirementId");
        return "stability-observation-retirement-"
                + EvidenceVerificationSupport.sha256(identity).substring("sha256:".length());
    }

    private static ObjectNode successorFloor(JsonNode retirement) {
        JsonNode evidence = retirement.path("evidence");
        JsonNode archive = evidence.path("archiveSegment");
        JsonNode retiredLast = archive.path("retiredEntries")
                .path(archive.path("retiredEntries").size() - 1);
        JsonNode successor = archive.path("successorEntry");
        ObjectNode floor = EvidenceTrustTestFixtures.JSON.createObjectNode();
        floor.put("schemaVersion", TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_FLOOR_V1);
        floor.put("scopeFingerprint", evidence.path("scopeFingerprint").asText());
        floor.set("suiteRef", evidence.path("suiteRef").deepCopy());
        floor.put("floorSequence", successor.path("sequence").asLong());
        floor.put("previousObservationId",
                retiredLast.at("/observation/evidence/observationId").asText());
        floor.put("previousEntryFingerprint", retiredLast.path("entryFingerprint").asText());
        floor.put("floorObservationId",
                successor.at("/observation/evidence/observationId").asText());
        floor.put("floorEntryFingerprint", successor.path("entryFingerprint").asText());
        floor.put("coverageFrom", successor.path("appendedAt").asText());
        floor.put("retirementGeneration", evidence.path("retirementGeneration").asLong());
        floor.put("latestRetirementId", evidence.path("retirementId").asText());
        floor.put("latestRetirementFingerprint",
                retirement.path("retirementFingerprint").asText());
        floor.put("updatedAt", evidence.path("retiredAt").asText());
        floor.put("floorFingerprint",
                EvidenceVerificationSupport.sha256(without(floor, "floorFingerprint")));
        return floor;
    }

    private static JsonNode retirementSignatureMaterial(JsonNode attestation) {
        ObjectNode material = EvidenceTrustTestFixtures.JSON.createObjectNode();
        List.of("schemaVersion", "retirementId", "evidenceFingerprint",
                "archiveSegmentFingerprint", "previousFloorFingerprint",
                "pinnedHeadFingerprint", "signedAt")
                .forEach(field -> material.set(field, attestation.path(field).deepCopy()));
        return material;
    }

    private static JsonNode observationSignatureMaterial(JsonNode attestation) {
        ObjectNode material = EvidenceTrustTestFixtures.JSON.createObjectNode();
        List.of("schemaVersion", "observationId", "observationFingerprint",
                "sourceEvidenceFingerprint", "sourceAttestationFingerprint", "signedAt")
                .forEach(field -> material.set(field, attestation.path(field).deepCopy()));
        return material;
    }

    private static JsonNode pageSignatureMaterial(JsonNode attestation) {
        ObjectNode material = EvidenceTrustTestFixtures.JSON.createObjectNode();
        List.of("schemaVersion", "lifecyclePageId", "requestFingerprint", "pageFingerprint",
                "scopeFingerprint", "startingFloorFingerprint", "terminalFloorFingerprint",
                "currentFloorFingerprint", "headFingerprint", "retirementRefs", "signedAt")
                .forEach(field -> material.set(field, attestation.path(field).deepCopy()));
        return material;
    }

    private static JsonNode without(JsonNode value, String field) {
        ObjectNode copy = ((ObjectNode) value).deepCopy();
        copy.remove(field);
        return copy;
    }

    private static String sign(JsonNode material, KeyPair keyPair) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keyPair.getPrivate());
            signer.update(EvidenceVerificationSupport.sha256(material)
                    .getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    record Fixture(
            ObjectNode response,
            EvidenceVerificationKey key,
            EvidenceVerificationKeySet keySet,
            KeyPair keyPair
    ) {
        TestSuiteStabilityObservationLedgerLifecyclePage page() {
            return TestSuiteStabilityObservationLedgerLifecyclePage.from(response);
        }

        ObjectNode copyResponse() {
            return response.deepCopy();
        }
    }

    record PagedFixture(
            ObjectNode firstResponse,
            ObjectNode secondResponse,
            EvidenceVerificationKey key,
            EvidenceVerificationKeySet keySet,
            KeyPair keyPair
    ) {
        TestSuiteStabilityObservationLedgerLifecyclePage firstPage() {
            return TestSuiteStabilityObservationLedgerLifecyclePage.from(firstResponse);
        }

        TestSuiteStabilityObservationLedgerLifecyclePage secondPage() {
            return TestSuiteStabilityObservationLedgerLifecyclePage.from(secondResponse);
        }
    }
}
