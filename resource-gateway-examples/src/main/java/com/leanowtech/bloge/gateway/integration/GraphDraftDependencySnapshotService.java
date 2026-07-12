package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.asset.VisualRuntimeBindingImplementationBinding;
import com.leanowtech.bloge.gateway.visual.asset.VisualRuntimeBindingImplementationRepository;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRevision;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeAdapterActivation;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeAdapterActivationRepository;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuite;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuiteRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Reads one deterministic, relevant-only dependency snapshot for GraphDraft integration export. */
@Service
public class GraphDraftDependencySnapshotService {
    private final VisualOperatorCatalog catalog;
    private final OperatorLibraryRegistry libraries;
    private final VisualRuntimeBindingImplementationRepository bindings;
    private final VisualRuntimeAdapterActivationRepository activations;
    private final VisualOperatorContractTestSuiteRepository suites;

    @Autowired
    public GraphDraftDependencySnapshotService(VisualOperatorCatalog catalog,
                                               OperatorLibraryRegistry libraries,
                                               VisualRuntimeBindingImplementationRepository bindings,
                                               VisualRuntimeAdapterActivationRepository activations,
                                               VisualOperatorContractTestSuiteRepository suites) {
        this.catalog = catalog;
        this.libraries = libraries;
        this.bindings = bindings;
        this.activations = activations;
        this.suites = suites;
    }

    GraphDraftDependencySnapshotService(VisualOperatorCatalog catalog) {
        this(catalog, null, null, null, null);
    }

    public Snapshot capture(GraphDraft draft) {
        if (draft == null) {
            throw new IllegalArgumentException("Graph draft is required for dependency snapshot capture");
        }
        Set<String> operatorRefs = new LinkedHashSet<>();
        draft.nodes().forEach(node -> operatorRefs.add(node.operatorRef()));

        List<OperatorDefinition> scopedOperators = scopedOperators(draft, operatorRefs);
        Map<String, OperatorDefinition> scopedByRef = byOperatorRef(scopedOperators);
        Map<String, OperatorDefinition> currentOperators = currentOperators(operatorRefs);
        Map<String, OperatorDefinition> exportOperators = exportOperators(
                draft, operatorRefs, scopedByRef);
        Map<String, OperatorDefinition> reportOperators = reportOperators(
                draft, operatorRefs, currentOperators, scopedByRef);
        Map<String, String> libraryIdsByRef = libraryIdsByOperatorRef(operatorRefs);
        Map<String, GraphDraftDependencyProfile.OperatorAssetSnapshot> assets = new LinkedHashMap<>();
        for (String operatorRef : operatorRefs.stream().sorted().toList()) {
            OperatorDefinition operator = exportOperators.get(operatorRef);
            boolean currentPresent = currentOperators.containsKey(operatorRef);
            boolean scopeAllowed = scopedByRef.containsKey(operatorRef);
            if (currentPresent && !scopeAllowed) {
                assets.put(operatorRef, restrictedAssets("SCOPE_MISMATCH"));
                continue;
            }
            if (!currentPresent) {
                assets.put(operatorRef, restrictedAssets("CATALOG_MISSING"));
                continue;
            }
            String libraryId = operator == null ? libraryIdsByRef.getOrDefault(operatorRef, "")
                    : firstNonBlank(operator.source().libraryId(), libraryIdsByRef.get(operatorRef));
            GraphDraftDependencyProfile.OperatorLibraryRef library = libraryRef(libraryId);
            List<GraphDraftDependencyProfile.RuntimeBindingRef> runtimeBindings = runtimeBindingRefs(
                    draft, operatorRef, operator);
            List<GraphDraftDependencyProfile.ContractSuiteRef> contractSuites = contractSuiteRefs(operatorRef);
            assets.put(operatorRef, new GraphDraftDependencyProfile.OperatorAssetSnapshot(
                    library, runtimeBindings, contractSuites,
                    readiness(operator, library, runtimeBindings, contractSuites)));
        }

        List<OperatorDefinition> frozenOperators = exportOperators.values().stream()
                .sorted(Comparator.comparing(OperatorDefinition::operatorRef))
                .toList();
        List<OperatorDefinition> frozenScoped = scopedOperators.stream()
                .sorted(Comparator.comparing(OperatorDefinition::operatorRef))
                .toList();
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("draftId", draft.draftId());
        material.put("draftRevision", draft.revision());
        material.put("draftFingerprint", VisualBundleFingerprint.fromMaterial(Map.of("draft", draft)));
        material.put("operators", frozenOperators);
        material.put("currentOperatorFingerprints", currentOperators.values().stream()
                .sorted(Comparator.comparing(OperatorDefinition::operatorRef))
                .map(operator -> Map.of("operatorRef", operator.operatorRef(), "fingerprint", operator.fingerprint()))
                .toList());
        material.put("scopedOperatorRefs", frozenScoped.stream().map(OperatorDefinition::operatorRef).toList());
        material.put("assets", assets);
        String fingerprint = VisualBundleFingerprint.fromMaterial(material);
        return new Snapshot(fingerprint, logicalCapturedAt(draft), frozenOperators,
                frozenCatalog(reportOperators.values().stream()
                        .sorted(Comparator.comparing(OperatorDefinition::operatorRef)).toList(),
                        frozenScoped, libraryIdsByRef), Map.copyOf(assets));
    }

