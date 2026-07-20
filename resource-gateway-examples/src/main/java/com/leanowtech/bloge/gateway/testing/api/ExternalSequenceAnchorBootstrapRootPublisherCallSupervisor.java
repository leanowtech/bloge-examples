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
 * Bounds opaque bootstrap-root publisher calls with fixed capacity and no queue.
 *
 * <p>Calls either start immediately on one of a fixed number of daemon platform threads or fail
 * closed. The wall-clock deadline asks a timed-out call to stop by interruption, while a publisher
 * that ignores interruption remains visible as lingering and continues to consume its fixed slot.
 * No local timeout is misrepresented as remote cancellation.</p>
 *
 * <p>Only the publisher port's bounded failure reason crosses this boundary. Provider exception
 * text, endpoint identity, publication content, fingerprints, and keys are never retained in the
 * exception or aggregate snapshot.</p>
 */
public final class ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor
        implements AutoCloseable {

    private static final AtomicLong POOL_SEQUENCE = new AtomicLong();

    private final Policy policy;
    private final ThreadPoolExecutor executor;
    private final Object outcomeLock = new Object();
    private final Object occupancyLock = new Object();
    private final Set<CallState<?>> runningCalls = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong acceptedCalls = new AtomicLong();
    private final AtomicLong completedCalls = new AtomicLong();
    private final AtomicLong unavailableCalls = new AtomicLong();
    private final AtomicLong invalidResponseCalls = new AtomicLong();
    private final AtomicLong conflictCalls = new AtomicLong();
    private final AtomicLong timedOutCalls = new AtomicLong();
    private final AtomicLong saturatedCalls = new AtomicLong();
    private final AtomicLong closedCalls = new AtomicLong();
    private final AtomicLong callerInterruptedCalls = new AtomicLong();
    private final AtomicLong activeCalls = new AtomicLong();
    private final AtomicLong lingeringCalls = new AtomicLong();

    /** Creates a fixed-capacity publisher boundary with conservative defaults. */
    public ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor() {
        this(Policy.DEFAULT);
    }

    /**
     * Creates a fixed-capacity publisher call boundary.
     *
     * @param policy local wall-clock deadline and maximum concurrent calls
     */
    public ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor(Policy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        long poolId = POOL_SEQUENCE.incrementAndGet();
        AtomicLong threadSequence = new AtomicLong();
        ThreadFactory threads = task -> Thread.ofPlatform().daemon(true).name(
                "resource-gateway-bootstrap-root-publisher-" + poolId + '-'
                        + threadSequence.incrementAndGet()).unstarted(task);
        this.executor = new ThreadPoolExecutor(
                policy.maximumConcurrentCalls(), policy.maximumConcurrentCalls(),
                0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), threads,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Invokes one idempotent publication under the local deadline.
     *
     * @param publisher authenticated remote delivery adapter
     * @param request exact durable outbox request
     * @return exact stable publication receipt
     * @throws InvocationException for every bounded non-success outcome
     */
    public ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationReceipt publish(
            ExternalSequenceAnchorBootstrapRootPublisher publisher,
            ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationRequest request) {
        ExternalSequenceAnchorBootstrapRootPublisher required = Objects.requireNonNull(
                publisher, "publisher");
        ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationRequest safeRequest =
                Objects.requireNonNull(request, "request");
        return invoke(() -> required.publish(safeRequest));
    }

    /**
     * Returns the minimum database lease that leaves a two-second local commit margin.
     *
     * @return whole seconds required by a publication service call
     */
    public long minimumLeaseDurationSeconds() {
        return Math.addExact(
                Math.floorDiv(policy.publisherTimeout().toMillis() + 999L, 1_000L), 2L);
    }

    /**
     * Returns a payload-free immutable capacity and outcome projection.
     *
     * @return fixed-capacity supervisor snapshot
     */
    public Snapshot snapshot() {
        synchronized (outcomeLock) {
            synchronized (occupancyLock) {
                return new Snapshot(Snapshot.SCHEMA_VERSION, policy,
                        acceptedCalls.get(), completedCalls.get(), unavailableCalls.get(),
                        invalidResponseCalls.get(), conflictCalls.get(), timedOutCalls.get(),
                        saturatedCalls.get(), closedCalls.get(), callerInterruptedCalls.get(),
                        activeCalls.get(), lingeringCalls.get(), closed.get());
            }
        }
    }

    /**
     * Rejects new calls and requests interruption without waiting for an uncooperative adapter.
     */
    @Override
    public void close() {
        boolean closing;
        synchronized (outcomeLock) {
            closing = closed.compareAndSet(false, true);
            if (closing) {
                executor.shutdownNow();
            }
        }
        if (closing) {
            runningCalls.forEach(CallState::requestCancellation);
        }
    }

    private <T> T invoke(Callable<T> operation) {
        if (closed.get()) {
            closedCalls.incrementAndGet();
            throw rejected(InvocationDisposition.CLOSED);
        }
        CallState<T> call = new CallState<>(operation);
        Future<T> future;
        try {
            synchronized (outcomeLock) {
                future = executor.submit(call);
                acceptedCalls.incrementAndGet();
            }
        } catch (RejectedExecutionException rejected) {
            if (closed.get()) {
                closedCalls.incrementAndGet();
                throw rejected(InvocationDisposition.CLOSED);
            }
            saturatedCalls.incrementAndGet();
            throw rejected(InvocationDisposition.SATURATED);
        }

        try {
            T result = future.get(policy.publisherTimeout().toMillis(),
                    TimeUnit.MILLISECONDS);
            increment(completedCalls);
            return result;
        } catch (TimeoutException timeout) {
            increment(timedOutCalls);
            call.requestCancellation();
            future.cancel(true);
            throw rejected(InvocationDisposition.TIMED_OUT);
        } catch (InterruptedException interrupted) {
            increment(callerInterruptedCalls);
            call.requestCancellation();
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw rejected(InvocationDisposition.CALLER_INTERRUPTED);
        } catch (ExecutionException unavailable) {
            InvocationDisposition disposition = disposition(unavailable.getCause());
            increment(counter(disposition));
            throw rejected(disposition);
        } catch (CancellationException unavailable) {
            increment(unavailableCalls);
            throw rejected(InvocationDisposition.UNAVAILABLE);
        }
    }

    private AtomicLong counter(InvocationDisposition disposition) {
        return switch (disposition) {
            case INVALID_RESPONSE -> invalidResponseCalls;
            case AUTHENTICATED_CONFLICT -> conflictCalls;
            default -> unavailableCalls;
        };
    }

    private static InvocationDisposition disposition(Throwable failure) {
        if (failure instanceof ExternalSequenceAnchorBootstrapRootPublisher
                .PublisherException publisherFailure) {
            return switch (publisherFailure.reason()) {
                case INVALID_RESPONSE -> InvocationDisposition.INVALID_RESPONSE;
                case AUTHENTICATED_CONFLICT ->
                        InvocationDisposition.AUTHENTICATED_CONFLICT;
                case UNAVAILABLE -> InvocationDisposition.UNAVAILABLE;
            };
        }
        return InvocationDisposition.UNAVAILABLE;
    }

    private void increment(AtomicLong counter) {
        synchronized (outcomeLock) {
            counter.incrementAndGet();
        }
    }

    private static InvocationException rejected(InvocationDisposition disposition) {
        return new InvocationException(disposition);
    }

    /** Bounded local or authenticated remote invocation outcome. */
    public enum InvocationDisposition {
        /** Publisher transport or implementation failed without a valid response. */
        UNAVAILABLE,

        /** Publisher returned invalid protocol, binding, freshness, or signature material. */
        INVALID_RESPONSE,

        /** Publisher returned a valid signed conflicting-head response. */
        AUTHENTICATED_CONFLICT,

        /** The local wall-clock deadline elapsed and interruption was requested. */
        TIMED_OUT,

        /** Every fixed worker slot was occupied and no call was queued. */
        SATURATED,

        /** The supervisor had already closed. */
        CLOSED,

        /** The caller was interrupted while awaiting the adapter. */
        CALLER_INTERRUPTED
    }

    /**
     * Fixed local publisher-call limits.
     *
     * @param publisherTimeout wall-clock deadline from 100 ms through 240 seconds
     * @param maximumConcurrentCalls fixed process-local capacity from one through 16
     */
    public record Policy(Duration publisherTimeout, int maximumConcurrentCalls) {

        /** Conservative default: 30-second call deadline and two fixed slots. */
        public static final Policy DEFAULT = new Policy(Duration.ofSeconds(30), 2);

        /** Enforces millisecond-exact finite time and capacity bounds. */
        public Policy {
            publisherTimeout = Objects.requireNonNull(
                    publisherTimeout, "publisherTimeout");
            if (publisherTimeout.compareTo(Duration.ofMillis(100)) < 0
                    || publisherTimeout.compareTo(Duration.ofSeconds(240)) > 0
                    || !publisherTimeout.equals(
                    Duration.ofMillis(publisherTimeout.toMillis()))
                    || maximumConcurrentCalls < 1 || maximumConcurrentCalls > 16) {
                throw new IllegalArgumentException(
                        "Bootstrap-root publisher call policy is invalid");
            }
        }
    }

    /**
     * Payload-free process-local call supervisor snapshot.
     *
     * @param schemaVersion snapshot protocol generation
     * @param policy immutable local call limits
     * @param acceptedCalls operations admitted to a worker
     * @param completedCalls operations returned normally before timeout
     * @param unavailableCalls adapter or transport failures
     * @param invalidResponseCalls invalid authenticated-protocol candidates
     * @param conflictCalls valid authenticated remote conflicts
     * @param timedOutCalls calls whose local wall-clock deadline elapsed
     * @param saturatedCalls calls rejected because every slot was occupied
     * @param closedCalls calls rejected after shutdown
     * @param callerInterruptedCalls calls cancelled after caller interruption
     * @param activeCalls adapter methods currently running
     * @param lingeringCalls active methods still running after cancellation request
     * @param closed whether new calls are rejected
     */
    public record Snapshot(
            String schemaVersion,
            Policy policy,
            long acceptedCalls,
            long completedCalls,
            long unavailableCalls,
            long invalidResponseCalls,
            long conflictCalls,
            long timedOutCalls,
            long saturatedCalls,
            long closedCalls,
            long callerInterruptedCalls,
            long activeCalls,
            long lingeringCalls,
            boolean closed) {

        /** Current publisher call snapshot generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootPublisherCallSnapshot.v1";

        /** Enforces monotonic counters and physically possible occupancy. */
        public Snapshot {
            schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
            policy = Objects.requireNonNull(policy, "policy");
            if (!SCHEMA_VERSION.equals(schemaVersion) || acceptedCalls < 0L
                    || completedCalls < 0L || unavailableCalls < 0L
                    || invalidResponseCalls < 0L || conflictCalls < 0L
                    || timedOutCalls < 0L || saturatedCalls < 0L || closedCalls < 0L
                    || callerInterruptedCalls < 0L || activeCalls < 0L
                    || lingeringCalls < 0L
                    || activeCalls > policy.maximumConcurrentCalls()
                    || lingeringCalls > activeCalls
                    || completedCalls + unavailableCalls + invalidResponseCalls
                    + conflictCalls + timedOutCalls + callerInterruptedCalls
                    > acceptedCalls) {
                throw new IllegalArgumentException(
                        "Bootstrap-root publisher call snapshot is invalid");
            }
        }
    }

    /** Bounded call failure that excludes remote exception text and identity. */
    public static final class InvocationException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        /** Local or authenticated remote outcome without provider diagnostics. */
        private final InvocationDisposition disposition;

        private InvocationException(InvocationDisposition disposition) {
            super("External bootstrap-root publisher call was "
                    + Objects.requireNonNull(disposition, "disposition").name()
                    .toLowerCase(java.util.Locale.ROOT));
            this.disposition = disposition;
        }

        /**
         * Returns the bounded local or authenticated remote outcome.
         *
         * @return invocation disposition
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
                synchronized (outcomeLock) {
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
