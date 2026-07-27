package com.leanowtech.bloge.gateway.authoring.scenario;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;

import java.util.Objects;

/**
 * Evaluates whether an Operator can be addressed by the current Scenario protocol scope.
 *
 * <p>The Scenario coordinate currently carries tenant and environment but no namespace. A
 * namespace-restricted Operator therefore remains unavailable until the protocol can represent
 * and authenticate that coordinate explicitly.</p>
 */
final class OperatorScenarioScope {

    private OperatorScenarioScope() {
    }

    /**
     * @return true when policy admits the identity and needs no missing namespace coordinate
     */
    static boolean allows(
            OperatorDefinition operator,
            IntegrationRequestContext identity) {
        OperatorDefinition definition = Objects.requireNonNull(operator, "operator");
        IntegrationRequestContext context = Objects.requireNonNull(identity, "identity");
        OperatorDefinition.Policy policy = definition.policy();
        boolean namespaceResolvable = policy.namespaces().isEmpty()
                || policy.namespaces().contains("*");
        return policy.allowsTenant(context.tenantId())
                && policy.allowsEnvironment(context.environmentId())
                && namespaceResolvable;
    }
}
