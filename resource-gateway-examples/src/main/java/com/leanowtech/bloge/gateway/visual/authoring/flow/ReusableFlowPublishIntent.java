package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.util.Objects;
import java.util.regex.Pattern;

/** Store-ready intent after exact draft resolution and deterministic compilation. */
public record ReusableFlowPublishIntent(AuthoringScope scope, String actorId, String flowId,
                                        String idempotencyKey, String requestFingerprint,
                                        String versionFingerprint, ReusableFlowDraft draft) {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    public ReusableFlowPublishIntent {
        scope = Objects.requireNonNull(scope, "scope");
        actorId = Objects.requireNonNull(actorId, "actorId");
        flowId = Objects.requireNonNull(flowId, "flowId");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        requestFingerprint = Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        versionFingerprint = Objects.requireNonNull(versionFingerprint, "versionFingerprint");
        draft = Objects.requireNonNull(draft, "draft");
        if (!IDENTIFIER.matcher(actorId).matches() || !IDENTIFIER.matcher(flowId).matches()
                || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()
                || !FINGERPRINT.matcher(requestFingerprint).matches()
                || !FINGERPRINT.matcher(versionFingerprint).matches()
                || !flowId.equals(draft.flowId())) {
            throw new IllegalArgumentException("reusable Flow publish intent is invalid");
        }
    }
}
