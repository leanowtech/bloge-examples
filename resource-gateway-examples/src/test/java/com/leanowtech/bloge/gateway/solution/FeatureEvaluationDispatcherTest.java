package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves kind/reference dispatch is unique and fails closed when no authority owns a binding. */
class FeatureEvaluationDispatcherTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void dispatchesToTheOnlyAdapterOwningKindAndReference() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("api", adapter(FeatureContract.EvaluationKind.API, "resource:", "api-value"));
        beans.addBean("dag", adapter(FeatureContract.EvaluationKind.DAG, "graph:", "dag-value"));
        FeatureEvaluationDispatcher dispatcher = new FeatureEvaluationDispatcher(
                beans.getBeanProvider(FeatureEvaluationAdapter.class));

        JsonNode result = dispatcher.evaluate(feature(FeatureContract.EvaluationKind.API, "resource:party"),
                mapper.createObjectNode(), null);

        assertThat(result.asText()).isEqualTo("api-value");
    }

    @Test
    void rejectsMissingAndAmbiguousAdaptersWithOnePayloadFreeCode() {
        StaticListableBeanFactory missing = new StaticListableBeanFactory();
        assertUnavailable(() -> new FeatureEvaluationDispatcher(
                missing.getBeanProvider(FeatureEvaluationAdapter.class)).evaluate(
                feature(FeatureContract.EvaluationKind.API, "resource:party"),
                mapper.createObjectNode(), null));
        StaticListableBeanFactory ambiguous = new StaticListableBeanFactory();
        ambiguous.addBean("a", adapter(FeatureContract.EvaluationKind.API, "resource:", "a"));
        ambiguous.addBean("b", adapter(FeatureContract.EvaluationKind.API, "resource:", "b"));
        assertUnavailable(() -> new FeatureEvaluationDispatcher(
                ambiguous.getBeanProvider(FeatureEvaluationAdapter.class)).evaluate(
                feature(FeatureContract.EvaluationKind.API, "resource:party"),
                mapper.createObjectNode(), null));
    }

    private FeatureEvaluationAdapter adapter(
            FeatureContract.EvaluationKind kind, String prefix, String value) {
        return new FeatureEvaluationAdapter() {
            @Override public FeatureContract.EvaluationKind kind() { return kind; }
            @Override public boolean supports(String ref) { return ref.startsWith(prefix); }
            @Override public JsonNode evaluate(FeatureContract feature, JsonNode inputs,
                                               com.leanowtech.bloge.gateway.integration.IntegrationRequestContext id) {
                return mapper.valueToTree(value);
            }
        };
    }

    private FeatureContract feature(FeatureContract.EvaluationKind kind, String ref) {
        return new FeatureContract("feature:test", mapper.valueToTree(Map.of("type", "string")),
                kind, kind == FeatureContract.EvaluationKind.MODEL
                        ? FeatureContract.Determinism.NON_DETERMINISTIC
                        : FeatureContract.Determinism.DETERMINISTIC,
                mapper.createObjectNode(), ref, "", "");
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(SolutionContractException.class)
                .extracting(failure -> ((SolutionContractException) failure).code())
                .isEqualTo("FEATURE_EVALUATOR_UNAVAILABLE");
    }
}
