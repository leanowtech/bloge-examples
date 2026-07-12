package com.leanowtech.bloge.gateway.operator;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.ExecutionBudget;
import com.leanowtech.bloge.core.spi.TimeSource;
import com.leanowtech.bloge.gateway.exception.ResourceCallException;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.resource.ParameterMapping;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.operators.http.HttpRequestInput;
import com.leanowtech.bloge.operators.http.HttpRequestOperator;
import com.leanowtech.bloge.operators.http.HttpResponseOutput;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Component tests for {@link HttpResourceOperator}.
 *
 * <p>Uses a stub {@link HttpRequestOperator} to capture the outgoing request and
 * return predetermined responses, allowing us to test URL rendering, header injection,
 * timeout selection, payload extraction, and validation failure handling without
 * any real HTTP traffic.
 */
class HttpResourceOperatorTest {

    private StubHttpRequestOperator httpStub;
    private StubResourceRegistry registry;
    private BlgeExpressionEvaluator evaluator;
    private HttpResourceOperator operator;

    @BeforeEach
    void setUp() {
        httpStub = new StubHttpRequestOperator();
        registry = new StubResourceRegistry();
        evaluator = new BlgeExpressionEvaluator();

        operator = new HttpResourceOperator(
                httpStub,
                registry,
                evaluator,
                new UrlTemplateRenderer(),
                new PayloadExtractor(),
                new ResponseValidator(evaluator)
        );
    }

    private OperatorContext operatorContext() {
        var graphContext = new GraphContext(new TenantContext("tenant-A", "ns-1"));
        return new OperatorContext("testNode", "testGraph", graphContext, 0);
    }

    // ── URL path & query rendering ──────────────────────────────────────

    @Test
    @DisplayName("path variables are substituted and query params appended")
    void urlPathAndQueryRendering() throws Exception {
        registry.put(new ResourceDescriptor(
                "svc.method", "https://api.example.com/users/{userId}/items", "GET",
                Map.of(), null, Duration.ofSeconds(5),
                new ParameterMapping(
                        Map.of("userId", "ctx.params.userId"),
                        Map.of("page", "ctx.params.page"),
                        null
                ),
                new ResponseProtocol.HttpStatus(), null
        ));

        httpStub.setResponse(new HttpResponseOutput(200, Map.of(), """
                {"result":"ok"}""", Duration.ofMillis(10)));

        var input = new HttpResourceInput("svc.method",
                Map.of("userId", "u42", "page", "3"));
        operator.execute(input, operatorContext());

        assertThat(httpStub.lastUrl()).contains("/users/u42/items");
        assertThat(httpStub.lastUrl()).contains("page=3");
    }

    // ── Tenant headers ──────────────────────────────────────────────────

    @Test
    @DisplayName("X-Tenant-Id and X-Namespace headers injected from context")
    void tenantHeadersInjected() throws Exception {
        registry.put(simpleDescriptor("hdr.svc"));
        httpStub.setResponse(new HttpResponseOutput(200, Map.of(), "{}", Duration.ofMillis(10)));

        operator.execute(new HttpResourceInput("hdr.svc", Map.of()), operatorContext());

        Map<String, String> headers = httpStub.lastHeaders();
        assertThat(headers).containsEntry("X-Tenant-Id", "tenant-A");
        assertThat(headers).containsEntry("X-Namespace", "ns-1");
    }

    @Test
    @DisplayName("descriptor header expressions are evaluated before request execution")
    void dynamicHeaderExpressions() throws Exception {
        registry.put(new ResourceDescriptor(
                "hdr.dynamic", "https://api.example.com/test", "GET",
                Map.of("Accept", "application/json", "X-Request-Id", "default-request"), null,
                Duration.ofSeconds(5),
                new ParameterMapping(
                        Map.of(),
                        Map.of(),
                        Map.of("X-Request-Id", "ctx.params[\"X-Request-Id\"]",
                                "X-User-Segment", "ctx.params.segment"),
                        null
                ),
                new ResponseProtocol.HttpStatus(), null
        ));
        httpStub.setResponse(new HttpResponseOutput(200, Map.of(), "{}", Duration.ofMillis(10)));

        operator.execute(new HttpResourceInput("hdr.dynamic",
                Map.of("X-Request-Id", "req-42", "segment", "gold")), operatorContext());

        Map<String, String> headers = httpStub.lastHeaders();
        assertThat(headers)
                .containsEntry("Accept", "application/json")
                .containsEntry("X-Request-Id", "req-42")
                .containsEntry("X-User-Segment", "gold")
                .containsEntry("X-Tenant-Id", "tenant-A")
                .containsEntry("X-Namespace", "ns-1");
    }

