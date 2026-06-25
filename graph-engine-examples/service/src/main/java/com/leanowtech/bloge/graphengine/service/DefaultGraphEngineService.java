package com.leanowtech.bloge.graphengine.service;

import com.leanowtech.bloge.core.checkpoint.TaskStore;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.core.context.TenantContextHolder;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.engine.StreamingGraphHandle;
import com.leanowtech.bloge.core.engine.ValidationResult;
import com.leanowtech.bloge.core.exception.OptimisticLockException;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.core.operator.RemoteWorkerEnvelope;
import com.leanowtech.bloge.core.runtime.checkpoint.CheckpointType;
import com.leanowtech.bloge.core.runtime.checkpoint.ExecutionCheckpoint;
import com.leanowtech.bloge.core.runtime.execution.ExecutionInstance;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStatus;
import com.leanowtech.bloge.core.runtime.event.EventMatcher;
import com.leanowtech.bloge.core.runtime.event.EventMatcherQuery;
import com.leanowtech.bloge.core.runtime.event.EventMatcherStatus;
import com.leanowtech.bloge.core.runtime.registry.GraphStatus;
import com.leanowtech.bloge.core.runtime.wait.ExecutionWait;
import com.leanowtech.bloge.core.runtime.wait.WaitStatus;
import com.leanowtech.bloge.core.runtime.wait.WaitType;
import com.leanowtech.bloge.core.runtime.work.WorkItem;
import com.leanowtech.bloge.core.runtime.work.WorkItemQuery;
import com.leanowtech.bloge.core.runtime.work.WorkItemStatus;
import com.leanowtech.bloge.core.runtime.work.WorkItemStore;
import com.leanowtech.bloge.core.runtime.work.WorkItemType;
import com.leanowtech.bloge.core.schema.SchemaCompatibility;
import com.leanowtech.bloge.core.schema.SchemaDescriptorJsonCodec;
import com.leanowtech.bloge.core.schema.SchemaEvolutionChecker;
import com.leanowtech.bloge.core.runtime.registry.GraphDefinitionHasher;
import com.leanowtech.bloge.core.runtime.registry.VersionRoutingPolicy;
import com.leanowtech.bloge.core.spi.OperatorAnnotationDetails;
import com.leanowtech.bloge.core.spi.OperatorAnnotationIntrospector;
import com.leanowtech.bloge.core.spi.OperatorMetadata;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.durable.TaskInboxTaskStore;
import com.leanowtech.bloge.durable.WorkItemTaskStore;
import com.leanowtech.bloge.durable.control.DeadLetterEntry;
import com.leanowtech.bloge.durable.control.DeadLetterQuery;
import com.leanowtech.bloge.durable.control.ExecutionTransitionLogEntry;
import com.leanowtech.bloge.durable.control.ExecutionTransitionQuery;
import com.leanowtech.bloge.session.durable.checkpoint.SessionCheckpoint;
import com.leanowtech.bloge.ext.engine.OwnerOnlySessionAccessGuard;
import com.leanowtech.bloge.ext.model.SessionGraph;
import com.leanowtech.bloge.ext.model.SessionIdentity;
import com.leanowtech.bloge.ext.model.RoundRecord;
import com.leanowtech.bloge.ext.model.SessionStateSnapshot;
import com.leanowtech.bloge.ext.model.SessionStatus;
import com.leanowtech.bloge.graphengine.model.GraphAuditEntry;
import com.leanowtech.bloge.graphengine.model.GraphDefinition;
import com.leanowtech.bloge.graphengine.model.GraphDeadLetter;
import com.leanowtech.bloge.graphengine.model.GraphDefinitionStatus;
import com.leanowtech.bloge.graphengine.model.GraphDeployment;
import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;
import com.leanowtech.bloge.graphengine.model.GraphInstance;
import com.leanowtech.bloge.graphengine.model.GraphInstanceDiagram;
import com.leanowtech.bloge.graphengine.model.GraphInstanceContext;
import com.leanowtech.bloge.graphengine.model.GraphInstanceStatus;
import com.leanowtech.bloge.graphengine.model.GraphNodeState;
import com.leanowtech.bloge.graphengine.model.GraphNodeStatus;
import com.leanowtech.bloge.graphengine.model.GraphPendingSignal;
import com.leanowtech.bloge.graphengine.model.PagedResult;
import com.leanowtech.bloge.graphengine.model.GraphRemoteWorkerAssignment;
import com.leanowtech.bloge.graphengine.model.GraphRemoteWorkerJob;
import com.leanowtech.bloge.graphengine.model.GraphRemoteWorkerRegistration;
import com.leanowtech.bloge.graphengine.model.GraphTask;
import com.leanowtech.bloge.graphengine.model.GraphTaskStatus;
import com.leanowtech.bloge.graphengine.model.GraphTransitionEntry;
import com.leanowtech.bloge.graphengine.model.GraphVersion;
import com.leanowtech.bloge.graphengine.model.GraphVersionDiagram;
import com.leanowtech.bloge.graphengine.model.GraphVersionMetadata;
import com.leanowtech.bloge.graphengine.model.GraphVersionStatus;
import com.leanowtech.bloge.graphengine.model.RemoteWorkerBinding;
import com.leanowtech.bloge.graphengine.model.TaskDefinition;
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
import com.leanowtech.bloge.graphengine.store.GraphDeadLetterQuery;
import com.leanowtech.bloge.graphengine.store.GraphEngineErrorCode;
import com.leanowtech.bloge.graphengine.store.GraphEngineStoreException;
import com.leanowtech.bloge.graphengine.store.GraphEngineStores;
import com.leanowtech.bloge.graphengine.store.GraphInstanceQuery;
import com.leanowtech.bloge.graphengine.store.GraphDeploymentQuery;
import com.leanowtech.bloge.graphengine.store.GraphVersionQuery;
import com.leanowtech.bloge.runtime.engine.DurableGraphEngine;
import com.leanowtech.bloge.runtime.audit.AuditEntry;
import com.leanowtech.bloge.runtime.task.TaskInbox;
import com.leanowtech.bloge.runtime.task.TaskInboxQuery;
import com.leanowtech.bloge.runtime.task.TaskInboxStatus;
import com.leanowtech.bloge.runtime.task.TaskInboxStore;
import com.leanowtech.bloge.session.durable.DurableSessionManager;
import com.leanowtech.bloge.session.durable.checkpoint.ExecutionCheckpointSessionStore;
import com.leanowtech.bloge.state.checkpoint.ExecutionCheckpointStateMachineStore;
import com.leanowtech.bloge.state.checkpoint.StateMachineCheckpoint;
import com.leanowtech.bloge.state.durable.DurableStateMachineManager;
import com.leanowtech.bloge.state.engine.StateMachineResult;
import com.leanowtech.bloge.state.model.StateDef;
import com.leanowtech.bloge.state.model.StateExecutionRecord;
import com.leanowtech.bloge.state.model.StateMachineDef;
import com.leanowtech.bloge.state.model.StateMachineStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default product-layer graph-engine service implementation.
 *
 * <p>This service composes the graph-engine metadata stores with the existing
 * durable BLOGE runtime so product APIs can manage definitions, versions,
 * deployments, instances, and human tasks without duplicating engine logic.</p>
 */
public class DefaultGraphEngineService implements GraphEngineService, AutoCloseable {
    private static final Logger logger = Logger.getLogger(DefaultGraphEngineService.class.getName());

    private static final String GOVERNANCE_SOURCE = "graph-engine-service";
    private static final String SESSION_TERMINATION_REASON_KEY = "__termination_reason__";
    private static final String SESSION_CANCEL_REASON_PREFIX = "__graph_engine_cancel__:";
    private static final String SESSION_SYNTHETIC_TRANSITION_SOURCE = "session-checkpoint-synthesis";
    private static final String SESSION_SYNTHETIC_TRANSITION_REASON = "Projected from session round history";
    private static final String DEFAULT_CANCEL_REASON = "cancelled";
    private static final String DEFAULT_TERMINATION_REASON = "terminated";
    private static final Duration EXECUTION_START_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration EXECUTION_START_POLL_INTERVAL = Duration.ofMillis(10);
    private static final Duration EXECUTION_RESULT_GRACE = Duration.ofMillis(100);
    /** Defensive cap for pending-signal matcher projection to avoid unbounded loads per instance. */
    private static final int MAX_PENDING_SIGNAL_MATCHERS = 10_000;

    private final GraphEngineStores stores;
    private final GraphEngineRuntimeSupport runtimeSupport;
    private final VersionCompiler versionCompiler;
    private final VisualLayoutGenerator visualLayoutGenerator;
    private final DurableGraphEngine durableGraphEngine;
    private final GraphEngineMetricsObserver metricsObserver;
    private final String stateMachineOwnerId = "graph-engine-service-" + UUID.randomUUID();
    private final String sessionOwnerId = "graph-engine-session-service-" + UUID.randomUUID();
    private final ConcurrentHashMap<String, SessionGraph> sessionDefinitions = new ConcurrentHashMap<>();
    private volatile DurableSessionManager sessionManager;

    /**
     * Creates a graph-engine service facade backed by the supplied stores and runtime support.
     *
     * @param stores product metadata stores
     * @param runtimeSupport runtime collaborators
     */
    public DefaultGraphEngineService(GraphEngineStores stores, GraphEngineRuntimeSupport runtimeSupport) {
        this.stores = Objects.requireNonNull(stores, "stores");
        this.runtimeSupport = Objects.requireNonNull(runtimeSupport, "runtimeSupport");
        this.versionCompiler = new VersionCompiler(runtimeSupport);
        this.visualLayoutGenerator = new VisualLayoutGenerator(runtimeSupport.jsonCodec());
        this.durableGraphEngine = runtimeSupport.durableGraphEngine();
        this.metricsObserver = runtimeSupport.metricsObserver();
    }

    @Override
    public GraphDefinition createDefinition(CreateDefinitionCommand command) {
        // createDefinition is open — no owning definition exists yet to enforce against.
        Scope scope = resolveScope(command.tenantId(), command.namespace());
        GraphDefinition definition = new GraphDefinition(
                UUID.randomUUID().toString(),
                command.definitionKey(),
                scope.tenantId(),
                scope.namespace(),
                command.displayName(),
                command.description(),
                command.category(),
                command.labels(),
                command.ownerTeam(),
                command.rbacPolicy(),
                GraphDefinitionStatus.ACTIVE,
                0,
                runtimeSupport.timeSource().now(),
                runtimeSupport.timeSource().now()
        );
        stores.graphDefinitionStore().create(definition);
        return definition;
    }

    @Override
    public GraphDefinition getDefinition(String definitionId) {
        GraphDefinition definition = requireDefinition(definitionId);
        RbacEnforcer.requireView(definition);
        return definition;
    }

    @Override
    public GraphDefinition getDefinitionByKey(String definitionKey, String tenantId, String namespace) {
        Scope scope = resolveScope(tenantId, namespace);
        GraphDefinition definition = stores.graphDefinitionStore().getByKey(scope.tenantId(), scope.namespace(), definitionKey)
                .orElseThrow(() -> notFound("Definition not found for key '" + definitionKey + "'"));
        RbacEnforcer.requireView(definition);
        return definition;
    }

    @Override
    public List<GraphDefinition> queryDefinitions(GraphDefinitionQuery query) {
        return stores.graphDefinitionStore().query(Objects.requireNonNull(query, "query")).stream()
                .filter(this::canViewDefinition)
                .toList();
    }

    @Override
    public GraphDefinition updateDefinition(UpdateDefinitionCommand command) {
        GraphDefinition existing = requireDefinition(command.definitionId());
        RbacEnforcer.requireAdmin(existing);
        GraphDefinition updated = new GraphDefinition(
                existing.definitionId(),
                existing.definitionKey(),
                existing.tenantId(),
                existing.namespace(),
                command.displayName(),
                command.description(),
                command.category(),
                command.labels(),
                command.ownerTeam(),
                command.rbacPolicy(),
                existing.status(),
                existing.revision(),
                existing.createdAt(),
                runtimeSupport.timeSource().now()
        );
        return stores.graphDefinitionStore().update(updated, command.expectedRevision());
    }

    @Override
    public GraphDefinition archiveDefinition(String definitionId, long expectedRevision) {
        GraphDefinition definition = requireDefinition(definitionId);
        RbacEnforcer.requireAdmin(definition);
        return stores.graphDefinitionStore().archive(definitionId, expectedRevision);
    }

    @Override
    public GraphVersion createVersion(CreateVersionCommand command) {
        GraphDefinition definition = requireActiveDefinition(command.definitionKey(), command.tenantId(), command.namespace());
        RbacEnforcer.requireDeploy(definition);
        String versionId = UUID.randomUUID().toString();
        GraphVersion draft = new GraphVersion(
                versionId,
                definition.definitionId(),
                command.version(),
                GraphDefinitionHasher.sha256Hex(command.dslSource()),
                command.dslSource(),
                command.visualLayout(),
                new GraphVersionMetadata(null, null, null, null, null, null, null),
                null,
                command.migrationPolicy(),
                GraphVersionStatus.DRAFT,
                0,
                null,
                runtimeSupport.timeSource().now(),
                runtimeSupport.timeSource().now()
        );
        VersionCompileResult compilation = versionCompiler.compile(definition, draft);

        GraphVersion version = new GraphVersion(
                versionId,
                definition.definitionId(),
                command.version(),
                compilation.contentHash(),
                command.dslSource(),
                command.visualLayout(),
                compilation.metadata(),
                compilation.compiledArtifactRef(),
                command.migrationPolicy(),
                GraphVersionStatus.DRAFT,
                0,
                null,
                runtimeSupport.timeSource().now(),
                runtimeSupport.timeSource().now()
        );
        stores.graphVersionStore().create(version);
        return version;
    }

    @Override
    public GraphVersion getVersion(String versionId) {
        GraphVersion version = requireVersion(versionId);
        RbacEnforcer.requireView(requireDefinition(version.definitionId()));
        return version;
    }

    @Override
    public List<GraphVersion> queryVersions(GraphVersionQuery query) {
        return stores.graphVersionStore().query(Objects.requireNonNull(query, "query")).stream()
                .filter(this::canViewVersion)
                .toList();
    }

    @Override
    public VersionValidationResult validateVersion(String versionId) {
        GraphVersion version = requireVersion(versionId);
        RbacEnforcer.requireDeploy(requireDefinition(version.definitionId()));
        return validateVersion(version);
    }

    private VersionValidationResult validateVersion(GraphVersion version) {
        GraphDefinition definition = requireDefinition(version.definitionId());
        VersionCompileResult compilation = versionCompiler.compile(definition, version);
        return new VersionValidationResult(
                version.versionId(),
                compilation.executionMode(),
                compilation.runtimeName(),
                compilation.declaredRootName(),
                compilation.contentHash(),
                compilation.compiledArtifactRef(),
                compilation.metadata(),
                compilation.diagnostics(),
                compilation.valid()
        );
    }

