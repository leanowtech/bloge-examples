package com.leanowtech.bloge.graphengine.service;

import com.leanowtech.bloge.graphengine.model.GraphDefinition;
import com.leanowtech.bloge.graphengine.model.GraphDeadLetter;
import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;
import com.leanowtech.bloge.graphengine.model.GraphInstanceDiagram;
import com.leanowtech.bloge.graphengine.model.GraphInstanceContext;
import com.leanowtech.bloge.graphengine.model.GraphNodeStatus;
import com.leanowtech.bloge.graphengine.model.GraphNodeState;
import com.leanowtech.bloge.graphengine.model.GraphOperationsSnapshot;
import com.leanowtech.bloge.graphengine.model.GraphPendingSignal;
import com.leanowtech.bloge.graphengine.model.PagedResult;
import com.leanowtech.bloge.graphengine.model.GraphDeployment;
import com.leanowtech.bloge.graphengine.model.GraphAuditEntry;
import com.leanowtech.bloge.graphengine.model.GraphControlActionEntry;
import com.leanowtech.bloge.graphengine.model.GraphInstance;
import com.leanowtech.bloge.graphengine.model.GraphRemoteWorkerJob;
import com.leanowtech.bloge.graphengine.model.GraphRemoteWorkerRegistration;
import com.leanowtech.bloge.graphengine.model.GraphTask;
import com.leanowtech.bloge.graphengine.model.GraphTransitionEntry;
import com.leanowtech.bloge.graphengine.model.GraphVersion;
import com.leanowtech.bloge.graphengine.model.GraphVersionDiagram;
import com.leanowtech.bloge.graphengine.store.GraphDeadLetterQuery;
import com.leanowtech.bloge.graphengine.service.command.CancelTaskCommand;
import com.leanowtech.bloge.graphengine.service.command.ClaimTaskCommand;
import com.leanowtech.bloge.graphengine.service.command.CompleteTaskCommand;
import com.leanowtech.bloge.graphengine.service.command.CompleteRemoteWorkerJobCommand;
import com.leanowtech.bloge.graphengine.service.command.CreateDefinitionCommand;
import com.leanowtech.bloge.graphengine.service.command.CreateDeploymentCommand;
import com.leanowtech.bloge.graphengine.service.command.CreateVersionCommand;
import com.leanowtech.bloge.graphengine.service.command.FailRemoteWorkerJobCommand;
import com.leanowtech.bloge.graphengine.service.command.HeartbeatRemoteWorkerJobCommand;
import com.leanowtech.bloge.graphengine.service.command.PollRemoteWorkerJobsCommand;
import com.leanowtech.bloge.graphengine.service.command.ReassignTaskCommand;
import com.leanowtech.bloge.graphengine.service.command.RegisterRemoteWorkerCommand;
import com.leanowtech.bloge.graphengine.service.command.SignalInstanceCommand;
import com.leanowtech.bloge.graphengine.service.command.StartInstanceCommand;
import com.leanowtech.bloge.graphengine.service.command.UpdateDefinitionCommand;
import com.leanowtech.bloge.graphengine.service.command.UpdateDeploymentCommand;
import com.leanowtech.bloge.graphengine.store.GraphDefinitionQuery;
import com.leanowtech.bloge.graphengine.store.GraphDeploymentQuery;
import com.leanowtech.bloge.graphengine.store.GraphInstanceQuery;
import com.leanowtech.bloge.graphengine.store.GraphVersionQuery;
import com.leanowtech.bloge.runtime.task.TaskInboxQuery;

import java.util.List;
import java.util.Set;

/**
 * Product-layer control-plane facade that composes graph-engine metadata stores
 * with the underlying durable BLOGE runtime.
 */
public interface GraphEngineService {

    /**
     * Creates a new stable product-layer graph definition identity.
     *
     * @param command definition creation command
     * @return persisted definition snapshot
     */
    GraphDefinition createDefinition(CreateDefinitionCommand command);

