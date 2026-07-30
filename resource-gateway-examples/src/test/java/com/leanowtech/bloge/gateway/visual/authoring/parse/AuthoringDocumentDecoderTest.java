package com.leanowtech.bloge.gateway.visual.authoring.parse;

import com.leanowtech.bloge.gateway.visual.authoring.compile.AuthoringCompiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoringDocumentDecoderTest {

    private final AuthoringDocumentDecoder decoder = new AuthoringDocumentDecoder();

    @Test
    void decodesStrictJsonAndYaml() {
        assertThat(decode("""
                {
                  "schemaVersion": "bloge.visualLibraryAuthoring.v1",
                  "library": {"id": "json-functions"},
                  "functions": {
                    "normalize": {"signature": "(value: string) -> string"}
                  }
                }
                """).successful()).isTrue();
        assertThat(decode("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: yaml-operators}
                operators:
                  test:echo:
                    input: {value: any}
                    output: {value: any}
                """).successful()).isTrue();
    }

    @Test
    void rejectsDuplicateKeysUnknownFieldsAndTrailingDocuments() {
        assertParseFailure("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: first}
                library: {id: second}
                functions: {normalize: {signature: "() -> string"}}
                """);
        assertParseFailure("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: unknown}
                executable: true
                functions: {normalize: {signature: "() -> string"}}
                """);
        assertParseFailure("""
                {"schemaVersion":"bloge.visualLibraryAuthoring.v1",
                 "library":{"id":"first"},
                 "functions":{"normalize":{"signature":"() -> string"}}}
                {"schemaVersion":"bloge.visualLibraryAuthoring.v1",
                 "library":{"id":"second"},
                 "functions":{"normalize":{"signature":"() -> string"}}}
                """);
    }

    @Test
    void rejectsCustomTagsAndRecursiveAliases() {
        assertParseFailure("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: !java/object {id: unsafe}
                functions: {normalize: {signature: "() -> string"}}
                """);
        assertParseFailure("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: &library
                  id: recursive
                  owner: *library
                functions: {normalize: {signature: "() -> string"}}
                """);
    }

    @Test
    @Timeout(2)
    void rejectsAliasAndNestingBombsWithinBoundedTime() {
        String aliases = """
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: alias-bomb}
                types:
                  Base: &base
                    fields: {value: string}
                  Expanded:
                    fields:
                """ + java.util.stream.IntStream.range(0, AuthoringDocumentDecoder.MAXIMUM_ALIASES + 2)
                .mapToObj(index -> "      value" + index + ": *base\n")
                .collect(java.util.stream.Collectors.joining())
                + "functions: {normalize: {signature: \"() -> string\"}}\n";
        String nested = deeplyNestedType();

        assertLimitFailure(aliases);
        assertLimitFailure(nested);
    }

    @Test
    void rejectsEmptyAndOversizedSourcesBeforeParsing() {
        assertThat(decoder.decode(new byte[0]).failure().code())
                .isEqualTo("RG.AUTHORING.PARSE_FAILED");
        byte[] oversized = new byte[AuthoringCompiler.MAX_AUTHORING_BYTES + 1];
        assertThat(decoder.decode(oversized).failure())
                .satisfies(failure -> {
                    assertThat(failure.code()).isEqualTo("RG.AUTHORING.DOCUMENT_LIMIT_EXCEEDED");
                    assertThat(failure.status()).isEqualTo(413);
                });
    }

    private AuthoringDocumentDecoder.DecodeResult decode(String source) {
        return decoder.decode(source.getBytes(StandardCharsets.UTF_8));
    }

    private static String deeplyNestedType() {
        StringBuilder source = new StringBuilder("""
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: {id: nested}
                types:
                  Deep:
                """);
        int indentation = 4;
        for (int depth = 0; depth < AuthoringDocumentDecoder.MAXIMUM_DEPTH + 4; depth++) {
            source.append(" ".repeat(indentation)).append("fields:\n");
            indentation += 2;
            source.append(" ".repeat(indentation)).append("level").append(depth).append(":\n");
            indentation += 2;
        }
        source.append(" ".repeat(indentation)).append("type: string\n");
        source.append("functions: {normalize: {signature: \"() -> string\"}}\n");
        return source.toString();
    }

    private void assertParseFailure(String source) {
        assertThat(decode(source).failure())
                .satisfies(failure -> {
                    assertThat(failure).isNotNull();
                    assertThat(failure.code()).isEqualTo("RG.AUTHORING.PARSE_FAILED");
                    assertThat(failure.status()).isEqualTo(400);
                });
    }

    private void assertLimitFailure(String source) {
        assertThat(decode(source).failure())
                .satisfies(failure -> {
                    assertThat(failure).isNotNull();
                    assertThat(failure.code()).isEqualTo("RG.AUTHORING.DOCUMENT_LIMIT_EXCEEDED");
                    assertThat(failure.status()).isEqualTo(413);
                });
    }
}
