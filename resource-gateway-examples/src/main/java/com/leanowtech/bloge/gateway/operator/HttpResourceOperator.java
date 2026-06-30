package com.leanowtech.bloge.gateway.operator;

import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.schema.SchemaAware;
import com.leanowtech.bloge.core.schema.SchemaDescriptor;
import com.leanowtech.bloge.core.schema.SchemaIntrospector;
import com.leanowtech.bloge.gateway.exception.ResourceCallException;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.resource.ParameterMapping;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.operators.http.HttpRequestInput;
import com.leanowtech.bloge.operators.http.HttpRequestOperator;
import com.leanowtech.bloge.operators.http.HttpResponseOutput;
import com.leanowtech.bloge.spring.annotation.BlogeOperator;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Generic HTTP resource operator that resolves an API descriptor from the registry
 * and executes the call.
 *
 * <p>Orchestrates the full lifecycle of a resource call:
 * <ol>
 *   <li>Resolve the {@link ResourceDescriptor} from the {@link ResourceRegistry}</li>
 *   <li>Evaluate {@link ParameterMapping} expressions against the input parameters</li>
 *   <li>Render the URL template with path variables and append query parameters</li>
 *   <li>Merge default headers with per-call overrides</li>
 *   <li>Build and execute the HTTP request via {@link HttpRequestOperator}</li>
 *   <li>Validate the response against the descriptor's {@code ResponseProtocol}</li>
 *   <li>Extract the payload using the descriptor's {@code payloadPath}</li>
 * </ol>
 */
@BlogeOperator(
    value = "httpResource",
    description = "Generic HTTP resource operator that resolves an API descriptor from the registry and executes the call",
    owner = "bloge-platform",
    tags = {"http", "resource", "gateway", "api"}
)
public class HttpResourceOperator implements Operator<Object, HttpResourceOutput>, SchemaAware {

    private final HttpRequestOperator httpRequestOperator;
    private final ResourceRegistry registry;
    private final BlgeExpressionEvaluator evaluator;
    private final UrlTemplateRenderer renderer;
    private final PayloadExtractor extractor;
    private final ResponseValidator validator;

    /**
     * @param httpRequestOperator the underlying HTTP client operator
     * @param registry            resource descriptor registry
     * @param evaluator           bloge expression evaluator for parameter mapping
     * @param renderer            URL template renderer
     * @param extractor           JSON payload extractor
     * @param validator           response protocol validator
     */
    public HttpResourceOperator(
            HttpRequestOperator httpRequestOperator,
            ResourceRegistry registry,
            BlgeExpressionEvaluator evaluator,
            UrlTemplateRenderer renderer,
            PayloadExtractor extractor,
            ResponseValidator validator) {
        this.httpRequestOperator = httpRequestOperator;
        this.registry = registry;
        this.evaluator = evaluator;
        this.renderer = renderer;
        this.extractor = extractor;
        this.validator = validator;
    }

    @Override
    public SchemaDescriptor inputSchema() {
        return SchemaIntrospector.introspect(HttpResourceInput.class);
    }

    @Override
    public SchemaDescriptor outputSchema() {
        return SchemaIntrospector.introspect(HttpResourceOutput.class);
    }

    @Override
    public HttpResourceOutput execute(Object input, OperatorContext ctx) throws Exception {
        return execute(normalizeInput(input), ctx);
    }

