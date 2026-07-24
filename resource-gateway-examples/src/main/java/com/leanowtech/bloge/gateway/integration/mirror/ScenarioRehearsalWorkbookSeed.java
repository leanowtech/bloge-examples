package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Deterministic payload-free ANEKE correctness-workbook seed for one Scenario rehearsal.
 *
 * <p>The seed binds the exact ScenarioPack, compiled execution license, signed aggregate
 * evidence, retention-registration proof, and ordered case/assertion closure. It does not replace
 * any source artifact and does not make an owner approval decision. A consumer can reconstruct
 * the seed from those immutable sources without receiving TestSuite inputs, fixture values,
 * Session entities, node payloads, or external responses.</p>
 *
 * @param schemaVersion workbook-seed protocol version
 * @param seedFingerprint canonical fingerprint with this field blanked
 * @param scope complete enterprise namespace
 * @param runId exact terminal Scenario aggregate run
 * @param requestId exact aggregate idempotency identity
 * @param scenarioPackRef exact authored ScenarioPack revision
 * @param compiledPlanRef exact compiler-issued execution license
 * @param targetCapabilityRef exact rehearsed capability
 * @param evidenceBundleFingerprint exact signed aggregate evidence
 * @param resultFingerprint exact content-addressed aggregate result
 * @param evidenceKeyId aggregate evidence signing-key identity
 * @param retentionProof stable signed registration event
 * @param outcome aggregate correctness outcome
 * @param summary server-derived aggregate counters
 * @param cases complete ordered case and assertion projections
 * @param gateReady whether the evidence closure has no deterministic publication blocker
 * @param blockers sorted bounded publication blockers
 */
