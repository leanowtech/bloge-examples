package com.leanowtech.bloge.gateway.operator;

import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectJournal;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.exception.NonRetryableException;
import com.leanowtech.bloge.core.schema.SchemaAware;
import com.leanowtech.bloge.core.schema.SchemaDescriptor;
import com.leanowtech.bloge.core.schema.SchemaIntrospector;
import com.leanowtech.bloge.gateway.exception.ResourceCallException;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.resource.ParameterMapping;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceExecutionAdmissionRegistry;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.operators.http.HttpRequestInput;
import com.leanowtech.bloge.operators.http.HttpRequestOperator;
import com.leanowtech.bloge.operators.http.HttpResponseOutput;
import com.leanowtech.bloge.spring.annotation.BlogeOperator;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.UUID;

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

    private static final String MULTIPART_BOUNDARY_PREFIX = "----BLOGEFormBoundary";
    private static final String CRLF = "\r\n";

    private final HttpRequestTransport httpRequestOperator;
    private final ResourceRegistry registry;
    private final BlgeExpressionEvaluator evaluator;
    private final UrlTemplateRenderer renderer;
    private final PayloadExtractor extractor;
    private final ResponseValidator validator;
    private final ResourceExecutionAdmissionRegistry executionAdmissions;

    /**
     * @param httpRequestOperator the underlying HTTP client operator
     * @param registry            resource descriptor registry
     * @param evaluator           bloge expression evaluator for parameter mapping
     * @param renderer            URL template renderer
     * @param extractor           JSON payload extractor
     * @param validator           response protocol validator
     */
    @Autowired
    public HttpResourceOperator(
            HttpRequestOperator httpRequestOperator,
            ResourceRegistry registry,
            BlgeExpressionEvaluator evaluator,
            UrlTemplateRenderer renderer,
            PayloadExtractor extractor,
            ResponseValidator validator,
            ResourceExecutionAdmissionRegistry executionAdmissions) {
        this((input, context) -> httpRequestOperator.execute(input, context), registry, evaluator,
                renderer, extractor, validator, executionAdmissions);
    }

    /** Backward-compatible constructor for explicitly assembled resource operators. */
    public HttpResourceOperator(
            HttpRequestOperator httpRequestOperator,
            ResourceRegistry registry,
            BlgeExpressionEvaluator evaluator,
            UrlTemplateRenderer renderer,
            PayloadExtractor extractor,
            ResponseValidator validator) {
        this((input, context) -> httpRequestOperator.execute(input, context), registry, evaluator,
                renderer, extractor, validator, null);
    }

    /**
     * Creates a resource operator with an already isolated request transport.
     *
     * <p>This overload keeps simulation and fixture runtimes from constructing the production
     * HTTP client while preserving the existing production constructor above.</p>
     */
    public HttpResourceOperator(
            HttpRequestTransport httpRequestOperator,
            ResourceRegistry registry,
            BlgeExpressionEvaluator evaluator,
            UrlTemplateRenderer renderer,
            PayloadExtractor extractor,
            ResponseValidator validator) {
        this(httpRequestOperator, registry, evaluator, renderer, extractor, validator, null);
    }

    /** Creates a resource operator that also enforces controlled-run descriptor admission. */
    public HttpResourceOperator(
            HttpRequestTransport httpRequestOperator,
            ResourceRegistry registry,
            BlgeExpressionEvaluator evaluator,
            UrlTemplateRenderer renderer,
            PayloadExtractor extractor,
            ResponseValidator validator,
            ResourceExecutionAdmissionRegistry executionAdmissions) {
        this.httpRequestOperator = httpRequestOperator;
        this.registry = registry;
        this.evaluator = evaluator;
        this.renderer = renderer;
        this.extractor = extractor;
        this.validator = validator;
        this.executionAdmissions = executionAdmissions;
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
        if (executionAdmissions != null) {
            executionAdmissions.requireCurrent(ctx, descriptor);
        }

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
        Map<String, String> headers = new LinkedHashMap<>(mergeHeaders(
                descriptor.defaultHeaders(),
                dynamicHeaders,
                input.headerOverrides(),
                ctx.graphContext().tenantId(),
                ctx.graphContext().namespace()
        ));
        EncodedBody encodedBody = encodeBodyForContentType(body, headerValue(headers, "Content-Type"));
        body = encodedBody.body();
        if (!encodedBody.contentType().isBlank()) {
            putHeader(headers, "Content-Type", encodedBody.contentType());
        }

        ResourceDescriptor.ExternalWriteContract writeContract = descriptor.externalWrite()
                ? requireWriteContract(descriptor)
                : null;
        String idempotencyKey = "";
        String reconciliationLookupRef = "";
        if (writeContract != null) {
            idempotencyKey = requiredProtocolParam(input, writeContract.idempotencyKeyParam(), "idempotency key");
            reconciliationLookupRef = requiredProtocolParam(input,
                    writeContract.reconciliationLookupParam(), "reconciliation lookup reference");
            if (!evidenceSafeReference(reconciliationLookupRef)) {
                throw new ExternalWriteProtocolException(
                        "External write reconciliation lookup reference is not evidence-safe");
            }
            putHeader(headers, writeContract.idempotencyHeader(), idempotencyKey);
        }

        // 5. Pick timeout
        Duration requestedTimeout = input.timeoutOverride() != null
                ? input.timeoutOverride()
                : descriptor.defaultTimeout();
        Duration timeout = ctx.capTimeout(requestedTimeout,
                "resource call '" + input.resourceId() + "'");

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
        if (writeContract == null) {
            return executeAndValidate(input, descriptor, httpInput, ctx).output();
        }
        try (SideEffectJournal.Attempt attempt = ctx.beginSideEffect(
                "resource:" + descriptor.resourceId(), idempotencyKey, writeContract.reconcilerRef(),
                reconciliationLookupRef)) {
            ExecutedResource executed;
            try {
                executed = executeAndValidate(input, descriptor, httpInput, ctx);
            } catch (ResourceCallException exception) {
                if (writeContract.failureResponseNotCommitted()) {
                    attempt.notCommitted("PROVIDER_REJECTED_REQUEST");
                }
                throw exception;
            }
            String receiptId = responseHeaderValue(executed.response(), writeContract.receiptIdHeader());
            if (receiptId.isBlank()) {
                throw new ExternalWriteProtocolException(
                        "External write provider returned success without the required commit receipt header");
            }
            attempt.committed(new SideEffectJournal.Receipt(
                    receiptId,
                    writeContract.provider(),
                    responseHeaderValue(executed.response(), writeContract.transactionRefHeader()),
                    Instant.now(),
                    new SideEffectJournal.Proof(
                            responseHeaderValue(executed.response(), writeContract.proofReferenceHeader()),
                            responseHeaderValue(executed.response(), writeContract.proofFingerprintHeader()))),
                    "PROVIDER_COMMIT_RECEIPT");
            return executed.output();
        }
    }

    @Override
    public Idempotency idempotency() {
        return Idempotency.UNKNOWN;
    }

    @Override
    public SideEffectType sideEffectType() {
        return SideEffectType.EXTERNAL_CALL;
    }

    private ExecutedResource executeAndValidate(HttpResourceInput input,
                                                ResourceDescriptor descriptor,
                                                HttpRequestInput httpInput,
                                                OperatorContext ctx) throws Exception {
        if (executionAdmissions != null) {
            executionAdmissions.recordTransportDispatch(ctx, descriptor);
        }
        HttpResponseOutput httpResponse = httpRequestOperator.execute(httpInput, ctx);
        var validationResult = validator.validate(httpResponse, descriptor.responseProtocol());
        if (!validationResult.success()) {
            throw new ResourceCallException(
                    input.resourceId(), httpResponse.statusCode(), validationResult.errorMessage(),
                    httpResponse.body());
        }
        Object payload = extractPayload(httpResponse, descriptor);
        return new ExecutedResource(new HttpResourceOutput(
                input.resourceId(), httpResponse.statusCode(), payload, httpResponse.body(),
                httpResponse.duration(), true), httpResponse);
    }

    private static ResourceDescriptor.ExternalWriteContract requireWriteContract(ResourceDescriptor descriptor) {
        ResourceDescriptor.ExternalWriteContract contract = descriptor.externalWriteContract();
        if (contract == null || !contract.conformant()) {
            throw new ExternalWriteProtocolException(
                    "External HTTP write is blocked until its descriptor declares a conformant externalWriteContract");
        }
        return contract;
    }

    private static String requiredProtocolParam(HttpResourceInput input, String parameter, String label) {
        Object value = input.params().get(parameter);
        String normalized = value == null ? "" : String.valueOf(value).trim();
        if (normalized.isBlank()) {
            throw new ExternalWriteProtocolException("External HTTP write requires a " + label);
        }
        return normalized;
    }

    private static boolean evidenceSafeReference(String value) {
        return value != null
                && value.length() <= 1024
                && !value.contains("?")
                && !value.contains("#")
                && !value.contains("@")
                && value.matches("[A-Za-z][A-Za-z0-9+.-]{1,31}:(//)?[A-Za-z0-9._:/-]+");
    }

    private static String responseHeaderValue(HttpResponseOutput response, String headerName) {
        if (response == null || headerName == null || headerName.isBlank()) {
            return "";
        }
        return response.headers().entrySet().stream()
                .filter(entry -> headerName.equalsIgnoreCase(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private record ExecutedResource(HttpResourceOutput output, HttpResponseOutput response) {
    }

    private static final class ExternalWriteProtocolException extends NonRetryableException {
        private ExternalWriteProtocolException(String message) {
            super(message);
        }
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

    private static EncodedBody encodeBodyForContentType(Object body, String contentType) {
        if (body == null || body instanceof String) {
            return new EncodedBody(body, "");
        }
        if (formUrlEncodedContent(contentType) && body instanceof Map<?, ?> formValues) {
            return new EncodedBody(encodeFormUrlEncodedBody(formValues), "");
        }
        if (multipartFormDataContent(contentType) && body instanceof Map<?, ?> formValues) {
            String boundary = multipartBoundary(contentType);
            if (boundary.isBlank()) {
                boundary = newMultipartBoundary();
                return new EncodedBody(encodeMultipartFormDataBody(formValues, boundary),
                        appendMultipartBoundary(contentType, boundary));
            }
            return new EncodedBody(encodeMultipartFormDataBody(formValues, boundary), "");
        }
        return new EncodedBody(body, "");
    }

    private static boolean formUrlEncodedContent(String contentType) {
        if (contentType == null) {
            return false;
        }
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        return "application/x-www-form-urlencoded".equals(mediaType);
    }

    private static boolean multipartFormDataContent(String contentType) {
        if (contentType == null) {
            return false;
        }
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        return "multipart/form-data".equals(mediaType);
    }

    private static String encodeFormUrlEncodedBody(Map<?, ?> formValues) {
        StringJoiner joiner = new StringJoiner("&");
        for (var entry : formValues.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (value instanceof Iterable<?> values && !(value instanceof CharSequence)) {
                for (Object item : values) {
                    if (item != null) {
                        addFormPair(joiner, entry.getKey(), item);
                    }
                }
            } else {
                addFormPair(joiner, entry.getKey(), value);
            }
        }
        return joiner.toString();
    }

    private static void addFormPair(StringJoiner joiner, Object key, Object value) {
        joiner.add(URLEncoder.encode(Objects.toString(key, ""), StandardCharsets.UTF_8)
                + "="
                + URLEncoder.encode(Objects.toString(value, ""), StandardCharsets.UTF_8));
    }

    private static String encodeMultipartFormDataBody(Map<?, ?> formValues, String boundary) {
        StringBuilder builder = new StringBuilder();
        for (var entry : formValues.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (value instanceof Iterable<?> values && !(value instanceof CharSequence)) {
                for (Object item : values) {
                    if (item != null) {
                        addMultipartPart(builder, boundary, entry.getKey(), item);
                    }
                }
            } else {
                addMultipartPart(builder, boundary, entry.getKey(), value);
            }
        }
        builder.append("--").append(boundary).append("--").append(CRLF);
        return builder.toString();
    }

    private static void addMultipartPart(StringBuilder builder, String boundary, Object key, Object value) {
        builder.append("--").append(boundary).append(CRLF);
        builder.append("Content-Disposition: form-data; name=\"")
                .append(escapeMultipartName(Objects.toString(key, "")))
                .append("\"")
                .append(CRLF)
                .append(CRLF);
        builder.append(Objects.toString(value, "")).append(CRLF);
    }

    private static String escapeMultipartName(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "");
    }

    private static String multipartBoundary(String contentType) {
        if (contentType == null) {
            return "";
        }
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            int separator = trimmed.indexOf('=');
            if (separator < 0) {
                continue;
            }
            String name = trimmed.substring(0, separator).trim();
            if (!"boundary".equalsIgnoreCase(name)) {
                continue;
            }
            String value = trimmed.substring(separator + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                return value.substring(1, value.length() - 1);
            }
            return value;
        }
        return "";
    }

    private static String appendMultipartBoundary(String contentType, String boundary) {
        String base = contentType == null || contentType.isBlank() ? "multipart/form-data" : contentType.trim();
        return base + "; boundary=" + boundary;
    }

    private static String newMultipartBoundary() {
        return MULTIPART_BOUNDARY_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    private static String headerValue(Map<String, String> headers, String name) {
        for (var entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static void putHeader(Map<String, String> headers, String name, String value) {
        String existing = null;
        for (String key : headers.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                existing = key;
                break;
            }
        }
        if (existing != null) {
            headers.remove(existing);
        }
        headers.put(name, value);
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

    private record EncodedBody(Object body, String contentType) {
        private EncodedBody {
            contentType = contentType == null ? "" : contentType;
        }
    }
}
