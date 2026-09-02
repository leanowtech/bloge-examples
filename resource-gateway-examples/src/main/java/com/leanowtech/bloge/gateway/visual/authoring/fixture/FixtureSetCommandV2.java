package com.leanowtech.bloge.gateway.visual.authoring.fixture;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.ExactFixtureSubjectRefV2;

import java.util.List;

/**
 * Frozen v2 Fixture Set wire command separating driver input from runtime match conditions.
 *
 * <p>This transport model does not reinterpret assertions as match conditions and does not expose
 * mutable Fixture heads. The current persistence adapter can materialize the three v1 subject kinds;
 * operator and function subjects remain valid v2 wire authorities and are connected by their own
 * implementation slices.</p>
 */
public record FixtureSetCommandV2(String schemaVersion, String displayName,
                                  ExactFixtureSubjectRefV2 subject, List<Case> cases) {
    public static final String SCHEMA_VERSION = "bloge.fixtureSetCommand.v2";

    public FixtureSetCommandV2 {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        cases = cases == null ? List.of() : cases.stream().map(Case::copy).toList();
    }

    @Override public List<Case> cases() { return cases.stream().map(Case::copy).toList(); }

    /** Converts supported subjects into the existing immutable Fixture authority without data loss. */
    public FixtureSetCommand toAuthority() {
        return new FixtureSetCommand(FixtureSetCommand.SCHEMA_VERSION, displayName,
                subject.toLegacyAuthority(), cases.stream().map(Case::toAuthority).toList());
    }

    /** Preserves existing Fixture content while exposing the v2 driver-input name on the wire. */
    public static FixtureSetCommandV2 fromAuthority(FixtureSetCommand command) {
        return new FixtureSetCommandV2(SCHEMA_VERSION, command.displayName(),
                ExactFixtureSubjectRefV2.from(command.subject()),
                command.cases().stream().map(Case::fromAuthority).toList());
    }

    /** Keeps driver inputs, assertions and inline Fixture material out of logs. */
    @Override public String toString() {
        return "FixtureSetCommandV2[schemaVersion=" + schemaVersion + ", displayName=" + displayName
                + ", subject=" + subject + ", cases=" + cases.size() + "]";
    }

    /** One reusable Case with independent input, condition, controls and expected output. */
    public record Case(String caseId, String name,
                       @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode driverInput,
                       @JsonInclude(JsonInclude.Include.NON_NULL) FixtureSetCommand.Condition when,
                       List<FixtureSetCommand.Control> controls,
                       @JsonInclude(JsonInclude.Include.NON_NULL) FixtureSetCommand.Expect expect) {
        public Case {
            driverInput = copyNullable(driverInput);
            when = when == null ? null : new FixtureSetCommand.Condition(when.conditionId(), when.all());
            controls = controls == null ? List.of() : List.copyOf(controls);
            expect = expect == null ? null : new FixtureSetCommand.Expect(expect.output());
        }

        @Override public JsonNode driverInput() { return copyNullable(driverInput); }
        @Override public FixtureSetCommand.Condition when() {
            return when == null ? null : new FixtureSetCommand.Condition(when.conditionId(), when.all());
        }
        @Override public List<FixtureSetCommand.Control> controls() { return List.copyOf(controls); }
        @Override public FixtureSetCommand.Expect expect() {
            return expect == null ? null : new FixtureSetCommand.Expect(expect.output());
        }

        private FixtureSetCommand.Case toAuthority() {
            return new FixtureSetCommand.Case(caseId, name,
                    driverInput == null ? NullNode.getInstance() : driverInput, when, controls, expect);
        }

        private static Case fromAuthority(FixtureSetCommand.Case value) {
            return new Case(value.caseId(), value.name(), value.input(), value.when(),
                    value.controls(), value.expect());
        }

        private Case copy() { return new Case(caseId, name, driverInput, when, controls, expect); }

        @Override public String toString() {
            return "Case[caseId=" + caseId + ", name=" + name + ", driverInput="
                    + (driverInput == null ? "absent" : "protected") + ", when="
                    + (when == null ? "absent" : when.conditionId()) + ", controls="
                    + controls.size() + ", expect=" + (expect == null ? "absent" : "protected") + "]";
        }
    }

    private static JsonNode copyNullable(JsonNode value) {
        return value == null || value.isNull() ? null : value.deepCopy();
    }
}
