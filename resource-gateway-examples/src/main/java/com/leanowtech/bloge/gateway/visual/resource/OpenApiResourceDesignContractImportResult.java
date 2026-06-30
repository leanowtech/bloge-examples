package com.leanowtech.bloge.gateway.visual.resource;

import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

/**
 * Projection result for an OpenAPI-to-resource-contract preview.
 *
 * @param contract generated contract draft, absent when the OpenAPI selector cannot be projected
 * @param validation import and resource-contract validation diagnostics
 */
public record OpenApiResourceDesignContractImportResult(
        ResourceDesignContract contract,
        VisualValidationResult validation
) {
}
