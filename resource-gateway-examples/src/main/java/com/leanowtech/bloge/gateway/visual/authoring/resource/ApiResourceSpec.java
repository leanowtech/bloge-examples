package com.leanowtech.bloge.gateway.visual.authoring.resource;

import java.util.List;

/**
 * Frozen, flattened authoritative API Resource revision. Runtime descriptors
 * and UI projections are derived from this shape; no command wrapper is kept.
 *
 * @param schemaVersion stable wire schema identifier
 * @param resourceId stable resource identifier
 * @param revision one-based optimistic-concurrency revision
 * @param fingerprint canonical content fingerprint
 * @param displayName human-readable resource name
 * @param description optional authoring description
 * @param connectionId exact existing connection identity
 * @param operation HTTP operation and bindings
 * @param contract input and output JSON Schema envelopes
 * @param response response success and output extraction contract
 * @param effect side-effect policy
 * @param examples immutable request/response examples
 * @param status lifecycle state, initially {@code DRAFT}
 */
public record ApiResourceSpec(
        String schemaVersion,
        String resourceId,
        int revision,
        String fingerprint,
        String displayName,
        String description,
        String connectionId,
        ApiResourceCommand.Operation operation,
        ApiResourceCommand.Contract contract,
        ApiResourceCommand.Response response,
        ApiResourceCommand.Effect effect,
        List<ApiResourceCommand.Example> examples,
        String status
) {
    public static final String SCHEMA_VERSION = "bloge.apiResourceSpec.v1";
    public static final String DRAFT = "DRAFT";

    public ApiResourceSpec {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        status = status == null ? DRAFT : status;
        ApiResourceCommand snapshot = new ApiResourceCommand(displayName, description, operation, contract,
                response, effect, examples);
        displayName = snapshot.displayName();
        description = snapshot.description();
        operation = snapshot.operation();
        contract = snapshot.contract();
        response = snapshot.response();
        effect = snapshot.effect();
        examples = snapshot.examples();
    }

    @Override
    public ApiResourceCommand.Operation operation() {
        return new ApiResourceCommand(displayName, description, operation, contract, response, effect, examples)
                .operation();
    }

    @Override
    public ApiResourceCommand.Contract contract() {
        return new ApiResourceCommand(displayName, description, operation, contract, response, effect, examples)
                .contract();
    }

    @Override
    public ApiResourceCommand.Response response() {
        return new ApiResourceCommand(displayName, description, operation, contract, response, effect, examples)
                .response();
    }

    @Override
    public ApiResourceCommand.Effect effect() {
        return new ApiResourceCommand(displayName, description, operation, contract, response, effect, examples)
                .effect();
    }

    @Override
    public List<ApiResourceCommand.Example> examples() {
        return new ApiResourceCommand(displayName, description, operation, contract, response, effect, examples)
                .examples();
    }

    /** Exact coordinate used by composable and simulation protocols. */
    public record ResourceRef(String kind, String resourceId, int revision, String fingerprint) {
    }

    /** @return exact API_RESOURCE reference for this revision */
    public ResourceRef ref() {
        return new ResourceRef("API_RESOURCE", resourceId, revision, fingerprint);
    }
}
