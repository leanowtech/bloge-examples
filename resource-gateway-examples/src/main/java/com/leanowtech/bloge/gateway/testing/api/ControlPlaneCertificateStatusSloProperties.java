package com.leanowtech.bloge.gateway.testing.api;

/**
 * Finite local SLO policy for certificate-status refresh and request admission.
 *
 * <p>The policy controls alert maturity and current-state thresholds only. It contains no
 * endpoint, target, certificate, authority, tenant, or credential dimensions, so the resulting
 * health and metrics remain fixed-cardinality.</p>
 *
 * @param startupGraceSeconds grace before first-publication violations become actionable
 * @param maximumRefreshSuccessAgeSeconds maximum age of the last successful refresh
 * @param minimumExpiryHeadroomSeconds minimum remaining signed admission lifetime
 * @param minimumRefreshSamples samples required before refresh-ratio enforcement
 * @param maximumRefreshFailureBasisPoints accepted mature refresh failure ratio
 * @param minimumAdmissionSamples samples required before admission-ratio enforcement
 * @param maximumAdmissionDenialBasisPoints accepted mature admission denial ratio
 * @param maximumSourceHeadLag maximum accepted exact publication backlog
 */
public record ControlPlaneCertificateStatusSloProperties(
        Long startupGraceSeconds,
        Long maximumRefreshSuccessAgeSeconds,
        Long minimumExpiryHeadroomSeconds,
        Integer minimumRefreshSamples,
        Integer maximumRefreshFailureBasisPoints,
        Integer minimumAdmissionSamples,
        Integer maximumAdmissionDenialBasisPoints,
        Integer maximumSourceHeadLag) {

    private static final long DEFAULT_STARTUP_GRACE_SECONDS = 60L;
    private static final long DEFAULT_MAXIMUM_REFRESH_SUCCESS_AGE_SECONDS = 120L;
    private static final long DEFAULT_MINIMUM_EXPIRY_HEADROOM_SECONDS = 60L;
    private static final int DEFAULT_MINIMUM_REFRESH_SAMPLES = 20;
    private static final int DEFAULT_MAXIMUM_REFRESH_FAILURE_BASIS_POINTS = 500;
    private static final int DEFAULT_MINIMUM_ADMISSION_SAMPLES = 100;
    private static final int DEFAULT_MAXIMUM_ADMISSION_DENIAL_BASIS_POINTS = 1_000;
    private static final int DEFAULT_MAXIMUM_SOURCE_HEAD_LAG = 0;

    /** Applies bounded defaults and delegates exact range validation to the SLO policy. */
    public ControlPlaneCertificateStatusSloProperties {
        startupGraceSeconds = startupGraceSeconds == null
                ? DEFAULT_STARTUP_GRACE_SECONDS : startupGraceSeconds;
        maximumRefreshSuccessAgeSeconds = maximumRefreshSuccessAgeSeconds == null
                ? DEFAULT_MAXIMUM_REFRESH_SUCCESS_AGE_SECONDS
                : maximumRefreshSuccessAgeSeconds;
        minimumExpiryHeadroomSeconds = minimumExpiryHeadroomSeconds == null
                ? DEFAULT_MINIMUM_EXPIRY_HEADROOM_SECONDS : minimumExpiryHeadroomSeconds;
        minimumRefreshSamples = minimumRefreshSamples == null
                ? DEFAULT_MINIMUM_REFRESH_SAMPLES : minimumRefreshSamples;
        maximumRefreshFailureBasisPoints = maximumRefreshFailureBasisPoints == null
                ? DEFAULT_MAXIMUM_REFRESH_FAILURE_BASIS_POINTS
                : maximumRefreshFailureBasisPoints;
        minimumAdmissionSamples = minimumAdmissionSamples == null
                ? DEFAULT_MINIMUM_ADMISSION_SAMPLES : minimumAdmissionSamples;
        maximumAdmissionDenialBasisPoints = maximumAdmissionDenialBasisPoints == null
                ? DEFAULT_MAXIMUM_ADMISSION_DENIAL_BASIS_POINTS
                : maximumAdmissionDenialBasisPoints;
        maximumSourceHeadLag = maximumSourceHeadLag == null
                ? DEFAULT_MAXIMUM_SOURCE_HEAD_LAG : maximumSourceHeadLag;
        policy(startupGraceSeconds, maximumRefreshSuccessAgeSeconds,
                minimumExpiryHeadroomSeconds, minimumRefreshSamples,
                maximumRefreshFailureBasisPoints, minimumAdmissionSamples,
                maximumAdmissionDenialBasisPoints, maximumSourceHeadLag);
    }

    /** @return validated runtime policy */
    public ControlPlaneCertificateStatusSloMonitor.Policy policy() {
        return policy(startupGraceSeconds, maximumRefreshSuccessAgeSeconds,
                minimumExpiryHeadroomSeconds, minimumRefreshSamples,
                maximumRefreshFailureBasisPoints, minimumAdmissionSamples,
                maximumAdmissionDenialBasisPoints, maximumSourceHeadLag);
    }

    /** @return canonical default policy */
    public static ControlPlaneCertificateStatusSloProperties defaults() {
        return new ControlPlaneCertificateStatusSloProperties(null, null, null, null,
                null, null, null, null);
    }

    boolean configured() {
        return startupGraceSeconds != DEFAULT_STARTUP_GRACE_SECONDS
                || maximumRefreshSuccessAgeSeconds
                != DEFAULT_MAXIMUM_REFRESH_SUCCESS_AGE_SECONDS
                || minimumExpiryHeadroomSeconds
                != DEFAULT_MINIMUM_EXPIRY_HEADROOM_SECONDS
                || minimumRefreshSamples != DEFAULT_MINIMUM_REFRESH_SAMPLES
                || maximumRefreshFailureBasisPoints
                != DEFAULT_MAXIMUM_REFRESH_FAILURE_BASIS_POINTS
                || minimumAdmissionSamples != DEFAULT_MINIMUM_ADMISSION_SAMPLES
                || maximumAdmissionDenialBasisPoints
                != DEFAULT_MAXIMUM_ADMISSION_DENIAL_BASIS_POINTS
                || maximumSourceHeadLag != DEFAULT_MAXIMUM_SOURCE_HEAD_LAG;
    }

    private static ControlPlaneCertificateStatusSloMonitor.Policy policy(
            long startupGraceSeconds,
            long maximumRefreshSuccessAgeSeconds,
            long minimumExpiryHeadroomSeconds,
            int minimumRefreshSamples,
            int maximumRefreshFailureBasisPoints,
            int minimumAdmissionSamples,
            int maximumAdmissionDenialBasisPoints,
            int maximumSourceHeadLag) {
        return new ControlPlaneCertificateStatusSloMonitor.Policy(
                startupGraceSeconds, maximumRefreshSuccessAgeSeconds,
                minimumExpiryHeadroomSeconds, minimumRefreshSamples,
                maximumRefreshFailureBasisPoints, minimumAdmissionSamples,
                maximumAdmissionDenialBasisPoints, maximumSourceHeadLag);
    }
}
