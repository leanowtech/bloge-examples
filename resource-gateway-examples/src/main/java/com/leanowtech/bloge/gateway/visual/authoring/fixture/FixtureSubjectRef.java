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
        @JsonSubTypes.Type(value = FixtureSubjectRef.FlowVersion.class, name = "FLOW_VERSION"),
        @JsonSubTypes.Type(value = FixtureSubjectRef.OperatorVersion.class, name = "OPERATOR_VERSION"),
        @JsonSubTypes.Type(value = FixtureSubjectRef.BuiltinFunctionVersion.class,
                name = "BUILTIN_FUNCTION_VERSION")
})
public sealed interface FixtureSubjectRef
        permits FixtureSubjectRef.ApiResource, FixtureSubjectRef.FlowDraft, FixtureSubjectRef.FlowVersion,
        FixtureSubjectRef.OperatorVersion, FixtureSubjectRef.BuiltinFunctionVersion {
    Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    /** @return stable wire discriminator */
    default String kind() {
        if (this instanceof ApiResource) return "API_RESOURCE";
        if (this instanceof FlowDraft) return "FLOW_DRAFT";
        if (this instanceof FlowVersion) return "FLOW_VERSION";
        return this instanceof OperatorVersion ? "OPERATOR_VERSION" : "BUILTIN_FUNCTION_VERSION";
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

    /** Exact operator-library revision and executable contract. */
    record OperatorVersion(String libraryId, int libraryRevision, String operatorRef,
                           String contractFingerprint) implements FixtureSubjectRef {
        public OperatorVersion {
            require(libraryId, libraryRevision, contractFingerprint);
            requireIdentifier(operatorRef);
        }
    }

    /** Exact built-in callable signature and runtime implementation generation. */
    record BuiltinFunctionVersion(String catalogId, int catalogRevision, String functionName,
                                  String signatureFingerprint, String runtimeFingerprint)
            implements FixtureSubjectRef {
        public BuiltinFunctionVersion {
            require(catalogId, catalogRevision, signatureFingerprint);
            requireIdentifier(functionName);
            if (runtimeFingerprint == null || !FINGERPRINT.matcher(runtimeFingerprint).matches()) {
                throw new IllegalArgumentException("fixture subject is invalid");
            }
        }
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

    private static void requireIdentifier(String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("fixture subject is invalid");
        }
    }
}
