package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Frozen caller-directed simulation command whose business input and Fixture selection are independent.
 *
 * <p>The command carries only exact immutable coordinates. It never accepts Fixture output, protected
 * material, credentials, replay payloads, UI coordinates or runtime invocation keys. External writes
 * remain unrepresentable, while an unmatched {@code REAL} target still needs a separately authorized
 * external-read policy.</p>
 */
public record SimulationCommandV2(String schemaVersion, ExactFixtureSubjectRefV2 subject, Input input,
                                  FixturePlan fixturePlan, ExecutionPolicy executionPolicy) {
    public static final String SCHEMA_VERSION = "bloge.simulationCommand.v2";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    /** Applies only the schema-defined deny-all policy default. */
    public SimulationCommandV2 {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        if (subject == null || input == null || fixturePlan == null) {
            throw new IllegalArgumentException("simulation command is incomplete");
        }
        executionPolicy = executionPolicy == null ? ExecutionPolicy.denyAll() : executionPolicy;
    }

    /** Inline business input or the driver input of one exact saved Case. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Input.Inline.class, name = "INLINE"),
            @JsonSubTypes.Type(value = Input.CaseInput.class, name = "CASE_INPUT")
    })
    public sealed interface Input permits Input.Inline, Input.CaseInput {
        record Inline(JsonNode value) implements Input {
            public Inline { value = copy(value); }
            @Override public JsonNode value() { return copy(value); }
            @Override public String toString() { return "Inline[value=protected]"; }
        }

        record CaseInput(ExactFixtureSetRef fixtureSet, String caseId) implements Input {
            public CaseInput { requireIdentifier(caseId, "caseId"); }
        }
    }

    /** None, all controls from one saved Case, or explicit per-target bindings. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = FixturePlan.None.class, name = "NONE"),
            @JsonSubTypes.Type(value = FixturePlan.CaseControls.class, name = "CASE_CONTROLS"),
            @JsonSubTypes.Type(value = FixturePlan.Bindings.class, name = "BINDINGS")
    })
    public sealed interface FixturePlan permits FixturePlan.None, FixturePlan.CaseControls,
            FixturePlan.Bindings {
        record None() implements FixturePlan { }

        record CaseControls(ExactFixtureSetRef fixtureSet, String caseId, Unmatched unmatched)
                implements FixturePlan {
            public CaseControls {
                requireIdentifier(caseId, "caseId");
                unmatched = unmatched == null ? Unmatched.BLOCK : unmatched;
            }
        }

        record Bindings(Unmatched unmatched, List<FixtureBinding> bindings) implements FixturePlan {
            public Bindings {
                unmatched = unmatched == null ? Unmatched.BLOCK : unmatched;
                bindings = bindings == null ? List.of() : List.copyOf(bindings);
            }
            @Override public List<FixtureBinding> bindings() { return List.copyOf(bindings); }
        }
    }

    public enum Unmatched { BLOCK, REAL }

    /** One static target and its saved Fixture selection. */
    public record FixtureBinding(FixtureTarget target, FixtureSelection selection) {
        public FixtureBinding {
            if (target == null || selection == null) {
                throw new IllegalArgumentException("fixture binding is incomplete");
            }
        }
    }

    /** Static subject, nested node path, or compiled built-in function call site. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = FixtureTarget.Subject.class, name = "SUBJECT"),
            @JsonSubTypes.Type(value = FixtureTarget.NodePath.class, name = "NODE_PATH"),
            @JsonSubTypes.Type(value = FixtureTarget.CallSite.class, name = "CALL_SITE")
    })
    public sealed interface FixtureTarget permits FixtureTarget.Subject, FixtureTarget.NodePath,
            FixtureTarget.CallSite {
        record Subject() implements FixtureTarget { }

        record NodePath(List<String> nodePath) implements FixtureTarget {
            public NodePath { nodePath = requirePath(nodePath); }
            @Override public List<String> nodePath() { return List.copyOf(nodePath); }
        }

        record CallSite(List<String> nodePath, String callSiteId) implements FixtureTarget {
            public CallSite {
                nodePath = requirePath(nodePath);
                requireIdentifier(callSiteId, "callSiteId");
            }
            @Override public List<String> nodePath() { return List.copyOf(nodePath); }
        }
    }

    /** Exact, named-condition or unique automatic selection from one immutable Fixture Set revision. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = FixtureSelection.ExactCase.class, name = "EXACT_CASE"),
            @JsonSubTypes.Type(value = FixtureSelection.MatchCondition.class, name = "MATCH_CONDITION"),
            @JsonSubTypes.Type(value = FixtureSelection.AutoMatch.class, name = "AUTO_MATCH")
    })
    public sealed interface FixtureSelection permits FixtureSelection.ExactCase,
            FixtureSelection.MatchCondition, FixtureSelection.AutoMatch {
        ExactFixtureSetRef fixtureSet();

        record ExactCase(ExactFixtureSetRef fixtureSet, String caseId) implements FixtureSelection {
            public ExactCase { requireIdentifier(caseId, "caseId"); }
        }

        record MatchCondition(ExactFixtureSetRef fixtureSet, String conditionId)
                implements FixtureSelection {
            public MatchCondition { requireIdentifier(conditionId, "conditionId"); }
        }

        record AutoMatch(ExactFixtureSetRef fixtureSet) implements FixtureSelection { }
    }

    /** Exact Fixture Set content authority; heads are never accepted at runtime. */
    public record ExactFixtureSetRef(String fixtureSetId, int revision, String fingerprint) {
        public ExactFixtureSetRef {
            requireIdentifier(fixtureSetId, "fixtureSetId");
            if (revision < 1 || fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()) {
                throw new IllegalArgumentException("fixture reference is invalid");
            }
        }
    }

    /** Explicit network policy; the only external-write policy is deny. */
    public record ExecutionPolicy(ExternalReads externalReads, ExternalWrites externalWrites) {
        public ExecutionPolicy {
            externalReads = externalReads == null ? new ExternalReads.Deny() : externalReads;
            externalWrites = externalWrites == null ? new ExternalWrites.Deny() : externalWrites;
        }
        public static ExecutionPolicy denyAll() {
            return new ExecutionPolicy(new ExternalReads.Deny(), new ExternalWrites.Deny());
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ExternalReads.Deny.class, name = "DENY"),
            @JsonSubTypes.Type(value = ExternalReads.AllowExact.class, name = "ALLOW_EXACT")
    })
    public sealed interface ExternalReads permits ExternalReads.Deny, ExternalReads.AllowExact {
        record Deny() implements ExternalReads { }
        record AllowExact(List<ApiResourceSpec.ResourceRef> resources, String justification)
                implements ExternalReads {
            public AllowExact { resources = resources == null ? List.of() : List.copyOf(resources); }
            @Override public List<ApiResourceSpec.ResourceRef> resources() { return List.copyOf(resources); }
            @Override public String toString() {
                return "AllowExact[resources=" + resources.size() + ", justification=protected]";
            }
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(@JsonSubTypes.Type(value = ExternalWrites.Deny.class, name = "DENY"))
    public sealed interface ExternalWrites permits ExternalWrites.Deny {
        record Deny() implements ExternalWrites { }
    }

    /** Keeps business input out of logs. */
    @Override public String toString() {
        return "SimulationCommandV2[schemaVersion=" + schemaVersion + ", subject=" + subject
                + ", input=protected, fixturePlan=" + fixturePlan.getClass().getSimpleName()
                + ", executionPolicy=" + executionPolicy + "]";
    }

    private static List<String> requirePath(List<String> path) {
        if (path == null || path.isEmpty() || path.size() > 32) {
            throw new IllegalArgumentException("nodePath is invalid");
        }
        path.forEach(value -> requireIdentifier(value, "nodePath"));
        return List.copyOf(path);
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static JsonNode copy(JsonNode value) {
        return value == null ? NullNode.getInstance() : value.deepCopy();
    }
}
