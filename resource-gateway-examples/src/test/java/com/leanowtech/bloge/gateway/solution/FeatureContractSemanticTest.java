package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that business identity changes invalidate a Feature while implementation rebinding does not. */
class FeatureContractSemanticTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void implementationReferencesAreExcludedButBusinessDefinitionIsIncludedInIdentity() throws Exception {
        BusinessFactSemanticContract definition = definition("REQUIRE_HUMAN_REVIEW");
        FeatureContract first = feature("resource:a", definition);
        FeatureContract rebound = feature("resource:b", definition);
        FeatureContract policyChanged = feature("resource:b", definition("USE_DEFAULT"));

        assertThat(first.contractIdentity()).isEqualTo(rebound.contractIdentity());
        assertThat(first.contractIdentity()).isNotEqualTo(policyChanged.contractIdentity());
        assertThat(mapper.valueToTree(first.contractIdentity()).toString())
                .doesNotContain("resource:a");
    }

    @Test
    void legacyConstructorProducesExplicitUnknownProjection() throws Exception {
        FeatureContract legacy = new FeatureContract("fact:legacy", mapper.readTree("{\"type\":\"string\"}"),
                FeatureContract.EvaluationKind.API, FeatureContract.Determinism.DETERMINISTIC,
                mapper.createObjectNode(), "resource:a", "", "", "legacy meaning");

        assertThat(legacy.businessDefinition().incompleteLegacyProjection()).isTrue();
        assertThat(legacy.businessDefinition().domain()).isEqualTo("UNKNOWN");
    }

    private FeatureContract feature(String binding, BusinessFactSemanticContract definition) throws Exception {
        return new FeatureContract("fact:party", mapper.readTree("{\"type\":\"string\"}"),
                FeatureContract.EvaluationKind.API, FeatureContract.Determinism.DETERMINISTIC,
                mapper.readTree("{\"orderId\":\"string\"}"), binding, "", "",
                "cancellation responsibility", definition);
    }

    private BusinessFactSemanticContract definition(String unknownPolicy) throws Exception {
        return new BusinessFactSemanticContract(BusinessFactSemanticContract.SCHEMA_VERSION,
                "ride.cancel.party", "判断取消责任", "ride-cancellation", "ride-order",
                mapper.readTree("[{\"semanticKey\":\"ride-order.id\",\"name\":\"orderId\",\"type\":\"string\",\"required\":true}]"),
                mapper.readTree("{\"type\":\"enum\",\"values\":[\"PASSENGER\",\"DRIVER\"]}"),
                "CANCELLATION_OCCURRED_AT", unknownPolicy, "PLATFORM", "responsibility-center",
                mapper.valueToTree(Map.of("mode", "AS_OF_EVENT")), "READ", "PROPOSED");
    }
}
