package com.leanowtech.bloge.gateway.visualadapter.authoring.simulation;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.ComponentSimulationAuthorityV2;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.ExactFixtureSubjectRefV2;
import com.leanowtech.bloge.gateway.visual.catalog.BuiltInFunctionCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.BuiltInFunctionContract;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Exact adapter over immutable Operator Library revisions and the server built-in catalog. */
public final class CatalogComponentSimulationAuthorityV2 implements ComponentSimulationAuthorityV2 {
    public static final String BUILTIN_CATALOG_ID = "bloge";
    public static final int BUILTIN_CATALOG_REVISION = 1;
    private final OperatorLibraryRegistry libraries;

    public CatalogComponentSimulationAuthorityV2(OperatorLibraryRegistry libraries) {
        this.libraries = Objects.requireNonNull(libraries, "libraries");
    }

    @Override
    public Optional<ComponentContract> resolve(AuthoringScope scope, ExactFixtureSubjectRefV2 subject) {
        if (scope == null || subject == null) return Optional.empty();
        if (subject instanceof ExactFixtureSubjectRefV2.OperatorVersion operator) {
            return operator(operator);
        }
        if (subject instanceof ExactFixtureSubjectRefV2.BuiltinFunctionVersion function) {
            return function(function);
        }
        return Optional.empty();
    }

    /** Returns the exact public built-in coordinate clients may persist in Fixture Sets. */
    public static ExactFixtureSubjectRefV2.BuiltinFunctionVersion builtinSubject(String functionName) {
        OperatorLibrary.BuiltInFunction function = BuiltInFunctionCatalog.defaults().stream()
                .filter(value -> value.name().equals(functionName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("built-in function is unknown"));
        String signature = BuiltInFunctionContract.callableFingerprint(function);
        return new ExactFixtureSubjectRefV2.BuiltinFunctionVersion(BUILTIN_CATALOG_ID,
                BUILTIN_CATALOG_REVISION, function.name(), signature, runtimeFingerprint(signature));
    }

    private Optional<ComponentContract> operator(ExactFixtureSubjectRefV2.OperatorVersion subject) {
        return libraries.findRevision(subject.libraryId(), subject.libraryRevision())
                .filter(revision -> revision.library() != null)
                .flatMap(revision -> revision.library().operators().stream()
                        .filter(Objects::nonNull)
                        .filter(value -> value.operatorRef().equals(subject.operatorRef()))
                        .filter(value -> value.fingerprint().equals(subject.contractFingerprint()))
                        .findFirst())
                .map(value -> new ComponentContract(ports(value.ports().inputs()),
                        ports(value.ports().outputs()), List.of()));
    }

    private static Optional<ComponentContract> function(
            ExactFixtureSubjectRefV2.BuiltinFunctionVersion subject) {
        if (!BUILTIN_CATALOG_ID.equals(subject.catalogId())
                || BUILTIN_CATALOG_REVISION != subject.catalogRevision()) return Optional.empty();
        return BuiltInFunctionCatalog.defaults().stream()
                .filter(value -> value.name().equals(subject.functionName()))
                .filter(value -> BuiltInFunctionContract.callableFingerprint(value)
                        .equals(subject.signatureFingerprint()))
                .filter(value -> runtimeFingerprint(subject.signatureFingerprint())
                        .equals(subject.runtimeFingerprint()))
                .findFirst().map(value -> new ComponentContract(
                        SchemaEnvelope.opaque(), SchemaEnvelope.opaque(), List.of()));
    }

    private static SchemaEnvelope ports(List<OperatorDefinition.Port> ports) {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        java.util.ArrayList<String> required = new java.util.ArrayList<>();
        for (OperatorDefinition.Port port : ports) {
            properties.put(port.name(), port.schema().schema());
            if (port.required()) required.add(port.name());
        }
        return SchemaEnvelope.object(properties, required);
    }

    private static String runtimeFingerprint(String signatureFingerprint) {
        return AuthoringFingerprints.of(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(Map.of(
                "catalogId", BUILTIN_CATALOG_ID,
                "catalogRevision", BUILTIN_CATALOG_REVISION,
                "signatureFingerprint", signatureFingerprint,
                "runtimeGeneration", "bloge-expression-runtime.v1")));
    }
}
