package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationLedgerLifecycleAttestation;

import java.util.regex.Pattern;

/**
 * Portable signed response for one exact-suite observation-ledger lifecycle page.
 *
 * @param schemaVersion exact response generation
 * @param lifecyclePageId deterministic identity over request and complete page
 * @param pageFingerprint canonical complete-page fingerprint
 * @param page complete lifecycle snapshot page
 * @param attestation detached page and ordered-retirement closure signature
 */
public record TestSuiteStabilityObservationLedgerLifecyclePageResponse(
        String schemaVersion,
        String lifecyclePageId,
        String pageFingerprint,
        TestSuiteStabilityObservationLedgerLifecyclePage page,
        TestSuiteStabilityObservationLedgerLifecycleAttestation attestation
) {
    /** Current signed lifecycle-page response generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationLedgerLifecyclePageResponse.v1";
    private static final Pattern PAGE_ID = Pattern.compile(
            "stability-observation-lifecycle-page-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates one syntactically complete signed response. */
    public TestSuiteStabilityObservationLedgerLifecyclePageResponse {
        schemaVersion = normalized(schemaVersion);
        lifecyclePageId = normalized(lifecyclePageId);
        pageFingerprint = normalized(pageFingerprint);
        if (!SCHEMA_VERSION.equals(schemaVersion) || !PAGE_ID.matcher(lifecyclePageId).matches()
                || !FINGERPRINT.matcher(pageFingerprint).matches()
                || page == null || attestation == null
                || !pageFingerprint.equals(page.pageFingerprint())
                || !lifecyclePageId.equals(attestation.lifecyclePageId())
                || !pageFingerprint.equals(attestation.pageFingerprint())) {
            throw new IllegalArgumentException(
                    "Complete signed observation-ledger lifecycle response is required");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
