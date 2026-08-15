package com.leanowtech.bloge.gateway.testing.correctness.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;

import java.util.Comparator;
import java.util.List;

import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.mutableRevision;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.required;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.protocolVersion;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.sortedStrings;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.trimmed;

/** Closed, deterministic technical checks implementing one approved Business Oracle. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssertionSet(
        String schemaVersion,
        String assertionSetId,
        long revision,
        ExactTargetRef target,
        ExactAssetRef oracleRef,
        AssertionLifecycle lifecycle,
        List<ExecutableAssertionSpec> assertions,
        CompilationCompatibility compatibility,
        AuditMetadata metadata
) {
    public static final String SCHEMA_VERSION = "bloge.assertionSet.v1";

    public enum AssertionLifecycle { DRAFT, VALID, STALE }
    public enum EvaluationKind { RUNTIME, EVIDENCE, GATE }
    public enum OutputOperator { EQUALS, CONTAINS, RANGE, SET, SCHEMA, EXISTS, ABSENT }
    public enum NodeOperator { STATUS, SKIPPED, FALLBACK, RETRY_COUNT }
    public enum EdgeOperator { TRANSFER, SCHEMA, DATA_MINIMIZATION }
    public enum InvocationOperator { USED, NOT_USED, COUNT, INPUT_MATCH }
    public enum StateEffectOperator { STATE_TRANSITION, SIDE_EFFECT, COMPENSATION }
    public enum GovernanceOperator { OWNER, RISK, BASIS, EVIDENCE_EXPECTATION }

    public AssertionSet {
        schemaVersion = protocolVersion(schemaVersion, SCHEMA_VERSION);
        assertionSetId = required(assertionSetId, "assertionSetId");
        revision = mutableRevision(revision);
        target = required(target, "target");
        oracleRef = required(oracleRef, "oracleRef");
        lifecycle = lifecycle == null ? AssertionLifecycle.DRAFT : lifecycle;
        assertions = assertions == null ? List.of() : assertions.stream()
                .sorted(Comparator.comparing(ExecutableAssertionSpec::assertionId))
                .toList();
        if (assertions.stream().map(ExecutableAssertionSpec::assertionId).distinct().count()
                != assertions.size()) {
            throw new IllegalArgumentException("Assertion ids must be unique");
        }
        compatibility = compatibility == null
                ? CompilationCompatibility.unsupported("RG.CORRECTNESS.EVALUATOR.UNKNOWN")
                : compatibility;
        metadata = required(metadata, "metadata");
        if (lifecycle == AssertionLifecycle.VALID) {
            boolean executable = assertions.stream().anyMatch(assertion ->
                    assertion.evaluationKind() == EvaluationKind.RUNTIME
                            || assertion.evaluationKind() == EvaluationKind.EVIDENCE);
            if (!executable || !compatibility.supported()) {
                throw new IllegalArgumentException(
                        "Valid Assertion Set requires a supported runtime or evidence assertion");
            }
        }
    }

    /** Returns the server-owned persisted revision without changing assertion semantics. */
    public AssertionSet persistedAs(long persistedRevision, AuditMetadata persistedMetadata) {
        if (persistedRevision < 1) {
            throw new IllegalArgumentException("Persisted Assertion Set revision must be positive");
        }
        return new AssertionSet(
                schemaVersion, assertionSetId, persistedRevision, target, oracleRef,
                lifecycle, assertions, compatibility, persistedMetadata);
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = OutputAssertion.class, name = "OUTPUT"),
            @JsonSubTypes.Type(value = ErrorAssertion.class, name = "ERROR"),
            @JsonSubTypes.Type(value = NodeAssertion.class, name = "NODE"),
            @JsonSubTypes.Type(value = EdgeAssertion.class, name = "EDGE"),
            @JsonSubTypes.Type(value = InvocationAssertion.class, name = "INVOCATION"),
            @JsonSubTypes.Type(value = StateEffectAssertion.class, name = "STATE_EFFECT"),
            @JsonSubTypes.Type(value = GovernanceExpectation.class, name = "GOVERNANCE")
    })
    public sealed interface ExecutableAssertionSpec permits OutputAssertion, ErrorAssertion,
            NodeAssertion, EdgeAssertion, InvocationAssertion, StateEffectAssertion,
            GovernanceExpectation {
        String assertionId();
        EvaluationKind evaluationKind();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OutputAssertion(
            String assertionId,
            EvaluationKind evaluationKind,
            String path,
            OutputOperator operator,
            Object expected
    ) implements ExecutableAssertionSpec {
        public OutputAssertion {
            assertionId = required(assertionId, "assertionId");
            evaluationKind = executableKind(evaluationKind);
            path = trimmed(path);
            operator = required(operator, "operator");
            expected = ProtocolJsonValue.freeze(expected);
            if (requiresPath(operator) && path.isEmpty()) {
                throw new IllegalArgumentException("Output assertion path is required");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorAssertion(
            String assertionId,
            EvaluationKind evaluationKind,
            String code,
            String errorType,
            Boolean retryable
    ) implements ExecutableAssertionSpec {
        public ErrorAssertion {
            assertionId = required(assertionId, "assertionId");
            evaluationKind = executableKind(evaluationKind);
            code = trimmed(code);
            errorType = trimmed(errorType);
            if (code.isEmpty() && errorType.isEmpty() && retryable == null) {
                throw new IllegalArgumentException("Error assertion requires one expected property");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NodeAssertion(
            String assertionId,
            EvaluationKind evaluationKind,
            String nodeId,
            NodeOperator operator,
            Object expected
    ) implements ExecutableAssertionSpec {
        public NodeAssertion {
            assertionId = required(assertionId, "assertionId");
            evaluationKind = executableKind(evaluationKind);
            nodeId = required(nodeId, "nodeId");
            operator = required(operator, "operator");
            expected = ProtocolJsonValue.freeze(expected);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EdgeAssertion(
            String assertionId,
            EvaluationKind evaluationKind,
            String fromNodeId,
            String toNodeId,
            EdgeOperator operator,
            Object expected
    ) implements ExecutableAssertionSpec {
        public EdgeAssertion {
            assertionId = required(assertionId, "assertionId");
            evaluationKind = executableKind(evaluationKind);
            fromNodeId = required(fromNodeId, "fromNodeId");
            toNodeId = required(toNodeId, "toNodeId");
            operator = required(operator, "operator");
            expected = ProtocolJsonValue.freeze(expected);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InvocationAssertion(
            String assertionId,
            EvaluationKind evaluationKind,
            String operatorRef,
            InvocationOperator operator,
            Object expected
    ) implements ExecutableAssertionSpec {
        public InvocationAssertion {
            assertionId = required(assertionId, "assertionId");
            evaluationKind = executableKind(evaluationKind);
            operatorRef = required(operatorRef, "operatorRef");
            operator = required(operator, "operator");
            expected = ProtocolJsonValue.freeze(expected);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StateEffectAssertion(
            String assertionId,
            EvaluationKind evaluationKind,
            StateEffectOperator operator,
            String stateOrEffect,
            Object expected
    ) implements ExecutableAssertionSpec {
        public StateEffectAssertion {
            assertionId = required(assertionId, "assertionId");
            evaluationKind = executableKind(evaluationKind);
            operator = required(operator, "operator");
            stateOrEffect = required(stateOrEffect, "stateOrEffect");
            expected = ProtocolJsonValue.freeze(expected);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GovernanceExpectation(
            String assertionId,
            EvaluationKind evaluationKind,
            GovernanceOperator operator,
            String expected
    ) implements ExecutableAssertionSpec {
        public GovernanceExpectation {
            assertionId = required(assertionId, "assertionId");
            evaluationKind = evaluationKind == null ? EvaluationKind.GATE : evaluationKind;
            if (evaluationKind != EvaluationKind.GATE) {
                throw new IllegalArgumentException("Governance expectation must use GATE evaluation");
            }
            operator = required(operator, "operator");
            expected = required(expected, "expected");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompilationCompatibility(
            boolean supported,
            String evaluatorVersion,
            List<String> capabilities,
            String reasonCode
    ) {
        public CompilationCompatibility {
            evaluatorVersion = trimmed(evaluatorVersion);
            capabilities = sortedStrings(capabilities);
            reasonCode = trimmed(reasonCode);
            if (supported && evaluatorVersion.isEmpty()) {
                throw new IllegalArgumentException(
                        "Supported compatibility requires evaluatorVersion");
            }
            if (!supported && reasonCode.isEmpty()) {
                throw new IllegalArgumentException(
                        "Unsupported compatibility requires reasonCode");
            }
        }

        public static CompilationCompatibility unsupported(String reasonCode) {
            return new CompilationCompatibility(false, "", List.of(), reasonCode);
        }
    }

    private static EvaluationKind executableKind(EvaluationKind kind) {
        EvaluationKind normalized = kind == null ? EvaluationKind.RUNTIME : kind;
        if (normalized == EvaluationKind.GATE) {
            throw new IllegalArgumentException("Executable assertion cannot use GATE evaluation");
        }
        return normalized;
    }

    private static boolean requiresPath(OutputOperator operator) {
        return operator != OutputOperator.SCHEMA;
    }
}
