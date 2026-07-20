package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Atomically refreshes a complete external-anchor bootstrap-root transition chain.
 *
 * <p>Bootstrap replays the entire remote bundle from deployment-pinned genesis before any root
 * snapshot is usable. One randomized daemon lane performs strict HTTPS/ETag refresh. A managed
 * notary publication signed by an unknown root key may trigger one synchronous refresh under a
 * global cooldown; invalid signatures do not trigger network activity.</p>
 *
 * <p>Transport, media, parser, chain, signature, lifecycle, durable-floor, or source-age ambiguity
 * immediately makes root verification fail closed. The preceding complete state remains only for
 * ancestry comparison and aggregate diagnostics. Endpoint, ETag, root identity, signature, public
 * key, policy, and material fingerprints never enter logs or health.</p>
 */
public final class DynamicExternalSequenceAnchorBootstrapRootTrustStore
        implements ExternalSequenceAnchorBootstrapRootTrustStore {

    /** Exact complete root-chain bundle media type. */
    public static final String MEDIA_TYPE =
            "application/vnd.bloge.external-sequence-anchor-bootstrap-root-bundle.v1+json";
    /** Exact root-chain wire-version header. */
    public static final String PROTOCOL_HEADER =
            "X-BLOGE-External-Sequence-Anchor-Bootstrap-Root-Protocol";

    private static final Logger log = LoggerFactory.getLogger(
            DynamicExternalSequenceAnchorBootstrapRootTrustStore.class);
    private static final int MAXIMUM_DOCUMENT_BYTES = 4 * 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ConfiguredExternalSequenceAnchorBootstrapRootTrustStore.ExpectedBinding binding;
    private final Set<String> acceptedPolicies;
    private final ExternalSequenceAnchorBootstrapRootGenesis genesis;
    private final ExternalSequenceAnchorBootstrapRootPublicationFloor floor;
    private final Settings settings;
    private final DocumentFetcher fetcher;
    private final Object refreshLock = new Object();
    private final ScheduledThreadPoolExecutor scheduler;
    private final AtomicBoolean refreshFailureLogged = new AtomicBoolean();

    private volatile State state = State.empty();
    private volatile Instant nextUnknownKeyRefreshAt = Instant.MIN;
    private volatile boolean closed;

    /**
     * Bootstraps a strict complete-chain source and starts bounded background refresh.
     *
     * @param objectMapper application JSON mapper
     * @param binding exact local root-chain binding and lifecycle policy
     * @param acceptedPolicies accepted ceremony policy fingerprints
     * @param genesis deployment-pinned finite trust anchor
     * @param floor durable monotonic verified-chain floor
     * @param settings strict HTTPS refresh policy
     */
    public DynamicExternalSequenceAnchorBootstrapRootTrustStore(
            ObjectMapper objectMapper,
            ConfiguredExternalSequenceAnchorBootstrapRootTrustStore.ExpectedBinding binding,
            Set<String> acceptedPolicies,
            ExternalSequenceAnchorBootstrapRootGenesis genesis,
            ExternalSequenceAnchorBootstrapRootPublicationFloor floor,
            Settings settings) {
        this(objectMapper, Clock.systemUTC(), binding, acceptedPolicies,
                genesis, floor, settings, null, true);
    }

    DynamicExternalSequenceAnchorBootstrapRootTrustStore(
            ObjectMapper objectMapper,
            Clock clock,
            ConfiguredExternalSequenceAnchorBootstrapRootTrustStore.ExpectedBinding binding,
            Set<String> acceptedPolicies,
            ExternalSequenceAnchorBootstrapRootGenesis genesis,
            ExternalSequenceAnchorBootstrapRootPublicationFloor floor,
            Settings settings,
            DocumentFetcher fetcher,
            boolean startScheduler) {
        this.objectMapper = strict(objectMapper);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.acceptedPolicies = Set.copyOf(Objects.requireNonNull(
                acceptedPolicies, "acceptedPolicies"));
        this.genesis = Objects.requireNonNull(genesis, "genesis");
        this.floor = Objects.requireNonNull(floor, "floor");
        if (!floor.durable()) {
            throw new IllegalArgumentException(
                    "Dynamic bootstrap-root trust requires a durable floor");
        }
        this.settings = Objects.requireNonNull(settings, "settings").validated();
        this.fetcher = fetcher == null ? new HttpDocumentFetcher() : fetcher;
        if (!refresh()) {
            throw new IllegalStateException(
                    "Dynamic external bootstrap-root trust bootstrap is unavailable");
        }
        this.scheduler = startScheduler ? scheduler() : null;
    }

    /** Verifies against one immutable healthy root generation with unknown-key refresh. */
    @Override
    public void verify(ExternalSequenceAnchorTrustPublication publication, Instant observedAt) {
        Objects.requireNonNull(publication, "publication");
        Instant now = observedAt == null ? clock.instant() : observedAt;
        State observed = state;
        requireUsable(observed, now);
        try {
            observed.store().verify(publication, now);
            return;
        } catch (TrustException rejected) {
            if (rejected.reason() != TrustException.Reason.UNKNOWN_KEY) {
                throw rejected;
            }
        }
        refreshUnknownKey();
        State refreshed = state;
        requireUsable(refreshed, now);
        refreshed.store().verify(publication, now);
    }

    /** {@inheritDoc} */
    @Override
    public void requireIndependentFrom(
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> notaryKeys) {
        State observed = state;
        requireUsable(observed, clock.instant());
        observed.store().requireIndependentFrom(notaryKeys);
    }

    /** {@inheritDoc} */
    @Override
    public boolean matchesBinding(String scopeId, String rootSetId, String trustDomain) {
        return binding.scopeId().equals(normalized(scopeId))
                && binding.rootSetId().equals(normalized(rootSetId))
                && binding.trustDomain().equals(normalized(trustDomain));
    }

    /** Returns key-free local capability truth without remote I/O. */
    @Override
    public Descriptor descriptor() {
        State observed = state;
        Instant now = clock.instant();
        Descriptor current = observed.store() == null
                ? new Descriptor(Descriptor.SCHEMA_VERSION, false, true, true,
                true, true, 0, 0, 0)
                : observed.store().descriptor();
        boolean available = usable(observed, now) && current.available();
        return new Descriptor(Descriptor.SCHEMA_VERSION, available, true, true,
                true, true, current.authorityCount(), current.activeAuthorityCount(),
                current.signatureThreshold());
    }

    /** Returns aggregate refresh and chain state without trust material. */
    @Override
    public Snapshot snapshot() {
        State observed = state;
        Instant now = clock.instant();
        Snapshot current = observed.store() == null
                ? new Snapshot(Snapshot.SCHEMA_VERSION, false, "UNINITIALIZED",
                0, 0, 0, 0, null, null, 0, 0)
                : observed.store().snapshot();
        String status = effectiveStatus(observed, now, current);
        return new Snapshot(Snapshot.SCHEMA_VERSION, "HEALTHY".equals(status), status,
                current.headSequence(), current.transitionCount(), current.authorityCount(),
                current.activeAuthorityCount(), current.headExpiresAt(),
                observed.lastSuccessfulRefreshAt(), observed.refreshSuccessCount(),
                observed.refreshFailureCount());
    }

    /** Stops background refresh and permanently closes this local root view. */
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
                        Math.min(1_000L, settings.requestTimeout().toMillis()),
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    boolean refreshNow() {
        return refresh();
    }

    private void refreshUnknownKey() {
        Instant now = clock.instant();
        synchronized (refreshLock) {
            if (closed || now.isBefore(nextUnknownKeyRefreshAt)) {
                return;
            }
            nextUnknownKeyRefreshAt = now.plus(settings.unknownKeyRefreshInterval());
            refreshLocked(now, state);
        }
    }

    private boolean refresh() {
        synchronized (refreshLock) {
            if (closed) {
                return false;
            }
            return refreshLocked(clock.instant(), state);
        }
    }

    private boolean refreshLocked(Instant now, State previous) {
        try {
            FetchedDocument document = fetcher.fetch(
                    settings.bundleUri(), previous.etag(), settings.requestTimeout());
            ExternalSequenceAnchorBootstrapRootBundle bundle;
            if (document.notModified()) {
                if (previous.bundle() == null) {
                    throw new IllegalArgumentException(
                            "Bootstrap-root source returned 304 before bootstrap");
                }
                bundle = previous.bundle();
            } else {
                bundle = objectMapper.readValue(
                        document.body(), ExternalSequenceAnchorBootstrapRootBundle.class);
            }
            var store = new ConfiguredExternalSequenceAnchorBootstrapRootTrustStore(
                    objectMapper, clock, binding, acceptedPolicies, genesis, floor, bundle);
            String etag = document.etag().isBlank() ? previous.etag() : document.etag();
            state = previous.succeeded(bundle, store, etag, now);
            refreshFailureLogged.set(false);
            return true;
        } catch (IOException | RuntimeException unavailable) {
            state = previous.failed(failureCode(unavailable));
            if (refreshFailureLogged.compareAndSet(false, true)) {
                log.warn("Dynamic external bootstrap-root trust refresh failed; "
                        + "managed notary publication verification is now fail-closed");
            }
            return false;
        }
    }

    private void requireUsable(State observed, Instant now) {
        if (closed) {
            throw new TrustException(TrustException.Reason.CLOSED);
        }
        if (!usable(observed, now)) {
            throw new TrustException(TrustException.Reason.UNAVAILABLE);
        }
    }

    private boolean usable(State observed, Instant now) {
        return !closed && observed.refreshStatus() == RefreshStatus.HEALTHY
                && observed.store() != null
                && observed.lastSuccessfulRefreshAt() != null
                && !now.isAfter(observed.lastSuccessfulRefreshAt()
                .plus(settings.maximumSnapshotAge()))
                && observed.store().descriptor().available();
    }

    private String effectiveStatus(State observed, Instant now, Snapshot current) {
        if (closed) {
            return "CLOSED";
        }
        if (observed.store() == null) {
            return observed.refreshStatus() == RefreshStatus.FAILED
                    ? "REFRESH_FAILED" : "UNINITIALIZED";
        }
        if (observed.refreshStatus() == RefreshStatus.FAILED) {
            return "REFRESH_FAILED";
        }
        if (observed.lastSuccessfulRefreshAt() == null
                || now.isAfter(observed.lastSuccessfulRefreshAt()
                .plus(settings.maximumSnapshotAge()))) {
            return "STALE";
        }
        if (!current.available()) {
            return current.status();
        }
        return "HEALTHY";
    }

    private ScheduledThreadPoolExecutor scheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable,
                    "rg-external-sequence-anchor-bootstrap-root-refresh");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        long interval = settings.refreshInterval().toMillis();
        long initial = ThreadLocalRandom.current().nextLong(
                Math.max(1L, interval / 2L), interval + 1L);
        executor.scheduleWithFixedDelay(() -> {
            try {
                refresh();
            } catch (RuntimeException ignored) {
                // refresh() publishes bounded failure state and aggregate-only logging.
            }
        }, initial, interval, TimeUnit.MILLISECONDS);
        return executor;
    }

    private static String failureCode(Throwable failure) {
        return failure instanceof IOException ? "TRANSPORT" : "INVALID_BUNDLE";
    }

    private static ObjectMapper strict(ObjectMapper source) {
        return Objects.requireNonNull(source, "objectMapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** Test seam for deterministic transport, catch-up, and concurrency verification. */
    interface DocumentFetcher {
        FetchedDocument fetch(URI uri, String etag, Duration timeout) throws IOException;
    }

    /** Strict source result; a 304 carries no body and retains the prior ETag. */
    record FetchedDocument(boolean notModified, byte[] body, String etag) {
        FetchedDocument {
            body = body == null ? new byte[0] : body.clone();
            etag = etag == null ? "" : etag;
            if (notModified && body.length != 0
                    || !notModified && (body.length == 0
                    || body.length > MAXIMUM_DOCUMENT_BYTES)) {
                throw new IllegalArgumentException(
                        "Bootstrap-root source result is invalid");
            }
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    private static final class HttpDocumentFetcher implements DocumentFetcher {

        private final HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build();

        @Override
        public FetchedDocument fetch(URI uri, String etag, Duration timeout) throws IOException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Accept", MEDIA_TYPE)
                    .header(PROTOCOL_HEADER,
                            ExternalSequenceAnchorBootstrapRootBundle.SCHEMA_VERSION)
                    .GET();
            if (etag != null && !etag.isBlank()) {
                builder.header("If-None-Match", etag);
            }
            HttpResponse<InputStream> response;
            try {
                response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Bootstrap-root fetch was interrupted", interrupted);
            }
            try (InputStream input = response.body()) {
                if (response.statusCode() == 304) {
                    return new FetchedDocument(true, new byte[0], etag);
                }
                if (response.statusCode() != 200
                        || !MEDIA_TYPE.equalsIgnoreCase(response.headers()
                        .firstValue("Content-Type").orElse(""))
                        || !ExternalSequenceAnchorBootstrapRootBundle.SCHEMA_VERSION.equals(
                        response.headers().firstValue(PROTOCOL_HEADER).orElse(""))) {
                    throw new IOException("Bootstrap-root response protocol is invalid");
                }
                byte[] body = input.readNBytes(MAXIMUM_DOCUMENT_BYTES + 1);
                if (body.length > MAXIMUM_DOCUMENT_BYTES) {
                    throw new IOException("Bootstrap-root bundle is oversized");
                }
                return new FetchedDocument(false, body,
                        response.headers().firstValue("ETag").orElse(""));
            }
        }
    }

    /** Strict complete-chain remote refresh policy. */
    public record Settings(
            URI bundleUri,
            Duration requestTimeout,
            Duration refreshInterval,
            Duration maximumSnapshotAge,
            Duration unknownKeyRefreshInterval,
            boolean allowInsecureLoopback) {

        /** Returns the validated immutable settings value. */
        Settings validated() {
            validateUri(bundleUri, allowInsecureLoopback);
            if (requestTimeout == null
                    || requestTimeout.compareTo(Duration.ofMillis(100)) < 0
                    || requestTimeout.compareTo(Duration.ofSeconds(30)) > 0
                    || refreshInterval == null
                    || refreshInterval.compareTo(Duration.ofSeconds(1)) < 0
                    || refreshInterval.compareTo(Duration.ofHours(1)) > 0
                    || maximumSnapshotAge == null
                    || maximumSnapshotAge.compareTo(refreshInterval) < 0
                    || maximumSnapshotAge.compareTo(Duration.ofHours(24)) > 0
                    || unknownKeyRefreshInterval == null
                    || unknownKeyRefreshInterval.compareTo(Duration.ofSeconds(1)) < 0
                    || unknownKeyRefreshInterval.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalArgumentException(
                        "Dynamic external bootstrap-root refresh policy is invalid");
            }
            return this;
        }
    }

    private static void validateUri(URI uri, boolean allowInsecureLoopback) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Bootstrap-root bundle URI is invalid");
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "0:0:0:0:0:0:0:1".equals(host);
        if (!allowInsecureLoopback || !loopback
                || !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Bootstrap-root bundle URI must use HTTPS");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private enum RefreshStatus {
        UNINITIALIZED,
        HEALTHY,
        FAILED
    }

    private record State(
            ExternalSequenceAnchorBootstrapRootBundle bundle,
            ConfiguredExternalSequenceAnchorBootstrapRootTrustStore store,
            String etag,
            RefreshStatus refreshStatus,
            Instant lastSuccessfulRefreshAt,
            long refreshSuccessCount,
            long refreshFailureCount,
            String lastFailureCode) {

        private static State empty() {
            return new State(null, null, "", RefreshStatus.UNINITIALIZED,
                    null, 0, 0, "");
        }

        private State succeeded(
                ExternalSequenceAnchorBootstrapRootBundle nextBundle,
                ConfiguredExternalSequenceAnchorBootstrapRootTrustStore nextStore,
                String nextEtag,
                Instant at) {
            return new State(nextBundle, nextStore, nextEtag, RefreshStatus.HEALTHY,
                    at, refreshSuccessCount + 1, refreshFailureCount, "");
        }

        private State failed(String code) {
            return new State(bundle, store, etag, RefreshStatus.FAILED,
                    lastSuccessfulRefreshAt, refreshSuccessCount,
                    refreshFailureCount + 1, code);
        }
    }
}
