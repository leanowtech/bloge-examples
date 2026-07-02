package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VisualRuntimeBindingRequirementPlannerTest {

    @Test
    void classifiesDesignOnlyExecutableLoweringGap() {
        OperatorDefinition operator = VisualCatalogTestSupport.designOnlyEligibilityOperator("integer");

        List<VisualRuntimeBindingRequirementPlanner.OperatorRequirement> requirements =
                VisualRuntimeBindingRequirementPlanner.from(operator, "design-only", "info", "");

        assertThat(requirements)
                .singleElement()
                .satisfies(requirement -> {
                    assertThat(requirement.operatorRef()).isEqualTo("risk:eligibility");
                    assertThat(requirement.label()).isEqualTo("Eligibility");
                    assertThat(requirement.bindingKind()).isEqualTo("executable-lowering");
                    assertThat(requirement.bindingTarget()).isEqualTo("risk:eligibility");
                    assertThat(requirement.handoffLane()).isEqualTo("operator-platform");
                    assertThat(requirement.handoffKind()).isEqualTo("operator-implementation");
                    assertThat(requirement.handoffTarget()).isEqualTo("risk:eligibility");
                    assertThat(requirement.sourceKind()).isEqualTo("user-library");
                    assertThat(requirement.loweringMode()).isEqualTo("design");
                });
    }

    @Test
    void classifiesRemoteWorkerRuntimeGap() {
        OperatorDefinition operator = VisualCatalogTestSupport.remoteWorkerEligibilityOperator("integer");

        List<VisualRuntimeBindingRequirementPlanner.OperatorRequirement> requirements =
                VisualRuntimeBindingRequirementPlanner.from(operator, "runtime-blocked", "warning", "");

        assertThat(requirements)
                .singleElement()
                .satisfies(requirement -> {
                    assertThat(requirement.bindingKind()).isEqualTo("remote-worker-runtime");
                    assertThat(requirement.bindingTarget()).isEqualTo("workers.risk.eligibility");
                    assertThat(requirement.handoffLane()).isEqualTo("worker-runtime");
                    assertThat(requirement.handoffKind()).isEqualTo("worker-dispatch");
                    assertThat(requirement.handoffTarget()).isEqualTo("workers.risk.eligibility");
                    assertThat(requirement.title()).isEqualTo("Remote worker runtime required");
                });
    }

    @Test
    void classifiesWebhookRuntimeGapFromLoweringEndpoint() {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                "risk:webhookEligibility",
                base.operatorVersion(),
                new OperatorDefinition.Display("Webhook eligibility", "Webhook boundary.", List.of("risk")),
                new OperatorDefinition.Source("webhook", "", "POST", "/legacy", false),
                base.ports(),
                base.configSchema(),
                base.capabilities(),
                base.policy(),
                new OperatorDefinition.Lowering("webhook", "", Map.of(
                        "method", "PUT",
                        "path", "/hooks/eligibility"
                )),
                base.diagnostics()
        );

        List<VisualRuntimeBindingRequirementPlanner.OperatorRequirement> requirements =
                VisualRuntimeBindingRequirementPlanner.from(operator, "runtime-blocked", "warning", "");

        assertThat(requirements)
                .singleElement()
                .satisfies(requirement -> {
                    assertThat(requirement.bindingKind()).isEqualTo("webhook-ingress-runtime");
                    assertThat(requirement.bindingTarget()).isEqualTo("PUT /hooks/eligibility");
                    assertThat(requirement.handoffLane()).isEqualTo("ingress-runtime");
                    assertThat(requirement.handoffKind()).isEqualTo("webhook-ingress");
                    assertThat(requirement.handoffTarget()).isEqualTo("PUT /hooks/eligibility");
                    assertThat(requirement.sourceKind()).isEqualTo("webhook");
                    assertThat(requirement.loweringMode()).isEqualTo("webhook");
                });
    }

    @Test
    void classifiesStreamingAndDurableRuntimeGapsTogether() {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                "risk:streamingDurableEligibility",
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                new OperatorDefinition.Capabilities("READ_EXTERNAL", "IDEMPOTENT", true, true, false),
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );

        List<VisualRuntimeBindingRequirementPlanner.OperatorRequirement> requirements =
                VisualRuntimeBindingRequirementPlanner.from(operator, "runtime-blocked", "warning", "");

        assertThat(requirements)
                .extracting(VisualRuntimeBindingRequirementPlanner.OperatorRequirement::bindingKind)
                .containsExactly("streaming-runtime", "durable-runtime");
        assertThat(requirements)
                .extracting(VisualRuntimeBindingRequirementPlanner.OperatorRequirement::handoffLane)
                .containsExactly("streaming-runtime", "durable-runtime");
        assertThat(requirements)
                .extracting(VisualRuntimeBindingRequirementPlanner.OperatorRequirement::handoffTarget)
                .containsExactly("risk:streamingDurableEligibility", "risk:streamingDurableEligibility");
    }
}
