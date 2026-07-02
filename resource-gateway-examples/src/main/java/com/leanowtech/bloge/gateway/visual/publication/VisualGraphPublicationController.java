package com.leanowtech.bloge.gateway.visual.publication;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunService;
import com.leanowtech.bloge.gateway.visual.runtime.VisualStoredDraftRunRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;

/**
 * Public API for immutable visual graph publications.
 */
@RestController
@RequestMapping("/api/visual/publications")
public class VisualGraphPublicationController {

    private final VisualGraphPublicationRepository repository;
    private final VisualGraphRunService runner;
    private final VisualGraphRunRepository runRepository;

    /**
     * @param repository publication repository
     * @param runner publication runner
     */
    public VisualGraphPublicationController(VisualGraphPublicationRepository repository,
                                            VisualGraphRunService runner,
                                            VisualGraphRunRepository runRepository) {
        this.repository = repository;
        this.runner = runner;
        this.runRepository = runRepository;
    }

    /**
     * @param tenantId tenant scope, empty for all
     * @param namespace namespace scope, empty for all
     * @param environment environment scope, empty for all
     * @return publications in scope
     */
    @GetMapping
    public Collection<VisualGraphPublication> list(@RequestParam(defaultValue = "") String tenantId,
                                                   @RequestParam(defaultValue = "") String namespace,
                                                   @RequestParam(defaultValue = "") String environment) {
        return repository.all().stream()
                .filter(publication -> matchesPublicationScope(publication, tenantId, namespace, environment))
                .toList();
    }

    /**
     * Backward-compatible direct-call helper for tests and non-Spring callers.
     */
    public Collection<VisualGraphPublication> list() {
        return list("", "", "");
    }

    /**
     * Lists lightweight publication summaries for asset indexes.
     *
     * @param tenantId tenant scope, empty for all
     * @param namespace namespace scope, empty for all
     * @param environment environment scope, empty for all
     * @return publication summaries newest first in scope
     */
    @GetMapping("/summaries")
    public List<VisualGraphPublicationSummary> summaries(@RequestParam(defaultValue = "") String tenantId,
                                                         @RequestParam(defaultValue = "") String namespace,
                                                         @RequestParam(defaultValue = "") String environment) {
        return repository.all().stream()
                .map(VisualGraphPublicationSummary::from)
                .filter(summary -> matchesPublicationSummaryScope(summary, tenantId, namespace, environment))
                .toList();
    }

    /**
     * Backward-compatible direct-call helper for tests and non-Spring callers.
     */
    public List<VisualGraphPublicationSummary> summaries() {
        return summaries("", "", "");
    }

    private static boolean matchesPublicationScope(VisualGraphPublication publication,
                                                   String tenantId,
                                                   String namespace,
                                                   String environment) {
        return publication != null
                && matchesScope(publication.tenantId(), tenantId)
                && matchesScope(publication.namespace(), namespace)
                && matchesScope(publication.environment(), environment);
    }

    private static boolean matchesPublicationSummaryScope(VisualGraphPublicationSummary summary,
                                                          String tenantId,
                                                          String namespace,
                                                          String environment) {
        return summary != null
                && matchesScope(summary.tenantId(), tenantId)
                && matchesScope(summary.namespace(), namespace)
                && matchesScope(summary.environment(), environment);
    }

    private static boolean matchesScope(String actual, String expected) {
        return expected == null || expected.isBlank() || String.valueOf(actual).equals(expected);
    }

    /**
     * Gets a publication.
     *
     * @param publicationId publication id
     * @return publication when present
     */
    @GetMapping("/{publicationId}")
    public ResponseEntity<VisualGraphPublication> get(@PathVariable String publicationId) {
        return repository.find(publicationId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Gets the publish-time dependency report frozen with a publication.
     *
     * @param publicationId publication id
     * @return frozen dependency report when the publication exists
     */
    @GetMapping("/{publicationId}/dependencies")
    public ResponseEntity<GraphDraftDependencyReport> dependencies(@PathVariable String publicationId) {
        return repository.find(publicationId)
                .map(publication -> ResponseEntity.ok(publication.dependencyReport()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Runs a published immutable visual graph artifact.
     *
     * @param publicationId publication id
     * @param request run request
     * @return run response when publication exists
     */
    @PostMapping("/{publicationId}/run")
    public ResponseEntity<VisualGraphRunResponse> run(@PathVariable String publicationId,
                                                      @RequestBody VisualStoredDraftRunRequest request) {
        return repository.find(publicationId)
                .map(publication -> {
                    VisualGraphRunResponse response = runner.run(publication, request.context(), request.outputNode());
                    VisualGraphRunRecord record = runRepository.create(VisualGraphRunRecord.publication(
                            publication, request.context(), response));
                    return ResponseEntity.ok(response.withRunId(record.runId()));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
