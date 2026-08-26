package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.testing.domain.ReplayPayloadRef;

import java.util.regex.Pattern;

/** Exact, governed source address accepted by World draft capture. */
public record WorldDraftSourceRef(
        Kind kind,
        String tenantId,
        String sourceId,
        long revision,
        String fingerprint
) {
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,511}");

    public enum Kind {
        REPLAY_PAYLOAD,
        CAPABILITY_CORPUS_TRAJECTORY,
        GOLDEN_CAPTURE,
        RUN_EVIDENCE_PAYLOAD
    }

    public WorldDraftSourceRef {
        kind = kind == null ? null : kind;
        tenantId = clean(tenantId);
        sourceId = clean(sourceId);
        fingerprint = clean(fingerprint);
        if (kind == null || tenantId.isBlank() || tenantId.length() > 255
                || !ID.matcher(sourceId).matches() || revision <= 0
                || !FINGERPRINT.matcher(fingerprint).matches()) {
            throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.INVALID_INPUT);
        }
    }

    public static WorldDraftSourceRef replay(String tenantId, ReplayPayloadRef ref) {
        if (ref == null) {
            throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.INVALID_INPUT);
        }
        return new WorldDraftSourceRef(Kind.REPLAY_PAYLOAD, tenantId, ref.replayPayloadId(),
                ref.revision(), ref.fingerprint());
    }

    public static WorldDraftSourceRef exact(Kind kind, String tenantId, String sourceId,
                                            long revision, String fingerprint) {
        return new WorldDraftSourceRef(kind, tenantId, sourceId, revision, fingerprint);
    }

    private static String clean(String value) {
        if (value == null || value.chars().anyMatch(Character::isISOControl)) {
            throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.INVALID_INPUT);
        }
        return value.trim();
    }
}