    /**
     * Loads one definition by internal identifier.
     *
     * @param definitionId internal definition identifier
     * @return matching definition
     */
    GraphDefinition getDefinition(String definitionId);

    /**
     * Loads one definition by business key inside the supplied tenant scope.
     *
     * @param definitionKey business-facing definition key
     * @param tenantId tenant identifier, or {@code null} to use the current bound tenant
     * @param namespace namespace identifier, or {@code null} to use the current bound namespace
     * @return matching definition
     */
    GraphDefinition getDefinitionByKey(String definitionKey, String tenantId, String namespace);

    /**
     * Queries definitions matching the supplied filter.
     *
     * @param query metadata query filter
     * @return immutable page of definitions
     */
    List<GraphDefinition> queryDefinitions(GraphDefinitionQuery query);

    /**
     * Updates mutable metadata on an existing definition.
     *
     * @param command definition update command
     * @return updated definition snapshot
     */
    GraphDefinition updateDefinition(UpdateDefinitionCommand command);

    /**
     * Archives one definition.
     *
     * @param definitionId definition identifier
     * @param expectedRevision optimistic-lock guard
     * @return archived definition snapshot
     */
    GraphDefinition archiveDefinition(String definitionId, long expectedRevision);

    /**
     * Creates a new immutable version snapshot for one definition.
     *
     * @param command version creation command
     * @return persisted draft version
     */
    GraphVersion createVersion(CreateVersionCommand command);

    /**
     * Loads one version by internal identifier.
     *
     * @param versionId version identifier
     * @return matching version snapshot
     */
    GraphVersion getVersion(String versionId);

    /**
     * Queries versions for one definition.
     *
     * @param query metadata query filter
     * @return immutable page of versions
     */
    List<GraphVersion> queryVersions(GraphVersionQuery query);

    /**
     * Runs lint and compilation validation for one stored version.
     *
     * @param versionId version identifier
     * @return validation result
     */
    VersionValidationResult validateVersion(String versionId);

    /**
     * Publishes one stored version into the underlying runtime.
     *
     * @param versionId version identifier
     * @param expectedRevision optimistic-lock guard
     * @return published version result
     */
    PublishVersionResult publishVersion(String versionId, long expectedRevision);

    /**
     * Compares two versions of the same graph definition and returns a
     * structural diff covering source equality, line-oriented unified diff,
     * and compiled metadata changes.
     *
     * @param leftVersionId  left (typically older) version identifier
     * @param rightVersionId right (typically newer) version identifier
     * @return version diff result
     */
    GraphVersionDiff diffVersions(String leftVersionId, String rightVersionId);

    /**
     * Marks one published version as deprecated so new starts stop routing to it.
     *
     * @param versionId version identifier
     * @param expectedRevision optimistic-lock guard
     * @return deprecated version snapshot
     */
    GraphVersion deprecateVersion(String versionId, long expectedRevision);

    /**
     * Creates a new deployment routing configuration.
     *
     * @param command deployment creation command
     * @return persisted deployment snapshot
     */
    GraphDeployment createDeployment(CreateDeploymentCommand command);

    /**
     * Loads one deployment by identifier.
     *
     * @param deploymentId deployment identifier
     * @return matching deployment
     */
    GraphDeployment getDeployment(String deploymentId);

    /**
     * Queries deployment routing metadata.
     *
     * @param query deployment query filter
     * @return immutable page of deployments
     */
    List<GraphDeployment> queryDeployments(GraphDeploymentQuery query);

    /**
     * Updates mutable deployment routing metadata.
     *
     * @param command deployment update command
     * @return updated deployment snapshot
     */
    GraphDeployment updateDeployment(UpdateDeploymentCommand command);

    /**
     * Changes one deployment's active flag.
     *
     * @param deploymentId deployment identifier
     * @param active target active-state flag
     * @param expectedRevision optimistic-lock guard
     * @return updated deployment snapshot
     */
    GraphDeployment activateDeployment(String deploymentId, boolean active, long expectedRevision);

