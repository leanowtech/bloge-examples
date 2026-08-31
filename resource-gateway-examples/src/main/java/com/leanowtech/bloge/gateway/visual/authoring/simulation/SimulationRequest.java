package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;

import java.util.List;

/** Frozen request for one policy-bounded authoring simulation. */
public record SimulationRequest(String schemaVersion, Source source, ExecutionPolicy executionPolicy) {
    public static final String SCHEMA_VERSION = "bloge.simulationRequest.v1";

    /** Normalizes only the schema-defined default policy; source validation belongs to the module. */
    public SimulationRequest {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        executionPolicy = executionPolicy == null ? ExecutionPolicy.denyAll() : executionPolicy;
    }

    /** Creates the common private Fixture Case request. */
    public static SimulationRequest fixtureCase(String fixtureSetId, int revision, String caseId) {
        return new SimulationRequest(SCHEMA_VERSION, fixtureCaseSource(fixtureSetId, revision, caseId),
                ExecutionPolicy.denyAll());
    }

    /** Creates an ad-hoc request without weakening its exact subject coordinate. */
    public static SimulationRequest adHoc(FixtureSubjectRef subject, JsonNode input) {
        return new SimulationRequest(SCHEMA_VERSION, new Source.AdHoc(subject, input), ExecutionPolicy.denyAll());
    }

    /** Creates a Fixture Case source for callers that need an explicit policy. */
    public static Source fixtureCaseSource(String fixtureSetId, int revision, String caseId) {
        return new Source.FixtureCase(fixtureSetId, revision, caseId);
    }

    /** Fixture-backed or ad-hoc input union. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Source.FixtureCase.class, name = "FIXTURE_CASE"),
            @JsonSubTypes.Type(value = Source.AdHoc.class, name = "AD_HOC")
    })
    public sealed interface Source permits Source.FixtureCase, Source.AdHoc {
        record FixtureCase(String fixtureSetId, int revision, String caseId) implements Source { }
        record AdHoc(FixtureSubjectRef subject, JsonNode input) implements Source {
            public AdHoc { input = input == null ? null : input.deepCopy(); }
            @Override public JsonNode input() { return input == null ? null : input.deepCopy(); }
            @Override public String toString() { return "AdHoc[subject=" + subject + ", input=protected]"; }
        }
    }

    /** Explicit egress policy; writes are always denied by the v1 wire contract. */
    public record ExecutionPolicy(ExternalReads externalReads, ExternalWrites externalWrites) {
        public ExecutionPolicy {
            externalReads = externalReads == null ? new ExternalReads.Deny() : externalReads;
            externalWrites = externalWrites == null ? new ExternalWrites.Deny() : externalWrites;
        }
        public static ExecutionPolicy denyAll() {
            return new ExecutionPolicy(new ExternalReads.Deny(), new ExternalWrites.Deny());
        }
    }

    /** External read policy union. */
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
        }
    }

    /** V1 deliberately exposes only a deny write policy. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(@JsonSubTypes.Type(value = ExternalWrites.Deny.class, name = "DENY"))
    public sealed interface ExternalWrites permits ExternalWrites.Deny {
        record Deny() implements ExternalWrites { }
    }

    /** Keeps ad-hoc business input out of diagnostics. */
    @Override public String toString() {
        return "SimulationRequest[schemaVersion=" + schemaVersion + ", source=" + source
                + ", executionPolicy=" + executionPolicy + "]";
    }
}