    @Override
    public PublishVersionResult publishVersion(String versionId, long expectedRevision) {
        GraphVersion version = requireVersion(versionId);
        GraphDefinition definition = requireDefinition(version.definitionId());
        RbacEnforcer.requireDeploy(definition);
        requireDefinitionIsActive(definition);

        VersionCompileResult compilation = versionCompiler.compile(definition, version);
        if (!compilation.valid()) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.VALIDATION_FAILED,
                    "Version '" + version.version() + "' contains blocking diagnostics and cannot be published"
            );
        }

        SchemaCompatibility compatibility = compatibility(definition, version, compilation);
        GraphVersion updated = new GraphVersion(
                version.versionId(),
                version.definitionId(),
                version.version(),
                compilation.contentHash(),
                version.dslSource(),
                version.visualLayout(),
                compilation.metadata(),
                compilation.compiledArtifactRef(),
                version.migrationPolicy(),
                GraphVersionStatus.PUBLISHED,
                version.revision(),
                runtimeSupport.timeSource().now(),
                version.createdAt(),
                runtimeSupport.timeSource().now()
        );

        if (compilation.executionMode() == GraphExecutionMode.GRAPH) {
            publishGraphArtifact(updated, compilation);
        } else if (compilation.executionMode() == GraphExecutionMode.SESSION) {
            registerSessionGraph(compilation);
        }

        GraphVersion stored = stores.graphVersionStore().update(updated, expectedRevision);
        versionCompiler.invalidate(version);
        metricsObserver.onVersionPublished(definition.definitionKey(), definition.tenantId(), definition.namespace());
        return new PublishVersionResult(stored, compatibility);
    }

    @Override
    public GraphVersion deprecateVersion(String versionId, long expectedRevision) {
        GraphVersion version = requireVersion(versionId);
        RbacEnforcer.requireDeploy(requireDefinition(version.definitionId()));
        if (version.status() == GraphVersionStatus.DEPRECATED) {
            requireExpectedRevision(version.revision(), expectedRevision, "Version");
            return version;
        }
        if (version.status() != GraphVersionStatus.PUBLISHED) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.INVALID_STATE,
                    "Version '" + version.version() + "' is not published"
            );
        }
        GraphVersion updated = new GraphVersion(
                version.versionId(),
                version.definitionId(),
                version.version(),
                version.contentHash(),
                version.dslSource(),
                version.visualLayout(),
                version.metadata(),
                version.compiledArtifactRef(),
                version.migrationPolicy(),
                GraphVersionStatus.DEPRECATED,
                version.revision(),
                runtimeSupport.timeSource().now(),
                version.createdAt(),
                runtimeSupport.timeSource().now()
        );
        GraphVersion stored = stores.graphVersionStore().update(updated, expectedRevision);
        versionCompiler.invalidate(version);
        return stored;
    }

    @Override
    public GraphVersionDiff diffVersions(String leftVersionId, String rightVersionId) {
        GraphVersion left = requireVersion(leftVersionId);
        GraphVersion right = requireVersion(rightVersionId);
        if (!left.definitionId().equals(right.definitionId())) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.VALIDATION_FAILED,
                    "Cannot diff versions from different definitions"
            );
        }
        RbacEnforcer.requireView(requireDefinition(left.definitionId()));
        VersionValidationResult leftValidation = validateVersion(left);
        VersionValidationResult rightValidation = validateVersion(right);

        boolean sourceEqual = left.contentHash().equals(right.contentHash());
        List<String> unifiedDiff = sourceEqual
                ? List.of()
                : SourceDiffer.unifiedDiff(left.dslSource(), right.dslSource(),
                        left.version(), right.version());

        MetadataDiff metadataDiff = computeMetadataDiff(leftValidation.metadata(), rightValidation.metadata());
        return new GraphVersionDiff(
                VersionSummary.from(left, leftValidation),
                VersionSummary.from(right, rightValidation),
                sourceEqual,
                unifiedDiff,
                metadataDiff
        );
    }

    @Override
    public GraphDeployment createDeployment(CreateDeploymentCommand command) {
        GraphDefinition definition = requireActiveDefinition(command.definitionKey(), command.tenantId(), command.namespace());
        RbacEnforcer.requireDeploy(definition);
        validateRoutingPolicy(definition.definitionId(), command.routingPolicy());
        if (command.active()) {
            deactivateOtherDeployments(definition.definitionKey(), definition.tenantId(), definition.namespace(), command.environment(), null);
        }
        GraphDeployment deployment = new GraphDeployment(
                UUID.randomUUID().toString(),
                definition.definitionKey(),
                definition.tenantId(),
                definition.namespace(),
                command.environment(),
                command.routingPolicy(),
                command.operatorPlaneConfig(),
                command.active(),
                0,
                runtimeSupport.timeSource().now(),
                runtimeSupport.timeSource().now()
        );
        stores.graphDeploymentStore().create(deployment);
        return deployment;
    }

    @Override
    public GraphDeployment getDeployment(String deploymentId) {
        GraphDeployment deployment = stores.graphDeploymentStore().get(deploymentId)
                .orElseThrow(() -> notFound("Deployment not found: " + deploymentId));
        enforceDeploymentView(deployment);
        return deployment;
    }

    @Override
    public List<GraphDeployment> queryDeployments(com.leanowtech.bloge.graphengine.store.GraphDeploymentQuery query) {
        return stores.graphDeploymentStore().query(Objects.requireNonNull(query, "query")).stream()
                .filter(this::canViewDeployment)
                .toList();
    }

    @Override
    public GraphDeployment updateDeployment(UpdateDeploymentCommand command) {
        GraphDeployment existing = getDeployment(command.deploymentId());
        GraphDefinition definition = getDefinitionByKey(existing.definitionKey(), existing.tenantId(), existing.namespace());
        RbacEnforcer.requireDeploy(definition);
        validateRoutingPolicy(definition.definitionId(), command.routingPolicy());
        if (command.active()) {
            deactivateOtherDeployments(
                    existing.definitionKey(),
                    existing.tenantId(),
                    existing.namespace(),
                    existing.environment(),
                    existing.deploymentId()
            );
        }
        GraphDeployment updated = new GraphDeployment(
                existing.deploymentId(),
                existing.definitionKey(),
                existing.tenantId(),
                existing.namespace(),
                existing.environment(),
                command.routingPolicy(),
                command.operatorPlaneConfig(),
                command.active(),
                existing.revision(),
                existing.createdAt(),
                runtimeSupport.timeSource().now()
        );
        return stores.graphDeploymentStore().update(updated, command.expectedRevision());
    }

    @Override
    public GraphDeployment activateDeployment(String deploymentId, boolean active, long expectedRevision) {
        GraphDeployment existing = getDeployment(deploymentId);
        enforceDeploymentDeploy(existing);
        if (active) {
            deactivateOtherDeployments(existing.definitionKey(), existing.tenantId(), existing.namespace(), existing.environment(), deploymentId);
        }
        return stores.graphDeploymentStore().activate(deploymentId, active, expectedRevision);
    }

    @Override
    public StartInstanceResult startInstance(StartInstanceCommand command) {
        GraphDefinition definition = requireActiveDefinition(command.definitionKey(), command.tenantId(), command.namespace());
        RbacEnforcer.requireStart(definition);
        if (command.businessKey() != null) {
            stores.graphInstanceStore().findByBusinessKey(definition.tenantId(), definition.namespace(), command.businessKey())
                    .ifPresent(existing -> {
                        throw new GraphEngineServiceException(
                                GraphEngineServiceErrorCode.DUPLICATE_BUSINESS_KEY,
                                "Business key already exists: " + command.businessKey()
                        );
                    });
        }

        GraphVersion version = resolvePublishedVersion(definition, command.version(), command.environment());
        VersionCompileResult compilation = versionCompiler.compile(definition, version);
        if (!compilation.valid()) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.VALIDATION_FAILED,
                    "Version '" + version.version() + "' failed to compile for execution"
            );
        }

        StartInstanceResult result = switch (compilation.executionMode()) {
            case GRAPH -> startGraphInstance(definition, version, compilation, command);
            case SESSION -> startSessionInstance(definition, version, compilation, command);
            case STATE_MACHINE -> startStateMachineInstance(definition, version, compilation, command);
        };
        GraphInstance inst = result.instance();
        recordInstanceStarted(inst);
        recordInstanceCompletedIfTerminal(inst);
        return result;
    }

    @Override
    public GraphInstance getInstance(String instanceId) {
        GraphInstance instance = stores.graphInstanceStore().get(instanceId)
                .orElseThrow(() -> notFound("Instance not found: " + instanceId));
        enforceInstanceView(instance);
        return refreshProjection(instance);
    }

    @Override
    public GraphInstanceContext getInstanceContext(String instanceId) {
        GraphInstance instance = getInstance(instanceId);
        InstanceContextProjection projection = switch (instance.executionMode()) {
            case GRAPH -> new InstanceContextProjection(
                    loadGraphNodeOutputs(instance.instanceId()),
                    Map.of(),
                    Map.of(),
                    Map.of()
            );
            case SESSION -> loadSessionContext(instance);
            case STATE_MACHINE -> loadStateMachineContext(instance);
        };
        return new GraphInstanceContext(
                instance.instanceId(),
                instance.executionMode(),
                instance.variables(),
                projection.nodeOutputs(),
                projection.sharedState(),
                projection.phaseOutputs(),
                projection.stateOutputs(),
                runtimeSupport.timeSource().now()
        );
    }

    @Override
    public List<GraphInstance> queryInstances(GraphInstanceQuery query) {
        return stores.graphInstanceStore().query(Objects.requireNonNull(query, "query")).stream()
                .filter(this::canViewInstance)
                .map(this::refreshProjection)
                .toList();
    }

    @Override
    public SignalInstanceResult signalInstance(SignalInstanceCommand command) {
        GraphInstance instance = getInstance(command.instanceId());
        GraphVersion version = requireVersion(instance.versionId());
        GraphDefinition definition = getDefinitionByKey(instance.definitionKey(), instance.tenantId(), instance.namespace());
        RbacEnforcer.requireStart(definition);
        VersionCompileResult compilation = versionCompiler.compile(definition, version);
        if (!compilation.valid()) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.VALIDATION_FAILED,
                    "Version '" + version.version() + "' can no longer be compiled for signaling"
            );
        }

        SessionProjection sessionBaseline = instance.executionMode() == GraphExecutionMode.SESSION
                ? currentSessionProjection(instance).orElse(null)
                : null;
        SignalInstanceResult result = switch (instance.executionMode()) {
            case GRAPH -> signalGraphInstance(instance, compilation, command);
            case SESSION -> signalSessionInstance(instance, compilation, command);
            case STATE_MACHINE -> signalStateMachineInstance(instance, compilation, command);
        };
        GraphInstance refreshed = switch (instance.executionMode()) {
            case SESSION -> awaitSessionSignalProjection(instance, sessionBaseline, EXECUTION_START_TIMEOUT);
            case GRAPH, STATE_MACHINE -> awaitTerminalProjection(
                    result.instance().instanceId(),
                    result.instance(),
                    EXECUTION_RESULT_GRACE
            );
        };
        recordInstanceCompletedIfTerminal(refreshed);
        return new SignalInstanceResult(refreshed, result.suspendedNodes());
    }

    @Override
    public GraphInstance cancelInstance(String instanceId, String reason, long expectedRevision) {
        GraphInstance instance = getInstance(instanceId);
        enforceInstanceAdmin(instance);
        requireExpectedRevision(instance.revision(), expectedRevision, "Instance");
        requireMutableInstance(instance, "cancel");
        String resolvedReason = normalizeLifecycleReason(reason, DEFAULT_CANCEL_REASON);
        VersionCompileResult sessionCompilation = instance.executionMode() == GraphExecutionMode.SESSION
                ? sessionCompilation(instance, "cancel")
                : null;
        GraphInstance result = switch (instance.executionMode()) {
            case GRAPH, STATE_MACHINE ->
                    updateExecutionLifecycle(instance, ExecutionStatus.CANCELLED, GraphInstanceStatus.CANCELLED, resolvedReason);
            case SESSION -> updateSessionLifecycle(
                    instance,
                    sessionCompilation,
                    GraphInstanceStatus.CANCELLED,
                    encodeSessionCancelReason(resolvedReason),
                    resolvedReason
            );
        };
        recordInstanceCompleted(result);
        return result;
    }

    @Override
    public GraphInstance terminateInstance(String instanceId, String reason, long expectedRevision) {
        GraphInstance instance = getInstance(instanceId);
        enforceInstanceAdmin(instance);
        requireExpectedRevision(instance.revision(), expectedRevision, "Instance");
        requireMutableInstance(instance, "terminate");
        String resolvedReason = normalizeLifecycleReason(reason, DEFAULT_TERMINATION_REASON);
        VersionCompileResult sessionCompilation = instance.executionMode() == GraphExecutionMode.SESSION
                ? sessionCompilation(instance, "terminate")
                : null;
        GraphInstance result = switch (instance.executionMode()) {
            case GRAPH, STATE_MACHINE ->
                    updateExecutionLifecycle(instance, ExecutionStatus.TERMINATED, GraphInstanceStatus.TERMINATED, resolvedReason);
            case SESSION -> updateSessionLifecycle(
                    instance,
                    sessionCompilation,
                    GraphInstanceStatus.TERMINATED,
                    resolvedReason,
                    resolvedReason
            );
        };
        recordInstanceCompleted(result);
        return result;
    }

    @Override
    public List<GraphAuditEntry> queryInstanceAuditLog(String instanceId, int page, int size) {
        GraphInstance instance = getInstance(instanceId);
        // getInstance already enforces view RBAC
        int resolvedSize = size <= 0 ? 50 : size;
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (instance.executionMode() == GraphExecutionMode.SESSION) {
            Optional<SessionNodeProjection> projection = currentSessionNodeProjection(instance);
            if (projection.isPresent()) {
                return paginate(projection.get().history(), page, resolvedSize).stream()
                        .map(round -> mapSessionAuditEntry(instance, round))
                        .toList();
            }
            if (sessionManager == null && runtimeSupport.executionCheckpointStore() == null) {
                throw new GraphEngineServiceException(
                        GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                        "Session audit projection requires durable session access"
                );
            }
            return List.of();
        }
        if (runtimeSupport.auditJournalStore() == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "Audit journal store is not configured"
            );
        }
        return paginate(runtimeSupport.auditJournalStore().queryByExecution(instanceId), page, resolvedSize).stream()
                .map(entry -> mapAuditEntry(instance, entry))
                .toList();
    }

    /**
     * Resolves durable transition history for one instance.
     *
     * @implNote SESSION instances consult the control plane first. When the control plane returns
     * data for {@code page = 0}, every page is served from that authoritative source. Session
     * checkpoint synthesis is only used as a first-page fallback and is never repeated for later
     * pages, because the synthesized chain is a single full-history projection instead of a true
     * paginated source.
     */
    @Override
    public List<GraphTransitionEntry> queryInstanceTransitions(String instanceId, int page, int size) {
        GraphInstance instance = getInstance(instanceId);
        // getInstance already enforces view RBAC
        ExecutionTransitionQuery query = new ExecutionTransitionQuery(instanceId, page, size);
        if (instance.executionMode() == GraphExecutionMode.SESSION) {
            if (runtimeSupport.controlPlaneService() != null) {
                List<ExecutionTransitionLogEntry> transitions = runtimeSupport.controlPlaneService()
                        .queryExecutionTransitions(query);
                if (query.page() > 0 || !transitions.isEmpty()) {
                    return transitions.stream()
                            .map(entry -> mapTransitionEntry(instance, entry))
                            .toList();
                }
            }
            Optional<SessionNodeProjection> projection = currentSessionNodeProjection(instance);
            if (projection.isPresent()) {
                return paginate(synthesizeSessionTransitions(instance, projection.get()), query.page(), query.size());
            }
            if (sessionManager == null && runtimeSupport.executionCheckpointStore() == null) {
                throw new GraphEngineServiceException(
                        GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                        "Session transition projection requires durable session access"
                );
            }
            return List.of();
        }
        if (runtimeSupport.controlPlaneService() == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "Control-plane service is not configured"
            );
        }
        return runtimeSupport.controlPlaneService().queryExecutionTransitions(query)
                .stream()
                .map(entry -> mapTransitionEntry(instance, entry))
                .toList();
    }

    @Override
    public List<GraphDeadLetter> queryDeadLetters(GraphDeadLetterQuery query) {
        Objects.requireNonNull(query, "query");
        if (runtimeSupport.controlPlaneService() == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "Control-plane service is not configured"
            );
        }
        Scope scope = resolveScope(query.tenantId(), query.namespace());
        return runtimeSupport.controlPlaneService().queryDeadLetters(DeadLetterQuery.builder()
                        .tenantId(scope.tenantId())
                        .namespace(scope.namespace())
                        .itemId(query.itemId())
                        .executionId(query.instanceId())
                        .itemType(query.itemType())
                        .shardId(query.shardId())
                        .failureClass(null)
                        .deadLetteredAfter(query.deadLetteredAfter())
                        .page(query.page())
                        .size(query.size())
                        .build()).stream()
                .filter(this::canViewDeadLetter)
                .map(this::mapDeadLetter)
                .toList();
    }

    @Override
    public void retryDeadLetter(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        if (runtimeSupport.workItemStore() == null || runtimeSupport.controlPlaneService() == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "Dead-letter replay requires work-item storage and control-plane support"
            );
        }
        Scope scope = resolveScope(null, null);
        List<DeadLetterEntry> matches = runtimeSupport.controlPlaneService().queryDeadLetters(DeadLetterQuery.builder()
                .tenantId(scope.tenantId())
                .namespace(scope.namespace())
                .itemId(itemId)
                .page(0)
                .size(1)
                .build());
        if (matches.isEmpty()) {
            throw notFound("Dead letter not found: " + itemId);
        }
        enforceDeadLetterAdmin(matches.getFirst());
        runtimeSupport.workItemStore().restoreDeadLetter(itemId);
        dispatchReadyWorkItems();
    }

    @Override
    public List<GraphNodeState> queryInstanceNodes(String instanceId) {
        return queryInstanceNodes(instanceId, null, 0, Integer.MAX_VALUE).items();
    }

    @Override
    public PagedResult<GraphNodeState> queryInstanceNodes(String instanceId, Set<GraphNodeStatus> statuses, int page, int size) {
        GraphInstance instance = getInstance(instanceId);
        List<GraphNodeState> nodeStates = switch (instance.executionMode()) {
            case GRAPH -> queryGraphInstanceNodes(instance);
            case SESSION -> querySessionInstanceNodes(instance);
            case STATE_MACHINE -> queryStateMachineInstanceNodes(instance);
        };
        return pageNodeStates(nodeStates, statuses, page, size);
    }

    @Override
    public GraphVersionDiagram getVersionDiagram(String versionId) {
        GraphVersion version = getVersion(versionId);
        GraphDefinition definition = requireDefinition(version.definitionId());
        VersionCompileResult compilation = versionCompiler.compile(definition, version);
        return new GraphVersionDiagram(
                version.versionId(),
                version.version(),
                visualLayoutGenerator.resolveLayout(definition, version, compilation)
        );
    }

    @Override
    public GraphInstanceDiagram getInstanceDiagram(String instanceId) {
        GraphInstance instance = getInstance(instanceId);
        GraphVersion version = requireVersion(instance.versionId());
        GraphDefinition definition = requireDefinition(version.definitionId());
        VersionCompileResult compilation = versionCompiler.compile(definition, version);
        return new GraphInstanceDiagram(
                instance.instanceId(),
                version.versionId(),
                visualLayoutGenerator.resolveLayout(definition, version, compilation),
                queryInstanceNodes(instanceId)
        );
    }

    private List<GraphNodeState> queryGraphInstanceNodes(GraphInstance instance) {
        GraphVersion version = requireVersion(instance.versionId());
        GraphDefinition definition = getDefinitionByKey(instance.definitionKey(), instance.tenantId(), instance.namespace());
        VersionCompileResult compilation = versionCompiler.compile(definition, version);
        if (!compilation.valid() || compilation.graph() == null) {
            return List.of();
        }

        Graph graph = compilation.graph();
        Map<String, NodeSpec> nodes = graph.nodes();

        // Load durable state for this execution
        Map<String, ExecutionCheckpoint> completedNodes = new java.util.LinkedHashMap<>();
        if (runtimeSupport.executionCheckpointStore() != null) {
            for (ExecutionCheckpoint cp : runtimeSupport.executionCheckpointStore().loadAll(instance.instanceId())) {
                if (cp.checkpointType() == CheckpointType.NODE_OUTPUT) {
                    completedNodes.put(cp.nodeId(), cp);
                }
            }
        }

        Map<String, ExecutionWait> activeWaits = new java.util.LinkedHashMap<>();
        if (runtimeSupport.waitStore() != null) {
            for (ExecutionWait wait : runtimeSupport.waitStore().findByExecution(instance.instanceId())) {
                if (wait.status() == WaitStatus.WAITING) {
                    activeWaits.put(wait.nodeId(), wait);
                }
            }
        }

        Map<String, WorkItem> workItemsByNode = new java.util.LinkedHashMap<>();
        if (runtimeSupport.workItemStore() != null) {
            int boundedWorkItemWindow = Math.max(1, graph.nodes().size() * 3);
            List<WorkItem> items = runtimeSupport.workItemStore().query(new WorkItemQuery(
                    instance.instanceId(), null, Set.of(), null, null, null, null, 0, boundedWorkItemWindow
            ));
            for (WorkItem item : items) {
                if (item.nodeId() == null) {
                    continue;
                }
                WorkItem existing = workItemsByNode.get(item.nodeId());
                if (existing == null || workItemPriority(item.status()) > workItemPriority(existing.status())) {
                    workItemsByNode.put(item.nodeId(), item);
                }
            }
        }

        // Assemble node states in topological order
        List<GraphNodeState> result = new ArrayList<>();
        for (String nodeId : graph.topologicalOrder()) {
            NodeSpec spec = nodes.get(nodeId);
            String operatorRef = spec != null ? spec.operatorRef() : null;

            if (completedNodes.containsKey(nodeId)) {
                ExecutionCheckpoint cp = completedNodes.get(nodeId);
                result.add(new GraphNodeState(nodeId, operatorRef, GraphNodeStatus.COMPLETED,
                        0, 0, null, null, cp.createdAt(), cp.updatedAt()));
                continue;
            }

            if (workItemsByNode.containsKey(nodeId)) {
                WorkItem item = workItemsByNode.get(nodeId);
                GraphNodeStatus status = switch (item.status()) {
                    case CLAIMED -> GraphNodeStatus.RUNNING;
                    case READY -> GraphNodeStatus.PENDING;
                    case RETRY_WAIT -> GraphNodeStatus.RETRYING;
                    case FAILED -> GraphNodeStatus.FAILED;
                    case DEAD_LETTER -> GraphNodeStatus.DEAD_LETTERED;
                    case CANCELLED -> GraphNodeStatus.CANCELLED;
                    case DONE -> GraphNodeStatus.COMPLETED;
                };
                result.add(new GraphNodeState(nodeId, operatorRef, status,
                        item.retryCount(), item.maxRetries(), item.lastError(),
                        null, item.createdAt(), item.completedAt()));
                continue;
            }

            if (activeWaits.containsKey(nodeId)) {
                ExecutionWait wait = activeWaits.get(nodeId);
                result.add(new GraphNodeState(nodeId, operatorRef, GraphNodeStatus.WAITING,
                        0, 0, null, wait.waitType().name(), wait.createdAt(), null));
                continue;
            }

            result.add(new GraphNodeState(nodeId, operatorRef, GraphNodeStatus.NOT_STARTED,
                    0, 0, null, null, null, null));
        }

        return List.copyOf(result);
    }

    private PagedResult<GraphNodeState> pageNodeStates(List<GraphNodeState> nodeStates,
                                                       Set<GraphNodeStatus> statuses,
                                                       int page,
                                                       int size) {
        Set<GraphNodeStatus> statusFilter = statuses == null || statuses.isEmpty() ? null : EnumSet.copyOf(statuses);
        List<GraphNodeState> filtered = statusFilter == null
                ? List.copyOf(nodeStates)
                : nodeStates.stream()
                .filter(nodeState -> statusFilter.contains(nodeState.status()))
                .toList();
        int normalizedPage = Math.max(0, page);
        int normalizedSize = size < 1 ? 50 : size;
        return new PagedResult<>(
                paginate(filtered, normalizedPage, normalizedSize),
                normalizedPage,
                normalizedSize,
                filtered.size()
        );
    }

    private List<GraphNodeState> querySessionInstanceNodes(GraphInstance instance) {
        GraphVersion version = requireVersion(instance.versionId());
        GraphDefinition definition = getDefinitionByKey(instance.definitionKey(), instance.tenantId(), instance.namespace());
        VersionCompileResult compilation = versionCompiler.compile(definition, version);
        if (!compilation.valid() || compilation.sessionGraph() == null) {
            return List.of();
        }

        Optional<SessionNodeProjection> projection = currentSessionNodeProjection(instance);
        if (projection.isEmpty()) {
            return List.of();
        }

        SessionGraph sessionGraph = compilation.sessionGraph();
        List<GraphNodeState> result = new ArrayList<>(sessionGraph.phases().size());
        for (var phase : sessionGraph.phases()) {
            GraphNodeStatus status = mapSessionPhaseStatus(phase.id(), projection.get());
            result.add(new GraphNodeState(
                    phase.id(),
                    null,
                    status,
                    0,
                    0,
                    null,
                    null,
                    firstPhaseTimestamp(projection.get().history(), phase.id()),
                    phaseCompletedAt(projection.get(), phase.id())
            ));
        }
        return List.copyOf(result);
    }

    private List<GraphNodeState> queryStateMachineInstanceNodes(GraphInstance instance) {
        GraphVersion version = requireVersion(instance.versionId());
        GraphDefinition definition = getDefinitionByKey(instance.definitionKey(), instance.tenantId(), instance.namespace());
        VersionCompileResult compilation = versionCompiler.compile(definition, version);
        if (!compilation.valid() || compilation.stateMachine() == null) {
            return List.of();
        }

        Optional<StateMachineNodeProjection> projection = loadStateMachineNodeProjection(instance);
        if (projection.isEmpty()) {
            return List.of();
        }

        StateMachineDef stateMachine = compilation.stateMachine();
        List<StateDef> orderedStates = orderedStateDefinitions(stateMachine);
        List<GraphNodeState> result = new ArrayList<>(orderedStates.size());
        for (StateDef state : orderedStates) {
            GraphNodeStatus status = mapStateMachineStateStatus(state.id(), projection.get());
            result.add(new GraphNodeState(
                    state.id(),
                    null,
                    status,
                    0,
                    0,
                    null,
                    null,
                    firstStateTimestamp(projection.get().history(), state.id()),
                    stateCompletedAt(projection.get(), state.id())
            ));
        }
        return List.copyOf(result);
    }

    @Override
    public List<GraphPendingSignal> queryPendingSignals(String instanceId) {
        GraphInstance instance = getInstance(instanceId);
        if (instance.executionMode() != GraphExecutionMode.GRAPH) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.UNSUPPORTED_EXECUTION_MODE,
                    "Pending signals are only supported for GRAPH instances: " + instance.executionMode()
            );
        }
        if (instance.status() != GraphInstanceStatus.SUSPENDED) {
            return List.of();
        }
        if (runtimeSupport.eventMatcherStore() == null || runtimeSupport.waitStore() == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "Pending signal projection requires both EventMatcherStore and WaitStore"
            );
        }

        Graph compiledGraph = compilePendingSignalGraph(instance).orElse(null);
        Map<String, ExecutionWait> waitsByNode = runtimeSupport.waitStore().findByExecution(instanceId).stream()
                .filter(wait -> wait.status() == WaitStatus.WAITING)
                .filter(wait -> wait.waitType() == WaitType.WAIT_SIGNAL || wait.waitType() == WaitType.WAIT_EVENT)
                .collect(java.util.stream.Collectors.toMap(
                        ExecutionWait::nodeId,
                        wait -> wait,
                        DefaultGraphEngineService::preferPendingSignalWait,
                        java.util.LinkedHashMap::new
                ));

        List<EventMatcher> matchers = runtimeSupport.eventMatcherStore().query(new EventMatcherQuery(
                null,
                null,
                null,
                Set.of(EventMatcherStatus.WAITING),
                instanceId,
                0,
                MAX_PENDING_SIGNAL_MATCHERS
        ));
        if (matchers.isEmpty()) {
            return List.of();
        }

        List<GraphPendingSignal> result = new ArrayList<>(matchers.size());
        for (EventMatcher matcher : matchers) {
            result.add(mapPendingSignal(matcher, compiledGraph, waitsByNode.get(matcher.nodeId())));
        }
        return List.copyOf(result);
    }

    @Override
    public RetryInstanceResult retryInstance(String instanceId, Set<String> nodeIds, long expectedRevision) {
        GraphInstance instance = getInstance(instanceId);
        enforceInstanceAdmin(instance);
        requireExpectedRevision(instance.revision(), expectedRevision, "Instance");

        if (runtimeSupport.workItemStore() == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "Instance retry requires work-item storage"
            );
        }

        // Find all dead-lettered work items for this instance
        List<WorkItem> deadLettered = runtimeSupport.workItemStore().query(new WorkItemQuery(
                instanceId, null, Set.of(WorkItemStatus.DEAD_LETTER), null, null, null, null, 0, Integer.MAX_VALUE
        ));

        // Optionally filter by node IDs
        if (nodeIds != null && !nodeIds.isEmpty()) {
            deadLettered = deadLettered.stream()
                    .filter(item -> item.nodeId() != null && nodeIds.contains(item.nodeId()))
                    .toList();
        }

        if (deadLettered.isEmpty()) {
            throw notFound("No dead-lettered work items found for instance '" + instanceId + "'");
        }

        // Restore each dead-lettered item
        int count = 0;
        for (WorkItem item : deadLettered) {
            runtimeSupport.workItemStore().restoreDeadLetter(item.itemId());
            count++;
        }

        dispatchReadyWorkItems();

        GraphInstance refreshed = refreshProjection(instance);
        return new RetryInstanceResult(refreshed, count);
    }

    private int workItemPriority(WorkItemStatus status) {
        return switch (status) {
            case DONE -> 7;
            case CLAIMED -> 6;
            case READY -> 5;
            case RETRY_WAIT -> 4;
            case FAILED -> 3;
            case DEAD_LETTER -> 2;
            case CANCELLED -> 1;
        };
    }

    @Override
    public GraphRemoteWorkerRegistration registerRemoteWorker(RegisterRemoteWorkerCommand command) {
        Objects.requireNonNull(command, "command");
        Scope scope = resolveScope(null, null);
        List<GraphRemoteWorkerAssignment> assignments = new ArrayList<>();
        for (GraphDeployment deployment : stores.graphDeploymentStore().query(new GraphDeploymentQuery(
                scope.tenantId(),
                scope.namespace(),
                null,
                null,
                true,
                0,
                Integer.MAX_VALUE
        ))) {
            for (Map.Entry<String, RemoteWorkerBinding> entry : deployment.operatorPlaneConfig().remoteWorkers().entrySet()) {
                RemoteWorkerBinding binding = entry.getValue();
                if (binding.workerId().equals(command.workerId()) || Objects.equals(binding.topic(), command.workerTopic())) {
                    assignments.add(new GraphRemoteWorkerAssignment(
                            deployment.deploymentId(),
                            deployment.definitionKey(),
                            deployment.environment(),
                            entry.getKey(),
                            binding
                    ));
                }
            }
        }
        return new GraphRemoteWorkerRegistration(
                command.workerId(),
                command.workerTopic(),
                scope.tenantId(),
                scope.namespace(),
                assignments
        );
    }

    @Override
    public List<GraphRemoteWorkerJob> pollRemoteWorkerJobs(PollRemoteWorkerJobsCommand command) {
        Objects.requireNonNull(command, "command");
        WorkItemStore workItemStore = requireWorkItemStore("Remote worker polling requires work-item storage");
        List<GraphRemoteWorkerJob> claimedJobs = new ArrayList<>();
        for (WorkItem workItem : workItemStore.pollReady(WorkItemType.EXECUTE_NODE, command.workerTopic(), command.limit())) {
            Optional<WorkItem> claimed = workItemStore.claim(
                    workItem.itemId(),
                    command.workerId(),
                    command.leaseDuration(),
                    workItem.version()
            );
            claimed.ifPresent(value -> claimedJobs.add(mapRemoteWorkerJob(requireRemoteWorkerWorkItem(value))));
            if (claimedJobs.size() >= command.limit()) {
                break;
            }
        }
        return List.copyOf(claimedJobs);
    }

    @Override
    public GraphRemoteWorkerJob heartbeatRemoteWorkerJob(HeartbeatRemoteWorkerJobCommand command) {
        Objects.requireNonNull(command, "command");
        requireRemoteWorkerWorkItem(command.itemId());
        return requireWorkItemStore("Remote worker heartbeats require work-item storage")
                .renewClaim(command.itemId(), command.leaseToken(), command.leaseDuration())
                .map(this::requireRemoteWorkerWorkItem)
                .map(this::mapRemoteWorkerJob)
                .orElseThrow(() -> conflict("Remote worker item lease is no longer active: " + command.itemId()));
    }

    @Override
    public void completeRemoteWorkerJob(CompleteRemoteWorkerJobCommand command) {
        Objects.requireNonNull(command, "command");
        WorkItem workItem = requireRemoteWorkerWorkItem(command.itemId());
        if (workItem.status() == WorkItemStatus.DONE) {
            refreshRemoteWorkerInstance(workItem.identity().executionId());
            return;
        }
        requireRemoteWorkerClaim(workItem, command.leaseToken(), command.expectedRevision(), "complete");
        requireDurableRuntime().signal(workItem.identity().executionId(), workItem.nodeId(), command.output());
        try {
            requireWorkItemStore("Remote worker completion requires work-item storage")
                    .markDone(command.itemId(), command.leaseToken(), command.expectedRevision());
        } catch (OptimisticLockException exception) {
            throw conflict("Remote worker item lease was lost while completing: " + command.itemId(), exception);
        }
        GraphInstance refreshed = awaitTerminalProjection(
                workItem.identity().executionId(),
                refreshRemoteWorkerInstance(workItem.identity().executionId()),
                EXECUTION_RESULT_GRACE
        );
        recordInstanceCompletedIfTerminal(refreshed);
    }

    @Override
    public void failRemoteWorkerJob(FailRemoteWorkerJobCommand command) {
        Objects.requireNonNull(command, "command");
        WorkItem workItem = requireRemoteWorkerWorkItem(command.itemId());
        if (workItem.status() == WorkItemStatus.RETRY_WAIT || workItem.status() == WorkItemStatus.DEAD_LETTER) {
            return;
        }
        requireRemoteWorkerClaim(workItem, command.leaseToken(), command.expectedRevision(), "fail");
        WorkItemStore workItemStore = requireWorkItemStore("Remote worker failure handling requires work-item storage");
        if (workItem.retryCount() + 1 >= workItem.maxRetries()) {
            workItemStore.markDeadLetter(command.itemId(), command.error());
            return;
        }
        Instant nextAttemptAt = runtimeSupport.timeSource().now().plus(remoteWorkerEnvelope(workItem)
                .retryPolicy()
                .nextDelay(workItem.retryCount()));
        try {
            workItemStore.markRetryWait(command.itemId(), command.leaseToken(), nextAttemptAt, command.expectedRevision());
        } catch (OptimisticLockException exception) {
            throw conflict("Remote worker item lease was lost while failing: " + command.itemId(), exception);
        }
    }

    @Override
    public GraphTask getTask(String taskId) {
        TaskInbox task = taskInboxStore().get(taskId)
                .orElseThrow(() -> notFound("Task not found: " + taskId));
        GraphTask mapped = mapTask(task);
        enforceTaskView(mapped);
        return mapped;
    }

    @Override
    public List<GraphTask> queryTasks(TaskInboxQuery query) {
        return taskInboxStore().query(Objects.requireNonNull(query, "query")).stream()
                .map(this::safeMapTask)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public GraphTask claimTask(ClaimTaskCommand command) {
        GraphTask task = getTask(command.taskId());
        enforceTaskAdmin(task);
        requireBaseTaskStore().claimTask(command.taskId(), command.userId());
        task = getTask(command.taskId());
        GraphInstance instance = getInstance(task.instanceId());
        metricsObserver.onTaskClaimed(task.definitionKey(), instance.tenantId(), instance.namespace(), task.nodeId());
        return task;
    }

    @Override
    public GraphTask completeTask(CompleteTaskCommand command) {
        GraphTask task = getTask(command.taskId());
        enforceTaskAdmin(task);
        GraphInstance instance = getInstance(task.instanceId());
        requireWorkItemTaskStore().completeTask(command.taskId(), command.output(), command.userId());
        dispatchReadyWorkItems();
        GraphInstance refreshed = awaitTerminalProjection(instance.instanceId(), instance, EXECUTION_RESULT_GRACE);
        metricsObserver.onTaskCompleted(task.definitionKey(), instance.tenantId(), instance.namespace(), task.nodeId());
        recordInstanceCompletedIfTerminal(refreshed);
        return getTask(command.taskId());
    }

    @Override
    public GraphTask reassignTask(ReassignTaskCommand command) {
        enforceTaskAdmin(getTask(command.taskId()));
        requireBaseTaskStore().reassignTask(command.taskId(), command.newAssignee());
        return getTask(command.taskId());
    }

    @Override
    public GraphTask cancelTask(CancelTaskCommand command) {
        enforceTaskAdmin(getTask(command.taskId()));
        requireBaseTaskStore().cancelTask(command.taskId(), command.reason());
        return getTask(command.taskId());
    }

    @Override
    public List<OperatorInventoryEntry> queryOperatorInventory(OperatorInventoryQuery query) {
        Objects.requireNonNull(query, "query");
        OperatorRegistry registry = runtimeSupport.operatorRegistry();
        if (registry == null) {
            return List.of();
        }

        Scope scope = resolveScope(query.tenantId(), query.namespace());
        Map<String, List<OperatorUsageReference>> usageByOperator =
                buildOperatorUsageMap(scope.tenantId(), scope.namespace());

        List<OperatorInventoryEntry> entries = new ArrayList<>();
        for (String name : registry.discover(query.pattern())) {
            OperatorMetadata metadata = registry.metadata(name);
            Object operator = registry.lookup(name);
            OperatorAnnotationDetails details = OperatorAnnotationIntrospector.introspect(operator);

            List<OperatorUsageReference> refs = usageByOperator.getOrDefault(name, List.of());
            long distinctDefinitions = refs.stream()
                    .map(OperatorUsageReference::definitionId)
                    .distinct()
                    .count();
            OperatorUsageSummary usage = new OperatorUsageSummary(
                    (int) distinctDefinitions,
                    refs.size(),
                    refs
            );

            entries.add(new OperatorInventoryEntry(
                    name,
                    details.description(),
                    details.owner(),
                    details.tags(),
                    metadata.inputClass() != null ? metadata.inputClass().getName() : "",
                    metadata.outputClass() != null ? metadata.outputClass().getName() : "",
                    SchemaDescriptorJsonCodec.serialize(metadata.inputSchema()),
                    SchemaDescriptorJsonCodec.serialize(metadata.outputSchema()),
                    details.usageExample(),
                    details.constraintsDescription(),
                    usage
            ));
        }
        return List.copyOf(entries);
    }

    @Override
    public void close() {
        versionCompiler.invalidateAll();
        if (sessionManager != null) {
            sessionManager.shutdown();
        }
    }

    private StartInstanceResult startGraphInstance(GraphDefinition definition,
                                                   GraphVersion version,
                                                   VersionCompileResult compilation,
                                                   StartInstanceCommand command) {
        DurableGraphEngine runtime = requireDurableRuntime();
        ValidationResult validation = runtime.validate(compilation.graph());
        if (!validation.isValid()) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.VALIDATION_FAILED,
                    "Runtime validation failed: " + validation.issues()
            );
        }

        GraphContext context = createExecutionContext(definition, version, compilation, command.variables());
        StreamingGraphHandle handle = runtime.executeStreaming(compilation.graph(), context);
        awaitExecutionStart(handle.executionId(), handle.completion(), "start");
        Optional<GraphResult> completedResult = awaitGraphResult(handle.completion(), "start", EXECUTION_RESULT_GRACE);
        ExecutionInstance execution = resolveExecution(handle.executionId());
        GraphInstance projection = new GraphInstance(
                handle.executionId(),
                definition.definitionKey(),
                version.versionId(),
                definition.tenantId(),
                definition.namespace(),
                command.businessKey(),
                GraphExecutionMode.GRAPH,
                GraphInstanceStatus.fromExecutionStatus(execution.status()),
                command.initiator(),
                command.variables(),
                0,
                runtimeSupport.timeSource().now(),
                runtimeSupport.timeSource().now(),
                null
        );
        stores.graphInstanceStore().create(projection);
        GraphInstance refreshed = refreshProjection(projection);
        return new StartInstanceResult(refreshed, completedResult
                .map(GraphResult::suspendedNodes)
                .orElse(Map.of()));
    }

    private StartInstanceResult startSessionInstance(GraphDefinition definition,
                                                     GraphVersion version,
                                                     VersionCompileResult compilation,
                                                     StartInstanceCommand command) {
        var handle = sessionManager(compilation).start(
                requireSessionGraph(compilation).name(),
                command.variables(),
                command.businessKey(),
                new SessionIdentity(definition.namespace(), command.initiator())
        );
        GraphInstance projection = new GraphInstance(
                handle.sessionId(),
                definition.definitionKey(),
                version.versionId(),
                definition.tenantId(),
                definition.namespace(),
                command.businessKey(),
                GraphExecutionMode.SESSION,
                GraphInstanceStatus.RUNNING,
                command.initiator(),
                command.variables(),
                0,
                runtimeSupport.timeSource().now(),
                runtimeSupport.timeSource().now(),
                null
        );
        stores.graphInstanceStore().create(projection);
        GraphInstance refreshed = awaitTerminalProjection(handle.sessionId(), refreshProjection(projection), EXECUTION_RESULT_GRACE);
        return new StartInstanceResult(refreshed, Map.of());
    }

    private StartInstanceResult startStateMachineInstance(GraphDefinition definition,
                                                          GraphVersion version,
                                                          VersionCompileResult compilation,
                                                          StartInstanceCommand command) {
        DurableStateMachineManager manager = stateMachineManager(compilation);
        StateMachineResult result = manager.start(
                compilation.stateMachine().name(),
                command.variables(),
                command.businessKey()
        );
        GraphInstance projection = new GraphInstance(
                result.instance().instanceId(),
                definition.definitionKey(),
                version.versionId(),
                definition.tenantId(),
                definition.namespace(),
                command.businessKey(),
                GraphExecutionMode.STATE_MACHINE,
                GraphInstanceStatus.RUNNING,
                command.initiator(),
                command.variables(),
                0,
                runtimeSupport.timeSource().now(),
                runtimeSupport.timeSource().now(),
                null
        );
        stores.graphInstanceStore().create(projection);
        return new StartInstanceResult(refreshProjection(projection), Map.of());
    }

    private SignalInstanceResult signalGraphInstance(GraphInstance instance,
                                                     VersionCompileResult compilation,
                                                     SignalInstanceCommand command) {
        if (command.nodeId() == null || command.nodeId().isBlank()) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.VALIDATION_FAILED,
                    "Graph-mode signaling requires nodeId"
            );
        }
        GraphResult result = requireDurableRuntime().signal(compilation.graph(), instance.instanceId(), command.nodeId(), command.payload());
        GraphInstance refreshed = refreshProjection(instance);
        return new SignalInstanceResult(refreshed, result == null ? Map.of() : result.suspendedNodes());
    }

    private SignalInstanceResult signalSessionInstance(GraphInstance instance,
                                                       VersionCompileResult compilation,
                                                       SignalInstanceCommand command) {
        sessionManager(compilation).signal(instance.instanceId(), command.payload(), command.callerId());
        return new SignalInstanceResult(instance, Map.of());
    }

    private SignalInstanceResult signalStateMachineInstance(GraphInstance instance,
                                                            VersionCompileResult compilation,
                                                            SignalInstanceCommand command) {
        String eventName = command.eventName() == null || command.eventName().isBlank()
                ? "signal"
                : command.eventName();
        Map<String, Object> payload = command.payload() instanceof Map<?, ?> map
                ? castPayloadMap(map)
                : Map.of("payload", command.payload());
        stateMachineManager(compilation).signal(instance.instanceId(), eventName, payload);
        return new SignalInstanceResult(refreshProjection(instance), Map.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castPayloadMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private GraphContext createExecutionContext(GraphDefinition definition,
                                                GraphVersion version,
                                                VersionCompileResult compilation,
                                                Map<String, Object> variables) {
        GraphContext context = new GraphContext(new TenantContext(definition.tenantId(), definition.namespace()));
        context.putAll(variables);
        context.put("graphEngine.definitionId", definition.definitionId());
        context.put("graphEngine.versionId", version.versionId());
        context.put("graphEngine.executionMode", compilation.executionMode().name());
        return context;
    }

    private ExecutionInstance awaitExecutionStart(String executionId,
                                                  CompletableFuture<GraphResult> completion,
                                                  String operation) {
        long deadline = System.nanoTime() + EXECUTION_START_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<ExecutionInstance> execution = runtimeSupport.executionStore() == null
                    ? Optional.empty()
                    : runtimeSupport.executionStore().get(executionId);
            if (execution.isPresent()) {
                return execution.get();
            }
            completedGraphResult(completion, operation);
            sleepExecutionStartPoll();
        }
        return resolveExecution(executionId);
    }

    private Optional<GraphResult> completedGraphResult(CompletableFuture<GraphResult> completion, String operation) {
        if (!completion.isDone()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(completion.join());
        } catch (CompletionException completionException) {
            throw propagateGraphOperationFailure(operation, completionException.getCause());
        }
    }

    private Optional<GraphResult> awaitGraphResult(CompletableFuture<GraphResult> completion,
                                                   String operation,
                                                   Duration timeout) {
        try {
            return Optional.ofNullable(completion.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException timeoutException) {
            return completedGraphResult(completion, operation);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "Interrupted while waiting for graph " + operation,
                    interruptedException
            );
        } catch (ExecutionException executionException) {
            throw propagateGraphOperationFailure(operation, executionException.getCause());
        }
    }

    private RuntimeException propagateGraphOperationFailure(String operation, Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new GraphEngineServiceException(
                GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                "Graph " + operation + " failed: " + cause.getMessage(),
                cause
        );
    }

    private void sleepExecutionStartPoll() {
        try {
            Thread.sleep(EXECUTION_START_POLL_INTERVAL);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "Interrupted while waiting for durable execution start",
                    interruptedException
            );
        }
    }

    private void sleepExecutionResultPoll() {
        try {
            Thread.sleep(EXECUTION_START_POLL_INTERVAL);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "Interrupted while waiting for durable execution result",
                    interruptedException
            );
        }
    }

    private ExecutionInstance resolveExecution(String executionId) {
        if (runtimeSupport.executionStore() == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "Execution store is not configured"
            );
        }
        return runtimeSupport.executionStore().get(executionId)
                .orElseThrow(() -> notFound("Execution not found: " + executionId));
    }

    private GraphVersion resolvePublishedVersion(GraphDefinition definition, String requestedVersion, String environment) {
        if (requestedVersion != null && !requestedVersion.isBlank()) {
            GraphVersion version = stores.graphVersionStore()
                    .getByDefinitionAndVersion(definition.definitionId(), requestedVersion)
                    .orElseThrow(() -> notFound("Version not found: " + requestedVersion));
            requirePublished(version);
            return version;
        }
        if (environment != null && !environment.isBlank()) {
            Optional<GraphDeployment> deployment = stores.graphDeploymentStore().findActive(
                    definition.tenantId(),
                    definition.namespace(),
                    definition.definitionKey(),
                    environment
            );
            if (deployment.isPresent()) {
                return resolveByRoutingPolicy(definition.definitionId(), deployment.get().routingPolicy());
            }
        }
        return stores.graphVersionStore().findLatestPublished(definition.definitionId())
                .orElseThrow(() -> notFound("No published version exists for definition '" + definition.definitionKey() + "'"));
    }

    private GraphVersion resolveByRoutingPolicy(String definitionId, VersionRoutingPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        if (policy instanceof VersionRoutingPolicy.Latest) {
            return stores.graphVersionStore().findLatestPublished(definitionId)
                    .orElseThrow(() -> notFound("No published version exists"));
        }
        if (policy instanceof VersionRoutingPolicy.Pinned pinned) {
            GraphVersion version = stores.graphVersionStore().getByDefinitionAndVersion(definitionId, pinned.version())
                    .orElseThrow(() -> notFound("Pinned version not found: " + pinned.version()));
            requirePublished(version);
            return version;
        }
        if (policy instanceof VersionRoutingPolicy.Canary canary) {
            String selectedVersion = java.util.concurrent.ThreadLocalRandom.current().nextInt(100) < canary.percentage()
                    ? canary.canaryVersion()
                    : canary.primaryVersion();
            GraphVersion version = stores.graphVersionStore().getByDefinitionAndVersion(definitionId, selectedVersion)
                    .orElseThrow(() -> notFound("Canary version not found: " + selectedVersion));
            requirePublished(version);
            return version;
        }
        throw new GraphEngineServiceException(
                GraphEngineServiceErrorCode.VALIDATION_FAILED,
                "Unsupported routing policy: " + policy
        );
    }

    private void validateRoutingPolicy(String definitionId, VersionRoutingPolicy policy) {
        if (policy instanceof VersionRoutingPolicy.Pinned pinned) {
            requirePublished(stores.graphVersionStore().getByDefinitionAndVersion(definitionId, pinned.version())
                    .orElseThrow(() -> notFound("Pinned version not found: " + pinned.version())));
        }
        if (policy instanceof VersionRoutingPolicy.Canary canary) {
            requirePublished(stores.graphVersionStore().getByDefinitionAndVersion(definitionId, canary.primaryVersion())
                    .orElseThrow(() -> notFound("Canary primary version not found: " + canary.primaryVersion())));
            requirePublished(stores.graphVersionStore().getByDefinitionAndVersion(definitionId, canary.canaryVersion())
                    .orElseThrow(() -> notFound("Canary version not found: " + canary.canaryVersion())));
        }
    }

    private void requirePublished(GraphVersion version) {
        if (version.status() != GraphVersionStatus.PUBLISHED) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.INVALID_STATE,
                    "Version '" + version.version() + "' is not published"
            );
        }
    }

    private MetadataDiff computeMetadataDiff(GraphVersionMetadata leftMeta, GraphVersionMetadata rightMeta) {
        boolean executionModeChanged = leftMeta.executionMode() != rightMeta.executionMode();

        var addedOps = new ArrayList<>(rightMeta.operatorRefs());
        addedOps.removeAll(leftMeta.operatorRefs());
        var removedOps = new ArrayList<>(leftMeta.operatorRefs());
        removedOps.removeAll(rightMeta.operatorRefs());

        var changedFingerprints = new ArrayList<String>();
        for (var entry : rightMeta.operatorFingerprints().entrySet()) {
            String leftFp = leftMeta.operatorFingerprints().get(entry.getKey());
            if (leftFp != null && !leftFp.equals(entry.getValue())) {
                changedFingerprints.add(entry.getKey());
            }
        }

        boolean inputSchemaChanged = !Objects.equals(
                leftMeta.inputSchema() == null ? null : leftMeta.inputSchema().toMap(),
                rightMeta.inputSchema() == null ? null : rightMeta.inputSchema().toMap());
        boolean outputSchemaChanged = !Objects.equals(
                leftMeta.outputSchema() == null ? null : leftMeta.outputSchema().toMap(),
                rightMeta.outputSchema() == null ? null : rightMeta.outputSchema().toMap());
        SchemaEvolutionChecker schemaEvolutionChecker = new SchemaEvolutionChecker();
        SchemaCompatibility inputCompatibility = schemaEvolutionChecker.check(leftMeta.inputSchema(), rightMeta.inputSchema());
        SchemaCompatibility outputCompatibility = schemaEvolutionChecker.check(leftMeta.outputSchema(), rightMeta.outputSchema());

        var addedTasks = new ArrayList<>(rightMeta.taskDefinitions().keySet());
        addedTasks.removeAll(leftMeta.taskDefinitions().keySet());
        var removedTasks = new ArrayList<>(leftMeta.taskDefinitions().keySet());
        removedTasks.removeAll(rightMeta.taskDefinitions().keySet());

        var summary = new ArrayList<String>();
        if (executionModeChanged) {
            summary.add("Execution mode changed from " + leftMeta.executionMode() + " to " + rightMeta.executionMode());
        }
        if (!addedOps.isEmpty()) {
            summary.add("Added operators: " + String.join(", ", addedOps));
        }
        if (!removedOps.isEmpty()) {
            summary.add("Removed operators: " + String.join(", ", removedOps));
        }
        if (!changedFingerprints.isEmpty()) {
            summary.add("Operator fingerprints changed for nodes: " + String.join(", ", changedFingerprints));
        }
        if (inputSchemaChanged) {
            summary.add("Input schema changed");
        }
        if (outputSchemaChanged) {
            summary.add("Output schema changed");
        }
        appendCompatibilitySummary(summary, "Input schema", inputSchemaChanged, inputCompatibility);
        appendCompatibilitySummary(summary, "Output schema", outputSchemaChanged, outputCompatibility);
        if (!addedTasks.isEmpty()) {
            summary.add("Added task definitions: " + String.join(", ", addedTasks));
        }
        if (!removedTasks.isEmpty()) {
            summary.add("Removed task definitions: " + String.join(", ", removedTasks));
        }
        if (summary.isEmpty()) {
            summary.add("No metadata changes detected");
        }

        return new MetadataDiff(
                executionModeChanged,
                leftMeta.executionMode(),
                rightMeta.executionMode(),
                List.copyOf(addedOps),
                List.copyOf(removedOps),
                List.copyOf(changedFingerprints),
                inputSchemaChanged,
                outputSchemaChanged,
                inputCompatibility,
                outputCompatibility,
                List.copyOf(addedTasks),
                List.copyOf(removedTasks),
                List.copyOf(summary)
        );
    }

    private static void appendCompatibilitySummary(List<String> summary,
                                                   String label,
                                                   boolean changed,
                                                   SchemaCompatibility compatibility) {
        if (!changed && compatibility instanceof SchemaCompatibility.FullyCompatible) {
            return;
        }
        summary.add(label + " compatibility: " + compatibility.summary());
        if (compatibility instanceof SchemaCompatibility.BackwardCompatible backwardCompatible) {
            backwardCompatible.warnings().forEach(warning -> summary.add(label + ": " + warning));
        } else if (compatibility instanceof SchemaCompatibility.BreakingChange breakingChange) {
            breakingChange.violations().forEach(violation -> summary.add(label + ": " + violation));
        }
    }

    private SchemaCompatibility compatibility(GraphDefinition definition,
                                              GraphVersion version,
                                              VersionCompileResult compilation) {
        if (compilation.executionMode() != GraphExecutionMode.GRAPH) {
            return new SchemaCompatibility.BackwardCompatible(
                    "Compatibility check skipped for " + compilation.executionMode().name().toLowerCase() + " versions",
                    List.of("Only graph-mode versions currently support schema evolution comparison")
            );
        }
        if (runtimeSupport.graphRegistryStore() == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "Graph registry store is not configured"
            );
        }
        com.leanowtech.bloge.core.runtime.registry.GraphDefinition candidate = toRegistryDefinition(version, compilation);
        try {
            return runtimeSupport.graphRegistryStore().checkEvolution(
                    compilation.runtimeName(),
                    GraphEngineDslCodecs.graphDefinitionCodec(runtimeSupport.jsonCodec()),
                    runtimeSupport.operatorRegistry() == null
                            ? new com.leanowtech.bloge.core.spi.DefaultOperatorRegistry()
                            : runtimeSupport.operatorRegistry(),
                    candidate
            );
        } catch (RuntimeException exception) {
            return new SchemaCompatibility.BackwardCompatible(
                    "Schema evolution check skipped",
                    List.of(exception.getMessage())
            );
        }
    }

    private void publishGraphArtifact(GraphVersion version, VersionCompileResult compilation) {
        if (runtimeSupport.graphRegistryStore() == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "Graph registry store is not configured"
            );
        }
        runtimeSupport.graphRegistryStore().publish(toRegistryDefinition(version, compilation));
    }

    private com.leanowtech.bloge.core.runtime.registry.GraphDefinition toRegistryDefinition(GraphVersion version,
                                                                                            VersionCompileResult compilation) {
        var codec = GraphEngineDslCodecs.graphDefinitionCodec(runtimeSupport.jsonCodec());
        var encoded = codec.encode(compilation.graph()).orElseThrow(() -> new GraphEngineServiceException(
                GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                "Compiled graph could not be encoded for registry publication"
        ));
        var now = runtimeSupport.timeSource().now();
        return com.leanowtech.bloge.core.runtime.registry.GraphDefinition.builder(
                        compilation.runtimeName(),
                        version.version(),
                        GraphEngineServiceHashes.hashDefinition(encoded.definitionJson()),
                        encoded.definitionJson())
                .status(GraphStatus.PUBLISHED)
                .createdAt(now)
                .updatedAt(now)
                .publishedAt(now)
                .migrationPolicy(version.migrationPolicy())
                .build();
    }

    private DurableSessionManager ensureDurableSessionManager() {
        DurableSessionManager current = sessionManager;
        if (current != null) {
            return current;
        }
        DurableGraphEngine runtime = requireDurableRuntime();
        if (runtimeSupport.executionStore() == null || runtimeSupport.executionCheckpointStore() == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "Session durable stores are not configured"
            );
        }
        synchronized (this) {
            if (sessionManager == null) {
                sessionManager = DurableSessionManager.builder()
                        .executionStore(runtimeSupport.executionStore())
                        .checkpointStore(runtimeSupport.executionCheckpointStore())
                        .graphEngine(runtime.asGraphEngine())
                        .accessGuard((state, callerId, operation) -> {
                            if (Objects.equals(GOVERNANCE_SOURCE, callerId)) {
                                return;
                            }
                            new OwnerOnlySessionAccessGuard().checkAccess(state, callerId, operation);
                        })
                        .definitionLookup(sessionDefinitions::get)
                        .timeSource(runtimeSupport.timeSource())
                        .ownerId(sessionOwnerId)
                        .build();
            }
            return sessionManager;
        }
    }

    private SessionGraph requireSessionGraph(VersionCompileResult compilation) {
        if (compilation.sessionGraph() == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "Session artifact is not available"
            );
        }
        return compilation.sessionGraph();
    }

    private void registerSessionGraph(VersionCompileResult compilation) {
        SessionGraph sessionGraph = requireSessionGraph(compilation);
        sessionDefinitions.put(sessionGraph.name(), sessionGraph);
    }

    private DurableSessionManager sessionManager(VersionCompileResult compilation) {
        registerSessionGraph(compilation);
        return ensureDurableSessionManager();
    }

    private VersionCompileResult sessionCompilation(GraphInstance instance, String action) {
        GraphVersion version = requireVersion(instance.versionId());
        GraphDefinition definition = getDefinitionByKey(instance.definitionKey(), instance.tenantId(), instance.namespace());
        VersionCompileResult compilation = versionCompiler.compile(definition, version);
        if (!compilation.valid() || compilation.sessionGraph() == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.VALIDATION_FAILED,
                    "Version '" + version.version() + "' can no longer be compiled for " + action
            );
        }
        return compilation;
    }

    private DurableStateMachineManager stateMachineManager(VersionCompileResult compilation) {
        if (compilation.stateMachine() == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "State-machine artifact is not available"
            );
        }
        if (runtimeSupport.executionStore() == null || runtimeSupport.executionCheckpointStore() == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "State-machine durable stores are not configured"
            );
        }
        return DurableStateMachineManager.builder()
                .executionStore(runtimeSupport.executionStore())
                .stateMachineStore(new ExecutionCheckpointStateMachineStore(
                        runtimeSupport.executionCheckpointStore(),
                        runtimeSupport.jsonCodec()
                ))
                .graphEngine(requireDurableRuntime().asGraphEngine())
                .definitionLookup(name -> Objects.equals(name, compilation.stateMachine().name()) ? compilation.stateMachine() : null)
                .timeSource(runtimeSupport.timeSource())
                .ownerId(stateMachineOwnerId)
                .build();
    }

    private TaskInboxStore taskInboxStore() {
        if (runtimeSupport.taskInboxStore() == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "Task inbox store is not configured"
            );
        }
        return runtimeSupport.taskInboxStore();
    }

    private TaskStore requireBaseTaskStore() {
        if (runtimeSupport.taskStore() != null) {
            return runtimeSupport.taskStore();
        }
        if (runtimeSupport.taskInboxStore() != null) {
            return new TaskInboxTaskStore(runtimeSupport.taskInboxStore(), runtimeSupport.executionStore());
        }
        throw new GraphEngineServiceException(
                GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                "Task store is not configured"
        );
    }

    private TaskStore requireWorkItemTaskStore() {
        if (runtimeSupport.workItemStore() == null
                || runtimeSupport.executionStore() == null
                || runtimeSupport.checkpointCodec() == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "Task completion requires work-item dispatch support"
            );
        }
        return new WorkItemTaskStore(
                requireBaseTaskStore(),
                runtimeSupport.executionStore(),
                runtimeSupport.workItemStore(),
                runtimeSupport.checkpointCodec(),
                runtimeSupport.workItemNotifier(),
                runtimeSupport.timeSource()
        );
    }

    private void dispatchReadyWorkItems() {
        if (durableGraphEngine == null || runtimeSupport.workItemStore() == null) {
            return;
        }
        durableGraphEngine.dispatchReadyWorkItems(GOVERNANCE_SOURCE, null, 10);
    }

    private GraphRemoteWorkerJob mapRemoteWorkerJob(WorkItem workItem) {
        RemoteWorkerEnvelope envelope = remoteWorkerEnvelope(workItem);
        return new GraphRemoteWorkerJob(
                workItem.itemId(),
                workItem.claimOwner(),
                workItem.claimToken(),
                workItem.claimUntil(),
                workItem.status(),
                workItem.priority(),
                workItem.retryCount(),
                workItem.maxRetries(),
                workItem.version(),
                workItem.lastError(),
                envelope,
                workItem.createdAt(),
                workItem.updatedAt()
        );
    }

    private GraphTask safeMapTask(TaskInbox taskInbox) {
        try {
            GraphTask task = mapTask(taskInbox);
            enforceTaskView(task);
            return task;
        } catch (GraphEngineServiceException exception) {
            if (exception.errorCode() == GraphEngineServiceErrorCode.NOT_FOUND
                    || exception.errorCode() == GraphEngineServiceErrorCode.ACCESS_DENIED) {
                return null;
            }
            throw exception;
        }
    }

    private GraphTask mapTask(TaskInbox taskInbox) {
        GraphInstance instance = stores.graphInstanceStore().get(taskInbox.identity().executionId())
                .orElseThrow(() -> notFound("Task instance not found: " + taskInbox.identity().executionId()));
        GraphVersion version = requireVersion(instance.versionId());
        TaskDefinition definition = version.metadata().taskDefinitions().get(taskInbox.nodeId());
        return new GraphTask(
                taskInbox.taskId(),
                instance.instanceId(),
                instance.definitionKey(),
                taskInbox.nodeId(),
                taskInbox.taskType(),
                taskInbox.title(),
                taskInbox.assignee(),
                taskInbox.candidateUsers(),
                taskInbox.candidateGroups(),
                taskInbox.candidateRoles(),
                definition == null ? null : definition.formRef(),
                definition == null ? null : definition.payloadSchema(),
                taskInbox.formData(),
                taskInbox.priority(),
                taskInbox.dueDate(),
                null,
                GraphTaskStatus.fromTaskInboxStatus(taskInbox.status()),
                taskInbox.version(),
                taskInbox.createdAt(),
                taskInbox.updatedAt(),
                taskInbox.completedAt()
        );
    }

    private GraphPendingSignal mapPendingSignal(EventMatcher matcher,
                                                Graph compiledGraph,
                                                ExecutionWait wait) {
        com.leanowtech.bloge.core.schema.SchemaDescriptor signalSchema = null;
        if (compiledGraph != null) {
            NodeSpec node = compiledGraph.nodes().get(matcher.nodeId());
            if (node == null) {
                logger.warning(() -> "Pending signal node not found in compiled graph: "
                        + matcher.nodeId()
                        + " for instance "
                        + matcher.identity().executionId());
            } else if (node.metadata().signalSchema() != null) {
                signalSchema = node.metadata().signalSchema();
            }
        }
        return new GraphPendingSignal(
                matcher.nodeId(),
                matcher.eventName(),
                matcher.correlationKey(),
                matcher.expectedValue(),
                matcher.optional(),
                signalSchema,
                matcher.createdAt(),
                wait == null ? null : wait.timeoutAt()
        );
    }

    private Map<String, Map<String, Object>> loadGraphNodeOutputs(String instanceId) {
        if (runtimeSupport.executionCheckpointStore() == null || runtimeSupport.checkpointCodec() == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "Graph context projection requires execution checkpoints and a checkpoint codec"
            );
        }
        Map<String, Map<String, Object>> nodeOutputs = new java.util.LinkedHashMap<>();
        for (ExecutionCheckpoint checkpoint : runtimeSupport.executionCheckpointStore()
                .loadByType(instanceId, CheckpointType.NODE_OUTPUT)) {
            decodeCheckpointMap(checkpoint).ifPresent(decoded -> nodeOutputs.put(checkpoint.nodeId(), decoded));
        }
        return Map.copyOf(nodeOutputs);
    }

    private Optional<Map<String, Object>> decodeCheckpointMap(ExecutionCheckpoint checkpoint) {
        if (checkpoint.payload() == null) {
            logger.warning(() -> "Skipping checkpoint without inline payload for context projection: "
                    + checkpoint.identity().executionId() + "/" + checkpoint.nodeId());
            return Optional.empty();
        }
        try {
            Object decoded = runtimeSupport.checkpointCodec().deserialize(checkpoint.payload());
            if (!(decoded instanceof Map<?, ?> map)) {
                return Optional.empty();
            }
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null) {
                    result.put(String.valueOf(key), value);
                }
            });
            return Optional.of(Map.copyOf(result));
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING,
                    "Failed to decode checkpoint payload while projecting instance context for "
                            + checkpoint.identity().executionId() + "/" + checkpoint.nodeId(),
                    exception);
            return Optional.empty();
        }
    }

    private InstanceContextProjection loadSessionContext(GraphInstance instance) {
        Optional<InstanceContextProjection> projection = currentSessionContextProjection(instance);
        if (projection.isPresent()) {
            return projection.get();
        }
        if (sessionManager == null && runtimeSupport.executionCheckpointStore() == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "Session context projection requires durable session access"
            );
        }
        return InstanceContextProjection.empty();
    }

    private Optional<InstanceContextProjection> currentSessionContextProjection(GraphInstance instance) {
        DurableSessionManager manager = sessionManager;
        if (manager != null) {
            Optional<SessionStateSnapshot> activeSnapshot = manager.query(instance.instanceId(), instance.initiator());
            if (activeSnapshot.isPresent()) {
                return activeSnapshot.map(this::toSessionContextProjection);
            }
            Optional<SessionCheckpoint> durableCheckpoint = manager.getCheckpoint(instance.instanceId());
            if (durableCheckpoint.isPresent()) {
                return durableCheckpoint.map(this::toSessionContextProjection);
            }
        }
        if (runtimeSupport.executionCheckpointStore() == null) {
            return Optional.empty();
        }
        return new ExecutionCheckpointSessionStore(runtimeSupport.executionCheckpointStore())
                .load(instance.instanceId())
                .map(this::toSessionContextProjection);
    }

    /**
     * Loads the freshest session-node projection visible to this service instance.
     *
     * <p>When the {@link DurableSessionManager} still holds an active in-memory snapshot for the
     * session, that snapshot is always at least as recent as its persisted checkpoint under normal
     * operation. The hot path therefore returns immediately and avoids a redundant checkpoint read.</p>
     */
    private Optional<SessionNodeProjection> currentSessionNodeProjection(GraphInstance instance) {
        DurableSessionManager manager = sessionManager;
        if (manager != null) {
            Optional<SessionStateSnapshot> activeSnapshot = manager.query(instance.instanceId(), instance.initiator());
            if (activeSnapshot.isPresent()) {
                return activeSnapshot.map(this::toSessionNodeProjection);
            }
            Optional<SessionCheckpoint> durableCheckpoint = manager.getCheckpoint(instance.instanceId());
            if (durableCheckpoint.isPresent()) {
                return durableCheckpoint.map(this::toSessionNodeProjection);
            }
        }
        if (runtimeSupport.executionCheckpointStore() == null) {
            return Optional.empty();
        }
        return new ExecutionCheckpointSessionStore(runtimeSupport.executionCheckpointStore())
                .load(instance.instanceId())
                .map(this::toSessionNodeProjection);
    }

    private InstanceContextProjection toSessionContextProjection(SessionStateSnapshot snapshot) {
        return new InstanceContextProjection(
                Map.of(),
                snapshot.sharedState(),
                snapshot.phaseOutputs(),
                Map.of()
        );
    }

    private InstanceContextProjection toSessionContextProjection(SessionCheckpoint checkpoint) {
        return new InstanceContextProjection(
                Map.of(),
                checkpoint.sharedState(),
                checkpoint.phaseOutputs(),
                Map.of()
        );
    }

    private SessionNodeProjection toSessionNodeProjection(SessionStateSnapshot snapshot) {
        return new SessionNodeProjection(
                snapshot.currentPhaseId(),
                snapshot.status(),
                snapshot.phaseVisitCount(),
                snapshot.history(),
                snapshot.lastTouchAt()
        );
    }

    private SessionNodeProjection toSessionNodeProjection(SessionCheckpoint checkpoint) {
        return new SessionNodeProjection(
                checkpoint.currentPhaseId(),
                checkpoint.status(),
                checkpoint.phaseVisitCount(),
                checkpoint.history(),
                checkpoint.checkpointedAt()
        );
    }

    private InstanceContextProjection loadStateMachineContext(GraphInstance instance) {
        if (runtimeSupport.executionCheckpointStore() == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "State-machine context projection requires durable checkpoint access"
            );
        }
        return new ExecutionCheckpointStateMachineStore(
                runtimeSupport.executionCheckpointStore(),
                runtimeSupport.jsonCodec()
        ).load(instance.instanceId())
                .map(this::toStateMachineContextProjection)
                .orElseGet(InstanceContextProjection::empty);
    }

    private InstanceContextProjection toStateMachineContextProjection(StateMachineCheckpoint checkpoint) {
        return new InstanceContextProjection(
                Map.of(),
                checkpoint.sharedContext(),
                Map.of(),
                checkpoint.stateOutputs()
        );
    }

    private Optional<StateMachineNodeProjection> loadStateMachineNodeProjection(GraphInstance instance) {
        if (runtimeSupport.executionCheckpointStore() == null) {
            return Optional.empty();
        }
        return new ExecutionCheckpointStateMachineStore(
                runtimeSupport.executionCheckpointStore(),
                runtimeSupport.jsonCodec()
        ).load(instance.instanceId()).map(this::toStateMachineNodeProjection);
    }

    private StateMachineNodeProjection toStateMachineNodeProjection(StateMachineCheckpoint checkpoint) {
        return new StateMachineNodeProjection(
                checkpoint.currentStateId(),
                checkpoint.status(),
                checkpoint.stateVisitCount(),
                checkpoint.history()
        );
    }

    private GraphNodeStatus mapSessionPhaseStatus(String phaseId, SessionNodeProjection projection) {
        boolean visited = projection.phaseVisitCount().getOrDefault(phaseId, 0) > 0
                || projection.history().stream().anyMatch(record -> Objects.equals(phaseId, record.phaseId()));
        if (Objects.equals(phaseId, projection.currentPhaseId())) {
            return switch (projection.status()) {
                case ACTIVE -> GraphNodeStatus.RUNNING;
                case SUSPENDED -> GraphNodeStatus.WAITING;
                case COMPLETED, TERMINATED, TIMED_OUT -> visited ? GraphNodeStatus.COMPLETED : GraphNodeStatus.NOT_STARTED;
            };
        }
        return visited ? GraphNodeStatus.COMPLETED : GraphNodeStatus.NOT_STARTED;
    }

    private GraphNodeStatus mapStateMachineStateStatus(String stateId, StateMachineNodeProjection projection) {
        boolean visited = projection.stateVisitCount().getOrDefault(stateId, 0) > 0
                || projection.history().stream().anyMatch(record -> Objects.equals(stateId, record.stateId()));
        if (Objects.equals(stateId, projection.currentStateId())) {
            return stateMachineCurrentNodeStatus(projection.status());
        }
        return visited ? GraphNodeStatus.COMPLETED : GraphNodeStatus.NOT_STARTED;
    }

    /**
     * Converts the lifecycle status of a state machine's current state into the node projection API.
     *
     * <p>{@link StateMachineStatus#TERMINATED} represents an administrative stop rather than a
     * state graph failure, so the current state is projected as {@link GraphNodeStatus#CANCELLED}.</p>
     *
     * @param status durable state-machine lifecycle status
     * @return graph-engine node projection status for the current state
     */
    static GraphNodeStatus stateMachineCurrentNodeStatus(StateMachineStatus status) {
        return switch (status) {
            case RUNNING -> GraphNodeStatus.RUNNING;
            case WAITING_EVENT -> GraphNodeStatus.WAITING;
            case COMPLETED -> GraphNodeStatus.COMPLETED;
            case FAILED -> GraphNodeStatus.FAILED;
            case TERMINATED -> GraphNodeStatus.CANCELLED;
        };
    }

    private Instant firstPhaseTimestamp(List<RoundRecord> history, String phaseId) {
        return history.stream()
                .filter(record -> Objects.equals(phaseId, record.phaseId()))
                .map(RoundRecord::timestamp)
                .findFirst()
                .orElse(null);
    }

    private Instant phaseCompletedAt(SessionNodeProjection projection, String phaseId) {
        if (Objects.equals(phaseId, projection.currentPhaseId())) {
            return null;
        }
        return projection.history().stream()
                .filter(record -> Objects.equals(phaseId, record.phaseId()))
                .map(RoundRecord::timestamp)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private Instant firstStateTimestamp(List<StateExecutionRecord> history, String stateId) {
        return history.stream()
                .filter(record -> Objects.equals(stateId, record.stateId()))
                .map(StateExecutionRecord::timestamp)
                .findFirst()
                .orElse(null);
    }

    private Instant stateCompletedAt(StateMachineNodeProjection projection, String stateId) {
        if (Objects.equals(stateId, projection.currentStateId())) {
            return null;
        }
        return projection.history().stream()
                .filter(record -> Objects.equals(stateId, record.stateId()))
                .map(StateExecutionRecord::timestamp)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private List<StateDef> orderedStateDefinitions(StateMachineDef stateMachine) {
        java.util.LinkedHashSet<String> ordered = new java.util.LinkedHashSet<>();
        visitState(stateMachine, stateMachine.initialStateId(), ordered);
        stateMachine.states().keySet().forEach(ordered::add);
        return ordered.stream()
                .map(stateMachine.states()::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private void visitState(StateMachineDef stateMachine,
                            String stateId,
                            java.util.LinkedHashSet<String> ordered) {
        if (stateId == null || !ordered.add(stateId)) {
            return;
        }
        StateDef state = stateMachine.states().get(stateId);
        if (state == null) {
            return;
        }
        state.transitions().forEach(transition -> visitState(stateMachine, transition.targetStateId(), ordered));
        if (state.onTimeoutState() != null && !state.onTimeoutState().isBlank()) {
            visitState(stateMachine, state.onTimeoutState(), ordered);
        }
        stateMachine.globalTransitions().forEach(transition -> visitState(stateMachine, transition.targetStateId(), ordered));
    }

    private static ExecutionWait preferPendingSignalWait(ExecutionWait left, ExecutionWait right) {
        if (left.timeoutAt() == null) {
            return right;
        }
        if (right.timeoutAt() == null) {
            return left;
        }
        return right.updatedAt().isAfter(left.updatedAt()) ? right : left;
    }

    private Optional<Graph> compilePendingSignalGraph(GraphInstance instance) {
        Optional<GraphDefinition> definition = stores.graphDefinitionStore().getByKey(
                instance.tenantId(),
                instance.namespace(),
                instance.definitionKey()
        );
        Optional<GraphVersion> version = stores.graphVersionStore().get(instance.versionId());
        if (definition.isEmpty() || version.isEmpty()) {
            return Optional.empty();
        }
        try {
            VersionCompileResult compilation = versionCompiler.compile(definition.get(), version.get());
            if (!compilation.valid() || compilation.graph() == null) {
                return Optional.empty();
            }
            return Optional.of(compilation.graph());
        } catch (RuntimeException exception) {
            logger.log(
                    Level.WARNING,
                    "Failed to compile graph version " + instance.versionId() + " while projecting pending signals",
                    exception
            );
            return Optional.empty();
        }
    }

    private GraphInstance refreshProjection(GraphInstance instance) {
        return switch (instance.executionMode()) {
            case GRAPH, STATE_MACHINE -> refreshExecutionProjection(instance);
            case SESSION -> refreshSessionProjection(instance);
        };
    }

    private GraphInstance refreshExecutionProjection(GraphInstance instance) {
        if (runtimeSupport.executionStore() == null) {
            return instance;
        }
        Optional<ExecutionInstance> execution = runtimeSupport.executionStore().get(instance.instanceId());
        if (execution.isEmpty()) {
            return instance;
        }
        GraphInstanceStatus status = GraphInstanceStatus.fromExecutionStatus(execution.get().status());
        return updateProjectionIfChanged(instance, status, execution.get().updatedAt(), execution.get().completedAt());
    }

    private GraphInstance refreshSessionProjection(GraphInstance instance) {
        DurableSessionManager manager = sessionManager;
        if (manager != null) {
            Optional<SessionStateSnapshot> activeSnapshot = manager.query(instance.instanceId(), instance.initiator());
            if (activeSnapshot.isPresent()) {
                SessionProjection projection = toSessionProjection(activeSnapshot.get());
                if (shouldRetainExistingSessionProjection(instance, projection)) {
                    return instance;
                }
                return updateProjectionIfChanged(
                        instance,
                        projection.status(),
                        projection.updatedAt(),
                        projection.completedAt()
                );
            }
            Optional<SessionCheckpoint> durableCheckpoint = manager.getCheckpoint(instance.instanceId());
            if (durableCheckpoint.isPresent()) {
                SessionProjection projection = toSessionProjection(durableCheckpoint.get());
                if (shouldRetainExistingSessionProjection(instance, projection)) {
                    return instance;
                }
                return updateProjectionIfChanged(
                        instance,
                        projection.status(),
                        projection.updatedAt(),
                        projection.completedAt()
                );
            }
        }
        if (runtimeSupport.executionCheckpointStore() != null) {
            Optional<SessionCheckpoint> checkpoint = new ExecutionCheckpointSessionStore(
                    runtimeSupport.executionCheckpointStore()
            ).load(instance.instanceId());
            if (checkpoint.isPresent()) {
                SessionProjection projection = toSessionProjection(checkpoint.get());
                if (shouldRetainExistingSessionProjection(instance, projection)) {
                    return instance;
                }
                return updateProjectionIfChanged(
                        instance,
                        projection.status(),
                        projection.updatedAt(),
                        projection.completedAt()
                );
            }
        }
        if (runtimeSupport.executionStore() == null) {
            return instance;
        }
        Optional<ExecutionInstance> execution = runtimeSupport.executionStore().get(instance.instanceId());
        if (execution.isEmpty()) {
            return instance;
        }
        GraphInstanceStatus status = GraphInstanceStatus.fromExecutionStatus(execution.get().status());
        return updateProjectionIfChanged(instance, status, execution.get().updatedAt(), execution.get().completedAt());
    }

    private GraphInstance updateProjectionIfChanged(GraphInstance instance,
                                                    GraphInstanceStatus status,
                                                    Instant updatedAt,
                                                    Instant completedAt) {
        return updateProjection(instance, status, updatedAt, completedAt, false);
    }

    private GraphInstance updateProjection(GraphInstance instance,
                                           GraphInstanceStatus status,
                                           Instant updatedAt,
                                           Instant completedAt,
                                           boolean updateTimestampOnly) {
        Instant resolvedUpdatedAt = updatedAt == null ? runtimeSupport.timeSource().now() : updatedAt;
        Instant resolvedCompletedAt = status.terminal()
                ? (completedAt == null ? resolvedUpdatedAt : completedAt)
                : null;
        if (instance.status() == status
                && Objects.equals(instance.completedAt(), resolvedCompletedAt)
                && (!updateTimestampOnly || Objects.equals(instance.updatedAt(), resolvedUpdatedAt))) {
            return instance;
        }
        GraphInstance updated = new GraphInstance(
                instance.instanceId(),
                instance.definitionKey(),
                instance.versionId(),
                instance.tenantId(),
                instance.namespace(),
                instance.businessKey(),
                instance.executionMode(),
                status,
                instance.initiator(),
                instance.variables(),
                instance.revision(),
                instance.createdAt(),
                resolvedUpdatedAt,
                resolvedCompletedAt
        );
        try {
            return stores.graphInstanceStore().update(updated, instance.revision());
        } catch (GraphEngineStoreException exception) {
            if (exception.errorCode() == GraphEngineErrorCode.VERSION_CONFLICT) {
                return stores.graphInstanceStore().get(instance.instanceId()).orElse(updated);
            }
            throw exception;
        }
    }

    private GraphInstanceStatus fromSessionStatus(SessionStatus status, Map<String, Object> sharedState) {
        return switch (status) {
            case ACTIVE -> GraphInstanceStatus.RUNNING;
            case SUSPENDED -> GraphInstanceStatus.SUSPENDED;
            case COMPLETED -> GraphInstanceStatus.COMPLETED;
            case TERMINATED -> isSessionCancellation(sharedState) ? GraphInstanceStatus.CANCELLED : GraphInstanceStatus.TERMINATED;
            case TIMED_OUT -> GraphInstanceStatus.FAILED;
        };
    }

    private GraphInstance updateExecutionLifecycle(GraphInstance instance,
                                                   ExecutionStatus targetStatus,
                                                   GraphInstanceStatus projectedStatus,
                                                   String reason) {
        if (runtimeSupport.executionStore() == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "Execution store is not configured"
            );
        }
        ExecutionInstance execution = runtimeSupport.executionStore().get(instance.instanceId()).orElse(null);
        if (execution == null) {
            throw notFound("Execution not found: " + instance.instanceId());
        }
        runtimeSupport.executionStore().updateStatus(
                execution.identity().executionId(),
                targetStatus,
                execution.version(),
                reason
        );
        if (runtimeSupport.timerService() != null) {
            runtimeSupport.timerService().cancelAllTimers(instance.instanceId());
        }
        if (runtimeSupport.waitStore() != null) {
            runtimeSupport.waitStore().deleteByExecution(instance.instanceId());
        }
        if (runtimeSupport.workItemStore() != null) {
            runtimeSupport.workItemStore().cancelByExecution(instance.instanceId(), reason);
        }
        cancelOpenTasks(instance.instanceId(), reason);
        return updateProjectionIfChanged(
                instance,
                projectedStatus,
                runtimeSupport.timeSource().now(),
                runtimeSupport.timeSource().now()
        );
    }

    private GraphInstance updateSessionLifecycle(GraphInstance instance,
                                                 VersionCompileResult compilation,
                                                 GraphInstanceStatus projectedStatus,
                                                 String encodedReason,
                                                 String visibleReason) {
        sessionManager(compilation).terminate(instance.instanceId(), encodedReason, GOVERNANCE_SOURCE);
        cancelOpenTasks(instance.instanceId(), visibleReason);
        return updateProjectionIfChanged(
                instance,
                projectedStatus,
                runtimeSupport.timeSource().now(),
                runtimeSupport.timeSource().now()
        );
    }

    /**
     * Maps one completed session round into the shared audit projection shape used by graph-mode history.
     */
    private GraphAuditEntry mapSessionAuditEntry(GraphInstance instance, RoundRecord roundRecord) {
        return new GraphAuditEntry(
                instance.instanceId(),
                instance.definitionKey(),
                instance.versionId(),
                instance.tenantId(),
                instance.namespace(),
                roundRecord.phaseId(),
                null,
                com.leanowtech.bloge.runtime.audit.AuditEventType.NODE_COMPLETE,
                safeSerializeSessionRoundValue(instance.instanceId(), roundRecord.phaseId(), "input", roundRecord.input()),
                safeSerializeSessionRoundValue(instance.instanceId(), roundRecord.phaseId(), "output", roundRecord.output()),
                null,
                // SESSION audit entries reuse retryAttempt to carry the within-phase round ordinal
                // (1-based). These rounds are iterations, not retry-failure counts; see
                // GraphAuditEntry.retryAttempt for the cross-mode contract.
                roundRecord.roundInPhase(),
                null,
                roundRecord.timestamp()
        );
    }

    /**
     * Serializes session round payloads without failing the entire audit projection on unsupported values.
     */
    private String safeSerializeSessionRoundValue(String instanceId, String phaseId, String fieldName, Object value) {
        if (value == null) {
            return null;
        }
        try {
            return runtimeSupport.jsonCodec().serialize(value);
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING,
                    "Failed to serialize session round " + fieldName
                            + " for " + instanceId + "/" + phaseId,
                    exception);
            return null;
        }
    }

    private GraphAuditEntry mapAuditEntry(GraphInstance instance, AuditEntry entry) {
        return new GraphAuditEntry(
                instance.instanceId(),
                instance.definitionKey(),
                instance.versionId(),
                instance.tenantId(),
                instance.namespace(),
                entry.nodeId(),
                entry.operatorRef(),
                entry.eventType(),
                entry.inputJson(),
                entry.outputJson(),
                entry.errorMessage(),
                entry.retryAttempt(),
                entry.elapsed() == null ? null : entry.elapsed().toMillis(),
                entry.timestamp()
        );
    }

    private GraphTransitionEntry mapTransitionEntry(GraphInstance instance, ExecutionTransitionLogEntry entry) {
        return new GraphTransitionEntry(
                entry.transitionId(),
                instance.instanceId(),
                instance.definitionKey(),
                instance.versionId(),
                instance.tenantId(),
                instance.namespace(),
                GraphInstanceStatus.fromExecutionStatus(entry.fromStatus()),
                GraphInstanceStatus.fromExecutionStatus(entry.toStatus()),
                entry.fromVersion(),
                entry.toVersion(),
                entry.transitionSource(),
                entry.transitionReason(),
                entry.createdAt()
        );
    }

    /**
     * Synthesizes a session lifecycle transition view from completed round history when the control plane has no rows.
     */
    private List<GraphTransitionEntry> synthesizeSessionTransitions(GraphInstance instance, SessionNodeProjection projection) {
        List<RoundRecord> history = projection.history();
        if (history.isEmpty()) {
            if (instance.status() == GraphInstanceStatus.RUNNING) {
                return List.of();
            }
            return List.of(newSyntheticSessionTransition(
                    instance,
                    0,
                    GraphInstanceStatus.RUNNING,
                    instance.status(),
                    syntheticSessionTerminalTimestamp(instance)
            ));
        }

        List<GraphTransitionEntry> transitions = new ArrayList<>();
        for (int index = 0; index < history.size(); index++) {
            RoundRecord round = history.get(index);
            if (index > 0) {
                Instant earliestNextRoundStart = history.get(index - 1).timestamp();
                transitions.add(newSyntheticSessionTransition(
                        instance,
                        transitions.size(),
                        GraphInstanceStatus.SUSPENDED,
                        GraphInstanceStatus.RUNNING,
                        // Round history only persists completion timestamps. The previous round's
                        // completion time is therefore the earliest observable moment when the next
                        // round could have resumed.
                        earliestNextRoundStart
                ));
            }

            boolean lastRound = index == history.size() - 1;
            if (!lastRound) {
                transitions.add(newSyntheticSessionTransition(
                        instance,
                        transitions.size(),
                        GraphInstanceStatus.RUNNING,
                        GraphInstanceStatus.SUSPENDED,
                        round.timestamp()
                ));
                continue;
            }

            switch (instance.status()) {
                case SUSPENDED -> transitions.add(newSyntheticSessionTransition(
                        instance,
                        transitions.size(),
                        GraphInstanceStatus.RUNNING,
                        GraphInstanceStatus.SUSPENDED,
                        round.timestamp()
                ));
                case RUNNING -> {
                    transitions.add(newSyntheticSessionTransition(
                            instance,
                            transitions.size(),
                            GraphInstanceStatus.RUNNING,
                            GraphInstanceStatus.SUSPENDED,
                            round.timestamp()
                    ));
                    transitions.add(newSyntheticSessionTransition(
                            instance,
                            transitions.size(),
                            GraphInstanceStatus.SUSPENDED,
                            GraphInstanceStatus.RUNNING,
                            laterOf(round.timestamp(), instance.updatedAt())
                    ));
                }
                case COMPLETED, FAILED, CANCELLED, TERMINATED -> transitions.add(newSyntheticSessionTransition(
                        instance,
                        transitions.size(),
                        GraphInstanceStatus.RUNNING,
                        instance.status(),
                        laterOf(round.timestamp(), syntheticSessionTerminalTimestamp(instance))
                ));
            }
        }
        return List.copyOf(transitions);
    }

    /**
     * Creates one deterministic synthetic transition entry for SESSION instances.
     */
    private GraphTransitionEntry newSyntheticSessionTransition(GraphInstance instance,
                                                              int index,
                                                              GraphInstanceStatus fromStatus,
                                                              GraphInstanceStatus toStatus,
                                                              Instant createdAt) {
        return new GraphTransitionEntry(
                "session-synth-" + instance.instanceId() + "-" + index,
                instance.instanceId(),
                instance.definitionKey(),
                instance.versionId(),
                instance.tenantId(),
                instance.namespace(),
                fromStatus,
                toStatus,
                0,
                0,
                SESSION_SYNTHETIC_TRANSITION_SOURCE,
                SESSION_SYNTHETIC_TRANSITION_REASON,
                createdAt
        );
    }

    private Instant syntheticSessionTerminalTimestamp(GraphInstance instance) {
        return instance.completedAt() == null ? instance.updatedAt() : instance.completedAt();
    }

    private static Instant laterOf(Instant left, Instant right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.compareTo(right) >= 0 ? left : right;
    }

    private GraphDeadLetter mapDeadLetter(DeadLetterEntry entry) {
        GraphInstance instance = stores.graphInstanceStore().get(entry.identity().executionId()).orElse(null);
        return new GraphDeadLetter(
                entry.itemId(),
                entry.identity().executionId(),
                instance == null ? entry.identity().graphName() : instance.definitionKey(),
                instance == null ? null : instance.versionId(),
                entry.identity().tenantId(),
                entry.identity().namespace(),
                entry.identity().businessKey(),
                entry.identity().shardId(),
                entry.itemType(),
                entry.nodeId(),
                entry.waitId(),
                entry.taskId(),
                entry.priority(),
                entry.retryCount(),
                entry.maxRetries(),
                entry.payload(),
                entry.payloadRef(),
                entry.lastError(),
                entry.deadLetterReason(),
                entry.firstSeenAt(),
                entry.deadLetteredAt()
        );
    }

    private RemoteWorkerEnvelope remoteWorkerEnvelope(WorkItem workItem) {
        if (workItem.payload() == null || workItem.payload().isBlank()) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.INVALID_STATE,
                    "Remote worker item payload is missing: " + workItem.itemId()
            );
        }
        try {
            return RemoteWorkerEnvelope.fromValue(runtimeSupport.jsonCodec().deserialize(workItem.payload()));
        } catch (RuntimeException exception) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.INVALID_STATE,
                    "Remote worker item payload is invalid: " + workItem.itemId(),
                    exception
            );
        }
    }

    private <T> List<T> paginate(List<T> items, int page, int size) {
        long fromIndexLong = Math.min((long) Math.max(0, page) * Math.max(0, size), items.size());
        int fromIndex = (int) fromIndexLong;
        int toIndex = (int) Math.min(fromIndexLong + Math.max(0L, size), items.size());
        return items.subList(fromIndex, toIndex);
    }

    private void requireExpectedRevision(long actualRevision, long expectedRevision, String resourceLabel) {
        if (actualRevision != expectedRevision) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.CONFLICT,
                    resourceLabel + " revision conflict: expected " + expectedRevision + " but was " + actualRevision
            );
        }
    }

    private void requireMutableInstance(GraphInstance instance, String operation) {
        if (instance.status().terminal()) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.INVALID_STATE,
                    "Cannot " + operation + " terminal instance '" + instance.instanceId() + "'"
            );
        }
    }

    private String normalizeLifecycleReason(String reason, String fallback) {
        if (reason == null || reason.isBlank()) {
            return fallback;
        }
        return reason;
    }

    private String encodeSessionCancelReason(String reason) {
        return SESSION_CANCEL_REASON_PREFIX + reason;
    }

    private boolean isSessionCancellation(Map<String, Object> sharedState) {
        if (sharedState == null) {
            return false;
        }
        Object reason = sharedState.get(SESSION_TERMINATION_REASON_KEY);
        return reason instanceof String value
                && (value.startsWith(SESSION_CANCEL_REASON_PREFIX) || DEFAULT_CANCEL_REASON.equalsIgnoreCase(value));
    }

    private void cancelOpenTasks(String instanceId, String reason) {
        TaskInboxStore inboxStore = runtimeSupport.taskInboxStore();
        if (inboxStore == null) {
            return;
        }
        inboxStore.query(new TaskInboxQuery(
                        null,
                        null,
                        null,
                        Set.of(TaskInboxStatus.OPEN, TaskInboxStatus.CLAIMED),
                        null,
                        null,
                        null,
                        instanceId,
                        0,
                        Integer.MAX_VALUE
                )).forEach(task -> inboxStore.cancel(task.taskId(), reason));
    }

    private WorkItem requireRemoteWorkerWorkItem(String itemId) {
        return requireWorkItemStore("Remote worker APIs require work-item storage")
                .get(itemId)
                .map(this::requireRemoteWorkerWorkItem)
                .orElseThrow(() -> notFound("Remote worker item not found: " + itemId));
    }

    private WorkItem requireRemoteWorkerWorkItem(WorkItem workItem) {
        if (workItem.itemType() != WorkItemType.EXECUTE_NODE) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.INVALID_STATE,
                    "Work item '" + workItem.itemId() + "' is not a remote worker execution item"
            );
        }
        return workItem;
    }

    private void requireRemoteWorkerClaim(WorkItem workItem,
                                          String leaseToken,
                                          long expectedRevision,
                                          String operation) {
        if (workItem.status() != WorkItemStatus.CLAIMED) {
            throw conflict("Remote worker item '" + workItem.itemId() + "' is not claimed for " + operation);
        }
        if (workItem.version() != expectedRevision) {
            throw conflict("Remote worker item revision conflict for '" + workItem.itemId()
                    + "': expected " + expectedRevision + " but was " + workItem.version());
        }
        if (!Objects.equals(workItem.claimToken(), leaseToken)) {
            throw conflict("Remote worker item lease token is invalid for " + workItem.itemId());
        }
        if (workItem.claimUntil() == null || !workItem.claimUntil().isAfter(runtimeSupport.timeSource().now())) {
            throw conflict("Remote worker item lease expired for " + workItem.itemId());
        }
    }

    private GraphDefinition requireDefinition(String definitionId) {
        return stores.graphDefinitionStore().get(definitionId)
                .orElseThrow(() -> notFound("Definition not found: " + definitionId));
    }

    private GraphVersion requireVersion(String versionId) {
        return stores.graphVersionStore().get(versionId)
                .orElseThrow(() -> notFound("Version not found: " + versionId));
    }

    private GraphDefinition requireActiveDefinition(String definitionKey, String tenantId, String namespace) {
        GraphDefinition definition = getDefinitionByKey(definitionKey, tenantId, namespace);
        requireDefinitionIsActive(definition);
        return definition;
    }

    private void requireDefinitionIsActive(GraphDefinition definition) {
        if (definition.status() != GraphDefinitionStatus.ACTIVE) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.INVALID_STATE,
                    "Definition '" + definition.definitionKey() + "' is not active"
            );
        }
    }

    private DurableGraphEngine requireDurableRuntime() {
        if (durableGraphEngine == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "Durable graph runtime is not configured"
            );
        }
        return durableGraphEngine;
    }

    private WorkItemStore requireWorkItemStore(String message) {
        if (runtimeSupport.workItemStore() == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    message
            );
        }
        return runtimeSupport.workItemStore();
    }

    private Scope resolveScope(String tenantId, String namespace) {
        TenantContext current = TenantContextHolder.current();
        return new Scope(
                tenantId == null ? current.tenantId() : tenantId,
                namespace == null ? current.namespace() : namespace
        );
    }

    private GraphEngineServiceException notFound(String message) {
        return new GraphEngineServiceException(GraphEngineServiceErrorCode.NOT_FOUND, message);
    }

    private GraphEngineServiceException conflict(String message) {
        return new GraphEngineServiceException(GraphEngineServiceErrorCode.CONFLICT, message);
    }

    private GraphEngineServiceException conflict(String message, Throwable cause) {
        return new GraphEngineServiceException(GraphEngineServiceErrorCode.CONFLICT, message, cause);
    }

    private void recordInstanceStarted(GraphInstance instance) {
        metricsObserver.onInstanceStarted(
                instance.definitionKey(),
                instance.tenantId(),
                instance.namespace(),
                instance.executionMode().name()
        );
    }

    private void recordInstanceCompletedIfTerminal(GraphInstance instance) {
        if (instance != null && instance.status().terminal()) {
            recordInstanceCompleted(instance);
        }
    }

    private void recordInstanceCompleted(GraphInstance instance) {
        metricsObserver.onInstanceCompleted(
                instance.definitionKey(),
                instance.tenantId(),
                instance.namespace(),
                instance.executionMode().name(),
                instance.status().name()
        );
    }

    private GraphInstance awaitTerminalProjection(String instanceId, GraphInstance fallback, Duration grace) {
        GraphInstance latest = stores.graphInstanceStore().get(instanceId)
                .map(this::refreshProjection)
                .orElse(fallback);
        if (latest == null || latest.status().terminal()) {
            return latest;
        }
        long deadline = System.nanoTime() + grace.toNanos();
        while (System.nanoTime() < deadline) {
            sleepExecutionResultPoll();
            latest = stores.graphInstanceStore().get(instanceId)
                    .map(this::refreshProjection)
                    .orElse(latest);
            if (latest.status().terminal()) {
                return latest;
            }
        }
        return latest;
    }

    private GraphInstance awaitSessionSignalProjection(GraphInstance baseline,
                                                       SessionProjection baselineProjection,
                                                       Duration grace) {
        GraphInstance latest = stores.graphInstanceStore().get(baseline.instanceId())
                .map(instance -> refreshSessionSignalProjection(instance, baseline))
                .orElse(baseline);
        if (latest == null || latest.status().terminal()) {
            return latest;
        }
        Optional<SessionProjection> latestProjection = currentSessionProjection(latest);
        if (latest.status() == GraphInstanceStatus.SUSPENDED
                && latestProjection.filter(projection -> projection.status() == GraphInstanceStatus.SUSPENDED)
                .map(projection -> sessionProjectionAdvanced(baselineProjection, projection))
                .orElse(false)) {
            return latest;
        }
        long deadline = System.nanoTime() + grace.toNanos();
        while (System.nanoTime() < deadline) {
            sleepExecutionResultPoll();
            latest = stores.graphInstanceStore().get(baseline.instanceId())
                    .map(instance -> refreshSessionSignalProjection(instance, baseline))
                    .orElse(latest);
            latestProjection = currentSessionProjection(latest);
            if (latest.status().terminal()) {
                return latest;
            }
            if (latest.status() == GraphInstanceStatus.SUSPENDED
                    && latestProjection.filter(projection -> projection.status() == GraphInstanceStatus.SUSPENDED)
                    .map(projection -> sessionProjectionAdvanced(baselineProjection, projection))
                    .orElse(false)) {
                return latest;
            }
        }
        return latest;
    }

    private GraphInstance refreshSessionSignalProjection(GraphInstance instance, GraphInstance baseline) {
        Optional<SessionProjection> projection = currentSessionProjection(instance);
        if (projection.isPresent()) {
            if (shouldRetainExistingSessionProjection(instance, projection.get())) {
                return instance;
            }
            return updateProjection(
                    instance,
                    projection.get().status(),
                    projection.get().updatedAt(),
                    projection.get().completedAt(),
                    projectionAdvanced(
                            baseline,
                            projection.get().status(),
                            projection.get().updatedAt(),
                            projection.get().completedAt()
                    )
            );
        }
        if (runtimeSupport.executionStore() != null) {
            Optional<ExecutionInstance> execution = runtimeSupport.executionStore().get(instance.instanceId());
            if (execution.isPresent()) {
                GraphInstanceStatus status = GraphInstanceStatus.fromExecutionStatus(execution.get().status());
                if (!status.terminal()) {
                    return instance;
                }
                return updateProjection(
                        instance,
                        status,
                        execution.get().updatedAt(),
                        execution.get().completedAt(),
                        projectionAdvanced(baseline, status, execution.get().updatedAt(), execution.get().completedAt())
                );
            }
        }
        return instance;
    }

    private Optional<SessionProjection> currentSessionProjection(GraphInstance instance) {
        DurableSessionManager manager = sessionManager;
        if (manager != null) {
            Optional<SessionStateSnapshot> activeSnapshot = manager.query(instance.instanceId(), instance.initiator());
            if (activeSnapshot.isPresent()) {
                return activeSnapshot.map(this::toSessionProjection);
            }
            Optional<SessionCheckpoint> durableCheckpoint = manager.getCheckpoint(instance.instanceId());
            if (durableCheckpoint.isPresent()) {
                return durableCheckpoint.map(this::toSessionProjection);
            }
        }
        if (runtimeSupport.executionCheckpointStore() == null) {
            return Optional.empty();
        }
        return new ExecutionCheckpointSessionStore(runtimeSupport.executionCheckpointStore())
                .load(instance.instanceId())
                .map(this::toSessionProjection);
    }

    private SessionProjection toSessionProjection(SessionStateSnapshot snapshot) {
        GraphInstanceStatus status = fromSessionStatus(snapshot.status(), snapshot.sharedState());
        Instant completedAt = status.terminal() ? snapshot.lastTouchAt() : null;
        return new SessionProjection(
                status,
                snapshot.lastTouchAt(),
                completedAt,
                snapshot.currentPhaseId(),
                snapshot.currentPhaseRound(),
                snapshot.totalRounds()
        );
    }

    private SessionProjection toSessionProjection(SessionCheckpoint checkpoint) {
        GraphInstanceStatus status = fromSessionStatus(checkpoint.status(), checkpoint.sharedState());
        Instant completedAt = status.terminal() ? checkpoint.checkpointedAt() : null;
        return new SessionProjection(
                status,
                checkpoint.checkpointedAt(),
                completedAt,
                checkpoint.currentPhaseId(),
                checkpoint.currentPhaseRound(),
                checkpoint.totalRounds()
        );
    }

    private boolean shouldRetainExistingSessionProjection(GraphInstance instance, SessionProjection projection) {
        if (instance.status() != GraphInstanceStatus.SUSPENDED && !instance.status().terminal()) {
            return false;
        }
        return projection.status() == GraphInstanceStatus.RUNNING
                && !projection.updatedAt().isAfter(instance.updatedAt());
    }

    private boolean sessionProjectionAdvanced(SessionProjection baseline, SessionProjection current) {
        if (current == null) {
            return false;
        }
        if (baseline == null) {
            return true;
        }
        return !Objects.equals(baseline.phaseId(), current.phaseId())
                || baseline.phaseRound() != current.phaseRound()
                || baseline.totalRounds() != current.totalRounds()
                || baseline.status() != current.status()
                || current.updatedAt().isAfter(baseline.updatedAt());
    }

    private record SessionProjection(GraphInstanceStatus status,
                                     Instant updatedAt,
                                     Instant completedAt,
                                     String phaseId,
                                     int phaseRound,
                                     int totalRounds) {
    }

    private record InstanceContextProjection(Map<String, Map<String, Object>> nodeOutputs,
                                             Map<String, Object> sharedState,
                                             Map<String, Object> phaseOutputs,
                                             Map<String, Map<String, Object>> stateOutputs) {
        private static InstanceContextProjection empty() {
            return new InstanceContextProjection(Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    private record SessionNodeProjection(String currentPhaseId,
                                         SessionStatus status,
                                         Map<String, Integer> phaseVisitCount,
                                         List<RoundRecord> history,
                                         Instant updatedAt) {
    }

    private record StateMachineNodeProjection(String currentStateId,
                                              StateMachineStatus status,
                                              Map<String, Integer> stateVisitCount,
                                              List<StateExecutionRecord> history) {
    }

    private boolean projectionAdvanced(GraphInstance baseline, GraphInstance current) {
        if (baseline == null) {
            return true;
        }
        return baseline.status() != current.status()
                || !Objects.equals(baseline.updatedAt(), current.updatedAt())
                || !Objects.equals(baseline.completedAt(), current.completedAt());
    }

    private boolean projectionAdvanced(GraphInstance baseline,
                                       GraphInstanceStatus status,
                                       Instant updatedAt,
                                       Instant completedAt) {
        if (baseline == null) {
            return true;
        }
        return baseline.status() != status
                || !Objects.equals(baseline.updatedAt(), updatedAt)
                || !Objects.equals(baseline.completedAt(), completedAt);
    }

    private GraphInstance refreshRemoteWorkerInstance(String executionId) {
        return stores.graphInstanceStore().get(executionId).map(this::refreshProjection).orElse(null);
    }

    private void deactivateOtherDeployments(String definitionKey,
                                            String tenantId,
                                            String namespace,
                                            String environment,
                                            String deploymentIdToKeep) {
        if (environment == null) {
            return;
        }
        stores.graphDeploymentStore().query(new com.leanowtech.bloge.graphengine.store.GraphDeploymentQuery(
                tenantId,
                namespace,
                definitionKey,
                environment,
                true,
                0,
                200
        )).stream()
                .filter(GraphDeployment::active)
                .filter(deployment -> !Objects.equals(deployment.deploymentId(), deploymentIdToKeep))
                .forEach(deployment -> stores.graphDeploymentStore().activate(
                        deployment.deploymentId(),
                        false,
                        deployment.revision()
                ));
    }

    private record Scope(String tenantId, String namespace) {
    }

    /**
     * Resolves the definition for a deployment and enforces view RBAC.
     */
    private void enforceDeploymentView(GraphDeployment deployment) {
        stores.graphDefinitionStore()
                .getByKey(deployment.tenantId(), deployment.namespace(), deployment.definitionKey())
                .ifPresent(RbacEnforcer::requireView);
    }

    /**
     * Resolves the definition for a deployment and enforces deploy RBAC.
     */
    private void enforceDeploymentDeploy(GraphDeployment deployment) {
        stores.graphDefinitionStore()
                .getByKey(deployment.tenantId(), deployment.namespace(), deployment.definitionKey())
                .ifPresent(RbacEnforcer::requireDeploy);
    }

    /**
     * Resolves the definition for an instance and enforces view RBAC.
     */
    private void enforceInstanceView(GraphInstance instance) {
        stores.graphDefinitionStore()
                .getByKey(instance.tenantId(), instance.namespace(), instance.definitionKey())
                .ifPresent(RbacEnforcer::requireView);
    }

    /**
     * Resolves the definition for an instance and enforces admin RBAC.
     */
    private void enforceInstanceAdmin(GraphInstance instance) {
        stores.graphDefinitionStore()
                .getByKey(instance.tenantId(), instance.namespace(), instance.definitionKey())
                .ifPresent(RbacEnforcer::requireAdmin);
    }

    /**
     * Resolves the definition for a task and enforces view RBAC.
     */
    private void enforceTaskView(GraphTask task) {
        stores.graphInstanceStore().get(task.instanceId())
                .flatMap(instance -> stores.graphDefinitionStore()
                        .getByKey(instance.tenantId(), instance.namespace(), instance.definitionKey()))
                .ifPresent(RbacEnforcer::requireView);
    }

    /**
     * Resolves the definition for a task and enforces admin RBAC.
     */
    private void enforceTaskAdmin(GraphTask task) {
        stores.graphInstanceStore().get(task.instanceId())
                .flatMap(instance -> stores.graphDefinitionStore()
                        .getByKey(instance.tenantId(), instance.namespace(), instance.definitionKey()))
                .ifPresent(RbacEnforcer::requireAdmin);
    }

    /**
     * Resolves the definition for a dead-letter entry and enforces admin RBAC.
     */
    private void enforceDeadLetterAdmin(DeadLetterEntry entry) {
        GraphInstance instance = stores.graphInstanceStore().get(entry.identity().executionId()).orElse(null);
        if (instance == null) {
            return;
        }
        stores.graphDefinitionStore()
                .getByKey(instance.tenantId(), instance.namespace(), instance.definitionKey())
                .ifPresent(RbacEnforcer::requireAdmin);
    }

    private boolean canViewDefinition(GraphDefinition definition) {
        return permitsView(() -> RbacEnforcer.requireView(definition));
    }

    private boolean canViewVersion(GraphVersion version) {
        return permitsView(() -> RbacEnforcer.requireView(requireDefinition(version.definitionId())));
    }

    private boolean canViewDeployment(GraphDeployment deployment) {
        return permitsView(() -> enforceDeploymentView(deployment));
    }

    private boolean canViewInstance(GraphInstance instance) {
        return permitsView(() -> enforceInstanceView(instance));
    }

    private boolean canViewDeadLetter(DeadLetterEntry entry) {
        return permitsView(() -> {
            GraphInstance instance = stores.graphInstanceStore().get(entry.identity().executionId()).orElse(null);
            if (instance == null) {
                return;
            }
            stores.graphDefinitionStore()
                    .getByKey(instance.tenantId(), instance.namespace(), instance.definitionKey())
                    .ifPresent(RbacEnforcer::requireView);
        });
    }

    private boolean permitsView(Runnable operation) {
        try {
            operation.run();
            return true;
        } catch (GraphEngineServiceException exception) {
            if (exception.errorCode() == GraphEngineServiceErrorCode.ACCESS_DENIED
                    || exception.errorCode() == GraphEngineServiceErrorCode.NOT_FOUND) {
                return false;
            }
            throw exception;
        }
    }

    /**
     * Scans visible definitions and versions within the given scope and builds a
     * map from operator name to the list of version references that use it.
     */
    private Map<String, List<OperatorUsageReference>> buildOperatorUsageMap(String tenantId, String namespace) {
        List<GraphDefinition> definitions = stores.graphDefinitionStore().query(
                new GraphDefinitionQuery(tenantId, namespace, null, null, null, null, 0, Integer.MAX_VALUE)
        );
        Map<String, List<OperatorUsageReference>> usageMap = new java.util.HashMap<>();
        for (GraphDefinition definition : definitions) {
            if (!canViewDefinition(definition)) {
                continue;
            }
            List<GraphVersion> versions = stores.graphVersionStore().query(
                    new GraphVersionQuery(definition.definitionId(), Set.of(), 0, Integer.MAX_VALUE)
            );
            for (GraphVersion version : versions) {
                if (version.metadata() == null) {
                    continue;
                }
                for (String operatorRef : version.metadata().operatorRefs()) {
                    usageMap.computeIfAbsent(operatorRef, ignored -> new ArrayList<>())
                            .add(new OperatorUsageReference(
                                    definition.definitionKey(),
                                    definition.definitionId(),
                                    version.version(),
                                    version.versionId(),
                                    version.status()
                            ));
                }
            }
        }
        return usageMap;
    }

    private static final class GraphEngineServiceHashes {
        private GraphEngineServiceHashes() {
        }

        private static String hashDefinition(String definitionJson) {
            return com.leanowtech.bloge.core.runtime.registry.GraphDefinitionHasher.sha256Hex(definitionJson);
        }
    }
}
