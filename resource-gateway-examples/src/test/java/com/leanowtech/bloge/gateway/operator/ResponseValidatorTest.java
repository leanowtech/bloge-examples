package com.leanowtech.bloge.gateway.operator;

import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.operator.ResponseValidator.ValidationResult;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.operators.http.HttpResponseOutput;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ResponseValidator} covering all five {@link ResponseProtocol} variants.
 */
class ResponseValidatorTest {

    private ResponseValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ResponseValidator(new BlgeExpressionEvaluator());
    }

    private static HttpResponseOutput response(int status, String body) {
        return new HttpResponseOutput(status, Map.of(), body, Duration.ofMillis(50));
    }

    // ── HttpStatus ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("HttpStatus protocol")
    class HttpStatusTests {

        private final ResponseProtocol protocol = new ResponseProtocol.HttpStatus();

        @Test
        void success_200() {
            ValidationResult result = validator.validate(response(200, "{}"), protocol);
            assertThat(result.success()).isTrue();
            assertThat(result.errorMessage()).isNull();
        }

        @Test
        void success_201() {
            assertThat(validator.validate(response(201, "{}"), protocol).success()).isTrue();
        }

        @Test
        void failure_500() {
            ValidationResult result = validator.validate(response(500, ""), protocol);
            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("HTTP 500");
        }

        @Test
        void failure_404() {
            assertThat(validator.validate(response(404, ""), protocol).success()).isFalse();
        }
    }

    // ── BodyCode ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("BodyCode protocol")
    class BodyCodeTests {

        @Test
        void successWithNumericCode() {
            var protocol = new ResponseProtocol.BodyCode("code", Set.of(0), "message");
            String body = """
                    {"code": 0, "message": "ok", "data": {"name": "Alice"}}
                    """;
            ValidationResult result = validator.validate(response(200, body), protocol);
            assertThat(result.success()).isTrue();
        }

        @Test
        void successWithStringCode() {
            var protocol = new ResponseProtocol.BodyCode("code", Set.of("SUCCESS", "200"), "message");
            String body = """
                    {"code": "SUCCESS", "data": [1,2,3]}
                    """;
            assertThat(validator.validate(response(200, body), protocol).success()).isTrue();
        }

        @Test
        void failureExtractsMessage() {
            var protocol = new ResponseProtocol.BodyCode("errcode", Set.of(0), "errmsg");
            String body = """
                    {"errcode": 40001, "errmsg": "Invalid token"}
                    """;
            ValidationResult result = validator.validate(response(200, body), protocol);
            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).isEqualTo("Invalid token");
        }

        @Test
        void failureWithoutMessagePath() {
            var protocol = new ResponseProtocol.BodyCode("code", Set.of(0), null);
            String body = """
                    {"code": 999}
                    """;
            ValidationResult result = validator.validate(response(200, body), protocol);
            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("999");
        }

        @Test
        void emptyBody() {
            var protocol = new ResponseProtocol.BodyCode("code", Set.of(0), "msg");
            ValidationResult result = validator.validate(response(200, ""), protocol);
            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("Empty");
        }

        @Test
        void nestedCodePath() {
            var protocol = new ResponseProtocol.BodyCode("result.status", Set.of("OK"), "result.error");
            String body = """
                    {"result": {"status": "OK", "data": "value"}}
                    """;
            assertThat(validator.validate(response(200, body), protocol).success()).isTrue();
        }
    }

    // ── BodyFlag ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("BodyFlag protocol")
    class BodyFlagTests {

        private final ResponseProtocol protocol = new ResponseProtocol.BodyFlag("success");

        @Test
        void flagTrue() {
            String body = """
                    {"success": true, "data": {"items": [1,2,3]}}
                    """;
            assertThat(validator.validate(response(200, body), protocol).success()).isTrue();
        }

        @Test
        void flagFalse() {
            String body = """
                    {"success": false, "error": "not found"}
                    """;
            ValidationResult result = validator.validate(response(200, body), protocol);
            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("success").contains("false");
        }

        @Test
        void flagMissing() {
            ValidationResult result = validator.validate(response(200, """
                    {"data": "no flag here"}
                    """), protocol);
            assertThat(result.success()).isFalse();
        }

        @Test
        void emptyBody() {
            assertThat(validator.validate(response(200, ""), protocol).success()).isFalse();
        }
    }

    // ── StatusCodes ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("StatusCodes protocol")
    class StatusCodesTests {

        private final ResponseProtocol protocol = new ResponseProtocol.StatusCodes(Set.of(200, 204, 206));

        @Test
        void acceptedCode200() {
            assertThat(validator.validate(response(200, "ok"), protocol).success()).isTrue();
        }

        @Test
        void acceptedCode204() {
            assertThat(validator.validate(response(204, ""), protocol).success()).isTrue();
        }

        @Test
        void rejectedCode201() {
            ValidationResult result = validator.validate(response(201, ""), protocol);
            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("201").contains("accepted codes");
        }

        @Test
        void rejectedCode500() {
            assertThat(validator.validate(response(500, ""), protocol).success()).isFalse();
        }
    }

    // ── BlgeExpression ──────────────────────────────────────────────────

    @Nested
    @DisplayName("BlgeExpression protocol")
    class BlgeExpressionTests {

        @Test
        void successWithExpression() {
            var protocol = new ResponseProtocol.BlgeExpression(
                    "ctx.statusCode == 200 && ctx.body.score != null",
                    "ctx.body.errorMessage ?? 'Unknown error'",
                    "ctx.body"
            );
            String body = """
                    {"score": 750, "provider": "equifax"}
                    """;
            ValidationResult result = validator.validate(response(200, body), protocol);
            assertThat(result.success()).isTrue();
        }

        @Test
        void failureExtractsMessage() {
            var protocol = new ResponseProtocol.BlgeExpression(
                    "ctx.statusCode == 200 && ctx.body.score != null",
                    "ctx.body.errorMessage ?? \"Unknown error\"",
                    null
            );
            String body = """
                    {"errorMessage": "Service degraded"}
                    """;
            ValidationResult result = validator.validate(response(200, body), protocol);
            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).isEqualTo("Service degraded");
        }

        @Test
        void failureFallbackMessage() {
            var protocol = new ResponseProtocol.BlgeExpression(
                    "ctx.statusCode == 200 && ctx.body.score != null",
                    null,
                    null
            );
            ValidationResult result = validator.validate(response(500, "{}"), protocol);
            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("expression evaluated to false");
        }

        @Test
        void failureWithBadStatus() {
            var protocol = new ResponseProtocol.BlgeExpression(
                    "ctx.statusCode == 200",
                    "\"HTTP error: \" + ctx.statusCode",
                    null
            );
            ValidationResult result = validator.validate(response(503, "{}"), protocol);
            assertThat(result.success()).isFalse();
        }
    }
}
