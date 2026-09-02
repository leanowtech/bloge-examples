package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulationRunV2StoreTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    @Test
    void claimCompletionReplayConflictAndScopedReadAreExact() {
        InMemorySimulationRunV2Store store = new InMemorySimulationRunV2Store();
        Instant time = Instant.parse("2030-01-01T00:00:00Z");
        SimulationRunV2 run = run("sim-v2-1", time);

        assertThat(store.claim(SCOPE, "key", FINGERPRINT, () -> run.runId(), time))
                .isEqualTo(new SimulationRunV2Store.Claim.Acquired(run.runId()));
        assertThat(store.claim(SCOPE, "key", FINGERPRINT, () -> "unused", time))
                .isEqualTo(new SimulationRunV2Store.Claim.Busy(run.runId()));
        assertThat(store.claim(SCOPE, "key", "sha256:" + "b".repeat(64), () -> "unused", time))
                .isInstanceOf(SimulationRunV2Store.Claim.Conflict.class);
        store.complete(SCOPE, "key", FINGERPRINT, run);
        assertThat(store.claim(SCOPE, "key", FINGERPRINT, () -> "unused", time))
                .isEqualTo(new SimulationRunV2Store.Claim.Replay(run));
        assertThat(store.find(SCOPE, run.runId())).contains(run);
        assertThat(store.find(new AuthoringScope("other", "project", "dev"), run.runId())).isEmpty();
    }

    @Test
    void storedOutputIsDefensiveAndLogsRemainPayloadFree() {
        InMemorySimulationRunV2Store store = new InMemorySimulationRunV2Store();
        Instant time = Instant.parse("2030-01-01T00:00:00Z");
        SimulationRunV2 run = run("sim-v2-2", time);
        store.claim(SCOPE, "key", FINGERPRINT, run::runId, time);
        store.complete(SCOPE, "key", FINGERPRINT, run);

        ((com.fasterxml.jackson.databind.node.ObjectNode) run.output())
                .put("credential", "must-not-leak");

        SimulationRunV2 stored = store.find(SCOPE, run.runId()).orElseThrow();
        assertThat(stored.output().has("credential")).isFalse();
        assertThat(stored.toString()).doesNotContain("VIP", "must-not-leak");
    }

    @Test
    void completionRejectsEvidenceFromAnotherRequestBeforePublishingIt() {
        InMemorySimulationRunV2Store store = new InMemorySimulationRunV2Store();
        Instant time = Instant.parse("2030-01-01T00:00:00Z");
        SimulationRunV2 run = run("sim-v2-3", time);
        store.claim(SCOPE, "key", "sha256:" + "b".repeat(64), run::runId, time);

        assertThatThrownBy(() -> store.complete(
                SCOPE, "key", "sha256:" + "b".repeat(64), run))
                .isInstanceOf(SimulationFailure.class)
                .extracting(value -> ((SimulationFailure) value).code())
                .isEqualTo(SimulationFailure.Code.INTEGRITY);
        assertThat(store.find(SCOPE, run.runId())).isEmpty();
    }

    static SimulationRunV2 run(String id, Instant time) {
        var subject = new ExactFixtureSubjectRefV2.ApiResource(
                "customer.get-profile", 3, "sha256:" + "c".repeat(64));
        return new SimulationRunV2(SimulationRunV2.SCHEMA_VERSION, id,
                SimulationRunV2.Status.SUCCEEDED, subject, FINGERPRINT,
                "sha256:" + "d".repeat(64), new ObjectMapper().createObjectNode().put("tier", "VIP"),
                List.of(new SimulationRunV2.Invocation(id + ":subject:1", null,
                        new SimulationCommandV2.FixtureTarget.Subject(), subject,
                        SimulationRunV2.InvocationStatus.COMPLETED, SimulationRunV2.Execution.MOCKED,
                        SimulationRunV2.MatchedBy.EXACT_CASE,
                        new SimulationRunV2.FixtureCase("profile-fixtures", 4,
                                "sha256:" + "e".repeat(64), "vip"),
                        SimulationRunV2.Behavior.RETURN, SimulationRunV2.Fidelity.OUTPUT_LEVEL,
                        SimulationRunV2.Provenance.PINNED_PRIVATE, null,
                        "sha256:" + "f".repeat(64), "sha256:" + "1".repeat(64),
                        new SimulationRun.Egress.Fixture(false))),
                new SimulationRunV2.Verdicts(SimulationRunV2.ExecutionVerdict.PASSED,
                        SimulationRunV2.AssertionsVerdict.NOT_CHECKED,
                        SimulationRunV2.ContractVerdict.VALID,
                        SimulationRunV2.GovernanceVerdict.NOT_CHECKED,
                        SimulationRunV2.AggregateVerdict.NOT_READY),
                List.of(), time, time.plusSeconds(1));
    }
}
