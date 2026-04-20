package com.leanowtech.bloge.graphengine.service;

import com.leanowtech.bloge.core.runtime.identity.ExecutionIdentity;
import com.leanowtech.bloge.core.runtime.identity.ExecutionType;
import com.leanowtech.bloge.core.runtime.registry.VersionRoutingPolicy;
import com.leanowtech.bloge.durable.store.memory.InMemoryTaskInboxStore;
import com.leanowtech.bloge.graphengine.model.GraphDefinition;
import com.leanowtech.bloge.graphengine.model.GraphDefinitionStatus;
import com.leanowtech.bloge.graphengine.model.GraphDeployment;
import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;
import com.leanowtech.bloge.graphengine.model.GraphInstance;
import com.leanowtech.bloge.graphengine.model.GraphInstanceStatus;
import com.leanowtech.bloge.graphengine.model.GraphVersion;
import com.leanowtech.bloge.graphengine.model.GraphVersionMetadata;
import com.leanowtech.bloge.graphengine.model.GraphVersionStatus;
import com.leanowtech.bloge.graphengine.model.RbacPolicy;
import com.leanowtech.bloge.graphengine.model.TaskDefinition;
import com.leanowtech.bloge.graphengine.service.command.CreateDefinitionCommand;
import com.leanowtech.bloge.graphengine.service.command.CreateVersionCommand;
import com.leanowtech.bloge.graphengine.service.command.StartInstanceCommand;
import com.leanowtech.bloge.graphengine.service.command.UpdateDefinitionCommand;
import com.leanowtech.bloge.graphengine.store.GraphDefinitionQuery;
import com.leanowtech.bloge.graphengine.store.GraphDeploymentQuery;
import com.leanowtech.bloge.graphengine.store.GraphEngineStores;
import com.leanowtech.bloge.graphengine.store.GraphInstanceQuery;
import com.leanowtech.bloge.graphengine.store.GraphVersionQuery;
import com.leanowtech.bloge.graphengine.store.memory.InMemoryGraphDefinitionStore;
import com.leanowtech.bloge.graphengine.store.memory.InMemoryGraphDeploymentStore;
import com.leanowtech.bloge.graphengine.store.memory.InMemoryGraphInstanceStore;
import com.leanowtech.bloge.graphengine.store.memory.InMemoryGraphVersionStore;
import com.leanowtech.bloge.runtime.task.TaskInbox;
import com.leanowtech.bloge.runtime.task.TaskInboxQuery;
import com.leanowtech.bloge.runtime.task.TaskInboxStatus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests RBAC enforcement in {@link DefaultGraphEngineService}.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Operations succeed when the caller has the required role.</li>
 *   <li>Operations fail with {@code ACCESS_DENIED} when the caller lacks the required role.</li>
 *   <li>Operations succeed when no {@link CallerContext} is bound (system/internal calls).</li>
 *   <li>Operations succeed when the policy role-set is empty (unrestricted).</li>
 * </ul>
 */
class RbacEnforcementTest {

    @AfterEach
    void clearCallerContext() {
        CallerContextHolder.clear();
    }

    // -- CallerContext unit tests --

    @Test
    void callerContextHasAnyRoleReturnsTrueWhenEmpty() {
        CallerContext ctx = new CallerContext(Set.of("user"));
        assertTrue(ctx.hasAnyRole(Set.of()), "empty required-roles set means unrestricted");
        assertTrue(ctx.hasAnyRole(null), "null required-roles set means unrestricted");
    }

    @Test
    void callerContextHasAnyRoleReturnsTrueWhenOverlap() {
        CallerContext ctx = new CallerContext(Set.of("admin", "viewer"));
        assertTrue(ctx.hasAnyRole(Set.of("admin")));
        assertTrue(ctx.hasAnyRole(Set.of("viewer", "other")));
    }

    @Test
    void callerContextHasAnyRoleReturnsFalseWhenNoOverlap() {
        CallerContext ctx = new CallerContext(Set.of("user"));
        assertFalse(ctx.hasAnyRole(Set.of("admin")));
    }

    @Test
    void callerContextAnonymousHasNoRoles() {
        assertTrue(CallerContext.ANONYMOUS.roles().isEmpty());
        assertFalse(CallerContext.ANONYMOUS.hasAnyRole(Set.of("admin")));
        assertTrue(CallerContext.ANONYMOUS.hasAnyRole(Set.of()), "empty = unrestricted");
    }

    // -- CallerContextHolder tests --

    @Test
    void callerContextHolderDefaultsToNull() {
        assertNull(CallerContextHolder.current());
    }

