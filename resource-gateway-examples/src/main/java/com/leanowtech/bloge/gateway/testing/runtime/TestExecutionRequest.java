package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;

import java.util.Map;

/**
 * Immutable internal request submitted by graph, operator, suite, and future HTTP adapters.
 *
 * <p>The constructor snapshots business context values. Each execution creates another fresh
 * {@link GraphContext}, so engine-owned services, node outputs, and side-effect journals cannot
 * leak between repeated or concurrent runs of the same request. An explicitly bound immutable
 * execution deadline is retained across those copies.</p>
 */
public record TestExecutionRequest(
        Graph graph,
        GraphContext context,
        FixtureBundle fixtureBundle,
        String authorizedPurpose,
        String targetFingerprint,
        FixtureSource fixtureSource,
        Map<String, Object> metadata,
        boolean certificationEligible,
        ResolvedReplayPayloads replayPayloads,
        ResolvedTestSecrets testSecrets
) {
    /** Provenance used for evidence trust classification. */
    public enum FixtureSource {
        INLINE,
        STORED
    }

    /** Normalizes nullable values and freezes caller-owned business context data. */
    public TestExecutionRequest {
        GraphContext sourceContext = context;
        context = sourceContext == null ? new GraphContext() : new GraphContext(sourceContext.asMap());
        if (sourceContext != null) {
            context.bindExecutionBudget(sourceContext.executionBudget());
        }
        authorizedPurpose = authorizedPurpose == null ? "" : authorizedPurpose.trim();
        targetFingerprint = targetFingerprint == null ? "" : targetFingerprint.trim();
        fixtureSource = fixtureSource == null ? FixtureSource.INLINE : fixtureSource;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        replayPayloads = replayPayloads == null ? ResolvedReplayPayloads.empty() : replayPayloads;
        testSecrets = testSecrets == null ? ResolvedTestSecrets.empty() : testSecrets;
    }

    /** Backward-compatible internal constructor for already-frozen adapters. */
    public TestExecutionRequest(Graph graph, GraphContext context, FixtureBundle fixtureBundle,
                                String authorizedPurpose, String targetFingerprint,
                                FixtureSource fixtureSource, Map<String, Object> metadata) {
        this(graph, context, fixtureBundle, authorizedPurpose, targetFingerprint,
                fixtureSource, metadata, true, ResolvedReplayPayloads.empty(),
                ResolvedTestSecrets.empty());
    }

    /** Backward-compatible internal constructor without governed replay dependencies. */
    public TestExecutionRequest(Graph graph, GraphContext context, FixtureBundle fixtureBundle,
                                String authorizedPurpose, String targetFingerprint,
                                FixtureSource fixtureSource, Map<String, Object> metadata,
                                boolean certificationEligible) {
        this(graph, context, fixtureBundle, authorizedPurpose, targetFingerprint,
                fixtureSource, metadata, certificationEligible, ResolvedReplayPayloads.empty(),
                ResolvedTestSecrets.empty());
    }

    /** Backward-compatible constructor without externally governed test secrets. */
    public TestExecutionRequest(Graph graph, GraphContext context, FixtureBundle fixtureBundle,
                                String authorizedPurpose, String targetFingerprint,
                                FixtureSource fixtureSource, Map<String, Object> metadata,
                                boolean certificationEligible,
                                ResolvedReplayPayloads replayPayloads) {
        this(graph, context, fixtureBundle, authorizedPurpose, targetFingerprint,
                fixtureSource, metadata, certificationEligible, replayPayloads,
                ResolvedTestSecrets.empty());
    }
}
