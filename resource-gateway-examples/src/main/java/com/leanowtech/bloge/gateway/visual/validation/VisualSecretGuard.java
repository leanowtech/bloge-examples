package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Detects raw secrets that must not be persisted in visual authoring artifacts.
 */
public final class VisualSecretGuard {

    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i).*(secret|password|passwd|token|api[_-]?key|credential|authorization|bearer).*");
    private static final Pattern REFERENCE_FIELD = Pattern.compile(
            "(?i).*(secretRef|secretReference|credentialRef|tokenRef|apiKeyRef).*");
    private static final Pattern RAW_SECRET_VALUE = Pattern.compile(
            "(?i)^(Bearer\\s+.+|Basic\\s+.+|sk-[A-Za-z0-9_-]{12,}|AKIA[A-Z0-9]{12,}|"
                    + "eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)$");

    private VisualSecretGuard() {
    }

    /**
     * @param operator operator definition to scan
     * @param path diagnostic path prefix
     * @return diagnostics for raw secret material
     */
    public static List<VisualDiagnostic> detectOperatorSecrets(OperatorDefinition operator, String path) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        scan(operator.configSchema().schema(), path + "/configSchema/schema", "", diagnostics);
        scan(operator.lowering().parameters(), path + "/lowering/parameters", "", diagnostics);
        for (int i = 0; i < operator.ports().inputs().size(); i++) {
            OperatorDefinition.Port port = operator.ports().inputs().get(i);
            if (port == null) {
                continue;
            }
            scan(port.schema().schema(),
                    path + "/ports/inputs/" + i + "/schema/schema", "", diagnostics);
        }
        for (int i = 0; i < operator.ports().outputs().size(); i++) {
            OperatorDefinition.Port port = operator.ports().outputs().get(i);
            if (port == null) {
                continue;
            }
            scan(port.schema().schema(),
                    path + "/ports/outputs/" + i + "/schema/schema", "", diagnostics);
        }
        return diagnostics;
    }

    /**
     * @param draft graph draft to scan
     * @return diagnostics for raw secret material
     */
    public static List<VisualDiagnostic> detectDraftSecrets(GraphDraft draft) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (draft == null) {
            return diagnostics;
        }
        scan(draft.inputSchema().schema(), "/inputSchema/schema", "", diagnostics);
        scan(draft.outputSchema().schema(), "/outputSchema/schema", "", diagnostics);
        scan(draft.visualLayout(), "/visualLayout", "", diagnostics);
        draft.nodeFixtures().forEach((nodeId, fixture) -> {
            scan(fixture.output(), "/nodeFixtures/" + nodeId + "/output", "output", diagnostics);
            scan(fixture.expectedInput(), "/nodeFixtures/" + nodeId + "/expectedInput",
                    "expectedInput", diagnostics);
        });
        for (int i = 0; i < draft.nodes().size(); i++) {
            int nodeIndex = i;
            GraphDraft.DraftNode node = draft.nodes().get(i);
            scan(node.config(), "/nodes/" + nodeIndex + "/config", "", diagnostics);
            node.inputs().forEach((key, binding) -> scanBinding(binding,
                    "/nodes/" + nodeIndex + "/inputs/" + key, key, diagnostics));
        }
        return diagnostics;
    }

    /**
     * @param value visual authoring artifact fragment to scan
     * @param path diagnostic path prefix
     * @return diagnostics for raw secret material
     */
    public static List<VisualDiagnostic> detectRawSecrets(Object value, String path) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        scan(value, path == null || path.isBlank() ? "/" : path, "", diagnostics);
        return diagnostics;
    }

    /**
     * Throws an exception if a draft contains raw secret material.
     *
     * @param draft draft to verify
     */
    public static void requireNoDraftSecrets(GraphDraft draft) {
        List<VisualDiagnostic> diagnostics = detectDraftSecrets(draft);
        if (!diagnostics.isEmpty()) {
            throw new IllegalArgumentException(diagnostics.getFirst().message());
        }
    }

    private static void scanBinding(GraphDraft.Binding binding,
                                    String path,
                                    String fieldName,
                                    List<VisualDiagnostic> diagnostics) {
        if ("constant".equals(binding.kind())) {
            scanScalar(binding.value(), path + "/value", fieldName, diagnostics);
        }
        if ("objectTemplate".equals(binding.kind())) {
            binding.fields().forEach((key, nested) -> scanBinding(nested, path + "/fields/" + key,
                    key, diagnostics));
        }
    }

    private static void scan(Object value, String path, String fieldName, List<VisualDiagnostic> diagnostics) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                scan(item, path + "/" + name, name, diagnostics);
            });
            return;
        }
        if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                scan(list.get(i), path + "/" + i, fieldName, diagnostics);
            }
            return;
        }
        scanScalar(value, path, fieldName, diagnostics);
    }

    private static void scanScalar(Object value,
                                   String path,
                                   String fieldName,
                                   List<VisualDiagnostic> diagnostics) {
        if (!(value instanceof String string) || string.isBlank()) {
            return;
        }
        if (isReferenceField(fieldName) || isPlaceholder(string)) {
            return;
        }
        if (isSensitiveField(fieldName) || RAW_SECRET_VALUE.matcher(string).matches()) {
            diagnostics.add(VisualDiagnostic.error("visual.secret.raw",
                    "Raw secret material must not be stored in visual artifacts; use a secretRef instead.",
                    path));
        }
    }

    private static boolean isSensitiveField(String fieldName) {
        return fieldName != null && SENSITIVE_FIELD.matcher(fieldName).matches();
    }

    private static boolean isReferenceField(String fieldName) {
        return fieldName != null && REFERENCE_FIELD.matcher(fieldName).matches();
    }

    private static boolean isPlaceholder(String value) {
        String candidate = value.trim();
        return candidate.startsWith("${") || candidate.startsWith("{{") || candidate.startsWith("secretRef:");
    }
}
