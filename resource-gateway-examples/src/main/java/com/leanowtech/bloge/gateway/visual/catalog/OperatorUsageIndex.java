package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Builds an authoritative operator usage index from stored drafts and immutable publications.
 */
@Service
public class OperatorUsageIndex {

    private static final String CURRENT = "CURRENT";
    private static final String DRIFTED = "DRIFTED";
    private static final String SNAPSHOT_MISSING = "SNAPSHOT_MISSING";
    private static final String OPERATOR_MISSING = "OPERATOR_MISSING";

    private final GraphDraftRepository draftRepository;
    private final VisualGraphPublicationRepository publicationRepository;
    private final VisualOperatorCatalog catalog;

    /**
     * @param draftRepository stored draft repository
     * @param publicationRepository immutable publication repository
     * @param catalog current visual operator catalog
     */
    public OperatorUsageIndex(GraphDraftRepository draftRepository,
                              VisualGraphPublicationRepository publicationRepository,
                              VisualOperatorCatalog catalog) {
        this.draftRepository = draftRepository;
        this.publicationRepository = publicationRepository;
        this.catalog = catalog;
    }

    /**
     * @param operatorRef visual operator reference
     * @return current usage index
     */
    public OperatorUsageResponse usage(String operatorRef) {
        String ref = operatorRef == null ? "" : operatorRef;
        if (ref.isBlank()) {
            return new OperatorUsageResponse(
                    OperatorUsageResponse.SCHEMA_VERSION,
                    ref,
                    "",
                    List.of(),
                    List.of(),
                    List.of(VisualDiagnostic.error("visual.operatorUsage.operatorRef.required",
                            "operatorRef is required.",
                            "/operatorRef"))
            );
        }

        Optional<OperatorDefinition> currentOperator = catalog.find(ref);
        String currentFingerprint = currentOperator.map(OperatorDefinition::fingerprint).orElse("");
        List<OperatorDraftUsage> drafts = draftUsages(ref, currentFingerprint, currentOperator.isPresent());
        List<OperatorPublicationUsage> publications = publicationUsages(ref, currentOperator);
        List<VisualDiagnostic> diagnostics = diagnostics(ref, currentOperator.isPresent(), drafts, publications);
        return new OperatorUsageResponse(
                OperatorUsageResponse.SCHEMA_VERSION,
                ref,
                currentFingerprint,
                drafts,
                publications,
                diagnostics
        );
    }

    private List<OperatorDraftUsage> draftUsages(String operatorRef,
                                                 String currentFingerprint,
                                                 boolean currentOperatorPresent) {
        List<OperatorDraftUsage> usages = new ArrayList<>();
        for (GraphDraft draft : draftRepository.all()) {
            for (GraphDraft.DraftNode node : draft.nodes()) {
                if (!operatorRef.equals(node.operatorRef())) {
                    continue;
                }
                String savedFingerprint = draft.operatorFingerprints().get(node.id());
                String status = fingerprintStatus(savedFingerprint, currentFingerprint, currentOperatorPresent);
                UsageChangeReport report = changeReport(status,
                        Optional.ofNullable(draft.operatorSnapshots().get(node.id()))
                                .filter(snapshot -> snapshotMatches(snapshot, operatorRef, savedFingerprint)),
                        catalog.find(operatorRef));
                usages.add(new OperatorDraftUsage(
                        draft.draftId(),
                        draft.revision(),
                        draft.graphName(),
                        draft.tenantId(),
                        draft.namespace(),
                        draft.environment(),
                        node.id(),
                        node.label(),
                        savedFingerprint,
                        currentFingerprint,
                        status,
                        report.summary(),
                        report.risk(),
                        report.categories(),
                        report.summary()
                ));
            }
        }
        return usages.stream()
                .sorted(Comparator.comparing(OperatorDraftUsage::draftId)
                        .thenComparingLong(OperatorDraftUsage::revision)
                        .thenComparing(OperatorDraftUsage::nodeId))
                .toList();
    }

