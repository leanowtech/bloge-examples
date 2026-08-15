package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.InlineValue;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionEvaluatorProfile;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionSetCompiler;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationTestData.input;
import static org.assertj.core.api.Assertions.assertThat;

class CorrectnessCompilerPhaseTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AssertionEvaluatorProfile profile = AssertionEvaluatorProfile.fixtureEvaluatorV1();

    @Test
    void explicitPurePhasesComposeToThePublicCompilerByteForByte() throws Exception {
        FrozenCompilationInput source = input(
                new InlineValue(Map.of("decision", "APPROVE")), true);
        CorrectnessCompilationValidator validator =
                new CorrectnessCompilationValidator(mapper);
        CorrectnessExecutionRiskAnalyzer riskAnalyzer =
                new CorrectnessExecutionRiskAnalyzer();
        CorrectnessScenarioLowerer lowerer = new CorrectnessScenarioLowerer(
                mapper, new AssertionSetCompiler(mapper), profile,
                CorrectnessCompiler.COMPILER_VERSION);
        CorrectnessCompilationCanonicalizer canonicalizer =
                new CorrectnessCompilationCanonicalizer(
                        mapper, CorrectnessCompiler.COMPILER_VERSION);

        CorrectnessCompilationValidator.ValidationResult validated =
                validator.validate(source);
        var risk = riskAnalyzer.analyze(
                source.scenarioDraftSet(), source.coordinate().scenarioDraftSetRef());
        var lowered = lowerer.lower(
                source, validated.fixtures(), validated.assertionSets());
        List<CorrectnessCompilationReport.Diagnostic> diagnostics =
                new java.util.ArrayList<>(validated.diagnostics());
        diagnostics.addAll(risk.diagnostics());
        diagnostics.addAll(lowered.diagnostics());
        CompiledCorrectnessPlan explicit = canonicalizer.complete(
                source.coordinate(), lowered.fixtureRegistrations(),
                lowered.suiteRegistration(), lowered.suiteRef(), lowered.sourceMap(),
                diagnostics, risk.summary());
        CompiledCorrectnessPlan facade = new CorrectnessCompiler(
                mapper, new AssertionSetCompiler(mapper), profile).compile(source);

        assertThat(validated.diagnostics()).isEmpty();
        assertThat(lowered.publishable()).isTrue();
        assertThat(mapper.writeValueAsBytes(explicit.report()))
                .isEqualTo(mapper.writeValueAsBytes(facade.report()));
        assertThat(mapper.writeValueAsBytes(explicit.fixtureRegistrations()))
                .isEqualTo(mapper.writeValueAsBytes(facade.fixtureRegistrations()));
        assertThat(mapper.writeValueAsBytes(explicit.suiteRegistration()))
                .isEqualTo(mapper.writeValueAsBytes(facade.suiteRegistration()));
    }

    @Test
    void purePhasesCannotAcquireRepositoryClockRandomOrNetworkDependencies() {
        List<Class<?>> phases = List.of(
                CorrectnessCompilationValidator.class,
                CorrectnessExecutionRiskAnalyzer.class,
                CorrectnessScenarioLowerer.class,
                CorrectnessAssertionLowerer.class,
                CorrectnessFixtureRuleLowerer.class,
                CorrectnessSourceMapBuilder.class,
                CorrectnessCompilationCanonicalizer.class);

        assertThat(phases.stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .map(Field::getType)
                .map(Class::getName))
                .noneMatch(name -> name.contains("Repository")
                        || name.equals("java.time.Clock")
                        || name.contains("Random")
                        || name.contains("HttpClient")
                        || name.contains("Secret"));
    }
}
