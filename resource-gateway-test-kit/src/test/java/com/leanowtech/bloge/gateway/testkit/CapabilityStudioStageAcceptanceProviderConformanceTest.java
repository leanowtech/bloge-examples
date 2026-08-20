package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceKind;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolution;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ReferenceKind;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolutionRequest;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolvedEvidence;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceProviderConformance.CheckResult;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceProviderConformance.CheckStatus;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceProviderConformance.Result;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceProviderConformance.Verdict;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioStageAcceptanceProviderConformanceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-01-01T00:12:00Z");

    @Test
    void conformantProviderPassesAllChecksAndUsesDeterministicChallengeCount() {
        ObjectNode input = validStagePass();
        Provider provider = conformantProvider(input);

        CapabilityStudioStageAcceptanceProviderConformance.Result result = verify(input, provider);

        assertThat(result.verdict())
                .isEqualTo(CapabilityStudioStageAcceptanceProviderConformance.Verdict.CONFORMANT);
        assertThat(result.checkResults()).extracting(
                CapabilityStudioStageAcceptanceProviderConformance.CheckResult::status)
                .containsExactlyElementsOf(java.util.List.of(
                        CapabilityStudioStageAcceptanceProviderConformance.CheckStatus.PASS,
                        CapabilityStudioStageAcceptanceProviderConformance.CheckStatus.PASS,
                        CapabilityStudioStageAcceptanceProviderConformance.CheckStatus.PASS,
                        CapabilityStudioStageAcceptanceProviderConformance.CheckStatus.PASS,
                        CapabilityStudioStageAcceptanceProviderConformance.CheckStatus.PASS,
                        CapabilityStudioStageAcceptanceProviderConformance.CheckStatus.PASS));
        assertThat(result.challengeCount()).isEqualTo(28);
        assertThat(result.resultFingerprint()).isEqualTo(
                EvidenceVerificationSupport.sha256Bounded(input, CapabilityStudioStageAcceptanceResultV2Verifier.MAXIMUM_RESULT_BYTES));
        assertThat(result.checkResults()).extracting(
                CapabilityStudioStageAcceptanceProviderConformance.CheckResult::challengeCount)
                .containsExactly(0, 0, 0, 14, 11, 3);
        assertThat(result.checkResults().stream()
                .mapToInt(CapabilityStudioStageAcceptanceProviderConformance.CheckResult::challengeCount)
                .sum()).isEqualTo(result.challengeCount());
    }

    @Test
    void invalidAndNonPassInputsMakeZeroProviderCalls() {
        CountingProvider provider = new CountingProvider(conformantProvider(validStagePass()));
        CapabilityStudioStageAcceptanceProviderConformance tck =
                new CapabilityStudioStageAcceptanceProviderConformance();

        var invalid = tck.verify("not-json".getBytes(), NOW, provider);
        assertThat(invalid.verdict())
                .isEqualTo(CapabilityStudioStageAcceptanceProviderConformance.Verdict.INPUT_INVALID);
        assertThat(invalid.resultId()).isNull();
        assertThat(invalid.revision()).isZero();
        assertThat(invalid.resultFingerprint()).isNull();
        assertThat(provider.accesses()).isZero();

        ObjectNode nonPass = nonPassResult();
        var notAccepted = tck.verify(bytes(nonPass), NOW, provider);
        assertThat(notAccepted.verdict()).isEqualTo(
                CapabilityStudioStageAcceptanceProviderConformance.Verdict.NON_CONFORMANT);
        assertThat(provider.accesses()).isZero();
    }

    @Test
    void baselineRejectAndBlockAreTerminalBeforeChallenges() {
        ObjectNode input = validStagePass();
        Provider rejected = conformantProvider(input);
        rejected.issuer = (reference, evidence, context) -> AuthorityDecision.rejected();
        var reject = verify(input, rejected);
        assertThat(reject.verdict()).isEqualTo(
                CapabilityStudioStageAcceptanceProviderConformance.Verdict.NON_CONFORMANT);
        assertThat(reject.checkResults().get(1).status()).isEqualTo(
                CapabilityStudioStageAcceptanceProviderConformance.CheckStatus.FAIL);
        assertThat(reject.challengeCount()).isZero();

        Provider blocked = conformantProvider(input);
        blocked.issuer = (reference, evidence, context) -> AuthorityDecision.unavailable();
        var unavailable = verify(input, blocked);
        assertThat(unavailable.verdict()).isEqualTo(
                CapabilityStudioStageAcceptanceProviderConformance.Verdict.BLOCKED);
        assertThat(unavailable.checkResults().get(1).status()).isEqualTo(
                CapabilityStudioStageAcceptanceProviderConformance.CheckStatus.BLOCKED);
    }

    @Test
    void replayDriftIsNonConformant() {
        ObjectNode input = validStagePass();
        Provider provider = conformantProvider(input);
        AtomicInteger issuerCalls = new AtomicInteger();
        provider.issuer = (reference, evidence, context) -> issuerCalls.incrementAndGet() > 11
                ? AuthorityDecision.rejected() : AuthorityDecision.verified();

        var result = verify(input, provider);

        assertThat(result.verdict()).isEqualTo(
                CapabilityStudioStageAcceptanceProviderConformance.Verdict.NON_CONFORMANT);
        assertThat(result.checkResults().get(2).status()).isEqualTo(
                CapabilityStudioStageAcceptanceProviderConformance.CheckStatus.FAIL);
        assertThat(result.challengeCount()).isZero();
    }

    @Test
    void replayRejectsAuthorityFactDriftEvenWhenBothDecisionsAreAccepted() {
        ObjectNode input = validStagePass();
        Provider provider = conformantProvider(input);
        EvidenceResolver original = provider.resolver;
        AtomicInteger resolverCalls = new AtomicInteger();
        int baselineCalls = input.path("evidenceRefs").size() + input.path("signoffs").size();
        provider.resolver = request -> {
            EvidenceResolution resolution = original.resolve(request);
            if (resolverCalls.incrementAndGet() <= baselineCalls
                    || resolution.status() != CapabilityStudioStageAcceptanceAuthorityVerifier
                    .ResolutionStatus.AVAILABLE) {
                return resolution;
            }
            ResolvedEvidence evidence = resolution.evidence();
            return EvidenceResolution.available(new ResolvedEvidence(
                    evidence.coordinate(), evidence.evidenceKind(), "issuer:replay-drift",
                    evidence.scope(), evidence.candidateArtifactFingerprint(),
                    evidence.candidateIntentFingerprint(), evidence.environmentFingerprint(),
                    evidence.observedFrom(), evidence.observedThrough(),
                    evidence.evidenceClosureFingerprint(), evidence.keyId(), evidence.algorithm(),
                    evidence.materialFingerprint(), evidence.signedAt(), evidence.expiresAt(),
                    evidence.signature()));
        };

        Result result = verify(input, provider);

        assertThat(result.verdict()).isEqualTo(Verdict.NON_CONFORMANT);
        assertThat(result.checkResults().get(2).status()).isEqualTo(CheckStatus.FAIL);
        assertThat(result.checkResults().get(2).reasonCode())
                .endsWith("DETERMINISTIC_REPLAY_DRIFT");
        assertThat(result.challengeCount()).isZero();
    }

    @Test
    void wrongFingerprintAvailableFailsAndUnavailableBlocks() {
        ObjectNode input = validStagePass();
        Provider available = conformantProvider(input);
        available.resolver = request -> EvidenceResolution.available(
                facts(request, input.path("evidenceClosureFingerprint").asText()));
        var fail = verify(input, available);
        assertThat(fail.verdict()).isEqualTo(
                CapabilityStudioStageAcceptanceProviderConformance.Verdict.NON_CONFORMANT);
        assertThat(fail.checkResults().get(3).status()).isEqualTo(
                CapabilityStudioStageAcceptanceProviderConformance.CheckStatus.FAIL);

        Provider unavailable = conformantProvider(input);
        EvidenceResolver original = unavailable.resolver;
        unavailable.resolver = request -> request.coordinate().fingerprint()
                .equals(originalCoordinate(input, request).fingerprint())
                ? original.resolve(request) : EvidenceResolution.unavailable();
        var blocked = verify(input, unavailable);
        assertThat(blocked.verdict()).isEqualTo(
                CapabilityStudioStageAcceptanceProviderConformance.Verdict.BLOCKED);
        assertThat(blocked.checkResults().get(3).status()).isEqualTo(
                CapabilityStudioStageAcceptanceProviderConformance.CheckStatus.BLOCKED);

        Provider notFoundWithEvidence = conformantProvider(input);
        EvidenceResolver exactResolver = notFoundWithEvidence.resolver;
        notFoundWithEvidence.resolver = request -> {
            EvidenceResolution resolution = exactResolver.resolve(request);
            return resolution.status() == CapabilityStudioStageAcceptanceAuthorityVerifier
                    .ResolutionStatus.NOT_FOUND
                    ? new EvidenceResolution(CapabilityStudioStageAcceptanceAuthorityVerifier
                    .ResolutionStatus.NOT_FOUND, facts(request,
                    input.path("evidenceClosureFingerprint").asText()))
                    : resolution;
        };
        var malformedNotFound = verify(input, notFoundWithEvidence);
        assertThat(malformedNotFound.verdict()).isEqualTo(Verdict.NON_CONFORMANT);
        assertThat(malformedNotFound.checkResults().get(3).status()).isEqualTo(CheckStatus.FAIL);
    }

    @Test
    void issuerAndOwnerTamperPoliciesMustReject() {
        ObjectNode input = validStagePass();
        Provider issuerVerified = conformantProvider(input);
        issuerVerified.issuer = (reference, evidence, context) ->
                evidence.materialFingerprint().equals(fingerprint('6'))
                        ? AuthorityDecision.verified() : AuthorityDecision.verified();
        var issuerResult = verify(input, issuerVerified);
        assertThat(issuerResult.checkResults().get(4).status()).isEqualTo(
                CapabilityStudioStageAcceptanceProviderConformance.CheckStatus.FAIL);

        Provider ownerVerified = conformantProvider(input);
        ownerVerified.owner = (signoff, signature, context) -> AuthorityDecision.verified();
        var ownerResult = verify(input, ownerVerified);
        assertThat(ownerResult.checkResults().get(5).status()).isEqualTo(
                CapabilityStudioStageAcceptanceProviderConformance.CheckStatus.FAIL);
    }

    @Test
    void issuerAndOwnerUnavailableOrExceptionsBlock() {
        ObjectNode input = validStagePass();
        Provider issuerUnavailable = conformantProvider(input);
        AtomicInteger issuerCalls = new AtomicInteger();
        int replayBoundary = input.path("evidenceRefs").size() * 2;
        issuerUnavailable.issuer = (reference, evidence, context) ->
                issuerCalls.incrementAndGet() <= replayBoundary
                        ? AuthorityDecision.verified() : AuthorityDecision.unavailable();
        var issuerResult = verify(input, issuerUnavailable);
        assertThat(issuerResult.verdict()).isEqualTo(
                CapabilityStudioStageAcceptanceProviderConformance.Verdict.BLOCKED);
        assertThat(issuerResult.checkResults().subList(0, 3)).allMatch(check ->
                check.status() == CapabilityStudioStageAcceptanceProviderConformance.CheckStatus.PASS);
        assertThat(issuerResult.checkResults().get(4).status()).isEqualTo(
                CapabilityStudioStageAcceptanceProviderConformance.CheckStatus.BLOCKED);

        Provider ownerException = conformantProvider(input);
        AtomicInteger ownerCalls = new AtomicInteger();
        int ownerReplayBoundary = input.path("signoffs").size() * 2;
        ownerException.owner = (signoff, signature, context) -> {
            if (ownerCalls.incrementAndGet() <= ownerReplayBoundary) {
                return AuthorityDecision.verified();
            }
            throw new IllegalStateException("secret-business-payload");
        };
        var ownerResult = verify(input, ownerException);
        assertThat(ownerResult.verdict()).isEqualTo(
                CapabilityStudioStageAcceptanceProviderConformance.Verdict.BLOCKED);
        assertThat(ownerResult.checkResults().subList(0, 3)).allMatch(check ->
                check.status() == CapabilityStudioStageAcceptanceProviderConformance.CheckStatus.PASS);
        assertThat(ownerResult.checkResults().get(5).status()).isEqualTo(
                CapabilityStudioStageAcceptanceProviderConformance.CheckStatus.BLOCKED);
        assertThat(ownerResult.toString()).doesNotContain("secret-business-payload");
    }

    @Test
    void resultConstructorRejectsInconsistentManualStates() {
        List<CheckResult> invalidInput = manualChecks(
                CheckStatus.FAIL, CheckStatus.NOT_RUN, CheckStatus.NOT_RUN,
                CheckStatus.NOT_RUN, CheckStatus.NOT_RUN, CheckStatus.NOT_RUN);
        assertThatThrownBy(() -> new Result(Verdict.INPUT_INVALID, tckCode("TEST"),
                invalidInput, 0, "SAR-manual", 1, fingerprint('f'), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manualResult(Verdict.INPUT_INVALID, manualChecks(
                CheckStatus.PASS, CheckStatus.NOT_RUN, CheckStatus.NOT_RUN,
                CheckStatus.NOT_RUN, CheckStatus.NOT_RUN, CheckStatus.NOT_RUN),
                null, 0, null, null)).isInstanceOf(IllegalArgumentException.class);

        List<CheckResult> baselineFailure = manualChecks(
                CheckStatus.PASS, CheckStatus.FAIL, CheckStatus.NOT_RUN,
                CheckStatus.NOT_RUN, CheckStatus.NOT_RUN, CheckStatus.NOT_RUN);
        assertThatThrownBy(() -> manualResult(Verdict.NON_CONFORMANT, baselineFailure,
                null, 0, null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manualResult(Verdict.NON_CONFORMANT, baselineFailure,
                "SAR-manual", 1, fingerprint('f'), null))
                .isInstanceOf(IllegalArgumentException.class);

        List<CheckResult> allPass = manualChecks(
                CheckStatus.PASS, CheckStatus.PASS, CheckStatus.PASS,
                CheckStatus.PASS, CheckStatus.PASS, CheckStatus.PASS);
        assertThatThrownBy(() -> manualResult(Verdict.NON_CONFORMANT, allPass,
                "SAR-manual", 1, fingerprint('f'), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manualResult(Verdict.BLOCKED, manualChecks(
                CheckStatus.PASS, CheckStatus.PASS, CheckStatus.PASS,
                CheckStatus.FAIL, CheckStatus.PASS, CheckStatus.PASS),
                "SAR-manual", 1, fingerprint('f'), NOW))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> manualResult(Verdict.BLOCKED, manualChecks(
                CheckStatus.PASS, CheckStatus.BLOCKED, CheckStatus.PASS,
                CheckStatus.NOT_RUN, CheckStatus.NOT_RUN, CheckStatus.NOT_RUN),
                "SAR-manual", 1, fingerprint('f'), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manualResult(Verdict.BLOCKED, manualChecks(
                CheckStatus.PASS, CheckStatus.PASS, CheckStatus.BLOCKED,
                CheckStatus.BLOCKED, CheckStatus.NOT_RUN, CheckStatus.NOT_RUN),
                "SAR-manual", 1, fingerprint('f'), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manualResult(Verdict.BLOCKED, manualChecks(
                CheckStatus.PASS, CheckStatus.PASS, CheckStatus.PASS,
                CheckStatus.BLOCKED, CheckStatus.NOT_RUN, CheckStatus.PASS),
                "SAR-manual", 1, fingerprint('f'), NOW))
                .isInstanceOf(IllegalArgumentException.class);

        List<CheckResult> countedLocal = new ArrayList<>(allPass);
        countedLocal.set(0, new CheckResult(
                CapabilityStudioStageAcceptanceProviderConformance.CHECK_IDS.getFirst(),
                CheckStatus.PASS, 1, tckCode("TEST")));
        assertThatThrownBy(() -> manualResult(Verdict.CONFORMANT, countedLocal,
                "SAR-manual", 1, fingerprint('f'), NOW))
                .isInstanceOf(IllegalArgumentException.class);

        List<CheckResult> zeroChallenge = new ArrayList<>(allPass);
        zeroChallenge.set(3, new CheckResult(
                CapabilityStudioStageAcceptanceProviderConformance.CHECK_IDS.get(3),
                CheckStatus.PASS, 0, tckCode("TEST")));
        assertThatThrownBy(() -> manualResult(Verdict.CONFORMANT, zeroChallenge,
                "SAR-manual", 1, fingerprint('f'), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyDependenciesBlockAndOutputDoesNotEchoWireContent() {
        ObjectNode input = validStagePass();
        CapabilityStudioStageAcceptanceAuthorityProvider empty = new Provider(
                null, null, null);
        var result = verify(input, empty);

        assertThat(result.verdict()).isEqualTo(
                CapabilityStudioStageAcceptanceProviderConformance.Verdict.BLOCKED);
        assertThat(result.toString()).doesNotContain(
                "attestation:environment:1", "actor:correctness", "c2lnbmF0dXJl");
    }

    @Test
    void doesNotSwallowErrorsFromProviderBoundary() {
        ObjectNode input = validStagePass();
        Provider provider = conformantProvider(input);
        provider.resolver = request -> {
            throw new AssertionError("provider-error");
        };

        assertThatThrownBy(() -> verify(input, provider))
                .isInstanceOf(AssertionError.class)
                .hasMessage("provider-error");
    }

    private static CapabilityStudioStageAcceptanceProviderConformance.Result verify(
            ObjectNode input, CapabilityStudioStageAcceptanceAuthorityProvider provider) {
        return new CapabilityStudioStageAcceptanceProviderConformance().verify(
                bytes(input), NOW, provider);
    }

    private static Result manualResult(
            Verdict verdict,
            List<CheckResult> checks,
            String resultId,
            int revision,
            String resultFingerprint,
            Instant verificationTime) {
        int challengeCount = checks.stream().mapToInt(CheckResult::challengeCount).sum();
        return new Result(verdict, tckCode("TEST"), checks, challengeCount,
                resultId, revision, resultFingerprint, verificationTime);
    }

    private static List<CheckResult> manualChecks(CheckStatus... statuses) {
        List<CheckResult> checks = new ArrayList<>();
        for (int index = 0; index < statuses.length; index++) {
            int challengeCount = index >= 3 && statuses[index] != CheckStatus.NOT_RUN ? 1 : 0;
            checks.add(new CheckResult(
                    CapabilityStudioStageAcceptanceProviderConformance.CHECK_IDS.get(index),
                    statuses[index], challengeCount, tckCode("TEST")));
        }
        return checks;
    }

    private static String tckCode(String suffix) {
        return CapabilityStudioStageAcceptanceProviderConformance.CODE_PREFIX + suffix;
    }

    private static Provider conformantProvider(ObjectNode input) {
        Set<CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate> coordinates =
                new HashSet<>();
        for (JsonNode value : input.path("evidenceRefs")) {
            coordinates.add(new CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate(
                    value.path("exactRef").asText(), value.path("fingerprint").asText()));
        }
        for (JsonNode value : input.path("signoffs")) {
            coordinates.add(new CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate(
                    value.path("signatureRef").path("exactRef").asText(),
                    value.path("signatureRef").path("fingerprint").asText()));
        }
        String closure = input.path("evidenceClosureFingerprint").asText();
        Provider provider = new Provider(
                request -> coordinates.contains(request.coordinate())
                        ? EvidenceResolution.available(facts(request, closure))
                        : EvidenceResolution.notFound(),
                (reference, evidence, context) -> evidence.materialFingerprint() != null
                        && evidence.materialFingerprint().equals(fingerprint('6'))
                        ? AuthorityDecision.verified() : AuthorityDecision.rejected(),
                (signoff, signature, context) -> signoff.evidenceClosureFingerprint() != null
                        && signoff.evidenceClosureFingerprint().equals(context.evidenceClosureFingerprint())
                        && signature.evidenceClosureFingerprint() != null
                        && signature.evidenceClosureFingerprint().equals(context.evidenceClosureFingerprint())
                        ? AuthorityDecision.verified() : AuthorityDecision.rejected());
        return provider;
    }

    private static ResolvedEvidence facts(ResolutionRequest request, String closure) {
        if (request.kind() == ReferenceKind.SIGNATURE) {
            return new ResolvedEvidence(request.coordinate(), EvidenceKind.OWNER_SIGNATURE,
                    "issuer:owner-authority", "tenant:demo/environment:acceptance",
                    fingerprint('5'), fingerprint('4'), fingerprint('3'), null, null, closure,
                    "key:owner:1", "Ed25519", fingerprint('6'), instant("00:07:00"),
                    instant("00:30:00"), "c2lnbmF0dXJl");
        }
        if (request.key().equals("environment")) {
            return new ResolvedEvidence(request.coordinate(), EvidenceKind.ENVIRONMENT_ATTESTATION,
                    "issuer:deployment-control-plane", "tenant:demo/environment:acceptance",
                    fingerprint('5'), null, fingerprint('3'), instant("00:00:00"),
                    instant("00:30:00"), null, "key:environment:1", "Ed25519",
                    fingerprint('6'), instant("00:00:00"), instant("00:30:00"), "c2lnbmF0dXJl");
        }
        if (request.key().equals("egress")) {
            return new ResolvedEvidence(request.coordinate(), EvidenceKind.DEPLOYMENT_EGRESS_OBSERVATION,
                    "issuer:network-observer", "tenant:demo/environment:acceptance", null,
                    fingerprint('4'), null, instant("00:00:00"), instant("00:05:00"), null,
                    "key:egress:1", "Ed25519", fingerprint('6'), instant("00:05:00"),
                    instant("00:30:00"), "c2lnbmF0dXJl");
        }
        return new ResolvedEvidence(request.coordinate(), EvidenceKind.ACCEPTANCE_EVIDENCE,
                "issuer:acceptance-evidence", "tenant:demo/environment:acceptance",
                fingerprint('5'), fingerprint('4'), fingerprint('3'), instant("00:00:00"),
                instant("00:05:00"), null, "key:acceptance:1", "Ed25519", fingerprint('6'),
                instant("00:05:00"), instant("00:30:00"), "c2lnbmF0dXJl");
    }

    private static CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate
            originalCoordinate(ObjectNode input, ResolutionRequest request) {
        String field = request.kind() == ReferenceKind.SIGNATURE ? "signatureRef" : null;
        for (JsonNode value : field == null ? input.path("evidenceRefs") : input.path("signoffs")) {
            JsonNode coordinate = field == null ? value : value.path(field);
            String key = field == null ? value.path("evidenceId").asText() : value.path("role").asText();
            if (key.equals(request.key())) {
                return new CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate(
                        coordinate.path("exactRef").asText(), coordinate.path("fingerprint").asText());
            }
        }
        throw new AssertionError("missing coordinate");
    }

    private static ObjectNode validStagePass() {
        return CapabilityStudioStageAcceptanceAuthorityVerifierTest.validStagePass();
    }

    private static ObjectNode nonPassResult() {
        ObjectNode result = validStagePass();
        result.put("status", "FAIL");
        ((ObjectNode) result.path("acceptanceChecks").path(0)).put("status", "FAIL")
                .putArray("evidenceIds");
        result.putArray("diagnostics").addObject().put("code", "ACCEPTANCE_CHECK_FAILED");
        result.put("evidenceClosureFingerprint",
                CapabilityStudioStageAcceptanceResultV2Verifier.closureFingerprint(result));
        for (JsonNode signoff : result.path("signoffs")) {
            ((ObjectNode) signoff).put("evidenceClosureFingerprint",
                    result.path("evidenceClosureFingerprint").asText());
        }
        return result;
    }

    private static byte[] bytes(ObjectNode value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static Instant instant(String time) {
        return Instant.parse("2026-01-01T" + time + "Z");
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }

    private static final class Provider implements CapabilityStudioStageAcceptanceAuthorityProvider {
        private EvidenceResolver resolver;
        private CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy issuer;
        private CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority owner;

        private Provider(EvidenceResolver resolver,
                         CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy issuer,
                         CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority owner) {
            this.resolver = resolver;
            this.issuer = issuer;
            this.owner = owner;
        }

        @Override
        public EvidenceResolver evidenceResolver() {
            return resolver;
        }

        @Override
        public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy
                evidenceIssuerPolicy() {
            return issuer;
        }

        @Override
        public CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority ownerAuthority() {
            return owner;
        }
    }

    private static final class CountingProvider implements CapabilityStudioStageAcceptanceAuthorityProvider {
        private final CapabilityStudioStageAcceptanceAuthorityProvider delegate;
        private int accesses;

        private CountingProvider(CapabilityStudioStageAcceptanceAuthorityProvider delegate) {
            this.delegate = delegate;
        }

        int accesses() {
            return accesses;
        }

        @Override
        public EvidenceResolver evidenceResolver() {
            accesses++;
            return delegate.evidenceResolver();
        }

        @Override
        public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy
                evidenceIssuerPolicy() {
            accesses++;
            return delegate.evidenceIssuerPolicy();
        }

        @Override
        public CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority ownerAuthority() {
            accesses++;
            return delegate.ownerAuthority();
        }
    }
}
