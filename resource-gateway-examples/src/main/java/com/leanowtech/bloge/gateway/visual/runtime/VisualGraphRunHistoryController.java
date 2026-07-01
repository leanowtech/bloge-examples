package com.leanowtech.bloge.gateway.visual.runtime;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;

/**
 * Public API for visual graph run history.
 */
@RestController
@RequestMapping("/api/visual/runs")
public class VisualGraphRunHistoryController {

    private final VisualGraphRunRepository repository;

    /**
     * @param repository visual graph run history repository
     */
    public VisualGraphRunHistoryController(VisualGraphRunRepository repository) {
        this.repository = repository;
    }

    /**
     * @return all visual graph run records, newest first
     */
    @GetMapping
    public Collection<VisualGraphRunRecord> list(@RequestParam(required = false) String sourceKind,
                                                 @RequestParam(required = false) String draftId,
                                                 @RequestParam(required = false) String publicationId,
                                                 @RequestParam(required = false) String graphName,
                                                 @RequestParam(required = false) Boolean success,
                                                 @RequestParam(required = false) Integer limit) {
        return repository.query(new VisualGraphRunQuery(sourceKind, draftId, publicationId, graphName, success,
                limit == null ? 0 : limit));
    }

    /**
     * @return aggregate run-history stats for the same filter window as list
     */
    @GetMapping("/stats")
    public VisualGraphRunStats stats(@RequestParam(required = false) String sourceKind,
                                     @RequestParam(required = false) String draftId,
                                     @RequestParam(required = false) String publicationId,
                                     @RequestParam(required = false) String graphName,
                                     @RequestParam(required = false) Boolean success,
                                     @RequestParam(required = false) Integer limit) {
        return VisualGraphRunStats.from(repository.query(new VisualGraphRunQuery(sourceKind, draftId, publicationId,
                graphName, success, limit == null ? 0 : limit)));
    }

    /**
     * Gets one run history record.
     *
     * @param runId run id
     * @return run record when present
     */
    @GetMapping("/{runId}")
    public ResponseEntity<VisualGraphRunRecord> get(@PathVariable String runId) {
        return repository.find(runId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Gets one shape-only run trace.
     *
     * @param runId run id
     * @return run trace when present
     */
    @GetMapping("/{runId}/trace")
    public ResponseEntity<VisualGraphRunTrace> trace(@PathVariable String runId) {
        return repository.find(runId)
                .map(record -> ResponseEntity.ok(VisualGraphRunTrace.from(record)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
