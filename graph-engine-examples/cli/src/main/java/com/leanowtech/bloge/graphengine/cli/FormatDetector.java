package com.leanowtech.bloge.graphengine.cli;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Resolves BPMN source format from CLI overrides and input file extensions.
 */
public final class FormatDetector {

    private FormatDetector() {
    }

    /**
     * Detects the effective format for an input path.
     *
     * @param input input file path
     * @param requestedFormat CLI format override
     * @return resolved effective format
     */
    public static CliArgs.Format detect(Path input, CliArgs.Format requestedFormat) {
        Objects.requireNonNull(input, "input must not be null");
        if (requestedFormat == CliArgs.Format.JSON || requestedFormat == CliArgs.Format.XML) {
            return requestedFormat;
        }
        return input.toString().toLowerCase(Locale.ROOT).endsWith(".json")
                ? CliArgs.Format.JSON
                : CliArgs.Format.XML;
    }

    /**
     * Returns whether a path is a supported BPMN input candidate for directory traversal.
     *
     * @param input candidate path
     * @return {@code true} when the file extension is supported
     */
    public static boolean isSupportedInput(Path input) {
        Objects.requireNonNull(input, "input must not be null");
        String normalized = input.toString().toLowerCase(Locale.ROOT);
        return normalized.endsWith(".bpmn") || normalized.endsWith(".xml") || normalized.endsWith(".json");
    }
}
