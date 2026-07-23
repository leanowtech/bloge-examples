package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorDeploymentIsolationAuthorityKeySetIntegrityTest {
    private static final Instant ISSUED_AT = Instant.now().minusSeconds(2);
    private static final Instant NOT_BEFORE = Instant.now().plusSeconds(30);
    private static final Instant EXPIRES_AT = NOT_BEFORE.plusSeconds(3_600);
    private static final String ISSUER = "sre:mirror-isolation";
    private static final String KEY_SET_ID = "mirror-isolation-authorities:staging";
    private static final String TRUST_DOMAIN = "security:mirror-bootstrap-roots";
    private static final String POLICY = fingerprint('9');

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final MirrorDeploymentIsolationAuthorityKeySetIntegrity integrity =
            new MirrorDeploymentIsolationAuthorityKeySetIntegrity(mapper);
    private final InMemoryVisualEvidenceSigner rootA = new InMemoryVisualEvidenceSigner();
    private final InMemoryVisualEvidenceSigner rootB = new InMemoryVisualEvidenceSigner();
    private final InMemoryVisualEvidenceSigner attestationSigner =
            new InMemoryVisualEvidenceSigner();

    private List<MirrorDeploymentIsolationAuthorityKeySetIntegrity.NamedRootSigner> signers;
    private List<MirrorDeploymentIsolationAuthorityKeySetIntegrity.RootVerificationKey> roots;
    private MirrorDeploymentIsolationAuthorityKeySetIntegrity.ExpectedBinding binding;
    private MirrorDeploymentIsolationAuthorityKeySetPublication publication;

    @BeforeEach
    void setUp() {
        signers = List.of(
                new MirrorDeploymentIsolationAuthorityKeySetIntegrity.NamedRootSigner(
                        "security-root:a", rootA),
                new MirrorDeploymentIsolationAuthorityKeySetIntegrity.NamedRootSigner(
                        "security-root:b", rootB));
        roots = List.of(root("security-root:a", rootA), root("security-root:b", rootB));
        binding = binding(List.of(POLICY));
        publication = integrity.seal(material(1, "", POLICY, 2), signers);
    }

    @Test
    void verifiesThresholdRootsAndExposesOnlyTheBoundAttestationKey() {
        var result = integrity.verify(publication, binding, roots, null,
                NOT_BEFORE.plusSeconds(1));

        assertThat(result.verified()).isTrue();
        assertThat(result.reasonCode()).isEqualTo("VERIFIED");
        assertThat(result.authorityKeys()).hasSize(1);
        assertThat(result.attestationKey(result.authorityKeys().getFirst().keyId()))
                .get()
                .extracting(MirrorDeploymentIsolationAttestationIntegrity.AuthorityKey::issuer)
                .isEqualTo(ISSUER);
        assertThat(publication.artifactRef()).isEqualTo(new MirrorArtifactRef(
                MirrorDeploymentIsolationAuthorityKeySetPublication.ARTIFACT_KIND,
                KEY_SET_ID, 1, publication.publicationFingerprint()));
    }

    @Test
    void rejectsScopeDeploymentIssuerTrustDomainThresholdAndPolicyDrift() {
        var driftedScope = new MirrorDeploymentIsolationAuthorityKeySetIntegrity.ExpectedBinding(
                new CapabilitySnapshot.Scope("tenant-a", "org-a", "project-a", "production",
                        "ap-southeast-1"), deployment(), ISSUER, KEY_SET_ID, TRUST_DOMAIN, 2,
                List.of(POLICY));
        assertThat(integrity.verify(publication, driftedScope, roots, null,
                NOT_BEFORE.plusSeconds(1)).reasonCode())
                .isEqualTo("PUBLICATION_BINDING_MISMATCH");

        var weakThreshold = integrity.seal(material(1, "", POLICY, 1), signers);
        assertThat(integrity.verify(weakThreshold, binding, roots, null,
                NOT_BEFORE.plusSeconds(1)).reasonCode())
                .isEqualTo("PUBLICATION_BINDING_MISMATCH");

        var rejectedPolicy = binding(List.of(fingerprint('8')));
        assertThat(integrity.verify(publication, rejectedPolicy, roots, null,
                NOT_BEFORE.plusSeconds(1)).reasonCode())
                .isEqualTo("PUBLICATION_BINDING_MISMATCH");
    }

    @Test
    void enforcesBootstrapSuccessorIdempotencyRollbackForkGapAndPredecessor() {
        var floor1 = new MirrorDeploymentIsolationAuthorityKeySetIntegrity.TrustedFloor(
                KEY_SET_ID, 1, publication.publicationFingerprint());
        assertThat(integrity.verify(publication, binding, roots, floor1,
                NOT_BEFORE.plusSeconds(1)).verified()).isTrue();

        var successor = integrity.seal(material(2, publication.publicationFingerprint(),
                POLICY, 2), signers);
        assertThat(integrity.verify(successor, binding, roots, floor1,
                NOT_BEFORE.plusSeconds(1)).verified()).isTrue();

        var floor2 = new MirrorDeploymentIsolationAuthorityKeySetIntegrity.TrustedFloor(
                KEY_SET_ID, 2, successor.publicationFingerprint());
        assertThat(integrity.verify(publication, binding, roots, floor2,
                NOT_BEFORE.plusSeconds(1)).reasonCode())
                .isEqualTo("PUBLICATION_GENERATION_ROLLBACK");

        var sameGenerationFork = integrity.seal(material(1, "", POLICY, 2), signers);
        assertThat(integrity.verify(sameGenerationFork, binding, roots, floor1,
                NOT_BEFORE.plusSeconds(1)).reasonCode())
                .isEqualTo("PUBLICATION_GENERATION_FORK");

        var gap = integrity.seal(material(3, successor.publicationFingerprint(),
                POLICY, 2), signers);
        assertThat(integrity.verify(gap, binding, roots, floor1,
                NOT_BEFORE.plusSeconds(1)).reasonCode())
                .isEqualTo("PUBLICATION_GENERATION_GAP");

        var wrongPredecessor = integrity.seal(material(2, fingerprint('7'), POLICY, 2),
                signers);
        assertThat(integrity.verify(wrongPredecessor, binding, roots, floor1,
                NOT_BEFORE.plusSeconds(1)).reasonCode())
                .isEqualTo("PUBLICATION_PREDECESSOR_MISMATCH");
    }

    @Test
    void rejectsUnpinnedRevokedAndWrongRootKeyMaterialEvenAboveThreshold() {
        assertThatThrownBy(() -> integrity.seal(material(1, "", POLICY, 2), List.of(
                new MirrorDeploymentIsolationAuthorityKeySetIntegrity.NamedRootSigner(
                        "security-root:a", rootA),
                new MirrorDeploymentIsolationAuthorityKeySetIntegrity.NamedRootSigner(
                        "security-root:b", rootA))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distinct key material");

        assertThat(integrity.verify(publication, binding, roots.subList(0, 1), null,
                NOT_BEFORE.plusSeconds(1)).reasonCode())
                .isEqualTo("BOOTSTRAP_ROOT_UNKNOWN");

        var aliasedRoot = new MirrorDeploymentIsolationAuthorityKeySetIntegrity
                .RootVerificationKey(
                roots.get(1).authorityId(), roots.get(1).keyId(), roots.get(1).algorithm(),
                roots.getFirst().encodedPublicKey(), roots.get(1).notBefore(),
                roots.get(1).notAfter(),
                MirrorDeploymentIsolationAuthorityKeySetIntegrity.RootKeyState.ACTIVE);
        assertThat(integrity.verify(publication, binding,
                List.of(roots.getFirst(), aliasedRoot), null,
                NOT_BEFORE.plusSeconds(1)).reasonCode())
                .isEqualTo("BOOTSTRAP_ROOTS_AMBIGUOUS");

        var revoked = new MirrorDeploymentIsolationAuthorityKeySetIntegrity.RootVerificationKey(
                roots.getFirst().authorityId(), roots.getFirst().keyId(),
                roots.getFirst().algorithm(), roots.getFirst().encodedPublicKey(),
                roots.getFirst().notBefore(), roots.getFirst().notAfter(),
                MirrorDeploymentIsolationAuthorityKeySetIntegrity.RootKeyState.REVOKED);
        assertThat(integrity.verify(publication, binding,
                List.of(revoked, roots.get(1)), null,
                NOT_BEFORE.plusSeconds(1)).reasonCode())
                .isEqualTo("BOOTSTRAP_ROOT_POLICY_REJECTED");

        InMemoryVisualEvidenceSigner unrelated = new InMemoryVisualEvidenceSigner();
        VisualEvidenceSigner.VerificationKey unrelatedKey = unrelated.key(
                unrelated.descriptor().activeKeyId()).orElseThrow();
        var wrongMaterial = new
                MirrorDeploymentIsolationAuthorityKeySetIntegrity.RootVerificationKey(
                roots.getFirst().authorityId(), roots.getFirst().keyId(), "Ed25519",
                unrelatedKey.encodedPublicKey(), roots.getFirst().notBefore(),
                roots.getFirst().notAfter(),
                MirrorDeploymentIsolationAuthorityKeySetIntegrity.RootKeyState.ACTIVE);
        assertThat(integrity.verify(publication, binding,
                List.of(wrongMaterial, roots.get(1)), null,
                NOT_BEFORE.plusSeconds(1)).reasonCode())
                .isEqualTo("BOOTSTRAP_ROOT_SIGNATURE_INVALID");
    }

    @Test
    void rejectsTamperingBeforeTrustEvaluationAndNeverExposesKeysOnFailure() {
        var alteredMaterial = new MirrorDeploymentIsolationAuthorityKeySetPublication.Material(
                publication.material().keySetId(), publication.material().generation(), "",
                new CapabilitySnapshot.Scope("tenant-a", "org-b", "project-a", "staging",
                        "ap-southeast-1"), publication.material().deployment(), ISSUER,
                TRUST_DOMAIN, 2, POLICY, ISSUED_AT, NOT_BEFORE, EXPIRES_AT,
                publication.material().authorityKeys());
        var tampered = new MirrorDeploymentIsolationAuthorityKeySetPublication("",
                publication.publicationFingerprint(), publication.materialFingerprint(),
                alteredMaterial, publication.signatures());

        var result = integrity.verify(tampered, binding, roots, null,
                NOT_BEFORE.plusSeconds(1));
        assertThat(result.reasonCode()).isEqualTo("PUBLICATION_FINGERPRINT_INVALID");
        assertThat(result.authorityKeys()).isEmpty();
        assertThat(result.attestationKey("anything")).isEmpty();
    }

    @Test
    void rejectsFutureExpiredAndNonBootstrapPublications() {
        assertThat(integrity.verify(publication, binding, roots, null,
                NOT_BEFORE.minusNanos(1)).reasonCode())
                .isEqualTo("PUBLICATION_OUTSIDE_VALIDITY_WINDOW");
        assertThat(integrity.verify(publication, binding, roots, null,
                EXPIRES_AT).reasonCode())
                .isEqualTo("PUBLICATION_OUTSIDE_VALIDITY_WINDOW");

        var successor = integrity.seal(material(2, publication.publicationFingerprint(),
                POLICY, 2), signers);
        assertThat(integrity.verify(successor, binding, roots, null,
                NOT_BEFORE.plusSeconds(1)).reasonCode())
                .isEqualTo("PUBLICATION_BOOTSTRAP_GENERATION_INVALID");
    }

    @Test
    void rejectsNonCanonicalKeysSignaturesAndUnusableActiveKeyWindows() {
        var key = authorityKey();
        assertThatThrownBy(() -> new MirrorDeploymentIsolationAuthorityKeySetPublication.Material(
                KEY_SET_ID, 1, "", scope(), deployment(), ISSUER, TRUST_DOMAIN, 2,
                POLICY, ISSUED_AT, NOT_BEFORE, EXPIRES_AT,
                List.of(new MirrorDeploymentIsolationAuthorityKeySetPublication.AuthorityKey(
                        key.keyId(), key.algorithm(), key.encodedPublicKey(), key.notBefore(),
                        NOT_BEFORE, key.state()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ACTIVE key");

        assertThatThrownBy(() -> new
                MirrorDeploymentIsolationAuthorityKeySetPublication.AuthorityKey(
                "isolation-key", "Ed25519", "AB==", ISSUED_AT, EXPIRES_AT,
                MirrorDeploymentIsolationAuthorityKeySetPublication.AuthorityKeyState.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical base64");

        assertThatThrownBy(() -> new MirrorDeploymentIsolationAuthorityKeySetPublication("",
                publication.publicationFingerprint(), publication.materialFingerprint(),
                publication.material(), publication.signatures().reversed()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical order");

        assertThatThrownBy(() -> new MirrorDeploymentIsolationAuthorityKeySetPublication.Material(
                KEY_SET_ID, 1, "",
                new CapabilitySnapshot.Scope("tenant a", "org-a", "project-a", "staging",
                        "ap-southeast-1"),
                deployment(), ISSUER, TRUST_DOMAIN, 2, POLICY, ISSUED_AT, NOT_BEFORE,
                EXPIRES_AT, List.of(authorityKey())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope.tenantId");
    }

    private MirrorDeploymentIsolationAuthorityKeySetPublication.Material material(
            long generation, String previous, String policy, int threshold) {
        return new MirrorDeploymentIsolationAuthorityKeySetPublication.Material(
                KEY_SET_ID, generation, previous, scope(), deployment(), ISSUER, TRUST_DOMAIN,
                threshold, policy, ISSUED_AT, NOT_BEFORE, EXPIRES_AT, List.of(authorityKey()));
    }

    private MirrorDeploymentIsolationAuthorityKeySetPublication.AuthorityKey authorityKey() {
        VisualEvidenceSigner.VerificationKey key = attestationSigner.key(
                attestationSigner.descriptor().activeKeyId()).orElseThrow();
        return new MirrorDeploymentIsolationAuthorityKeySetPublication.AuthorityKey(
                key.keyId(), key.algorithm(), key.encodedPublicKey(),
                ISSUED_AT.minusSeconds(3_600), EXPIRES_AT.plusSeconds(3_600),
                MirrorDeploymentIsolationAuthorityKeySetPublication.AuthorityKeyState.ACTIVE);
    }

    private static MirrorDeploymentIsolationAuthorityKeySetIntegrity.RootVerificationKey root(
            String authorityId, InMemoryVisualEvidenceSigner signer) {
        VisualEvidenceSigner.VerificationKey key = signer.key(
                signer.descriptor().activeKeyId()).orElseThrow();
        return new MirrorDeploymentIsolationAuthorityKeySetIntegrity.RootVerificationKey(
                authorityId, key.keyId(), key.algorithm(), key.encodedPublicKey(),
                ISSUED_AT.minusSeconds(3_600), EXPIRES_AT.plusSeconds(3_600),
                MirrorDeploymentIsolationAuthorityKeySetIntegrity.RootKeyState.ACTIVE);
    }

    private static MirrorDeploymentIsolationAuthorityKeySetIntegrity.ExpectedBinding binding(
            List<String> policies) {
        return new MirrorDeploymentIsolationAuthorityKeySetIntegrity.ExpectedBinding(
                scope(), deployment(), ISSUER, KEY_SET_ID, TRUST_DOMAIN, 2, policies);
    }

    private static CapabilitySnapshot.Scope scope() {
        return new CapabilitySnapshot.Scope("tenant-a", "org-a", "project-a", "staging",
                "ap-southeast-1");
    }

    private static MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment() {
        return new MirrorDeploymentIsolationAttestation.DeploymentIdentity(
                "deployment:staging", "cluster-a", "rg-mirror", "resource-gateway",
                "rg-mirror", fingerprint('1'));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
