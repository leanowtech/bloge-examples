package com.leanowtech.bloge.gateway.visual.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public visual operator catalog API.
 */
@RestController
@RequestMapping("/api/visual/operators")
public class VisualOperatorCatalogController {

    private final VisualOperatorCatalog catalog;

    /**
     * @param catalog visual operator catalog
     */
    public VisualOperatorCatalogController(VisualOperatorCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Lists operators available to the visual canvas.
     *
     * @param search free-text search
     * @param tags required tags
     * @param resourceOnly whether to return only resource virtual operators
     * @param includeDeprecated include deprecated resource contracts
     * @return catalog response
     */
    @GetMapping
    public OperatorCatalogResponse list(@RequestParam(defaultValue = "") String search,
                                        @RequestParam(defaultValue = "") List<String> tags,
                                        @RequestParam(defaultValue = "false") boolean resourceOnly,
                                        @RequestParam(defaultValue = "false") boolean includeDeprecated) {
        return new OperatorCatalogResponse(
                "bloge.visualOperatorCatalog.v1",
                catalog.list(new OperatorCatalogQuery(search, tags, resourceOnly, includeDeprecated)),
                List.of()
        );
    }
}
