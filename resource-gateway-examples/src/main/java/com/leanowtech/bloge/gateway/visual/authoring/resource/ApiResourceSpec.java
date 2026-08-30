package com.leanowtech.bloge.gateway.visual.authoring.resource;

/** Immutable authoritative API Resource revision and its exact subject reference. */
public record ApiResourceSpec(
        String resourceId,
        String resolvedConnectionId,
        int revision,
        String fingerprint,
        ApiResourceCommand command
) {
    public ApiResourceSpec {
        command = command == null ? null : command.copy();
    }

    /** @return independent command snapshot */
    @Override
    public ApiResourceCommand command() {
        return command == null ? null : command.copy();
    }

    /** Exact coordinate used by composable and simulation protocols. */
    public record ResourceRef(String kind, String resourceId, int revision, String fingerprint) {
    }

    /** @return exact API_RESOURCE reference for this revision */
    public ResourceRef ref() {
        return new ResourceRef("API_RESOURCE", resourceId, revision, fingerprint);
    }
}
