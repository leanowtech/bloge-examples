package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationExternalArchiveIntegrity;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationExternalArchiveInventoryIntegrity;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Strict multi-authority HTTPS adapter for external immutable observation archives.
 *
 * <p>Every configured authority receives the same fresh challenge-bound request concurrently.
 * Accepted receipts count only after strict HTTP/media/version parsing, exact endpoint and request
 * binding, canonical fingerprint verification, freshness checks, and detached Ed25519 signature
 * verification. A configured number of distinct authorities and failure domains must accept the
 * write. One authenticated immutable conflict is fatal even when enough other copies accept it.
 * Malformed or unavailable minorities are tolerated only when the copy threshold still holds.</p>
 *
 * <p>The adapter never follows redirects and never retries. Response bodies are bounded, strict
 * JSON rejects duplicate/unknown/trailing fields, and aggregate descriptor/health state contains
 * no endpoint, authority, key, object, request, challenge, or fingerprint identity.</p>
 */
public final class HttpTestSuiteStabilityObservationExternalArchiveAuthority
        implements TestSuiteStabilityObservationExternalArchiveAuthority,
        TestSuiteStabilityObservationExternalArchiveInventoryAuthority {

    /** Exact request and response media type. */
    public static final String MEDIA_TYPE =
            "application/vnd.bloge.suite-stability-observation-external-archive.v1+json";
    /** Exact read-only inventory request and response media type. */
    public static final String INVENTORY_MEDIA_TYPE =
            "application/vnd.bloge.suite-stability-observation-external-archive-inventory.v1+json";
    /** Explicit request/response wire-version header. */
    public static final String PROTOCOL_HEADER =
            "X-BLOGE-Stability-Observation-External-Archive-Protocol";

    private static final int MAXIMUM_REQUEST_BYTES = 2 * 1024 * 1024;
    private static final int MAXIMUM_RESPONSE_BYTES = 128 * 1024;
    private static final int MAXIMUM_INVENTORY_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Set<String> ENDPOINT_FIELDS =
            Set.of("authorityId", "failureDomain", "uri");

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SecureRandom secureRandom;
    private final String trustDomain;
    private final String archiveSetId;
    private final int requiredCopies;
    private final Duration minimumRetention;
    private final Map<String,
            ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> keys;
    private final List<Endpoint> endpoints;
    private final Map<String, Endpoint> endpointsByAuthority;
    private final Settings settings;
    private final HttpClient httpClient;
    private final AtomicReference<RuntimeState> state =
            new AtomicReference<>(RuntimeState.initial());

    /**
     * Strictly parses static historical public keys and independent WORM endpoints.
     *
     * @param objectMapper canonical JSON baseline
     * @param trustDomain expected external archive trust domain
     * @param archiveSetId stable independently governed archive-set identity
     * @param requiredCopies minimum accepted independent copies
     * @param minimumRetention minimum immutable retention requested for every object
     * @param authorityKeysJson bounded public Ed25519 key configuration
     * @param endpointsJson one endpoint and failure domain per authority
     * @param settings bounded transport and receipt-freshness policy
     * @return configured strict HTTPS external archive authority
     */
    public static HttpTestSuiteStabilityObservationExternalArchiveAuthority fromJson(
            ObjectMapper objectMapper,
            String trustDomain,
            String archiveSetId,
            int requiredCopies,
            Duration minimumRetention,
            String authorityKeysJson,
            String endpointsJson,
            Settings settings) {
        try {
            ObjectMapper strict = strict(Objects.requireNonNull(objectMapper, "objectMapper"));
            return new HttpTestSuiteStabilityObservationExternalArchiveAuthority(
                    strict, Clock.systemUTC(), new SecureRandom(), trustDomain, archiveSetId,
                    requiredCopies, minimumRetention,
                    ConfiguredTestSuiteStabilityServingInventoryAuthority.parseKeys(
                            strict, authorityKeysJson),
                    parseEndpoints(strict, endpointsJson), settings, null);
        } catch (RuntimeException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new IllegalArgumentException(
                    "External observation-archive configuration is invalid", invalid);
        }
    }

    /** Package-visible seam for deterministic protocol, trust, and timeout tests. */
    HttpTestSuiteStabilityObservationExternalArchiveAuthority(
            ObjectMapper objectMapper,
            Clock clock,
            SecureRandom secureRandom,
            String trustDomain,
            String archiveSetId,
            int requiredCopies,
            Duration minimumRetention,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    authorityKeys,
            List<Endpoint> endpoints,
            Settings settings,
            HttpClient httpClient) {
        this.objectMapper = strict(Objects.requireNonNull(objectMapper, "objectMapper"));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        this.trustDomain = normalized(trustDomain);
        this.archiveSetId = normalized(archiveSetId);
        this.requiredCopies = requiredCopies;
        this.minimumRetention = Objects.requireNonNull(
                minimumRetention, "minimumRetention");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.endpoints = validateEndpoints(endpoints, settings.allowInsecureLoopback());
        this.endpointsByAuthority = indexEndpoints(this.endpoints);
        this.keys = ConfiguredTestSuiteStabilityServingInventoryAuthority.indexedKeys(
                authorityKeys, requiredCopies);
        validatePolicy();
        this.httpClient = httpClient == null ? HttpClient.newBuilder()
                .connectTimeout(settings.requestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build() : httpClient;
    }

    /**
     * Stores one signed retirement concurrently and returns a locally reverified receipt set.
     *
     * <p>Calls for independent suite retirements may run concurrently and each call fans out to
     * its configured authorities concurrently. Aggregate state uses atomic updates, while callers
     * own retry/backoff so this adapter performs exactly one request per configured authority.</p>
     */
    @Override
    public TestSuiteStabilityObservationExternalArchiveReceiptSet archive(
            TestSuiteStabilityObservationFloorRetirement retirement,
            Instant retainUntil) {
        Objects.requireNonNull(retirement, "retirement");
        Objects.requireNonNull(retainUntil, "retainUntil");
        if (!descriptor().available()) {
            throw failed(ExternalArchiveException.Reason.UNAVAILABLE);
        }
        Instant requestedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        if (retainUntil.isBefore(requestedAt.plus(minimumRetention))) {
            throw failed(ExternalArchiveException.Reason.INVALID_RECEIPT);
        }
        TestSuiteStabilityObservationExternalArchiveRequest request;
        byte[] body;
        try {
            request = TestSuiteStabilityObservationExternalArchiveRequest.create(
                    objectMapper, trustDomain, archiveSetId, retirement, retainUntil,
                    challenge(), requestedAt,
                    requestedAt.plus(settings.maximumReceiptLifetime()));
            body = write(request);
        } catch (RuntimeException invalid) {
            throw failed(ExternalArchiveException.Reason.INVALID_RECEIPT);
        }
        if (body.length > MAXIMUM_REQUEST_BYTES) {
            throw failed(ExternalArchiveException.Reason.INVALID_RECEIPT);
        }
        String objectId = TestSuiteStabilityObservationExternalArchiveIntegrity.objectId(
                objectMapper, retirement);
        List<CompletableFuture<Observation>> pending = endpoints.stream()
                .map(endpoint -> observeAsync(endpoint, request, objectId, body))
                .toList();
        List<TestSuiteStabilityObservationExternalArchiveReceipt> accepted =
                new ArrayList<>();
        boolean authenticatedConflict = false;
        boolean invalidResponse = false;
        for (CompletableFuture<Observation> result : pending) {
            try {
                Observation observation = result.join();
                if (observation.conflict()) {
                    authenticatedConflict = true;
                } else {
                    accepted.add(observation.receipt());
                }
            } catch (CompletionException failure) {
                if (rootCause(failure) instanceof InvalidArchiveResponseException) {
                    invalidResponse = true;
                }
            }
        }
        if (authenticatedConflict) {
            throw failed(ExternalArchiveException.Reason.AUTHENTICATED_CONFLICT);
        }
        if (accepted.size() < requiredCopies) {
            throw failed(invalidResponse
                    ? ExternalArchiveException.Reason.INVALID_RECEIPT
                    : ExternalArchiveException.Reason.UNAVAILABLE);
        }
        accepted.sort(Comparator.comparing(
                TestSuiteStabilityObservationExternalArchiveReceipt::authorityId));
        Instant confirmedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet;
        try {
            receiptSet = TestSuiteStabilityObservationExternalArchiveIntegrity.sealSet(
                    objectMapper, request, requiredCopies, accepted, confirmedAt);
        } catch (RuntimeException invalid) {
            throw failed(ExternalArchiveException.Reason.INVALID_RECEIPT);
        }
        if (verifyAt(receiptSet, clock.instant())
                != TestSuiteStabilityObservationExternalArchiveAuthority.Verification.VERIFIED) {
            throw failed(ExternalArchiveException.Reason.INVALID_RECEIPT);
        }
        boolean degraded = invalidResponse || accepted.size() < endpoints.size();
        state.updateAndGet(current -> current.succeeded(confirmedAt, degraded));
        return receiptSet;
    }

    /** Re-verifies canonical closure, configured topology, freshness, and every signature. */
    @Override
    public TestSuiteStabilityObservationExternalArchiveAuthority.Verification verify(
            TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet) {
        try {
            return verifyAt(Objects.requireNonNull(receiptSet, "receiptSet"), clock.instant());
        } catch (RuntimeException invalid) {
            return TestSuiteStabilityObservationExternalArchiveAuthority.Verification.INVALID;
        }
    }

    /** Returns key-free static policy facts and current local-key readiness. */
    @Override
    public Descriptor descriptor() {
        boolean available = activeAuthorityCount(clock.instant()) >= requiredCopies;
        return new Descriptor(Descriptor.SCHEMA_VERSION, available, true, true, true,
                endpoints.size(), requiredCopies, endpoints.size(), minimumRetention,
                Map.of("sourceType", "HTTPS_SIGNED_MULTI_WORM",
                        "externalFirstCommit", true,
                        "writeOnce", true,
                        "complianceRetention", true));
    }

    /** Returns aggregate process-local state without remote or cryptographic identities. */
    @Override
    public Snapshot snapshot() {
        RuntimeState observed = state.get();
        return new Snapshot(Snapshot.SCHEMA_VERSION, observed.available(), observed.status(),
                observed.lastSuccessfulArchiveAt(), observed.successCount(),
                observed.failureCount(), observed.conflictCount(), endpoints.size(),
                requiredCopies, endpoints.size());
    }

    /** Returns configured inventory authorities in stable lexical order. */
    @Override
    public List<String> inventoryAuthorities() {
        return endpointsByAuthority.keySet().stream().sorted().toList();
    }

    /**
     * Reads one strict signed page without exposing any remote mutation operation.
     *
     * <p>The same configured endpoint accepts a distinct inventory media type. A first-page call
     * establishes an immutable snapshot; later calls must carry the exact snapshot and object
     * cursor supplied by the preceding verified page.</p>
     */
    @Override
    public TestSuiteStabilityObservationExternalArchiveInventoryPage inventoryPage(
            String authorityId,
            Cursor cursor,
            int maximumItems) {
        String exactAuthority = normalized(authorityId);
        Cursor exactCursor = Objects.requireNonNull(cursor, "cursor");
        Endpoint endpoint = endpointsByAuthority.get(exactAuthority);
        Instant requestedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        if (endpoint == null || !activeAuthority(exactAuthority, requestedAt)) {
            throw inventoryFailed(InventoryException.Reason.UNAVAILABLE);
        }
        TestSuiteStabilityObservationExternalArchiveInventoryRequest request;
        byte[] body;
        try {
            request = TestSuiteStabilityObservationExternalArchiveInventoryRequest.create(
                    objectMapper, trustDomain, archiveSetId, exactAuthority,
                    exactCursor.snapshotId(), exactCursor.afterObjectId(),
                    exactCursor.pageSequence(), maximumItems, challenge(), requestedAt,
                    requestedAt.plus(settings.maximumReceiptLifetime()));
            body = writeInventoryRequest(request);
        } catch (RuntimeException invalid) {
            throw inventoryFailed(InventoryException.Reason.INVALID_PAGE);
        }
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint.uri())
                .timeout(settings.requestTimeout())
                .header("Accept", INVENTORY_MEDIA_TYPE)
                .header("Content-Type", INVENTORY_MEDIA_TYPE)
                .header(PROTOCOL_HEADER,
                        TestSuiteStabilityObservationExternalArchiveInventoryRequest
                                .SCHEMA_VERSION)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw inventoryFailed(InventoryException.Reason.UNAVAILABLE);
        } catch (IOException | RuntimeException unavailable) {
            throw inventoryFailed(InventoryException.Reason.UNAVAILABLE);
        }
        return inventoryResponse(endpoint, request, response);
    }

    /** Re-verifies one page against local topology, key lifecycle, and admission time. */
    @Override
    public TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Verification
            verifyInventoryPage(
                    TestSuiteStabilityObservationExternalArchiveInventoryPage page) {
        Objects.requireNonNull(page, "page");
        Endpoint endpoint = endpointsByAuthority.get(page.authorityId());
        if (endpoint == null || !activeAuthority(page.authorityId(), clock.instant())) {
            return TestSuiteStabilityObservationExternalArchiveInventoryAuthority
                    .Verification.UNAVAILABLE;
        }
        return inventoryPageValid(endpoint, page.request(), page, clock.instant())
                ? TestSuiteStabilityObservationExternalArchiveInventoryAuthority
                .Verification.VERIFIED
                : TestSuiteStabilityObservationExternalArchiveInventoryAuthority
                .Verification.INVALID;
    }

    /**
     * Re-verifies a stored page without applying its already-consumed live admission deadline.
     *
     * <p>The exact historical key must remain configured and must have been valid at the page's
     * signing time. Administrative revocation and missing trust material fail closed.</p>
     */
    @Override
    public TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Verification
            verifyStoredInventoryPage(
                    TestSuiteStabilityObservationExternalArchiveInventoryPage page) {
        Objects.requireNonNull(page, "page");
        Endpoint endpoint = endpointsByAuthority.get(page.authorityId());
        ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey key =
                keys.get(page.authorityId() + '\u0000' + page.keyId());
        if (endpoint == null || key == null || !key.activeAt(page.issuedAt())) {
            return TestSuiteStabilityObservationExternalArchiveInventoryAuthority
                    .Verification.UNAVAILABLE;
        }
        return storedInventoryPageValid(endpoint, page)
                ? TestSuiteStabilityObservationExternalArchiveInventoryAuthority
                .Verification.VERIFIED
                : TestSuiteStabilityObservationExternalArchiveInventoryAuthority
                .Verification.INVALID;
    }

    private TestSuiteStabilityObservationExternalArchiveAuthority.Verification verifyAt(
            TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet,
            Instant observedAt) {
        if (!TestSuiteStabilityObservationExternalArchiveIntegrity.valid(
                objectMapper, receiptSet)
                || !trustDomain.equals(receiptSet.request().trustDomain())
                || !archiveSetId.equals(receiptSet.request().archiveSetId())
                || requiredCopies != receiptSet.requiredCopies()
                || receiptSet.request().requestedAt().isAfter(observedAt)
                || !observedAt.isBefore(receiptSet.request().expiresAt())
                || Duration.between(receiptSet.request().requestedAt(),
                receiptSet.request().expiresAt()).compareTo(
                settings.maximumReceiptLifetime()) > 0
                || receiptSet.request().retainUntil().isBefore(
                receiptSet.request().requestedAt().plus(minimumRetention))) {
            return TestSuiteStabilityObservationExternalArchiveAuthority.Verification.INVALID;
        }
        for (TestSuiteStabilityObservationExternalArchiveReceipt receipt
                : receiptSet.receipts()) {
            Endpoint endpoint = endpointsByAuthority.get(receipt.authorityId());
            if (endpoint == null || !acceptedReceiptValid(
                    endpoint, receiptSet.request(), receipt, observedAt)) {
                return TestSuiteStabilityObservationExternalArchiveAuthority
                        .Verification.INVALID;
            }
        }
        return TestSuiteStabilityObservationExternalArchiveAuthority.Verification.VERIFIED;
    }

    private CompletableFuture<Observation> observeAsync(
            Endpoint endpoint,
            TestSuiteStabilityObservationExternalArchiveRequest request,
            String objectId,
            byte[] body) {
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint.uri())
                .timeout(settings.requestTimeout())
                .header("Accept", MEDIA_TYPE)
                .header("Content-Type", MEDIA_TYPE)
                .header(PROTOCOL_HEADER,
                        TestSuiteStabilityObservationExternalArchiveRequest.SCHEMA_VERSION)
                .header("Idempotency-Key", objectId)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            return httpClient.sendAsync(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                    .thenApply(response -> observe(endpoint, request, objectId, response));
        } catch (RuntimeException unavailable) {
            return CompletableFuture.failedFuture(unavailable);
        }
    }

    private TestSuiteStabilityObservationExternalArchiveInventoryPage inventoryResponse(
            Endpoint endpoint,
            TestSuiteStabilityObservationExternalArchiveInventoryRequest request,
            HttpResponse<InputStream> response) {
        if (response.statusCode() == 410) {
            closeQuietly(response.body());
            throw inventoryFailed(InventoryException.Reason.SNAPSHOT_EXPIRED);
        }
        if (response.statusCode() != 200) {
            closeQuietly(response.body());
            throw inventoryFailed(InventoryException.Reason.UNAVAILABLE);
        }
        if (!INVENTORY_MEDIA_TYPE.equalsIgnoreCase(response.headers()
                .firstValue("Content-Type").orElse(""))
                || !TestSuiteStabilityObservationExternalArchiveInventoryPage.SCHEMA_VERSION
                .equals(response.headers().firstValue(PROTOCOL_HEADER).orElse(""))
                || response.headers().firstValueAsLong("Content-Length").orElse(-1)
                > MAXIMUM_INVENTORY_RESPONSE_BYTES) {
            closeQuietly(response.body());
            throw inventoryFailed(InventoryException.Reason.INVALID_PAGE);
        }
        byte[] bytes;
        try (InputStream input = response.body()) {
            bytes = input.readNBytes(MAXIMUM_INVENTORY_RESPONSE_BYTES + 1);
        } catch (IOException invalid) {
            throw inventoryFailed(InventoryException.Reason.INVALID_PAGE);
        }
        if (bytes.length == 0 || bytes.length > MAXIMUM_INVENTORY_RESPONSE_BYTES) {
            throw inventoryFailed(InventoryException.Reason.INVALID_PAGE);
        }
        try {
            TestSuiteStabilityObservationExternalArchiveInventoryPage page =
                    objectMapper.readValue(bytes,
                            TestSuiteStabilityObservationExternalArchiveInventoryPage.class);
            if (!inventoryPageValid(endpoint, request, page, clock.instant())) {
                throw inventoryFailed(InventoryException.Reason.INVALID_PAGE);
            }
            return page;
        } catch (InventoryException invalid) {
            throw invalid;
        } catch (IOException | RuntimeException invalid) {
            throw inventoryFailed(InventoryException.Reason.INVALID_PAGE);
        }
    }

    private boolean inventoryPageValid(
            Endpoint endpoint,
            TestSuiteStabilityObservationExternalArchiveInventoryRequest request,
            TestSuiteStabilityObservationExternalArchiveInventoryPage page,
            Instant observedAt) {
        String expectedSnapshotId =
                TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.snapshotId(
                        objectMapper, trustDomain, archiveSetId, endpoint.authorityId(),
                        endpoint.failureDomain(), page.snapshotAt(),
                        page.snapshotObjectCount(), page.snapshotRoot());
        if (!page.fingerprintVerified(objectMapper)
                || !request.equals(page.request())
                || !trustDomain.equals(request.trustDomain())
                || !archiveSetId.equals(request.archiveSetId())
                || !endpoint.authorityId().equals(page.authorityId())
                || !endpoint.failureDomain().equals(page.failureDomain())
                || !expectedSnapshotId.equals(page.snapshotId())
                || !request.snapshotId().isEmpty()
                && !request.snapshotId().equals(page.snapshotId())
                || page.snapshotAt().isAfter(observedAt)
                || page.snapshotAt().isBefore(
                observedAt.minus(settings.maximumInventorySnapshotAge()))
                || page.issuedAt().isAfter(observedAt)
                || !observedAt.isBefore(request.expiresAt())
                || !observedAt.isBefore(page.expiresAt())
                || Duration.between(page.issuedAt(), page.expiresAt()).compareTo(
                settings.maximumReceiptLifetime()) > 0) {
            return false;
        }
        return signatureValid(page.authorityId(), page.keyId(), page.algorithm(),
                page.issuedAt(), page.expiresAt(), page.pageFingerprint(), page.signature(),
                observedAt, "External archive inventory page");
    }

    private boolean storedInventoryPageValid(
            Endpoint endpoint,
            TestSuiteStabilityObservationExternalArchiveInventoryPage page) {
        TestSuiteStabilityObservationExternalArchiveInventoryRequest request = page.request();
        String expectedSnapshotId =
                TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.snapshotId(
                        objectMapper, trustDomain, archiveSetId, endpoint.authorityId(),
                        endpoint.failureDomain(), page.snapshotAt(),
                        page.snapshotObjectCount(), page.snapshotRoot());
        if (!page.fingerprintVerified(objectMapper)
                || !trustDomain.equals(request.trustDomain())
                || !archiveSetId.equals(request.archiveSetId())
                || !endpoint.authorityId().equals(page.authorityId())
                || !endpoint.failureDomain().equals(page.failureDomain())
                || !expectedSnapshotId.equals(page.snapshotId())
                || !request.snapshotId().isEmpty()
                && !request.snapshotId().equals(page.snapshotId())
                || Duration.between(page.issuedAt(), page.expiresAt()).compareTo(
                settings.maximumReceiptLifetime()) > 0) {
            return false;
        }
        return signatureValid(page.authorityId(), page.keyId(), page.algorithm(),
                page.issuedAt(), page.expiresAt(), page.pageFingerprint(), page.signature(),
                page.issuedAt(), "Stored external archive inventory page");
    }

    private Observation observe(
            Endpoint endpoint,
            TestSuiteStabilityObservationExternalArchiveRequest request,
            String objectId,
            HttpResponse<InputStream> response) {
        int status = response.statusCode();
        if (status != 200 && status != 409) {
            closeQuietly(response.body());
            throw new CompletionException(new IOException(
                    "External archive authority is unavailable"));
        }
        String expectedVersion = status == 200
                ? TestSuiteStabilityObservationExternalArchiveReceipt.SCHEMA_VERSION
                : TestSuiteStabilityObservationExternalArchiveConflictReceipt.SCHEMA_VERSION;
        if (!MEDIA_TYPE.equalsIgnoreCase(response.headers()
                .firstValue("Content-Type").orElse(""))
                || !expectedVersion.equals(response.headers()
                .firstValue(PROTOCOL_HEADER).orElse(""))
                || response.headers().firstValueAsLong("Content-Length")
                .orElse(-1) > MAXIMUM_RESPONSE_BYTES) {
            closeQuietly(response.body());
            throw invalidResponse();
        }
        byte[] bytes;
        try (InputStream input = response.body()) {
            bytes = input.readNBytes(MAXIMUM_RESPONSE_BYTES + 1);
        } catch (IOException invalid) {
            throw invalidResponse();
        }
        if (bytes.length == 0 || bytes.length > MAXIMUM_RESPONSE_BYTES) {
            throw invalidResponse();
        }
        try {
            if (status == 200) {
                TestSuiteStabilityObservationExternalArchiveReceipt receipt =
                        objectMapper.readValue(bytes,
                                TestSuiteStabilityObservationExternalArchiveReceipt.class);
                if (!acceptedReceiptValid(endpoint, request, receipt, clock.instant())) {
                    throw invalidResponse();
                }
                return Observation.accepted(receipt);
            }
            TestSuiteStabilityObservationExternalArchiveConflictReceipt conflict =
                    objectMapper.readValue(bytes,
                            TestSuiteStabilityObservationExternalArchiveConflictReceipt.class);
            if (!conflictValid(endpoint, request, objectId, conflict, clock.instant())) {
                throw invalidResponse();
            }
            return Observation.authenticatedConflict();
        } catch (InvalidArchiveResponseException invalid) {
            throw invalid;
        } catch (IOException | RuntimeException invalid) {
            throw invalidResponse();
        }
    }

    private boolean acceptedReceiptValid(
            Endpoint endpoint,
            TestSuiteStabilityObservationExternalArchiveRequest request,
            TestSuiteStabilityObservationExternalArchiveReceipt receipt,
            Instant observedAt) {
        if (!receipt.fingerprintVerified(objectMapper)
                || !request.requestFingerprint().equals(receipt.requestFingerprint())
                || !trustDomain.equals(receipt.trustDomain())
                || !archiveSetId.equals(receipt.archiveSetId())
                || !endpoint.authorityId().equals(receipt.authorityId())
                || !endpoint.failureDomain().equals(receipt.failureDomain())
                || !TestSuiteStabilityObservationExternalArchiveIntegrity.objectId(
                objectMapper, request.retirement()).equals(receipt.objectId())
                || !request.retirement().evidence().retirementId()
                .equals(receipt.retirementId())
                || !request.retirement().retirementFingerprint()
                .equals(receipt.retirementFingerprint())
                || !request.retirement().evidence().archiveSegment().segmentId()
                .equals(receipt.segmentId())
                || !request.retirement().evidence().archiveSegment().segmentFingerprint()
                .equals(receipt.segmentFingerprint())
                || !request.retirement().evidence().retentionPolicyFingerprint()
                .equals(receipt.retentionPolicyFingerprint())
                || receipt.retainUntil().isBefore(request.retainUntil())
                || receipt.issuedAt().isBefore(request.requestedAt())
                || receipt.issuedAt().isAfter(observedAt)
                || !observedAt.isBefore(request.expiresAt())
                || !observedAt.isBefore(receipt.expiresAt())
                || receipt.expiresAt().isAfter(request.expiresAt())
                || Duration.between(receipt.issuedAt(), receipt.expiresAt()).compareTo(
                settings.maximumReceiptLifetime()) > 0) {
            return false;
        }
        return signatureValid(receipt.authorityId(), receipt.keyId(), receipt.algorithm(),
                receipt.issuedAt(), receipt.expiresAt(), receipt.receiptFingerprint(),
                receipt.signature(), observedAt, "External archive receipt");
    }

    private boolean conflictValid(
            Endpoint endpoint,
            TestSuiteStabilityObservationExternalArchiveRequest request,
            String objectId,
            TestSuiteStabilityObservationExternalArchiveConflictReceipt conflict,
            Instant observedAt) {
        String commitment = TestSuiteStabilityObservationExternalArchiveIntegrity
                .objectCommitment(objectMapper, request.retirement(), request.retainUntil());
        if (!conflict.fingerprintVerified(objectMapper)
                || !request.requestFingerprint().equals(conflict.requestFingerprint())
                || !trustDomain.equals(conflict.trustDomain())
                || !archiveSetId.equals(conflict.archiveSetId())
                || !endpoint.authorityId().equals(conflict.authorityId())
                || !endpoint.failureDomain().equals(conflict.failureDomain())
                || !objectId.equals(conflict.objectId())
                || !commitment.equals(conflict.expectedObjectCommitment())
                || conflict.issuedAt().isBefore(request.requestedAt())
                || conflict.issuedAt().isAfter(observedAt)
                || !observedAt.isBefore(request.expiresAt())
                || !observedAt.isBefore(conflict.expiresAt())
                || conflict.expiresAt().isAfter(request.expiresAt())
                || Duration.between(conflict.issuedAt(), conflict.expiresAt()).compareTo(
                settings.maximumReceiptLifetime()) > 0) {
            return false;
        }
        return signatureValid(conflict.authorityId(), conflict.keyId(), conflict.algorithm(),
                conflict.issuedAt(), conflict.expiresAt(), conflict.conflictFingerprint(),
                conflict.signature(), observedAt, "External archive conflict");
    }

    private boolean signatureValid(
            String authorityId,
            String keyId,
            String algorithm,
            Instant issuedAt,
            Instant expiresAt,
            String fingerprint,
            String signature,
            Instant observedAt,
            String label) {
        try {
            var signed = new TestSuiteStabilityServingInventory.AuthoritySignature(
                    authorityId, keyId, algorithm, issuedAt, signature);
            ConfiguredTestSuiteStabilityServingInventoryAuthority.verifyDetachedSignatures(
                    keys, 1, List.of(signed), fingerprint, issuedAt, expiresAt,
                    observedAt, label);
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private void validatePolicy() {
        if (!IDENTIFIER.matcher(trustDomain).matches()
                || !IDENTIFIER.matcher(archiveSetId).matches()
                || requiredCopies < 1 || requiredCopies > endpoints.size()
                || endpoints.size()
                > TestSuiteStabilityObservationExternalArchiveReceiptSet.MAXIMUM_RECEIPTS
                || minimumRetention.isZero() || minimumRetention.isNegative()
                || minimumRetention.compareTo(Duration.ofDays(36_500)) > 0) {
            throw new IllegalArgumentException(
                    "External observation-archive policy is invalid");
        }
        Set<String> endpointAuthorities = endpointsByAuthority.keySet();
        Set<String> keyAuthorities = new HashSet<>();
        keys.values().forEach(key -> keyAuthorities.add(key.authorityId()));
        if (!endpointAuthorities.equals(keyAuthorities)
                || activeAuthorityCount(clock.instant()) < requiredCopies) {
            throw new IllegalArgumentException(
                    "Every external archive authority requires an active configured key");
        }
    }

    private int activeAuthorityCount(Instant observedAt) {
        Set<String> active = new HashSet<>();
        keys.values().stream().filter(key -> key.activeAt(observedAt))
                .forEach(key -> active.add(key.authorityId()));
        return active.size();
    }

    private boolean activeAuthority(String authorityId, Instant observedAt) {
        return keys.values().stream().anyMatch(key -> authorityId.equals(key.authorityId())
                && key.activeAt(observedAt));
    }

    private ExternalArchiveException failed(ExternalArchiveException.Reason reason) {
        state.updateAndGet(current -> switch (reason) {
            case AUTHENTICATED_CONFLICT -> current.conflicted();
            case INVALID_RECEIPT -> current.failed("INVALID_RECEIPT");
            case UNAVAILABLE -> current.failed("COPY_THRESHOLD_UNAVAILABLE");
            case CLOSED -> current.failed("CLOSED");
        });
        return new ExternalArchiveException(reason);
    }

    private byte[] write(TestSuiteStabilityObservationExternalArchiveRequest request) {
        try {
            return objectMapper.writeValueAsBytes(request);
        } catch (IOException invalid) {
            throw new IllegalArgumentException(
                    "External archive request cannot be serialized", invalid);
        }
    }

    private byte[] writeInventoryRequest(
            TestSuiteStabilityObservationExternalArchiveInventoryRequest request) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(request);
            if (body.length > MAXIMUM_REQUEST_BYTES) {
                throw new IllegalArgumentException(
                        "External inventory request exceeds its byte limit");
            }
            return body;
        } catch (IOException invalid) {
            throw new IllegalArgumentException(
                    "External inventory request cannot be serialized", invalid);
        }
    }

    private static InventoryException inventoryFailed(InventoryException.Reason reason) {
        return new InventoryException(reason);
    }

    private String challenge() {
        byte[] value = new byte[32];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static List<Endpoint> parseEndpoints(ObjectMapper objectMapper, String value)
            throws IOException {
        JsonNode root = objectMapper.readTree(normalized(value));
        if (root == null || !root.isArray() || root.isEmpty()
                || root.size()
                > TestSuiteStabilityObservationExternalArchiveReceiptSet.MAXIMUM_RECEIPTS) {
            throw new IllegalArgumentException(
                    "External archive endpoints must be a non-empty bounded array");
        }
        List<Endpoint> endpoints = new ArrayList<>();
        for (JsonNode item : root) {
            if (!item.isObject()) {
                throw new IllegalArgumentException(
                        "External archive endpoint must be an object");
            }
            Set<String> fields = new HashSet<>();
            item.fieldNames().forEachRemaining(fields::add);
            if (!ENDPOINT_FIELDS.equals(fields)) {
                throw new IllegalArgumentException(
                        "External archive endpoint fields are invalid");
            }
            endpoints.add(new Endpoint(required(item, "authorityId"),
                    required(item, "failureDomain"),
                    URI.create(required(item, "uri"))));
        }
        return List.copyOf(endpoints);
    }

    private static String required(JsonNode item, String field) {
        JsonNode value = item.path(field);
        String result = value.isTextual() ? normalized(value.textValue()) : "";
        if (result.isBlank() || result.length() > 2048) {
            throw new IllegalArgumentException(
                    "External archive endpoint field is invalid");
        }
        return result;
    }

    private static List<Endpoint> validateEndpoints(
            List<Endpoint> values,
            boolean allowInsecureLoopback) {
        List<Endpoint> result = values == null ? List.of() : List.copyOf(values);
        if (result.isEmpty()
                || result.size()
                > TestSuiteStabilityObservationExternalArchiveReceiptSet.MAXIMUM_RECEIPTS) {
            throw new IllegalArgumentException(
                    "External archive endpoints must be non-empty and bounded");
        }
        Set<String> authorities = new HashSet<>();
        Set<String> domains = new HashSet<>();
        Set<URI> uris = new HashSet<>();
        for (Endpoint endpoint : result) {
            if (endpoint == null || !authorities.add(endpoint.authorityId())
                    || !domains.add(endpoint.failureDomain()) || !uris.add(endpoint.uri())) {
                throw new IllegalArgumentException(
                        "External archive authorities, domains, and endpoints must be unique");
            }
            validateUri(endpoint.uri(), allowInsecureLoopback);
        }
        return result;
    }

    private static Map<String, Endpoint> indexEndpoints(List<Endpoint> endpoints) {
        Map<String, Endpoint> result = new HashMap<>();
        endpoints.forEach(endpoint -> result.put(endpoint.authorityId(), endpoint));
        return Map.copyOf(result);
    }

    private static void validateUri(URI uri, boolean allowInsecureLoopback) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("External archive URI is invalid");
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "0:0:0:0:0:0:0:1".equals(host);
        if (!allowInsecureLoopback || !loopback
                || !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("External archive URI must use HTTPS");
        }
    }

    private static ObjectMapper strict(ObjectMapper source) {
        return source.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable result = failure;
        while (result.getCause() != null && result.getCause() != result) {
            result = result.getCause();
        }
        return result;
    }

    private static InvalidArchiveResponseException invalidResponse() {
        return new InvalidArchiveResponseException();
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // The bounded protocol failure remains authoritative.
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    /** One externally configured immutable-storage authority and failure domain. */
    record Endpoint(String authorityId, String failureDomain, URI uri) {
        Endpoint {
            authorityId = normalized(authorityId);
            failureDomain = normalized(failureDomain);
            if (!IDENTIFIER.matcher(authorityId).matches()
                    || !IDENTIFIER.matcher(failureDomain).matches() || uri == null) {
                throw new IllegalArgumentException("Invalid external archive endpoint");
            }
        }
    }

    /**
     * Bounded transport and signed-receipt freshness policy.
     *
     * @param requestTimeout per-authority HTTP timeout, 100 ms through 30 seconds
     * @param maximumReceiptLifetime request/receipt lifetime, one through 60 seconds
     * @param maximumInventorySnapshotAge accepted age of a pre-generated immutable inventory
     *                                    snapshot, one second through seven days
     * @param allowInsecureLoopback explicit local-test-only HTTP escape hatch
     */
    public record Settings(
            Duration requestTimeout,
            Duration maximumReceiptLifetime,
            Duration maximumInventorySnapshotAge,
            boolean allowInsecureLoopback) {
        /** Enforces bounded latency, challenge replay windows, and inventory staleness. */
        public Settings {
            if (requestTimeout == null
                    || requestTimeout.compareTo(Duration.ofMillis(100)) < 0
                    || requestTimeout.compareTo(Duration.ofSeconds(30)) > 0
                    || maximumReceiptLifetime == null
                    || maximumReceiptLifetime.compareTo(Duration.ofSeconds(1)) < 0
                    || maximumReceiptLifetime.compareTo(
                    TestSuiteStabilityObservationExternalArchiveRequest.MAXIMUM_LIFETIME) > 0
                    || maximumInventorySnapshotAge == null
                    || maximumInventorySnapshotAge.compareTo(Duration.ofSeconds(1)) < 0
                    || maximumInventorySnapshotAge.compareTo(Duration.ofDays(7)) > 0
                    || requestTimeout.compareTo(maximumReceiptLifetime) >= 0) {
                throw new IllegalArgumentException(
                        "External archive timing policy is invalid");
            }
        }
    }

    private record Observation(
            TestSuiteStabilityObservationExternalArchiveReceipt receipt,
            boolean conflict) {
        private static Observation accepted(
                TestSuiteStabilityObservationExternalArchiveReceipt receipt) {
            return new Observation(Objects.requireNonNull(receipt, "receipt"), false);
        }

        private static Observation authenticatedConflict() {
            return new Observation(null, true);
        }
    }

    private record RuntimeState(
            boolean available,
            String status,
            Instant lastSuccessfulArchiveAt,
            long successCount,
            long failureCount,
            long conflictCount) {
        private static RuntimeState initial() {
            return new RuntimeState(false, "UNVERIFIED", null, 0, 0, 0);
        }

        private RuntimeState succeeded(Instant at, boolean degraded) {
            return new RuntimeState(true,
                    degraded ? "DEGRADED_COPY_SET" : "HEALTHY", at,
                    successCount + 1, failureCount, conflictCount);
        }

        private RuntimeState failed(String nextStatus) {
            return new RuntimeState(false, nextStatus, lastSuccessfulArchiveAt,
                    successCount, failureCount + 1, conflictCount);
        }

        private RuntimeState conflicted() {
            return new RuntimeState(false, "AUTHENTICATED_CONFLICT",
                    lastSuccessfulArchiveAt, successCount, failureCount,
                    conflictCount + 1);
        }
    }

    private static final class InvalidArchiveResponseException
            extends IllegalArgumentException {
        private InvalidArchiveResponseException() {
            super("External archive response is invalid");
        }
    }
}
