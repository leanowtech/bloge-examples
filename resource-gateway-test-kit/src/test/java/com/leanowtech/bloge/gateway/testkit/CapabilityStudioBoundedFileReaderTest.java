package com.leanowtech.bloge.gateway.testkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

class CapabilityStudioBoundedFileReaderTest {
    @TempDir
    Path temp;

    @Test
    void readsAnOrdinaryFileUpToTheInclusiveLimit() throws Exception {
        Path input = temp.resolve("result.json");
        byte[] expected = "{\"status\":\"PASS\"}".getBytes(StandardCharsets.UTF_8);
        Files.write(input, expected);

        assertThat(CapabilityStudioBoundedFileReader.read(input, expected.length))
                .isEqualTo(expected);
    }

    @Test
    void rejectsSymlinkWithoutReadingItsTarget() throws Exception {
        Path target = temp.resolve("target.json");
        Path link = temp.resolve("result.json");
        Files.writeString(target, "secret-payload", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (UnsupportedOperationException | IOException failure) {
            abort("symbolic links are unavailable in this test environment");
        }

        assertThat(CapabilityStudioBoundedFileReader.read(link, 1024)).isNull();
    }

    @Test
    void rejectsDirectoriesAndOtherNonRegularInputs() throws Exception {
        Path directory = temp.resolve("result.json");
        Files.createDirectory(directory);

        assertThat(CapabilityStudioBoundedFileReader.read(directory, 1024)).isNull();
    }

    @Test
    void rejectsAFileAboveTheHardLimitBeforeAllocatingItsContents() throws Exception {
        Path input = temp.resolve("result.json");
        Files.write(input, new byte[1025]);

        assertThat(CapabilityStudioBoundedFileReader.read(input, 1024)).isNull();
    }

    @Test
    void rejectsTruncationAfterTheChannelWasOpened() throws Exception {
        Path input = temp.resolve("result.json");
        Files.writeString(input, "original", StandardCharsets.UTF_8);

        byte[] result = CapabilityStudioBoundedFileReader.read(input, 1024, () -> {
            try {
                Files.write(input, new byte[0], StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        });

        assertThat(result).isNull();
    }

    @Test
    void rejectsReplacementAfterTheChannelWasOpened() throws Exception {
        Path input = temp.resolve("result.json");
        Path replacement = temp.resolve("replacement.json");
        Files.writeString(input, "original", StandardCharsets.UTF_8);
        Files.writeString(replacement, "replacement", StandardCharsets.UTF_8);

        byte[] result = CapabilityStudioBoundedFileReader.read(input, 1024, () -> {
            try {
                Files.move(replacement, input, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        });

        assertThat(result).isNull();
    }
}