    @Test
    @DisplayName("per-call header overrides take precedence over descriptor header expressions")
    void dynamicHeaderExpressionsRespectOverrides() throws Exception {
        registry.put(new ResourceDescriptor(
                "hdr.override", "https://api.example.com/test", "GET",
                Map.of(), null, Duration.ofSeconds(5),
                new ParameterMapping(
                        Map.of(),
                        Map.of(),
                        Map.of("X-Request-Id", "ctx.params.requestId"),
                        null
                ),
                new ResponseProtocol.HttpStatus(), null
        ));
        httpStub.setResponse(new HttpResponseOutput(200, Map.of(), "{}", Duration.ofMillis(10)));

        operator.execute(new HttpResourceInput("hdr.override",
                Map.of("requestId", "descriptor-value"),
                Map.of("X-Request-Id", "override-value"),
                null), operatorContext());

        assertThat(httpStub.lastHeaders()).containsEntry("X-Request-Id", "override-value");
    }

    @Test
    @DisplayName("descriptor cookie expressions are assembled into a Cookie header")
    void dynamicCookieExpressions() throws Exception {
        registry.put(new ResourceDescriptor(
                "cookie.dynamic", "https://api.example.com/test", "GET",
                Map.of("Accept", "application/json"), null, Duration.ofSeconds(5),
                new ParameterMapping(
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of("SESSION", "ctx.params.sessionId",
                                "tenant_pref", "ctx.tenantId"),
                        null
                ),
                new ResponseProtocol.HttpStatus(), null
        ));
        httpStub.setResponse(new HttpResponseOutput(200, Map.of(), "{}", Duration.ofMillis(10)));

        operator.execute(new HttpResourceInput("cookie.dynamic",
                Map.of("sessionId", "abc 123")), operatorContext());

        assertThat(httpStub.lastHeaders())
                .containsEntry("Accept", "application/json")
                .containsKey("Cookie");
        assertThat(httpStub.lastHeaders().get("Cookie").split("; "))
                .containsExactlyInAnyOrder("SESSION=abc%20123", "tenant_pref=tenant-A");
    }

    @Test
    @DisplayName("per-call Cookie header override takes precedence over descriptor cookie expressions")
    void dynamicCookieExpressionsRespectOverrides() throws Exception {
        registry.put(new ResourceDescriptor(
                "cookie.override", "https://api.example.com/test", "GET",
                Map.of(), null, Duration.ofSeconds(5),
                new ParameterMapping(
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of("SESSION", "ctx.params.sessionId"),
                        null
                ),
                new ResponseProtocol.HttpStatus(), null
        ));
        httpStub.setResponse(new HttpResponseOutput(200, Map.of(), "{}", Duration.ofMillis(10)));

        operator.execute(new HttpResourceInput("cookie.override",
                Map.of("sessionId", "descriptor-value"),
                Map.of("Cookie", "SESSION=override-value"),
                null), operatorContext());

        assertThat(httpStub.lastHeaders()).containsEntry("Cookie", "SESSION=override-value");
    }