    /**
     * Starts a new instance by resolving a published version and executing the
     * corresponding runtime artifact.
     *
     * @param command instance start command
     * @return start result with the projected instance snapshot
     */
    StartInstanceResult startInstance(StartInstanceCommand command);

    /**
     * Loads one projected instance.
     *
     * @param instanceId execution identifier
     * @return refreshed instance snapshot
     */
    GraphInstance getInstance(String instanceId);

    /**
     * Loads one business-safe context snapshot for an instance.
     *
     * <p>The returned projection always includes the captured start variables and,
     * depending on execution mode, may also include graph node outputs, session
     * shared state / phase outputs, or state-machine shared context / state
     * outputs.</p>
     *
     * @param instanceId instance identifier
     * @return immutable context snapshot
     */
    GraphInstanceContext getInstanceContext(String instanceId);

    /**
     * Queries projected instances.
     *
     * @param query instance query filter
     * @return immutable page of refreshed instance projections
     */
    List<GraphInstance> queryInstances(GraphInstanceQuery query);

    /**
     * Returns a tenant-scoped operations snapshot for the graph-engine control plane.
     *
     * <p>The snapshot aggregates bounded samples from instance, deployment, and dead-letter
     * projections so dashboards and operators can quickly identify health, blockers, and
     * next recovery actions. It is intentionally not a replacement for paginated APIs or
     * external metrics.</p>
     *
     * @param tenantId tenant identifier, or {@code null} to use the current bound tenant
     * @param namespace namespace identifier, or {@code null} to use the current bound namespace
     * @return operations snapshot
     */
    GraphOperationsSnapshot queryOperationsSnapshot(String tenantId, String namespace);

    /**
     * Delivers an external signal to one running or suspended instance.
     *
     * @param command signal command
     * @return refreshed instance result
     */
    SignalInstanceResult signalInstance(SignalInstanceCommand command);

    /**
     * Cancels one active instance and projects the resulting terminal state.
     *
     * @param instanceId instance identifier
     * @param reason human-readable cancellation reason
     * @param expectedRevision optimistic-lock guard on the instance projection
     * @return refreshed cancelled instance
     */
    GraphInstance cancelInstance(String instanceId, String reason, long expectedRevision);

    /**
     * Force-terminates one active instance and projects the resulting terminal state.
     *
     * @param instanceId instance identifier
     * @param reason human-readable termination reason
     * @param expectedRevision optimistic-lock guard on the instance projection
     * @return refreshed terminated instance
     */
    GraphInstance terminateInstance(String instanceId, String reason, long expectedRevision);

    /**
     * Queries node-level audit events for one instance.
     *
     * @param instanceId instance identifier
     * @param page zero-based page index
     * @param size requested page size
     * @return immutable page of audit events
     */
    List<GraphAuditEntry> queryInstanceAuditLog(String instanceId, int page, int size);

    /**
     * Queries control-plane action audit events for one instance as a structured timeline.
     *
     * @param instanceId instance identifier
     * @param page zero-based page index
     * @param size requested page size
     * @return immutable page of structured control action events
     */
    default List<GraphControlActionEntry> queryInstanceControlActions(String instanceId, int page, int size) {
        throw new UnsupportedOperationException("Structured control action timeline is not supported");
    }

    /**
     * Queries durable status transitions for one instance.
     *
     * @param instanceId instance identifier
     * @param page zero-based page index
     * @param size requested page size
     * @return immutable page of transition entries
     * @apiNote SESSION instances use a control-plane-first strategy. When the control plane
     * returns rows for {@code page = 0}, callers should treat that source as authoritative for
     * the whole paginated history. Checkpoint synthesis is only a first-page fallback when the
     * control plane is absent or returns an empty first page, because synthesized transition
     * chains are a single projection that cannot be meaningfully paginated across later pages.
     */
    List<GraphTransitionEntry> queryInstanceTransitions(String instanceId, int page, int size);

