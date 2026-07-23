package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Deterministic public-key-only fixtures for authority publication persistence and API tests. */
final class MirrorDeploymentIsolationAuthorityPublicationTestFixtures {
    static final String ISSUER = "sre:mirror-isolation";
    static final String KEY_SET_ID = "mirror-isolation-authorities:staging";
    static final String DEPLOYMENT_SCOPE_ID = "deployment:staging";
    static final String TRUST_DOMAIN = "security:mirror-bootstrap-roots";
    static final String POLICY = fingerprint('9');

    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final MirrorDeploymentIsolationAuthorityKeySetIntegrity integrity =
            new MirrorDeploymentIsolationAuthorityKeySetIntegrity(mapper);
    final Instant issuedAt = Instant.now().minusSeconds(2);
    final Instant notBefore = Instant.now().plusSeconds(10);
    final Instant expiresAt = notBefore.plusSeconds(3_600);
    final Clock activeClock = Clock.fixed(notBefore.plusSeconds(1), ZoneOffset.UTC);

    private final InMemoryVisualEvidenceSigner rootA = new InMemoryVisualEvidenceSigner();
    private final InMemoryVisualEvidenceSigner rootB = new InMemoryVisualEvidenceSigner();
    private final InMemoryVisualEvidenceSigner attestation = new InMemoryVisualEvidenceSigner();
    private final List<MirrorDeploymentIsolationAuthorityKeySetIntegrity.NamedRootSigner> signers =
            List.of(named("security-root:a", rootA), named("security-root:b", rootB));

    MirrorDeploymentIsolationAuthorityKeySetPublication publication(
            long generation, String predecessor) {
        return publication(generation, predecessor, scope("org-a"), deployment("cluster-a"),
                POLICY);
    }

    MirrorDeploymentIsolationAuthorityKeySetPublication publication(
            long generation,
            String predecessor,
            CapabilitySnapshot.Scope scope,
            MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment,
            String policyFingerprint) {
        var material = new MirrorDeploymentIsolationAuthorityKeySetPublication.Material(
                KEY_SET_ID, generation, predecessor, scope, deployment, ISSUER, TRUST_DOMAIN, 2,
                policyFingerprint, issuedAt, notBefore, expiresAt, List.of(authorityKey()));
        return integrity.seal(material, signers);
    }

    MirrorDeploymentIsolationAuthorityKeySetIntegrity.ExpectedBinding binding() {
        return binding(scope("org-a"), deployment("cluster-a"), List.of(POLICY));
    }

    MirrorDeploymentIsolationAuthorityKeySetIntegrity.ExpectedBinding binding(
            CapabilitySnapshot.Scope scope,
            MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment,
            List<String> policies) {
        return new MirrorDeploymentIsolationAuthorityKeySetIntegrity.ExpectedBinding(
                scope, deployment, ISSUER, KEY_SET_ID, TRUST_DOMAIN, 2,
                policies.stream().sorted().toList());
    }

    MirrorDeploymentIsolationAuthorityTrustPolicyProvider provider() {
        var policy = new MirrorDeploymentIsolationAuthorityTrustPolicyProvider.TrustPolicy(
                binding(), roots());
        return new MirrorDeploymentIsolationAuthorityTrustPolicyProvider() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public Optional<TrustPolicy> resolve(
                    CapabilitySnapshot.Scope scope, String deploymentScopeId, String keySetId) {
                return policy.binding().scope().equals(scope)
                        && policy.binding().deployment().deploymentScopeId()
                        .equals(deploymentScopeId)
                        && policy.binding().keySetId().equals(keySetId)
                        ? Optional.of(policy) : Optional.empty();
            }
        };
    }

    List<MirrorDeploymentIsolationAuthorityKeySetIntegrity.RootVerificationKey> roots() {
        return List.of(root("security-root:a", rootA), root("security-root:b", rootB));
    }

    CapabilitySnapshot.Scope scope(String organizationId) {
        return new CapabilitySnapshot.Scope("tenant-a", organizationId, "project-a", "staging",
                "ap-southeast-1");
    }

    MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment(String clusterId) {
        return new MirrorDeploymentIsolationAttestation.DeploymentIdentity(
                DEPLOYMENT_SCOPE_ID, clusterId, "rg-mirror", "resource-gateway", "rg-mirror",
                fingerprint('1'));
    }

    private MirrorDeploymentIsolationAuthorityKeySetPublication.AuthorityKey authorityKey() {
        VisualEvidenceSigner.VerificationKey key = attestation.key(
                attestation.descriptor().activeKeyId()).orElseThrow();
        return new MirrorDeploymentIsolationAuthorityKeySetPublication.AuthorityKey(
                key.keyId(), key.algorithm(), key.encodedPublicKey(), issuedAt.minusSeconds(60),
                expiresAt.plusSeconds(60),
                MirrorDeploymentIsolationAuthorityKeySetPublication.AuthorityKeyState.ACTIVE);
    }

    private MirrorDeploymentIsolationAuthorityKeySetIntegrity.RootVerificationKey root(
            String authorityId, InMemoryVisualEvidenceSigner signer) {
        VisualEvidenceSigner.VerificationKey key = signer.key(
                signer.descriptor().activeKeyId()).orElseThrow();
        return new MirrorDeploymentIsolationAuthorityKeySetIntegrity.RootVerificationKey(
                authorityId, key.keyId(), key.algorithm(), key.encodedPublicKey(),
                issuedAt.minusSeconds(60), expiresAt.plusSeconds(60),
                MirrorDeploymentIsolationAuthorityKeySetIntegrity.RootKeyState.ACTIVE);
    }

    private static MirrorDeploymentIsolationAuthorityKeySetIntegrity.NamedRootSigner named(
            String authorityId, InMemoryVisualEvidenceSigner signer) {
        return new MirrorDeploymentIsolationAuthorityKeySetIntegrity.NamedRootSigner(
                authorityId, signer);
    }

    static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
