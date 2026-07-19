package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationExternalArchiveInventoryIntegrity;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpTestSuiteStabilityObservationExternalArchiveInventoryAuthorityTest {

    private static final Instant NOW = Instant.parse("2026-07-20T01:00:00Z");
    private static final String TRUST_DOMAIN = "archive.example";
    private static final String ARCHIVE_SET = "archive-set-a";
    private static final String AUTHORITY = "archive-1";
    private static final String FAILURE_DOMAIN = "region-1";
    private static final String KEY_ID = "key-1";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.ACCEPT);
    private final AtomicReference<TestSuiteStabilityObservationExternalArchiveInventoryPage>
            replay = new AtomicReference<>();
    private final AtomicReference<Instant> snapshotTime = new AtomicReference<>(NOW);
    private HttpServer server;
    private ExecutorService executor;
    private KeyPair keyPair;
    private List<TestSuiteStabilityObservationExternalArchiveInventoryItem> inventory;

    @BeforeEach
    void setUp() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        inventory = List.of(item(1), item(2), item(3));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/archive", this::handle);
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
    void readsPinnedSnapshotAcrossStrictlyContinuousSignedPages() {
        HttpTestSuiteStabilityObservationExternalArchiveAuthority authority = authority(NOW);

        TestSuiteStabilityObservationExternalArchiveInventoryPage first =
                authority.inventoryPage(AUTHORITY,
                        TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Cursor
                                .initial(), 2);
        TestSuiteStabilityObservationExternalArchiveInventoryPage second =
                authority.inventoryPage(AUTHORITY,
                        TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Cursor
                                .after(first), 2);

        assertThat(authority.inventoryAuthorities()).containsExactly(AUTHORITY);
        assertThat(first.complete()).isFalse();
        assertThat(first.items()).extracting(
                TestSuiteStabilityObservationExternalArchiveInventoryItem::objectId)
                .containsExactly(inventory.get(0).objectId(), inventory.get(1).objectId());
        assertThat(second.complete()).isTrue();
        assertThat(second.items()).containsExactly(inventory.get(2));
        assertThat(second.snapshotId()).isEqualTo(first.snapshotId());
        assertThat(second.snapshotRoot()).isEqualTo(first.snapshotRoot())
                .isEqualTo(TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.root(
                        objectMapper, inventory));
        assertThat(second.snapshotObjectCount()).isEqualTo(3);
        assertThat(authority.verifyInventoryPage(first)).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Verification
                        .VERIFIED);
        assertThat(authority.verifyInventoryPage(second)).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Verification
                        .VERIFIED);
    }

    @Test
    void continuationRejectsReplayedPageFromAnOlderChallenge() {
        HttpTestSuiteStabilityObservationExternalArchiveAuthority authority = authority(NOW);
        TestSuiteStabilityObservationExternalArchiveInventoryPage first =
                authority.inventoryPage(AUTHORITY,
                        TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Cursor
                                .initial(), 2);
        replay.set(first);
        mode.set(Mode.REPLAY);

        assertInventoryFailure(() -> authority.inventoryPage(AUTHORITY,
                        TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Cursor
                                .after(first), 2),
                TestSuiteStabilityObservationExternalArchiveInventoryAuthority.InventoryException
                        .Reason.INVALID_PAGE);
    }

    @Test
    void continuationRejectsProviderSnapshotDrift() {
        HttpTestSuiteStabilityObservationExternalArchiveAuthority authority = authority(NOW);
        TestSuiteStabilityObservationExternalArchiveInventoryPage first =
                authority.inventoryPage(AUTHORITY,
                        TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Cursor
                                .initial(), 2);
        mode.set(Mode.DRIFT_SNAPSHOT);

        assertInventoryFailure(() -> authority.inventoryPage(AUTHORITY,
                        TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Cursor
                                .after(first), 2),
                TestSuiteStabilityObservationExternalArchiveInventoryAuthority.InventoryException
                        .Reason.INVALID_PAGE);
    }

    @Test
    void deterministicSnapshotIdRejectsRootSubstitution() {
        mode.set(Mode.WRONG_ROOT);

        assertInventoryFailure(() -> authority(NOW).inventoryPage(AUTHORITY,
                        TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Cursor
                                .initial(), 2),
                TestSuiteStabilityObservationExternalArchiveInventoryAuthority.InventoryException
                        .Reason.INVALID_PAGE);
    }

    @Test
    void invalidSignatureCannotCreateInventoryEvidence() {
        mode.set(Mode.INVALID_SIGNATURE);

        assertInventoryFailure(() -> authority(NOW).inventoryPage(AUTHORITY,
                        TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Cursor
                                .initial(), 2),
                TestSuiteStabilityObservationExternalArchiveInventoryAuthority.InventoryException
                        .Reason.INVALID_PAGE);
    }

    @Test
    void strictParserRejectsUnknownDuplicateAndOversizedResponses() {
        for (Mode invalid : List.of(Mode.UNKNOWN_FIELD, Mode.DUPLICATE_FIELD, Mode.OVERSIZED)) {
            mode.set(invalid);
            assertInventoryFailure(() -> authority(NOW).inventoryPage(AUTHORITY,
                            TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Cursor
                                    .initial(), 2),
                    TestSuiteStabilityObservationExternalArchiveInventoryAuthority
                            .InventoryException.Reason.INVALID_PAGE);
        }
    }

    @Test
    void snapshotExpiryHasAClosedRetryableFailureFamily() {
        mode.set(Mode.SNAPSHOT_EXPIRED);

        assertInventoryFailure(() -> authority(NOW).inventoryPage(AUTHORITY,
                        TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Cursor
                                .initial(), 2),
                TestSuiteStabilityObservationExternalArchiveInventoryAuthority.InventoryException
                        .Reason.SNAPSHOT_EXPIRED);
    }

    @Test
    void verifierRejectsPageAtItsExclusiveAdmissionDeadline() {
        HttpTestSuiteStabilityObservationExternalArchiveAuthority writer = authority(NOW);
        TestSuiteStabilityObservationExternalArchiveInventoryPage page =
                writer.inventoryPage(AUTHORITY,
                        TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Cursor
                                .initial(), 2);

        assertThat(authority(page.expiresAt()).verifyInventoryPage(page)).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Verification
                        .INVALID);
    }

    @Test
    void boundedPreGeneratedSnapshotIsAcceptedButStaleSnapshotFailsClosed() {
        snapshotTime.set(NOW.minus(Duration.ofMinutes(5)));
        HttpTestSuiteStabilityObservationExternalArchiveAuthority authority = authority(NOW);
        TestSuiteStabilityObservationExternalArchiveInventoryPage accepted =
                authority.inventoryPage(AUTHORITY,
                        TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Cursor
                                .initial(), 2);

        assertThat(accepted.snapshotAt()).isEqualTo(snapshotTime.get());
        assertThat(authority.verifyInventoryPage(accepted)).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Verification
                        .VERIFIED);

        snapshotTime.set(NOW.minus(Duration.ofMinutes(5)).minusSeconds(1));
        assertInventoryFailure(() -> authority.inventoryPage(AUTHORITY,
                        TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Cursor
                                .initial(), 2),
                TestSuiteStabilityObservationExternalArchiveInventoryAuthority.InventoryException
                        .Reason.INVALID_PAGE);
    }

    @Test
    void requestAndPageModelsRejectMixedOrNonAdvancingCursors() {
        assertThatThrownBy(() -> new
                TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Cursor(
                "", inventory.getFirst().objectId(), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inventory cursor");

        TestSuiteStabilityObservationExternalArchiveInventoryRequest request = request(0, "", "");
        assertThatThrownBy(() -> page(request, inventory.subList(0, 2), false,
                inventory.getFirst().objectId(), false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inventory page");
        assertThatThrownBy(() -> new
                HttpTestSuiteStabilityObservationExternalArchiveAuthority.Settings(
                Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ZERO, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timing policy");
        assertThatThrownBy(() -> new
                HttpTestSuiteStabilityObservationExternalArchiveAuthority.Settings(
                Duration.ofSeconds(1), Duration.ofSeconds(10),
                Duration.ofDays(7).plusSeconds(1), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timing policy");
    }

    @Test
    void orderedRootIsCanonicalAndInventoryBoundaryHasNoDestructiveOperation() {
        String root = TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.root(
                objectMapper, inventory);
        assertThat(root).isNotEqualTo(
                TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.root(
                        objectMapper, inventory.subList(0, 2)));
        assertThatThrownBy(() ->
                TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.root(
                        objectMapper, List.of(inventory.get(1), inventory.get(0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique and sorted");
        assertThatThrownBy(() ->
                TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.snapshotId(
                        objectMapper, "", ARCHIVE_SET, AUTHORITY, FAILURE_DOMAIN, NOW,
                        inventory.size(), root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snapshot identity");

        assertThat(List.of(
                TestSuiteStabilityObservationExternalArchiveInventoryAuthority.class
                        .getMethods()).stream()
                .map(method -> method.getName().toLowerCase())
                .noneMatch(name -> name.contains("delete") || name.contains("purge")
                        || name.contains("overwrite") || name.contains("shorten")))
                .isTrue();
    }

    private HttpTestSuiteStabilityObservationExternalArchiveAuthority authority(Instant now) {
        var key = new ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey(
                AUTHORITY, KEY_ID, keyPair.getPublic(), NOW.minusSeconds(60),
                NOW.plusSeconds(3600), true, false);
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/archive");
        var endpoint = new HttpTestSuiteStabilityObservationExternalArchiveAuthority.Endpoint(
                AUTHORITY, FAILURE_DOMAIN, uri);
        return new HttpTestSuiteStabilityObservationExternalArchiveAuthority(
                objectMapper, Clock.fixed(now, ZoneOffset.UTC), new SecureRandom(),
                TRUST_DOMAIN, ARCHIVE_SET, 1, Duration.ofDays(1), List.of(key),
                List.of(endpoint), new HttpTestSuiteStabilityObservationExternalArchiveAuthority
                .Settings(Duration.ofSeconds(1), Duration.ofSeconds(10),
                        Duration.ofMinutes(5), true),
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());
    }

    private TestSuiteStabilityObservationExternalArchiveInventoryRequest request(
            long pageSequence,
            String snapshotId,
            String afterObjectId) {
        return TestSuiteStabilityObservationExternalArchiveInventoryRequest.create(
                objectMapper, TRUST_DOMAIN, ARCHIVE_SET, AUTHORITY, snapshotId, afterObjectId,
                pageSequence, 2, Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[32]), NOW, NOW.plusSeconds(10));
    }

    private TestSuiteStabilityObservationExternalArchiveInventoryItem item(int index) {
        String suffix = "%064x".formatted(index);
        var material = new TestSuiteStabilityObservationExternalArchiveInventoryItem.Material(
                TestSuiteStabilityObservationExternalArchiveInventoryItem.SCHEMA_VERSION,
                "stability-observation-worm-" + suffix,
                "sha256:" + "%064x".formatted(100 + index),
                "stability-observation-retirement-" + suffix,
                "sha256:" + "%064x".formatted(200 + index),
                "stability-observation-archive-" + suffix,
                "sha256:" + "%064x".formatted(300 + index),
                "sha256:" + "%064x".formatted(400 + index),
                NOW.plus(Duration.ofDays(30)), NOW.minusSeconds(600L - index));
        return new TestSuiteStabilityObservationExternalArchiveInventoryItem(
                material.schemaVersion(), ProtocolFingerprint.of(objectMapper, material),
                material.objectId(), material.objectCommitment(), material.retirementId(),
                material.retirementFingerprint(), material.segmentId(),
                material.segmentFingerprint(), material.retentionPolicyFingerprint(),
                material.retainUntil(), material.storedAt());
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (mode.get() == Mode.SNAPSHOT_EXPIRED) {
                exchange.sendResponseHeaders(410, -1);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())
                    || !HttpTestSuiteStabilityObservationExternalArchiveAuthority
                    .INVENTORY_MEDIA_TYPE.equals(
                            exchange.getRequestHeaders().getFirst("Content-Type"))
                    || !TestSuiteStabilityObservationExternalArchiveInventoryRequest
                    .SCHEMA_VERSION.equals(exchange.getRequestHeaders().getFirst(
                            HttpTestSuiteStabilityObservationExternalArchiveAuthority
                                    .PROTOCOL_HEADER))) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            TestSuiteStabilityObservationExternalArchiveInventoryRequest request =
                    objectMapper.readValue(exchange.getRequestBody(),
                            TestSuiteStabilityObservationExternalArchiveInventoryRequest.class);
            TestSuiteStabilityObservationExternalArchiveInventoryPage response;
            if (mode.get() == Mode.REPLAY) {
                response = replay.get();
            } else {
                int start = request.pageSequence() == 0 ? 0 : 2;
                List<TestSuiteStabilityObservationExternalArchiveInventoryItem> pageItems =
                        inventory.subList(start, Math.min(inventory.size(),
                                start + request.maximumItems()));
                boolean complete = start + pageItems.size() == inventory.size();
                response = page(request, pageItems, complete,
                        complete ? "" : pageItems.getLast().objectId(),
                        mode.get() == Mode.WRONG_ROOT,
                        mode.get() == Mode.INVALID_SIGNATURE
                                || mode.get() == Mode.DRIFT_SNAPSHOT);
                if (mode.get() == Mode.DRIFT_SNAPSHOT) {
                    response = driftingPage(request, pageItems);
                }
            }
            byte[] body;
            if (mode.get() == Mode.UNKNOWN_FIELD) {
                ObjectNode value = objectMapper.valueToTree(response);
                value.put("credential", "forbidden");
                body = objectMapper.writeValueAsBytes(value);
            } else if (mode.get() == Mode.DUPLICATE_FIELD) {
                String json = objectMapper.writeValueAsString(response);
                body = json.replaceFirst("\\{", "{\"schemaVersion\":\""
                        + response.schemaVersion() + "\",")
                        .getBytes(StandardCharsets.UTF_8);
            } else if (mode.get() == Mode.OVERSIZED) {
                body = new byte[2 * 1024 * 1024 + 1];
            } else {
                body = objectMapper.writeValueAsBytes(response);
            }
            exchange.getResponseHeaders().set("Content-Type",
                    HttpTestSuiteStabilityObservationExternalArchiveAuthority
                            .INVENTORY_MEDIA_TYPE);
            exchange.getResponseHeaders().set(
                    HttpTestSuiteStabilityObservationExternalArchiveAuthority.PROTOCOL_HEADER,
                    TestSuiteStabilityObservationExternalArchiveInventoryPage.SCHEMA_VERSION);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } catch (Exception invalid) {
            if (exchange.getResponseCode() < 0) {
                exchange.sendResponseHeaders(500, -1);
            }
        }
    }

    private TestSuiteStabilityObservationExternalArchiveInventoryPage page(
            TestSuiteStabilityObservationExternalArchiveInventoryRequest request,
            List<TestSuiteStabilityObservationExternalArchiveInventoryItem> pageItems,
            boolean complete,
            String nextAfterObjectId,
            boolean wrongRoot,
            boolean invalidSignature) throws Exception {
        String root = TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.root(
                objectMapper, inventory);
        String snapshotId = TestSuiteStabilityObservationExternalArchiveInventoryIntegrity
                .snapshotId(objectMapper, TRUST_DOMAIN, ARCHIVE_SET, AUTHORITY,
                        FAILURE_DOMAIN, snapshotTime.get(), inventory.size(), root);
        String responseRoot = wrongRoot ? "sha256:" + "f".repeat(64) : root;
        var material = new TestSuiteStabilityObservationExternalArchiveInventoryPage.Material(
                TestSuiteStabilityObservationExternalArchiveInventoryPage.SCHEMA_VERSION,
                request.requestFingerprint(), AUTHORITY, FAILURE_DOMAIN, KEY_ID, snapshotId,
                snapshotTime.get(), inventory.size(), responseRoot, pageItems,
                nextAfterObjectId, complete,
                NOW, request.expiresAt(), "Ed25519");
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return new TestSuiteStabilityObservationExternalArchiveInventoryPage(
                material.schemaVersion(), fingerprint, request, material.authorityId(),
                material.failureDomain(), material.keyId(), material.snapshotId(),
                material.snapshotAt(), material.snapshotObjectCount(), material.snapshotRoot(),
                material.items(), material.nextAfterObjectId(), material.complete(),
                material.issuedAt(), material.expiresAt(), material.algorithm(),
                sign(fingerprint, invalidSignature));
    }

    private TestSuiteStabilityObservationExternalArchiveInventoryPage driftingPage(
            TestSuiteStabilityObservationExternalArchiveInventoryRequest request,
            List<TestSuiteStabilityObservationExternalArchiveInventoryItem> pageItems)
            throws Exception {
        List<TestSuiteStabilityObservationExternalArchiveInventoryItem> drifted =
                new ArrayList<>(inventory);
        drifted.add(item(4));
        String root = TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.root(
                objectMapper, drifted);
        String snapshotId = TestSuiteStabilityObservationExternalArchiveInventoryIntegrity
                .snapshotId(objectMapper, TRUST_DOMAIN, ARCHIVE_SET, AUTHORITY,
                        FAILURE_DOMAIN, snapshotTime.get(), drifted.size(), root);
        var material = new TestSuiteStabilityObservationExternalArchiveInventoryPage.Material(
                TestSuiteStabilityObservationExternalArchiveInventoryPage.SCHEMA_VERSION,
                request.requestFingerprint(), AUTHORITY, FAILURE_DOMAIN, KEY_ID, snapshotId,
                snapshotTime.get(), drifted.size(), root, pageItems, "", true, NOW,
                request.expiresAt(), "Ed25519");
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return new TestSuiteStabilityObservationExternalArchiveInventoryPage(
                material.schemaVersion(), fingerprint, request, material.authorityId(),
                material.failureDomain(), material.keyId(), material.snapshotId(),
                material.snapshotAt(), material.snapshotObjectCount(), material.snapshotRoot(),
                material.items(), material.nextAfterObjectId(), material.complete(),
                material.issuedAt(), material.expiresAt(), material.algorithm(),
                sign(fingerprint, false));
    }

    private String sign(String fingerprint, boolean invalid) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
        byte[] signature = signer.sign();
        if (invalid) {
            signature[0] ^= 1;
        }
        return Base64.getEncoder().encodeToString(signature);
    }

    private static void assertInventoryFailure(
            Runnable action,
            TestSuiteStabilityObservationExternalArchiveInventoryAuthority.InventoryException
                    .Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOf(TestSuiteStabilityObservationExternalArchiveInventoryAuthority
                        .InventoryException.class)
                .extracting(error -> ((TestSuiteStabilityObservationExternalArchiveInventoryAuthority
                        .InventoryException) error).reason())
                .isEqualTo(reason);
    }

    private enum Mode {
        ACCEPT,
        REPLAY,
        DRIFT_SNAPSHOT,
        WRONG_ROOT,
        INVALID_SIGNATURE,
        UNKNOWN_FIELD,
        DUPLICATE_FIELD,
        OVERSIZED,
        SNAPSHOT_EXPIRED
    }
}
