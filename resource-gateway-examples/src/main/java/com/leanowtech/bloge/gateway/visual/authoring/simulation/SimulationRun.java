package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;

import java.time.Instant;
import java.util.List;

/** Immutable, payload-safe simulation evidence returned by POST and exact GET. */
public record SimulationRun(String schemaVersion, String runId, Status status, FixtureSubjectRef subject,
                            @JsonInclude(JsonInclude.Include.NON_NULL) FixtureCase fixtureCase,
                            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode output,
                            List<Node> nodes, Verdicts verdicts, List<Diagnostic> diagnostics,
                            Instant startedAt, @JsonInclude(JsonInclude.Include.NON_NULL) Instant endedAt) {
    public static final String SCHEMA_VERSION = "bloge.simulationRun.v1";

    /** Defensively copies mutable JSON and evidence collections. */
    public SimulationRun {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        output = output == null ? null : output.deepCopy();
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    @Override public JsonNode output() { return output == null ? null : output.deepCopy(); }
    @Override public List<Node> nodes() { return List.copyOf(nodes); }
    @Override public List<Diagnostic> diagnostics() { return List.copyOf(diagnostics); }

    /** Keeps simulated output and diagnostic detail out of logs. */
    @Override public String toString() {
        return "SimulationRun[runId=" + runId + ", status=" + status + ", subject=" + subject
                + ", nodes=" + nodes.size() + ", diagnostics=" + diagnostics.size() + "]";
    }

    public enum Status { RUNNING, SUCCEEDED, FAILED, BLOCKED }
    public enum NodeStatus { COMPLETED, FAILED, BLOCKED, SKIPPED }
    public enum Execution { REAL, MOCKED }
    public enum FixtureSource { NONE, INLINE, FIXTURE_ASSET, REPLAY, APPLY_CASE }
    public enum Fidelity { OUTPUT_LEVEL, PROTOCOL_DERIVED, TRANSPORT_LEVEL }
    public enum ExecutionVerdict { PASSED_REAL, PASSED_WITH_MOCKS, SIMULATED_ONLY, FAILED, BLOCKED }
    public enum Verdict { PASSED, FAILED, NOT_CHECKED }

    /** Exact Fixture Case coordinate used by this run. */
    public record FixtureCase(String fixtureSetId, int revision, String caseId) { }
    /** Per-node trust and egress evidence. */
    public record Node(String nodeId, NodeStatus status, Execution execution, FixtureSource fixtureSource,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Fidelity fidelity, Egress egress) { }
    /** Orthogonal execution, contract, assertion and governance results. */
    public record Verdicts(ExecutionVerdict execution, Verdict contract, Verdict assertions,
                           Verdict governance) { }
    /** Stable code and safe user-facing explanation. */
    public record Diagnostic(String code, String message) { }

    /** Closed evidence union: no request, response, credential or protected material is embedded. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "decision")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Egress.Fixture.class, name = "FIXTURE"),
            @JsonSubTypes.Type(value = Egress.NotApplicable.class, name = "NOT_APPLICABLE"),
            @JsonSubTypes.Type(value = Egress.AllowedRead.class, name = "ALLOWED_READ"),
            @JsonSubTypes.Type(value = Egress.Denied.class, name = "DENIED"),
            @JsonSubTypes.Type(value = Egress.NotAttempted.class, name = "NOT_ATTEMPTED")
    })
    public sealed interface Egress
            permits Egress.Fixture, Egress.NotApplicable, Egress.AllowedRead, Egress.Denied,
            Egress.NotAttempted {
        record Fixture(boolean attempted) implements Egress { }
        record NotApplicable(boolean attempted) implements Egress { }
        record AllowedRead(boolean attempted, ApiResourceSpec.ResourceRef resource,
                           Connection connection, String authorizationDecisionId, Outcome outcome)
                implements Egress { }
        record Denied(boolean attempted, ApiResourceSpec.ResourceRef resource,
                      String authorizationDecisionId, String reasonCode) implements Egress { }
        record NotAttempted(boolean attempted,
                            @JsonInclude(JsonInclude.Include.NON_NULL) ApiResourceSpec.ResourceRef resource,
                            String reasonCode) implements Egress { }
        record Connection(String connectionId, long revision) { }
        enum Outcome { SUCCEEDED, FAILED }

        static Egress fixture() { return new Fixture(false); }
        static Egress notApplicable() { return new NotApplicable(false); }
    }
}