    @Test
    void callerContextHolderSetAndClear() {
        CallerContext ctx = new CallerContext(Set.of("ops"));
        CallerContextHolder.set(ctx);
        assertSame(ctx, CallerContextHolder.current());
        CallerContextHolder.clear();
        assertNull(CallerContextHolder.current());
    }

    // -- RbacEnforcer unit tests --

    @Test
    void rbacEnforcerAllowsWhenNoCallerContext() {
        GraphDefinition def = definition(new RbacPolicy(Set.of("admin"), Set.of(), Set.of(), Set.of()));
        // No CallerContext bound → system call → should not throw
        assertDoesNotThrow(() -> RbacEnforcer.requireView(def));
        assertDoesNotThrow(() -> RbacEnforcer.requireStart(def));
        assertDoesNotThrow(() -> RbacEnforcer.requireDeploy(def));
        assertDoesNotThrow(() -> RbacEnforcer.requireAdmin(def));
    }

    @Test
    void rbacEnforcerAllowsWhenRolesAreEmpty() {
        CallerContextHolder.set(CallerContext.ANONYMOUS);
        GraphDefinition def = definition(new RbacPolicy(Set.of(), Set.of(), Set.of(), Set.of()));
        assertDoesNotThrow(() -> RbacEnforcer.requireView(def));
        assertDoesNotThrow(() -> RbacEnforcer.requireStart(def));
        assertDoesNotThrow(() -> RbacEnforcer.requireDeploy(def));
        assertDoesNotThrow(() -> RbacEnforcer.requireAdmin(def));
    }

    @Test
    void rbacEnforcerAllowsWithMatchingRole() {
        CallerContextHolder.set(new CallerContext(Set.of("deployer")));
        GraphDefinition def = definition(new RbacPolicy(
                Set.of("viewer"), Set.of("starter"), Set.of("deployer"), Set.of("admin")
        ));
        assertDoesNotThrow(() -> RbacEnforcer.requireDeploy(def));
    }

    @Test
    void rbacEnforcerDeniesWithMismatchedRole() {
        CallerContextHolder.set(new CallerContext(Set.of("viewer")));
        GraphDefinition def = definition(new RbacPolicy(
                Set.of("viewer"), Set.of("starter"), Set.of("deployer"), Set.of("admin")
        ));
        GraphEngineServiceException ex = assertThrows(
                GraphEngineServiceException.class,
                () -> RbacEnforcer.requireAdmin(def)
        );
        assertEquals(GraphEngineServiceErrorCode.ACCESS_DENIED, ex.errorCode());
        assertTrue(ex.getMessage().contains("admin"));
    }

    @Test
    void rbacEnforcerDeniesViewWhenRestricted() {
        CallerContextHolder.set(new CallerContext(Set.of("other")));
        GraphDefinition def = definition(new RbacPolicy(
                Set.of("viewer"), Set.of(), Set.of(), Set.of()
        ));
        GraphEngineServiceException ex = assertThrows(
                GraphEngineServiceException.class,
                () -> RbacEnforcer.requireView(def)
        );
        assertEquals(GraphEngineServiceErrorCode.ACCESS_DENIED, ex.errorCode());
    }

    @Test
    void rbacEnforcerDeniesStartWhenRestricted() {
        CallerContextHolder.set(new CallerContext(Set.of("viewer")));
        GraphDefinition def = definition(new RbacPolicy(
                Set.of(), Set.of("starter"), Set.of(), Set.of()
        ));
        GraphEngineServiceException ex = assertThrows(
                GraphEngineServiceException.class,
                () -> RbacEnforcer.requireStart(def)
        );
        assertEquals(GraphEngineServiceErrorCode.ACCESS_DENIED, ex.errorCode());
    }

    // -- Integration tests with DefaultGraphEngineService --

    @Test
    void getDefinitionAllowedWithViewRole() {
        ServiceFixture fx = new ServiceFixture();
        GraphDefinition created = fx.createDefinition(new RbacPolicy(
                Set.of("viewer"), Set.of(), Set.of(), Set.of()
        ));
        CallerContextHolder.set(new CallerContext(Set.of("viewer")));
        GraphDefinition found = fx.service.getDefinition(created.definitionId());
        assertEquals(created.definitionId(), found.definitionId());
    }

