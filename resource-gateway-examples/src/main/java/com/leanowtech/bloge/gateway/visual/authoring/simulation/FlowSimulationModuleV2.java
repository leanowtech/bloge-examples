package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.StoredApiResource;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Executes caller-directed Fixtures against an exact reusable Flow DAG.
 *
 * <p>Every node receives a fresh server-generated Invocation Key. Mappings run before dynamic
 * condition selection, so repeated or nested invocations may select different Cases from the same
 * pinned Fixture revision. A Fixture on a nested Flow node replaces that whole Flow and suppresses
 * all descendant invocations; without such a Fixture the child Flow expands locally. Unmatched API
 * Resources remain blocked unless a later governed real-read runtime is explicitly installed.</p>
 */
public final class FlowSimulationModuleV2 {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final ApiResourceCommitStore resources;
    private final FlowFixturePlanCompilerV2 plans;
    private final FixtureAssetSimulationResolver protectedFixtures;
    private final SimulationReplayResolver replays;
    private final SimulationFixtureUsageRecorder usage;
    private final SimulationRunV2Store runs;
    private final Clock clock;
    private final Supplier<String> runIds;
    private final Supplier<String> invocationIds;

    /** Creates the Flow runtime with optional protected-material and replay authorities. */
    public FlowSimulationModuleV2(ApiResourceCommitStore resources, FlowFixturePlanCompilerV2 plans,
                                  FixtureAssetSimulationResolver protectedFixtures,
                                  SimulationReplayResolver replays,
                                  SimulationFixtureUsageRecorder usage,
                                  SimulationRunV2Store runs) {
        this(resources, plans, protectedFixtures, replays, usage, runs, Clock.systemUTC(),
                () -> "sim-" + UUID.randomUUID(), () -> "inv-" + UUID.randomUUID());
    }

