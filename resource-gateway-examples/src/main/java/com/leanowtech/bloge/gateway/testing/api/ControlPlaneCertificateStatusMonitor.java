package com.leanowtech.bloge.gateway.testing.api;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded watcher that moves signed status publications into durable and request-path state.
 *
 * <p>Each refresh restores the latest durable publication into the local admission cache, then
 * fetches at most a configured number of contiguous successors. Every successful source response
 * must carry a signed exact source head. The watcher validates response/cursor consistency and
 * durably floors that head before accepting a publication. The exact freshness-bound lag, rather
 * than a local batch heuristic, determines whether catch-up remains incomplete. A source or floor
 * failure never erases a still-fresh cache, while admission hard expiry closes requests without
 * waiting for the watcher.</p>
 */
public final class ControlPlaneCertificateStatusMonitor {

    /** Closed refresh outcomes suitable for health and fixed-cardinality metrics. */
    public enum RefreshStatus {
        /** Durable state was restored and the source had no successor. */
        CURRENT,
        /** One or more contiguous publications were applied. */
        APPLIED,
        /** The bounded catch-up limit was reached and more work may remain. */
        BATCH_LIMIT,
        /** The source could not provide a response. */
        SOURCE_UNAVAILABLE,
        /** The source response violated the strict transport or publication protocol. */
        SOURCE_REJECTED,
        /** The source omitted, expired, rolled back, or forked its exact signed head. */
        SOURCE_HEAD_REJECTED,
        /** The durable floor was unavailable or corrupt. */
        FLOOR_UNAVAILABLE,
        /** A supplied publication failed durable verification or cursor admission. */
        PUBLICATION_REJECTED,
        /** Durable state could not be installed into the local hard-expiry cache. */
        CACHE_REJECTED
    }

    private final ControlPlaneCertificateStatusFloor floor;
    private final ControlPlaneCertificateStatusSourceHeadFloor sourceHeadFloor;
    private final ControlPlaneCertificateStatusSource source;
    private final ControlPlaneCertificateStatusAdmission admission;
    private final Clock clock;
    private final int maximumBatch;
    private final ControlPlaneCertificateStatusTelemetry telemetry;
    private final AtomicReference<Descriptor> latest;

    /**
     * Creates one bounded status watcher.
     *
     * @param floor durable database-time status authority
     * @param sourceHeadFloor durable database-time exact source-head authority
     * @param source untrusted normalized publication source
     * @param admission local non-blocking request-path cache
     * @param clock local health observation clock
     * @param maximumBatch one through 32 successors per refresh
     */
    public ControlPlaneCertificateStatusMonitor(
            ControlPlaneCertificateStatusFloor floor,
            ControlPlaneCertificateStatusSourceHeadFloor sourceHeadFloor,
            ControlPlaneCertificateStatusSource source,
            ControlPlaneCertificateStatusAdmission admission,
            Clock clock,
            int maximumBatch) {
        this(floor, sourceHeadFloor, source, admission, clock, maximumBatch,
                ControlPlaneCertificateStatusTelemetry.noop());
    }

    /**
     * Creates one bounded watcher with fixed-cardinality operational telemetry.
     *
     * @param floor durable database-time status authority
     * @param sourceHeadFloor durable database-time exact source-head authority
     * @param source untrusted normalized publication source
     * @param admission local non-blocking request-path cache
     * @param clock local health observation clock
     * @param maximumBatch one through 32 successors per refresh
     * @param telemetry refresh recorder without source or certificate identity tags
     */
    public ControlPlaneCertificateStatusMonitor(
            ControlPlaneCertificateStatusFloor floor,
            ControlPlaneCertificateStatusSourceHeadFloor sourceHeadFloor,
            ControlPlaneCertificateStatusSource source,
            ControlPlaneCertificateStatusAdmission admission,
            Clock clock,
            int maximumBatch,
            ControlPlaneCertificateStatusTelemetry telemetry) {
        this.floor = Objects.requireNonNull(floor, "floor");
        this.sourceHeadFloor = Objects.requireNonNull(sourceHeadFloor, "sourceHeadFloor");
        this.source = Objects.requireNonNull(source, "source");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        if (!floor.durable() || !sourceHeadFloor.durable()
                || maximumBatch < 1 || maximumBatch > 32) {
            throw new IllegalArgumentException(
                    "Certificate status monitor configuration is invalid");
        }
        this.maximumBatch = maximumBatch;
        this.latest = new AtomicReference<>(descriptor(
                RefreshStatus.FLOOR_UNAVAILABLE, 0, "", 0, null, null));
    }

