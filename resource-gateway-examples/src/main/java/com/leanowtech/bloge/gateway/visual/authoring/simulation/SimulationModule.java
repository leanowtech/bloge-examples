package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetCommitStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetAuthorityReader;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StoredFixtureSet;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowFailure;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowVersion;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.StoredApiResource;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Deep simulation application module for exact private Fixture Case runs.
 *
 * <p>The current tracer supports one API Resource subject with one SUBJECT RETURN/INLINE control.
 * That shape is locally executable, performs no network I/O and produces explicit mocked/fixture
 * evidence. Ad-hoc inputs, real external reads, internal-node controls and governed material remain
 * fail-closed until their independent runtime authorities are wired.</p>
 */
public final class SimulationModule {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private final ApiResourceCommitStore resources;
    private final FixtureSetAuthorityReader fixtures;
    private final ReusableFlowPublicationStore flows;
    private final SimulationRunStore runs;
    private final Clock clock;
    private final Supplier<String> runIds;

    /** Creates a production-neutral module with server-generated run ids. */
    public SimulationModule(ApiResourceCommitStore resources, FixtureSetAuthorityReader fixtures,
                            SimulationRunStore runs) {
        this(resources, fixtures, null, runs, Clock.systemUTC(), () -> "sim-" + UUID.randomUUID());
    }

    /** Creates a module that can also execute exact whole-flow Fixture returns. */
    public SimulationModule(ApiResourceCommitStore resources, FixtureSetAuthorityReader fixtures,
                            ReusableFlowPublicationStore flows, SimulationRunStore runs) {
        this(resources, fixtures, flows, runs, Clock.systemUTC(), () -> "sim-" + UUID.randomUUID());
    }

    /** Test seam for deterministic time and ids. */
    SimulationModule(ApiResourceCommitStore resources, FixtureSetAuthorityReader fixtures,
                     SimulationRunStore runs, Clock clock, Supplier<String> runIds) {
        this(resources, fixtures, null, runs, clock, runIds);
    }