public record ScenarioRehearsalWorkbookSeed(
        String schemaVersion,
        String seedFingerprint,
        CapabilitySnapshot.Scope scope,
        String runId,
        String requestId,
        MirrorArtifactRef scenarioPackRef,
        MirrorArtifactRef compiledPlanRef,
        MirrorArtifactRef targetCapabilityRef,
        String evidenceBundleFingerprint,
        String resultFingerprint,
        String evidenceKeyId,
        ScenarioRehearsalRetentionEvent retentionProof,
        ScenarioCaseRehearsalResult.Outcome outcome,
        ScenarioRehearsalResult.Summary summary,
        List<CaseResult> cases,
        boolean gateReady,
        List<String> blockers
) {
    /** Current Scenario correctness-workbook seed version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalWorkbookSeed.v1";
    /** Maximum canonical seed bytes admitted to fingerprinting. */
    public static final int MAXIMUM_CANONICAL_BYTES = 8 * 1024 * 1024;
    /** Maximum deterministic publication blockers represented by one seed. */
    public static final int MAXIMUM_BLOCKERS = 16;
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern MACHINE_CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");

    /** Validates source identities, ordered case closure, and conservative gate readiness. */
    public ScenarioRehearsalWorkbookSeed {
        schemaVersion = version(schemaVersion);
        seedFingerprint = optionalFingerprint(
                seedFingerprint, "seedFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        runId = identifier(runId, "runId");
        if (!ScenarioRehearsalRunIdentity.hasCanonicalShape(runId)) {
            throw new IllegalArgumentException(
                    "workbook runId must be a canonical Scenario identity");
        }
        requestId = identifier(requestId, "requestId");
        scenarioPackRef = requireKind(
                scenarioPackRef, "SCENARIO_PACK", "scenarioPackRef");
        compiledPlanRef = requireKind(
                compiledPlanRef,
                "COMPILED_REHEARSAL_PLAN",
                "compiledPlanRef");
        targetCapabilityRef = requireKind(
                targetCapabilityRef,
                "CAPABILITY",
                "targetCapabilityRef");
        evidenceBundleFingerprint = fingerprint(
                evidenceBundleFingerprint,
                "evidenceBundleFingerprint");
        resultFingerprint = fingerprint(
                resultFingerprint, "resultFingerprint");
        evidenceKeyId = verificationKeyId(
                evidenceKeyId, "evidenceKeyId");
        retentionProof = Objects.requireNonNull(
                retentionProof, "retentionProof");
        if (retentionProof.revision() != 1
                || retentionProof.type()
                != ScenarioRehearsalRetentionEvent.Type
                .RETENTION_REGISTERED
                || !retentionProof.scope().equals(scope)
                || !retentionProof.runId().equals(runId)
                || !retentionProof.requestId().equals(requestId)
                || !retentionProof.evidenceBundleFingerprint()
                .equals(evidenceBundleFingerprint)
                || !retentionProof.evidenceSeal().signed()
                || !retentionProof.evidenceSeal()
                .materialFingerprint().equals(
                        retentionProof.eventFingerprint())) {
            throw new IllegalArgumentException(
                    "workbook retention proof is not a signed registration event");
        }
        outcome = Objects.requireNonNull(outcome, "outcome");
        summary = Objects.requireNonNull(summary, "summary");
        cases = cases == null ? List.of() : List.copyOf(cases);
        if (cases.isEmpty()
                || cases.size() > ScenarioPack.MAXIMUM_CASES
                || cases.size() != summary.totalCases()) {
            throw new IllegalArgumentException(
                    "workbook cases differ from aggregate summary");
        }
        Set<MirrorArtifactRef> caseRefs = new HashSet<>();
        for (int index = 0; index < cases.size(); index++) {
            CaseResult result = Objects.requireNonNull(
                    cases.get(index), "caseResult");
            if (result.caseIndex() != index
                    || !caseRefs.add(result.scenarioCaseRef())) {
                throw new IllegalArgumentException(
                        "workbook cases must be ordered and unique");
            }
        }
        if (!summary.equals(summary(cases))
                || outcome != deriveOutcome(cases)) {
            throw new IllegalArgumentException(
                    "workbook outcome and summary must be case-derived");
        }
        blockers = orderedBlockers(blockers);
        List<String> expectedBlockers =
                deriveBlockers(outcome, cases);
        if (!blockers.equals(expectedBlockers)
                || gateReady != blockers.isEmpty()) {
            throw new IllegalArgumentException(
                    "workbook gate readiness must be evidence-derived");
        }
    }

    /**
     * Payload-free correctness projection for one compiled Scenario case.
     *
     * @param caseIndex zero-based compiled order
     * @param scenarioCaseRef exact authored ScenarioCase
     * @param caseType business coverage intent
     * @param testSuiteRef exact governed TestSuite revision
     * @param testCaseId exact TestSuite case identity
     * @param mirrorPlanRef exact MirrorPlan generation
     * @param fixtureBundleRef exact governed fixture generation
     * @param sessionCheckpointRef optional exact isolated Session checkpoint
     * @param childRunId exact child Mirror run, blank when execution produced no evidence
     * @param childEvidenceBundleFingerprint exact child evidence, blank with no evidence
     * @param evidenceStatus child execution evidence status, blank with no evidence
     * @param evidenceClass child evidence trust class, blank with no evidence
     * @param outcome case correctness outcome
     * @param diagnosticCode stable non-passing reason, blank on pass
     * @param assertionResults complete ordered handling-assertion closure
     */
    public record CaseResult(
            int caseIndex,
            MirrorArtifactRef scenarioCaseRef,
            ScenarioCase.CaseType caseType,
            MirrorArtifactRef testSuiteRef,
            String testCaseId,
            MirrorArtifactRef mirrorPlanRef,
            MirrorArtifactRef fixtureBundleRef,
            MirrorArtifactRef sessionCheckpointRef,
            String childRunId,
            String childEvidenceBundleFingerprint,
            String evidenceStatus,
            String evidenceClass,
            ScenarioCaseRehearsalResult.Outcome outcome,
            String diagnosticCode,
            List<ScenarioHandlingAssertionResult> assertionResults
    ) {
        /** Validates one complete evidence-backed or pre-evidence case projection. */
        public CaseResult {
            if (caseIndex < 0
                    || caseIndex >= ScenarioPack.MAXIMUM_CASES) {
                throw new IllegalArgumentException(
                        "workbook caseIndex is outside the Scenario bound");
            }
            scenarioCaseRef = requireKind(
                    scenarioCaseRef,
                    "SCENARIO_CASE",
                    "scenarioCaseRef");
            caseType = Objects.requireNonNull(
                    caseType, "caseType");
            testSuiteRef = requireKind(
                    testSuiteRef,
                    "TEST_SUITE",
                    "testSuiteRef");
            testCaseId = identifier(testCaseId, "testCaseId");
            mirrorPlanRef = requireKind(
                    mirrorPlanRef,
                    "MIRROR_PLAN",
                    "mirrorPlanRef");
            fixtureBundleRef = requireKind(
                    fixtureBundleRef,
                    "FIXTURE_BUNDLE",
                    "fixtureBundleRef");
            if (sessionCheckpointRef != null) {
                sessionCheckpointRef = requireKind(
                        sessionCheckpointRef,
                        "MIRROR_SESSION_CHECKPOINT",
                        "sessionCheckpointRef");
            }
            childRunId = optionalIdentifier(
                    childRunId, "childRunId");
            childEvidenceBundleFingerprint =
                    optionalFingerprint(
                            childEvidenceBundleFingerprint,
                            "childEvidenceBundleFingerprint");
            evidenceStatus = optionalMachineCode(
                    evidenceStatus, "evidenceStatus");
            evidenceClass = optionalMachineCode(
                    evidenceClass, "evidenceClass");
            outcome = Objects.requireNonNull(outcome, "outcome");
            diagnosticCode = optionalMachineCode(
                    diagnosticCode, "diagnosticCode");
            assertionResults = assertionResults == null
                    ? List.of() : List.copyOf(assertionResults);
            boolean noEvidence = childRunId.isBlank()
                    && childEvidenceBundleFingerprint.isBlank()
                    && evidenceStatus.isBlank()
                    && evidenceClass.isBlank();
            boolean completeEvidence = !childRunId.isBlank()
                    && !childEvidenceBundleFingerprint.isBlank()
                    && !evidenceStatus.isBlank()
                    && !evidenceClass.isBlank();
            if (!noEvidence && !completeEvidence
                    || noEvidence && (!assertionResults.isEmpty()
                    || outcome
                    == ScenarioCaseRehearsalResult.Outcome.PASS)
                    || completeEvidence && assertionResults.isEmpty()
                    || outcome
                    == ScenarioCaseRehearsalResult.Outcome.PASS
                    && !diagnosticCode.isBlank()
                    || outcome
                    != ScenarioCaseRehearsalResult.Outcome.PASS
                    && diagnosticCode.isBlank()) {
                throw new IllegalArgumentException(
                        "workbook case evidence closure is inconsistent");
            }
            LinkedHashSet<MirrorArtifactRef> assertions =
                    new LinkedHashSet<>();
            for (ScenarioHandlingAssertionResult result
                    : assertionResults) {
                ScenarioHandlingAssertionResult value =
                        Objects.requireNonNull(
                                result, "assertionResult");
                if (!assertions.add(value.assertionRef())
                        || !childRunId.equals(value.runId())
                        || !childEvidenceBundleFingerprint.equals(
                        value.evidenceBundleFingerprint())
                        || !mirrorPlanRef.fingerprint().equals(
                        value.planFingerprint())) {
                    throw new IllegalArgumentException(
                            "workbook assertion closure differs from child evidence");
                }
            }
        }

        /** @return true when this case has a complete child evidence identity */
        public boolean evidenceBacked() {
            return !childRunId.isBlank();
        }
    }

    /**
     * Projects verified immutable sources into one deterministic workbook seed.
     *
     * <p>The caller must obtain the plan, evidence, retention state, and event chain from
     * boundaries that already verify their content addresses and signatures.</p>
     *
     * @param mapper canonical protocol mapper
     * @param plan verified compiler-issued execution license
     * @param bundle independently verified signed aggregate evidence
     * @param retentionState verified current retention projection
     * @param retentionEvents verified complete retention event chain
     * @return sealed deterministic payload-free workbook seed
     */
    public static ScenarioRehearsalWorkbookSeed project(
            ObjectMapper mapper,
            CompiledScenarioRehearsalPlan plan,
            ScenarioRehearsalEvidenceBundle bundle,
            ScenarioRehearsalRetentionState retentionState,
            List<ScenarioRehearsalRetentionEvent> retentionEvents) {
        Objects.requireNonNull(mapper, "mapper");
        CompiledScenarioRehearsalPlanIntegrity.verify(
                mapper, Objects.requireNonNull(plan, "plan"));
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(retentionState, "retentionState");
        List<ScenarioRehearsalRetentionEvent> events =
                retentionEvents == null
                        ? List.of() : List.copyOf(retentionEvents);
        if (events.isEmpty()) {
            throw new IllegalArgumentException(
                    "workbook requires a retention registration proof");
        }
        ScenarioRehearsalResult aggregate = bundle.result();
        MirrorArtifactRef planRef =
                CompiledScenarioRehearsalPlanIntegrity.reference(plan);
        if (!planRef.equals(aggregate.compiledPlanRef())
                || !plan.scope().equals(aggregate.scope())
                || !plan.targetCapabilityRef().equals(
                aggregate.targetCapabilityRef())
                || !aggregate.scope().equals(
                retentionState.scope())
                || !aggregate.requestId().equals(
                retentionState.requestId())
                || !bundle.attestation().runId().equals(
                retentionState.runId())
                || !bundle.bundleFingerprint().equals(
                retentionState.evidenceBundleFingerprint())
                || retentionState.status()
                != ScenarioRehearsalRetentionState.Status.RETAINED) {
            throw new IllegalArgumentException(
                    "workbook source identities do not form one active closure");
        }
        ScenarioRehearsalRetentionEvent registration =
                events.getFirst();
        if (registration.revision() != 1
                || registration.type()
                != ScenarioRehearsalRetentionEvent.Type
                .RETENTION_REGISTERED
                || !registration.scope().equals(aggregate.scope())
                || !registration.runId().equals(
                bundle.attestation().runId())
                || !registration.requestId().equals(
                aggregate.requestId())
                || !registration.evidenceBundleFingerprint().equals(
                bundle.bundleFingerprint())
                || !registration.evidenceSeal().signed()
                || !registration.evidenceSeal()
                .materialFingerprint().equals(
                        registration.eventFingerprint())
                || !retentionState.retainUntil().equals(
                registration.retainUntil())) {
            throw new IllegalArgumentException(
                    "workbook retention registration proof is invalid");
        }
        if (plan.cases().size()
                != aggregate.caseResults().size()) {
            throw new IllegalArgumentException(
                    "workbook compiled and executed case counts differ");
        }
        Set<MirrorArtifactRef> planAssertionClosure =
                new HashSet<>(plan.assertionRefs());
        Set<MirrorArtifactRef> caseAssertionClosure =
                new HashSet<>();
        List<CaseResult> projectedCases =
                java.util.stream.IntStream.range(
                                0, plan.cases().size())
                        .mapToObj(index -> projectCase(
                                index,
                                plan.cases().get(index),
                                aggregate.caseResults().get(index),
                                caseAssertionClosure))
                        .toList();
        if (!planAssertionClosure.equals(
                caseAssertionClosure)) {
            throw new IllegalArgumentException(
                    "workbook assertion closure differs from compiled plan");
        }
        List<String> blockers = deriveBlockers(
                aggregate.outcome(), projectedCases);
        ScenarioRehearsalWorkbookSeed material =
                new ScenarioRehearsalWorkbookSeed(
                        SCHEMA_VERSION,
                        "",
                        aggregate.scope(),
                        bundle.attestation().runId(),
                        aggregate.requestId(),
                        plan.scenarioPackRef(),
                        planRef,
                        aggregate.targetCapabilityRef(),
                        bundle.bundleFingerprint(),
                        aggregate.resultFingerprint(),
                        bundle.attestation().keyId(),
                        registration,
                        aggregate.outcome(),
                        aggregate.summary(),
                        projectedCases,
                        blockers.isEmpty(),
                        blockers);
        return material.withFingerprint(
                ProtocolFingerprint.ofBounded(
                        mapper,
                        material,
                        MAXIMUM_CANONICAL_BYTES));
    }

    /**
     * Recomputes and checks this seed's self-fingerprint.
     *
     * @param mapper canonical protocol mapper
     */
    public void verify(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        if (!ProtocolFingerprint.ofBounded(
                mapper,
                withFingerprint(""),
                MAXIMUM_CANONICAL_BYTES)
                .equals(seedFingerprint)) {
            throw new IllegalArgumentException(
                    "Scenario workbook seed fingerprint mismatch");
        }
    }

    /** @return identical seed carrying a replacement canonical fingerprint */
    public ScenarioRehearsalWorkbookSeed withFingerprint(
            String fingerprint) {
        return new ScenarioRehearsalWorkbookSeed(
                schemaVersion, fingerprint, scope,
                runId, requestId, scenarioPackRef,
                compiledPlanRef, targetCapabilityRef,
                evidenceBundleFingerprint, resultFingerprint,
                evidenceKeyId, retentionProof, outcome,
                summary, cases, gateReady, blockers);
    }

    /** Keeps exact evidence coordinates out of generic logs. */
    @Override
    public String toString() {
        return "ScenarioRehearsalWorkbookSeed[runId="
                + runId + ", outcome=" + outcome
                + ", cases=" + cases.size()
                + ", gateReady=" + gateReady + "]";
    }

    private static CaseResult projectCase(
            int index,
            CompiledScenarioRehearsalPlan.CaseBinding binding,
            ScenarioCaseRehearsalResult result,
            Set<MirrorArtifactRef> assertionClosure) {
        List<MirrorArtifactRef> actualAssertions =
                result.assertionResults().stream()
                        .map(ScenarioHandlingAssertionResult::assertionRef)
                        .toList();
        if (result.caseIndex() != index
                || !binding.scenarioCaseRef().equals(
                result.scenarioCaseRef())
                || binding.caseType() != result.caseType()
                || !binding.testSuiteRef().equals(
                result.testSuiteRef())
                || !binding.testCaseId().equals(
                result.testCaseId())
                || !binding.mirrorPlanRef().equals(
                result.mirrorPlanRef())
                || !binding.fixtureBundleRef().equals(
                result.fixtureBundleRef())
                || !Objects.equals(
                binding.sessionCheckpointRef(),
                result.sessionCheckpointRef())
                || !binding.assertionRefs().equals(
                actualAssertions)) {
            throw new IllegalArgumentException(
                    "workbook case differs from compiled binding");
        }
        assertionClosure.addAll(actualAssertions);
        return new CaseResult(
                index,
                result.scenarioCaseRef(),
                result.caseType(),
                result.testSuiteRef(),
                result.testCaseId(),
                result.mirrorPlanRef(),
                result.fixtureBundleRef(),
                result.sessionCheckpointRef(),
                result.runId(),
                result.evidenceBundleFingerprint(),
                result.evidenceStatus() == null
                        ? "" : result.evidenceStatus().name(),
                result.evidenceClass() == null
                        ? "" : result.evidenceClass().name(),
                result.outcome(),
                result.diagnosticCode(),
                result.assertionResults());
    }

    private static ScenarioRehearsalResult.Summary summary(
            List<CaseResult> cases) {
        return new ScenarioRehearsalResult.Summary(
                cases.size(),
                countOutcome(
                        cases,
                        ScenarioCaseRehearsalResult.Outcome.PASS),
                countOutcome(
                        cases,
                        ScenarioCaseRehearsalResult.Outcome.FAIL),
                countOutcome(
                        cases,
                        ScenarioCaseRehearsalResult.Outcome
                                .INDETERMINATE),
                Math.toIntExact(cases.stream()
                        .mapToLong(value ->
                                value.assertionResults().size())
                        .sum()),
                Math.toIntExact(cases.stream()
                        .flatMap(value ->
                                value.assertionResults().stream())
                        .filter(value ->
                                value.severity()
                                == CaseHandlingAssertion.Severity.BLOCKER
                                && value.outcome()
                                == ScenarioHandlingAssertionResult.Outcome.FAIL)
                        .count()),
                Math.toIntExact(cases.stream()
                        .flatMap(value ->
                                value.assertionResults().stream())
                        .filter(value ->
                                value.severity()
                                == CaseHandlingAssertion.Severity.BLOCKER
                                && value.outcome()
                                == ScenarioHandlingAssertionResult.Outcome
                                .INDETERMINATE)
                        .count()),
                Math.toIntExact(cases.stream()
                        .flatMap(value ->
                                value.assertionResults().stream())
                        .filter(value ->
                                value.severity()
                                == CaseHandlingAssertion.Severity.WARNING
                                && value.outcome()
                                == ScenarioHandlingAssertionResult.Outcome.FAIL)
                        .count()),
                Math.toIntExact(cases.stream()
                        .flatMap(value ->
                                value.assertionResults().stream())
                        .filter(value ->
                                value.severity()
                                == CaseHandlingAssertion.Severity.WARNING
                                && value.outcome()
                                == ScenarioHandlingAssertionResult.Outcome
                                .INDETERMINATE)
                        .count()));
    }

    private static int countOutcome(
            List<CaseResult> cases,
            ScenarioCaseRehearsalResult.Outcome outcome) {
        return Math.toIntExact(cases.stream()
                .filter(value -> value.outcome() == outcome)
                .count());
    }

    private static ScenarioCaseRehearsalResult.Outcome deriveOutcome(
            List<CaseResult> cases) {
        if (cases.stream().anyMatch(value ->
                value.outcome()
                == ScenarioCaseRehearsalResult.Outcome.FAIL)) {
            return ScenarioCaseRehearsalResult.Outcome.FAIL;
        }
        if (cases.stream().anyMatch(value ->
                value.outcome()
                == ScenarioCaseRehearsalResult.Outcome
                .INDETERMINATE)) {
            return ScenarioCaseRehearsalResult.Outcome.INDETERMINATE;
        }
        return ScenarioCaseRehearsalResult.Outcome.PASS;
    }

    private static List<String> deriveBlockers(
            ScenarioCaseRehearsalResult.Outcome outcome,
            List<CaseResult> cases) {
        TreeSet<String> blockers = new TreeSet<>();
        if (outcome
                == ScenarioCaseRehearsalResult.Outcome.FAIL) {
            blockers.add("REHEARSAL_FAILED");
        } else if (outcome
                == ScenarioCaseRehearsalResult.Outcome
                .INDETERMINATE) {
            blockers.add("REHEARSAL_INDETERMINATE");
        }
        for (CaseResult result : cases) {
            if (!result.evidenceBacked()) {
                blockers.add("CASE_EVIDENCE_MISSING");
            } else if (!MirrorRunEvidence.EvidenceClass.CERTIFIABLE
                    .name().equals(result.evidenceClass())) {
                blockers.add("CHILD_EVIDENCE_NOT_CERTIFIABLE");
            }
            for (ScenarioHandlingAssertionResult assertion
                    : result.assertionResults()) {
                if (assertion.severity()
                        != CaseHandlingAssertion.Severity.BLOCKER) {
                    continue;
                }
                if (assertion.outcome()
                        == ScenarioHandlingAssertionResult.Outcome.FAIL) {
                    blockers.add("BLOCKER_ASSERTION_FAILED");
                } else if (assertion.outcome()
                        == ScenarioHandlingAssertionResult.Outcome
                        .INDETERMINATE) {
                    blockers.add(
                            "BLOCKER_ASSERTION_INDETERMINATE");
                }
            }
        }
        return List.copyOf(blockers);
    }

    private static List<String> orderedBlockers(
            List<String> values) {
        TreeSet<String> result = new TreeSet<>();
        if (values != null) {
            for (String value : values) {
                String blocker = identifier(value, "blocker");
                if (!MACHINE_CODE.matcher(blocker).matches()
                        || !result.add(blocker)) {
                    throw new IllegalArgumentException(
                            "workbook blockers must be unique machine codes");
                }
            }
        }
        if (result.size() > MAXIMUM_BLOCKERS) {
            throw new IllegalArgumentException(
                    "workbook blockers exceed the protocol bound");
        }
        return List.copyOf(result);
    }

    private static MirrorArtifactRef requireKind(
            MirrorArtifactRef value,
            String kind,
            String field) {
        MirrorArtifactRef required =
                Objects.requireNonNull(value, field);
        if (!kind.equals(required.kind())) {
            throw new IllegalArgumentException(
                    field + " must reference " + kind);
        }
        return required;
    }

    private static String version(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            return SCHEMA_VERSION;
        }
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario rehearsal workbook seed version");
        }
        return normalized;
    }

    private static String identifier(
            String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return normalized;
    }

    private static String optionalIdentifier(
            String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank()
                && !IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return normalized;
    }

    private static String verificationKeyId(
            String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()
                || normalized.length() > 1_024
                || normalized.contains("\r")
                || normalized.contains("\n")) {
            throw new IllegalArgumentException(
                    field + " must contain 1 to 1024 safe characters");
        }
        return normalized;
    }

    private static String optionalMachineCode(
            String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank()
                && !MACHINE_CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return normalized;
    }

    private static String fingerprint(
            String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a canonical SHA-256 value");
        }
        return normalized;
    }

    private static String optionalFingerprint(
            String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank()
                && !FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be blank or canonical SHA-256");
        }
        return normalized;
    }
}
