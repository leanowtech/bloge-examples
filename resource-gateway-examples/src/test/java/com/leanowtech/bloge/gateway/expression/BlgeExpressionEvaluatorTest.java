package com.leanowtech.bloge.gateway.expression;

import com.leanowtech.bloge.gateway.exception.ResourceDescriptorException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BlgeExpressionEvaluator}.
 */
class BlgeExpressionEvaluatorTest {

    private BlgeExpressionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new BlgeExpressionEvaluator();
    }

    // ── Boolean evaluation ──────────────────────────────────────────────

    @Nested
    @DisplayName("evaluateBoolean")
    class EvaluateBoolean {

        @Test
        void trueExpression() {
            boolean result = evaluator.evaluateBoolean("1 == 1", Map.of());
            assertThat(result).isTrue();
        }

        @Test
        void falseExpression() {
            boolean result = evaluator.evaluateBoolean("1 == 2", Map.of());
            assertThat(result).isFalse();
        }

        @Test
        void contextFieldComparison() {
            var ctx = Map.<String, Object>of("statusCode", 200);
            assertThat(evaluator.evaluateBoolean("ctx.statusCode == 200", ctx)).isTrue();
            assertThat(evaluator.evaluateBoolean("ctx.statusCode == 500", ctx)).isFalse();
        }

        @Test
        void nestedContextField() {
            var ctx = Map.<String, Object>of("body", Map.of("errno", 0));
            assertThat(evaluator.evaluateBoolean("ctx.body.errno == 0", ctx)).isTrue();
        }

        @Test
        void nullCoercion() {
            // null → false
            var ctx = Map.<String, Object>of("body", Map.of());
            assertThat(evaluator.evaluateBoolean("ctx.body.missing", ctx)).isFalse();
        }

        @Test
        void nonZeroNumberIsTruthy() {
            var ctx = Map.<String, Object>of("count", 42);
            assertThat(evaluator.evaluateBoolean("ctx.count", ctx)).isTrue();
        }

        @Test
        void zeroNumberIsFalsy() {
            var ctx = Map.<String, Object>of("count", 0);
            assertThat(evaluator.evaluateBoolean("ctx.count", ctx)).isFalse();
        }
    }

    // ── String evaluation ───────────────────────────────────────────────

    @Nested
    @DisplayName("evaluateString")
    class EvaluateString {

        @Test
        void literalString() {
            String result = evaluator.evaluateString("\"hello\"", Map.of());
            assertThat(result).isEqualTo("hello");
        }

        @Test
        void contextFieldToString() {
            var ctx = Map.<String, Object>of("body", Map.of("message", "ok"));
            assertThat(evaluator.evaluateString("ctx.body.message", ctx)).isEqualTo("ok");
        }

        @Test
        void nullResultReturnsNull() {
            var ctx = Map.<String, Object>of("body", Map.of());
            assertThat(evaluator.evaluateString("ctx.body.missing", ctx)).isNull();
        }

        @Test
        void numberResultAsString() {
            var ctx = Map.<String, Object>of("code", 404);
            assertThat(evaluator.evaluateString("ctx.code", ctx)).isEqualTo("404");
        }
    }

    // ── Object/collection evaluation ────────────────────────────────────

    @Nested
    @DisplayName("evaluate (object/collection)")
    class EvaluateObjectCollection {

        @Test
        void mapLiteral() {
            Object result = evaluator.evaluate("{ a: 1, b: 2 }", Map.of());
            assertThat(result).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) result;
            assertThat(map).containsKey("a");
            assertThat(map).containsKey("b");
            assertThat(((Number) map.get("a")).intValue()).isEqualTo(1);
            assertThat(((Number) map.get("b")).intValue()).isEqualTo(2);
        }

        @Test
        void listLiteral() {
            Object result = evaluator.evaluate("[1, 2, 3]", Map.of());
            assertThat(result).isInstanceOf(List.class);
            @SuppressWarnings("unchecked")
            List<Number> nums = (List<Number>) result;
            assertThat(nums).extracting(Number::intValue).containsExactly(1, 2, 3);
        }

        @Test
        void nestedMapAccess() {
            var ctx = Map.<String, Object>of("data", Map.of("items", List.of("x", "y")));
            Object result = evaluator.evaluate("ctx.data.items", ctx);
            assertThat(result).isInstanceOf(List.class);
            assertThat(result).asList().containsExactly("x", "y");
        }

        @Test
        void nullCoalesceReturnsFirst() {
            var ctx = Map.<String, Object>of("a", "found");
            Object result = evaluator.evaluate("ctx.a ?? \"fallback\"", ctx);
            assertThat(result).isEqualTo("found");
        }

        @Test
        void nullCoalesceFallsBack() {
            Object result = evaluator.evaluate("ctx.missing ?? \"fallback\"", Map.of());
            assertThat(result).isEqualTo("fallback");
        }
    }

    @Nested
    @DisplayName("built-in functions")
    class BuiltInFunctions {

        @Test
        void evaluatesFunctionsUsedByVisualCanvasExamples() {
            assertThat(evaluator.evaluate("coalesce(ctx.missing, \"fallback\")", Map.of()))
                    .isEqualTo("fallback");
            assertThat(((Number) evaluator.evaluate("toNumber(\"728\")", Map.of())).intValue())
                    .isEqualTo(728);
            assertThat(((Number) evaluator.evaluate("round(128.45)", Map.of())).intValue())
                    .isEqualTo(128);
        }
    }

    // ── canCompile ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("canCompile")
    class CanCompile {

        @Test
        void validExpression() {
            assertThat(evaluator.canCompile("ctx.statusCode == 200")).isTrue();
        }

        @Test
        void validLiteral() {
            assertThat(evaluator.canCompile("42")).isTrue();
        }

        @Test
        void invalidExpression() {
            assertThat(evaluator.canCompile("==== garbage @@")).isFalse();
        }
    }

    // ── precompile ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("precompile")
    class Precompile {

        @Test
        void validExpressionDoesNotThrow() {
            evaluator.precompile("ctx.x + 1");
        }

        @Test
        void invalidExpressionThrowsResourceDescriptorException() {
            assertThatThrownBy(() -> evaluator.precompile("==== garbage @@"))
                    .isInstanceOf(ResourceDescriptorException.class)
                    .hasMessageContaining("startup-validation");
        }
    }
}
