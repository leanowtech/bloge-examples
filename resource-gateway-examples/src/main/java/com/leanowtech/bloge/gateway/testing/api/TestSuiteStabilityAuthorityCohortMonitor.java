package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes local dynamic-trust observations and gates on exact database cohort convergence.
 *
 * <p>Descriptor reads never publish or call the remote JWKS endpoint. They first compare current
 * local trust with the last successfully heartbeated observation; a local change therefore closes
 * the gate immediately and remains closed until the background lane publishes it. Database
 * unavailability, scheduler failure and process close are all fail-closed.</p>
 */
public final class TestSuiteStabilityAuthorityCohortMonitor
        implements TestSuiteStabilityAuthorityCohortGate, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(
            TestSuiteStabilityAuthorityCohortMonitor.class);

    private final TestSuiteStabilityAuthorityCohortRepository repository;
    private final DynamicJwksTestSuiteStabilityAuthorityTrustStore trustStore;
    private final TestSuiteStabilityServingInventoryAuthority inventoryAuthority;
    private final TestSuiteStabilityAuthorityCohortPolicy policy;
    private final ObjectMapper objectMapper;
    private final String policyFingerprint;
    private final ScheduledThreadPoolExecutor scheduler;
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    private volatile String publishedObservationFingerprint = "";
    private volatile boolean closed;

    /**
     * Starts one process-local heartbeat lane after an immediate bounded publication attempt.
     *
     * @param repository database-clock cohort authority
     * @param trustStore local dynamic JWKS trust source
     * @param policy exact configured cohort contract
     * @param objectMapper canonical private observation fingerprint mapper
     */
    public TestSuiteStabilityAuthorityCohortMonitor(
            TestSuiteStabilityAuthorityCohortRepository repository,
            DynamicJwksTestSuiteStabilityAuthorityTrustStore trustStore,
            TestSuiteStabilityAuthorityCohortPolicy policy,
            ObjectMapper objectMapper) {
        this(repository, trustStore, TestSuiteStabilityServingInventoryAuthority.localOnly(),
                policy, objectMapper, true);
    }

    /**
     * Starts cohort publication with an independently verified serving-inventory authority.
     *
     * @param repository database-clock cohort authority
     * @param trustStore local dynamic JWKS trust source
     * @param inventoryAuthority external exact serving-inventory authority
     * @param policy exact cohort contract derived from that inventory
     * @param objectMapper canonical private observation fingerprint mapper
     */
    public TestSuiteStabilityAuthorityCohortMonitor(
            TestSuiteStabilityAuthorityCohortRepository repository,
            DynamicJwksTestSuiteStabilityAuthorityTrustStore trustStore,
            TestSuiteStabilityServingInventoryAuthority inventoryAuthority,
            TestSuiteStabilityAuthorityCohortPolicy policy,
            ObjectMapper objectMapper) {
        this(repository, trustStore, inventoryAuthority, policy, objectMapper, true);
    }

    TestSuiteStabilityAuthorityCohortMonitor(
            TestSuiteStabilityAuthorityCohortRepository repository,
            DynamicJwksTestSuiteStabilityAuthorityTrustStore trustStore,
            TestSuiteStabilityAuthorityCohortPolicy policy,
            ObjectMapper objectMapper,
            boolean startScheduler) {
        this(repository, trustStore, TestSuiteStabilityServingInventoryAuthority.localOnly(),
                policy, objectMapper, startScheduler);
    }

    TestSuiteStabilityAuthorityCohortMonitor(
            TestSuiteStabilityAuthorityCohortRepository repository,
            DynamicJwksTestSuiteStabilityAuthorityTrustStore trustStore,
            TestSuiteStabilityServingInventoryAuthority inventoryAuthority,
            TestSuiteStabilityAuthorityCohortPolicy policy,
            ObjectMapper objectMapper,
            boolean startScheduler) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.trustStore = Objects.requireNonNull(trustStore, "trustStore");
        this.inventoryAuthority = Objects.requireNonNull(
                inventoryAuthority, "inventoryAuthority");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.policyFingerprint = policy.cohortFingerprint(this.objectMapper);
        requireCurrentInventory();
        publishNow();
        this.scheduler = startScheduler ? scheduler() : null;
    }

    /**
     * Returns aggregate cohort readiness without remote I/O or a membership write.
     *
     * @return exact current local-plus-database gate state
     */
    @Override
    public Descriptor descriptor() {
        if (closed) {
            return unavailable("CLOSED");
        }
        TestSuiteStabilityServingInventoryAuthority.Observation inventory;
        try {
            inventory = requireCurrentInventory();
        } catch (IllegalStateException unavailable) {
            return unavailable(inventoryStatus());
        }
        DynamicJwksTestSuiteStabilityAuthorityTrustStore.CohortObservation local;
        try {
            local = trustStore.cohortObservation();
        } catch (RuntimeException unavailable) {
            return unavailable("LOCAL_TRUST_UNAVAILABLE");
        }
        if (!observationFingerprint(local, inventory).equals(
                publishedObservationFingerprint)) {
            return unavailable("LOCAL_OBSERVATION_UNPUBLISHED");
        }
        try {
            TestSuiteStabilityAuthorityCohortRepository.Snapshot snapshot =
                    repository.snapshot();
            return new Descriptor(Descriptor.SCHEMA_VERSION, true, snapshot.converged(),
                    snapshot.status(), snapshot.expectedReplicaCount(),
                    snapshot.liveReplicaCount(), snapshot.healthyReplicaCount(),
                    snapshot.distinctSnapshotCount(), policy.leaseDuration().toSeconds(),
                    true, true,
                    policy.servingInventory().externallyAttested());
        } catch (RuntimeException storeUnavailable) {
            return Descriptor.unavailable(policy.expectedInstanceIds().size(),
                    policy.leaseDuration().toSeconds(),
                    policy.servingInventory().externallyAttested());
        }
    }

    /** Publishes one immediate local heartbeat; exposed package-locally for deterministic tests. */
    boolean publishNow() {
        if (closed) {
            return false;
        }
        try {
            DynamicJwksTestSuiteStabilityAuthorityTrustStore.CohortObservation observation =
                    trustStore.cohortObservation();
            TestSuiteStabilityServingInventoryAuthority.Observation inventory =
                    requireCurrentInventory();
            TestSuiteStabilityAuthorityCohortRepository.Member member = member(observation);
            repository.heartbeat(member);
            publishedObservationFingerprint = observationFingerprint(observation, inventory);
            failureLogged.set(false);
            return true;
        } catch (RuntimeException unavailable) {
            if (failureLogged.compareAndSet(false, true)) {
                log.warn("Suite-stability authority cohort heartbeat failed; admission and "
                        + "worker claims are fail-closed");
            }
            return false;
        }
    }

    /** Stops heartbeats and best-effort withdraws only this process-start membership. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(
                        Math.min(1_000L, policy.heartbeatInterval().toMillis()),
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            repository.withdraw(policy.instanceId(), policy.startupId());
        } catch (RuntimeException ignored) {
            // The database-clock lease remains the crash-safe withdrawal boundary.
        }
    }

    private TestSuiteStabilityAuthorityCohortRepository.Member member(
            DynamicJwksTestSuiteStabilityAuthorityTrustStore.CohortObservation observation) {
        return new TestSuiteStabilityAuthorityCohortRepository.Member(
                "bloge.testSuiteStabilityAuthorityCohortMember.v1",
                policy.scopeId(), policy.cohortId(), policy.instanceId(), policy.startupId(),
                policy.artifactFingerprint(), policyFingerprint,
                policy.protocolVersion(), policy.authorityId(),
                "DYNAMIC_JWKS_ED25519", observation.available(),
                observation.refreshState(), observation.snapshotFingerprint(),
                observation.activeKeyCount(), observation.lastSuccessfulRefreshAt());
    }

    private String observationFingerprint(
            DynamicJwksTestSuiteStabilityAuthorityTrustStore.CohortObservation observation,
            TestSuiteStabilityServingInventoryAuthority.Observation inventory) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion",
                        "bloge.testSuiteStabilityAuthorityLocalPublication.v1"),
                Map.entry("available", observation.available()),
                Map.entry("refreshState", observation.refreshState()),
                Map.entry("snapshotFingerprint", observation.snapshotFingerprint()),
                Map.entry("activeKeyCount", observation.activeKeyCount()),
                Map.entry("servingInventoryConfigured", inventory.configured()),
                Map.entry("servingInventoryAvailable", inventory.available()),
                Map.entry("servingInventoryRevision", inventory.revision()),
                Map.entry("servingInventoryMaterialFingerprint",
                        inventory.materialFingerprint()),
                Map.entry("policyFingerprint", policyFingerprint)));
    }

    private TestSuiteStabilityServingInventoryAuthority.Observation requireCurrentInventory() {
        TestSuiteStabilityServingInventoryAuthority.Observation observed =
                inventoryAuthority.observation();
        TestSuiteStabilityAuthorityCohortPolicy.ServingInventoryAttestation expected =
                policy.servingInventory();
        if (!expected.externallyAttested()) {
            if (observed.configured()) {
                throw new IllegalStateException(
                        "Local cohort policy cannot use an external inventory authority");
            }
            return observed;
        }
        if (!observed.configured() || !observed.externallyAttested()
                || !observed.available()
                || observed.revision() != expected.revision()
                || !observed.sourceType().equals(expected.sourceType())
                || !observed.materialFingerprint().equals(expected.materialFingerprint())
                || !observed.policyFingerprint().equals(expected.policyFingerprint())
                || !Objects.equals(observed.expiresAt(), expected.expiresAt())
                || !Set.copyOf(observed.expectedInstanceIds()).equals(
                policy.expectedInstanceIds())) {
            throw new IllegalStateException(
                    "Serving inventory no longer matches the cohort policy");
        }
        return observed;
    }

    private String inventoryStatus() {
        try {
            TestSuiteStabilityServingInventoryAuthority.Observation observed =
                    inventoryAuthority.observation();
            if (!observed.available()) {
                return "SERVING_INVENTORY_" + observed.status();
            }
            return "SERVING_INVENTORY_DIVERGED";
        } catch (RuntimeException unavailable) {
            return "SERVING_INVENTORY_UNAVAILABLE";
        }
    }

    private Descriptor unavailable(String status) {
        return new Descriptor(Descriptor.SCHEMA_VERSION, true, false, status,
                policy.expectedInstanceIds().size(), 0, 0, 0,
                policy.leaseDuration().toSeconds(), true, true,
                policy.servingInventory().externallyAttested());
    }

    private ScheduledThreadPoolExecutor scheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task,
                    "resource-gateway-stability-authority-cohort-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        long intervalMillis = policy.heartbeatInterval().toMillis();
        long initialDelayMillis = ThreadLocalRandom.current().nextLong(
                Math.max(1L, intervalMillis / 2L), intervalMillis + 1L);
        executor.scheduleWithFixedDelay(this::publishSafely,
                initialDelayMillis, intervalMillis, TimeUnit.MILLISECONDS);
        return executor;
    }

    private void publishSafely() {
        publishNow();
    }
}
