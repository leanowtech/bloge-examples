package com.leanowtech.bloge.gateway.visual.resource;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import java.util.List;

/**
 * Validation response for resource design-contract preflight.
 *
 * @param valid whether validation has no blocking errors
 * @param diagnostics detailed diagnostics
 * @param impact machine-readable resource-contract impact review
 */
public record ResourceDesignContractValidationResult(
        boolean valid,
        List<VisualDiagnostic> diagnostics,
        ResourceDesignContractImpactReview impact
) {
    /**
     * Creates a validation result.
     */
    public ResourceDesignContractValidationResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        valid = diagnostics.stream().noneMatch(VisualDiagnostic::error);
        impact = impact == null ? ResourceDesignContractImpactReview.fromDiagnostics(diagnostics, "") : impact;
    }

    /**
     * @param contract resource design contract under validation
     * @param diagnostics validation diagnostics
     * @return result with an impact review derived from the contract resource id
     */
    public static ResourceDesignContractValidationResult from(ResourceDesignContract contract,
                                                              List<VisualDiagnostic> diagnostics) {
        String resourceId = contract == null ? "" : contract.resourceId();
        return new ResourceDesignContractValidationResult(false, diagnostics,
                ResourceDesignContractImpactReview.fromDiagnostics(diagnostics, resourceId));
    }

    /**
     * @param validation generic validation result
     * @return resource-contract validation result with an empty impact scope
     */
    public static ResourceDesignContractValidationResult from(VisualValidationResult validation) {
        return new ResourceDesignContractValidationResult(false,
                validation == null ? List.of() : validation.diagnostics(),
                null);
    }
}
