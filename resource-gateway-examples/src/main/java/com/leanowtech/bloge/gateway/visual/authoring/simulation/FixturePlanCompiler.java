package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetCommitStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetAuthorityReader;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StoredFixtureSet;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deep module that resolves caller Fixture selections against immutable Fixture Set authority.
 *
 * <p>This first executable slice resolves a whole API Resource {@code SUBJECT}. Nested node paths and
 * function call sites are accepted by the wire model but fail closed until their topology authorities
 * are connected. Conditions are deterministic, side-effect free and evaluated only against the current
 * invocation input. The returned fingerprint contains coordinates and input fingerprints, never Fixture
 * material or business input.</p>
 */
public final class FixturePlanCompiler {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern PATH = Pattern.compile("\\$(?:\\.[A-Za-z0-9_-]+){0,16}");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final FixtureSetAuthorityReader fixtures;

    public FixturePlanCompiler(FixtureSetAuthorityReader fixtures) {
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
    }

    /** Compiles one exact command or fails before any runtime or material access. */
    public ResolvedFixturePlan compile(AuthoringScope scope, SimulationCommandV2 command) {
        requireCommand(scope, command);
        try {
            JsonNode input = input(scope, command);
            if (command.fixturePlan() instanceof SimulationCommandV2.FixturePlan.None) {
                return plan(command.subject(), input, SimulationCommandV2.Unmatched.BLOCK, List.of());
            }
            if (!(command.subject() instanceof ExactFixtureSubjectRefV2.ApiResource)) {
                throw failure(FixturePlanFailure.Code.TARGET_UNSUPPORTED);
            }
            List<ResolvedFixturePlan.Selection> selections =
                    command.fixturePlan() instanceof SimulationCommandV2.FixturePlan.CaseControls controls
                            ? caseControls(scope, command, input, controls)
                            : bindings(scope, command, input,
                            (SimulationCommandV2.FixturePlan.Bindings) command.fixturePlan());
            SimulationCommandV2.Unmatched unmatched = command.fixturePlan()
                    instanceof SimulationCommandV2.FixturePlan.CaseControls controls
                    ? controls.unmatched()
                    : ((SimulationCommandV2.FixturePlan.Bindings) command.fixturePlan()).unmatched();
            return plan(command.subject(), input, unmatched, selections);
        } catch (FixturePlanFailure failure) {
            throw failure;
        } catch (ApiFixtureSetCommitStoreException | StandaloneFixtureSetStoreException failure) {
            throw failure(FixturePlanFailure.Code.INTEGRITY);
        } catch (RuntimeException failure) {
            throw failure(FixturePlanFailure.Code.VALIDATION);
        }
    }

    private List<ResolvedFixturePlan.Selection> caseControls(
            AuthoringScope scope, SimulationCommandV2 command, JsonNode input,
            SimulationCommandV2.FixturePlan.CaseControls controls) {
        StoredFixtureSet stored = fixture(scope, controls.fixtureSet());
        requireSubject(command, stored);
        FixtureSetCommand.Case fixtureCase = exactCase(stored, controls.caseId());
        List<ResolvedFixturePlan.Selection> resolved = new ArrayList<>();
        for (FixtureSetCommand.Control control : fixtureCase.controls()) {
            SimulationCommandV2.FixtureTarget target = target(control.target());
            requireSupportedTarget(target);
            resolved.add(new ResolvedFixturePlan.Selection(target, controls.fixtureSet(),
                    fixtureCase.caseId(), ResolvedFixturePlan.MatchedBy.CASE_CONTROLS, control));
        }
        requireUniqueTargets(resolved.stream().map(ResolvedFixturePlan.Selection::target).toList());
        return List.copyOf(resolved);
    }

