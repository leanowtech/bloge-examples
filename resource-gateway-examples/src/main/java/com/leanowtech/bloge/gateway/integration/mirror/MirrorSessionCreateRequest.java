package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Strict create command for one isolated stateful mirror session.
 *
 * <p>The caller supplies a sealed simulation aggregate and an idempotency key. The service derives
 * enterprise scope from authenticated identity and rejects any aggregate whose embedded scope
 * differs. The request therefore contains no independent tenant or environment selector.</p>
 *
 * @param schemaVersion create command wire version
 * @param requestId stable create idempotency key inside the authenticated scope
 * @param payload sealed initial encrypted aggregate
 */
public record MirrorSessionCreateRequest(
        String schemaVersion,
        String requestId,
        MirrorSessionPayload payload
) {
    /** Current session-create command version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorSessionCreateRequest.v1";
    private static final Pattern REQUEST_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,255}");

    /** Validates one complete create command. */
    public MirrorSessionCreateRequest {
        schemaVersion = version(schemaVersion);
        requestId = MirrorStateProtocolSupport.required(requestId, "requestId");
        if (!REQUEST_ID.matcher(requestId).matches()) {
            throw new IllegalArgumentException("requestId is invalid");
        }
        payload = Objects.requireNonNull(payload, "payload");
    }

    private static String version(String value) {
        String normalized = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException(
                    "unsupported mirror session create schemaVersion");
        }
        return normalized;
    }
}
