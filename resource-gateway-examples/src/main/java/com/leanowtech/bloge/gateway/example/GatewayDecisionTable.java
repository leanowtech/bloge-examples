package com.leanowtech.bloge.gateway.example;

import java.util.List;
import java.util.Map;

/**
 * Presentation metadata for a BLOGE decision table in the browser showcase.
 *
 * @param title     table title shown by the UI
 * @param hitPolicy BLOGE hit policy, such as {@code unique}
 * @param inputs    ordered input columns
 * @param outputs   ordered output columns
 * @param rows      ordered decision rows
 */
public record GatewayDecisionTable(
        String title,
        String hitPolicy,
        List<Column> inputs,
        List<Column> outputs,
        List<Row> rows
) {
    /**
     * Creates a decision-table view model.
     */
    public GatewayDecisionTable {
        title = (title == null || title.isBlank()) ? "Decision Table" : title;
        hitPolicy = (hitPolicy == null || hitPolicy.isBlank()) ? "first" : hitPolicy;
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /**
     * A decision-table display column.
     *
     * @param key   stable key used by row maps
     * @param label user-facing column label
     */
    public record Column(String key, String label) {
        /**
         * Creates a display column.
         */
        public Column {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("key must not be blank");
            }
            label = (label == null || label.isBlank()) ? key : label;
        }
    }

    /**
     * A decision-table display row.
     *
     * @param id          stable row identifier, normally the rule id
     * @param conditions  input condition snippets keyed by input column
     * @param output      output values keyed by output column
     * @param explanation short row explanation for the inspector
     */
    public record Row(
            String id,
            Map<String, String> conditions,
            Map<String, Object> output,
            String explanation
    ) {
        /**
         * Creates a display row.
         */
        public Row {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id must not be blank");
            }
            conditions = conditions == null ? Map.of() : Map.copyOf(conditions);
            output = output == null ? Map.of() : Map.copyOf(output);
            explanation = explanation == null ? "" : explanation;
        }
    }
}
