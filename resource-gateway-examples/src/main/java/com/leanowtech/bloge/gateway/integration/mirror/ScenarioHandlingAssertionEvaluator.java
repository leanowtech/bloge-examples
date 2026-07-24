package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Deterministic evaluator for business handling assertions over verified Mirror Evidence.
 *
 * <p>This component never reads graph payloads, fixture payloads, mutable registries, or runtime
 * state. It accepts only a {@link MirrorEvidenceIntegrityService.VerifiedBundle} capability
 * created by the evidence integrity boundary. Dimensions represented by the current evidence
 * protocol are evaluated exactly; dimensions that require unavailable path-level output, schema,
 * fallback-order, compensation, or invariant facts return {@code INDETERMINATE}. This preserves
 * the distinction between "the graph ran" and "the business handling assertion was proven".</p>
 */
public final class ScenarioHandlingAssertionEvaluator {
    /** Evaluator implementation generation included in unsupported-fact limitations. */
    public static final String EVALUATOR_VERSION = "SCENARIO_ASSERTION_EVALUATOR_V1";
    private static final ScenarioHandlingAssertionResult.ReasonCode MATCHED =
            ScenarioHandlingAssertionResult.ReasonCode.ASSERTION_MATCHED;
    private static final ScenarioHandlingAssertionResult.ReasonCode MISMATCH =
            ScenarioHandlingAssertionResult.ReasonCode.ASSERTION_MISMATCH;
    private static final ScenarioHandlingAssertionResult.ReasonCode ABSENT =
            ScenarioHandlingAssertionResult.ReasonCode
                    .ASSERTION_OBSERVATION_ABSENT;
    private static final ScenarioHandlingAssertionResult.ReasonCode INCOMPLETE =
            ScenarioHandlingAssertionResult.ReasonCode
                    .ASSERTION_EVIDENCE_INCOMPLETE;
    private static final ScenarioHandlingAssertionResult.ReasonCode UNAVAILABLE =
            ScenarioHandlingAssertionResult.ReasonCode
                    .ASSERTION_EVIDENCE_FACT_UNAVAILABLE;
    private static final String NON_CANONICAL_ERROR = "NON_CANONICAL_ERROR_CODE";
    private static final Pattern MACHINE_VALUE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,191}");

    private final ObjectMapper mapper;

    /**
     * Creates an evaluator using the canonical protocol mapper.
     *
     * @param mapper canonical mapper used to seal deterministic results
     */
    public ScenarioHandlingAssertionEvaluator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Evaluates one exact assertion against one independently verified evidence bundle.
     *
     * @param assertion sealed, active, same-scope handling assertion
     * @param verifiedBundle integrity-bound signed Mirror Evidence
     * @return sealed payload-free assertion result
     */
    public ScenarioHandlingAssertionResult evaluate(
            CaseHandlingAssertion assertion,
            MirrorEvidenceIntegrityService.VerifiedBundle verifiedBundle) {
        Objects.requireNonNull(assertion, "assertion");
        MirrorEvidenceBundle bundle =
                Objects.requireNonNull(
                        verifiedBundle, "verifiedBundle").bundle();
        ScenarioPackIntegrity.verifyAssertion(mapper, assertion);
        MirrorRunEvidence evidence = bundle.evidence();
        if (assertion.lifecycle() != CapabilitySnapshot.Lifecycle.ACTIVE
                || !assertion.scope().equals(evidence.scope())) {
            throw new IllegalArgumentException(
                    "handling assertion must be active and match the evidence scope");
        }
        ArtifactProvenance provenance = assertion.provenance();
        if (provenance.approvedAt() == null
                || provenance.approvedAt().isAfter(evidence.startedAt())
                || (provenance.expiresAt() != null
                && !evidence.completedAt().isBefore(provenance.expiresAt()))
                || !provenance.revocationRef().isBlank()) {
            throw new IllegalArgumentException(
                    "handling assertion was not approved for the evidence window");
        }
        if (evidence.status() == MirrorRunEvidence.Status.EVIDENCE_INCOMPLETE) {
            return result(assertion, bundle,
                    ScenarioHandlingAssertionResult.Outcome.INDETERMINATE,
                    INCOMPLETE,
                    new ScenarioHandlingAssertionResult.ObservedFacts(
                            List.of(evidence.status().name()), List.of(), List.of(),
                            List.of(evidence.evidenceClass().name()), null, null, null,
                            List.of(INCOMPLETE.name())));
        }

        Evaluation evaluation = switch (assertion.observation()) {
            case NODE_STATUS -> nodeStatus(assertion, evidence);
            case EDGE_STATUS -> edgeStatus(assertion, evidence);
            case CAPABILITY_OCCURRENCE -> capabilityOccurrence(assertion, evidence);
            case INVOCATION_INPUT -> invocationInput(assertion, evidence);
            case ERROR -> error(assertion, evidence);
            case STATE_TRANSITION -> stateTransition(assertion, evidence);
            case SIDE_EFFECT_RECEIPT -> sideEffectReceipt(assertion, evidence);
            case GOVERNANCE_EXPECTATION -> governance(assertion, evidence);
            case LATENCY_BUDGET -> latency(assertion, evidence);
            case RETRY_BUDGET -> retryBudget(assertion, evidence);
            case RESOURCE_BUDGET -> resourceBudget(assertion, evidence);
            case GRAPH_OUTPUT_VALUE, GRAPH_OUTPUT_SCHEMA, FALLBACK, COMPENSATION,
                    FINAL_STATE_INVARIANT -> unavailable(assertion.observation());
        };
        return result(
                assertion, bundle, evaluation.outcome(),
                evaluation.reasonCode(), evaluation.observed());
    }

    private Evaluation nodeStatus(
            CaseHandlingAssertion assertion,
            MirrorRunEvidence evidence) {
        List<MirrorRunEvidence.NodeTrace> nodes =
                evidence.nodeTraces().stream()
                        .filter(nodeFilter(assertion.selector(), evidence))
                        .toList();
        List<String> statuses = nodes.stream()
                .map(MirrorRunEvidence.NodeTrace::status)
                .map(ScenarioHandlingAssertionEvaluator::machineOrUnknown)
                .distinct()
                .sorted()
                .toList();
        return statusEvaluation(statuses, assertion.expectation().statuses());
    }

    private Evaluation edgeStatus(
            CaseHandlingAssertion assertion,
            MirrorRunEvidence evidence) {
        List<String> statuses = evidence.edgeTraces().stream()
                .filter(edge -> assertion.selector().edgeId().equals(edge.edgeId()))
                .map(MirrorRunEvidence.EdgeTrace::status)
                .map(ScenarioHandlingAssertionEvaluator::machineOrUnknown)
                .distinct()
                .sorted()
                .toList();
        return statusEvaluation(statuses, assertion.expectation().statuses());
    }

    private Evaluation capabilityOccurrence(
            CaseHandlingAssertion assertion,
            MirrorRunEvidence evidence) {
        MirrorArtifactRef capability = assertion.selector().capabilityRef();
        long occurrences = evidence.resolutions().stream()
                .filter(value -> value.capabilityRef().equals(capability))
                .map(ScenarioHandlingAssertionEvaluator::occurrenceCoordinate)
                .distinct()
                .count();
        boolean matched = withinBounds(
                occurrences,
                assertion.expectation().minimumOccurrences(),
                assertion.expectation().maximumOccurrences());
        return counted(matched, occurrences);
    }

    private Evaluation invocationInput(
            CaseHandlingAssertion assertion,
            MirrorRunEvidence evidence) {
        String site = assertion.selector().invocationSiteId();
        Set<String> fingerprints = new LinkedHashSet<>();
        evidence.nodeTraces().stream()
                .filter(node -> site.equals(node.invocationSiteId()))
                .map(MirrorRunEvidence.NodeTrace::inputFingerprint)
                .forEach(fingerprints::add);
        evidence.resolutions().stream()
                .filter(resolution -> site.equals(resolution.invocationSiteId()))
                .map(MirrorResolution::requestFingerprint)
                .forEach(fingerprints::add);
        List<String> observed = fingerprints.stream().sorted().toList();
        if (observed.isEmpty()) {
            return absent(new ScenarioHandlingAssertionResult.ObservedFacts(
                    List.of(), List.of(), List.of(), List.of(),
                    0L, null, null, List.of()));
        }
        boolean matched = observed.stream().allMatch(
                assertion.expectation().valueFingerprint()::equals);
        return evaluated(matched,
                new ScenarioHandlingAssertionResult.ObservedFacts(
                        List.of(), List.of(), observed, List.of(),
                        (long) observed.size(), null, null, List.of()));
    }

    private Evaluation error(
            CaseHandlingAssertion assertion,
            MirrorRunEvidence evidence) {
        Set<String> errorCodes = new LinkedHashSet<>();
        Predicate<MirrorRunEvidence.NodeTrace> nodes =
                nodeFilter(assertion.selector(), evidence);
        evidence.nodeTraces().stream().filter(nodes).forEach(node -> {
            addError(errorCodes, node.errorCode());
            node.attempts().forEach(attempt ->
                    addError(errorCodes, attempt.errorCode()));
        });
        evidence.resolutions().stream()
                .filter(resolutionFilter(assertion.selector()))
                .filter(resolution -> resolution.error() != null)
                .forEach(resolution ->
                        addError(errorCodes, resolution.error().code()));
        List<String> observed = errorCodes.stream().sorted().toList();
        if (observed.isEmpty()) {
            return absent(new ScenarioHandlingAssertionResult.ObservedFacts(
                    List.of(), List.of(), List.of(), List.of(),
                    0L, null, null, List.of()));
        }
        boolean matched = observed.size() == 1
                && observed.contains(assertion.expectation().errorCode());
        return evaluated(matched,
                new ScenarioHandlingAssertionResult.ObservedFacts(
                        List.of(), observed, List.of(), List.of(),
                        (long) observed.size(), null, null, List.of()));
    }

    private Evaluation stateTransition(
            CaseHandlingAssertion assertion,
            MirrorRunEvidence evidence) {
        StateFacts facts = stateFacts(
                evidence.stateEvidence(), assertion.selector());
        return booleanAndStatuses(
                assertion.expectation(), facts.present(), facts.statuses(),
                facts.fingerprints());
    }

    private Evaluation sideEffectReceipt(
            CaseHandlingAssertion assertion,
            MirrorRunEvidence evidence) {
        StateFacts facts = stateFacts(
                evidence.stateEvidence(), assertion.selector());
        return booleanAndStatuses(
                assertion.expectation(), !facts.fingerprints().isEmpty(),
                facts.statuses(), facts.fingerprints());
    }

    private Evaluation governance(
            CaseHandlingAssertion assertion,
            MirrorRunEvidence evidence) {
        boolean certifiable =
                evidence.evidenceClass()
                        == MirrorRunEvidence.EvidenceClass.CERTIFIABLE
                        && evidence.limitations().isEmpty()
                        && evidence.isolation().limitations().isEmpty();
        List<String> sources = List.of(evidence.evidenceClass().name());
        List<String> limitations = canonicalLimitations(evidence);
        boolean matched = true;
        if (assertion.expectation().expectedBoolean() != null) {
            matched &= assertion.expectation().expectedBoolean() == certifiable;
        }
        if (!assertion.expectation().statuses().isEmpty()) {
            matched &= assertion.expectation().statuses()
                    .contains(evidence.evidenceClass().name());
        }
        return evaluated(matched,
                new ScenarioHandlingAssertionResult.ObservedFacts(
                        List.of(), List.of(), List.of(), sources,
                        null, null, certifiable, limitations));
    }

    private Evaluation latency(
            CaseHandlingAssertion assertion,
            MirrorRunEvidence evidence) {
        CaseHandlingAssertion.Selector selector = assertion.selector();
        long duration;
        long occurrences;
        if (!selector.nodeId().isBlank()
                || !selector.invocationSiteId().isBlank()
                || selector.capabilityRef() != null) {
            List<MirrorRunEvidence.NodeTrace> nodes =
                    evidence.nodeTraces().stream()
                            .filter(nodeFilter(selector, evidence))
                            .toList();
            if (nodes.isEmpty()) {
                return absent(new ScenarioHandlingAssertionResult.ObservedFacts(
                        List.of(), List.of(), List.of(), List.of(),
                        0L, null, null, List.of()));
            }
            duration = nodes.stream()
                    .mapToLong(MirrorRunEvidence.NodeTrace::durationMs)
                    .max()
                    .orElseThrow();
            occurrences = nodes.size();
        } else {
            duration = Duration.between(
                    evidence.startedAt(), evidence.completedAt()).toMillis();
            occurrences = 1;
        }
        return evaluated(
                duration <= assertion.expectation().maximumDurationMillis(),
                new ScenarioHandlingAssertionResult.ObservedFacts(
                        List.of(), List.of(), List.of(), List.of(),
                        occurrences, duration, null, List.of()));
    }

    private Evaluation retryBudget(
            CaseHandlingAssertion assertion,
            MirrorRunEvidence evidence) {
        List<MirrorRunEvidence.NodeTrace> nodes =
                evidence.nodeTraces().stream()
                        .filter(nodeFilter(assertion.selector(), evidence))
                        .toList();
        long retries;
        if (!nodes.isEmpty()) {
            retries = nodes.stream()
                    .mapToLong(node -> Math.max(0, node.attempts().size() - 1L))
                    .sum();
        } else {
            Map<String, Integer> maximumAttempt =
                    evidence.resolutions().stream()
                            .filter(resolutionFilter(assertion.selector()))
                            .collect(Collectors.toMap(
                                    ScenarioHandlingAssertionEvaluator
                                            ::occurrenceCoordinate,
                                    MirrorResolution::attempt,
                                    Math::max));
            retries = maximumAttempt.values().stream()
                    .mapToLong(attempt -> Math.max(0, attempt - 1L))
                    .sum();
        }
        return counted(
                retries <= assertion.expectation().maximumOccurrences(),
                retries);
    }

    private Evaluation resourceBudget(
            CaseHandlingAssertion assertion,
            MirrorRunEvidence evidence) {
        CaseHandlingAssertion.Selector selector = assertion.selector();
        long count;
        if (!selector.nodeId().isBlank()
                || !selector.invocationSiteId().isBlank()
                || selector.capabilityRef() != null) {
            long nodes = evidence.nodeTraces().stream()
                    .filter(nodeFilter(selector, evidence)).count();
            long resolutions = evidence.resolutions().stream()
                    .filter(resolutionFilter(selector)).count();
            count = nodes + resolutions;
        } else if (!selector.edgeId().isBlank()) {
            count = evidence.edgeTraces().stream()
                    .filter(edge -> selector.edgeId().equals(edge.edgeId()))
                    .count();
        } else {
            count = Math.addExact(
                    Math.addExact(
                            evidence.nodeTraces().size(),
                            evidence.edgeTraces().size()),
                    evidence.resolutions().size());
        }
        return counted(
                count <= assertion.expectation().maximumOccurrences(),
                count);
    }

    private Evaluation unavailable(
            CaseHandlingAssertion.Observation observation) {
        return new Evaluation(
                ScenarioHandlingAssertionResult.Outcome.INDETERMINATE,
                UNAVAILABLE,
                new ScenarioHandlingAssertionResult.ObservedFacts(
                        List.of(), List.of(), List.of(),
                        List.of(EVALUATOR_VERSION), null, null, null,
                        List.of("MISSING_" + observation.name() + "_FACT")));
    }

    private ScenarioHandlingAssertionResult result(
            CaseHandlingAssertion assertion,
            MirrorEvidenceBundle bundle,
            ScenarioHandlingAssertionResult.Outcome outcome,
            ScenarioHandlingAssertionResult.ReasonCode reasonCode,
            ScenarioHandlingAssertionResult.ObservedFacts observed) {
        MirrorRunEvidence evidence = bundle.evidence();
        ScenarioHandlingAssertionResult material =
                new ScenarioHandlingAssertionResult(
                        "", "", evidence.runId(),
                        bundle.bundleFingerprint(), evidence.planFingerprint(),
                        ScenarioPackIntegrity.reference(assertion),
                        assertion.observation(), outcome, assertion.severity(),
                        assertion.governanceCode(), reasonCode, observed);
        return ScenarioHandlingAssertionResultIntegrity.seal(mapper, material);
    }

    private static Evaluation statusEvaluation(
            List<String> observed,
            List<String> expected) {
        ScenarioHandlingAssertionResult.ObservedFacts facts =
                new ScenarioHandlingAssertionResult.ObservedFacts(
                        observed, List.of(), List.of(), List.of(),
                        (long) observed.size(), null, null, List.of());
        if (observed.isEmpty()) {
            return absent(facts);
        }
        return evaluated(
                observed.stream().allMatch(expected::contains), facts);
    }

    private static Evaluation booleanAndStatuses(
            CaseHandlingAssertion.Expectation expectation,
            boolean actual,
            List<String> statuses,
            List<String> fingerprints) {
        boolean matched = true;
        if (expectation.expectedBoolean() != null) {
            matched &= expectation.expectedBoolean() == actual;
        }
        if (!expectation.statuses().isEmpty()) {
            matched &= !statuses.isEmpty()
                    && statuses.stream().allMatch(
                    expectation.statuses()::contains);
        }
        return evaluated(matched,
                new ScenarioHandlingAssertionResult.ObservedFacts(
                        statuses, List.of(), fingerprints, List.of(),
                        (long) statuses.size(), null, actual, List.of()));
    }

    private static Evaluation counted(boolean matched, long count) {
        return evaluated(matched,
                new ScenarioHandlingAssertionResult.ObservedFacts(
                        List.of(), List.of(), List.of(), List.of(),
                        count, null, null, List.of()));
    }

    private static Evaluation evaluated(
            boolean matched,
            ScenarioHandlingAssertionResult.ObservedFacts facts) {
        return new Evaluation(
                matched
                        ? ScenarioHandlingAssertionResult.Outcome.PASS
                        : ScenarioHandlingAssertionResult.Outcome.FAIL,
                matched ? MATCHED : MISMATCH,
                facts);
    }

    private static Evaluation absent(
            ScenarioHandlingAssertionResult.ObservedFacts facts) {
        return new Evaluation(
                ScenarioHandlingAssertionResult.Outcome.FAIL,
                ABSENT,
                facts);
    }

    private static boolean withinBounds(
            long value, Long minimum, Long maximum) {
        return (minimum == null || value >= minimum)
                && (maximum == null || value <= maximum);
    }

    private static Predicate<MirrorRunEvidence.NodeTrace> nodeFilter(
            CaseHandlingAssertion.Selector selector,
            MirrorRunEvidence evidence) {
        Set<String> capabilitySites =
                selector.capabilityRef() == null
                        ? Set.of()
                        : evidence.externalBindings().stream()
                        .filter(binding -> binding.capabilityRef().equals(
                                selector.capabilityRef()))
                        .map(MirrorRunEvidence.ExternalBinding::invocationSiteId)
                        .collect(Collectors.toUnmodifiableSet());
        return node -> (selector.nodeId().isBlank()
                || selector.nodeId().equals(node.nodeId()))
                && (selector.invocationSiteId().isBlank()
                || selector.invocationSiteId().equals(
                node.invocationSiteId()))
                && (selector.capabilityRef() == null
                || capabilitySites.contains(node.invocationSiteId()));
    }

    private static Predicate<MirrorResolution> resolutionFilter(
            CaseHandlingAssertion.Selector selector) {
        return resolution -> (selector.invocationSiteId().isBlank()
                || selector.invocationSiteId().equals(
                resolution.invocationSiteId()))
                && (selector.capabilityRef() == null
                || selector.capabilityRef().equals(
                resolution.capabilityRef()));
    }

    private static String occurrenceCoordinate(MirrorResolution resolution) {
        return resolution.invocationSiteId() + '\0'
                + resolution.graphPath() + '\0'
                + resolution.correlationKey() + '\0'
                + resolution.occurrence();
    }

    private static void addError(Set<String> errors, String error) {
        if (error == null || error.isBlank()) {
            return;
        }
        String normalized = error.trim();
        errors.add(MACHINE_VALUE.matcher(normalized).matches()
                ? normalized : NON_CANONICAL_ERROR);
    }

    private static String machineOrUnknown(String value) {
        String normalized = value == null ? "" : value.trim();
        return MACHINE_VALUE.matcher(normalized).matches()
                ? normalized : "UNKNOWN";
    }

    private static List<String> canonicalLimitations(
            MirrorRunEvidence evidence) {
        Set<String> limitations = new HashSet<>();
        evidence.limitations().forEach(value ->
                limitations.add(machineOrUnknown(value)));
        evidence.isolation().limitations().forEach(value ->
                limitations.add(machineOrUnknown(value)));
        if (limitations.size()
                > ScenarioHandlingAssertionResult.MAXIMUM_FACTS) {
            return List.of("LIMITATION_SET_EXCEEDS_RESULT_BOUND");
        }
        return limitations.stream().sorted().toList();
    }

    private static StateFacts stateFacts(
            MirrorStateEvidence stateEvidence,
            CaseHandlingAssertion.Selector selector) {
        List<String> statuses = new ArrayList<>();
        List<String> fingerprints = new ArrayList<>();
        if (stateEvidence
                instanceof MirrorStateTransitionRunEvidence transitions) {
            transitions.transitions().stream()
                    .filter(value -> stateMatches(
                            selector, value.invocationSiteId(),
                            value.capabilityRef()))
                    .forEach(value -> {
                        statuses.add(value.replayed()
                                ? "REPLAYED" : "COMMITTED");
                        fingerprints.add(value.receiptFingerprint());
                    });
        } else if (stateEvidence
                instanceof MirrorStateWriteOutcomeRunEvidence outcomes) {
            outcomes.writeAttempts().stream()
                    .filter(value -> stateMatches(
                            selector, value.invocationSiteId(),
                            value.capabilityRef()))
                    .forEach(value -> {
                        statuses.add(value.outcome().name());
                        if (value.transition() != null) {
                            fingerprints.add(
                                    value.transition().receiptFingerprint());
                        }
                    });
        }
        return new StateFacts(
                !statuses.isEmpty(),
                statuses.stream().distinct().sorted().toList(),
                fingerprints.stream().distinct().sorted().toList());
    }

    private static boolean stateMatches(
            CaseHandlingAssertion.Selector selector,
            String invocationSiteId,
            MirrorArtifactRef capabilityRef) {
        return (selector.invocationSiteId().isBlank()
                || selector.invocationSiteId().equals(invocationSiteId))
                && (selector.capabilityRef() == null
                || selector.capabilityRef().equals(capabilityRef));
    }

    private record Evaluation(
            ScenarioHandlingAssertionResult.Outcome outcome,
            ScenarioHandlingAssertionResult.ReasonCode reasonCode,
            ScenarioHandlingAssertionResult.ObservedFacts observed) {
    }

    private record StateFacts(
            boolean present,
            List<String> statuses,
            List<String> fingerprints) {
    }
}
