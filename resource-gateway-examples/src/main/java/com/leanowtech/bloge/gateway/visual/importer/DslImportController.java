package com.leanowtech.bloge.gateway.visual.importer;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Visual DSL import preview API.
 */
@RestController
@RequestMapping("/api/visual/dsl-imports")
public class DslImportController {

    private final DslImportService service;

    public DslImportController(DslImportService service) {
        this.service = service;
    }

    @PostMapping("/preview")
    public DslVisualProjection preview(@RequestBody DslImportPreviewRequest request) {
        return service.preview(request);
    }
}
