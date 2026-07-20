package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureExecutionServices;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GovernedExecutionServicesTest {

    private static final String TARGET = "sha256:" + "a".repeat(64);
    private static final String PLAN = "sha256:" + "b".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void sameSeedAndScopeSequenceReproducesRandomAndUuidValuesAcrossRuns() {
        GovernedExecutionServices first = prepared(42L);
        GovernedExecutionServices second = prepared(42L);
        GovernedExecutionServices changed = prepared(43L);

        List<Long> firstRandom = List.of(
                first.services().randomSource().nextLong("random@4:7#node=price"),
                first.services().randomSource().nextLong("random@4:7#node=price"));
        List<Long> secondRandom = List.of(
                second.services().randomSource().nextLong("random@4:7#node=price"),
                second.services().randomSource().nextLong("random@4:7#node=price"));
        List<String> firstIds = List.of(
                first.services().idGenerator().nextId("uuid@5:9#node=price"),
                first.services().idGenerator().nextId("uuid@5:9#node=price"));
        List<String> secondIds = List.of(
                second.services().idGenerator().nextId("uuid@5:9#node=price"),
                second.services().idGenerator().nextId("uuid@5:9#node=price"));

        assertThat(secondRandom).isEqualTo(firstRandom);
        assertThat(secondIds).isEqualTo(firstIds);
        assertThat(changed.services().randomSource().nextLong("random@4:7#node=price"))
                .isNotEqualTo(firstRandom.getFirst());
        assertThat(changed.services().idGenerator().nextId("uuid@5:9#node=price"))
                .isNotEqualTo(firstIds.getFirst());
        assertThat(first.usageSnapshot()).extracting(GovernedExecutionServices.ExecutionServiceUsage::service)
                .containsExactly("RANDOM", "UUID");
        assertThat(first.certificationGaps()).isEmpty();
    }

    @Test
    void servicePlanProjectionIsPayloadFreeAndLogicalClockAdvancesWithoutWallTime() throws Exception {
        GovernedExecutionServices services = prepared(42L);

        assertThat(services.services().timeSource().now())
                .isEqualTo(Instant.parse("2026-07-15T00:00:00Z"));
        services.services().timeSource().sleep(Duration.ofSeconds(5));
        assertThat(services.services().timeSource().now())
                .isEqualTo(Instant.parse("2026-07-15T00:00:05Z"));
        assertThat(services.logicalTimeObservation()).satisfies(observation -> {
            assertThat(observation.origin()).isEqualTo(Instant.parse("2026-07-15T00:00:00Z"));
            assertThat(observation.current()).isEqualTo(Instant.parse("2026-07-15T00:00:05Z"));
            assertThat(observation.elapsed()).isEqualTo(Duration.ofSeconds(5));
        });
        assertThat(services.bindings()).extracting(
                        EffectiveExecutionPlan.ExecutionServiceBinding::service)
                .containsExactly("TIME", "RANDOM", "UUID", "IDENTITY", "FEATURE_FLAG", "SECRET");
        assertThat(services.bindings()).filteredOn(binding -> binding.service().equals("TIME"))
                .singleElement().satisfies(binding -> {
                    assertThat(binding.mode()).isEqualTo("LOGICAL_ADVANCING");
                    assertThat(binding.deterministic()).isTrue();
                });

        String projection = mapper.writeValueAsString(services.bindings());
        assertThat(projection)
                .doesNotContain("randomSeed", "logicalClock", "2026-07-15T00:00:00Z")
                .contains("configurationFingerprint");
    }

    @Test
    void unsupportedAmbientAuthoritiesFailClosedAndRemainAuditable() {
        GovernedExecutionServices services = prepared(42L);

        assertThatThrownBy(() -> services.services().identityProvider().resolve("subject"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No governed identity fixture value");
        assertThatThrownBy(() -> services.services().featureFlagProvider().enabled("new-price"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No governed feature-flag fixture decision");
        assertThatThrownBy(() -> services.services().secretProvider().resolve("payment-key"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("No SecretProvider configured");

        assertThat(services.usageSnapshot())
                .extracting(GovernedExecutionServices.ExecutionServiceUsage::service)
                .containsExactly("FEATURE_FLAG", "IDENTITY", "SECRET");
        assertThat(services.certificationGaps())
                .containsExactlyInAnyOrder(
                        "IDENTITY has no governed test authority configured.",
                        "FEATURE_FLAG has no governed test authority configured.",
                        "SECRET has no governed test authority configured.");
    }

    @Test
    void fixtureIdentityAndFlagsResolveExactlyWithoutLeakingValuesIntoPlanOrCheckpoint() throws Exception {
        FixtureBundle fixture = controlledFixture("tenant-sensitive-C-1001", true);
        GovernedExecutionServices services = GovernedExecutionServices.prepare(
                mapper, fixture, inventory()).bindToPlan(PLAN);

        assertThat(services.services().identityProvider().resolve("tenant"))
                .isEqualTo("tenant-sensitive-C-1001");
        assertThat(services.services().identityProvider().resolve("riskLevel")).isEqualTo(7);
        assertThat(services.services().featureFlagProvider().enabled("pricing-v2")).isTrue();
        assertThatThrownBy(() -> services.services().identityProvider().resolve("missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("missing");
        assertThatThrownBy(() -> services.services().featureFlagProvider().enabled("missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("missing");
        assertThatThrownBy(() -> services.services().secretProvider().resolve("payment-key"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("No SecretProvider configured");

        assertThat(services.bindings()).filteredOn(binding -> binding.service().equals("IDENTITY"))
                .singleElement().satisfies(binding -> {
                    assertThat(binding.mode()).isEqualTo("FIXTURE_MAP");
                    assertThat(binding.available()).isTrue();
                    assertThat(binding.deterministic()).isTrue();
                    assertThat(binding.certificationGaps()).isEmpty();
                });
        assertThat(services.bindings()).filteredOn(
                        binding -> binding.service().equals("FEATURE_FLAG"))
                .singleElement().satisfies(binding -> {
                    assertThat(binding.mode()).isEqualTo("FIXTURE_MAP");
                    assertThat(binding.available()).isTrue();
                    assertThat(binding.certificationGaps()).isEmpty();
                });
        assertThat(services.certificationGaps())
                .containsExactly("SECRET has no governed test authority configured.");

        ExecutionServiceStateSnapshot snapshot = services.snapshotState();
        GovernedExecutionServices restored = GovernedExecutionServices.restore(
                mapper, fixture, inventory(), PLAN, snapshot);
        assertThat(restored.services().identityProvider().resolve("tenant"))
                .isEqualTo("tenant-sensitive-C-1001");
        assertThat(restored.services().featureFlagProvider().enabled("pricing-v2")).isTrue();

        String projection = mapper.writeValueAsString(Map.of(
                "bindings", services.bindings(), "snapshot", snapshot,
                "usage", services.usageSnapshot()));
        assertThat(projection)
                .doesNotContain("tenant-sensitive-C-1001", "riskLevel", "pricing-v2", "payment-key")
                .contains("configurationFingerprint", "providerScopeFingerprints");
        assertThatThrownBy(() -> GovernedExecutionServices.restore(
                mapper, controlledFixture("changed-tenant", true), inventory(), PLAN, snapshot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("binding");
    }

    @Test
    void externalTestSecretsResolveAtRunScopeWithoutEnteringPlanOrCheckpoint() throws Exception {
        FixtureBundle fixture = new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "fixture", 1,
                TARGET, "RESTRICTED", Instant.parse("2026-07-15T00:00:00Z"), 42L,
                List.of(), List.of(), Map.of(FixtureExecutionServices.METADATA_KEY, Map.of(
                        "schemaVersion", FixtureExecutionServices.SCHEMA_VERSION_V2,
                        "identityAttributes", Map.of(), "featureFlags", Map.of(),
                        "secretRefs", Map.of(
                                "payment-key", "vault://test/payments/key@v3"))));
        ResolvedTestSecrets resolved = new ResolvedTestSecrets("",
                "sha256:" + "c".repeat(64), "authority-a", "generation-1",
                Instant.parse("2026-07-15T00:00:00Z"),
                Instant.parse("2026-07-15T00:05:00Z"), Map.of("payment-key",
                new ResolvedTestSecrets.Secret("payment-key",
                        "vault://test/payments/key@v3", "version-3",
                        "sha256:" + "d".repeat(64), "run-only-secret")));

        GovernedExecutionServices services = GovernedExecutionServices.prepare(
                mapper, fixture, inventory(), resolved).bindToPlan(PLAN);

        assertThat(services.services().secretProvider().resolve("payment-key"))
                .isEqualTo("run-only-secret");
        assertThat(services.bindings()).filteredOn(binding -> binding.service().equals("SECRET"))
                .singleElement().satisfies(binding -> {
                    assertThat(binding.mode()).isEqualTo("EXTERNAL_TEST_AUTHORITY");
                    assertThat(binding.available()).isTrue();
                    assertThat(binding.deterministic()).isTrue();
                    assertThat(binding.certificationGaps()).isEmpty();
                });
        assertThat(mapper.writeValueAsString(services.bindings()))
                .doesNotContain("run-only-secret", "payment-key",
                        "vault://test/payments/key@v3", "version-3");
        assertThat(mapper.writeValueAsString(services.snapshotState()))
                .doesNotContain("run-only-secret", "payment-key");
    }

    @Test
    void snapshotAndRestoreContinueExactLogicalTimeRandomUuidAndUsageState() throws Exception {
        GovernedExecutionServices running = prepared(42L).bindToPlan(PLAN);
        String randomScope = "random@4:7#node=price/customer-C-1001";
        String uuidScope = "uuid@5:9#node=price/customer-C-1001";
        running.services().timeSource().sleep(Duration.ofSeconds(7));
        running.services().randomSource().nextLong(randomScope);
        running.services().randomSource().nextLong(randomScope);
        running.services().idGenerator().nextId(uuidScope);

        ExecutionServiceStateSnapshot snapshot = running.snapshotState();
        assertThat(ProtocolFingerprint.of(mapper, snapshot.fingerprintMaterial()))
                .isEqualTo(snapshot.snapshotFingerprint());
        GovernedExecutionServices restored = GovernedExecutionServices.restore(
                mapper, fixture(42L), inventory(), PLAN, snapshot);

        assertThat(restored.services().timeSource().now())
                .isEqualTo(running.services().timeSource().now())
                .isEqualTo(Instant.parse("2026-07-15T00:00:07Z"));
        assertThat(restored.services().randomSource().nextLong(randomScope))
                .isEqualTo(running.services().randomSource().nextLong(randomScope));
        assertThat(restored.services().idGenerator().nextId(uuidScope))
                .isEqualTo(running.services().idGenerator().nextId(uuidScope));
        assertThat(restored.usageSnapshot()).isEqualTo(running.usageSnapshot());
        assertThat(restored.snapshotState().snapshotFingerprint())
                .isEqualTo(running.snapshotState().snapshotFingerprint());

        String wire = mapper.writeValueAsString(snapshot);
        assertThat(wire)
                .doesNotContain(randomScope, uuidScope, "customer-C-1001", "randomSeed")
                .contains("randomScopeCursors", "uuidScopeCursors", "snapshotFingerprint");
    }

    @Test
    void restoreRejectsTamperingPlanDriftAndProviderConfigurationDrift() {
        ExecutionServiceStateSnapshot snapshot = prepared(42L).bindToPlan(PLAN).snapshotState();
        ExecutionServiceStateSnapshot tampered = new ExecutionServiceStateSnapshot(
                snapshot.schemaVersion(), snapshot.planFingerprint(), snapshot.bindingSetFingerprint(),
                snapshot.logicalTime(), Map.of("sha256:" + "c".repeat(64), 7L),
                snapshot.uuidScopeCursors(), snapshot.usages(), snapshot.restorable(),
                snapshot.restoreGaps(), snapshot.snapshotFingerprint());

        assertThatThrownBy(() -> GovernedExecutionServices.restore(
                mapper, fixture(42L), inventory(), PLAN, tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint");
        assertThatThrownBy(() -> GovernedExecutionServices.restore(
                mapper, fixture(42L), inventory(), "sha256:" + "d".repeat(64), snapshot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plan");
        assertThatThrownBy(() -> GovernedExecutionServices.restore(
                mapper, fixture(43L), inventory(), PLAN, snapshot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("binding");
    }

    @Test
    void snapshotExposesButRefusesToRestoreObservedSystemRandomState() {
        GovernedExecutionServices services = prepared(null).bindToPlan(PLAN);
        services.services().randomSource().nextLong("pricing-decision");

        ExecutionServiceStateSnapshot snapshot = services.snapshotState();

        assertThat(snapshot.restorable()).isFalse();
        assertThat(snapshot.restoreGaps())
                .containsExactly("RANDOM requires fixtureBundle.randomSeed for certification.");
        assertThat(services.stateFingerprint()).isEqualTo(snapshot.snapshotFingerprint());
        assertThatThrownBy(() -> GovernedExecutionServices.restore(
                mapper, fixture(null), inventory(), PLAN, snapshot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-restorable");
    }

    @Test
    void restoreRecomputesPolicyAndRejectsSelfFingerprintingInconsistentCursors() {
        ExecutionServiceStateSnapshot original = prepared(42L).bindToPlan(PLAN).snapshotState();
        String scope = "sha256:" + "c".repeat(64);
        ExecutionServiceStateSnapshot draft = new ExecutionServiceStateSnapshot(
                original.schemaVersion(), original.planFingerprint(), original.bindingSetFingerprint(),
                original.logicalTime(), Map.of(scope, 1L), original.uuidScopeCursors(),
                original.usages(), true, List.of(), "sha256:" + "d".repeat(64));
        ExecutionServiceStateSnapshot forged = new ExecutionServiceStateSnapshot(
                draft.schemaVersion(), draft.planFingerprint(), draft.bindingSetFingerprint(),
                draft.logicalTime(), draft.randomScopeCursors(), draft.uuidScopeCursors(),
                draft.usages(), draft.restorable(), draft.restoreGaps(),
                ProtocolFingerprint.of(mapper, draft.fingerprintMaterial()));

        assertThatThrownBy(() -> GovernedExecutionServices.restore(
                mapper, fixture(42L), inventory(), PLAN, forged))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursors");

        GovernedExecutionServices systemRandom = prepared(null).bindToPlan(PLAN);
        systemRandom.services().randomSource().nextLong("pricing-decision");
        ExecutionServiceStateSnapshot nonRestorable = systemRandom.snapshotState();
        ExecutionServiceStateSnapshot policyDraft = new ExecutionServiceStateSnapshot(
                nonRestorable.schemaVersion(), nonRestorable.planFingerprint(),
                nonRestorable.bindingSetFingerprint(), nonRestorable.logicalTime(),
                nonRestorable.randomScopeCursors(), nonRestorable.uuidScopeCursors(),
                nonRestorable.usages(), true, List.of(), "sha256:" + "e".repeat(64));
        ExecutionServiceStateSnapshot policyForgery = new ExecutionServiceStateSnapshot(
                policyDraft.schemaVersion(), policyDraft.planFingerprint(),
                policyDraft.bindingSetFingerprint(), policyDraft.logicalTime(),
                policyDraft.randomScopeCursors(), policyDraft.uuidScopeCursors(),
                policyDraft.usages(), policyDraft.restorable(), policyDraft.restoreGaps(),
                ProtocolFingerprint.of(mapper, policyDraft.fingerprintMaterial()));

        assertThatThrownBy(() -> GovernedExecutionServices.restore(
                mapper, fixture(null), inventory(), PLAN, policyForgery))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("policy");
    }

    @Test
    void concurrentSnapshotsNeverSplitSequenceCursorFromUsageAudit() throws Exception {
        GovernedExecutionServices services = prepared(42L).bindToPlan(PLAN);
        int workers = 6;
        int callsPerWorker = 250;
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(workers)) {
            var tasks = java.util.stream.IntStream.range(0, workers)
                    .mapToObj(worker -> executor.submit(() -> {
                        start.await();
                        for (int call = 0; call < callsPerWorker; call++) {
                            services.services().randomSource().nextLong("shared-scope");
                        }
                        return null;
                    })).toList();
            start.countDown();

            for (int attempt = 0; attempt < 20; attempt++) {
                ExecutionServiceStateSnapshot snapshot = services.snapshotState();
                long cursorCalls = snapshot.randomScopeCursors().values().stream()
                        .mapToLong(Long::longValue).sum();
                long auditedCalls = snapshot.usages().stream()
                        .filter(usage -> usage.service().equals("RANDOM"))
                        .mapToLong(ExecutionServiceStateSnapshot.UsageState::providerCalls)
                        .sum();
                assertThat(auditedCalls).isEqualTo(cursorCalls);
            }
            for (var task : tasks) {
                task.get(5, TimeUnit.SECONDS);
            }
        }

        ExecutionServiceStateSnapshot terminal = services.snapshotState();
        assertThat(terminal.randomScopeCursors().values()).containsExactly((long) workers * callsPerWorker);
        assertThat(terminal.usages()).filteredOn(usage -> usage.service().equals("RANDOM"))
                .singleElement().extracting(ExecutionServiceStateSnapshot.UsageState::providerCalls)
                .isEqualTo((long) workers * callsPerWorker);
    }

    @Test
    void providerScopeCardinalityFailsBeforeUsageOrCursorCanGrowPastTheProtocolBound() {
        GovernedExecutionServices services = prepared(42L).bindToPlan(PLAN);
        for (int scope = 0; scope < 10_000; scope++) {
            services.services().randomSource().nextLong("scope-" + scope);
        }

        assertThatThrownBy(() -> services.services().randomSource().nextLong("scope-overflow"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider scopes");

        ExecutionServiceStateSnapshot snapshot = services.snapshotState();
        assertThat(snapshot.randomScopeCursors()).hasSize(10_000);
        assertThat(snapshot.usages()).filteredOn(usage -> usage.service().equals("RANDOM"))
                .singleElement().satisfies(usage -> {
                    assertThat(usage.providerCalls()).isEqualTo(10_000);
                    assertThat(usage.providerScopeFingerprints()).hasSize(10_000);
                });
    }

    private GovernedExecutionServices prepared(Long seed) {
        return GovernedExecutionServices.prepare(mapper, fixture(seed), inventory());
    }

    private FixtureBundle fixture(Long seed) {
        return new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "fixture", 1,
                TARGET, "INTERNAL", Instant.parse("2026-07-15T00:00:00Z"), seed,
                List.of(), List.of(), Map.of());
    }

    private FixtureBundle controlledFixture(String tenant, boolean pricingV2) {
        return new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "fixture", 1,
                TARGET, "INTERNAL", Instant.parse("2026-07-15T00:00:00Z"), 42L,
                List.of(), List.of(), Map.of(FixtureExecutionServices.METADATA_KEY, Map.of(
                        "schemaVersion", FixtureExecutionServices.SCHEMA_VERSION,
                        "identityAttributes", Map.of("tenant", tenant, "riskLevel", 7),
                        "featureFlags", Map.of("pricing-v2", pricingV2))));
    }

    private InvocationInventory inventory() {
        return new InvocationInventory(List.of(), Map.of(), Map.of());
    }
}
