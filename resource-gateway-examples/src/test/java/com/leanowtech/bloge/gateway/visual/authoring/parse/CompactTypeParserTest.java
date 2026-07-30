package com.leanowtech.bloge.gateway.visual.authoring.parse;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class CompactTypeParserTest {

    private final CompactTypeParser parser = new CompactTypeParser();

    @Test
    void parsesPrimitiveNamedArrayAndNullableTypes() {
        assertThat(parser.parse("string").expression())
                .isEqualTo(new CompactTypeParser.TypeExpression("string", true, 0, false));
        assertThat(parser.parse("Ticket[]?").expression())
                .isEqualTo(new CompactTypeParser.TypeExpression("Ticket", false, 1, true));
        assertThat(parser.parse("integer[][]").expression().canonicalText())
                .isEqualTo("integer[][]");
    }

    @Test
    void rejectsAmbiguousOrExecutableSyntaxWithExactOffsets() {
        assertThat(parser.parse("string | null").issues().getFirst())
                .satisfies(issue -> {
                    assertThat(issue.code()).isEqualTo("RG.AUTHORING.TYPE_INVALID");
                    assertThat(issue.offset()).isEqualTo(6);
                });
        assertThat(parser.parse("string?[]").issues().getFirst().offset()).isEqualTo(7);
        assertThat(parser.parse("${run()}").valid()).isFalse();
        assertThat(parser.parse("java.lang.String").valid()).isFalse();
    }

    @Test
    void enforcesSourceAndNestingLimits() {
        assertThat(parser.parse("string" + "[]".repeat(CompactTypeParser.MAX_ARRAY_DEPTH)).valid()).isTrue();
        assertThat(parser.parse("string" + "[]".repeat(CompactTypeParser.MAX_ARRAY_DEPTH + 1))
                .issues().getFirst().message()).contains("nesting");
        assertThat(parser.parse("A".repeat(CompactTypeParser.MAX_SOURCE_LENGTH + 1))
                .issues().getFirst().message()).contains("character limit");
    }

    @Test
    void adversarialCorpusIsBoundedAndNeverThrows() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            Random random = new Random(923847L);
            for (int sample = 0; sample < 20_000; sample++) {
                int length = random.nextInt(384);
                StringBuilder value = new StringBuilder(length);
                for (int index = 0; index < length; index++) {
                    value.append((char) random.nextInt(128));
                }
                assertThatCode(() -> parser.parse(value.toString())).doesNotThrowAnyException();
            }
        });
    }
}
