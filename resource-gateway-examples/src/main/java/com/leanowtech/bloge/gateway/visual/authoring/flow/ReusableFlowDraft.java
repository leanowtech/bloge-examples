package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;

import java.util.regex.Pattern;

/** Authoritative saved view of one reusable Tool or Solution draft revision. */
public record ReusableFlowDraft(String schemaVersion, String flowId, String draftId, int revision,
                                String fingerprint, String displayName, ReusableFlowCommand.Kind kind,
                                String description, ReusableFlowCommand.Contract contract,
                                ReusableFlowCommand.Graph graph, ReusableFlowCommand.Layout layout,
                                Status status) {
    public static final String SCHEMA_VERSION = "bloge.reusableFlowDraft.v1";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    public enum Status { DRAFT }

    /** Validates server identity and copies the authored command records. */
    public ReusableFlowDraft {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        if (!SCHEMA_VERSION.equals(schemaVersion) || !validId(flowId) || !validId(draftId)
                || revision < 1 || fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()
                || displayName == null || displayName.isBlank() || displayName.length() > 200
                || kind == null || description == null || description.length() > 2000
                || contract == null || graph == null || layout == null || status != Status.DRAFT) {
            throw new IllegalArgumentException("reusable Flow draft is invalid");
        }
        contract = new ReusableFlowCommand.Contract(contract.input(), contract.output());
        graph = new ReusableFlowCommand.Graph(graph.nodes(), graph.output());
        layout = new ReusableFlowCommand.Layout(layout.nodes());
    }

    /** Exact Fixture/Simulation subject coordinate for this revision. */
    public FixtureSubjectRef.FlowDraft subject() {
        return new FixtureSubjectRef.FlowDraft(draftId, revision, fingerprint);
    }

    @Override public ReusableFlowCommand.Contract contract() {
        return new ReusableFlowCommand.Contract(contract.input(), contract.output());
    }

    @Override public ReusableFlowCommand.Graph graph() {
        return new ReusableFlowCommand.Graph(graph.nodes(), graph.output());
    }

    @Override public ReusableFlowCommand.Layout layout() {
        return new ReusableFlowCommand.Layout(layout.nodes());
    }

    private static boolean validId(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }
}
