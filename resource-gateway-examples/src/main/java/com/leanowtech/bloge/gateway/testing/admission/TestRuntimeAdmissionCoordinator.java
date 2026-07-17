package com.leanowtech.bloge.gateway.testing.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.AdmissionGuard;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.AdmissionIntent;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeAdmissionControl;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeAdmissionControl.AcquireResult;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeAdmissionControl.AdmissionConflictException;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeAdmissionControl.AdmissionLease;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeAdmissionControl.AdmissionRequest;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeAdmissionControl.QuotaSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Application-facing admission coordinator with hashed subjects and renewable permit guards.
 *
 * <p>The coordinator is the only layer that sees raw suite, operator, and dependency references.
 * It binds them to the verified tenant/environment scope, hashes them before persistence, and maps
 * aggregate saturation to a stable retryable 429 problem. A single daemon renewer serves all live
 * local guards; lease loss is checked before a terminal response is published.</p>
 */
public final class TestRuntimeAdmissionCoordinator
        implements TestRuntimeAdmissionGate, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(
            TestRuntimeAdmissionCoordinator.class);
    private static final Pattern OWNER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    private final DatabaseTestRuntimeAdmissionControl controlPlane;
    private final TestRuntimeAdmissionPolicy policy;
    private final ObjectMapper objectMapper;
    private final TestRuntimeAdmissionTelemetry telemetry;
    private final String ownerId;
    private final ScheduledExecutorService renewer;
    private final Object lifecycleMonitor = new Object();
    private final Set<Guard> activeGuards = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /**
     * Creates a cross-replica admission gate.
     *
     * @param controlPlane database-authoritative permit protocol
     * @param policy versioned quota and lease policy
     * @param objectMapper canonical subject fingerprint mapper
     * @param telemetry closed-vocabulary decision metrics
     * @param ownerId process owner id; blank creates an opaque random owner
     */
    public TestRuntimeAdmissionCoordinator(
            DatabaseTestRuntimeAdmissionControl controlPlane,
            TestRuntimeAdmissionPolicy policy,
            ObjectMapper objectMapper,
            TestRuntimeAdmissionTelemetry telemetry,
            String ownerId) {
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        String normalizedOwner = ownerId == null ? "" : ownerId.trim();
        if (!normalizedOwner.isBlank() && !OWNER.matcher(normalizedOwner).matches()) {
            throw new IllegalArgumentException("Admission instance-id is invalid");
        }
        this.ownerId = normalizedOwner.isBlank()
                ? "admission-" + UUID.randomUUID() : normalizedOwner;
        renewer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-runtime-admission-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Hashes every subject and acquires all claims before execution starts.
     *
     * @param identity verified non-production caller identity
     * @param intent payload-free exact admission intent
     * @return renewable exact permit guard
     */
    @Override
    public AdmissionGuard admit(
            IntegrationRequestContext identity,
            AdmissionIntent intent) {
        IntegrationRequestContext principal = Objects.requireNonNull(identity, "identity");
        principal.requireComplete();
        if (closed) {
            throw unavailable(principal, "RG.TEST.ADMISSION_COORDINATOR_CLOSED",
                    "Test-runtime admission is shutting down.");
        }
        AdmissionIntent requested = Objects.requireNonNull(intent, "intent");
        AdmissionRequest request = request(principal, requested);
        AcquireResult result;
        try {
            result = controlPlane.acquire(request);
        } catch (AdmissionConflictException conflict) {
            throw mapConflict(conflict, principal);
        } catch (RuntimeException unavailable) {
            telemetry.record(TestRuntimeAdmissionTelemetry.Result.STORE_UNAVAILABLE,
                    TestRuntimeAdmissionTelemetry.Scope.RUNTIME);
            throw unavailable(principal, "RG.TEST.ADMISSION_STORE_UNAVAILABLE",
                    "Test-runtime admission authority is unavailable.");
        }
        return switch (result.state()) {
            case ACQUIRED -> {
                yield register(result.lease(), principal);
            }
            case ALREADY_ACTIVE -> {
                telemetry.record(TestRuntimeAdmissionTelemetry.Result.IN_PROGRESS,
                        TestRuntimeAdmissionTelemetry.Scope.RUNTIME);
                throw throttled(principal, "RG.TEST.ADMISSION_IN_PROGRESS",
                        "The same execution intent is already admitted.",
                        TestRuntimeAdmissionTelemetry.Scope.RUNTIME,
                        result.retryAfterSeconds(), Map.of());
            }
            case REJECTED -> {
                var rejection = result.rejection();
                TestRuntimeAdmissionTelemetry.Scope scope =
                        TestRuntimeAdmissionTelemetry.scope(rejection.dimension());
                telemetry.record(TestRuntimeAdmissionTelemetry.Result.REJECTED, scope);
                throw throttled(principal, "RG.TEST.ADMISSION_QUOTA_EXCEEDED",
                        "Test-runtime capacity is exhausted for one governed quota dimension.",
                        scope, rejection.retryAfterSeconds(), Map.of(
                                "dimension", rejection.dimension().name(),
                                "maxActive", rejection.maxActive(),
                                "active", rejection.active(),
                                "policyGeneration", policy.generation()));
            }
        };
    }

    private Guard register(
            AdmissionLease lease,
            IntegrationRequestContext identity) {
        synchronized (lifecycleMonitor) {
            if (closed) {
                releaseUnregistered(lease);
                throw unavailable(identity, "RG.TEST.ADMISSION_COORDINATOR_CLOSED",
                        "Test-runtime admission is shutting down.");
            }
            try {
                Guard guard = new Guard(lease, identity.correlationId());
                activeGuards.add(guard);
                telemetry.record(TestRuntimeAdmissionTelemetry.Result.ACQUIRED,
                        TestRuntimeAdmissionTelemetry.Scope.RUNTIME);
                return guard;
            } catch (RuntimeException schedulingFailure) {
                releaseUnregistered(lease);
                throw unavailable(identity, "RG.TEST.ADMISSION_COORDINATOR_UNAVAILABLE",
                        "Test-runtime admission heartbeat is unavailable.");
            }
        }
    }

    private void releaseUnregistered(AdmissionLease lease) {
        try {
            if (!controlPlane.release(lease)) {
                telemetry.record(TestRuntimeAdmissionTelemetry.Result.RELEASE_FAILED,
                        TestRuntimeAdmissionTelemetry.Scope.RUNTIME);
            }
        } catch (RuntimeException unavailable) {
            telemetry.record(TestRuntimeAdmissionTelemetry.Result.RELEASE_FAILED,
                    TestRuntimeAdmissionTelemetry.Scope.RUNTIME);
            log.warn("Unregistered test-runtime admission release failed; lease expiry will recover capacity");
        }
    }

    private AdmissionRequest request(
            IntegrationRequestContext identity,
            AdmissionIntent intent) {
        Set<QuotaSubject> subjects = new LinkedHashSet<>();
        subjects.add(subject(identity, TestRuntimeAdmissionPolicy.Dimension.TENANT,
                "tenant", policy.tenantMaxActive()));
        if (!intent.suiteRef().isBlank()) {
            subjects.add(subject(identity, TestRuntimeAdmissionPolicy.Dimension.SUITE,
                    intent.suiteRef(), policy.suiteMaxActive()));
        }
        intent.operatorRefs().stream().sorted().forEach(ref -> subjects.add(subject(
                identity, TestRuntimeAdmissionPolicy.Dimension.OPERATOR,
                ref, policy.operatorMaxActive())));
        intent.dependencyRefs().stream().sorted().forEach(ref -> subjects.add(subject(
                identity, TestRuntimeAdmissionPolicy.Dimension.DEPENDENCY,
                ref, policy.dependencyMaxActive())));

        String admissionId = ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", "bloge.testRuntimeAdmissionIdentity.v1",
                "tenantId", identity.tenantId(),
                "environmentId", identity.environmentId(),
                "kind", intent.kind().name(),
                "stableRequestKey", intent.stableRequestKey()));
        String boundIntent = ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.testRuntimeAdmissionIntent.v1"),
                Map.entry("tenantId", identity.tenantId()),
                Map.entry("organizationId", identity.organizationId()),
                Map.entry("projectId", identity.projectId()),
                Map.entry("environmentId", identity.environmentId()),
                Map.entry("actorId", identity.actorId()),
                Map.entry("kind", intent.kind().name()),
                Map.entry("intentFingerprint", intent.intentFingerprint()),
                Map.entry("subjects", subjects.stream()
                        .sorted(Comparator.comparing(QuotaSubject::subjectFingerprint))
                        .map(QuotaSubject::subjectFingerprint).toList())));
        return new AdmissionRequest(admissionId, boundIntent, policy.fingerprint(),
                policy.generation(), ownerId, policy.leaseDuration(),
                new ArrayList<>(subjects));
    }

    private QuotaSubject subject(
            IntegrationRequestContext identity,
            TestRuntimeAdmissionPolicy.Dimension dimension,
            String subject,
            long limit) {
        String fingerprint = ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", "bloge.testRuntimeAdmissionSubject.v1",
                "tenantId", identity.tenantId(),
                "environmentId", identity.environmentId(),
                "dimension", dimension.name(),
                "subject", subject));
        return new QuotaSubject(dimension, fingerprint, limit);
    }

    private IntegrationProblemException mapConflict(
            AdmissionConflictException conflict,
            IntegrationRequestContext identity) {
        if (conflict.reason()
                == DatabaseTestRuntimeAdmissionControl.ConflictReason.IDENTITY_CONFLICT) {
            return new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.TEST.ADMISSION_IDEMPOTENCY_CONFLICT",
                    "Admission identity already represents a different execution intent.",
                    identity.correlationId(), Map.of()));
        }
        telemetry.record(TestRuntimeAdmissionTelemetry.Result.POLICY_DRIFT,
                TestRuntimeAdmissionTelemetry.Scope.RUNTIME);
        return unavailable(identity, "RG.TEST.ADMISSION_POLICY_DRIFT",
                "Admission replicas do not share an active quota policy generation.");
    }

    private IntegrationProblemException throttled(
            IntegrationRequestContext identity,
            String code,
            String title,
            TestRuntimeAdmissionTelemetry.Scope scope,
            long retryAfterSeconds,
            Map<String, Object> facts) {
        Map<String, Object> details = new java.util.LinkedHashMap<>(facts);
        details.put("scope", scope.name());
        details.put("retryAfterSeconds", Math.max(1, Math.min(3600, retryAfterSeconds)));
        return new IntegrationProblemException(IntegrationProblem.tooManyRequests(
                code, title, identity.correlationId(), details));
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity,
            String code,
            String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, identity.correlationId(), Map.of()));
    }

    /** Invalidates and releases every local permit before stopping future renewals. */
    @Override
    public void close() {
        List<Guard> guards;
        synchronized (lifecycleMonitor) {
            if (closed) {
                return;
            }
            closed = true;
            guards = List.copyOf(activeGuards);
        }
        guards.forEach(Guard::shutdown);
        renewer.shutdownNow();
    }

    private final class Guard implements AdmissionGuard {
        private AdmissionLease lease;
        private final String correlationId;
        private final ScheduledFuture<?> heartbeat;
        private boolean closed;
        private boolean lost;

        private Guard(AdmissionLease lease, String correlationId) {
            this.lease = Objects.requireNonNull(lease, "lease");
            this.correlationId = correlationId == null ? "" : correlationId;
            long interval = policy.heartbeatInterval().toMillis();
            heartbeat = renewer.scheduleWithFixedDelay(
                    this::renew, interval, interval, TimeUnit.MILLISECONDS);
        }

        private synchronized void renew() {
            if (closed || lost) {
                return;
            }
            try {
                lease = controlPlane.renew(lease, policy.leaseDuration())
                        .orElseGet(() -> {
                            lost = true;
                            telemetry.record(TestRuntimeAdmissionTelemetry.Result.LEASE_LOST,
                                    TestRuntimeAdmissionTelemetry.Scope.RUNTIME);
                            return lease;
                        });
            } catch (RuntimeException unavailable) {
                lost = true;
                telemetry.record(TestRuntimeAdmissionTelemetry.Result.LEASE_LOST,
                        TestRuntimeAdmissionTelemetry.Scope.RUNTIME);
                log.warn("Test-runtime admission heartbeat failed; permit is fail-closed");
            }
        }

        /** Fails terminal publication after any heartbeat or fencing loss. */
        @Override
        public synchronized void checkpoint() {
            if (lost) {
                throw new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                        "RG.TEST.ADMISSION_LEASE_LOST",
                        "Test-runtime admission ownership was lost before completion.",
                        correlationId, Map.of()));
            }
        }

        /** Releases exact ownership, relying on lease expiry if the store is unavailable. */
        @Override
        public synchronized void close() {
            terminate(false);
        }

        private synchronized void shutdown() {
            terminate(true);
        }

        private void terminate(boolean invalidate) {
            if (invalidate) {
                lost = true;
            }
            if (closed) {
                return;
            }
            closed = true;
            heartbeat.cancel(false);
            activeGuards.remove(this);
            try {
                if (!controlPlane.release(lease)) {
                    telemetry.record(TestRuntimeAdmissionTelemetry.Result.RELEASE_FAILED,
                            TestRuntimeAdmissionTelemetry.Scope.RUNTIME);
                }
            } catch (RuntimeException unavailable) {
                telemetry.record(TestRuntimeAdmissionTelemetry.Result.RELEASE_FAILED,
                        TestRuntimeAdmissionTelemetry.Scope.RUNTIME);
                log.warn("Test-runtime admission release failed; database lease expiry will recover capacity");
            }
        }
    }
}
