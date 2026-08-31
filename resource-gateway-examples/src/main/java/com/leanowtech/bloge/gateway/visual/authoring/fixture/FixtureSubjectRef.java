package com.leanowtech.bloge.gateway.visual.authoring.fixture;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;

import java.util.Objects;
import java.util.regex.Pattern;

/** Exact immutable subject coordinate shared by Fixture Set commands and views. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = FixtureSubjectRef.ApiResource.class, name = "API_RESOURCE"),
        @JsonSubTypes.Type(value = FixtureSubjectRef.FlowDraft.class, name = "FLOW_DRAFT"),
        @JsonSubTypes.Type(value = FixtureSubjectRef.FlowVersion.class, name = "FLOW_VERSION")
})
public sealed interface FixtureSubjectRef
        permits FixtureSubjectRef.ApiResource, FixtureSubjectRef.FlowDraft, FixtureSubjectRef.FlowVersion {
    Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    /** @return stable wire discriminator */
    default String kind() {
        if (this instanceof ApiResource) return "API_RESOURCE";
        return this instanceof FlowDraft ? "FLOW_DRAFT" : "FLOW_VERSION";
    }

    /** Exact API Resource revision. */
    record ApiResource(String resourceId, int revision, String fingerprint) implements FixtureSubjectRef {
        public ApiResource { require(resourceId, revision, fingerprint); }
    }

    /** Exact reusable Flow draft revision. */
    record FlowDraft(String draftId, int revision, String fingerprint) implements FixtureSubjectRef {
        public FlowDraft { require(draftId, revision, fingerprint); }
    }

    /** Exact published reusable Flow revision. */
    record FlowVersion(String publicationId, int revision, String fingerprint) implements FixtureSubjectRef {
        public FlowVersion { require(publicationId, revision, fingerprint); }
    }

    /** Converts the existing Resource authority coordinate without weakening it. */
    static FixtureSubjectRef apiResource(ApiResourceSpec.ResourceRef ref) {
        Objects.requireNonNull(ref, "ref");
        if (!"API_RESOURCE".equals(ref.kind())) throw new IllegalArgumentException("subject kind is invalid");
        return new ApiResource(ref.resourceId(), ref.revision(), ref.fingerprint());
    }

    private static void require(String id, int revision, String fingerprint) {
        if (id == null || !IDENTIFIER.matcher(id).matches() || revision < 1
                || fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("fixture subject is invalid");
        }
    }
}