    /**
     * Restores durable state and performs one bounded source catch-up cycle.
     *
     * @return immutable latest operational descriptor
     */
    public Descriptor refresh() {
        ControlPlaneCertificateStatusFloor.Snapshot current;
        ControlPlaneCertificateStatusSourceHeadFloor.Snapshot sourceHead;
        try {
            current = floor.snapshot();
            sourceHead = sourceHeadFloor.snapshot();
        } catch (RuntimeException unavailable) {
            return publish(RefreshStatus.FLOOR_UNAVAILABLE, 0, "", 0, null, null);
        }
        if (current.initialized()) {
            try {
                admission.refresh(current);
            } catch (RuntimeException rejected) {
                return publish(RefreshStatus.CACHE_REJECTED,
                        current.sequence(), current.publicationFingerprint(), 0,
                        current.expiresAt(), sourceHead);
            }
        }

        int applied = 0;
        for (int index = 0; index < maximumBatch; index++) {
            ControlPlaneCertificateStatusSource.FetchResult fetched;
            try {
                fetched = Objects.requireNonNull(source.fetch(new
                        ControlPlaneCertificateStatusSource.Cursor(current.sequence(),
                        current.publicationFingerprint())), "fetch result");
            } catch (RuntimeException unavailable) {
                return publish(RefreshStatus.SOURCE_UNAVAILABLE,
                        current.sequence(), current.publicationFingerprint(), applied,
                        current.expiresAt(), sourceHead);
            }
            if (fetched.status()
                    == ControlPlaneCertificateStatusSource.FetchStatus.SOURCE_UNAVAILABLE) {
                return publish(RefreshStatus.SOURCE_UNAVAILABLE,
                        current.sequence(), current.publicationFingerprint(), applied,
                        current.expiresAt(), sourceHead);
            }
            if (fetched.status()
                    == ControlPlaneCertificateStatusSource.FetchStatus.PROTOCOL_REJECTED) {
                return publish(RefreshStatus.SOURCE_REJECTED,
                        current.sequence(), current.publicationFingerprint(), applied,
                        current.expiresAt(), sourceHead);
            }
            if (!fetched.exactSourceHead() || !consistent(fetched, current)) {
                return publish(RefreshStatus.SOURCE_HEAD_REJECTED,
                        current.sequence(), current.publicationFingerprint(), applied,
                        current.expiresAt(), sourceHead);
            }
            try {
                sourceHead = sourceHeadFloor.accept(fetched.sourceHead()).snapshot();
            } catch (IllegalArgumentException rejected) {
                return publish(RefreshStatus.SOURCE_HEAD_REJECTED,
                        current.sequence(), current.publicationFingerprint(), applied,
                        current.expiresAt(), sourceHead);
            } catch (RuntimeException unavailable) {
                return publish(RefreshStatus.FLOOR_UNAVAILABLE,
                        current.sequence(), current.publicationFingerprint(), applied,
                        current.expiresAt(), sourceHead);
            }
            if (fetched.status() == ControlPlaneCertificateStatusSource.FetchStatus.UNCHANGED) {
                return publish(applied == 0 ? RefreshStatus.CURRENT : RefreshStatus.APPLIED,
                        current.sequence(), current.publicationFingerprint(), applied,
                        current.expiresAt(), sourceHead);
            }
            ControlPlaneCertificateStatusFloor.Acceptance accepted;
            try {
                accepted = floor.accept(fetched.publication());
            } catch (IllegalArgumentException rejected) {
                return publish(RefreshStatus.PUBLICATION_REJECTED,
                        current.sequence(), current.publicationFingerprint(), applied,
                        current.expiresAt(), sourceHead);
            } catch (RuntimeException unavailable) {
                return publish(RefreshStatus.FLOOR_UNAVAILABLE,
                        current.sequence(), current.publicationFingerprint(), applied,
                        current.expiresAt(), sourceHead);
            }
            current = accepted.snapshot();
            try {
                admission.refresh(current);
            } catch (RuntimeException rejected) {
                return publish(RefreshStatus.CACHE_REJECTED,
                        current.sequence(), current.publicationFingerprint(), applied,
                        current.expiresAt(), sourceHead);
            }
            applied++;
        }
        long exactLag = sourceHead.exactLagFrom(current.sequence(),
                current.publicationFingerprint(), clock.instant());
        return publish(exactLag == 0 ? RefreshStatus.APPLIED
                        : exactLag > 0 ? RefreshStatus.BATCH_LIMIT
                        : RefreshStatus.SOURCE_HEAD_REJECTED,
                current.sequence(), current.publicationFingerprint(), applied,
                current.expiresAt(), sourceHead);
    }

    /** @return last immutable refresh descriptor */
    public Descriptor descriptor() {
        Descriptor observed = latest.get();
        ControlPlaneCertificateStatusAdmission.Descriptor cache = admission.descriptor();
        boolean sourceHeadFresh = observed.sourceHeadVerified()
                && observed.sourceHeadExpiresAt() != null
                && clock.instant().isBefore(observed.sourceHeadExpiresAt());
        if (observed.admissionFresh() == cache.fresh()
                && observed.sourceHeadVerified() == sourceHeadFresh) {
            return observed;
        }
        return new Descriptor(Descriptor.SCHEMA_VERSION, observed.status(),
                observed.durable(), observed.sourceAvailable(), cache.fresh(),
                observed.sequence(), observed.appliedCount(), observed.observedAt(),
                observed.publicationExpiresAt(), sourceHeadFresh,
                observed.sourceHeadSequence(),
                sourceHeadFresh ? observed.sourceHeadLag() : -1L,
                observed.sourceHeadExpiresAt());
    }