    private List<OperatorPublicationUsage> publicationUsages(String operatorRef,
                                                             Optional<OperatorDefinition> currentOperator) {
        List<OperatorPublicationUsage> usages = new ArrayList<>();
        String currentFingerprint = currentOperator.map(OperatorDefinition::fingerprint).orElse("");
        boolean currentOperatorPresent = currentOperator.isPresent();
        for (VisualGraphPublication publication : publicationRepository.all()) {
            GraphDraft draft = publication.draft();
            if (draft == null) {
                continue;
            }
            for (GraphDraft.DraftNode node : draft.nodes()) {
                if (!operatorRef.equals(node.operatorRef())) {
                    continue;
                }
                String frozenFingerprint = publication.operatorFingerprints().get(node.id());
                String status = fingerprintStatus(frozenFingerprint, currentFingerprint, currentOperatorPresent);
                Optional<OperatorDefinition> frozenOperator = frozenOperator(publication, operatorRef,
                        frozenFingerprint);
                UsageChangeReport report = changeReport(status, frozenOperator,
                        currentOperator);
                usages.add(new OperatorPublicationUsage(
                        publication.publicationId(),
                        publication.draftId(),
                        publication.draftRevision(),
                        publication.graphName(),
                        publication.tenantId(),
                        publication.namespace(),
                        publication.environment(),
                        node.id(),
                        node.label(),
                        frozenFingerprint,
                        currentFingerprint,
                        status,
                        report.summary(),
                        report.risk(),
                        report.categories(),
                        report.summary()
                ));
            }
        }
        return usages.stream()
                .sorted(Comparator.comparing(OperatorPublicationUsage::publicationId)
                        .thenComparing(OperatorPublicationUsage::nodeId))
                .toList();
    }

    private static List<VisualDiagnostic> diagnostics(String operatorRef,
                                                      boolean currentOperatorPresent,
                                                      List<OperatorDraftUsage> drafts,
                                                      List<OperatorPublicationUsage> publications) {
        if (currentOperatorPresent) {
            return List.of();
        }
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        String message = drafts.isEmpty() && publications.isEmpty()
                ? "OperatorRef '%s' is not present in the current visual catalog.".formatted(operatorRef)
                : "OperatorRef '%s' is used by stored artifacts but is not present in the current visual catalog."
                        .formatted(operatorRef);
        diagnostics.add(VisualDiagnostic.warning("visual.operatorUsage.operatorMissing",
                message,
                "/operatorRef"));
        return List.copyOf(diagnostics);
    }

    private static String fingerprintStatus(String savedFingerprint,
                                            String currentFingerprint,
                                            boolean currentOperatorPresent) {
        if (savedFingerprint == null || savedFingerprint.isBlank()) {
            return SNAPSHOT_MISSING;
        }
        if (!currentOperatorPresent) {
            return OPERATOR_MISSING;
        }
        return savedFingerprint.equals(currentFingerprint) ? CURRENT : DRIFTED;
    }

    private static Optional<OperatorDefinition> frozenOperator(VisualGraphPublication publication,
                                                               String operatorRef,
                                                               String frozenFingerprint) {
        return publication.operatorSnapshots().stream()
                .filter(operator -> snapshotMatches(operator, operatorRef, frozenFingerprint))
                .findFirst();
    }

    private static boolean snapshotMatches(OperatorDefinition snapshot,
                                           String operatorRef,
                                           String fingerprint) {
        if (snapshot == null || !operatorRef.equals(snapshot.operatorRef())) {
            return false;
        }
        return fingerprint == null || fingerprint.isBlank() || fingerprint.equals(snapshot.fingerprint());
    }

    private static UsageChangeReport changeReport(
            String status,
            Optional<OperatorDefinition> previousOperator,
            Optional<OperatorDefinition> currentOperator) {
        if (!DRIFTED.equals(status) || previousOperator.isEmpty() || currentOperator.isEmpty()) {
            return UsageChangeReport.empty();
        }
        OperatorDefinitionChangeSummary.ChangeReport report = OperatorDefinitionChangeSummary.analyze(
                previousOperator.get(), currentOperator.get());
        return new UsageChangeReport(report.risk(), report.categories(), report.summary());
    }

    private record UsageChangeReport(String risk, List<String> categories, String summary) {
        private UsageChangeReport {
            risk = risk == null ? "" : risk;
            categories = categories == null ? List.of() : List.copyOf(categories);
            summary = summary == null ? "" : summary;
        }

        private static UsageChangeReport empty() {
            return new UsageChangeReport("", List.of(), "");
        }
    }
}
