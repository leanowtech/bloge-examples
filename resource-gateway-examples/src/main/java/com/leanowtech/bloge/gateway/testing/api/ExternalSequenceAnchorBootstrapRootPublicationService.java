package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationAcquisition;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationAcquisitionCommand;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationAcquisitionDisposition;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationClaim;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationCompletionDisposition;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationFailureDisposition;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationFailureReason;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationReceipt;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationSnapshot;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor.InvocationDisposition;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor.InvocationException;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Database-fenced execution service for ordered bootstrap-root publication.
 *
 * <p>Each call first asks the durable outbox for its oldest eligible sequence. Only the resulting
 * live claim is sent through the fixed-capacity publisher supervisor. A successful exact receipt is
 * committed under that same database fence; if the local lease is lost after remote success, the
 * next worker repeats the content-addressed publication id and cannot create a second intent.</p>
 *
 * <p>Unavailable and malformed responses enter database-time retry backoff. Only an authenticated
 * remote conflict enters permanent quarantine and blocks later sequences. Control-store failures
 * never get rewritten as publisher failures, and all result/snapshot types remain payload-free
 * except for the already public outbox projection explicitly returned by the caller-owned store.</p>
 */
public final class ExternalSequenceAnchorBootstrapRootPublicationService
        implements AutoCloseable {

    private final ExternalSequenceAnchorBootstrapRootPublicationOutbox outbox;
    private final ExternalSequenceAnchorBootstrapRootPublisher publisher;
    private final ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor supervisor;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a root-set publication service with the default fixed-capacity supervisor.
     *
     * @param outbox durable root-set-scoped publication authority
     * @param publisher authenticated remote delivery adapter owned by this service
     */
    public ExternalSequenceAnchorBootstrapRootPublicationService(
            ExternalSequenceAnchorBootstrapRootPublicationOutbox outbox,
            ExternalSequenceAnchorBootstrapRootPublisher publisher) {
        this(outbox, publisher,
                new ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor());
    }

    /**
     * Creates a service with an explicit publisher-call policy.
     *
     * @param outbox durable root-set-scoped publication authority
     * @param publisher authenticated remote delivery adapter owned by this service
     * @param policy fixed-capacity local call limits
     */
    public ExternalSequenceAnchorBootstrapRootPublicationService(
            ExternalSequenceAnchorBootstrapRootPublicationOutbox outbox,
            ExternalSequenceAnchorBootstrapRootPublisher publisher,
            ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor.Policy policy) {
        this(outbox, publisher,
                new ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor(policy));
    }

    /** Package-visible seam for supervisor failure and occupancy tests. */
    ExternalSequenceAnchorBootstrapRootPublicationService(
            ExternalSequenceAnchorBootstrapRootPublicationOutbox outbox,
            ExternalSequenceAnchorBootstrapRootPublisher publisher,
            ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor supervisor) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        if (!outbox.durablePublicationOutbox()) {
            throw new IllegalArgumentException(
                    "Automatic bootstrap-root publication requires a durable outbox");
        }
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
    }

    /**
     * Acquires and executes at most one oldest eligible publication.
     *
     * @param workerId stable pre-authenticated publisher worker identity
     * @param leaseDurationSeconds database lease at least two seconds longer than call deadline
     * @return one database-authoritative wait, failure, quarantine, or completion result
     */
    public ExecutionResult publishNext(String workerId, long leaseDurationSeconds) {
        if (closed.get()) {
            throw new IllegalStateException(
                    "Bootstrap-root publication service is closed");
        }
        PublicationAcquisitionCommand command = new PublicationAcquisitionCommand(
                PublicationAcquisitionCommand.SCHEMA_VERSION,
                workerId, leaseDurationSeconds);
        if (leaseDurationSeconds < supervisor.minimumLeaseDurationSeconds()) {
            throw new IllegalArgumentException(
                    "Bootstrap-root publication lease is shorter than its call deadline margin");
        }
        PublicationAcquisition acquisition;
        try {
            acquisition = outbox.acquirePublication(command);
        } catch (RuntimeException controlFailure) {
            return new ExecutionResult(ExecutionStatus.CONTROL_UNAVAILABLE,
                    null, null);
        }
        if (acquisition.disposition() != PublicationAcquisitionDisposition.ACQUIRED) {
            return waitResult(acquisition);
        }
        PublicationClaim claim = acquisition.claim();
        PublicationReceipt receipt;
        try {
            receipt = supervisor.publish(publisher, claim.request());
        } catch (InvocationException failure) {
            return invocationFailure(claim, acquisition.snapshot(), failure.disposition());
        }
        if (!bound(claim, receipt)) {
            return recordFailure(claim, acquisition.snapshot(),
                    PublicationFailureReason.RESPONSE_INVALID,
                    ExecutionStatus.RESPONSE_INVALID);
        }
        try {
            var completed = outbox.completePublication(claim, receipt);
            return switch (completed.disposition()) {
                case PUBLISHED -> new ExecutionResult(
                        ExecutionStatus.PUBLISHED, completed.snapshot(), null);
                case IDEMPOTENT_REPLAY -> new ExecutionResult(
                        ExecutionStatus.IDEMPOTENT_REPLAY, completed.snapshot(), null);
                case FENCE_REJECTED -> new ExecutionResult(
                        ExecutionStatus.FENCE_REJECTED, completed.snapshot(), null);
                case RECEIPT_CONFLICT -> new ExecutionResult(
                        ExecutionStatus.RECEIPT_CONFLICT, completed.snapshot(), null);
            };
        } catch (RuntimeException controlFailure) {
            return new ExecutionResult(ExecutionStatus.CONTROL_UNAVAILABLE,
                    acquisition.snapshot(), null);
        }
    }

    /**
     * Returns payload-free adapter and call-supervisor health without remote I/O.
     *
     * @return immutable process-local service projection
     */
    public Snapshot snapshot() {
        return new Snapshot(Snapshot.SCHEMA_VERSION, closed.get(),
                publisher.descriptor(), publisher.snapshot(), supervisor.snapshot());
    }

    /**
     * Returns the minimum database lease compatible with the local publisher deadline.
     *
     * @return whole-second call deadline plus two-second terminal-commit margin
     */
    public long minimumLeaseDurationSeconds() {
        return supervisor.minimumLeaseDurationSeconds();
    }

    /**
     * Rejects new work, requests local call cancellation, then closes the owned publisher.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        supervisor.close();
        publisher.close();
    }

    private ExecutionResult invocationFailure(
            PublicationClaim claim,
            PublicationSnapshot snapshot,
            InvocationDisposition disposition) {
        return switch (disposition) {
            case AUTHENTICATED_CONFLICT -> recordFailure(claim, snapshot,
                    PublicationFailureReason.AUTHENTICATED_CONFLICT,
                    ExecutionStatus.AUTHENTICATED_CONFLICT);
            case INVALID_RESPONSE -> recordFailure(claim, snapshot,
                    PublicationFailureReason.RESPONSE_INVALID,
                    ExecutionStatus.RESPONSE_INVALID);
            case UNAVAILABLE, TIMED_OUT, SATURATED, CLOSED, CALLER_INTERRUPTED ->
                    recordFailure(claim, snapshot,
                            PublicationFailureReason.PUBLISHER_UNAVAILABLE,
                            ExecutionStatus.PUBLISHER_UNAVAILABLE);
        };
    }

    private ExecutionResult recordFailure(
            PublicationClaim claim,
            PublicationSnapshot snapshot,
            PublicationFailureReason reason,
            ExecutionStatus status) {
        try {
            var failed = outbox.releasePublication(claim, reason);
            if (failed.disposition() == PublicationFailureDisposition.FENCE_REJECTED) {
                return new ExecutionResult(
                        ExecutionStatus.FENCE_REJECTED, failed.snapshot(), null);
            }
            boolean expectedQuarantine = reason
                    == PublicationFailureReason.AUTHENTICATED_CONFLICT;
            if (expectedQuarantine != (failed.disposition()
                    == PublicationFailureDisposition.QUARANTINED)) {
                return new ExecutionResult(ExecutionStatus.CONTROL_UNAVAILABLE,
                        failed.snapshot(), null);
            }
            return new ExecutionResult(status, failed.snapshot(), null);
        } catch (RuntimeException controlFailure) {
            return new ExecutionResult(
                    ExecutionStatus.CONTROL_UNAVAILABLE, snapshot, null);
        }
    }

    private static ExecutionResult waitResult(PublicationAcquisition acquisition) {
        ExecutionStatus status = switch (acquisition.disposition()) {
            case NO_WORK -> ExecutionStatus.NO_WORK;
            case BUSY -> ExecutionStatus.BUSY;
            case RETRY_DELAYED -> ExecutionStatus.RETRY_DELAYED;
            case ATTEMPT_LIMIT_REACHED -> ExecutionStatus.ATTEMPT_LIMIT_REACHED;
            case QUARANTINED -> ExecutionStatus.QUARANTINED;
            case ACQUIRED -> throw new IllegalArgumentException(
                    "An acquired publication must be executed");
        };
        return new ExecutionResult(status, acquisition.snapshot(), acquisition.eligibleAt());
    }

    private static boolean bound(PublicationClaim claim, PublicationReceipt receipt) {
        return receipt != null
                && claim.publicationId().equals(receipt.publicationId())
                && claim.request().sequence() == receipt.sequence()
                && claim.request().bundleFingerprint().equals(receipt.bundleFingerprint())
                && claim.request().headMaterialFingerprint().equals(
                receipt.headMaterialFingerprint());
    }

    /** One publication worker outcome. */
    public enum ExecutionStatus {
        /** No unpublished bundle exists. */
        NO_WORK,

        /** Another worker owns the oldest bundle's live lease. */
        BUSY,

        /** Database-time retry backoff has not elapsed. */
        RETRY_DELAYED,

        /** Durable automatic attempt budget is exhausted. */
        ATTEMPT_LIMIT_REACHED,

        /** A prior authenticated remote conflict blocks the root set. */
        QUARANTINED,

        /** This call committed a newly applied remote receipt. */
        PUBLISHED,

        /** The exact remote receipt was already durably committed. */
        IDEMPOTENT_REPLAY,

        /** The database claim was no longer live at terminal mutation. */
        FENCE_REJECTED,

        /** A different receipt was already committed for the publication id. */
        RECEIPT_CONFLICT,

        /** Publisher transport, timeout, saturation, or adapter execution was unavailable. */
        PUBLISHER_UNAVAILABLE,

        /** Publisher response failed strict protocol, signature, or request binding. */
        RESPONSE_INVALID,

        /** This call authenticated a conflict and permanently quarantined the oldest bundle. */
        AUTHENTICATED_CONFLICT,

        /** Durable outbox control could not safely complete the requested mutation. */
        CONTROL_UNAVAILABLE
    }

    /**
     * Immutable execution result.
     *
     * @param status database wait, bounded failure, quarantine, or completion class
     * @param snapshot current or last safely observed durable projection
     * @param eligibleAt database wait horizon only for busy or retry-delayed outcomes
     */
    public record ExecutionResult(
            ExecutionStatus status,
            PublicationSnapshot snapshot,
            Instant eligibleAt) {

        /** Enforces result-dependent projection and timing presence. */
        public ExecutionResult {
            status = Objects.requireNonNull(status, "status");
            boolean noProjection = status == ExecutionStatus.NO_WORK
                    || status == ExecutionStatus.CONTROL_UNAVAILABLE
                    && snapshot == null;
            boolean timed = status == ExecutionStatus.BUSY
                    || status == ExecutionStatus.RETRY_DELAYED;
            if (noProjection != (snapshot == null)
                    || timed != (eligibleAt != null)) {
                throw new IllegalArgumentException(
                        "Bootstrap-root publication execution result is invalid");
            }
        }
    }

    /**
     * Payload-free service snapshot.
     *
     * @param schemaVersion snapshot protocol generation
     * @param closed whether new work is rejected
     * @param descriptor publisher capability without identity
     * @param publisher publisher aggregate runtime state
     * @param supervisor fixed-capacity local call state
     */
    public record Snapshot(
            String schemaVersion,
            boolean closed,
            ExternalSequenceAnchorBootstrapRootPublisher.Descriptor descriptor,
            ExternalSequenceAnchorBootstrapRootPublisher.Snapshot publisher,
            ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor.Snapshot supervisor) {

        /** Current publication service snapshot generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootPublicationServiceSnapshot.v1";

        /** Enforces complete aggregate-only child projections. */
        public Snapshot {
            schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            publisher = Objects.requireNonNull(publisher, "publisher");
            supervisor = Objects.requireNonNull(supervisor, "supervisor");
            if (!SCHEMA_VERSION.equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "Bootstrap-root publication service snapshot is invalid");
            }
        }
    }
}
