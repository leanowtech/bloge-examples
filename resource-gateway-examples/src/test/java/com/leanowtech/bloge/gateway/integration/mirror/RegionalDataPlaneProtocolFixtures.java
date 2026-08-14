package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

public final class RegionalDataPlaneProtocolFixtures {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final RegionalDataPlaneCertificationIntegrity INTEGRITY =
            new RegionalDataPlaneCertificationIntegrity(MAPPER);
    private static final Instant OBSERVED = Instant.parse("2026-08-10T00:00:00Z");
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant-demo", "org-support", "project-business-mirror", "staging", "sg");
    private static final MirrorDeploymentIsolationAttestation.DeploymentIdentity DEPLOYMENT =
            new MirrorDeploymentIsolationAttestation.DeploymentIdentity(
                    "mirror-staging-sg", "cluster-sg-01", "resource-gateway-mirror",
                    "resource-gateway", "resource-gateway-mirror",
                    fingerprint('1'));

    private RegionalDataPlaneProtocolFixtures() {
    }

    static RegionalDataPlaneDeploymentContract contract() {
        return INTEGRITY.address(new RegionalDataPlaneCertificationIntegrity.ContractMaterial(
                "regional-data-plane:sg", 4, SCOPE, "ap-southeast-1", DEPLOYMENT,
                requirements(), new RegionalDataPlaneDeploymentContract.RotationPolicy(
                7_776_000, 7_776_000, 600, true, true),
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-12-31T00:00:00Z"), "security:mirror-platform"));
    }

    static RegionalDataPlaneCertification certification() {
        RegionalDataPlaneDeploymentContract contract = contract();
        return INTEGRITY.seal(new RegionalDataPlaneCertificationIntegrity.CertificationMaterial(
                "regional-certification:sg", 11, contract.artifactRef(), SCOPE,
                contract.region(), DEPLOYMENT, OBSERVED, OBSERVED.plusSeconds(1),
                OBSERVED.plusSeconds(601), observations(contract), rotations(), 0, 0,
                "security:regional-certification",
                List.of(ref("REGIONAL_CERTIFICATION_REPORT", "report-20260810", 9, 'f'))),
                new FixedSigner());
    }

    static MirrorDeploymentIsolationAttestationBundle isolationBundle() {
        var attestationIntegrity = new MirrorDeploymentIsolationAttestationIntegrity(MAPPER);
        var enforcement = new MirrorDeploymentIsolationAttestation.EnforcementFacts(
                List.of(
                        MirrorDeploymentIsolationAttestation.EnforcementLayer
                                .KUBERNETES_NETWORK_POLICY,
                        MirrorDeploymentIsolationAttestation.EnforcementLayer
                                .SERVICE_MESH_AUTHORIZATION,
                        MirrorDeploymentIsolationAttestation.EnforcementLayer.WORKLOAD_SANDBOX),
                true, true, true, true, true, true,
                fingerprint('2'), fingerprint('3'), fingerprint('4'),
                List.of(MirrorDeploymentIsolationAttestation.AllowedEgressClass.DNS,
                        MirrorDeploymentIsolationAttestation.AllowedEgressClass.EVIDENCE_SIGNER,
                        MirrorDeploymentIsolationAttestation.AllowedEgressClass
                                .PAYLOAD_FREE_DATABASE),
                List.of(ref("DEPLOYMENT_POLICY_PROOF", "policy-evaluation:staging", 19, '5')));
        var attestation = attestationIntegrity.seal(
                new MirrorDeploymentIsolationAttestation.Material(
                        "mirror-staging-isolation", 7, DEPLOYMENT, enforcement,
                        OBSERVED.minusSeconds(1), OBSERVED, OBSERVED.plusSeconds(600),
                        "sre:mirror-isolation"), new FixedSigner());
        var bundleIntegrity = new MirrorDeploymentIsolationAttestationBundleIntegrity(
                MAPPER, attestationIntegrity);
        MirrorArtifactRef authorityRef = ref(
                MirrorDeploymentIsolationAuthorityKeySetPublication.ARTIFACT_KIND,
                "mirror-isolation-authority", 3, 'a');
        var status = bundleIntegrity.activeStatus(SCOPE, authorityRef, attestation, OBSERVED);
        return bundleIntegrity.bundle(SCOPE, authorityRef, attestation, status,
                certification().artifactRef());
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "bundle".equals(args[0])) {
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(isolationBundle()));
            return;
        }
        System.out.println("CONTRACT_BEGIN");
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(contract()));
        System.out.println("CONTRACT_END");
        System.out.println("CERTIFICATION_BEGIN");
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(certification()));
        System.out.println("CERTIFICATION_END");
        System.out.println("ISOLATION_BUNDLE_BEGIN");
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(isolationBundle()));
        System.out.println("ISOLATION_BUNDLE_END");
    }

    private static List<RegionalDataPlaneDeploymentContract.ComponentRequirement>
    requirements() {
        List<RegionalDataPlaneDeploymentContract.ComponentRequirement> values = new ArrayList<>();
        for (var kind : RegionalDataPlaneDeploymentContract.ComponentKind.values()) {
            values.add(new RegionalDataPlaneDeploymentContract.ComponentRequirement(kind,
                    "authority:" + kind.name().toLowerCase(),
                    ref("REGIONAL_COMPONENT_POLICY", kind.name().toLowerCase(), 3,
                            (char) ('1' + kind.ordinal())),
                    1, 60, true, true, true));
        }
        return List.copyOf(values);
    }

    private static List<RegionalDataPlaneCertification.ComponentObservation> observations(
            RegionalDataPlaneDeploymentContract contract) {
        List<RegionalDataPlaneCertification.ComponentObservation> values = new ArrayList<>();
        for (var requirement : contract.requiredComponents()) {
            long generation = requirement.kind()
                    == RegionalDataPlaneDeploymentContract.ComponentKind.EVIDENCE_KMS
                    || requirement.kind()
                    == RegionalDataPlaneDeploymentContract.ComponentKind.MUTUAL_TLS ? 2 : 1;
            values.add(new RegionalDataPlaneCertification.ComponentObservation(
                    requirement.kind(), requirement.authorityId(), requirement.policyRef(),
                    generation, RegionalDataPlaneCertification.ComponentStatus.READY,
                    OBSERVED.minusSeconds(1), true, true, true, true,
                    fingerprint((char) ('1' + requirement.kind().ordinal())),
                    List.of(ref("REGIONAL_COMPONENT_PROOF",
                            requirement.kind().name().toLowerCase(), generation,
                            (char) ('8' - requirement.kind().ordinal())))));
        }
        return List.copyOf(values);
    }

    private static List<RegionalDataPlaneCertification.RotationObservation> rotations() {
        return List.of(
                new RegionalDataPlaneCertification.RotationObservation(
                        RegionalDataPlaneCertification.RotationKind.EVIDENCE_KMS_KEY,
                        1, 2, OBSERVED.minusSeconds(30), 600,
                        true, true, true, true, OBSERVED.minusSeconds(2),
                        List.of(ref("REGIONAL_ROTATION_PROOF", "kms", 2, '8'))),
                new RegionalDataPlaneCertification.RotationObservation(
                        RegionalDataPlaneCertification.RotationKind.MUTUAL_TLS_CA,
                        1, 2, OBSERVED.minusSeconds(30), 600,
                        true, true, true, true, OBSERVED.minusSeconds(2),
                        List.of(ref("REGIONAL_ROTATION_PROOF", "mtls-ca", 2, '9'))));
    }

    private static MirrorArtifactRef ref(
            String kind, String id, long revision, char fingerprintCharacter) {
        return new MirrorArtifactRef(kind, id, revision, fingerprint(fingerprintCharacter));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static final class FixedSigner implements VisualEvidenceSigner {
        @Override
        public VisualRunEvidenceSeal seal(String materialFingerprint) {
            return new VisualRunEvidenceSeal("", materialFingerprint, "Ed25519",
                    "regional-fixture-key", OBSERVED,
                    Base64.getEncoder().encodeToString(new byte[64]));
        }

        @Override
        public Verification verify(
                VisualRunEvidenceSeal seal, String actualMaterialFingerprint) {
            return new Verification(false, "FIXTURE_ONLY", "No private fixture key exists.");
        }

        @Override
        public Optional<VerificationKey> key(String keyId) {
            return Optional.empty();
        }

        @Override
        public boolean available() {
            return true;
        }
    }
}
