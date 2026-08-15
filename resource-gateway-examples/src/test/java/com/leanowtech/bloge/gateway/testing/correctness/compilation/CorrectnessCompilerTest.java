package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.GeneratedValueRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.InlineValue;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionEvaluatorProfile;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionSetCompiler;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import static com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationTestData.SECRET;
import static com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationTestData.fp;
import static com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationTestData.input;
import static com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationTestData.ref;
import static org.assertj.core.api.Assertions.assertThat;

class CorrectnessCompilerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AssertionEvaluatorProfile profile = AssertionEvaluatorProfile.fixtureEvaluatorV1();
    private final CorrectnessCompiler compiler = new CorrectnessCompiler(
            mapper, new AssertionSetCompiler(mapper), profile);

    @Test
    void compilesExactClosureToExistingRegistrationsWithCompleteCaseSourceMap() {
        FrozenCompilationInput source = input(
                new InlineValue(Map.of("decision", "APPROVE")), true);

        CompiledCorrectnessPlan plan = compiler.compile(source);

        assertThat(plan.report().publishable()).isTrue();
        assertThat(plan.fixtureRegistrations()).hasSize(1);
        assertThat(plan.suiteRegistration()).isNotNull();
        assertThat(plan.fixtureRegistrations().getFirst().fixtureBundle().assertions()).hasSize(1);
        assertThat(plan.report().sourceMap()).anySatisfy(mapping -> {
            assertThat(mapping.source().elementKind()).isEqualTo("SCENARIO_CASE");
            assertThat(mapping.source().elementId()).isEqualTo("prime-approval");
            assertThat(mapping.output().elementKind()).isEqualTo("TEST_CASE");
        });
        assertThat(plan.report().sourceMap()).anySatisfy(mapping -> {
            assertThat(mapping.source().elementKind()).isEqualTo("OBLIGATION");
            assertThat(mapping.source().elementId()).isEqualTo("OBL-PRIME");
            assertThat(mapping.output().elementKind()).isEqualTo("TEST_CASE");
        });
    }

    @Test
    void repeatedCompilationIsByteEquivalentAndDoesNotLeakFixturePayload() throws Exception {
        FrozenCompilationInput source = input(
                new InlineValue(Map.of("decision", "APPROVE")), true);
        CompiledCorrectnessPlan first = compiler.compile(source);
        byte[] expectedReport = mapper.writeValueAsBytes(first.report());
        byte[] expectedFixture = mapper.writeValueAsBytes(first.fixtureRegistrations());
        byte[] expectedSuite = mapper.writeValueAsBytes(first.suiteRegistration());

        for (int iteration = 0; iteration < 100; iteration++) {
            CompiledCorrectnessPlan actual = compiler.compile(source);
            assertThat(mapper.writeValueAsBytes(actual.report())).isEqualTo(expectedReport);
            assertThat(mapper.writeValueAsBytes(actual.fixtureRegistrations()))
                    .isEqualTo(expectedFixture);
            assertThat(mapper.writeValueAsBytes(actual.suiteRegistration()))
                    .isEqualTo(expectedSuite);
        }

        assertThat(mapper.writeValueAsString(first.report())).doesNotContain(SECRET);
        assertThat(first.toString()).doesNotContain(SECRET);
        assertThat(source.toString()).doesNotContain(SECRET);
        assertThat(source.fixtures().getFirst().toString()).doesNotContain(SECRET);
        assertThat(mapper.writeValueAsString(first.suiteRegistration())).contains(SECRET);
    }

    @Test
    void localeAndTimezoneDoNotChangeCompilationFingerprint() {
        Locale originalLocale = Locale.getDefault();
        TimeZone originalZone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.FRANCE);
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"));
            String first = compiler.compile(input(
                            new InlineValue(Map.of("decision", "APPROVE")), true))
                    .report().compilationFingerprint();

            Locale.setDefault(Locale.JAPAN);
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            String second = compiler.compile(input(
                            new InlineValue(Map.of("decision", "APPROVE")), true))
                    .report().compilationFingerprint();

            assertThat(second).isEqualTo(first);
        } finally {
            Locale.setDefault(originalLocale);
            TimeZone.setDefault(originalZone);
        }
    }

    @Test
    void unsupportedGeneratedInputBlocksWithoutPartialRegistrations() {
        FrozenCompilationInput source = input(
                new GeneratedValueRef(
                        ref("GENERATOR", "loan-generator", 'e'), fp('f')),
                true);

        CompiledCorrectnessPlan plan = compiler.compile(source);

        assertThat(plan.report().publishable()).isFalse();
        assertThat(plan.fixtureRegistrations()).isEmpty();
        assertThat(plan.suiteRegistration()).isNull();
        assertThat(plan.report().diagnostics()).extracting(value -> value.code())
                .contains("RG.CORRECTNESS.GENERATOR_LOWERING_UNSUPPORTED");
    }

    @Test
    void unsupportedExecutableAssertionIsNeverSilentlyDropped() {
        FrozenCompilationInput source = input(
                new InlineValue(Map.of("decision", "APPROVE")), false);

        CompiledCorrectnessPlan plan = compiler.compile(source);

        assertThat(plan.report().publishable()).isFalse();
        assertThat(plan.fixtureRegistrations()).isEmpty();
        assertThat(plan.report().diagnostics()).extracting(value -> value.code())
                .contains("RG.CORRECTNESS.EVALUATOR_CAPABILITY_UNSUPPORTED");
    }
}
