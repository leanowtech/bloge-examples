package com.leanowtech.bloge.gateway.visual.authoring.compile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringCompileResult;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDiagnostic;
import com.leanowtech.bloge.gateway.visual.authoring.model.VisualLibraryAuthoringDocument;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoringCompilerTest {

    private final ObjectMapper mapper = new YAMLMapper().findAndRegisterModules();
    private final AuthoringCompiler compiler = new AuthoringCompiler(mapper, new OperatorLibraryValidator());

    @Test
    void compilesNamedTypesPureOperatorAndFunctionOverloads() throws Exception {
        AuthoringCompileResult result = compile("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library:
                  id: customer-service
                  name: Customer Service
                  version: 1.2.0
                  owner: customer-ops
                defaults:
                  namespace: support
                types:
                  Ticket:
                    fields:
                      id: string
                      subject: string
                      customerTier?:
                        enum: [free, pro, enterprise]
                  TriageResult:
                    fields:
                      priority:
                        enum: [p0, p1, p2, p3]
                      confidence:
                        type: number
                        minimum: 0
                        maximum: 1
                operators:
                  support:classify-ticket:
                    name: Classify Ticket
                    archetype: pure
                    input:
                      ticket: Ticket
                    output:
                      triage: TriageResult
                functions:
                  normalizeText:
                    signature: "(text: string) -> string"
                  coalesce:
                    signatures:
                      - "(value: any, fallback?: any) -> any"
                      - "(values: any[]) -> any"
                """);

        assertThat(result.importable()).isTrue();
        assertThat(result.previewAuthority()).isEqualTo(AuthoringCompileResult.SERVER_AUTHORITATIVE);
        assertThat(result.authoringFingerprint()).startsWith("sha256:");
        assertThat(result.compileFingerprint()).startsWith("sha256:");
        assertThat(result.canonicalFingerprint()).startsWith("sha256:");
        assertThat(result.canonicalLibrary().operators()).hasSize(1);
        assertThat(result.canonicalLibrary().builtInFunctions()).hasSize(2);
        assertThat(result.canonicalLibrary().builtInFunctions())
                .extracting(function -> function.name() + ":" + function.namespace())
                .containsExactly("coalesce:support", "normalizeText:support");
        assertThat(result.canonicalLibrary().builtInFunctions().getFirst().signatures()).hasSize(2);

        Map<String, Object> ticketSchema = result.canonicalLibrary().operators().getFirst()
                .ports().inputs().getFirst().schema().schema();
        assertThat(ticketSchema.get("required")).isEqualTo(List.of("id", "subject"));
        assertThat(result.sourceMap())
                .anySatisfy(entry -> {
                    assertThat(entry.authoringPath())
                            .isEqualTo("/operators/support:classify-ticket/input/ticket");
                    assertThat(entry.canonicalPath())
                            .isEqualTo("/operators/0/ports/inputs/0/schema");
                });
    }

    @Test
    void mapAndFieldOrderDoNotChangeFingerprintsOrCanonicalBytes() throws Exception {
        AuthoringCompileResult first = compile("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: order-test, owner: test-team}
                types:
                  Payload:
                    fields:
                      zeta: integer
                      alpha: string
                      metadata:
                        type: object
                        fields:
                          labels:
                            fields:
                              zeta: boolean
                              alpha: string
                operators:
                  test:echo:
                    archetype: pure
                    input: {payload: Payload}
                    output: {result: Payload}
                functions:
                  trim:
                    signature: "(value: string) -> string"
                """);
        AuthoringCompileResult reordered = compile("""
                functions:
                  trim:
                    signature: "(value: string) -> string"
                operators:
                  test:echo:
                    output: {result: Payload}
                    input: {payload: Payload}
                    archetype: pure
                types:
                  Payload:
                    fields:
                      alpha: string
                      metadata:
                        fields:
                          labels:
                            fields:
                              alpha: string
                              zeta: boolean
                        type: object
                      zeta: integer
                library: {owner: test-team, id: order-test}
                schemaVersion: bloge.visualLibraryAuthoring.v1
                """);

        assertThat(first.authoringFingerprint()).isEqualTo(reordered.authoringFingerprint());
        assertThat(first.compileFingerprint()).isEqualTo(reordered.compileFingerprint());
        assertThat(first.canonicalFingerprint()).isEqualTo(reordered.canonicalFingerprint());
        assertThat(mapper.writeValueAsBytes(first.canonicalLibrary()))
                .containsExactly(mapper.writeValueAsBytes(reordered.canonicalLibrary()));
    }

    @Test
    void distinguishesOptionalFieldsNullableValuesAndOptionalPorts() throws Exception {
        AuthoringCompileResult result = compile("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: optional-test, owner: test-team}
                types:
                  Profile:
                    fields:
                      requiredNullable: string?
                      optionalValue?: string
                      both?: integer?
                operators:
                  test:optional:
                    archetype: pure
                    input:
                      profile?: Profile
                    output:
                      result: boolean
                """);

        assertThat(result.importable()).isTrue();
        var input = result.canonicalLibrary().operators().getFirst().ports().inputs().getFirst();
        assertThat(input.required()).isFalse();
        assertThat(input.name()).isEqualTo("profile");
        assertThat(input.schema().required()).containsExactly("requiredNullable");
        Map<?, ?> requiredNullable = (Map<?, ?>) input.schema().properties().get("requiredNullable");
        Map<?, ?> optionalValue = (Map<?, ?>) input.schema().properties().get("optionalValue");
        assertThat(requiredNullable.containsKey("anyOf")).isTrue();
        assertThat(optionalValue.get("type")).isEqualTo("string");
    }

    @Test
    void missingAndCyclicNamedTypesBlockCanonicalAssemblyAtEditablePaths() throws Exception {
        AuthoringCompileResult missing = compile("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: missing-type, owner: test-team}
                operators:
                  test:missing:
                    archetype: pure
                    input: {value: DoesNotExist}
                    output: {result: string}
                """);
        assertThat(missing.canonicalLibrary()).isNull();
        assertThat(missing.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("RG.AUTHORING.TYPE_REF_NOT_FOUND");
                    assertThat(diagnostic.authoringPath())
                            .isEqualTo("/operators/test:missing/input/value");
                });

        AuthoringCompileResult cyclic = compile("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: cycle-type, owner: test-team}
                types:
                  Node:
                    fields:
                      next?: Node
                operators:
                  test:cycle:
                    archetype: pure
                    input: {node: Node}
                    output: {result: string}
                """);
        assertThat(cyclic.diagnostics())
                .extracting(AuthoringDiagnostic::code)
                .contains("RG.AUTHORING.TYPE_CYCLE_UNSUPPORTED");
    }

    @Test
    void unknownTypeIsExplicitlyDocumentedOnlyWhileAnyIsStronglyDeclared() throws Exception {
        AuthoringCompileResult unknown = compile("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: unknown-test, owner: test-team}
                operators:
                  test:unknown:
                    archetype: pure
                    input: {value: unknown}
                    output: {result: string}
                """);
        assertThat(unknown.importable()).isTrue();
        assertThat(unknown.readiness().state()).isEqualTo("DOCUMENTED_ONLY");
        assertThat(unknown.readiness().strongSchemaReady()).isFalse();
        assertThat(unknown.diagnostics())
                .extracting(AuthoringDiagnostic::code)
                .contains("RG.AUTHORING.TYPE_UNRESOLVED");

        AuthoringCompileResult any = compile("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: any-test, owner: test-team}
                operators:
                  test:any:
                    archetype: pure
                    input: {value: any}
                    output: {result: string}
                """);
        assertThat(any.readiness().state()).isEqualTo("DESIGN_READY");
        assertThat(any.readiness().strongSchemaReady()).isTrue();
    }

    @Test
    void functionOnlyLibraryCompilesWithoutSyntheticOperator() throws Exception {
        AuthoringCompileResult result = compile("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: expression-functions, owner: platform-team}
                defaults: {namespace: expressions}
                functions:
                  normalizeText:
                    signatures:
                      - "(text: string) -> string"
                      - "(values: string[]) -> string"
                """);

        assertThat(result.importable()).isTrue();
        assertThat(result.canonicalLibrary().operators()).isEmpty();
        assertThat(result.canonicalLibrary().builtInFunctions()).hasSize(1);
    }

    @Test
    void canonicalDiagnosticsMapBackToQuickFields() throws Exception {
        AuthoringCompileResult result = compile("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: reserved-test, owner: test-team}
                operators:
                  httpResource:
                    archetype: pure
                    input: {value: string}
                    output: {result: string}
                """);

        assertThat(result.canonicalLibrary()).isNotNull();
        assertThat(result.importable()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.operator.ref.reserved");
                    assertThat(diagnostic.canonicalPath()).isEqualTo("/operators/0/operatorRef");
                    assertThat(diagnostic.authoringPath()).isEqualTo("/operators/httpResource");
                    assertThat(diagnostic.fixes()).isNotEmpty();
                });
        assertThat(result.diagnostics())
                .allSatisfy(diagnostic -> assertThat(diagnostic.authoringPath()).isNotBlank());
    }

    @Test
    void externalArchetypeCannotSilentlyAssumeNoSecrets() throws Exception {
        AuthoringCompileResult result = compile("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: external-test, owner: test-team}
                operators:
                  external:read:
                    archetype: resource-read
                    input: {request: string}
                    output: {response: string}
                """);

        assertThat(result.canonicalLibrary()).isNull();
        assertThat(result.diagnostics())
                .extracting(AuthoringDiagnostic::code)
                .contains("RG.AUTHORING.SECRET_POSTURE_REQUIRED");
        assertThat(result.confirmationRequests())
                .extracting(AuthoringCompileResult.ConfirmationRequest::authoringPath)
                .contains("/operators/external:read/requiresSecrets");
    }

    @Test
    void quickModeRejectsUnsafeSchemaFeaturesAndUnlockedImports() throws Exception {
        AuthoringCompileResult result = compile("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: unsafe-test, owner: test-team}
                imports:
                  - {libraryId: common, version: 1.0.0, fingerprint: "sha256:abc"}
                operators:
                  test:unsafe:
                    archetype: pure
                    input:
                      value:
                        type: string
                        $ref: https://example.invalid/schema
                    output: {result: string}
                """);

        assertThat(result.canonicalLibrary()).isNull();
        assertThat(result.diagnostics())
                .extracting(AuthoringDiagnostic::code)
                .contains(
                        "RG.AUTHORING.CROSS_LIBRARY_IMPORT_UNSUPPORTED",
                        "RG.AUTHORING.TYPE_CONSTRAINT_UNSUPPORTED"
                );
    }

    private AuthoringCompileResult compile(String yaml) throws Exception {
        return compiler.compile(mapper.readValue(yaml, VisualLibraryAuthoringDocument.class));
    }
}
