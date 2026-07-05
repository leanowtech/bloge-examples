package com.leanowtech.bloge.graphengine.server.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.model.BackoffStrategy;
import com.leanowtech.bloge.core.operator.RemoteWorkerEnvelope;
import com.leanowtech.bloge.core.runtime.registry.VersionRoutingPolicy;
import com.leanowtech.bloge.core.runtime.work.WorkItemType;
import com.leanowtech.bloge.core.schema.SchemaCompatibility;
import com.leanowtech.bloge.graphengine.model.GraphAuditEntry;
import com.leanowtech.bloge.graphengine.model.GraphControlActionEntry;
import com.leanowtech.bloge.graphengine.model.GraphDeadLetter;
import com.leanowtech.bloge.graphengine.model.GraphDefinition;
import com.leanowtech.bloge.graphengine.model.GraphDefinitionStatus;
import com.leanowtech.bloge.graphengine.model.GraphDeployment;
import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;
import com.leanowtech.bloge.graphengine.model.GraphInstance;
import com.leanowtech.bloge.graphengine.model.GraphInstanceDiagram;
import com.leanowtech.bloge.graphengine.model.GraphInstanceContext;
import com.leanowtech.bloge.graphengine.model.GraphInstanceStatus;
import com.leanowtech.bloge.graphengine.model.GraphNodeState;
import com.leanowtech.bloge.graphengine.model.GraphNodeStatus;
import com.leanowtech.bloge.graphengine.model.GraphOperationsSnapshot;
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
import com.leanowtech.bloge.graphengine.model.GraphVersionStatus;
import com.leanowtech.bloge.graphengine.model.RemoteWorkerBinding;
import com.leanowtech.bloge.graphengine.server.config.GraphEngineServerJacksonSupport;
import com.leanowtech.bloge.graphengine.server.config.GraphEngineServerProperties;
import com.leanowtech.bloge.graphengine.service.GraphEngineService;
import com.leanowtech.bloge.graphengine.service.GraphVersionDiff;
import com.leanowtech.bloge.graphengine.service.OperatorInventoryEntry;
import com.leanowtech.bloge.graphengine.service.OperatorInventoryQuery;
import com.leanowtech.bloge.graphengine.service.PublishVersionResult;
import com.leanowtech.bloge.graphengine.service.RecoveryActionEvidence;
import com.leanowtech.bloge.graphengine.service.RetryDeadLetterResult;
import com.leanowtech.bloge.graphengine.service.RetryInstanceResult;
import com.leanowtech.bloge.graphengine.service.SignalInstanceResult;
import com.leanowtech.bloge.graphengine.service.StartInstanceResult;
import com.leanowtech.bloge.graphengine.service.VersionValidationResult;
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
import com.leanowtech.bloge.graphengine.store.GraphDeploymentQuery;
import com.leanowtech.bloge.graphengine.store.GraphInstanceQuery;
import com.leanowtech.bloge.graphengine.store.GraphVersionQuery;
import com.leanowtech.bloge.runtime.audit.AuditEventType;
import com.leanowtech.bloge.runtime.task.TaskInboxQuery;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Duration;

/**
 * Shared controller-test support for graph-engine REST endpoints.
 */
abstract class AbstractGraphControllerTest {

    protected RecordingGraphEngineService graphEngineService;
    protected GraphEngineRequestScopeResolver scopeResolver;
    protected GraphEngineServerProperties properties;
    protected ObjectMapper objectMapper;

    @BeforeEach
    void setUpBase() {
        graphEngineService = new RecordingGraphEngineService();
        scopeResolver = new GraphEngineRequestScopeResolver();
        properties = new GraphEngineServerProperties();
        objectMapper = new ObjectMapper().findAndRegisterModules();
        GraphEngineServerJacksonSupport.registerMixins(objectMapper);
    }

