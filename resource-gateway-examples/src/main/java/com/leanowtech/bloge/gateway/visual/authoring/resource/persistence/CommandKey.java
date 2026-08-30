package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

/** Full scope and actor coordinate for one idempotent authoring command. */
public record CommandKey(AuthoringScope scope, String actorId, AuthoringEndpoint endpoint,
                         String targetId, String idempotencyKey) {
    /** Validates the coordinate without interpreting its request payload. */
    public CommandKey {
        if (scope == null || endpoint == null) throw new IllegalArgumentException("scope and endpoint are required");
        require(actorId, "actorId");
        require(targetId, "targetId");
        require(idempotencyKey, "idempotencyKey");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256) throw new IllegalArgumentException(name + " is invalid");
    }
}
