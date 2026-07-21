package com.leanowtech.bloge.gateway.testing.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Applies authenticated event pages through a durable stage/apply/commit pipeline.
 *
 * <p>Each poll first requires local serving admission, then fetches from the committed cursor,
 * durably pins the exact page, applies every independently signed event through the rotation
 * runtime, and commits only after every result is {@code APPLIED} or {@code REPLAYED}. Source,
 * protocol, cursor, or event rejection leaves the committed cursor unchanged. Exact restart replay
 * repairs a crash after partial or complete application without rerunning material resolution for
 * already accepted events.</p>
 */
public final class ControlPlaneCertificateRotationEventWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(
            ControlPlaneCertificateRotationEventWatcher.class);
    private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    private final ControlPlaneCertificateRotationEventSource source;
    private final ControlPlaneCertificateRotationEventCursor cursor;
    private final EventApplier eventApplier;
    private final BooleanSupplier servingAdmission;
    private final Duration pollInterval;
    private final int maximumPagesPerPoll;
    private final ScheduledThreadPoolExecutor scheduler;
    private final Object pollLock = new Object();
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    private final AtomicLong appliedPages = new AtomicLong();
    private final AtomicLong appliedEvents = new AtomicLong();

    private volatile WatcherStatus status = WatcherStatus.NEW;
    private volatile String reasonCode = "NOT_POLLED";
    private volatile ControlPlaneCertificateRotationEventCursor.Snapshot observedCursor;
    private volatile boolean closed;

    /**
     * Starts one fixed-delay product watcher.
     *
     * @param source authenticated strict page source
     * @param cursor durable serving-slot cursor
     * @param runtime local signed-event application runtime
     * @param pollInterval fixed delay between bounded poll cycles
     * @param maximumPagesPerPoll one through 32 pages per cycle
     */
    public ControlPlaneCertificateRotationEventWatcher(
            ControlPlaneCertificateRotationEventSource source,
            ControlPlaneCertificateRotationEventCursor cursor,
            ControlPlaneCertificateRotationRuntime runtime,
            Duration pollInterval,
            int maximumPagesPerPoll) {
        this(source, cursor, Objects.requireNonNull(runtime, "runtime")::apply,
                () -> runtime.descriptor().servingReady(), pollInterval,
                maximumPagesPerPoll, true);
    }

    ControlPlaneCertificateRotationEventWatcher(
            ControlPlaneCertificateRotationEventSource source,
            ControlPlaneCertificateRotationEventCursor cursor,
            EventApplier eventApplier,
            BooleanSupplier servingAdmission,
            Duration pollInterval,
            int maximumPagesPerPoll,
            boolean automaticPolling) {
        this.source = Objects.requireNonNull(source, "source");
        this.cursor = Objects.requireNonNull(cursor, "cursor");
        this.eventApplier = Objects.requireNonNull(eventApplier, "eventApplier");
        this.servingAdmission = Objects.requireNonNull(
                servingAdmission, "servingAdmission");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        this.maximumPagesPerPoll = maximumPagesPerPoll;
        ControlPlaneCertificateRotationEventSource.Descriptor security = source.descriptor();
        if (!cursor.durable() || pollInterval.compareTo(Duration.ofSeconds(1)) < 0
                || pollInterval.compareTo(Duration.ofHours(1)) > 0
                || maximumPagesPerPoll < 1 || maximumPagesPerPoll > 32
                || !security.authenticatedProtocol()
                || !security.certificateIdentityBound()) {
            throw new IllegalArgumentException(
                    "Certificate rotation event watcher configuration is invalid");
        }
        this.observedCursor = Objects.requireNonNull(cursor.snapshot(), "cursor snapshot");
        this.scheduler = automaticPolling ? scheduler() : null;
    }

    /**
     * Runs one bounded poll cycle and never throws provider or cursor failure details.
     *
     * @return post-cycle material-free descriptor
     */
    public Descriptor pollOnce() {
        synchronized (pollLock) {
            if (closed) {
                set(WatcherStatus.CLOSED, "WATCHER_CLOSED");
                return descriptor();
            }
            try {
                for (int pageIndex = 0; pageIndex < maximumPagesPerPoll; pageIndex++) {
                    boolean admitted;
                    try {
                        admitted = servingAdmission.getAsBoolean();
                    } catch (RuntimeException unavailable) {
                        set(WatcherStatus.RUNTIME_UNAVAILABLE, "RUNTIME_ADMISSION_UNAVAILABLE");
                        logFailureOnce();
                        return descriptor();
                    }
                    if (!admitted) {
                        set(WatcherStatus.RUNTIME_FENCED, "RUNTIME_SERVING_FENCED");
                        return descriptor();
                    }
                    ControlPlaneCertificateRotationEventCursor.Snapshot current;
                    try {
                        current = cursor.snapshot();
                    } catch (RuntimeException unavailable) {
                        set(WatcherStatus.CURSOR_UNAVAILABLE, "EVENT_CURSOR_UNAVAILABLE");
                        logFailureOnce();
                        return descriptor();
                    }
                    observedCursor = current;
                    ControlPlaneCertificateRotationEventSource.FetchResult result;
                    try {
                        result = Objects.requireNonNull(source.fetch(
                                new ControlPlaneCertificateRotationEventSource.Position(
                                        current.deploymentScopeId(), current.committedSequence(),
                                        current.committedPageFingerprint())), "fetch result");
                    } catch (RuntimeException unavailable) {
                        set(WatcherStatus.SOURCE_UNAVAILABLE, "EVENT_SOURCE_UNAVAILABLE");
                        logFailureOnce();
                        return descriptor();
                    }
                    if (result.status()
                            == ControlPlaneCertificateRotationEventSource.FetchStatus.NO_CHANGE) {
                        set(WatcherStatus.IDLE, result.reasonCode());
                        failureLogged.set(false);
                        return descriptor();
                    }
                    if (result.status()
                            == ControlPlaneCertificateRotationEventSource.FetchStatus
                            .SOURCE_UNAVAILABLE) {
                        set(WatcherStatus.SOURCE_UNAVAILABLE, result.reasonCode());
                        logFailureOnce();
                        return descriptor();
                    }
                    if (result.status()
                            == ControlPlaneCertificateRotationEventSource.FetchStatus
                            .PROTOCOL_REJECTED) {
                        set(WatcherStatus.PROTOCOL_REJECTED, result.reasonCode());
                        logFailureOnce();
                        return descriptor();
                    }
                    if (!applyPage(result.page())) {
                        logFailureOnce();
                        return descriptor();
                    }
                    failureLogged.set(false);
                }
                set(WatcherStatus.APPLIED, "POLL_PAGE_LIMIT_REACHED");
                return descriptor();
            } catch (RuntimeException unavailable) {
                set(WatcherStatus.WATCHER_UNAVAILABLE, "EVENT_WATCHER_UNAVAILABLE");
                logFailureOnce();
                return descriptor();
            }
        }
    }

    /** @return bounded cached watcher state without triggering network or database I/O */
    public Descriptor descriptor() {
        ControlPlaneCertificateRotationEventCursor.Snapshot current = observedCursor;
        WatcherStatus observed = status;
        return new Descriptor(Descriptor.SCHEMA_VERSION,
                ready(observed), cursor.durable(), scheduler != null,
                source.descriptor().authenticatedProtocol(),
                source.descriptor().mutualTls(),
                source.descriptor().certificateIdentityBound(),
                current.committedSequence(), current.hasStagedPage(),
                appliedPages.get(), appliedEvents.get(), observed.name(), reasonCode);
    }

    /** Stops future polls and waits for no unbounded external work. */
    @Override
    public void close() {
        synchronized (pollLock) {
            closed = true;
            set(WatcherStatus.CLOSED, "WATCHER_CLOSED");
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
        }
    }

    private boolean applyPage(ControlPlaneCertificateRotationEventPage page) {
        ControlPlaneCertificateRotationEventCursor.StageResult staged;
        try {
            staged = cursor.stage(page);
        } catch (RuntimeException unavailable) {
            set(WatcherStatus.CURSOR_UNAVAILABLE, "EVENT_CURSOR_UNAVAILABLE");
            return false;
        }
        observedCursor = staged.snapshot();
        if (staged.status() == ControlPlaneCertificateRotationEventCursor.StageStatus.CONFLICT) {
            set(WatcherStatus.CURSOR_CONFLICT, "EVENT_CURSOR_PAGE_CONFLICT");
            return false;
        }
        int acceptedEvents = 0;
        for (ControlPlaneCertificateRotationEvent event : page.material().events()) {
            ControlPlaneCertificateRotationController.ApplyResult applied;
            try {
                applied = eventApplier.apply(event);
            } catch (RuntimeException unavailable) {
                set(WatcherStatus.APPLY_BLOCKED, "EVENT_APPLY_UNAVAILABLE");
                return false;
            }
            if (applied == null || !applied.accepted()) {
                set(WatcherStatus.APPLY_BLOCKED, applied == null
                        ? "EVENT_APPLY_UNAVAILABLE" : safeReason(applied.reasonCode()));
                return false;
            }
            acceptedEvents++;
        }
        ControlPlaneCertificateRotationEventCursor.CommitResult committed;
        try {
            committed = cursor.commit(page.pageFingerprint());
        } catch (RuntimeException unavailable) {
            set(WatcherStatus.CURSOR_UNAVAILABLE, "EVENT_CURSOR_UNAVAILABLE");
            return false;
        }
        observedCursor = committed.snapshot();
        if (committed.status() == ControlPlaneCertificateRotationEventCursor.CommitStatus.CONFLICT) {
            set(WatcherStatus.CURSOR_CONFLICT, "EVENT_CURSOR_COMMIT_CONFLICT");
            return false;
        }
        increment(appliedPages, 1);
        increment(appliedEvents, acceptedEvents);
        set(WatcherStatus.APPLIED, "PAGE_APPLIED");
        return true;
    }

    private ScheduledThreadPoolExecutor scheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task,
                    "resource-gateway-certificate-rotation-event-watcher");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        long intervalMillis = pollInterval.toMillis();
        long jitter = ThreadLocalRandom.current().nextLong(
                Math.max(1L, Math.min(intervalMillis, 1_000L)));
        executor.scheduleWithFixedDelay(this::safePoll, jitter, intervalMillis,
                TimeUnit.MILLISECONDS);
        return executor;
    }

    private void safePoll() {
        try {
            pollOnce();
        } catch (RuntimeException failure) {
            set(WatcherStatus.WATCHER_UNAVAILABLE, "EVENT_WATCHER_UNAVAILABLE");
            logFailureOnce();
        }
    }

    private void logFailureOnce() {
        if (failureLogged.compareAndSet(false, true)) {
            log.warn("Certificate rotation event watcher is not progressing: status={} reason={}",
                    status, reasonCode);
        }
    }

    private void set(WatcherStatus next, String reason) {
        status = Objects.requireNonNull(next, "next");
        reasonCode = safeReason(reason);
    }

    private static boolean ready(WatcherStatus status) {
        return status == WatcherStatus.IDLE || status == WatcherStatus.APPLIED
                || status == WatcherStatus.RUNTIME_FENCED;
    }

    private static String safeReason(String value) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        return REASON.matcher(normalized).matches()
                ? normalized : "EVENT_WATCHER_UNAVAILABLE";
    }

    private static void increment(AtomicLong counter, long value) {
        counter.updateAndGet(current -> current > Long.MAX_VALUE - value
                ? Long.MAX_VALUE : current + value);
    }

    /** Applies one event through the existing authorization and durable-floor runtime. */
    @FunctionalInterface
    interface EventApplier {
        /** @return bounded runtime application result */
        ControlPlaneCertificateRotationController.ApplyResult apply(
                ControlPlaneCertificateRotationEvent event);
    }

    /** Closed watcher states safe for health and metrics labels. */
    public enum WatcherStatus {
        /** No poll has completed. */
        NEW,
        /** Source has no next page. */
        IDLE,
        /** At least one page completed stage/apply/commit. */
        APPLIED,
        /** Local fleet serving admission intentionally pauses delivery. */
        RUNTIME_FENCED,
        /** Runtime serving admission could not be evaluated. */
        RUNTIME_UNAVAILABLE,
        /** Authenticated source was transiently unavailable. */
        SOURCE_UNAVAILABLE,
        /** Source response or page-chain protocol was rejected. */
        PROTOCOL_REJECTED,
        /** Durable cursor could not be read or mutated. */
        CURSOR_UNAVAILABLE,
        /** Page stage or commit conflicted with the durable cursor. */
        CURSOR_CONFLICT,
        /** At least one independently authorized event was rejected. */
        APPLY_BLOCKED,
        /** An unexpected local watcher invariant failed. */
        WATCHER_UNAVAILABLE,
        /** Watcher has been closed. */
        CLOSED
    }

    /**
     * Fixed-cardinality material-free watcher projection.
     *
     * @param schemaVersion descriptor protocol version
     * @param ready watcher has no local source/cursor/protocol/apply fault
     * @param durableCursor cursor survives process restart
     * @param automaticPolling fixed-delay scheduler is active
     * @param authenticatedProtocol media type and version header are enforced
     * @param sourceMutualTls source transport presents a client certificate
     * @param sourceCertificateIdentityBound both source transport workloads are policy bound
     * @param committedSequence current page sequence without page fingerprint
     * @param stagedPage whether an exact page awaits complete application
     * @param appliedPageCount saturating number of pages committed by this process start
     * @param appliedEventCount saturating number of accepted events committed by this process start
     * @param status closed watcher state
     * @param reasonCode stable payload-free reason
     */
    public record Descriptor(
            String schemaVersion,
            boolean ready,
            boolean durableCursor,
            boolean automaticPolling,
            boolean authenticatedProtocol,
            boolean sourceMutualTls,
            boolean sourceCertificateIdentityBound,
            long committedSequence,
            boolean stagedPage,
            long appliedPageCount,
            long appliedEventCount,
            String status,
            String reasonCode) {

        /** Current watcher descriptor protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateRotationEventWatcherDescriptor.v1";

        /** Rejects contradictory security, readiness, count, and state projections. */
        public Descriptor {
            schemaVersion = Objects.requireNonNullElse(schemaVersion, "").trim();
            status = Objects.requireNonNullElse(status, "").trim();
            reasonCode = Objects.requireNonNullElse(reasonCode, "").trim();
            WatcherStatus parsed;
            try {
                parsed = WatcherStatus.valueOf(status);
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException(
                        "Certificate rotation event watcher descriptor is invalid", invalid);
            }
            if (!SCHEMA_VERSION.equals(schemaVersion) || committedSequence < 0
                    || appliedPageCount < 0 || appliedEventCount < 0
                    || !authenticatedProtocol || !sourceMutualTls
                    || !sourceCertificateIdentityBound || !REASON.matcher(reasonCode).matches()
                    || ready != ControlPlaneCertificateRotationEventWatcher.ready(parsed)) {
                throw new IllegalArgumentException(
                        "Certificate rotation event watcher descriptor is invalid");
            }
        }
    }
}
