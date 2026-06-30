package com.leanowtech.bloge.gateway.visual.draft;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Applies a small RFC 6902 JSON Patch subset to graph drafts.
 */
@Service
public class GraphDraftPatchService {

    private static final Set<String> SERVER_MANAGED_ROOTS = Set.of(
            "draftId",
            "revision",
            "revisionMetadata",
            "operatorFingerprints"
    );

    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper JSON mapper
     */
    public GraphDraftPatchService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Applies patch operations to the current draft.
     *
     * @param current current draft
     * @param request patch request
     * @return patched draft
     */
    public GraphDraft apply(GraphDraft current, GraphDraftPatchRequest request) {
        if (current == null) {
            throw new IllegalArgumentException("Current draft is required.");
        }
        if (request == null || request.patch().isEmpty()) {
            throw new IllegalArgumentException("Patch must contain at least one operation.");
        }

        JsonNode working = objectMapper.valueToTree(current);
        for (GraphDraftPatchRequest.PatchOperation operation : request.patch()) {
            working = applyOperation(working, operation);
        }
        GraphDraft patched = objectMapper.convertValue(working, GraphDraft.class);
        if (patched == null) {
            throw new IllegalArgumentException("Patch result must be a graph draft.");
        }
        return patched;
    }

    /**
     * @param current current draft
     * @param request patch request
     * @return diagnostics for patch preconditions
     */
    public List<VisualDiagnostic> validateRequest(GraphDraft current, GraphDraftPatchRequest request) {
        if (request == null) {
            return List.of(VisualDiagnostic.error("visual.draft.patchMissing",
                    "Patch request is required.", "/"));
        }
        if (request.expectedRevision() != current.revision()) {
            return List.of(VisualDiagnostic.error("visual.draft.revisionConflict",
                    "Draft revision conflict: expected %d but current revision is %d."
                            .formatted(request.expectedRevision(), current.revision()),
                    "/expectedRevision"));
        }
        if (request.patch().isEmpty()) {
            return List.of(VisualDiagnostic.error("visual.draft.patchEmpty",
                    "Patch must contain at least one operation.", "/patch"));
        }
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        for (int i = 0; i < request.patch().size(); i++) {
            int operationIndex = i;
            GraphDraftPatchRequest.PatchOperation operation = request.patch().get(i);
            if (operation == null) {
                diagnostics.add(VisualDiagnostic.error("visual.draft.patchOperationMissing",
                        "Patch operation is required.",
                        "/patch/" + operationIndex));
                continue;
            }
            forbiddenServerManagedRoot(operation.path()).ifPresent(root -> diagnostics.add(VisualDiagnostic.error(
                    "visual.draft.patchPathForbidden",
                    "Patch path '%s' targets server-managed draft field '%s'."
                            .formatted(operation.path().isBlank() ? "/" : operation.path(), root),
                    "/patch/" + operationIndex + "/path"
            )));
        }
        return diagnostics;
    }

    private JsonNode applyOperation(JsonNode working, GraphDraftPatchRequest.PatchOperation operation) {
        if (operation == null) {
            throw new IllegalArgumentException("Patch operation is required.");
        }
        String op = operation.op().trim();
        return switch (op) {
            case "add" -> add(working, operation.path(), operation.value());
            case "replace" -> replace(working, operation.path(), operation.value());
            case "remove" -> remove(working, operation.path());
            default -> throw new IllegalArgumentException("Unsupported patch operation: " + operation.op());
        };
    }

    private JsonNode add(JsonNode working, String path, Object value) {
        if (path.isBlank()) {
            return objectMapper.valueToTree(value);
        }
        ParentPointer parent = parentPointer(path);
        JsonNode parentNode = working.at(parent.parentPath());
        JsonNode valueNode = objectMapper.valueToTree(value);
        if (parentNode instanceof ObjectNode objectNode) {
            objectNode.set(parent.segment(), valueNode);
            return working;
        }
        if (parentNode instanceof ArrayNode arrayNode) {
            if ("-".equals(parent.segment())) {
                arrayNode.add(valueNode);
            } else {
                arrayNode.insert(arrayIndex(parent.segment(), arrayNode.size(), true), valueNode);
            }
            return working;
        }
        throw new IllegalArgumentException("Patch parent does not exist or is not mutable: " + parent.parentPath());
    }

    private JsonNode replace(JsonNode working, String path, Object value) {
        if (path.isBlank()) {
            return objectMapper.valueToTree(value);
        }
        ParentPointer parent = parentPointer(path);
        JsonNode parentNode = working.at(parent.parentPath());
        JsonNode valueNode = objectMapper.valueToTree(value);
        if (parentNode instanceof ObjectNode objectNode && objectNode.has(parent.segment())) {
            objectNode.set(parent.segment(), valueNode);
            return working;
        }
        if (parentNode instanceof ArrayNode arrayNode) {
            arrayNode.set(arrayIndex(parent.segment(), arrayNode.size(), false), valueNode);
            return working;
        }
        throw new IllegalArgumentException("Patch target does not exist: " + path);
    }

    private JsonNode remove(JsonNode working, String path) {
        if (path.isBlank()) {
            throw new IllegalArgumentException("Cannot remove the draft root.");
        }
        ParentPointer parent = parentPointer(path);
        JsonNode parentNode = working.at(parent.parentPath());
        if (parentNode instanceof ObjectNode objectNode && objectNode.has(parent.segment())) {
            objectNode.remove(parent.segment());
            return working;
        }
        if (parentNode instanceof ArrayNode arrayNode) {
            arrayNode.remove(arrayIndex(parent.segment(), arrayNode.size(), false));
            return working;
        }
        throw new IllegalArgumentException("Patch target does not exist: " + path);
    }

    private static ParentPointer parentPointer(String path) {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Patch path must be a JSON pointer: " + path);
        }
        int slash = path.lastIndexOf('/');
        String parentPath = slash == 0 ? "" : path.substring(0, slash);
        String segment = JsonPointer.compile(path.substring(slash)).getMatchingProperty();
        return new ParentPointer(JsonPointer.compile(parentPath), segment);
    }

    private static int arrayIndex(String segment, int size, boolean allowEnd) {
        try {
            int index = Integer.parseInt(segment);
            int upperBound = allowEnd ? size : size - 1;
            if (index < 0 || index > upperBound) {
                throw new IllegalArgumentException("Array index out of bounds: " + segment);
            }
            return index;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Array patch segment must be an index: " + segment, e);
        }
    }

    private record ParentPointer(JsonPointer parentPath, String segment) {
    }

    private static Optional<String> forbiddenServerManagedRoot(String path) {
        if (path == null || path.isBlank()) {
            return Optional.of("/");
        }
        if (!path.startsWith("/")) {
            return Optional.empty();
        }
        int end = path.indexOf('/', 1);
        String segment = end < 0 ? path.substring(1) : path.substring(1, end);
        String root = segment.replace("~1", "/").replace("~0", "~");
        return SERVER_MANAGED_ROOTS.contains(root) ? Optional.of(root) : Optional.empty();
    }
}
