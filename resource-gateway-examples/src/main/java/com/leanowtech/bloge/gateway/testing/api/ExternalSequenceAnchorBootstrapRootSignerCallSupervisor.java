package com.leanowtech.bloge.gateway.testing.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounds opaque bootstrap-root signer calls without claiming remote-provider cancellation.
 *
 * <p>The supervisor uses a fixed number of daemon platform threads and a zero-capacity handoff
 * queue. Authority resolution, descriptor, and signature calls therefore either start immediately
 * or fail closed; a blocked adapter can never create an unbounded thread or queue backlog. A
 * wall-clock timeout asks the task to stop with an interrupt and returns a bounded
 * {@link InvocationException} without provider diagnostics.</p>
 *
 * <p>Interrupt is only a local cancellation request. If an adapter ignores it, the running call is
 * counted as lingering and continues to consume one fixed slot until it actually exits. A valid
 * remote cancellation receipt or process/container termination is still required before a
 * deployment may claim provider-confirmed hard cancellation.</p>
 */
public final class ExternalSequenceAnchorBootstrapRootSignerCallSupervisor
        implements AutoCloseable {

    private static final AtomicLong POOL_SEQUENCE = new AtomicLong();

    private final Policy policy;
    private final ThreadPoolExecutor executor;
    private final Object outcomeCounterLock = new Object();
    private final Object occupancyLock = new Object();
    private final Set<CallState<?>> runningCalls = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong acceptedCalls = new AtomicLong();
    private final AtomicLong completedCalls = new AtomicLong();
    private final AtomicLong failedCalls = new AtomicLong();
    private final AtomicLong timedOutCalls = new AtomicLong();
    private final AtomicLong saturatedCalls = new AtomicLong();
    private final AtomicLong closedCalls = new AtomicLong();
    private final AtomicLong callerInterruptedCalls = new AtomicLong();
    private final AtomicLong activeCalls = new AtomicLong();
    private final AtomicLong lingeringCalls = new AtomicLong();

    /** Creates a supervisor with the conservative embedded-service defaults. */
    public ExternalSequenceAnchorBootstrapRootSignerCallSupervisor() {
        this(Policy.DEFAULT);
    }

    /**
     * Creates a fixed-capacity signer call boundary.
     *
     * @param policy resolver/descriptor/signature deadlines and maximum concurrent calls
     */
    public ExternalSequenceAnchorBootstrapRootSignerCallSupervisor(Policy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        long poolId = POOL_SEQUENCE.incrementAndGet();
        AtomicLong threadSequence = new AtomicLong();
        ThreadFactory threadFactory = task -> {
            Thread thread = Thread.ofPlatform().daemon(true).name(
                    "resource-gateway-bootstrap-root-signer-" + poolId + '-'
                            + threadSequence.incrementAndGet()).unstarted(task);
            return thread;
        };
        this.executor = new ThreadPoolExecutor(
                policy.maximumConcurrentCalls(), policy.maximumConcurrentCalls(),
                0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Resolves runtime authority ports under the dedicated resolution deadline.
     *
     * @param resolver embedding boundary for approved-cohort runtime adapters
     * @param proposal immutable database-approved proposal
     * @return both role-specific runtime authority collections
     * @throws InvocationException when resolution is unavailable, timed out, or rejected
     */
    public ExternalSequenceAnchorBootstrapRootAuthorityResolver.AuthoritySet resolve(
            ExternalSequenceAnchorBootstrapRootAuthorityResolver resolver,
            ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonyProposal proposal) {
        ExternalSequenceAnchorBootstrapRootAuthorityResolver requiredResolver =
                Objects.requireNonNull(resolver, "resolver");
        ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonyProposal safeProposal =
                Objects.requireNonNull(proposal, "proposal");
        return invoke(CallType.AUTHORITY_RESOLUTION, policy.resolverTimeout(),
                () -> requiredResolver.resolve(safeProposal));
    }

    /**
     * Obtains one signer public descriptor under the descriptor deadline.
     *
     * @param authority opaque signer adapter
     * @return adapter descriptor, which the producer still validates independently
     * @throws InvocationException when the call is unavailable, timed out, or rejected
     */
    public ExternalSequenceAnchorBootstrapRootSigningAuthority.Descriptor descriptor(
            ExternalSequenceAnchorBootstrapRootSigningAuthority authority) {
        ExternalSequenceAnchorBootstrapRootSigningAuthority required =
                Objects.requireNonNull(authority, "authority");
        return invoke(CallType.DESCRIPTOR, policy.descriptorTimeout(), required::descriptor);
    }

    /**
     * Invokes one idempotent detached-signature request under the signature deadline.
     *
     * @param authority opaque signer adapter
     * @param request complete content-addressed signing command
     * @return detached response, which the producer still binds and verifies independently
     * @throws InvocationException when the call is unavailable, timed out, or rejected
     */
    public ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureResponse sign(
            ExternalSequenceAnchorBootstrapRootSigningAuthority authority,
            ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureRequest request) {
        ExternalSequenceAnchorBootstrapRootSigningAuthority required =
                Objects.requireNonNull(authority, "authority");
        ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureRequest safeRequest =
                Objects.requireNonNull(request, "request");
        return invoke(CallType.SIGNATURE, policy.signatureTimeout(),
                () -> required.sign(safeRequest));
    }

    /**
     * Returns a payload-free monotonic process-local supervisor projection.
     *
     * @return capacity, counters, live occupancy, and closed state
     */
    public Snapshot snapshot() {
        synchronized (outcomeCounterLock) {
            synchronized (occupancyLock) {
                return new Snapshot(Snapshot.SCHEMA_VERSION, policy,
                        acceptedCalls.get(), completedCalls.get(), failedCalls.get(),
                        timedOutCalls.get(), saturatedCalls.get(), closedCalls.get(),
                        callerInterruptedCalls.get(), activeCalls.get(), lingeringCalls.get(),
                        closed.get());
            }
        }
    }

    /**
     * Requests interruption of every active adapter call and rejects new calls immediately.
     *
     * <p>The method never waits for an interrupt-ignoring adapter. Such a daemon call remains
     * visible in {@link #snapshot()} until its provider method actually returns.</p>
     */
    @Override
    public void close() {
        boolean closing;
        synchronized (outcomeCounterLock) {
            closing = closed.compareAndSet(false, true);
            if (closing) {
                executor.shutdownNow();
            }
        }
        if (closing) {
            runningCalls.forEach(CallState::requestCancellation);
        }
    }

    private <T> T invoke(CallType callType, Duration timeout, Callable<T> operation) {
        if (closed.get()) {
            closedCalls.incrementAndGet();
            throw rejected(callType, InvocationDisposition.CLOSED);
        }
        CallState<T> call = new CallState<>(operation);
        Future<T> future;
        try {
            synchronized (outcomeCounterLock) {
                future = executor.submit(call);
                acceptedCalls.incrementAndGet();
            }
        } catch (RejectedExecutionException rejected) {
            if (closed.get()) {
                closedCalls.incrementAndGet();
                throw rejected(callType, InvocationDisposition.CLOSED);
            }
            saturatedCalls.incrementAndGet();
            throw rejected(callType, InvocationDisposition.SATURATED);
        }

        try {
            T result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            incrementOutcome(completedCalls);
            return result;
        } catch (TimeoutException timeoutFailure) {
            incrementOutcome(timedOutCalls);
            call.requestCancellation();
            future.cancel(true);
            throw rejected(callType, InvocationDisposition.TIMED_OUT);
        } catch (InterruptedException callerInterrupted) {
            incrementOutcome(callerInterruptedCalls);
            call.requestCancellation();
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw rejected(callType, InvocationDisposition.CALLER_INTERRUPTED);
        } catch (ExecutionException | CancellationException unavailable) {
            incrementOutcome(failedCalls);
            throw rejected(callType, InvocationDisposition.UNAVAILABLE);
        }
    }

    private void incrementOutcome(AtomicLong counter) {
        synchronized (outcomeCounterLock) {
            counter.incrementAndGet();
        }
    }

    private static InvocationException rejected(
            CallType callType, InvocationDisposition disposition) {
        return new InvocationException(callType, disposition);
    }

    /** Classifies the bounded local signer operation without exposing authority identity. */
    public enum CallType {
        /** Runtime authority adapter resolution for an approved proposal. */
        AUTHORITY_RESOLUTION,

        /** Public descriptor lookup used by preflight binding. */
        DESCRIPTOR,

        /** Idempotent detached signature invocation. */
        SIGNATURE
    }

    /** Bounded local invocation outcome used by fail-closed callers. */
    public enum InvocationDisposition {
        /** The configured wall-clock deadline elapsed and local interrupt was requested. */
        TIMED_OUT,

        /** Every fixed worker slot was occupied; no operation was queued. */
        SATURATED,

        /** The supervisor had already closed. */
        CLOSED,

        /** The caller thread was interrupted while awaiting the adapter. */
        CALLER_INTERRUPTED,

        /** The adapter failed or its future was cancelled without provider diagnostics. */
        UNAVAILABLE
    }

    /**
     * Fixed local signer-call limits.
     *
     * @param resolverTimeout runtime authority resolution timeout from 100 ms through 300 seconds
     * @param descriptorTimeout public descriptor call timeout from 100 ms through 300 seconds
     * @param signatureTimeout detached signing call timeout from 100 ms through 300 seconds
     * @param maximumConcurrentCalls fixed process-local worker capacity from 1 through 32
     */
    public record Policy(
            Duration resolverTimeout,
            Duration descriptorTimeout,
            Duration signatureTimeout,
            int maximumConcurrentCalls) {

        /** Conservative defaults: 5 s resolution/descriptor, 30 s signature, 8 workers. */
        public static final Policy DEFAULT = new Policy(
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(30), 8);

        /**
         * Preserves the original descriptor/signature policy surface and uses descriptor timeout
         * for authority resolution.
         *
         * @param descriptorTimeout public descriptor and authority resolution timeout
         * @param signatureTimeout detached signing call timeout
         * @param maximumConcurrentCalls fixed process-local worker capacity
         */
        public Policy(
                Duration descriptorTimeout,
                Duration signatureTimeout,
                int maximumConcurrentCalls) {
            this(descriptorTimeout, descriptorTimeout, signatureTimeout,
                    maximumConcurrentCalls);
        }

        /** Enforces millisecond-exact bounded timeouts and fixed capacity. */
        public Policy {
            resolverTimeout = requiredTimeout(resolverTimeout, "resolverTimeout");
            descriptorTimeout = requiredTimeout(descriptorTimeout, "descriptorTimeout");
            signatureTimeout = requiredTimeout(signatureTimeout, "signatureTimeout");
            if (maximumConcurrentCalls < 1 || maximumConcurrentCalls > 32) {
                throw new IllegalArgumentException(
                        "Bootstrap-root signer maximum concurrency is invalid");
            }
        }

        private static Duration requiredTimeout(Duration value, String field) {
            Duration required = Objects.requireNonNull(value, field);
            if (required.compareTo(Duration.ofMillis(100)) < 0
                    || required.compareTo(Duration.ofSeconds(300)) > 0
                    || !required.equals(Duration.ofMillis(required.toMillis()))) {
                throw new IllegalArgumentException(
                        "Bootstrap-root signer call timeout is invalid");
            }
            return required;
        }
    }

    /**
     * Payload-free process-local call supervisor snapshot.
     *
     * @param schemaVersion snapshot protocol generation
     * @param policy immutable supervisor limits
     * @param acceptedCalls operations admitted to a worker
     * @param completedCalls operations returned normally before timeout
     * @param failedCalls operations whose adapter failed before timeout
     * @param timedOutCalls operations whose caller observed the wall-clock timeout
     * @param saturatedCalls operations rejected because every worker was occupied
     * @param closedCalls operations rejected after shutdown
     * @param callerInterruptedCalls operations cancelled because the caller was interrupted
     * @param activeCalls adapter methods currently running
     * @param lingeringCalls active methods that were still running when cancellation was requested
     * @param closed whether the supervisor rejects every new operation
     */
    public record Snapshot(
            String schemaVersion,
            Policy policy,
            long acceptedCalls,
            long completedCalls,
            long failedCalls,
            long timedOutCalls,
            long saturatedCalls,
            long closedCalls,
            long callerInterruptedCalls,
            long activeCalls,
            long lingeringCalls,
            boolean closed) {

        /** Current process-local supervisor snapshot protocol. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootSignerCallSnapshot.v1";

        /** Enforces monotonic counters and physically possible live occupancy. */
        public Snapshot {
            schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
            policy = Objects.requireNonNull(policy, "policy");
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || acceptedCalls < 0 || completedCalls < 0 || failedCalls < 0
                    || timedOutCalls < 0 || saturatedCalls < 0 || closedCalls < 0
                    || callerInterruptedCalls < 0 || activeCalls < 0 || lingeringCalls < 0
                    || activeCalls > policy.maximumConcurrentCalls()
                    || lingeringCalls > activeCalls
                    || completedCalls + failedCalls + timedOutCalls
                    + callerInterruptedCalls > acceptedCalls) {
                throw new IllegalArgumentException(
                        "Bootstrap-root signer call snapshot is invalid");
            }
        }
    }

    /** Bounded local failure that deliberately excludes provider exception text and identity. */
    public static final class InvocationException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        /** Local operation class. */
        private final CallType callType;

        /** Local bounded disposition. */
        private final InvocationDisposition disposition;

        private InvocationException(CallType callType, InvocationDisposition disposition) {
            super("External bootstrap-root signer call was "
                    + disposition.name().toLowerCase(java.util.Locale.ROOT));
            this.callType = Objects.requireNonNull(callType, "callType");
            this.disposition = Objects.requireNonNull(disposition, "disposition");
        }

        /**
         * Returns whether descriptor binding or detached signing was attempted.
         *
         * @return bounded call type
         */
        public CallType callType() {
            return callType;
        }

        /**
         * Returns the local timeout, saturation, shutdown, interruption, or failure class.
         *
         * @return bounded local disposition
         */
        public InvocationDisposition disposition() {
            return disposition;
        }
    }

    private final class CallState<T> implements Callable<T> {
        private final Callable<T> operation;
        private boolean started;
        private boolean completed;
        private boolean cancellationRequested;
        private boolean lingeringCounted;

        private CallState(Callable<T> operation) {
            this.operation = Objects.requireNonNull(operation, "operation");
        }

        @Override
        public T call() throws Exception {
            synchronized (this) {
                synchronized (outcomeCounterLock) {
                    if (closed.get()) {
                        throw new CancellationException();
                    }
                    started = true;
                    synchronized (occupancyLock) {
                        activeCalls.incrementAndGet();
                    }
                    runningCalls.add(this);
                }
                countLingeringIfRequired();
            }
            try {
                return operation.call();
            } finally {
                synchronized (this) {
                    completed = true;
                    if (lingeringCounted) {
                        synchronized (occupancyLock) {
                            lingeringCalls.decrementAndGet();
                        }
                    }
                }
                synchronized (occupancyLock) {
                    activeCalls.decrementAndGet();
                }
                runningCalls.remove(this);
            }
        }

        private synchronized void requestCancellation() {
            cancellationRequested = true;
            countLingeringIfRequired();
        }

        private void countLingeringIfRequired() {
            if (started && !completed && cancellationRequested && !lingeringCounted) {
                lingeringCounted = true;
                synchronized (occupancyLock) {
                    lingeringCalls.incrementAndGet();
                }
            }
        }
    }
}
