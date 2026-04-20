package com.leanowtech.bloge.graphengine.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Zero-dependency line-oriented unified diff generator for DSL source
 * comparison.
 *
 * <p>Uses an O(nm) longest-common-subsequence algorithm which is fine for
 * typical graph definition sources (usually &lt; 1000 lines).</p>
 */
final class SourceDiffer {

    private static final int CONTEXT_LINES = 3;

    private SourceDiffer() {
    }

    /**
     * Produces a unified diff between two source strings.
     *
     * @param leftSource  old/left source text
     * @param rightSource new/right source text
     * @param leftLabel   label for the left header (e.g. version string)
     * @param rightLabel  label for the right header (e.g. version string)
     * @return unified diff lines, empty when sources are identical
     */
    static List<String> unifiedDiff(String leftSource, String rightSource,
                                    String leftLabel, String rightLabel) {
        if (leftSource.equals(rightSource)) {
            return List.of();
        }
        String[] leftLines = leftSource.split("\n", -1);
        String[] rightLines = rightSource.split("\n", -1);

        int[][] lcs = lcsTable(leftLines, rightLines);
        List<EditLine> editScript = backtrack(lcs, leftLines, rightLines);
        return formatUnified(editScript, leftLabel, rightLabel, CONTEXT_LINES);
    }

    private static int[][] lcsTable(String[] a, String[] b) {
        int m = a.length;
        int n = b.length;
        int[][] table = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (a[i - 1].equals(b[j - 1])) {
                    table[i][j] = table[i - 1][j - 1] + 1;
                } else {
                    table[i][j] = Math.max(table[i - 1][j], table[i][j - 1]);
                }
            }
        }
        return table;
    }

    private static List<EditLine> backtrack(int[][] table, String[] a, String[] b) {
        List<EditLine> result = new ArrayList<>();
        int i = a.length;
        int j = b.length;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && a[i - 1].equals(b[j - 1])) {
                result.addFirst(new EditLine(EditType.CONTEXT, a[i - 1], i, j));
                i--;
                j--;
            } else if (j > 0 && (i == 0 || table[i][j - 1] >= table[i - 1][j])) {
                result.addFirst(new EditLine(EditType.ADD, b[j - 1], i, j));
                j--;
            } else {
                result.addFirst(new EditLine(EditType.DELETE, a[i - 1], i, j));
                i--;
            }
        }
        return result;
    }

    private static List<String> formatUnified(List<EditLine> edits, String leftLabel,
                                              String rightLabel, int contextSize) {
        List<String> output = new ArrayList<>();
        output.add("--- " + leftLabel);
        output.add("+++ " + rightLabel);

        List<int[]> hunkRanges = findHunkRanges(edits, contextSize);
        for (int[] range : hunkRanges) {
            int start = range[0];
            int end = range[1];
            int leftStart = 0;
            int leftCount = 0;
            int rightStart = 0;
            int rightCount = 0;
            boolean firstLeft = true;
            boolean firstRight = true;

            for (int k = start; k <= end; k++) {
                EditLine edit = edits.get(k);
                switch (edit.type) {
                    case CONTEXT -> {
                        if (firstLeft) { leftStart = edit.leftLine; firstLeft = false; }
                        if (firstRight) { rightStart = edit.rightLine; firstRight = false; }
                        leftCount++;
                        rightCount++;
                    }
                    case DELETE -> {
                        if (firstLeft) { leftStart = edit.leftLine; firstLeft = false; }
                        if (firstRight) { rightStart = edit.rightLine + 1; firstRight = false; }
                        leftCount++;
                    }
                    case ADD -> {
                        if (firstLeft) { leftStart = edit.leftLine + 1; firstLeft = false; }
                        if (firstRight) { rightStart = edit.rightLine; firstRight = false; }
                        rightCount++;
                    }
                }
            }
            output.add("@@ -" + leftStart + "," + leftCount + " +" + rightStart + "," + rightCount + " @@");
            for (int k = start; k <= end; k++) {
                EditLine edit = edits.get(k);
                output.add(switch (edit.type) {
                    case CONTEXT -> " " + edit.text;
                    case ADD -> "+" + edit.text;
                    case DELETE -> "-" + edit.text;
                });
            }
        }
        return List.copyOf(output);
    }

    private static List<int[]> findHunkRanges(List<EditLine> edits, int contextSize) {
        List<int[]> ranges = new ArrayList<>();
        int hunkStart = -1;
        int hunkEnd = -1;
        int trailing = 0;

        for (int i = 0; i < edits.size(); i++) {
            EditLine edit = edits.get(i);
            if (edit.type != EditType.CONTEXT) {
                int start = Math.max(0, i - contextSize);
                int end = Math.min(edits.size() - 1, i + contextSize);
                if (hunkStart == -1) {
                    hunkStart = start;
                    hunkEnd = end;
                } else if (start <= hunkEnd + 1) {
                    hunkEnd = end;
                } else {
                    ranges.add(new int[]{hunkStart, hunkEnd});
                    hunkStart = start;
                    hunkEnd = end;
                }
            }
        }
        if (hunkStart != -1) {
            ranges.add(new int[]{hunkStart, hunkEnd});
        }
        return ranges;
    }

    private enum EditType { CONTEXT, ADD, DELETE }

    private record EditLine(EditType type, String text, int leftLine, int rightLine) {
    }
}
