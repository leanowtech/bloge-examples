package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRuntimeBootstrapRootConfigurationTest {

    private static final String SECRET_PREFIX =
            "gateway.testing.test-secrets.authority.http.jwks.cohort.signed-inventory.remote.external-anchor";
    private static final String SUITE_PREFIX =
            "gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.external-anchor";
    private static final String SCOPE = "secret-fleet";
    private static final String ROOT_SET = "secret-notary-roots";
    private static final String ROOT_DOMAIN = "secret-notary-root.example";
    private static final String NOTARY_DOMAIN = "secret-notary.example";
    private static final String POLICY = "sha256:" + "a".repeat(64);
    private static final String GENESIS_POLICY = "sha256:" + "b".repeat(64);

    private ObjectMapper objectMapper;
    private Instant now;
    private Map<String, KeyPair> genesisKeys;
    private Map<String, KeyPair> successorKeys;
    private ExternalSequenceAnchorBootstrapRootGenesis genesis;
    private ExternalSequenceAnchorBootstrapRootBundle bundle;
    private HttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        genesisKeys = keys("genesis");
        successorKeys = keys("successor");
        genesis = genesis(genesisKeys, 3, 1);
        ExternalSequenceAnchorBootstrapRootTransition transition = transition();
        bundle = new ExternalSequenceAnchorBootstrapRootBundle(
                ExternalSequenceAnchorBootstrapRootBundle.SCHEMA_VERSION,
                genesis.materialFingerprint(objectMapper), List.of(transition),
                transition.materialFingerprint());
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void springCompositionBootstrapsCompleteManagedChainAndPersistsItsFloor()
            throws Exception {
        startServer(bundle);
        MockEnvironment environment = environment(genesis);
        try (TestRuntimeDatabase database = database("complete")) {
            ExternalSequenceAnchorBootstrapRootTrustStore store =
                    TestRuntimeConfiguration.buildBootstrapRootTrustStore(
                            objectMapper, environment, database, SECRET_PREFIX,
                            "test-secret", false, SCOPE, ROOT_SET, ROOT_DOMAIN,
                            NOTARY_DOMAIN);
            try {
                assertThat(store.descriptor()).satisfies(descriptor -> {
                    assertThat(descriptor.available()).isTrue();
                    assertThat(descriptor.managedChain()).isTrue();
                    assertThat(descriptor.restartFreeRotation()).isTrue();
                    assertThat(descriptor.completeGenesisReplay()).isTrue();
                    assertThat(descriptor.durableFloor()).isTrue();
                    assertThat(descriptor.authorityCount()).isEqualTo(4);
                    assertThat(descriptor.signatureThreshold()).isEqualTo(3);
                });
                assertThat(store.snapshot()).satisfies(snapshot -> {
                    assertThat(snapshot.status()).isEqualTo("HEALTHY");
                    assertThat(snapshot.headSequence()).isOne();
                    assertThat(snapshot.transitionCount()).isOne();
                    assertThat(snapshot.refreshSuccessCount()).isOne();
                });
                assertThat(database.jdbc().queryForObject("""
                        SELECT COUNT(*)
                        FROM rg_external_sequence_anchor_bootstrap_root_floors
                        WHERE scope_id = ? AND root_set_id = ?
                        """, Long.class, SCOPE, ROOT_SET)).isOne();
            } finally {
                store.close();
            }
        }
    }

    @Test
    void stagingRejectsNonByzantineGenesisBeforeFetchingItsBundle() throws Exception {
        KeyPair onlyKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Map<String, KeyPair> singleRoot = Map.of("root-1", onlyKey);
        ExternalSequenceAnchorBootstrapRootGenesis weakGenesis =
                genesis(singleRoot, 1, 0);
        MockEnvironment environment = environment(weakGenesis)
                .withProperty(SECRET_PREFIX
                        + ".managed-trust.bootstrap-roots.bundle-uri",
                        "https://root-chain.example/current")
                .withProperty(SECRET_PREFIX
                        + ".managed-trust.bootstrap-roots.allow-insecure-loopback", "false");
        try (TestRuntimeDatabase database = database("weak")) {
            assertThatThrownBy(() -> TestRuntimeConfiguration.buildBootstrapRootTrustStore(
                    objectMapper, environment, database, SECRET_PREFIX,
                    "test-secret", true, SCOPE, ROOT_SET, ROOT_DOMAIN, NOTARY_DOMAIN))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("requires Byzantine fault tolerance");
        }
    }

    @Test
    void rejectsCrossDomainTrustAndFloorAliasingBeforeFetching() {
        MockEnvironment environment = environment(genesis)
                .withProperty(SUITE_PREFIX + ".enabled", "true")
                .withProperty(SUITE_PREFIX + ".managed-trust.enabled", "true")
                .withProperty(SUITE_PREFIX
                        + ".managed-trust.bootstrap-roots.enabled", "true")
                .withProperty(
                        "gateway.testing.stability-jobs.authority.http.jwks.cohort.scope-id",
                        SCOPE)
                .withProperty(SUITE_PREFIX + ".managed-trust.trust-root-set-id", ROOT_SET)
                .withProperty(SUITE_PREFIX + ".trust-domain", "suite-notary.example")
                .withProperty(SUITE_PREFIX + ".managed-trust.bootstrap-trust-domain",
                        "suite-notary-root.example");
        try (TestRuntimeDatabase database = database("alias")) {
            assertThatThrownBy(() -> TestRuntimeConfiguration.buildBootstrapRootTrustStore(
                    objectMapper, environment, database, SECRET_PREFIX,
                    "test-secret", false, SCOPE, ROOT_SET, ROOT_DOMAIN, NOTARY_DOMAIN))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must not share trust domains or root floors");
        }
    }

    @Test
    void rejectsCrossRoleTrustDomainAliasingBeforeFetching() {
        MockEnvironment environment = environment(genesis)
                .withProperty(SUITE_PREFIX + ".enabled", "true")
                .withProperty(SUITE_PREFIX + ".managed-trust.enabled", "true")
                .withProperty(SUITE_PREFIX
                        + ".managed-trust.bootstrap-roots.enabled", "true")
                .withProperty(
                        "gateway.testing.stability-jobs.authority.http.jwks.cohort.scope-id",
                        "suite-fleet")
                .withProperty(SUITE_PREFIX + ".managed-trust.trust-root-set-id",
                        "suite-notary-roots")
                .withProperty(SUITE_PREFIX + ".trust-domain", "suite-notary.example")
                .withProperty(SUITE_PREFIX + ".managed-trust.bootstrap-trust-domain",
                        NOTARY_DOMAIN);
        try (TestRuntimeDatabase database = database("cross-role-alias")) {
            assertThatThrownBy(() -> TestRuntimeConfiguration.buildBootstrapRootTrustStore(
                    objectMapper, environment, database, SECRET_PREFIX,
                    "test-secret", false, SCOPE, ROOT_SET, ROOT_DOMAIN, NOTARY_DOMAIN))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must not share trust domains or root floors");
        }
    }

    private MockEnvironment environment(
            ExternalSequenceAnchorBootstrapRootGenesis configuredGenesis) {
        return new MockEnvironment()
                .withProperty(SECRET_PREFIX + ".managed-trust.bootstrap-roots.genesis-json",
                        json(configuredGenesis))
                .withProperty(SECRET_PREFIX
                        + ".managed-trust.bootstrap-roots.accepted-policy-fingerprints", POLICY)
                .withProperty(SECRET_PREFIX + ".managed-trust.bootstrap-roots.bundle-uri",
                        server == null ? "http://127.0.0.1:1/roots" :
                                "http://127.0.0.1:" + server.getAddress().getPort() + "/roots")
                .withProperty(SECRET_PREFIX
                        + ".managed-trust.bootstrap-roots.allow-insecure-loopback", "true")
                .withProperty(SUITE_PREFIX + ".enabled", "false");
    }

    private void startServer(ExternalSequenceAnchorBootstrapRootBundle document)
            throws Exception {
        byte[] body = objectMapper.writeValueAsBytes(document);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/roots", exchange -> {
            exchange.getResponseHeaders().set("Content-Type",
                    DynamicExternalSequenceAnchorBootstrapRootTrustStore.MEDIA_TYPE);
            exchange.getResponseHeaders().set(
                    DynamicExternalSequenceAnchorBootstrapRootTrustStore.PROTOCOL_HEADER,
                    ExternalSequenceAnchorBootstrapRootBundle.SCHEMA_VERSION);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    private ExternalSequenceAnchorBootstrapRootGenesis genesis(
            Map<String, KeyPair> roots, int threshold, int maximumFaults) {
        return new ExternalSequenceAnchorBootstrapRootGenesis(
                ExternalSequenceAnchorBootstrapRootGenesis.SCHEMA_VERSION,
                SCOPE, ROOT_SET, ROOT_DOMAIN, threshold, maximumFaults,
                materials(roots, "genesis", now.minusSeconds(3600),
                        now.plusSeconds(86_400)), GENESIS_POLICY);
    }

    private ExternalSequenceAnchorBootstrapRootTransition transition() throws Exception {
        Instant issuedAt = now.minusSeconds(30);
        var material = new ExternalSequenceAnchorBootstrapRootTransition.Material(
                ExternalSequenceAnchorBootstrapRootTransition.Material.SCHEMA_VERSION,
                ROOT_SET, 1, genesis.materialFingerprint(objectMapper), SCOPE,
                ROOT_DOMAIN, 3, 1,
                materials(successorKeys, "successor", now.minusSeconds(60),
                        now.plusSeconds(7200)),
                POLICY, issuedAt, issuedAt, now.plusSeconds(7200));
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return new ExternalSequenceAnchorBootstrapRootTransition(
                ExternalSequenceAnchorBootstrapRootTransition.SCHEMA_VERSION,
                material, fingerprint,
                signatures(genesisKeys, "genesis", fingerprint, issuedAt),
                signatures(successorKeys, "successor", fingerprint, issuedAt));
    }

    private List<ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial> materials(
            Map<String, KeyPair> roots,
            String keyPrefix,
            Instant notBefore,
            Instant expiresAt) {
        return roots.entrySet().stream()
                .map(entry -> new ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial(
                        entry.getKey(), keyPrefix + "-key-" + suffix(entry.getKey()),
                        Base64.getEncoder().encodeToString(
                                entry.getValue().getPublic().getEncoded()),
                        notBefore, expiresAt, true, false))
                .sorted(Comparator.comparing(
                        ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial::authorityId))
                .toList();
    }

    private List<TestSuiteStabilityServingInventory.AuthoritySignature> signatures(
            Map<String, KeyPair> roots,
            String keyPrefix,
            String fingerprint,
            Instant signedAt) throws Exception {
        List<TestSuiteStabilityServingInventory.AuthoritySignature> result = new ArrayList<>();
        for (Map.Entry<String, KeyPair> entry : roots.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).limit(3).toList()) {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(entry.getValue().getPrivate());
            signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
            result.add(new TestSuiteStabilityServingInventory.AuthoritySignature(
                    entry.getKey(), keyPrefix + "-key-" + suffix(entry.getKey()),
                    "Ed25519", signedAt,
                    Base64.getEncoder().encodeToString(signer.sign())));
        }
        return List.copyOf(result);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    private static Map<String, KeyPair> keys(String ignoredLabel) throws Exception {
        Map<String, KeyPair> result = new LinkedHashMap<>();
        for (int index = 1; index <= 4; index++) {
            result.put("root-" + index,
                    KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        }
        return result;
    }

    private static String suffix(String authorityId) {
        return authorityId.substring(authorityId.lastIndexOf('-') + 1);
    }

    private static TestRuntimeDatabase database(String label) {
        return new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:bootstrap-root-config-" + label + '-' + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 2));
    }
}
