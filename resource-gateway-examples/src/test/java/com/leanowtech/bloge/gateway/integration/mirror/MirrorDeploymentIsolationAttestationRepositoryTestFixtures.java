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

/** Deterministic payload-free fixtures for deployment-isolation attestation repository tests. */
public final class MirrorDeploymentIsolationAttestationRepositoryTestFixtures {
    static final String KEY_SET_ID = "mirror-isolation-authorities:staging";
    static final String ATTESTATION_ID = "mirror-staging-isolation";
    static final long BOOTSTRAP_REVISION = 7;
    static final String POLICY = fingerprint('9');
    static final String TRUST_DOMAIN = "security:mirror-bootstrap-roots";

    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final MirrorDeploymentIsolationAttestationIntegrity attestationIntegrity =
            new MirrorDeploymentIsolationAttestationIntegrity(mapper);
    final MirrorDeploymentIsolationAttestationBundleIntegrity bundleIntegrity =
            new MirrorDeploymentIsolationAttestationBundleIntegrity(
                    mapper, attestationIntegrity);
    final MirrorDeploymentIsolationAuthorityKeySetIntegrity authorityIntegrity =
            new MirrorDeploymentIsolationAuthorityKeySetIntegrity(mapper);

    private final InMemoryVisualEvidenceSigner authority = new InMemoryVisualEvidenceSigner();
    private final InMemoryVisualEvidenceSigner rootA = new InMemoryVisualEvidenceSigner();
    private final InMemoryVisualEvidenceSigner rootB = new InMemoryVisualEvidenceSigner();
    private final Instant base = Instant.now();
    private final Instant observedAt = base.minusSeconds(2);
    private final Instant expiresAt = observedAt.plusSeconds(600);
    final Clock activeClock = Clock.fixed(base.plusSeconds(11), ZoneOffset.UTC);
    final Clock expiredClock = Clock.fixed(expiresAt.plusSeconds(1), ZoneOffset.UTC);

    public MirrorDeploymentIsolationAttestationRepositoryTestFixtures() {
    }

    public DistributionFixtures distributionFixtures() {
        var publication = authorityPublication();
        var signed = attestation(BOOTSTRAP_REVISION, deployment("cluster-a"), fingerprint('2'));
        var status = bundleIntegrity.activeStatus(scope("org-a"), publication.artifactRef(),
                signed, activeClock.instant());
        var bundle = bundleIntegrity.bundle(scope("org-a"), publication.artifactRef(),
                signed, status);
        return new DistributionFixtures(mapper, publication, bundle,
                deployment("cluster-a").deploymentScopeId(), KEY_SET_ID, ATTESTATION_ID);
    }

    public record DistributionFixtures(
            ObjectMapper mapper,
            MirrorDeploymentIsolationAuthorityKeySetPublication authority,
            MirrorDeploymentIsolationAttestationBundle bundle,
            String deploymentScopeId,
            String keySetId,
            String attestationId) {
    }

    MirrorDeploymentIsolationAttestationBundle bundle(long revision) {
        return bundle(revision, scope("org-a"), deployment("cluster-a"), fingerprint('2'));
    }

    MirrorDeploymentIsolationAttestationBundle bundle(
            long revision,
            CapabilitySnapshot.Scope scope,
            MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment,
            String networkPolicyFingerprint) {
        var attestation = attestation(revision, deployment, networkPolicyFingerprint);
        MirrorArtifactRef authorityRef = new MirrorArtifactRef(
                MirrorDeploymentIsolationAuthorityKeySetPublication.ARTIFACT_KIND,
                KEY_SET_ID, 3, fingerprint('a'));
        var status = bundleIntegrity.activeStatus(
                scope, authorityRef, attestation, attestation.seal().signedAt());
        return bundleIntegrity.bundle(scope, authorityRef, attestation, status);
    }

    MirrorDeploymentIsolationAttestation attestation(
            long revision,
            MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment,
            String networkPolicyFingerprint) {
        var enforcement = new MirrorDeploymentIsolationAttestation.EnforcementFacts(
                List.of(
                        MirrorDeploymentIsolationAttestation.EnforcementLayer
                                .KUBERNETES_NETWORK_POLICY,
                        MirrorDeploymentIsolationAttestation.EnforcementLayer.WORKLOAD_SANDBOX),
                true, true, true, true, true, true,
                networkPolicyFingerprint, fingerprint('3'), fingerprint('4'),
                List.of(MirrorDeploymentIsolationAttestation.AllowedEgressClass.DNS),
                List.of(new MirrorArtifactRef("DEPLOYMENT_POLICY_PROOF",
                        "policy-evaluation:staging", 19, fingerprint('5'))));
        var material = new MirrorDeploymentIsolationAttestation.Material(
                ATTESTATION_ID, revision, deployment, enforcement, observedAt,
                observedAt.plusSeconds(1), expiresAt, "sre:mirror-isolation");
        return attestationIntegrity.seal(material, authority);
    }

