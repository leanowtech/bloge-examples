package com.leanowtech.bloge.examples.common;

import com.leanowtech.bloge.core.engine.GraphResult;

import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

/**
 * Shared helper methods for interactive REPL examples.
 */
public final class ReplHelper {

    private ReplHelper() {
    }

    public static void header(String title) {
        System.out.println("\n═══ " + title + " ═══");
    }

    public static String promptString(Scanner scanner, String prompt, String defaultValue) {
        System.out.print(prompt + " [" + defaultValue + "]: ");
        String line = scanner.nextLine().trim();
        return line.isEmpty() ? defaultValue : line;
    }

    public static int promptInt(Scanner scanner, String prompt, int defaultValue) {
        String raw = promptString(scanner, prompt, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    public static double promptDouble(Scanner scanner, String prompt, double defaultValue) {
        String raw = promptString(scanner, prompt, String.valueOf(defaultValue));
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    public static List<String> promptList(Scanner scanner, String prompt, List<String> defaults) {
        String defaultValue = String.join(",", defaults);
        String raw = promptString(scanner, prompt, defaultValue);
        if (raw.isBlank()) {
            return defaults;
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public static void printResult(GraphResult result) {
        System.out.println("\n═══ Execution Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();
        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-25s → %s%n", entry.getKey(), entry.getValue());
        }
    }

    public static boolean askRunAgain(Scanner scanner) {
        System.out.print("\nRun again? [y/N]: ");
        String answer = scanner.nextLine().trim().toLowerCase();
        return "y".equals(answer) || "yes".equals(answer);
    }
}
