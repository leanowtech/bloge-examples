package com.leanowtech.bloge.gateway.visual.resource;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualSecretGuard;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates resource design contracts before they become resource-backed visual operators.
 */
@Service
public class ResourceDesignContractValidator {

    /**
     * @param contract resource design contract
     * @return structured validation result
     */
    public VisualValidationResult validate(ResourceDesignContract contract) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (contract == null) {
            diagnostics.add(VisualDiagnostic.error("visual.resourceContract.missing",
                    "Resource design contract is required.",
                    "/"));
            return new VisualValidationResult(false, diagnostics);
        }
        if (contract.resourceId().isBlank()) {
            diagnostics.add(VisualDiagnostic.error("visual.resourceContract.resourceId.required",
                    "Resource design contract must declare a resourceId.",
                    "/resourceId"));
        }
        if (!ResourceDesignContract.isSupportedStatus(contract.status())) {
            diagnostics.add(VisualDiagnostic.error("visual.resourceContract.status.unsupported",
                    "Resource design contract status '%s' must be one of ACTIVE, DEPRECATED, or DISABLED."
                            .formatted(contract.status()),
                    "/status"));
        }
        diagnostics.addAll(VisualSchemaValidator.validateEnvelope(
                contract.requestSchema(), "/requestSchema"));
        diagnostics.addAll(VisualSchemaValidator.validateEnvelope(
                contract.responseSchema(), "/responseSchema"));
        diagnostics.addAll(VisualSecretGuard.detectRawSecrets(contract.examples(), "/examples"));
        return new VisualValidationResult(true, diagnostics);
    }
}