    protected MockMvc mockMvc(Object... controllers) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(controllers)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setValidator(validator)
                .build();
    }

    protected GraphDefinition definition(String definitionId, String definitionKey) {
        return new GraphDefinition(
                definitionId,
                definitionKey,
                "default",
                "default",
                definitionKey,
                null,
                null,
                Map.of(),
                null,
                null,
                GraphDefinitionStatus.ACTIVE,
                2,
                null,
                null
        );
    }

    protected GraphVersion version(GraphDefinition definition, String versionId, String version) {
        return new GraphVersion(
                versionId,
                definition.definitionId(),
                version,
                "hash-" + version,
                "graph " + definition.definitionKey() + " {}",
                null,
                null,
                "artifact-" + version,
                null,
                GraphVersionStatus.DRAFT,
                1,
                null,
                null,
                null
        );
    }

    protected GraphVersion publishedVersion(GraphDefinition definition, String versionId, String version) {
        return new GraphVersion(
                versionId,
                definition.definitionId(),
                version,
                "hash-" + version,
                "graph " + definition.definitionKey() + " {}",
                null,
                null,
                "artifact-" + version,
                null,
                GraphVersionStatus.PUBLISHED,
                2,
                java.time.Instant.now(),
                null,
                null
        );
    }

    protected GraphDeployment deployment(String deploymentId, String definitionKey, VersionRoutingPolicy routingPolicy) {
        return new GraphDeployment(
                deploymentId,
                definitionKey,
                "default",
                "default",
                "production",
                routingPolicy,
                null,
                true,
                1,
                null,
                null
        );
    }

    protected GraphInstance instance(String instanceId, String definitionKey, String versionId, GraphInstanceStatus status) {
        return new GraphInstance(
                instanceId,
                definitionKey,
                versionId,
                "default",
                "default",
                "business-key",
                GraphExecutionMode.GRAPH,
                status,
                "starter",
                Map.of("orderId", "A-1"),
                1,
                null,
                null,
                status.terminal() ? java.time.Instant.now() : null
        );
    }

    protected GraphTask task(String taskId, String instanceId, String definitionKey, GraphTaskStatus status) {
        return new GraphTask(
                taskId,
                instanceId,
                definitionKey,
                "approval",
                "user-task",
                "Approve order",
                "alice",
                List.of("alice", "bob"),
                List.of("ops"),
                List.of(),
                "forms/approval",
                null,
                Map.of("orderId", "A-1"),
                5,
                null,
                null,
                status,
                1,
                null,
                null,
                status == GraphTaskStatus.COMPLETED ? java.time.Instant.now() : null
        );
    }

    protected GraphAuditEntry auditEntry(GraphInstance instance, String nodeId) {
        return new GraphAuditEntry(
                instance.instanceId(),
                instance.definitionKey(),
                instance.versionId(),
                instance.tenantId(),
                instance.namespace(),
                nodeId,
                "echo",
                AuditEventType.NODE_COMPLETE,
                "{\"approved\":true}",
                "{\"approved\":true}",
                null,
                0,
                12L,
                java.time.Instant.now()
        );
    }

    protected GraphControlActionEntry controlActionEntry(GraphInstance instance) {
        return new GraphControlActionEntry(
                instance.instanceId(),
                instance.definitionKey(),
                instance.versionId(),
                instance.tenantId(),
                instance.namespace(),
                "__control_retry_dead_letter__",
                "graph-engine-service",
                "RETRY_DEAD_LETTER",
                "RETRY_DEAD_LETTER",
                "DEAD_LETTER_OLDEST_AGE",
                "validated replay",
                "ops-alice",
                "INC-123",
                GraphControlActionEntry.AttemptStatus.SUCCEEDED,
                "RESTORED",
                "dead-1",
                "EVENT_MATCHED",
                "approval",
                "wait-1",
                null,
                null,
                null,
                List.of(),
                "manual intervention",
                1,
                List.of("dead-1"),
                List.of("approval"),
                1,
                List.of("dead-1"),
                List.of("approval"),
                null,
                null,
                null,
                "{\"actionCode\":\"RETRY_DEAD_LETTER\"}",
                "{\"attemptStatus\":\"SUCCEEDED\"}",
                java.time.Instant.now()
        );
    }

    protected GraphTransitionEntry transitionEntry(GraphInstance instance, GraphInstanceStatus toStatus) {
        return new GraphTransitionEntry(
                "transition-1",
                instance.instanceId(),
                instance.definitionKey(),
                instance.versionId(),
                instance.tenantId(),
                instance.namespace(),
                instance.status(),
                toStatus,
                1,
                2,
                "graph-engine-service",
                "governance action",
                java.time.Instant.now()
        );
    }

    protected GraphDeadLetter deadLetter(GraphInstance instance, String itemId) {
        return new GraphDeadLetter(
                itemId,
                instance.instanceId(),
                instance.definitionKey(),
                instance.versionId(),
                instance.tenantId(),
                instance.namespace(),
                instance.businessKey(),
                "shard-a",
                WorkItemType.TASK_RESUME,
                "approval",
                "wait-1",
                "task-1",
                5,
                3,
                5,
                "{\"orderId\":\"A-1\"}",
                null,
                "boom",
                "retry limit exceeded",
                java.time.Instant.now().minusSeconds(30),
                java.time.Instant.now()
        );
    }

    protected GraphRemoteWorkerJob remoteWorkerJob(String itemId, String workerTopic, String operatorRef) {
        return new GraphRemoteWorkerJob(
                itemId,
                "worker-a",
                "lease-" + itemId,
                java.time.Instant.now().plusSeconds(300),
                com.leanowtech.bloge.core.runtime.work.WorkItemStatus.CLAIMED,
                40,
                0,
                4,
                1,
                null,
                new RemoteWorkerEnvelope(
                        operatorRef,
                        workerTopic,
                        Map.of("orderId", "A-1"),
                        new RemoteWorkerEnvelope.RetryPolicy(3, Duration.ofSeconds(2), BackoffStrategy.EXPONENTIAL),
                        new RemoteWorkerEnvelope.ExecutionContext(
                                "approvalFlow",
                                "exec-1",
                                "riskCheck",
                                "approval-flow",
                                "version-1",
                                "default",
                                "default",
                                "business-key"
                        ),
                        java.time.Instant.now()
                ),
                java.time.Instant.now(),
                java.time.Instant.now()
        );
    }

    protected GraphRemoteWorkerRegistration remoteWorkerRegistration(String workerId, String workerTopic) {
        return new GraphRemoteWorkerRegistration(
                workerId,
                workerTopic,
                "default",
                "default",
                List.of(new GraphRemoteWorkerAssignment(
                        "deployment-1",
                        "approval-flow",
                        "production",
                        "RiskAssessment",
                        new RemoteWorkerBinding(workerId, workerTopic, null, Map.of("tier", "ml"))
                ))
        );
    }

    protected SchemaCompatibility backwardCompatible(String summary, String warning) {
        return new SchemaCompatibility.BackwardCompatible(summary, List.of(warning));
    }

    /**
     * Small hand-written service double that avoids Byte Buddy / Mockito issues on Java 25.
     */
    protected static final class RecordingGraphEngineService implements GraphEngineService {
        CreateDefinitionCommand createDefinitionCommand;
        GraphDefinition createDefinitionResult;
        String definitionLookupKey;
        String definitionLookupTenantId;
        String definitionLookupNamespace;
        GraphDefinition getDefinitionByKeyResult;
        UpdateDefinitionCommand updateDefinitionCommand;
        GraphDefinition updateDefinitionResult;
        String archiveDefinitionId;
        long archiveExpectedRevision;
        GraphDefinition archiveDefinitionResult;
        GraphVersionQuery queryVersionsQuery;
        List<GraphVersion> queryVersionsResult = List.of();
        String publishVersionId;
        long publishExpectedRevision;
        PublishVersionResult publishVersionResult;
        String deprecateVersionId;
        long deprecateExpectedRevision;
        GraphVersion deprecateVersionResult;
        CreateDeploymentCommand createDeploymentCommand;
        GraphDeployment createDeploymentResult;
        StartInstanceCommand startInstanceCommand;
        StartInstanceResult startInstanceResult;
        String getInstanceId;
        GraphInstance getInstanceResult;
        SignalInstanceCommand signalInstanceCommand;
        SignalInstanceResult signalInstanceResult;
        String cancelInstanceId;
        String cancelInstanceReason;
        long cancelInstanceExpectedRevision;
        GraphInstance cancelInstanceResult;
        String terminateInstanceId;
        String terminateInstanceReason;
        long terminateInstanceExpectedRevision;
        GraphInstance terminateInstanceResult;
        String auditInstanceId;
        int auditPage;
        int auditSize;
        List<GraphAuditEntry> queryAuditLogResult = List.of();
        String controlActionsInstanceId;
        int controlActionsPage;
        int controlActionsSize;
        List<GraphControlActionEntry> queryControlActionsResult = List.of();
        String transitionsInstanceId;
        int transitionsPage;
        int transitionsSize;
        List<GraphTransitionEntry> queryTransitionsResult = List.of();
        GraphDeadLetterQuery deadLetterQuery;
        List<GraphDeadLetter> queryDeadLettersResult = List.of();
        String retryDeadLetterItemId;
        RecoveryActionEvidence retryDeadLetterEvidence;
        RetryDeadLetterResult retryDeadLetterResult;
        RegisterRemoteWorkerCommand registerRemoteWorkerCommand;
        GraphRemoteWorkerRegistration registerRemoteWorkerResult;
        PollRemoteWorkerJobsCommand pollRemoteWorkerJobsCommand;
        List<GraphRemoteWorkerJob> pollRemoteWorkerJobsResult = List.of();
        HeartbeatRemoteWorkerJobCommand heartbeatRemoteWorkerJobCommand;
        GraphRemoteWorkerJob heartbeatRemoteWorkerJobResult;
        CompleteRemoteWorkerJobCommand completeRemoteWorkerJobCommand;
        FailRemoteWorkerJobCommand failRemoteWorkerJobCommand;
        CompleteTaskCommand completeTaskCommand;
        GraphTask completeTaskResult;
        String diffLeftVersionId;
        String diffRightVersionId;
        GraphVersionDiff diffVersionsResult;
        String operationsSnapshotTenantId;
        String operationsSnapshotNamespace;
        GraphOperationsSnapshot operationsSnapshotResult;
        Runnable createDefinitionOverride;

        @Override
        public GraphDefinition createDefinition(CreateDefinitionCommand command) {
            if (createDefinitionOverride != null) {
                createDefinitionOverride.run();
            }
            createDefinitionCommand = command;
            return required(createDefinitionResult, "createDefinitionResult");
        }

        @Override
        public GraphDefinition getDefinition(String definitionId) {
            throw unsupported("getDefinition");
        }

        @Override
        public GraphDefinition getDefinitionByKey(String definitionKey, String tenantId, String namespace) {
            definitionLookupKey = definitionKey;
            definitionLookupTenantId = tenantId;
            definitionLookupNamespace = namespace;
            return required(getDefinitionByKeyResult, "getDefinitionByKeyResult");
        }

        @Override
        public List<GraphDefinition> queryDefinitions(GraphDefinitionQuery query) {
            throw unsupported("queryDefinitions");
        }

        @Override
        public GraphDefinition updateDefinition(UpdateDefinitionCommand command) {
            updateDefinitionCommand = command;
            return required(updateDefinitionResult, "updateDefinitionResult");
        }

        @Override
        public GraphDefinition archiveDefinition(String definitionId, long expectedRevision) {
            archiveDefinitionId = definitionId;
            archiveExpectedRevision = expectedRevision;
            return required(archiveDefinitionResult, "archiveDefinitionResult");
        }

        @Override
        public GraphVersion createVersion(CreateVersionCommand command) {
            throw unsupported("createVersion");
        }

        @Override
        public GraphVersion getVersion(String versionId) {
            getVersionId = versionId;
            return required(getVersionResult, "getVersionResult");
        }

        @Override
        public List<GraphVersion> queryVersions(GraphVersionQuery query) {
            queryVersionsQuery = query;
            return queryVersionsResult;
        }

        @Override
        public VersionValidationResult validateVersion(String versionId) {
            throw unsupported("validateVersion");
        }

        @Override
        public PublishVersionResult publishVersion(String versionId, long expectedRevision) {
            publishVersionId = versionId;
            publishExpectedRevision = expectedRevision;
            return required(publishVersionResult, "publishVersionResult");
        }

        @Override
        public GraphVersion deprecateVersion(String versionId, long expectedRevision) {
            deprecateVersionId = versionId;
            deprecateExpectedRevision = expectedRevision;
            return required(deprecateVersionResult, "deprecateVersionResult");
        }

        @Override
        public GraphVersionDiff diffVersions(String leftVersionId, String rightVersionId) {
            diffLeftVersionId = leftVersionId;
            diffRightVersionId = rightVersionId;
            return required(diffVersionsResult, "diffVersionsResult");
        }

        @Override
        public GraphDeployment createDeployment(CreateDeploymentCommand command) {
            createDeploymentCommand = command;
            return required(createDeploymentResult, "createDeploymentResult");
        }

        @Override
        public GraphDeployment getDeployment(String deploymentId) {
            throw unsupported("getDeployment");
        }

        @Override
        public List<GraphDeployment> queryDeployments(GraphDeploymentQuery query) {
            throw unsupported("queryDeployments");
        }

        @Override
        public GraphDeployment updateDeployment(UpdateDeploymentCommand command) {
            throw unsupported("updateDeployment");
        }

        @Override
        public GraphDeployment activateDeployment(String deploymentId, boolean active, long expectedRevision) {
            throw unsupported("activateDeployment");
        }

        @Override
        public StartInstanceResult startInstance(StartInstanceCommand command) {
            startInstanceCommand = command;
            return required(startInstanceResult, "startInstanceResult");
        }

        @Override
        public GraphInstance getInstance(String instanceId) {
            getInstanceId = instanceId;
            return required(getInstanceResult, "getInstanceResult");
        }

        @Override
        public List<GraphInstance> queryInstances(GraphInstanceQuery query) {
            throw unsupported("queryInstances");
        }

        @Override
        public GraphOperationsSnapshot queryOperationsSnapshot(String tenantId, String namespace) {
            operationsSnapshotTenantId = tenantId;
            operationsSnapshotNamespace = namespace;
            return required(operationsSnapshotResult, "operationsSnapshotResult");
        }

        @Override
        public SignalInstanceResult signalInstance(SignalInstanceCommand command) {
            signalInstanceCommand = command;
            return required(signalInstanceResult, "signalInstanceResult");
        }

        @Override
        public GraphInstance cancelInstance(String instanceId, String reason, long expectedRevision) {
            cancelInstanceId = instanceId;
            cancelInstanceReason = reason;
            cancelInstanceExpectedRevision = expectedRevision;
            return required(cancelInstanceResult, "cancelInstanceResult");
        }

        @Override
        public GraphInstance terminateInstance(String instanceId, String reason, long expectedRevision) {
            terminateInstanceId = instanceId;
            terminateInstanceReason = reason;
            terminateInstanceExpectedRevision = expectedRevision;
            return required(terminateInstanceResult, "terminateInstanceResult");
        }

        @Override
        public List<GraphAuditEntry> queryInstanceAuditLog(String instanceId, int page, int size) {
            auditInstanceId = instanceId;
            auditPage = page;
            auditSize = size;
            return queryAuditLogResult;
        }

        @Override
        public List<GraphControlActionEntry> queryInstanceControlActions(String instanceId, int page, int size) {
            controlActionsInstanceId = instanceId;
            controlActionsPage = page;
            controlActionsSize = size;
            return queryControlActionsResult;
        }

        @Override
        public List<GraphTransitionEntry> queryInstanceTransitions(String instanceId, int page, int size) {
            transitionsInstanceId = instanceId;
            transitionsPage = page;
            transitionsSize = size;
            return queryTransitionsResult;
        }

        @Override
        public List<GraphDeadLetter> queryDeadLetters(GraphDeadLetterQuery query) {
            deadLetterQuery = query;
            return queryDeadLettersResult;
        }

        @Override
        public void retryDeadLetter(String itemId) {
            retryDeadLetterItemId = itemId;
        }

        @Override
        public void retryDeadLetter(String itemId, RecoveryActionEvidence evidence) {
            retryDeadLetterEvidence = evidence;
            retryDeadLetter(itemId);
        }

        @Override
        public RetryDeadLetterResult retryDeadLetterWithResult(String itemId, RecoveryActionEvidence evidence) {
            retryDeadLetterEvidence = evidence;
            retryDeadLetterItemId = itemId;
            if (retryDeadLetterResult != null) {
                return retryDeadLetterResult;
            }
            return new RetryDeadLetterResult(itemId, null, 1);
        }

        @Override
        public GraphRemoteWorkerRegistration registerRemoteWorker(RegisterRemoteWorkerCommand command) {
            registerRemoteWorkerCommand = command;
            return required(registerRemoteWorkerResult, "registerRemoteWorkerResult");
        }

        @Override
        public List<GraphRemoteWorkerJob> pollRemoteWorkerJobs(PollRemoteWorkerJobsCommand command) {
            pollRemoteWorkerJobsCommand = command;
            return pollRemoteWorkerJobsResult;
        }

        @Override
        public GraphRemoteWorkerJob heartbeatRemoteWorkerJob(HeartbeatRemoteWorkerJobCommand command) {
            heartbeatRemoteWorkerJobCommand = command;
            return required(heartbeatRemoteWorkerJobResult, "heartbeatRemoteWorkerJobResult");
        }

        @Override
        public void completeRemoteWorkerJob(CompleteRemoteWorkerJobCommand command) {
            completeRemoteWorkerJobCommand = command;
        }

        @Override
        public void failRemoteWorkerJob(FailRemoteWorkerJobCommand command) {
            failRemoteWorkerJobCommand = command;
        }

        @Override
        public GraphTask getTask(String taskId) {
            throw unsupported("getTask");
        }

        @Override
        public List<GraphTask> queryTasks(TaskInboxQuery query) {
            throw unsupported("queryTasks");
        }

        @Override
        public GraphTask claimTask(ClaimTaskCommand command) {
            throw unsupported("claimTask");
        }

        @Override
        public GraphTask completeTask(CompleteTaskCommand command) {
            completeTaskCommand = command;
            return required(completeTaskResult, "completeTaskResult");
        }

        @Override
        public GraphTask reassignTask(ReassignTaskCommand command) {
            throw unsupported("reassignTask");
        }

        @Override
        public GraphTask cancelTask(CancelTaskCommand command) {
            throw unsupported("cancelTask");
        }

        OperatorInventoryQuery operatorInventoryQuery;
        List<OperatorInventoryEntry> operatorInventoryResult = List.of();

        @Override
        public List<OperatorInventoryEntry> queryOperatorInventory(OperatorInventoryQuery query) {
            operatorInventoryQuery = query;
            return operatorInventoryResult;
        }

        String queryNodesInstanceId;
        Set<GraphNodeStatus> queryNodeStatuses;
        int queryNodesPage;
        int queryNodesSize;
        List<GraphNodeState> queryNodesResult = List.of();
        String instanceDiagramInstanceId;
        GraphInstanceDiagram instanceDiagramResult;
        String queryContextInstanceId;
        GraphInstanceContext queryContextResult;
        String queryPendingSignalsInstanceId;
        List<GraphPendingSignal> queryPendingSignalsResult = List.of();
        String versionDiagramVersionId;
        GraphVersionDiagram versionDiagramResult;
        String getVersionId;
        GraphVersion getVersionResult;
        String retryInstanceId;
        Set<String> retryNodeIds;
        long retryExpectedRevision;
        RecoveryActionEvidence retryInstanceEvidence;
        RetryInstanceResult retryInstanceResult;

        @Override
        public PagedResult<GraphNodeState> queryInstanceNodes(String instanceId, Set<GraphNodeStatus> statuses, int page, int size) {
            queryNodesInstanceId = instanceId;
            queryNodeStatuses = statuses;
            queryNodesPage = page;
            queryNodesSize = size;
            List<GraphNodeState> filtered = statuses == null || statuses.isEmpty()
                    ? queryNodesResult
                    : queryNodesResult.stream().filter(node -> statuses.contains(node.status())).toList();
            int fromIndex = Math.min(Math.max(0, page) * Math.max(1, size), filtered.size());
            int toIndex = Math.min(fromIndex + Math.max(1, size), filtered.size());
            return new PagedResult<>(filtered.subList(fromIndex, toIndex), page, size, filtered.size());
        }

        @Override
        public GraphVersionDiagram getVersionDiagram(String versionId) {
            versionDiagramVersionId = versionId;
            return required(versionDiagramResult, "versionDiagramResult");
        }

        @Override
        public GraphInstanceDiagram getInstanceDiagram(String instanceId) {
            instanceDiagramInstanceId = instanceId;
            return required(instanceDiagramResult, "instanceDiagramResult");
        }

        @Override
        public GraphInstanceContext getInstanceContext(String instanceId) {
            queryContextInstanceId = instanceId;
            return required(queryContextResult, "queryContextResult");
        }

        @Override
        public List<GraphPendingSignal> queryPendingSignals(String instanceId) {
            queryPendingSignalsInstanceId = instanceId;
            return queryPendingSignalsResult;
        }

        @Override
        public RetryInstanceResult retryInstance(String instanceId, Set<String> nodeIds, long expectedRevision) {
            retryInstanceId = instanceId;
            retryNodeIds = nodeIds;
            retryExpectedRevision = expectedRevision;
            return required(retryInstanceResult, "retryInstanceResult");
        }

        @Override
        public RetryInstanceResult retryInstance(String instanceId,
                                                Set<String> nodeIds,
                                                long expectedRevision,
                                                RecoveryActionEvidence evidence) {
            retryInstanceEvidence = evidence;
            return retryInstance(instanceId, nodeIds, expectedRevision);
        }

        private static UnsupportedOperationException unsupported(String methodName) {
            return new UnsupportedOperationException(methodName + " was not configured for this test");
        }

        private static <T> T required(T value, String label) {
            if (value == null) {
                throw new IllegalStateException(label + " was not configured for this test");
            }
            return value;
        }
    }
}