    private Descriptor publish(
            RefreshStatus status,
            long sequence,
            String publicationFingerprint,
            int applied,
            Instant expiresAt,
            ControlPlaneCertificateStatusSourceHeadFloor.Snapshot sourceHead) {
        Descriptor descriptor = descriptor(status, sequence, publicationFingerprint,
                applied, expiresAt, sourceHead);
        latest.set(descriptor);
        telemetry.recordRefresh(descriptor, admission.descriptor());
        return descriptor;
    }

    private Descriptor descriptor(
            RefreshStatus status,
            long sequence,
            String publicationFingerprint,
            int applied,
            Instant expiresAt,
            ControlPlaneCertificateStatusSourceHeadFloor.Snapshot sourceHead) {
        Instant observedAt = clock.instant();
        long sourceHeadSequence = sourceHead == null ? 0L : sourceHead.headSequence();
        long sourceHeadLag = sourceHead == null
                ? -1L : sourceHead.exactLagFrom(
                sequence, publicationFingerprint, observedAt);
        return new Descriptor(Descriptor.SCHEMA_VERSION, status,
                floor.durable() && sourceHeadFloor.durable(),
                status == RefreshStatus.CURRENT || status == RefreshStatus.APPLIED
                        || status == RefreshStatus.BATCH_LIMIT
                        || status == RefreshStatus.PUBLICATION_REJECTED
                        || status == RefreshStatus.SOURCE_REJECTED
                        || status == RefreshStatus.SOURCE_HEAD_REJECTED,
                admission.descriptor().fresh(), sequence, applied, observedAt, expiresAt,
                sourceHeadLag >= 0, sourceHeadSequence, sourceHeadLag,
                sourceHead == null ? null : sourceHead.expiresAt());
    }

    private static boolean consistent(
            ControlPlaneCertificateStatusSource.FetchResult fetched,
            ControlPlaneCertificateStatusFloor.Snapshot current) {
        var head = fetched.sourceHead().material();
        if (!head.deploymentScopeId().equals(current.deploymentScopeId())
                || head.headSequence() < current.sequence()
                || head.headSequence() == current.sequence()
                && !head.headPublicationFingerprint().equals(
                current.publicationFingerprint())) {
            return false;
        }
        if (fetched.status() == ControlPlaneCertificateStatusSource.FetchStatus.UNCHANGED) {
            return head.headSequence() == current.sequence();
        }
        ControlPlaneCertificateStatusPublication publication = fetched.publication();
        return publication != null && current.sequence() != Long.MAX_VALUE
                && publication.material().deploymentScopeId().equals(
                current.deploymentScopeId())
                && publication.material().sequence() == current.sequence() + 1
                && publication.material().sequence() <= head.headSequence()
                && (publication.material().sequence() != head.headSequence()
                || publication.materialFingerprint().equals(
                head.headPublicationFingerprint()));
    }

    /**
     * Fixed-cardinality watcher posture.
     *
     * @param schemaVersion descriptor protocol version
     * @param status closed latest refresh outcome
     * @param durable whether the status floor is cross-restart durable
     * @param sourceAvailable whether the last source interaction succeeded
     * @param admissionFresh whether request-path status remains fresh
     * @param sequence latest observed durable cursor
     * @param appliedCount publications applied in the latest bounded cycle
     * @param observedAt local descriptor observation time
     * @param publicationExpiresAt signed hard expiry, nullable before first publication
     * @param sourceHeadVerified whether a fresh exact head proves the current lag
     * @param sourceHeadSequence highest durably verified external source sequence
     * @param sourceHeadLag exact non-negative backlog, or -1 without a fresh proof
     * @param sourceHeadExpiresAt signed source-head hard expiry, nullable before first proof
     */
    public record Descriptor(
            String schemaVersion,
            RefreshStatus status,
            boolean durable,
            boolean sourceAvailable,
            boolean admissionFresh,
            long sequence,
            int appliedCount,
            Instant observedAt,
            Instant publicationExpiresAt,
            boolean sourceHeadVerified,
            long sourceHeadSequence,
            long sourceHeadLag,
            Instant sourceHeadExpiresAt) {

        /** Current watcher descriptor protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateStatusMonitorDescriptor.v2";

        /** Rejects contradictory or unbounded operational projection. */
        public Descriptor {
            schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
            status = Objects.requireNonNull(status, "status");
            observedAt = Objects.requireNonNull(observedAt, "observedAt");
            if (!SCHEMA_VERSION.equals(schemaVersion) || sequence < 0
                    || appliedCount < 0 || appliedCount > 32
                    || admissionFresh && publicationExpiresAt == null
                    || sourceHeadSequence < 0 || sourceHeadLag < -1
                    || sourceHeadVerified != (sourceHeadLag >= 0)
                    || sourceHeadVerified && (sourceHeadExpiresAt == null
                    || sourceHeadSequence < sequence
                    || sourceHeadLag != sourceHeadSequence - sequence)
                    || !durable) {
                throw new IllegalArgumentException(
                        "Certificate status monitor descriptor is invalid");
            }
        }
    }
}
