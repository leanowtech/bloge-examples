package com.leanowtech.bloge.gateway.testing.correctness.scenario;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewRecord;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewStatus;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioDraftV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredScenarioDraftSetV2;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.ScenarioClosureReport.ClosurePhase;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Application boundary for governed Scenario v2 draft and review transitions. */
public final class ScenarioDraftSetV2Service {

    private final ScenarioDraftSetV2Repository scenarios;
    private final ScenarioClosureValidator closureValidator;
    private final ScenarioReviewAuthorizer reviewAuthorizer;
    private final Clock clock;

    public ScenarioDraftSetV2Service(
            ScenarioDraftSetV2Repository scenarios,
            ScenarioClosureValidator closureValidator,
            ScenarioReviewAuthorizer reviewAuthorizer
    ) {
        this(scenarios, closureValidator, reviewAuthorizer, Clock.systemUTC());
    }

    public ScenarioDraftSetV2Service(
            ScenarioDraftSetV2Repository scenarios,
            ScenarioClosureValidator closureValidator,
            ScenarioReviewAuthorizer reviewAuthorizer,
            Clock clock
    ) {
        this.scenarios = Objects.requireNonNull(scenarios, "scenarios");
        this.closureValidator = Objects.requireNonNull(closureValidator, "closureValidator");
        this.reviewAuthorizer = reviewAuthorizer == null
                ? ScenarioReviewAuthorizer.denyAll() : reviewAuthorizer;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public StoredScenarioDraftSetV2 saveDraft(
            long expectedRevision,
            ScenarioDraftSetV2 candidate,
            PrincipalRef actor
    ) {
        requireActor(actor);
        if (candidate == null || expectedRevision < 0
                || candidate.revision() != expectedRevision) {
            throw failure("RG.CORRECTNESS.SCENARIO_DRAFT_INVALID",
                    "Draft save requires a Scenario v2 document at the expected revision.");
        }
        StoredScenarioDraftSetV2 stored = scenarios.findHead(
                candidate.scope(), candidate.scenarioDraftSetId()).orElse(null);
        if ((stored == null && expectedRevision != 0)
                || (stored != null
                && stored.scenarioDraftSet().revision() != expectedRevision)) {
            throw conflict();
        }
        validateDraftMutation(stored == null ? null : stored.scenarioDraftSet(), candidate);
        return scenarios.saveIfRevision(expectedRevision, candidate, actor)
                .orElseThrow(ScenarioDraftSetV2Service::conflict);
    }

    public TransitionResult markReviewReady(
            EnterpriseScope scope,
            String scenarioDraftSetId,
            String scenarioId,
            long expectedRevision,
            PrincipalRef actor
    ) {
        requireActor(actor);
        ScenarioDraftSetV2 current = exactHead(
                scope, scenarioDraftSetId, expectedRevision).scenarioDraftSet();
        ScenarioDraftV2 selected = findCase(current, scenarioId);
        if (selected.lifecycle() != ScenarioLifecycle.EXPLORATORY) {
            throw invalidTransition("Only an EXPLORATORY Case can enter review readiness.");
        }
        ScenarioClosureReport closure = closureValidator.validate(
                current, selected, ClosurePhase.REVIEW_READY);
        requireComplete(closure);

        ScenarioDraftV2 transitioned = copyCase(
                selected, ScenarioLifecycle.REVIEW_READY, ReviewRecord.pending());
        StoredScenarioDraftSetV2 stored = saveTransition(
                current, replaceCase(current.scenarios(), transitioned), actor);
        return new TransitionResult(stored, closure);
    }

    public TransitionResult approveCanonical(
            EnterpriseScope scope,
            String scenarioDraftSetId,
            String scenarioId,
            long expectedRevision,
            String reviewComment,
            PrincipalRef actor
    ) {
        requireActor(actor);
        String comment = reviewComment == null ? "" : reviewComment.trim();
        if (comment.isEmpty() || comment.length() > 4096) {
            throw failure("RG.CORRECTNESS.SCENARIO_REVIEW_INVALID",
                    "Canonical approval requires a bounded review comment.");
        }
        ScenarioDraftSetV2 current = exactHead(
                scope, scenarioDraftSetId, expectedRevision).scenarioDraftSet();
        ScenarioDraftV2 selected = findCase(current, scenarioId);
        if (selected.lifecycle() != ScenarioLifecycle.REVIEW_READY) {
            throw invalidTransition("Only a REVIEW_READY Case can become CANONICAL.");
        }
        ScenarioClosureReport closure = closureValidator.validate(
                current, selected, ClosurePhase.CANONICAL);
        requireComplete(closure);
        ScenarioReviewAuthorizer.ReviewDecision decision = reviewAuthorizer.authorize(
                scope, current, selected, actor);
        if (decision == null || !decision.allowed()) {
            throw failure("RG.CORRECTNESS.SCENARIO_REVIEW_FORBIDDEN",
                    "The actor is not authorized to approve this Scenario Case.");
        }
        if (decision.independentReviewRequired()
                && current.metadata().createdBy().id().equals(actor.id())) {
            throw failure("RG.CORRECTNESS.FOUR_EYES_REQUIRED",
                    "Canonical approval requires a reviewer other than the Scenario set author.");
        }

        ReviewRecord approval = new ReviewRecord(
                ReviewStatus.APPROVED, actor, clock.instant(), comment);
        ScenarioDraftV2 transitioned = copyCase(
                selected, ScenarioLifecycle.CANONICAL, approval);
        StoredScenarioDraftSetV2 stored = saveTransition(
                current, replaceCase(current.scenarios(), transitioned), actor);
        return new TransitionResult(stored, closure);
    }

    private void validateDraftMutation(
            ScenarioDraftSetV2 current,
            ScenarioDraftSetV2 candidate
    ) {
        if (current != null && (!current.target().equals(candidate.target())
                || !current.contractRef().equals(candidate.contractRef()))) {
            throw failure("RG.CORRECTNESS.SCENARIO_REFERENCE_DRIFT",
                    "Draft save cannot change the exact target or contract coordinate.");
        }
        Map<String, ScenarioDraftV2> currentById = byId(
                current == null ? List.of() : current.scenarios());
        Map<String, ScenarioDraftV2> candidateById = byId(candidate.scenarios());
        for (ScenarioDraftV2 proposed : candidate.scenarios()) {
            ScenarioDraftV2 previous = currentById.get(proposed.scenarioId());
            if (previous == null || previous.lifecycle() == ScenarioLifecycle.EXPLORATORY) {
                if (proposed.lifecycle() != ScenarioLifecycle.EXPLORATORY
                        || proposed.review().status() != ReviewStatus.PENDING) {
                    throw failure("RG.CORRECTNESS.SCENARIO_TRANSITION_REQUIRED",
                            "New and editable Cases must remain EXPLORATORY with pending review.");
                }
            } else if (!previous.equals(proposed)) {
                throw failure("RG.CORRECTNESS.SCENARIO_IMMUTABLE",
                        "Reviewed Cases can only change through governed transition commands.");
            }
        }
        for (ScenarioDraftV2 previous : currentById.values()) {
            if (previous.lifecycle() != ScenarioLifecycle.EXPLORATORY
                    && !candidateById.containsKey(previous.scenarioId())) {
                throw failure("RG.CORRECTNESS.SCENARIO_IMMUTABLE",
                        "Reviewed Cases cannot be removed by draft save.");
            }
        }
    }

    private StoredScenarioDraftSetV2 exactHead(
            EnterpriseScope scope,
            String scenarioDraftSetId,
            long expectedRevision
    ) {
        if (scope == null || scenarioDraftSetId == null || scenarioDraftSetId.isBlank()
                || expectedRevision < 1) {
            throw failure("RG.CORRECTNESS.SCENARIO_COMMAND_INVALID",
                    "Scenario transition requires an exact scoped revision.");
        }
        StoredScenarioDraftSetV2 stored = scenarios.findHead(
                        scope, scenarioDraftSetId.trim())
                .orElseThrow(() -> failure("RG.CORRECTNESS.SCENARIO_NOT_FOUND",
                        "Scenario Draft Set was not found in the authorized scope."));
        if (stored.scenarioDraftSet().revision() != expectedRevision) throw conflict();
        return stored;
    }

    private StoredScenarioDraftSetV2 saveTransition(
            ScenarioDraftSetV2 current,
            List<ScenarioDraftV2> cases,
            PrincipalRef actor
    ) {
        ScenarioDraftSetV2 candidate = new ScenarioDraftSetV2(
                current.schemaVersion(), current.scenarioDraftSetId(), current.revision(),
                current.scope(), current.target(), current.contractRef(), cases,
                current.metadata());
        return scenarios.saveIfRevision(current.revision(), candidate, actor)
                .orElseThrow(ScenarioDraftSetV2Service::conflict);
    }

    private static void requireComplete(ScenarioClosureReport closure) {
        if (!closure.complete()) {
            throw new ScenarioV2CommandException(
                    "RG.CORRECTNESS.SCENARIO_CLOSURE_INCOMPLETE",
                    "Scenario exact-reference closure is incomplete.", closure);
        }
    }

    private static ScenarioDraftV2 findCase(
            ScenarioDraftSetV2 draftSet,
            String scenarioId
    ) {
        String exactId = scenarioId == null ? "" : scenarioId.trim();
        if (exactId.isEmpty()) {
            throw failure("RG.CORRECTNESS.SCENARIO_CASE_NOT_FOUND",
                    "Scenario Case id is required.");
        }
        return draftSet.scenarios().stream()
                .filter(value -> value.scenarioId().equals(exactId))
                .findFirst()
                .orElseThrow(() -> failure("RG.CORRECTNESS.SCENARIO_CASE_NOT_FOUND",
                        "Scenario Case was not found in the exact Draft Set revision."));
    }

    private static List<ScenarioDraftV2> replaceCase(
            List<ScenarioDraftV2> cases,
            ScenarioDraftV2 replacement
    ) {
        List<ScenarioDraftV2> result = new ArrayList<>(cases.size());
        for (ScenarioDraftV2 value : cases) {
            result.add(value.scenarioId().equals(replacement.scenarioId())
                    ? replacement : value);
        }
        return List.copyOf(result);
    }

    private static ScenarioDraftV2 copyCase(
            ScenarioDraftV2 value,
            ScenarioLifecycle lifecycle,
            ReviewRecord review
    ) {
        return new ScenarioDraftV2(
                value.scenarioId(), value.name(), value.businessIntent(), value.description(),
                value.caseType(), value.risk(), value.owner(), lifecycle,
                value.obligationRefs(), value.oracleRefs(), value.assertionSetRefs(),
                value.sourceRefs(), value.given(), value.dependencies(), review, value.tags());
    }

    private static Map<String, ScenarioDraftV2> byId(List<ScenarioDraftV2> cases) {
        Map<String, ScenarioDraftV2> result = new LinkedHashMap<>();
        for (ScenarioDraftV2 value : cases) result.put(value.scenarioId(), value);
        return result;
    }

    private static void requireActor(PrincipalRef actor) {
        if (actor == null) {
            throw failure("RG.CORRECTNESS.ACTOR_REQUIRED",
                    "An authenticated command actor is required.");
        }
    }

    private static ScenarioV2CommandException conflict() {
        return failure("RG.CORRECTNESS.REVISION_CONFLICT",
                "The Scenario Draft Set changed; reload the exact head and retry.");
    }

    private static ScenarioV2CommandException invalidTransition(String message) {
        return failure("RG.CORRECTNESS.SCENARIO_TRANSITION_INVALID", message);
    }

    private static ScenarioV2CommandException failure(String code, String message) {
        return new ScenarioV2CommandException(code, message);
    }

    public record TransitionResult(
            StoredScenarioDraftSetV2 stored,
            ScenarioClosureReport closure
    ) {
        public TransitionResult {
            if (stored == null || closure == null || !closure.complete()) {
                throw new IllegalArgumentException("Completed Scenario transition is required");
            }
        }
    }
}
