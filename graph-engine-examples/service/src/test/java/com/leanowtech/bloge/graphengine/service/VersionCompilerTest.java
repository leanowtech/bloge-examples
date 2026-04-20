package com.leanowtech.bloge.graphengine.service;

import com.leanowtech.bloge.core.checkpoint.TaskStore;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.RemoteWorkerOperator;
import com.leanowtech.bloge.core.runtime.registry.GraphDefinitionHasher;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.durable.TaskInboxTaskStore;
import com.leanowtech.bloge.durable.UserTaskOperator;
import com.leanowtech.bloge.durable.store.memory.InMemoryWorkItemStore;
import com.leanowtech.bloge.durable.store.memory.InMemoryTaskInboxStore;
import com.leanowtech.bloge.graphengine.model.GraphDefinition;
import com.leanowtech.bloge.graphengine.model.GraphDefinitionStatus;
import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;
import com.leanowtech.bloge.graphengine.model.GraphVersion;
import com.leanowtech.bloge.graphengine.model.GraphVersionMetadata;
import com.leanowtech.bloge.graphengine.model.GraphVersionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionCompilerTest {

    @Test
    void compileGraphRenamesArtifactAndCapturesTaskMetadata() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        TaskStore taskStore = new TaskInboxTaskStore(new InMemoryTaskInboxStore());
        registry.register("echo", (Operator<Object, Object>) (input, ctx) -> input);
        UserTaskOperator userTaskOperator = new UserTaskOperator(taskStore, "user-task", null, List.of("ops"));
        registry.registerRaw("user", userTaskOperator);
        registry.registerRaw("user-task", userTaskOperator);

        VersionCompiler compiler = new VersionCompiler(GraphEngineRuntimeSupport.builder()
                .operatorRegistry(registry)
                .build());

        GraphDefinition definition = definition("orders");
        GraphVersion version = version(
                definition,
                "version-graph",
                "1.0.0",
                        """
                        graph approvalFlow {
                          node approval : user {
                            input {
                              title = "Approve order"
                            }
                          }

                          node done : echo {
                            depends_on = [approval]
                            input {
                              approved = approval.output.approved
                            }
                          }
                        }
                        """
        );

        VersionCompileResult result = compiler.compile(definition, version);

        assertTrue(result.valid());
        assertEquals(GraphExecutionMode.GRAPH, result.executionMode());
        assertEquals("approvalFlow", result.declaredRootName());
        assertEquals(RuntimeArtifactNames.graphRuntimeName(definition), result.runtimeName());
        assertNotNull(result.graph());
        assertEquals(result.runtimeName(), result.graph().name());
        assertTrue(result.metadata().operatorRefs().contains("echo"));
        assertTrue(result.metadata().operatorRefs().contains("user"));
        assertTrue(result.metadata().taskDefinitions().containsKey("approval"));
        assertEquals("user-task", result.metadata().taskDefinitions().get("approval").taskType());
    }

    @Test
    void compileSessionUsesVersionPinnedRuntimeName() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.register("echo", (Operator<Object, Object>) (input, ctx) -> input);

        VersionCompiler compiler = new VersionCompiler(GraphEngineRuntimeSupport.builder()
                .operatorRegistry(registry)
                .build());

        GraphDefinition definition = definition("support-flow");
        GraphVersion version = version(
                definition,
                "version-session",
                "1.0.0",
                """
                        session supportFlow {
                          idle_timeout = 5m
                          max_rounds = 4
                          max_history = 10

                          phase intake {
                            node greet : echo {
                              input {
                                orderId = ctx.orderId
                              }
                            }
                            then -> done
                          }

                          phase done {
                            node finish : echo {
                              input {
                                previous = ctx.intake.output.greet.orderId
                              }
                            }
                          }
                        }
                        """
        );

        VersionCompileResult result = compiler.compile(definition, version);

        assertTrue(result.valid());
        assertEquals(GraphExecutionMode.SESSION, result.executionMode());
        assertEquals("supportFlow", result.declaredRootName());
        assertEquals(RuntimeArtifactNames.sessionRuntimeName(version.versionId()), result.runtimeName());
        assertNotNull(result.sessionGraph());
        assertEquals(result.runtimeName(), result.sessionGraph().name());
    }

    @Test
    void compileStateMachineUsesVersionPinnedRuntimeName() {
        VersionCompiler compiler = new VersionCompiler(GraphEngineRuntimeSupport.builder().build());
        GraphDefinition definition = definition("state-flow");
        GraphVersion version = version(
                definition,
                "version-state",
                "1.0.0",
                """
                        state_machine orderLifecycle {
                          max_transitions = 20
                          max_state_visits = 5

                          state draft [initial] {
                            on submit -> completed
                          }

                          state completed [terminal] { }
                        }
                        """
        );

        VersionCompileResult result = compiler.compile(definition, version);

        assertTrue(result.valid());
        assertEquals(GraphExecutionMode.STATE_MACHINE, result.executionMode());
        assertEquals("orderLifecycle", result.declaredRootName());
        assertEquals(RuntimeArtifactNames.stateMachineRuntimeName(version.versionId()), result.runtimeName());
        assertNotNull(result.stateMachine());
        assertEquals(result.runtimeName(), result.stateMachine().name());
    }

    @Test
    void compileInvalidSourceReturnsBlockingDiagnostic() {
        VersionCompiler compiler = new VersionCompiler(GraphEngineRuntimeSupport.builder().build());
        GraphDefinition definition = definition("broken");
        GraphVersion version = version(definition, "version-broken", "1.0.0", "graph {");

        VersionCompileResult result = compiler.compile(definition, version);

        assertFalse(result.valid());
        assertNull(result.graph());
        assertTrue(result.diagnostics().stream().anyMatch(GraphEngineDiagnostic::error));
    }

    @Test
    void compileCachesImmutableVersionResultsUntilInvalidated() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.register("echo", (Operator<Object, Object>) (input, ctx) -> input);
        VersionCompiler compiler = new VersionCompiler(GraphEngineRuntimeSupport.builder()
                .operatorRegistry(registry)
                .build());
        GraphDefinition definition = definition("cached");
        GraphVersion version = version(
                definition,
                "version-cached",
                "1.0.0",
                """
                        graph cachedFlow {
                          node only : echo {
                            input {
                              orderId = ctx.orderId
                            }
                          }
                        }
                        """
        );

        VersionCompileResult first = compiler.compile(definition, version);
        VersionCompileResult second = compiler.compile(definition, version);

        assertSame(first, second);
        assertEquals(1L, compiler.cacheEntryCount());

        compiler.invalidate(version);

        VersionCompileResult recompiled = compiler.compile(definition, version);
        assertNotSame(first, recompiled);
        assertEquals(1L, compiler.cacheEntryCount());
    }

    @Test
    void compileRemoteGraphUsesDurableRemoteWorkerBridge() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        VersionCompiler compiler = new VersionCompiler(GraphEngineRuntimeSupport.builder()
                .operatorRegistry(registry)
                .workItemStore(new InMemoryWorkItemStore())
                .build());

        GraphDefinition definition = definition("remote-dispatch");
        GraphVersion version = version(
                definition,
                "version-remote",
                "1.0.0",
                """
                        graph remoteDispatch {
                          node classify : supportClassifier {
                            execution_mode = remote
                            worker_topic = "workers.ai"
                          }
                        }
                        """
        );

        VersionCompileResult result = compiler.compile(definition, version);

        assertTrue(result.valid());
        assertNotNull(result.graph());
        assertTrue(result.metadata().operatorRefs().contains("supportClassifier"));
        assertEquals("remote", result.graph().nodes().get("classify").metadata().attributes().get("execution_mode"));
        assertEquals("workers.ai", result.graph().nodes().get("classify").metadata().attributes().get("worker_topic"));
        assertInstanceOf(RemoteWorkerOperator.class, result.graph().embeddedOperators().get("classify"));
    }

    private static GraphDefinition definition(String key) {
        return new GraphDefinition(
                "definition-" + key,
                key,
                "tenant-a",
                "sales",
                "Definition " + key,
                null,
                null,
                Map.of(),
                null,
                null,
                GraphDefinitionStatus.ACTIVE,
                0,
                null,
                null
        );
    }

    private static GraphVersion version(GraphDefinition definition, String versionId, String version, String source) {
        return new GraphVersion(
                versionId,
                definition.definitionId(),
                version,
                GraphDefinitionHasher.sha256Hex(source),
                source,
                null,
                new GraphVersionMetadata(null, null, null, null, null, null, null),
                null,
                null,
                GraphVersionStatus.DRAFT,
                0,
                null,
                null,
                null
        );
    }
}
