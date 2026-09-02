package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;

import java.util.List;

/**
 * Immutable server-compiled plan for one simulation command.
 *
 * <p>The Interface exposes exact Fixture coordinates and resolved Case ids while keeping inline and
 * protected material behind the module. Runtime adapters consume the selected control, but transport
 * and logs must never serialize this internal value.</p>
 */
public record ResolvedFixturePlan(ExactFixtureSubjectRefV2 subject, JsonNode input,
                                  SimulationCommandV2.Unmatched unmatched,
                                  List<Selection> selections, String fingerprint) {
    public ResolvedFixturePlan {
        input = input == null ? NullNode.getInstance() : input.deepCopy();
        selections = selections == null ? List.of() : List.copyOf(selections);
    }

    @Override public JsonNode input() { return input.deepCopy(); }
    @Override public List<Selection> selections() { return List.copyOf(selections); }

    /** Keeps driver input and selected material out of diagnostics. */
    @Override public String toString() {
        return "ResolvedFixturePlan[subject=" + subject + ", input=protected, unmatched=" + unmatched
                + ", selections=" + selections.size() + ", fingerprint=" + fingerprint + "]";
    }

    public enum MatchedBy { EXACT_CASE, CONDITION, AUTO_MATCH, CASE_CONTROLS }

    /** Exact per-target selection. The control is an internal execution value, never wire evidence. */
    public record Selection(SimulationCommandV2.FixtureTarget target,
                            SimulationCommandV2.ExactFixtureSetRef fixtureSet,
                            String caseId, MatchedBy matchedBy,
                            FixtureSetCommand.Control control) { }
}
