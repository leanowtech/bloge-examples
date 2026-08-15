package com.leanowtech.bloge.gateway.testing.correctness.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompiler;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilerTest;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.FrozenCompilationInput;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.InlineValue;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionEvaluatorProfile;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionSetCompiler;
import com.leanowtech.bloge.gateway.testing.correctness.publication.CorrectnessPublicationRepository;
import com.leanowtech.bloge.gateway.testing.correctness.publication.StoredCorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceComponentSource.Coordinate;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceComponentSource.PageRequest;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicationCorrectnessWorkspaceComponentSourceTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void projectsLatestExactManifestWithoutCompiledPayload() throws Exception {
        FrozenCompilationInput source = new CorrectnessCompilerTest().input(
                new InlineValue(Map.of("decision", "APPROVE")), true);
        StoredCorrectnessPublication publication = publication(source);
        CorrectnessPublicationRepository repository = mock(CorrectnessPublicationRepository.class);
        when(repository.findLatestPublication(any(), any(), any()))
                .thenReturn(Optional.of(publication));
        var components = new PublicationCorrectnessWorkspaceComponentSource(
                new DefinitionOnlyCorrectnessWorkspaceComponentSource(), repository);
        Coordinate coordinate = new Coordinate(
                source.scope(), source.coordinate().definitionRef(), source.coordinate().target(),
                source.coordinate().inventoryRef());

        var result = components.load(
                coordinate, new PageRequest("", 100, fp('1')));

        assertThat(result.lastPublication().publicationRef().id()).isEqualTo("publication-a");
        assertThat(result.lastPublication().lifecycle()).isEqualTo("COMMITTED");
        assertThat(result.capabilities()).contains("CORRECTNESS_PUBLICATION_READ_V1");
        assertThat(mapper.writeValueAsString(result))
                .doesNotContain("customer-account-secret-8848");

        Coordinate mismatchedInventory = new Coordinate(
                source.scope(), source.coordinate().definitionRef(), source.coordinate().target(),
                new ExactAssetRef("INVENTORY", "other", 1, fp('2')));
        assertThatThrownBy(() -> components.load(
                mismatchedInventory, new PageRequest("", 100, fp('1'))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Workspace coordinate");
    }

    private StoredCorrectnessPublication publication(FrozenCompilationInput source) {
        CorrectnessCompiler compiler = new CorrectnessCompiler(
                mapper, new AssertionSetCompiler(mapper),
                AssertionEvaluatorProfile.fixtureEvaluatorV1());
        var report = compiler.compileReport(source);
        List<ExactAssetRef> fixtures = report.compiledAssets().stream()
                .map(value -> value.assetRef())
                .filter(ref -> "FIXTURE_BUNDLE".equals(ref.kind())).toList();
        ExactAssetRef suite = report.compiledAssets().stream()
                .map(value -> value.assetRef())
                .filter(ref -> "TEST_SUITE".equals(ref.kind())).findFirst().orElseThrow();
        PrincipalRef actor = new PrincipalRef("publisher", PrincipalKind.USER, "Publisher");
        Instant now = Instant.parse("2026-08-15T12:00:00Z");
        CorrectnessPublication value = new CorrectnessPublication(
                "", "publication-a", source.scope(), source.coordinate().target(),
                source.coordinate().definitionRef(), source.coordinate().inventoryRef(),
                source.coordinate().scenarioDraftSetRef(), source.coordinate().oracleRefs(),
                source.coordinate().assertionSetRefs(), source.coordinate().fixtureAssetRefs(),
                fixtures, suite, CorrectnessCompiler.COMPILER_VERSION,
                report.compilationFingerprint(), new AuditMetadata(now, now, actor, actor));
        return StoredCorrectnessPublication.verified(mapper, value);
    }

    private static String fp(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
