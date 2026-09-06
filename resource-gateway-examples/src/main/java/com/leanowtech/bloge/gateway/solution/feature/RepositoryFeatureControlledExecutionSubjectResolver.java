package com.leanowtech.bloge.gateway.solution.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRevision;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Repository-backed exact subject resolver used by the production Feature suite boundary. */
@Service
public final class RepositoryFeatureControlledExecutionSubjectResolver
        implements FeatureControlledExecutionSubjectResolver {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final GraphDraftRepository drafts;
    private final OperatorLibraryRegistry libraries;
    private final ObjectMapper mapper;

    /** Creates the resolver over the authoritative graph and library revision stores. */
    public RepositoryFeatureControlledExecutionSubjectResolver(
            GraphDraftRepository drafts, OperatorLibraryRegistry libraries, ObjectMapper mapper) {
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.libraries = Objects.requireNonNull(libraries, "libraries");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Captures the exact graph and library revisions and derives coverage obligations from graph
     * nodes owned by the server. Caller claims must equal this denominator.
     */
    @Override
    public Subject freeze(String evaluationRef,
                          List<String> libraryRefs,
                          List<String> claimedCoverageTargets,
                          IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        String ref = required(evaluationRef, "evaluationRef");
        GraphDraft draft = drafts.find(ref)
                .filter(identity::matchesDraftScope)
                .orElseThrow(() -> stale("The exact Feature evaluation graph is unavailable."));
        List<String> requestedLibraries = normalized(libraryRefs);
        Set<String> graphLibraries = draft.operatorSnapshots().values().stream()
                .filter(Objects::nonNull)
                .map(operator -> libraryRef(operator.operatorRef()))
                .filter(value -> !value.isBlank() && !"bloge".equals(value))
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        if (!new TreeSet<>(requestedLibraries).equals(graphLibraries)) {
            throw stale("The exact Feature graph library set changed.");
        }

        LinkedHashMap<String, Object> libraryCoordinates = new LinkedHashMap<>();
        for (String libraryRef : requestedLibraries) {
            OperatorLibrary library = libraries.find(libraryRef)
                    .orElseThrow(() -> stale("A Feature graph library is unavailable."));
            long revision = libraries.revisions(libraryRef).stream()
                    .mapToLong(OperatorLibraryRevision::revision).max().orElse(0L);
            libraryCoordinates.put(libraryRef, Map.of(
                    "revision", revision,
                    "fingerprint", fingerprint(library)));
        }

        List<String> obligations = draft.operatorSnapshots().keySet().stream()
                .map(nodeId -> "node:" + nodeId)
                .distinct().sorted().toList();
        if (obligations.isEmpty()) {
            throw new AgentTddToolException(
                    "FEATURE_SUITE_COVERAGE_INVALID", "The Feature graph has no coverable nodes.");
        }
        if (!obligations.equals(normalized(claimedCoverageTargets))) {
            throw new AgentTddToolException(
                    "FEATURE_SUITE_COVERAGE_INVALID",
                    "Coverage targets must equal the server-derived Feature graph obligations.");
        }
        return new Subject(
                fingerprint(ref), draft.revision(), fingerprint(draft),
                fingerprint(libraryCoordinates), fingerprint(obligations), obligations);
    }

    private String fingerprint(Object value) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper, value, MAX_BYTES);
    }

    private static List<String> normalized(List<String> values) {
        return values == null ? List.of() : values.stream()
                .map(value -> required(value, "list entry"))
                .distinct().sorted().toList();
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String libraryRef(String operatorRef) {
        String normalized = operatorRef == null ? "" : operatorRef.trim();
        int separator = normalized.indexOf(':');
        return separator <= 0 ? "" : normalized.substring(0, separator);
    }

    private static AgentTddToolException stale(String message) {
        return new AgentTddToolException("FEATURE_SUITE_EVIDENCE_STALE", message);
    }
}
