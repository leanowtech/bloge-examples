package com.leanowtech.bloge.gateway.visual.authoring.parse;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class FunctionSignatureParserTest {

    private final FunctionSignatureParser parser = new FunctionSignatureParser();

    @Test
    void parsesRequiredOptionalVariadicAndNamedTypes() {
        FunctionSignatureParser.ParseResult ordinary =
                parser.parse("(value: Ticket, fallback?: any) -> TriageResult?");
        assertThat(ordinary.valid()).isTrue();
        assertThat(ordinary.signature().normalized())
                .isEqualTo("(value: Ticket, fallback?: any) -> TriageResult?");
        assertThat(ordinary.signature().parameters())
                .extracting(FunctionSignatureParser.Parameter::name)
                .containsExactly("value", "fallback");

        FunctionSignatureParser.ParseResult variadic =
                parser.parse("(...values: string[]) -> string");
        assertThat(variadic.valid()).isTrue();
        assertThat(variadic.signature().parameters().getFirst().variadic()).isTrue();
    }

    @Test
    void rejectsDuplicateParametersAndNonFinalVariadicParameters() {
        assertThat(parser.parse("(value: string, value: string) -> string").issues())
                .extracting(FunctionSignatureParser.ParseIssue::message)
                .anyMatch(message -> message.contains("duplicate parameter"));
        assertThat(parser.parse("(...values: string[], suffix: string) -> string").issues())
                .extracting(FunctionSignatureParser.ParseIssue::message)
                .anyMatch(message -> message.contains("final parameter"));
    }

    @Test
    void rejectsCodeDefaultsNestedFunctionsAndMalformedArrows() {
        assertThat(parser.parse("(value: string = run()) -> string").issues())
                .extracting(FunctionSignatureParser.ParseIssue::message)
                .anyMatch(message -> message.contains("Default value expressions"));
        assertThat(parser.parse("(fn: (string) -> string) -> string").valid()).isFalse();
        assertThat(parser.parse("(value: string) => string").valid()).isFalse();
        assertThat(parser.parse("java.lang.Runtime.getRuntime()").valid()).isFalse();
    }

    @Test
    void enforcesParameterAndSourceLimits() {
        String tooMany = "(" + java.util.stream.IntStream
                .range(0, FunctionSignatureParser.MAX_PARAMETERS + 1)
                .mapToObj(index -> "p" + index + ": string")
                .collect(java.util.stream.Collectors.joining(", "))
                + ") -> string";
        assertThat(parser.parse(tooMany).issues())
                .extracting(FunctionSignatureParser.ParseIssue::message)
                .anyMatch(message -> message.contains("parameter limit"));
        assertThat(parser.parse("(" + "x".repeat(FunctionSignatureParser.MAX_SOURCE_LENGTH) + ") -> string")
                .issues().getFirst().message()).contains("character limit");
    }

    @Test
    void adversarialCorpusIsBoundedAndNeverThrows() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            Random random = new Random(492038L);
            for (int sample = 0; sample < 20_000; sample++) {
                int length = random.nextInt(1400);
                StringBuilder value = new StringBuilder(length);
                for (int index = 0; index < length; index++) {
                    value.append((char) random.nextInt(128));
                }
                assertThatCode(() -> parser.parse(value.toString())).doesNotThrowAnyException();
            }
        });
    }
}
