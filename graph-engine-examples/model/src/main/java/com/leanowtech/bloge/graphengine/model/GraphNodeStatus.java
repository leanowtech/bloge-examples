package com.leanowtech.bloge.graphengine.model;

/**
 * Inferred execution status for an individual node within a running graph instance.
 *
 * <p>There is no persistent per-node status map in the durable runtime. Instead,
 * the product-layer service <em>infers</em> each node's state from the graph
 * definition combined with durable checkpoints, waits, and work items:
 *
 * <ul>
 *   <li>{@link #COMPLETED} — a {@code NODE_OUTPUT} checkpoint exists for the node</li>
 *   <li>{@link #WAITING} — an active {@code WAITING} wait is registered for the node</li>
 *   <li>{@link #RUNNING} — a work item with status {@code CLAIMED} targets the node</li>
 *   <li>{@link #PENDING} — a work item with status {@code READY} targets the node</li>
 *   <li>{@link #RETRYING} — a work item with status {@code RETRY_WAIT} targets the node</li>
 *   <li>{@link #FAILED} — a work item with status {@code FAILED} targets the node</li>
 *   <li>{@link #DEAD_LETTERED} — a work item with status {@code DEAD_LETTER} targets the node</li>
 *   <li>{@link #CANCELLED} — a work item with status {@code CANCELLED} targets the node</li>
 *   <li>{@link #NOT_STARTED} — the node exists in the graph definition but no
 *       checkpoint, wait, or work item references it</li>
 * </ul>
 *
 * <p>Completed checkpoints always win. Otherwise, explicit work-item state wins
 * over a generic active wait because it carries the more actionable lifecycle
 * signal for suspended operators such as remote workers and resumed timers. A
 * node only reports {@link #WAITING} when no overriding work-item state exists.</p>
 */
public enum GraphNodeStatus {
    NOT_STARTED,
    PENDING,
    RUNNING,
    WAITING,
    COMPLETED,
    FAILED,
    DEAD_LETTERED,
    RETRYING,
    CANCELLED
}
