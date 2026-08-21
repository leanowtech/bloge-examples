package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioFormalEvidenceRunManifestCompilerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void compilesCanonicalManifestAndReturnsDefensiveCopies() throws Exception {
        ObjectNode manifest = validManifest();
        byte[] exact = CapabilityStudioFormalEvidenceRunManifest.canonicalBytes(manifest);

        var compiled = CapabilityStudioFormalEvidenceRunManifest.compile(
                exact);

        assertThat(compiled.inventory()).isEmpty();
        assertThat(compiled.obligations()).hasSize(14)
                .allMatch(obligation -> "NOT_RUN".equals(obligation.status()));
        assertThat(compiled.obligationCounts()).isEqualTo(
                new CapabilityStudioFormalEvidenceRunManifest.ObligationCounts(0, 0, 14));
        assertThat(compiled.evidenceByteSize()).isZero();
        assertThat(compiled.verificationLevel()).isEqualTo("INCOMPLETE");
        assertThat(compiled.manifestFingerprint())
                .isEqualTo(manifest.path("manifestFingerprint").textValue());
        assertThat(compiled.rawManifestFingerprint())
                .isEqualTo(CapabilityStudioFormalEvidenceRunManifest.sha256(exact));

        byte[] returned = compiled.exactBytes();
        returned[0] ^= 1;
        assertThat(compiled.exactBytes()).isEqualTo(exact);
    }

    @Test
    void rejectsDuplicateKeysAndTrailingJson() throws Exception {
        byte[] duplicate = "{\"contractId\":\"one\",\"contractId\":\"two\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertInvalid(duplicate);

        byte[] trailing = append(
                CapabilityStudioFormalEvidenceRunManifest.canonicalBytes(validManifest()), " {}");
        assertInvalid(trailing);
    }

    @Test
    void rejectsNonCanonicalEncodingBeforeSemanticChecks() throws Exception {
        byte[] nonCanonical = JSON.writeValueAsBytes(validManifest());

        assertInvalid(nonCanonical);
    }

    @Test
    void rejectsObligationOrderAndCountDrift() throws Exception {
        ObjectNode wrongOrder = validManifest();
        ArrayNode obligations = wrongOrder.withArray("obligations");
        var first = obligations.get(0);
        obligations.set(0, obligations.get(1));
        obligations.set(1, first);
        assertInvalid(canonical(wrongOrder));

        ObjectNode wrongCount = validManifest();
        wrongCount.put("failed", 1);
        assertInvalid(canonical(wrongCount));

        ObjectNode missingSlot = validManifest();
        missingSlot.withArray("obligations").remove(13);
        assertInvalid(canonical(missingSlot));
    }

    @Test
    void rejectsInventoryOrderAndCountDrift() throws Exception {
        ObjectNode wrongOrder = validManifest();
        ArrayNode inventory = wrongOrder.withArray("evidenceInventory");
        inventory.addObject().put("relativePath", "z.json").put("byteSize", 2)
                .put("rawFingerprint", CapabilityStudioFormalEvidenceRunManifest.sha256(
                        new byte[]{'z', '!'}));
        inventory.addObject().put("relativePath", "a.json").put("byteSize", 1)
                .put("rawFingerprint", CapabilityStudioFormalEvidenceRunManifest.sha256(
                        new byte[]{'a'}));
        refresh(wrongOrder);
        assertInvalid(canonical(wrongOrder));

        ObjectNode wrongCount = validManifest();
        wrongCount.put("evidenceCount", 1);
        assertInvalid(CapabilityStudioFormalEvidenceRunManifest.canonicalBytes(wrongCount));
    }

    @Test
    void rejectsObligationEvidenceReferenceOutsideInventory() throws Exception {
        ObjectNode manifest = validManifest();
        ObjectNode obligation = (ObjectNode) manifest.withArray("obligations").get(0);
        obligation.put("status", "FAIL").withArray("evidencePaths").add("missing.json");
        manifest.put("failed", 1).put("notRun", 13);

        assertInvalid(canonical(manifest));
    }

    @Test
    void rejectsInventoryAndManifestFingerprintDrift() throws Exception {
        ObjectNode inventoryDrift = validManifest();
        inventoryDrift.put("inventoryClosureFingerprint", CapabilityStudioFormalEvidenceRunManifest.sha256(
                new byte[]{'x'}));
        assertInvalid(CapabilityStudioFormalEvidenceRunManifest.canonicalBytes(inventoryDrift));

        ObjectNode manifestDrift = validManifest();
        manifestDrift.put("manifestFingerprint", CapabilityStudioFormalEvidenceRunManifest.sha256(
                new byte[]{'x'}));
        assertInvalid(CapabilityStudioFormalEvidenceRunManifest.canonicalBytes(manifestDrift));
    }

    @Test
    void rejectsClosedReplayReferenceMismatchWithoutReadingEvidenceRoot() throws Exception {
        ObjectNode manifest = validManifest();
        manifest.withArray("typedEvidenceReplays").addObject()
                .put("id", "stage-replay")
                .put("role", "STAGE_ACCEPTANCE_RESULT")
                .put("kind", "STAGE_ACCEPTANCE_RESULT_V2")
                .put("verifierId", "CapabilityStudioStageAcceptanceResultV2Verifier.verify")
                .put("verifierRevision", 1)
                .put("subjectPath", "stage-result.json")
                .set("inputs", JSON.createObjectNode().put(
                        "verificationInstant", "2026-01-01T00:08:00Z"));

        assertInvalid(canonical(manifest));
    }

    @Test
    void rejectsReplayPlanExpansionDuplicateIdsDuplicateSlotsAndUnsortedIds() throws Exception {
        ObjectNode duplicateSlot = validManifest();
        addInventory(duplicateSlot, "a.json", new byte[]{'a'});
        addInventory(duplicateSlot, "b.json", new byte[]{'b'});
        addStageReplay(duplicateSlot, "a", "a.json");
        addStageReplay(duplicateSlot, "b", "b.json");
        assertInvalid(canonical(duplicateSlot));

        ObjectNode duplicateId = validManifest();
        addInventory(duplicateId, "a.json", new byte[]{'a'});
        addInventory(duplicateId, "b.json", new byte[]{'b'});
        addStageReplay(duplicateId, "same", "a.json");
        addDurableReplay(duplicateId, "same", "b.json");
        assertInvalid(canonical(duplicateId));

        ObjectNode unsorted = validManifest();
        addInventory(unsorted, "a.json", new byte[]{'a'});
        addInventory(unsorted, "b.json", new byte[]{'b'});
        addStageReplay(unsorted, "b", "b.json");
        addDurableReplay(unsorted, "a", "a.json");
        assertInvalid(canonical(unsorted));

        ObjectNode expanded = validManifest();
        for (int index = 0; index < 4; index++) {
            String path = "replay-" + index + ".json";
            addInventory(expanded, path, new byte[]{(byte) index});
            addStageReplay(expanded, "replay-" + index, path);
        }
        assertInvalid(canonical(expanded));
    }

    @Test
    void rejectsDirectorySubjectThatIsOnlyAFileAndRootLevelDurableWrapper() throws Exception {
        ObjectNode fileMasqueradingAsTree = validManifest();
        addInventory(fileMasqueradingAsTree, "tree-subject", new byte[]{'a'});
        addFormalInputReplay(fileMasqueradingAsTree, "tree", "tree-subject");
        assertInvalid(canonical(fileMasqueradingAsTree));

        ObjectNode rootDurableWrapper = validManifest();
        addInventory(rootDurableWrapper, "durable.json", new byte[]{'a'});
        addDurableReplay(rootDurableWrapper, "durable", "durable.json");
        assertInvalid(canonical(rootDurableWrapper));
    }

    private static ObjectNode validManifest() throws Exception {
        ObjectNode manifest = JSON.createObjectNode()
                .put("contractId", CapabilityStudioFormalEvidenceRunManifest.CONTRACT_ID)
                .put("runId", fp('0'))
                .put("candidatePinFingerprint", fp('1'))
                .put("inputPinFingerprint", fp('2'))
                .put("environmentPinFingerprint", fp('3'));
        manifest.putObject("executionWindow")
                .put("startedAt", "2026-01-01T00:00:00Z")
                .put("endedAt", "2026-01-01T00:01:00Z");
        manifest.putObject("independentReview")
                .put("reviewerFingerprint", fp('4'))
                .put("reviewedAt", "2026-01-01T00:02:00Z")
                .put("reviewFingerprint", fp('5'));
        ArrayNode obligations = manifest.putArray("obligations");
        for (String id : CapabilityStudioFormalEvidenceRunManifest.OBLIGATION_IDS) {
            obligations.addObject().put("id", id).put("status", "NOT_RUN")
                    .putArray("evidencePaths");
        }
        manifest.put("openP0", 1).put("openP1", 1).put("passed", 0)
                .put("failed", 0).put("blocked", 0).put("notRun", 14)
                .put("verificationLevel", "INCOMPLETE")
                .put("formalPassCount", 0).put("formalExpectedCount", 27)
                .put("evidenceCount", 0).put("evidenceByteSize", 0)
                .putArray("evidenceInventory");
        manifest.put("inventoryClosureFingerprint", fp('6'))
                .putArray("typedEvidenceReplays");
        manifest.putNull("manifestFingerprint");
        refresh(manifest);
        return manifest;
    }

    private static void refresh(ObjectNode manifest) throws Exception {
        ArrayNode inventory = manifest.withArray("evidenceInventory");
        long byteSize = 0;
        for (var entry : inventory) {
            byteSize += entry.path("byteSize").longValue();
        }
        manifest.put("evidenceCount", inventory.size())
                .put("evidenceByteSize", byteSize)
                .put("inventoryClosureFingerprint",
                        CapabilityStudioFormalEvidenceRunManifest.canonicalFingerprint(inventory))
                .putNull("manifestFingerprint")
                .put("manifestFingerprint",
                        CapabilityStudioFormalEvidenceRunManifest.canonicalFingerprint(manifest));
    }

    private static void addInventory(ObjectNode manifest, String path, byte[] bytes) {
        manifest.withArray("evidenceInventory").addObject()
                .put("relativePath", path)
                .put("byteSize", bytes.length)
                .put("rawFingerprint", CapabilityStudioFormalEvidenceRunManifest.sha256(bytes));
    }

    private static void addStageReplay(ObjectNode manifest, String id, String subject) {
        manifest.withArray("typedEvidenceReplays").addObject()
                .put("id", id)
                .put("role", "STAGE_ACCEPTANCE_RESULT")
                .put("kind", "STAGE_ACCEPTANCE_RESULT_V2")
                .put("verifierId", "CapabilityStudioStageAcceptanceResultV2Verifier.verify")
                .put("verifierRevision", 2)
                .put("subjectPath", subject)
                .set("inputs", JSON.createObjectNode().put(
                        "verificationInstant", "2026-01-01T00:08:00Z"));
    }

    private static void addDurableReplay(ObjectNode manifest, String id, String subject) {
        manifest.withArray("typedEvidenceReplays").addObject()
                .put("id", id)
                .put("role", "DURABLE_EVIDENCE_CLOSURE")
                .put("kind", "EXECUTION_LEASE_DURABLE_WRAPPER_V1")
                .put("verifierId", "CapabilityStudioExecutionLeaseEvidenceBundleVerifier.verify")
                .put("verifierRevision", 1)
                .put("subjectPath", subject)
                .set("inputs", JSON.createObjectNode()
                        .put("stageResultRawFingerprint", fp('7'))
                        .put("formalOuterFingerprint", fp('8'))
                        .put("publicationFingerprint", fp('9')));
    }

    private static void addFormalInputReplay(ObjectNode manifest, String id, String subject) {
        manifest.withArray("typedEvidenceReplays").addObject()
                .put("id", id)
                .put("role", "FORMAL_INPUT_TREE")
                .put("kind", "FORMAL_INPUT_TREE_V1")
                .put("verifierId", "CapabilityStudioFormalInputTreeSnapshotter.verify")
                .put("verifierRevision", 1)
                .put("subjectPath", subject)
                .set("inputs", JSON.createObjectNode()
                        .put("treeKind", "AUTHORITY_BUNDLE")
                        .put("bundleSemanticFingerprint", fp('6'))
                        .put("treeFingerprint", fp('7'))
                        .put("publicationFingerprint", fp('8'))
                        .put("transactionId", fp('9')));
    }

    private static byte[] canonical(ObjectNode manifest) throws Exception {
        refresh(manifest);
        return CapabilityStudioFormalEvidenceRunManifest.canonicalBytes(manifest);
    }

    private static byte[] append(byte[] first, String suffix) {
        byte[] second = suffix.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static void assertInvalid(byte[] bytes) {
        assertThatThrownBy(() -> CapabilityStudioFormalEvidenceRunManifest.compile(bytes))
                .isInstanceOfSatisfying(
                        CapabilityStudioFormalEvidenceRunManifest.CompileException.class,
                        failure -> assertThat(failure.kind())
                                .isEqualTo(CapabilityStudioFormalEvidenceRunManifest.FailureKind.INVALID));
    }

    private static String fp(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
