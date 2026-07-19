package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityObservationLifecycleProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationExternalArchiveIntegrity;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpTestSuiteStabilityObservationExternalArchiveAuthorityTest {

    private static final Instant NOW = Instant.parse("2026-07-19T00:11:00Z");
    private static final String TRUST_DOMAIN = "archive.example";
    private static final String ARCHIVE_SET = "archive-set-a";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Map<String, KeyPair> keyPairs = new HashMap<>();
    private final Map<String, Mode> modes = new ConcurrentHashMap<>();
    private final Map<String, TestSuiteStabilityObservationExternalArchiveReceipt> cached =
            new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyKeys = new ConcurrentHashMap<>();
    private List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> keys;
    private List<HttpTestSuiteStabilityObservationExternalArchiveAuthority.Endpoint> endpoints;
    private HttpServer server;
    private ExecutorService executor;
    private TestSuiteStabilityObservationFloorRetirement retirement;
    private volatile CountDownLatch concurrentArrivals;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        retirement = TestSuiteStabilityObservationLifecycleProtocolFixtures.page(
                objectMapper, new InMemoryVisualEvidenceSigner())
                .page().retirements().getFirst();
        keys = new ArrayList<>();
        endpoints = new ArrayList<>();
        for (int index = 1; index <= 3; index++) {
            int authorityIndex = index;
            String authority = "archive-" + index;
            String domain = "region-" + index;
            String keyId = "key-" + index;
            KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            keyPairs.put(authority, pair);
            modes.put(authority, Mode.ACCEPT);
            keys.add(new ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey(
                    authority, keyId, pair.getPublic(), NOW.minusSeconds(60),
                    NOW.plusSeconds(3600), true, false));
            String path = "/archives/" + authority;
            server.createContext(path, exchange -> handle(
                    exchange, authority, domain, "key-" + authorityIndex));
            endpoints.add(new HttpTestSuiteStabilityObservationExternalArchiveAuthority.Endpoint(
                    authority, domain, URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + path)));
        }
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void twoOfThreePersistsOnlyStrictlyVerifiedIndependentReceipts() {
        modes.put("archive-3", Mode.UNAVAILABLE);
        var authority = authority(Clock.fixed(NOW, ZoneOffset.UTC), 2, settings());

        TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet =
                authority.archive(retirement(), retainUntil());

        assertThat(receiptSet.requiredCopies()).isEqualTo(2);
        assertThat(receiptSet.receipts()).extracting(
                TestSuiteStabilityObservationExternalArchiveReceipt::authorityId)
                .containsExactly("archive-1", "archive-2");
        assertThat(receiptSet.receipts()).extracting(
                TestSuiteStabilityObservationExternalArchiveReceipt::failureDomain)
                .containsExactly("region-1", "region-2");
        assertThat(authority.verify(receiptSet))
                .isEqualTo(TestSuiteStabilityObservationExternalArchiveAuthority.Verification
                        .VERIFIED);
        String expectedObjectId = TestSuiteStabilityObservationExternalArchiveIntegrity.objectId(
                objectMapper, retirement());
        assertThat(receiptSet.request().retirement()).isEqualTo(retirement());
        assertThat(idempotencyKeys.values()).allMatch(expectedObjectId::equals);
        assertThat(authority.snapshot().status()).isEqualTo("DEGRADED_COPY_SET");
        assertThat(authority.snapshot().successCount()).isEqualTo(1);
    }

    @Test
    void independentRetirementsAreNotSerializedBehindOneProcessWideMonitor()
            throws Exception {
        modes.replaceAll((ignored, previous) -> Mode.UNAVAILABLE);
        modes.put("archive-1", Mode.CONCURRENT_GATE);
        concurrentArrivals = new CountDownLatch(2);
        var authority = authority(Clock.fixed(NOW, ZoneOffset.UTC), 1,
                new HttpTestSuiteStabilityObservationExternalArchiveAuthority.Settings(
                        Duration.ofSeconds(2), Duration.ofSeconds(5), true));

        CompletableFuture<TestSuiteStabilityObservationExternalArchiveReceiptSet> first =
                CompletableFuture.supplyAsync(
                        () -> authority.archive(retirement(), retainUntil()));
        CompletableFuture<TestSuiteStabilityObservationExternalArchiveReceiptSet> second =
                CompletableFuture.supplyAsync(
                        () -> authority.archive(retirement(), retainUntil()));

        assertThat(concurrentArrivals.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(first.join()).isNotNull();
        assertThat(second.join()).isNotNull();
        assertThat(authority.snapshot().successCount()).isEqualTo(2);
    }

    @Test
    void oneAuthenticatedImmutableConflictIsFatalDespiteAcceptedCopyThreshold() {
        modes.put("archive-3", Mode.CONFLICT);
        var authority = authority(Clock.fixed(NOW, ZoneOffset.UTC), 2, settings());

        assertThatThrownBy(() -> authority.archive(retirement(), retainUntil()))
                .isInstanceOf(TestSuiteStabilityObservationExternalArchiveAuthority
                        .ExternalArchiveException.class)
                .extracting(error -> ((TestSuiteStabilityObservationExternalArchiveAuthority
                        .ExternalArchiveException) error).reason())
                .isEqualTo(TestSuiteStabilityObservationExternalArchiveAuthority
                        .ExternalArchiveException.Reason.AUTHENTICATED_CONFLICT);
        assertThat(authority.snapshot().status()).isEqualTo("AUTHENTICATED_CONFLICT");
        assertThat(authority.snapshot().conflictCount()).isEqualTo(1);
    }

    @Test
    void unsignedConflictCannotOverrideAcceptedCopiesOrBecomeSafetyTruth() {
        modes.put("archive-3", Mode.INVALID_CONFLICT_SIGNATURE);
        var authority = authority(Clock.fixed(NOW, ZoneOffset.UTC), 2, settings());

        TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet =
                authority.archive(retirement(), retainUntil());

        assertThat(receiptSet.receipts()).hasSize(2);
        assertThat(authority.snapshot().conflictCount()).isZero();
    }

    @Test
    void invalidAcceptedSignatureCannotContributeToRequiredCopies() {
        modes.put("archive-2", Mode.INVALID_SIGNATURE);
        modes.put("archive-3", Mode.UNAVAILABLE);
        var authority = authority(Clock.fixed(NOW, ZoneOffset.UTC), 2, settings());

        assertFailure(authority,
                TestSuiteStabilityObservationExternalArchiveAuthority
                        .ExternalArchiveException.Reason.INVALID_RECEIPT);
        assertThat(authority.snapshot().status()).isEqualTo("INVALID_RECEIPT");
        assertThat(authority.snapshot().failureCount()).isEqualTo(1);
    }

    @Test
    void replayedReceiptsFromAnOldChallengeNeverAuthorizeANewWrite() {
        var authority = authority(Clock.fixed(NOW, ZoneOffset.UTC), 2, settings());
        authority.archive(retirement(), retainUntil());
        modes.replaceAll((ignored, previous) -> Mode.REPLAY);

        assertFailure(authority,
                TestSuiteStabilityObservationExternalArchiveAuthority
                        .ExternalArchiveException.Reason.INVALID_RECEIPT);
        assertThat(authority.snapshot().successCount()).isEqualTo(1);
        assertThat(authority.snapshot().failureCount()).isEqualTo(1);
    }

    @Test
    void providerCannotShortenRequestedComplianceRetention() {
        modes.put("archive-2", Mode.SHORT_RETENTION);
        modes.put("archive-3", Mode.UNAVAILABLE);
        var authority = authority(Clock.fixed(NOW, ZoneOffset.UTC), 2, settings());

        assertFailure(authority,
                TestSuiteStabilityObservationExternalArchiveAuthority
                        .ExternalArchiveException.Reason.INVALID_RECEIPT);
    }

    @Test
    void strictJsonAndBoundedTransportRejectUnknownFieldsRedirectsAndTimeouts() {
        for (Mode invalid : List.of(Mode.UNKNOWN_FIELD, Mode.REDIRECT, Mode.SLOW)) {
            modes.replaceAll((ignored, previous) -> Mode.UNAVAILABLE);
            modes.put("archive-1", invalid);
            var authority = authority(Clock.fixed(NOW, ZoneOffset.UTC), 1,
                    invalid == Mode.SLOW
                            ? new HttpTestSuiteStabilityObservationExternalArchiveAuthority
                            .Settings(Duration.ofMillis(100), Duration.ofSeconds(2), true)
                            : settings());

            assertThatThrownBy(() -> authority.archive(retirement(), retainUntil()))
                    .isInstanceOf(TestSuiteStabilityObservationExternalArchiveAuthority
                            .ExternalArchiveException.class);
        }
    }

    @Test
    void immediateVerifierFailsClosedAfterExclusiveAdmissionExpiry() {
        var writer = authority(Clock.fixed(NOW, ZoneOffset.UTC), 2, settings());
        TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet =
                writer.archive(retirement(), retainUntil());
        var lateVerifier = authority(
                Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC), 2, settings());

        assertThat(lateVerifier.verify(receiptSet))
                .isEqualTo(TestSuiteStabilityObservationExternalArchiveAuthority.Verification
                        .INVALID);
    }

    @Test
    void configurationRejectsSharedFailureDomainsMissingKeysAndInsecureRemoteHttp() {
        List<HttpTestSuiteStabilityObservationExternalArchiveAuthority.Endpoint> duplicateDomain =
                new ArrayList<>(endpoints);
        var second = duplicateDomain.get(1);
        duplicateDomain.set(1,
                new HttpTestSuiteStabilityObservationExternalArchiveAuthority.Endpoint(
                        second.authorityId(), endpoints.getFirst().failureDomain(),
                        second.uri()));
        assertThatThrownBy(() -> newAuthority(
                Clock.fixed(NOW, ZoneOffset.UTC), 2, keys, duplicateDomain, settings()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be unique");

        assertThatThrownBy(() -> newAuthority(
                Clock.fixed(NOW, ZoneOffset.UTC), 2, keys.subList(0, 2),
                endpoints, settings()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Every external archive authority");

        var insecure = new HttpTestSuiteStabilityObservationExternalArchiveAuthority.Endpoint(
                "remote", "region-remote", URI.create("http://example.com/archive"));
        assertThatThrownBy(() -> newAuthority(
                Clock.fixed(NOW, ZoneOffset.UTC), 1, List.of(keys.getFirst()),
                List.of(insecure), new HttpTestSuiteStabilityObservationExternalArchiveAuthority
                        .Settings(Duration.ofSeconds(1), Duration.ofSeconds(2), false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must use HTTPS");
    }

    @Test
    void healthTransitionsWithoutLeakingRemoteOrCryptographicIdentity() {
        var authority = authority(Clock.fixed(NOW, ZoneOffset.UTC), 2, settings());
        var health = new TestSuiteStabilityObservationExternalArchiveHealth(authority);

        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        authority.archive(retirement(), retainUntil());

        var result = health.health();
        assertThat(result.getStatus()).isEqualTo(Status.UP);
        assertThat(result.getDetails()).containsEntry("status", "HEALTHY")
                .containsEntry("requiredCopies", 2);
        assertThat(result.getDetails()).doesNotContainKeys(
                "uri", "authorityId", "failureDomain", "keyId", "objectId",
                "requestFingerprint", "challenge", "signature");
    }

    @Test
    void conflictReceiptRejectsSelfEqualCommitmentsBeforeSignatureTrust() {
        String fingerprint = "sha256:" + "a".repeat(64);
        assertThatThrownBy(() -> new
                TestSuiteStabilityObservationExternalArchiveConflictReceipt(
                TestSuiteStabilityObservationExternalArchiveConflictReceipt.SCHEMA_VERSION,
                fingerprint, fingerprint, TRUST_DOMAIN, ARCHIVE_SET, "archive-1",
                "region-1", "key-1", "stability-observation-worm-" + "b".repeat(64),
                fingerprint, fingerprint, NOW, NOW.plusSeconds(10), "Ed25519",
                Base64.getEncoder().encodeToString(new byte[64])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflict receipt");
    }

    private HttpTestSuiteStabilityObservationExternalArchiveAuthority authority(
            Clock clock,
            int requiredCopies,
            HttpTestSuiteStabilityObservationExternalArchiveAuthority.Settings settings) {
        return newAuthority(clock, requiredCopies, keys, endpoints, settings);
    }

    private HttpTestSuiteStabilityObservationExternalArchiveAuthority newAuthority(
            Clock clock,
            int requiredCopies,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    authorityKeys,
            List<HttpTestSuiteStabilityObservationExternalArchiveAuthority.Endpoint>
                    authorityEndpoints,
            HttpTestSuiteStabilityObservationExternalArchiveAuthority.Settings settings) {
        return new HttpTestSuiteStabilityObservationExternalArchiveAuthority(
                objectMapper, clock, new SecureRandom(), TRUST_DOMAIN, ARCHIVE_SET,
                requiredCopies, Duration.ofDays(1), authorityKeys, authorityEndpoints,
                settings, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    private HttpTestSuiteStabilityObservationExternalArchiveAuthority.Settings settings() {
        return new HttpTestSuiteStabilityObservationExternalArchiveAuthority.Settings(
                Duration.ofSeconds(1), Duration.ofSeconds(10), true);
    }

    private TestSuiteStabilityObservationFloorRetirement retirement() {
        return retirement;
    }

    private Instant retainUntil() {
        return NOW.plus(Duration.ofDays(30));
    }

    private void assertFailure(
            HttpTestSuiteStabilityObservationExternalArchiveAuthority authority,
            TestSuiteStabilityObservationExternalArchiveAuthority.ExternalArchiveException.Reason
                    reason) {
        assertThatThrownBy(() -> authority.archive(retirement(), retainUntil()))
                .isInstanceOf(TestSuiteStabilityObservationExternalArchiveAuthority
                        .ExternalArchiveException.class)
                .extracting(error -> ((TestSuiteStabilityObservationExternalArchiveAuthority
                        .ExternalArchiveException) error).reason())
                .isEqualTo(reason);
    }

    private void handle(
            HttpExchange exchange,
            String authority,
            String failureDomain,
            String keyId) throws IOException {
        try (exchange) {
            Mode mode = modes.get(authority);
            if (mode == Mode.UNAVAILABLE) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            if (mode == Mode.REDIRECT) {
                exchange.getResponseHeaders().set("Location", "/redirected");
                exchange.sendResponseHeaders(302, -1);
                return;
            }
            if (mode == Mode.SLOW) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
            }
            if (mode == Mode.CONCURRENT_GATE) {
                concurrentArrivals.countDown();
                try {
                    if (!concurrentArrivals.await(1, TimeUnit.SECONDS)) {
                        exchange.sendResponseHeaders(503, -1);
                        return;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
            }
            if (!"POST".equals(exchange.getRequestMethod())
                    || !HttpTestSuiteStabilityObservationExternalArchiveAuthority.MEDIA_TYPE
                    .equals(exchange.getRequestHeaders().getFirst("Content-Type"))
                    || !TestSuiteStabilityObservationExternalArchiveRequest.SCHEMA_VERSION.equals(
                    exchange.getRequestHeaders().getFirst(
                            HttpTestSuiteStabilityObservationExternalArchiveAuthority
                                    .PROTOCOL_HEADER))) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            TestSuiteStabilityObservationExternalArchiveRequest request =
                    objectMapper.readValue(exchange.getRequestBody(),
                            TestSuiteStabilityObservationExternalArchiveRequest.class);
            idempotencyKeys.put(authority,
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            if (mode == Mode.CONFLICT || mode == Mode.INVALID_CONFLICT_SIGNATURE) {
                byte[] body = objectMapper.writeValueAsBytes(conflict(
                        request, authority, failureDomain, keyId,
                        mode == Mode.INVALID_CONFLICT_SIGNATURE));
                respond(exchange, 409,
                        TestSuiteStabilityObservationExternalArchiveConflictReceipt
                                .SCHEMA_VERSION,
                        body);
                return;
            }
            TestSuiteStabilityObservationExternalArchiveReceipt receipt;
            if (mode == Mode.REPLAY) {
                receipt = cached.get(authority);
            } else {
                receipt = receipt(request, authority, failureDomain, keyId,
                        mode == Mode.INVALID_SIGNATURE, mode == Mode.SHORT_RETENTION);
                cached.put(authority, receipt);
            }
            byte[] body;
            if (mode == Mode.UNKNOWN_FIELD) {
                ObjectNode node = objectMapper.valueToTree(receipt);
                node.put("credential", "forbidden");
                body = objectMapper.writeValueAsBytes(node);
            } else {
                body = objectMapper.writeValueAsBytes(receipt);
            }
            respond(exchange, 200,
                    TestSuiteStabilityObservationExternalArchiveReceipt.SCHEMA_VERSION, body);
        } catch (Exception invalid) {
            if (exchange.getResponseCode() < 0) {
                exchange.sendResponseHeaders(500, -1);
            }
        }
    }

    private void respond(
            HttpExchange exchange,
            int status,
            String protocol,
            byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type",
                HttpTestSuiteStabilityObservationExternalArchiveAuthority.MEDIA_TYPE);
        exchange.getResponseHeaders().set(
                HttpTestSuiteStabilityObservationExternalArchiveAuthority.PROTOCOL_HEADER,
                protocol);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private TestSuiteStabilityObservationExternalArchiveReceipt receipt(
            TestSuiteStabilityObservationExternalArchiveRequest request,
            String authority,
            String failureDomain,
            String keyId,
            boolean invalidSignature,
            boolean shortRetention) throws Exception {
        Instant retainUntil = shortRetention
                ? request.retainUntil().minusSeconds(1) : request.retainUntil();
        String objectId = TestSuiteStabilityObservationExternalArchiveIntegrity.objectId(
                objectMapper, request.retirement());
        var material = new TestSuiteStabilityObservationExternalArchiveReceipt.Material(
                TestSuiteStabilityObservationExternalArchiveReceipt.SCHEMA_VERSION,
                request.requestFingerprint(), TRUST_DOMAIN, ARCHIVE_SET, authority,
                failureDomain, keyId, objectId,
                request.retirement().evidence().retirementId(),
                request.retirement().retirementFingerprint(),
                request.retirement().evidence().archiveSegment().segmentId(),
                request.retirement().evidence().archiveSegment().segmentFingerprint(),
                request.retirement().evidence().retentionPolicyFingerprint(), retainUntil,
                request.requestedAt(), request.requestedAt(), request.expiresAt(),
                TestSuiteStabilityObservationExternalArchiveReceipt.RetentionMode.COMPLIANCE,
                true, true, true, "Ed25519");
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return new TestSuiteStabilityObservationExternalArchiveReceipt(
                material.schemaVersion(), fingerprint, material.requestFingerprint(),
                material.trustDomain(), material.archiveSetId(), material.authorityId(),
                material.failureDomain(), material.keyId(), material.objectId(),
                material.retirementId(), material.retirementFingerprint(),
                material.segmentId(), material.segmentFingerprint(),
                material.retentionPolicyFingerprint(), material.retainUntil(),
                material.storedAt(), material.issuedAt(), material.expiresAt(),
                material.retentionMode(), material.externallyDurable(), material.writeOnce(),
                material.deleteBeforeRetentionDenied(), material.algorithm(),
                sign(authority, fingerprint, invalidSignature));
    }

    private TestSuiteStabilityObservationExternalArchiveConflictReceipt conflict(
            TestSuiteStabilityObservationExternalArchiveRequest request,
            String authority,
            String failureDomain,
            String keyId,
            boolean invalidSignature) throws Exception {
        String objectId = TestSuiteStabilityObservationExternalArchiveIntegrity.objectId(
                objectMapper, request.retirement());
        String expected = TestSuiteStabilityObservationExternalArchiveIntegrity
                .objectCommitment(objectMapper, request.retirement(), request.retainUntil());
        String observed = "sha256:" + "f".repeat(64);
        if (observed.equals(expected)) {
            observed = "sha256:" + "e".repeat(64);
        }
        var material = new
                TestSuiteStabilityObservationExternalArchiveConflictReceipt.Material(
                TestSuiteStabilityObservationExternalArchiveConflictReceipt.SCHEMA_VERSION,
                request.requestFingerprint(), TRUST_DOMAIN, ARCHIVE_SET, authority,
                failureDomain, keyId, objectId, expected, observed,
                request.requestedAt(), request.expiresAt(), "Ed25519");
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return new TestSuiteStabilityObservationExternalArchiveConflictReceipt(
                material.schemaVersion(), fingerprint, material.requestFingerprint(),
                material.trustDomain(), material.archiveSetId(), material.authorityId(),
                material.failureDomain(), material.keyId(), material.objectId(),
                material.expectedObjectCommitment(), material.observedObjectCommitment(),
                material.issuedAt(), material.expiresAt(), material.algorithm(),
                sign(authority, fingerprint, invalidSignature));
    }

    private String sign(
            String authority,
            String fingerprint,
            boolean invalidSignature) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPairs.get(authority).getPrivate());
        signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
        byte[] signature = signer.sign();
        if (invalidSignature) {
            signature[0] ^= 1;
        }
        return Base64.getEncoder().encodeToString(signature);
    }

    private enum Mode {
        ACCEPT,
        UNAVAILABLE,
        CONFLICT,
        INVALID_CONFLICT_SIGNATURE,
        INVALID_SIGNATURE,
        REPLAY,
        SHORT_RETENTION,
        UNKNOWN_FIELD,
        REDIRECT,
        SLOW,
        CONCURRENT_GATE
    }
}
