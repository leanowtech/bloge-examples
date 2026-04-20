package com.leanowtech.bloge.graphengine.store.contract;

import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.core.runtime.registry.GraphMigrationPolicy;
import com.leanowtech.bloge.core.runtime.registry.VersionRoutingPolicy;
import com.leanowtech.bloge.graphengine.model.GraphCategory;
import com.leanowtech.bloge.graphengine.model.GraphDefinition;
import com.leanowtech.bloge.graphengine.model.GraphDefinitionStatus;
import com.leanowtech.bloge.graphengine.model.GraphDeployment;
import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;
import com.leanowtech.bloge.graphengine.model.GraphInstance;
import com.leanowtech.bloge.graphengine.model.GraphInstanceStatus;
import com.leanowtech.bloge.graphengine.model.GraphVersion;
import com.leanowtech.bloge.graphengine.model.GraphVersionMetadata;
import com.leanowtech.bloge.graphengine.model.GraphVersionStatus;
import com.leanowtech.bloge.graphengine.model.OperatorPlaneConfig;
import com.leanowtech.bloge.graphengine.model.RbacPolicy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Shared fixtures for graph-engine metadata store contract tests.
 */
public final class ContractTestSupport {
    private ContractTestSupport() {
    }

    static final Instant BASE_TIME = Instant.parse("2026-01-01T00:00:00Z");

    public static TenantContext tenant(String tenantId, String namespace) {
        return new TenantContext(tenantId, namespace);
    }

    public static GraphDefinition definition(String definitionId,
                                             String tenantId,
                                             String namespace,
                                             String definitionKey) {
        return new GraphDefinition(
                definitionId,
                definitionKey,
                tenantId,
                namespace,
                "Display " + definitionKey,
                "Definition " + definitionKey,
                GraphCategory.PIPELINE,
                Map.of("team", "platform"),
                "platform",
                new RbacPolicy(null, null, null, null),
                GraphDefinitionStatus.ACTIVE,
                0,
                BASE_TIME,
                BASE_TIME
        );
    }

    public static GraphVersion version(String versionId, String definitionId, String semanticVersion) {
        return new GraphVersion(
                versionId,
                definitionId,
                semanticVersion,
                "hash-" + semanticVersion,
                "graph sample { node a : noop {} }",
                null,
                new GraphVersionMetadata(GraphExecutionMode.GRAPH, null, null, null, null, null, null),
                "artifact-" + semanticVersion,
                GraphMigrationPolicy.PIN_VERSION,
                GraphVersionStatus.DRAFT,
                0,
                null,
                BASE_TIME,
                BASE_TIME
        );
    }

    public static GraphDeployment deployment(String deploymentId,
                                             String tenantId,
                                             String namespace,
                                             String definitionKey,
                                             String environment,
                                             boolean active) {
        return new GraphDeployment(
                deploymentId,
                definitionKey,
                tenantId,
                namespace,
                environment,
                new VersionRoutingPolicy.Latest(),
                OperatorPlaneConfig.defaults(),
                active,
                0,
                BASE_TIME,
                BASE_TIME
        );
    }

    public static GraphInstance instance(String instanceId,
                                         String tenantId,
                                         String namespace,
                                         String definitionKey,
                                         GraphInstanceStatus status) {
        return new GraphInstance(
                instanceId,
                definitionKey,
                "version-" + UUID.randomUUID(),
                tenantId,
                namespace,
                "biz-" + instanceId,
                GraphExecutionMode.GRAPH,
                status,
                "tester",
                Map.of("instanceId", instanceId),
                0,
                BASE_TIME,
                BASE_TIME,
                status.terminal() ? BASE_TIME : null
        );
    }
}
