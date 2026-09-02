package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;

import java.util.regex.Pattern;

/**
 * Exact immutable subject authority accepted by caller-directed simulation v2.
 *
 * <p>Operator and built-in function identities intentionally include their contract and runtime
 * fingerprints. A friendly name, mutable catalog head or UI canvas id is never sufficient authority
 * for selecting reusable Fixture material.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ExactFixtureSubjectRefV2.ApiResource.class, name = "API_RESOURCE"),
        @JsonSubTypes.Type(value = ExactFixtureSubjectRefV2.FlowDraft.class, name = "FLOW_DRAFT"),
        @JsonSubTypes.Type(value = ExactFixtureSubjectRefV2.FlowVersion.class, name = "FLOW_VERSION"),
        @JsonSubTypes.Type(value = ExactFixtureSubjectRefV2.OperatorVersion.class,
                name = "OPERATOR_VERSION"),
        @JsonSubTypes.Type(value = ExactFixtureSubjectRefV2.BuiltinFunctionVersion.class,
                name = "BUILTIN_FUNCTION_VERSION")
})
public sealed interface ExactFixtureSubjectRefV2 permits ExactFixtureSubjectRefV2.ApiResource,
        ExactFixtureSubjectRefV2.FlowDraft, ExactFixtureSubjectRefV2.FlowVersion,
        ExactFixtureSubjectRefV2.OperatorVersion, ExactFixtureSubjectRefV2.BuiltinFunctionVersion {
    Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    /** Existing exact API Resource authority. */
    record ApiResource(String resourceId, int revision, String fingerprint)
            implements ExactFixtureSubjectRefV2 {
        public ApiResource { require(resourceId, revision, fingerprint); }
    }

    /** Existing exact reusable Flow draft authority. */
    record FlowDraft(String draftId, int revision, String fingerprint)
            implements ExactFixtureSubjectRefV2 {
        public FlowDraft { require(draftId, revision, fingerprint); }
    }

    /** Existing exact published reusable Flow authority. */
    record FlowVersion(String publicationId, int revision, String fingerprint)
            implements ExactFixtureSubjectRefV2 {
        public FlowVersion { require(publicationId, revision, fingerprint); }
    }

    /** Exact operator library revision and compiled contract. */
    record OperatorVersion(String libraryId, int libraryRevision, String operatorRef,
                           String contractFingerprint) implements ExactFixtureSubjectRefV2 {
        public OperatorVersion {
            requireIdentifier(libraryId);
            requireIdentifier(operatorRef);
            requireRevision(libraryRevision);
            requireFingerprint(contractFingerprint);
        }
    }

    /** Exact built-in function catalog, signature and runtime implementation. */
    record BuiltinFunctionVersion(String catalogId, int catalogRevision, String functionName,
                                  String signatureFingerprint, String runtimeFingerprint)
            implements ExactFixtureSubjectRefV2 {
        public BuiltinFunctionVersion {
            requireIdentifier(catalogId);
            requireIdentifier(functionName);
            requireRevision(catalogRevision);
            requireFingerprint(signatureFingerprint);
            requireFingerprint(runtimeFingerprint);
        }
    }

    /** Converts the v1 authorities without weakening their exact coordinates. */
    static ExactFixtureSubjectRefV2 from(FixtureSubjectRef subject) {
        if (subject instanceof FixtureSubjectRef.ApiResource value) {
            return new ApiResource(value.resourceId(), value.revision(), value.fingerprint());
        }
        if (subject instanceof FixtureSubjectRef.FlowDraft value) {
            return new FlowDraft(value.draftId(), value.revision(), value.fingerprint());
        }
        FixtureSubjectRef.FlowVersion value = (FixtureSubjectRef.FlowVersion) subject;
        return new FlowVersion(value.publicationId(), value.revision(), value.fingerprint());
    }

    /** Converts authorities supported by the v1 persistence layer; later subject slices add stores. */
    default FixtureSubjectRef toLegacyAuthority() {
        if (this instanceof ApiResource value) {
            return new FixtureSubjectRef.ApiResource(value.resourceId(), value.revision(), value.fingerprint());
        }
        if (this instanceof FlowDraft value) {
            return new FixtureSubjectRef.FlowDraft(value.draftId(), value.revision(), value.fingerprint());
        }
        if (this instanceof FlowVersion value) {
            return new FixtureSubjectRef.FlowVersion(
                    value.publicationId(), value.revision(), value.fingerprint());
        }
        throw new IllegalStateException("subject persistence adapter is unavailable");
    }

    private static void require(String id, int revision, String fingerprint) {
        requireIdentifier(id);
        requireRevision(revision);
        requireFingerprint(fingerprint);
    }

    private static void requireIdentifier(String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("subject identifier is invalid");
        }
    }

    private static void requireRevision(int revision) {
        if (revision < 1) throw new IllegalArgumentException("subject revision is invalid");
    }

    private static void requireFingerprint(String value) {
        if (value == null || !FINGERPRINT.matcher(value).matches()) {
            throw new IllegalArgumentException("subject fingerprint is invalid");
        }
    }
}
