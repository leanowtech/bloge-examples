package com.leanowtech.bloge.examples.common;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.spi.ExecutionListener;

import java.time.Duration;
import java.util.Map;

/**
 * A reusable {@link ExecutionListener} implementation that prints a concise log line for
 * every graph and node lifecycle event to standard output.
 *
 * <p>This listener is shared across all example programs in the {@code bloge-examples} module.
 * Attach it to a {@link com.leanowtech.bloge.core.engine.GraphEngine} to observe execution flow:
 *
 * <pre>{@code
 * var engine = GraphEngine.builder()
 *         .registry(registry)
 *         .listeners(List.of(new LoggingListener()))
 *         .build();
 * }</pre>
 *
 * <p>Supported events (in rough execution order):
 * <ol>
 *   <li>Graph start / complete</li>
 *   <li>Node start / complete / skip / fail</li>
 *   <li>Node retry / timeout / fallback</li>
 *   <li>ForEach item complete</li>
 *   <li>Loop iteration / loop complete</li>
 * </ol>
 */
public class LoggingListener implements ExecutionListener {

    /**
     * Called once before the first node in {@code graphName} is executed.
     *
     * @param graphName the logical name of the graph (from {@code Graph.builder(name)})
     * @param ctx       the immutable context carrying input parameters for this execution
     */
    @Override
    public void onGraphStart(String graphName, GraphContext ctx) {
        System.out.println("[START] Graph: " + graphName);
    }

    /**
     * Called immediately before a node begins executing its operator.
     *
     * @param graphName the name of the enclosing graph
     * @param nodeId    the unique node identifier within the graph
     */
    @Override
    public void onNodeStart(String graphName, String nodeId) {
        System.out.printf("  [→] %s starting%n", nodeId);
    }

    /**
     * Called after a node finishes successfully.
     *
     * @param graphName the name of the enclosing graph
     * @param nodeId    the unique node identifier
     * @param output    the value returned by the operator
     * @param elapsed   wall-clock time taken by this node
     */
    @Override
    public void onNodeComplete(String graphName, String nodeId, Object output, Duration elapsed) {
        System.out.printf("  [✓] %s completed in %dms%n", nodeId, elapsed.toMillis());
    }

    /**
     * Called when a node throws an exception after all retry attempts are exhausted
     * and no fallback is configured.
     *
     * @param graphName    the name of the enclosing graph
     * @param nodeId       the unique node identifier
     * @param error        the terminal exception
     * @param retryAttempt the total number of attempts that were made (1 = no retry)
     */
    @Override
    public void onNodeFailed(String graphName, String nodeId, Exception error, int retryAttempt) {
        System.out.printf("  [✗] %s failed (attempt %d): %s%n", nodeId, retryAttempt, error.getMessage());
    }

    /**
     * Called when a node is deliberately skipped, typically because a branch condition
     * directed execution to a different downstream node.
     *
     * @param graphName the name of the enclosing graph
     * @param nodeId    the unique node identifier
     * @param reason    human-readable explanation of why the node was skipped
     */
    @Override
    public void onNodeSkipped(String graphName, String nodeId, String reason) {
        System.out.printf("  [⊘] %s skipped: %s%n", nodeId, reason);
    }

    /**
     * Called once after all nodes in the graph have either completed, failed, or been skipped.
     *
     * @param graphName the name of the enclosing graph
     * @param result    the aggregated execution result including elapsed time and per-node statuses
     */
    @Override
    public void onGraphComplete(String graphName, GraphResult result) {
        System.out.printf("[END] Graph: %s (%dms, %s)%n",
                graphName, result.elapsed().toMillis(),
                result.isSuccess() ? "SUCCESS" : "FAILED");
    }

    /**
     * Called before each retry attempt of a failing node (i.e., called for attempt 2, 3, …).
     *
     * @param graphName the name of the enclosing graph
     * @param nodeId    the unique node identifier
     * @param attempt   the upcoming attempt number (starts at 2)
     * @param lastError the exception thrown by the previous attempt
     */
    @Override
    public void onNodeRetry(String graphName, String nodeId, int attempt, Exception lastError) {
        System.out.printf("  [↺] %s retry attempt %d: %s%n", nodeId, attempt, lastError.getMessage());
    }

    /**
     * Called when a node exceeds its configured execution deadline.
     *
     * @param graphName         the name of the enclosing graph
     * @param nodeId            the unique node identifier
     * @param configuredTimeout the timeout threshold that was exceeded
     */
    @Override
    public void onNodeTimeout(String graphName, String nodeId, Duration configuredTimeout) {
        System.out.printf("  [⏱] %s timed out after %dms%n", nodeId, configuredTimeout.toMillis());
    }

    /**
     * Called when a node's fallback value kicks in because the primary operator failed.
     *
     * @param graphName     the name of the enclosing graph
     * @param nodeId        the unique node identifier
     * @param originalError the exception that triggered the fallback
     */
    @Override
    public void onNodeFallback(String graphName, String nodeId, Exception originalError) {
        System.out.printf("  [↩] %s fell back: %s%n", nodeId, originalError.getMessage());
    }

    /**
     * Called after each individual item finishes processing inside a {@code forEach} node.
     *
     * @param graphName     the name of the enclosing graph
     * @param foreachNodeId the node identifier of the forEach construct
     * @param itemIndex     zero-based index of the completed item
     * @param itemOutput    the result produced for that item
     */
    @Override
    public void onForEachItemComplete(String graphName, String foreachNodeId,
                                       int itemIndex, Object itemOutput) {
        System.out.printf("  [∀] %s item #%d completed%n", foreachNodeId, itemIndex);
    }

    /**
     * Called after each completed iteration of a loop node.
     *
     * @param graphName       the name of the enclosing graph
     * @param loopNodeId      the node identifier of the loop construct
     * @param iteration       one-based iteration number
     * @param iterationOutput the output produced by all nodes in that iteration
     */
    @Override
    public void onLoopIterationComplete(String graphName, String loopNodeId,
                                         int iteration, Map<String, Object> iterationOutput) {
        System.out.printf("  [↻] %s iteration #%d completed%n", loopNodeId, iteration);
    }

    /**
     * Called once after a loop node has exhausted all iterations or its exit condition is met.
     *
     * @param graphName       the name of the enclosing graph
     * @param loopNodeId      the node identifier of the loop construct
     * @param totalIterations the total number of iterations that were executed
     */
    @Override
    public void onLoopComplete(String graphName, String loopNodeId, int totalIterations) {
        System.out.printf("  [↻✓] %s loop completed after %d iterations%n", loopNodeId, totalIterations);
    }
}
