package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioFormalInputTreeSnapshotter.TreeKind.AUTHORITY_BUNDLE;
import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioFormalInputTreeTestFixtures.AUTHORITY_SEMANTIC;
import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioFormalInputTreeTestFixtures.PUBLICATION_FINGERPRINT;
import static com.leanowtech.bloge.gateway.testkit.CapabilityStudioFormalInputTreeTestFixtures.TRANSACTION_NONCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioFormalInputTreeSchemaPackagingTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void strictDraft202012SchemaIsPackagedWithKindSpecificBounds() throws Exception {
        var resource = getClass().getResource(
                CapabilityStudioSchemaSupport.FORMAL_INPUT_TREE_V1_RESOURCE);

        assertThat(resource).isNotNull();
        var schema = JSON.readTree(resource);
        assertThat(schema.path("$schema").asText())
                .isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.at("/properties/treeKind/enum")).hasSize(2);
        assertThat(schema.at("/properties/entries/maxItems").intValue())
                .isEqualTo(CapabilityStudioFormalInputTreeSnapshotter
                        .MAXIMUM_AUTHORITY_ENTRY_COUNT);
        assertThat(schema.at("/allOf/0/then/properties/entryCount/const").intValue())
                .isEqualTo(CapabilityStudioFormalInputTreeSnapshotter
                        .TARGET_ADMISSION_ENTRY_COUNT);
        assertThat(schema.at("/$defs/entry/additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void publishedCanonicalManifestValidatesAndUnknownFieldIsRejected() throws Exception {
        Path workspace = CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                temporaryDirectory.toRealPath().resolve("workspace"));
        Path source = CapabilityStudioFormalInputTreeTestFixtures.authorityBundle(
                CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                        workspace.resolve("source")));
        Path output = CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                workspace.resolve("publication")).resolve("snapshot");
        var snapshotter = new CapabilityStudioFormalInputTreeSnapshotter();
        var declaration = snapshotter.declare(AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC);
        snapshotter.snapshot(AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC,
                output, declaration.treeFingerprint(), PUBLICATION_FINGERPRINT,
                TRANSACTION_NONCE);
        ObjectNode manifest = (ObjectNode) JSON.readTree(output.resolve(
                CapabilityStudioFormalInputTreeSnapshotter.MANIFEST_FILE).toFile());

        assertThat(CapabilityStudioSchemaSupport.validate(
                manifest, CapabilityStudioSchemaSupport.FORMAL_INPUT_TREE_V1_RESOURCE)).isEmpty();
        manifest.put("unknown", "secret");
        assertThat(CapabilityStudioSchemaSupport.validate(
                manifest, CapabilityStudioSchemaSupport.FORMAL_INPUT_TREE_V1_RESOURCE))
                .isNotEmpty();
    }

    @Test
    void declarationApiRejectsUnsortedEntries() {
        assertThatThrownBy(() -> CapabilityStudioFormalInputTreeSnapshotter.createDeclaration(
                AUTHORITY_BUNDLE, AUTHORITY_SEMANTIC,
                java.util.List.of(
                        new CapabilityStudioFormalInputTreeSnapshotter.TreeEntry(
                                "z.json", 1,
                                CapabilityStudioFormalInputTreeTestFixtures.fingerprint('1')),
                        new CapabilityStudioFormalInputTreeSnapshotter.TreeEntry(
                                "a.json", 1,
                                CapabilityStudioFormalInputTreeTestFixtures.fingerprint('2')))))
                .isInstanceOf(CapabilityStudioFormalInputTreeSnapshotter
                        .FormalInputTreeException.class);
    }

    @Test
    void verifierRejectsDuplicateUnknownAndTrailingManifestBytes() throws Exception {
        for (String mutation : java.util.List.of("duplicate", "unknown", "trailing")) {
            Path workspace = CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                    temporaryDirectory.toRealPath().resolve("strict-" + mutation));
            Path source = CapabilityStudioFormalInputTreeTestFixtures.authorityBundle(
                    CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                            workspace.resolve("source")));
            Path output = CapabilityStudioFormalInputTreeTestFixtures.privateDirectory(
                    workspace.resolve("publication")).resolve("snapshot");
            var snapshotter = new CapabilityStudioFormalInputTreeSnapshotter();
            var declaration = snapshotter.declare(
                    AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC);
            var receipt = snapshotter.snapshot(
                    AUTHORITY_BUNDLE, source, AUTHORITY_SEMANTIC, output,
                    declaration.treeFingerprint(), PUBLICATION_FINGERPRINT,
                    TRANSACTION_NONCE);
            Path manifest = output.resolve(
                    CapabilityStudioFormalInputTreeSnapshotter.MANIFEST_FILE);
            String original = Files.readString(manifest);
            String changed = switch (mutation) {
                case "duplicate" -> original.replaceFirst(
                        "\\{", "{\"messageVersion\":\"duplicate\",");
                case "unknown" -> original.substring(0, original.length() - 1)
                        + ",\"unknown\":true}";
                case "trailing" -> original + " ";
                default -> throw new IllegalStateException();
            };
            Files.setPosixFilePermissions(manifest,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            Files.writeString(manifest, changed);
            Files.setPosixFilePermissions(manifest,
                    java.nio.file.attribute.PosixFilePermissions.fromString("r--------"));

            assertThatThrownBy(() -> snapshotter.verify(
                    output, AUTHORITY_BUNDLE, AUTHORITY_SEMANTIC,
                    declaration.treeFingerprint(), PUBLICATION_FINGERPRINT,
                    receipt.transactionId()))
                    .isInstanceOf(CapabilityStudioFormalInputTreeSnapshotter
                            .FormalInputTreeException.class);
        }
    }
}
