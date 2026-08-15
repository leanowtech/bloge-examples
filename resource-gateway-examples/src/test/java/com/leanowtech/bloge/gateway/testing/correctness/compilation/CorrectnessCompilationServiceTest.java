package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.CompilationCoordinate;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.FixtureSubject;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.InlineValue;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialResolver;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialResolver.MaterialAccessContext;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialResolver.ResolvedFixtureMaterial;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionEvaluatorProfile;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionSetCompiler;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.AssertionSetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.BusinessOracleRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CorrectnessDefinitionRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CoverageInventoryRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredAssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredBusinessOracle;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredCorrectnessDefinition;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredCoverageInventory;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredScenarioDraftSetV2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorrectnessCompilationServiceTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CorrectnessDefinitionRepository definitions =
            mock(CorrectnessDefinitionRepository.class);
    private final CoverageInventoryRepository inventories =
            mock(CoverageInventoryRepository.class);
    private final BusinessOracleRepository oracles = mock(BusinessOracleRepository.class);
    private final AssertionSetRepository assertions = mock(AssertionSetRepository.class);
    private final ScenarioDraftSetV2Repository scenarios =
            mock(ScenarioDraftSetV2Repository.class);
    private final FixtureAssetRepository fixtures = mock(FixtureAssetRepository.class);
    private final FixtureMaterialResolver materials = mock(FixtureMaterialResolver.class);
    private final CorrectnessCompilationReferenceSource externalReferences =
            mock(CorrectnessCompilationReferenceSource.class);
    private final CorrectnessCompiler compiler = new CorrectnessCompiler(
            mapper, new AssertionSetCompiler(mapper),
            AssertionEvaluatorProfile.fixtureEvaluatorV1());
    private final CorrectnessCompilationService service = new CorrectnessCompilationService(
            definitions, inventories, oracles, assertions, scenarios, fixtures,
            materials, externalReferences, compiler, mapper);

    private FrozenCompilationInput source;

    @BeforeEach
    void setUp() {
        source = new CorrectnessCompilerTest().input(
                new InlineValue(Map.of("decision", "APPROVE")), true);
        stubAuthoringClosure(source);
        stubMaterial(source, source.coordinate().target());
        when(externalReferences.referenceIsCurrent(
                eq(source.scope()), eq(source.coordinate().target()), any(), any()))
                .thenReturn(true);
    }

    @Test
    void resolvesExactAssetsAndUsesDedicatedMaterialPurpose() throws Exception {
        CorrectnessCompilationReport report = service.compile(source.coordinate(), identity());

        assertThat(report.publishable()).isTrue();
        assertThat(mapper.writeValueAsString(report))
                .doesNotContain("customer-account-secret-8848");
        ArgumentCaptor<MaterialAccessContext> access =
                ArgumentCaptor.forClass(MaterialAccessContext.class);
        verify(materials).resolve(
                eq(source.scope()),
                eq(source.fixtures().getFirst().materialRef()),
                access.capture());
        assertThat(access.getValue().purpose())
                .isEqualTo("CORRECTNESS_FIXTURE_MATERIAL_RESOLVE");
        assertThat(access.getValue().clearance()).isEqualTo("CONFIDENTIAL");
    }

    @Test
    void rejectsDriftedExactReferenceBeforeCompilation() {
        CompilationCoordinate actual = source.coordinate();
        CompilationCoordinate drifted = new CompilationCoordinate(
                new ExactAssetRef(
                        actual.definitionRef().kind(), actual.definitionRef().id(),
                        actual.definitionRef().revision(), fp('e')),
                actual.inventoryRef(), actual.scenarioDraftSetRef(), actual.oracleRefs(),
                actual.assertionSetRefs(), actual.fixtureAssetRefs(), actual.target());

        assertThatThrownBy(() -> service.compile(drifted, identity()))
                .isInstanceOfSatisfying(CorrectnessCompilationException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(409);
                    assertThat(failure.code()).isEqualTo("RG.CORRECTNESS.REFERENCE_DRIFTED");
                });
    }

    @Test
    void rejectsMaterialReceiptBoundToAnotherTarget() {
        stubMaterial(source, new ExactTargetRef(
                TargetKind.GRAPH, "other-graph", 1, fp('d')));

        assertThatThrownBy(() -> service.compile(source.coordinate(), identity()))
                .isInstanceOfSatisfying(CorrectnessCompilationException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(409);
                    assertThat(failure.code())
                            .isEqualTo("RG.CORRECTNESS.FIXTURE_MATERIAL_CLOSURE_DRIFT");
                });
    }

    @Test
    void rejectsDriftedContractBeforeReadingFixtureMaterial() {
        when(externalReferences.referenceIsCurrent(
                eq(source.scope()), eq(source.coordinate().target()),
                eq(source.scenarioDraftSet().contractRef()), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.compile(source.coordinate(), identity()))
                .isInstanceOfSatisfying(CorrectnessCompilationException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(409);
                    assertThat(failure.code())
                            .isEqualTo("RG.CORRECTNESS.EXTERNAL_REFERENCE_DRIFT");
                });
        verify(materials, org.mockito.Mockito.never()).resolve(any(), any(), any());
    }

    @Test
    void masksCrossScopeRepositoryResultsAsNotFound() {
        when(definitions.findRevision(
                source.scope(), source.coordinate().definitionRef().id(),
                source.coordinate().definitionRef().revision()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.compile(source.coordinate(), identity()))
                .isInstanceOfSatisfying(CorrectnessCompilationException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(404);
                    assertThat(failure.code()).isEqualTo("RG.CORRECTNESS.REFERENCE_NOT_FOUND");
                });
    }

    @Test
    void requiresDedicatedPurposeAndNonProductionScope() {
        IntegrationRequestContext wrongPurpose = new IntegrationRequestContext(
                "tenant-a", "org-a", "loan", "test", "sg", "USER", "author", "",
                "CORRECTNESS_WRITE", "corr-1", Set.of(), "CONFIDENTIAL", "");
        assertThatThrownBy(() -> service.compile(source.coordinate(), wrongPurpose))
                .isInstanceOfSatisfying(CorrectnessCompilationException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo("RG.CORRECTNESS.COMPILATION_PURPOSE_FORBIDDEN"));

        IntegrationRequestContext production = new IntegrationRequestContext(
                "tenant-a", "org-a", "loan", "production", "sg", "USER", "author", "",
                CorrectnessCompilationService.PURPOSE, "corr-1", Set.of(),
                "CONFIDENTIAL", "");
        assertThatThrownBy(() -> service.compile(source.coordinate(), production))
                .isInstanceOfSatisfying(CorrectnessCompilationException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo("RG.CORRECTNESS.COMPILATION_SCOPE_FORBIDDEN"));
    }

    private void stubAuthoringClosure(FrozenCompilationInput value) {
        var coordinate = value.coordinate();
        when(definitions.findRevision(
                value.scope(), coordinate.definitionRef().id(),
                coordinate.definitionRef().revision()))
                .thenReturn(Optional.of(StoredCorrectnessDefinition.verified(
                        mapper, value.definition())));
        when(inventories.findRevision(
                value.scope(), coordinate.inventoryRef().id(),
                coordinate.inventoryRef().revision()))
                .thenReturn(Optional.of(StoredCoverageInventory.verified(
                        mapper, value.inventory())));
        when(scenarios.findRevision(
                value.scope(), coordinate.scenarioDraftSetRef().id(),
                coordinate.scenarioDraftSetRef().revision()))
                .thenReturn(Optional.of(StoredScenarioDraftSetV2.verified(
                        mapper, value.scenarioDraftSet())));
        value.oracles().forEach(oracle -> {
            ExactAssetRef ref = coordinate.oracleRefs().stream()
                    .filter(candidate -> candidate.id().equals(oracle.oracleId()))
                    .findFirst().orElseThrow();
            when(oracles.findRevision(value.scope(), ref.id(), ref.revision()))
                    .thenReturn(Optional.of(StoredBusinessOracle.verified(mapper, oracle)));
        });
        value.assertionSets().forEach(assertion -> {
            ExactAssetRef ref = coordinate.assertionSetRefs().stream()
                    .filter(candidate -> candidate.id().equals(assertion.assertionSetId()))
                    .findFirst().orElseThrow();
            when(assertions.findRevision(value.scope(), ref.id(), ref.revision()))
                    .thenReturn(Optional.of(StoredAssertionSet.verified(
                            mapper, value.scope(), assertion)));
        });
        value.fixtures().forEach(fixture -> when(fixtures.findRevision(
                value.scope(), fixture.descriptorRef().id(),
                fixture.descriptorRef().revision()))
                .thenReturn(Optional.of(StoredFixtureAsset.verified(
                        mapper, fixture.descriptor()))));
    }

    private void stubMaterial(FrozenCompilationInput value, ExactTargetRef target) {
        var fixture = value.fixtures().getFirst();
        var descriptor = fixture.descriptor();
        Receipt receipt = new Receipt(
                "", descriptor.fixtureAssetId(), descriptor.materialRef(),
                descriptor.materialRef().fingerprint(), descriptor.source(),
                FixtureSubject.GRAPH, target, descriptor.schemaRef(),
                descriptor.classification(), descriptor.retention(), descriptor.redaction(),
                List.of(), true, false);
        when(materials.resolve(
                eq(value.scope()), eq(descriptor.materialRef()), any(MaterialAccessContext.class)))
                .thenReturn(new ResolvedFixtureMaterial(
                        descriptor.materialRef(), receipt, fixture.payload()));
    }

    private IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "loan", "test", "sg", "USER", "author", "",
                CorrectnessCompilationService.PURPOSE, "corr-1", Set.of(),
                "CONFIDENTIAL", "");
    }

    private String fp(char digit) {
        return "sha256:" + String.valueOf(digit).repeat(64);
    }
}
