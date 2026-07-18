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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Atomically refreshes dual-quorum signed serving-inventory runtime verification keys.
 *
 * <p>Bootstrap must produce a current publication whose deployment and witness runtime thresholds
 * are usable. Every modified response is strictly parsed, checked against the process-local chain,
 * verified by both independent bootstrap-root quorums, and committed to a durable sequence floor
 * before one immutable local key-set snapshot is published. Any transport, protocol, signature,
 * lifecycle, floor, or refresh-age ambiguity immediately closes key access.</p>
 *
 * <p>An unknown runtime signature key may trigger one synchronous conditional refresh under a
 * bounded cooldown. Reads of snapshot, health, and capability state never perform remote I/O.</p>
 */
public final class DynamicTestSuiteStabilityServingInventoryTrustRootAuthority
        implements AutoCloseable {

    /** Exact media type required from the managed dual trust-root endpoint. */
    public static final String MEDIA_TYPE =
            "application/vnd.bloge.suite-stability-serving-inventory-trust-roots.v1+json";
    /** Explicit protocol negotiation header required on every response. */
    public static final String PROTOCOL_HEADER =
            "X-BLOGE-Serving-Inventory-Trust-Root-Protocol";

    private static final Logger log = LoggerFactory.getLogger(
            DynamicTestSuiteStabilityServingInventoryTrustRootAuthority.class);
    private static final int MAXIMUM_DOCUMENT_BYTES = 512 * 1024;

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ConfiguredTestSuiteStabilityServingInventoryTrustRootAuthority.ExpectedBinding
            binding;
    private final Set<String> acceptedPolicyFingerprints;
    private final int deploymentRootSignatureThreshold;
    private final List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
            deploymentRootKeys;
    private final int witnessRootSignatureThreshold;
    private final List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
            witnessRootKeys;
    private final TestSuiteStabilityServingInventoryTrustRootFloor floor;
    private final Settings settings;
    private final DocumentFetcher fetcher;
    private final Object refreshLock = new Object();
    private final ScheduledThreadPoolExecutor scheduler;
    private final AtomicBoolean refreshFailureLogged = new AtomicBoolean();

    private volatile State state = State.empty();
    private volatile Instant nextUnknownKeyRefreshAt = Instant.MIN;
    private volatile boolean closed;

    /**
     * Bootstraps the managed key-set source and starts its background refresh lane.
     *
     * @param objectMapper application protocol mapper
     * @param binding exact local trust-root publication binding
     * @param acceptedPolicyFingerprints accepted key-rotation policy revisions
     * @param deploymentRootSignatureThreshold deployment bootstrap-root threshold
     * @param deploymentRootKeys deployment bootstrap public keys
     * @param witnessRootSignatureThreshold independent witness bootstrap-root threshold
     * @param witnessRootKeys witness bootstrap public keys
     * @param floor durable key-set publication floor
     * @param settings bounded HTTPS refresh settings
     */
    public DynamicTestSuiteStabilityServingInventoryTrustRootAuthority(
            ObjectMapper objectMapper,
            ConfiguredTestSuiteStabilityServingInventoryTrustRootAuthority.ExpectedBinding binding,
            Set<String> acceptedPolicyFingerprints,
            int deploymentRootSignatureThreshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    deploymentRootKeys,
            int witnessRootSignatureThreshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    witnessRootKeys,
            TestSuiteStabilityServingInventoryTrustRootFloor floor,
            Settings settings) {
        this(objectMapper, Clock.systemUTC(), binding, acceptedPolicyFingerprints,
                deploymentRootSignatureThreshold, deploymentRootKeys,
                witnessRootSignatureThreshold, witnessRootKeys, floor, settings, null, true);
    }

    DynamicTestSuiteStabilityServingInventoryTrustRootAuthority(
            ObjectMapper objectMapper,
            Clock clock,
            ConfiguredTestSuiteStabilityServingInventoryTrustRootAuthority.ExpectedBinding binding,
            Set<String> acceptedPolicyFingerprints,
            int deploymentRootSignatureThreshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    deploymentRootKeys,
            int witnessRootSignatureThreshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    witnessRootKeys,
            TestSuiteStabilityServingInventoryTrustRootFloor floor,
            Settings settings,
            DocumentFetcher fetcher,
            boolean startScheduler) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.acceptedPolicyFingerprints = Set.copyOf(Objects.requireNonNull(
                acceptedPolicyFingerprints, "acceptedPolicyFingerprints"));
        this.deploymentRootSignatureThreshold = deploymentRootSignatureThreshold;
        this.deploymentRootKeys = List.copyOf(Objects.requireNonNull(
                deploymentRootKeys, "deploymentRootKeys"));
        this.witnessRootSignatureThreshold = witnessRootSignatureThreshold;
        this.witnessRootKeys = List.copyOf(Objects.requireNonNull(
                witnessRootKeys, "witnessRootKeys"));
        this.floor = Objects.requireNonNull(floor, "floor");
        if (!this.floor.durable()) {
            throw new IllegalArgumentException(
                    "Dynamic serving-inventory trust roots require a durable floor");
        }
        this.settings = Objects.requireNonNull(settings, "settings").validated();
        this.fetcher = fetcher == null ? new HttpDocumentFetcher(this.settings) : fetcher;
        if (!refresh() || !usable(state, clock.instant())) {
            throw new IllegalStateException(
                    "Dynamic serving-inventory trust-root bootstrap is unavailable");
        }
        this.scheduler = startScheduler ? scheduler() : null;
    }

    /**
     * Parses strict bootstrap-root configuration and constructs one managed dual key-set source.
     *
     * @param objectMapper application protocol mapper
     * @param binding exact fleet, root-set, protocol, and bootstrap-domain binding
     * @param acceptedPolicies comma-separated accepted rotation-policy fingerprints
     * @param deploymentRootSignatureThreshold deployment bootstrap-root M-of-N threshold
     * @param deploymentRootKeysJson public deployment bootstrap-root key array
     * @param witnessRootSignatureThreshold independent witness bootstrap-root threshold
     * @param witnessRootKeysJson public witness bootstrap-root key array
     * @param floor durable root-publication sequence floor
     * @param settings bounded strict-HTTPS refresh settings
     * @return bootstrapped managed runtime-key authority
     */
    public static DynamicTestSuiteStabilityServingInventoryTrustRootAuthority fromJson(
            ObjectMapper objectMapper,
            ConfiguredTestSuiteStabilityServingInventoryTrustRootAuthority.ExpectedBinding binding,
            String acceptedPolicies,
            int deploymentRootSignatureThreshold,
            String deploymentRootKeysJson,
            int witnessRootSignatureThreshold,
            String witnessRootKeysJson,
            TestSuiteStabilityServingInventoryTrustRootFloor floor,
            Settings settings) {
        try {
            ObjectMapper strict = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            return new DynamicTestSuiteStabilityServingInventoryTrustRootAuthority(
                    objectMapper, binding,
                    ConfiguredTestSuiteStabilityServingInventoryAuthority.parsePolicies(
                            acceptedPolicies),
                    deploymentRootSignatureThreshold,
                    ConfiguredTestSuiteStabilityServingInventoryAuthority.parseKeys(
                            strict, deploymentRootKeysJson),
                    witnessRootSignatureThreshold,
                    ConfiguredTestSuiteStabilityServingInventoryAuthority.parseKeys(
                            strict, witnessRootKeysJson),
                    floor, settings);
        } catch (RuntimeException | java.security.GeneralSecurityException
                 | java.io.IOException invalid) {
            throw new IllegalArgumentException(
                    "Dynamic serving-inventory trust-root configuration is invalid", invalid);
        }
    }

    /**
     * Returns current keys, synchronously refreshing once when a candidate uses an unknown key.
     *
     * @param deploymentSignatures untrusted deployment publication signatures
     * @param witnessSignatures untrusted witness checkpoint signatures
     * @return current verified immutable dual key set
     */
    ConfiguredTestSuiteStabilityServingInventoryTrustRootAuthority.VerifiedKeySet keysFor(
            List<TestSuiteStabilityServingInventory.AuthoritySignature> deploymentSignatures,
            List<TestSuiteStabilityServingInventory.AuthoritySignature> witnessSignatures) {
        State observed = state;
        Instant now = clock.instant();
        if (usable(observed, now) && unknownKey(observed, deploymentSignatures,
                witnessSignatures)) {
            refreshUnknownKey();
            observed = state;
        }
        if (!usable(observed, clock.instant())) {
            throw new IllegalStateException(
                    "Dynamic serving-inventory trust-root snapshot is unavailable");
        }
        return observed.authority().verifiedKeySet();
    }

    /** Returns aggregate refresh truth without remote I/O, key ids, endpoints, or fingerprints. */
    public Snapshot snapshot() {
        State observed = state;
        Instant now = clock.instant();
        ConfiguredTestSuiteStabilityServingInventoryTrustRootAuthority.Snapshot rootSnapshot =
                observed.authority() == null ? null : observed.authority().snapshot();
        String status = effectiveStatus(observed, rootSnapshot, now);
        boolean available = !closed && "HEALTHY".equals(status);
        return new Snapshot(Snapshot.SCHEMA_VERSION, available, status,
                observed.publication() == null ? 0 : observed.publication().material().sequence(),
                observed.lastSuccessfulRefreshAt(), observed.refreshSuccessCount(),
                observed.refreshFailureCount(), observed.lastFailureCode(),
                settings.refreshInterval().toSeconds(), settings.requestTimeout().toMillis(),
                settings.unknownKeyRefreshInterval().toSeconds(),
                settings.maximumSnapshotAge().toSeconds(),
                rootSnapshot == null ? 0 : rootSnapshot.deploymentSignatureThreshold(),
                rootSnapshot == null ? 0 : rootSnapshot.witnessSignatureThreshold(),
                rootSnapshot == null ? 0 : rootSnapshot.activeDeploymentAuthorityCount(),
                rootSnapshot == null ? 0 : rootSnapshot.activeWitnessAuthorityCount(),
                true, floor.externallyAnchored(), floor.byzantineQuorumAnchored(),
                scheduler != null && !closed);
    }

    /** @return true only when managed-root ordering is anchored outside the local database */
    boolean externallyAnchoredFloor() {
        return floor.externallyAnchored();
    }

    /** @return true only when managed-root ordering uses an intersecting Byzantine quorum */
    boolean byzantineQuorumAnchoredFloor() {
        return floor.byzantineQuorumAnchored();
    }

    /** Returns the private managed root generation for exact cohort convergence. */
    String generationFingerprint() {
        State observed = state;
        if (!usable(observed, clock.instant())) {
            return "";
        }
        return observed.publication().materialFingerprint();
    }

    /** Performs one immediate conditional refresh; exposed package-locally for deterministic tests. */
    boolean refreshNow() {
        return refresh();
    }

    /** Stops background refresh and immediately makes runtime key access unavailable. */
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
            if (document.notModified()) {
                if (previous.authority() == null) {
                    throw new IllegalArgumentException(
                            "Trust-root source returned 304 before bootstrap");
                }
                state = previous.succeeded(previous.publication(), previous.authority(),
                        document.etag().isBlank() ? previous.etag() : document.etag(), now);
            } else {
                String json = new String(document.body(), java.nio.charset.StandardCharsets.UTF_8);
                TestSuiteStabilityServingInventoryTrustRootPublication publication =
                        ConfiguredTestSuiteStabilityServingInventoryTrustRootAuthority
                                .parsePublication(objectMapper, json);
                requireSuccessor(previous.publication(), publication);
                var authority =
                        new ConfiguredTestSuiteStabilityServingInventoryTrustRootAuthority(
                                objectMapper, clock, binding, acceptedPolicyFingerprints,
                                deploymentRootSignatureThreshold, deploymentRootKeys,
                                witnessRootSignatureThreshold, witnessRootKeys,
                                floor, publication);
                state = previous.succeeded(publication, authority,
                        document.etag().isBlank() ? previous.etag() : document.etag(), now);
            }
            refreshFailureLogged.set(false);
            return true;
        } catch (RuntimeException | IOException invalid) {
            state = previous.failed(failureCode(invalid));
            if (refreshFailureLogged.compareAndSet(false, true)) {
                log.warn("Dynamic serving-inventory trust-root refresh failed; "
                        + "fresh admission is now fail-closed");
            }
            return false;
        }
    }

    private static void requireSuccessor(
            TestSuiteStabilityServingInventoryTrustRootPublication previous,
            TestSuiteStabilityServingInventoryTrustRootPublication candidate) {
        if (previous == null) {
            return;
        }
        long current = previous.material().sequence();
        long next = candidate.material().sequence();
        if (next == current) {
            if (!candidate.materialFingerprint().equals(previous.materialFingerprint())) {
                throw new IllegalArgumentException(
                        "Serving-inventory trust-root publication fork");
            }
            return;
        }
        if (next != current + 1
                || !candidate.material().previousMaterialFingerprint().equals(
                previous.materialFingerprint())) {
            throw new IllegalArgumentException(
                    "Serving-inventory trust-root publication chain is discontinuous");
        }
    }

    private static boolean unknownKey(
            State state,
            List<TestSuiteStabilityServingInventory.AuthoritySignature> deploymentSignatures,
            List<TestSuiteStabilityServingInventory.AuthoritySignature> witnessSignatures) {
        ConfiguredTestSuiteStabilityServingInventoryTrustRootAuthority.VerifiedKeySet keys =
                state.authority().verifiedKeySet();
        return references(deploymentSignatures).stream()
                .anyMatch(reference -> !keys.deploymentKeys().containsKey(reference))
                || references(witnessSignatures).stream()
                .anyMatch(reference -> !keys.witnessKeys().containsKey(reference));
    }

    private static Set<String> references(
            List<TestSuiteStabilityServingInventory.AuthoritySignature> signatures) {
        Set<String> result = new HashSet<>();
        for (TestSuiteStabilityServingInventory.AuthoritySignature signature
                : signatures == null
                ? List.<TestSuiteStabilityServingInventory.AuthoritySignature>of()
                : signatures) {
            result.add(signature.authorityId() + '\u0000' + signature.keyId());
        }
        return result;
    }

    private boolean usable(State observed, Instant now) {
        return !closed && observed.localState() == LocalState.HEALTHY
                && observed.authority() != null
                && observed.authority().snapshot().available()
                && observed.lastSuccessfulRefreshAt() != null
                && Duration.between(observed.lastSuccessfulRefreshAt(), now)
                .compareTo(settings.maximumSnapshotAge()) <= 0;
    }

    private String effectiveStatus(
            State observed,
            ConfiguredTestSuiteStabilityServingInventoryTrustRootAuthority.Snapshot rootSnapshot,
            Instant now) {
        if (closed) {
            return "CLOSED";
        }
        if (observed.localState() != LocalState.HEALTHY || rootSnapshot == null) {
            return "REFRESH_UNAVAILABLE";
        }
        if (Duration.between(observed.lastSuccessfulRefreshAt(), now)
                .compareTo(settings.maximumSnapshotAge()) > 0) {
            return "SOURCE_EXPIRED";
        }
        if (!rootSnapshot.available()) {
            return rootSnapshot.status();
        }
        return "HEALTHY";
    }

    private ScheduledThreadPoolExecutor scheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task,
                    "resource-gateway-serving-inventory-trust-root-refresh");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        long intervalMillis = settings.refreshInterval().toMillis();
        long initialDelay = ThreadLocalRandom.current().nextLong(
                Math.max(1L, intervalMillis / 2L), intervalMillis + 1L);
        executor.scheduleWithFixedDelay(this::refreshSafely, initialDelay,
                intervalMillis, TimeUnit.MILLISECONDS);
        return executor;
    }

    private void refreshSafely() {
        try {
            refresh();
        } catch (RuntimeException unexpected) {
            state = state.failed("TRUST_ROOT_REFRESH_FAILED");
        }
    }

    private static String failureCode(Throwable failure) {
        return failure instanceof RemoteTrustRootUnavailableException
                ? "TRUST_ROOT_SOURCE_UNAVAILABLE" : "TRUST_ROOT_DOCUMENT_INVALID";
    }

    private enum LocalState {
        EMPTY,
        HEALTHY,
        UNAVAILABLE
    }

    /** Bounded strict-HTTPS refresh settings for the managed dual key-set source. */
    public record Settings(
            URI publicationUri,
            Duration refreshInterval,
            Duration requestTimeout,
            Duration unknownKeyRefreshInterval,
            Duration maximumSnapshotAge,
            boolean allowInsecureLoopback) {

        /** @return validated immutable settings */
        public Settings validated() {
            validateUri(publicationUri, allowInsecureLoopback);
            Duration refresh = bounded(refreshInterval, Duration.ofSeconds(1),
                    Duration.ofHours(1), "refresh interval");
            Duration timeout = bounded(requestTimeout, Duration.ofMillis(100),
                    Duration.ofSeconds(30), "request timeout");
            Duration unknown = bounded(unknownKeyRefreshInterval, Duration.ofSeconds(1),
                    Duration.ofHours(1), "unknown-key refresh interval");
            Duration maximumAge = bounded(maximumSnapshotAge, Duration.ofSeconds(2),
                    Duration.ofHours(24), "maximum snapshot age");
            if (maximumAge.compareTo(refresh.plus(timeout)) < 0) {
                throw new IllegalArgumentException(
                        "Trust-root snapshot age must cover refresh plus timeout");
            }
            return new Settings(publicationUri, refresh, timeout, unknown, maximumAge,
                    allowInsecureLoopback);
        }

        private static void validateUri(URI uri, boolean allowInsecureLoopback) {
            if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getQuery() != null) {
                throw new IllegalArgumentException(
                        "A valid serving-inventory trust-root URI is required");
            }
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            boolean loopback = host.equals("localhost") || host.equals("127.0.0.1")
                    || host.equals("::1");
            if (!allowInsecureLoopback || !loopback
                    || !"http".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException(
                        "Serving-inventory trust-root source must use HTTPS");
            }
        }
    }

    @FunctionalInterface
    interface DocumentFetcher {
        FetchedDocument fetch(URI uri, String etag, Duration timeout);
    }

    record FetchedDocument(byte[] body, String etag, boolean notModified) {
        FetchedDocument {
            body = body == null ? new byte[0] : body.clone();
            etag = normalized(etag);
            if (etag.length() > 512 || etag.chars().anyMatch(Character::isISOControl)
                    || !notModified && (body.length == 0
                    || body.length > MAXIMUM_DOCUMENT_BYTES)) {
                throw new IllegalArgumentException(
                        "Invalid serving-inventory trust-root response");
            }
        }

        static FetchedDocument modified(byte[] body, String etag) {
            return new FetchedDocument(body, etag, false);
        }

        static FetchedDocument notModified(String etag) {
            return new FetchedDocument(new byte[0], etag, true);
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    private static final class HttpDocumentFetcher implements DocumentFetcher {
        private final HttpClient client;

        private HttpDocumentFetcher(Settings settings) {
            client = HttpClient.newBuilder().connectTimeout(settings.requestTimeout())
                    .followRedirects(HttpClient.Redirect.NEVER).build();
        }

        @Override
        public FetchedDocument fetch(URI uri, String etag, Duration timeout) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET().timeout(timeout)
                        .header("Accept", MEDIA_TYPE)
                        .header(PROTOCOL_HEADER,
                                TestSuiteStabilityServingInventoryTrustRootPublication
                                        .SCHEMA_VERSION);
                if (!etag.isBlank()) {
                    request.header("If-None-Match", etag);
                }
                HttpResponse<InputStream> response = client.send(
                        request.build(), HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 304) {
                    requireProtocolHeaders(response);
                    response.body().close();
                    return FetchedDocument.notModified(
                            response.headers().firstValue("ETag").orElse(etag));
                }
                if (response.statusCode() != 200) {
                    response.body().close();
                    throw new RemoteTrustRootUnavailableException(
                            "Trust-root source returned a non-success status");
                }
                requireProtocolHeaders(response);
                long declaredLength = response.headers().firstValueAsLong("Content-Length")
                        .orElse(-1);
                if (declaredLength > MAXIMUM_DOCUMENT_BYTES) {
                    response.body().close();
                    throw new IllegalArgumentException("Trust-root publication is too large");
                }
                byte[] body;
                try (InputStream input = response.body()) {
                    body = input.readNBytes(MAXIMUM_DOCUMENT_BYTES + 1);
                }
                return FetchedDocument.modified(body,
                        response.headers().firstValue("ETag").orElse(""));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RemoteTrustRootUnavailableException(
                        "Trust-root request was interrupted", interrupted);
            } catch (IOException unavailable) {
                throw new RemoteTrustRootUnavailableException(
                        "Trust-root request failed", unavailable);
            }
        }

        private static void requireProtocolHeaders(HttpResponse<?> response) {
            String contentType = response.headers().firstValue("Content-Type")
                    .orElse("").toLowerCase(Locale.ROOT);
            String protocol = response.headers().firstValue(PROTOCOL_HEADER).orElse("");
            if (!(contentType.equals(MEDIA_TYPE) || contentType.startsWith(MEDIA_TYPE + ";"))
                    || !TestSuiteStabilityServingInventoryTrustRootPublication.SCHEMA_VERSION
                    .equals(protocol)) {
                try {
                    if (response.body() instanceof InputStream body) {
                        body.close();
                    }
                } catch (IOException ignored) {
                    // The protocol mismatch remains the authoritative failure.
                }
                throw new IllegalArgumentException(
                        "Serving-inventory trust-root protocol negotiation failed");
            }
        }
    }

    static final class RemoteTrustRootUnavailableException extends RuntimeException {
        RemoteTrustRootUnavailableException(String message) {
            super(message);
        }

        RemoteTrustRootUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record State(
            TestSuiteStabilityServingInventoryTrustRootPublication publication,
            ConfiguredTestSuiteStabilityServingInventoryTrustRootAuthority authority,
            String etag,
            LocalState localState,
            Instant lastSuccessfulRefreshAt,
            long refreshSuccessCount,
            long refreshFailureCount,
            String lastFailureCode) {

        private static State empty() {
            return new State(null, null, "", LocalState.EMPTY, null, 0, 0, "");
        }

        private State succeeded(
                TestSuiteStabilityServingInventoryTrustRootPublication nextPublication,
                ConfiguredTestSuiteStabilityServingInventoryTrustRootAuthority nextAuthority,
                String nextEtag,
                Instant observedAt) {
            return new State(nextPublication, nextAuthority, nextEtag, LocalState.HEALTHY,
                    observedAt, refreshSuccessCount + 1, refreshFailureCount, "");
        }

        private State failed(String failureCode) {
            return new State(publication, authority, etag, LocalState.UNAVAILABLE,
                    lastSuccessfulRefreshAt, refreshSuccessCount,
                    refreshFailureCount + 1, normalized(failureCode));
        }
    }

    /**
     * Aggregate key-free refresh and lifecycle status.
     *
     * @param schemaVersion snapshot protocol generation
     * @param available whether the current dual key set may verify inventory publications
     * @param status bounded refresh lifecycle state
     * @param sequence current accepted trust-root sequence
     * @param lastSuccessfulRefreshAt last complete dual-root publication time
     * @param refreshSuccessCount process-local successful refresh count
     * @param refreshFailureCount process-local failed refresh count
     * @param lastFailureCode stable payload-free failure family
     * @param refreshIntervalSeconds configured background refresh interval
     * @param requestTimeoutMillis configured source request timeout
     * @param unknownKeyRefreshIntervalSeconds minimum synchronous refresh interval
     * @param maximumSnapshotAgeSeconds hard local freshness fence
     * @param deploymentSignatureThreshold active deployment-key threshold
     * @param witnessSignatureThreshold active witness-key threshold
     * @param activeDeploymentAuthorityCount active deployment authorities
     * @param activeWitnessAuthorityCount active witness authorities
     * @param durableFloor whether the local root head survives fleet restart
     * @param externalNonEquivocation whether root ordering is anchored outside the database
     * @param byzantineQuorumNonEquivocation whether that anchor tolerates declared faulty notaries
     * @param automaticRefresh whether the runtime owns a background refresh lane
     */
    public record Snapshot(
            String schemaVersion,
            boolean available,
            String status,
            long sequence,
            Instant lastSuccessfulRefreshAt,
            long refreshSuccessCount,
            long refreshFailureCount,
            String lastFailureCode,
            long refreshIntervalSeconds,
            long requestTimeoutMillis,
            long unknownKeyRefreshIntervalSeconds,
            long maximumSnapshotAgeSeconds,
            int deploymentSignatureThreshold,
            int witnessSignatureThreshold,
            long activeDeploymentAuthorityCount,
            long activeWitnessAuthorityCount,
            boolean durableFloor,
            boolean externalNonEquivocation,
            boolean byzantineQuorumNonEquivocation,
            boolean automaticRefresh) {

        /** Current aggregate dynamic trust-root snapshot generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityServingInventoryDynamicTrustRootSnapshot.v1";

        /** Enforces bounded counters, timing, and key-free status. */
        public Snapshot {
            status = normalized(status);
            lastFailureCode = normalized(lastFailureCode);
            if (!SCHEMA_VERSION.equals(schemaVersion) || status.isBlank() || sequence < 0
                    || refreshSuccessCount < 0 || refreshFailureCount < 0
                    || refreshIntervalSeconds < 1 || requestTimeoutMillis < 100
                    || unknownKeyRefreshIntervalSeconds < 1
                    || maximumSnapshotAgeSeconds < 2
                    || deploymentSignatureThreshold < 0
                    || witnessSignatureThreshold < 0
                    || activeDeploymentAuthorityCount < 0
                    || activeWitnessAuthorityCount < 0 || !durableFloor
                    || byzantineQuorumNonEquivocation && !externalNonEquivocation) {
                throw new IllegalArgumentException(
                        "Dynamic serving-inventory trust-root snapshot is invalid");
            }
        }
    }

    private static Duration bounded(
            Duration value, Duration minimum, Duration maximum, String label) {
        Duration result = Objects.requireNonNull(value, label);
        if (result.compareTo(minimum) < 0 || result.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Invalid trust-root " + label);
        }
        return result;
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
