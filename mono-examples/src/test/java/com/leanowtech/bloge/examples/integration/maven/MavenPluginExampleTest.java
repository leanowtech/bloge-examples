package com.leanowtech.bloge.examples.integration.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level checks for the helper around the Maven plugin example profile.
 */
class MavenPluginExampleTest {

    @TempDir
    Path tempDirectory;

    @Test
    void metadataPathMatchesProfileConfigurationFromRepositoryRoot() {
        assertEquals(
                tempDirectory.resolve("bloge-examples/target/bloge-examples/operator-metadata.json"),
                MavenPluginExample.metadataPath(tempDirectory)
        );
    }

    @Test
    void metadataPathMatchesProfileConfigurationFromExamplesModuleRoot() {
        Path moduleRoot = tempDirectory.resolve("bloge-examples");
        assertEquals(
                moduleRoot.resolve("target/bloge-examples/operator-metadata.json"),
                MavenPluginExample.metadataPath(moduleRoot)
        );
    }

    @Test
    void readMetadataReturnsFileContents() throws Exception {
        Path tempFile = Files.createTempFile(tempDirectory, "bloge-metadata", ".json");
        Files.writeString(tempFile, "{\"operators\":[]}");

        assertEquals("{\"operators\":[]}", MavenPluginExample.readMetadata(tempFile));
    }

    @Test
    void readExportedMetadataFindsRepositoryRootOutput() throws Exception {
        Path metadataFile = tempDirectory.resolve("bloge-examples/target/bloge-examples/operator-metadata.json");
        Files.createDirectories(metadataFile.getParent());
        Files.writeString(metadataFile, "{\"operators\":[]}");

        assertEquals("{\"operators\":[]}", MavenPluginExample.readExportedMetadata(tempDirectory));
    }

    @Test
    void readExportedMetadataFindsExamplesModuleOutput() throws Exception {
        Path moduleRoot = tempDirectory.resolve("bloge-examples");
        Path metadataFile = moduleRoot.resolve("target/bloge-examples/operator-metadata.json");
        Files.createDirectories(metadataFile.getParent());
        Files.writeString(metadataFile, "{\"operators\":[]}");

        assertEquals("{\"operators\":[]}", MavenPluginExample.readExportedMetadata(moduleRoot));
    }

    @Test
    void readMetadataExplainsHowToGenerateMissingFile() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> MavenPluginExample.readMetadata(Path.of("target", "missing-operator-metadata.json"))
        );
        assertTrue(exception.getMessage().contains("bloge-plugin-example"));
    }

    @Test
    void readExportedMetadataExplainsCheckedLocationsWhenMissing() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> MavenPluginExample.readExportedMetadata(tempDirectory)
        );

        assertTrue(exception.getMessage().contains("bloge-plugin-example"));
        assertTrue(exception.getMessage().contains(tempDirectory.resolve("bloge-examples/target/bloge-examples/operator-metadata.json").toString()));
    }
}
