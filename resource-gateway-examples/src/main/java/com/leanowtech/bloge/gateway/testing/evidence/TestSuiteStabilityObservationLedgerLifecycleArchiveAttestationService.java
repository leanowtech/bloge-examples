package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api
        .TestSuiteStabilityObservationLedgerLifecycleArchivePage;
import com.leanowtech.bloge.gateway.testing.domain
        .TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Signs and verifies receipt-aware lifecycle pages in a distinct v2 signature domain. */
public final class TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationService {
    /** Stable failure when v2 page or nested external proof closure is invalid. */
    public static final String PAGE_INVALID =
            "STABILITY_OBSERVATION_LEDGER_LIFECYCLE_ARCHIVE_PAGE_INVALID";
    /** Stable failure when no v2 signing authority is available. */
    public static final String SIGNER_UNAVAILABLE =
            "STABILITY_OBSERVATION_LEDGER_LIFECYCLE_ARCHIVE_SIGNER_UNAVAILABLE";
    /** Stable failure when a newly generated v2 signature cannot be verified. */
    public static final String SIGNATURE_INVALID =
            "STABILITY_OBSERVATION_LEDGER_LIFECYCLE_ARCHIVE_SIGNATURE_INVALID";

    private final ObjectMapper objectMapper;
    private final VisualEvidenceSigner signer;
    private final Clock clock;

    /**
     * Creates a receipt-aware lifecycle signing boundary using current UTC time.
     *
     * @param objectMapper canonical protocol mapper
     * @param signer local or managed Ed25519 evidence signer
     */
    public TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationService(
            ObjectMapper objectMapper,
            VisualEvidenceSigner signer) {
        this(objectMapper, signer, Clock.systemUTC());
    }

    TestSuiteStabilityObservationLedgerLifecycleArchiveAttestationService(
            ObjectMapper objectMapper,
            VisualEvidenceSigner signer,
            Clock clock) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.signer = signer == null ? VisualEvidenceSigner.unavailable() : signer;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Canonically signs and immediately verifies one complete receipt-aware lifecycle page.
     *
     * @param page canonical v2 lifecycle page
     * @return verified signature or bounded fail-closed reason
     */
    public SealResult seal(TestSuiteStabilityObservationLedgerLifecycleArchivePage page) {
        Objects.requireNonNull(page, "page");
        if (!TestSuiteStabilityObservationLedgerLifecycleArchivePageIntegrity.valid(
                objectMapper, page)) {
            return SealResult.failed(PAGE_INVALID);
        }
        if (!signer.available()) {
            return SealResult.failed(SIGNER_UNAVAILABLE);
        }
        try {
            String pageId = TestSuiteStabilityObservationLedgerLifecycleArchivePageIntegrity
                    .lifecyclePageId(objectMapper, page.requestFingerprint(),
                            page.pageFingerprint());
            List<TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation.ArchiveRef> refs =
                    TestSuiteStabilityObservationLedgerLifecycleArchivePageIntegrity.archiveRefs(
                            page.retirements(), page.externalArchiveReceiptSets());
            Instant signedAt = clock.instant();
            String materialFingerprint = materialFingerprint(pageId, page, refs, signedAt);
            VisualRunEvidenceSeal seal = signer.seal(materialFingerprint);
            TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation attestation =
                    new TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation(
                            TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation
                                    .SCHEMA_VERSION,
                            TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation
                                    .SignatureStatus.VERIFIED,
                            pageId, page.requestFingerprint(), page.pageFingerprint(),
                            page.scopeFingerprint(), page.startingFloor().floorFingerprint(),
                            page.terminalFloor().floorFingerprint(),
                            page.currentFloor().floorFingerprint(),
                            page.head().headFingerprint(), refs, signedAt, seal.keyId(),
                            seal.algorithm(), seal.signature(), true);
            if (verify(page, attestation) != Verification.VERIFIED) {
                return SealResult.failed(SIGNATURE_INVALID);
            }
            return SealResult.verified(attestation);
        } catch (RuntimeException unavailable) {
            return SealResult.failed(SIGNER_UNAVAILABLE);
        }
    }

