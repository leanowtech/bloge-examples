package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

final class MirrorPersistenceTestFixtures {
    static final Instant COMPILED_AT = Instant.parse("2026-07-23T00:00:00Z");
    static final String PURPOSE = "MIRROR_REHEARSAL";

    private MirrorPersistenceTestFixtures() {
    }

    static CapabilitySnapshot.Scope scope(String organization) {
        return new CapabilitySnapshot.Scope(
                "tenant-a", organization, "support", "test", "sg");
    }

    static IntegrationRequestContext identity(String organization) {
        return new IntegrationRequestContext("tenant-a", organization, "support", "test", "sg",
                "SERVICE", "mirror-test-client", "", PURPOSE, "corr-mirror-test",
                Set.of("quality"), "CONFIDENTIAL", "");
    }

    static MirrorPlan plan(ObjectMapper mapper,
                           CapabilitySnapshot.Scope scope,
                           String planId,
                           char material) {
        CapabilityContract contract = new CapabilityContract("", SchemaEnvelope.opaque(),
                SchemaEnvelope.opaque(), List.of(),
                EffectContract.readOnly(List.of("resource:customers.get")),
                CapabilityContract.Determinism.CONTROLLED_NONDETERMINISTIC,
                new CapabilityContract.IdempotencyContract(
                        CapabilityContract.IdempotencyMode.IDEMPOTENT, "", true),
                null, CapabilityContract.CompatibilityPolicy.conservative(),
                new CapabilityContract.SecurityContract(
                        CapabilityContract.DataClassification.CONFIDENTIAL,
                        false, List.of("sg"), false),
                CapabilityContract.SloContract.unspecified());
        ArtifactProvenance provenance = new ArtifactProvenance("",
                ArtifactProvenance.SourceType.OWNER, List.of(), scope.tenantId(), PURPOSE,
                null, null, null, null, List.of(), "owner-a",
                COMPILED_AT.minus(Duration.ofDays(1)), null, "");
        CapabilitySnapshot child = CapabilitySnapshotIntegrity.seal(mapper,
                new CapabilitySnapshot("", "resource:customers.get", 1, "",
                        CapabilitySnapshot.Kind.EXTERNAL, scope,
                        new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.RESOURCE,
                                "customers.get", fingerprint('a')),
                        contract, new CapabilitySnapshot.RuntimeBinding(
                        "HTTP_RESOURCE", "customers.get@1", fingerprint('b'), true, List.of()),
                        List.of(), new CapabilitySnapshot.Ownership(
                        "owner-a", "support", "pager"),
                        CapabilitySnapshot.Lifecycle.ACTIVE, provenance, COMPILED_AT));
        MirrorArtifactRef childRef = CapabilityClosureIntegrity.reference(child);
        CapabilitySnapshot root = CapabilitySnapshotIntegrity.seal(mapper,
                new CapabilitySnapshot("", "graph:customer-view", 1, "",
                        CapabilitySnapshot.Kind.COMPOSED, scope,
                        new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.GRAPH,
                                "customer-view", fingerprint(material)),
                        contract, new CapabilitySnapshot.RuntimeBinding(
                        "BLOGE_GRAPH", "customer-view@1", fingerprint('c'), true, List.of()),
                        List.of(new CapabilitySnapshot.Dependency(
                                "loadCustomer", childRef, true, List.of())),
                        child.ownership(), CapabilitySnapshot.Lifecycle.ACTIVE,
                        provenance, COMPILED_AT));
        CapabilityClosure closure = CapabilityClosureIntegrity.seal(mapper,
                new CapabilityClosure("", CapabilityClosureIntegrity.reference(root),
                        List.of(root, child), ""));
        MirrorPlan.ExternalBinding binding = new MirrorPlan.ExternalBinding(
                closure.rootRef(), "loadCustomer", childRef,
                "/root/loadCustomer#RESOURCE", "/root",
                child.source().sourceKind(), child.source().sourceRef(),
                List.of(MirrorPlan.MirrorSource.ABSTAINED), List.of());
        MirrorPlan unsealed = new MirrorPlan("", planId, "", closure.rootRef(),
                closure.fingerprint(), closure.snapshots(), scope,
                new MirrorArtifactRef("FIXTURE_BUNDLE", "customer-fixture", 1,
                        fingerprint('e')),
                fingerprint(material), null, List.of(binding), null, List.of(),
                new MirrorPlan.ExecutionServices(COMPILED_AT, material, null, null),
                new MirrorPlan.ExecutionPolicy(PURPOSE, false, false, false,
                        false, false, MirrorPlan.UnmatchedResolution.ABSTAINED,
                        100, Duration.ofMinutes(5),
                        CapabilityContract.DataClassification.CONFIDENTIAL,
                        List.of("sg"), List.of(CapabilitySnapshot.Lifecycle.ACTIVE)),
                COMPILED_AT, COMPILED_AT.plus(Duration.ofHours(1)));
        return MirrorPlanIntegrity.seal(mapper, unsealed);
    }

    static MirrorEvidenceBundle evidence(ObjectMapper mapper,
                                         VisualEvidenceSigner signer,
                                         MirrorPlan plan,
                                         String runId,
                                         char semanticMaterial) {
        return evidence(mapper, signer, plan, runId, semanticMaterial,
                "request-" + runId, fingerprint('1'));
    }

    static MirrorEvidenceBundle evidence(ObjectMapper mapper,
                                         VisualEvidenceSigner signer,
                                         MirrorPlan plan,
                                         String runId,
                                         char semanticMaterial,
                                         String requestId,
                                         String contextFingerprint) {
        Instant startedAt = COMPILED_AT.plusSeconds(10);
        MirrorRunEvidence run = new MirrorRunEvidence("", runId, requestId,
                contextFingerprint, plan.planId(), plan.planFingerprint(),
                plan.capabilityClosureFingerprint(), plan.executionControlFingerprint(),
                plan.rootCapability(), plan.fixtureBundleRef(), List.of(
                new MirrorRunEvidence.ExternalBinding(plan.rootCapability(), "loadCustomer",
                        plan.externalBindings().getFirst().capabilityRef(),
                        "/root/loadCustomer#RESOURCE", "/root")), plan.scope(),
                PURPOSE, MirrorRunEvidence.Status.PASSED,
                MirrorRunEvidence.EvidenceClass.EXPLORATORY, fingerprint(semanticMaterial),
                startedAt, startedAt.plusSeconds(1), List.of(), List.of(), List.of(),
                new MirrorRunEvidence.IsolationFacts(
                        MirrorRunEvidence.IsolationFacts.EngineMode.INDEPENDENT_TEST_ENGINE,
                        List.of(), List.of("InvocationRecorder"), false, false, false,
                        false, false, false, false, null,
                        List.of("DEPLOYMENT_EGRESS_NOT_ATTESTED")),
                List.of("DEPLOYMENT_EGRESS_NOT_ATTESTED"));
        return new MirrorEvidenceIntegrityService(mapper, signer,
                Clock.fixed(startedAt.plusSeconds(2), ZoneOffset.UTC)).seal(run).bundle();
    }

    static MirrorEvidenceBundle statefulEvidence(
            ObjectMapper mapper,
            VisualEvidenceSigner signer,
            MirrorPlan plan,
            String runId,
            char semanticMaterial) {
        Instant startedAt = COMPILED_AT.plusSeconds(10);
        MirrorArtifactRef capability =
                plan.externalBindings().getFirst().capabilityRef();
        MirrorArtifactRef stateRef = new MirrorArtifactRef(
                "SESSION_STATE", "customer-session-1", 1,
                fingerprint('3'));
        MirrorArtifactRef modelRef = new MirrorArtifactRef(
                "STATE_MODEL", "customer-state", 1,
                fingerprint('4'));
        MirrorArtifactRef readSpecRef = new MirrorArtifactRef(
                "STATE_READ_SPEC", "query-customer", 1,
                fingerprint('5'));
        MirrorStateRunEvidence stateEvidence =
                MirrorStateRunEvidenceIntegrity.seal(
                        mapper, new MirrorStateRunEvidence(
                                MirrorStateRunEvidence.SCHEMA_VERSION,
                                "", runId, plan.planFingerprint(),
                                stateRef, modelRef, 0,
                                fingerprint('6'), startedAt,
                                MirrorStateRunEvidence.Mode
                                        .READ_ONLY_SNAPSHOT,
                                List.of(
                                        new MirrorStateRunEvidence
                                                .StatefulBinding(
                                                "/root/loadCustomer#RESOURCE",
                                                "/root", capability,
                                                readSpecRef)),
                                List.of(), List.of()));
        MirrorRunEvidence run = new MirrorRunEvidence(
                MirrorRunEvidence.STATEFUL_SCHEMA_VERSION,
                runId, "request-" + runId, fingerprint('1'),
                plan.planId(), plan.planFingerprint(),
                plan.capabilityClosureFingerprint(),
                plan.executionControlFingerprint(),
                plan.rootCapability(), plan.fixtureBundleRef(),
                List.of(new MirrorRunEvidence.ExternalBinding(
                        plan.rootCapability(), "loadCustomer",
                        capability,
                        "/root/loadCustomer#RESOURCE", "/root")),
                plan.scope(), PURPOSE,
                MirrorRunEvidence.Status.PASSED,
                MirrorRunEvidence.EvidenceClass.EXPLORATORY,
                fingerprint(semanticMaterial), startedAt,
                startedAt.plusSeconds(1), List.of(), List.of(),
                List.of(), stateEvidence,
                new MirrorRunEvidence.IsolationFacts(
                        MirrorRunEvidence.IsolationFacts.EngineMode
                                .INDEPENDENT_TEST_ENGINE,
                        List.of(), List.of("InvocationRecorder"),
                        false, false, false, false, false,
                        false, false, null,
                        List.of(
                                "DEPLOYMENT_EGRESS_NOT_ATTESTED")),
                List.of("DEPLOYMENT_EGRESS_NOT_ATTESTED"));
        return new MirrorEvidenceIntegrityService(
                mapper, signer,
                Clock.fixed(startedAt.plusSeconds(2),
                        ZoneOffset.UTC)).seal(run).bundle();
    }

    static MirrorEvidenceBundle certifiableEvidence(
            ObjectMapper mapper,
            VisualEvidenceSigner signer,
            MirrorPlan plan,
            String runId,
            char semanticMaterial,
            String requestId,
            String contextFingerprint,
            MirrorDeploymentIsolationRunTrust.Binding trustBinding) {
        Instant startedAt = COMPILED_AT.plusSeconds(10);
        MirrorRunEvidence run = new MirrorRunEvidence("", runId, requestId,
                contextFingerprint, plan.planId(), plan.planFingerprint(),
                plan.capabilityClosureFingerprint(), plan.executionControlFingerprint(),
                plan.rootCapability(), plan.fixtureBundleRef(), List.of(
                new MirrorRunEvidence.ExternalBinding(plan.rootCapability(), "loadCustomer",
                        plan.externalBindings().getFirst().capabilityRef(),
                        "/root/loadCustomer#RESOURCE", "/root")), plan.scope(),
                PURPOSE, MirrorRunEvidence.Status.PASSED,
                MirrorRunEvidence.EvidenceClass.CERTIFIABLE, fingerprint(semanticMaterial),
                startedAt, startedAt.plusSeconds(1), List.of(), List.of(), List.of(),
                new MirrorRunEvidence.IsolationFacts(
                        MirrorRunEvidence.IsolationFacts.EngineMode.INDEPENDENT_TEST_ENGINE,
                        List.of(), List.of("InvocationRecorder"), false, false, false,
                        false, false, false, true, trustBinding.attestationRef(), trustBinding,
                        List.of()), List.of());
        return new MirrorEvidenceIntegrityService(mapper, signer,
                Clock.fixed(startedAt.plusSeconds(3), ZoneOffset.UTC)).seal(run).bundle();
    }

    static MirrorDeploymentIsolationRunTrust.Admission trustAdmission(
            CapabilitySnapshot.Scope scope) {
        return new MirrorDeploymentIsolationRunTrust.Admission(scope,
                new MirrorArtifactRef(
                        MirrorDeploymentIsolationAttestationBundle.ARTIFACT_KIND,
                        "isolation-bundle-a", 7, fingerprint('d')),
                new MirrorArtifactRef(
                        MirrorDeploymentIsolationAuthorityKeySetPublication.ARTIFACT_KIND,
                        "isolation-authority-a", 3, fingerprint('e')),
                new MirrorArtifactRef(MirrorDeploymentIsolationAttestation.ARTIFACT_KIND,
                        "isolation-attestation-a", 5, fingerprint('f')),
                new MirrorArtifactRef(
                        MirrorDeploymentIsolationAttestationStatusPublication.ARTIFACT_KIND,
                        "isolation-attestation-a", 7, fingerprint('0')),
                new MirrorArtifactRef(MirrorDeploymentIsolationAgentSnapshot.ARTIFACT_KIND,
                        "isolation-agent-a", 11, fingerprint('1')),
                COMPILED_AT.minusSeconds(1), COMPILED_AT.plus(Duration.ofHours(1)));
    }

    static MirrorDeploymentIsolationRunTrust.Binding trustBinding(
            CapabilitySnapshot.Scope scope) {
        MirrorDeploymentIsolationRunTrust.Admission admission = trustAdmission(scope);
        return new MirrorDeploymentIsolationRunTrust.Binding("", admission.decisionRef(),
                admission.authorityKeySetRef(), admission.attestationRef(),
                admission.statusRef(), admission.admittedSnapshotRef(),
                new MirrorArtifactRef(MirrorDeploymentIsolationAgentSnapshot.ARTIFACT_KIND,
                        admission.admittedSnapshotRef().id(), 12, fingerprint('2')),
                admission.admittedAt(), COMPILED_AT.plusSeconds(12));
    }

    static String fingerprint(char material) {
        return "sha256:" + String.valueOf(material).repeat(64);
    }
}
