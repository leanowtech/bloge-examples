package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable executable Tool/Solution snapshot published into the composable catalog. */
public record ReusableFlowVersion(String schemaVersion, String publicationId, int revision,
                                  String fingerprint, Source source, String flowId, String displayName,
                                  ReusableFlowCommand.Kind kind, String description,
                                  ReusableFlowCommand.Contract contract, ReusableFlowCommand.Graph graph,
                                  @JsonFormat(shape = JsonFormat.Shape.STRING) Instant publishedAt,
                                  String publishedBy, Status status) {
    public static final String SCHEMA_VERSION = "bloge.reusableFlowVersion.v1";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    public enum Status { PUBLISHED }

    public record Source(String draftId, int revision, String fingerprint) {
        public Source {
            requireCoordinate(draftId, revision, fingerprint);
        }
    }

    public ReusableFlowVersion {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        if (!SCHEMA_VERSION.equals(schemaVersion) || !validId(publicationId) || revision < 1
                || !validFingerprint(fingerprint) || source == null || !validId(flowId)
                || displayName == null || displayName.isBlank() || displayName.length() > 200
                || kind == null || description == null || description.length() > 2000
                || contract == null || graph == null || publishedAt == null || !validId(publishedBy)
                || status != Status.PUBLISHED) {
            throw new IllegalArgumentException("reusable Flow version is invalid");
        }
        contract = new ReusableFlowCommand.Contract(contract.input(), contract.output());
        graph = new ReusableFlowCommand.Graph(graph.nodes(), graph.output());
    }

    /** Exact catalog and Fixture subject coordinate for this immutable version. */
    public FixtureSubjectRef.FlowVersion subject() {
        return new FixtureSubjectRef.FlowVersion(publicationId, revision, fingerprint);
    }

    @Override public ReusableFlowCommand.Contract contract() {
        return new ReusableFlowCommand.Contract(contract.input(), contract.output());
    }

    @Override public ReusableFlowCommand.Graph graph() {
        return new ReusableFlowCommand.Graph(graph.nodes(), graph.output());
    }

    private static void requireCoordinate(String id, int revision, String fingerprint) {
        if (!validId(id) || revision < 1 || !validFingerprint(fingerprint)) {
            throw new IllegalArgumentException("reusable Flow version source is invalid");
        }
    }

    private static boolean validId(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private static boolean validFingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }
}
