package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlyShadowAuthorityKeySetIntegrityTest {
    private static final Instant NOW = Instant.parse("2026-07-26T10:30:00Z");
    private static final String KEY_SET_ID = "shadow-sampling-keys:staging";
    private static final String ISSUER = "data-governance:shadow";
    private static final String TRUST_DOMAIN = "security:shadow-bootstrap";
    private static final String POLICY = fingerprint('a');

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

    private List<ReadOnlyShadowAuthorityKeySetIntegrity.NamedRootSigner> signers;
    private List<ReadOnlyShadowAuthorityKeySetIntegrity.RootVerificationKey> roots;
    private ReadOnlyShadowAuthorityKeySetIntegrity.ExpectedBinding binding;

    @BeforeEach
    void setUp() {
        signers = List.of(
                new ReadOnlyShadowAuthorityKeySetIntegrity.NamedRootSigner(
                        "security-root:a", rootA),
                new ReadOnlyShadowAuthorityKeySetIntegrity.NamedRootSigner(
                        "security-root:b", rootB));
        roots = List.of(root("security-root:a", rootA, true),
                root("security-root:b", rootB, true));
        binding = new ReadOnlyShadowAuthorityKeySetIntegrity.ExpectedBinding(
                scope(), ReadOnlyShadowAuthorityIntegrity.PublicationKind.SAMPLING_GRANT,
                ISSUER, KEY_SET_ID, TRUST_DOMAIN, 2, Set.of(POLICY));
    }

    @Test
    void verifiesThresholdSignaturesExactBindingAndCurrentFloor() {
        var publication = integrity.seal(material(1, "", activeKey()), signers);

        var result = integrity.verify(publication, binding, roots, null, NOW.plusSeconds(1));

        assertThat(result.verified()).isTrue();
        assertThat(result.reasonCode()).isEqualTo("VERIFIED");
        var floor = new ReadOnlyShadowAuthorityKeySetIntegrity.TrustedFloor(
                KEY_SET_ID, 1, publication.publicationFingerprint());
        assertThat(integrity.verify(
                publication, binding, roots, floor, NOW.plusSeconds(1)).verified()).isTrue();
    }

    @Test
    void rejectsRevokedUnknownAliasedAndWrongMaterialRoots() {
        var publication = integrity.seal(material(1, "", activeKey()), signers);
        var revoked = root("security-root:a", rootA, false);
        assertThat(integrity.verify(publication, binding,
                List.of(revoked, roots.get(1)), null, NOW.plusSeconds(1)).reasonCode())
                .isEqualTo("BOOTSTRAP_ROOT_POLICY_REJECTED");

        assertThat(integrity.verify(publication, binding,
                roots.subList(0, 1), null, NOW.plusSeconds(1)).reasonCode())
                .isEqualTo("BOOTSTRAP_ROOT_UNKNOWN");

        var aliased = new ReadOnlyShadowAuthorityKeySetIntegrity.RootVerificationKey(
                roots.get(1).authorityId(), roots.get(1).keyId(), "Ed25519",
                roots.getFirst().encodedPublicKey(), NOW.minusSeconds(60),
                NOW.plusSeconds(7_200), true);
        assertThat(integrity.verify(publication, binding,
                List.of(roots.getFirst(), aliased), null, NOW.plusSeconds(1)).reasonCode())
                .isEqualTo("BOOTSTRAP_ROOTS_AMBIGUOUS");

        InMemoryVisualEvidenceSigner unrelated =
                InMemoryVisualEvidenceSigner.usingClock(Clock.fixed(NOW, ZoneOffset.UTC));
        var wrong = root("security-root:a", unrelated, true);
        wrong = new ReadOnlyShadowAuthorityKeySetIntegrity.RootVerificationKey(
                roots.getFirst().authorityId(), roots.getFirst().keyId(), wrong.algorithm(),
                wrong.encodedPublicKey(), wrong.notBefore(), wrong.notAfter(), true);
        assertThat(integrity.verify(publication, binding,
                List.of(wrong, roots.get(1)), null, NOW.plusSeconds(1)).reasonCode())
                .isEqualTo("BOOTSTRAP_ROOT_SIGNATURE_INVALID");
    }

    @Test
    void rejectsRollbackForkGapWrongPredecessorAndExpiredPublication() {
        var genesis = integrity.seal(material(1, "", activeKey()), signers);
        var floor = new ReadOnlyShadowAuthorityKeySetIntegrity.TrustedFloor(
                KEY_SET_ID, 1, genesis.publicationFingerprint());
        var successor = integrity.seal(
                material(2, genesis.publicationFingerprint(), activeKey()), signers);
        assertThat(integrity.verify(
                successor, binding, roots, floor, NOW.plusSeconds(1)).verified()).isTrue();

        var wrongPredecessor = integrity.seal(
                material(2, fingerprint('b'), activeKey()), signers);
        assertThat(integrity.verify(wrongPredecessor, binding, roots, floor,
                NOW.plusSeconds(1)).reasonCode())
                .isEqualTo("PUBLICATION_PREDECESSOR_MISMATCH");

        var gap = integrity.seal(
                material(3, successor.publicationFingerprint(), activeKey()), signers);
        assertThat(integrity.verify(gap, binding, roots, floor,
                NOW.plusSeconds(1)).reasonCode())
                .isEqualTo("PUBLICATION_GENERATION_GAP");

        var floor2 = new ReadOnlyShadowAuthorityKeySetIntegrity.TrustedFloor(
                KEY_SET_ID, 2, successor.publicationFingerprint());
        assertThat(integrity.verify(genesis, binding, roots, floor2,
                NOW.plusSeconds(1)).reasonCode())
                .isEqualTo("PUBLICATION_GENERATION_ROLLBACK");
        assertThat(integrity.verify(genesis, binding, roots, null,
                NOW.plusSeconds(3_600)).reasonCode())
                .isEqualTo("PUBLICATION_OUTSIDE_VALIDITY_WINDOW");
    }

    private ReadOnlyShadowAuthorityKeySetPublication.Material material(
            long generation,
            String previous,
            ReadOnlyShadowAuthorityKeySetPublication.AuthorityKey key) {
        return new ReadOnlyShadowAuthorityKeySetPublication.Material(
                KEY_SET_ID, generation, previous, scope(),
                ReadOnlyShadowAuthorityIntegrity.PublicationKind.SAMPLING_GRANT,
                ISSUER, TRUST_DOMAIN, 2, POLICY, NOW.minusSeconds(1), NOW,
                NOW.plusSeconds(3_600), List.of(key));
    }

    private ReadOnlyShadowAuthorityKeySetPublication.AuthorityKey activeKey() {
        VisualEvidenceSigner.VerificationKey key = authority.key(
                authority.descriptor().activeKeyId()).orElseThrow();
        return new ReadOnlyShadowAuthorityKeySetPublication.AuthorityKey(
                key.keyId(), key.algorithm(), key.encodedPublicKey(),
                NOW.minusSeconds(60), NOW.plusSeconds(7_200), null,
                ReadOnlyShadowAuthorityIntegrity.KeyState.ACTIVE);
    }

    private static ReadOnlyShadowAuthorityKeySetIntegrity.RootVerificationKey root(
            String authorityId,
            InMemoryVisualEvidenceSigner signer,
            boolean allowed) {
        VisualEvidenceSigner.VerificationKey key = signer.key(
                signer.descriptor().activeKeyId()).orElseThrow();
        return new ReadOnlyShadowAuthorityKeySetIntegrity.RootVerificationKey(
                authorityId, key.keyId(), key.algorithm(), key.encodedPublicKey(),
                NOW.minusSeconds(60), NOW.plusSeconds(7_200), allowed);
    }

    private static CapabilitySnapshot.Scope scope() {
        return new CapabilitySnapshot.Scope(
                "tenant-a", "org-a", "project-a", "staging", "ap-southeast-1");
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
