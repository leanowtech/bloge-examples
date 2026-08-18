package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceResultV2Builder.SourceTreeStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioStageAcceptanceResultV2BuilderTest {
    private static final Instant DECIDED_AT = Instant.parse("2026-01-01T00:07:00Z");
    private static final String STARTED_AT = "2026-01-01T00:00:00Z";
    private static final String COMPLETED_AT = "2026-01-01T00:05:00Z";

    private static final CapabilityStudioStageAcceptanceResultV2Verifier VERIFIER =
            new CapabilityStudioStageAcceptanceResultV2Verifier();

    @Test
    void buildsAnHonestCompletedBlockedResultAcceptedByVerifier() {
        ObjectNode result = completedBuilder().build();

        assertThat(result.path("status").textValue()).isEqualTo("BLOCKED");
        assertThat(result.path("candidateExecutionBinding").path("executionStartedAt")
                .textValue()).isEqualTo(STARTED_AT);
        assertThat(result.path("diagnostics").findValuesAsText("code"))
                .doesNotContain("RUN_NOT_STARTED");
        assertThat(VERIFIER.verify(result, DECIDED_AT).verified()).isTrue();
    }

    @Test
    void buildsAPreExecutionBlockedResultWithNullTimes() {
        ObjectNode result = notStartedBuilder().build();

        JsonNode binding = result.path("candidateExecutionBinding");
        assertThat(binding.path("executionStartedAt").isNull()).isTrue();
        assertThat(binding.path("evidenceCompletedAt").isNull()).isTrue();
        assertThat(result.path("diagnostics").findValuesAsText("code"))
                .contains("RUN_NOT_STARTED");
        assertThat(VERIFIER.verify(result, DECIDED_AT).verified()).isTrue();
    }

    @Test
    void directLocalFailureWinsOverExternalBlockers() {
        ObjectNode result = completedBuilder()
                .recordCheck(CapabilityStudioStageAcceptanceResultV2Builder.Check.of(
                        "AC-STD-02", CapabilityStudioStageAcceptanceResultV2Builder.CheckStatus.FAIL))
                .build();

        assertThat(result.path("status").textValue()).isEqualTo("FAIL");
        assertThat(check(result, "AC-STD-01").path("status").textValue())
                .isEqualTo("BLOCKED");
        assertThat(check(result, "AC-STD-02").path("status").textValue())
                .isEqualTo("FAIL");
        assertThat(result.path("diagnostics").findValuesAsText("code"))
                .contains("ACCEPTANCE_CHECK_FAILED");
        assertThat(VERIFIER.verify(result, DECIDED_AT).verified()).isTrue();
    }

    @Test
    void emitsCanonicalBytesAndClosureForFixedInput() {
        CapabilityStudioStageAcceptanceResultV2Builder first = completedBuilder();
        CapabilityStudioStageAcceptanceResultV2Builder second = completedBuilder();
        for (CapabilityStudioStageAcceptanceResultV2Builder builder : List.of(first, second)) {
            builder.recordEvidence(evidence("e-2", '2'));
            builder.recordEvidence(evidence("e-1", '1'));
            builder.recordCheck(new CapabilityStudioStageAcceptanceResultV2Builder.Check(
                    "AC-STD-02", CapabilityStudioStageAcceptanceResultV2Builder.CheckStatus.PASS,
                    List.of("e-2", "e-1")));
        }

        ObjectNode firstResult = first.build();
        ObjectNode secondResult = second.build();

        assertThat(first.buildBytes()).isEqualTo(second.buildBytes());
        assertThat(firstResult.path("evidenceClosureFingerprint").textValue())
                .isEqualTo(secondResult.path("evidenceClosureFingerprint").textValue());
        assertThat(arrayTexts(firstResult.path("acceptanceChecks").path(1).path("evidenceIds")))
                .containsExactly("e-1", "e-2");
        assertThat(firstResult.path("evidenceRefs").findValuesAsText("evidenceId"))
                .containsExactly("e-1", "e-2");
    }

    @Test
    void emitsAllNineChecksExactlyOnceInCanonicalOrder() {
        CapabilityStudioStageAcceptanceResultV2Builder builder = completedBuilder();
        for (String checkId : List.of(
                "AC-STD-08", "AC-STD-07", "AC-STD-05", "AC-STD-04", "AC-STD-03", "AC-STD-02")) {
            String evidenceId = checkId.toLowerCase();
            builder.recordEvidence(evidence(evidenceId, checkId.charAt(checkId.length() - 1)));
            builder.recordCheck(new CapabilityStudioStageAcceptanceResultV2Builder.Check(
                    checkId, CapabilityStudioStageAcceptanceResultV2Builder.CheckStatus.PASS,
                    List.of(evidenceId)));
        }

        ObjectNode result = builder.build();

        assertThat(result.path("acceptanceChecks").findValuesAsText("checkId"))
                .containsExactly("AC-STD-01", "AC-STD-02", "AC-STD-03", "AC-STD-04",
                        "AC-STD-05", "AC-STD-06", "AC-STD-07", "AC-STD-08", "AC-STD-09");
        assertThat(result.path("acceptanceChecks").findValuesAsText("status"))
                .containsExactly("BLOCKED", "PASS", "PASS", "PASS", "PASS", "BLOCKED",
                        "PASS", "PASS", "BLOCKED");
        assertThat(result.path("diagnostics").findValuesAsText("code"))
                .containsExactly("ENVIRONMENT_ATTESTATION_UNAVAILABLE",
                        "DEPLOYMENT_EGRESS_UNAVAILABLE", "SIGNOFFS_UNAVAILABLE",
                        "ACCEPTANCE_CHECK_BLOCKED");
    }

    @Test
    void missingLocalChecksBecomeNotRun() {
        ObjectNode result = completedBuilder().build();

        assertThat(result.path("acceptanceChecks").findValuesAsText("status"))
                .containsExactly("BLOCKED", "NOT_RUN", "NOT_RUN", "NOT_RUN", "NOT_RUN",
                        "BLOCKED", "NOT_RUN", "NOT_RUN", "BLOCKED");
        assertThat(result.path("diagnostics").findValuesAsText("code"))
                .contains("ACCEPTANCE_CHECK_NOT_RUN");
    }

    @Test
    void rejectsUnresolvedEvidenceBeforeBuild() {
        CapabilityStudioStageAcceptanceResultV2Builder builder = completedBuilder()
                .recordCheck(new CapabilityStudioStageAcceptanceResultV2Builder.Check(
                        "AC-STD-02", CapabilityStudioStageAcceptanceResultV2Builder.CheckStatus.FAIL,
                        List.of("missing")));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void rejectsDuplicateEvidenceAndChecks() {
        CapabilityStudioStageAcceptanceResultV2Builder builder = completedBuilder();
        builder.recordEvidence(evidence("e-1", '1'));

        assertThatThrownBy(() -> builder.recordEvidence(evidence("e-1", '1')))
                .isInstanceOf(IllegalStateException.class);

        builder.recordCheck(CapabilityStudioStageAcceptanceResultV2Builder.Check.of(
                "AC-STD-02", CapabilityStudioStageAcceptanceResultV2Builder.CheckStatus.NOT_RUN));
        assertThatThrownBy(() -> builder.recordCheck(
                CapabilityStudioStageAcceptanceResultV2Builder.Check.of(
                        "AC-STD-02", CapabilityStudioStageAcceptanceResultV2Builder.CheckStatus.NOT_RUN)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAllCallerSuppliedAuthorityChecks() {
        CapabilityStudioStageAcceptanceResultV2Builder builder = completedBuilder();

        for (String checkId : List.of("AC-STD-01", "AC-STD-06", "AC-STD-09")) {
            CapabilityStudioStageAcceptanceResultV2Builder.Check check =
                    CapabilityStudioStageAcceptanceResultV2Builder.Check.of(
                            checkId,
                            CapabilityStudioStageAcceptanceResultV2Builder.CheckStatus.BLOCKED);
            assertThatThrownBy(() -> builder.recordCheck(check))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("authority-derived");
        }
    }

    @Test
    void dirtyOrUnknownCandidateIsAClosedLocalFailure() {
        ObjectNode dirty = completedBuilder(SourceTreeStatus.DIRTY).build();
        ObjectNode unknown = completedBuilder(SourceTreeStatus.UNKNOWN).build();

        for (ObjectNode result : List.of(dirty, unknown)) {
            assertThat(result.path("status").textValue()).isEqualTo("FAIL");
            assertThat(check(result, "AC-STD-01").path("status").textValue())
                    .isEqualTo("FAIL");
            assertThat(VERIFIER.verify(result, DECIDED_AT).verified()).isTrue();
        }
        assertThatThrownBy(() -> notStartedBuilder(SourceTreeStatus.DIRTY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidTimesReferencesAndFingerprints() {
        assertThatThrownBy(() -> CapabilityStudioStageAcceptanceResultV2Builder.ExecutionWindow
                .completed(COMPLETED_AT, STARTED_AT, DECIDED_AT.toString()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapabilityStudioStageAcceptanceResultV2Builder.ExactRef(
                "bad ref", fingerprint('a'))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapabilityStudioStageAcceptanceResultV2Builder.EvidenceRef(
                "e-1", "e-1", "sha256:bad"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapabilityStudioStageAcceptanceResultV2Builder.CandidateBuild(
                "build-1", "1", "bad", SourceTreeStatus.CLEAN, fingerprint('a')))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emitsNoAuthorityProjectionOrPayloadFields() {
        ObjectNode result = completedBuilder().build();

        assertThat(result.path("environmentAttestation").isNull()).isTrue();
        assertThat(result.path("deploymentEgressObservation").isNull()).isTrue();
        assertThat(result.path("signoffs").isEmpty()).isTrue();
        assertThat(result.findValue("issuer")).isNull();
        assertThat(result.findValue("signatureRef")).isNull();
        assertThat(result.findValue("payload")).isNull();
        assertThat(result.toString()).doesNotContain("PASS", "issuer", "signature", "payload");
    }

    @Test
    void protectsInputListsAndReturnedTreesFromMutation() {
        List<String> evidenceIds = new ArrayList<>(List.of("e-2", "e-1"));
        CapabilityStudioStageAcceptanceResultV2Builder.Check check =
                new CapabilityStudioStageAcceptanceResultV2Builder.Check(
                        "AC-STD-02", CapabilityStudioStageAcceptanceResultV2Builder.CheckStatus.PASS,
                        evidenceIds);
        evidenceIds.clear();

        CapabilityStudioStageAcceptanceResultV2Builder builder = completedBuilder()
                .recordEvidence(evidence("e-1", '1'))
                .recordEvidence(evidence("e-2", '2'))
                .recordCheck(check);
        ObjectNode first = builder.build();
        first.put("status", "FAIL");
        ((ArrayNode) first.path("acceptanceChecks").path(1).path("evidenceIds")).removeAll();

        ObjectNode second = builder.build();
        assertThat(second.path("status").textValue()).isEqualTo("BLOCKED");
        assertThat(arrayTexts(second.path("acceptanceChecks").path(1).path("evidenceIds")))
                .containsExactly("e-1", "e-2");
    }

    private static CapabilityStudioStageAcceptanceResultV2Builder completedBuilder() {
        return completedBuilder(SourceTreeStatus.CLEAN);
    }

    private static CapabilityStudioStageAcceptanceResultV2Builder completedBuilder(
            SourceTreeStatus sourceTreeStatus) {
        return new CapabilityStudioStageAcceptanceResultV2Builder(
                "SAR-builder-test", 1, "contract-1", "1",
                new CapabilityStudioStageAcceptanceResultV2Builder.CandidateBuild(
                        "build-1", "1", "abcdef1", sourceTreeStatus, fingerprint('b')),
                new CapabilityStudioStageAcceptanceResultV2Builder.ExactRef(
                        "baseline-1", fingerprint('c')),
                new CapabilityStudioStageAcceptanceResultV2Builder.ExactRef(
                        "demo-1", fingerprint('d')),
                fingerprint('e'), fingerprint('f'),
                CapabilityStudioStageAcceptanceResultV2Builder.ExecutionWindow.completed(
                        STARTED_AT, COMPLETED_AT, DECIDED_AT.toString()));
    }

    private static CapabilityStudioStageAcceptanceResultV2Builder notStartedBuilder() {
        return notStartedBuilder(SourceTreeStatus.CLEAN);
    }

    private static CapabilityStudioStageAcceptanceResultV2Builder notStartedBuilder(
            SourceTreeStatus sourceTreeStatus) {
        return new CapabilityStudioStageAcceptanceResultV2Builder(
                "SAR-builder-test", 1, "contract-1", "1",
                new CapabilityStudioStageAcceptanceResultV2Builder.CandidateBuild(
                        "build-1", "1", "abcdef1", sourceTreeStatus, fingerprint('b')),
                new CapabilityStudioStageAcceptanceResultV2Builder.ExactRef(
                        "baseline-1", fingerprint('c')),
                new CapabilityStudioStageAcceptanceResultV2Builder.ExactRef(
                        "demo-1", fingerprint('d')),
                fingerprint('e'), fingerprint('f'),
                CapabilityStudioStageAcceptanceResultV2Builder.ExecutionWindow.notStarted(
                        DECIDED_AT.toString()));
    }

    private static CapabilityStudioStageAcceptanceResultV2Builder.EvidenceRef evidence(
            String id, char seed) {
        return new CapabilityStudioStageAcceptanceResultV2Builder.EvidenceRef(
                id, "evidence/" + id, fingerprint(seed));
    }

    private static ObjectNode check(ObjectNode result, String checkId) {
        for (JsonNode value : result.path("acceptanceChecks")) {
            if (checkId.equals(value.path("checkId").textValue())) {
                return (ObjectNode) value;
            }
        }
        throw new AssertionError("missing check " + checkId);
    }

    private static List<String> arrayTexts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.textValue()));
        return values;
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }
}
