package com.leanowtech.bloge.graphengine.server;

import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.graphengine.model.GraphDefinition;
import com.leanowtech.bloge.graphengine.model.GraphInstance;
import com.leanowtech.bloge.graphengine.model.GraphInstanceDiagram;
import com.leanowtech.bloge.graphengine.model.GraphInstanceStatus;
import com.leanowtech.bloge.graphengine.model.GraphNodeState;
import com.leanowtech.bloge.graphengine.model.GraphNodeStatus;
import com.leanowtech.bloge.graphengine.model.PagedResult;
import com.leanowtech.bloge.graphengine.model.GraphTask;
import com.leanowtech.bloge.graphengine.model.GraphTaskStatus;
import com.leanowtech.bloge.graphengine.model.GraphVersion;
import com.leanowtech.bloge.graphengine.model.GraphVersionStatus;
import com.leanowtech.bloge.spring.annotation.BlogeOperator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Focused HTTP integration tests for instance node-state projection endpoints.
 */
@SpringBootTest(
        classes = GraphEngineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:graph-instance-nodes-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
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
@Import(GraphInstanceNodesApiIT.TestOperators.class)
class GraphInstanceNodesApiIT {

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

    private static final String SESSION_DSL = """
            session reviewSession {
              idle_timeout = 5m
              max_rounds = 4

              phase firstDecision {
                max_rounds = 3
                yield_on = [capture]
                round {
                  node capture : echo {
                    input {
                      ready = ctx.round.input.ready
                      action = ctx.round.input.action
                    }
                  }
                }
                until capture.output.ready == true
                then {
                  capture.output.action == "approve" -> approved
                  otherwise -> secondDecision
                }
              }

              phase secondDecision {
                max_rounds = 3
                yield_on = [capture]
                round {
                  node capture : echo {
                    input {
                      ready = ctx.round.input.ready
                      action = ctx.round.input.action
                    }
                  }
                }
                until capture.output.ready == true
                then {
                  capture.output.action == "approve" -> approved
                  otherwise -> rejected
                }
              }

              phase approved {
                node finalize : echo {
                  input {
                    status = "approved"
                  }
                }
              }

              phase rejected {
                node finalize : echo {
                  input {
                    status = "rejected"
                  }
                }
              }
            }
            """;

    private static final String STATE_MACHINE_DSL = """
            state_machine orderLifecycle {
              state draft [initial] {
                on submit -> review
              }

              state review {
                on approve -> approved
              }

              state approved [terminal] { }
            }
            """;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void httpApiReturnsWaitingAndCompletedNodeStatesForUserTaskFlow() throws Exception {
        GraphDefinition definition = exchange("/api/v1/graphs", HttpMethod.POST, Map.of(
                "definitionKey", "approval-node-view",
                "displayName", "Approval Node View"
        ), GraphDefinition.class).getBody();
        assertNotNull(definition);

        GraphVersion version = exchange("/api/v1/graphs/approval-node-view/versions", HttpMethod.POST, Map.of(
                "version", "1.0.0",
                "dslSource", APPROVAL_DSL,
                "visualLayout", "{\"nodes\":[{\"id\":\"approval\"}]}"
        ), GraphVersion.class).getBody();
        assertNotNull(version);

        Map<String, Object> published = exchangeMap("/api/v1/graphs/approval-node-view/versions/1.0.0/publish", HttpMethod.POST, Map.of(
                "expectedRevision", version.revision()
        ));
        assertNotNull(published);
        assertEquals("PUBLISHED", nestedMap(published, "version").get("status"));

        Map<String, Object> started = exchangeMap("/api/v1/graphs/approval-node-view/instances", HttpMethod.POST, Map.of(
                "businessKey", "approval-node-view-001",
                "initiator", "starter",
                "variables", Map.of("orderId", "approval-node-view-001")
        ));
        assertNotNull(started);

        String startedInstanceId = stringValue(nestedMap(started, "instance").get("instanceId"));
        GraphInstance suspended = awaitInstanceStatus(startedInstanceId, GraphInstanceStatus.SUSPENDED);
        PagedResult<GraphNodeState> suspendedNodes = exchangeNodePage(
                "/api/v1/instances/{instanceId}/nodes",
                suspended.instanceId()
        );
        assertNotNull(suspendedNodes);
        assertEquals(0, suspendedNodes.page());
        assertEquals(50, suspendedNodes.size());
        assertEquals(1, suspendedNodes.total());
        assertEquals(1, suspendedNodes.items().size());
        assertEquals("approval", suspendedNodes.items().getFirst().nodeId());
        assertEquals(GraphNodeStatus.WAITING, suspendedNodes.items().getFirst().status());
        assertEquals("WAIT_SIGNAL", suspendedNodes.items().getFirst().waitType());

        PagedResult<GraphNodeState> waitingNodes = exchangeNodePage(
                "/api/v1/instances/{instanceId}/nodes?status=WAITING&page=0&size=1",
                suspended.instanceId()
        );
        assertNotNull(waitingNodes);
        assertEquals(0, waitingNodes.page());
        assertEquals(1, waitingNodes.size());
        assertEquals(1, waitingNodes.total());
        assertEquals(1, waitingNodes.items().size());
        assertEquals(GraphNodeStatus.WAITING, waitingNodes.items().getFirst().status());

        GraphInstanceDiagram suspendedDiagram = restTemplate.exchange(
                "/api/v1/instances/{instanceId}/diagram",
                HttpMethod.GET,
                new HttpEntity<>(tenantHeaders()),
                GraphInstanceDiagram.class,
                suspended.instanceId()
        ).getBody();
        assertNotNull(suspendedDiagram);
        assertEquals("{\"nodes\":[{\"id\":\"approval\"}]}", suspendedDiagram.visualLayout());
        assertEquals(1, suspendedDiagram.nodeStates().size());
        assertEquals(GraphNodeStatus.WAITING, suspendedDiagram.nodeStates().getFirst().status());

        GraphTask task = awaitTask(suspended.instanceId());
        assertEquals(GraphTaskStatus.OPEN, task.status());

        GraphTask completedTask = exchange("/api/v1/tasks/" + task.taskId() + "/complete", HttpMethod.POST, Map.of(
                "userId", "reviewer",
                "output", Map.of(
                        "approved", true,
                        "orderId", "approval-node-view-001"
                )
        ), GraphTask.class).getBody();
        assertNotNull(completedTask);
        assertEquals(GraphTaskStatus.COMPLETED, completedTask.status());

        GraphInstance completed = awaitInstanceStatus(startedInstanceId, GraphInstanceStatus.COMPLETED);
        PagedResult<GraphNodeState> completedNodes = exchangeNodePage(
                "/api/v1/instances/{instanceId}/nodes",
                completed.instanceId()
        );
        assertNotNull(completedNodes);
        assertEquals(1, completedNodes.items().size());
        assertEquals(GraphNodeStatus.COMPLETED, completedNodes.items().getFirst().status());
        assertNotNull(completedNodes.items().getFirst().completedAt());
    }

    @Test
    void httpApiReturnsSessionPhaseNodesAfterSignalAdvancesPhase() throws Exception {
        exchange("/api/v1/graphs", HttpMethod.POST, Map.of(
                "definitionKey", "session-node-view",
                "displayName", "Session Node View"
        ), GraphDefinition.class);

        GraphVersion version = exchange("/api/v1/graphs/session-node-view/versions", HttpMethod.POST, Map.of(
                "version", "1.0.0",
                "dslSource", SESSION_DSL
        ), GraphVersion.class).getBody();
        assertNotNull(version);
        Map<String, Object> published = exchangeMap("/api/v1/graphs/session-node-view/versions/1.0.0/publish", HttpMethod.POST, Map.of(
                "expectedRevision", version.revision()
        ));
        assertNotNull(published);
        assertEquals("PUBLISHED", nestedMap(published, "version").get("status"));

        Map<String, Object> started = exchangeMap("/api/v1/graphs/session-node-view/instances", HttpMethod.POST, Map.of(
                "businessKey", "session-node-view-001",
                "initiator", "starter",
                "variables", Map.of("orderId", "session-node-view-001")
        ));
        assertNotNull(started);

        String startedInstanceId = stringValue(nestedMap(started, "instance").get("instanceId"));
        GraphInstance suspended = awaitInstanceStatus(startedInstanceId, GraphInstanceStatus.SUSPENDED);
        exchange("/api/v1/instances/" + suspended.instanceId() + "/signal", HttpMethod.POST, Map.of(
                "payload", Map.of("ready", true, "action", "review"),
                "callerId", "starter"
        ), Object.class);
        awaitInstanceStatus(suspended.instanceId(), GraphInstanceStatus.SUSPENDED);

        PagedResult<GraphNodeState> nodes = exchangeNodePage(
                "/api/v1/instances/{instanceId}/nodes",
                suspended.instanceId()
        );
        assertNotNull(nodes);
        assertEquals(4, nodes.total());
        assertEquals(4, nodes.items().size());
        assertEquals("firstDecision", nodes.items().get(0).nodeId());
        assertEquals(GraphNodeStatus.COMPLETED, nodes.items().get(0).status());
        assertNotNull(nodes.items().get(0).completedAt());
        assertEquals("secondDecision", nodes.items().get(1).nodeId());
        assertEquals(GraphNodeStatus.WAITING, nodes.items().get(1).status());
        assertNull(nodes.items().get(1).waitType());
        assertEquals(GraphNodeStatus.NOT_STARTED, nodes.items().get(2).status());
        assertEquals(GraphNodeStatus.NOT_STARTED, nodes.items().get(3).status());
    }

    @Test
    void httpApiReturnsStateMachineNodesAfterEventTransition() throws Exception {
        exchange("/api/v1/graphs", HttpMethod.POST, Map.of(
                "definitionKey", "state-node-view",
                "displayName", "State Node View"
        ), GraphDefinition.class);

        GraphVersion version = exchange("/api/v1/graphs/state-node-view/versions", HttpMethod.POST, Map.of(
                "version", "1.0.0",
                "dslSource", STATE_MACHINE_DSL
        ), GraphVersion.class).getBody();
        assertNotNull(version);
        Map<String, Object> published = exchangeMap("/api/v1/graphs/state-node-view/versions/1.0.0/publish", HttpMethod.POST, Map.of(
                "expectedRevision", version.revision()
        ));
        assertNotNull(published);
        assertEquals("PUBLISHED", nestedMap(published, "version").get("status"));

        Map<String, Object> started = exchangeMap("/api/v1/graphs/state-node-view/instances", HttpMethod.POST, Map.of(
                "businessKey", "state-node-view-001",
                "initiator", "starter",
                "variables", Map.of("orderId", "state-node-view-001")
        ));
        assertNotNull(started);

        String startedInstanceId = stringValue(nestedMap(started, "instance").get("instanceId"));
        GraphInstance running = awaitInstanceStatus(startedInstanceId, GraphInstanceStatus.SUSPENDED);
        exchange("/api/v1/instances/" + running.instanceId() + "/signal", HttpMethod.POST, Map.of(
                "eventName", "submit",
                "payload", Map.of(),
                "callerId", "starter"
        ), Object.class);
        awaitInstanceStatus(running.instanceId(), GraphInstanceStatus.SUSPENDED);

        PagedResult<GraphNodeState> nodes = exchangeNodePage(
                "/api/v1/instances/{instanceId}/nodes",
                running.instanceId()
        );
        assertNotNull(nodes);
        assertEquals(3, nodes.total());
        assertEquals(3, nodes.items().size());
        assertEquals("draft", nodes.items().get(0).nodeId());
        assertEquals(GraphNodeStatus.COMPLETED, nodes.items().get(0).status());
        assertEquals("review", nodes.items().get(1).nodeId());
        assertEquals(GraphNodeStatus.WAITING, nodes.items().get(1).status());
        assertNull(nodes.items().get(1).waitType());
        assertEquals(GraphNodeStatus.NOT_STARTED, nodes.items().get(2).status());
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

    private PagedResult<GraphNodeState> exchangeNodePage(String url, Object... uriVariables) {
        ResponseEntity<PagedResult<GraphNodeState>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(tenantHeaders()),
                new ParameterizedTypeReference<>() {
                },
                uriVariables
        );
        return response.getBody();
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

    @TestConfiguration(proxyBeanMethods = false)
    static class TestOperators {

        @Bean
        EchoOperator echoOperator() {
            return new EchoOperator();
        }
    }

    @BlogeOperator("echo")
    static final class EchoOperator implements Operator<Object, Object> {
        @Override
        public Object execute(Object input, com.leanowtech.bloge.core.operator.OperatorContext ctx) {
            return input;
        }
    }
}
