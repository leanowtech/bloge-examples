package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.AcceptanceContext;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceKind;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolutionRequest;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolvedEvidence;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioStageAcceptanceAuthorityVerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-01-01T00:12:00Z");
    private static final CapabilityStudioStageAcceptanceAuthorityVerifier VERIFIER =
            new CapabilityStudioStageAcceptanceAuthorityVerifier();

    @Test
    void acceptsOnlyAfterEveryEvidenceAndOwnerAuthorityVerifies() {
        ObjectNode input = validStagePass();
        List<String> calls = new ArrayList<>();

        CapabilityStudioStageAcceptanceAuthorityVerifier.VerificationResult result = verify(
                input, calls, request -> available(request, input),
                (reference, evidence, context) -> verified(),
                (signoff, signature, context) -> verified());

        assertThat(result.outcome())
                .isEqualTo(CapabilityStudioStageAcceptanceAuthorityVerifier.Outcome.ACCEPTED);
        assertThat(result.reasonCode()).isEqualTo(code("ACCEPTED"));
        assertThat(calls).containsExactly(
                "EVIDENCE:check-1", "EVIDENCE:check-2", "EVIDENCE:check-3",
                "EVIDENCE:check-4", "EVIDENCE:check-5", "EVIDENCE:check-6",
                "EVIDENCE:check-7", "EVIDENCE:check-8", "EVIDENCE:check-9",
                "EVIDENCE:egress", "EVIDENCE:environment",
                "SIGNATURE:CORRECTNESS_OWNER", "SIGNATURE:QA_OWNER", "SIGNATURE:RUNTIME_OWNER");
    }

    @Test
    void protocolInvalidMakesZeroExternalCalls() {
        List<String> calls = new ArrayList<>();
        ObjectNode invalid = validStagePass().put("status", "PARTIAL");

        var result = verify(invalid, calls, request -> available(request, invalid),
                (reference, evidence, context) -> verified(),
                (signoff, signature, context) -> verified());

        assertThat(result.outcome()).isEqualTo(
                CapabilityStudioStageAcceptanceAuthorityVerifier.Outcome.PROTOCOL_INVALID);
        assertThat(calls).isEmpty();
    }

    @Test
    void nonPassMakesZeroExternalCalls() {
        ObjectNode nonPass = validStagePass().put("status", "FAIL");
        check(nonPass, "AC-STD-01").put("status", "FAIL");
        replaceDiagnostics(nonPass, "ACCEPTANCE_CHECK_FAILED");
        refreshClosure(nonPass);
        List<String> calls = new ArrayList<>();

        var result = verify(nonPass, calls, request -> available(request, nonPass),
                (reference, evidence, context) -> verified(),
                (signoff, signature, context) -> verified());

        assertThat(result.outcome()).isEqualTo(
                CapabilityStudioStageAcceptanceAuthorityVerifier.Outcome.NOT_ACCEPTED);
        assertThat(result.reasonCode()).isEqualTo(code("STATUS_NOT_PASS"));
        assertThat(calls).isEmpty();
    }

    @Test
    void evidenceNotFoundAndUnavailableMapToRejectedAndBlocked() {
        for (var expected : List.of(
                CapabilityStudioStageAcceptanceAuthorityVerifier.Outcome.REJECTED,
                CapabilityStudioStageAcceptanceAuthorityVerifier.Outcome.BLOCKED)) {
            ObjectNode input = validStagePass();
            var result = verify(input, new ArrayList<>(), request ->
                    request.key().equals("check-1")
                            ? expected == CapabilityStudioStageAcceptanceAuthorityVerifier.Outcome.REJECTED
                            ? CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolution.notFound()
                            : CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolution.unavailable()
                            : available(request, input),
                    (reference, evidence, context) -> verified(),
                    (signoff, signature, context) -> verified());

            assertThat(result.outcome()).isEqualTo(expected);
        }
    }

    @Test
    void wrongResolvedCoordinateIsRejectedWithoutCallingIssuerPolicy() {
        ObjectNode input = validStagePass();
        List<String> issuerCalls = new ArrayList<>();
        var result = verify(input, new ArrayList<>(), request ->
                        available(coordinate('f')),
                (reference, evidence, context) -> {
                    issuerCalls.add(reference.evidenceId());
                    return verified();
                }, (signoff, signature, context) -> verified());

        assertThat(result.outcome()).isEqualTo(
                CapabilityStudioStageAcceptanceAuthorityVerifier.Outcome.REJECTED);
        assertThat(result.reasonCode()).isEqualTo(code("EVIDENCE_COORDINATE_MISMATCH"));
        assertThat(issuerCalls).isEmpty();
    }

    @Test
    void issuerRejectAndUnavailableMapToRejectedAndBlocked() {
        ObjectNode rejectedInput = validStagePass();
        var rejected = verify(rejectedInput, new ArrayList<>(),
                request -> available(request, rejectedInput),
                (reference, evidence, context) -> CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision.rejected("ISSUER_REJECTED"),
                (signoff, signature, context) -> verified());
        ObjectNode unavailableInput = validStagePass();
        var unavailable = verify(unavailableInput, new ArrayList<>(),
                request -> available(request, unavailableInput),
                (reference, evidence, context) -> CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision.unavailable("ISSUER_UNAVAILABLE"),
                (signoff, signature, context) -> verified());

        assertThat(rejected.outcome()).isEqualTo(
                CapabilityStudioStageAcceptanceAuthorityVerifier.Outcome.REJECTED);
        assertThat(unavailable.outcome()).isEqualTo(
                CapabilityStudioStageAcceptanceAuthorityVerifier.Outcome.BLOCKED);
    }

    @Test
    void ownerRejectAndUnavailableMapToRejectedAndBlocked() {
        ObjectNode rejectedInput = validStagePass();
        var rejected = verify(rejectedInput, new ArrayList<>(),
                request -> available(request, rejectedInput),
                (reference, evidence, context) -> verified(),
                (signoff, signature, context) -> CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision.rejected("OWNER_REJECTED"));
        ObjectNode unavailableInput = validStagePass();
        var unavailable = verify(unavailableInput, new ArrayList<>(),
                request -> available(request, unavailableInput),
                (reference, evidence, context) -> verified(),
                (signoff, signature, context) -> CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision.unavailable("OWNER_UNAVAILABLE"));

        assertThat(rejected.outcome()).isEqualTo(
                CapabilityStudioStageAcceptanceAuthorityVerifier.Outcome.REJECTED);
        assertThat(unavailable.outcome()).isEqualTo(
                CapabilityStudioStageAcceptanceAuthorityVerifier.Outcome.BLOCKED);
    }

    @Test
    void wrongSignatureCoordinateIsRejectedBeforeOwnerAuthority() {
        ObjectNode input = validStagePass();
        List<String> ownerCalls = new ArrayList<>();
        var result = verify(input, new ArrayList<>(), request ->
                        request.kind() == CapabilityStudioStageAcceptanceAuthorityVerifier.ReferenceKind.SIGNATURE
                                ? available(coordinate('f')) : available(request, input),
                (reference, evidence, context) -> verified(), (signoff, signature, context) -> {
                    ownerCalls.add(signoff.role());
                    return verified();
                });

        assertThat(result.outcome()).isEqualTo(
                CapabilityStudioStageAcceptanceAuthorityVerifier.Outcome.REJECTED);
        assertThat(result.reasonCode()).isEqualTo(code("SIGNATURE_COORDINATE_MISMATCH"));
        assertThat(ownerCalls).isEmpty();
    }

    @Test
    void resolvesEveryReferenceInStableOrderEvenWhenWireArraysAreShuffled() {
        ObjectNode input = validStagePass();
        ArrayNode evidence = (ArrayNode) input.path("evidenceRefs");
        JsonNode last = evidence.remove(0);
        evidence.add(last);
        ArrayNode signoffs = (ArrayNode) input.path("signoffs");
        JsonNode first = signoffs.remove(0);
        signoffs.add(first);
        List<String> calls = new ArrayList<>();

        verify(input, calls, request -> available(request, input),
                (reference, resolved, context) -> verified(),
                (signoff, resolved, context) -> verified());

        assertThat(calls).startsWith("EVIDENCE:check-1", "EVIDENCE:check-2");
        assertThat(calls).containsSubsequence(
                "SIGNATURE:CORRECTNESS_OWNER", "SIGNATURE:QA_OWNER", "SIGNATURE:RUNTIME_OWNER");
        assertThat(calls).containsExactly(
                "EVIDENCE:check-1", "EVIDENCE:check-2", "EVIDENCE:check-3",
                "EVIDENCE:check-4", "EVIDENCE:check-5", "EVIDENCE:check-6",
                "EVIDENCE:check-7", "EVIDENCE:check-8", "EVIDENCE:check-9",
                "EVIDENCE:egress", "EVIDENCE:environment",
                "SIGNATURE:CORRECTNESS_OWNER", "SIGNATURE:QA_OWNER", "SIGNATURE:RUNTIME_OWNER");
    }

    @Test
    void everyPolicyReceivesTheSameCompleteAcceptanceContext() {
        ObjectNode input = validStagePass();
        List<AcceptanceContext> contexts = new ArrayList<>();

        var result = verify(input, new ArrayList<>(), request -> available(request, input),
                (reference, evidence, context) -> {
                    contexts.add(context);
                    return verified();
                }, (signoff, signature, context) -> {
                    contexts.add(context);
                    return verified();
                });

        assertThat(result.accepted()).isTrue();
        assertThat(contexts).hasSize(14);
        AcceptanceContext context = contexts.getFirst();
        assertThat(contexts).allMatch(value -> value == context);
        assertThat(context.resultId()).isEqualTo("SAR-authority-test");
        assertThat(context.revision()).isEqualTo(2);
        assertThat(context.contractId())
                .isEqualTo("contract:capability-studio-stage-acceptance");
        assertThat(context.contractRevision()).isEqualTo("2026-01");
        assertThat(context.candidateArtifactFingerprint()).isEqualTo(fingerprint('5'));
        assertThat(context.candidateIntentFingerprint()).isEqualTo(fingerprint('4'));
        assertThat(context.environmentFingerprint()).isEqualTo(fingerprint('3'));
        assertThat(context.executionStartedAt())
                .isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(context.evidenceCompletedAt())
                .isEqualTo(Instant.parse("2026-01-01T00:05:00Z"));
        assertThat(context.decidedAt()).isEqualTo(Instant.parse("2026-01-01T00:11:00Z"));
        assertThat(context.evidenceClosureFingerprint())
                .isEqualTo(input.path("evidenceClosureFingerprint").textValue());
        assertThat(context.environmentProfile())
                .isEqualTo("capability-studio:stage-acceptance");
        assertThat(context.environmentScope())
                .isEqualTo("tenant:demo/environment:acceptance");
        assertThat(context.environmentIssuer())
                .isEqualTo("issuer:deployment-control-plane");
    }

    @Test
    void rejectsEnvironmentIssuerScopeCandidateFingerprintAndWindowDrift() {
        ObjectNode input = validStagePass();
        var coordinate = evidenceCoordinate(input, "environment");
        List<ResolvedEvidence> drifted = List.of(
                environmentFacts(coordinate, "issuer:other",
                        "tenant:demo/environment:acceptance", fingerprint('5'),
                        fingerprint('3'), instant("00:00:00"), instant("00:30:00")),
                environmentFacts(coordinate, "issuer:deployment-control-plane",
                        "tenant:other/environment:acceptance", fingerprint('5'),
                        fingerprint('3'), instant("00:00:00"), instant("00:30:00")),
                environmentFacts(coordinate, "issuer:deployment-control-plane",
                        "tenant:demo/environment:acceptance", fingerprint('6'),
                        fingerprint('3'), instant("00:00:00"), instant("00:30:00")),
                environmentFacts(coordinate, "issuer:deployment-control-plane",
                        "tenant:demo/environment:acceptance", fingerprint('5'),
                        fingerprint('7'), instant("00:00:00"), instant("00:30:00")),
                environmentFacts(coordinate, "issuer:deployment-control-plane",
                        "tenant:demo/environment:acceptance", fingerprint('5'),
                        fingerprint('3'), instant("00:00:01"), instant("00:30:00")));

        for (ResolvedEvidence drift : drifted) {
            var result = verify(input, new ArrayList<>(), request ->
                            request.key().equals("environment")
                                    ? available(drift) : available(request, input),
                    (reference, evidence, context) -> verified(),
                    (signoff, signature, context) -> verified());

            assertThat(result.outcome()).isEqualTo(
                    CapabilityStudioStageAcceptanceAuthorityVerifier.Outcome.REJECTED);
        }
    }

    @Test
    void rejectsEgressCandidateIntentAndWindowDrift() {
        ObjectNode input = validStagePass();
        var coordinate = evidenceCoordinate(input, "egress");
        List<ResolvedEvidence> drifted = List.of(
                egressFacts(coordinate, fingerprint('6'),
                        instant("00:00:00"), instant("00:05:00")),
                egressFacts(coordinate, fingerprint('4'),
                        instant("00:00:01"), instant("00:05:00")),
                egressFacts(coordinate, fingerprint('4'),
                        instant("00:00:00"), instant("00:06:00")));

        for (ResolvedEvidence drift : drifted) {
            var result = verify(input, new ArrayList<>(), request ->
                            request.key().equals("egress")
                                    ? available(drift) : available(request, input),
                    (reference, evidence, context) -> verified(),
                    (signoff, signature, context) -> verified());

            assertThat(result.outcome()).isEqualTo(
                    CapabilityStudioStageAcceptanceAuthorityVerifier.Outcome.REJECTED);
        }
    }

    @Test
    void rejectsOwnerSignatureClosureDrift() {
        ObjectNode input = validStagePass();

        var result = verify(input, new ArrayList<>(), request ->
                        request.kind() == CapabilityStudioStageAcceptanceAuthorityVerifier.ReferenceKind.SIGNATURE
                                ? available(ownerSignatureFacts(
                                request.coordinate(), fingerprint('f')))
                                : available(request, input),
                (reference, evidence, context) -> verified(),
                (signoff, signature, context) -> verified());

        assertThat(result.outcome()).isEqualTo(
                CapabilityStudioStageAcceptanceAuthorityVerifier.Outcome.REJECTED);
        assertThat(result.reasonCode()).isEqualTo(code("OWNER_SIGNATURE_CLOSURE_MISMATCH"));
    }

    @Test
    void rejectsOrdinaryEvidenceUsedAsEnvironmentEgressOrOwnerSignature() {
        ObjectNode environmentInput = validStagePass();
        var environment = verify(environmentInput, new ArrayList<>(), request ->
                        request.key().equals("environment")
                                ? available(ordinaryFacts(request.coordinate()))
                                : available(request, environmentInput),
                (reference, evidence, context) -> verified(),
                (signoff, signature, context) -> verified());

        ObjectNode egressInput = validStagePass();
        var egress = verify(egressInput, new ArrayList<>(), request ->
                        request.key().equals("egress")
                                ? available(ordinaryFacts(request.coordinate()))
                                : available(request, egressInput),
                (reference, evidence, context) -> verified(),
                (signoff, signature, context) -> verified());

        ObjectNode signatureInput = validStagePass();
        var signature = verify(signatureInput, new ArrayList<>(), request ->
                        request.kind() == CapabilityStudioStageAcceptanceAuthorityVerifier.ReferenceKind.SIGNATURE
                                ? available(ordinaryFacts(request.coordinate()))
                                : available(request, signatureInput),
                (reference, evidence, context) -> verified(),
                (signoff, resolved, context) -> verified());

        assertThat(List.of(environment.outcome(), egress.outcome(), signature.outcome()))
                .containsOnly(CapabilityStudioStageAcceptanceAuthorityVerifier.Outcome.REJECTED);
    }

    @Test
    void missingKnownAuthorityFactsFailClosed() {
        ObjectNode input = validStagePass();
        ResolvedEvidence incomplete = new ResolvedEvidence(
                evidenceCoordinate(input, "environment"),
                EvidenceKind.ENVIRONMENT_ATTESTATION,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);

        var result = verify(input, new ArrayList<>(), request ->
                        request.key().equals("environment")
                                ? available(incomplete) : available(request, input),
                (reference, evidence, context) -> verified(),
                (signoff, signature, context) -> verified());

        assertThat(result.outcome()).isEqualTo(
                CapabilityStudioStageAcceptanceAuthorityVerifier.Outcome.BLOCKED);
        assertThat(result.reasonCode()).isEqualTo(
                code("ENVIRONMENT_AUTHORITY_FACTS_INCOMPLETE"));
    }

    @Test
    void externalExceptionsFailClosedWithoutLeakingExceptionText() {
        String secret = "private-payload-should-not-escape";
        var result = verify(validStagePass(), new ArrayList<>(), request -> {
            throw new IllegalStateException(secret);
        }, (reference, evidence, context) -> {
            throw new IllegalStateException(secret);
        }, (signoff, signature, context) -> {
            throw new IllegalStateException(secret);
        });

        assertThat(result.outcome()).isEqualTo(
                CapabilityStudioStageAcceptanceAuthorityVerifier.Outcome.BLOCKED);
        assertThat(result.toString()).doesNotContain(secret, "check-1", "signature:correctness");
    }

    @Test
    void resultIsPayloadFreeAndNeverEchoesReferencesOrSignatures() {
        var result = verify(validStagePass(), new ArrayList<>(), request ->
                        CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolution.notFound(),
                (reference, evidence, context) -> CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision.rejected("NOPE"),
                (signoff, signature, context) -> CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision.rejected("NOPE"));

        assertThat(result.toString()).doesNotContain(
                "evidence:check:1", "check-1", "signature:correctness", "actor:correctness");
        assertThat(result.reasonCode()).startsWith(
                CapabilityStudioStageAcceptanceAuthorityVerifier.CODE_PREFIX);
    }

    private static CapabilityStudioStageAcceptanceAuthorityVerifier.VerificationResult verify(
            ObjectNode input,
            List<String> calls,
            CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver resolver,
            CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy issuer,
            CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority owner) {
        return VERIFIER.verify(input, NOW, request -> {
            calls.add(request.kind() + ":" + request.key());
            return resolver.resolve(request);
        }, issuer, owner);
    }

    private static CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolution available(
            CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate coordinate) {
        return available(ordinaryFacts(coordinate));
    }

    private static CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolution available(
            ResolvedEvidence evidence) {
        return CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolution.available(
                evidence);
    }

    private static CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolution available(
            ResolutionRequest request, ObjectNode input) {
        return available(authorityFacts(request, input));
    }

    private static ResolvedEvidence authorityFacts(
            ResolutionRequest request, ObjectNode input) {
        if (request.kind()
                == CapabilityStudioStageAcceptanceAuthorityVerifier.ReferenceKind.SIGNATURE) {
            return ownerSignatureFacts(request.coordinate(),
                    input.path("evidenceClosureFingerprint").textValue());
        }
        return switch (request.key()) {
            case "environment" -> environmentFacts(
                    request.coordinate(),
                    "issuer:deployment-control-plane",
                    "tenant:demo/environment:acceptance",
                    fingerprint('5'), fingerprint('3'),
                    instant("00:00:00"), instant("00:30:00"));
            case "egress" -> egressFacts(
                    request.coordinate(), fingerprint('4'),
                    instant("00:00:00"), instant("00:05:00"));
            default -> ordinaryFacts(request.coordinate());
        };
    }

    private static ResolvedEvidence ordinaryFacts(
            CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate coordinate) {
        return new ResolvedEvidence(
                coordinate, EvidenceKind.ACCEPTANCE_EVIDENCE,
                "issuer:acceptance-evidence", "tenant:demo/environment:acceptance",
                fingerprint('5'), fingerprint('4'), fingerprint('3'),
                instant("00:00:00"), instant("00:05:00"), null,
                "key:acceptance:1", "Ed25519", fingerprint('6'),
                instant("00:05:00"), instant("00:30:00"), "c2lnbmF0dXJl");
    }

    private static ResolvedEvidence environmentFacts(
            CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate coordinate,
            String issuer,
            String scope,
            String candidateArtifactFingerprint,
            String environmentFingerprint,
            Instant observedFrom,
            Instant observedThrough) {
        return new ResolvedEvidence(
                coordinate, EvidenceKind.ENVIRONMENT_ATTESTATION,
                issuer, scope, candidateArtifactFingerprint, null, environmentFingerprint,
                observedFrom, observedThrough, null,
                "key:environment:1", "Ed25519", fingerprint('6'),
                observedFrom, observedThrough, "c2lnbmF0dXJl");
    }

    private static ResolvedEvidence egressFacts(
            CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate coordinate,
            String candidateIntentFingerprint,
            Instant observedFrom,
            Instant observedThrough) {
        return new ResolvedEvidence(
                coordinate, EvidenceKind.DEPLOYMENT_EGRESS_OBSERVATION,
                "issuer:network-observer", "tenant:demo/environment:acceptance",
                null, candidateIntentFingerprint, null,
                observedFrom, observedThrough, null,
                "key:egress:1", "Ed25519", fingerprint('6'),
                observedThrough, instant("00:30:00"), "c2lnbmF0dXJl");
    }

    private static ResolvedEvidence ownerSignatureFacts(
            CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate coordinate,
            String evidenceClosureFingerprint) {
        return new ResolvedEvidence(
                coordinate, EvidenceKind.OWNER_SIGNATURE,
                "issuer:owner-authority", "tenant:demo/environment:acceptance",
                fingerprint('5'), fingerprint('4'), fingerprint('3'),
                null, null, evidenceClosureFingerprint,
                "key:owner:1", "Ed25519", fingerprint('6'),
                instant("00:07:00"), instant("00:30:00"), "c2lnbmF0dXJl");
    }

    private static CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate
            evidenceCoordinate(ObjectNode input, String evidenceId) {
        for (JsonNode evidence : input.path("evidenceRefs")) {
            if (evidenceId.equals(evidence.path("evidenceId").textValue())) {
                return new CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate(
                        evidence.path("exactRef").textValue(),
                        evidence.path("fingerprint").textValue());
            }
        }
        throw new AssertionError("missing evidence " + evidenceId);
    }

    private static Instant instant(String time) {
        return Instant.parse("2026-01-01T" + time + "Z");
    }

    private static CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate coordinate(
            char seed) {
        return new CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate(
                "wrong:coordinate", fingerprint(seed));
    }

    private static CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision verified() {
        return CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision.verified();
    }

    private static String code(String suffix) {
        return CapabilityStudioStageAcceptanceAuthorityVerifier.CODE_PREFIX + suffix;
    }

    static ObjectNode validStagePass() {
        ObjectNode result = JSON.createObjectNode();
        result.put("schemaVersion", "bloge.capabilityStudioStageAcceptanceResult.v2");
        result.put("resultId", "SAR-authority-test");
        result.put("revision", 2);
        result.put("contractId", "contract:capability-studio-stage-acceptance");
        result.put("contractRevision", "2026-01");
        result.put("resultKind", "STAGE_EXIT");
        result.put("status", "PASS");
        result.put("decidedAt", "2026-01-01T00:11:00Z");
        ObjectNode binding = result.putObject("candidateExecutionBinding");
        binding.putObject("candidateBuild")
                .put("buildRef", "build:capability-studio")
                .put("revision", "rev-2")
                .put("sourceCommit", "abcdef1234567")
                .put("sourceTreeStatus", "CLEAN")
                .put("artifactFingerprint", fingerprint('5'));
        binding.put("candidateIntentFingerprint", fingerprint('4'));
        binding.putObject("baselineRef")
                .put("exactRef", "baseline:capability-studio:v2")
                .put("fingerprint", fingerprint('1'));
        binding.putObject("demoPackRef")
                .put("exactRef", "demo-pack:capability-studio:v2")
                .put("fingerprint", fingerprint('2'));
        binding.put("environmentFingerprint", fingerprint('3'))
                .put("executionStartedAt", "2026-01-01T00:00:00Z")
                .put("evidenceCompletedAt", "2026-01-01T00:05:00Z");
        result.putObject("environmentAttestation")
                .put("exactRef", "attestation:environment:1")
                .put("fingerprint", fingerprint('a'))
                .put("environmentFingerprint", fingerprint('3'))
                .put("profile", "capability-studio:stage-acceptance")
                .put("scope", "tenant:demo/environment:acceptance")
                .put("issuer", "issuer:deployment-control-plane")
                .put("issuedAt", "2026-01-01T00:00:00Z")
                .put("expiresAt", "2026-01-01T00:30:00Z")
                .put("candidateArtifactFingerprint", fingerprint('5'));
        result.putObject("deploymentEgressObservation")
                .put("exactRef", "egress-observation:deployment:1")
                .put("fingerprint", fingerprint('b'))
                .put("candidateIntentFingerprint", fingerprint('4'))
                .put("observationStartedAt", "2026-01-01T00:00:00Z")
                .put("observationCompletedAt", "2026-01-01T00:05:00Z")
                .put("networkPolicyRef", "network-policy:deny-external-v1")
                .put("observedExternalCallCount", 0)
                .put("deniedAttemptCount", 0)
                .put("status", "PASS");
        ArrayNode evidence = result.putArray("evidenceRefs");
        evidence.add(evidence("environment", "attestation:environment:1", 'a'));
        evidence.add(evidence("egress", "egress-observation:deployment:1", 'b'));
        for (int i = 1; i <= 9; i++) {
            evidence.add(evidence("check-" + i, "evidence:check:" + i, (char) ('0' + i)));
        }
        ArrayNode checks = result.putArray("acceptanceChecks");
        for (int i = 1; i <= 9; i++) {
            checks.addObject().put("checkId", "AC-STD-0" + i).put("status", "PASS")
                    .putArray("evidenceIds").add("check-" + i);
        }
        ArrayNode signoffs = result.putArray("signoffs");
        signoffs.add(signoff("CORRECTNESS_OWNER", "actor:correctness", "signature:correctness", 'c'));
        signoffs.add(signoff("RUNTIME_OWNER", "actor:runtime", "signature:runtime", 'd'));
        signoffs.add(signoff("QA_OWNER", "actor:qa", "signature:qa", 'e'));
        result.putArray("diagnostics");
        refreshClosure(result);
        return result;
    }

    private static ObjectNode evidence(String id, String exactRef, char seed) {
        return JSON.createObjectNode().put("evidenceId", id).put("exactRef", exactRef)
                .put("fingerprint", fingerprint(seed)).put("status", "AVAILABLE");
    }

    private static ObjectNode signoff(String role, String actor, String exactRef, char seed) {
        ObjectNode value = JSON.createObjectNode().put("role", role).put("actorRef", actor)
                .put("decision", "APPROVED").put("signedAt", "2026-01-01T00:07:00Z");
        value.putObject("signatureRef").put("exactRef", exactRef)
                .put("fingerprint", fingerprint(seed));
        value.put("evidenceClosureFingerprint", fingerprint('0'));
        return value;
    }

    private static ObjectNode check(ObjectNode result, String id) {
        for (JsonNode value : result.path("acceptanceChecks")) {
            if (id.equals(value.path("checkId").textValue())) {
                return (ObjectNode) value;
            }
        }
        throw new AssertionError("missing check " + id);
    }

    private static void refreshClosure(ObjectNode result) {
        String closure = CapabilityStudioStageAcceptanceResultV2Verifier.closureFingerprint(result);
        result.put("evidenceClosureFingerprint", closure);
        for (JsonNode signoff : result.path("signoffs")) {
            ((ObjectNode) signoff).put("evidenceClosureFingerprint", closure);
        }
    }

    private static void replaceDiagnostics(ObjectNode result, String... codes) {
        ArrayNode diagnostics = result.putArray("diagnostics");
        for (String code : codes) {
            diagnostics.addObject().put("code", code);
        }
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }
}
