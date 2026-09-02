package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.StoredApiResource;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Executes caller-directed API Resource Fixtures and commits invocation-level v2 evidence.
 *
 * <p>The module consumes one immutable {@link ResolvedFixturePlan}; it never reads a mutable Fixture
 * head. Inline material is accepted only from a private revision, protected material only through a
 * trusted resolver and identity, and replay output only through an exact replay authority. Error and
 * timeout controls are simulated deterministically without sleeping or performing network I/O. The
 * module records governed usage only after immutable run completion, using an idempotent evidence
 * coordinate so replay cannot inflate usage.</p>
 */
public final class SimulationModuleV2 {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private final ApiResourceCommitStore resources;
    private final FixturePlanCompiler plans;
    private final FixtureAssetSimulationResolver protectedFixtures;
    private final SimulationReplayResolver replays;
    private final SimulationFixtureUsageRecorder usage;
    private final SimulationRunV2Store runs;
    private final FlowSimulationModuleV2 flows;
    private final Clock clock;
    private final Supplier<String> runIds;
    private final Supplier<String> invocationIds;

    /** Creates a module with optional governed-material and replay authorities. */
    public SimulationModuleV2(ApiResourceCommitStore resources, FixturePlanCompiler plans,
                              FixtureAssetSimulationResolver protectedFixtures,
                              SimulationReplayResolver replays,
                              SimulationFixtureUsageRecorder usage,
                              SimulationRunV2Store runs) {
        this(resources, plans, protectedFixtures, replays, usage, runs, Clock.systemUTC(),
                () -> "sim-" + UUID.randomUUID(), () -> "inv-" + UUID.randomUUID(), null);
    }

    /** Creates the API Resource runtime with an optional exact reusable-Flow runtime. */
    public SimulationModuleV2(ApiResourceCommitStore resources, FixturePlanCompiler plans,
                              FixtureAssetSimulationResolver protectedFixtures,
                              SimulationReplayResolver replays,
                              SimulationFixtureUsageRecorder usage,
                              SimulationRunV2Store runs, FlowSimulationModuleV2 flows) {
        this(resources, plans, protectedFixtures, replays, usage, runs, Clock.systemUTC(),
                () -> "sim-" + UUID.randomUUID(), () -> "inv-" + UUID.randomUUID(), flows);
    }

    /** Package-visible deterministic time and identity seam for behavior tests. */
    SimulationModuleV2(ApiResourceCommitStore resources, FixturePlanCompiler plans,
                       FixtureAssetSimulationResolver protectedFixtures,
                       SimulationReplayResolver replays, SimulationFixtureUsageRecorder usage,
                       SimulationRunV2Store runs, Clock clock, Supplier<String> runIds,
                       Supplier<String> invocationIds) {
        this(resources, plans, protectedFixtures, replays, usage, runs, clock, runIds,
                invocationIds, null);
    }

