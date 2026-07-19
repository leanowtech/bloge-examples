package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerLifecyclePage;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationLedgerLifecycleAttestation;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Signs and verifies exact observation-ledger lifecycle pages in an independent domain.
 *
 * <p>The signed material binds the request, starting/terminal/current floors, current head, and
 * ordered retirement identities. The service verifies its own detached signature before returning
 * it so an unavailable or inconsistent signing authority cannot produce a partially trusted page.</p>
 */
public final class TestSuiteStabilityObservationLedgerLifecycleAttestationService {
    /** Stable failure when the page or nested transition closure is invalid. */
    public static final String PAGE_INVALID =
            "STABILITY_OBSERVATION_LEDGER_LIFECYCLE_PAGE_INVALID";
    /** Stable failure when no signing authority is available. */
    public static final String SIGNER_UNAVAILABLE =
            "STABILITY_OBSERVATION_LEDGER_LIFECYCLE_SIGNER_UNAVAILABLE";
    /** Stable failure when the newly generated detached signature cannot be verified. */
    public static final String SIGNATURE_INVALID =
            "STABILITY_OBSERVATION_LEDGER_LIFECYCLE_SIGNATURE_INVALID";

    private final ObjectMapper objectMapper;
    private final VisualEvidenceSigner signer;
    private final Clock clock;

    /**
     * Creates a lifecycle-page signing boundary using current UTC time.
     *
     * @param objectMapper canonical protocol mapper
     * @param signer local or managed Ed25519 evidence signer
     */
    public TestSuiteStabilityObservationLedgerLifecycleAttestationService(
            ObjectMapper objectMapper,
            VisualEvidenceSigner signer) {
        this(objectMapper, signer, Clock.systemUTC());
    }

    TestSuiteStabilityObservationLedgerLifecycleAttestationService(
            ObjectMapper objectMapper,
            VisualEvidenceSigner signer,
            Clock clock) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.signer = signer == null ? VisualEvidenceSigner.unavailable() : signer;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Canonically signs and immediately verifies one complete lifecycle page.
     *
     * @param page canonical database-snapshot lifecycle page
     * @return verified signature or bounded fail-closed reason
     */
    public SealResult seal(TestSuiteStabilityObservationLedgerLifecyclePage page) {
        Objects.requireNonNull(page, "page");
        if (!TestSuiteStabilityObservationLedgerLifecyclePageIntegrity.valid(
                objectMapper, page)) {
            return SealResult.failed(PAGE_INVALID);
        }
        if (!signer.available()) {
            return SealResult.failed(SIGNER_UNAVAILABLE);
        }
        try {
            String pageId = TestSuiteStabilityObservationLedgerLifecyclePageIntegrity
                    .lifecyclePageId(objectMapper, page.requestFingerprint(),
                            page.pageFingerprint());
            List<TestSuiteStabilityObservationLedgerLifecycleAttestation.RetirementRef> refs =
                    TestSuiteStabilityObservationLedgerLifecyclePageIntegrity.retirementRefs(
                            page.retirements());
            Instant signedAt = clock.instant();
            String materialFingerprint = materialFingerprint(pageId, page, refs, signedAt);
            VisualRunEvidenceSeal seal = signer.seal(materialFingerprint);
            TestSuiteStabilityObservationLedgerLifecycleAttestation attestation =
                    new TestSuiteStabilityObservationLedgerLifecycleAttestation(
                            TestSuiteStabilityObservationLedgerLifecycleAttestation.SCHEMA_VERSION,
                            TestSuiteStabilityObservationLedgerLifecycleAttestation.SignatureStatus
                                    .VERIFIED,
                            pageId, page.requestFingerprint(), page.pageFingerprint(),
                            page.scopeFingerprint(), page.startingFloor().floorFingerprint(),
                            page.terminalFloor().floorFingerprint(),
                            page.currentFloor().floorFingerprint(),
                            page.head().headFingerprint(), refs, signedAt,
                            seal.keyId(), seal.algorithm(), seal.signature(), true);
            if (verify(page, attestation) != Verification.VERIFIED) {
                return SealResult.failed(SIGNATURE_INVALID);
            }
            return SealResult.verified(attestation);
        } catch (RuntimeException unavailable) {
            return SealResult.failed(SIGNER_UNAVAILABLE);
        }
    }

    /**
     * Recomputes the complete page closure and detached signature material.
     *
     * @param page exact lifecycle page
     * @param attestation detached lifecycle-page signature
     * @return bounded trust result
     */
    public Verification verify(
            TestSuiteStabilityObservationLedgerLifecyclePage page,
            TestSuiteStabilityObservationLedgerLifecycleAttestation attestation) {
        if (page == null || attestation == null
                || !TestSuiteStabilityObservationLedgerLifecyclePageIntegrity.valid(
                objectMapper, page)) {
            return Verification.INVALID;
        }
        String pageId = TestSuiteStabilityObservationLedgerLifecyclePageIntegrity.lifecyclePageId(
                objectMapper, page.requestFingerprint(), page.pageFingerprint());
        List<TestSuiteStabilityObservationLedgerLifecycleAttestation.RetirementRef> refs =
                TestSuiteStabilityObservationLedgerLifecyclePageIntegrity.retirementRefs(
                        page.retirements());
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
                || !refs.equals(attestation.retirementRefs())
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
            TestSuiteStabilityObservationLedgerLifecyclePage page,
            List<TestSuiteStabilityObservationLedgerLifecycleAttestation.RetirementRef> refs,
            Instant signedAt) {
        return ProtocolFingerprint.of(objectMapper, new SignatureMaterial(
                TestSuiteStabilityObservationLedgerLifecycleAttestation.SCHEMA_VERSION,
                pageId, page.requestFingerprint(), page.pageFingerprint(),
                page.scopeFingerprint(), page.startingFloor().floorFingerprint(),
                page.terminalFloor().floorFingerprint(),
                page.currentFloor().floorFingerprint(), page.head().headFingerprint(),
                refs, signedAt));
    }

    /** Closed verification state separating invalid material from authority outage. */
    public enum Verification {
        /** Complete page and detached signature verified. */
        VERIFIED,
        /** Material, key state, or signature is invalid. */
        INVALID,
        /** Current authority cannot resolve the required key. */
        UNAVAILABLE
    }

    /**
     * Result of one immediate sign-and-verify operation.
     *
     * @param attestation verified attestation; null on failure
     * @param failureCode stable bounded reason; blank on success
     */
    public record SealResult(
            TestSuiteStabilityObservationLedgerLifecycleAttestation attestation,
            String failureCode
    ) {
        /** Normalizes one exclusive success-or-failure result. */
        public SealResult {
            failureCode = failureCode == null ? "" : failureCode.trim();
            if ((attestation == null) == failureCode.isBlank()) {
                throw new IllegalArgumentException(
                        "Lifecycle-page seal result must contain exactly one outcome");
            }
        }

        /** @return whether a detached signature was generated and immediately verified */
        public boolean verified() {
            return attestation != null && failureCode.isBlank();
        }

        /** Creates a successful verified result. */
        public static SealResult verified(
                TestSuiteStabilityObservationLedgerLifecycleAttestation attestation) {
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
            List<TestSuiteStabilityObservationLedgerLifecycleAttestation.RetirementRef>
                    retirementRefs,
            Instant signedAt) {
    }
}