    /** Deterministic identity/time seam used only by package behavior tests. */
    FlowSimulationModuleV2(ApiResourceCommitStore resources, FlowFixturePlanCompilerV2 plans,
                           FixtureAssetSimulationResolver protectedFixtures,
                           SimulationReplayResolver replays, SimulationFixtureUsageRecorder usage,
                           SimulationRunV2Store runs, Clock clock, Supplier<String> runIds,
                           Supplier<String> invocationIds) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.protectedFixtures = protectedFixtures;
        this.replays = replays;
        this.usage = usage == null ? SimulationFixtureUsageRecorder.none() : usage;
        this.runs = Objects.requireNonNull(runs, "runs");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runIds = Objects.requireNonNull(runIds, "runIds");
        this.invocationIds = Objects.requireNonNull(invocationIds, "invocationIds");
    }

    /** Executes one exact Flow command and commits its immutable invocation evidence. */
    public SimulationExecutionResultV2 execute(AuthoringScope scope, String idempotencyKey,
                                               SimulationCommandV2 command,
                                               SimulationIdentity identity) {
        ResolvedFlowSimulationPlanV2 plan;
        try {
            plan = plans.compile(scope, command);
        } catch (FixturePlanFailure failure) {
            throw new SimulationFailure(map(failure.code()));
        }
        String requestFingerprint = AuthoringFingerprints.of(JSON.valueToTree(command));
        Instant startedAt = clock.instant();
        SimulationRunV2Store.Claim claim = runs.claim(
                scope, idempotencyKey, requestFingerprint, runIds, startedAt);
        if (claim instanceof SimulationRunV2Store.Claim.Replay replay) {
            recordUsage(scope, replay.run());
            return new SimulationExecutionResultV2(replay.run(), true);
        }
        if (claim instanceof SimulationRunV2Store.Claim.Conflict) {
            throw new SimulationFailure(SimulationFailure.Code.CONFLICT);
        }
        if (claim instanceof SimulationRunV2Store.Claim.Busy) {
            throw new SimulationFailure(SimulationFailure.Code.BUSY);
        }
        String runId = ((SimulationRunV2Store.Claim.Acquired) claim).runId();
        SimulationRunV2 completed = run(scope, runId, requestFingerprint, startedAt, plan, identity);
        SimulationRunV2 committed = runs.complete(scope, idempotencyKey, requestFingerprint, completed);
        recordUsage(scope, committed);
        return new SimulationExecutionResultV2(committed, false);
    }

    /** Reads one immutable Flow run inside the exact trusted scope. */
    public Optional<SimulationRunV2> read(AuthoringScope scope, String runId) {
        return runs.find(scope, runId).filter(run -> run.subject() instanceof ExactFixtureSubjectRefV2.FlowDraft
                || run.subject() instanceof ExactFixtureSubjectRefV2.FlowVersion);
    }

    private SimulationRunV2 run(AuthoringScope scope, String runId, String requestFingerprint,
                                Instant startedAt, ResolvedFlowSimulationPlanV2 plan,
                                SimulationIdentity identity) {
        List<VisualDiagnostic> inputProblems = validate(plan.inputContract(), plan.input(), "/input");
        if (!inputProblems.isEmpty()) return contractFailure(
                runId, requestFingerprint, startedAt, plan, "FLOW_INPUT_INVALID");
        State state = new State();
        JsonNode output = executeGraph(scope, plan, plan.graph(), List.of(), plan.input(), null, state, identity);
        if (state.blocked) return result(runId, requestFingerprint, startedAt, plan,
                SimulationRunV2.Status.BLOCKED, null, state,
                SimulationRunV2.ExecutionVerdict.BLOCKED, SimulationRunV2.ContractVerdict.NOT_CHECKED);
        if (state.failed) return result(runId, requestFingerprint, startedAt, plan,
                SimulationRunV2.Status.FAILED, null, state,
                SimulationRunV2.ExecutionVerdict.FAILED, SimulationRunV2.ContractVerdict.NOT_CHECKED);
        if (!validate(plan.outputContract(), output, "/output").isEmpty()) {
            state.diagnostics.add(new SimulationRunV2.Diagnostic(
                    "FLOW_OUTPUT_INVALID", "Flow output does not satisfy its exact contract."));
            return result(runId, requestFingerprint, startedAt, plan,
                    SimulationRunV2.Status.FAILED, null, state,
                    SimulationRunV2.ExecutionVerdict.FAILED, SimulationRunV2.ContractVerdict.INVALID);
        }
        return result(runId, requestFingerprint, startedAt, plan,
                SimulationRunV2.Status.SUCCEEDED, output, state,
                SimulationRunV2.ExecutionVerdict.PASSED, SimulationRunV2.ContractVerdict.VALID);
    }

    private JsonNode executeGraph(
            AuthoringScope scope, ResolvedFlowSimulationPlanV2 plan, ReusableFlowCommand.Graph graph,
            List<String> prefix, JsonNode flowInput, String parentInvocationKey, State state,
            SimulationIdentity identity) {
        Map<String, JsonNode> localOutputs = new LinkedHashMap<>();
        for (ReusableFlowCommand.Node authored : graph.nodes()) {
            List<String> path = append(prefix, authored.nodeId());
            ResolvedFlowSimulationPlanV2.Node node = plan.nodes().get(path);
            JsonNode invocationInput = mapInput(authored, flowInput, localOutputs);
            String invocationKey = invocationIds.get();
            StoredApiResource resource = resource(scope, node.subject());
            SchemaEnvelope nodeInput = resource == null ? node.contract().input()
                    : resource.resource().contract().input();
            if (!validate(nodeInput, invocationInput, "/input").isEmpty()) {
                state.failed = true;
                state.diagnostics.add(new SimulationRunV2.Diagnostic(
                        "NODE_INPUT_INVALID", "Node input does not satisfy its exact contract."));
                return null;
            }
            ResolvedFlowSimulationPlanV2.Binding binding = plan.bindings().get(path);
            JsonNode output;
            if (binding != null) {
                ResolvedFixturePlan.Selection selected;
                try {
                    selected = plans.resolveInvocation(scope, node, binding, invocationInput);
                    selected = unwrapAppliedCase(scope, node, binding.target(), selected, invocationInput);
                    if (selected.control().behavior() instanceof FixtureSetCommand.Behavior.Real) {
                        state.blocked = true;
                        state.diagnostics.add(new SimulationRunV2.Diagnostic(
                                "REAL_EXECUTION_UNAVAILABLE", "Real API execution is not authorized."));
                        state.invocations.add(realInvocation(invocationKey, parentInvocationKey,
                                binding.target(), node.subject(), SimulationRunV2.InvocationStatus.BLOCKED,
                                invocationInput, null));
                        return null;
                    }
                    output = mock(scope, node, selected, invocationInput, invocationKey,
                            parentInvocationKey, state, identity);
                } catch (FixturePlanFailure failure) {
                    state.blocked = true;
                    state.diagnostics.add(new SimulationRunV2.Diagnostic(
                            map(failure.code()).name(), "Fixture selection could not be resolved."));
                    state.invocations.add(realInvocation(invocationKey, parentInvocationKey,
                            binding.target(), node.subject(), SimulationRunV2.InvocationStatus.BLOCKED,
                            invocationInput, null));
                    return null;
                }
            } else if (node.childGraph() != null) {
                SimulationCommandV2.FixtureTarget target =
                        new SimulationCommandV2.FixtureTarget.NodePath(path);
                state.invocations.add(realInvocation(invocationKey, parentInvocationKey, target,
                        node.subject(), SimulationRunV2.InvocationStatus.COMPLETED, invocationInput, null));
                output = executeGraph(scope, plan, node.childGraph(), path, invocationInput,
                        invocationKey, state, identity);
            } else {
                SimulationCommandV2.FixtureTarget target =
                        new SimulationCommandV2.FixtureTarget.NodePath(path);
                state.blocked = true;
                state.diagnostics.add(new SimulationRunV2.Diagnostic(
                        "UNMATCHED_EXTERNAL_INVOCATION", "External API invocation is not Fixture controlled."));
                state.invocations.add(realInvocation(invocationKey, parentInvocationKey, target,
                        node.subject(), SimulationRunV2.InvocationStatus.BLOCKED, invocationInput, null));
                return null;
            }
            if (state.blocked || state.failed) return null;
            SchemaEnvelope nodeOutput = resource == null ? node.contract().output()
                    : resource.resource().contract().output();
            if (!validate(nodeOutput, output, "/output").isEmpty()) {
                state.failed = true;
                state.diagnostics.add(new SimulationRunV2.Diagnostic(
                        "NODE_OUTPUT_INVALID", "Node output does not satisfy its exact contract."));
                return null;
            }
            localOutputs.put(authored.nodeId(), output);
        }
        JsonNode source = localOutputs.get(graph.output().nodeId());
        return source == null ? null : at(source, graph.output().path());
    }

    private ResolvedFixturePlan.Selection unwrapAppliedCase(
            AuthoringScope scope, ResolvedFlowSimulationPlanV2.Node node,
            SimulationCommandV2.FixtureTarget target, ResolvedFixturePlan.Selection selected,
            JsonNode invocationInput) {
        ResolvedFixturePlan.Selection current = selected;
        for (int depth = 0; depth < 8
                && current.control().behavior() instanceof FixtureSetCommand.Behavior.ApplyCase applied; depth++) {
            current = plans.resolveAppliedCase(scope, node, target, applied, invocationInput);
        }
        if (current.control().behavior() instanceof FixtureSetCommand.Behavior.ApplyCase) {
            throw new FixturePlanFailure(FixturePlanFailure.Code.INTEGRITY);
        }
        return current;
    }

    private JsonNode mock(
            AuthoringScope scope, ResolvedFlowSimulationPlanV2.Node node,
            ResolvedFixturePlan.Selection selected, JsonNode input, String invocationKey,
            String parentInvocationKey, State state, SimulationIdentity identity) {
        FixtureSetCommand.Behavior behavior = selected.control().behavior();
        if (selected.control().fidelity() != null
                && selected.control().fidelity() != FixtureSetCommand.Fidelity.OUTPUT_LEVEL) {
            state.blocked = true;
            state.diagnostics.add(new SimulationRunV2.Diagnostic(
                    "FIXTURE_FIDELITY_UNSUPPORTED", "Flow nodes support output-level Fixture simulation only."));
            state.invocations.add(mockInvocation(invocationKey, parentInvocationKey, node, selected,
                    SimulationRunV2.InvocationStatus.BLOCKED, input, null, null));
            return null;
        }
        JsonNode output = null;
        SimulationRunV2.Provenance provenance = SimulationRunV2.Provenance.PINNED_PRIVATE;
        SimulationRunV2.FixtureAssetRef assetRef = null;
        if (behavior instanceof FixtureSetCommand.Behavior.Return returned) {
            if (returned.material() instanceof FixtureSetCommand.Material.Inline inline
                    && selected.fixtureStatus() == FixtureSetView.Status.PRIVATE_DRAFT) {
                output = inline.value();
            } else if (returned.material() instanceof FixtureSetCommand.Material.FixtureAsset asset
                    && selected.fixtureStatus() == FixtureSetView.Status.TEAM_AVAILABLE
                    && protectedFixtures != null && identity != null) {
                try {
                    output = protectedFixtures.resolve(identity, asset);
                    provenance = SimulationRunV2.Provenance.GOVERNED_ASSET;
                    assetRef = new SimulationRunV2.FixtureAssetRef(
                            asset.fixtureAssetId(), asset.revision(), asset.schemaFingerprint());
                    state.governed = output != null;
                } catch (RuntimeException unavailable) {
                    output = null;
                }
            }
        } else if (behavior instanceof FixtureSetCommand.Behavior.Replay replay && replays != null) {
            try {
                output = replays.resolve(scope, replay.replayId(), replay.fingerprint());
                provenance = SimulationRunV2.Provenance.REPLAY;
            } catch (RuntimeException unavailable) {
                output = null;
            }
        } else if (behavior instanceof FixtureSetCommand.Behavior.Error) {
            state.failed = true;
            state.diagnostics.add(new SimulationRunV2.Diagnostic("FIXTURE_ERROR", "Fixture configured an error."));
        } else if (behavior instanceof FixtureSetCommand.Behavior.Timeout) {
            state.failed = true;
            state.diagnostics.add(new SimulationRunV2.Diagnostic(
                    "FIXTURE_TIMEOUT", "Fixture configured a timeout."));
        }
        if (output == null && !state.failed) {
            state.blocked = true;
            state.diagnostics.add(new SimulationRunV2.Diagnostic(
                    "FIXTURE_MATERIAL_UNAVAILABLE", "Fixture material is unavailable."));
        }
        if (selected.expect() != null && output != null) {
            state.assertionsChecked = true;
            if (!selected.expect().output().equals(output)) {
                state.failed = true;
                state.assertionFailed = true;
                state.diagnostics.add(new SimulationRunV2.Diagnostic(
                        "FIXTURE_ASSERTION_FAILED", "Fixture output did not satisfy the saved expectation."));
            }
        }
        SimulationRunV2.InvocationStatus status = state.blocked
                ? SimulationRunV2.InvocationStatus.BLOCKED : state.failed
                ? SimulationRunV2.InvocationStatus.FAILED : SimulationRunV2.InvocationStatus.COMPLETED;
        state.invocations.add(mockInvocation(invocationKey, parentInvocationKey, node, selected,
                status, input, output, assetRef));
        return output;
    }

    private static SimulationRunV2.Invocation mockInvocation(
            String key, String parent, ResolvedFlowSimulationPlanV2.Node node,
            ResolvedFixturePlan.Selection selected, SimulationRunV2.InvocationStatus status,
            JsonNode input, JsonNode output, SimulationRunV2.FixtureAssetRef assetRef) {
        return new SimulationRunV2.Invocation(key, parent, selected.target(), node.subject(), status,
                SimulationRunV2.Execution.MOCKED, matchedBy(selected.matchedBy()),
                new SimulationRunV2.FixtureCase(selected.fixtureSet().fixtureSetId(),
                        selected.fixtureSet().revision(), selected.fixtureSet().fingerprint(), selected.caseId()),
                behavior(selected.control().behavior()), SimulationRunV2.Fidelity.OUTPUT_LEVEL,
                assetRef == null ? selected.control().behavior() instanceof FixtureSetCommand.Behavior.Replay
                        ? SimulationRunV2.Provenance.REPLAY : SimulationRunV2.Provenance.PINNED_PRIVATE
                        : SimulationRunV2.Provenance.GOVERNED_ASSET,
                assetRef, AuthoringFingerprints.of(input), fingerprint(output),
                new SimulationRun.Egress.Fixture(false));
    }

    private static SimulationRunV2.Invocation realInvocation(
            String key, String parent, SimulationCommandV2.FixtureTarget target,
            ExactFixtureSubjectRefV2 subject, SimulationRunV2.InvocationStatus status,
            JsonNode input, JsonNode output) {
        return new SimulationRunV2.Invocation(key, parent, target, subject, status,
                SimulationRunV2.Execution.REAL, SimulationRunV2.MatchedBy.NONE,
                null, null, null, null, null, AuthoringFingerprints.of(input), fingerprint(output),
                SimulationRun.Egress.notApplicable());
    }

    private SimulationRunV2 contractFailure(
            String runId, String requestFingerprint, Instant startedAt,
            ResolvedFlowSimulationPlanV2 plan, String code) {
        State state = new State();
        state.failed = true;
        state.diagnostics.add(new SimulationRunV2.Diagnostic(code, "Flow input does not satisfy its contract."));
        return result(runId, requestFingerprint, startedAt, plan, SimulationRunV2.Status.FAILED,
                null, state, SimulationRunV2.ExecutionVerdict.FAILED,
                SimulationRunV2.ContractVerdict.INVALID);
    }

    private SimulationRunV2 result(
            String runId, String requestFingerprint, Instant startedAt,
            ResolvedFlowSimulationPlanV2 plan, SimulationRunV2.Status status, JsonNode output,
            State state, SimulationRunV2.ExecutionVerdict execution,
            SimulationRunV2.ContractVerdict contract) {
        SimulationRunV2.AssertionsVerdict assertions = state.assertionFailed
                ? SimulationRunV2.AssertionsVerdict.FAILED : state.assertionsChecked
                ? SimulationRunV2.AssertionsVerdict.PASSED : SimulationRunV2.AssertionsVerdict.NOT_CHECKED;
        return new SimulationRunV2(SimulationRunV2.SCHEMA_VERSION, runId, status, plan.subject(),
                requestFingerprint, plan.fingerprint(), output, state.invocations,
                new SimulationRunV2.Verdicts(execution, assertions, contract,
                        state.governed ? SimulationRunV2.GovernanceVerdict.PASSED
                                : SimulationRunV2.GovernanceVerdict.NOT_CHECKED,
                        SimulationRunV2.AggregateVerdict.NOT_READY), state.diagnostics,
                startedAt, clock.instant());
    }

    private static JsonNode mapInput(ReusableFlowCommand.Node node, JsonNode flowInput,
                                     Map<String, JsonNode> localOutputs) {
        ObjectNode result = JSON.createObjectNode();
        for (ReusableFlowCommand.Input mapping : node.inputs()) {
            JsonNode value;
            if (mapping.from() instanceof ReusableFlowCommand.MappingSource.FlowInput source) {
                value = at(flowInput, source.path());
            } else if (mapping.from() instanceof ReusableFlowCommand.MappingSource.NodeOutput source) {
                value = at(localOutputs.get(source.nodeId()), source.path());
            } else {
                value = ((ReusableFlowCommand.MappingSource.Constant) mapping.from()).value();
            }
            put(result, mapping.to(), value);
        }
        return result;
    }

    private static JsonNode at(JsonNode source, String path) {
        if (source == null || path == null || "$".equals(path)) return source;
        JsonNode value = source;
        for (String segment : path.substring(2).split("\\.")) value = value.path(segment);
        return value.isMissingNode() ? null : value.deepCopy();
    }

    private static void put(ObjectNode root, String path, JsonNode value) {
        if ("$".equals(path)) {
            if (value != null && value.isObject()) root.setAll((ObjectNode) value);
            return;
        }
        String[] segments = path.substring(2).split("\\.");
        ObjectNode parent = root;
        for (int index = 0; index < segments.length - 1; index++) {
            JsonNode existing = parent.get(segments[index]);
            if (existing instanceof ObjectNode object) parent = object;
            else parent = parent.putObject(segments[index]);
        }
        parent.set(segments[segments.length - 1], value == null
                ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : value.deepCopy());
    }

    private static List<VisualDiagnostic> validate(SchemaEnvelope schema, JsonNode value, String path) {
        return VisualSchemaValidator.validateValue(schema, JSON.convertValue(value, Object.class), path);
    }

    private StoredApiResource resource(AuthoringScope scope, ExactFixtureSubjectRefV2 subject) {
        if (!(subject instanceof ExactFixtureSubjectRefV2.ApiResource ref)) return null;
        StoredApiResource stored = resources.findRevision(scope, ref.resourceId(), ref.revision())
                .orElseThrow(() -> new SimulationFailure(SimulationFailure.Code.NOT_FOUND));
        ExactFixtureSubjectRefV2.ApiResource actual = new ExactFixtureSubjectRefV2.ApiResource(
                stored.resource().resourceId(), stored.resource().revision(), stored.resource().fingerprint());
        if (!actual.equals(ref)) throw new SimulationFailure(SimulationFailure.Code.INTEGRITY);
        return stored;
    }

    private void recordUsage(AuthoringScope scope, SimulationRunV2 run) {
        for (SimulationRunV2.Invocation invocation : run.invocations()) {
            if (invocation.status() == SimulationRunV2.InvocationStatus.COMPLETED
                    && invocation.fixtureAssetRef() != null) {
                usage.record(scope, run.runId(), invocation.invocationKey(), invocation.fixtureAssetRef());
            }
        }
    }

    private static SimulationRunV2.MatchedBy matchedBy(ResolvedFixturePlan.MatchedBy value) {
        return SimulationRunV2.MatchedBy.valueOf(value.name());
    }

    private static SimulationRunV2.Behavior behavior(FixtureSetCommand.Behavior value) {
        if (value instanceof FixtureSetCommand.Behavior.Return) return SimulationRunV2.Behavior.RETURN;
        if (value instanceof FixtureSetCommand.Behavior.Error) return SimulationRunV2.Behavior.ERROR;
        if (value instanceof FixtureSetCommand.Behavior.Timeout) return SimulationRunV2.Behavior.TIMEOUT;
        return SimulationRunV2.Behavior.REPLAY;
    }

    private static String fingerprint(JsonNode value) {
        return value == null ? null : AuthoringFingerprints.of(value);
    }

    private static List<String> append(List<String> prefix, String value) {
        List<String> path = new ArrayList<>(prefix);
        path.add(value);
        return List.copyOf(path);
    }

    private static SimulationFailure.Code map(FixturePlanFailure.Code code) {
        return switch (code) {
            case VALIDATION -> SimulationFailure.Code.COMMAND_INVALID;
            case FIXTURE_NOT_FOUND, CASE_NOT_FOUND, CONDITION_NOT_FOUND -> SimulationFailure.Code.NOT_FOUND;
            case FIXTURE_SUBJECT_MISMATCH, FIXTURE_REFERENCE_MISMATCH ->
                    SimulationFailure.Code.FIXTURE_SUBJECT_MISMATCH;
            case FIXTURE_STALE -> SimulationFailure.Code.FIXTURE_STALE;
            case CONDITION_NOT_SATISFIED -> SimulationFailure.Code.FIXTURE_CONDITION_NOT_SATISFIED;
            case AUTO_MATCH_EMPTY -> SimulationFailure.Code.FIXTURE_AUTO_MATCH_EMPTY;
            case AUTO_MATCH_AMBIGUOUS -> SimulationFailure.Code.FIXTURE_AUTO_MATCH_AMBIGUOUS;
            case TARGET_OVERLAP -> SimulationFailure.Code.FIXTURE_TARGET_OVERLAP;
            case MATERIAL_UNAVAILABLE -> SimulationFailure.Code.FIXTURE_MATERIAL_UNAVAILABLE;
            case TARGET_UNSUPPORTED -> SimulationFailure.Code.UNSUPPORTED;
            case INTEGRITY -> SimulationFailure.Code.INTEGRITY;
        };
    }

    private static final class State {
        private final List<SimulationRunV2.Invocation> invocations = new ArrayList<>();
        private final List<SimulationRunV2.Diagnostic> diagnostics = new ArrayList<>();
        private boolean blocked;
        private boolean failed;
        private boolean assertionsChecked;
        private boolean assertionFailed;
        private boolean governed;
    }
}
