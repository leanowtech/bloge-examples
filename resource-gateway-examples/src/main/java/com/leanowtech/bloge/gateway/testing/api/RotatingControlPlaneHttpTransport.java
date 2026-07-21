package com.leanowtech.bloge.gateway.testing.api;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Atomically rotates pinned mutual-TLS control-plane client identities without process restart.
 *
 * <p>Each generation is fully loaded and certificate-policy verified before it can become pending.
 * Generation numbers are contiguous, activation time is bounded, the old and new client
 * certificates must overlap for a configured safety window, and only one successor may be staged.
 * A stable {@link HttpClient} proxy selects one complete immutable generation per request, so trust
 * stores, pins, client keys, and identity policies can never be mixed across generations.</p>
 *
 * <p>Rotation is deliberately caller-driven. A deployment controller remains responsible for
 * authenticating a signed inventory or secret-manager event before invoking {@link #stage}. This
 * class owns atomic activation and fail-closed certificate timing, not PKI issuance or event
 * authorization.</p>
 */
public final class RotatingControlPlaneHttpTransport
        implements RecoveryFleetPublicationTransport, ControlPlaneCertificateRotationTarget {

    /**
     * Process-local, non-blocking fleet admission decision used inside the request-state lock.
     *
     * <p>Implementations must return only locally cached decisions. Database or network I/O in
     * either method would put every control-plane request behind the convergence authority.</p>
     */
    public interface ActivationGate {

        /** @return whether the exact successor may atomically replace the active generation */
        boolean activationPermitted(long generation, Instant activateAt);

        /** @return whether an already selected generation may serve a new request */
        default boolean servingPermitted(long generation) {
            return true;
        }

        /** @return a compatibility gate for deployments without fleet convergence */
        static ActivationGate permitAll() {
            return (generation, activateAt) -> true;
        }
    }

    private static final Duration MINIMUM_LEAD = Duration.ofSeconds(1);
    private static final Duration MAXIMUM_LEAD = Duration.ofDays(30);
    private static final Duration MAXIMUM_OVERLAP = Duration.ofDays(30);

    private final SecretResolver secretResolver;
    private final Clock clock;
    private final Duration minimumOverlap;
    private final Duration maximumLeadTime;
    private final ActivationGate activationGate;
    private final Object monitor = new Object();

    private volatile ActiveGeneration active;
    private volatile PendingGeneration pending;

    /**
     * Loads an immediately active generation and freezes the rotation timing policy.
     *
     * @param initialGeneration positive initial generation
     * @param initialSettings complete pinned mutual-TLS settings with a bound identity policy
     * @param secretResolver resolver returning fresh caller-owned credential characters
     * @param clock authoritative local activation clock
     * @param minimumOverlap minimum time the old identity remains valid after activation
     * @param maximumLeadTime maximum allowed delay between staging and activation
     */
    public RotatingControlPlaneHttpTransport(
            long initialGeneration,
            PinnedMutualTlsRecoveryFleetPublicationTransport.Settings initialSettings,
            SecretResolver secretResolver,
            Clock clock,
            Duration minimumOverlap,
            Duration maximumLeadTime) {
        this(initialGeneration, initialSettings, secretResolver, clock, minimumOverlap,
                maximumLeadTime, ActivationGate.permitAll());
    }

    /**
     * Loads an active generation behind an explicit process-local fleet admission gate.
     *
     * @param initialGeneration positive initial generation
     * @param initialSettings complete pinned mutual-TLS settings with a bound identity policy
     * @param secretResolver resolver returning fresh caller-owned credential characters
     * @param clock authoritative local activation clock
     * @param minimumOverlap minimum time the old identity remains valid after activation
     * @param maximumLeadTime maximum allowed delay between staging and activation
     * @param activationGate non-blocking cached fleet admission decision
     */
    public RotatingControlPlaneHttpTransport(
            long initialGeneration,
            PinnedMutualTlsRecoveryFleetPublicationTransport.Settings initialSettings,
            SecretResolver secretResolver,
            Clock clock,
            Duration minimumOverlap,
            Duration maximumLeadTime,
            ActivationGate activationGate) {
        if (initialGeneration < 1) {
            throw invalid();
        }
        this.secretResolver = Objects.requireNonNull(secretResolver, "secretResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.minimumOverlap = bounded(minimumOverlap, Duration.ZERO, MAXIMUM_OVERLAP);
        this.maximumLeadTime = bounded(maximumLeadTime, MINIMUM_LEAD, MAXIMUM_LEAD);
        this.activationGate = Objects.requireNonNull(activationGate, "activationGate");
        Instant now = clock.instant();
        var transport = load(initialSettings, now);
        requireBound(transport);
        if (!transport.clientIdentityExpiresAt().isAfter(now.plus(this.minimumOverlap))) {
            throw invalidMaterial();
        }
        this.active = new ActiveGeneration(initialGeneration, now, transport);
    }

    /**
     * Preloads one contiguous successor without changing the active request path.
     *
     * <p>Structural and timing checks run before credential resolution. Material validation then
     * occurs off the active state and outside the request-state lock. The candidate is published
     * only after a second comparison proves the active generation is unchanged. Any failure leaves
     * the current generation untouched.</p>
     *
     * @param generation exact current generation plus one
     * @param activateAt bounded activation instant
     * @param settings complete successor TLS settings and workload identity policy
     */
    public void stage(
            long generation,
            Instant activateAt,
            PinnedMutualTlsRecoveryFleetPublicationTransport.Settings settings) {
        Objects.requireNonNull(activateAt, "activateAt");
        Objects.requireNonNull(settings, "settings");
        ActiveGeneration baseline;
        synchronized (monitor) {
            Instant now = clock.instant();
            baseline = promoteIfDue(now);
            if (pending != null || generation != baseline.generation() + 1
                    || activateAt.isBefore(now.plus(MINIMUM_LEAD))
                    || activateAt.isAfter(now.plus(maximumLeadTime))) {
                throw invalid();
            }
        }

        var candidate = load(settings, activateAt);
        requireBound(candidate);
        if (!candidate.descriptor().equals(baseline.transport().descriptor())
                || activateAt.plus(minimumOverlap).isAfter(
                baseline.transport().clientIdentityExpiresAt())
                || candidate.clientIdentityNotBefore().isAfter(activateAt)
                || !candidate.clientIdentityExpiresAt().isAfter(
                activateAt.plus(minimumOverlap))) {
            throw invalidMaterial();
        }

        synchronized (monitor) {
            Instant now = clock.instant();
            ActiveGeneration observed = promoteIfDue(now);
            if (pending != null || observed.generation() != baseline.generation()
                    || now.isAfter(activateAt)
                    || !observed.transport().clientIdentityExpiresAt().isAfter(
                    activateAt.plus(minimumOverlap))) {
                throw invalid();
            }
            pending = new PendingGeneration(generation, activateAt, candidate);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void reconcileActive(
            long generation,
            Instant activatedAt,
            PinnedMutualTlsRecoveryFleetPublicationTransport.Settings settings) {
        Objects.requireNonNull(activatedAt, "activatedAt");
        Objects.requireNonNull(settings, "settings");
        ActiveGeneration baseline;
        Instant now;
        synchronized (monitor) {
            now = clock.instant();
            baseline = promoteIfDue(now);
            if (pending != null || generation != baseline.generation() + 1
                    || activatedAt.isAfter(now)) {
                throw invalid();
            }
        }

        var candidate = load(settings, now);
        requireBound(candidate);
        if (!candidate.descriptor().equals(baseline.transport().descriptor())
                || candidate.clientIdentityNotBefore().isAfter(now)
                || !candidate.clientIdentityExpiresAt().isAfter(now.plus(minimumOverlap))) {
            throw invalidMaterial();
        }

        synchronized (monitor) {
            Instant observedAt = clock.instant();
            ActiveGeneration observed = promoteIfDue(observedAt);
            if (pending != null || observed.generation() != baseline.generation()
                    || activatedAt.isAfter(observedAt)
                    || !candidate.clientIdentityExpiresAt().isAfter(
                    observedAt.plus(minimumOverlap))) {
                throw invalid();
            }
            if (activationGate.activationPermitted(generation, activatedAt)) {
                active = new ActiveGeneration(generation, activatedAt, candidate);
            } else {
                pending = new PendingGeneration(generation, activatedAt, candidate);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void restorePending(
            long generation,
            Instant activateAt,
            PinnedMutualTlsRecoveryFleetPublicationTransport.Settings settings) {
        Objects.requireNonNull(activateAt, "activateAt");
        Objects.requireNonNull(settings, "settings");
        ActiveGeneration baseline;
        synchronized (monitor) {
            baseline = promoteIfDue(clock.instant());
            if (pending != null || generation != baseline.generation() + 1) {
                throw invalid();
            }
        }

        var candidate = load(settings, activateAt);
        requireBound(candidate);
        if (!candidate.descriptor().equals(baseline.transport().descriptor())
                || activateAt.plus(minimumOverlap).isAfter(
                baseline.transport().clientIdentityExpiresAt())
                || candidate.clientIdentityNotBefore().isAfter(activateAt)
                || !candidate.clientIdentityExpiresAt().isAfter(
                activateAt.plus(minimumOverlap))) {
            throw invalidMaterial();
        }

        synchronized (monitor) {
            Instant now = clock.instant();
            ActiveGeneration observed = promoteIfDue(now);
            if (pending != null || observed.generation() != baseline.generation()) {
                throw invalid();
            }
            if (now.isBefore(activateAt)) {
                pending = new PendingGeneration(generation, activateAt, candidate);
            } else if (candidate.clientIdentityExpiresAt().isAfter(now.plus(minimumOverlap))
                    && activationGate.activationPermitted(generation, activateAt)) {
                active = new ActiveGeneration(generation, activateAt, candidate);
            } else if (candidate.clientIdentityExpiresAt().isAfter(now.plus(minimumOverlap))) {
                pending = new PendingGeneration(generation, activateAt, candidate);
            } else {
                throw invalidMaterial();
            }
        }
    }

    /** @return current active generation after applying any due successor */
    public long activeGeneration() {
        return reconcileGeneration();
    }

    /** @return staged successor generation, or empty when no rotation is pending */
    public OptionalLong pendingGeneration() {
        PendingGeneration observed = pending;
        return observed == null ? OptionalLong.empty()
                : OptionalLong.of(observed.generation());
    }

    /**
     * Evaluates a due successor against the cached fleet gate without selecting it for a request.
     *
     * @return locally active generation after the reconciliation attempt
     */
    public long reconcileGeneration() {
        synchronized (monitor) {
            return promoteIfDue(clock.instant()).generation();
        }
    }

    /** @return locally loaded active generation without promotion or serving admission */
    public long localActiveGeneration() {
        return active.generation();
    }

    /** {@inheritDoc} */
    @Override
    public HttpClient client(Duration connectTimeout) {
        Duration bounded = SystemTrustRecoveryFleetPublicationTransport.bounded(connectTimeout);
        return new GenerationSelectingHttpClient(this, bounded);
    }

    /** {@inheritDoc} */
    @Override
    public Descriptor descriptor() {
        return active.transport().descriptor();
    }

    /** Rotation always requires exact workload-identity binding. */
    @Override
    public boolean certificateIdentityBound() {
        return true;
    }

    private ActiveGeneration activeForRequest() {
        synchronized (monitor) {
            Instant now = clock.instant();
            ActiveGeneration selected = promoteIfDue(now);
            if (!activationGate.servingPermitted(selected.generation())) {
                throw new IllegalStateException(
                        "Control-plane client identity is not fleet-admitted");
            }
            if (!selected.transport().clientIdentityExpiresAt().isAfter(now)) {
                throw new IllegalStateException(
                        "Control-plane client identity has no active certificate generation");
            }
            return selected;
        }
    }

    private ActiveGeneration promoteIfDue(Instant now) {
        PendingGeneration candidate = pending;
        if (candidate != null && !now.isBefore(candidate.activateAt())
                && activationGate.activationPermitted(
                candidate.generation(), candidate.activateAt())) {
            active = new ActiveGeneration(candidate.generation(), candidate.activateAt(),
                    candidate.transport());
            pending = null;
        }
        return active;
    }

    private PinnedMutualTlsRecoveryFleetPublicationTransport load(
            PinnedMutualTlsRecoveryFleetPublicationTransport.Settings settings,
            Instant validAt) {
        return new PinnedMutualTlsRecoveryFleetPublicationTransport(
                Objects.requireNonNull(settings, "settings"), secretResolver, validAt);
    }

    private static void requireBound(
            PinnedMutualTlsRecoveryFleetPublicationTransport transport) {
        if (!transport.certificateIdentityBound()) {
            throw invalid();
        }
    }

    private static Duration bounded(Duration value, Duration minimum, Duration maximum) {
        Duration required = Objects.requireNonNull(value, "duration");
        if (required.compareTo(minimum) < 0 || required.compareTo(maximum) > 0) {
            throw invalid();
        }
        return required;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "Control-plane certificate rotation configuration is invalid");
    }

    private static IllegalArgumentException invalidMaterial() {
        return new IllegalArgumentException(
                "Control-plane certificate rotation material is invalid");
    }

    private record ActiveGeneration(
            long generation,
            Instant activatedAt,
            PinnedMutualTlsRecoveryFleetPublicationTransport transport) {
    }

    private record PendingGeneration(
            long generation,
            Instant activateAt,
            PinnedMutualTlsRecoveryFleetPublicationTransport transport) {
    }

    /** Stable proxy that resolves exactly one immutable TLS generation for each request. */
    private static final class GenerationSelectingHttpClient extends HttpClient {
        private final RotatingControlPlaneHttpTransport owner;
        private final Duration timeout;
        private volatile SelectedClient selected;

        private GenerationSelectingHttpClient(
                RotatingControlPlaneHttpTransport owner,
                Duration timeout) {
            this.owner = owner;
            this.timeout = timeout;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return delegate().cookieHandler();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(timeout);
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return delegate().proxy();
        }

        @Override
        public SSLContext sslContext() {
            return delegate().sslContext();
        }

        @Override
        public SSLParameters sslParameters() {
            return delegate().sslParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return delegate().authenticator();
        }

        @Override
        public Version version() {
            return delegate().version();
        }

        @Override
        public Optional<Executor> executor() {
            return delegate().executor();
        }

        @Override
        public <T> HttpResponse<T> send(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            try {
                return delegate().send(request, responseBodyHandler);
            } catch (IllegalStateException unavailable) {
                throw new IOException("Control-plane client identity is unavailable", unavailable);
            }
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            try {
                return delegate().sendAsync(request, responseBodyHandler);
            } catch (RuntimeException unavailable) {
                return CompletableFuture.failedFuture(unavailable);
            }
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            try {
                return delegate().sendAsync(request, responseBodyHandler, pushPromiseHandler);
            } catch (RuntimeException unavailable) {
                return CompletableFuture.failedFuture(unavailable);
            }
        }

        private HttpClient delegate() {
            ActiveGeneration active = owner.activeForRequest();
            SelectedClient observed = selected;
            if (observed != null && observed.generation() == active.generation()) {
                return observed.client();
            }
            synchronized (this) {
                observed = selected;
                if (observed == null || observed.generation() != active.generation()) {
                    observed = new SelectedClient(active.generation(),
                            active.transport().client(timeout));
                    selected = observed;
                }
                return observed.client();
            }
        }
    }

    private record SelectedClient(long generation, HttpClient client) {
    }
}