    private List<OperatorDefinition> scopedOperators(GraphDraft draft, Set<String> operatorRefs) {
        if (catalog == null) {
            return List.of();
        }
        OperatorCatalogQuery query = new OperatorCatalogQuery("", List.of(), false, true,
                draft.tenantId(), draft.namespace(), draft.environment());
        return catalog.list(query).stream()
                .filter(operator -> operatorRefs.contains(operator.operatorRef()))
                .toList();
    }

    private Map<String, OperatorDefinition> currentOperators(Set<String> operatorRefs) {
        Map<String, OperatorDefinition> values = new LinkedHashMap<>();
        for (String operatorRef : operatorRefs) {
            OperatorDefinition operator = catalog == null ? null : catalog.find(operatorRef).orElse(null);
            if (operator != null) {
                values.put(operatorRef, operator);
            }
        }
        return Map.copyOf(values);
    }

    private static Map<String, OperatorDefinition> byOperatorRef(List<OperatorDefinition> operators) {
        Map<String, OperatorDefinition> values = new LinkedHashMap<>();
        operators.forEach(operator -> values.put(operator.operatorRef(), operator));
        return Map.copyOf(values);
    }

    private static Map<String, OperatorDefinition> exportOperators(
            GraphDraft draft,
            Set<String> operatorRefs,
            Map<String, OperatorDefinition> scoped) {
        Map<String, OperatorDefinition> values = new LinkedHashMap<>();
        for (String operatorRef : operatorRefs.stream().sorted().toList()) {
            OperatorDefinition operator = scoped.get(operatorRef);
            if (operator == null) {
                operator = savedSnapshot(draft, operatorRef);
            }
            if (operator != null) {
                values.put(operatorRef, operator);
            }
        }
        return Map.copyOf(values);
    }

    private static Map<String, OperatorDefinition> reportOperators(
            GraphDraft draft,
            Set<String> operatorRefs,
            Map<String, OperatorDefinition> current,
            Map<String, OperatorDefinition> scoped) {
        Map<String, OperatorDefinition> values = new LinkedHashMap<>();
        for (String operatorRef : operatorRefs.stream().sorted().toList()) {
            OperatorDefinition operator = scoped.get(operatorRef);
            if (operator == null && current.containsKey(operatorRef)) {
                operator = Optional.ofNullable(savedSnapshot(draft, operatorRef))
                        .orElseGet(() -> restrictedMarker(operatorRef));
            }
            if (operator != null) {
                values.put(operatorRef, operator);
            }
        }
        return Map.copyOf(values);
    }

