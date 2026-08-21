package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioDeploymentStateObservation.Observation;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioDeploymentStateObservation.Phase;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioExecutionLeaseTranscript.EvidencePublicationStatus;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.AdmissionLifecycleMaterial;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.AtomicAdmissionLifecycleCommitReceipt;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceExecutionLeaseCommitResult;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseCommitStatus;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseReceipt;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseRequest;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseTransitionWitness;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.RevocationAuthoritySnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioFormalEvidenceWireTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant TRUSTED = Instant.parse("2026-08-20T00:00:10Z");

    @Test
    void observationHasGoldenCanonicalMaterialAndPhaseBoundIdentity() throws Exception {
        Observation before = observation(Phase.BEFORE, 0, fp('2'), fp('3'), 0, 0);
        Observation afterSame = CapabilityStudioDeploymentStateObservation.create(Phase.AFTER,
                before.evidenceTransactionId(), before.storeDescriptorFingerprint(),
                before.storeDescriptorRawFingerprint(), before.generation(),
                before.previousStateFingerprint(),
                before.stateFingerprint(), before.stateRawFingerprint(),
                before.checkpointFingerprint(), before.checkpointRawFingerprint(),
                before.revocationHeadSequence(), before.revocationHeadFingerprint(),
                before.revocationHeadRawFingerprint(), before.lifecycleHeadFingerprint(),
                before.fencingSequence(), before.leaseCount(),
                before.leaseInventoryFingerprint());

        assertThat(CapabilityStudioDeploymentStateObservation
                .stateMaterialCanonicalMessage(before.storeDescriptorFingerprint(),
                        before.storeDescriptorRawFingerprint(), before.generation(),
                        before.previousStateFingerprint(),
                        before.stateFingerprint(), before.stateRawFingerprint(),
                        before.checkpointFingerprint(), before.checkpointRawFingerprint(),
                        before.revocationHeadSequence(), before.revocationHeadFingerprint(),
                        before.revocationHeadRawFingerprint(),
                        before.lifecycleHeadFingerprint(), before.fencingSequence(),
                        before.leaseCount(), before.leaseInventoryFingerprint()))
                .contains("\"messageVersion\":\"resource-gateway.capability-studio."
                        + "deployment-state-material.v1\"");
        assertThat(before.stateMaterialFingerprint())
                .isEqualTo(afterSame.stateMaterialFingerprint());
        assertThat(before.observationFingerprint())
                .isNotEqualTo(afterSame.observationFingerprint());
        assertThat(CapabilityStudioDeploymentStateObservation.verify(before.bytes()))
                .isEqualTo(before);
        assertThat(new String(before.bytes(), StandardCharsets.UTF_8))
                .startsWith("{\"messageVersion\":\"resource-gateway.capability-studio."
                        + "deployment-state-observation.v1\",\"phase\":\"BEFORE\","
                        + "\"evidenceTransactionId\":\"")
                .endsWith("\"}");
    }

    @Test
    void transcriptRoundTripsAndBindsWitnessAttemptAndObservations() throws Exception {
        Fixture fixture = fixture(ExecutionLeaseCommitStatus.COMMITTED, TRUSTED);
        byte[] bytes = fixture.transcript.bytes();

        assertThat(CapabilityStudioExecutionLeaseTranscript.verify(bytes))
                .isEqualTo(fixture.transcript);
        assertThat(fixture.transcript.commitIdentityFingerprint())
                .isEqualTo(fixture.request.commitIdentityFingerprint());
        assertThat(fixture.transcript.canonicalMessage())
                .contains("\"transcriptFingerprint\":null")
                .contains("\"executionLeaseTransitionWitness\":{")
                .contains("\"semanticVerificationTime\":\"2026-08-20T00:00:00Z\"");
        assertThat(sha256(fixture.transcript.canonicalMessage()
                .getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(fixture.transcript.transcriptFingerprint());
        assertThat(sha256(bytes)).isEqualTo(
                "sha256:9bbbad5cabeb78f0b6d2a3b5ae530626b7af740fde3dfb5b35cb94483aa910d7");
        assertThat(fixture.transcript.transcriptFingerprint())
                .isEqualTo("sha256:b5080c5117d103ab665f6e855b39ec54c2f367974b33fad04a75d5d681d96221");
        assertThat(sha256(fixture.transcript.beforeStateObservation().bytes()))
                .isEqualTo("sha256:efc0c48a0916b5f45a8e658cf16adbdb513e9d313512b1f2870b842684a69579");
    }

    @Test
    void attemptTimeChangesTranscriptButNotStableCommitIdentity() {
        Fixture first = fixture(ExecutionLeaseCommitStatus.COMMITTED, TRUSTED);
        Fixture retry = fixture(ExecutionLeaseCommitStatus.RECOVERED, TRUSTED.plusSeconds(1));

        assertThat(retry.request.commitIdentityFingerprint())
                .isEqualTo(first.request.commitIdentityFingerprint());
        assertThat(retry.transcript.transcriptFingerprint())
                .isNotEqualTo(first.transcript.transcriptFingerprint());
        assertThat(retry.transcript.executionLeaseReceipt().fingerprint())
                .isEqualTo(first.transcript.executionLeaseReceipt().fingerprint());
    }

    @Test
    void strictSchemasArePackagedAndRejectUnknownDuplicateAndTrailingBytes() throws Exception {
        assertThat(getClass().getResource(
                CapabilityStudioSchemaSupport.DEPLOYMENT_STATE_OBSERVATION_V1_RESOURCE))
                .isNotNull();
        assertThat(getClass().getResource(
                CapabilityStudioSchemaSupport.EXECUTION_LEASE_TRANSCRIPT_V1_RESOURCE))
                .isNotNull();
        assertThat(getClass().getResource(CapabilityStudioSchemaSupport
                .EXECUTION_LEASE_EVIDENCE_COMMIT_MANIFEST_V1_RESOURCE)).isNotNull();
        String original = new String(fixture(ExecutionLeaseCommitStatus.COMMITTED, TRUSTED)
                .transcript.bytes(), StandardCharsets.UTF_8);
        for (String changed : java.util.List.of(
                original.replaceFirst("\\{", "{\"messageVersion\":\"duplicate\","),
                original.substring(0, original.length() - 1) + ",\"unknown\":true}",
                original + " ")) {
            assertThatThrownBy(() -> CapabilityStudioExecutionLeaseTranscript.verify(
                    changed.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void commitManifestWireIsCanonicalClosedAndRejectsArtifactConfusion() throws Exception {
        byte[] valid = commitManifestFixture();
        CapabilityStudioExecutionLeaseEvidenceCli
                .verifyCommitManifestWireForTesting(valid);

        ObjectNode extra = (ObjectNode) JSON.readTree(valid);
        extra.put("unknown", true);
        ObjectNode traversal = (ObjectNode) JSON.readTree(valid);
        ((ObjectNode) traversal.withArray("artifacts").get(0))
                .put("relativePath", "../before.json");
        ObjectNode duplicateRole = (ObjectNode) JSON.readTree(valid);
        ((ObjectNode) duplicateRole.withArray("artifacts").get(1))
                .put("role", duplicateRole.withArray("artifacts").get(0)
                        .path("role").asText());
        ObjectNode wrongCanonical = (ObjectNode) JSON.readTree(valid);
        ((ObjectNode) wrongCanonical.withArray("artifacts").get(0))
                .put("canonicalAbsent", true).putNull("canonicalFingerprint");
        for (ObjectNode invalid : java.util.List.of(
                extra, traversal, duplicateRole, wrongCanonical)) {
            byte[] bytes = manifestWithRecomputedFingerprint(invalid);
            assertThatThrownBy(() -> CapabilityStudioExecutionLeaseEvidenceCli
                    .verifyCommitManifestWireForTesting(bytes))
                    .isInstanceOf(IOException.class);
        }

        String duplicateField = new String(valid, StandardCharsets.UTF_8)
                .replaceFirst("\\\"ownerFingerprint\\\":",
                        "\\\"ownerFingerprint\\\":\\\"" + fp('f')
                                + "\\\",\\\"ownerFingerprint\\\":");
        assertThatThrownBy(() -> CapabilityStudioExecutionLeaseEvidenceCli
                .verifyCommitManifestWireForTesting(
                        duplicateField.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void bundleVerifierAndCliExposeOnlyClosedInvalidOrUnavailableFailures(
            @TempDir Path temporaryDirectory) throws Exception {
        String secret = "UPPERCASE_CREDENTIAL_PAYLOAD";
        var invalid = org.junit.jupiter.api.Assertions.assertThrows(
                CapabilityStudioExecutionLeaseEvidenceBundleVerifier
                        .VerificationException.class,
                () -> CapabilityStudioExecutionLeaseEvidenceBundleVerifier.verify(
                        Path.of(secret), fp('1'), fp('2'), fp('3')));
        assertThat(invalid.failureKind()).isEqualTo(
                CapabilityStudioStageAcceptanceAuthorityProvider
                        .EvidenceFailureKind.INVALID);
        assertThat(invalid.toString()).doesNotContain(secret);

        ByteArrayOutputStream invalidBytes = new ByteArrayOutputStream();
        int invalidExit = CapabilityStudioExecutionLeaseEvidenceBundleVerifyCli.run(
                new String[]{"--transcript", secret,
                        "--expected-stage-result-raw-fingerprint", fp('1'),
                        "--expected-formal-outer-fingerprint", fp('2'),
                        "--expected-publication-fingerprint", fp('3')},
                new PrintStream(invalidBytes, true, StandardCharsets.UTF_8));
        assertThat(invalidExit).isEqualTo(2);
        assertThat(invalidBytes.toString(StandardCharsets.UTF_8)).isEqualTo(
                "INVALID errorCode=RG.CAPABILITY_STUDIO."
                        + "EXECUTION_LEASE_EVIDENCE_BUNDLE_VERIFY_CLI.INVALID\n")
                .doesNotContain(secret);

        Path missing = temporaryDirectory.toRealPath().resolve("missing.json");
        var unavailable = org.junit.jupiter.api.Assertions.assertThrows(
                CapabilityStudioExecutionLeaseEvidenceBundleVerifier
                        .VerificationException.class,
                () -> CapabilityStudioExecutionLeaseEvidenceBundleVerifier.verify(
                        missing, fp('1'), fp('2'), fp('3')));
        assertThat(unavailable.failureKind()).isEqualTo(
                CapabilityStudioStageAcceptanceAuthorityProvider
                        .EvidenceFailureKind.UNAVAILABLE);
        assertThat(unavailable.toString()).doesNotContain(missing.toString());

        ByteArrayOutputStream unavailableBytes = new ByteArrayOutputStream();
        int unavailableExit = CapabilityStudioExecutionLeaseEvidenceBundleVerifyCli.run(
                new String[]{"--transcript", missing.toString(),
                        "--expected-stage-result-raw-fingerprint", fp('1'),
                        "--expected-formal-outer-fingerprint", fp('2'),
                        "--expected-publication-fingerprint", fp('3')},
                new PrintStream(unavailableBytes, true, StandardCharsets.UTF_8));
        assertThat(unavailableExit).isEqualTo(3);
        assertThat(unavailableBytes.toString(StandardCharsets.UTF_8)).isEqualTo(
                "NOT_VERIFIED outcome=BLOCKED reasonCode=RG.CAPABILITY_STUDIO."
                        + "EXECUTION_LEASE_EVIDENCE_BUNDLE_VERIFY_CLI.UNAVAILABLE\n")
                .doesNotContain(missing.toString(), secret);
    }

    @Test
    void substitutionsAcrossRequestReceiptWitnessAndObservationFailClosed() throws Exception {
        Fixture fixture = fixture(ExecutionLeaseCommitStatus.COMMITTED, TRUSTED);
        ObjectNode wire = (ObjectNode) JSON.readTree(fixture.transcript.bytes());
        for (String pointer : java.util.List.of(
                "/executionLeaseRequest/stageResultRawFingerprint",
                "/executionLeaseRequest/evidenceClosureFingerprint",
                "/executionLeaseRequest/providerOuterFingerprint",
                "/executionLeaseRequest/targetRawFingerprint",
                "/executionLeaseRequest/targetCanonicalFingerprint",
                "/executionLeaseReceipt/fingerprint",
                "/atomicAdmissionLifecycleCommitReceipt/revocationSnapshotFingerprint",
                "/executionLeaseTransitionWitness/postStateFingerprint",
                "/afterStateObservation/stateRawFingerprint")) {
            ObjectNode changed = wire.deepCopy();
            JsonPointerMutation.put(changed, pointer, fp('8'));
            assertThatThrownBy(() -> CapabilityStudioExecutionLeaseTranscript.verify(
                    JSON.writeValueAsBytes(changed)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static Fixture fixture(
            ExecutionLeaseCommitStatus status, Instant trustedTime) {
        RevocationAuthoritySnapshot revocation = new RevocationAuthoritySnapshot(
                "registry:test", 1, fp('1'), Instant.parse("2026-08-19T00:00:00Z"),
                Instant.parse("2026-08-21T00:00:00Z"));
        AdmissionLifecycleMaterial lifecycle = new AdmissionLifecycleMaterial(
                fp('a'), "bundle:test", 1, "ACTIVE", null, revocation);
        ExecutionLeaseRequest request = new ExecutionLeaseRequest(
                "result:test", 1, fp('b'), fp('c'), "contract:test", "revision:1",
                "lease:test", fp('d'), fp('e'), fp('f'), lifecycle, fp('0'), trustedTime);
        AtomicAdmissionLifecycleCommitReceipt atomic =
                new AtomicAdmissionLifecycleCommitReceipt(fp('0'), lifecycle.fingerprint(),
                        revocation.registryRef(), revocation.revision(),
                        revocation.snapshotFingerprint(), 1,
                        Instant.parse("2026-08-20T00:00:20Z"),
                        request.commitIdentityFingerprint());
        ExecutionLeaseReceipt receipt = new ExecutionLeaseReceipt(
                request.commitIdentityFingerprint(), lifecycle, atomic);
        ExecutionLeaseTransitionWitness witness = new ExecutionLeaseTransitionWitness(
                fp('9'), request.commitIdentityFingerprint(), receipt.fingerprint(),
                fp('2'), 0, 0, fp('3'), 0, fp('4'), fp('7'), fp('5'),
                1, 1, fp('6'), 0, fp('4'));
        EvidenceExecutionLeaseCommitResult result = new EvidenceExecutionLeaseCommitResult(
                status, receipt, witness, "LEASE_OK");
        Observation before = observation(Phase.BEFORE, 0, fp('2'), fp('3'), 0, 0);
        Observation after = observation(Phase.AFTER, 1, fp('5'), fp('6'), 1, 1);
        var transcript = CapabilityStudioExecutionLeaseTranscript.create(fp('8'),
                EvidencePublicationStatus.COMMITTED, before, after, request, result,
                Instant.parse("2026-08-20T00:00:00Z"));
        return new Fixture(request, transcript);
    }

    private static byte[] commitManifestFixture() throws Exception {
        ObjectNode node = JSON.createObjectNode();
        node.put("messageVersion", "resource-gateway.capability-studio."
                + "execution-lease-evidence-commit-manifest.v1");
        node.put("ownerFingerprint", fp('1'));
        node.put("attemptGeneration", 1);
        node.putNull("previousAttemptClosureFingerprint");
        node.put("requestCommitIdentityFingerprint", fp('2'));
        node.put("beforeRawFingerprint", fp('3'));
        node.put("beforeJournalFingerprint", fp('4'));
        node.put("transcriptRawFingerprint", fp('5'));
        node.put("transcriptFingerprint", fp('6'));
        node.put("receiptFingerprint", fp('7'));
        node.put("witnessFingerprint", fp('8'));
        var artifacts = node.putArray("artifacts");
        artifact(artifacts.addObject(), "before-v2-g00000000000000000001.json",
                "BEFORE_JOURNAL_G00000000000000000001", 11, fp('9'), fp('a'));
        artifact(artifacts.addObject(), "committed-transcript-v1.json",
                "RETAINED_TRANSCRIPT", 12, fp('b'), fp('c'));
        artifact(artifacts.addObject(), "owner-v3.json", "OWNER", 13,
                fp('d'), fp('e'));
        return manifestWithRecomputedFingerprint(node);
    }

    private static void artifact(
            ObjectNode node, String path, String role, long size,
            String raw, String canonical) {
        node.put("relativePath", path);
        node.put("role", role);
        node.put("byteSize", size);
        node.put("rawFingerprint", raw);
        node.put("canonicalAbsent", false);
        node.put("canonicalFingerprint", canonical);
    }

    private static byte[] manifestWithRecomputedFingerprint(ObjectNode node) throws Exception {
        node.putNull("commitManifestFingerprint");
        String fingerprint = sha256(JSON.writeValueAsBytes(node));
        node.put("commitManifestFingerprint", fingerprint);
        return JSON.writeValueAsBytes(node);
    }

    private static Observation observation(
            Phase phase, long generation, String state, String checkpoint,
            long fencing, int leases) {
        return CapabilityStudioDeploymentStateObservation.create(phase, fp('8'), fp('9'),
                fp('0'), generation, generation == 0 ? null : fp('2'), state, fp('7'),
                checkpoint, fp('6'), 0, fp('4'), fp('5'),
                generation == 0 ? null : fp('a'),
                fencing, leases, fp('1'));
    }

    private static String fp(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Fixture(
            ExecutionLeaseRequest request,
            CapabilityStudioExecutionLeaseTranscript.Transcript transcript) {
    }

    private static final class JsonPointerMutation {
        private static void put(ObjectNode root, String pointer, String value) {
            int split = pointer.lastIndexOf('/');
            ObjectNode parent = (ObjectNode) root.at(pointer.substring(0, split));
            parent.put(pointer.substring(split + 1), value);
        }
    }
}