    @Test
    void getDefinitionDeniedWithoutViewRole() {
        ServiceFixture fx = new ServiceFixture();
        GraphDefinition created = fx.createDefinition(new RbacPolicy(
                Set.of("viewer"), Set.of(), Set.of(), Set.of()
        ));
        CallerContextHolder.set(new CallerContext(Set.of("other")));
        GraphEngineServiceException ex = assertThrows(
                GraphEngineServiceException.class,
                () -> fx.service.getDefinition(created.definitionId())
        );
        assertEquals(GraphEngineServiceErrorCode.ACCESS_DENIED, ex.errorCode());
    }

    @Test
    void getDefinitionAllowedWithoutCallerContext() {
        ServiceFixture fx = new ServiceFixture();
        GraphDefinition created = fx.createDefinition(new RbacPolicy(
                Set.of("viewer"), Set.of(), Set.of(), Set.of()
        ));
        // No CallerContext set → system call → should succeed
        assertDoesNotThrow(() -> fx.service.getDefinition(created.definitionId()));
    }

    @Test
    void getDefinitionAllowedWithEmptyPolicy() {
        ServiceFixture fx = new ServiceFixture();
        GraphDefinition created = fx.createDefinition(new RbacPolicy(null, null, null, null));
        CallerContextHolder.set(CallerContext.ANONYMOUS);
        assertDoesNotThrow(() -> fx.service.getDefinition(created.definitionId()));
    }

    @Test
    void updateDefinitionDeniedWithoutAdminRole() {
        ServiceFixture fx = new ServiceFixture();
        GraphDefinition created = fx.createDefinition(new RbacPolicy(
                Set.of(), Set.of(), Set.of(), Set.of("admin")
        ));
        CallerContextHolder.set(new CallerContext(Set.of("viewer")));
        GraphEngineServiceException ex = assertThrows(
                GraphEngineServiceException.class,
                () -> fx.service.updateDefinition(new UpdateDefinitionCommand(
                        created.definitionId(),
                        created.revision(),
                        "updated-name",
                        null, null, null, null, null
                ))
        );
        assertEquals(GraphEngineServiceErrorCode.ACCESS_DENIED, ex.errorCode());
    }

    @Test
    void updateDefinitionAllowedWithAdminRole() {
        ServiceFixture fx = new ServiceFixture();
        GraphDefinition created = fx.createDefinition(new RbacPolicy(
                Set.of(), Set.of(), Set.of(), Set.of("admin")
        ));
        CallerContextHolder.set(new CallerContext(Set.of("admin")));
        GraphDefinition updated = fx.service.updateDefinition(new UpdateDefinitionCommand(
                created.definitionId(),
                created.revision(),
                "updated-name",
                null, null, null, null, null
        ));
        assertEquals("updated-name", updated.displayName());
    }

    @Test
    void archiveDefinitionDeniedWithoutAdminRole() {
        ServiceFixture fx = new ServiceFixture();
        GraphDefinition created = fx.createDefinition(new RbacPolicy(
                Set.of(), Set.of(), Set.of(), Set.of("admin")
        ));
        CallerContextHolder.set(new CallerContext(Set.of("deployer")));
        GraphEngineServiceException ex = assertThrows(
                GraphEngineServiceException.class,
                () -> fx.service.archiveDefinition(created.definitionId(), created.revision())
        );
        assertEquals(GraphEngineServiceErrorCode.ACCESS_DENIED, ex.errorCode());
    }

    @Test
    void createVersionDeniedWithoutDeployRole() {
        ServiceFixture fx = new ServiceFixture();
        GraphDefinition created = fx.createDefinition(new RbacPolicy(
                Set.of(), Set.of(), Set.of("deployer"), Set.of()
        ));
        CallerContextHolder.set(new CallerContext(Set.of("viewer")));
        GraphEngineServiceException ex = assertThrows(
                GraphEngineServiceException.class,
                () -> fx.service.createVersion(new CreateVersionCommand(
                        created.definitionKey(), null, null,
                        "1.0.0", "graph hello { echo(\"hi\") }", null, null
                ))
        );
        assertEquals(GraphEngineServiceErrorCode.ACCESS_DENIED, ex.errorCode());
    }

    @Test
    void getDefinitionByKeyDeniedWithoutViewRole() {
        ServiceFixture fx = new ServiceFixture();
        GraphDefinition created = fx.createDefinition(new RbacPolicy(
                Set.of("viewer"), Set.of(), Set.of(), Set.of()
        ));
        CallerContextHolder.set(new CallerContext(Set.of("other")));
        GraphEngineServiceException ex = assertThrows(
                GraphEngineServiceException.class,
                () -> fx.service.getDefinitionByKey(created.definitionKey(), null, null)
        );
        assertEquals(GraphEngineServiceErrorCode.ACCESS_DENIED, ex.errorCode());
    }

