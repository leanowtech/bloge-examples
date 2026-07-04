package com.leanowtech.bloge.gateway.visual.catalog;

/**
 * Visual operator detail response with server-derived control-plane projections.
 *
 * @param schemaVersion response schema version
 * @param operator visible operator definition
 * @param runtimeBindingProjection runtime implementation binding projection for this operator
 * @param executablePromotionProjection executable promotion projection for this operator
 * @param filter normalized catalog visibility filter used to resolve the operator
 */
public record OperatorDetailResponse(
        String schemaVersion,
        OperatorDefinition operator,
        OperatorRuntimeBindingProjection runtimeBindingProjection,
        OperatorExecutablePromotionProjection executablePromotionProjection,
        OperatorCatalogQuery filter
) {
    /**
     * Creates a normalized detail response.
     */
    public OperatorDetailResponse {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? "bloge.visualOperatorDetail.v1"
                : schemaVersion;
        if (operator == null) {
            throw new IllegalArgumentException("operator is required");
        }
        runtimeBindingProjection = runtimeBindingProjection == null
                ? OperatorRuntimeBindingProjection.from(operator, null)
                : runtimeBindingProjection;
        executablePromotionProjection = executablePromotionProjection == null
                ? OperatorExecutablePromotionProjection.from(runtimeBindingProjection)
                : executablePromotionProjection;
        filter = filter == null ? OperatorCatalogQuery.all() : filter;
    }
}