    private List<ResolvedFixturePlan.Selection> bindings(
            AuthoringScope scope, SimulationCommandV2 command, JsonNode input,
            SimulationCommandV2.FixturePlan.Bindings plan) {
        requireUniqueTargets(plan.bindings().stream().map(SimulationCommandV2.FixtureBinding::target).toList());
        List<ResolvedFixturePlan.Selection> resolved = new ArrayList<>();
        for (SimulationCommandV2.FixtureBinding binding : plan.bindings()) {
            requireSupportedTarget(binding.target());
            StoredFixtureSet stored = fixture(scope, binding.selection().fixtureSet());
            requireSubject(command, stored);
            FixtureSetCommand.Case fixtureCase = select(stored, binding.selection(), input);
            FixtureSetCommand.Control control = control(fixtureCase, binding.target());
            resolved.add(new ResolvedFixturePlan.Selection(binding.target(), binding.selection().fixtureSet(),
                    fixtureCase.caseId(), matchedBy(binding.selection()), control));
        }
        return List.copyOf(resolved);
    }

    private JsonNode input(AuthoringScope scope, SimulationCommandV2 command) {
        SimulationCommandV2.Input input = command.input();
        if (input instanceof SimulationCommandV2.Input.Inline inline) return inline.value();
        SimulationCommandV2.Input.CaseInput source = (SimulationCommandV2.Input.CaseInput) input;
        StoredFixtureSet stored = fixture(scope, source.fixtureSet());
        requireSubject(command, stored);
        return exactCase(stored, source.caseId()).input();
    }

    private StoredFixtureSet fixture(AuthoringScope scope, SimulationCommandV2.ExactFixtureSetRef reference) {
        if (reference == null) throw failure(FixturePlanFailure.Code.VALIDATION);
        StoredFixtureSet stored = fixtures.findRevision(scope, reference.fixtureSetId(), reference.revision())
                .orElseThrow(() -> failure(FixturePlanFailure.Code.FIXTURE_NOT_FOUND));
        FixtureSetView view = stored.generated().view();
        if (!reference.fingerprint().equals(view.fingerprint())) {
            throw failure(FixturePlanFailure.Code.FIXTURE_REFERENCE_MISMATCH);
        }
        if (view.status() == FixtureSetView.Status.STALE) {
            throw failure(FixturePlanFailure.Code.FIXTURE_STALE);
        }
        if (view.status() != FixtureSetView.Status.PRIVATE_DRAFT
                && view.status() != FixtureSetView.Status.TEAM_AVAILABLE) {
            throw failure(FixturePlanFailure.Code.MATERIAL_UNAVAILABLE);
        }
        requireConditionAuthority(view.cases());
        return stored;
    }

    private static void requireSubject(SimulationCommandV2 command, StoredFixtureSet stored) {
        if (!command.subject().equals(ExactFixtureSubjectRefV2.from(stored.generated().view().subject()))) {
            throw failure(FixturePlanFailure.Code.FIXTURE_SUBJECT_MISMATCH);
        }
    }

    private static FixtureSetCommand.Case select(
            StoredFixtureSet stored, SimulationCommandV2.FixtureSelection selection, JsonNode input) {
        List<FixtureSetCommand.Case> cases = stored.generated().view().cases();
        if (selection instanceof SimulationCommandV2.FixtureSelection.ExactCase exact) {
            return exactCase(stored, exact.caseId());
        }
        if (selection instanceof SimulationCommandV2.FixtureSelection.MatchCondition match) {
            List<FixtureSetCommand.Case> matchingId = cases.stream()
                    .filter(value -> value.when() != null
                            && match.conditionId().equals(value.when().conditionId())).toList();
            if (matchingId.size() != 1) throw failure(FixturePlanFailure.Code.CONDITION_NOT_FOUND);
            if (!matches(matchingId.getFirst().when(), input)) {
                throw failure(FixturePlanFailure.Code.CONDITION_NOT_SATISFIED);
            }
            return matchingId.getFirst();
        }
        List<FixtureSetCommand.Case> matches = cases.stream()
                .filter(value -> value.when() != null && matches(value.when(), input)).toList();
        if (matches.isEmpty()) throw failure(FixturePlanFailure.Code.AUTO_MATCH_EMPTY);
        if (matches.size() != 1) throw failure(FixturePlanFailure.Code.AUTO_MATCH_AMBIGUOUS);
        return matches.getFirst();
    }