    @Test
    void queryDefinitionsFiltersInaccessibleDefinitions() {
        ServiceFixture fx = new ServiceFixture();
        GraphDefinition visible = fx.createDefinition(new RbacPolicy(Set.of("viewer"), Set.of(), Set.of(), Set.of()));
        GraphDefinition hidden = fx.createDefinition(new RbacPolicy(Set.of("admin"), Set.of(), Set.of(), Set.of()));

        CallerContextHolder.set(new CallerContext(Set.of("viewer")));

        List<GraphDefinition> definitions = fx.service.queryDefinitions(new GraphDefinitionQuery(
                null, null, null, null, null, null, 0, 50
        ));

        assertEquals(Set.of(visible.definitionId()), definitions.stream().map(GraphDefinition::definitionId).collect(java.util.stream.Collectors.toSet()));
        assertFalse(definitions.stream().anyMatch(definition -> definition.definitionId().equals(hidden.definitionId())));
    }

    @Test
    void queryVersionsReturnsEmptyWhenDefinitionIsNotVisible() {
        ServiceFixture fx = new ServiceFixture();
        GraphDefinition restricted = fx.createDefinition(new RbacPolicy(Set.of("viewer"), Set.of(), Set.of(), Set.of()));
        GraphVersion version = fx.createVersion(restricted);

        CallerContextHolder.set(new CallerContext(Set.of("other")));

        List<GraphVersion> versions = fx.service.queryVersions(new GraphVersionQuery(
                restricted.definitionId(), Set.of(), 0, 50
        ));

        assertTrue(versions.isEmpty());
        assertNotNull(version.versionId());
    }

    @Test
    void queryDeploymentsFiltersInaccessibleDeployments() {
        ServiceFixture fx = new ServiceFixture();
        GraphDefinition restricted = fx.createDefinition(new RbacPolicy(Set.of("viewer"), Set.of(), Set.of(), Set.of()));
        fx.createDeployment(restricted);

        CallerContextHolder.set(new CallerContext(Set.of("other")));

        List<GraphDeployment> deployments = fx.service.queryDeployments(new GraphDeploymentQuery(
                null, null, null, null, null, 0, 50
        ));

        assertTrue(deployments.isEmpty());
    }

    @Test
    void queryInstancesFiltersInaccessibleInstances() {
        ServiceFixture fx = new ServiceFixture();
        GraphDefinition restricted = fx.createDefinition(new RbacPolicy(Set.of("viewer"), Set.of(), Set.of(), Set.of()));
        GraphVersion version = fx.createVersion(restricted);
        fx.createInstance(restricted, version);

        CallerContextHolder.set(new CallerContext(Set.of("other")));

        List<GraphInstance> instances = fx.service.queryInstances(new GraphInstanceQuery(
                null, null, null, null, Set.of(), null, 0, 50
        ));

        assertTrue(instances.isEmpty());
    }

    @Test
    void queryTasksFiltersInaccessibleTasks() {
        ServiceFixture fx = new ServiceFixture();
        GraphDefinition restricted = fx.createDefinition(new RbacPolicy(Set.of("viewer"), Set.of(), Set.of(), Set.of()));
        GraphVersion version = fx.createTaskVersion(restricted, "approval");
        GraphInstance instance = fx.createInstance(restricted, version);
        fx.createTask(instance, "approval");

        CallerContextHolder.set(new CallerContext(Set.of("other")));

        List<?> tasks = fx.service.queryTasks(new TaskInboxQuery(
                null, null, null, Set.of(TaskInboxStatus.OPEN), null, null, null, instance.instanceId(), 0, 50
        ));

        assertTrue(tasks.isEmpty());
    }

    // -- Helpers --

    private static GraphDefinition definition(RbacPolicy policy) {
        return new GraphDefinition(
                UUID.randomUUID().toString(),
                "test-key",
                "default",
                "default",
                "Test",
                null,
                null,
                null,
                null,
                policy,
                GraphDefinitionStatus.ACTIVE,
                0,
                Instant.now(),
                Instant.now()
        );
    }

    /**
     * Minimal fixture that only sets up the stores needed for RBAC-relevant
     * operations, without the full durable-engine wiring.
     */
    private static final class ServiceFixture {
        final InMemoryGraphDefinitionStore graphDefinitionStore = new InMemoryGraphDefinitionStore();
        final InMemoryGraphVersionStore graphVersionStore = new InMemoryGraphVersionStore();
        final InMemoryGraphDeploymentStore graphDeploymentStore = new InMemoryGraphDeploymentStore();
        final InMemoryGraphInstanceStore graphInstanceStore = new InMemoryGraphInstanceStore();
        final InMemoryTaskInboxStore taskInboxStore = new InMemoryTaskInboxStore();
        final DefaultGraphEngineService service;

