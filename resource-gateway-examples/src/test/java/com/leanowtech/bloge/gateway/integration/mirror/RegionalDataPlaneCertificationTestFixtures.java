package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class RegionalDataPlaneCertificationTestFixtures {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final RegionalDataPlaneCertificationIntegrity integrity =
            new RegionalDataPlaneCertificationIntegrity(mapper);
    final InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
    final Instant now = Instant.now();
    final MirrorDeploymentIsolationAttestationRepositoryTestFixtures isolationFixtures =
            new MirrorDeploymentIsolationAttestationRepositoryTestFixtures();
    final MirrorDeploymentIsolationAttestationBundle isolationV1 =
            isolationFixtures.bundle(7);
    final CapabilitySnapshot.Scope scope = isolationV1.scope();
    final MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment =
            isolationV1.attestation().material().deployment();
    final RegionalDataPlaneDeploymentContract contract = integrity.address(
            new RegionalDataPlaneCertificationIntegrity.ContractMaterial(
                    "regional-data-plane:sg", 4, scope, "ap-southeast-1", deployment,
                    requirements(), new RegionalDataPlaneDeploymentContract.RotationPolicy(
                    7_776_000, 7_776_000, 600, true, true),
                    now.minusSeconds(60), now.plusSeconds(86_400), "security:mirror-platform"));
    final RegionalDataPlaneCertification certification = certification(
            observations(now.minusSeconds(2), null), rotations(now.minusSeconds(3), null), 0, 0);
    final MirrorDeploymentIsolationAttestationBundle isolationV2 =
            isolationFixtures.bundleIntegrity.bundle(isolationV1.scope(),
                    isolationV1.authorityKeySetRef(), isolationV1.attestation(),
                    isolationV1.status(), certification.artifactRef());
    final RegionalDataPlaneCertificationIntegrity.AuthorityKey authorityKey = authorityKey();

    RegionalDataPlaneCertification certification(
            List<RegionalDataPlaneCertification.ComponentObservation> components,
            List<RegionalDataPlaneCertification.RotationObservation> rotations,
            long writeAttempts,
            long writeEscapes) {
        return integrity.seal(new RegionalDataPlaneCertificationIntegrity.CertificationMaterial(
                "regional-certification:sg", 11, contract.artifactRef(), scope,
                contract.region(), deployment, now.minusSeconds(1), now, now.plusSeconds(300),
                components, rotations, writeAttempts, writeEscapes,
                "security:regional-certification",
                List.of(ref("REGIONAL_CERTIFICATION_REPORT", "report", 9, 'f'))), signer);
    }

    List<RegionalDataPlaneCertification.ComponentObservation> observations(
            Instant observedAt,
            RegionalDataPlaneDeploymentContract.ComponentKind degraded) {
        List<RegionalDataPlaneCertification.ComponentObservation> values = new ArrayList<>();
        for (var requirement : contract == null ? requirements() : contract.requiredComponents()) {
            long generation = requirement.kind()
                    == RegionalDataPlaneDeploymentContract.ComponentKind.EVIDENCE_KMS
                    || requirement.kind()
                    == RegionalDataPlaneDeploymentContract.ComponentKind.MUTUAL_TLS ? 2 : 1;
            values.add(new RegionalDataPlaneCertification.ComponentObservation(
                    requirement.kind(), requirement.authorityId(), requirement.policyRef(),
                    generation, requirement.kind() == degraded
                    ? RegionalDataPlaneCertification.ComponentStatus.DEGRADED
                    : RegionalDataPlaneCertification.ComponentStatus.READY,
                    observedAt, true, true, true, true,
                    fingerprint((char) ('1' + requirement.kind().ordinal())),
                    List.of(ref("REGIONAL_COMPONENT_PROOF",
                            requirement.kind().name().toLowerCase(), generation,
                            (char) ('1' + requirement.kind().ordinal())))));
        }
        return List.copyOf(values);
    }

    List<RegionalDataPlaneCertification.RotationObservation> rotations(
            Instant observedAt, RegionalDataPlaneCertification.RotationKind failed) {
        return List.of(
                rotation(RegionalDataPlaneCertification.RotationKind.EVIDENCE_KMS_KEY,
                        observedAt, failed),
                rotation(RegionalDataPlaneCertification.RotationKind.MUTUAL_TLS_CA,
                        observedAt, failed));
    }

    private RegionalDataPlaneCertification.RotationObservation rotation(
            RegionalDataPlaneCertification.RotationKind kind,
            Instant observedAt,
            RegionalDataPlaneCertification.RotationKind failed) {
        boolean converged = kind != failed;
        return new RegionalDataPlaneCertification.RotationObservation(kind, 1, 2,
                observedAt.minusSeconds(30), 600,
                converged, converged, converged, converged, observedAt,
                List.of(ref("REGIONAL_ROTATION_PROOF", kind.name().toLowerCase(), 2,
                        kind == RegionalDataPlaneCertification.RotationKind.EVIDENCE_KMS_KEY
                                ? '8' : '9')));
    }

    private List<RegionalDataPlaneDeploymentContract.ComponentRequirement> requirements() {
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

    private RegionalDataPlaneCertificationIntegrity.AuthorityKey authorityKey() {
        VisualEvidenceSigner.VerificationKey key = signer.key(
                signer.descriptor().activeKeyId()).orElseThrow();
        return new RegionalDataPlaneCertificationIntegrity.AuthorityKey(
                key.keyId(), key.algorithm(), key.encodedPublicKey(),
                "security:regional-certification", now.minusSeconds(60),
                now.plusSeconds(600),
                RegionalDataPlaneCertificationIntegrity.KeyState.ACTIVE);
    }

    static MirrorArtifactRef ref(
            String kind, String id, long revision, char fingerprintCharacter) {
        return new MirrorArtifactRef(kind, id, revision, fingerprint(fingerprintCharacter));
    }

    static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
