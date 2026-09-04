package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.BuiltInFunctionCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Resolves one immutable, scope-bound authoring catalog without later live-catalog lookups. */
final class DslAuthoringContextResolver {
    private static final int MAX_LIBRARY_REFS = 64;
    private static final int MAX_CONTEXT_BYTES = 1024 * 1024;

    private final VisualOperatorCatalog catalog;
    private final OperatorLibraryRegistry libraries;
    private final ObjectMapper mapper;
    private final DslReferenceBundleLoader.Bundle bundle;
    private final DslContractLens lens;

    DslAuthoringContextResolver(VisualOperatorCatalog catalog,
                                OperatorLibraryRegistry libraries,
                                ObjectMapper mapper,
                                DslReferenceBundleLoader.Bundle bundle) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.libraries = Objects.requireNonNull(libraries, "libraries");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.bundle = Objects.requireNonNull(bundle, "bundle");
        this.lens = new DslContractLens(mapper);
    }

    /**
     * Materializes the exact catalog used by one reference or compile request.
     *
     * @param requestedLibraryRefs explicit authored libraries; empty still includes platform built-ins and
     *                             scope-visible resource bindings
     * @param identity authenticated tenant, project and environment
     * @return frozen context and canonical fingerprint
     */
    DslAuthoringContext resolve(List<String> requestedLibraryRefs, IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (requestedLibraryRefs == null) {
            throw new AgentTddToolException("DSL_AUTHORING_CONTEXT_REQUIRED",
                    "Explicit libraryRefs are required for DSL authoring.",
                    Map.of("nextAction", "FETCH_DSL_REFERENCE"));
        }
        List<String> libraryRefs = normalize(requestedLibraryRefs);
        if (libraryRefs.size() > MAX_LIBRARY_REFS) {
            throw tooLarge();
        }
        List<OperatorLibrary> registrySnapshot = libraries.all().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(OperatorLibrary::libraryId))
                .toList();
        Map<String, OperatorLibrary> librariesById = registrySnapshot.stream().collect(
                java.util.stream.Collectors.toMap(OperatorLibrary::libraryId, value -> value,
                        (first, ignored) -> first, TreeMap::new));
        List<OperatorLibrary> selected = libraryRefs.stream().map(libraryRef -> {
            OperatorLibrary library = librariesById.get(libraryRef);
            if (library == null || !library.visibleInCatalog(false)
                    || visibleOperators(library, identity).isEmpty()) {
                throw notVisible();
            }
            return library;
        }).toList();

        OperatorCatalogQuery allInScope = new OperatorCatalogQuery(
                "", List.of(), false, false,
                identity.tenantId(), identity.projectId(), identity.environmentId());
        Map<String, OperatorDefinition> operators = new TreeMap<>();
        catalog.list(allInScope).stream()
                .filter(Objects::nonNull)
                .filter(operator -> operator.policy().allows(
                        identity.tenantId(), identity.projectId(), identity.environmentId()))
                .filter(operator -> operator.source().libraryId().isBlank())
                .filter(operator -> !libraryRefs.isEmpty() || platformOwned(operator))
                .forEach(operator -> operators.putIfAbsent(operator.operatorRef(), operator));
        selected.forEach(library -> visibleOperators(library, identity).stream()
                .map(operator -> ownedBy(library, operator))
                .forEach(operator -> operators.putIfAbsent(operator.operatorRef(), operator)));

        Map<String, OperatorLibrary.BuiltInFunction> functions = new TreeMap<>();
        BuiltInFunctionCatalog.defaults().stream().filter(Objects::nonNull)
                .forEach(function -> functions.putIfAbsent(function.name(), function));
        selected.stream().flatMap(library -> library.builtInFunctions().stream())
                .filter(Objects::nonNull)
                .forEach(function -> functions.putIfAbsent(function.name(), function));

        List<DslAuthoringContext.LibrarySnapshot> librarySnapshots = selected.stream()
                .sorted(Comparator.comparing(OperatorLibrary::libraryId))
                .map(library -> new DslAuthoringContext.LibrarySnapshot(
                        library.libraryId(), library.version(), libraryFingerprint(
                                library, operators.values(), functions.values())))
                .toList();
        LinkedHashMap<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", "rg.dslAuthoringContext.v1");
        material.put("languageVersion", bundle.languageVersion());
        material.put("compilerProfile", bundle.compilerProfile());
        material.put("supportedRootKinds", bundle.supportedRootKinds().stream().sorted().toList());
        material.put("referenceVersion", bundle.fingerprint());
        material.put("tenantId", identity.tenantId());
        material.put("projectId", identity.projectId());
        material.put("environmentId", identity.environmentId());
        material.put("libraries", librarySnapshots);
        material.put("operators", operators.values().stream().map(operator -> Map.of(
                "operatorRef", operator.operatorRef(),
                "contractFingerprint", operator.fingerprint(),
                "effect", operator.capabilities().effect(),
                "archetype", lens.operator(operator).archetype(),
                "bindingState", lens.operator(operator).bindingState())).toList());
        material.put("functions", functions.values().stream().map(lens::function).toList());
        String fingerprint;
        try {
            fingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper, material, MAX_CONTEXT_BYTES);
        } catch (IllegalArgumentException failure) {
            throw tooLarge();
        }
        return new DslAuthoringContext(
                "rg.dslAuthoringContext.v1", bundle.languageVersion(), bundle.compilerProfile(),
                Set.copyOf(bundle.supportedRootKinds()), librarySnapshots, Map.copyOf(operators),
                Map.copyOf(functions), bundle.fingerprint(), fingerprint,
                new DslAuthoringContext.AuthoringScope(
                        identity.tenantId(), identity.projectId(), identity.environmentId()));
    }

    private String libraryFingerprint(OperatorLibrary library,
                                      java.util.Collection<OperatorDefinition> visibleOperators,
                                      java.util.Collection<OperatorLibrary.BuiltInFunction> visibleFunctions) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("libraryId", library.libraryId());
        material.put("version", library.version());
        material.put("operators", visibleOperators.stream()
                .filter(operator -> library.libraryId().equals(operator.source().libraryId()))
                .map(operator -> Map.of("operatorRef", operator.operatorRef(),
                        "contractFingerprint", operator.fingerprint()))
                .sorted(Comparator.comparing(value -> value.get("operatorRef")))
                .toList());
        material.put("functions", visibleFunctions.stream()
                .filter(function -> library.libraryId().equals(function.namespace()))
                .map(lens::function).sorted(Comparator.comparing(DslReferenceSnapshot.FunctionContract::name))
                .toList());
        return VisualBundleFingerprint.fromCanonicalValue(mapper, material, MAX_CONTEXT_BYTES);
    }

    private static boolean platformOwned(OperatorDefinition operator) {
        return operator.source().libraryId().isBlank()
                && (!operator.source().resourceId().isBlank()
                || Set.of("built-in", "bloge-dsl", "bloge-operator").contains(operator.source().kind()));
    }

    private static List<OperatorDefinition> visibleOperators(OperatorLibrary library,
                                                              IntegrationRequestContext identity) {
        return library.operators().stream()
                .filter(Objects::nonNull)
                .filter(DslAuthoringContextResolver::hasConcretePorts)
                .filter(operator -> operator.policy().allows(
                        identity.tenantId(), identity.projectId(), identity.environmentId()))
                .toList();
    }

    private static boolean hasConcretePorts(OperatorDefinition operator) {
        return operator.ports().inputs().stream().allMatch(Objects::nonNull)
                && operator.ports().outputs().stream().allMatch(Objects::nonNull);
    }

    private static OperatorDefinition ownedBy(OperatorLibrary library, OperatorDefinition operator) {
        if (library.libraryId().equals(operator.source().libraryId())) return operator;
        OperatorDefinition.Source source = operator.source();
        return new OperatorDefinition(
                operator.schemaVersion(), operator.operatorRef(), operator.operatorVersion(),
                operator.fingerprint(), operator.display(),
                new OperatorDefinition.Source(source.kind(), source.resourceId(), source.method(),
                        source.urlTemplate(), source.virtual(), library.libraryId()),
                operator.ports(), operator.configSchema(), operator.capabilities(), operator.policy(),
                operator.lowering(), operator.diagnostics(), operator.runtimeReadiness());
    }

    private static AgentTddToolException notVisible() {
        return new AgentTddToolException("DSL_LIBRARY_NOT_VISIBLE",
                "A requested DSL library is not visible in this authoring scope.",
                Map.of("nextAction", "CHECK_LIBRARY_REFS"));
    }

    private static List<String> normalize(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new AgentTddToolException("DSL_LIBRARY_NOT_VISIBLE",
                        "A requested DSL library is not visible in this authoring scope.",
                        Map.of("nextAction", "CHECK_LIBRARY_REFS"));
            }
            normalized.add(value.trim());
        }
        return normalized.stream().sorted().toList();
    }

    private static AgentTddToolException tooLarge() {
        return new AgentTddToolException("DSL_REFERENCE_TOO_LARGE",
                "The requested DSL reference exceeds its safe size limit.",
                Map.of("nextAction", "NARROW_REFERENCE_REQUEST"));
    }
}
