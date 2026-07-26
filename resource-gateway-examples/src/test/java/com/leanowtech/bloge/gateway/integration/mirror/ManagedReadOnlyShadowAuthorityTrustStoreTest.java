package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedReadOnlyShadowAuthorityTrustStoreTest {
    private static final Instant NOW = Instant.parse("2026-07-26T10:30:00Z");
    private static final String KEY_SET_ID = "shadow-sampling-keys:staging";
    private static final String ISSUER = "data-governance:shadow";
    private static final String TRUST_DOMAIN = "security:shadow-bootstrap";
    private static final String POLICY = fingerprint('a');

    private final Clock clock = Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final ReadOnlyShadowAuthorityKeySetIntegrity integrity =
            new ReadOnlyShadowAuthorityKeySetIntegrity(mapper);
    private final InMemoryVisualEvidenceSigner rootA =
            InMemoryVisualEvidenceSigner.usingClock(Clock.fixed(NOW, ZoneOffset.UTC));
    private final InMemoryVisualEvidenceSigner rootB =
            InMemoryVisualEvidenceSigner.usingClock(Clock.fixed(NOW, ZoneOffset.UTC));
    private final InMemoryVisualEvidenceSigner authority =
            InMemoryVisualEvidenceSigner.usingClock(Clock.fixed(NOW, ZoneOffset.UTC));
    private final InMemoryVisualEvidenceSigner replacement =
            InMemoryVisualEvidenceSigner.usingClock(Clock.fixed(NOW, ZoneOffset.UTC));

    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DatabaseReadOnlyShadowAuthorityKeySetRepository repository;
    private ReadOnlyShadowAuthorityKeySetTrustPolicyProvider.TrustPolicy policy;
    private ReadOnlyShadowAuthorityKeySetService service;
    private ManagedReadOnlyShadowAuthorityTrustStore trustStore;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
        jdbc = new JdbcTemplate(database);
        repository = new DatabaseReadOnlyShadowAuthorityKeySetRepository(
                jdbc, mapper, integrity, new DataSourceTransactionManager(database));
        repository.init();
        policy = new ReadOnlyShadowAuthorityKeySetTrustPolicyProvider.TrustPolicy(
                new ReadOnlyShadowAuthorityKeySetIntegrity.ExpectedBinding(
                        scope(), ReadOnlyShadowAuthorityIntegrity.PublicationKind.SAMPLING_GRANT,
                        ISSUER, KEY_SET_ID, TRUST_DOMAIN, 2, Set.of(POLICY)),
                List.of(root("security-root:a", rootA), root("security-root:b", rootB)));
        ReadOnlyShadowAuthorityKeySetTrustPolicyProvider provider = provider(policy);
        service = new ReadOnlyShadowAuthorityKeySetService(
                repository, provider, integrity, clock);
        trustStore = new ManagedReadOnlyShadowAuthorityTrustStore(
                repository, provider, integrity, clock);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void publishesCurrentHeadAndMakesRevocationVisibleWithoutPositiveCache() {
        var active = authorityKey(
                authority, ReadOnlyShadowAuthorityIntegrity.KeyState.ACTIVE, null);
        var genesis = publication(1, "", List.of(active));
        service.publish(genesis);

        assertThat(trustStore.available()).isTrue();
        assertThat(trustStore.resolve(scope(),
                ReadOnlyShadowAuthorityIntegrity.PublicationKind.SAMPLING_GRANT,
                ISSUER, active.keyId()))
                .get()
                .extracting(ReadOnlyShadowAuthorityIntegrity.AuthorityKey::state)
                .isEqualTo(ReadOnlyShadowAuthorityIntegrity.KeyState.ACTIVE);

        var revoked = authorityKey(
                authority, ReadOnlyShadowAuthorityIntegrity.KeyState.REVOKED, null);
        var successor = publication(
                2, genesis.publicationFingerprint(), List.of(revoked));
        service.publish(successor);

        assertThat(trustStore.resolve(scope(),
                ReadOnlyShadowAuthorityIntegrity.PublicationKind.SAMPLING_GRANT,
                ISSUER, active.keyId()))
                .get()
                .extracting(ReadOnlyShadowAuthorityIntegrity.AuthorityKey::state)
                .isEqualTo(ReadOnlyShadowAuthorityIntegrity.KeyState.REVOKED);
        assertThat(repository.floor(stream()))
                .get()
                .extracting(ReadOnlyShadowAuthorityKeySetIntegrity.TrustedFloor::generation)
                .isEqualTo(2L);
    }

    @Test
    void permitsRotationButRejectsMaterialReplacementOmissionAndReactivation() {
        var active = authorityKey(
                authority, ReadOnlyShadowAuthorityIntegrity.KeyState.ACTIVE, null);
        var genesis = publication(1, "", List.of(active));
        service.publish(genesis);

        var changedMaterial = authorityKey(
                replacement, ReadOnlyShadowAuthorityIntegrity.KeyState.ACTIVE, null);
        changedMaterial = new ReadOnlyShadowAuthorityKeySetPublication.AuthorityKey(
                active.keyId(), changedMaterial.algorithm(), changedMaterial.encodedPublicKey(),
                active.notBefore(), active.notAfter(), null,
                ReadOnlyShadowAuthorityIntegrity.KeyState.ACTIVE);
        var materialReplacement = publication(
                2, genesis.publicationFingerprint(), List.of(changedMaterial));
        assertRepositoryReason(() -> service.publish(materialReplacement),
                ReadOnlyShadowAuthorityKeySetRepository.Reason.KEY_LIFECYCLE_INVALID);

        var replacementKey = authorityKey(
                replacement, ReadOnlyShadowAuthorityIntegrity.KeyState.ACTIVE, null);
        var omission = publication(
                2, genesis.publicationFingerprint(), List.of(replacementKey));
        assertRepositoryReason(() -> service.publish(omission),
                ReadOnlyShadowAuthorityKeySetRepository.Reason.KEY_LIFECYCLE_INVALID);

        var revoked = authorityKey(
                authority, ReadOnlyShadowAuthorityIntegrity.KeyState.REVOKED, null);
        var revokedGeneration = publication(
                2, genesis.publicationFingerprint(), List.of(revoked, replacementKey).stream()
                .sorted(java.util.Comparator.comparing(
                        ReadOnlyShadowAuthorityKeySetPublication.AuthorityKey::keyId)).toList());
        service.publish(revokedGeneration);
        var reactivated = publication(
                3, revokedGeneration.publicationFingerprint(),
                List.of(active, replacementKey).stream()
                        .sorted(java.util.Comparator.comparing(
                                ReadOnlyShadowAuthorityKeySetPublication.AuthorityKey::keyId))
                        .toList());
        assertRepositoryReason(() -> service.publish(reactivated),
                ReadOnlyShadowAuthorityKeySetRepository.Reason.KEY_LIFECYCLE_INVALID);
    }

    @Test
    void rejectsUntrustedRootBeforeItCanPoisonTheDurableHead() {
        var genesis = publication(1, "", List.of(authorityKey(
                authority, ReadOnlyShadowAuthorityIntegrity.KeyState.ACTIVE, null)));
        InMemoryVisualEvidenceSigner unrelated =
                InMemoryVisualEvidenceSigner.usingClock(Clock.fixed(NOW, ZoneOffset.UTC));
        var wrongPolicy = new ReadOnlyShadowAuthorityKeySetTrustPolicyProvider.TrustPolicy(
                policy.binding(),
                List.of(root("security-root:a", unrelated), policy.roots().get(1)));
        var rejecting = new ReadOnlyShadowAuthorityKeySetService(
                repository, provider(wrongPolicy), integrity, clock);

        assertThatThrownBy(() -> rejecting.publish(genesis))
                .isInstanceOf(ReadOnlyShadowAuthorityKeySetService.AdmissionRejected.class)
                .extracting("reason")
                .isEqualTo(ReadOnlyShadowAuthorityKeySetService.Reason.ROOT_POLICY_REJECTED);
        assertThat(repository.latest(stream())).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mirror_shadow_authority_key_set_publications",
                Integer.class)).isZero();
    }

    private ReadOnlyShadowAuthorityKeySetPublication publication(
            long generation,
            String previous,
            List<ReadOnlyShadowAuthorityKeySetPublication.AuthorityKey> keys) {
        var material = new ReadOnlyShadowAuthorityKeySetPublication.Material(
                KEY_SET_ID, generation, previous, scope(),
                ReadOnlyShadowAuthorityIntegrity.PublicationKind.SAMPLING_GRANT,
                ISSUER, TRUST_DOMAIN, 2, POLICY, NOW.minusSeconds(1), NOW,
                NOW.plusSeconds(3_600), keys);
        return integrity.seal(material, List.of(
                new ReadOnlyShadowAuthorityKeySetIntegrity.NamedRootSigner(
                        "security-root:a", rootA),
                new ReadOnlyShadowAuthorityKeySetIntegrity.NamedRootSigner(
                        "security-root:b", rootB)));
    }

    private ReadOnlyShadowAuthorityKeySetPublication.AuthorityKey authorityKey(
            InMemoryVisualEvidenceSigner signer,
            ReadOnlyShadowAuthorityIntegrity.KeyState state,
            Instant retiredAt) {
        VisualEvidenceSigner.VerificationKey key = signer.key(
                signer.descriptor().activeKeyId()).orElseThrow();
        return new ReadOnlyShadowAuthorityKeySetPublication.AuthorityKey(
                key.keyId(), key.algorithm(), key.encodedPublicKey(),
                NOW.minusSeconds(60), NOW.plusSeconds(7_200), retiredAt, state);
    }

    private static ReadOnlyShadowAuthorityKeySetIntegrity.RootVerificationKey root(
            String authorityId,
            InMemoryVisualEvidenceSigner signer) {
        VisualEvidenceSigner.VerificationKey key = signer.key(
                signer.descriptor().activeKeyId()).orElseThrow();
        return new ReadOnlyShadowAuthorityKeySetIntegrity.RootVerificationKey(
                authorityId, key.keyId(), key.algorithm(), key.encodedPublicKey(),
                NOW.minusSeconds(60), NOW.plusSeconds(7_200), true);
    }

    private static ReadOnlyShadowAuthorityKeySetTrustPolicyProvider provider(
            ReadOnlyShadowAuthorityKeySetTrustPolicyProvider.TrustPolicy policy) {
        return new ReadOnlyShadowAuthorityKeySetTrustPolicyProvider() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public Optional<TrustPolicy> resolve(
                    CapabilitySnapshot.Scope scope,
                    ReadOnlyShadowAuthorityIntegrity.PublicationKind publicationKind,
                    String issuer) {
                return ManagedReadOnlyShadowAuthorityTrustStoreTest.scope().equals(scope)
                        && publicationKind
                        == ReadOnlyShadowAuthorityIntegrity.PublicationKind.SAMPLING_GRANT
                        && ISSUER.equals(issuer) ? Optional.of(policy) : Optional.empty();
            }
        };
    }

    private static ReadOnlyShadowAuthorityKeySetRepository.StreamIdentity stream() {
        return new ReadOnlyShadowAuthorityKeySetRepository.StreamIdentity(
                scope(), ReadOnlyShadowAuthorityIntegrity.PublicationKind.SAMPLING_GRANT,
                ISSUER, KEY_SET_ID);
    }

    private static CapabilitySnapshot.Scope scope() {
        return new CapabilitySnapshot.Scope(
                "tenant-a", "org-a", "project-a", "staging", "ap-southeast-1");
    }

    private static void assertRepositoryReason(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            ReadOnlyShadowAuthorityKeySetRepository.Reason reason) {
        assertThatThrownBy(action)
                .isInstanceOf(ReadOnlyShadowAuthorityKeySetRepository.Violation.class)
                .extracting("reason")
                .isEqualTo(reason);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
