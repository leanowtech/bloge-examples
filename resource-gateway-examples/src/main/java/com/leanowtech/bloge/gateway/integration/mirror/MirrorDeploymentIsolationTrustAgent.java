package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Pulls, verifies, and atomically publishes deployment-isolation trust to a local runtime.
 *
 * <p>The agent is the only cache writer. Active refreshes require a current root-verified authority
 * publication and a signature-verified attestation. Revocation is denial-only and deliberately
 * does not depend on positive authority availability, so a valid current revocation can replace the
 * cache even after its signing authority expires. Runtime readers may use the last active snapshot
 * only until its hard local deadline; that deadline bounds missed-revocation exposure during a
 * control-plane outage.</p>
 *
 * <p>First bootstrap is never trust-on-first-use. All authority, attestation, and status coordinates
 * must equal an operator-provisioned floor. Later replacements enforce contiguous attestation and
 * status transitions plus the authority publication's signed predecessor chain.</p>
 */
public final class MirrorDeploymentIsolationTrustAgent implements AutoCloseable {
    private final Clock clock;
    private final MirrorDeploymentIsolationTrustSource source;
    private final MirrorDeploymentIsolationAgentCache cache;
    private final MirrorDeploymentIsolationAgentSnapshotIntegrity snapshotIntegrity;
    private final MirrorDeploymentIsolationAuthorityKeySetIntegrity authorityIntegrity;
    private final MirrorDeploymentIsolationAttestationBundleIntegrity bundleIntegrity;
    private final MirrorDeploymentIsolationAttestationIntegrity attestationIntegrity;
    private final TrustPolicy trustPolicy;
    private final Settings settings;
    private final Object refreshLock = new Object();
    private final ScheduledThreadPoolExecutor scheduler;

    private volatile RefreshState state = RefreshState.empty();
    private volatile boolean closed;

    /**
     * Creates an agent, validates any durable generation, and starts bounded background refresh.
     *
     * @param clock trusted deployment clock
     * @param source authenticated remote current-state source
     * @param cache durable single-writer atomic cache
     * @param snapshotIntegrity cache fingerprint boundary
     * @param authorityIntegrity authority publication verifier
     * @param bundleIntegrity attestation bundle fingerprint verifier
     * @param attestationIntegrity isolation-attestation signature verifier
     * @param trustPolicy locally provisioned roots, bindings, and bootstrap floor
     * @param settings refresh interval and hard positive-admission age
     */
    public MirrorDeploymentIsolationTrustAgent(
            Clock clock,
            MirrorDeploymentIsolationTrustSource source,
            MirrorDeploymentIsolationAgentCache cache,
            MirrorDeploymentIsolationAgentSnapshotIntegrity snapshotIntegrity,
            MirrorDeploymentIsolationAuthorityKeySetIntegrity authorityIntegrity,
            MirrorDeploymentIsolationAttestationBundleIntegrity bundleIntegrity,
            MirrorDeploymentIsolationAttestationIntegrity attestationIntegrity,
            TrustPolicy trustPolicy,
            Settings settings) {
        this(clock, source, cache, snapshotIntegrity, authorityIntegrity, bundleIntegrity,
                attestationIntegrity, trustPolicy, settings, true);
    }

