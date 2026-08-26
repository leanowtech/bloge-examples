package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable, schema-guided redaction policy for one governed capture. */
public record WorldDraftRedactionPolicy(
        String policyId,
        List<FieldRule> requestRules,
        List<FieldRule> responseRules,
        String fingerprint
) {
    public static final int MAX_RULES = 256;
    public static final int MAX_POLICY_BYTES = 64 * 1024;

    public enum Action {
        KEEP,
        DROP,
        FIXED_REPLACEMENT,
        FORMAT_PRESERVING_TOKEN
    }

    public record FieldRule(String path, Action action, Object replacement) {
        public FieldRule {
            path = normalizePath(path);
            action = action == null ? null : action;
            if (action == null || (action == Action.FIXED_REPLACEMENT && replacement == null)
                    || (action != Action.FIXED_REPLACEMENT && replacement != null)) {
                throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.INVALID_INPUT);
            }
            try {
                replacement = ProtocolJsonValue.freeze(replacement);
            } catch (RuntimeException invalid) {
                throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.INVALID_INPUT);
            }
        }

        private static String normalizePath(String value) {
            if (value == null || value.isBlank() || !value.startsWith("/")
                    || value.length() > 512 || value.chars().anyMatch(Character::isISOControl)) {
                throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.INVALID_INPUT);
            }
            return value;
        }
    }

    public WorldDraftRedactionPolicy {
        policyId = clean(policyId);
        requestRules = freezeRules(requestRules);
        responseRules = freezeRules(responseRules);
        if (requestRules.size() + responseRules.size() > MAX_RULES) {
            throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.LIMIT_EXCEEDED);
        }
        String computed = VisualBundleFingerprint.fromMaterial(material(policyId, requestRules, responseRules));
        if (fingerprint == null || fingerprint.isBlank()) {
            fingerprint = computed;
        } else if (!computed.equals(fingerprint.trim())) {
            throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.SOURCE_INTEGRITY);
        }
        if (fingerprint.length() > 80) {
            throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.LIMIT_EXCEEDED);
        }
    }

    public WorldDraftRedactionPolicy(String policyId, List<FieldRule> requestRules,
                                     List<FieldRule> responseRules) {
        this(policyId, requestRules, responseRules, null);
    }

    public static WorldDraftRedactionPolicy identity(String policyId) {
        return new WorldDraftRedactionPolicy(policyId, List.of(), List.of());
    }

    public List<FieldRule> rules(boolean request) {
        return request ? requestRules : responseRules;
    }

    /** Deliberately omits replacement values and paths that could carry business data. */
    @Override
    public String toString() {
        return "WorldDraftRedactionPolicy[policyId=" + policyId + ",requestRules=" + requestRules.size()
                + ",responseRules=" + responseRules.size() + ",fingerprint=" + fingerprint + "]";
    }

    private static List<FieldRule> freezeRules(List<FieldRule> source) {
        if (source == null) {
            return List.of();
        }
        List<FieldRule> copy = new ArrayList<>(source);
        Set<String> paths = new java.util.HashSet<>();
        for (FieldRule rule : copy) {
            if (rule == null || !paths.add(rule.path())) {
                throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.INVALID_INPUT);
            }
        }
        copy.sort(java.util.Comparator.comparing(FieldRule::path));
        try {
            if (com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                    .writeValueAsBytes(copy).length > MAX_POLICY_BYTES) {
                throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.LIMIT_EXCEEDED);
            }
        } catch (WorldDraftCandidateException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.INVALID_INPUT);
        }
        return List.copyOf(copy);
    }

    private static Map<String, Object> material(String id, List<FieldRule> request,
                                                 List<FieldRule> response) {
        return Map.of("policyId", id, "requestRules", request, "responseRules", response);
    }

    private static String clean(String value) {
        if (value == null || value.isBlank() || value.length() > 256
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.INVALID_INPUT);
        }
        return value.trim();
    }
}
