package com.leanowtech.bloge.examples.integration;

import com.leanowtech.bloge.examples.integration.maven.MavenPluginExample;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level checks for the helper around the Maven plugin example profile.
 */
class MavenPluginExampleTest {

    @Test
    void metadataPathMatchesProfileConfiguration() {
        assertEquals(Path.of("target", "bloge-examples", "operator-metadata.json"), MavenPluginExample.metadataPath());
    }

    @Test
    void readMetadataReturnsFileContents() throws Exception {
        Path tempFile = Files.createTempFile("bloge-metadata", ".json");
        Files.writeString(tempFile, "{\"operators\":[]}");

        assertEquals("{\"operators\":[]}", MavenPluginExample.readMetadata(tempFile));
    }

    @Test
    void readMetadataExplainsHowToGenerateMissingFile() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> MavenPluginExample.readMetadata(Path.of("target", "missing-operator-metadata.json"))
        );
        assertTrue(exception.getMessage().contains("bloge-plugin-example"));
    }
}
