package com.leanowtech.bloge.gateway.visual.importer;

/**
 * Import projection coverage summary.
 *
 * @param memberCount parsed graph body member count
 * @param projectedNodeCount visual nodes created
 * @param edgeCount visual edges created
 * @param unsupportedSyntaxCount unsupported AST members or expression surfaces
 * @param missingOperatorCount nodes whose operator schema is not present in the effective catalog
 * @param missingFunctionCount function references missing from the effective function catalog
 */
public record DslImportCoverage(
        int memberCount,
        int projectedNodeCount,
        int edgeCount,
        int unsupportedSyntaxCount,
        int missingOperatorCount,
        int missingFunctionCount
) {
    public static DslImportCoverage empty() {
        return new DslImportCoverage(0, 0, 0, 0, 0, 0);
    }
}