    SimulationModuleV2(ApiResourceCommitStore resources, FixturePlanCompiler plans,
                       FixtureAssetSimulationResolver protectedFixtures,
                       SimulationReplayResolver replays, SimulationFixtureUsageRecorder usage,
                       SimulationRunV2Store runs, Clock clock, Supplier<String> runIds,
                       Supplier<String> invocationIds, FlowSimulationModuleV2 flows) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.protectedFixtures = protectedFixtures;
        this.replays = replays;
        this.usage = usage == null ? SimulationFixtureUsageRecorder.none() : usage;
        this.runs = Objects.requireNonNull(runs, "runs");
        this.flows = flows;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runIds = Objects.requireNonNull(runIds, "runIds");
        this.invocationIds = Objects.requireNonNull(invocationIds, "invocationIds");
    }

    /** Executes one command without governed-material identity. */
    public SimulationExecutionResultV2 execute(AuthoringScope scope, String idempotencyKey,
                                               SimulationCommandV2 command) {
        return execute(scope, idempotencyKey, command, null);
    }

    /** Executes one command with a trusted identity for governed material reads. */
    public SimulationExecutionResultV2 execute(AuthoringScope scope, String idempotencyKey,
                                               SimulationCommandV2 command,
                                               SimulationIdentity identity) {
        validate(scope, idempotencyKey, command, identity);
        if (command.subject() instanceof ExactFixtureSubjectRefV2.FlowDraft
                || command.subject() instanceof ExactFixtureSubjectRefV2.FlowVersion) {
            if (flows == null) throw failure(SimulationFailure.Code.UNSUPPORTED);
            return flows.execute(scope, idempotencyKey, command, identity);
        }
        ResolvedFixturePlan plan = compile(scope, command);
        StoredApiResource resource = resource(scope, plan.subject());
        requireSupportedPlan(plan);
        String requestFingerprint = AuthoringFingerprints.of(JSON.valueToTree(command));
        Instant startedAt = clock.instant();
        SimulationRunV2Store.Claim claim = runs.claim(
                scope, idempotencyKey, requestFingerprint, runIds, startedAt);
        if (claim instanceof SimulationRunV2Store.Claim.Replay replay) {
            recordUsage(scope, replay.run());
            return new SimulationExecutionResultV2(replay.run(), true);
        }
        if (claim instanceof SimulationRunV2Store.Claim.Conflict) {
            throw failure(SimulationFailure.Code.CONFLICT);
        }
        if (claim instanceof SimulationRunV2Store.Claim.Busy) {
            throw failure(SimulationFailure.Code.BUSY);
        }
        String runId = ((SimulationRunV2Store.Claim.Acquired) claim).runId();
        SimulationRunV2 completed = run(runId, startedAt, requestFingerprint, plan, resource, identity);
        SimulationRunV2 committed = runs.complete(
                scope, idempotencyKey, requestFingerprint, completed);
        recordUsage(scope, committed);
        return new SimulationExecutionResultV2(committed, false);
    }

    /** Reads one immutable v2 run inside the trusted scope. */
    public Optional<SimulationRunV2> read(AuthoringScope scope, String runId) {
        if (scope == null || runId == null || !IDENTIFIER.matcher(runId).matches()) {
            throw failure(SimulationFailure.Code.VALIDATION);
        }
        return runs.find(scope, runId);
    }

    /** Reads one immutable v2 run or fails with the closed not-found code. */
    public SimulationRunV2 readRequired(AuthoringScope scope, String runId) {
        return read(scope, runId).orElseThrow(() -> failure(SimulationFailure.Code.NOT_FOUND));
    }

    private SimulationRunV2 run(String runId, Instant startedAt, String requestFingerprint,
                                ResolvedFixturePlan plan, StoredApiResource resource,
                                SimulationIdentity identity) {
        List<VisualDiagnostic> inputProblems = VisualSchemaValidator.validateValue(
                resource.resource().contract().input(), javaValue(plan.input()), "/input");
        if (!inputProblems.isEmpty()) {
            return failedContract(runId, startedAt, requestFingerprint, plan, inputProblems);
        }
        if (plan.selections().isEmpty()) {
            return blocked(runId, startedAt, requestFingerprint, plan, "FIXTURE_UNMATCHED",
                    "No Fixture was selected and external execution is not authorized.");
        }
        ResolvedFixturePlan.Selection selection = plan.selections().getFirst();
        FixtureSetCommand.Behavior behavior = selection.control().behavior();
        if (behavior instanceof FixtureSetCommand.Behavior.Return returned) {
            return returned(runId, startedAt, requestFingerprint, plan, resource, selection,
                    returned.material(), identity);
        }
        if (behavior instanceof FixtureSetCommand.Behavior.Replay replay) {
            if (replays == null) {
                return blocked(runId, startedAt, requestFingerprint, plan,
                        "REPLAY_AUTHORITY_UNAVAILABLE", "Replay authority is unavailable.");
            }
            JsonNode output;
            try {
                output = replays.resolve(resource.scope(), replay.replayId(), replay.fingerprint());
            } catch (RuntimeException unavailable) {
                return blocked(runId, startedAt, requestFingerprint, plan,
                        "REPLAY_MATERIAL_UNAVAILABLE", "Replay material is unavailable.");
            }
            if (output == null) {
                return blocked(runId, startedAt, requestFingerprint, plan,
                        "REPLAY_MATERIAL_UNAVAILABLE", "Replay material is unavailable.");
            }
            return completedOutput(runId, startedAt, requestFingerprint, plan, resource, selection,
                    output, SimulationRunV2.Provenance.REPLAY, null);
        }
        if (behavior instanceof FixtureSetCommand.Behavior.Error) {
            return failedBehavior(runId, startedAt, requestFingerprint, plan, selection,
                    SimulationRunV2.Behavior.ERROR, "FIXTURE_ERROR", "Fixture configured an error.");
        }
        if (behavior instanceof FixtureSetCommand.Behavior.Timeout) {
            return failedBehavior(runId, startedAt, requestFingerprint, plan, selection,
                    SimulationRunV2.Behavior.TIMEOUT, "FIXTURE_TIMEOUT", "Fixture configured a timeout.");
        }
        return blocked(runId, startedAt, requestFingerprint, plan, "FIXTURE_BEHAVIOR_UNSUPPORTED",
                "Fixture behavior is unsupported for this subject.");
    }

    private ResolvedFixturePlan compile(AuthoringScope scope, SimulationCommandV2 command) {
        try {
            return plans.compile(scope, command);
        } catch (FixturePlanFailure failure) {
            throw failure(switch (failure.code()) {
                case VALIDATION -> SimulationFailure.Code.COMMAND_INVALID;
                case FIXTURE_NOT_FOUND, CASE_NOT_FOUND, CONDITION_NOT_FOUND ->
                        SimulationFailure.Code.NOT_FOUND;
                case FIXTURE_SUBJECT_MISMATCH, FIXTURE_REFERENCE_MISMATCH ->
                        SimulationFailure.Code.FIXTURE_SUBJECT_MISMATCH;
                case FIXTURE_STALE -> SimulationFailure.Code.FIXTURE_STALE;
                case CONDITION_NOT_SATISFIED ->
                        SimulationFailure.Code.FIXTURE_CONDITION_NOT_SATISFIED;
                case AUTO_MATCH_EMPTY -> SimulationFailure.Code.FIXTURE_AUTO_MATCH_EMPTY;
                case AUTO_MATCH_AMBIGUOUS -> SimulationFailure.Code.FIXTURE_AUTO_MATCH_AMBIGUOUS;
                case TARGET_OVERLAP -> SimulationFailure.Code.FIXTURE_TARGET_OVERLAP;
                case MATERIAL_UNAVAILABLE -> SimulationFailure.Code.FIXTURE_MATERIAL_UNAVAILABLE;
                case TARGET_UNSUPPORTED -> SimulationFailure.Code.UNSUPPORTED;
                case INTEGRITY -> SimulationFailure.Code.INTEGRITY;
            });
        }
    }

    private SimulationRunV2 returned(
            String runId, Instant startedAt, String requestFingerprint, ResolvedFixturePlan plan,
            StoredApiResource resource, ResolvedFixturePlan.Selection selection,
            FixtureSetCommand.Material material, SimulationIdentity identity) {
        if (material instanceof FixtureSetCommand.Material.Inline inline) {
            if (selection.fixtureStatus() != FixtureSetView.Status.PRIVATE_DRAFT) {
                return blocked(runId, startedAt, requestFingerprint, plan,
                        "FIXTURE_MATERIAL_UNAVAILABLE", "Fixture material is unavailable.");
            }
            return completedOutput(runId, startedAt, requestFingerprint, plan, resource, selection,
                    inline.value(), SimulationRunV2.Provenance.PINNED_PRIVATE, null);
        }
        FixtureSetCommand.Material.FixtureAsset asset =
                (FixtureSetCommand.Material.FixtureAsset) material;
        if (selection.fixtureStatus() != FixtureSetView.Status.TEAM_AVAILABLE
                || protectedFixtures == null || identity == null) {
            return blocked(runId, startedAt, requestFingerprint, plan,
                    "FIXTURE_MATERIAL_UNAVAILABLE", "Fixture material is unavailable.");
        }
        JsonNode output;
        try {
            output = protectedFixtures.resolve(identity, asset);
        } catch (RuntimeException unavailable) {
            return blocked(runId, startedAt, requestFingerprint, plan,
                    "FIXTURE_MATERIAL_UNAVAILABLE", "Fixture material is unavailable.");
        }
        if (output == null) {
            return blocked(runId, startedAt, requestFingerprint, plan,
                    "FIXTURE_MATERIAL_UNAVAILABLE", "Fixture material is unavailable.");
        }
        SimulationRunV2.FixtureAssetRef assetRef = new SimulationRunV2.FixtureAssetRef(
                asset.fixtureAssetId(), asset.revision(), asset.schemaFingerprint());
        return completedOutput(runId, startedAt, requestFingerprint, plan, resource, selection,
                output, SimulationRunV2.Provenance.GOVERNED_ASSET, assetRef);
    }

    private SimulationRunV2 completedOutput(
            String runId, Instant startedAt, String requestFingerprint, ResolvedFixturePlan plan,
            StoredApiResource resource, ResolvedFixturePlan.Selection selection, JsonNode output,
            SimulationRunV2.Provenance provenance, SimulationRunV2.FixtureAssetRef assetRef) {
        List<VisualDiagnostic> problems = VisualSchemaValidator.validateValue(
                resource.resource().contract().output(), javaValue(output), "/output");
        if (!problems.isEmpty()) {
            return failedOutput(runId, startedAt, requestFingerprint, plan, selection, output,
                    provenance, assetRef, problems);
        }
        SimulationRunV2.AssertionsVerdict assertions = selection.expect() == null
                ? SimulationRunV2.AssertionsVerdict.NOT_CHECKED
                : selection.expect().output().equals(output)
                ? SimulationRunV2.AssertionsVerdict.PASSED : SimulationRunV2.AssertionsVerdict.FAILED;
        boolean success = assertions != SimulationRunV2.AssertionsVerdict.FAILED;
        SimulationRunV2.Invocation invocation = invocation(plan.subject(), selection,
                success ? SimulationRunV2.InvocationStatus.COMPLETED
                        : SimulationRunV2.InvocationStatus.FAILED,
                behavior(selection), provenance, assetRef, plan.input(), output);
        return completed(runId, startedAt, requestFingerprint, plan,
                success ? SimulationRunV2.Status.SUCCEEDED : SimulationRunV2.Status.FAILED,
                output, List.of(invocation), new SimulationRunV2.Verdicts(
                        success ? SimulationRunV2.ExecutionVerdict.PASSED
                                : SimulationRunV2.ExecutionVerdict.FAILED,
                        assertions, SimulationRunV2.ContractVerdict.VALID,
                        provenance == SimulationRunV2.Provenance.GOVERNED_ASSET
                                ? SimulationRunV2.GovernanceVerdict.PASSED
                                : SimulationRunV2.GovernanceVerdict.NOT_CHECKED,
                        SimulationRunV2.AggregateVerdict.NOT_READY), List.of());
    }

    private SimulationRunV2 failedOutput(
            String runId, Instant startedAt, String requestFingerprint, ResolvedFixturePlan plan,
            ResolvedFixturePlan.Selection selection, JsonNode output,
            SimulationRunV2.Provenance provenance, SimulationRunV2.FixtureAssetRef assetRef,
            List<VisualDiagnostic> problems) {
        SimulationRunV2.Invocation invocation = invocation(plan.subject(), selection,
                SimulationRunV2.InvocationStatus.FAILED, behavior(selection), provenance, assetRef,
                plan.input(), output);
        return completed(runId, startedAt, requestFingerprint, plan, SimulationRunV2.Status.FAILED,
                null, List.of(invocation), failedVerdicts(SimulationRunV2.ContractVerdict.INVALID),
                diagnostics(problems, "Output does not satisfy the API Resource contract."));
    }

    private SimulationRunV2 failedContract(
            String runId, Instant startedAt, String requestFingerprint, ResolvedFixturePlan plan,
            List<VisualDiagnostic> problems) {
        return completed(runId, startedAt, requestFingerprint, plan, SimulationRunV2.Status.FAILED,
                null, List.of(), failedVerdicts(SimulationRunV2.ContractVerdict.INVALID),
                diagnostics(problems, "Input does not satisfy the API Resource contract."));
    }

    private SimulationRunV2 failedBehavior(
            String runId, Instant startedAt, String requestFingerprint, ResolvedFixturePlan plan,
            ResolvedFixturePlan.Selection selection, SimulationRunV2.Behavior behavior,
            String code, String message) {
        SimulationRunV2.Invocation invocation = invocation(plan.subject(), selection,
                SimulationRunV2.InvocationStatus.FAILED, behavior,
                provenance(selection), assetRef(selection), plan.input(), null);
        return completed(runId, startedAt, requestFingerprint, plan, SimulationRunV2.Status.FAILED,
                null, List.of(invocation), failedVerdicts(SimulationRunV2.ContractVerdict.NOT_CHECKED),
                List.of(new SimulationRunV2.Diagnostic(code, message)));
    }

    private SimulationRunV2 blocked(String runId, Instant startedAt, String requestFingerprint,
                                    ResolvedFixturePlan plan, String code, String message) {
        List<SimulationRunV2.Invocation> invocations = plan.selections().isEmpty() ? List.of()
                : List.of(invocation(plan.subject(), plan.selections().getFirst(),
                SimulationRunV2.InvocationStatus.BLOCKED,
                behavior(plan.selections().getFirst()), provenance(plan.selections().getFirst()),
                assetRef(plan.selections().getFirst()), plan.input(), null));
        return completed(runId, startedAt, requestFingerprint, plan, SimulationRunV2.Status.BLOCKED,
                null, invocations, new SimulationRunV2.Verdicts(
                        SimulationRunV2.ExecutionVerdict.BLOCKED,
                        SimulationRunV2.AssertionsVerdict.NOT_CHECKED,
                        SimulationRunV2.ContractVerdict.NOT_CHECKED,
                        SimulationRunV2.GovernanceVerdict.NOT_CHECKED,
                        SimulationRunV2.AggregateVerdict.NOT_READY),
                List.of(new SimulationRunV2.Diagnostic(code, message)));
    }

    private SimulationRunV2.Invocation invocation(
            ExactFixtureSubjectRefV2 subject, ResolvedFixturePlan.Selection selection,
            SimulationRunV2.InvocationStatus status,
            SimulationRunV2.Behavior behavior, SimulationRunV2.Provenance provenance,
            SimulationRunV2.FixtureAssetRef assetRef, JsonNode input, JsonNode output) {
        return new SimulationRunV2.Invocation(invocationIds.get(), null, selection.target(),
                subject, status, SimulationRunV2.Execution.MOCKED,
                matchedBy(selection.matchedBy()), new SimulationRunV2.FixtureCase(
                selection.fixtureSet().fixtureSetId(), selection.fixtureSet().revision(),
                selection.fixtureSet().fingerprint(), selection.caseId()), behavior,
                fidelity(selection), provenance, assetRef, AuthoringFingerprints.of(input),
                output == null ? null : AuthoringFingerprints.of(output),
                new SimulationRun.Egress.Fixture(false));
    }

    private SimulationRunV2 completed(
            String runId, Instant startedAt, String requestFingerprint, ResolvedFixturePlan plan,
            SimulationRunV2.Status status, JsonNode output, List<SimulationRunV2.Invocation> invocations,
            SimulationRunV2.Verdicts verdicts, List<SimulationRunV2.Diagnostic> diagnostics) {
        return new SimulationRunV2(SimulationRunV2.SCHEMA_VERSION, runId, status, plan.subject(),
                requestFingerprint, plan.fingerprint(), output, invocations, verdicts, diagnostics,
                startedAt, clock.instant());
    }

    private void recordUsage(AuthoringScope scope, SimulationRunV2 run) {
        run.invocations().stream().filter(value -> value.fixtureAssetRef() != null
                        && value.status() == SimulationRunV2.InvocationStatus.COMPLETED)
                .forEach(value -> usage.record(
                        scope, run.runId(), value.invocationKey(), value.fixtureAssetRef()));
    }

    private StoredApiResource resource(AuthoringScope scope, ExactFixtureSubjectRefV2 subject) {
        if (!(subject instanceof ExactFixtureSubjectRefV2.ApiResource ref)) {
            throw failure(SimulationFailure.Code.UNSUPPORTED);
        }
        try {
            StoredApiResource stored = resources.findRevision(scope, ref.resourceId(), ref.revision())
                    .orElseThrow(() -> failure(SimulationFailure.Code.NOT_FOUND));
            ExactFixtureSubjectRefV2.ApiResource storedRef = new ExactFixtureSubjectRefV2.ApiResource(
                    stored.resource().resourceId(), stored.resource().revision(),
                    stored.resource().fingerprint());
            if (!storedRef.equals(ref)) {
                throw failure(SimulationFailure.Code.INTEGRITY);
            }
            return stored;
        } catch (SimulationFailure failure) {
            throw failure;
        } catch (ApiResourceCommitStoreException failure) {
            throw failure(SimulationFailure.Code.INTEGRITY);
        }
    }

    private static void requireSupportedPlan(ResolvedFixturePlan plan) {
        if (plan.selections().size() > 1 || plan.selections().stream()
                .anyMatch(value -> !(value.target() instanceof SimulationCommandV2.FixtureTarget.Subject))) {
            throw failure(SimulationFailure.Code.UNSUPPORTED);
        }
        if (!plan.selections().isEmpty()) {
            FixtureSetCommand.Behavior behavior = plan.selections().getFirst().control().behavior();
            if (behavior instanceof FixtureSetCommand.Behavior.Real
                    || behavior instanceof FixtureSetCommand.Behavior.ApplyCase) {
                throw failure(SimulationFailure.Code.UNSUPPORTED);
            }
        }
    }

    private static void validate(AuthoringScope scope, String key, SimulationCommandV2 command,
                                 SimulationIdentity identity) {
        if (scope == null || key == null || key.isBlank() || key.length() > 160 || command == null) {
            throw failure(SimulationFailure.Code.VALIDATION);
        }
        if (identity != null && !scope.equals(identity.scope())) {
            throw failure(SimulationFailure.Code.VALIDATION);
        }
    }

    private static List<SimulationRunV2.Diagnostic> diagnostics(
            List<VisualDiagnostic> problems, String message) {
        List<SimulationRunV2.Diagnostic> result = new ArrayList<>();
        problems.stream().map(VisualDiagnostic::code).distinct().limit(100)
                .map(code -> new SimulationRunV2.Diagnostic(code, message)).forEach(result::add);
        return List.copyOf(result);
    }

    private static SimulationRunV2.Verdicts failedVerdicts(SimulationRunV2.ContractVerdict contract) {
        return new SimulationRunV2.Verdicts(SimulationRunV2.ExecutionVerdict.FAILED,
                SimulationRunV2.AssertionsVerdict.NOT_CHECKED, contract,
                SimulationRunV2.GovernanceVerdict.NOT_CHECKED,
                SimulationRunV2.AggregateVerdict.NOT_READY);
    }

    private static SimulationRunV2.MatchedBy matchedBy(ResolvedFixturePlan.MatchedBy value) {
        return SimulationRunV2.MatchedBy.valueOf(value.name());
    }

    private static SimulationRunV2.Behavior behavior(ResolvedFixturePlan.Selection selection) {
        FixtureSetCommand.Behavior value = selection.control().behavior();
        if (value instanceof FixtureSetCommand.Behavior.Return) return SimulationRunV2.Behavior.RETURN;
        if (value instanceof FixtureSetCommand.Behavior.Error) return SimulationRunV2.Behavior.ERROR;
        if (value instanceof FixtureSetCommand.Behavior.Timeout) return SimulationRunV2.Behavior.TIMEOUT;
        if (value instanceof FixtureSetCommand.Behavior.Replay) return SimulationRunV2.Behavior.REPLAY;
        throw failure(SimulationFailure.Code.UNSUPPORTED);
    }

    private static SimulationRunV2.Fidelity fidelity(ResolvedFixturePlan.Selection selection) {
        FixtureSetCommand.Fidelity value = selection.control().fidelity();
        return value == null ? SimulationRunV2.Fidelity.OUTPUT_LEVEL
                : SimulationRunV2.Fidelity.valueOf(value.name());
    }

    private static SimulationRunV2.Provenance provenance(ResolvedFixturePlan.Selection selection) {
        if (selection.control().behavior() instanceof FixtureSetCommand.Behavior.Replay) {
            return SimulationRunV2.Provenance.REPLAY;
        }
        if (selection.control().behavior() instanceof FixtureSetCommand.Behavior.Return returned
                && returned.material() instanceof FixtureSetCommand.Material.FixtureAsset) {
            return SimulationRunV2.Provenance.GOVERNED_ASSET;
        }
        return SimulationRunV2.Provenance.PINNED_PRIVATE;
    }

    private static SimulationRunV2.FixtureAssetRef assetRef(ResolvedFixturePlan.Selection selection) {
        if (selection.control().behavior() instanceof FixtureSetCommand.Behavior.Return returned
                && returned.material() instanceof FixtureSetCommand.Material.FixtureAsset asset) {
            return new SimulationRunV2.FixtureAssetRef(
                    asset.fixtureAssetId(), asset.revision(), asset.schemaFingerprint());
        }
        return null;
    }

    private static Object javaValue(JsonNode value) { return JSON.convertValue(value, Object.class); }
    private static SimulationFailure failure(SimulationFailure.Code code) { return new SimulationFailure(code); }
}
