package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceExecutionLeaseCommitResult;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseCommitStatus;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseTransitionWitness;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.FormalEvidenceAuthorityBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.FormalEvidenceRecoveryBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetBoundAuthorityBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetAdmissionBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExistingEvidenceRecoveryResult;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExistingEvidenceRecoveryStatus;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceExecutionLeaseTransactionResult;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceExecutionLeaseTransactionAuthority;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceTransactionJournal;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceExecutionLeaseAttempt;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseRequest;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.AdmissionLifecycleMaterial;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.RevocationAuthoritySnapshot;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.DeploymentAuthorityDecision;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceFailureKind;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceKind;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolution;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolutionRequest;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ReferenceKind;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolvedEvidence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

/** Test-only builders for real Gate A evidence, never used by production code. */
final class CapabilityStudioFormalEvidenceRunTestFixtures {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant STAGE_VERIFY_TIME = Instant.parse("2026-01-01T00:08:00Z");

    private CapabilityStudioFormalEvidenceRunTestFixtures() {
    }

    static Fixture empty(Path parent) throws IOException {
        Path realParent = parent.toRealPath();
        Path fixtureParent = privateDirectory(realParent.resolve("fixture-" + UUID.randomUUID()));
        Path root = privateDirectory(fixtureParent.resolve("bundle"));
        return new Fixture(root, fixtureParent.resolve("manifest.json"), manifest());
    }

    static Fixture stageResult(Path parent) throws Exception {
        Fixture fixture = empty(parent);
        createStageResult(fixture);
        fixture.addAllInventory();
        addStageReplay(fixture);
        fixture.write();
        return fixture;
    }

    static Fixture formalInputTree(Path parent) throws Exception {
        Fixture fixture = empty(parent);
        createFormalInputTree(fixture, parent);
        fixture.addAllInventory();
        addTreeReplay(fixture);
        fixture.write();
        return fixture;
    }

    static Fixture durableWrapper(Path parent) throws Exception {
        Fixture fixture = empty(parent);
        createDurableWrapper(fixture);
        fixture.addAllInventory();
        addDurableReplay(fixture);
        fixture.write();
        return fixture;
    }

    static Fixture allAdapters(Path parent) throws Exception {
        Fixture fixture = empty(parent);
        createStageResult(fixture);
        createFormalInputTree(fixture, parent);
        createDurableWrapper(fixture);
        fixture.addAllInventory();

        // The manifest compiler treats replay order as part of the wire contract.
        addDurableReplay(fixture);
        addStageReplay(fixture);
        addTreeReplay(fixture);
        fixture.write();
        return fixture;
    }

    private static void createStageResult(Fixture fixture) throws IOException {
        Path stageResult = fixture.root().resolve("stage-result.json");
        Files.write(stageResult,
                new CapabilityStudioStageAcceptanceResultV2Builder(
                        "SAR-gate-a", 1, "contract:gate-a", "1",
                        new CapabilityStudioStageAcceptanceResultV2Builder.CandidateBuild(
                                "build:gate-a", "1", "abcdef1",
                                CapabilityStudioStageAcceptanceResultV2Builder.SourceTreeStatus.CLEAN,
                                fp('b')),
                        new CapabilityStudioStageAcceptanceResultV2Builder.ExactRef(
                                "baseline:gate-a", fp('c')),
                        new CapabilityStudioStageAcceptanceResultV2Builder.ExactRef(
                                "demo:gate-a", fp('d')),
                        fp('e'), fp('f'),
                        CapabilityStudioStageAcceptanceResultV2Builder.ExecutionWindow.completed(
                                "2026-01-01T00:00:00Z", "2026-01-01T00:05:00Z",
                                "2026-01-01T00:07:00Z")).buildBytes());
        Files.setPosixFilePermissions(stageResult,
                PosixFilePermissions.fromString("rw-------"));
    }

    private static void addStageReplay(Fixture fixture) {
        fixture.replay("stage-replay", "STAGE_ACCEPTANCE_RESULT",
                "STAGE_ACCEPTANCE_RESULT_V2", 2, "stage-result.json")
                .putObject("inputs").put("verificationInstant", STAGE_VERIFY_TIME.toString());
    }

