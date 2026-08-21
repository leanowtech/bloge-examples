package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioGateACandidateReplayResultTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void emitsStrictIncompleteResultWithFourteenObligations() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.empty(temporaryDirectory);
        fixture.addAllInventory();
        fixture.write();

        var bundle = CapabilityStudioGateACandidateReplayResult.create(
                fixture.manifest(), fixture.root(), context());
        var result = CapabilityStudioFormalEvidenceRunManifest.parseStrict(bundle.resultBytes());

        assertThat(result.path("terminal").textValue()).isEqualTo("INCOMPLETE");
        assertThat(result.path("reasonCode").textValue()).isEqualTo("A0_INCOMPLETE");
        assertThat(result.path("adapterResults")).hasSize(3);
        assertThat(result.path("obligationResults")).hasSize(14);
        assertThat(result.path("adapterNotRunCount").intValue()).isEqualTo(3);
        assertThat(bundle.adapterMaterials()).isEmpty();
        CapabilityStudioGateACandidateReplayResult.verifyResultBytes(bundle.resultBytes());
    }

    @Test
    void emitsResolvableExactMaterialForVerifiedAdapter() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(temporaryDirectory);

        var bundle = CapabilityStudioGateACandidateReplayResult.create(
                fixture.manifest(), fixture.root(), context());
        var result = CapabilityStudioFormalEvidenceRunManifest.parseStrict(bundle.resultBytes());
        var material = bundle.adapterMaterials().get("STAGE_ACCEPTANCE_RESULT_V2");

        assertThat(result.path("terminal").textValue()).isEqualTo("STRUCTURE_VERIFIED");
        assertThat(result.path("adapterVerifiedCount").intValue()).isEqualTo(1);
        assertThat(material).isNotNull();
        assertThat(CapabilityStudioFormalEvidenceRunManifest.sha256(material.exactBytes()))
                .isEqualTo(material.rawFingerprint());
        assertThat(result.path("adapterResults").path(2).path("resultRef")
                .path("rawFingerprint").path("value").textValue())
                .isEqualTo(material.rawFingerprint());
    }

    @Test
    void projectsAllThreeAdaptersAndEvidenceReferencesWithoutLosingBindings() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.allAdapters(
                temporaryDirectory);
        ObjectNode firstObligation = (ObjectNode) fixture.manifestNode()
                .withArray("obligations").get(0);
        firstObligation.put("status", "FAIL").withArray("evidencePaths")
                .add("stage-result.json");
        fixture.manifestNode().put("failed", 1).put("notRun", 13);
        fixture.write();

        var bundle = CapabilityStudioGateACandidateReplayResult.create(
                fixture.manifest(), fixture.root(), context());
        var result = CapabilityStudioFormalEvidenceRunManifest.parseStrict(bundle.resultBytes());

        assertThat(result.path("adapterVerifiedCount").intValue()).isEqualTo(3);
        assertThat(bundle.adapterMaterials()).hasSize(3);
        assertThat(result.path("obligationResults").path(0).path("evidenceRefs")
                .path(0).path("uri").textValue())
                .isEqualTo("formal-evidence/files/stage-result.json");
        assertThat(result.path("obligationFailedCount").intValue()).isOne();
    }

    @Test
    void rejectsDerivedCountMutationEvenWithARefreshedDocumentFingerprint() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.empty(temporaryDirectory);
        fixture.addAllInventory();
        fixture.write();
        var bundle = CapabilityStudioGateACandidateReplayResult.create(
                fixture.manifest(), fixture.root(), context());
        ObjectNode result = (ObjectNode) CapabilityStudioFormalEvidenceRunManifest
                .parseStrict(bundle.resultBytes());
        result.put("adapterNotRunCount", 2);
        result.with("resultFingerprint").put("value", resultFingerprint(result));
        byte[] mutated = CapabilityStudioFormalEvidenceRunManifest.canonicalBytes(result);

        assertThatThrownBy(() ->
                CapabilityStudioGateACandidateReplayResult.verifyResultBytes(mutated))
                .isInstanceOf(
                        CapabilityStudioFormalEvidenceRunVerifier.VerificationException.class)
                .satisfies(error -> assertThat(
                        ((CapabilityStudioFormalEvidenceRunVerifier.VerificationException) error)
                                .failureKind())
                        .isEqualTo(CapabilityStudioFormalEvidenceRunVerifier.FailureKind.INVALID));
    }

    @Test
    void rejectsSuccessTerminalMutationEvenWithARefreshedDocumentFingerprint() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.empty(temporaryDirectory);
        fixture.addAllInventory();
        fixture.write();
        var bundle = CapabilityStudioGateACandidateReplayResult.create(
                fixture.manifest(), fixture.root(), context());
        ObjectNode result = (ObjectNode) CapabilityStudioFormalEvidenceRunManifest
                .parseStrict(bundle.resultBytes());
        result.put("terminal", "STRUCTURE_VERIFIED")
                .put("reasonCode", "A0_STRUCTURE_VERIFIED");
        result.with("resultFingerprint").put("value", resultFingerprint(result));
        byte[] mutated = CapabilityStudioFormalEvidenceRunManifest.canonicalBytes(result);

        assertInvalid(() -> CapabilityStudioGateACandidateReplayResult
                .verifyResultBytes(mutated));
    }

    @Test
    void rejectsFailureTerminalWithoutAVisibleAdapterFact() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.empty(temporaryDirectory);
        fixture.addAllInventory();
        fixture.write();
        var bundle = CapabilityStudioGateACandidateReplayResult.create(
                fixture.manifest(), fixture.root(), context());

        for (String[] mutation : new String[][]{
                {"INVALID", "A0_INVALID"}, {"UNAVAILABLE", "A0_UNAVAILABLE"}}) {
            ObjectNode result = (ObjectNode) CapabilityStudioFormalEvidenceRunManifest
                    .parseStrict(bundle.resultBytes());
            result.put("terminal", mutation[0]).put("reasonCode", mutation[1]);
            result.with("resultFingerprint").put("value", resultFingerprint(result));
            byte[] mutated = CapabilityStudioFormalEvidenceRunManifest.canonicalBytes(result);

            assertInvalid(() -> CapabilityStudioGateACandidateReplayResult
                    .verifyResultBytes(mutated));
        }
    }

    @Test
    void rejectsReasonCodeThatDoesNotMatchTerminal() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.empty(temporaryDirectory);
        fixture.addAllInventory();
        fixture.write();
        var bundle = CapabilityStudioGateACandidateReplayResult.create(
                fixture.manifest(), fixture.root(), context());
        ObjectNode result = (ObjectNode) CapabilityStudioFormalEvidenceRunManifest
                .parseStrict(bundle.resultBytes());
        result.put("reasonCode", "A0_INVALID");
        result.with("resultFingerprint").put("value", resultFingerprint(result));
        byte[] mutated = CapabilityStudioFormalEvidenceRunManifest.canonicalBytes(result);

        assertInvalid(() -> CapabilityStudioGateACandidateReplayResult
                .verifyResultBytes(mutated));
    }

    @Test
    void rejectsBundleWhoseResultRefsDoNotCloseOverReturnedMaterials() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.stageResult(temporaryDirectory);
        var bundle = CapabilityStudioGateACandidateReplayResult.create(
                fixture.manifest(), fixture.root(), context());

        assertInvalid(() -> new CapabilityStudioGateACandidateReplayResult.Bundle(
                bundle.resultBytes(), java.util.Map.of()));
    }

    private static CapabilityStudioGateACandidateReplayResult.Context context() {
        return new CapabilityStudioGateACandidateReplayResult.Context(
                "A0-DEMO-001",
                raw("candidate/artifact", 'a'),
                raw("challenge/trust-pin", 'b'),
                "formal-evidence/manifest",
                new CapabilityStudioGateACandidateReplayResult.TreeRef(
                        "challenge/input-root", fp('c')),
                raw("registry/typed-replay", 'd'),
                "formal-evidence/files",
                "candidate-result/adapter-materials");
    }

    private static CapabilityStudioGateACandidateReplayResult.RawRef raw(
            String uri, char seed) {
        return new CapabilityStudioGateACandidateReplayResult.RawRef(uri, fp(seed));
    }

    private static String resultFingerprint(ObjectNode result) throws Exception {
        ObjectNode copy = result.deepCopy();
        copy.putNull("resultFingerprint");
        byte[] canonical = CapabilityStudioFormalEvidenceRunManifest.canonicalBytes(copy);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write((CapabilityStudioGateACandidateReplayResult.FINGERPRINT_PROFILE + "\0")
                .getBytes(StandardCharsets.UTF_8));
        bytes.write(canonical);
        return CapabilityStudioFormalEvidenceRunManifest.sha256(bytes.toByteArray());
    }

    private static void assertInvalid(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOf(
                        CapabilityStudioFormalEvidenceRunVerifier.VerificationException.class)
                .satisfies(error -> assertThat(
                        ((CapabilityStudioFormalEvidenceRunVerifier.VerificationException) error)
                                .failureKind())
                        .isEqualTo(CapabilityStudioFormalEvidenceRunVerifier.FailureKind.INVALID));
    }

    private static String fp(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }
}
