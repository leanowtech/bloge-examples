package com.leanowtech.bloge.examples.integration.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Helper around the Maven plugin profile introduced for the examples module.
 *
 * <p>Run {@code mvn -pl bloge-maven-plugin,bloge-examples -am -Pbloge-plugin-example verify}
 * to export operator metadata and lint the dedicated plugin example DSL. This class then prints the
 * generated metadata file so readers can inspect what Bloge Studio would import, regardless of whether
 * it is launched from the repository root or the {@code bloge-examples} module directory.</p>
 */
public final class MavenPluginExample {

    private static final String EXAMPLES_MODULE = "bloge-examples";
    private static final Path METADATA_RELATIVE_PATH =
            Path.of("target", EXAMPLES_MODULE, "operator-metadata.json");
    private static final String GENERATE_COMMAND =
            "mvn -pl bloge-maven-plugin,bloge-examples -am -Pbloge-plugin-example verify";
    private static final String PRINT_COMMAND =
            "mvn exec:java -pl bloge-examples "
                    + "-Dexec.mainClass=\"com.leanowtech.bloge.examples.integration.maven.MavenPluginExample\"";

    private MavenPluginExample() {
    }

    /**
     * Returns the location configured by the example profile.
     */
    public static Path metadataPath() {
        return metadataPath(Path.of("").toAbsolutePath().normalize());
    }

    static Path metadataPath(Path workingDirectory) {
        List<Path> candidates = metadataCandidates(workingDirectory);
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return candidates.get(0);
    }

    static List<Path> metadataCandidates(Path workingDirectory) {
        Path normalizedWorkingDirectory = workingDirectory.toAbsolutePath().normalize();
        Path workingDirectoryName = normalizedWorkingDirectory.getFileName();
        if (workingDirectoryName != null && EXAMPLES_MODULE.equals(workingDirectoryName.toString())) {
            return List.of(normalizedWorkingDirectory.resolve(METADATA_RELATIVE_PATH));
        }
        return List.of(
                normalizedWorkingDirectory.resolve(EXAMPLES_MODULE).resolve(METADATA_RELATIVE_PATH),
                normalizedWorkingDirectory.resolve(METADATA_RELATIVE_PATH)
        );
    }

    /**
     * Reads an exported metadata file and fails with a helpful message when the profile was not run.
     */
    public static String readMetadata(Path path) {
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!Files.exists(normalizedPath)) {
            throw new IllegalStateException(
                    "Metadata file not found at " + normalizedPath + ". Run: " + GENERATE_COMMAND
            );
        }
        try {
            return Files.readString(normalizedPath);
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to read exported metadata from " + normalizedPath, ioException);
        }
    }

    public static String readExportedMetadata() {
        return readExportedMetadata(Path.of("").toAbsolutePath().normalize());
    }

    static String readExportedMetadata(Path workingDirectory) {
        List<Path> candidates = metadataCandidates(workingDirectory);
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return readMetadata(candidate);
            }
        }
        throw new IllegalStateException(
                "Metadata file not found. Checked: " + describePaths(candidates)
                        + ". Run: " + GENERATE_COMMAND
                        + " and then " + PRINT_COMMAND
        );
    }

    private static String describePaths(List<Path> paths) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < paths.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(paths.get(index));
        }
        return builder.toString();
    }

    public static void main(String[] args) {
        System.out.println(readExportedMetadata());
    }
}
