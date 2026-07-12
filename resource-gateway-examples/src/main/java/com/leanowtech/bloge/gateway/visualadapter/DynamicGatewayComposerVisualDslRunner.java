package com.leanowtech.bloge.gateway.visualadapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.example.DynamicGatewayComposerService;
import com.leanowtech.bloge.gateway.example.DynamicGraphRunRequest;
import com.leanowtech.bloge.gateway.example.DynamicGraphRunResponse;
import com.leanowtech.bloge.gateway.example.DynamicRunControlCommand;
import com.leanowtech.bloge.gateway.example.DynamicRunControlResult;
import com.leanowtech.bloge.gateway.example.DynamicRunControlView;
import com.leanowtech.bloge.gateway.example.DynamicRunIntent;
import com.leanowtech.bloge.gateway.example.ExampleVisualLayout;
import com.leanowtech.bloge.gateway.example.GatewayDecisionTable;
import com.leanowtech.bloge.gateway.visual.runtime.VisualDecisionTable;
import com.leanowtech.bloge.gateway.visual.runtime.VisualDslRunRequest;
import com.leanowtech.bloge.gateway.visual.runtime.VisualDslRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualDslRunner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualDslRunnerFactory;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunLayout;
import com.leanowtech.bloge.gateway.visual.runtime.VisualNodeExecutionAttempt;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunControlCommand;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunControlResult;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunControlView;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resource-gateway adapter from the visual runtime DSL runner port to the existing dynamic composer.
 *
 * <p>This class is intentionally outside the {@code gateway.visual} package: the generic visual
 * services depend on {@link VisualDslRunner}, while this adapter owns the resource-gateway-specific
 * {@link DynamicGatewayComposerService} dependency and DTO mapping.</p>
 */
@Component
public class DynamicGatewayComposerVisualDslRunner implements VisualDslRunner, VisualDslRunnerFactory {

    private final DynamicGatewayComposerService delegate;
    private final ObjectMapper objectMapper;

    /**
     * Creates the Spring adapter for the application-wide dynamic composer.
     *
     * @param delegate resource-gateway dynamic composer
     * @param objectMapper mapper reused when simulation creates request-scoped composer instances
     */
    @Autowired
    public DynamicGatewayComposerVisualDslRunner(DynamicGatewayComposerService delegate,
                                                ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.objectMapper = objectMapper == null ? new ObjectMapper().findAndRegisterModules() : objectMapper;
    }

    /**
     * Creates a test adapter around a supplied dynamic composer.
     *
     * @param delegate resource-gateway dynamic composer
     */
    public DynamicGatewayComposerVisualDslRunner(DynamicGatewayComposerService delegate) {
        this(delegate, new ObjectMapper().findAndRegisterModules());
    }

    /**
     * Creates a test adapter directly from an operator registry.
     *
     * @param registry executable operator registry
     */
    public DynamicGatewayComposerVisualDslRunner(OperatorRegistry registry) {
        this(new DynamicGatewayComposerService(registry));
    }

    @Override
    public VisualDslRunResponse run(VisualDslRunRequest request) {
        DynamicGraphRunResponse response = delegate.run(new DynamicGraphRunRequest(
                request.dsl(),
                request.context(),
                request.outputNode(),
                new DynamicRunIntent("", request.runIntent().requestId(), request.runIntent().deadlineAt(),
                        request.runIntent().fencingToken(), request.runIntent().cancellationGraceMs())
        ));
        return fromDynamic(response);
    }

    @Override
    public List<VisualDslRunResponse.Diagnostic> compileDiagnostics(String dsl) {
        return delegate.compileDiagnostics(dsl).stream()
                .map(DynamicGatewayComposerVisualDslRunner::diagnostic)
                .toList();
    }

    @Override
    public VisualRunControlResult runControl(String requestId, String fencingToken) {
        return controlResult(delegate.runControl(requestId, fencingToken));
    }

    @Override
    public VisualRunControlResult cancel(VisualRunControlCommand command) {
        return controlResult(delegate.cancel(new DynamicRunControlCommand(
                command.requestId(), command.fencingToken(), command.expectedRevision(), command.reason())));
    }

    @Override
    public VisualDslRunner forRegistry(OperatorRegistry registry) {
        return new DynamicGatewayComposerVisualDslRunner(
                new DynamicGatewayComposerService(registry, objectMapper),
                objectMapper
        );
    }

