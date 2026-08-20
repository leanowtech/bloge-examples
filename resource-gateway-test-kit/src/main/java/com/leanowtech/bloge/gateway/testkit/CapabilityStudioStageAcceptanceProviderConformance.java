package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.AcceptanceContext;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.AuthorityDecision;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceReference;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolution;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerSignoff;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ReferenceKind;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolutionRequest;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolutionStatus;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolvedEvidence;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Authority-material-only preflight TCK for Capability Studio Stage Acceptance providers.
 *
 * <p>Provider Conformance v1 and v2 are {@code AUTHORITY-MATERIAL-ONLY}. They validate the
 * resolver, issuer policy, and owner authority from an atomic
 * {@link CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding}. They cannot prove
 * Candidate Authority, Environment Authority, Target Admission, or formal Stage Acceptance. A
 * {@link Verdict#CONFORMANT CONFORMANT} v2 result must never be described or consumed as
 * target-bound conformance.</p>
 *
 * <p>Target-bound formal admission is enforced exclusively by
 * {@link CapabilityStudioStageAcceptanceCli}, using a
 * {@link CapabilityStudioStageAcceptanceAuthorityProvider.TargetBoundAuthorityBinding}. The v2
 * check set is intentionally not extended for that purpose.</p>
 *
 * <p>The TCK reports only stable, bounded metadata; it never returns the input document, exact
 * references, actors, signatures, provider messages, or business payload.</p>
 */
public final class CapabilityStudioStageAcceptanceProviderConformance {
    /** Stable prefix shared by every TCK reason code. */
    public static final String CODE_PREFIX =
            "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_PROVIDER_CONFORMANCE.";

    /** The fixed TCK check order. */
    public static final List<String> CHECK_IDS = List.of(
            "LOCAL_PROTOCOL",
            "AUTHORITY_BINDING",
            "BASELINE_AUTHORITY_ACCEPTANCE",
            "DETERMINISTIC_REPLAY",
            "RESOLVER_WRONG_FINGERPRINT_FAIL_CLOSED",
            "EVIDENCE_POLICY_TAMPER_FAIL_CLOSED",
            "OWNER_AUTHORITY_TAMPER_FAIL_CLOSED");

    /** The frozen six-check order emitted by the legacy v1 Java/report API. */
    public static final List<String> LEGACY_CHECK_IDS = List.of(
            "LOCAL_PROTOCOL",
            "BASELINE_AUTHORITY_ACCEPTANCE",
            "DETERMINISTIC_REPLAY",
            "RESOLVER_WRONG_FINGERPRINT_FAIL_CLOSED",
            "EVIDENCE_POLICY_TAMPER_FAIL_CLOSED",
            "OWNER_AUTHORITY_TAMPER_FAIL_CLOSED");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern RESULT_ID = Pattern.compile("SAR-[A-Za-z0-9._-]{1,120}");
    private static final Pattern SAFE_CODE_SUFFIX = Pattern.compile("[A-Z][A-Z0-9_.-]{0,120}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[A-Fa-f0-9]{64}");
    private static final Pattern AUTHORITY_BINDING_FINGERPRINT =
            Pattern.compile("sha256:[0-9a-f]{64}");
    private static final int MAXIMUM_RESULT_BYTES =
            CapabilityStudioStageAcceptanceResultV2Verifier.MAXIMUM_RESULT_BYTES;

    private final CapabilityStudioStageAcceptanceResultV2Verifier localVerifier;
    private final CapabilityStudioStageAcceptanceAuthorityVerifier authorityVerifier;

    /** Creates a TCK using the packaged v2 semantic and authority verifiers. */
    public CapabilityStudioStageAcceptanceProviderConformance() {
        this(new CapabilityStudioStageAcceptanceResultV2Verifier());
    }

    CapabilityStudioStageAcceptanceProviderConformance(
            CapabilityStudioStageAcceptanceResultV2Verifier localVerifier) {
        this.localVerifier = Objects.requireNonNull(localVerifier, "localVerifier is required");
        this.authorityVerifier = new CapabilityStudioStageAcceptanceAuthorityVerifier(
                localVerifier);
    }

    /** Final TCK verdicts. */
    public enum Verdict {
        /** All authority-material-only checks in the selected v1 or v2 protocol passed. */
        CONFORMANT,
        /** A deterministic provider contract violation was observed. */
        NON_CONFORMANT,
        /** A required provider dependency could not complete safely. */
        BLOCKED,
        /** The wire document or verification clock was invalid. */
        INPUT_INVALID
    }

    /** Bounded status of one fixed TCK check. */
    public enum CheckStatus {
        /** The check passed. */
        PASS,
        /** The provider violated the check. */
        FAIL,
        /** The check could not complete because a dependency was unavailable. */
        BLOCKED,
        /** The check was not reached after an earlier blocking or failing check. */
        NOT_RUN
    }

    /**
     * Payload-free result for one conformance check.
     *
     * @param checkId fixed check identity
     * @param status bounded check status
     * @param challengeCount number of provider challenges attempted by this check
     * @param reasonCode stable payload-free reason code
     */
    public record CheckResult(
            String checkId, CheckStatus status, int challengeCount, String reasonCode) {
        /** Validates one bounded check result. */
        public CheckResult {
            if (!CHECK_IDS.contains(checkId)) {
                throw new IllegalArgumentException("checkId is not part of the fixed TCK");
            }
            status = Objects.requireNonNull(status, "status is required");
            if (challengeCount < 0) {
                throw new IllegalArgumentException("challengeCount cannot be negative");
            }
            reasonCode = code(reasonCode);
        }

        /**
         * Creates a non-challenge check result.
         *
         * @param checkId fixed check identity
         * @param status bounded check status
         * @param reasonCode stable payload-free reason code
         */
        public CheckResult(String checkId, CheckStatus status, String reasonCode) {
            this(checkId, status, 0, reasonCode);
        }

        /** Returns a redacted check description. */
        @Override
        public String toString() {
            return "CheckResult[checkId=" + checkId + ", status=" + status
                    + ", challengeCount=" + challengeCount + ", reasonCode=" + reasonCode + "]";
        }
    }

    /**
     * Defensive, payload-free authority-material-only conformance result.
     *
     * <p>A {@link Verdict#CONFORMANT CONFORMANT} result does not establish Candidate Authority,
     * Environment Authority, Target Admission, or formal Stage Acceptance.</p>
     *
     * @param verdict final TCK verdict
     * @param reasonCode stable final reason code
     * @param checkResults exactly the frozen six-check v1 or seven-check v2 result set
     * @param challengeCount number of challenge invocations attempted
     * @param resultId verified input result identity, or null for invalid input
     * @param revision verified input revision, or zero for invalid input
     * @param resultFingerprint canonical fingerprint of the verified input Stage Result, or null
     *                          for invalid input
     * @param providerBindingFingerprint verified deployment-owned authority binding, or null
     *                                   when provider identity was not verified
     * @param verificationTime trusted verification time, or null when the clock is invalid
     */
    public record Result(
            Verdict verdict,
            String reasonCode,
            List<CheckResult> checkResults,
            int challengeCount,
            String resultId,
            int revision,
            String resultFingerprint,
            String providerBindingFingerprint,
            Instant verificationTime) {
        /**
         * Source-compatible constructor for callers that do not yet provide a binding.
         *
         * @param verdict final TCK verdict
         * @param reasonCode stable final reason code
         * @param checkResults fixed TCK checks
         * @param challengeCount total challenge count
         * @param resultId verified result identity
         * @param revision verified result revision
         * @param resultFingerprint verified result fingerprint
         * @param verificationTime trusted verification time
         */
        public Result(
                Verdict verdict,
                String reasonCode,
                List<CheckResult> checkResults,
                int challengeCount,
                String resultId,
                int revision,
                String resultFingerprint,
                Instant verificationTime) {
            this(verdict, reasonCode, checkResults, challengeCount, resultId, revision,
                    resultFingerprint, null, verificationTime);
        }

        /** Validates and defensively copies the result boundary. */
        public Result {
            verdict = Objects.requireNonNull(verdict, "verdict is required");
            reasonCode = code(reasonCode);
            checkResults = List.copyOf(new ArrayList<>(
                    Objects.requireNonNull(checkResults, "checkResults are required")));
            List<String> checkIds = checkResults.stream().map(CheckResult::checkId).toList();
            boolean v2 = checkIds.equals(CHECK_IDS);
            boolean v1 = checkIds.equals(LEGACY_CHECK_IDS);
            if (!v1 && !v2) {
                throw new IllegalArgumentException("checkResults must contain a fixed TCK order");
            }
            if (challengeCount < 0) {
                throw new IllegalArgumentException("challengeCount cannot be negative");
            }
            int attempts = checkResults.stream().mapToInt(CheckResult::challengeCount).sum();
            if (attempts != challengeCount) {
                throw new IllegalArgumentException("challengeCount does not match check results");
            }
            int nonChallengeCount = v2 ? 4 : 3;
            if (checkResults.subList(0, nonChallengeCount).stream()
                    .anyMatch(check -> check.challengeCount() != 0)) {
                throw new IllegalArgumentException("non-challenge checks cannot count challenges");
            }
            if (checkResults.stream().anyMatch(check ->
                    check.status() == CheckStatus.NOT_RUN && check.challengeCount() != 0)) {
                throw new IllegalArgumentException("NOT_RUN checks cannot count challenges");
            }

            if (verdict == Verdict.INPUT_INVALID) {
                if (resultId != null || revision != 0 || resultFingerprint != null
                        || providerBindingFingerprint != null || verificationTime != null
                        || checkResults.getFirst().status() != CheckStatus.FAIL
                        || checkResults.subList(1, checkResults.size()).stream()
                        .anyMatch(check -> check.status() != CheckStatus.NOT_RUN)) {
                    throw new IllegalArgumentException("INPUT_INVALID result state is inconsistent");
                }
            } else {
                if (resultId == null || !RESULT_ID.matcher(resultId).matches()
                        || revision < 1
                        || resultFingerprint == null
                        || !FINGERPRINT.matcher(resultFingerprint).matches()
                        || verificationTime == null
                        || checkResults.getFirst().status() != CheckStatus.PASS) {
                    throw new IllegalArgumentException("verified result binding is invalid");
                }
                if (v1 && providerBindingFingerprint != null) {
                    throw new IllegalArgumentException("v1 cannot carry provider authority binding");
                }
                if (v2) {
                    if (checkResults.get(1).status() == CheckStatus.PASS
                            && (providerBindingFingerprint == null
                            || !AUTHORITY_BINDING_FINGERPRINT.matcher(providerBindingFingerprint)
                            .matches())) {
                        throw new IllegalArgumentException("provider authority binding is invalid");
                    }
                    if (checkResults.get(1).status() != CheckStatus.PASS
                            && providerBindingFingerprint != null) {
                        throw new IllegalArgumentException(
                                "provider authority binding must be null before binding passes");
                    }
                }
                validateCheckProgression(checkResults, v2);
            }

            boolean failed = checkResults.stream()
                    .anyMatch(check -> check.status() == CheckStatus.FAIL);
            boolean blocked = checkResults.stream()
                    .anyMatch(check -> check.status() == CheckStatus.BLOCKED);
            switch (verdict) {
                case CONFORMANT -> {
                    if (challengeCount == 0 || checkResults.stream()
                            .anyMatch(check -> check.status() != CheckStatus.PASS)) {
                        throw new IllegalArgumentException("CONFORMANT result state is inconsistent");
                    }
                }
                case NON_CONFORMANT -> {
                    if (!failed) {
                        throw new IllegalArgumentException(
                                "NON_CONFORMANT result requires a failed check");
                    }
                }
                case BLOCKED -> {
                    if (!blocked || failed) {
                        throw new IllegalArgumentException("BLOCKED result state is inconsistent");
                    }
                }
                case INPUT_INVALID -> {
                    // Input binding and check shape are validated above.
                }
            }
        }

        /**
         * Returns the fixed check results under the conventional short accessor name.
         *
         * @return immutable check results in protocol order
         */
        public List<CheckResult> checks() {
            return checkResults;
        }

        private static void validateCheckProgression(List<CheckResult> checks, boolean v2) {
            int baselineIndex = v2 ? 2 : 1;
            int replayIndex = v2 ? 3 : 2;
            int challengeStart = v2 ? 4 : 3;
            if (v2) {
                CheckStatus binding = checks.get(1).status();
                if (binding != CheckStatus.PASS) {
                    if (binding == CheckStatus.NOT_RUN
                            && checks.get(baselineIndex).status() != CheckStatus.FAIL) {
                        throw new IllegalArgumentException("binding termination order is invalid");
                    }
                    if (binding != CheckStatus.NOT_RUN
                            && checks.subList(baselineIndex, checks.size()).stream()
                            .anyMatch(check -> check.status() != CheckStatus.NOT_RUN)) {
                        throw new IllegalArgumentException("binding termination order is invalid");
                    }
                    if (binding == CheckStatus.NOT_RUN
                            && checks.subList(replayIndex, checks.size()).stream()
                            .anyMatch(check -> check.status() != CheckStatus.NOT_RUN)) {
                        throw new IllegalArgumentException("binding termination order is invalid");
                    }
                    return;
                }
            }
            CheckStatus baseline = checks.get(baselineIndex).status();
            CheckStatus replay = checks.get(replayIndex).status();
            if (baseline != CheckStatus.PASS) {
                if (baseline == CheckStatus.NOT_RUN || checks.subList(replayIndex, checks.size()).stream()
                        .anyMatch(check -> check.status() != CheckStatus.NOT_RUN)) {
                    throw new IllegalArgumentException("baseline termination order is invalid");
                }
                return;
            }
            if (replay != CheckStatus.PASS) {
                if (replay == CheckStatus.NOT_RUN || checks.subList(challengeStart, checks.size()).stream()
                        .anyMatch(check -> check.status() != CheckStatus.NOT_RUN)) {
                    throw new IllegalArgumentException("replay termination order is invalid");
                }
                return;
            }
            if (checks.subList(challengeStart, checks.size()).stream().anyMatch(check ->
                    check.status() == CheckStatus.NOT_RUN || check.challengeCount() == 0)) {
                throw new IllegalArgumentException("challenge check progression is invalid");
            }
        }

        /** Returns a redacted description that cannot expose provider or wire payloads. */
        @Override
        public String toString() {
            return "Result[verdict=" + verdict + ", reasonCode=" + reasonCode
                    + ", checkResults=" + checkResults + ", challengeCount=" + challengeCount
                    + ", resultId=" + resultId + ", revision=" + revision
                    + ", resultFingerprint=" + resultFingerprint
                    + ", verificationTime=" + verificationTime + "]";
        }

    }

    /**
     * Runs the Provider Conformance v2 authority-material-only preflight against one provider.
     *
     * <p>This method validates only the provider's atomic resolver/issuer/owner
     * {@link CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding}. Formal
     * target-bound admission remains the exclusive responsibility of
     * {@link CapabilityStudioStageAcceptanceCli} and its
     * {@link CapabilityStudioStageAcceptanceAuthorityProvider.TargetBoundAuthorityBinding}.</p>
     *
     * @param wire UTF-8 Stage Acceptance Result v2 document
     * @param now trusted verification instant
     * @param provider deployment-owned authority provider
     * @return defensive payload-free conformance result
     */
    public Result verify(
            byte[] wire,
            Instant now,
            CapabilityStudioStageAcceptanceAuthorityProvider provider) {
        CapabilityStudioStageAcceptanceResultV2Verifier.VerificationResult local;
        JsonNode input = null;
        try {
            local = localVerifier.verify(wire, now);
            if (local.verified()) {
                input = JSON.readTree(wire);
            }
        } catch (IOException | RuntimeException invalid) {
            local = null;
        }
        if (local == null || !local.verified() || input == null) {
            return invalidResult(now);
        }

        String resultId = input.path("resultId").textValue();
        int revision = input.path("revision").intValue();
        String resultFingerprint;
        try {
            resultFingerprint = EvidenceVerificationSupport.sha256Bounded(
                    input, MAXIMUM_RESULT_BYTES);
        } catch (RuntimeException invalid) {
            return invalidResult(now);
        }
        if (!"PASS".equals(input.path("status").textValue())) {
            return finish(Verdict.NON_CONFORMANT, "STATUS_NOT_PASS", resultId, revision,
                    resultFingerprint, now, 0, notRunChecks(
                            pass("LOCAL_PROTOCOL", "LOCAL_PROTOCOL_VALID"),
                            new CheckResult("AUTHORITY_BINDING", CheckStatus.NOT_RUN,
                                    code("NOT_RUN")),
                            fail("BASELINE_AUTHORITY_ACCEPTANCE", "STATUS_NOT_PASS")), null);
        }
        if (provider == null) {
            return finish(Verdict.BLOCKED, "PROVIDER_DEPENDENCY_UNAVAILABLE", resultId, revision,
                    resultFingerprint, now, 0,
                    blockedAfterLocal("PROVIDER_DEPENDENCY_UNAVAILABLE"), null);
        }

        CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding baselineBinding;
        try {
            baselineBinding = provider.authorityBinding();
        } catch (RuntimeException unavailable) {
            return finish(Verdict.BLOCKED, "AUTHORITY_BINDING_UNAVAILABLE", resultId, revision,
                    resultFingerprint, now, 0,
                    blockedAfterLocal("AUTHORITY_BINDING_UNAVAILABLE"), null);
        }
        if (baselineBinding == null) {
            return finish(Verdict.NON_CONFORMANT, "AUTHORITY_BINDING_MISSING", resultId, revision,
                    resultFingerprint, now, 0,
                    failedAfterBinding("AUTHORITY_BINDING_MISSING"), null);
        }
        String providerBindingFingerprint = baselineBinding.fingerprint();
        if (!AUTHORITY_BINDING_FINGERPRINT.matcher(providerBindingFingerprint).matches()) {
            return finish(Verdict.NON_CONFORMANT, "AUTHORITY_BINDING_INVALID", resultId, revision,
                    resultFingerprint, now, 0,
                    failedAfterBinding("AUTHORITY_BINDING_INVALID"), null);
        }

        EvidenceResolver resolver = baselineBinding.resolver();
        CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy issuerPolicy =
                baselineBinding.issuerPolicy();
        CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority ownerAuthority =
                baselineBinding.ownerAuthority();

        AcceptanceContext context;
        List<EvidenceReference> evidence;
        List<OwnerSignoff> signoffs;
        try {
            context = CapabilityStudioStageAcceptanceAuthorityVerifier.acceptanceContext(input);
            evidence = CapabilityStudioStageAcceptanceAuthorityVerifier.evidenceReferences(input);
            signoffs = CapabilityStudioStageAcceptanceAuthorityVerifier.ownerSignoffs(input);
        } catch (RuntimeException invalidProjection) {
            return invalidResult(now);
        }

        List<CheckResult> checks = new ArrayList<>();
        checks.add(pass("LOCAL_PROTOCOL", "LOCAL_PROTOCOL_VALID"));
        checks.add(pass("AUTHORITY_BINDING", "AUTHORITY_BINDING_VALID"));
        AuthorityTranscript baselineTranscript = new AuthorityTranscript();
        CapabilityStudioStageAcceptanceAuthorityVerifier.VerificationResult baseline = safeAuthority(
                input, now, baselineTranscript.resolver(resolver),
                baselineTranscript.issuerPolicy(issuerPolicy),
                baselineTranscript.ownerAuthority(ownerAuthority));
        checks.add(authorityCheck("BASELINE_AUTHORITY_ACCEPTANCE", baseline,
                "BASELINE_AUTHORITY_ACCEPTED", "BASELINE_AUTHORITY_REJECTED",
                "BASELINE_AUTHORITY_BLOCKED"));
        if (baseline == null || !baseline.accepted()) {
            return finish(verdictFor(checks), primaryReason(checks), resultId, revision,
                    resultFingerprint, now, 0,
                    appendNotRun(checks), providerBindingFingerprint);
        }

        CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding replayBinding;
        try {
            replayBinding = provider.authorityBinding();
        } catch (RuntimeException unavailable) {
            checks.add(new CheckResult("DETERMINISTIC_REPLAY", CheckStatus.BLOCKED,
                    code("DETERMINISTIC_REPLAY_AUTHORITY_BINDING_UNAVAILABLE")));
            return finish(Verdict.BLOCKED, "DETERMINISTIC_REPLAY_AUTHORITY_BINDING_UNAVAILABLE",
                    resultId, revision, resultFingerprint, now, 0,
                    appendNotRun(checks), providerBindingFingerprint);
        }
        if (replayBinding == null
                || !AUTHORITY_BINDING_FINGERPRINT.matcher(replayBinding.fingerprint()).matches()) {
            checks.add(fail("DETERMINISTIC_REPLAY",
                    "DETERMINISTIC_REPLAY_AUTHORITY_BINDING_INVALID"));
            return finish(Verdict.NON_CONFORMANT, "DETERMINISTIC_REPLAY_AUTHORITY_BINDING_INVALID",
                    resultId, revision, resultFingerprint, now, 0,
                    appendNotRun(checks), providerBindingFingerprint);
        }
        AuthorityTranscript replayTranscript = new AuthorityTranscript();
        CapabilityStudioStageAcceptanceAuthorityVerifier.VerificationResult replay = safeAuthority(
                input, now, replayTranscript.resolver(replayBinding.resolver()),
                replayTranscript.issuerPolicy(replayBinding.issuerPolicy()),
                replayTranscript.ownerAuthority(replayBinding.ownerAuthority()));
        CheckResult replayCheck = authorityCheck("DETERMINISTIC_REPLAY", replay,
                "DETERMINISTIC_REPLAY_ACCEPTED", "DETERMINISTIC_REPLAY_DRIFT",
                "DETERMINISTIC_REPLAY_BLOCKED");
        if (replay != null && replay.accepted()
                && !baselineTranscript.sameAs(replayTranscript)) {
            replayCheck = fail("DETERMINISTIC_REPLAY", "DETERMINISTIC_REPLAY_DRIFT");
        }
        if (replay != null && replay.accepted()
                && !providerBindingFingerprint.equals(replayBinding.fingerprint())) {
            replayCheck = fail("DETERMINISTIC_REPLAY",
                    "DETERMINISTIC_REPLAY_AUTHORITY_BINDING_DRIFT");
        }
        checks.add(replayCheck);
        if (replay == null || !replay.accepted() || replayCheck.status() != CheckStatus.PASS) {
            return finish(verdictFor(checks), primaryReason(checks), resultId, revision,
                    resultFingerprint, now, 0,
                    appendNotRun(checks), providerBindingFingerprint);
        }

        int challengeCount = 0;
        Challenge resolverChallenge = resolverChallenge(
                resolver, evidence, signoffs);
        challengeCount += resolverChallenge.attempts();
        checks.add(resolverChallenge.result());

        Challenge issuerChallenge = issuerChallenge(
                resolver, issuerPolicy, context, evidence);
        challengeCount += issuerChallenge.attempts();
        checks.add(issuerChallenge.result());

        Challenge ownerChallenge = ownerChallenge(
                resolver, ownerAuthority, context, signoffs);
        challengeCount += ownerChallenge.attempts();
        checks.add(ownerChallenge.result());

        return finish(verdictFor(checks), primaryReason(checks), resultId, revision,
                resultFingerprint, now, challengeCount, checks, providerBindingFingerprint);
    }

    private CapabilityStudioStageAcceptanceAuthorityVerifier.VerificationResult safeAuthority(
            JsonNode input,
            Instant now,
            EvidenceResolver resolver,
            CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy issuerPolicy,
            CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority ownerAuthority) {
        try {
            return authorityVerifier.verify(input, now, resolver, issuerPolicy, ownerAuthority);
        } catch (RuntimeException unavailable) {
            return new CapabilityStudioStageAcceptanceAuthorityVerifier.VerificationResult(
                    CapabilityStudioStageAcceptanceAuthorityVerifier.Outcome.BLOCKED,
                    CapabilityStudioStageAcceptanceAuthorityVerifier.CODE_PREFIX
                            + "AUTHORITY_UNAVAILABLE");
        }
    }

    private static Challenge resolverChallenge(
            EvidenceResolver resolver,
            List<EvidenceReference> evidence,
            List<OwnerSignoff> signoffs) {
        CheckStatus status = CheckStatus.PASS;
        String reason = "RESOLVER_WRONG_FINGERPRINT_NOT_FOUND";
        int attempts = 0;
        for (EvidenceReference reference : evidence) {
            ChallengeOutcome outcome = resolveWrongFingerprint(resolver,
                    ResolutionRequest.evidence(reference));
            attempts++;
            if (outcome == ChallengeOutcome.BLOCKED && status != CheckStatus.FAIL) {
                status = CheckStatus.BLOCKED;
                reason = "RESOLVER_WRONG_FINGERPRINT_UNAVAILABLE";
            } else if (outcome == ChallengeOutcome.FAIL) {
                status = CheckStatus.FAIL;
                reason = "RESOLVER_WRONG_FINGERPRINT_ACCEPTED";
            }
        }
        for (OwnerSignoff signoff : signoffs) {
            ChallengeOutcome outcome = resolveWrongFingerprint(resolver,
                    ResolutionRequest.signature(signoff));
            attempts++;
            if (outcome == ChallengeOutcome.BLOCKED && status != CheckStatus.FAIL) {
                status = CheckStatus.BLOCKED;
                reason = "RESOLVER_WRONG_FINGERPRINT_UNAVAILABLE";
            } else if (outcome == ChallengeOutcome.FAIL) {
                status = CheckStatus.FAIL;
                reason = "RESOLVER_WRONG_FINGERPRINT_ACCEPTED";
            }
        }
        return new Challenge(new CheckResult(
                "RESOLVER_WRONG_FINGERPRINT_FAIL_CLOSED", status, attempts, code(reason)), attempts);
    }

    private static ChallengeOutcome resolveWrongFingerprint(
            EvidenceResolver resolver, ResolutionRequest original) {
        try {
            EvidenceCoordinate coordinate = original.coordinate();
            EvidenceResolution resolution = resolver.resolve(new ResolutionRequest(
                    original.kind(), original.key(), new EvidenceCoordinate(
                            coordinate.exactRef(), alternateFingerprint(coordinate.fingerprint()))));
            if (resolution == null || resolution.status() == ResolutionStatus.UNAVAILABLE) {
                return ChallengeOutcome.BLOCKED;
            }
            return resolution.status() == ResolutionStatus.NOT_FOUND
                    && resolution.evidence() == null
                    ? ChallengeOutcome.PASS : ChallengeOutcome.FAIL;
        } catch (RuntimeException unavailable) {
            return ChallengeOutcome.BLOCKED;
        }
    }

    private static Challenge issuerChallenge(
            EvidenceResolver resolver,
            CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy issuerPolicy,
            AcceptanceContext context,
            List<EvidenceReference> references) {
        CheckStatus status = CheckStatus.PASS;
        String reason = "EVIDENCE_POLICY_TAMPER_REJECTED";
        int attempts = 0;
        for (EvidenceReference reference : references) {
            attempts++;
            try {
                EvidenceResolution resolution = resolver.resolve(ResolutionRequest.evidence(reference));
                if (resolution == null || resolution.status() != ResolutionStatus.AVAILABLE
                        || resolution.evidence() == null
                        || !reference.coordinate().equals(resolution.evidence().coordinate())) {
                    status = merge(status, CheckStatus.BLOCKED);
                    reason = "EVIDENCE_POLICY_TAMPER_UNAVAILABLE";
                    continue;
                }
                ResolvedEvidence tampered = withMaterialFingerprint(
                        resolution.evidence(), alternateFingerprint(
                                resolution.evidence().materialFingerprint()));
                AuthorityDecision decision = issuerPolicy.verify(reference, tampered, context);
                if (decision == null || decision.status() == AuthorityDecision.Decision.UNAVAILABLE) {
                    if (status != CheckStatus.FAIL) {
                        status = CheckStatus.BLOCKED;
                        reason = "EVIDENCE_POLICY_TAMPER_UNAVAILABLE";
                    }
                } else if (decision.status() == AuthorityDecision.Decision.VERIFIED) {
                    status = CheckStatus.FAIL;
                    reason = "EVIDENCE_POLICY_TAMPER_VERIFIED";
                }
            } catch (RuntimeException unavailable) {
                if (status != CheckStatus.FAIL) {
                    status = CheckStatus.BLOCKED;
                    reason = "EVIDENCE_POLICY_TAMPER_UNAVAILABLE";
                }
            }
        }
        return new Challenge(new CheckResult(
                "EVIDENCE_POLICY_TAMPER_FAIL_CLOSED", status, attempts, code(reason)), attempts);
    }

    private static Challenge ownerChallenge(
            EvidenceResolver resolver,
            CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority ownerAuthority,
            AcceptanceContext context,
            List<OwnerSignoff> signoffs) {
        CheckStatus status = CheckStatus.PASS;
        String reason = "OWNER_AUTHORITY_TAMPER_REJECTED";
        int attempts = 0;
        for (OwnerSignoff signoff : signoffs) {
            attempts++;
            try {
                EvidenceResolution resolution = resolver.resolve(ResolutionRequest.signature(signoff));
                if (resolution == null || resolution.status() != ResolutionStatus.AVAILABLE
                        || resolution.evidence() == null
                        || !signoff.signatureCoordinate().equals(resolution.evidence().coordinate())) {
                    status = merge(status, CheckStatus.BLOCKED);
                    reason = "OWNER_AUTHORITY_TAMPER_UNAVAILABLE";
                    continue;
                }
                OwnerSignoff tamperedSignoff = withClosureFingerprint(
                        signoff, alternateFingerprint(signoff.evidenceClosureFingerprint()));
                AuthorityDecision decision = ownerAuthority.verify(
                        tamperedSignoff, resolution.evidence(), context);
                if (decision == null || decision.status() == AuthorityDecision.Decision.UNAVAILABLE) {
                    if (status != CheckStatus.FAIL) {
                        status = CheckStatus.BLOCKED;
                        reason = "OWNER_AUTHORITY_TAMPER_UNAVAILABLE";
                    }
                } else if (decision.status() == AuthorityDecision.Decision.VERIFIED) {
                    status = CheckStatus.FAIL;
                    reason = "OWNER_AUTHORITY_TAMPER_VERIFIED";
                }
            } catch (RuntimeException unavailable) {
                if (status != CheckStatus.FAIL) {
                    status = CheckStatus.BLOCKED;
                    reason = "OWNER_AUTHORITY_TAMPER_UNAVAILABLE";
                }
            }
        }
        return new Challenge(new CheckResult(
                "OWNER_AUTHORITY_TAMPER_FAIL_CLOSED", status, attempts, code(reason)), attempts);
    }

    private static ResolvedEvidence withMaterialFingerprint(
            ResolvedEvidence evidence, String materialFingerprint) {
        return new ResolvedEvidence(evidence.coordinate(), evidence.evidenceKind(),
                evidence.issuerRef(), evidence.scope(), evidence.candidateArtifactFingerprint(),
                evidence.candidateIntentFingerprint(), evidence.environmentFingerprint(),
                evidence.observedFrom(), evidence.observedThrough(),
                evidence.evidenceClosureFingerprint(), evidence.keyId(), evidence.algorithm(),
                materialFingerprint, evidence.signedAt(), evidence.expiresAt(), evidence.signature());
    }

    private static OwnerSignoff withClosureFingerprint(
            OwnerSignoff signoff, String closureFingerprint) {
        return new OwnerSignoff(signoff.role(), signoff.actorRef(), signoff.decision(),
                signoff.signedAt(), signoff.signatureCoordinate(), closureFingerprint);
    }

    private static String alternateFingerprint(String original) {
        String alternate = "sha256:" + "0".repeat(64);
        return alternate.equals(original) ? "sha256:" + "1".repeat(64) : alternate;
    }

    private static CheckStatus merge(CheckStatus current, CheckStatus next) {
        if (current == CheckStatus.FAIL || next == CheckStatus.FAIL) {
            return CheckStatus.FAIL;
        }
        if (current == CheckStatus.BLOCKED || next == CheckStatus.BLOCKED) {
            return CheckStatus.BLOCKED;
        }
        return next;
    }

    private static CheckResult authorityCheck(
            String checkId,
            CapabilityStudioStageAcceptanceAuthorityVerifier.VerificationResult result,
            String passReason,
            String failReason,
            String blockedReason) {
        if (result == null || result.outcome()
                == CapabilityStudioStageAcceptanceAuthorityVerifier.Outcome.BLOCKED) {
            return new CheckResult(checkId, CheckStatus.BLOCKED, code(blockedReason));
        }
        if (result.outcome()
                == CapabilityStudioStageAcceptanceAuthorityVerifier.Outcome.ACCEPTED) {
            return new CheckResult(checkId, CheckStatus.PASS, code(passReason));
        }
        return new CheckResult(checkId, CheckStatus.FAIL, code(failReason));
    }

    private static List<CheckResult> notRunChecks(CheckResult... results) {
        List<CheckResult> checks = new ArrayList<>(List.of(results));
        while (checks.size() < CHECK_IDS.size()) {
            String id = CHECK_IDS.get(checks.size());
            checks.add(new CheckResult(id, CheckStatus.NOT_RUN, code("NOT_RUN")));
        }
        return checks;
    }

    private static List<CheckResult> blockedAfterLocal(String reason) {
        return notRunChecks(pass("LOCAL_PROTOCOL", "LOCAL_PROTOCOL_VALID"),
                new CheckResult("AUTHORITY_BINDING", CheckStatus.BLOCKED, code(reason)));
    }

    private static List<CheckResult> blockedAfterBinding(String reason) {
        return notRunChecks(pass("LOCAL_PROTOCOL", "LOCAL_PROTOCOL_VALID"),
                pass("AUTHORITY_BINDING", "AUTHORITY_BINDING_VALID"),
                new CheckResult("BASELINE_AUTHORITY_ACCEPTANCE", CheckStatus.BLOCKED,
                        code(reason)));
    }

    private static List<CheckResult> failedAfterBinding(String reason) {
        return notRunChecks(pass("LOCAL_PROTOCOL", "LOCAL_PROTOCOL_VALID"),
                fail("AUTHORITY_BINDING", reason));
    }

    private static List<CheckResult> appendNotRun(List<CheckResult> checks) {
        while (checks.size() < CHECK_IDS.size()) {
            checks.add(new CheckResult(CHECK_IDS.get(checks.size()), CheckStatus.NOT_RUN,
                    code("NOT_RUN")));
        }
        return checks;
    }

    private static CheckResult pass(String checkId, String reason) {
        return new CheckResult(checkId, CheckStatus.PASS, code(reason));
    }

    private static CheckResult fail(String checkId, String reason) {
        return new CheckResult(checkId, CheckStatus.FAIL, code(reason));
    }

    private Result invalidResult(Instant now) {
        List<CheckResult> checks = notRunChecks(
                fail("LOCAL_PROTOCOL", "LOCAL_PROTOCOL_INVALID"));
        return finish(Verdict.INPUT_INVALID, "LOCAL_PROTOCOL_INVALID", null, 0,
                null, null, 0, checks, null);
    }

    private static Result finish(
            Verdict verdict,
            String reason,
            String resultId,
            int revision,
            String resultFingerprint,
            Instant now,
            int challengeCount,
            List<CheckResult> checks,
            String providerBindingFingerprint) {
        List<CheckResult> fixed = List.copyOf(checks);
        return new Result(verdict, code(reason), fixed, challengeCount,
                resultId, revision, resultFingerprint, providerBindingFingerprint, now);
    }

    private static Verdict verdictFor(List<CheckResult> checks) {
        if (checks.stream().anyMatch(check -> check.status() == CheckStatus.FAIL)) {
            return Verdict.NON_CONFORMANT;
        }
        return checks.stream().anyMatch(check -> check.status() == CheckStatus.BLOCKED)
                ? Verdict.BLOCKED : Verdict.CONFORMANT;
    }

    private static String primaryReason(List<CheckResult> checks) {
        return checks.stream()
                .filter(check -> check.status() != CheckStatus.PASS
                        && check.status() != CheckStatus.NOT_RUN)
                .findFirst()
                .map(CheckResult::reasonCode)
                .orElse(code("CONFORMANT"));
    }

    private static String code(String suffix) {
        if (suffix == null) {
            throw new IllegalArgumentException("reasonCode is required");
        }
        String value = suffix.startsWith(CODE_PREFIX) ? suffix.substring(CODE_PREFIX.length()) : suffix;
        if (!SAFE_CODE_SUFFIX.matcher(value).matches()) {
            throw new IllegalArgumentException("reasonCode is invalid");
        }
        return CODE_PREFIX + value;
    }

    private enum ChallengeOutcome {
        PASS,
        FAIL,
        BLOCKED
    }

    private record Challenge(CheckResult result, int attempts) {
    }

    private static final class AuthorityTranscript {
        private final List<ResolutionObservation> resolutions = new ArrayList<>();
        private final List<IssuerObservation> issuerDecisions = new ArrayList<>();
        private final List<OwnerObservation> ownerDecisions = new ArrayList<>();

        private EvidenceResolver resolver(EvidenceResolver delegate) {
            return request -> {
                EvidenceResolution response = delegate.resolve(request);
                resolutions.add(new ResolutionObservation(request, response));
                return response;
            };
        }

        private CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy
                issuerPolicy(
                        CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy
                                delegate) {
            return (reference, evidence, context) -> {
                AuthorityDecision decision = delegate.verify(reference, evidence, context);
                issuerDecisions.add(new IssuerObservation(
                        reference, evidence, context, decision));
                return decision;
            };
        }

        private CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority ownerAuthority(
                CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority delegate) {
            return (signoff, signature, context) -> {
                AuthorityDecision decision = delegate.verify(signoff, signature, context);
                ownerDecisions.add(new OwnerObservation(
                        signoff, signature, context, decision));
                return decision;
            };
        }

        private boolean sameAs(AuthorityTranscript other) {
            return resolutions.equals(other.resolutions)
                    && issuerDecisions.equals(other.issuerDecisions)
                    && ownerDecisions.equals(other.ownerDecisions);
        }
    }

    private record ResolutionObservation(
            ResolutionRequest request, EvidenceResolution response) {
    }

    private record IssuerObservation(
            EvidenceReference reference,
            ResolvedEvidence evidence,
            AcceptanceContext context,
            AuthorityDecision decision) {
    }

    private record OwnerObservation(
            OwnerSignoff signoff,
            ResolvedEvidence signature,
            AcceptanceContext context,
            AuthorityDecision decision) {
    }
}