    MirrorDeploymentIsolationAuthorityKeySetPublication authorityPublication() {
        return authorityPublication(1, "");
    }

    MirrorDeploymentIsolationAuthorityKeySetPublication authorityPublication(
            long generation, String predecessor) {
        VisualEvidenceSigner.VerificationKey key = authority.key(
                authority.descriptor().activeKeyId()).orElseThrow();
        var authorityKey = new MirrorDeploymentIsolationAuthorityKeySetPublication.AuthorityKey(
                key.keyId(), key.algorithm(), key.encodedPublicKey(), base.minusSeconds(60),
                base.plusSeconds(700),
                MirrorDeploymentIsolationAuthorityKeySetPublication.AuthorityKeyState.ACTIVE);
        var material = new MirrorDeploymentIsolationAuthorityKeySetPublication.Material(
                KEY_SET_ID, generation, predecessor, scope("org-a"), deployment("cluster-a"),
                "sre:mirror-isolation", TRUST_DOMAIN, 2, POLICY, base.minusSeconds(5),
                base.plusSeconds(10), base.plusSeconds(650), List.of(authorityKey));
        return authorityIntegrity.seal(material, List.of(
                named("security-root:a", rootA), named("security-root:b", rootB)));
    }

    MirrorDeploymentIsolationAuthorityTrustPolicyProvider authorityPolicyProvider() {
        var policy = new MirrorDeploymentIsolationAuthorityTrustPolicyProvider.TrustPolicy(
                new MirrorDeploymentIsolationAuthorityKeySetIntegrity.ExpectedBinding(
                        scope("org-a"), deployment("cluster-a"), "sre:mirror-isolation",
                        KEY_SET_ID, TRUST_DOMAIN, 2, List.of(POLICY)),
                List.of(root("security-root:a", rootA), root("security-root:b", rootB)));
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

    MirrorDeploymentIsolationAttestationAdmissionPolicyProvider admissionPolicyProvider() {
        var policy = new MirrorDeploymentIsolationAttestationAdmissionPolicyProvider
                .AdmissionPolicy(scope("org-a"), deployment("cluster-a"), KEY_SET_ID,
                ATTESTATION_ID, BOOTSTRAP_REVISION);
        return new MirrorDeploymentIsolationAttestationAdmissionPolicyProvider() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public Optional<AdmissionPolicy> resolve(
                    CapabilitySnapshot.Scope scope,
                    String deploymentScopeId,
                    String keySetId,
                    String attestationId) {
                return policy.scope().equals(scope)
                        && policy.deployment().deploymentScopeId().equals(deploymentScopeId)
                        && policy.keySetId().equals(keySetId)
                        && policy.attestationId().equals(attestationId)
                        ? Optional.of(policy) : Optional.empty();
            }
        };
    }

    CapabilitySnapshot.Scope scope(String organizationId) {
        return new CapabilitySnapshot.Scope("tenant-a", organizationId, "project-a", "staging",
                "ap-southeast-1");
    }

    MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment(String clusterId) {
        return new MirrorDeploymentIsolationAttestation.DeploymentIdentity(
                "deployment:staging", clusterId, "rg-mirror", "resource-gateway", "rg-mirror",
                fingerprint('1'));
    }

    static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private MirrorDeploymentIsolationAuthorityKeySetIntegrity.RootVerificationKey root(
            String authorityId, InMemoryVisualEvidenceSigner signer) {
        VisualEvidenceSigner.VerificationKey key = signer.key(
                signer.descriptor().activeKeyId()).orElseThrow();
        return new MirrorDeploymentIsolationAuthorityKeySetIntegrity.RootVerificationKey(
                authorityId, key.keyId(), key.algorithm(), key.encodedPublicKey(),
                base.minusSeconds(60), base.plusSeconds(700),
                MirrorDeploymentIsolationAuthorityKeySetIntegrity.RootKeyState.ACTIVE);
    }

    private static MirrorDeploymentIsolationAuthorityKeySetIntegrity.NamedRootSigner named(
            String authorityId, InMemoryVisualEvidenceSigner signer) {
        return new MirrorDeploymentIsolationAuthorityKeySetIntegrity.NamedRootSigner(
                authorityId, signer);
    }
}