    private static void createFormalInputTree(Fixture fixture, Path parent) throws Exception {
        Path sourceParent = privateDirectory(parent.toRealPath().resolve("source-parent-" + UUID.randomUUID()));
        Path source = CapabilityStudioFormalInputTreeTestFixtures.authorityBundle(sourceParent);
        Path subjectParent = privateDirectory(fixture.root().resolve("tree-subject"));
        Path output = subjectParent.resolve("input-wrapper");
        var snapshot = new CapabilityStudioFormalInputTreeSnapshotter().snapshot(
                CapabilityStudioFormalInputTreeSnapshotter.TreeKind.AUTHORITY_BUNDLE,
                source, CapabilityStudioFormalInputTreeTestFixtures.AUTHORITY_SEMANTIC,
                output,
                declaration(source).treeFingerprint(),
                CapabilityStudioFormalInputTreeTestFixtures.PUBLICATION_FINGERPRINT,
                CapabilityStudioFormalInputTreeTestFixtures.TRANSACTION_NONCE);
        fixture.treeFingerprint = snapshot.declaration().treeFingerprint();
        fixture.treeTransactionId = snapshot.transactionId();
    }

    private static void addTreeReplay(Fixture fixture) throws IOException {
        fixture.replay("tree-replay", "FORMAL_INPUT_TREE", "FORMAL_INPUT_TREE_V1", 1,
                "tree-subject/input-wrapper").putObject("inputs")
                .put("treeKind", "AUTHORITY_BUNDLE")
                .put("bundleSemanticFingerprint", CapabilityStudioFormalInputTreeTestFixtures.AUTHORITY_SEMANTIC)
                .put("treeFingerprint", fixture.treeFingerprint)
                .put("publicationFingerprint", CapabilityStudioFormalInputTreeTestFixtures.PUBLICATION_FINGERPRINT)
                .put("transactionId", fixture.treeTransactionId);
    }

