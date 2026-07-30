package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Callable identity and compatibility rules for BLOGE expression functions.
 */
public final class BuiltInFunctionContract {

    private BuiltInFunctionContract() {
    }

    /**
     * The expression language resolves a function by its callable name. Namespace is
     * provenance metadata and cannot make two different callable contracts coexist.
     */
    public static String callableName(OperatorLibrary.BuiltInFunction function) {
        return function == null ? "" : function.name();
    }

    /**
     * Fingerprints callable semantics while excluding display-only metadata.
     */
    public static String callableFingerprint(OperatorLibrary.BuiltInFunction function) {
        if (function == null) {
            return VisualBundleFingerprint.fromMaterial(Map.of());
        }
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("name", function.name());
        List<Map<String, Object>> signatures = new ArrayList<>();
        for (OperatorLibrary.Signature signature : function.signatures()) {
            signatures.add(signatureMaterial(signature));
        }
        material.put("signatures", signatures);
        return VisualBundleFingerprint.fromMaterial(material);
    }

    public static boolean compatible(OperatorLibrary.BuiltInFunction left,
                                     OperatorLibrary.BuiltInFunction right) {
        return !callableName(left).isBlank()
                && callableName(left).equals(callableName(right))
                && callableFingerprint(left).equals(callableFingerprint(right));
    }

    /**
     * Enforces callable ownership for registry mutations independently of the HTTP
     * validation path.
     */
    public static void ensureNoLibraryCallableConflicts(Collection<OperatorLibrary> existingLibraries,
                                                        OperatorLibrary candidate) {
        Objects.requireNonNull(candidate, "candidate library is required");
        Map<String, FunctionOwner> ownerByCallableName = new LinkedHashMap<>();
        for (OperatorLibrary.BuiltInFunction function : BuiltInFunctionCatalog.defaults()) {
            ownerByCallableName.putIfAbsent(callableName(function), new FunctionOwner("builtin", function));
        }
        if (existingLibraries != null) {
            existingLibraries.stream()
                    .filter(Objects::nonNull)
                    .filter(existing -> !existing.libraryId().equals(candidate.libraryId()))
                    .sorted(Comparator.comparing(OperatorLibrary::libraryId))
                    .forEach(existing -> existing.builtInFunctions().stream()
                            .filter(Objects::nonNull)
                            .forEach(function -> ownerByCallableName.putIfAbsent(
                                    callableName(function),
                                    new FunctionOwner(existing.libraryId(), function))));
        }
        Set<String> candidateCallableNames = new LinkedHashSet<>();
        for (OperatorLibrary.BuiltInFunction function : candidate.builtInFunctions()) {
            if (function == null || function.name().isBlank()) {
                continue;
            }
            if (!candidateCallableNames.add(function.name())) {
                throw new IllegalArgumentException("callable function '%s' is declared more than once by library '%s'"
                        .formatted(function.name(), candidate.libraryId()));
            }
            FunctionOwner existing = ownerByCallableName.get(function.name());
            if (existing != null && !compatible(existing.function(), function)) {
                throw new IllegalArgumentException("callable function '%s' conflicts with library '%s'"
                        .formatted(function.name(), existing.libraryId()));
            }
            ownerByCallableName.putIfAbsent(function.name(), new FunctionOwner(candidate.libraryId(), function));
        }
    }

    private static Map<String, Object> signatureMaterial(OperatorLibrary.Signature signature) {
        if (signature == null) {
            return Map.of();
        }
        Map<String, Object> material = new LinkedHashMap<>();
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (OperatorLibrary.Parameter parameter : signature.parameters()) {
            parameters.add(parameterMaterial(parameter));
        }
        material.put("parameters", parameters);
        material.put("returns", returnMaterial(signature.returns()));
        return material;
    }

    private static Map<String, Object> parameterMaterial(OperatorLibrary.Parameter parameter) {
        if (parameter == null) {
            return Map.of();
        }
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("name", parameter.name());
        material.put("type", parameter.type());
        material.put("schema", schemaMaterial(parameter.schema()));
        material.put("optional", parameter.optional());
        material.put("variadic", parameter.variadic());
        return material;
    }

    private static Map<String, Object> returnMaterial(OperatorLibrary.ReturnValue returns) {
        if (returns == null) {
            return Map.of();
        }
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("type", returns.type());
        material.put("schema", schemaMaterial(returns.schema()));
        return material;
    }

    private static Map<String, Object> schemaMaterial(SchemaEnvelope envelope) {
        if (envelope == null) {
            return Map.of();
        }
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("format", envelope.format());
        material.put("version", envelope.version());
        material.put("schema", envelope.schema());
        return material;
    }

    private record FunctionOwner(String libraryId, OperatorLibrary.BuiltInFunction function) {
    }
}
