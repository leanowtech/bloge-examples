package com.leanowtech.bloge.gateway.visual.runtime;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    public Collection<VisualGraphRunRecord> list() {
        return repository.all();
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
}
