package com.leanowtech.bloge.gateway.visual.draft;

/**
 * Versioned, canonical input to one idempotent Graph draft save command.
 */
public record GraphDraftSaveCommand(
        String schemaVersion,
        String operation,
        String routeDraftId,
        GraphDraft draft,
        String actor,
        String changeSource,
        String changeSummary,
        String reason) {

    public static final String SCHEMA_VERSION = "bloge.graphDraftSaveCommand.v1";

    public GraphDraftSaveCommand {
        schemaVersion = normalized(schemaVersion).isBlank() ? SCHEMA_VERSION : normalized(schemaVersion);
        operation = normalized(operation).toUpperCase();
        routeDraftId = normalized(routeDraftId);
        actor = normalized(actor);
        changeSource = normalized(changeSource);
        changeSummary = normalized(changeSummary);
        reason = normalized(reason);
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported Graph draft save command schema: " + schemaVersion);
        }
        if (!("CREATE".equals(operation) || "UPDATE".equals(operation))) {
            throw new IllegalArgumentException("Graph draft save operation must be CREATE or UPDATE");
        }
        if (draft == null) {
            throw new IllegalArgumentException("Graph draft save command requires a draft");
        }
        if ("UPDATE".equals(operation) && routeDraftId.isBlank()) {
            throw new IllegalArgumentException("Graph draft update command requires a route draft id");
        }
    }

    public static GraphDraftSaveCommand create(
            GraphDraft draft,
            String actor,
            String changeSource,
            String changeSummary,
            String reason) {
        return new GraphDraftSaveCommand(
                SCHEMA_VERSION, "CREATE", "", draft, actor, changeSource, changeSummary, reason);
    }

    public static GraphDraftSaveCommand update(
            String draftId,
            GraphDraft draft,
            String actor,
            String changeSource,
            String changeSummary,
            String reason) {
        return new GraphDraftSaveCommand(
                SCHEMA_VERSION, "UPDATE", draftId, draft, actor, changeSource, changeSummary, reason);
    }

    public GraphDraftSaveScope scope() {
        return new GraphDraftSaveScope(draft.tenantId(), draft.namespace(), draft.environment());
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
