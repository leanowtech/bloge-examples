package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain
        .TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation;

import java.util.regex.Pattern;

/**
 * Portable signed v2 response carrying exact external archive proof for every retirement.
 *
 * @param schemaVersion exact response generation
 * @param lifecyclePageId deterministic identity over request and complete v2 page
 * @param pageFingerprint canonical complete-page fingerprint
 * @param page complete receipt-aware lifecycle snapshot page
 * @param attestation detached v2 page and archive-proof closure signature
 */
public record TestSuiteStabilityObservationLedgerLifecycleArchivePageResponse(
        String schemaVersion,
        String lifecyclePageId,
        String pageFingerprint,
        TestSuiteStabilityObservationLedgerLifecycleArchivePage page,
        TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation attestation
) {
    /** Current signed receipt-aware lifecycle-page response generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationLedgerLifecyclePageResponse.v2";
    private static final Pattern PAGE_ID = Pattern.compile(
            "stability-observation-lifecycle-page-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates one syntactically complete signed receipt-aware response. */
    public TestSuiteStabilityObservationLedgerLifecycleArchivePageResponse {
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
                    "Complete signed receipt-aware lifecycle response is required");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