    /**
     * Recomputes the complete v2 page and archive-reference signature material.
     *
     * @param page exact receipt-aware lifecycle page
     * @param attestation detached v2 signature
     * @return bounded trust result
     */
    public Verification verify(
            TestSuiteStabilityObservationLedgerLifecycleArchivePage page,
            TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation attestation) {
        if (page == null || attestation == null
                || !TestSuiteStabilityObservationLedgerLifecycleArchivePageIntegrity.valid(
                objectMapper, page)) {
            return Verification.INVALID;
        }
        String pageId = TestSuiteStabilityObservationLedgerLifecycleArchivePageIntegrity
                .lifecyclePageId(objectMapper, page.requestFingerprint(),
                        page.pageFingerprint());
        List<TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation.ArchiveRef> refs =
                TestSuiteStabilityObservationLedgerLifecycleArchivePageIntegrity.archiveRefs(
                        page.retirements(), page.externalArchiveReceiptSets());
        if (!attestation.independentlyVerifiable()
                || !pageId.equals(attestation.lifecyclePageId())
                || !page.requestFingerprint().equals(attestation.requestFingerprint())
                || !page.pageFingerprint().equals(attestation.pageFingerprint())
                || !page.scopeFingerprint().equals(attestation.scopeFingerprint())
                || !page.startingFloor().floorFingerprint().equals(
                attestation.startingFloorFingerprint())
                || !page.terminalFloor().floorFingerprint().equals(
                attestation.terminalFloorFingerprint())
                || !page.currentFloor().floorFingerprint().equals(
                attestation.currentFloorFingerprint())
                || !page.head().headFingerprint().equals(attestation.headFingerprint())
                || !refs.equals(attestation.archiveRefs())
                || attestation.signedAt().isBefore(page.observedAt())) {
            return Verification.INVALID;
        }
        if (!signer.available()) {
            return Verification.UNAVAILABLE;
        }
        try {
            VisualEvidenceSigner.KeyResolution resolution = signer.resolveKey(attestation.keyId());
            if (resolution.status() != VisualEvidenceSigner.KeyResolutionStatus.AVAILABLE) {
                return Verification.UNAVAILABLE;
            }
            if (resolution.key() == null
                    || !attestation.algorithm().equals(resolution.key().algorithm())
                    || !List.of("ACTIVE", "RETIRED").contains(resolution.key().state())) {
                return Verification.INVALID;
            }
            String materialFingerprint = materialFingerprint(
                    pageId, page, refs, attestation.signedAt());
            VisualEvidenceSigner.Verification verification = signer.verify(
                    new VisualRunEvidenceSeal("", materialFingerprint,
                            attestation.algorithm(), attestation.keyId(),
                            attestation.signedAt(), attestation.signature()),
                    materialFingerprint);
            if (verification.valid()) {
                return Verification.VERIFIED;
            }
            return "KEY_UNAVAILABLE".equals(verification.status())
                    || "UNAVAILABLE".equals(verification.status())
                    ? Verification.UNAVAILABLE : Verification.INVALID;
        } catch (RuntimeException unavailable) {
            return Verification.UNAVAILABLE;
        }
    }

    private String materialFingerprint(
            String pageId,
            TestSuiteStabilityObservationLedgerLifecycleArchivePage page,
            List<TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation.ArchiveRef> refs,
            Instant signedAt) {
        return ProtocolFingerprint.of(objectMapper, new SignatureMaterial(
                TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation.SCHEMA_VERSION,
                pageId, page.requestFingerprint(), page.pageFingerprint(),
                page.scopeFingerprint(), page.startingFloor().floorFingerprint(),
                page.terminalFloor().floorFingerprint(),
                page.currentFloor().floorFingerprint(), page.head().headFingerprint(),
                refs, signedAt));
    }

    /** Closed verification state separating invalid material from authority outage. */
    public enum Verification {
        /** Complete v2 page and detached signature verified. */
        VERIFIED,
        /** Material, key state, or signature is invalid. */
        INVALID,
        /** Current authority cannot resolve the required key. */
        UNAVAILABLE
    }

    /**
     * Result of one immediate v2 sign-and-verify operation.
     *
     * @param attestation verified attestation; null on failure
     * @param failureCode stable bounded reason; blank on success
     */
    public record SealResult(
            TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation attestation,
            String failureCode
    ) {
        /** Normalizes one exclusive success-or-failure result. */
        public SealResult {
            failureCode = failureCode == null ? "" : failureCode.trim();
            if ((attestation == null) == failureCode.isBlank()) {
                throw new IllegalArgumentException(
                        "Receipt-aware lifecycle seal result must contain exactly one outcome");
            }
        }

        /** @return whether a detached v2 signature was generated and immediately verified */
        public boolean verified() {
            return attestation != null && failureCode.isBlank();
        }

        /** Creates a successful verified v2 result. */
        public static SealResult verified(
                TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation attestation) {
            return new SealResult(Objects.requireNonNull(attestation, "attestation"), "");
        }

        /** Creates a fail-closed result without partial signature material. */
        public static SealResult failed(String failureCode) {
            return new SealResult(null, Objects.requireNonNull(failureCode, "failureCode"));
        }
    }

    private record SignatureMaterial(
            String schemaVersion,
            String lifecyclePageId,
            String requestFingerprint,
            String pageFingerprint,
            String scopeFingerprint,
            String startingFloorFingerprint,
            String terminalFloorFingerprint,
            String currentFloorFingerprint,
            String headFingerprint,
            List<TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation.ArchiveRef>
                    archiveRefs,
            Instant signedAt) {
    }
}
