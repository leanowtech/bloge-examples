package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;

import java.util.Map;

/** Immutable internal request submitted by graph, operator, suite, and future HTTP adapters. */
public record TestExecutionRequest(
        Graph graph,
        GraphContext context,
        FixtureBundle fixtureBundle,
        String authorizedPurpose,
        String targetFingerprint,
        FixtureSource fixtureSource,
        Map<String, Object> metadata,
        boolean certificationEligible,
        ResolvedReplayPayloads replayPayloads
) {
    /** Provenance used for evidence trust classification. */
    public enum FixtureSource {
        INLINE,
        STORED
    }

    /** Normalizes nullable context and metadata without reading controls from business context. */
    public TestExecutionRequest {
        context = context == null ? new GraphContext() : context;
        authorizedPurpose = authorizedPurpose == null ? "" : authorizedPurpose.trim();
        targetFingerprint = targetFingerprint == null ? "" : targetFingerprint.trim();
        fixtureSource = fixtureSource == null ? FixtureSource.INLINE : fixtureSource;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        replayPayloads = replayPayloads == null ? ResolvedReplayPayloads.empty() : replayPayloads;
    }

    /** Backward-compatible internal constructor for already-frozen adapters. */
    public TestExecutionRequest(Graph graph, GraphContext context, FixtureBundle fixtureBundle,
                                String authorizedPurpose, String targetFingerprint,
                                FixtureSource fixtureSource, Map<String, Object> metadata) {
        this(graph, context, fixtureBundle, authorizedPurpose, targetFingerprint,
                fixtureSource, metadata, true, ResolvedReplayPayloads.empty());
    }

    /** Backward-compatible internal constructor without governed replay dependencies. */
    public TestExecutionRequest(Graph graph, GraphContext context, FixtureBundle fixtureBundle,
                                String authorizedPurpose, String targetFingerprint,
                                FixtureSource fixtureSource, Map<String, Object> metadata,
                                boolean certificationEligible) {
        this(graph, context, fixtureBundle, authorizedPurpose, targetFingerprint,
                fixtureSource, metadata, certificationEligible, ResolvedReplayPayloads.empty());
    }
}