    private Map<String, String> libraryIdsByOperatorRef(Set<String> operatorRefs) {
        if (catalog == null) {
            return Map.of();
        }
        Map<String, String> relevant = new LinkedHashMap<>();
        catalog.operatorLibraryIdsByOperatorRef(true).entrySet().stream()
                .filter(entry -> operatorRefs.contains(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> relevant.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(relevant);
    }

    private GraphDraftDependencyProfile.OperatorLibraryRef libraryRef(String libraryId) {
        if (libraryId == null || libraryId.isBlank() || libraries == null) {
            return GraphDraftDependencyProfile.OperatorLibraryRef.missing(libraryId);
        }
        OperatorLibrary library = libraries.find(libraryId).orElse(null);
        OperatorLibraryRevision latest = libraries.revisions(libraryId).stream()
                .max(Comparator.comparingLong(OperatorLibraryRevision::revision)).orElse(null);
        if (library == null) {
            return GraphDraftDependencyProfile.OperatorLibraryRef.missing(libraryId);
        }
        long revision = latest == null ? 0 : latest.revision();
        return new GraphDraftDependencyProfile.OperatorLibraryRef(library.libraryId(), revision, library.version(),
                library.owner(), library.status(), VisualBundleFingerprint.fromMaterial(Map.of("library", library)),
                true);
    }

    private List<GraphDraftDependencyProfile.RuntimeBindingRef> runtimeBindingRefs(GraphDraft draft,
                                                                                   String operatorRef,
                                                                                   OperatorDefinition operator) {
        if (bindings == null) {
            return List.of();
        }
        List<GraphDraftDependencyProfile.RuntimeBindingRef> refs = new ArrayList<>();
        bindings.all().stream()
                .filter(binding -> operatorRef.equals(binding.operatorRef()))
                .sorted(Comparator.comparing(VisualRuntimeBindingImplementationBinding::bindingId)
                        .thenComparingLong(VisualRuntimeBindingImplementationBinding::revision))
                .forEach(binding -> refs.add(runtimeBindingRef(draft, operator, binding)));
        return List.copyOf(refs);
    }

    private GraphDraftDependencyProfile.RuntimeBindingRef runtimeBindingRef(
            GraphDraft draft,
            OperatorDefinition operator,
            VisualRuntimeBindingImplementationBinding binding) {
        VisualRuntimeAdapterActivation activation = activations == null ? null
                : activations.findActiveByBindingId(binding.bindingId()).orElse(null);
        boolean fingerprintCurrent = operator != null && operator.fingerprint().equals(binding.operatorFingerprint());
        boolean activationCurrent = activation != null
                && activation.active()
                && activation.bindingRevision() == binding.revision()
                && binding.operatorRef().equals(activation.operatorRef())
                && binding.operatorFingerprint().equals(activation.operatorFingerprint())
                && (activation.runtimeEnvironment().isBlank()
                    || activation.runtimeEnvironment().equals(draft.environment()))
                && VisualRuntimeAdapterActivation.HEALTH_HEALTHY.equals(activation.healthState());
        return new GraphDraftDependencyProfile.RuntimeBindingRef(
                binding.bindingId(), binding.revision(), binding.state(), binding.operatorFingerprint(),
                VisualBundleFingerprint.fromMaterial(Map.of("binding", binding)),
                activation == null ? "" : activation.activationId(),
                activation == null ? 0 : activation.revision(),
                activation == null ? "missing" : activation.state(),
                activation == null ? "" : activation.runtimeEnvironment(),
                activation == null ? "" : activation.healthState(),
                activation == null ? "" : VisualBundleFingerprint.fromMaterial(Map.of("activation", activation)),
                binding.bound() && fingerprintCurrent && activationCurrent);
    }

    private List<GraphDraftDependencyProfile.ContractSuiteRef> contractSuiteRefs(String operatorRef) {
        if (suites == null) {
            return List.of();
        }
        return suites.all().stream()
                .filter(suite -> suite != null && operatorRef.equals(suite.request().operatorRef()))
                .sorted(Comparator.comparing(VisualOperatorContractTestSuite::suiteId))
                .map(suite -> new GraphDraftDependencyProfile.ContractSuiteRef(
                        suite.suiteId(), suites.revision(suite.suiteId()), suite.schemaVersion(),
                        suite.request().cases().size(), VisualBundleFingerprint.fromMaterial(Map.of("suite", suite))))
                .toList();
    }

    private static GraphDraftDependencyProfile.RuntimeReadiness readiness(
            OperatorDefinition operator,
            GraphDraftDependencyProfile.OperatorLibraryRef library,
            List<GraphDraftDependencyProfile.RuntimeBindingRef> bindings,
            List<GraphDraftDependencyProfile.ContractSuiteRef> suites) {
        if (operator == null) {
            return new GraphDraftDependencyProfile.RuntimeReadiness(false, false, false, "UNKNOWN", "", "",
                    "CATALOG_MISSING");
        }
        boolean imported = !library.libraryId().isBlank();
        boolean libraryReady = !imported || library.present() && OperatorLibrary.STATUS_ACTIVE.equals(library.status());
        boolean nativeExecutable = operator.runtimeReadiness() != null && operator.runtimeReadiness().executable();
        boolean bindingReady = bindings.stream().anyMatch(GraphDraftDependencyProfile.RuntimeBindingRef::ready);
        boolean suiteReady = !imported || !suites.isEmpty();
        boolean runtimeReady = libraryReady && suiteReady && (nativeExecutable || bindingReady);
        String state;
        if (!libraryReady) {
            state = library.present() ? "LIBRARY_NOT_ACTIVE" : "LIBRARY_MISSING";
        } else if (!suiteReady) {
            state = "CONTRACT_SUITE_MISSING";
        } else if (nativeExecutable) {
            state = "RUNTIME_EXECUTABLE";
        } else if (bindingReady) {
            state = "EXTERNAL_RUNTIME_BOUND";
        } else if (bindings.stream().anyMatch(binding -> "bound".equals(binding.state()))) {
            state = "ACTIVATION_MISSING_OR_STALE";
        } else {
            state = "RUNTIME_BINDING_MISSING";
        }
        String owner = library.owner();
        String risk = operator.capabilities() == null ? "UNKNOWN" : operator.capabilities().effect();
        return new GraphDraftDependencyProfile.RuntimeReadiness(true, runtimeReady,
                nativeExecutable || bindingReady, risk, owner, "", state);
    }

    private static VisualOperatorCatalog frozenCatalog(List<OperatorDefinition> global,
                                                       List<OperatorDefinition> scoped,
                                                       Map<String, String> libraryIds) {
        Map<String, OperatorDefinition> byRef = new LinkedHashMap<>();
        global.forEach(operator -> byRef.put(operator.operatorRef(), operator));
        return new VisualOperatorCatalog() {
            @Override
            public List<OperatorDefinition> list(OperatorCatalogQuery query) {
                return scoped;
            }

            @Override
            public Map<String, String> operatorLibraryIdsByOperatorRef(boolean includeDeprecated) {
                return libraryIds;
            }

            @Override
            public Optional<OperatorDefinition> find(String operatorRef) {
                return Optional.ofNullable(byRef.get(operatorRef));
            }
        };
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }

    private static OperatorDefinition savedSnapshot(GraphDraft draft, String operatorRef) {
        return draft.nodes().stream()
                .filter(node -> node.operatorRef().equals(operatorRef))
                .map(node -> draft.operatorSnapshots().get(node.id()))
                .filter(snapshot -> snapshot != null && operatorRef.equals(snapshot.operatorRef()))
                .findFirst().orElse(null);
    }

    private static OperatorDefinition restrictedMarker(String operatorRef) {
        return new OperatorDefinition("", operatorRef, "restricted",
                new OperatorDefinition.Display(operatorRef, "", List.of()),
                new OperatorDefinition.Source("scope-restricted", "", "", "", false, ""),
                new OperatorDefinition.Ports(List.of(), List.of()), SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("design", "", Map.of()), List.of());
    }

    private static GraphDraftDependencyProfile.OperatorAssetSnapshot restrictedAssets(String state) {
        return new GraphDraftDependencyProfile.OperatorAssetSnapshot(null, List.of(), List.of(),
                new GraphDraftDependencyProfile.RuntimeReadiness(false, false, false,
                        "UNKNOWN", "", "", state));
    }

    private static Instant logicalCapturedAt(GraphDraft draft) {
        String updatedAt = draft.revisionMetadata().updatedAt();
        if (updatedAt == null || updatedAt.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(updatedAt);
        } catch (DateTimeParseException ignored) {
            return Instant.EPOCH;
        }
    }

    public record Snapshot(String fingerprint,
                           Instant capturedAt,
                           List<OperatorDefinition> operators,
                           VisualOperatorCatalog catalog,
                           Map<String, GraphDraftDependencyProfile.OperatorAssetSnapshot> assets) {
        public Snapshot {
            fingerprint = fingerprint == null ? "" : fingerprint;
            capturedAt = capturedAt == null ? Instant.EPOCH : capturedAt;
            operators = operators == null ? List.of() : List.copyOf(operators);
            assets = assets == null ? Map.of() : Map.copyOf(assets);
        }
    }
}
