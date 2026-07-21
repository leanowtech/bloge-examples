package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Snapshot;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Observation;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.VerifiedBinding;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.CycleDisposition;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTest.lane;
import static com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTest.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalSequenceAnchorBootstrapRootRecoveryFleetRuntimeConfigurationTest {

    private static final String PREFIX =
            ExternalSequenceAnchorBootstrapRootRecoveryFleetRuntimeConfiguration
                    .FleetProperties.PREFIX + ".";
    private static final String SINGLE_LANE_ENABLED =
            ExternalSequenceAnchorBootstrapRootRecoveryRuntimeConfiguration
                    .RecoveryProperties.PREFIX + ".enabled";

    @Test
    void disabledFleetInstallsNoRuntimeBeans() {
        try (var database = database();
             var context = context(Map.of(PREFIX + "enabled", "false"),
                     emptyInventory(), database, null, "test")) {
            assertFleetAbsent(context);
        }
    }

    @Test
    void productionPresencePhysicallyExcludesFleetEvenWhenTestIsAlsoActive() {
        try (var database = database();
             var production = context(enabledProperties(), emptyInventory(), database,
                     null, "production");
             var mixed = context(enabledProperties(), emptyInventory(), database,
                     null, "production", "test")) {
            assertFleetAbsent(production);
            assertFleetAbsent(mixed);
        }
    }

    @Test
    void enabledTestProfileAssemblesDatabaseFencedFleetAndAggregateHealth() {
        try (var database = database()) {
            var context = context(enabledProperties(), emptyInventory(), database,
                    null, "test");
            var worker = context.getBean(
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.class);
            var scheduler = context.getBean(
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.class);
            try {
                assertThat(context.getBeansOfType(
                        DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator
                                .class)).hasSize(1);
                assertThat(context.getBeansOfType(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.class))
                        .hasSize(1);
                assertThat(context.getBeansOfType(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.class))
                        .hasSize(1);

                var cycle = scheduler.runOnce();
                assertThat(cycle.disposition()).isEqualTo(CycleDisposition.COMPLETED);
                assertThat(cycle.inventoryGeneration()).isEqualTo(1L);
                assertThat(cycle.lanes()).isEmpty();

                var health = context.getBean(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetHealth.class).health();
                assertThat(health.getStatus()).isEqualTo(Status.UP);
                assertThat(health.getDetails()).containsEntry("runtimeStatus", "READY")
                        .containsEntry("schedulerPollCount", 1L)
                        .containsEntry("latestInventoryGeneration", 1L);
                assertThat(health.getDetails().toString()).doesNotContain(
                        "fleet-sensitive", "replica-sensitive", "scope-sensitive",
                        "root-sensitive", "sha256:", "jdbc:h2");
            } finally {
                context.close();
            }
            assertThat(scheduler.snapshot().closed()).isTrue();
            assertThat(worker.runtimeSnapshot().closed()).isTrue();
        }
    }

    @Test
    void signedInventoryAddsIndependentAggregateHealthAfterExactPreflight() {
        var authority = signedAuthority(1L, 0, 4);
        try (var database = database();
             var context = context(enabledProperties(), authority, database,
                     null, "test")) {
            var health = context.getBean(
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth.class)
                    .health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .containsEntry("inventoryGeneration", 1L)
                    .containsEntry("laneCount", 0)
                    .containsEntry("fleetTopologyBound", true);
            assertThat(health.getDetails().toString()).doesNotContain(
                    "fleet-sensitive", "replica-sensitive", "recovery-prod");
        }
    }

    @Test
    void signedInventoryDriftFailsBeforeCoordinatorStateExists() {
        var authority = signedAuthority(2L, 0, 3);
        try (var database = database()) {
            var context = unrefreshedContext(enabledProperties(), authority, database,
                    null, "test");
            try {
                assertThatThrownBy(context::refresh)
                        .rootCause()
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage(
                                "Bootstrap-root recovery fleet runtime configuration is invalid");
                assertThat(database.jdbc().queryForObject(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                                + "WHERE TABLE_NAME LIKE 'RG_EXTERNAL_SEQUENCE_ANCHOR_%'",
                        Integer.class)).isZero();
            } finally {
                context.close();
            }
        }
    }

    @Test
    void durableCursorSurvivesACompleteSpringRuntimeRebuild() {
        var inventory = (ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory) () ->
                snapshot(7L, lane("scope-sensitive", "root-a", 'a'),
                        lane("scope-sensitive", "root-b", 'b'));
        Map<String, Object> properties = enabledProperties();
        properties.put(PREFIX + "partition-count", "1");
        properties.put(PREFIX + "maximum-lanes-per-cycle", "1");

        try (var database = database()) {
            ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.CycleResult first;
            try (var firstContext = context(properties, inventory, database, null, "test")) {
                first = firstContext.getBean(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.class)
                        .runOnce();
            }
            ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.CycleResult second;
            try (var secondContext = context(properties, inventory, database, null, "test")) {
                second = secondContext.getBean(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.class)
                        .runOnce();
            }

            assertThat(first.lanes()).extracting(result -> result.laneKey().rootSetId())
                    .containsExactly("root-a");
            assertThat(second.lanes()).extracting(result -> result.laneKey().rootSetId())
                    .containsExactly("root-b");
        }
    }

    @Test
    void enabledFleetRequiresAnExplicitAlreadyAuthorizedInventory() {
        try (var database = database()) {
            var context = unrefreshedContext(enabledProperties(), null, database,
                    null, "test");
            try {
                assertThatThrownBy(context::refresh)
                        .hasStackTraceContaining(
                                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.class
                                        .getName());
            } finally {
                context.close();
            }
        }
    }

    @Test
    void singleLaneAndFleetModesCannotPollTheSameRecoveryRuntime() {
        Map<String, Object> properties = enabledProperties();
        properties.put(SINGLE_LANE_ENABLED, "true");
        assertInvalidConfiguration(properties, emptyInventory(), null);
    }

    @Test
    void customCoordinatorMustStillProvideDurableCrossReplicaSemantics() {
        var coordinator = mock(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.class);
        when(coordinator.durable()).thenReturn(false);
        assertInvalidConfiguration(enabledProperties(), emptyInventory(), coordinator);
    }

    @Test
    void malformedAndUnknownFleetConfigurationFailsClosed() {
        Map<String, Object> malformed = enabledProperties();
        malformed.put(PREFIX + "partition-count", "65");
        assertInvalidConfiguration(malformed, emptyInventory(), null);

        Map<String, Object> unsafeSchedule = enabledProperties();
        unsafeSchedule.put(PREFIX + "poll-interval-millis", "99");
        assertInvalidConfiguration(unsafeSchedule, emptyInventory(), null);

        try (var database = database()) {
            Map<String, Object> unknown = enabledProperties();
            unknown.put(PREFIX + "signer-private-key", "must-not-bind");
            var context = unrefreshedContext(unknown, emptyInventory(), database,
                    null, "test");
            try {
                assertThatThrownBy(context::refresh)
                        .hasStackTraceContaining("signer-private-key")
                        .hasStackTraceContaining("ignoreUnknownFields");
            } finally {
                context.close();
            }
        }
    }

    @Test
    void inventoryFailureIsSanitizedBeforeAnyStatefulBeanStarts() {
        var inventory = (ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory) () -> {
            throw new IllegalStateException("must-not-echo-inventory-diagnostic");
        };
        try (var database = database()) {
            var context = unrefreshedContext(enabledProperties(), inventory, database,
                    null, "staging");
            try {
                assertThatThrownBy(context::refresh)
                        .rootCause()
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Bootstrap-root recovery fleet runtime configuration is invalid")
                        .hasMessageNotContaining("must-not-echo-inventory-diagnostic");
                assertThat(database.jdbc().queryForObject(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                                + "WHERE TABLE_NAME LIKE 'RG_EXTERNAL_SEQUENCE_ANCHOR_%'",
                        Integer.class)).isZero();
            } finally {
                context.close();
            }
        }
    }

    private static void assertInvalidConfiguration(
            Map<String, Object> properties,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory inventory,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator coordinator) {
        try (var database = database()) {
            var context = unrefreshedContext(properties, inventory, database,
                    coordinator, "test");
            try {
                assertThatThrownBy(context::refresh)
                        .rootCause()
                        .isInstanceOf(IllegalArgumentException.class);
            } finally {
                context.close();
            }
        }
    }

    private static AnnotationConfigApplicationContext context(
            Map<String, Object> properties,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory inventory,
            TestRuntimeDatabase database,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator coordinator,
            String... profiles) {
        var context = unrefreshedContext(
                properties, inventory, database, coordinator, profiles);
        context.refresh();
        return context;
    }

    private static AnnotationConfigApplicationContext unrefreshedContext(
            Map<String, Object> properties,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory inventory,
            TestRuntimeDatabase database,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator coordinator,
            String... profiles) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("bootstrap-root-recovery-fleet",
                        new LinkedHashMap<>(properties)));
        context.registerBean(ObjectMapper.class,
                () -> new ObjectMapper().findAndRegisterModules());
        context.registerBean(TestRuntimeDatabase.class, () -> database,
                definition -> definition.setDestroyMethodName(""));
        if (inventory instanceof
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority authority) {
            context.registerBean(
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.class,
                    () -> authority);
        } else if (inventory != null) {
            context.registerBean(
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.class,
                    () -> inventory);
        }
        if (coordinator != null) {
            context.registerBean(
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.class,
                    () -> coordinator);
        }
        context.register(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetRuntimeConfiguration.class);
        return context;
    }

    private static TestRuntimeDatabase database() {
        return new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:bootstrap-root-recovery-fleet-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 4));
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory
            emptyInventory() {
        return () -> new Snapshot(Snapshot.SCHEMA_VERSION, 1L, java.util.List.of());
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
            signedAuthority(long observedGeneration, int laneCount, int partitionCount) {
        var authority = mock(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.class);
        when(authority.snapshot()).thenReturn(emptyInventory().snapshot());
        var observation = new Observation(
                Observation.SCHEMA_VERSION, true, "VERIFIED",
                "STATIC_SIGNED_ED25519_M_OF_N", observedGeneration, laneCount,
                java.time.Instant.parse("2026-07-21T12:00:00Z"), 2, 2);
        when(authority.observation()).thenReturn(observation);
        when(authority.descriptor()).thenReturn(new
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Descriptor(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Descriptor
                        .SCHEMA_VERSION,
                true, true, true, "VERIFIED", observedGeneration, laneCount,
                Map.of("sourceType", "STATIC_SIGNED_ED25519_M_OF_N",
                        "fleetTopologyBound", true)));
        when(authority.verifiedBinding()).thenReturn(new VerifiedBinding(
                "recovery-prod", "fleet-sensitive", "sha256:" + "a".repeat(64),
                partitionCount));
        return authority;
    }

    private static Map<String, Object> enabledProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(PREFIX + "enabled", "true");
        properties.put(PREFIX + "fleet-id", "fleet-sensitive");
        properties.put(PREFIX + "worker-id", "replica-sensitive");
        properties.put(PREFIX + "partition-count", "4");
        properties.put(PREFIX + "lease-duration-seconds", "3");
        properties.put(PREFIX + "maximum-lanes-per-cycle", "8");
        properties.put(PREFIX + "initial-delay-millis", "300000");
        properties.put(PREFIX + "poll-interval-millis", "1000");
        properties.put(PREFIX + "maximum-cycle-duration-millis", "10000");
        properties.put(PREFIX + "drain-timeout-millis", "1000");
        return properties;
    }

    private static void assertFleetAbsent(AnnotationConfigApplicationContext context) {
        assertThat(context.getBeansOfType(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetHealth.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth.class)).isEmpty();
    }
}
