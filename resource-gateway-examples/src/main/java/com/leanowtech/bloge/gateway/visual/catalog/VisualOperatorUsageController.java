package com.leanowtech.bloge.gateway.visual.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public operator usage index API for impact review before catalog changes.
 */
@RestController
@RequestMapping("/api/visual/operators")
public class VisualOperatorUsageController {

    private final OperatorUsageIndex usageIndex;

    /**
     * @param usageIndex operator usage index
     */
    public VisualOperatorUsageController(OperatorUsageIndex usageIndex) {
        this.usageIndex = usageIndex;
    }

    /**
     * @param operatorRef visual operator reference
     * @return stored draft/publication usage for the operator
     */
    @GetMapping("/{operatorRef:.+}/usage")
    public OperatorUsageResponse usage(@PathVariable String operatorRef) {
        return usageIndex.usage(operatorRef);
    }
}
