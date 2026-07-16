package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GovernedExecutionServicesTest {

    private static final String TARGET = "sha256:" + "a".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

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
                .hasMessageContaining("No IdentityProvider configured");
        assertThatThrownBy(() -> services.services().featureFlagProvider().enabled("new-price"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No FeatureFlagProvider configured");
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

    private GovernedExecutionServices prepared(Long seed) {
        FixtureBundle fixture = new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "fixture", 1,
                TARGET, "INTERNAL", Instant.parse("2026-07-15T00:00:00Z"), seed,
                List.of(), List.of(), Map.of());
        return GovernedExecutionServices.prepare(mapper, fixture,
                new InvocationInventory(List.of(), Map.of(), Map.of()));
    }
}
