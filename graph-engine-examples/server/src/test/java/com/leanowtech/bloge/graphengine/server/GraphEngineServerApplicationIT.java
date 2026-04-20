package com.leanowtech.bloge.graphengine.server;

import com.leanowtech.bloge.graphengine.model.GraphDefinition;
import com.leanowtech.bloge.graphengine.model.GraphDeployment;
import com.leanowtech.bloge.graphengine.model.GraphInstance;
import com.leanowtech.bloge.graphengine.model.GraphInstanceStatus;
import com.leanowtech.bloge.graphengine.model.GraphTask;
import com.leanowtech.bloge.graphengine.model.GraphTaskStatus;
import com.leanowtech.bloge.graphengine.model.GraphTransitionEntry;
import com.leanowtech.bloge.graphengine.model.GraphVersion;
import com.leanowtech.bloge.graphengine.model.GraphVersionDiagram;
import com.leanowtech.bloge.graphengine.model.GraphVersionStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end HTTP test for the graph-engine Spring Boot server.
 */
@SpringBootTest(
        classes = GraphEngineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:graph-engine-server-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "spring.datasource.driverClassName=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.bloge.dsl-locations=classpath:/missing-bloge/",
                "spring.bloge.tenant.enabled=true",
                "spring.bloge.tenant.mode=HEADER",
                "spring.bloge.tenant.header-name=X-Tenant-Id",
                "spring.bloge.tenant.namespace-header-name=X-Namespace"
        }
)
class GraphEngineServerApplicationIT {

    private static final String APPROVAL_DSL = """
            graph approvalFlow {
              node approval : user {
                input {
                  title = "Approve order"
                  candidateGroups = ["ops"]
                  orderId = ctx.orderId
                }
              }
            }
            """;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void httpApiCreatesPublishesDeploysStartsAndCompletesTask() throws Exception {
        GraphDefinition definition = exchange("/api/v1/graphs", HttpMethod.POST, Map.of(
                "definitionKey", "approval-flow",
                "displayName", "Approval Flow"
        ), GraphDefinition.class).getBody();
        assertNotNull(definition);
        assertEquals("approval-flow", definition.definitionKey());
        assertEquals("acme", definition.tenantId());
        assertEquals("sales", definition.namespace());

        GraphVersion version = exchange("/api/v1/graphs/approval-flow/versions", HttpMethod.POST, Map.of(
                "version", "1.0.0",
                "dslSource", APPROVAL_DSL
        ), GraphVersion.class).getBody();
        assertNotNull(version);
        assertEquals("1.0.0", version.version());

        Map<String, Object> published = exchangeMap("/api/v1/graphs/approval-flow/versions/1.0.0/publish", HttpMethod.POST, Map.of(
                "expectedRevision", version.revision()
        ));
        assertNotNull(published);
        assertEquals("PUBLISHED", nestedMap(published, "version").get("status"));

        GraphDeployment deployment = exchange("/api/v1/deployments", HttpMethod.POST, Map.of(
                "definitionKey", "approval-flow",
                "environment", "production",
                "active", true,
                "routingPolicy", Map.of("type", "latest")
        ), GraphDeployment.class).getBody();
        assertNotNull(deployment);
        assertEquals("approval-flow", deployment.definitionKey());

        Map<String, Object> started = exchangeMap("/api/v1/graphs/approval-flow/instances", HttpMethod.POST, Map.of(
                "businessKey", "approval-001",
                "initiator", "starter",
                "variables", Map.of("orderId", "approval-001")
        ));
        assertNotNull(started);

        String startedInstanceId = stringValue(nestedMap(started, "instance").get("instanceId"));
        GraphInstance suspended = awaitInstanceStatus(startedInstanceId, GraphInstanceStatus.SUSPENDED);
        GraphTask task = awaitTask(suspended.instanceId());
        assertEquals(GraphTaskStatus.OPEN, task.status());

        GraphTask claimedTask = exchange("/api/v1/tasks/" + task.taskId() + "/claim", HttpMethod.POST, Map.of(
                "userId", "reviewer"
        ), GraphTask.class).getBody();
        assertNotNull(claimedTask);
        assertEquals(GraphTaskStatus.CLAIMED, claimedTask.status());

        GraphTask completedTask = exchange("/api/v1/tasks/" + task.taskId() + "/complete", HttpMethod.POST, Map.of(
                "userId", "reviewer",
                "output", Map.of(
                        "approved", true,
                        "orderId", "approval-001"
                )
        ), GraphTask.class).getBody();
        assertNotNull(completedTask);
        assertEquals(GraphTaskStatus.COMPLETED, completedTask.status());

        GraphInstance completed = awaitInstanceStatus(startedInstanceId, GraphInstanceStatus.COMPLETED);
        assertEquals(GraphInstanceStatus.COMPLETED, completed.status());

        assertCounter("ge.version.published", 1.0,
                "definition", "approval-flow",
                "tenant", "acme",
                "namespace", "sales");
        assertCounter("ge.instance.started", 1.0,
                "definition", "approval-flow",
                "tenant", "acme",
                "namespace", "sales",
                "mode", "GRAPH");
        assertCounter("ge.task.claimed", 1.0,
                "definition", "approval-flow",
                "tenant", "acme",
                "namespace", "sales",
                "node", "approval");
        assertCounter("ge.task.completed", 1.0,
                "definition", "approval-flow",
                "tenant", "acme",
                "namespace", "sales",
                "node", "approval");
        assertCounter("ge.instance.completed", 1.0,
                "definition", "approval-flow",
                "tenant", "acme",
                "namespace", "sales",
                "mode", "GRAPH",
                "status", "COMPLETED");
    }

