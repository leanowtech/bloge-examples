package com.leanowtech.bloge.gateway.example;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Extracts display-only decision-table metadata from showcase DSL.
 *
 * <p>The BLOGE compiler remains the source of truth for execution. This class only
 * turns a familiar decision-table snippet into a browser matrix so users can see
 * the rows they are editing.</p>
 */
final class DecisionTableDslViewExtractor {

    private DecisionTableDslViewExtractor() {
    }

    static Optional<GatewayDecisionTable> extract(String dsl) {
        if (dsl == null || dsl.isBlank()) {
            return Optional.empty();
        }

        int start = dsl.indexOf("decision_table ");
        if (start < 0) {
            return Optional.empty();
        }

        int nameStart = start + "decision_table ".length();
        int paramsStart = dsl.indexOf('(', nameStart);
        if (paramsStart < 0) {
            return Optional.empty();
        }
        String name = dsl.substring(nameStart, paramsStart).trim();

        int paramsEnd = findMatching(dsl, paramsStart, '(', ')');
        if (paramsEnd < 0) {
            return Optional.empty();
        }
        List<GatewayDecisionTable.Column> inputs = parseColumns(dsl.substring(paramsStart + 1, paramsEnd));

        int hitIndex = dsl.indexOf("hit=", paramsEnd);
        String hitPolicy = "first";
        if (hitIndex >= 0) {
            int hitEnd = hitIndex + 4;
            while (hitEnd < dsl.length() && Character.isJavaIdentifierPart(dsl.charAt(hitEnd))) {
                hitEnd++;
            }
            hitPolicy = dsl.substring(hitIndex + 4, hitEnd);
        }

        int outputArrow = dsl.indexOf("->", paramsEnd);
        if (outputArrow < 0) {
            return Optional.empty();
        }
        int outputStart = dsl.indexOf('{', outputArrow);
        if (outputStart < 0) {
            return Optional.empty();
        }
        int outputEnd = findMatching(dsl, outputStart, '{', '}');
        if (outputEnd < 0) {
            return Optional.empty();
        }
        List<GatewayDecisionTable.Column> outputs = parseColumns(dsl.substring(outputStart + 1, outputEnd));

        int rulesStart = dsl.indexOf('{', outputEnd + 1);
        if (rulesStart < 0) {
            return Optional.empty();
        }
        int rulesEnd = findMatching(dsl, rulesStart, '{', '}');
        if (rulesEnd < 0) {
            return Optional.empty();
        }

        List<GatewayDecisionTable.Row> rows = parseRows(dsl.substring(rulesStart + 1, rulesEnd), inputs);
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new GatewayDecisionTable(
                readableName(name),
                hitPolicy,
                inputs,
                outputs,
                rows
        ));
    }

    private static List<GatewayDecisionTable.Column> parseColumns(String source) {
        List<GatewayDecisionTable.Column> columns = new ArrayList<>();
        for (String part : splitTopLevel(source, ',')) {
            String trimmed = part.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            int colon = trimmed.indexOf(':');
            int end = equals >= 0 ? equals : colon;
            if (end < 0) {
                end = trimmed.length();
            }
            String key = trimmed.substring(0, end).trim();
            if (!key.isBlank()) {
                columns.add(new GatewayDecisionTable.Column(key, readableName(key)));
            }
        }
        return columns;
    }

    private static List<GatewayDecisionTable.Row> parseRows(String source,
                                                            List<GatewayDecisionTable.Column> inputs) {
        List<GatewayDecisionTable.Row> rows = new ArrayList<>();
        int generatedId = 1;
        for (String line : source.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("///")) {
                continue;
            }

            Map<String, String> conditions = new LinkedHashMap<>();
            String outputSource;
            if (trimmed.startsWith("rule ")) {
                int conditionStart = trimmed.indexOf('(');
                int conditionEnd = conditionStart < 0 ? -1 : findMatching(trimmed, conditionStart, '(', ')');
                if (conditionStart < 0 || conditionEnd < 0) {
                    continue;
                }
                conditions.putAll(parseConditions(trimmed.substring(conditionStart + 1, conditionEnd)));
                outputSource = afterArrow(trimmed.substring(conditionEnd + 1));
            } else if (trimmed.startsWith("otherwise")) {
                for (GatewayDecisionTable.Column input : inputs) {
                    conditions.put(input.key(), "otherwise");
                }
                outputSource = afterArrow(trimmed);
            } else {
                continue;
            }

            Map<String, Object> output = parseOutput(outputSource);
            String id = String.valueOf(output.getOrDefault("ruleId", "R" + generatedId));
            rows.add(new GatewayDecisionTable.Row(id, conditions, output, trimmed));
            generatedId++;
        }
        return rows;
    }

    private static Map<String, String> parseConditions(String source) {
        Map<String, String> conditions = new LinkedHashMap<>();
        for (String part : splitTopLevel(source, ',')) {
            String trimmed = part.trim();
            int colon = trimmed.indexOf(':');
            if (colon > 0) {
                conditions.put(trimmed.substring(0, colon).trim(), trimmed.substring(colon + 1).trim());
            }
        }
        return conditions;
    }

    private static Map<String, Object> parseOutput(String source) {
        Map<String, Object> output = new LinkedHashMap<>();
        int start = source.indexOf('{');
        int end = start < 0 ? -1 : findMatching(source, start, '{', '}');
        if (start < 0 || end < 0) {
            return output;
        }
        String body = source.substring(start + 1, end);
        for (String part : splitTopLevel(body, ',')) {
            String trimmed = part.trim();
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            output.put(trimmed.substring(0, colon).trim(), parseLiteral(trimmed.substring(colon + 1).trim()));
        }
        return output;
    }

    private static Object parseLiteral(String value) {
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            return value.substring(1, value.length() - 1);
        }
        try {
            if (value.contains(".")) {
                return Double.parseDouble(value);
            }
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private static String afterArrow(String source) {
        int arrow = source.indexOf("->");
        return arrow < 0 ? "" : source.substring(arrow + 2).trim();
    }

    private static int findMatching(String source, int openIndex, char open, char close) {
        int depth = 0;
        boolean inString = false;
        for (int i = openIndex; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '"' && (i == 0 || source.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (inString) {
                continue;
            }
            if (ch == open) {
                depth++;
            } else if (ch == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<String> splitTopLevel(String source, char separator) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        int start = 0;
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '"' && (i == 0 || source.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString) {
                if (ch == '{' || ch == '(' || ch == '[') {
                    depth++;
                } else if (ch == '}' || ch == ')' || ch == ']') {
                    depth--;
                } else if (ch == separator && depth == 0) {
                    result.add(source.substring(start, i));
                    start = i + 1;
                }
            }
        }
        result.add(source.substring(start));
        return result;
    }

    private static String readableName(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String spaced = key.replace('_', ' ').replace('-', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
