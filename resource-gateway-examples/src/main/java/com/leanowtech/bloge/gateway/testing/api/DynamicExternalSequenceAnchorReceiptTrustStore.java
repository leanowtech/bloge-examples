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
 * Atomically refreshes bootstrap-signed external sequence-notary trust publications.
 *
 * <p>Bootstrap must complete canonical, binding, freshness, bootstrap-quorum and durable-floor
 * verification before this store is usable. One randomized daemon lane performs strict HTTPS/ETag
 * refresh. An unknown receipt key may trigger one synchronous refresh under a global cooldown;
 * the refresh lock rechecks the immutable published state so a rotation burst cannot create a
 * network stampede.</p>
 *
 * <p>Any transport, media, parser, signature, predecessor, floor, scheduling-age, or lifecycle
 * ambiguity makes verification immediately fail closed. The prior complete snapshot remains only
 * for recovery comparison and aggregate diagnostics. Endpoint identity, ETag, key identity,
 * publication fingerprint, and public key material never enter logs or health.</p>
 */
public final class DynamicExternalSequenceAnchorReceiptTrustStore
        implements ExternalSequenceAnchorReceiptTrustStore {

    /** Exact managed trust publication media type. */
    public static final String MEDIA_TYPE =
            "application/vnd.bloge.external-sequence-anchor-trust-publication.v1+json";
    /** Exact managed trust publication wire-version header. */
    public static final String PROTOCOL_HEADER =
            "X-BLOGE-External-Sequence-Anchor-Trust-Protocol";

    private static final Logger log = LoggerFactory.getLogger(
            DynamicExternalSequenceAnchorReceiptTrustStore.class);
    private static final int MAXIMUM_DOCUMENT_BYTES = 256 * 1024;

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ConfiguredExternalSequenceAnchorReceiptTrustStore.ExpectedBinding binding;
    private final Set<String> acceptedPolicies;
    private final ExternalSequenceAnchorBootstrapRootTrustStore rootTrustStore;
    private final boolean ownsRootTrustStore;
    private final ExternalSequenceAnchorTrustPublicationFloor floor;
    private final Settings settings;
    private final DocumentFetcher fetcher;
    private final Object refreshLock = new Object();
    private final ScheduledThreadPoolExecutor scheduler;
    private final AtomicBoolean refreshFailureLogged = new AtomicBoolean();

    private volatile State state = State.empty();
    private volatile Instant nextUnknownKeyRefreshAt = Instant.MIN;
    private volatile boolean closed;

    /**
     * Bootstraps one strict remote publication and starts bounded background refresh.
     *
     * @param objectMapper application JSON mapper
     * @param binding exact deployment binding and freshness policy
     * @param acceptedPolicies accepted external rotation-policy fingerprints
     * @param bootstrapSignatureThreshold required bootstrap-root signature quorum
     * @param bootstrapRootKeys independent bootstrap-root verification keys
     * @param floor durable monotonic publication floor
     * @param settings strict HTTPS refresh policy
     */
    public DynamicExternalSequenceAnchorReceiptTrustStore(
            ObjectMapper objectMapper,
            ConfiguredExternalSequenceAnchorReceiptTrustStore.ExpectedBinding binding,
            Set<String> acceptedPolicies,
            int bootstrapSignatureThreshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    bootstrapRootKeys,
            ExternalSequenceAnchorTrustPublicationFloor floor,
            Settings settings) {
        this(objectMapper, Clock.systemUTC(), binding, acceptedPolicies,
                bootstrapSignatureThreshold, bootstrapRootKeys, floor, settings, null, true);
    }

    /**
     * Bootstraps managed notary trust through one dynamically managed bootstrap-root chain.
     *
     * <p>This store owns and closes the supplied root store. A root refresh failure immediately
     * closes receipt verification even when the last notary publication remains locally fresh.</p>
     */
    public DynamicExternalSequenceAnchorReceiptTrustStore(
            ObjectMapper objectMapper,
            ConfiguredExternalSequenceAnchorReceiptTrustStore.ExpectedBinding binding,
            Set<String> acceptedPolicies,
            ExternalSequenceAnchorBootstrapRootTrustStore rootTrustStore,
            ExternalSequenceAnchorTrustPublicationFloor floor,
            Settings settings) {
        this(objectMapper, Clock.systemUTC(), binding, acceptedPolicies,
                rootTrustStore, floor, settings, null, true, true);
    }

    DynamicExternalSequenceAnchorReceiptTrustStore(
            ObjectMapper objectMapper,
            Clock clock,
            ConfiguredExternalSequenceAnchorReceiptTrustStore.ExpectedBinding binding,
            Set<String> acceptedPolicies,
            int bootstrapSignatureThreshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    bootstrapRootKeys,
            ExternalSequenceAnchorTrustPublicationFloor floor,
            Settings settings,
            DocumentFetcher fetcher,
            boolean startScheduler) {
        this(objectMapper, clock, binding, acceptedPolicies,
                staticRootStore(objectMapper, clock, binding,
                        bootstrapSignatureThreshold, bootstrapRootKeys),
                floor, settings, fetcher, startScheduler, true);
    }

    DynamicExternalSequenceAnchorReceiptTrustStore(
            ObjectMapper objectMapper,
            Clock clock,
            ConfiguredExternalSequenceAnchorReceiptTrustStore.ExpectedBinding binding,
            Set<String> acceptedPolicies,
            ExternalSequenceAnchorBootstrapRootTrustStore rootTrustStore,
            ExternalSequenceAnchorTrustPublicationFloor floor,
            Settings settings,
            DocumentFetcher fetcher,
            boolean startScheduler,
            boolean ownsRootTrustStore) {
        this.objectMapper = strict(objectMapper);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.acceptedPolicies = Set.copyOf(Objects.requireNonNull(
                acceptedPolicies, "acceptedPolicies"));
        this.rootTrustStore = Objects.requireNonNull(rootTrustStore, "rootTrustStore");
        this.ownsRootTrustStore = ownsRootTrustStore;
        this.floor = Objects.requireNonNull(floor, "floor");
        if (!floor.durable()) {
            throw new IllegalArgumentException(
                    "Dynamic external sequence-anchor trust requires a durable floor");
        }
        this.settings = Objects.requireNonNull(settings, "settings").validated();
        this.fetcher = fetcher == null ? new HttpDocumentFetcher() : fetcher;
        if (!refresh()) {
            if (ownsRootTrustStore) {
                rootTrustStore.close();
            }
            throw new IllegalStateException(
                    "Dynamic external sequence-anchor trust bootstrap is unavailable");
        }
        this.scheduler = startScheduler ? scheduler() : null;
    }

    /**
     * Verifies against one immutable healthy generation and performs bounded unknown-key refresh.
     */
    @Override
    public void verify(
            TestSuiteStabilityExternalSequenceCheckpointReceipt receipt,
            Instant observedAt) {
        Objects.requireNonNull(receipt, "receipt");
        Instant now = observedAt == null ? clock.instant() : observedAt;
        State observed = state;
        requireUsable(observed, now);
        try {
            observed.store().verify(receipt, now);
            return;
        } catch (TrustException rejected) {
            if (rejected.reason() != TrustException.Reason.UNKNOWN_KEY) {
                throw rejected;
            }
        }
        refreshUnknownKey();
        State refreshed = state;
        requireUsable(refreshed, now);
        refreshed.store().verify(receipt, now);
    }

    /** {@inheritDoc} */
    @Override
    public boolean coversAuthorities(Set<String> authorityIds) {
        State observed = state;
        return usable(observed, clock.instant())
                && observed.store().coversAuthorities(authorityIds);
    }

    /** Returns key-free local capability truth without remote I/O. */
    @Override
    public Descriptor descriptor() {
        State observed = state;
        Instant now = clock.instant();
        Descriptor current = observed.store() == null
                ? new Descriptor(Descriptor.SCHEMA_VERSION, false, true, true, true, 0, 0)
                : observed.store().descriptor();
        boolean available = usable(observed, now) && current.available();
        return new Descriptor(Descriptor.SCHEMA_VERSION, available, true, true, true,
                current.authorityCount(), current.activeAuthorityCount());
    }

    /** Returns aggregate refresh state without endpoint, ETag, key, or fingerprint material. */
    @Override
    public Snapshot snapshot() {
        State observed = state;
        Instant now = clock.instant();
        Snapshot current = observed.store() == null
                ? new Snapshot(Snapshot.SCHEMA_VERSION, false, "UNINITIALIZED",
                0, 0, 0, null, 0, 0)
                : observed.store().snapshot();
        String status = effectiveStatus(observed, now, current);
        boolean available = "HEALTHY".equals(status);
        return new Snapshot(Snapshot.SCHEMA_VERSION, available, status,
                current.publicationSequence(), current.authorityCount(),
                current.activeAuthorityCount(), observed.lastSuccessfulRefreshAt(),
                observed.refreshSuccessCount(), observed.refreshFailureCount());
    }

    /** Stops background refresh and permanently closes this local trust view. */
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
        if (ownsRootTrustStore) {
            rootTrustStore.close();
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
                    settings.publicationUri(), previous.etag(), settings.requestTimeout());
            ExternalSequenceAnchorTrustPublication publication;
            if (document.notModified()) {
                if (previous.publication() == null) {
                    throw new IllegalArgumentException(
                            "Managed notary trust returned 304 before bootstrap");
                }
                publication = previous.publication();
            } else {
                publication = objectMapper.readValue(
                        document.body(), ExternalSequenceAnchorTrustPublication.class);
            }
            verifySuccessor(previous.publication(), publication);
            ConfiguredExternalSequenceAnchorReceiptTrustStore store =
                    new ConfiguredExternalSequenceAnchorReceiptTrustStore(
                            objectMapper, clock, binding, acceptedPolicies,
                            rootTrustStore, floor, publication);
            String etag = document.etag().isBlank() ? previous.etag() : document.etag();
            state = previous.succeeded(publication, store, etag, now);
            refreshFailureLogged.set(false);
            return true;
        } catch (IOException | RuntimeException unavailable) {
            state = previous.failed(failureCode(unavailable));
            if (refreshFailureLogged.compareAndSet(false, true)) {
                log.warn("Dynamic external sequence-anchor trust refresh failed; "
                        + "receipt verification is now fail-closed");
            }
            return false;
        }
    }

    private static void verifySuccessor(
            ExternalSequenceAnchorTrustPublication previous,
            ExternalSequenceAnchorTrustPublication candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (previous == null) {
            if (candidate.material().sequence() != 1) {
                throw new IllegalArgumentException(
                        "Managed notary trust bootstrap must begin at sequence one");
            }
            return;
        }
        long current = previous.material().sequence();
        long next = candidate.material().sequence();
        if (next == current
                && candidate.materialFingerprint().equals(previous.materialFingerprint())) {
            return;
        }
        if (next != current + 1
                || !candidate.material().previousMaterialFingerprint()
                .equals(previous.materialFingerprint())) {
            throw new IllegalArgumentException(
                    "Managed notary trust publication is not an exact successor");
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
                    "rg-external-sequence-anchor-trust-refresh");
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
                // refresh() publishes bounded failure state and performs aggregate logging.
            }
        }, initial, interval, TimeUnit.MILLISECONDS);
        return executor;
    }

    private static String failureCode(Throwable failure) {
        return failure instanceof IOException ? "TRANSPORT" : "INVALID_PUBLICATION";
    }

    private static ExternalSequenceAnchorBootstrapRootTrustStore staticRootStore(
            ObjectMapper objectMapper,
            Clock clock,
            ConfiguredExternalSequenceAnchorReceiptTrustStore.ExpectedBinding binding,
            int signatureThreshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> rootKeys) {
        ConfiguredExternalSequenceAnchorReceiptTrustStore.ExpectedBinding expected =
                Objects.requireNonNull(binding, "binding");
        return new StaticExternalSequenceAnchorBootstrapRootTrustStore(
                objectMapper, clock, expected.scopeId(), expected.trustRootSetId(),
                expected.bootstrapTrustDomain(), signatureThreshold, rootKeys);
    }

    private static ObjectMapper strict(ObjectMapper source) {
        return Objects.requireNonNull(source, "objectMapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** Test seam for deterministic transport and concurrency verification. */
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
                        "Managed notary trust source result is invalid");
            }
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    private static final class HttpDocumentFetcher implements DocumentFetcher {

        private final HttpClient client;

        private HttpDocumentFetcher() {
            this.client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER).build();
        }

        @Override
        public FetchedDocument fetch(URI uri, String etag, Duration timeout) throws IOException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Accept", MEDIA_TYPE)
                    .header(PROTOCOL_HEADER, ExternalSequenceAnchorTrustPublication.SCHEMA_VERSION)
                    .GET();
            if (etag != null && !etag.isBlank()) {
                builder.header("If-None-Match", etag);
            }
            HttpResponse<InputStream> response;
            try {
                response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Managed notary trust fetch was interrupted", interrupted);
            }
            try (InputStream input = response.body()) {
                if (response.statusCode() == 304) {
                    return new FetchedDocument(true, new byte[0], etag);
                }
                if (response.statusCode() != 200
                        || !MEDIA_TYPE.equalsIgnoreCase(response.headers()
                        .firstValue("Content-Type").orElse(""))
                        || !ExternalSequenceAnchorTrustPublication.SCHEMA_VERSION.equals(
                        response.headers().firstValue(PROTOCOL_HEADER).orElse(""))) {
                    throw new IOException(
                            "Managed notary trust response protocol is invalid");
                }
                byte[] body = input.readNBytes(MAXIMUM_DOCUMENT_BYTES + 1);
                if (body.length > MAXIMUM_DOCUMENT_BYTES) {
                    throw new IOException(
                            "Managed notary trust publication is oversized");
                }
                return new FetchedDocument(false, body,
                        response.headers().firstValue("ETag").orElse(""));
            }
        }
    }

    /**
     * Strict remote refresh policy.
     *
     * @param publicationUri HTTPS managed publication endpoint
     * @param requestTimeout bounded per-request timeout
     * @param refreshInterval background refresh cadence
     * @param maximumSnapshotAge hard age since last successful refresh
     * @param unknownKeyRefreshInterval global synchronous refresh cooldown
     * @param allowInsecureLoopback explicit local-test HTTP escape hatch
     */
    public record Settings(
            URI publicationUri,
            Duration requestTimeout,
            Duration refreshInterval,
            Duration maximumSnapshotAge,
            Duration unknownKeyRefreshInterval,
            boolean allowInsecureLoopback) {

        /** Returns the validated immutable settings value. */
        Settings validated() {
            validateUri(publicationUri, allowInsecureLoopback);
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
                        "Dynamic external sequence-anchor trust refresh policy is invalid");
            }
            return this;
        }
    }

    private static void validateUri(URI uri, boolean allowInsecureLoopback) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "Managed external sequence-anchor trust URI is invalid");
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "0:0:0:0:0:0:0:1".equals(host);
        if (!allowInsecureLoopback || !loopback
                || !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException(
                    "Managed external sequence-anchor trust URI must use HTTPS");
        }
    }

    private enum RefreshStatus {
        UNINITIALIZED,
        HEALTHY,
        FAILED
    }

    private record State(
            ExternalSequenceAnchorTrustPublication publication,
            ConfiguredExternalSequenceAnchorReceiptTrustStore store,
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
                ExternalSequenceAnchorTrustPublication nextPublication,
                ConfiguredExternalSequenceAnchorReceiptTrustStore nextStore,
                String nextEtag,
                Instant at) {
            return new State(nextPublication, nextStore, nextEtag, RefreshStatus.HEALTHY,
                    at, refreshSuccessCount + 1, refreshFailureCount, "");
        }

        private State failed(String code) {
            return new State(publication, store, etag, RefreshStatus.FAILED,
                    lastSuccessfulRefreshAt, refreshSuccessCount,
                    refreshFailureCount + 1, code);
        }
    }
}
