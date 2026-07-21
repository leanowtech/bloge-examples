package com.leanowtech.bloge.gateway.testing.api;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded watcher that moves signed status publications into durable and request-path state.
 *
 * <p>Each refresh first restores the latest durable head into the local admission cache, then
 * fetches at most a configured number of contiguous successors. Source objects remain untrusted;
 * {@link ControlPlaneCertificateStatusFloor#accept} performs database-time signature and cursor
 * verification. A source or floor failure never erases a still-fresh cache, while hard expiry in
 * {@link ControlPlaneCertificateStatusAdmission} closes requests without waiting for the watcher.
 * </p>
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
        /** The durable floor was unavailable or corrupt. */
        FLOOR_UNAVAILABLE,
        /** A supplied publication failed durable verification or cursor admission. */
        PUBLICATION_REJECTED,
        /** Durable state could not be installed into the local hard-expiry cache. */
        CACHE_REJECTED
    }

    private final ControlPlaneCertificateStatusFloor floor;
    private final ControlPlaneCertificateStatusSource source;
    private final ControlPlaneCertificateStatusAdmission admission;
    private final Clock clock;
    private final int maximumBatch;
    private final AtomicReference<Descriptor> latest;

    /**
     * Creates one bounded status watcher.
     *
     * @param floor durable database-time status authority
     * @param source untrusted normalized publication source
     * @param admission local non-blocking request-path cache
     * @param clock local health observation clock
     * @param maximumBatch one through 32 successors per refresh
     */
    public ControlPlaneCertificateStatusMonitor(
            ControlPlaneCertificateStatusFloor floor,
            ControlPlaneCertificateStatusSource source,
            ControlPlaneCertificateStatusAdmission admission,
            Clock clock,
            int maximumBatch) {
        this.floor = Objects.requireNonNull(floor, "floor");
        this.source = Objects.requireNonNull(source, "source");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (!floor.durable() || maximumBatch < 1 || maximumBatch > 32) {
            throw new IllegalArgumentException(
                    "Certificate status monitor configuration is invalid");
        }
        this.maximumBatch = maximumBatch;
        this.latest = new AtomicReference<>(descriptor(
                RefreshStatus.FLOOR_UNAVAILABLE, 0, 0, null));
    }

    /**
     * Restores durable state and performs one bounded source catch-up cycle.
     *
     * @return immutable latest operational descriptor
     */
    public Descriptor refresh() {
        ControlPlaneCertificateStatusFloor.Snapshot current;
        try {
            current = floor.snapshot();
        } catch (RuntimeException unavailable) {
            return publish(RefreshStatus.FLOOR_UNAVAILABLE, 0, 0, null);
        }
        if (current.initialized()) {
            try {
                admission.refresh(current);
            } catch (RuntimeException rejected) {
                return publish(RefreshStatus.CACHE_REJECTED,
                        current.sequence(), 0, current.expiresAt());
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
                        current.sequence(), applied, current.expiresAt());
            }
            if (fetched.status() == ControlPlaneCertificateStatusSource.FetchStatus.UNCHANGED) {
                return publish(applied == 0 ? RefreshStatus.CURRENT : RefreshStatus.APPLIED,
                        current.sequence(), applied, current.expiresAt());
            }
            if (fetched.status()
                    == ControlPlaneCertificateStatusSource.FetchStatus.SOURCE_UNAVAILABLE) {
                return publish(RefreshStatus.SOURCE_UNAVAILABLE,
                        current.sequence(), applied, current.expiresAt());
            }
            if (fetched.status()
                    == ControlPlaneCertificateStatusSource.FetchStatus.PROTOCOL_REJECTED) {
                return publish(RefreshStatus.SOURCE_REJECTED,
                        current.sequence(), applied, current.expiresAt());
            }
            ControlPlaneCertificateStatusFloor.Acceptance accepted;
            try {
                accepted = floor.accept(fetched.publication());
            } catch (IllegalArgumentException rejected) {
                return publish(RefreshStatus.PUBLICATION_REJECTED,
                        current.sequence(), applied, current.expiresAt());
            } catch (RuntimeException unavailable) {
                return publish(RefreshStatus.FLOOR_UNAVAILABLE,
                        current.sequence(), applied, current.expiresAt());
            }
            current = accepted.snapshot();
            try {
                admission.refresh(current);
            } catch (RuntimeException rejected) {
                return publish(RefreshStatus.CACHE_REJECTED,
                        current.sequence(), applied, current.expiresAt());
            }
            if (accepted.status() == ControlPlaneCertificateStatusFloor
                    .AcceptanceStatus.REPLAYED) {
                return publish(RefreshStatus.CURRENT,
                        current.sequence(), applied, current.expiresAt());
            }
            applied++;
        }
        return publish(RefreshStatus.BATCH_LIMIT,
                current.sequence(), applied, current.expiresAt());
    }

    /** @return last immutable refresh descriptor */
    public Descriptor descriptor() {
        Descriptor observed = latest.get();
        ControlPlaneCertificateStatusAdmission.Descriptor cache = admission.descriptor();
        if (observed.admissionFresh() == cache.fresh()) {
            return observed;
        }
        return new Descriptor(Descriptor.SCHEMA_VERSION, observed.status(),
                observed.durable(), observed.sourceAvailable(), cache.fresh(),
                observed.sequence(), observed.appliedCount(), observed.observedAt(),
                observed.publicationExpiresAt());
    }

    private Descriptor publish(
            RefreshStatus status, long sequence, int applied, Instant expiresAt) {
        Descriptor descriptor = descriptor(status, sequence, applied, expiresAt);
        latest.set(descriptor);
        return descriptor;
    }

    private Descriptor descriptor(
            RefreshStatus status, long sequence, int applied, Instant expiresAt) {
        return new Descriptor(Descriptor.SCHEMA_VERSION, status, floor.durable(),
                status == RefreshStatus.CURRENT || status == RefreshStatus.APPLIED
                        || status == RefreshStatus.BATCH_LIMIT
                        || status == RefreshStatus.PUBLICATION_REJECTED
                        || status == RefreshStatus.SOURCE_REJECTED,
                admission.descriptor().fresh(), sequence, applied, clock.instant(), expiresAt);
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
            Instant publicationExpiresAt) {

        /** Current watcher descriptor protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateStatusMonitorDescriptor.v1";

        /** Rejects contradictory or unbounded operational projection. */
        public Descriptor {
            schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
            status = Objects.requireNonNull(status, "status");
            observedAt = Objects.requireNonNull(observedAt, "observedAt");
            if (!SCHEMA_VERSION.equals(schemaVersion) || sequence < 0
                    || appliedCount < 0 || appliedCount > 32
                    || admissionFresh && publicationExpiresAt == null
                    || !durable) {
                throw new IllegalArgumentException(
                        "Certificate status monitor descriptor is invalid");
            }
        }
    }
}