    private static FixtureSetCommand.Case exactCase(StoredFixtureSet stored, String caseId) {
        List<FixtureSetCommand.Case> matches = stored.generated().view().cases().stream()
                .filter(value -> caseId.equals(value.caseId())).toList();
        if (matches.isEmpty()) throw failure(FixturePlanFailure.Code.CASE_NOT_FOUND);
        if (matches.size() != 1) throw failure(FixturePlanFailure.Code.INTEGRITY);
        return matches.getFirst();
    }

    private static FixtureSetCommand.Control control(
            FixtureSetCommand.Case fixtureCase, SimulationCommandV2.FixtureTarget target) {
        List<FixtureSetCommand.Control> matches = fixtureCase.controls().stream()
                .filter(value -> target(value.target()).equals(target)).toList();
        if (matches.isEmpty()) throw failure(FixturePlanFailure.Code.FIXTURE_SUBJECT_MISMATCH);
        if (matches.size() != 1) throw failure(FixturePlanFailure.Code.INTEGRITY);
        return matches.getFirst();
    }

    private static SimulationCommandV2.FixtureTarget target(FixtureSetCommand.Target target) {
        if (target instanceof FixtureSetCommand.Target.Subject) {
            return new SimulationCommandV2.FixtureTarget.Subject();
        }
        FixtureSetCommand.Target.Node node = (FixtureSetCommand.Target.Node) target;
        return new SimulationCommandV2.FixtureTarget.NodePath(List.of(node.nodeId()));
    }

    private static void requireSupportedTarget(SimulationCommandV2.FixtureTarget target) {
        if (!(target instanceof SimulationCommandV2.FixtureTarget.Subject)) {
            throw failure(FixturePlanFailure.Code.TARGET_UNSUPPORTED);
        }
    }

    private static boolean matches(FixtureSetCommand.Condition condition, JsonNode input) {
        return condition.all().stream().allMatch(predicate -> matches(predicate, input));
    }

    private static boolean matches(FixtureSetCommand.Predicate predicate, JsonNode input) {
        JsonNode value = at(input, predicate.path());
        if (predicate instanceof FixtureSetCommand.Predicate.Eq equal) return value.equals(equal.value());
        if (predicate instanceof FixtureSetCommand.Predicate.In in) return in.values().contains(value);
        if (predicate instanceof FixtureSetCommand.Predicate.Present) return !value.isMissingNode();
        if (predicate instanceof FixtureSetCommand.Predicate.Absent) return value.isMissingNode();
        FixtureSetCommand.Predicate.NumberRange range =
                (FixtureSetCommand.Predicate.NumberRange) predicate;
        if (!value.isNumber()) return false;
        BigDecimal number = value.decimalValue();
        return (range.minimum() == null || number.compareTo(range.minimum()) >= 0)
                && (range.maximum() == null || number.compareTo(range.maximum()) <= 0);
    }

    private static JsonNode at(JsonNode input, String path) {
        requirePath(path);
        JsonNode value = input;
        if ("$".equals(path)) return value;
        for (String segment : path.substring(2).split("\\.")) value = value.path(segment);
        return value;
    }

    private static void requireConditionAuthority(List<FixtureSetCommand.Case> cases) {
        Set<String> caseIds = new HashSet<>();
        Set<String> conditionIds = new HashSet<>();
        for (FixtureSetCommand.Case fixtureCase : cases) {
            requireIdentifier(fixtureCase.caseId());
            if (!caseIds.add(fixtureCase.caseId())) throw failure(FixturePlanFailure.Code.INTEGRITY);
            if (fixtureCase.when() == null) continue;
            requireIdentifier(fixtureCase.when().conditionId());
            if (!conditionIds.add(fixtureCase.when().conditionId()) || fixtureCase.when().all().isEmpty()
                    || fixtureCase.when().all().size() > 16) {
                throw failure(FixturePlanFailure.Code.INTEGRITY);
            }
            fixtureCase.when().all().forEach(predicate -> {
                requirePath(predicate.path());
                if ((predicate instanceof FixtureSetCommand.Predicate.In in
                        && (in.values().isEmpty() || in.values().size() > 32))
                        || (predicate instanceof FixtureSetCommand.Predicate.NumberRange range
                        && range.minimum() == null && range.maximum() == null)) {
                    throw failure(FixturePlanFailure.Code.INTEGRITY);
                }
            });
        }
    }

