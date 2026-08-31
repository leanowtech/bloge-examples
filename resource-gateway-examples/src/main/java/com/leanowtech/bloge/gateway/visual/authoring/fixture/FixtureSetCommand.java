package com.leanowtech.bloge.gateway.visual.authoring.fixture;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.List;

/** Frozen editable Fixture Set content; identity and revisions are server-owned. */
public record FixtureSetCommand(String schemaVersion, String displayName, FixtureSubjectRef subject,
                                List<Case> cases) {
    public static final String SCHEMA_VERSION = "bloge.fixtureSetCommand.v1";

    public FixtureSetCommand {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        cases = cases == null ? List.of() : cases.stream().map(Case::copy).toList();
    }

    @Override public List<Case> cases() { return cases.stream().map(Case::copy).toList(); }

    /** Keeps Case input, expected output and inline material out of logs. */
    @Override public String toString() {
        return "FixtureSetCommand[schemaVersion=" + schemaVersion + ", displayName=" + displayName
                + ", subject=" + subject + ", cases=" + cases.size() + "]";
    }

    /** One reusable input and its explicit runtime controls. */
    public record Case(String caseId, String name, JsonNode input, List<Control> controls,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Expect expect) {
        public Case {
            input = copyNode(input);
            controls = controls == null ? List.of() : List.copyOf(controls);
            expect = expect == null ? null : new Expect(expect.output());
        }

        @Override public JsonNode input() { return copyNode(input); }
        @Override public List<Control> controls() { return List.copyOf(controls); }
        @Override public Expect expect() { return expect == null ? null : new Expect(expect.output()); }
        @Override public String toString() {
            return "Case[caseId=" + caseId + ", name=" + name + ", input=protected, controls="
                    + controls.size() + ", expect=" + (expect == null ? "absent" : "protected") + "]";
        }
        private Case copy() { return new Case(caseId, name, input, controls, expect); }
    }

    /** Optional expected whole output; it is assertion evidence, not a control. */
    public record Expect(JsonNode output) {
        public Expect { output = copyNode(output); }
        @Override public JsonNode output() { return copyNode(output); }
        @Override public String toString() { return "Expect[output=protected]"; }
    }

    /** Exact target behavior and optional fidelity. */
    public record Control(Target target, Behavior behavior,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Fidelity fidelity) { }

    /** Supported Fixture fidelity levels. */
    public enum Fidelity { OUTPUT_LEVEL, PROTOCOL_DERIVED, TRANSPORT_LEVEL }

    /** Subject or exact internal node target. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Target.Subject.class, name = "SUBJECT"),
            @JsonSubTypes.Type(value = Target.Node.class, name = "NODE")
    })
    public sealed interface Target permits Target.Subject, Target.Node {
        default String kind() { return this instanceof Subject ? "SUBJECT" : "NODE"; }
        record Subject() implements Target { }
        record Node(String nodeId) implements Target { }
        static Target subject() { return new Subject(); }
        static Target node(String nodeId) { return new Node(nodeId); }
    }

    /** Inline or governed-asset material. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Material.Inline.class, name = "INLINE"),
            @JsonSubTypes.Type(value = Material.FixtureAsset.class, name = "FIXTURE_ASSET")
    })
    public sealed interface Material permits Material.Inline, Material.FixtureAsset {
        default String kind() { return this instanceof Inline ? "INLINE" : "FIXTURE_ASSET"; }
        record Inline(JsonNode value) implements Material {
            public Inline { value = copyNode(value); }
            @Override public JsonNode value() { return copyNode(value); }
            @Override public String toString() { return "Inline[value=protected]"; }
        }
        record FixtureAsset(String fixtureAssetId, int revision, String schemaFingerprint)
                implements Material { }
        static Material inline(JsonNode value) { return new Inline(value); }
    }

    /** Explicit runtime behavior union; no implicit self-mocking is permitted. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Behavior.Real.class, name = "REAL"),
            @JsonSubTypes.Type(value = Behavior.Return.class, name = "RETURN"),
            @JsonSubTypes.Type(value = Behavior.ApplyCase.class, name = "APPLY_CASE"),
            @JsonSubTypes.Type(value = Behavior.Error.class, name = "ERROR"),
            @JsonSubTypes.Type(value = Behavior.Timeout.class, name = "TIMEOUT"),
            @JsonSubTypes.Type(value = Behavior.Replay.class, name = "REPLAY")
    })
    public sealed interface Behavior permits Behavior.Real, Behavior.Return, Behavior.ApplyCase,
            Behavior.Error, Behavior.Timeout, Behavior.Replay {
        default String kind() {
            if (this instanceof Real) return "REAL";
            if (this instanceof Return) return "RETURN";
            if (this instanceof ApplyCase) return "APPLY_CASE";
            if (this instanceof Error) return "ERROR";
            return this instanceof Timeout ? "TIMEOUT" : "REPLAY";
        }
        record Real() implements Behavior { }
        record Return(Material material) implements Behavior { }
        record ApplyCase(String fixtureSetId, int revision, String caseId) implements Behavior { }
        record Error(String code, String message) implements Behavior {
            @Override public String toString() { return "Error[code=" + code + ", message=protected]"; }
        }
        record Timeout(long afterMs) implements Behavior { }
        record Replay(String replayId, String fingerprint) implements Behavior { }
        static Behavior returned(Material material) { return new Return(material); }
    }

    private static JsonNode copyNode(JsonNode value) {
        return value == null ? NullNode.getInstance() : value.deepCopy();
    }
}
