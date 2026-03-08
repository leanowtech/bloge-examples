package com.leanowtech.bloge.examples.integration.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Helper around the Maven plugin profile introduced for the examples module.
 *
 * <p>Run {@code mvn -pl bloge-maven-plugin,bloge-examples -am -Pbloge-plugin-example verify}
 * to export operator metadata and lint the dedicated plugin example DSL. This class then prints the
 * generated metadata file so readers can inspect what Bloge Studio would import.</p>
 */
public final class MavenPluginExample {

    private static final String GENERATE_COMMAND =
            "mvn -pl bloge-maven-plugin,bloge-examples -am -Pbloge-plugin-example verify";

    private MavenPluginExample() {
    }

    /**
     * Returns the location configured by the example profile.
     */
    public static Path metadataPath() {
        return Path.of("target", "bloge-examples", "operator-metadata.json");
    }

    /**
     * Reads an exported metadata file and fails with a helpful message when the profile was not run.
     */
    public static String readMetadata(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalStateException("Metadata file not found at " + path + ". Run: " + GENERATE_COMMAND);
        }
        try {
            return Files.readString(path);
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to read exported metadata from " + path, ioException);
        }
    }

    public static String readExportedMetadata() {
        return readMetadata(metadataPath());
    }

    public static void main(String[] args) {
        System.out.println(readExportedMetadata());
    }
}