    @Test
    @DisplayName("application/x-www-form-urlencoded body maps are encoded before HTTP execution")
    void formUrlEncodedBodyMapsAreEncoded() throws Exception {
        registry.put(new ResourceDescriptor(
                "form.submit", "https://api.example.com/forms", "POST",
                Map.of(
                        "Accept", "application/json",
                        "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8"
                ),
                null, Duration.ofSeconds(5),
                new ParameterMapping(
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        "ctx.params.body"
                ),
                new ResponseProtocol.HttpStatus(), null
        ));
        httpStub.setResponse(new HttpResponseOutput(200, Map.of(), "{}", Duration.ofMillis(10)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("priority", "high");
        body.put("note", "rush order");
        body.put("tag", List.of("vip", "gift"));

        operator.execute(new HttpResourceInput("form.submit", Map.of("body", body)), operatorContext());

        assertThat(httpStub.lastBody()).isEqualTo("priority=high&note=rush+order&tag=vip&tag=gift");
    }

    @Test
    @DisplayName("multipart/form-data body maps are encoded and boundary is added")
    void multipartFormDataBodyMapsAreEncoded() throws Exception {
        registry.put(new ResourceDescriptor(
                "form.multipart", "https://api.example.com/forms", "POST",
                Map.of(
                        "Accept", "application/json",
                        "Content-Type", "multipart/form-data"
                ),
                null, Duration.ofSeconds(5),
                new ParameterMapping(
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        "ctx.params.body"
                ),
                new ResponseProtocol.HttpStatus(), null
        ));
        httpStub.setResponse(new HttpResponseOutput(200, Map.of(), "{}", Duration.ofMillis(10)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("priority", "high");
        body.put("note", "rush order");
        body.put("tag", List.of("vip", "gift"));

        operator.execute(new HttpResourceInput("form.multipart", Map.of("body", body)), operatorContext());

        assertThat(httpStub.lastHeaders().get("Content-Type"))
                .startsWith("multipart/form-data; boundary=");
        String boundary = httpStub.lastHeaders().get("Content-Type").substring(
                "multipart/form-data; boundary=".length());
        assertThat(httpStub.lastBody()).isInstanceOf(String.class);
        assertThat((String) httpStub.lastBody())
                .contains("--" + boundary + "\r\n")
                .contains("Content-Disposition: form-data; name=\"priority\"\r\n\r\nhigh\r\n")
                .contains("Content-Disposition: form-data; name=\"note\"\r\n\r\nrush order\r\n")
                .contains("Content-Disposition: form-data; name=\"tag\"\r\n\r\nvip\r\n")
                .contains("Content-Disposition: form-data; name=\"tag\"\r\n\r\ngift\r\n")
                .endsWith("--" + boundary + "--\r\n");
    }

    // ── Timeout default vs override ─────────────────────────────────────

    @Test
    @DisplayName("descriptor default timeout used when no override")
    void descriptorDefaultTimeout() throws Exception {
        registry.put(new ResourceDescriptor(
                "timeout.svc", "https://api.example.com/test", "GET",
                Map.of(), null, Duration.ofSeconds(7),
                null, new ResponseProtocol.HttpStatus(), null
        ));
        httpStub.setResponse(new HttpResponseOutput(200, Map.of(), "{}", Duration.ofMillis(10)));

        operator.execute(new HttpResourceInput("timeout.svc", Map.of()), operatorContext());

        assertThat(httpStub.lastTimeout()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    @DisplayName("per-call timeout override takes precedence")
    void timeoutOverride() throws Exception {
        registry.put(new ResourceDescriptor(
                "timeout.svc", "https://api.example.com/test", "GET",
                Map.of(), null, Duration.ofSeconds(7),
                null, new ResponseProtocol.HttpStatus(), null
        ));
        httpStub.setResponse(new HttpResponseOutput(200, Map.of(), "{}", Duration.ofMillis(10)));

        var input = new HttpResourceInput("timeout.svc", Map.of(), Map.of(), Duration.ofSeconds(2));
        operator.execute(input, operatorContext());

        assertThat(httpStub.lastTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("graph remaining budget caps descriptor and per-call timeout")
    void remainingGraphBudgetCapsResourceTimeout() throws Exception {
        registry.put(new ResourceDescriptor(
                "timeout.svc", "https://api.example.com/test", "GET",
                Map.of(), null, Duration.ofSeconds(30),
                null, new ResponseProtocol.HttpStatus(), null
        ));
        httpStub.setResponse(new HttpResponseOutput(200, Map.of(), "{}", Duration.ofMillis(10)));
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var graphContext = new GraphContext(new TenantContext("tenant-A", "ns-1"));
        graphContext.bindExecutionBudget(ExecutionBudget.until(now.plusSeconds(5), Duration.ofSeconds(1)));
        var context = new OperatorContext("testNode", "testGraph", graphContext, 0, "exec-budget",
                new FixedTimeSource(now));

        operator.execute(new HttpResourceInput("timeout.svc", Map.of(), Map.of(), Duration.ofSeconds(20)), context);

        assertThat(httpStub.lastTimeout()).isEqualTo(Duration.ofSeconds(4));
    }

    // ── Auth override precedence ────────────────────────────────────────

    @Test
    @DisplayName("authOverride takes precedence over descriptor authStrategy")
    void authOverridePrecedence() throws Exception {
        registry.put(new ResourceDescriptor(
                "auth.svc", "https://api.example.com/secure", "GET",
                Map.of(), new HttpRequestInput.BearerAuth("descriptor-token"), Duration.ofSeconds(5),
                null, new ResponseProtocol.HttpStatus(), null
        ));
        httpStub.setResponse(new HttpResponseOutput(200, Map.of(), "{}", Duration.ofMillis(10)));

        var overrideAuth = new HttpRequestInput.BearerAuth("override-token");
        var input = new HttpResourceInput("auth.svc", Map.of(), Map.of(), overrideAuth, null);
        operator.execute(input, operatorContext());

        assertThat(httpStub.lastAuth()).isEqualTo(overrideAuth);
    }

    @Test
    @DisplayName("raw DSL map input is normalized before authOverride precedence is applied")
    void rawMapInputAuthOverridePrecedence() throws Exception {
        registry.put(new ResourceDescriptor(
                "auth.raw", "https://api.example.com/secure", "GET",
                Map.of(), new HttpRequestInput.BearerAuth("descriptor-token"), Duration.ofSeconds(5),
                null, new ResponseProtocol.HttpStatus(), null
        ));
        httpStub.setResponse(new HttpResponseOutput(200, Map.of(), "{}", Duration.ofMillis(10)));

        var overrideAuth = new HttpRequestInput.BearerAuth("override-token");
        operator.execute(Map.of(
                "resourceId", "auth.raw",
                "params", Map.of(),
                "headerOverrides", Map.of(),
                "authOverride", overrideAuth
        ), operatorContext());

        assertThat(httpStub.lastAuth()).isEqualTo(overrideAuth);
    }

    @Test
    @DisplayName("structured authOverride maps are normalized for DSL-style inputs")
    void rawMapInputStructuredAuthOverride() throws Exception {
        registry.put(new ResourceDescriptor(
                "auth.apiKey", "https://api.example.com/secure", "GET",
                Map.of(), null, Duration.ofSeconds(5),
                null, new ResponseProtocol.HttpStatus(), null
        ));
        httpStub.setResponse(new HttpResponseOutput(200, Map.of(), "{}", Duration.ofMillis(10)));

        operator.execute(Map.of(
                "resourceId", "auth.apiKey",
                "params", Map.of(),
                "headerOverrides", Map.of(),
                "authOverride", Map.of(
                        "type", "apiKey",
                        "headerName", "X-Api-Key",
                        "key", "override-key"
                )
        ), operatorContext());

        assertThat(httpStub.lastAuth()).isEqualTo(new HttpRequestInput.ApiKeyAuth("X-Api-Key", "override-key"));
    }

    @Test
    @DisplayName("descriptor authStrategy used when no authOverride")
    void descriptorAuthUsedWhenNoOverride() throws Exception {
        var descriptorAuth = new HttpRequestInput.ApiKeyAuth("X-Api-Key", "secret-key");
        registry.put(new ResourceDescriptor(
                "auth.svc2", "https://api.example.com/api", "GET",
                Map.of(), descriptorAuth, Duration.ofSeconds(5),
                null, new ResponseProtocol.HttpStatus(), null
        ));
        httpStub.setResponse(new HttpResponseOutput(200, Map.of(), "{}", Duration.ofMillis(10)));

        var input = new HttpResourceInput("auth.svc2", Map.of());
        operator.execute(input, operatorContext());

        assertThat(httpStub.lastAuth()).isEqualTo(descriptorAuth);
    }

    // ── Payload path extraction ─────────────────────────────────────────

    @Test
    @DisplayName("payloadPath extracts nested data from response body")
    void payloadPathExtraction() throws Exception {
        registry.put(new ResourceDescriptor(
                "payload.svc", "https://api.example.com/data", "GET",
                Map.of(), null, Duration.ofSeconds(5),
                null, new ResponseProtocol.HttpStatus(), "data.items"
        ));
        httpStub.setResponse(new HttpResponseOutput(200, Map.of(), """
                {"data": {"items": [1, 2, 3]}}""", Duration.ofMillis(10)));

        HttpResourceOutput output = operator.execute(
                new HttpResourceInput("payload.svc", Map.of()), operatorContext());

        assertThat(output.payload()).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Number> nums = (List<Number>) output.payload();
        assertThat(nums).extracting(Number::intValue).containsExactly(1, 2, 3);
    }

    // ── BlgeExpression payload extraction ───────────────────────────────

    @Test
    @DisplayName("BlgeExpression payloadExpr extracts payload via expression")
    void blgeExpressionPayloadExtraction() throws Exception {
        registry.put(new ResourceDescriptor(
                "expr.svc", "https://api.example.com/credit/{userId}", "GET",
                Map.of(), null, Duration.ofSeconds(5),
                new ParameterMapping(Map.of("userId", "ctx.params.userId"), Map.of(), null),
                new ResponseProtocol.BlgeExpression(
                        "ctx.statusCode == 200 && ctx.body.score != null",
                        null,
                        "ctx.body"
                ),
                null
        ));
        httpStub.setResponse(new HttpResponseOutput(200, Map.of(), """
                {"score": 750, "provider": "equifax"}""", Duration.ofMillis(10)));

        HttpResourceOutput output = operator.execute(
                new HttpResourceInput("expr.svc", Map.of("userId", "u1")), operatorContext());

        assertThat(output.success()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) output.payload();
        assertThat(payload).containsEntry("score", 750);
    }

    // ── Validation failure throws ResourceCallException ─────────────────

    @Test
    @DisplayName("validation failure throws ResourceCallException")
    void validationFailureThrows() {
        registry.put(new ResourceDescriptor(
                "fail.svc", "https://api.example.com/fail", "GET",
                Map.of(), null, Duration.ofSeconds(5),
                null,
                new ResponseProtocol.BodyCode("code", java.util.Set.of(0), "message"),
                "data"
        ));
        httpStub.setResponse(new HttpResponseOutput(200, Map.of(), """
                {"code": 500, "message": "Internal error"}""", Duration.ofMillis(10)));

        assertThatThrownBy(() ->
                operator.execute(new HttpResourceInput("fail.svc", Map.of()), operatorContext())
        )
                .isInstanceOf(ResourceCallException.class)
                .satisfies(ex -> {
                    var rce = (ResourceCallException) ex;
                    assertThat(rce.resourceId()).isEqualTo("fail.svc");
                    assertThat(rce.errorMessage()).isEqualTo("Internal error");
                });
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static ResourceDescriptor simpleDescriptor(String resourceId) {
        return new ResourceDescriptor(
                resourceId, "https://api.example.com/test", "GET",
                Map.of("Accept", "application/json"), null, Duration.ofSeconds(5),
                null, new ResponseProtocol.HttpStatus(), null
        );
    }

    /** Captures the last outgoing request for assertion. */
    private static class StubHttpRequestOperator extends HttpRequestOperator {
        private HttpResponseOutput response;
        private HttpRequestInput lastInput;

        void setResponse(HttpResponseOutput response) {
            this.response = response;
        }

        @Override
        public HttpResponseOutput execute(HttpRequestInput input, OperatorContext ctx) {
            this.lastInput = input;
            return response;
        }

        String lastUrl() { return lastInput.url(); }
        Map<String, String> lastHeaders() { return lastInput.headers(); }
        Object lastBody() { return lastInput.body(); }
        Duration lastTimeout() { return lastInput.timeout(); }
        HttpRequestInput.HttpAuth lastAuth() { return lastInput.auth(); }
    }

    private static class StubResourceRegistry implements ResourceRegistry {
        private final ConcurrentHashMap<String, ResourceDescriptor> map = new ConcurrentHashMap<>();

        void put(ResourceDescriptor d) { map.put(d.resourceId(), d); }

        @Override
        public ResourceDescriptor resolve(String id) {
            ResourceDescriptor d = map.get(id);
            if (d == null) throw new com.leanowtech.bloge.gateway.exception.ResourceNotFoundException(id);
            return d;
        }

        @Override
        public boolean contains(String id) { return map.containsKey(id); }

        @Override
        public Collection<ResourceDescriptor> all() { return map.values(); }
    }

    private record FixedTimeSource(Instant now) implements TimeSource {
        @Override
        public void sleep(Duration duration) {
        }
    }
}
