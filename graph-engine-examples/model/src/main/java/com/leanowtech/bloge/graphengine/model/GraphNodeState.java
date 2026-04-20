package com.leanowtech.bloge.graphengine.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Inferred execution state for one execution node in a running instance.
 *
 * <p>For {@link GraphExecutionMode#GRAPH}, {@code nodeId} addresses a DAG node
 * from the compiled graph definition. For {@link GraphExecutionMode#SESSION}
 * and {@link GraphExecutionMode#STATE_MACHINE}, the same DTO is reused with the
 * phase ID or state ID as {@code nodeId}. See {@link GraphNodeStatus} for the
 * projection semantics.</p>
 *
 * @param nodeId       graph node ID, session phase ID, or state-machine state ID
 * @param operatorRef  operator reference on the graph node specification ({@code null} for
 *                     session phases and state-machine states)
 * @param status       inferred execution status
 * @param retryCount   consumed retry attempts (0 when unknown)
 * @param maxRetries   configured retry budget (0 when unknown)
 * @param lastError    last recorded error message ({@code null} when none)
 * @param waitType     active wait type when {@code status == WAITING}; reserved for GRAPH-mode
 *                     waits and {@code null} for SESSION / STATE_MACHINE projections
 * @param startedAt    earliest timestamp among matching work items or checkpoints ({@code null}
 *                     when the node has not started)
 * @param completedAt  checkpoint creation timestamp when {@code status == COMPLETED}
 *                     ({@code null} otherwise)
 */
public record GraphNodeState(
        String nodeId,
        String operatorRef,
        GraphNodeStatus status,
        int retryCount,
        int maxRetries,
        String lastError,
        String waitType,
        Instant startedAt,
        Instant completedAt
) {
    public GraphNodeState {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(status, "status");
    }
}
