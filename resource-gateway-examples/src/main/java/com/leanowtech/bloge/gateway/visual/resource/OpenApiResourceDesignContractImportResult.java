package com.leanowtech.bloge.gateway.visual.resource;

import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

/**
 * Projection result for an OpenAPI-to-resource-contract preview.
 *
 * @param contract generated contract draft, absent when the OpenAPI selector cannot be projected
 * @param validation import and resource-contract validation diagnostics
 * @param descriptorSuggestion generated runtime descriptor draft, absent when no contract can be projected
 */
public record OpenApiResourceDesignContractImportResult(
        ResourceDesignContract contract,
        VisualValidationResult validation,
        ResourceDescriptor descriptorSuggestion
) {
    /**
     * Backward-compatible constructor for callers that only project a design contract.
     */
    public OpenApiResourceDesignContractImportResult(ResourceDesignContract contract,
                                                    VisualValidationResult validation) {
        this(contract, validation, null);
    }
}