    /** Test seam for deterministic whole-flow execution time and ids. */
    SimulationModule(ApiResourceCommitStore resources, FixtureSetAuthorityReader fixtures,
                     ReusableFlowPublicationStore flows, SimulationRunStore runs,
                     Clock clock, Supplier<String> runIds) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
        this.flows = flows;
        this.runs = Objects.requireNonNull(runs, "runs");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runIds = Objects.requireNonNull(runIds, "runIds");
    }

    /** Executes or exactly replays one fixture-backed simulation. */
    public SimulationRun run(AuthoringScope scope, String idempotencyKey, SimulationRequest request) {
        return execute(scope, idempotencyKey, request).run();
    }

    /** Executes one command and exposes whether the exact immutable result was replayed. */
    public SimulationExecutionResult execute(AuthoringScope scope, String idempotencyKey,
                                             SimulationRequest request) {
        validate(scope, idempotencyKey, request);
        CompiledCase compiled = compile(scope, (SimulationRequest.Source.FixtureCase) request.source());
        String fingerprint = fingerprint(request);
        Instant startedAt = clock.instant();
        SimulationRunStore.Claim claim = runs.claim(scope, idempotencyKey, fingerprint, runIds, startedAt);
        if (claim instanceof SimulationRunStore.Claim.Replay replay) {
            return new SimulationExecutionResult(replay.run(), true);
        }
        if (claim instanceof SimulationRunStore.Claim.Conflict) throw failure(SimulationFailure.Code.CONFLICT);
        if (claim instanceof SimulationRunStore.Claim.Busy) throw failure(SimulationFailure.Code.BUSY);
        String runId = ((SimulationRunStore.Claim.Acquired) claim).runId();
        SimulationRun result = execute(runId, startedAt, compiled);
        return new SimulationExecutionResult(runs.complete(scope, idempotencyKey, fingerprint, result), false);
    }

    /** Reads one immutable run in the trusted scope. */
    public Optional<SimulationRun> read(AuthoringScope scope, String runId) {
        if (scope == null || runId == null || !IDENTIFIER.matcher(runId).matches()) {
            throw failure(SimulationFailure.Code.VALIDATION);
        }
        return runs.find(scope, runId);
    }

    /** Reads one immutable run or returns the closed not-found code. */
    public SimulationRun readRequired(AuthoringScope scope, String runId) {
        return read(scope, runId).orElseThrow(() -> failure(SimulationFailure.Code.NOT_FOUND));
    }

    private CompiledCase compile(AuthoringScope scope, SimulationRequest.Source.FixtureCase source) {
        try {
            StoredFixtureSet fixture = fixtures.findRevision(scope, source.fixtureSetId(), source.revision())
                    .orElseThrow(() -> failure(SimulationFailure.Code.NOT_FOUND));
            FixtureSetCommand.Case selected = fixture.generated().view().cases().stream()
                    .filter(value -> source.caseId().equals(value.caseId()))
                    .reduce((left, right) -> { throw failure(SimulationFailure.Code.INTEGRITY); })
                    .orElseThrow(() -> failure(SimulationFailure.Code.NOT_FOUND));
            FixtureSetCommand.Control control = soleReturnControl(selected);
            FixtureSetCommand.Behavior.Return returned = (FixtureSetCommand.Behavior.Return) control.behavior();
            if (!(returned.material() instanceof FixtureSetCommand.Material.Inline inline)) {
                throw failure(SimulationFailure.Code.UNSUPPORTED);
            }
            FixtureSubjectRef subject = fixture.generated().view().subject();
            if (subject instanceof FixtureSubjectRef.ApiResource resourceSubject) {
                StoredApiResource resource = resources.findRevision(
                                scope, resourceSubject.resourceId(), resourceSubject.revision())
                        .orElseThrow(() -> failure(SimulationFailure.Code.NOT_FOUND));
                if (!FixtureSubjectRef.apiResource(resource.resource().ref()).equals(resourceSubject)) {
                    throw failure(SimulationFailure.Code.INTEGRITY);
                }
                return new CompiledCase(source, resourceSubject, resource.resource().contract().input(),
                        resource.resource().contract().output(), selected, control, inline.value(),
                        resource.resource().resourceId());
            }
            if (subject instanceof FixtureSubjectRef.FlowVersion flowSubject) {
                if (flows == null) throw failure(SimulationFailure.Code.UNSUPPORTED);
                ReusableFlowVersion version = flows.findVersion(
                                scope, flowSubject.publicationId(), flowSubject.revision())
                        .orElseThrow(() -> failure(SimulationFailure.Code.NOT_FOUND));
                if (!version.subject().equals(flowSubject)) throw failure(SimulationFailure.Code.INTEGRITY);
                if (control.fidelity() != null
                        && control.fidelity() != FixtureSetCommand.Fidelity.OUTPUT_LEVEL) {
                    throw failure(SimulationFailure.Code.UNSUPPORTED);
                }
                return new CompiledCase(source, flowSubject, version.contract().input(),
                        version.contract().output(), selected, control, inline.value(), null);
            }
            throw failure(SimulationFailure.Code.UNSUPPORTED);
        } catch (SimulationFailure failure) {
            throw failure;
        } catch (ApiFixtureSetCommitStoreException | StandaloneFixtureSetStoreException
                 | ApiResourceCommitStoreException
                 | ReusableFlowFailure failure) {
            throw failure(SimulationFailure.Code.INTEGRITY);
        }
    }

    private SimulationRun execute(String runId, Instant startedAt, CompiledCase compiled) {
        List<SimulationRun.Diagnostic> diagnostics = new ArrayList<>();
        List<VisualDiagnostic> inputProblems = VisualSchemaValidator.validateValue(
                compiled.input(), javaValue(compiled.fixture().input()), "/input");
        List<VisualDiagnostic> outputProblems = VisualSchemaValidator.validateValue(
                compiled.outputSchema(), javaValue(compiled.output()), "/output");
        inputProblems.stream().map(SimulationModule::diagnostic).forEach(diagnostics::add);
        outputProblems.stream().map(SimulationModule::diagnostic).forEach(diagnostics::add);
        boolean contractPassed = diagnostics.isEmpty();
        SimulationRun.Verdict assertion = compiled.fixture().expect() == null
                ? SimulationRun.Verdict.NOT_CHECKED
                : compiled.fixture().expect().output().equals(compiled.output())
                ? SimulationRun.Verdict.PASSED : SimulationRun.Verdict.FAILED;
        boolean success = contractPassed && assertion != SimulationRun.Verdict.FAILED;
        SimulationRun.Fidelity fidelity = compiled.control().fidelity() == null
                ? SimulationRun.Fidelity.OUTPUT_LEVEL
                : SimulationRun.Fidelity.valueOf(compiled.control().fidelity().name());
        List<SimulationRun.Node> nodes = compiled.evidenceNodeId() == null ? List.of() : List.of(
                new SimulationRun.Node(compiled.evidenceNodeId(),
                        success ? SimulationRun.NodeStatus.COMPLETED : SimulationRun.NodeStatus.FAILED,
                        SimulationRun.Execution.MOCKED, SimulationRun.FixtureSource.INLINE, fidelity,
                        SimulationRun.Egress.fixture()));
        return new SimulationRun(SimulationRun.SCHEMA_VERSION, runId,
                success ? SimulationRun.Status.SUCCEEDED : SimulationRun.Status.FAILED,
                compiled.subject(),
                new SimulationRun.FixtureCase(compiled.source().fixtureSetId(),
                        compiled.source().revision(), compiled.source().caseId()), compiled.output(),
                nodes,
                new SimulationRun.Verdicts(success ? SimulationRun.ExecutionVerdict.SIMULATED_ONLY
                        : SimulationRun.ExecutionVerdict.FAILED,
                        contractPassed ? SimulationRun.Verdict.PASSED : SimulationRun.Verdict.FAILED,
                        assertion, SimulationRun.Verdict.NOT_CHECKED), diagnostics, startedAt, clock.instant());
    }

    private static FixtureSetCommand.Control soleReturnControl(FixtureSetCommand.Case fixture) {
        if (fixture.controls().size() != 1) throw failure(SimulationFailure.Code.UNSUPPORTED);
        FixtureSetCommand.Control control = fixture.controls().getFirst();
        if (!(control.target() instanceof FixtureSetCommand.Target.Subject)
                || !(control.behavior() instanceof FixtureSetCommand.Behavior.Return)) {
            throw failure(SimulationFailure.Code.UNSUPPORTED);
        }
        return control;
    }

    private static void validate(AuthoringScope scope, String key, SimulationRequest request) {
        if (scope == null || key == null || key.isBlank() || key.length() > 160 || request == null
                || !SimulationRequest.SCHEMA_VERSION.equals(request.schemaVersion()) || request.source() == null
                || request.executionPolicy() == null
                || !(request.executionPolicy().externalWrites() instanceof SimulationRequest.ExternalWrites.Deny)) {
            throw failure(SimulationFailure.Code.VALIDATION);
        }
        if (!(request.source() instanceof SimulationRequest.Source.FixtureCase source)) {
            throw failure(SimulationFailure.Code.UNSUPPORTED);
        }
        if (source.fixtureSetId() == null || !IDENTIFIER.matcher(source.fixtureSetId()).matches()
                || source.caseId() == null || !IDENTIFIER.matcher(source.caseId()).matches()
                || source.revision() < 1) {
            throw failure(SimulationFailure.Code.VALIDATION);
        }
        if (!(request.executionPolicy().externalReads() instanceof SimulationRequest.ExternalReads.Deny)) {
            throw failure(SimulationFailure.Code.UNSUPPORTED);
        }
    }

    private static String fingerprint(SimulationRequest request) {
        SimulationRequest.Source.FixtureCase source = (SimulationRequest.Source.FixtureCase) request.source();
        return AuthoringFingerprints.of(JSON.valueToTree(Map.of(
                "schemaVersion", request.schemaVersion(), "sourceKind", "FIXTURE_CASE",
                "fixtureSetId", source.fixtureSetId(), "revision", source.revision(),
                "caseId", source.caseId(), "externalReads", "DENY", "externalWrites", "DENY")));
    }

    private static Object javaValue(JsonNode value) { return JSON.convertValue(value, Object.class); }
    private static SimulationRun.Diagnostic diagnostic(VisualDiagnostic value) {
        return new SimulationRun.Diagnostic(value.code(), value.message());
    }
    private static SimulationFailure failure(SimulationFailure.Code code) { return new SimulationFailure(code); }

    private record CompiledCase(SimulationRequest.Source.FixtureCase source, FixtureSubjectRef subject,
                                SchemaEnvelope input, SchemaEnvelope outputSchema,
                                FixtureSetCommand.Case fixture, FixtureSetCommand.Control control,
                                JsonNode output, String evidenceNodeId) {
        private CompiledCase { output = output.deepCopy(); }
        @Override public JsonNode output() { return output.deepCopy(); }
    }
}
