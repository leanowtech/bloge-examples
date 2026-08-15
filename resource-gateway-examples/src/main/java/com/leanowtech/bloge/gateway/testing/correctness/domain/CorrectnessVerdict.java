package com.leanowtech.bloge.gateway.testing.correctness.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.required;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.trimmed;

/** Five independent correctness axes; intentionally has no aggregate pass field. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CorrectnessVerdict(
        ExecutionVerdict execution,
        AssertionVerdict assertions,
        CoverageVerdict coverage,
        EvidenceVerdict evidence,
        GateVerdict gate,
        ProofLevel proofLevel,
        List<Reason> reasons,
        List<Remediation> nextActions
) {
    public enum ExecutionVerdict { NOT_RUN, RUNNING, SUCCESS, FAILED, PARTIAL, CANCELLED }
    public enum AssertionVerdict { NOT_EVALUATED, NONE, PASSED, FAILED, INCONCLUSIVE }
    public enum CoverageVerdict { NOT_EVALUATED, UNFROZEN, COMPLETE, INCOMPLETE, STALE }
    public enum EvidenceVerdict { NONE, EXPLORATORY, CURRENT, STALE, REVOKED }
    public enum GateVerdict { NOT_EVALUATED, BLOCKED, REVIEW, ACCEPTED }
    public enum ProofLevel { STRUCTURAL, SIMULATED_BUSINESS, CONTROLLED_INTEGRATION, REPLAY_DERIVED }

    public CorrectnessVerdict {
        execution = required(execution, "execution");
        assertions = required(assertions, "assertions");
        coverage = required(coverage, "coverage");
        evidence = required(evidence, "evidence");
        gate = required(gate, "gate");
        proofLevel = required(proofLevel, "proofLevel");
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
        if (assertions == AssertionVerdict.NONE && gate == GateVerdict.ACCEPTED) {
            throw new IllegalArgumentException("Zero assertions cannot be accepted");
        }
        if (evidence == EvidenceVerdict.STALE && gate == GateVerdict.ACCEPTED) {
            throw new IllegalArgumentException("Stale evidence cannot be accepted");
        }
        if (gate == GateVerdict.ACCEPTED
                && (execution != ExecutionVerdict.SUCCESS
                || assertions != AssertionVerdict.PASSED
                || coverage != CoverageVerdict.COMPLETE
                || evidence != EvidenceVerdict.CURRENT)) {
            throw new IllegalArgumentException(
                    "Accepted gate requires successful, proven, complete, current evidence");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reason(String code, String axis, String messageId) {
        public Reason {
            code = required(code, "code");
            axis = required(axis, "axis");
            messageId = required(messageId, "messageId");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Remediation(String command, String reasonCode) {
        public Remediation {
            command = required(command, "command");
            reasonCode = trimmed(reasonCode);
        }
    }
}