    private static void createDurableWrapper(Fixture fixture) throws Exception {
        ObjectNode result = passResult();
        Path stage = fixture.root().resolve("durable-stage.json");
        byte[] stageBytes = result.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(stage, stageBytes);
        Files.setPosixFilePermissions(stage, PosixFilePermissions.fromString("rw-------"));
        Path durableRoot = fixture.root().resolve("durable");
        Files.createDirectory(durableRoot,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var publication = CapabilityStudioExecutionLeaseEvidencePublication.provision(
                durableRoot, fp('e'));
        Path transcript = durableRoot.resolve(publication.transcriptRelativePath());
        Provider ordinary = acceptingProvider(result);
        FormalTargetBoundAuthorityBinding formal = ordinary.formalTargetBoundAuthorityBinding();
        Provider evidence = evidenceProvider(ordinary, formal);
        ByteArrayOutputStream acceptanceOutput = new ByteArrayOutputStream();
        int acceptanceExit = CapabilityStudioStageAcceptanceCli.run(
                new String[]{stage.toString()},
                new PrintStream(acceptanceOutput, true, StandardCharsets.UTF_8), System.err,
                Instant.parse("2026-01-01T00:12:00Z"), () -> List.of(evidence),
                formal.fingerprint());
        if (acceptanceExit != 0) {
            throw new AssertionError("stage=" + acceptanceOutput.toString(StandardCharsets.UTF_8));
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int exit = CapabilityStudioExecutionLeaseEvidenceCli.run(
                new String[]{stage.toString(), transcript.toString()},
                new PrintStream(output, true, StandardCharsets.UTF_8), System.err,
                Instant.parse("2026-01-01T00:12:00Z"), () -> List.of(evidence),
                formal.fingerprint(), publication.publicationFingerprint());
        if (exit != 0) {
            throw new AssertionError(output.toString(StandardCharsets.UTF_8));
        }
        // Keep replay inputs on the fixture for addDurableReplay after inventory closure.
        fixture.durableStageFingerprint = CapabilityStudioFormalEvidenceRunManifest.sha256(stageBytes);
        fixture.durableFormalFingerprint = formal.fingerprint();
        fixture.durablePublicationFingerprint = publication.publicationFingerprint();
        fixture.durableTranscript = "durable/" + publication.transcriptRelativePath();
    }

    private static void addDurableReplay(Fixture fixture) {
        fixture.replay("durable-replay", "DURABLE_EVIDENCE_CLOSURE",
                "EXECUTION_LEASE_DURABLE_WRAPPER_V1", 1, fixture.durableTranscript).putObject("inputs")
                .put("stageResultRawFingerprint", fixture.durableStageFingerprint)
                .put("formalOuterFingerprint", fixture.durableFormalFingerprint)
                .put("publicationFingerprint", fixture.durablePublicationFingerprint);
    }

    private static ObjectNode passResult() {
        ObjectNode result = CapabilityStudioStageAcceptanceAuthorityVerifierTest.validStagePass();
        try {
            ObjectNode candidate = candidateAttestation();
            ObjectNode environment = environmentAttestation(result, candidate);
            byte[] candidateBytes = JSON.writeValueAsBytes(candidate);
            environment.with("candidateAttestation").put("fingerprint",
                    targetVerifier().rawAttestationFingerprint(candidateBytes));
            byte[] environmentBytes = JSON.writeValueAsBytes(environment);
            result.with("environmentAttestation").put("fingerprint",
                    targetVerifier().rawAttestationFingerprint(environmentBytes));
            ((ObjectNode) result.path("evidenceRefs").path(0)).put("fingerprint",
                    targetVerifier().rawAttestationFingerprint(environmentBytes));
            refreshClosure(result);
            return result;
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static CapabilityStudioStageAcceptanceTargetBindingVerifier targetVerifier() {
        return new CapabilityStudioStageAcceptanceTargetBindingVerifier();
    }

    private static FormalTargetAdmissionBinding targetAdmission(ObjectNode result) {
        try {
            ObjectNode candidate = candidateAttestation();
            ObjectNode environment = environmentAttestation(result, candidate);
            byte[] candidateBytes = JSON.writeValueAsBytes(candidate);
            environment.with("candidateAttestation").put("fingerprint",
                    targetVerifier().rawAttestationFingerprint(candidateBytes));
            byte[] environmentBytes = JSON.writeValueAsBytes(environment);
            ObjectNode target = JSON.createObjectNode()
                    .put("schemaVersion", CapabilityStudioStageAcceptanceTargetBindingVerifier.TARGET_BINDING_SCHEMA_VERSION)
                    .put("resultId", result.path("resultId").textValue())
                    .put("resultRevision", result.path("revision").intValue())
                    .put("contractId", result.path("contractId").textValue())
                    .put("contractRevision", result.path("contractRevision").textValue())
                    .put("executionLeaseId", "lease:stage-acceptance:1");
            target.putObject("candidateAttestation").put("candidateRef", candidate.path("candidateRef").textValue())
                    .put("attestationRevision", 1).put("fingerprint",
                            targetVerifier().rawAttestationFingerprint(candidateBytes));
            target.putObject("environmentAttestation").put("environmentRef", environment.path("environmentRef").textValue())
                    .put("attestationRevision", 1).put("fingerprint",
                            targetVerifier().rawAttestationFingerprint(environmentBytes));
            target.putArray("trustedTargetIdentities").add("runtime:capability-studio");
            target.put("fingerprint", fp('0'));
            target.put("fingerprint", targetVerifier().targetBindingFingerprint(target));
            byte[] targetBytes = JSON.writeValueAsBytes(target);
            var context = new CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationContext(
                    "lease:stage-acceptance:1", Set.of("runtime:capability-studio"),
                    target.path("fingerprint").textValue());
            var checked = targetVerifier().verify(JSON.writeValueAsBytes(result), targetBytes,
                    candidateBytes, environmentBytes, context,
                    Instant.parse("2026-01-01T00:12:00Z"),
                    facts -> CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision.verified(),
                    facts -> CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision.verified());
            if (!checked.verified()) {
                throw new AssertionError(checked.reasonCode());
            }
            return new FormalTargetAdmissionBinding(
                    fp('9'), targetVerifier().rawAttestationFingerprint(targetBytes),
                    target.path("fingerprint").textValue(), targetBytes, candidateBytes,
                    environmentBytes, context,
                    facts -> CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision.verified(),
                    facts -> CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision.verified(), lifecycleMaterial(),
                    new CapabilityStudioStageAcceptanceAuthorityProvider.DeploymentAdmissionAuthorityBinding(
                            new CapabilityStudioStageAcceptanceAuthorityProvider.TrustedVerificationClockBinding(
                                    fp('7'), () -> Instant.parse("2026-01-01T00:12:00Z")),
                            new CapabilityStudioStageAcceptanceAuthorityProvider.AdmissionLifecycleAuthorityBinding(
                                    fp('8'), request -> DeploymentAuthorityDecision.verified("LIFECYCLE")),
                            new CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseAuthorityBinding(
                                    fp('9'), CapabilityStudioFormalEvidenceRunTestFixtures::committedLease)));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static ObjectNode candidateAttestation() {
        ObjectNode candidate = JSON.createObjectNode()
                .put("schemaVersion", CapabilityStudioStageAcceptanceTargetBindingVerifier.CANDIDATE_ATTESTATION_SCHEMA_VERSION)
                .put("candidateRef", "candidate:capability-studio:2026-01").put("attestationRevision", 1)
                .put("role", "CANDIDATE_AUTHORITY").put("buildRef", "build:capability-studio")
                .put("revision", "rev-2").put("sourceCommit", "abcdef1234567")
                .put("sourceTreeStatus", "CLEAN").put("artifactDigest", fp('5'))
                .put("executionIntentFingerprint", fp('4')).put("scope", "tenant:demo/environment:acceptance")
                .put("issuer", "issuer:candidate-authority").put("issuedAt", "2026-01-01T00:00:00Z")
                .put("expiresAt", "2026-01-01T00:30:00Z");
        reference(candidate, "baselineRef", "baseline:capability-studio:v2", '1');
        reference(candidate, "demoPackRef", "demo-pack:capability-studio:v2", '2');
        return candidate;
    }

    private static ObjectNode environmentAttestation(ObjectNode result, ObjectNode candidate) {
        ObjectNode environment = JSON.createObjectNode()
                .put("schemaVersion", CapabilityStudioStageAcceptanceTargetBindingVerifier.ENVIRONMENT_ATTESTATION_SCHEMA_VERSION)
                .put("environmentRef", result.path("environmentAttestation").path("exactRef").textValue())
                .put("attestationRevision", 1).put("role", "ENVIRONMENT_AUTHORITY")
                .put("executionLeaseId", "lease:stage-acceptance:1").put("environmentFingerprint", fp('3'))
                .put("targetProfile", "capability-studio:stage-acceptance")
                .put("scope", "tenant:demo/environment:acceptance").put("region", "region:sg1")
                .put("runtimeIdentity", "runtime:capability-studio")
                .put("networkPolicy", "network-policy:deny-external-v1")
                .put("logicalClock", "2026-01-01T00:00:00Z").put("issuer", "issuer:deployment-control-plane")
                .put("issuedAt", "2026-01-01T00:00:00Z").put("expiresAt", "2026-01-01T00:30:00Z")
                ;
        environment.putObject("candidateAttestation").put("candidateRef", candidate.path("candidateRef").textValue())
                .put("attestationRevision", 1).put("fingerprint", fp('0'));
        environment.putObject("admissionWindow").put("from", "2026-01-01T00:00:00Z")
                .put("through", "2026-01-01T00:30:00Z");
        environment.putArray("trustedTargetIdentities").add("runtime:capability-studio");
        reference(environment, "featureFlagsRef", "feature-flags:capability-studio:v1", '6');
        return environment;
    }

    private static void reference(ObjectNode parent, String field, String exactRef, char seed) {
        parent.set(field, JSON.createObjectNode().put("exactRef", exactRef)
                .put("fingerprint", fp(seed)));
    }

    private static Provider acceptingProvider(ObjectNode result) {
        FormalTargetAdmissionBinding admission = targetAdmission(result);
        String closure = result.path("evidenceClosureFingerprint").textValue();
        return new Provider(request -> EvidenceResolution.available(facts(request, closure)),
                (reference, evidence, context) ->
                        CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision.verified(),
                (signoff, signature, context) ->
                        CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision.verified(),
                admission, false, null);
    }

    private static Provider evidenceProvider(Provider ordinary, FormalTargetBoundAuthorityBinding formal) {
        AtomicReference<EvidenceExecutionLeaseCommitResult> durable = new AtomicReference<>();
        AtomicReference<CapabilityStudioDeploymentStateObservation.Observation> before = new AtomicReference<>();
        AtomicReference<CapabilityStudioDeploymentStateObservation.Observation> after = new AtomicReference<>();
        return new Provider(ordinary.resolver(), ordinary.issuer(), ordinary.owner(),
                formal.targetAdmissionBinding(), true, formal, durable, before, after);
    }

    private record Provider(
            CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver resolver,
            CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy issuer,
            CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority owner,
            FormalTargetAdmissionBinding targetAdmission,
            boolean evidence,
            FormalTargetBoundAuthorityBinding formal,
            AtomicReference<EvidenceExecutionLeaseCommitResult> durable,
            AtomicReference<CapabilityStudioDeploymentStateObservation.Observation> before,
            AtomicReference<CapabilityStudioDeploymentStateObservation.Observation> after)
            implements CapabilityStudioStageAcceptanceAuthorityProvider {
        private Provider(
                CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver resolver,
                CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy issuer,
                CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority owner,
                FormalTargetAdmissionBinding targetAdmission,
                boolean evidence,
                FormalTargetBoundAuthorityBinding formal) {
            this(resolver, issuer, owner, targetAdmission, evidence, formal,
                    new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
        }

        @Override
        public AuthorityBinding authorityBinding() {
            return new AuthorityBinding(fp('a'), resolver, issuer, owner);
        }

        @Override
        public FormalTargetBoundAuthorityBinding formalTargetBoundAuthorityBinding() {
            return formal == null ? new FormalTargetBoundAuthorityBinding(
                    authorityBinding(), targetAdmission) : formal;
        }

        @Override
        public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver evidenceResolver() {
            return resolver;
        }

        @Override
        public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy evidenceIssuerPolicy() {
            return issuer;
        }

        @Override
        public CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority ownerAuthority() {
            return owner;
        }

        @Override
        public FormalEvidenceAuthorityBinding formalEvidenceAuthorityBinding() {
            if (!evidence) {
                return null;
            }
            return new FormalEvidenceAuthorityBinding(formalTargetBoundAuthorityBinding(), fp('6'),
                    (phase, transactionId) -> observation(phase, transactionId,
                            phase == CapabilityStudioDeploymentStateObservation.Phase.AFTER),
                    new EvidenceExecutionLeaseTransactionAuthority() {
                        @Override
                        public EvidenceExecutionLeaseTransactionResult commit(
                                EvidenceExecutionLeaseAttempt attempt,
                                EvidenceTransactionJournal journal) {
                            var beforeObservation = journal.prepareBefore(attempt,
                                    observation(CapabilityStudioDeploymentStateObservation.Phase.BEFORE,
                                            attempt.evidenceTransactionId(), false));
                            var lease = committedEvidenceLease(attempt.request());
                            var afterObservation = observation(
                                    CapabilityStudioDeploymentStateObservation.Phase.AFTER,
                                    attempt.evidenceTransactionId(), true);
                            durable.set(lease);
                            before.set(beforeObservation);
                            after.set(afterObservation);
                            journal.persistCommitted(attempt, beforeObservation, afterObservation, lease);
                            return new EvidenceExecutionLeaseTransactionResult(
                                    beforeObservation, afterObservation, lease);
                        }

                        @Override
                        public EvidenceExecutionLeaseCommitResult recoverExisting(
                                ExecutionLeaseRequest request) {
                            return new EvidenceExecutionLeaseCommitResult(
                                    ExecutionLeaseCommitStatus.UNAVAILABLE, null, null,
                                    "LEASE_UNAVAILABLE");
                        }
                    });
        }

        @Override
        public FormalEvidenceRecoveryBinding formalEvidenceRecoveryBinding() {
            if (!evidence) {
                return null;
            }
            return new FormalEvidenceRecoveryBinding(fp('6'),
                    (phase, transactionId) -> phase
                            == CapabilityStudioDeploymentStateObservation.Phase.BEFORE
                            ? before.get() : after.get(),
                    (attempt, journal) -> {
                        EvidenceExecutionLeaseCommitResult value = durable.get();
                        if (value == null) {
                            return new ExistingEvidenceRecoveryResult(
                                    ExistingEvidenceRecoveryStatus.ABSENT, null, null,
                                    "LEASE_ABSENT");
                        }
                        boolean exact = value.receipt().requestFingerprint().equals(
                                attempt.request().commitIdentityFingerprint());
                        return new ExistingEvidenceRecoveryResult(
                                exact ? ExistingEvidenceRecoveryStatus.FOUND
                                        : ExistingEvidenceRecoveryStatus.CONFLICT,
                                exact ? value.receipt() : null,
                                exact ? value.transitionWitness() : null,
                                exact ? before.get() : null, exact ? after.get() : null,
                                exact ? "LEASE_RECOVERED" : "LEASE_CONFLICT");
                    });
        }
    }

    private static ResolvedEvidence facts(ResolutionRequest request, String closure) {
        if (request.kind() == ReferenceKind.SIGNATURE) {
            return new ResolvedEvidence(request.coordinate(), EvidenceKind.OWNER_SIGNATURE,
                    "issuer:owner-authority", "tenant:demo/environment:acceptance",
                    fp('5'), fp('4'), fp('3'), null, null, closure,
                    "key:owner:1", "Ed25519", fp('6'),
                    Instant.parse("2026-01-01T00:07:00Z"),
                    Instant.parse("2026-01-01T00:30:00Z"), "c2lnbmF0b3Jl");
        }
        if (request.key().equals("environment")) {
            return new ResolvedEvidence(request.coordinate(), EvidenceKind.ENVIRONMENT_ATTESTATION,
                    "issuer:deployment-control-plane", "tenant:demo/environment:acceptance",
                    fp('5'), null, fp('3'),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-01T00:30:00Z"), null,
                    "key:environment:1", "Ed25519", fp('6'),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-01T00:30:00Z"), "c2lnbmF0dXJl");
        }
        if (request.key().equals("egress")) {
            return new ResolvedEvidence(request.coordinate(),
                    EvidenceKind.DEPLOYMENT_EGRESS_OBSERVATION,
                    "issuer:network-observer", "tenant:demo/environment:acceptance",
                    null, fp('4'), null,
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-01T00:05:00Z"), null,
                    "key:egress:1", "Ed25519", fp('6'),
                    Instant.parse("2026-01-01T00:05:00Z"),
                    Instant.parse("2026-01-01T00:30:00Z"), "c2lnbmF0dXJl");
        }
        return new ResolvedEvidence(request.coordinate(), EvidenceKind.ACCEPTANCE_EVIDENCE,
                "issuer:acceptance-evidence", "tenant:demo/environment:acceptance",
                fp('5'), fp('4'), fp('3'),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:05:00Z"), closure,
                "key:acceptance:1", "Ed25519", fp('6'),
                Instant.parse("2026-01-01T00:05:00Z"),
                Instant.parse("2026-01-01T00:30:00Z"), "c2lnbmF0dXJl");
    }

    private static AdmissionLifecycleMaterial lifecycleMaterial() {
        return new AdmissionLifecycleMaterial(fp('9'), "bundle:stage-acceptance", 1, "ACTIVE", null,
                new RevocationAuthoritySnapshot("registry:stage-acceptance", 1, fp('8'),
                        Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:30:00Z")));
    }

    private static CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseCommitResult
            committedLease(ExecutionLeaseRequest request) {
        var revocation = request.lifecycleMaterial().revocationAuthority();
        var atomic = new CapabilityStudioStageAcceptanceAuthorityProvider.AtomicAdmissionLifecycleCommitReceipt(
                request.deploymentAdmissionAuthorityMaterialFingerprint(), request.lifecycleMaterial().fingerprint(),
                revocation.registryRef(), revocation.revision(), revocation.snapshotFingerprint(), 1,
                Instant.parse("2026-01-01T00:12:00Z"), request.commitIdentityFingerprint());
        var receipt = new CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseReceipt(
                request.commitIdentityFingerprint(), request.lifecycleMaterial(), atomic);
        return CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseCommitResult
                .committed(receipt, "LEASE_COMMITTED");
    }

    private static EvidenceExecutionLeaseCommitResult committedEvidenceLease(
            ExecutionLeaseRequest request) {
        var receipt = committedLease(request).receipt();
        var witness = new ExecutionLeaseTransitionWitness(fp('6'), request.commitIdentityFingerprint(),
                receipt.fingerprint(), fp('1'), 0, 0, fp('2'), 0, fp('3'), fp('7'), fp('4'), 1, 1,
                fp('5'), 0, fp('3'));
        return new EvidenceExecutionLeaseCommitResult(ExecutionLeaseCommitStatus.COMMITTED,
                receipt, witness, "LEASE_COMMITTED");
    }

    private static CapabilityStudioDeploymentStateObservation.Observation observation(
            CapabilityStudioDeploymentStateObservation.Phase phase,
            String transactionId, boolean committed) {
        boolean post = committed || phase == CapabilityStudioDeploymentStateObservation.Phase.AFTER;
        return CapabilityStudioDeploymentStateObservation.create(phase, transactionId,
                fp('6'), fp('e'), post ? 1 : 0, post ? fp('1') : null,
                post ? fp('4') : fp('1'), fp('a'), post ? fp('5') : fp('2'), fp('b'),
                0, fp('3'), fp('c'), post ? lifecycleMaterial().fingerprint() : null,
                post ? 1 : 0, post ? 1 : 0, fp('d'));
    }

    private static void refreshClosure(ObjectNode result) {
        String closure = CapabilityStudioStageAcceptanceResultV2Verifier.closureFingerprint(result);
        result.put("evidenceClosureFingerprint", closure);
        for (JsonNode signoff : result.path("signoffs")) {
            ((ObjectNode) signoff).put("evidenceClosureFingerprint", closure);
        }
    }

    private static CapabilityStudioFormalInputTreeSnapshotter.Declaration declaration(Path source)
            throws IOException {
        List<CapabilityStudioFormalInputTreeSnapshotter.TreeEntry> entries;
        try (var stream = Files.list(source)) {
            entries = stream.sorted().map(path -> {
                try {
                    byte[] bytes = Files.readAllBytes(path);
                    return new CapabilityStudioFormalInputTreeSnapshotter.TreeEntry(
                            path.getFileName().toString(), bytes.length,
                            CapabilityStudioFormalEvidenceRunManifest.sha256(bytes));
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }).toList();
        }
        return CapabilityStudioFormalInputTreeSnapshotter.createDeclaration(
                CapabilityStudioFormalInputTreeSnapshotter.TreeKind.AUTHORITY_BUNDLE,
                CapabilityStudioFormalInputTreeTestFixtures.AUTHORITY_SEMANTIC, entries);
    }

    private static ObjectNode manifest() {
        ObjectNode node = JSON.createObjectNode();
        node.put("contractId", CapabilityStudioFormalEvidenceRunManifest.CONTRACT_ID)
                .put("runId", fp('0')).put("candidatePinFingerprint", fp('1'))
                .put("inputPinFingerprint", fp('2')).put("environmentPinFingerprint", fp('3'));
        node.putObject("executionWindow").put("startedAt", "2026-01-01T00:00:00Z")
                .put("endedAt", "2026-01-01T00:01:00Z");
        node.putObject("independentReview").put("reviewerFingerprint", fp('4'))
                .put("reviewedAt", "2026-01-01T00:02:00Z").put("reviewFingerprint", fp('5'));
        ArrayNode obligations = node.putArray("obligations");
        for (String id : CapabilityStudioFormalEvidenceRunManifest.OBLIGATION_IDS) {
            obligations.addObject().put("id", id).put("status", "NOT_RUN").putArray("evidencePaths");
        }
        node.put("openP0", 1).put("openP1", 1).put("passed", 0).put("failed", 0)
                .put("blocked", 0).put("notRun", 14).put("verificationLevel", "INCOMPLETE")
                .put("formalPassCount", 0).put("formalExpectedCount", 27)
                .put("evidenceCount", 0).put("evidenceByteSize", 0);
        node.putArray("evidenceInventory");
        node.put("inventoryClosureFingerprint", fp('6'));
        node.putArray("typedEvidenceReplays");
        node.putNull("manifestFingerprint");
        return node;
    }

    private static Path privateDirectory(Path path) throws IOException {
        Files.createDirectory(path);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        return path;
    }

    static String fp(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    static final class Fixture {
        private final Path root;
        private final Path manifest;
        private final ObjectNode node;
        private String treeFingerprint;
        private String treeTransactionId;
        private String durableStageFingerprint;
        private String durableFormalFingerprint;
        private String durablePublicationFingerprint;
        private String durableTranscript;

        Fixture(Path root, Path manifest, ObjectNode node) {
            this.root = root;
            this.manifest = manifest;
            this.node = node;
        }

        Path root() {
            return root;
        }

        Path manifest() {
            return manifest;
        }

        ObjectNode manifestNode() {
            return node;
        }

        void addAllInventory() throws IOException {
            ArrayNode inventory = node.withArray("evidenceInventory");
            inventory.removeAll();
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile).sorted().forEach(path -> {
                    try {
                        byte[] bytes = Files.readAllBytes(path);
                        inventory.addObject().put("relativePath", root.relativize(path).toString())
                                .put("byteSize", bytes.length)
                                .put("rawFingerprint", CapabilityStudioFormalEvidenceRunManifest.sha256(bytes));
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                });
            }
        }

        ObjectNode replay(String id, String role, String kind, int revision, String subject) {
            return node.withArray("typedEvidenceReplays").addObject()
                    .put("id", id).put("role", role).put("kind", kind)
                    .put("verifierId", verifierId(kind)).put("verifierRevision", revision)
                    .put("subjectPath", subject);
        }

        void write() throws IOException {
            ArrayNode inventory = node.withArray("evidenceInventory");
            long bytes = 0;
            for (var item : inventory) {
                bytes += item.path("byteSize").longValue();
            }
            node.put("evidenceCount", inventory.size()).put("evidenceByteSize", bytes)
                    .put("inventoryClosureFingerprint",
                            CapabilityStudioFormalEvidenceRunManifest.canonicalFingerprint(inventory));
            node.put("verificationLevel", node.withArray("typedEvidenceReplays").isEmpty()
                    ? "INCOMPLETE" : "STRUCTURE_VERIFIED");
            node.putNull("manifestFingerprint");
            node.put("manifestFingerprint",
                    CapabilityStudioFormalEvidenceRunManifest.canonicalFingerprint(node));
            Files.write(manifest, CapabilityStudioFormalEvidenceRunManifest.canonicalBytes(node));
            Files.setPosixFilePermissions(manifest,
                    PosixFilePermissions.fromString("rw-------"));
        }

        private static String verifierId(String kind) {
            return switch (kind) {
                case "FORMAL_INPUT_TREE_V1" ->
                        CapabilityStudioTypedEvidenceReplayRegistry.FORMAL_INPUT_TREE_VERIFIER;
                case "EXECUTION_LEASE_DURABLE_WRAPPER_V1" ->
                        CapabilityStudioTypedEvidenceReplayRegistry.DURABLE_WRAPPER_VERIFIER;
                case "STAGE_ACCEPTANCE_RESULT_V2" ->
                        CapabilityStudioTypedEvidenceReplayRegistry.STAGE_RESULT_VERIFIER;
                default -> "placeholder";
            };
        }
    }
}
