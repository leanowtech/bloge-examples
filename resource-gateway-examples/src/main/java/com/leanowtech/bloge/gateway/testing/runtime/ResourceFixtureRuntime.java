package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.operator.HttpResourceOperator;
import com.leanowtech.bloge.gateway.operator.HttpResourceOutput;
import com.leanowtech.bloge.gateway.operator.PayloadExtractor;
import com.leanowtech.bloge.gateway.operator.ResponseValidator;
import com.leanowtech.bloge.gateway.operator.UrlTemplateRenderer;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.operators.http.HttpResponseOutput;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Executes descriptor-backed resource fixtures through the real request mapping, response
 * protocol, and payload extraction pipeline while replacing only the HTTP transport.
 */
public class ResourceFixtureRuntime {

    private final ResourceRegistry registry;
    private final BlgeExpressionEvaluator evaluator;
    private final ObjectMapper objectMapper;

    /**
     * @param registry frozen resource descriptor inventory
     * @param evaluator production BLOGE expression evaluator
     * @param objectMapper JSON mapper used by payload extraction
     */
    public ResourceFixtureRuntime(ResourceRegistry registry, BlgeExpressionEvaluator evaluator,
                                  ObjectMapper objectMapper) {
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
        this.evaluator = java.util.Objects.requireNonNull(evaluator, "evaluator");
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Executes one protocol-derived resource fixture.
     *
     * @param behavior raw transport response behavior
     * @param input original httpResource node input
     * @param context operator context
     * @return output derived by the real resource descriptor protocol
     */
    public HttpResourceOutput execute(FixtureRule.Behavior behavior, Object input,
                                      OperatorContext context) throws Exception {
        if (behavior.statusCode() == null) {
            throw new TestControlException("CONTROL_PLAN_INVALID_RESOURCE_FIXTURE",
                    "RESOURCE_FIXTURE", "Protocol-derived resource fixture requires statusCode.");
        }
        Map<String, List<String>> headers = behavior.headers().entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> List.of(entry.getValue())));
        StubHttpRequestOperator transport = new StubHttpRequestOperator(new HttpResponseOutput(
                behavior.statusCode(), headers, behavior.rawBody(), Duration.ZERO));
        HttpResourceOperator operator = new HttpResourceOperator(
                transport, registry, evaluator, new UrlTemplateRenderer(),
                new PayloadExtractor(objectMapper), new ResponseValidator(evaluator));
        return operator.execute(input, context);
    }
}