    private static VisualDslRunResponse fromDynamic(DynamicGraphRunResponse source) {
        return new VisualDslRunResponse(
                source.compiled(),
                source.success(),
                source.graphName(),
                source.outputNode(),
                source.output(),
                source.results(),
                source.statusMap(),
                source.elapsedMs(),
                source.nodeElapsedMs(),
                source.nodeAttempts().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        java.util.Map.Entry::getKey,
                        entry -> entry.getValue().stream().map(DynamicGatewayComposerVisualDslRunner::attempt).toList(),
                        (left, right) -> left,
                        java.util.LinkedHashMap::new
                )),
                source.nodeExecutionFacts().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        java.util.Map.Entry::getKey,
                        entry -> fact(entry.getValue()),
                        (left, right) -> left,
                        java.util.LinkedHashMap::new
                )),
                source.diagnostics().stream()
                        .map(DynamicGatewayComposerVisualDslRunner::diagnostic)
                        .toList(),
                source.errors(),
                layout(source.layout()),
                decisionTable(source.decisionTable()),
                control(source.runControl())
        );
    }

    private static VisualRunControlResult controlResult(DynamicRunControlResult source) {
        return new VisualRunControlResult(source.accepted(), source.code(), source.message(), control(source.control()));
    }

    private static VisualRunControlView control(DynamicRunControlView source) {
        if (source == null) {
            return VisualRunControlView.unmanaged();
        }
        return new VisualRunControlView("", source.requestId(), source.engineExecutionId(), source.status(),
                source.reasonCode(), source.revision(), source.deadlineAt(), source.startedAt(),
                source.cancelRequestedAt(), source.terminalAt(), source.terminationConfirmed(),
                source.sideEffectsMayBeInFlight());
    }

    private static VisualNodeExecutionAttempt attempt(DynamicGraphRunResponse.NodeAttempt source) {
        return new VisualNodeExecutionAttempt(source.attempt(), source.input(), source.output(), source.status(),
                source.startedAt(), source.elapsedMs(), source.errorType(), source.errorMessage());
    }

    private static com.leanowtech.bloge.gateway.visual.runtime.VisualNodeExecutionFact fact(
            DynamicGraphRunResponse.NodeExecutionFact source) {
        return new com.leanowtech.bloge.gateway.visual.runtime.VisualNodeExecutionFact(
                source.status(), source.reasonCode(), source.observationSource(), source.causedByNodeIds(),
                new com.leanowtech.bloge.gateway.visual.runtime.VisualNodeExecutionFact.Retry(
                        source.retry().configuredMaxAttempts(), source.retry().observedAttempts(),
                        source.retry().exhausted(), source.retry().lastErrorType()),
                new com.leanowtech.bloge.gateway.visual.runtime.VisualNodeExecutionFact.Timeout(
                        source.timeout().configured(), source.timeout().configuredTimeoutMs(),
                        source.timeout().observed()),
                new com.leanowtech.bloge.gateway.visual.runtime.VisualNodeExecutionFact.Fallback(
                        source.fallback().configured(), source.fallback().used(), source.fallback().strategy(),
                        source.fallback().originalErrorType()),
                source.sideEffectOutcome(),
                source.events().stream().map(event ->
                        new com.leanowtech.bloge.gateway.visual.runtime.VisualNodeExecutionFact.Event(
                                event.sequence(), event.type(), event.observedAt(), event.attempt(),
                                event.errorType())).toList());
    }

    private static VisualDslRunResponse.Diagnostic diagnostic(DynamicGraphRunResponse.Diagnostic source) {
        return new VisualDslRunResponse.Diagnostic(
                source.level(),
                source.message(),
                source.nodeId(),
                source.field(),
                source.line(),
                source.column()
        );
    }

    private static VisualRunLayout layout(ExampleVisualLayout source) {
        if (source == null) {
            return null;
        }
        return new VisualRunLayout(
                source.schemaVersion(),
                source.rootId(),
                source.executionMode(),
                source.nodes().stream()
                        .map(node -> new VisualRunLayout.Node(
                                node.id(),
                                node.kind(),
                                node.operatorRef(),
                                node.label(),
                                position(node.position()),
                                size(node.size()),
                                node.group(),
                                node.annotations()))
                        .toList(),
                source.edges().stream()
                        .map(edge -> new VisualRunLayout.Edge(edge.id(), edge.source(), edge.target(), edge.label()))
                        .toList(),
                source.groups().stream()
                        .map(group -> new VisualRunLayout.Group(group.id(), group.label(), group.kind()))
                        .toList(),
                viewport(source.viewport())
        );
    }

    private static VisualRunLayout.Position position(ExampleVisualLayout.Position source) {
        return source == null ? null : new VisualRunLayout.Position(source.x(), source.y());
    }

    private static VisualRunLayout.Size size(ExampleVisualLayout.Size source) {
        return source == null ? null : new VisualRunLayout.Size(source.width(), source.height());
    }

    private static VisualRunLayout.Viewport viewport(ExampleVisualLayout.Viewport source) {
        return source == null ? null : new VisualRunLayout.Viewport(source.x(), source.y(), source.zoom());
    }

    private static VisualDecisionTable decisionTable(GatewayDecisionTable source) {
        if (source == null) {
            return null;
        }
        return new VisualDecisionTable(
                source.title(),
                source.hitPolicy(),
                source.inputs().stream()
                        .map(column -> new VisualDecisionTable.Column(column.key(), column.label()))
                        .toList(),
                source.outputs().stream()
                        .map(column -> new VisualDecisionTable.Column(column.key(), column.label()))
                        .toList(),
                source.rows().stream()
                        .map(row -> new VisualDecisionTable.Row(
                                row.id(),
                                row.conditions(),
                                row.output(),
                                row.explanation()))
                        .toList()
        );
    }
}
