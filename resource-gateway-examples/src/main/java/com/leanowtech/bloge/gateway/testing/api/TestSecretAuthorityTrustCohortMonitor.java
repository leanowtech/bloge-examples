package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes local test-secret trust observations and gates on exact database convergence.
 *
 * <p>Descriptor reads perform no remote JWKS or secret-authority I/O. A local generation change
 * closes the gate synchronously until the heartbeat lane publishes it; database unavailability,
 * scheduler failure and process close are also fail-closed. The database snapshot then requires
 * exactly one live healthy process per configured slot and exactly one trust generation.</p>
 */
public final class TestSecretAuthorityTrustCohortMonitor
        implements TestSecretAuthorityTrustCohortGate, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(
            TestSecretAuthorityTrustCohortMonitor.class);

    private final TestSecretAuthorityTrustCohortRepository repository;
    private final DynamicJwksTestSecretAuthorityTrustStore trustStore;
    private final TestSecretAuthorityTrustCohortPolicy policy;
    private final ObjectMapper objectMapper;
    private final ScheduledThreadPoolExecutor scheduler;
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    private volatile String publishedObservationFingerprint = "";
    private volatile boolean closed;

    /**
     * Starts one process-local heartbeat lane after an immediate bounded publication attempt.
     *
     * @param repository database-clock cohort authority
     * @param trustStore local dynamic JWKS trust source
     * @param policy exact configured deployment cohort
     * @param objectMapper canonical private observation fingerprint mapper
     */
    public TestSecretAuthorityTrustCohortMonitor(
            TestSecretAuthorityTrustCohortRepository repository,
            DynamicJwksTestSecretAuthorityTrustStore trustStore,
            TestSecretAuthorityTrustCohortPolicy policy,
            ObjectMapper objectMapper) {
        this(repository, trustStore, policy, objectMapper, true);
    }

    /** Package-visible deterministic constructor used by scheduler-independent tests. */
    TestSecretAuthorityTrustCohortMonitor(
            TestSecretAuthorityTrustCohortRepository repository,
            DynamicJwksTestSecretAuthorityTrustStore trustStore,
            TestSecretAuthorityTrustCohortPolicy policy,
            ObjectMapper objectMapper,
            boolean startScheduler) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.trustStore = Objects.requireNonNull(trustStore, "trustStore");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        publishNow();
        this.scheduler = startScheduler ? scheduler() : null;
    }

    /**
     * Returns current local-plus-database readiness without publishing a heartbeat.
     *
     * @return aggregate payload-free gate state
     */
    @Override
    public Descriptor descriptor() {
        if (closed) {
            return unavailable("CLOSED");
        }
        DynamicJwksTestSecretAuthorityTrustStore.CohortObservation local;
        try {
            local = trustStore.cohortObservation();
        } catch (RuntimeException unavailable) {
            return unavailable("LOCAL_TRUST_UNAVAILABLE");
        }
        if (!observationFingerprint(local).equals(publishedObservationFingerprint)) {
            return unavailable("LOCAL_OBSERVATION_UNPUBLISHED");
        }
        try {
            TestSecretAuthorityTrustCohortRepository.Snapshot snapshot = repository.snapshot();
            return new Descriptor(Descriptor.SCHEMA_VERSION, true, snapshot.converged(),
                    snapshot.status(), snapshot.expectedReplicaCount(),
                    snapshot.liveReplicaCount(), snapshot.healthyReplicaCount(),
                    snapshot.distinctTrustGenerationCount(),
                    policy.leaseDuration().toSeconds(), true, true);
        } catch (RuntimeException storeUnavailable) {
            return Descriptor.unavailable(policy.expectedInstanceIds().size(),
                    policy.leaseDuration().toSeconds());
        }
    }

    /** Publishes one immediate local heartbeat; exposed package-locally for deterministic tests. */
    boolean publishNow() {
        if (closed) {
            return false;
        }
        try {
            DynamicJwksTestSecretAuthorityTrustStore.CohortObservation observation =
                    trustStore.cohortObservation();
            repository.heartbeat(observation);
            publishedObservationFingerprint = observationFingerprint(observation);
            failureLogged.set(false);
            return true;
        } catch (RuntimeException unavailable) {
            if (failureLogged.compareAndSet(false, true)) {
                log.warn("Test-secret authority trust cohort heartbeat failed; secret "
                        + "resolution is fail-closed");
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

    private String observationFingerprint(
            DynamicJwksTestSecretAuthorityTrustStore.CohortObservation observation) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion",
                        "bloge.testSecretAuthorityTrustLocalPublication.v1"),
                Map.entry("available", observation.available()),
                Map.entry("refreshState", observation.refreshState()),
                Map.entry("snapshotFingerprint", observation.snapshotFingerprint()),
                Map.entry("activeKeyCount", observation.activeKeyCount()),
                Map.entry("scopeId", policy.scopeId()),
                Map.entry("cohortId", policy.cohortId()),
                Map.entry("artifactFingerprint", policy.artifactFingerprint()),
                Map.entry("authorityId", policy.authorityId()),
                Map.entry("protocolVersion", policy.protocolVersion()),
                Map.entry("expectedInstanceIds",
                        policy.expectedInstanceIds().stream().sorted().toList())));
    }

    private Descriptor unavailable(String status) {
        return new Descriptor(Descriptor.SCHEMA_VERSION, true, false, status,
                policy.expectedInstanceIds().size(), 0, 0, 0,
                policy.leaseDuration().toSeconds(), true, true);
    }

    private ScheduledThreadPoolExecutor scheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task,
                    "resource-gateway-test-secret-trust-cohort-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        long intervalMillis = policy.heartbeatInterval().toMillis();
        long initialDelayMillis = ThreadLocalRandom.current().nextLong(
                Math.max(1L, intervalMillis / 2L), intervalMillis + 1L);
        executor.scheduleWithFixedDelay(this::publishNow,
                initialDelayMillis, intervalMillis, TimeUnit.MILLISECONDS);
        return executor;
    }
}