    MirrorDeploymentIsolationTrustAgent(
            Clock clock,
            MirrorDeploymentIsolationTrustSource source,
            MirrorDeploymentIsolationAgentCache cache,
            MirrorDeploymentIsolationAgentSnapshotIntegrity snapshotIntegrity,
            MirrorDeploymentIsolationAuthorityKeySetIntegrity authorityIntegrity,
            MirrorDeploymentIsolationAttestationBundleIntegrity bundleIntegrity,
            MirrorDeploymentIsolationAttestationIntegrity attestationIntegrity,
            TrustPolicy trustPolicy,
            Settings settings,
            boolean startScheduler) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.source = Objects.requireNonNull(source, "source");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.snapshotIntegrity = Objects.requireNonNull(
                snapshotIntegrity, "snapshotIntegrity");
        this.authorityIntegrity = Objects.requireNonNull(
                authorityIntegrity, "authorityIntegrity");
        this.bundleIntegrity = Objects.requireNonNull(bundleIntegrity, "bundleIntegrity");
        this.attestationIntegrity = Objects.requireNonNull(
                attestationIntegrity, "attestationIntegrity");
        this.trustPolicy = Objects.requireNonNull(trustPolicy, "trustPolicy");
        this.settings = Objects.requireNonNull(settings, "settings").validated(
                source.descriptor());
        if (!cache.durable()) {
            throw new IllegalArgumentException(
                    "deployment isolation trust agent requires a durable cache");
        }
        Optional<MirrorDeploymentIsolationAgentSnapshot> restored = cache.current();
        restored.ifPresent(this::verifyRestored);
        if (restored.isPresent()) {
            state = RefreshState.restored(restored.orElseThrow(), clock.instant());
        }
        this.scheduler = startScheduler ? scheduler() : null;
    }

    /**
     * Performs one complete remote refresh and atomic cache replacement.
     *
     * @return true only when a complete active or revoked generation was committed
     */
    public boolean refreshNow() {
        synchronized (refreshLock) {
            if (closed) {
                return false;
            }
            Instant now = clock.instant();
            state = state.attempted(now);
            try {
                Optional<MirrorDeploymentIsolationAgentSnapshot> previous = cache.current();
                MirrorDeploymentIsolationAttestationBundle bundle =
                        source.latestAttestation();
                requireBundle(bundle, now);
                requireTransition(previous.orElse(null), bundle);

                MirrorDeploymentIsolationAuthorityKeySetPublication authority = null;
                Instant validUntil = now.plus(settings.maximumSnapshotAge());
                if (bundle.active()) {
                    authority = source.currentAuthority(bundle.authorityKeySetRef());
                    verifyActive(previous.orElse(null), authority, bundle, now);
                    validUntil = earliest(validUntil,
                            authority.material().expiresAt(),
                            bundle.attestation().material().expiresAt());
                    if (!validUntil.isAfter(now)) {
                        throw rejected("AGENT_ACTIVE_WINDOW_EXHAUSTED");
                    }
                } else if (previous.isPresent()
                        && previous.orElseThrow().authorityPublication() != null
                        && previous.orElseThrow().authorityPublication().artifactRef().equals(
                        bundle.authorityKeySetRef())) {
                    authority = previous.orElseThrow().authorityPublication();
                }

                long generation = previous.map(
                        MirrorDeploymentIsolationAgentSnapshot::cacheGeneration).orElse(0L);
                if (generation == Long.MAX_VALUE) {
                    throw rejected("AGENT_CACHE_GENERATION_EXHAUSTED");
                }
                MirrorDeploymentIsolationAgentSnapshot candidate = snapshotIntegrity.snapshot(
                        generation + 1L, now, validUntil, authority, bundle);
                String expected = previous.map(
                        MirrorDeploymentIsolationAgentSnapshot::snapshotFingerprint).orElse("");
                MirrorDeploymentIsolationAgentSnapshot committed = cache.replace(
                        expected, candidate);
                state = state.succeeded(committed, now);
                return true;
            } catch (RuntimeException failure) {
                state = state.failed(reasonCode(failure), now);
                return false;
            }
        }
    }

    /**
     * Returns the current verified cache generation without remote I/O.
     *
     * @return current generation, or empty before bootstrap
     */
    public Optional<MirrorDeploymentIsolationAgentSnapshot> current() {
        Optional<MirrorDeploymentIsolationAgentSnapshot> current = cache.current();
        current.ifPresent(snapshot -> {
            if (!snapshotIntegrity.canonicalSnapshotVerified(snapshot)) {
                throw new TrustUnavailableException("AGENT_CACHE_INTEGRITY_INVALID");
            }
        });
        return current;
    }

    /**
     * Returns the current active generation only while its hard freshness fence remains open.
     *
     * @return current positively admissible snapshot
     * @throws TrustUnavailableException when absent, revoked, expired, or corrupt
     */
    public MirrorDeploymentIsolationAgentSnapshot requireActive() {
        MirrorDeploymentIsolationAgentSnapshot snapshot = current().orElseThrow(
                () -> new TrustUnavailableException("AGENT_CACHE_UNAVAILABLE"));
        if (!snapshot.usableAt(clock.instant())) {
            throw new TrustUnavailableException(snapshot.revoked()
                    ? "AGENT_CACHE_REVOKED" : "AGENT_CACHE_EXPIRED");
        }
        return snapshot;
    }

    /**
     * Projects fixed-cardinality refresh and admission health without remote I/O.
     *
     * @return fixed-cardinality, payload-free refresh and admission health
     */
    public Observation observation() {
        RefreshState observed = state;
        Optional<MirrorDeploymentIsolationAgentSnapshot> cached;
        try {
            cached = current();
        } catch (RuntimeException invalid) {
            return observation(false, "CACHE_INVALID", null, observed);
        }
        if (closed) {
            return observation(false, "CLOSED", cached.orElse(null), observed);
        }
        if (cached.isEmpty()) {
            return observation(false,
                    observed.refreshFailureCount() == 0 ? "BOOTSTRAPPING" : "UNAVAILABLE",
                    null, observed);
        }
        MirrorDeploymentIsolationAgentSnapshot snapshot = cached.orElseThrow();
        if (snapshot.revoked()) {
            return observation(false, "REVOKED", snapshot, observed);
        }
        if (!snapshot.usableAt(clock.instant())) {
            return observation(false, "EXPIRED", snapshot, observed);
        }
        return observation(true,
                observed.lastFailureCode().isBlank() ? "ACTIVE" : "ACTIVE_REFRESH_DEGRADED",
                snapshot, observed);
    }

    /** Stops background refresh; the durable cache remains available for forensic inspection. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void verifyRestored(MirrorDeploymentIsolationAgentSnapshot snapshot) {
        if (!snapshotIntegrity.canonicalSnapshotVerified(snapshot)) {
            throw new IllegalStateException(
                    "deployment isolation trust cache integrity is invalid");
        }
        requireBundle(snapshot.attestationBundle(), clock.instant());
        if (snapshot.attestationBundle().active()) {
            verifyActive(snapshot, snapshot.authorityPublication(),
                    snapshot.attestationBundle(), clock.instant());
        }
    }

    private void verifyActive(
            MirrorDeploymentIsolationAgentSnapshot previous,
            MirrorDeploymentIsolationAuthorityKeySetPublication authority,
            MirrorDeploymentIsolationAttestationBundle bundle,
            Instant now) {
        if (authority == null || !authority.artifactRef().equals(
                bundle.authorityKeySetRef())) {
            throw rejected("AGENT_AUTHORITY_REFERENCE_MISMATCH");
        }
        MirrorDeploymentIsolationAuthorityKeySetIntegrity.TrustedFloor floor =
                authorityFloor(previous);
        var verifiedAuthority = authorityIntegrity.verify(authority,
                trustPolicy.binding(), trustPolicy.bootstrapRoots(), floor, now);
        if (!verifiedAuthority.verified()) {
            throw rejected("AGENT_AUTHORITY_" + verifiedAuthority.reasonCode());
        }
        var key = verifiedAuthority.attestationKey(bundle.attestation().seal().keyId())
                .orElseThrow(() -> rejected("AGENT_ATTESTATION_KEY_NOT_CURRENT"));
        var attestation = attestationIntegrity.verifyAt(bundle.attestation(), key,
                trustPolicy.binding().deployment(), now);
        if (!attestation.verified()) {
            throw rejected("AGENT_ATTESTATION_" + attestation.reasonCode());
        }
    }

    private MirrorDeploymentIsolationAuthorityKeySetIntegrity.TrustedFloor authorityFloor(
            MirrorDeploymentIsolationAgentSnapshot previous) {
        if (previous != null && previous.authorityPublication() != null) {
            var authority = previous.authorityPublication();
            return new MirrorDeploymentIsolationAuthorityKeySetIntegrity.TrustedFloor(
                    authority.material().keySetId(), authority.material().generation(),
                    authority.publicationFingerprint());
        }
        BootstrapFloor floor = trustPolicy.bootstrapFloor();
        return new MirrorDeploymentIsolationAuthorityKeySetIntegrity.TrustedFloor(
                trustPolicy.binding().keySetId(), floor.authorityGeneration(),
                floor.authorityPublicationFingerprint());
    }

    private void requireBundle(
            MirrorDeploymentIsolationAttestationBundle bundle, Instant now) {
        if (!bundleIntegrity.canonicalBundleVerified(bundle)
                || !trustPolicy.binding().scope().equals(bundle.scope())
                || !trustPolicy.binding().deployment().equals(
                bundle.attestation().material().deployment())
                || !trustPolicy.binding().keySetId().equals(
                bundle.authorityKeySetRef().id())
                || !trustPolicy.attestationId().equals(
                bundle.attestation().material().attestationId())
                || bundle.status().material().effectiveAt().isAfter(
                now.plus(settings.clockSkew()))) {
            throw rejected("AGENT_ATTESTATION_BUNDLE_INVALID");
        }
    }

    private void requireTransition(
            MirrorDeploymentIsolationAgentSnapshot previous,
            MirrorDeploymentIsolationAttestationBundle next) {
        if (previous == null) {
            requireBootstrapOrCoordinates(next);
            return;
        }
        MirrorDeploymentIsolationAttestationBundle prior = previous.attestationBundle();
        long priorRevision = prior.attestation().material().revision();
        long nextRevision = next.attestation().material().revision();
        if (nextRevision == priorRevision) {
            if (!next.attestation().attestationFingerprint().equals(
                    prior.attestation().attestationFingerprint())) {
                throw rejected("AGENT_ATTESTATION_REVISION_FORK");
            }
            long priorStatus = prior.status().material().statusRevision();
            long nextStatus = next.status().material().statusRevision();
            if (nextStatus == priorStatus) {
                if (!next.status().statusFingerprint().equals(
                        prior.status().statusFingerprint())) {
                    throw rejected("AGENT_ATTESTATION_STATUS_FORK");
                }
                return;
            }
            if (nextStatus != priorStatus + 1L || !prior.active() || next.active()
                    || !next.status().material().previousStatusFingerprint().equals(
                    prior.status().statusFingerprint())) {
                throw rejected("AGENT_ATTESTATION_STATUS_DISCONTINUITY");
            }
            return;
        }
        if (priorRevision == Long.MAX_VALUE || nextRevision != priorRevision + 1L
                || !next.active()
                || next.status().material().statusRevision() != 1L) {
            throw rejected("AGENT_ATTESTATION_REVISION_DISCONTINUITY");
        }
    }

    private void requireBootstrapOrCoordinates(
            MirrorDeploymentIsolationAttestationBundle bundle) {
        BootstrapFloor floor = trustPolicy.bootstrapFloor();
        if (bundle.authorityKeySetRef().revision() != floor.authorityGeneration()
                || !bundle.authorityKeySetRef().fingerprint().equals(
                floor.authorityPublicationFingerprint())
                || bundle.attestation().material().revision()
                != floor.attestationRevision()
                || !bundle.attestation().attestationFingerprint().equals(
                floor.attestationFingerprint())
                || bundle.status().material().statusRevision() != floor.statusRevision()
                || !bundle.status().statusFingerprint().equals(floor.statusFingerprint())) {
            throw rejected("AGENT_BOOTSTRAP_FLOOR_MISMATCH");
        }
    }

    private Observation observation(
            boolean available,
            String status,
            MirrorDeploymentIsolationAgentSnapshot snapshot,
            RefreshState refresh) {
        return new Observation(Observation.SCHEMA_VERSION, available, status,
                snapshot == null ? 0L : snapshot.cacheGeneration(),
                snapshot == null ? 0L
                        : snapshot.attestationBundle().authorityKeySetRef().revision(),
                snapshot == null ? 0L
                        : snapshot.attestationBundle().attestation().material().revision(),
                snapshot == null ? 0L
                        : snapshot.attestationBundle().status().material().statusRevision(),
                snapshot == null ? null : snapshot.refreshedAt(),
                snapshot == null ? null : snapshot.validUntil(),
                refresh.lastAttemptAt(), refresh.lastSuccessfulRefreshAt(),
                refresh.refreshSuccessCount(), refresh.refreshFailureCount(),
                refresh.lastFailureCode(), settings.refreshInterval().toSeconds(),
                settings.maximumSnapshotAge().toSeconds(),
                settings.maximumRevocationConvergence().toSeconds(), cache.durable(),
                source.descriptor().mutualTls(),
                source.descriptor().certificateIdentityBound());
    }

    private ScheduledThreadPoolExecutor scheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task,
                    "resource-gateway-mirror-isolation-trust-refresh");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        long interval = settings.refreshInterval().toMillis();
        long initialDelay = ThreadLocalRandom.current().nextLong(
                Math.max(1L, interval / 2L), interval + 1L);
        executor.scheduleWithFixedDelay(this::refreshSafely, initialDelay, interval,
                TimeUnit.MILLISECONDS);
        return executor;
    }

    private void refreshSafely() {
        try {
            refreshNow();
        } catch (RuntimeException failure) {
            synchronized (refreshLock) {
                state = state.failed("AGENT_REFRESH_TASK_FAILED", clock.instant());
            }
        }
    }

    private static Instant earliest(Instant first, Instant second, Instant third) {
        Instant result = first.isBefore(second) ? first : second;
        return result.isBefore(third) ? result : third;
    }

    private static String reasonCode(RuntimeException failure) {
        if (failure instanceof AgentRejectedException rejected) {
            return rejected.reasonCode();
        }
        if (failure instanceof HttpMirrorDeploymentIsolationTrustSource.SourceException source) {
            return source.reasonCode();
        }
        if (failure instanceof AtomicFileMirrorDeploymentIsolationAgentCache
                .ConcurrentCacheReplacementException) {
            return "AGENT_CACHE_CONCURRENT_REPLACEMENT";
        }
        return "AGENT_REFRESH_FAILED";
    }

    private static AgentRejectedException rejected(String reasonCode) {
        return new AgentRejectedException(reasonCode);
    }

    /**
     * Locally provisioned trust policy and non-TOFU bootstrap floor.
     *
     * @param attestationId exact governed attestation stream
     * @param binding exact scope, deployment, issuer, key-set, root, and policy binding
     * @param bootstrapRoots locally pinned independent bootstrap-root keys
     * @param bootstrapFloor exact first accepted remote head
     */
    public record TrustPolicy(
            String attestationId,
            MirrorDeploymentIsolationAuthorityKeySetIntegrity.ExpectedBinding binding,
            List<MirrorDeploymentIsolationAuthorityKeySetIntegrity.RootVerificationKey>
                    bootstrapRoots,
            BootstrapFloor bootstrapFloor) {
        /** Validates a bounded immutable local trust policy. */
        public TrustPolicy {
            attestationId = normalized(attestationId);
            binding = Objects.requireNonNull(binding, "binding");
            bootstrapRoots = bootstrapRoots == null ? List.of()
                    : List.copyOf(bootstrapRoots);
            bootstrapFloor = Objects.requireNonNull(bootstrapFloor, "bootstrapFloor");
            if (!attestationId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,254}")
                    || bootstrapRoots.isEmpty()
                    || bootstrapRoots.size()
                    > MirrorDeploymentIsolationAuthorityKeySetPublication
                    .MAXIMUM_ROOT_SIGNATURES) {
                throw new IllegalArgumentException(
                        "deployment isolation agent trust policy is invalid");
            }
        }
    }

    /**
     * Exact operator-provisioned remote head required on an empty cache.
     *
     * @param authorityGeneration exact authority publication generation
     * @param authorityPublicationFingerprint exact authority publication fingerprint
     * @param attestationRevision exact external attestation revision
     * @param attestationFingerprint exact attestation fingerprint
     * @param statusRevision exact local status revision
     * @param statusFingerprint exact local status fingerprint
     */
    public record BootstrapFloor(
            long authorityGeneration,
            String authorityPublicationFingerprint,
            long attestationRevision,
            String attestationFingerprint,
            long statusRevision,
            String statusFingerprint) {
        /** Validates complete positive coordinates without treating them as trusted remotely. */
        public BootstrapFloor {
            authorityPublicationFingerprint = fingerprint(
                    authorityPublicationFingerprint, "authorityPublicationFingerprint");
            attestationFingerprint = fingerprint(
                    attestationFingerprint, "attestationFingerprint");
            statusFingerprint = fingerprint(statusFingerprint, "statusFingerprint");
            if (authorityGeneration < 1 || attestationRevision < 1
                    || statusRevision < 1) {
                throw new IllegalArgumentException(
                        "deployment isolation agent bootstrap floor is invalid");
            }
        }
    }

    /**
     * Bounded refresh and missed-revocation exposure policy.
     *
     * @param refreshInterval fixed delay between completed refresh attempts
     * @param maximumSnapshotAge hard upper bound for positive cached admission
     * @param clockSkew maximum accepted future status-effective-time skew
     */
    public record Settings(
            Duration refreshInterval,
            Duration maximumSnapshotAge,
            Duration clockSkew) {
        /** Validates absolute bounds; source-specific request bounds are checked by the agent. */
        public Settings {
            refreshInterval = bounded(refreshInterval, Duration.ofSeconds(1),
                    Duration.ofMinutes(30), "refreshInterval");
            maximumSnapshotAge = bounded(maximumSnapshotAge, Duration.ofSeconds(3),
                    Duration.ofHours(1), "maximumSnapshotAge");
            clockSkew = bounded(clockSkew, Duration.ZERO, Duration.ofMinutes(5),
                    "clockSkew");
        }

        private Settings validated(MirrorDeploymentIsolationTrustSource.Descriptor source) {
            Duration convergence = refreshInterval.plusMillis(
                    Math.multiplyExact(source.requestTimeoutMillis(), 2L));
            if (maximumSnapshotAge.compareTo(convergence) < 0) {
                throw new IllegalArgumentException(
                        "maximum snapshot age must cover refresh plus two requests");
            }
            return this;
        }

        /**
         * Returns the hard missed-revocation exposure bound during remote outage.
         *
         * @return maximum positive snapshot age
         */
        public Duration maximumRevocationConvergence() {
            return maximumSnapshotAge;
        }
    }

    /**
     * Fixed-cardinality deployment-agent health and SLO projection.
     *
     * @param schemaVersion observation protocol version
     * @param available whether the current cache may positively admit work
     * @param status bounded aggregate agent state
     * @param cacheGeneration current local cache generation, or zero before bootstrap
     * @param authorityGeneration current authority generation, or zero before bootstrap
     * @param attestationRevision current attestation revision, or zero before bootstrap
     * @param statusRevision current attestation-status revision, or zero before bootstrap
     * @param refreshedAt cache acceptance time, or null before bootstrap
     * @param validUntil exclusive positive-admission deadline, or null before bootstrap
     * @param lastAttemptAt latest refresh-attempt time, or null before the first attempt
     * @param lastSuccessfulRefreshAt latest successful refresh time, or null before success
     * @param refreshSuccessCount process-local successful refresh count
     * @param refreshFailureCount process-local failed refresh count
     * @param lastFailureCode latest stable payload-free failure, or blank after success
     * @param refreshIntervalSeconds configured fixed-delay refresh interval
     * @param maximumSnapshotAgeSeconds configured hard positive-admission age
     * @param maximumRevocationConvergenceSeconds hard missed-revocation exposure bound
     * @param durableAtomicCache whether accepted generations survive process restart atomically
     * @param mutualTls whether the remote source requires a client certificate
     * @param certificateIdentityBound whether both TLS peers have exact workload identities
     */
    public record Observation(
            String schemaVersion,
            boolean available,
            String status,
            long cacheGeneration,
            long authorityGeneration,
            long attestationRevision,
            long statusRevision,
            Instant refreshedAt,
            Instant validUntil,
            Instant lastAttemptAt,
            Instant lastSuccessfulRefreshAt,
            long refreshSuccessCount,
            long refreshFailureCount,
            String lastFailureCode,
            long refreshIntervalSeconds,
            long maximumSnapshotAgeSeconds,
            long maximumRevocationConvergenceSeconds,
            boolean durableAtomicCache,
            boolean mutualTls,
            boolean certificateIdentityBound) {
        /** Current health projection version. */
        public static final String SCHEMA_VERSION =
                "resourceGateway.mirrorDeploymentIsolationTrustAgentObservation.v1";

        /** Enforces bounded values without exposing scope, ids, paths, or fingerprints. */
        public Observation {
            schemaVersion = normalized(schemaVersion);
            status = normalized(status);
            lastFailureCode = normalized(lastFailureCode);
            if (!SCHEMA_VERSION.equals(schemaVersion) || status.isBlank()
                    || cacheGeneration < 0 || authorityGeneration < 0
                    || attestationRevision < 0 || statusRevision < 0
                    || refreshSuccessCount < 0 || refreshFailureCount < 0
                    || refreshIntervalSeconds < 1 || maximumSnapshotAgeSeconds < 3
                    || maximumRevocationConvergenceSeconds < refreshIntervalSeconds
                    || !durableAtomicCache || !mutualTls || !certificateIdentityBound
                    || available && cacheGeneration == 0) {
                throw new IllegalArgumentException(
                        "deployment isolation trust agent observation is invalid");
            }
        }
    }

    /** Stable runtime denial without trust payload material. */
    public static final class TrustUnavailableException extends IllegalStateException {
        /** Stable payload-free runtime denial reason. */
        private final String reasonCode;

        /**
         * Creates one fail-closed positive-admission denial.
         *
         * @param reasonCode stable payload-free denial reason
         */
        public TrustUnavailableException(String reasonCode) {
            super(normalized(reasonCode));
            this.reasonCode = normalized(reasonCode);
        }

        /**
         * Returns the stable payload-free denial reason.
         *
         * @return stable payload-free denial reason
         */
        public String reasonCode() {
            return reasonCode;
        }
    }

    private record RefreshState(
            Instant lastAttemptAt,
            Instant lastSuccessfulRefreshAt,
            long refreshSuccessCount,
            long refreshFailureCount,
            String lastFailureCode) {
        private RefreshState {
            lastFailureCode = normalized(lastFailureCode);
        }

        private static RefreshState empty() {
            return new RefreshState(null, null, 0, 0, "");
        }

        private static RefreshState restored(
                MirrorDeploymentIsolationAgentSnapshot snapshot, Instant now) {
            return new RefreshState(now, snapshot.refreshedAt(), 0, 0, "");
        }

        private RefreshState attempted(Instant now) {
            return new RefreshState(now, lastSuccessfulRefreshAt,
                    refreshSuccessCount, refreshFailureCount, lastFailureCode);
        }

        private RefreshState succeeded(
                MirrorDeploymentIsolationAgentSnapshot snapshot, Instant now) {
            return new RefreshState(now, snapshot.refreshedAt(),
                    refreshSuccessCount + 1L, refreshFailureCount, "");
        }

        private RefreshState failed(String reasonCode, Instant now) {
            return new RefreshState(now, lastSuccessfulRefreshAt,
                    refreshSuccessCount, refreshFailureCount + 1L, reasonCode);
        }
    }

    private static final class AgentRejectedException extends RuntimeException {
        private final String reasonCode;

        private AgentRejectedException(String reasonCode) {
            super(normalized(reasonCode));
            this.reasonCode = normalized(reasonCode);
        }

        private String reasonCode() {
            return reasonCode;
        }
    }

    private static Duration bounded(
            Duration value, Duration minimum, Duration maximum, String name) {
        Duration exact = Objects.requireNonNull(value, name);
        if (exact.compareTo(minimum) < 0 || exact.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    "deployment isolation trust agent " + name + " is invalid");
        }
        return exact;
    }

    private static String fingerprint(String value, String name) {
        String exact = normalized(value);
        if (!exact.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return exact;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