    /**
     * Executes a resource call with a fully normalized typed input.
     *
     * @param input the typed resource-call input
     * @param ctx   the operator context
     * @return the validated resource response envelope
     * @throws Exception if resolution, execution, or validation fails
     */
    public HttpResourceOutput execute(HttpResourceInput input, OperatorContext ctx) throws Exception {
        // 1. Resolve descriptor
        ResourceDescriptor descriptor = registry.resolve(input.resourceId());

        // 2. Evaluate parameter mapping expressions
        Map<String, Object> exprContext = Map.of(
                "params", input.params(),
                "tenantId", ctx.graphContext().tenantId(),
                "namespace", ctx.graphContext().namespace()
        );
        ParameterMapping mapping = descriptor.parameterMapping();

        Map<String, String> pathValues = evaluatePathExpressions(mapping, exprContext);
        Map<String, String> queryParams = evaluateQueryExpressions(mapping, exprContext);
        Map<String, String> dynamicHeaders = dynamicHeaders(mapping, exprContext);
        Object body = evaluateBodyExpression(mapping, exprContext);

        // 3. Render URL with path variables and append query parameters
        String url = renderer.render(descriptor.urlTemplate(), pathValues);
        url = appendQueryParams(url, queryParams);

        // 4. Merge headers: descriptor defaults + dynamic expressions + per-call overrides
        Map<String, String> headers = mergeHeaders(
                descriptor.defaultHeaders(),
                dynamicHeaders,
                input.headerOverrides(),
                ctx.graphContext().tenantId(),
                ctx.graphContext().namespace()
        );

        // 5. Pick timeout
        Duration timeout = input.timeoutOverride() != null ? input.timeoutOverride() : descriptor.defaultTimeout();

        // 6. Pick auth: per-call override takes precedence over descriptor default
        var auth = input.authOverride() != null ? input.authOverride() : descriptor.authStrategy();

        // 7. Build and execute HTTP request
        var httpInput = new HttpRequestInput(
            url,
            descriptor.method(),
            headers,
            body,
            auth,
            timeout,
            true
        );
        HttpResponseOutput httpResponse = httpRequestOperator.execute(httpInput, ctx);

        // 8. Validate response
        var validationResult = validator.validate(httpResponse, descriptor.responseProtocol());

        // 9. If validation fails, throw
        if (!validationResult.success()) {
            throw new ResourceCallException(
                input.resourceId(),
                httpResponse.statusCode(),
                validationResult.errorMessage(),
                httpResponse.body()
            );
        }

        // 10. Extract payload
        Object payload = extractPayload(httpResponse, descriptor);

        // 11. Return result
        return new HttpResourceOutput(
            input.resourceId(),
            httpResponse.statusCode(),
            payload,
            httpResponse.body(),
            httpResponse.duration(),
            true
        );
    }

    @Override
    public Idempotency idempotency() {
        return Idempotency.UNKNOWN;
    }

    @Override
    public SideEffectType sideEffectType() {
        return SideEffectType.EXTERNAL_CALL;
    }

    private static HttpResourceInput normalizeInput(Object rawInput) {
        if (rawInput instanceof HttpResourceInput input) {
            return input;
        }
        if (rawInput instanceof Map<?, ?> map) {
            return new HttpResourceInput(
                    requiredString(map, "resourceId"),
                    toObjectMap(map.get("params")),
                    toStringMap(map.get("headerOverrides")),
                    toAuth(map.get("authOverride")),
                    toDuration(map.get("timeoutOverride"))
            );
        }
        throw new IllegalArgumentException("httpResource input must be HttpResourceInput or Map, but was "
                + (rawInput == null ? "null" : rawInput.getClass().getName()));
    }

