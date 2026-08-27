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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Executes descriptor-backed resource fixtures through the real request mapping, response
 * protocol, and payload extraction pipeline while replacing only the HTTP transport.
 */
public class ResourceFixtureRuntime {

    private final ResourceRegistry registry;
    private final BlgeExpressionEvaluator evaluator;
    private final ObjectMapper objectMapper;
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
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
        this.extractor = new PayloadExtractor(
                java.util.Objects.requireNonNull(objectMapper, "objectMapper"));
        this.validator = new ResponseValidator(evaluator);
    }

    /**
     * Projects governed business material through the descriptor's real success protocol and
     * payload path. This keeps protocol/transport evidence server-authoritative while ensuring
     * the subsequent execution still exercises the real descriptor pipeline.
     *
     * @param resourceId exact configured resource descriptor id
     * @param payload governed, schema-compatible business payload
     * @param boundary descriptor protocol or complete descriptor transport boundary
     * @return raw-response behavior consumable by the descriptor-backed fixture runtime
     */
    public FixtureRule.Behavior projectGovernedPayload(
            String resourceId, Object payload, FixtureRule.DoubleBoundary boundary) {
        ResourceDescriptor descriptor = registry.resolve(resourceId);
        if (descriptor.responseProtocol() instanceof ResponseProtocol.BlgeExpression) {
            throw new IllegalArgumentException(
                    "Expression response protocols cannot project governed material");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (descriptor.responseProtocol() instanceof ResponseProtocol.BodyCode protocol) {
            setPath(body, protocol.codePath(), protocol.successValues().iterator().next());
        } else if (descriptor.responseProtocol() instanceof ResponseProtocol.BodyFlag protocol) {
            setPath(body, protocol.flagPath(), true);
        }
        setPath(body, descriptor.payloadPath(), payload);
        String rawBody;
        try {
            rawBody = objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Governed material could not be serialized", failure);
        }
        return FixtureRule.Behavior.protocolResponse(
                rawBody, successStatus(descriptor.responseProtocol()),
                Map.of("Content-Type", "application/json"), boundary);
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

    private static int successStatus(ResponseProtocol protocol) {
        if (protocol instanceof ResponseProtocol.BodyCode bodyCode
                && bodyCode.successValues().iterator().next() instanceof Number code) {
            return code.intValue();
        }
        if (protocol instanceof ResponseProtocol.StatusCodes statusCodes) {
            return statusCodes.successCodes().iterator().next();
        }
        return 200;
    }

    private static void setPath(Map<String, Object> target, String path, Object value) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Descriptor response path must not be blank");
        }
        String[] segments = path.split("\\.");
        Map<String, Object> current = target;
        for (int index = 0; index < segments.length - 1; index += 1) {
            Object child = current.computeIfAbsent(segments[index], key -> new LinkedHashMap<String, Object>());
            if (!(child instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Descriptor response path conflicts with success marker");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> nested = (Map<String, Object>) child;
            current = nested;
        }
        current.put(segments[segments.length - 1], value);
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
