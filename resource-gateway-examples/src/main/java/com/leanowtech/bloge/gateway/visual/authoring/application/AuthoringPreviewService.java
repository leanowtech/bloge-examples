package com.leanowtech.bloge.gateway.visual.authoring.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.compile.AuthoringCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.discovery.AuthoringFactProjection;
import com.leanowtech.bloge.gateway.visual.authoring.discovery.RuntimeParityService;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringCompileResult;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDiagnostic;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.model.VisualLibraryAuthoringDocument;
import com.leanowtech.bloge.gateway.visual.catalog.BuiltInFunctionCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.BuiltInFunctionContract;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryDiff;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryImpactReview;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stateless authoritative preview orchestration over the pure authoring compiler and target registry.
 */
@Service
public final class AuthoringPreviewService {

    private static final int MAXIMUM_CATALOG_ENTRY_BYTES = 10 * 1024 * 1024;

    private final AuthoringCompiler compiler;
    private final OperatorLibraryRegistry registry;
    private final ObjectMapper objectMapper;
    private final RuntimeParityService parityService;

    @Autowired
    public AuthoringPreviewService(AuthoringCompiler compiler,
                                   OperatorLibraryRegistry registry,
                                   ObjectMapper objectMapper,
                                   RuntimeParityService parityService) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.parityService = parityService;
    }

    /**
     * Backward-compatible constructor for focused compiler/registry unit tests.
     */
    public AuthoringPreviewService(AuthoringCompiler compiler,
                                   OperatorLibraryRegistry registry,
                                   ObjectMapper objectMapper) {
        this(compiler, registry, objectMapper, null);
    }

    public AuthoringCompileResult preview(VisualLibraryAuthoringDocument document) {
        AuthoringCompileResult compiled = compiler.compile(document);
        OperatorLibrary candidate = compiled.canonicalLibrary();
        List<AuthoringDiagnostic> registryDiagnostics = candidate == null
                ? List.of()
                : registryDiagnostics(candidate);
        OperatorLibraryDiff diff = candidate == null ? null : diff(candidate);
        OperatorLibraryImpactReview impact = candidate == null
                ? OperatorLibraryImpactReview.empty()
                : OperatorLibraryImpactReview.fromDiagnostics(
                        List.of(),
                        candidate.operators().stream()
                                .filter(Objects::nonNull)
                                .map(OperatorDefinition::operatorRef)
                                .toList());
        RuntimeParityService.Snapshot parity = candidate == null || parityService == null
                ? new RuntimeParityService.Snapshot("", List.of())
                : parityService.evaluate(candidate);
        AuthoringReadiness readiness = readiness(compiled, registryDiagnostics, parity.parity());
        return compiled.withPreviewContext(
                catalogFingerprint(),
                registryDiagnostics,
                diff,
                impact,
                readiness,
                parity.inventoryFingerprint(),
                parity.parity()
        );
    }

    public String catalogFingerprint() {
        List<Map<String, Object>> builtinFunctions = BuiltInFunctionCatalog.defaults().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(BuiltInFunctionContract::callableName))
                .map(function -> Map.<String, Object>of(
                        "callableName", BuiltInFunctionContract.callableName(function),
                        "fingerprint", BuiltInFunctionContract.callableFingerprint(function)
                ))
                .toList();
        List<Map<String, Object>> libraries = registry.all().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(OperatorLibrary::libraryId))
                .map(library -> Map.<String, Object>of(
                        "libraryId", library.libraryId(),
                        "fingerprint", VisualBundleFingerprint.fromCanonicalValue(
                                objectMapper, library, MAXIMUM_CATALOG_ENTRY_BYTES)
                ))
                .toList();
        return VisualBundleFingerprint.fromMaterial(Map.of(
                "builtinFunctions", builtinFunctions,
                "libraries", libraries
        ));
    }

    private List<AuthoringDiagnostic> registryDiagnostics(OperatorLibrary candidate) {
        List<AuthoringDiagnostic> diagnostics = new ArrayList<>();
        for (BuiltInFunctionContract.CallableConflict conflict :
                BuiltInFunctionContract.libraryCallableConflicts(registry.all(), candidate)) {
            diagnostics.add(AuthoringDiagnostic.compiler(
                    "ERROR",
                    "RG.AUTHORING.FUNCTION_CALLABLE_CONFLICT",
                    conflict.message(),
                    "/functions/" + pointer(conflict.callableName()),
                    -1,
                    Map.of(
                            "callableName", conflict.callableName(),
                            "candidateLibraryId", conflict.candidateLibraryId(),
                            "existingLibraryId", conflict.existingLibraryId(),
                            "reason", conflict.reason()
                    )
            ));
        }

        Map<String, String> owners = new LinkedHashMap<>();
        registry.all().stream()
                .filter(Objects::nonNull)
                .filter(existing -> !existing.libraryId().equals(candidate.libraryId()))
                .sorted(Comparator.comparing(OperatorLibrary::libraryId))
                .forEach(existing -> existing.operators().stream()
                        .filter(Objects::nonNull)
                        .forEach(operator -> owners.putIfAbsent(
                                operator.operatorRef(), existing.libraryId())));
        for (OperatorDefinition operator : candidate.operators()) {
            if (operator == null) {
                continue;
            }
            String owner = owners.get(operator.operatorRef());
            if (owner != null) {
                diagnostics.add(AuthoringDiagnostic.compiler(
                        "ERROR",
                        "RG.AUTHORING.OPERATOR_REF_CONFLICT",
                        "operatorRef '%s' is already provided by library '%s'."
                                .formatted(operator.operatorRef(), owner),
                        "/operators/" + pointer(operator.operatorRef()),
                        -1,
                        Map.of(
                                "operatorRef", operator.operatorRef(),
                                "existingLibraryId", owner
                        )
                ));
            }
        }
        return diagnostics.stream()
                .sorted(Comparator.comparing(AuthoringDiagnostic::authoringPath)
                        .thenComparing(AuthoringDiagnostic::code))
                .toList();
    }

    private OperatorLibraryDiff diff(OperatorLibrary candidate) {
        OperatorLibrary current = registry.find(candidate.libraryId()).orElse(null);
        long baseRevision = registry.revisions(candidate.libraryId()).stream()
                .mapToLong(revision -> revision.revision())
                .max()
                .orElse(0L);
        return OperatorLibraryDiff.betweenSnapshots(
                candidate.libraryId(),
                baseRevision,
                current,
                0L,
                candidate
        );
    }

    private static AuthoringReadiness readiness(AuthoringCompileResult compiled,
                                                List<AuthoringDiagnostic> additionalDiagnostics,
                                                List<AuthoringFactProjection.RuntimeParity> runtimeParity) {
        List<AuthoringDiagnostic> diagnostics = new ArrayList<>(compiled.diagnostics());
        diagnostics.addAll(additionalDiagnostics);
        boolean errors = diagnostics.stream().anyMatch(AuthoringDiagnostic::error);
        List<AuthoringReadiness.Gate> gates = new ArrayList<>(diagnostics.stream()
                .map(diagnostic -> new AuthoringReadiness.Gate(
                        diagnostic.code(),
                        diagnostic.level(),
                        diagnostic.message(),
                        diagnostic.authoringPath(),
                        diagnostic.error()
                ))
                .toList());
        if (runtimeParity != null) {
            runtimeParity.stream()
                    .filter(AuthoringFactProjection.RuntimeParity::unresolved)
                    .map(parity -> new AuthoringReadiness.Gate(
                            parity.reasonCode(),
                            "DRIFTED".equals(parity.state())
                                    || "BLOCKED_BY_POLICY".equals(parity.state())
                                    ? "ERROR" : "WARNING",
                            parity.message(),
                            parityPath(parity),
                            false
                    ))
                    .forEach(gates::add);
        }
        AuthoringReadiness current = compiled.readiness();
        boolean runtimeBound = current.designReady()
                && runtimeParity != null
                && !runtimeParity.isEmpty()
                && runtimeParity.stream()
                        .allMatch(AuthoringFactProjection.RuntimeParity::executableReady);
        return new AuthoringReadiness(
                errors ? "INVALID" : runtimeBound ? "RUNTIME_BOUND" : current.state(),
                current.importable() && !errors,
                current.strongSchemaReady(),
                current.designReady() && !errors,
                runtimeBound,
                gates
        );
    }

    private static String parityPath(AuthoringFactProjection.RuntimeParity parity) {
        String collection = "FUNCTION".equals(parity.assetKind()) ? "functions" : "operators";
        return "/" + collection + "/" + pointer(parity.assetRef());
    }

    private static String pointer(String value) {
        return (value == null ? "" : value)
                .replace("~", "~0")
                .replace("/", "~1");
    }
}