    private static void requireUniqueTargets(List<SimulationCommandV2.FixtureTarget> targets) {
        Set<SimulationCommandV2.FixtureTarget> seen = new HashSet<>();
        for (SimulationCommandV2.FixtureTarget target : targets) {
            if (!seen.add(target)) throw failure(FixturePlanFailure.Code.TARGET_OVERLAP);
            if ((target instanceof SimulationCommandV2.FixtureTarget.NodePath node
                    && targets.stream().anyMatch(other -> descendant(node.nodePath(), other)))
                    || (target instanceof SimulationCommandV2.FixtureTarget.CallSite call
                    && targets.stream().anyMatch(other -> ancestor(call.nodePath(), other)))) {
                throw failure(FixturePlanFailure.Code.TARGET_OVERLAP);
            }
        }
    }

    private static boolean descendant(List<String> parent, SimulationCommandV2.FixtureTarget target) {
        List<String> path = target instanceof SimulationCommandV2.FixtureTarget.NodePath node
                ? node.nodePath() : target instanceof SimulationCommandV2.FixtureTarget.CallSite call
                ? call.nodePath() : List.of();
        return path.size() > parent.size() && path.subList(0, parent.size()).equals(parent);
    }

    private static boolean ancestor(List<String> child, SimulationCommandV2.FixtureTarget target) {
        return target instanceof SimulationCommandV2.FixtureTarget.NodePath node
                && child.size() >= node.nodePath().size()
                && child.subList(0, node.nodePath().size()).equals(node.nodePath());
    }

    private static ResolvedFixturePlan.MatchedBy matchedBy(SimulationCommandV2.FixtureSelection selection) {
        if (selection instanceof SimulationCommandV2.FixtureSelection.ExactCase) {
            return ResolvedFixturePlan.MatchedBy.EXACT_CASE;
        }
        return selection instanceof SimulationCommandV2.FixtureSelection.MatchCondition
                ? ResolvedFixturePlan.MatchedBy.CONDITION : ResolvedFixturePlan.MatchedBy.AUTO_MATCH;
    }

    private static ResolvedFixturePlan plan(
            ExactFixtureSubjectRefV2 subject,
            JsonNode input, SimulationCommandV2.Unmatched unmatched,
            List<ResolvedFixturePlan.Selection> selections) {
        ObjectNode authority = JSON.createObjectNode();
        authority.set("subject", JSON.valueToTree(subject));
        authority.put("inputFingerprint", AuthoringFingerprints.of(input));
        authority.put("unmatched", unmatched.name());
        ArrayNode selected = authority.putArray("selections");
        selections.forEach(value -> {
            ObjectNode item = selected.addObject();
            item.set("target", JSON.valueToTree(value.target()));
            item.set("fixtureSet", JSON.valueToTree(value.fixtureSet()));
            item.put("caseId", value.caseId());
            item.put("matchedBy", value.matchedBy().name());
            item.put("behavior", value.control().behavior().kind());
            if (value.control().fidelity() != null) item.put("fidelity", value.control().fidelity().name());
        });
        return new ResolvedFixturePlan(subject, input, unmatched, selections,
                AuthoringFingerprints.of(authority));
    }

    private static void requireCommand(AuthoringScope scope, SimulationCommandV2 command) {
        if (scope == null || command == null || !SimulationCommandV2.SCHEMA_VERSION.equals(command.schemaVersion())
                || command.executionPolicy() == null
                || !(command.executionPolicy().externalWrites() instanceof SimulationCommandV2.ExternalWrites.Deny)) {
            throw failure(FixturePlanFailure.Code.VALIDATION);
        }
    }

    private static void requireIdentifier(String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw failure(FixturePlanFailure.Code.INTEGRITY);
        }
    }

    private static void requirePath(String path) {
        if (path == null || !PATH.matcher(path).matches()) {
            throw failure(FixturePlanFailure.Code.INTEGRITY);
        }
    }

    private static FixturePlanFailure failure(FixturePlanFailure.Code code) {
        return new FixturePlanFailure(code);
    }
}
