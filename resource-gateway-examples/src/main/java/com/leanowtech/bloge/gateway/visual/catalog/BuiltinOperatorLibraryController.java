package com.leanowtech.bloge.gateway.visual.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint that exports the server's built-in operator registry as a portable operator library
 * (idea #5, decision D16).
 */
@RestController
@RequestMapping("/api/visual/builtin-library")
public class BuiltinOperatorLibraryController {

    private final BuiltinOperatorLibraryExporter exporter;

    /**
     * @param exporter built-in operator library exporter
     */
    public BuiltinOperatorLibraryController(BuiltinOperatorLibraryExporter exporter) {
        this.exporter = exporter;
    }

    /**
     * Exports the built-in operator registry as a portable {@code bloge.visualOperatorLibrary.v1}
     * document that can be re-imported into any canvas instance.
     *
     * @return the built-in operator library
     */
    @GetMapping("/export")
    public OperatorLibrary export() {
        return exporter.export();
    }
}