    /**
     * Queries tenant-scoped dead-letter items.
     *
     * @param query dead-letter query filter
     * @return immutable page of dead-letter entries
     */
    List<GraphDeadLetter> queryDeadLetters(GraphDeadLetterQuery query);

    /**
     * Replays one dead-lettered work item back into the ready queue.
     *
     * @param itemId dead-letter item identifier
     */
    void retryDeadLetter(String itemId);

    /**
     * Replays one dead-lettered work item back into the ready queue and attaches
     * recovery evidence for audit/correlation.
     *
     * @param itemId dead-letter item identifier
     * @param evidence optional recovery evidence
     */
    default void retryDeadLetter(String itemId, RecoveryActionEvidence evidence) {
        retryDeadLetter(itemId);
    }

    /**
     * Replays one dead-lettered work item and returns whether the request was
     * executed now or replayed from a prior terminal requestId result.
     *
     * @param itemId dead-letter item identifier
     * @param evidence optional recovery evidence
     * @return retry result with idempotency metadata
     */
    default RetryDeadLetterResult retryDeadLetterWithResult(String itemId, RecoveryActionEvidence evidence) {
        retryDeadLetter(itemId, evidence);
        return new RetryDeadLetterResult(itemId, null, 1);
    }

    /**
     * Registers one remote worker against the active deployment bindings visible in the current scope.
     *
     * @param command registration command
     * @return resolved registration view
     */
    GraphRemoteWorkerRegistration registerRemoteWorker(RegisterRemoteWorkerCommand command);

    /**
     * Polls and claims ready remote-worker execution items for one worker topic.
     *
     * @param command poll command
     * @return claimed remote-worker jobs
     */
    List<GraphRemoteWorkerJob> pollRemoteWorkerJobs(PollRemoteWorkerJobsCommand command);

    /**
     * Renews the lease on one claimed remote-worker job.
     *
     * @param command heartbeat command
     * @return refreshed claimed job
     */
    GraphRemoteWorkerJob heartbeatRemoteWorkerJob(HeartbeatRemoteWorkerJobCommand command);

    /**
     * Completes one claimed remote-worker job and resumes the suspended execution node.
     *
     * @param command completion command
     */
    void completeRemoteWorkerJob(CompleteRemoteWorkerJobCommand command);

    /**
     * Reports one claimed remote-worker job as failed, scheduling a retry or dead-letter transition.
     *
     * @param command failure command
     */
    void failRemoteWorkerJob(FailRemoteWorkerJobCommand command);

    /**
     * Loads one human-task projection.
     *
     * @param taskId task identifier
     * @return matching task
     */
    GraphTask getTask(String taskId);

    /**
     * Queries human tasks and enriches them with product-layer metadata.
     *
     * @param query task query filter
     * @return immutable page of task projections
     */
    List<GraphTask> queryTasks(TaskInboxQuery query);

    /**
     * Claims one task for a concrete user.
     *
     * @param command claim command
     * @return refreshed task projection
     */
    GraphTask claimTask(ClaimTaskCommand command);

    /**
     * Completes one task and resumes the underlying execution when work-item
     * dispatch is configured.
     *
     * @param command completion command
     * @return refreshed task projection
     */
    GraphTask completeTask(CompleteTaskCommand command);

    /**
     * Reassigns one task to another owner.
     *
     * @param command reassignment command
     * @return refreshed task projection
     */
    GraphTask reassignTask(ReassignTaskCommand command);

    /**
     * Cancels one task.
     *
     * @param command cancellation command
     * @return refreshed task projection
     */
    GraphTask cancelTask(CancelTaskCommand command);

