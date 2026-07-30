package com.leanowtech.bloge.gateway.visual.authoring.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.leanowtech.bloge.core.spi.ExpressionFunction;
import com.leanowtech.bloge.gateway.visual.authoring.compile.AuthoringCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.discovery.FrameworkFunctionInventory;
import com.leanowtech.bloge.gateway.visual.authoring.discovery.FrameworkFunctionInventoryProvider;
import com.leanowtech.bloge.gateway.visual.authoring.discovery.RuntimeParityService;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringCompileResult;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDiagnostic;
import com.leanowtech.bloge.gateway.visual.authoring.model.VisualLibraryAuthoringDocument;
import com.leanowtech.bloge.gateway.visual.catalog.InMemoryOperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.JavaOperatorInventoryProjector;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoringPreviewServiceTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ObjectMapper yaml = new YAMLMapper().findAndRegisterModules();
    private final InMemoryOperatorLibraryRegistry registry = new InMemoryOperatorLibraryRegistry();
    private final AuthoringCompiler compiler =
            new AuthoringCompiler(mapper, new OperatorLibraryValidator());
    private final AuthoringPreviewService service =
            new AuthoringPreviewService(compiler, registry, mapper);

    @Test
    void returnsAuthoritativeCatalogFingerprintAndTargetDiff() throws Exception {
        AuthoringCompileResult result = service.preview(document("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: customer-support, owner: support-team}
                operators:
                  support:echo:
                    input: {value: any}
                    output: {value: any}
                """));

        assertThat(result.importable()).isTrue();
        assertThat(result.catalogFingerprint()).startsWith("sha256:");
        assertThat(result.diff()).satisfies(diff -> {
            assertThat(diff.libraryId()).isEqualTo("customer-support");
            assertThat(diff.changed()).isTrue();
            assertThat(diff.addedOperatorCount()).isEqualTo(1);
            assertThat(diff.baseRevision()).isZero();
        });
        assertThat(result.impact().operatorRefs()).containsExactly("support:echo");
    }

    @Test
    void mapsGlobalCallableConflictBackToTheFunctionKey() throws Exception {
        AuthoringCompileResult result = service.preview(document("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: conflicting-functions, owner: support-team}
                functions:
                  coalesce:
                    signature: "(values: string[]) -> string"
                """));

        assertThat(result.importable()).isFalse();
        assertThat(result.readiness().state()).isEqualTo("INVALID");
        assertThat(result.diagnostics())
                .filteredOn(diagnostic ->
                        "RG.AUTHORING.FUNCTION_CALLABLE_CONFLICT".equals(diagnostic.code()))
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.authoringPath()).isEqualTo("/functions/coalesce");
                    assertThat(diagnostic.metadata().get("existingLibraryId")).isEqualTo("builtin");
                });
    }

    @Test
    void blocksOperatorRefOwnedByAnotherLibraryAndDiffsReplacementRevision() throws Exception {
        AuthoringCompileResult existing = compiler.compile(document("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: shared-operators, owner: platform}
                operators:
                  support:echo:
                    input: {value: string}
                    output: {value: string}
                """));
        registry.upsert(existing.canonicalLibrary());

        AuthoringCompileResult conflict = service.preview(document("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: tenant-operators, owner: tenant-team}
                operators:
                  support:echo:
                    input: {value: string}
                    output: {value: string}
                """));
        AuthoringCompileResult replacement = service.preview(document("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library:
                  id: shared-operators
                  owner: platform
                  version: 2.0.0
                operators:
                  support:echo:
                    input: {value: string}
                    output: {value: string}
                  support:enrich:
                    input: {value: string}
                    output: {value: string}
                """));

        assertThat(conflict.diagnostics())
                .extracting(AuthoringDiagnostic::code)
                .contains("RG.AUTHORING.OPERATOR_REF_CONFLICT");
        assertThat(conflict.importable()).isFalse();
        assertThat(replacement.diff()).satisfies(diff -> {
            assertThat(diff.baseRevision()).isEqualTo(1);
            assertThat(diff.addedOperatorCount()).isEqualTo(1);
            assertThat(diff.changed()).isTrue();
        });
    }

    @Test
    void reachesRuntimeBoundOnlyWhenTheAuthoritativeFunctionSignatureMatches() throws Exception {
        VisualLibraryAuthoringDocument document = document("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: business-functions, owner: support-team}
                functions:
                  businessNormalize:
                    signature: "(value: string) -> string"
                """);
        OperatorLibrary.BuiltInFunction runtimeContract = compiler.compile(document)
                .canonicalLibrary()
                .builtInFunctions()
                .getFirst();
        FrameworkFunctionInventoryProvider provider = new FrameworkFunctionInventoryProvider() {
            @Override
            public String providerId() {
                return "business-runtime";
            }

            @Override
            public String runtimeProfile() {
                return "production";
            }

            @Override
            public Collection<FunctionBinding> functions() {
                ExpressionFunction function = new ExpressionFunction() {
                    @Override
                    public String name() {
                        return "businessNormalize";
                    }

                    @Override
                    public Object apply(Object... arguments) {
                        return arguments.length == 0 ? "" : arguments[0];
                    }

                    @Override
                    public String returnType(String... argumentTypes) {
                        return "String";
                    }
                };
                return List.of(new FunctionBinding(
                        "businessNormalize",
                        function,
                        runtimeContract));
            }
        };
        RuntimeParityService parity = new RuntimeParityService(
                JavaOperatorInventoryProjector.forRegistry(null),
                new FrameworkFunctionInventory(List.of(provider)));
        AuthoringPreviewService runtimeAware = new AuthoringPreviewService(
                compiler,
                registry,
                mapper,
                parity);

        AuthoringCompileResult result = runtimeAware.preview(document);

        assertThat(result.runtimeInventoryFingerprint()).startsWith("sha256:");
        assertThat(result.runtimeParity()).singleElement().satisfies(item -> {
            assertThat(item.assetRef()).isEqualTo("businessNormalize");
            assertThat(item.state()).isEqualTo("BOUND");
            assertThat(item.executableReady()).isTrue();
        });
        assertThat(result.readiness().state()).isEqualTo("RUNTIME_BOUND");
        assertThat(result.readiness().productionReady()).isTrue();
    }

    private VisualLibraryAuthoringDocument document(String source) throws Exception {
        return yaml.readValue(source, VisualLibraryAuthoringDocument.class);
    }
}
