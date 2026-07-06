package com.leanowtech.bloge.gateway.gateway;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Result of evaluating a suite coverage policy against a contract-test result.
 *
 * @param passed whether the policy was satisfied
 * @param diagnostics policy diagnostics
 */
public record GatewayGraphContractTestPolicyResult(
        boolean passed,
        List<VisualDiagnostic> diagnostics
) {
    /**
     * Creates a policy result.
     */
    public GatewayGraphContractTestPolicyResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /**
     * @return passing policy result
     */
    public static GatewayGraphContractTestPolicyResult passing() {
        return new GatewayGraphContractTestPolicyResult(true, List.of());
    }
}
