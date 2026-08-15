package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.Diagnostic;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.FrozenCompilationInput.MaterializedFixture;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessDefinition.DefinitionLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactObligationRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.CompilationCoordinate;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.InventoryLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.FixtureVariantRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioDraftV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Pure authority and exact-reference validation phase for correctness compilation. */
final class CorrectnessCompilationValidator {

    private final ObjectMapper mapper;

    CorrectnessCompilationValidator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    ValidationResult validate(FrozenCompilationInput input) {
        Objects.requireNonNull(input, "input");
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<ExactAssetRef, MaterializedFixture> fixtures = input.fixtures().stream()
                .collect(Collectors.toMap(MaterializedFixture::descriptorRef, Function.identity()));
        Map<ExactAssetRef, BusinessOracle> oracles = indexOracles(input);
        Map<ExactAssetRef, AssertionSet> assertionSets = indexAssertionSets(input);

        validateExactClosure(input, fixtures.keySet(), oracles.keySet(), assertionSets.keySet(),
                diagnostics);
        validateAuthority(input, fixtures, oracles, assertionSets, diagnostics);
        return new ValidationResult(fixtures, oracles, assertionSets, diagnostics);
    }

    private void validateExactClosure(
            FrozenCompilationInput input,
            Set<ExactAssetRef> fixtureRefs,
            Set<ExactAssetRef> oracleRefs,
            Set<ExactAssetRef> assertionRefs,
            List<Diagnostic> diagnostics
    ) {
        CompilationCoordinate coordinate = input.coordinate();
        requireExactAsset(
                coordinate.definitionRef(), "DEFINITION", input.definition().definitionId(),
                input.definition().revision(),
                CorrectnessProtocolFingerprint.fingerprint(mapper, input.definition()), diagnostics);
        requireExactAsset(
                coordinate.inventoryRef(), "INVENTORY", input.inventory().inventoryId(),
                input.inventory().revision(),
                CorrectnessProtocolFingerprint.fingerprint(mapper, input.inventory()), diagnostics);
        requireExactAsset(
                coordinate.scenarioDraftSetRef(), "SCENARIO_DRAFT_SET",
                input.scenarioDraftSet().scenarioDraftSetId(), input.scenarioDraftSet().revision(),
                CorrectnessProtocolFingerprint.fingerprint(mapper, input.scenarioDraftSet()),
                diagnostics);
        if (!Set.copyOf(coordinate.oracleRefs()).equals(oracleRefs)) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.ORACLE_CLOSURE_MISMATCH", coordinate.definitionRef(),
                    "/oracleRefs", "correctness.compilation.oracleClosureMismatch"));
        }
        if (!Set.copyOf(coordinate.assertionSetRefs()).equals(assertionRefs)) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.ASSERTION_CLOSURE_MISMATCH", coordinate.definitionRef(),
                    "/assertionSetRefs", "correctness.compilation.assertionClosureMismatch"));
        }
        if (!Set.copyOf(coordinate.fixtureAssetRefs()).equals(fixtureRefs)) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.FIXTURE_CLOSURE_MISMATCH", coordinate.definitionRef(),
                    "/fixtureAssetRefs", "correctness.compilation.fixtureClosureMismatch"));
        }

        Set<ExactAssetRef> referencedOracles = input.scenarioDraftSet().scenarios().stream()
                .flatMap(value -> value.oracleRefs().stream()).collect(Collectors.toSet());
        Set<ExactAssetRef> referencedAssertions = input.scenarioDraftSet().scenarios().stream()
                .flatMap(value -> value.assertionSetRefs().stream()).collect(Collectors.toSet());
        Set<ExactAssetRef> referencedFixtures = referencedFixtures(input.scenarioDraftSet());
        requireSameClosure("ORACLE", oracleRefs, referencedOracles,
                coordinate.scenarioDraftSetRef(), diagnostics);
        requireSameClosure("ASSERTION", assertionRefs, referencedAssertions,
                coordinate.scenarioDraftSetRef(), diagnostics);
        requireSameClosure("FIXTURE", fixtureRefs, referencedFixtures,
                coordinate.scenarioDraftSetRef(), diagnostics);
    }

    private void validateAuthority(
            FrozenCompilationInput input,
            Map<ExactAssetRef, MaterializedFixture> fixtures,
            Map<ExactAssetRef, BusinessOracle> oracles,
            Map<ExactAssetRef, AssertionSet> assertionSets,
            List<Diagnostic> diagnostics
    ) {
        CompilationCoordinate coordinate = input.coordinate();
        ExactTargetRef target = coordinate.target();
        if (!input.scope().equals(input.definition().scope())
                || !input.scope().equals(input.inventory().scope())
                || !input.scope().equals(input.scenarioDraftSet().scope())
                || fixtures.values().stream().anyMatch(value ->
                !input.scope().equals(value.descriptor().scope()))
                || oracles.values().stream().anyMatch(value ->
                !input.scope().equals(value.scope()))) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.SCOPE_MISMATCH", coordinate.definitionRef(),
                    "/scope", "correctness.compilation.scopeMismatch"));
        }
        if (!target.equals(input.definition().target())
                || !target.equals(input.inventory().target())
                || !target.equals(input.scenarioDraftSet().target())
                || oracles.values().stream().anyMatch(value -> !target.equals(value.target()))
                || assertionSets.values().stream().anyMatch(value -> !target.equals(value.target()))) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.TARGET_MISMATCH", coordinate.definitionRef(),
                    "/target", "correctness.compilation.targetMismatch"));
        }
        if (input.definition().lifecycle() != DefinitionLifecycle.ACTIVE
                || !input.definition().review().approved()) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.DEFINITION_NOT_ACTIVE", coordinate.definitionRef(),
                    "/lifecycle", "correctness.compilation.definitionNotActive"));
        }
        if (!coordinate.inventoryRef().equals(input.definition().activeInventoryRef())) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.ACTIVE_INVENTORY_MISMATCH", coordinate.definitionRef(),
                    "/activeInventoryRef", "correctness.compilation.activeInventoryMismatch"));
        }
        if (input.inventory().lifecycle() != InventoryLifecycle.FROZEN
                || !input.inventory().freezeReview().approved()) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.DENOMINATOR_NOT_FROZEN", coordinate.inventoryRef(),
                    "/lifecycle", "correctness.compilation.denominatorNotFrozen"));
        }
        String environment = input.scope().environment().toLowerCase(Locale.ROOT);
        if (environment.equals("prod") || environment.equals("production")) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.PRODUCTION_COMPILATION_FORBIDDEN", coordinate.definitionRef(),
                    "/scope/environment", "correctness.compilation.productionForbidden"));
        }
        if (target.kind().name().equals("FUNCTION")) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.FUNCTION_TARGET_UNSUPPORTED", coordinate.definitionRef(),
                    "/target/kind", "correctness.compilation.functionTargetUnsupported"));
        }

        Map<String, CoverageInventory.CoverageObligation> obligations =
                input.inventory().obligations().stream().collect(Collectors.toMap(
                        CoverageInventory.CoverageObligation::obligationId,
                        Function.identity()));
        for (ScenarioDraftV2 scenario : input.scenarioDraftSet().scenarios()) {
            validateScenarioAuthority(
                    input, scenario, obligations, oracles, assertionSets, diagnostics);
        }
        fixtures.forEach((ref, fixture) -> {
            if (fixture.descriptor().lifecycle() != FixtureLifecycle.ACTIVE
                    || !fixture.descriptor().quality().schemaValid()
                    || !fixture.descriptor().quality().redactionVerified()
                    || !fixture.descriptor().redaction().reviewed()) {
                diagnostics.add(Diagnostic.error(
                        "RG.CORRECTNESS.FIXTURE_NOT_ACTIVE", ref, "/lifecycle",
                        "correctness.compilation.fixtureNotActive"));
            }
        });
    }

    private void validateScenarioAuthority(
            FrozenCompilationInput input,
            ScenarioDraftV2 scenario,
            Map<String, CoverageInventory.CoverageObligation> obligations,
            Map<ExactAssetRef, BusinessOracle> oracles,
            Map<ExactAssetRef, AssertionSet> assertionSets,
            List<Diagnostic> diagnostics
    ) {
        ExactAssetRef scenarioRef = input.coordinate().scenarioDraftSetRef();
        String path = scenarioPath(scenario);
        if (scenario.lifecycle() != ScenarioLifecycle.CANONICAL || !scenario.review().approved()) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.SCENARIO_NOT_CANONICAL", scenarioRef,
                    path + "/lifecycle", "correctness.compilation.scenarioNotCanonical"));
        }
        for (ExactObligationRef ref : scenario.obligationRefs()) {
            CoverageInventory.CoverageObligation obligation = obligations.get(ref.obligationId());
            if (!ref.inventoryRef().equals(input.coordinate().inventoryRef())
                    || obligation == null
                    || obligation.lifecycle() != ObligationLifecycle.FROZEN
                    || !CorrectnessProtocolFingerprint.obligationFingerprint(mapper, obligation)
                    .equals(ref.obligationFingerprint())) {
                diagnostics.add(Diagnostic.error(
                        "RG.CORRECTNESS.OBLIGATION_REFERENCE_DRIFT", scenarioRef,
                        path + "/obligationRefs/" + ref.obligationId(),
                        "correctness.compilation.obligationReferenceDrift"));
            }
        }
        for (ExactAssetRef ref : scenario.oracleRefs()) {
            BusinessOracle oracle = oracles.get(ref);
            if (oracle == null
                    || oracle.lifecycle() != BusinessOracle.OracleLifecycle.APPROVED
                    || !oracle.approval().approved()) {
                diagnostics.add(Diagnostic.error(
                        "RG.CORRECTNESS.ORACLE_NOT_APPROVED", ref,
                        path + "/oracleRefs", "correctness.compilation.oracleNotApproved"));
            }
        }
        Set<ExactAssetRef> scenarioOracleRefs = Set.copyOf(scenario.oracleRefs());
        for (ExactAssetRef ref : scenario.assertionSetRefs()) {
            AssertionSet assertionSet = assertionSets.get(ref);
            if (assertionSet == null
                    || assertionSet.lifecycle() != AssertionSet.AssertionLifecycle.VALID
                    || !assertionSet.compatibility().supported()) {
                diagnostics.add(Diagnostic.error(
                        "RG.CORRECTNESS.ASSERTION_UNSUPPORTED", ref,
                        path + "/assertionSetRefs",
                        "correctness.compilation.assertionUnsupported"));
            } else if (!scenarioOracleRefs.contains(assertionSet.oracleRef())) {
                diagnostics.add(Diagnostic.error(
                        "RG.CORRECTNESS.ASSERTION_ORACLE_MISMATCH", ref,
                        path + "/assertionSetRefs",
                        "correctness.compilation.assertionOracleMismatch"));
            }
        }
    }

    private Map<ExactAssetRef, BusinessOracle> indexOracles(FrozenCompilationInput input) {
        Map<ExactAssetRef, BusinessOracle> result = new LinkedHashMap<>();
        for (BusinessOracle value : input.oracles()) {
            ExactAssetRef ref = new ExactAssetRef(
                    "ORACLE", value.oracleId(), value.revision(),
                    CorrectnessProtocolFingerprint.fingerprint(mapper, value));
            result.put(ref, value);
        }
        return Map.copyOf(result);
    }

    private Map<ExactAssetRef, AssertionSet> indexAssertionSets(FrozenCompilationInput input) {
        Map<ExactAssetRef, AssertionSet> result = new LinkedHashMap<>();
        for (AssertionSet value : input.assertionSets()) {
            ExactAssetRef ref = new ExactAssetRef(
                    "ASSERTION_SET", value.assertionSetId(), value.revision(),
                    CorrectnessProtocolFingerprint.fingerprint(mapper, value));
            result.put(ref, value);
        }
        return Map.copyOf(result);
    }

    private static void requireExactAsset(
            ExactAssetRef actual,
            String kind,
            String id,
            long revision,
            String fingerprint,
            List<Diagnostic> diagnostics
    ) {
        if (!actual.kind().equals(kind) || !actual.id().equals(id)
                || actual.revision() != revision || !actual.fingerprint().equals(fingerprint)) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS.REFERENCE_DRIFT", actual, "",
                    "correctness.compilation.referenceDrift"));
        }
    }

    private static void requireSameClosure(
            String kind,
            Set<ExactAssetRef> supplied,
            Set<ExactAssetRef> referenced,
            ExactAssetRef source,
            List<Diagnostic> diagnostics
    ) {
        if (!supplied.equals(referenced)) {
            diagnostics.add(Diagnostic.error(
                    "RG.CORRECTNESS." + kind + "_REFERENCE_CLOSURE_INCOMPLETE", source,
                    "/scenarios", "correctness.compilation.referenceClosureIncomplete"));
        }
    }

    private static Set<ExactAssetRef> referencedFixtures(ScenarioDraftSetV2 draftSet) {
        Set<ExactAssetRef> result = new LinkedHashSet<>();
        for (ScenarioDraftV2 scenario : draftSet.scenarios()) {
            scenario.sourceRefs().stream().filter(ref -> "FIXTURE_ASSET".equals(ref.kind()))
                    .forEach(result::add);
            addFixtureRef(result, scenario.given().input());
            scenario.dependencies().forEach(dependency ->
                    addFixtureRef(result, dependency.behavior().value()));
        }
        return Set.copyOf(result);
    }

    private static void addFixtureRef(Set<ExactAssetRef> target, ValueSource value) {
        if (value instanceof FixtureVariantRef fixture) {
            target.add(fixture.fixtureAssetRef());
        }
    }

    private static String scenarioPath(ScenarioDraftV2 scenario) {
        return "/scenarios/" + scenario.scenarioId();
    }

    record ValidationResult(
            Map<ExactAssetRef, MaterializedFixture> fixtures,
            Map<ExactAssetRef, BusinessOracle> oracles,
            Map<ExactAssetRef, AssertionSet> assertionSets,
            List<Diagnostic> diagnostics
    ) {
        ValidationResult {
            fixtures = Map.copyOf(fixtures);
            oracles = Map.copyOf(oracles);
            assertionSets = Map.copyOf(assertionSets);
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
