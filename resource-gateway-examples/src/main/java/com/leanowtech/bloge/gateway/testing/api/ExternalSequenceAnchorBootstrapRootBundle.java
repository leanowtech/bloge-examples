package com.leanowtech.bloge.gateway.testing.api;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Complete bounded transition history from deployment-pinned genesis to the current root head.
 *
 * <p>Serving the complete chain allows a restarted process to reconstruct current root trust from
 * genesis without trusting a rollbackable local cache. Bundles are deliberately bounded; reaching
 * the limit requires a planned out-of-band genesis rollover instead of silent history truncation.</p>
 *
 * @param schemaVersion bundle protocol generation
 * @param genesisMaterialFingerprint exact locally pinned genesis identity
 * @param transitions ordered contiguous transition chain
 * @param headMaterialFingerprint exact identity of the final transition
 */
public record ExternalSequenceAnchorBootstrapRootBundle(
        String schemaVersion,
        String genesisMaterialFingerprint,
        List<ExternalSequenceAnchorBootstrapRootTransition> transitions,
        String headMaterialFingerprint) {

    /** Current complete-chain bundle generation. */
    public static final String SCHEMA_VERSION =
            "bloge.externalSequenceAnchorBootstrapRootBundle.v1";
    /** Hard protocol bound for retained ceremony transitions. */
    public static final int MAXIMUM_TRANSITIONS = 128;

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Rejects empty, unbounded, null-bearing, or head-ambiguous bundles. */
    public ExternalSequenceAnchorBootstrapRootBundle {
        schemaVersion = ExternalSequenceAnchorBootstrapRootGenesis.normalized(schemaVersion);
        genesisMaterialFingerprint =
                ExternalSequenceAnchorBootstrapRootGenesis.normalized(
                        genesisMaterialFingerprint);
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
        headMaterialFingerprint =
                ExternalSequenceAnchorBootstrapRootGenesis.normalized(
                        headMaterialFingerprint);
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(genesisMaterialFingerprint).matches()
                || transitions.isEmpty() || transitions.size() > MAXIMUM_TRANSITIONS
                || transitions.stream().anyMatch(java.util.Objects::isNull)
                || !FINGERPRINT.matcher(headMaterialFingerprint).matches()
                || !headMaterialFingerprint.equals(
                transitions.getLast().materialFingerprint())) {
            throw new IllegalArgumentException(
                    "External sequence-anchor bootstrap-root bundle is invalid");
        }
    }
}
