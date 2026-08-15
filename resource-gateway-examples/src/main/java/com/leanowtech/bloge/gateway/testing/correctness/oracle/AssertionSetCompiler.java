package com.leanowtech.bloge.gateway.testing.correctness.oracle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.CompilationCompatibility;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.EdgeAssertion;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.ErrorAssertion;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.EvaluationKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.ExecutableAssertionSpec;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.GovernanceExpectation;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.InvocationAssertion;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.NodeAssertion;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.NodeOperator;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.OutputAssertion;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.OutputOperator;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.StateEffectAssertion;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionCompilationReport.AssertionDisposition;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionCompilationReport.DispositionStatus;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle.Assertion;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pure compiler that accounts for every typed assertion and never silently drops semantics. */
public final class AssertionSetCompiler {

    private final ObjectMapper mapper;

    public AssertionSetCompiler(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public AssertionCompilationReport compile(
            AssertionSet source,
            AssertionEvaluatorProfile profile
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(profile, "profile");
        List<AssertionDisposition> dispositions = new ArrayList<>();
        List<Assertion> lowered = new ArrayList<>();
        Set<String> usedCapabilities = new LinkedHashSet<>();
        int evidenceCount = 0;
        int gateCount = 0;
        int unsupportedCount = 0;

        for (ExecutableAssertionSpec spec : source.assertions()) {
            String capability = capability(spec);
            if (spec.evaluationKind() == EvaluationKind.GATE) {
                gateCount++;
                usedCapabilities.add(capability);
                dispositions.add(disposition(
                        spec, capability, DispositionStatus.RETAINED_GATE, "", 0));
                continue;
            }
            if (!profile.supports(capability)) {
                unsupportedCount++;
                dispositions.add(disposition(
                        spec, capability, DispositionStatus.UNSUPPORTED,
                        "RG.CORRECTNESS.EVALUATOR_CAPABILITY_UNSUPPORTED", 0));
                continue;
            }
            if (spec.evaluationKind() == EvaluationKind.EVIDENCE) {
                evidenceCount++;
                usedCapabilities.add(capability);
                dispositions.add(disposition(
                        spec, capability, DispositionStatus.BOUND_EVIDENCE, "", 0));
                continue;
            }

            Assertion runtime = lowerRuntime(spec);
            if (runtime == null) {
                unsupportedCount++;
                dispositions.add(disposition(
                        spec, capability, DispositionStatus.UNSUPPORTED,
                        "RG.CORRECTNESS.RUNTIME_LOWERING_UNAVAILABLE", 0));
            } else {
                lowered.add(runtime);
                usedCapabilities.add(capability);
                dispositions.add(disposition(
                        spec, capability, DispositionStatus.COMPILED_RUNTIME, "", 1));
            }
        }

        int executableCount = lowered.size() + evidenceCount;
        CompilationCompatibility compatibility;
        if (unsupportedCount > 0) {
            compatibility = new CompilationCompatibility(
                    false, profile.evaluatorVersion(), List.copyOf(usedCapabilities),
                    "RG.CORRECTNESS.ASSERTION_UNSUPPORTED");
        } else if (executableCount == 0) {
            compatibility = new CompilationCompatibility(
                    false, profile.evaluatorVersion(), List.copyOf(usedCapabilities),
                    "RG.CORRECTNESS.ASSERTION_NONE");
        } else {
            compatibility = new CompilationCompatibility(
                    true, profile.evaluatorVersion(), List.copyOf(usedCapabilities), "");
        }
        return new AssertionCompilationReport(
                "", CorrectnessProtocolFingerprint.fingerprint(mapper, source), compatibility,
                dispositions, lowered, evidenceCount, gateCount);
    }

    private static AssertionDisposition disposition(
            ExecutableAssertionSpec spec,
            String capability,
            DispositionStatus status,
            String reasonCode,
            int loweredCount
    ) {
        return new AssertionDisposition(
                spec.assertionId(), spec.evaluationKind(), capability, status,
                reasonCode, loweredCount);
    }

    private static String capability(ExecutableAssertionSpec spec) {
        String kind;
        String operation;
        if (spec instanceof OutputAssertion output) {
            kind = "OUTPUT";
            operation = output.operator().name();
        } else if (spec instanceof ErrorAssertion) {
            kind = "ERROR";
            operation = "EXPECTED";
        } else if (spec instanceof NodeAssertion node) {
            kind = "NODE";
            operation = node.operator().name();
        } else if (spec instanceof EdgeAssertion edge) {
            kind = "EDGE";
            operation = edge.operator().name();
        } else if (spec instanceof InvocationAssertion invocation) {
            kind = "INVOCATION";
            operation = invocation.operator().name();
        } else if (spec instanceof StateEffectAssertion stateEffect) {
            kind = "STATE_EFFECT";
            operation = stateEffect.operator().name();
        } else if (spec instanceof GovernanceExpectation governance) {
            kind = "GOVERNANCE";
            operation = governance.operator().name();
        } else {
            throw new IllegalArgumentException(
                    "Unknown assertion implementation: " + spec.getClass().getName());
        }
        return spec.evaluationKind().name() + ":" + kind + ":" + operation;
    }

    private static Assertion lowerRuntime(ExecutableAssertionSpec spec) {
        if (spec instanceof OutputAssertion output) {
            return lowerOutput(output);
        }
        if (spec instanceof NodeAssertion node && node.operator() == NodeOperator.STATUS) {
            return new Assertion(
                    "NODE_STATUS", node.nodeId(), "", "EQUALS", node.expected(), null);
        }
        return null;
    }

    private static Assertion lowerOutput(OutputAssertion output) {
        String operator = switch (output.operator()) {
            case EQUALS -> "EQUALS";
            case CONTAINS -> "CONTAINS";
            case SCHEMA -> "MATCHES_SCHEMA";
            case EXISTS -> "EXISTS";
            case ABSENT -> "ABSENT";
            case RANGE, SET -> null;
        };
        if (operator == null) return null;
        String path = output.operator() == OutputOperator.SCHEMA ? "" : output.path();
        return new Assertion("OUTPUT_PATH", "", path, operator, output.expected(), null);
    }
}
