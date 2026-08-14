package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessMirrorProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void packagesStrictSchemasAndExecutableFixturesWithoutServerDependencies() throws Exception {
        JsonNode packageDraft = fixture(BusinessMirrorProtocol.PACKAGE_FIXTURE_RESOURCE);
        JsonNode proposalSnapshot = fixture(BusinessMirrorProtocol.PROPOSAL_FIXTURE_RESOURCE);

        assertThatNoException().isThrownBy(
                () -> BusinessMirrorProtocol.requirePackageDraft(packageDraft));
        assertThatNoException().isThrownBy(
                () -> BusinessMirrorProtocol.requireProposalSnapshot(proposalSnapshot));
        assertThat(packageDraft.path("businessDefinition").path("problemCode").asText())
                .isEqualTo("TRIP.CANCELLATION.FEE");
        assertThat(proposalSnapshot.path("simulationRuntimeBinding")
                .path("networkEgressAllowed").asBoolean()).isFalse();
    }

    @Test
    void validatesAllSevenBusinessMirrorRoots() throws Exception {
        ObjectNode packageDraft = (ObjectNode) fixture(
                BusinessMirrorProtocol.PACKAGE_FIXTURE_RESOURCE);
        ObjectNode proposalSnapshot = (ObjectNode) fixture(
                BusinessMirrorProtocol.PROPOSAL_FIXTURE_RESOURCE);

        assertThatNoException().isThrownBy(() -> BusinessMirrorProtocol.requireBusinessAssetLink(
                businessAssetLink(packageDraft)));
        assertThatNoException().isThrownBy(() -> BusinessMirrorProtocol.requireBusinessAssetLinkClosure(
                businessAssetLinkClosure(packageDraft)));
        assertThatNoException().isThrownBy(() -> BusinessMirrorProtocol.requirePackageCompilationReceipt(
                packageCompilationReceipt(packageDraft)));
        assertThatNoException().isThrownBy(() -> BusinessMirrorProtocol.requirePackageDraft(
                packageDraft));
        assertThatNoException().isThrownBy(() -> BusinessMirrorProtocol.requirePackageSnapshot(
                packageSnapshot(packageDraft)));
        assertThatNoException().isThrownBy(
                () -> BusinessMirrorProtocol.requirePackageReadinessReport(
                        readinessReport(packageDraft)));
        assertThatNoException().isThrownBy(() -> BusinessMirrorProtocol.requireProposalDraft(
                proposalDraft(proposalSnapshot)));
        assertThatNoException().isThrownBy(() -> BusinessMirrorProtocol.requireProposalSnapshot(
                proposalSnapshot));
    }

    @Test
    void validatesDurablePackageApiEnvelopesAndExactReceiptFixture() throws Exception {
        ObjectNode receipt = (ObjectNode) fixture(
                BusinessMirrorProtocol.PACKAGE_SAVE_RECEIPT_FIXTURE_RESOURCE);
        ObjectNode page = JSON.createObjectNode();
        page.put("schemaVersion", BusinessMirrorProtocol.DOMAIN_CAPABILITY_PACKAGE_PAGE_V1);
        page.putArray("items").add(receipt.path("result").deepCopy());
        page.put("nextCursor", "");

        assertThatNoException().isThrownBy(() ->
                BusinessMirrorProtocol.requireStoredPackageDraft(receipt.path("result")));
        assertThatNoException().isThrownBy(() ->
                BusinessMirrorProtocol.requirePackageSaveReceipt(receipt));
        assertThatNoException().isThrownBy(() ->
                BusinessMirrorProtocol.requirePackagePage(page));
    }

    @Test
    void rejectsTamperedDurablePackageApiEnvelopeShape() throws Exception {
        ObjectNode receipt = (ObjectNode) fixture(
                BusinessMirrorProtocol.PACKAGE_SAVE_RECEIPT_FIXTURE_RESOURCE);
        ((ObjectNode) receipt.path("result")).put("rawBusinessPayload", "must-not-leak");

        assertThatThrownBy(() -> BusinessMirrorProtocol.requirePackageSaveReceipt(receipt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_SAVE_RECEIPT_INVALID")
                .hasMessageNotContaining("must-not-leak");
    }

    @Test
    void rejectsSchemaValidStoredDraftContentWhoseCanonicalFingerprintWasNotUpdated() throws Exception {
        ObjectNode receipt = (ObjectNode) fixture(
                BusinessMirrorProtocol.PACKAGE_SAVE_RECEIPT_FIXTURE_RESOURCE);
        ((ArrayNode) receipt.path("result").path("draft").path("assumptions"))
                .add("tampered-but-schema-valid");

        assertThatThrownBy(() -> BusinessMirrorProtocol.requirePackageSaveReceipt(receipt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_DRAFT_FINGERPRINT_MISMATCH")
                .hasMessageNotContaining("tampered-but-schema-valid");
    }

    @Test
    void rejectsDuplicatePageItemsAndCursorThatDoesNotBindTheLastItem() throws Exception {
        ObjectNode receipt = (ObjectNode) fixture(
                BusinessMirrorProtocol.PACKAGE_SAVE_RECEIPT_FIXTURE_RESOURCE);
        ObjectNode duplicatePage = JSON.createObjectNode();
        duplicatePage.put("schemaVersion", BusinessMirrorProtocol.DOMAIN_CAPABILITY_PACKAGE_PAGE_V1);
        duplicatePage.putArray("items")
                .add(receipt.path("result").deepCopy())
                .add(receipt.path("result").deepCopy());
        duplicatePage.put("nextCursor", "cancellation-fee-resolution");

        assertThatThrownBy(() -> BusinessMirrorProtocol.requirePackagePage(duplicatePage))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_PAGE_ORDER_INVALID");

        ObjectNode badCursorPage = duplicatePage.deepCopy();
        ((ArrayNode) badCursorPage.path("items")).remove(1);
        badCursorPage.put("nextCursor", "different-package");
        assertThatThrownBy(() -> BusinessMirrorProtocol.requirePackagePage(badCursorPage))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_PAGE_CURSOR_INVALID");
    }

    @Test
    void rejectsUnknownFieldsAndDoesNotLeakTheirValues() throws Exception {
        ObjectNode packageDraft = (ObjectNode) fixture(
                BusinessMirrorProtocol.PACKAGE_FIXTURE_RESOURCE);
        packageDraft.put("productionCredential", "do-not-emit-this-value");

        assertThatThrownBy(() -> BusinessMirrorProtocol.requirePackageDraft(packageDraft))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_DRAFT_INVALID")
                .hasMessageNotContaining("do-not-emit-this-value");
    }

    @Test
    void rejectsSimulationBindingsThatPermitExternalBehavior() throws Exception {
        ObjectNode proposal = (ObjectNode) fixture(
                BusinessMirrorProtocol.PROPOSAL_FIXTURE_RESOURCE);
        ((ObjectNode) proposal.path("simulationRuntimeBinding"))
                .put("networkEgressAllowed", true);

        assertThatThrownBy(() -> BusinessMirrorProtocol.requireProposalSnapshot(proposal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_SNAPSHOT_INVALID");
    }

    @Test
    void rejectsLayerKindDriftAtTheWireBoundary() throws Exception {
        ObjectNode packageDraft = (ObjectNode) fixture(
                BusinessMirrorProtocol.PACKAGE_FIXTURE_RESOURCE);
        ObjectNode link = businessAssetLink(packageDraft);
        ((ObjectNode) link.path("targetRef")).put("kind", "OPERATOR");

        assertThatThrownBy(() -> BusinessMirrorProtocol.requireBusinessAssetLink(link))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.BUSINESS_ASSET_LINK_INVALID");
    }

    @Test
    void rejectsReadinessStatusThatContradictsFindings() throws Exception {
        ObjectNode packageDraft = (ObjectNode) fixture(
                BusinessMirrorProtocol.PACKAGE_FIXTURE_RESOURCE);
        ObjectNode report = readinessReport(packageDraft);
        report.put("status", "READY");

        assertThatThrownBy(() -> BusinessMirrorProtocol.requirePackageReadinessReport(report))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_READINESS_INVALID");
    }

    @Test
    void rejectsSchemaValidCompilationFactsAfterContentTampering() throws Exception {
        ObjectNode packageDraft = (ObjectNode) fixture(
                BusinessMirrorProtocol.PACKAGE_FIXTURE_RESOURCE);
        ObjectNode snapshot = packageSnapshot(packageDraft);
        ((ObjectNode) snapshot.path("businessDefinition")).put(
                "businessGoal", "schema-valid but modified");

        assertThatThrownBy(() -> BusinessMirrorProtocol.requirePackageSnapshot(snapshot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_SNAPSHOT_FINGERPRINT_MISMATCH")
                .hasMessageNotContaining("schema-valid but modified");
    }

    @Test
    void rejectsDanglingAndCyclicBusinessAssetLinkClosures() throws Exception {
        ObjectNode packageDraft = (ObjectNode) fixture(
                BusinessMirrorProtocol.PACKAGE_FIXTURE_RESOURCE);
        ObjectNode dangling = businessAssetLinkClosure(packageDraft);
        ((ArrayNode) dangling.path("assets")).remove(1);
        seal(dangling);

        assertThatThrownBy(() -> BusinessMirrorProtocol.requireBusinessAssetLinkClosure(dangling))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.BUSINESS_ASSET_LINK_CLOSURE_DANGLING");

        ObjectNode cyclic = businessAssetLinkClosure(packageDraft);
        ObjectNode backwards = JSON.createObjectNode();
        backwards.put("schemaVersion", BusinessMirrorProtocol.BUSINESS_ASSET_LINK_V1);
        backwards.set("sourceRef", packageDraft.path("channelRefs").get(0).deepCopy());
        backwards.set("targetRef", packageDraft.path("solutionRefs").get(0).deepCopy());
        backwards.put("relation", "USES");
        backwards.put("condition", "");
        backwards.put("risk", "HIGH");
        backwards.put("owner", "cancellation-service-owner");
        backwards.set("provenance", packageDraft.path("provenance").deepCopy());
        ((ArrayNode) cyclic.path("links")).add(backwards);
        seal(cyclic);
        assertThatThrownBy(() -> BusinessMirrorProtocol.requireBusinessAssetLinkClosure(cyclic))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.BUSINESS_ASSET_LINK_CLOSURE_CYCLE");
    }

    @Test
    void rejectsSemanticallyDuplicateBusinessAssetLinksEvenWhenMetadataDiffers() throws Exception {
        ObjectNode packageDraft = (ObjectNode) fixture(
                BusinessMirrorProtocol.PACKAGE_FIXTURE_RESOURCE);
        ObjectNode closure = businessAssetLinkClosure(packageDraft);
        ObjectNode duplicate = businessAssetLink(packageDraft);
        duplicate.put("risk", "LOW");
        duplicate.put("owner", "another-owner");
        ((ArrayNode) closure.path("links")).add(duplicate);
        seal(closure);

        assertThatThrownBy(() -> BusinessMirrorProtocol.requireBusinessAssetLinkClosure(closure))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.BUSINESS_ASSET_LINK_CLOSURE_LINK_DUPLICATE");
    }

    @Test
    void rejectsCompilationReceiptWhoseEmbeddedFactsDoNotShareOneSource() throws Exception {
        ObjectNode packageDraft = (ObjectNode) fixture(
                BusinessMirrorProtocol.PACKAGE_FIXTURE_RESOURCE);
        ObjectNode receipt = packageCompilationReceipt(packageDraft);
        receipt.put("sourceDraftRevision", 2);

        assertThatThrownBy(() -> BusinessMirrorProtocol.requirePackageCompilationReceipt(receipt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_COMPILATION_RECEIPT_INCONSISTENT");
    }

    @Test
    void rejectsEvidenceStateWithoutRequiredEvidenceKinds() throws Exception {
        ObjectNode proposal = (ObjectNode) fixture(
                BusinessMirrorProtocol.PROPOSAL_FIXTURE_RESOURCE);
        proposal.put("evidenceState", "CALIBRATED");

        assertThatThrownBy(() -> BusinessMirrorProtocol.requireProposalSnapshot(proposal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.PROPOSAL_SNAPSHOT_INVALID");
    }

    @Test
    void exposesOnlyVersionedWireConstants() {
        assertThat(BusinessMirrorProtocol.DOMAIN_CAPABILITY_PACKAGE_DRAFT_V1)
                .isEqualTo("bloge.domainCapabilityPackageDraft.v1");
        assertThat(BusinessMirrorProtocol.CAPABILITY_PROPOSAL_SNAPSHOT_V1)
                .isEqualTo("resourceGateway.capabilityProposalSnapshot.v1");
        assertThat(BusinessMirrorProtocol.DOMAIN_CAPABILITY_PACKAGE_SAVE_RECEIPT_V1)
                .isEqualTo("resourceGateway.domainCapabilityPackageSaveReceipt.v1");
        assertThatThrownBy(() -> BusinessMirrorProtocol.requirePackageDraft(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.PACKAGE_DRAFT_INVALID");
    }

    private static JsonNode fixture(String resource) throws Exception {
        try (InputStream input = BusinessMirrorProtocolTest.class.getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            return JSON.readTree(input);
        }
    }

    private static ObjectNode businessAssetLink(ObjectNode packageDraft) {
        ObjectNode link = JSON.createObjectNode();
        link.put("schemaVersion", BusinessMirrorProtocol.BUSINESS_ASSET_LINK_V1);
        link.set("sourceRef", packageDraft.path("solutionRefs").get(0).deepCopy());
        link.set("targetRef", packageDraft.path("carrierRefs").get(0).deepCopy());
        link.put("relation", "DELIVERED_BY");
        link.put("condition", "fee-disputed");
        link.put("risk", "HIGH");
        link.put("owner", "cancellation-service-owner");
        link.set("provenance", packageDraft.path("provenance").deepCopy());
        return link;
    }

    private static ObjectNode packageSnapshot(ObjectNode packageDraft) {
        ObjectNode snapshot = JSON.createObjectNode();
        snapshot.put("schemaVersion",
                BusinessMirrorProtocol.DOMAIN_CAPABILITY_PACKAGE_SNAPSHOT_V1);
        snapshot.put("packageId", packageDraft.path("packageId").asText());
        snapshot.put("revision", 1);
        snapshot.put("fingerprint", fingerprint('1'));
        snapshot.set("scope", packageDraft.path("scope").deepCopy());
        snapshot.put("sourceDraftRevision", 1);
        snapshot.put("sourceDraftFingerprint", fingerprint('2'));
        snapshot.set("businessDefinition", packageDraft.path("businessDefinition").deepCopy());
        snapshot.set("packageContractRef", packageDraft.path("packageContractRef").deepCopy());
        snapshot.set("capabilityClosureRef", artifactRef("CAPABILITY_CLOSURE", "closure", '3'));
        snapshot.putArray("mirrorPlanRefs").add(artifactRef("MIRROR_PLAN", "plan", '4'));
        snapshot.set("businessAssetLinkClosureRef",
                artifactRef("BUSINESS_ASSET_LINK_CLOSURE", "links", '5'));
        snapshot.set("readinessReportRef",
                artifactRef("PACKAGE_READINESS_REPORT", "readiness", '6'));
        snapshot.putArray("dependencyManifest")
                .add(artifactRef("CAPABILITY", "trip-query", '7'));
        snapshot.putArray("evidenceRefs");
        snapshot.put("compilerVersion", "business-mirror-compiler-v1");
        snapshot.set("policyGenerationRef",
                artifactRef("PACKAGE_COMPILATION_POLICY", "default", '8'));
        snapshot.set("provenance", packageDraft.path("provenance").deepCopy());
        snapshot.put("createdAt", "2026-08-14T02:00:00Z");
        return seal(snapshot);
    }

    private static ObjectNode readinessReport(ObjectNode packageDraft) {
        ObjectNode report = JSON.createObjectNode();
        report.put("schemaVersion", BusinessMirrorProtocol.PACKAGE_READINESS_REPORT_V1);
        report.put("reportId", "cancellation-readiness");
        report.put("revision", 1);
        report.put("fingerprint", fingerprint('9'));
        report.set("scope", packageDraft.path("scope").deepCopy());
        report.put("packageId", packageDraft.path("packageId").asText());
        report.put("sourceDraftRevision", 1);
        report.put("sourceDraftFingerprint", fingerprint('a'));
        report.put("status", "REVIEW_REQUIRED");
        ObjectNode finding = JSON.createObjectNode();
        finding.put("findingId", "owner-review");
        finding.put("code", "OWNER_REVIEW");
        finding.put("severity", "WARNING");
        finding.put("category", "GOVERNANCE");
        finding.put("fieldPath", "/businessDefinition/accountableOwner");
        finding.putNull("artifactRef");
        finding.put("messageId", "package.owner.review");
        report.putArray("findings").add(finding);
        report.put("createdAt", "2026-08-14T02:00:00Z");
        return seal(report);
    }

    private static ObjectNode businessAssetLinkClosure(ObjectNode packageDraft) {
        ObjectNode closure = JSON.createObjectNode();
        closure.put("schemaVersion", BusinessMirrorProtocol.BUSINESS_ASSET_LINK_CLOSURE_V1);
        closure.put("closureId", "cancellation-asset-links");
        closure.put("revision", 1);
        closure.put("fingerprint", "");
        closure.set("scope", packageDraft.path("scope").deepCopy());
        closure.put("packageId", packageDraft.path("packageId").asText());
        closure.putArray("assets")
                .add(packageDraft.path("solutionRefs").get(0).deepCopy())
                .add(packageDraft.path("carrierRefs").get(0).deepCopy())
                .add(packageDraft.path("channelRefs").get(0).deepCopy());
        ArrayNode links = closure.putArray("links");
        links.add(businessAssetLink(packageDraft));
        ObjectNode exposed = JSON.createObjectNode();
        exposed.put("schemaVersion", BusinessMirrorProtocol.BUSINESS_ASSET_LINK_V1);
        exposed.set("sourceRef", packageDraft.path("carrierRefs").get(0).deepCopy());
        exposed.set("targetRef", packageDraft.path("channelRefs").get(0).deepCopy());
        exposed.put("relation", "EXPOSED_ON");
        exposed.put("condition", "");
        exposed.put("risk", "HIGH");
        exposed.put("owner", "cancellation-service-owner");
        exposed.set("provenance", packageDraft.path("provenance").deepCopy());
        links.add(exposed);
        closure.put("createdAt", "2026-08-14T02:00:00Z");
        return seal(closure);
    }

    private static ObjectNode packageCompilationReceipt(ObjectNode packageDraft) {
        ObjectNode readiness = readinessReport(packageDraft);
        ObjectNode closure = businessAssetLinkClosure(packageDraft);
        ObjectNode snapshot = packageSnapshot(packageDraft);
        snapshot.put("sourceDraftFingerprint", readiness.path("sourceDraftFingerprint").asText());
        ObjectNode readinessRef = (ObjectNode) snapshot.path("readinessReportRef");
        readinessRef.put("id", readiness.path("reportId").asText());
        readinessRef.put("revision", readiness.path("revision").asLong());
        readinessRef.put("fingerprint", readiness.path("fingerprint").asText());
        ObjectNode closureRef = (ObjectNode) snapshot.path("businessAssetLinkClosureRef");
        closureRef.put("id", closure.path("closureId").asText());
        closureRef.put("revision", closure.path("revision").asLong());
        closureRef.put("fingerprint", closure.path("fingerprint").asText());
        seal(snapshot);

        ObjectNode receipt = JSON.createObjectNode();
        receipt.put("schemaVersion", BusinessMirrorProtocol.PACKAGE_COMPILATION_RECEIPT_V1);
        receipt.put("requestFingerprint", fingerprint('b'));
        receipt.put("packageId", packageDraft.path("packageId").asText());
        receipt.put("sourceDraftRevision", readiness.path("sourceDraftRevision").asLong());
        receipt.put("sourceDraftFingerprint", readiness.path("sourceDraftFingerprint").asText());
        receipt.put("compilationRevision", readiness.path("revision").asLong());
        receipt.set("readiness", readiness);
        receipt.set("businessAssetLinkClosure", closure);
        receipt.set("snapshot", snapshot);
        receipt.put("authorityGeneration", "authority-generation-7");
        receipt.put("completedAt", readiness.path("createdAt").asText());
        return receipt;
    }

    private static ObjectNode seal(ObjectNode value) {
        value.put("fingerprint", "");
        value.put("fingerprint", BusinessMirrorCanonical.fingerprint(value,
                "RG.BUSINESS_MIRROR.CLIENT.TEST_TOO_LARGE",
                "RG.BUSINESS_MIRROR.CLIENT.TEST_CANONICALIZATION_FAILED"));
        return value;
    }

    private static ObjectNode proposalDraft(ObjectNode snapshot) {
        ObjectNode draft = snapshot.deepCopy();
        draft.put("schemaVersion", BusinessMirrorProtocol.CAPABILITY_PROPOSAL_DRAFT_V1);
        draft.remove("fingerprint");
        draft.remove("sourceDraftRevision");
        draft.remove("sourceDraftFingerprint");
        draft.remove("implementationBindingRef");
        draft.remove("evidenceState");
        draft.remove("evidenceRefs");
        draft.remove("createdAt");
        draft.put("lifecycle", "READY_FOR_REVIEW");
        return draft;
    }

    private static ObjectNode artifactRef(String kind, String id, char value) {
        ObjectNode ref = JSON.createObjectNode();
        ref.put("kind", kind);
        ref.put("id", id);
        ref.put("revision", 1);
        ref.put("fingerprint", fingerprint(value));
        return ref;
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