    @Test
    void httpApiCancelsSuspendedInstanceAndExposesTransitionHistory() throws Exception {
        GraphDefinition definition = exchange("/api/v1/graphs", HttpMethod.POST, Map.of(
                "definitionKey", "approval-cancel-flow",
                "displayName", "Approval Cancel Flow"
        ), GraphDefinition.class).getBody();
        assertNotNull(definition);

        GraphVersion version = exchange("/api/v1/graphs/approval-cancel-flow/versions", HttpMethod.POST, Map.of(
                "version", "1.0.0",
                "dslSource", APPROVAL_DSL
        ), GraphVersion.class).getBody();
        assertNotNull(version);

        Map<String, Object> published = exchangeMap("/api/v1/graphs/approval-cancel-flow/versions/1.0.0/publish", HttpMethod.POST, Map.of(
                "expectedRevision", version.revision()
        ));
        assertNotNull(published);
        assertEquals("PUBLISHED", nestedMap(published, "version").get("status"));

        Map<String, Object> started = exchangeMap("/api/v1/graphs/approval-cancel-flow/instances", HttpMethod.POST, Map.of(
                "businessKey", "approval-cancel-001",
                "initiator", "starter",
                "variables", Map.of("orderId", "approval-cancel-001")
        ));
        assertNotNull(started);

        String startedInstanceId = stringValue(nestedMap(started, "instance").get("instanceId"));
        GraphInstance suspended = awaitInstanceStatus(startedInstanceId, GraphInstanceStatus.SUSPENDED);

        GraphInstance cancelled = exchange("/api/v1/instances/" + suspended.instanceId() + "/cancel", HttpMethod.POST, Map.of(
                "expectedRevision", suspended.revision(),
                "reason", "duplicate submission"
        ), GraphInstance.class).getBody();
        assertNotNull(cancelled);
        assertEquals(GraphInstanceStatus.CANCELLED, cancelled.status());

        GraphInstance refreshed = awaitInstanceStatus(suspended.instanceId(), GraphInstanceStatus.CANCELLED);
        assertEquals(GraphInstanceStatus.CANCELLED, refreshed.status());
        assertCounter("ge.instance.completed", 1.0,
                "definition", "approval-cancel-flow",
                "tenant", "acme",
                "namespace", "sales",
                "mode", "GRAPH",
                "status", "CANCELLED");

        ResponseEntity<GraphTask[]> taskResponse = restTemplate.exchange(
                "/api/v1/tasks?executionId={executionId}",
                HttpMethod.GET,
                new HttpEntity<>(tenantHeaders()),
                GraphTask[].class,
                suspended.instanceId()
        );
        GraphTask[] tasks = taskResponse.getBody();
        assertNotNull(tasks);
        assertEquals(1, tasks.length);
        assertEquals(GraphTaskStatus.CANCELLED, tasks[0].status());

        ResponseEntity<GraphTransitionEntry[]> transitionsResponse = restTemplate.exchange(
                "/api/v1/instances/{instanceId}/transitions",
                HttpMethod.GET,
                new HttpEntity<>(tenantHeaders()),
                GraphTransitionEntry[].class,
                suspended.instanceId()
        );
        GraphTransitionEntry[] transitions = transitionsResponse.getBody();
        assertNotNull(transitions);
        assertTrue(transitions.length >= 1);
        assertTrue(java.util.Arrays.stream(transitions)
                .anyMatch(entry -> entry.toStatus() == GraphInstanceStatus.CANCELLED));
    }

