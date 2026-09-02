package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable v2 simulation evidence with one entry per dynamic runtime invocation.
 *
 * <p>Evidence carries only exact coordinates, fingerprints, stable enums and safe diagnostics. It
 * intentionally excludes request/response headers, credentials, protected Fixture material, replay
 * payloads and raw invocation input. The aggregate can be {@code READY} only when all four verdict
 * axes independently pass.</p>
 */
public record SimulationRunV2(String schemaVersion, String runId, Status status,
                              ExactFixtureSubjectRefV2 subject, String requestFingerprint,
                              String resolvedFixturePlanFingerprint,
                              @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode output,
                              List<Invocation> invocations, Verdicts verdicts,
                              List<Diagnostic> diagnostics,
                              @JsonFormat(shape = JsonFormat.Shape.STRING) Instant startedAt,
                              @JsonInclude(JsonInclude.Include.NON_NULL)
                              @JsonFormat(shape = JsonFormat.Shape.STRING) Instant endedAt) {
    public static final String SCHEMA_VERSION = "bloge.simulationRun.v2";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    public SimulationRunV2 {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        output = copyNullable(output);
        invocations = invocations == null ? List.of() : List.copyOf(invocations);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        require(runId, status, subject, requestFingerprint, resolvedFixturePlanFingerprint,
                invocations, verdicts, startedAt, endedAt);
    }

    @Override public JsonNode output() { return copyNullable(output); }
    @Override public List<Invocation> invocations() { return List.copyOf(invocations); }
    @Override public List<Diagnostic> diagnostics() { return List.copyOf(diagnostics); }

    /** Keeps simulated output and diagnostic detail out of logs. */
    @Override public String toString() {
        return "SimulationRunV2[runId=" + runId + ", status=" + status + ", subject=" + subject
                + ", invocations=" + invocations.size() + ", diagnostics=" + diagnostics.size() + "]";
    }

    public enum Status { RUNNING, SUCCEEDED, FAILED, BLOCKED }
    public enum InvocationStatus { COMPLETED, FAILED, BLOCKED, SKIPPED }
    public enum Execution { REAL, MOCKED }
    public enum MatchedBy { NONE, EXACT_CASE, CONDITION, AUTO_MATCH, CASE_CONTROLS }
    public enum Behavior { RETURN, ERROR, TIMEOUT, REPLAY }
    public enum Fidelity { OUTPUT_LEVEL, PROTOCOL_DERIVED, TRANSPORT_LEVEL }
    public enum Provenance { PINNED_PRIVATE, GOVERNED_ASSET, REPLAY }
    public enum ExecutionVerdict { PASSED, FAILED, BLOCKED }
    public enum AssertionsVerdict { PASSED, FAILED, NOT_CHECKED }
    public enum ContractVerdict { VALID, INVALID, NOT_CHECKED }
    public enum GovernanceVerdict { PASSED, FAILED, NOT_CHECKED }
    public enum AggregateVerdict { READY, NOT_READY }

    /** One server-generated invocation identity and its payload-free execution proof. */
    public record Invocation(String invocationKey,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String parentInvocationKey,
                             SimulationCommandV2.FixtureTarget target,
                             ExactFixtureSubjectRefV2 subject, InvocationStatus status,
                             Execution execution, MatchedBy matchedBy,
                             @JsonInclude(JsonInclude.Include.NON_NULL) FixtureCase fixtureCase,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Behavior behavior,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Fidelity fidelity,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Provenance provenance,
                             @JsonInclude(JsonInclude.Include.NON_NULL) FixtureAssetRef fixtureAssetRef,
                             String inputFingerprint,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String outputFingerprint,
                             SimulationRun.Egress egress) {
        public Invocation {
            if (!identifier(invocationKey) || parentInvocationKey != null && !identifier(parentInvocationKey)
                    || target == null || subject == null || status == null || execution == null
                    || matchedBy == null || !fingerprint(inputFingerprint)
                    || outputFingerprint != null && !fingerprint(outputFingerprint) || egress == null) {
                throw new IllegalArgumentException("simulation invocation evidence is invalid");
            }
            if (execution == Execution.REAL && (matchedBy != MatchedBy.NONE || fixtureCase != null
                    || behavior != null || fidelity != null || provenance != null
                    || fixtureAssetRef != null)) {
                throw new IllegalArgumentException("real invocation cannot claim Fixture evidence");
            }
            if (execution == Execution.MOCKED && (matchedBy == MatchedBy.NONE || fixtureCase == null
                    || behavior == null || fidelity == null || provenance == null)) {
                throw new IllegalArgumentException("mocked invocation evidence is incomplete");
            }
        }
    }

    /** Exact selected Case authority. */
    public record FixtureCase(String fixtureSetId, int revision, String fingerprint, String caseId) {
        public FixtureCase {
            if (!identifier(fixtureSetId) || revision < 1 || !SimulationRunV2.fingerprint(fingerprint)
                    || !identifier(caseId)) {
                throw new IllegalArgumentException("fixture Case evidence is invalid");
            }
        }
    }

    /** Exact governed material coordinate, without material content. */
    public record FixtureAssetRef(String fixtureAssetId, int revision, String schemaFingerprint) {
        public FixtureAssetRef {
            if (!identifier(fixtureAssetId) || revision < 1 || !fingerprint(schemaFingerprint)) {
                throw new IllegalArgumentException("fixture asset evidence is invalid");
            }
        }
    }

    /** Orthogonal execution, assertion, contract and governance results. */
    public record Verdicts(ExecutionVerdict execution, AssertionsVerdict assertions,
                           ContractVerdict contract, GovernanceVerdict governance,
                           AggregateVerdict aggregate) {
        public Verdicts {
            if (execution == null || assertions == null || contract == null || governance == null
                    || aggregate == null) {
                throw new IllegalArgumentException("simulation verdicts are incomplete");
            }
            boolean ready = execution == ExecutionVerdict.PASSED
                    && assertions == AssertionsVerdict.PASSED
                    && contract == ContractVerdict.VALID
                    && governance == GovernanceVerdict.PASSED;
            if ((aggregate == AggregateVerdict.READY) != ready) {
                throw new IllegalArgumentException("simulation aggregate is inconsistent");
            }
        }
    }

    /** Stable code and bounded safe explanation. */
    public record Diagnostic(String code, String message) {
        public Diagnostic {
            if (!identifier(code) || message == null || message.isBlank() || message.length() > 500) {
                throw new IllegalArgumentException("simulation diagnostic is invalid");
            }
        }
        @Override public String toString() { return "Diagnostic[code=" + code + ", message=protected]"; }
    }

    private static void require(String runId, Status status, ExactFixtureSubjectRefV2 subject,
                                String requestFingerprint, String planFingerprint,
                                List<Invocation> invocations, Verdicts verdicts,
                                Instant startedAt, Instant endedAt) {
        if (!identifier(runId) || status == null || subject == null || !fingerprint(requestFingerprint)
                || !fingerprint(planFingerprint) || verdicts == null || startedAt == null
                || (status == Status.RUNNING) != (endedAt == null)) {
            throw new IllegalArgumentException("simulation run evidence is incomplete");
        }
        Set<String> invocationKeys = new HashSet<>();
        if (invocations.stream().anyMatch(value -> !invocationKeys.add(value.invocationKey()))) {
            throw new IllegalArgumentException("simulation invocation keys are not unique");
        }
        if ((status == Status.SUCCEEDED && verdicts.execution() != ExecutionVerdict.PASSED)
                || (status == Status.FAILED && verdicts.execution() != ExecutionVerdict.FAILED)
                || (status == Status.BLOCKED && verdicts.execution() != ExecutionVerdict.BLOCKED)) {
            throw new IllegalArgumentException("simulation status and verdict disagree");
        }
    }

    private static boolean identifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private static boolean fingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }

    private static JsonNode copyNullable(JsonNode value) {
        return value == null ? null : value.deepCopy();
    }
}
