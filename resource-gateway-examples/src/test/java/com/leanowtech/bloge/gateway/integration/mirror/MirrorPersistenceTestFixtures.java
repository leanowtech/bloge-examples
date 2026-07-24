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

    static MirrorEvidenceBundle readWriteEvidence(
            ObjectMapper mapper,
            VisualEvidenceSigner signer,
            MirrorPlan plan,
            String runId,
            char semanticMaterial) {
        MirrorRunEvidence base = statefulEvidence(
                mapper, signer, plan, runId,
                semanticMaterial).evidence();
        MirrorRunEvidence.ExternalBinding binding =
                base.externalBindings().getFirst();
        MirrorStateTransitionRunEvidence source =
                MirrorStateTransitionRunEvidenceIntegrityTest
                        .evidence();
        MirrorStateTransitionRunEvidence.StateTransition
                sourceTransition =
                source.transitions().getFirst();
        MirrorStateTransitionRunEvidence transitionEvidence =
                MirrorStateTransitionRunEvidenceIntegrity.seal(
                        mapper,
                        new MirrorStateTransitionRunEvidence(
                                MirrorStateTransitionRunEvidence
                                        .SCHEMA_VERSION,
                                "", runId,
                                plan.planFingerprint(),
                                source.sessionStateRef(),
                                source.finalSessionStateRef(),
                                source.stateModelRef(),
                                source.stateRevision(),
                                source.finalStateRevision(),
                                source.worldFingerprint(),
                                source.finalWorldFingerprint(),
                                source.logicalClock(),
                                source.finalLogicalClock(),
                                source.mode(),
                                List.of(
                                        new MirrorStateTransitionRunEvidence
                                                .StatefulBinding(
                                                binding.invocationSiteId(),
                                                binding.graphPath(),
                                                binding.capabilityRef(),
                                                MirrorStateTransitionRunEvidence
                                                        .Interaction.WRITE,
                                                null,
                                                sourceTransition
                                                        .writeEffectRef())),
                                List.of(),
                                List.of(
                                        new MirrorStateTransitionRunEvidence
                                                .StateTransition(
                                                binding.invocationSiteId(),
                                                binding.graphPath(),
                                                "", 1, 1,
                                                binding.capabilityRef(),
                                                sourceTransition
                                                        .writeEffectRef(),
                                                sourceTransition
                                                        .initialStateRef(),
                                                sourceTransition
                                                        .finalStateRef(),
                                                sourceTransition
                                                        .revisionBefore(),
                                                sourceTransition
                                                        .revisionAfter(),
                                                sourceTransition
                                                        .initialWorldFingerprint(),
                                                sourceTransition
                                                        .finalWorldFingerprint(),
                                                sourceTransition
                                                        .initialLogicalClock(),
                                                sourceTransition
                                                        .finalLogicalClock(),
                                                sourceTransition
                                                        .requestFingerprint(),
                                                sourceTransition
                                                        .idempotencyKeyFingerprint(),
                                                sourceTransition
                                                        .commandFingerprint(),
                                                sourceTransition
                                                        .receiptFingerprint(),
                                                sourceTransition
                                                        .responseFingerprint(),
                                                sourceTransition
                                                        .resultingWorldFingerprint(),
                                                sourceTransition.committedAt(),
                                                sourceTransition.replayed(),
                                                sourceTransition.events())),
                                List.of()));
        MirrorRunEvidence run = new MirrorRunEvidence(
                MirrorRunEvidence.READ_WRITE_SCHEMA_VERSION,
                base.runId(), base.requestId(),
                base.requestContextFingerprint(), base.planId(),
                base.planFingerprint(),
                base.capabilityClosureFingerprint(),
                base.executionControlFingerprint(),
                base.rootCapability(), base.fixtureBundleRef(),
                base.externalBindings(), base.scope(),
                base.authorizedPurpose(), base.status(),
                base.evidenceClass(),
                base.semanticResultFingerprint(),
                base.startedAt(), base.completedAt(),
                base.nodeTraces(), base.edgeTraces(),
                base.resolutions(), transitionEvidence,
                base.isolation(), base.limitations());
        return new MirrorEvidenceIntegrityService(
                mapper, signer,
                Clock.fixed(base.completedAt().plusSeconds(1),
                        ZoneOffset.UTC)).seal(run).bundle();
    }

    static MirrorEvidenceBundle writeOutcomeEvidence(
            ObjectMapper mapper,
            VisualEvidenceSigner signer,
            MirrorPlan plan,
            String runId,
            char semanticMaterial) {
        return writeOutcomeEvidence(
                mapper, signer, plan, runId,
                semanticMaterial, false);
    }

    static MirrorEvidenceBundle rejectedWriteOutcomeEvidence(
            ObjectMapper mapper,
            VisualEvidenceSigner signer,
            MirrorPlan plan,
            String runId,
            char semanticMaterial) {
        return writeOutcomeEvidence(
                mapper, signer, plan, runId,
                semanticMaterial, true);
    }

    private static MirrorEvidenceBundle writeOutcomeEvidence(
            ObjectMapper mapper,
            VisualEvidenceSigner signer,
            MirrorPlan plan,
            String runId,
            char semanticMaterial,
            boolean rejected) {
        MirrorRunEvidence base = readWriteEvidence(
                mapper, signer, plan, runId,
                semanticMaterial).evidence();
        MirrorStateTransitionRunEvidence transitionState =
                (MirrorStateTransitionRunEvidence)
                        base.stateEvidence();
        MirrorStateTransitionRunEvidence.StateTransition
                transition =
                transitionState.transitions().getFirst();
        MirrorStateWriteOutcomeRunEvidence.StateWriteAttempt
                attempt;
        MirrorArtifactRef terminalState;
        long terminalRevision;
        String terminalWorld;
        Instant terminalClock;
        List<String> stateLimitations;
        if (rejected) {
            String errorCode =
                    "RG.MIRROR.STATE.PRECONDITION_FAILED";
            String errorType = "MIRROR_STATE_WRITE";
            String failureFingerprint =
                    MirrorStateWriteOutcomeRunEvidenceIntegrity
                            .failureFingerprint(
                                    mapper,
                                    transition.writeEffectRef(),
                                    transition.initialStateRef(),
                                    transition.revisionBefore(),
                                    transition.initialWorldFingerprint(),
                                    transition.initialLogicalClock(),
                                    transition.requestFingerprint(),
                                    MirrorStateWriteOutcomeRunEvidence
                                            .WriteOutcome.REJECTED,
                                    MirrorStateWriteOutcomeRunEvidence
                                            .WriteStage
                                            .COMMAND_EVALUATION,
                                    MirrorStateWriteOutcomeRunEvidence
                                            .StateDisposition.UNCHANGED,
                                    false, errorCode, errorType);
            attempt = new MirrorStateWriteOutcomeRunEvidence
                    .StateWriteAttempt(
                    transition.invocationSiteId(),
                    transition.graphPath(),
                    transition.correlationKey(),
                    transition.occurrence(),
                    transition.attempt(),
                    transition.capabilityRef(),
                    transition.writeEffectRef(),
                    transition.initialStateRef(),
                    transition.revisionBefore(),
                    transition.initialWorldFingerprint(),
                    transition.initialLogicalClock(),
                    transition.requestFingerprint(),
                    MirrorStateWriteOutcomeRunEvidence
                            .WriteOutcome.REJECTED,
                    MirrorStateWriteOutcomeRunEvidence
                            .WriteStage.COMMAND_EVALUATION,
                    MirrorStateWriteOutcomeRunEvidence
                            .StateDisposition.UNCHANGED,
                    false, errorCode, errorType,
                    failureFingerprint, null);
            terminalState = transition.initialStateRef();
            terminalRevision = transition.revisionBefore();
            terminalWorld =
                    transition.initialWorldFingerprint();
            terminalClock = transition.initialLogicalClock();
            stateLimitations = List.of();
        } else {
            attempt = new MirrorStateWriteOutcomeRunEvidence
                    .StateWriteAttempt(
                    transition.invocationSiteId(),
                    transition.graphPath(),
                    transition.correlationKey(),
                    transition.occurrence(),
                    transition.attempt(),
                    transition.capabilityRef(),
                    transition.writeEffectRef(),
                    transition.initialStateRef(),
                    transition.revisionBefore(),
                    transition.initialWorldFingerprint(),
                    transition.initialLogicalClock(),
                    transition.requestFingerprint(),
                    MirrorStateWriteOutcomeRunEvidence
                            .WriteOutcome.COMMITTED,
                    MirrorStateWriteOutcomeRunEvidence
                            .WriteStage.COMPLETED,
                    MirrorStateWriteOutcomeRunEvidence
                            .StateDisposition.ADVANCED,
                    false, "", "", "",
                    transition);
            terminalState = transition.finalStateRef();
            terminalRevision = transition.revisionAfter();
            terminalWorld =
                    transition.finalWorldFingerprint();
            terminalClock = transition.finalLogicalClock();
            stateLimitations = List.of();
        }
        MirrorStateWriteOutcomeRunEvidence state =
                MirrorStateWriteOutcomeRunEvidenceIntegrity
                        .seal(
                                mapper,
                                new MirrorStateWriteOutcomeRunEvidence(
                                        MirrorStateWriteOutcomeRunEvidence
                                                .SCHEMA_VERSION,
                                        "", runId,
                                        plan.planFingerprint(),
                                        transition.initialStateRef(),
                                        terminalState,
                                        transitionState
                                                .stateModelRef(),
                                        transition.revisionBefore(),
                                        terminalRevision,
                                        transition
                                                .initialWorldFingerprint(),
                                        terminalWorld,
                                        transition
                                                .initialLogicalClock(),
                                        terminalClock,
                                        MirrorStateWriteOutcomeRunEvidence
                                                .Mode
                                                .SERIALIZABLE_READ_WRITE_OUTCOMES,
                                        transitionState
                                                .statefulBindings(),
                                        List.of(),
                                        List.of(attempt),
                                        stateLimitations));
        List<String> limitations = rejected
                ? java.util.stream.Stream.concat(
                base.limitations().stream(),
                java.util.stream.Stream.of(
                        "STATE_WRITE_REJECTED"))
                .toList()
                : base.limitations();
        MirrorRunEvidence run = new MirrorRunEvidence(
                MirrorRunEvidence
                        .WRITE_OUTCOME_SCHEMA_VERSION,
                base.runId(), base.requestId(),
                base.requestContextFingerprint(),
                base.planId(), base.planFingerprint(),
                base.capabilityClosureFingerprint(),
                base.executionControlFingerprint(),
                base.rootCapability(),
                base.fixtureBundleRef(),
                base.externalBindings(), base.scope(),
                base.authorizedPurpose(),
                rejected
                        ? MirrorRunEvidence.Status
                        .EXECUTION_FAILED
                        : base.status(),
                base.evidenceClass(),
                base.semanticResultFingerprint(),
                base.startedAt(), base.completedAt(),
                base.nodeTraces(), base.edgeTraces(),
                base.resolutions(), state,
                base.isolation(), limitations);
        return new MirrorEvidenceIntegrityService(
                mapper, signer,
                Clock.fixed(
                        base.completedAt().plusSeconds(1),
                        ZoneOffset.UTC))
                .seal(run).bundle();
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