    @Test
    void httpApiReturnsVersionDiagramUsingSemanticVersionRoute() {
        GraphDefinition definition = exchange("/api/v1/graphs", HttpMethod.POST, Map.of(
                "definitionKey", "approval-diagram-flow",
                "displayName", "Approval Diagram Flow"
        ), GraphDefinition.class).getBody();
        assertNotNull(definition);

        GraphVersion version = exchange("/api/v1/graphs/approval-diagram-flow/versions", HttpMethod.POST, Map.of(
                "version", "1.0.0",
                "dslSource", APPROVAL_DSL,
                "visualLayout", "{\"nodes\":[{\"id\":\"approval\"}]}"
        ), GraphVersion.class).getBody();
        assertNotNull(version);

        GraphVersionDiagram diagram = restTemplate.exchange(
                "/api/v1/graphs/approval-diagram-flow/versions/1.0.0/diagram",
                HttpMethod.GET,
                new HttpEntity<>(tenantHeaders()),
                GraphVersionDiagram.class
        ).getBody();
        assertNotNull(diagram);
        assertEquals(version.versionId(), diagram.versionId());
        assertEquals("1.0.0", diagram.version());
        assertEquals("{\"nodes\":[{\"id\":\"approval\"}]}", diagram.visualLayout());
    }

    private GraphTask awaitTask(String executionId) throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            ResponseEntity<GraphTask[]> response = restTemplate.exchange(
                    "/api/v1/tasks?executionId={executionId}",
                    HttpMethod.GET,
                    new HttpEntity<>(tenantHeaders()),
                    GraphTask[].class,
                    executionId
            );
            GraphTask[] tasks = response.getBody();
            if (tasks != null && tasks.length > 0) {
                return tasks[0];
            }
            Thread.sleep(50);
        }
        fail("Timed out waiting for task creation");
        return null;
    }

    private GraphInstance awaitInstanceStatus(String instanceId, GraphInstanceStatus expectedStatus) throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            GraphInstance instance = exchange("/api/v1/instances/" + instanceId, HttpMethod.GET, null, GraphInstance.class)
                    .getBody();
            if (instance != null && instance.status() == expectedStatus) {
                return instance;
            }
            Thread.sleep(50);
        }
        fail("Timed out waiting for instance status " + expectedStatus);
        return null;
    }

    private <T> ResponseEntity<T> exchange(String path, HttpMethod method, Object body, Class<T> responseType) {
        return restTemplate.exchange(path, method, new HttpEntity<>(body, tenantHeaders()), responseType);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchangeMap(String path, HttpMethod method, Object body) {
        return exchange(path, method, body, Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedMap(Map<String, Object> source, String key) {
        Object value = source.get(key);
        assertNotNull(value, () -> "Expected key '" + key + "' in response: " + source);
        return (Map<String, Object>) value;
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private HttpHeaders tenantHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Tenant-Id", "acme");
        headers.add("X-Namespace", "sales");
        return headers;
    }

    private void assertCounter(String name, double expectedCount, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        assertNotNull(counter, "Expected counter " + name);
        assertEquals(expectedCount, counter.count());
    }
}