        ServiceFixture() {
            GraphEngineStores stores = new GraphEngineStores(
                    graphDefinitionStore,
                    graphVersionStore,
                    graphDeploymentStore,
                    graphInstanceStore
            );
            GraphEngineRuntimeSupport runtimeSupport = GraphEngineRuntimeSupport.builder()
                    .taskInboxStore(taskInboxStore)
                    .build();
            service = new DefaultGraphEngineService(stores, runtimeSupport);
        }

        /**
         * Creates a definition bypassing RBAC (no CallerContext bound).
         */
        GraphDefinition createDefinition(RbacPolicy rbacPolicy) {
            CallerContextHolder.clear();
            return service.createDefinition(new CreateDefinitionCommand(
                    "test-def-" + UUID.randomUUID().toString().substring(0, 8),
                    null,
                    null,
                    "Test Definition",
                    null,
                    null,
                    null,
                    null,
                    rbacPolicy
            ));
        }

        GraphVersion createVersion(GraphDefinition definition) {
            GraphVersion version = new GraphVersion(
                    UUID.randomUUID().toString(),
                    definition.definitionId(),
                    "1.0.0",
                    "hash-1.0.0",
                    "graph test {}",
                    null,
                    new GraphVersionMetadata(null, List.of(), Map.of(), null, null, Map.of(), Map.of()),
                    null,
                    null,
                    GraphVersionStatus.PUBLISHED,
                    0,
                    Instant.now(),
                    Instant.now(),
                    Instant.now()
            );
            graphVersionStore.create(version);
            return version;
        }

        GraphVersion createTaskVersion(GraphDefinition definition, String nodeId) {
            GraphVersion version = new GraphVersion(
                    UUID.randomUUID().toString(),
                    definition.definitionId(),
                    "1.0.0",
                    "hash-task-1.0.0",
                    "graph taskFlow {}",
                    null,
                    new GraphVersionMetadata(
                            GraphExecutionMode.GRAPH,
                            List.of("user-task"),
                            Map.of(),
                            null,
                            null,
                            Map.of(nodeId, new TaskDefinition(nodeId, "USER_TASK", null, null, List.of("ops"), List.of(), null)),
                            Map.of()
                    ),
                    null,
                    null,
                    GraphVersionStatus.PUBLISHED,
                    0,
                    Instant.now(),
                    Instant.now(),
                    Instant.now()
            );
            graphVersionStore.create(version);
            return version;
        }

        GraphDeployment createDeployment(GraphDefinition definition) {
            GraphDeployment deployment = new GraphDeployment(
                    UUID.randomUUID().toString(),
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "production",
                    new VersionRoutingPolicy.Latest(),
                    null,
                    true,
                    0,
                    Instant.now(),
                    Instant.now()
            );
            graphDeploymentStore.create(deployment);
            return deployment;
        }

        GraphInstance createInstance(GraphDefinition definition, GraphVersion version) {
            GraphInstance instance = new GraphInstance(
                    UUID.randomUUID().toString(),
                    definition.definitionKey(),
                    version.versionId(),
                    definition.tenantId(),
                    definition.namespace(),
                    "business-" + version.versionId(),
                    GraphExecutionMode.GRAPH,
                    GraphInstanceStatus.SUSPENDED,
                    "starter",
                    Map.of(),
                    0,
                    Instant.now(),
                    Instant.now(),
                    null
            );
            graphInstanceStore.create(instance);
            return instance;
        }

        void createTask(GraphInstance instance, String nodeId) {
            Instant now = Instant.now();
            taskInboxStore.create(new TaskInbox(
                    UUID.randomUUID().toString(),
                    new ExecutionIdentity(
                            instance.tenantId(),
                            instance.namespace(),
                            instance.businessKey(),
                            instance.instanceId(),
                            ExecutionType.GRAPH,
                            instance.definitionKey(),
                            "1.0.0",
                            "hash-task-1.0.0",
                            null,
                            null,
                            null,
                            null
                    ),
                    nodeId,
                    "USER_TASK",
                    null,
                    List.of(),
                    List.of("ops"),
                    List.of(),
                    "Approve",
                    "Review",
                    Map.of("orderId", instance.businessKey()),
                    now.plusSeconds(300),
                    1,
                    TaskInboxStatus.OPEN,
                    0,
                    now,
                    now,
                    null
            ));
        }
    }
}
