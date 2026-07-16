package com.leanowtech.bloge.gateway.testing.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurableTestExecutionCheckpointTest {

    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private static final String SHA_B = "sha256:" + "b".repeat(64);
    private static final String SHA_C = "sha256:" + "c".repeat(64);
    private static final String SHA_D = "sha256:" + "d".repeat(64);

    private ObjectMapper mapper;
    private DurableTestExecutionCheckpointIntegrity integrity;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        integrity = new DurableTestExecutionCheckpointIntegrity(mapper);
    }

    @Test
    void sealsAndVerifiesTheCompletePayloadFreeRecoveryClosure() {
        DurableTestExecutionCheckpoint checkpoint = integrity.seal(checkpoint("test", 0));

        assertThat(checkpoint.fixtureConsumptionState().stateFingerprint())
                .matches("sha256:[a-f0-9]{64}");
        assertThat(checkpoint.checkpointFingerprint()).matches("sha256:[a-f0-9]{64}");
        assertThat(checkpoint.dependencies().plan().replayDependencies())
                .extracting(EffectiveExecutionPlan.ReplayDependency::replayRef)
                .containsExactly("bloge-replay:payload-a@2#" + SHA_D);
        assertThat(checkpoint.fingerprintMaterial()).doesNotContainKey("checkpointFingerprint");
        integrity.requireValid(checkpoint);
    }

    @Test
    void currentProtocolRequiresAnExactTargetLocatorBoundToThePlan() {
        DurableTestExecutionCheckpoint checkpoint = checkpoint("test", 0);
        DurableTestExecutionCheckpoint.ControlDependencies dependencies = checkpoint.dependencies();

        assertThatThrownBy(() -> new DurableTestExecutionCheckpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION,
                checkpoint.scope(), checkpoint.runId(), checkpoint.engineExecutionId(),
                new DurableTestExecutionCheckpoint.ControlDependencies(
                        dependencies.plan(), dependencies.fixture(), dependencies.sideEffectPolicy(),
                        dependencies.identitySnapshot(), null),
                checkpoint.fixtureConsumptionState(), checkpoint.executionServiceState(),
                checkpoint.engineState(), checkpoint.lifecycle(), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target locator");

        assertThatThrownBy(() -> new DurableTestExecutionCheckpoint.ControlDependencies(
                dependencies.plan(), dependencies.fixture(), dependencies.sideEffectPolicy(),
                dependencies.identitySnapshot(),
                new DurableTestExecutionCheckpoint.ExecutionTargetRef("GRAPH", "credit-score", SHA_A)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target fingerprint");
    }

    @Test
    void targetKindMustMatchTheServerAuthorizedExecutionPurpose() {
        DurableTestExecutionCheckpoint checkpoint = checkpoint("test", 0);
        DurableTestExecutionCheckpoint.ControlDependencies dependencies = checkpoint.dependencies();

        assertThatThrownBy(() -> new DurableTestExecutionCheckpoint.ControlDependencies(
                dependencies.plan(), dependencies.fixture(), dependencies.sideEffectPolicy(),
                dependencies.identitySnapshot(),
                new DurableTestExecutionCheckpoint.ExecutionTargetRef(
                        "OPERATOR", "credit-score", dependencies.plan().targetFingerprint())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("execution purpose");
    }

    @Test
    void legacyV1WithoutTargetLocatorRemainsCanonicallyReadable() throws Exception {
        DurableTestExecutionCheckpoint current = checkpoint("test", 0);
        DurableTestExecutionCheckpoint legacy = integrity.seal(new DurableTestExecutionCheckpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION_V1,
                current.scope(), current.runId(), current.engineExecutionId(),
                new DurableTestExecutionCheckpoint.ControlDependencies(
                        current.dependencies().plan(), current.dependencies().fixture(),
                        current.dependencies().sideEffectPolicy(),
                        current.dependencies().identitySnapshot()),
                current.fixtureConsumptionState(), current.executionServiceState(),
                current.engineState(), current.lifecycle(), ""));

        String json = mapper.writeValueAsString(legacy);
        DurableTestExecutionCheckpoint decoded = mapper.readValue(
                json, DurableTestExecutionCheckpoint.class);

        assertThat(mapper.readTree(json).path("dependencies").has("target")).isFalse();
        assertThat(decoded.dependencies().target()).isNull();
        assertThat(decoded.checkpointFingerprint()).isEqualTo(legacy.checkpointFingerprint());
        integrity.requireValid(decoded);
    }

    @Test
    void legacyVersionCannotSmuggleTheV2TargetShape() {
        DurableTestExecutionCheckpoint current = checkpoint("test", 0);

        assertThatThrownBy(() -> new DurableTestExecutionCheckpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION_V1,
                current.scope(), current.runId(), current.engineExecutionId(),
                current.dependencies(), current.fixtureConsumptionState(),
                current.executionServiceState(), current.engineState(), current.lifecycle(), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v1 checkpoint cannot contain");
    }

    @Test
    void rejectsTamperingInNestedFixtureOrProviderState() {
        DurableTestExecutionCheckpoint sealed = integrity.seal(checkpoint("test", 0));
        FixtureConsumptionStateSnapshot tamperedConsumption = new FixtureConsumptionStateSnapshot(
                FixtureConsumptionStateSnapshot.SCHEMA_VERSION,
                Map.of("rule-a", 2L), sealed.fixtureConsumptionState().siteOccurrenceCursors(),
                sealed.fixtureConsumptionState().graphOccurrenceCursors(),
                sealed.fixtureConsumptionState().stateFingerprint());
        DurableTestExecutionCheckpoint tampered = new DurableTestExecutionCheckpoint(
                sealed.schemaVersion(), sealed.scope(), sealed.runId(), sealed.engineExecutionId(),
                sealed.dependencies(), tamperedConsumption, sealed.executionServiceState(),
                sealed.engineState(), sealed.lifecycle(), sealed.checkpointFingerprint());

        assertThatThrownBy(() -> integrity.requireValid(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fixture-consumption state fingerprint");

        ExecutionServiceStateSnapshot provider = sealed.executionServiceState();
        ExecutionServiceStateSnapshot tamperedProvider = new ExecutionServiceStateSnapshot(
                provider.schemaVersion(), provider.planFingerprint(), provider.bindingSetFingerprint(),
                provider.logicalTime().plusSeconds(1), provider.randomScopeCursors(),
                provider.uuidScopeCursors(), provider.usages(), provider.restorable(),
                provider.restoreGaps(), provider.snapshotFingerprint());
        DurableTestExecutionCheckpoint providerTampered = new DurableTestExecutionCheckpoint(
                sealed.schemaVersion(), sealed.scope(), sealed.runId(), sealed.engineExecutionId(),
                sealed.dependencies(), sealed.fixtureConsumptionState(), tamperedProvider,
                sealed.engineState(), sealed.lifecycle(), sealed.checkpointFingerprint());
        assertThatThrownBy(() -> integrity.requireValid(providerTampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("execution-service state fingerprint");
    }

    @Test
    void rejectsProductionScopeRawCorrelationKeysAndPlanStateDrift() {
        assertThatThrownBy(() -> checkpoint("production", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("test or staging");
        assertThatThrownBy(() -> new FixtureConsumptionStateSnapshot(
                FixtureConsumptionStateSnapshot.SCHEMA_VERSION, Map.of(),
                Map.of("customer@example.com", 1L), Map.of(), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical SHA-256");

        DurableTestExecutionCheckpoint checkpoint = checkpoint("test", 0);
        ExecutionServiceStateSnapshot provider = checkpoint.executionServiceState();
        ExecutionServiceStateSnapshot wrongPlan = new ExecutionServiceStateSnapshot(
                provider.schemaVersion(), SHA_D, provider.bindingSetFingerprint(), provider.logicalTime(),
                provider.randomScopeCursors(), provider.uuidScopeCursors(), provider.usages(),
                provider.restorable(), provider.restoreGaps(), provider.snapshotFingerprint());
        assertThatThrownBy(() -> new DurableTestExecutionCheckpoint(
                checkpoint.schemaVersion(), checkpoint.scope(), checkpoint.runId(),
                checkpoint.engineExecutionId(), checkpoint.dependencies(),
                checkpoint.fixtureConsumptionState(), wrongPlan, checkpoint.engineState(),
                checkpoint.lifecycle(), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same effective plan");
    }

    @Test
    void normalizesLifecycleToPortableDatabasePrecision() {
        Instant nanos = Instant.parse("2026-07-16T08:00:00.123456789Z");
        DurableTestExecutionCheckpoint.Lifecycle lifecycle =
                new DurableTestExecutionCheckpoint.Lifecycle(
                        DurableTestExecutionCheckpoint.Status.ACTIVE, "instance-a", 1, 0,
                        nanos, nanos.plusNanos(1000), nanos.plusSeconds(30));

        assertThat(lifecycle.createdAt()).isEqualTo(
                Instant.parse("2026-07-16T08:00:00.123456Z"));
        assertThat(lifecycle.updatedAt()).isEqualTo(
                Instant.parse("2026-07-16T08:00:00.123457Z"));
        assertThat(lifecycle.leaseExpiresAt()).isEqualTo(
                Instant.parse("2026-07-16T08:00:30.123456Z"));
    }

    private DurableTestExecutionCheckpoint checkpoint(String environment, long revision) {
        Instant now = Instant.parse("2026-07-16T08:00:00Z").plusSeconds(revision);
        String replayRef = "bloge-replay:payload-a@2#" + SHA_D;
        EffectiveExecutionPlan plan = new EffectiveExecutionPlan(
                EffectiveExecutionPlan.SCHEMA_VERSION, "plan-a", SHA_A,
                "GRAPH_CONTRACT_TEST", SHA_B, SHA_C, List.of(),
                List.of(new EffectiveExecutionPlan.ReplayDependency(
                        replayRef, "payload-a", 2, SHA_D, "INTERNAL", "source-run",
                        "fetch", 1, SHA_A, SHA_B, now.plusSeconds(3600), true, List.of())),
                List.of(), Map.of("unmatchedExternalEffect", "DENY"), List.of());
        ExecutionServiceStateSnapshot provider = providerState(now);
        FixtureConsumptionStateSnapshot consumption = new FixtureConsumptionStateSnapshot(
                FixtureConsumptionStateSnapshot.SCHEMA_VERSION,
                Map.of("rule-a", 1L), Map.of(SHA_A, 2L), Map.of(SHA_B, 1L), "");
        return new DurableTestExecutionCheckpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION,
                new DurableTestExecutionCheckpoint.Scope(
                        "tenant-a", "org-a", "project-a", environment, "runner"),
                "run-a", "engine-a",
                new DurableTestExecutionCheckpoint.ControlDependencies(
                        plan, new DurableTestExecutionCheckpoint.ExactFixtureRef(
                        "fixture-a", 3, SHA_C), "DENY_REAL",
                        new DurableTestExecutionCheckpoint.AuthoritySnapshot("FAIL_CLOSED", SHA_D),
                        new DurableTestExecutionCheckpoint.ExecutionTargetRef(
                                "GRAPH", "credit-score", SHA_B)),
                consumption, provider,
                new DurableTestExecutionCheckpoint.EngineState(
                        "checkpoint-a-" + revision, "fetch", "NODE_BOUNDARY",
                        revision + 1, revision, ProtocolFingerprint.ofText("engine-" + revision)),
                new DurableTestExecutionCheckpoint.Lifecycle(
                        DurableTestExecutionCheckpoint.Status.SUSPENDED, "instance-a", 1,
                        revision, now, now, now.plusSeconds(30)), "");
    }

    private ExecutionServiceStateSnapshot providerState(Instant now) {
        ExecutionServiceStateSnapshot unsealed = new ExecutionServiceStateSnapshot(
                ExecutionServiceStateSnapshot.SCHEMA_VERSION, SHA_A, SHA_B, now,
                Map.of(SHA_C, 1L), Map.of(), List.of(), true, List.of(), SHA_D);
        return new ExecutionServiceStateSnapshot(unsealed.schemaVersion(), unsealed.planFingerprint(),
                unsealed.bindingSetFingerprint(), unsealed.logicalTime(), unsealed.randomScopeCursors(),
                unsealed.uuidScopeCursors(), unsealed.usages(), unsealed.restorable(),
                unsealed.restoreGaps(), ProtocolFingerprint.of(mapper, unsealed.fingerprintMaterial()));
    }
}
