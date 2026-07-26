package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseReadOnlyShadowAuthorityPublicationRepositoryTest {
    private static final Instant NOW =
            Instant.parse("2026-07-26T09:20:00Z");
    private static final String ISSUER =
            "data-governance:shadow";

    private final ObjectMapper mapper =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(
                            SerializationFeature
                                    .WRITE_DATES_AS_TIMESTAMPS);
    private final ReadOnlyShadowAuthorityIntegrity integrity =
            new ReadOnlyShadowAuthorityIntegrity(mapper);
    private final InMemoryVisualEvidenceSigner signer =
            InMemoryVisualEvidenceSigner.usingClock(
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private DatabaseReadOnlyShadowAuthorityPublicationRepository
            repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        transactions =
                new DataSourceTransactionManager(database);
        repository = repository();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void persistsAllCurrentHeadsAcrossRepositoryRestart() {
        var policy = policy(
                1, "", limits(4));
        var grant = grant(
                1, "", true, policy.artifactRef(), 100);
        var killSwitch = killSwitch(
                1, "", true);

        assertThat(repository.append(policy))
                .isEqualTo(policy);
        assertThat(repository.append(grant))
                .isEqualTo(grant);
        assertThat(repository.append(killSwitch))
                .isEqualTo(killSwitch);
        assertThat(repository.append(grant))
                .isEqualTo(grant);

        var restarted = repository();
        assertThat(restarted.available()).isTrue();
        assertThat(restarted.currentGuardPolicy(
                guardScope(),
                policy.material().policyId()))
                .contains(policy);
        assertThat(restarted.currentSamplingGrant(
                executionScope(),
                grant.material().grantId()))
                .contains(grant);
        assertThat(restarted.currentKillSwitch(
                executionScope(),
                killSwitch.material().switchId()))
                .contains(killSwitch);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mirror_shadow_authority_publications",
                Integer.class)).isEqualTo(3);
    }

    @Test
    void rejectsGapWrongPredecessorRollbackAndSameRevisionFork() {
        var genesis = policy(
                1, "", limits(4));
        assertReason(
                () -> repository.append(
                        policy(
                                2,
                                fingerprint('a'),
                                limits(2))),
                DatabaseReadOnlyShadowAuthorityPublicationRepository
                        .Reason.BOOTSTRAP_REVISION_INVALID);

        repository.append(genesis);
        assertReason(
                () -> repository.append(
                        policy(
                                3,
                                genesis.publicationFingerprint(),
                                limits(2))),
                DatabaseReadOnlyShadowAuthorityPublicationRepository
                        .Reason.REVISION_GAP);
        assertReason(
                () -> repository.append(
                        policy(
                                2,
                                fingerprint('b'),
                                limits(2))),
                DatabaseReadOnlyShadowAuthorityPublicationRepository
                        .Reason.PREDECESSOR_MISMATCH);

        var successor = policy(
                2,
                genesis.publicationFingerprint(),
                limits(2));
        repository.append(successor);
        assertReason(
                () -> repository.append(genesis),
                DatabaseReadOnlyShadowAuthorityPublicationRepository
                        .Reason.REVISION_ROLLBACK);
        assertReason(
                () -> repository.append(
                        policy(
                                2,
                                genesis.publicationFingerprint(),
                                limits(3))),
                DatabaseReadOnlyShadowAuthorityPublicationRepository
                        .Reason.REVISION_FORK);
    }

    @Test
    void serializesCompetingSuccessorsAcrossRepositoryInstances()
            throws Exception {
        var genesis = grant(
                1,
                "",
                true,
                policy(1, "", limits(4))
                        .artifactRef(),
                100);
        repository.append(genesis);
        var candidateA = grant(
                2,
                genesis.publicationFingerprint(),
                true,
                genesis.material().guardPolicyRef(),
                90);
        var candidateB = grant(
                2,
                genesis.publicationFingerprint(),
                false,
                genesis.material().guardPolicyRef(),
                100);
        var second = repository();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<Object> firstResult =
                appendAsync(
                        repository,
                        candidateA,
                        ready,
                        start);
        CompletableFuture<Object> secondResult =
                appendAsync(
                        second,
                        candidateB,
                        ready,
                        start);
        ready.await();
        start.countDown();
        List<Object> outcomes = List.of(
                firstResult.get(),
                secondResult.get());

        assertThat(outcomes.stream()
                .filter(
                        ReadOnlyShadowSamplingGrantPublication
                                .class::isInstance))
                .hasSize(1);
        assertThat(outcomes.stream()
                .filter(
                        DatabaseReadOnlyShadowAuthorityPublicationRepository
                                .Violation.class::isInstance)
                .map(
                        DatabaseReadOnlyShadowAuthorityPublicationRepository
                                .Violation.class::cast)
                .map(
                        DatabaseReadOnlyShadowAuthorityPublicationRepository
                                .Violation::reason))
                .containsExactly(
                        DatabaseReadOnlyShadowAuthorityPublicationRepository
                                .Reason.REVISION_FORK);
        assertThat(repository.currentSamplingGrant(
                executionScope(),
                genesis.material().grantId())
                .orElseThrow())
                .isIn(candidateA, candidateB);
    }

    @Test
    void serializesCompetingGenesisPublicationsWithoutAbortedTransactionReuse()
            throws Exception {
        var policy = policy(
                1, "", limits(4));
        repository.append(policy);
        var candidateA = grant(
                1, "", true, policy.artifactRef(), 100);
        var candidateB = grant(
                1, "", false, policy.artifactRef(), 100);
        var second = repository();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<Object> firstResult =
                appendAsync(
                        repository,
                        candidateA,
                        ready,
                        start);
        CompletableFuture<Object> secondResult =
                appendAsync(
                        second,
                        candidateB,
                        ready,
                        start);
        ready.await();
        start.countDown();
        List<Object> outcomes = List.of(
                firstResult.get(),
                secondResult.get());

        assertThat(outcomes.stream()
                .filter(
                        ReadOnlyShadowSamplingGrantPublication
                                .class::isInstance))
                .hasSize(1);
        assertThat(outcomes.stream()
                .filter(
                        DatabaseReadOnlyShadowAuthorityPublicationRepository
                                .Violation.class::isInstance)
                .map(
                        DatabaseReadOnlyShadowAuthorityPublicationRepository
                                .Violation.class::cast)
                .map(
                        DatabaseReadOnlyShadowAuthorityPublicationRepository
                                .Violation::reason))
                .containsExactly(
                        DatabaseReadOnlyShadowAuthorityPublicationRepository
                                .Reason.REVISION_FORK);
    }

    @Test
    void untrustedPublicationCannotPoisonTheCurrentHead() {
        InMemoryVisualEvidenceSigner foreign =
                InMemoryVisualEvidenceSigner.usingClock(
                        Clock.fixed(
                                NOW, ZoneOffset.UTC));
        ReadOnlyShadowKillSwitchPublication untrusted =
                integrity.sealKillSwitch(
                        new ReadOnlyShadowKillSwitchPublication.Material(
                                "switch:untrusted",
                                1,
                                "",
                                executionScope(),
                                true,
                                NOW.minusSeconds(60),
                                NOW.minusSeconds(30),
                                NOW.plusSeconds(600),
                                ISSUER),
                        foreign);

        assertReason(
                () -> repository.append(untrusted),
                DatabaseReadOnlyShadowAuthorityPublicationRepository
                        .Reason.AUTHORITY_KEY_REJECTED);
        assertThat(repository.currentKillSwitch(
                executionScope(),
                untrusted.material().switchId()))
                .isEmpty();
    }

    @Test
    void trustedKeyIdentityWithInvalidSignatureCannotPoisonTheCurrentHead()
            throws Exception {
        ReadOnlyShadowKillSwitchPublication trusted =
                killSwitch(1, "", true);
        ReadOnlyShadowAuthoritySeal forgedSeal =
                new ReadOnlyShadowAuthoritySeal(
                        trusted.materialFingerprint(),
                        trusted.seal().algorithm(),
                        trusted.seal().keyId(),
                        trusted.seal().signedAt(),
                        Base64.getEncoder().encodeToString(
                                new byte[64]));
        String publicationFingerprint =
                VisualBundleFingerprint.fromCanonicalValue(
                        mapper,
                        new ForgedPublicationMaterial(
                                trusted.schemaVersion(),
                                "",
                                trusted.materialFingerprint(),
                                trusted.material(),
                                forgedSeal),
                        ReadOnlyShadowAuthorityIntegrity
                                .MAXIMUM_PUBLICATION_BYTES);
        ReadOnlyShadowKillSwitchPublication publication =
                new ReadOnlyShadowKillSwitchPublication(
                        "",
                        publicationFingerprint,
                        trusted.materialFingerprint(),
                        trusted.material(),
                        forgedSeal);

        assertThat(integrity
                .canonicalFingerprintVerified(publication))
                .isTrue();
        assertReason(
                () -> repository.append(publication),
                DatabaseReadOnlyShadowAuthorityPublicationRepository
                        .Reason.AUTHORITY_PUBLICATION_UNTRUSTED);
        assertThat(repository.currentKillSwitch(
                executionScope(),
                publication.material().switchId()))
                .isEmpty();
    }

    private record ForgedPublicationMaterial(
            String schemaVersion,
            String publicationFingerprint,
            String materialFingerprint,
            Object material,
            ReadOnlyShadowAuthoritySeal seal
    ) {
    }

    @Test
    void isolatesScopesAndFailsClosedForCorruptStoredJson() {
        var tenantA = killSwitch(
                1, "", true);
        var tenantB = integrity.sealKillSwitch(
                new ReadOnlyShadowKillSwitchPublication.Material(
                        tenantA.material().switchId(),
                        1,
                        "",
                        new CapabilitySnapshot.Scope(
                                "tenant-b",
                                "risk",
                                "loan",
                                "staging",
                                "sg"),
                        true,
                        NOW.minusSeconds(60),
                        NOW.minusSeconds(30),
                        NOW.plusSeconds(600),
                        ISSUER),
                signer);
        repository.append(tenantA);
        repository.append(tenantB);

        assertThat(repository.currentKillSwitch(
                executionScope(),
                tenantA.material().switchId()))
                .contains(tenantA);
        assertThat(repository.currentKillSwitch(
                tenantB.material().scope(),
                tenantB.material().switchId()))
                .contains(tenantB);

        jdbc.update("""
                UPDATE mirror_shadow_authority_publications
                SET publication_json = REPLACE(
                    publication_json, 'tenant-a', 'tenant-z')
                WHERE tenant_id = 'tenant-a'
                """);
        assertReason(
                () -> repository.currentKillSwitch(
                        executionScope(),
                        tenantA.material().switchId()),
                DatabaseReadOnlyShadowAuthorityPublicationRepository
                        .Reason.STORED_STATE_CORRUPT);
    }

    @Test
    void unknownStoredFieldsFailClosedEvenWhenApplicationMapperIsLenient()
            throws Exception {
        ObjectMapper lenient = mapper.copy()
                .disable(
                        DeserializationFeature
                                .FAIL_ON_UNKNOWN_PROPERTIES);
        var strictRepository =
                new DatabaseReadOnlyShadowAuthorityPublicationRepository(
                        jdbc,
                        lenient,
                        new ReadOnlyShadowAuthorityIntegrity(
                                lenient),
                        trustStore(),
                        Clock.fixed(
                                NOW, ZoneOffset.UTC),
                        transactions);
        strictRepository.init();
        var publication = killSwitch(
                1, "", true);
        strictRepository.append(publication);

        ObjectNode stored = (ObjectNode) lenient.readTree(
                jdbc.queryForObject(
                        """
                        SELECT publication_json
                        FROM mirror_shadow_authority_publications
                        WHERE publication_fingerprint = ?
                        """,
                        String.class,
                        publication.publicationFingerprint()));
        stored.put("trusted", true);
        jdbc.update(
                """
                UPDATE mirror_shadow_authority_publications
                SET publication_json = ?
                WHERE publication_fingerprint = ?
                """,
                lenient.writeValueAsString(stored),
                publication.publicationFingerprint());

        assertReason(
                () -> strictRepository.currentKillSwitch(
                        executionScope(),
                        publication.material().switchId()),
                DatabaseReadOnlyShadowAuthorityPublicationRepository
                        .Reason.STORED_STATE_CORRUPT);
    }

    @Test
    void schemaContainsNoBusinessPayloadOrCredentialColumns() {
        List<String> headColumns =
                columns("MIRROR_SHADOW_AUTHORITY_HEADS");
        List<String> publicationColumns =
                columns(
                        "MIRROR_SHADOW_AUTHORITY_PUBLICATIONS");

        assertThat(headColumns)
                .contains(
                        "TENANT_ID",
                        "ORGANIZATION_ID",
                        "PROJECT_ID",
                        "ENVIRONMENT_ID",
                        "REGION",
                        "PUBLICATION_KIND",
                        "STREAM_ID",
                        "CURRENT_REVISION",
                        "CURRENT_PUBLICATION_FINGERPRINT");
        assertThat(publicationColumns)
                .contains(
                        "MATERIAL_FINGERPRINT",
                        "SCHEMA_VERSION",
                        "PUBLICATION_JSON");
        assertThat(headColumns)
                .noneMatch(
                        DatabaseReadOnlyShadowAuthorityPublicationRepositoryTest
                                ::payloadColumn);
        assertThat(publicationColumns)
                .noneMatch(
                        DatabaseReadOnlyShadowAuthorityPublicationRepositoryTest
                                ::payloadColumn);
    }

    private DatabaseReadOnlyShadowAuthorityPublicationRepository
    repository() {
        var value =
                new DatabaseReadOnlyShadowAuthorityPublicationRepository(
                        jdbc,
                        mapper,
                        integrity,
                        trustStore(),
                        Clock.fixed(
                                NOW, ZoneOffset.UTC),
                        transactions);
        value.init();
        return value;
    }

    private ReadOnlyShadowAuthorityTrustStore trustStore() {
        return new ReadOnlyShadowAuthorityTrustStore() {
            @Override
            public Optional<
                    ReadOnlyShadowAuthorityIntegrity.AuthorityKey>
            resolve(
                    CapabilitySnapshot.Scope scope,
                    ReadOnlyShadowAuthorityIntegrity.PublicationKind
                            publicationKind,
                    String issuer,
                    String keyId) {
                boolean delegated =
                        publicationKind
                                == ReadOnlyShadowAuthorityIntegrity
                                .PublicationKind.GUARD_POLICY
                                ? guardScope().equals(scope)
                                : executionScope().equals(scope)
                                || tenantBExecutionScope()
                                .equals(scope);
                if (!delegated || !ISSUER.equals(issuer)) {
                    return Optional.empty();
                }
                var verificationKey = signer.key(keyId)
                        .orElse(null);
                if (verificationKey == null) {
                    return Optional.empty();
                }
                return Optional.of(
                        new ReadOnlyShadowAuthorityIntegrity
                                .AuthorityKey(
                                verificationKey.keyId(),
                                verificationKey.algorithm(),
                                verificationKey.encodedPublicKey(),
                                ISSUER,
                                scope,
                                publicationKind,
                                NOW.minusSeconds(1),
                                NOW.plusSeconds(3600),
                                null,
                                ReadOnlyShadowAuthorityIntegrity
                                        .KeyState.ACTIVE));
            }

            @Override
            public boolean available() {
                return true;
            }
        };
    }

    private CompletableFuture<Object> appendAsync(
            DatabaseReadOnlyShadowAuthorityPublicationRepository
                    target,
            ReadOnlyShadowSamplingGrantPublication publication,
            CountDownLatch ready,
            CountDownLatch start) {
        return CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            try {
                start.await();
                return target.append(publication);
            } catch (Exception failure) {
                return failure;
            }
        });
    }

    private List<String> columns(String table) {
        return jdbc.queryForList("""
                SELECT COLUMN_NAME
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = ?
                ORDER BY ORDINAL_POSITION
                """, String.class, table);
    }

    private ReadOnlyShadowGuardPolicyPublication policy(
            long revision,
            String previous,
            ReadOnlyShadowExecutionGuard.Limits limits) {
        return integrity.sealGuardPolicy(
                new ReadOnlyShadowGuardPolicyPublication.Material(
                        "provider:credit-primary",
                        revision,
                        previous,
                        guardScope(),
                        limits,
                        NOW.minusSeconds(60),
                        NOW.minusSeconds(30),
                        NOW.plusSeconds(600),
                        ISSUER),
                signer);
    }

    private ReadOnlyShadowSamplingGrantPublication grant(
            long revision,
            String previous,
            boolean active,
            MirrorArtifactRef policyRef,
            long maximumSamples) {
        return integrity.sealSamplingGrant(
                new ReadOnlyShadowSamplingGrantPublication.Material(
                        "grant:loan-risk",
                        revision,
                        previous,
                        executionScope(),
                        active,
                        maximumSamples,
                        guardScope(),
                        policyRef,
                        NOW.minusSeconds(60),
                        NOW.minusSeconds(30),
                        NOW.plusSeconds(600),
                        ISSUER),
                signer);
    }

    private ReadOnlyShadowKillSwitchPublication killSwitch(
            long revision,
            String previous,
            boolean enabled) {
        return integrity.sealKillSwitch(
                new ReadOnlyShadowKillSwitchPublication.Material(
                        "switch:loan-risk",
                        revision,
                        previous,
                        executionScope(),
                        enabled,
                        NOW.minusSeconds(60),
                        NOW.minusSeconds(30),
                        NOW.plusSeconds(600),
                        ISSUER),
                signer);
    }

    private static ReadOnlyShadowExecutionGuard.Limits
    limits(int maximumConcurrent) {
        return new ReadOnlyShadowExecutionGuard.Limits(
                maximumConcurrent,
                20,
                Duration.ofMinutes(1),
                3,
                Duration.ofMinutes(2));
    }

    private static CapabilitySnapshot.Scope executionScope() {
        return new CapabilitySnapshot.Scope(
                "tenant-a",
                "risk",
                "loan",
                "staging",
                "sg");
    }

    private static CapabilitySnapshot.Scope guardScope() {
        return new CapabilitySnapshot.Scope(
                "tenant-a",
                "shared-provider",
                "",
                "staging",
                "sg");
    }

    private static CapabilitySnapshot.Scope
    tenantBExecutionScope() {
        return new CapabilitySnapshot.Scope(
                "tenant-b",
                "risk",
                "loan",
                "staging",
                "sg");
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static boolean payloadColumn(String column) {
        String normalized =
                column.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("payload")
                || normalized.contains("request")
                || normalized.contains("response")
                || normalized.contains("credential")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("exception")
                || normalized.contains("stack");
    }

    private static void assertReason(
            org.assertj.core.api.ThrowableAssert
                    .ThrowingCallable action,
            DatabaseReadOnlyShadowAuthorityPublicationRepository
                    .Reason reason) {
        assertThatThrownBy(action)
                .isInstanceOf(
                        DatabaseReadOnlyShadowAuthorityPublicationRepository
                                .Violation.class)
                .extracting(value ->
                        ((DatabaseReadOnlyShadowAuthorityPublicationRepository
                                .Violation) value).reason())
                .isEqualTo(reason);
    }
}
