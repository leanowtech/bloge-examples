package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.exception.ResourceCallException;
import com.leanowtech.bloge.gateway.operator.HttpResourceOperator;
import com.leanowtech.bloge.gateway.operator.HttpResourceInput;
import com.leanowtech.bloge.gateway.operator.HttpResourceOutput;
import com.leanowtech.bloge.gateway.operator.PayloadExtractor;
import com.leanowtech.bloge.gateway.operator.ResponseValidator;
import com.leanowtech.bloge.gateway.operator.UrlTemplateRenderer;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.operators.http.HttpRequestInput;
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
    private final PayloadExtractor extractor;
    private final ResponseValidator validator;

    /**
     * @param registry frozen resource descriptor inventory
     * @param evaluator production BLOGE expression evaluator
     * @param objectMapper JSON mapper used by payload extraction
     */
    public ResourceFixtureRuntime(ResourceRegistry registry, BlgeExpressionEvaluator evaluator,
                                  ObjectMapper objectMapper) {
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
        this.evaluator = java.util.Objects.requireNonNull(evaluator, "evaluator");
        this.extractor = new PayloadExtractor(
                java.util.Objects.requireNonNull(objectMapper, "objectMapper"));
        this.validator = new ResponseValidator(evaluator);
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
        return behavior.boundary() == FixtureRule.DoubleBoundary.TRANSPORT
                ? executeDescriptorTransport(behavior, input, context)
                : executeDescriptorProtocol(behavior, input, context);
    }

    /** Interprets a raw response through the real response protocol without request construction. */
    public HttpResourceOutput executeDescriptorProtocol(
            FixtureRule.Behavior behavior, Object input, OperatorContext context) {
        requireRawResponse(behavior, FixtureRule.DoubleBoundary.NODE);
        String resourceId = resourceId(input);
        ResourceDescriptor descriptor = registry.resolve(resourceId);
        HttpResponseOutput response = response(behavior);
        ResponseValidator.ValidationResult validation = validator.validate(
                response, descriptor.responseProtocol());
        if (!validation.success()) {
            throw new ResourceCallException(resourceId, response.statusCode(),
                    validation.errorMessage(), response.body());
        }
        Object payload = extractPayload(response, descriptor);
        return new HttpResourceOutput(resourceId, response.statusCode(), payload,
                response.body(), response.duration(), true);
    }

    /** Runs the complete descriptor request pipeline with only HTTP transport replaced. */
    public HttpResourceOutput executeDescriptorTransport(
            FixtureRule.Behavior behavior, Object input, OperatorContext context) throws Exception {
        return executeDescriptorTransportObserved(behavior, input, context).output();
    }

    DescriptorTransportResult executeDescriptorTransportObserved(
            FixtureRule.Behavior behavior, Object input, OperatorContext context) throws Exception {
        requireRawResponse(behavior, FixtureRule.DoubleBoundary.TRANSPORT);
        StubHttpRequestOperator transport = new StubHttpRequestOperator(response(behavior));
        HttpResourceOperator operator = new HttpResourceOperator(
                transport, registry, evaluator, new UrlTemplateRenderer(), extractor, validator);
        HttpResourceOutput output = operator.execute(input, context);
        return new DescriptorTransportResult(output, transport.lastRequest());
    }

    private static void requireRawResponse(
            FixtureRule.Behavior behavior, FixtureRule.DoubleBoundary expectedBoundary) {
        if (behavior.statusCode() == null) {
            throw new TestControlException("CONTROL_PLAN_INVALID_RESOURCE_FIXTURE",
                    "RESOURCE_FIXTURE", "Descriptor response fixture requires statusCode.");
        }
        if (behavior.boundary() != expectedBoundary) {
            throw new TestControlException("CONTROL_PLAN_EXECUTION_MODE_MISMATCH",
                    "RESOURCE_FIXTURE", "Descriptor fixture boundary does not match execution mode.");
        }
    }

    private static HttpResponseOutput response(FixtureRule.Behavior behavior) {
        Map<String, List<String>> headers = behavior.headers().entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> List.of(entry.getValue())));
        return new HttpResponseOutput(
                behavior.statusCode(), headers, behavior.rawBody(), Duration.ZERO);
    }

    private Object extractPayload(HttpResponseOutput response, ResourceDescriptor descriptor) {
        if (descriptor.responseProtocol() instanceof ResponseProtocol.BlgeExpression expression
                && expression.payloadExpr() != null && !expression.payloadExpr().isBlank()) {
            Object parsedBody = extractor.extract(response.body(), null);
            Map<String, Object> expressionContext = Map.of(
                    "statusCode", response.statusCode(),
                    "headers", response.headers(),
                    "body", parsedBody == null ? Map.of() : parsedBody);
            return evaluator.evaluate(expression.payloadExpr(), expressionContext);
        }
        return extractor.extract(response.body(), descriptor.payloadPath());
    }

    private static String resourceId(Object input) {
        if (input instanceof HttpResourceInput resourceInput) {
            return resourceInput.resourceId();
        }
        if (input instanceof Map<?, ?> map) {
            Object value = map.get("resourceId");
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        throw new IllegalArgumentException(
                "httpResource input must contain a non-blank resourceId");
    }

    record DescriptorTransportResult(HttpResourceOutput output, HttpRequestInput request) {
    }
}