    /**
     * Queries the operator inventory visible in the current scope.
     *
     * <p>Each entry combines runtime registry metadata, human-facing annotation
     * details (from {@code @OperatorMeta} and {@code @BlogeOperator}), schema
     * information, and cross-definition usage statistics derived from
     * {@link com.leanowtech.bloge.graphengine.model.GraphVersionMetadata#operatorRefs()}
     * of visible graph versions.</p>
     *
     * @param query inventory query filter
     * @return immutable list of inventory entries
     */
    List<OperatorInventoryEntry> queryOperatorInventory(OperatorInventoryQuery query);

    /**
     * Returns one filtered and paginated node-state projection for a graph instance.
     *
     * <p>Node state is not persisted; it is derived from the compiled execution
     * artifact plus durable runtime state. GRAPH instances project DAG nodes,
     * SESSION instances project phases, and STATE_MACHINE instances project
     * state lifecycle using the same DTO shape.</p>
     *
     * @param instanceId instance identifier
     * @param statuses optional status filter; empty or {@code null} means all statuses
     * @param page zero-based page index
     * @param size requested page size
     * @return paged node-state projection
     */
    PagedResult<GraphNodeState> queryInstanceNodes(String instanceId, Set<GraphNodeStatus> statuses, int page, int size);

    /**
     * Returns the inferred execution state of every node in one graph instance.
     *
     * @param instanceId instance identifier
     * @return immutable list of node states in their natural projection order
     */
    default List<GraphNodeState> queryInstanceNodes(String instanceId) {
        return queryInstanceNodes(instanceId, null, 0, Integer.MAX_VALUE).items();
    }

    /**
     * Returns the stored visual layout for one immutable version.
     *
     * @param versionId internal version identifier
     * @return version diagram payload
     */
    GraphVersionDiagram getVersionDiagram(String versionId);

    /**
     * Returns the stored visual layout for one concrete instance plus its node-state overlay.
     *
     * @param instanceId instance identifier
     * @return instance diagram payload
     */
    GraphInstanceDiagram getInstanceDiagram(String instanceId);

    /**
     * Returns the external signals currently awaited by one suspended GRAPH instance.
     *
     * <p>The first iteration only supports {@link GraphExecutionMode#GRAPH}. Other
     * execution modes fail with {@link GraphEngineServiceErrorCode#UNSUPPORTED_EXECUTION_MODE}
     * so callers do not mistake the absence of a product projection for the absence
     * of pending work.</p>
     *
     * @param instanceId instance identifier
     * @return immutable list of pending signal projections
     */
    List<GraphPendingSignal> queryPendingSignals(String instanceId);

    /**
     * Retries all dead-lettered work items belonging to one instance, optionally
     * filtered to a subset of node identifiers.
     *
     * <p>Each matching dead-lettered work item is restored to {@code READY} via
     * {@code WorkItemStore.restoreDeadLetter(...)} and a dispatch cycle is triggered
     * so items re-enter the normal execution flow.</p>
     *
     * @param instanceId       instance identifier
     * @param nodeIds          optional node-ID filter; when empty or {@code null}, all
     *                         dead-lettered items for the instance are retried
     * @param expectedRevision optimistic-lock guard on the instance projection
     * @return retry result with refreshed instance and count of restored items
     */
    RetryInstanceResult retryInstance(String instanceId, java.util.Set<String> nodeIds, long expectedRevision);

    /**
     * Retries all dead-lettered work items belonging to one instance and attaches
     * recovery evidence for audit/correlation.
     *
     * @param instanceId       instance identifier
     * @param nodeIds          optional node-ID filter; when empty or {@code null}, all
     *                         dead-lettered items for the instance are retried
     * @param expectedRevision optimistic-lock guard on the instance projection
     * @param evidence optional recovery evidence
     * @return retry result with refreshed instance and count of restored items
     */
    default RetryInstanceResult retryInstance(String instanceId,
                                             java.util.Set<String> nodeIds,
                                             long expectedRevision,
                                             RecoveryActionEvidence evidence) {
        return retryInstance(instanceId, nodeIds, expectedRevision);
    }
}