    private static String requiredString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required input field: " + key);
        }
        String text = Objects.toString(value, "").trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Input field must not be blank: " + key);
        }
        return text;
    }

    private static Map<String, Object> toObjectMap(Object rawValue) {
        if (rawValue == null) {
            return Map.of();
        }
        if (!(rawValue instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected params to be a map but was "
                    + rawValue.getClass().getName());
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            normalized.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return normalized;
    }

    private static Map<String, String> toStringMap(Object rawValue) {
        if (rawValue == null) {
            return Map.of();
        }
        if (!(rawValue instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected headerOverrides to be a map but was "
                    + rawValue.getClass().getName());
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            normalized.put(String.valueOf(entry.getKey()), Objects.toString(entry.getValue(), null));
        }
        return normalized;
    }

    private static HttpRequestInput.HttpAuth toAuth(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        if (rawValue instanceof HttpRequestInput.HttpAuth auth) {
            return auth;
        }
        if (rawValue instanceof Map<?, ?> map) {
            String type = requiredString(map, "type");
            return switch (type) {
                case "bearer" -> new HttpRequestInput.BearerAuth(requiredString(map, "token"));
                case "basic" -> new HttpRequestInput.BasicAuth(
                        requiredString(map, "username"),
                        requiredString(map, "password")
                );
                case "apiKey" -> new HttpRequestInput.ApiKeyAuth(
                        requiredString(map, "headerName"),
                        map.containsKey("key") ? requiredString(map, "key") : requiredString(map, "apiKey")
                );
                default -> throw new IllegalArgumentException("Unsupported authOverride type: " + type);
            };
        }
        throw new IllegalArgumentException("Expected authOverride to be HttpAuth or Map but was "
                + rawValue.getClass().getName());
    }

    private static Duration toDuration(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        if (rawValue instanceof Duration duration) {
            return duration;
        }
        if (rawValue instanceof String text && !text.isBlank()) {
            return Duration.parse(text);
        }
        throw new IllegalArgumentException("Expected timeoutOverride to be Duration or ISO-8601 string but was "
                + rawValue.getClass().getName());
    }

    private Map<String, String> evaluatePathExpressions(ParameterMapping mapping, Map<String, Object> context) {
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : mapping.pathExpressions().entrySet()) {
            Object value = evaluator.evaluate(entry.getValue(), context);
            result.put(entry.getKey(), value != null ? value.toString() : "");
        }
        return result;
    }

    private Map<String, String> evaluateQueryExpressions(ParameterMapping mapping, Map<String, Object> context) {
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : mapping.queryExpressions().entrySet()) {
            Object value = evaluator.evaluate(entry.getValue(), context);
            if (value != null) {
                result.put(entry.getKey(), value.toString());
            }
        }
        return result;
    }

    private Map<String, String> evaluateHeaderExpressions(ParameterMapping mapping, Map<String, Object> context) {
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : mapping.headerExpressions().entrySet()) {
            Object value = evaluator.evaluate(entry.getValue(), context);
            if (value != null) {
                result.put(entry.getKey(), value.toString());
            }
        }
        return result;
    }

    private Map<String, String> dynamicHeaders(ParameterMapping mapping, Map<String, Object> context) {
        Map<String, String> headers = new LinkedHashMap<>();
        String cookieHeader = evaluateCookieHeader(mapping, context);
        if (!cookieHeader.isBlank()) {
            headers.put("Cookie", cookieHeader);
        }
        headers.putAll(evaluateHeaderExpressions(mapping, context));
        return headers;
    }

    private String evaluateCookieHeader(ParameterMapping mapping, Map<String, Object> context) {
        if (mapping.cookieExpressions().isEmpty()) {
            return "";
        }
        List<String> pairs = new ArrayList<>();
        for (var entry : mapping.cookieExpressions().entrySet()) {
            Object value = evaluator.evaluate(entry.getValue(), context);
            if (value != null) {
                pairs.add(entry.getKey() + "=" + encodeCookieValue(value.toString()));
            }
        }
        return String.join("; ", pairs);
    }

    private Object evaluateBodyExpression(ParameterMapping mapping, Map<String, Object> context) {
        if (mapping.bodyExpression() == null || mapping.bodyExpression().isBlank()) {
            return null;
        }
        return evaluator.evaluate(mapping.bodyExpression(), context);
    }

    private static String appendQueryParams(String url, Map<String, String> queryParams) {
        if (queryParams.isEmpty()) {
            return url;
        }
        StringJoiner joiner = new StringJoiner("&");
        for (var entry : queryParams.entrySet()) {
            joiner.add(
                URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "=" +
                URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)
            );
        }
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + joiner;
    }

    private static String encodeCookieValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private Object extractPayload(HttpResponseOutput response, ResourceDescriptor descriptor) {
        if (descriptor.responseProtocol() instanceof ResponseProtocol.BlgeExpression expression
                && expression.payloadExpr() != null
                && !expression.payloadExpr().isBlank()) {
            Object parsedBody = extractor.extract(response.body(), null);
            Map<String, Object> context = Map.of(
                    "statusCode", response.statusCode(),
                    "headers", response.headers(),
                    "body", parsedBody == null ? Map.of() : parsedBody
            );
            return evaluator.evaluate(expression.payloadExpr(), context);
        }
        return extractor.extract(response.body(), descriptor.payloadPath());
    }

    private static Map<String, String> mergeHeaders(Map<String, String> defaults,
                                                    Map<String, String> dynamicHeaders,
                                                    Map<String, String> overrides,
                                                    String tenantId,
                                                    String namespace) {
        Map<String, String> merged = new HashMap<>(defaults);
        if (!dynamicHeaders.isEmpty()) {
            merged.putAll(dynamicHeaders);
        }
        if (!overrides.isEmpty()) {
            merged.putAll(overrides);
        }
        merged.put("X-Tenant-Id", tenantId);
        merged.put("X-Namespace", namespace);
        return Map.copyOf(merged);
    }
}
