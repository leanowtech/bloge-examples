package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.regex.Pattern;

/**
 * Fail-closed command-line verifier for formal Capability Studio Stage Acceptance Result v2.
 *
 * <p>The CLI accepts one result path and discovers exactly one deployment-owned
 * {@link CapabilityStudioStageAcceptanceAuthorityProvider}. It validates the local protocol before
 * loading that provider, then verifies deployment-owned lifecycle and target admission before
 * any post-run authority call. Malformed and non-{@code PASS} results make no external authority
 * calls. The result document cannot carry or select its own trust provider. Immediately before
 * acceptance, the CLI requires the deployment lease Authority to atomically consume or fence the
 * exact lease request. The mounted bundle loader does not consume leases or self-prove lifecycle
 * currentness. Output reason codes are a closed CLI-owned vocabulary; Provider reason text is
 * never emitted.</p>
 */
public final class CapabilityStudioStageAcceptanceCli {
    /** Exit code for a formally accepted result. */
    public static final int EXIT_ACCEPTED = 0;
    /** Exit code for usage, read, protocol, or provider configuration failure. */
    public static final int EXIT_INVALID = 2;
    /** Exit code for a valid result that is blocked, rejected, or not declared PASS. */
    public static final int EXIT_NOT_ACCEPTED = 3;

    private static final String CODE_PREFIX = "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_CLI.";
    private static final String REASON_PROTOCOL_INVALID = CODE_PREFIX + "PROTOCOL_INVALID";
    private static final String REASON_STATUS_NOT_PASS = CODE_PREFIX + "STATUS_NOT_PASS";
    private static final String REASON_FORMAL_TARGET_BINDING_UNAVAILABLE =
            CODE_PREFIX + "FORMAL_TARGET_BINDING_UNAVAILABLE";
    private static final String REASON_TRUSTED_CLOCK_UNAVAILABLE =
            CODE_PREFIX + "TRUSTED_CLOCK_UNAVAILABLE";
    private static final String REASON_ADMISSION_LIFECYCLE_REJECTED =
            CODE_PREFIX + "ADMISSION_LIFECYCLE_REJECTED";
    private static final String REASON_ADMISSION_LIFECYCLE_UNAVAILABLE =
            CODE_PREFIX + "ADMISSION_LIFECYCLE_UNAVAILABLE";
    private static final String REASON_TARGET_BINDING_REJECTED =
            CODE_PREFIX + "TARGET_BINDING_REJECTED";
    private static final String REASON_TARGET_BINDING_UNAVAILABLE =
            CODE_PREFIX + "TARGET_BINDING_UNAVAILABLE";
    private static final String REASON_AUTHORITY_REJECTED =
            CODE_PREFIX + "AUTHORITY_REJECTED";
    private static final String REASON_AUTHORITY_UNAVAILABLE =
            CODE_PREFIX + "AUTHORITY_UNAVAILABLE";
    private static final String REASON_AUTHORITY_PROTOCOL_INVALID =
            CODE_PREFIX + "AUTHORITY_PROTOCOL_INVALID";
    private static final String REASON_EXECUTION_LEASE_REJECTED =
            CODE_PREFIX + "EXECUTION_LEASE_REJECTED";
    private static final String REASON_EXECUTION_LEASE_UNAVAILABLE =
            CODE_PREFIX + "EXECUTION_LEASE_UNAVAILABLE";
    private static final String REASON_ACCEPTED = CODE_PREFIX + "ACCEPTED";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern AUTHORITY_BINDING_FINGERPRINT =
            Pattern.compile("sha256:[0-9a-f]{64}");
    private static final String EXPECTED_AUTHORITY_BINDING_ENV =
            "BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT";

    private CapabilityStudioStageAcceptanceCli() {
    }

    /**
     * Revalidates one result with an independently deployed authority provider.
     *
     * @param args exactly one Stage Acceptance Result v2 path
     * @param out payload-free result stream
     * @param err reserved payload-free error stream
     * @return {@link #EXIT_ACCEPTED}, {@link #EXIT_INVALID}, or {@link #EXIT_NOT_ACCEPTED}
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, Instant.now(), CapabilityStudioStageAcceptanceCli::providers);
    }

    /**
     * Standard process entry point.
     *
     * @param args exactly one Stage Acceptance Result v2 path
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(
            String[] args,
            PrintStream out,
            PrintStream err,
            Instant now,
            ProviderSource providerSource) {
        return run(args, out, err, now, providerSource,
                System.getenv(EXPECTED_AUTHORITY_BINDING_ENV));
    }

    static int run(
            String[] args,
            PrintStream out,
            PrintStream err,
            Instant now,
            ProviderSource providerSource,
            String expectedAuthorityBindingFingerprint) {
        PrintStream safeOut = out == null ? System.out : out;
        Objects.requireNonNull(now, "now is required");
        if (args == null || args.length != 1 || blank(args[0])) {
            invalid(safeOut, "USAGE");
            return EXIT_INVALID;
        }

        byte[] wire;
        try {
            wire = CapabilityStudioBoundedFileReader.read(Path.of(args[0]),
                    CapabilityStudioStageAcceptanceResultV2Verifier.MAXIMUM_RESULT_BYTES);
        } catch (RuntimeException failure) {
            wire = null;
        }
        if (wire == null) {
            invalid(safeOut, "READ");
            return EXIT_INVALID;
        }
        byte[] originalStageResultBytes = wire;
        String stageResultRawFingerprint = sha256(originalStageResultBytes);

        CapabilityStudioStageAcceptanceResultV2Verifier semanticVerifier =
                new CapabilityStudioStageAcceptanceResultV2Verifier();
        CapabilityStudioStageAcceptanceResultV2Verifier.VerificationResult semantic;
        try {
            semantic = semanticVerifier.verify(wire, now);
        } catch (RuntimeException failure) {
            invalid(safeOut, "PROTOCOL_INVALID");
            return EXIT_INVALID;
        }
        if (!semantic.verified()) {
            safeOut.println("INVALID outcome=PROTOCOL_INVALID reasonCode="
                    + REASON_PROTOCOL_INVALID);
            return EXIT_INVALID;
        }

        JsonNode result;
        try {
            result = JSON.readTree(wire);
        } catch (IOException | RuntimeException failure) {
            invalid(safeOut, "PROTOCOL_INVALID");
            return EXIT_INVALID;
        }
        if (!"PASS".equals(result.path("status").textValue())) {
            safeOut.println("NOT_ACCEPTED outcome=NOT_ACCEPTED reasonCode="
                    + REASON_STATUS_NOT_PASS);
            return EXIT_NOT_ACCEPTED;
        }
        if (!AUTHORITY_BINDING_FINGERPRINT.matcher(
                String.valueOf(expectedAuthorityBindingFingerprint)).matches()) {
            invalid(safeOut, "EXPECTED_AUTHORITY_BINDING_INVALID");
            return EXIT_INVALID;
        }

        CapabilityStudioStageAcceptanceAuthorityProvider provider;
        try {
            provider = CapabilityStudioProviderOutputIsolation.call(
                    () -> loadProvider(providerSource));
        } catch (CapabilityStudioStageAcceptanceAuthorityProvider
                 .DeploymentUnavailableException unavailable) {
            blocked(safeOut, REASON_FORMAL_TARGET_BINDING_UNAVAILABLE);
            return EXIT_NOT_ACCEPTED;
        } catch (RuntimeException failure) {
            invalid(safeOut, "PROVIDER_CONFIGURATION");
            return EXIT_INVALID;
        }
        if (provider == null) {
            invalid(safeOut, "PROVIDER_CONFIGURATION");
            return EXIT_INVALID;
        }

        CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetBoundAuthorityBinding binding;
        try {
            binding = CapabilityStudioProviderOutputIsolation.call(
                    provider::formalTargetBoundAuthorityBinding);
        } catch (CapabilityStudioStageAcceptanceAuthorityProvider
                 .DeploymentUnavailableException unavailable) {
            blocked(safeOut, REASON_FORMAL_TARGET_BINDING_UNAVAILABLE);
            return EXIT_NOT_ACCEPTED;
        } catch (RuntimeException failure) {
            invalid(safeOut, "PROVIDER_CONFIGURATION");
            return EXIT_INVALID;
        }
        if (binding == null) {
            blocked(safeOut, REASON_FORMAL_TARGET_BINDING_UNAVAILABLE);
            return EXIT_NOT_ACCEPTED;
        }

        CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding authorityBinding =
                binding.authorityBinding();
        CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetAdmissionBinding
                targetAdmission = binding.targetAdmissionBinding();
        CapabilityStudioStageAcceptanceAuthorityProvider.DeploymentAdmissionAuthorityBinding
                deploymentAuthority = targetAdmission.deploymentAuthorityBinding();
        try {
            String aggregate = CapabilityStudioStageAcceptanceAuthorityProvider
                    .FormalTargetBoundAuthorityBinding.aggregateFingerprint(
                            CapabilityStudioStageAcceptanceAuthorityProvider
                                    .FormalTargetBoundAuthorityBinding.MESSAGE_VERSION,
                            authorityBinding.fingerprint(),
                            deploymentAuthority.fingerprint(),
                            targetAdmission.targetAdmissionMaterialFingerprint(),
                            targetAdmission.targetRawFingerprint(),
                            targetAdmission.targetCanonicalFingerprint());
            if (!AUTHORITY_BINDING_FINGERPRINT.matcher(binding.fingerprint()).matches()
                    || !aggregate.equals(binding.fingerprint())
                    || !expectedAuthorityBindingFingerprint.equals(binding.fingerprint())) {
                throw new IllegalArgumentException("authority binding fingerprint does not match pin");
            }
        } catch (RuntimeException failure) {
            invalid(safeOut, "PROVIDER_CONFIGURATION");
            return EXIT_INVALID;
        }

        Instant trustedVerificationTime;
        if (deploymentAuthority.trustedClock() == null) {
            blocked(safeOut, REASON_TRUSTED_CLOCK_UNAVAILABLE);
            return EXIT_NOT_ACCEPTED;
        }
        try {
            trustedVerificationTime = CapabilityStudioProviderOutputIsolation.call(
                    deploymentAuthority.trustedClock()::verificationTime);
            if (trustedVerificationTime == null) {
                throw new IllegalStateException("trusted clock returned null");
            }
        } catch (CapabilityStudioStageAcceptanceAuthorityProvider
                 .DeploymentUnavailableException unavailable) {
            blocked(safeOut, REASON_TRUSTED_CLOCK_UNAVAILABLE);
            return EXIT_NOT_ACCEPTED;
        } catch (RuntimeException failure) {
            invalid(safeOut, "PROVIDER_CONFIGURATION");
            return EXIT_INVALID;
        }

        CapabilityStudioStageAcceptanceAuthorityProvider.DeploymentAuthorityDecision
                lifecycleDecision;
        if (deploymentAuthority.lifecycleAuthority() == null) {
            blocked(safeOut, REASON_ADMISSION_LIFECYCLE_UNAVAILABLE);
            return EXIT_NOT_ACCEPTED;
        }
        try {
            var lifecycleRequest = new CapabilityStudioStageAcceptanceAuthorityProvider
                    .AdmissionLifecycleRequest(targetAdmission.lifecycleMaterial(),
                    binding.fingerprint(), targetAdmission.targetRawFingerprint(),
                    targetAdmission.targetCanonicalFingerprint(),
                    deploymentAuthority.fingerprint(), trustedVerificationTime);
            lifecycleDecision = CapabilityStudioProviderOutputIsolation.call(
                    () -> deploymentAuthority.lifecycleAuthority().verify(lifecycleRequest));
        } catch (CapabilityStudioStageAcceptanceAuthorityProvider
                 .DeploymentUnavailableException unavailable) {
            blocked(safeOut, REASON_ADMISSION_LIFECYCLE_UNAVAILABLE);
            return EXIT_NOT_ACCEPTED;
        } catch (RuntimeException failure) {
            invalid(safeOut, "PROVIDER_CONFIGURATION");
            return EXIT_INVALID;
        }
        int lifecycleExit = deploymentDecision(safeOut, lifecycleDecision);
        if (lifecycleExit != EXIT_ACCEPTED) {
            return lifecycleExit;
        }

        CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationResult targetVerification;
        try {
            targetVerification = CapabilityStudioProviderOutputIsolation.call(
                    () -> new CapabilityStudioStageAcceptanceTargetBindingVerifier().verify(
                            originalStageResultBytes,
                            targetAdmission.targetBindingBytes(),
                            targetAdmission.candidateAttestationBytes(),
                            targetAdmission.environmentAttestationBytes(),
                            targetAdmission.verificationContext(), trustedVerificationTime,
                            targetAdmission.candidateAuthority(),
                            targetAdmission.environmentAuthority()));
        } catch (CapabilityStudioStageAcceptanceAuthorityProvider
                 .DeploymentUnavailableException unavailable) {
            blocked(safeOut, REASON_FORMAL_TARGET_BINDING_UNAVAILABLE);
            return EXIT_NOT_ACCEPTED;
        } catch (RuntimeException failure) {
            invalid(safeOut, "PROVIDER_CONFIGURATION");
            return EXIT_INVALID;
        }
        if (targetVerification == null) {
            invalid(safeOut, "PROVIDER_CONFIGURATION");
            return EXIT_INVALID;
        }
        if (!targetVerification.verified()) {
            safeOut.println("NOT_ACCEPTED outcome=" + targetVerification.outcome().name()
                    + " reasonCode=" + switch (targetVerification.outcome()) {
                        case REJECTED -> REASON_TARGET_BINDING_REJECTED;
                        case BLOCKED -> REASON_TARGET_BINDING_UNAVAILABLE;
                        case VERIFIED -> throw new IllegalArgumentException(
                                "target verification result is invalid");
                    });
            return EXIT_NOT_ACCEPTED;
        }

        CapabilityStudioStageAcceptanceAuthorityVerifier.VerificationResult verification;
        try {
            verification = CapabilityStudioProviderOutputIsolation.call(
                    () -> new CapabilityStudioStageAcceptanceAuthorityVerifier().verify(
                            result, trustedVerificationTime, authorityBinding.resolver(),
                            authorityBinding.issuerPolicy(), authorityBinding.ownerAuthority()));
        } catch (RuntimeException failure) {
            safeOut.println("NOT_ACCEPTED outcome=BLOCKED reasonCode="
                    + REASON_AUTHORITY_UNAVAILABLE);
            return EXIT_NOT_ACCEPTED;
        }
        if (!verification.accepted()) {
            safeOut.println("NOT_ACCEPTED outcome=" + verification.outcome()
                    + " reasonCode=" + switch (verification.outcome()) {
                        case BLOCKED -> REASON_AUTHORITY_UNAVAILABLE;
                        case REJECTED -> REASON_AUTHORITY_REJECTED;
                        case PROTOCOL_INVALID -> REASON_AUTHORITY_PROTOCOL_INVALID;
                        case ACCEPTED, NOT_ACCEPTED -> throw new IllegalArgumentException(
                                "authority verification result is invalid");
                    });
            return verification.outcome()
                    == CapabilityStudioStageAcceptanceAuthorityVerifier.Outcome.PROTOCOL_INVALID
                    ? EXIT_INVALID : EXIT_NOT_ACCEPTED;
        }

        CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseCommitResult leaseResult;
        if (deploymentAuthority.executionLeaseAuthority() == null) {
            blocked(safeOut, REASON_EXECUTION_LEASE_UNAVAILABLE);
            return EXIT_NOT_ACCEPTED;
        }
        try {
            var leaseRequest = new CapabilityStudioStageAcceptanceAuthorityProvider
                    .ExecutionLeaseRequest(result.path("resultId").textValue(),
                    result.path("revision").longValue(),
                    stageResultRawFingerprint,
                    result.path("evidenceClosureFingerprint").textValue(),
                    result.path("contractId").textValue(),
                    result.path("contractRevision").textValue(),
                    targetAdmission.verificationContext().executionLeaseId(),
                    binding.fingerprint(), targetAdmission.targetRawFingerprint(),
                    targetAdmission.targetCanonicalFingerprint(),
                    targetAdmission.lifecycleMaterial(), deploymentAuthority.fingerprint(),
                    trustedVerificationTime);
            leaseResult = CapabilityStudioProviderOutputIsolation.call(
                    () -> deploymentAuthority.executionLeaseAuthority().commit(leaseRequest));
            CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseReceipt receipt =
                    requireValidReceipt(leaseResult, leaseRequest);
            if (receipt == null) {
                return emitLeaseFailure(safeOut, leaseResult);
            }
            safeOut.println("ACCEPTED outcome=ACCEPTED authorityBindingFingerprint="
                    + binding.fingerprint() + " leaseCommitStatus=" + leaseResult.status()
                    + " leaseReceiptFingerprint=" + receipt.fingerprint()
                    + " reasonCode=" + REASON_ACCEPTED);
            return EXIT_ACCEPTED;
        } catch (CapabilityStudioStageAcceptanceAuthorityProvider
                 .DeploymentUnavailableException unavailable) {
            blocked(safeOut, REASON_EXECUTION_LEASE_UNAVAILABLE);
            return EXIT_NOT_ACCEPTED;
        } catch (RuntimeException failure) {
            invalid(safeOut, "PROVIDER_CONFIGURATION");
            return EXIT_INVALID;
        }
    }

    @FunctionalInterface
    interface ProviderSource {
        List<CapabilityStudioStageAcceptanceAuthorityProvider> load();
    }

    private static CapabilityStudioStageAcceptanceAuthorityProvider loadProvider(
            ProviderSource source) {
        if (source == null) {
            return null;
        }
        try {
            List<CapabilityStudioStageAcceptanceAuthorityProvider> providers = source.load();
            if (providers == null || providers.size() != 1 || providers.getFirst() == null) {
                return null;
            }
            return providers.getFirst();
        } catch (RuntimeException | ServiceConfigurationError failure) {
            if (deploymentUnavailable(failure)) {
                throw new CapabilityStudioStageAcceptanceAuthorityProvider
                        .DeploymentUnavailableException();
            }
            return null;
        }
    }

    private static List<CapabilityStudioStageAcceptanceAuthorityProvider> providers() {
        List<ServiceLoader.Provider<CapabilityStudioStageAcceptanceAuthorityProvider>> discovered =
                ServiceLoader.load(CapabilityStudioStageAcceptanceAuthorityProvider.class)
                        .stream().limit(2).toList();
        if (discovered.size() != 1) {
            return List.of();
        }
        return List.of(discovered.getFirst().get());
    }

    private static void invalid(PrintStream out, String suffix) {
        out.println("INVALID errorCode=" + CODE_PREFIX + suffix);
    }

    private static void blocked(PrintStream out, String reasonCode) {
        out.println("NOT_ACCEPTED outcome=BLOCKED reasonCode=" + reasonCode);
    }

    private static CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseReceipt
            requireValidReceipt(
            CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseCommitResult result,
            CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseRequest request) {
        if (result == null || result.status() == null) {
            throw new IllegalArgumentException("execution lease commit result is invalid");
        }
        if (result.status()
                != CapabilityStudioStageAcceptanceAuthorityProvider
                .ExecutionLeaseCommitStatus.COMMITTED
                && result.status()
                != CapabilityStudioStageAcceptanceAuthorityProvider
                .ExecutionLeaseCommitStatus.RECOVERED) {
            return null;
        }
        CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseReceipt receipt =
                result.receipt();
        if (receipt == null
                || !receipt.requestFingerprint().equals(request.commitIdentityFingerprint())
                || !receipt.lifecycleMaterial().equals(request.lifecycleMaterial())) {
            throw new IllegalArgumentException("execution lease receipt is invalid");
        }
        var lifecycleReceipt = receipt.lifecycleCommitReceipt();
        var revocation = request.lifecycleMaterial().revocationAuthority();
        if (!lifecycleReceipt.requestFingerprint().equals(
                request.commitIdentityFingerprint())
                || !lifecycleReceipt.deploymentAdmissionAuthorityMaterialFingerprint().equals(
                request.deploymentAdmissionAuthorityMaterialFingerprint())
                || !lifecycleReceipt.lifecycleMaterialFingerprint().equals(
                request.lifecycleMaterial().fingerprint())
                || !lifecycleReceipt.revocationRegistryRef().equals(revocation.registryRef())
                || lifecycleReceipt.revocationRegistryRevision() != revocation.revision()
                || !lifecycleReceipt.revocationSnapshotFingerprint().equals(
                revocation.snapshotFingerprint())
                || lifecycleReceipt.committedAt().isBefore(revocation.observedAt())
                || !revocation.expiresAt().isAfter(lifecycleReceipt.committedAt())) {
            throw new IllegalArgumentException("execution lease receipt is invalid");
        }
        return receipt;
    }

    private static int emitLeaseFailure(
            PrintStream out,
            CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseCommitResult result) {
        return switch (result.status()) {
            case REJECTED -> {
                out.println("NOT_ACCEPTED outcome=REJECTED reasonCode="
                        + REASON_EXECUTION_LEASE_REJECTED);
                yield EXIT_NOT_ACCEPTED;
            }
            case UNAVAILABLE -> {
                out.println("NOT_ACCEPTED outcome=BLOCKED reasonCode="
                        + REASON_EXECUTION_LEASE_UNAVAILABLE);
                yield EXIT_NOT_ACCEPTED;
            }
            case COMMITTED, RECOVERED -> throw new IllegalArgumentException(
                    "execution lease commit result is invalid");
        };
    }

    private static boolean deploymentUnavailable(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (current instanceof CapabilityStudioStageAcceptanceAuthorityProvider
                    .DeploymentUnavailableException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                return false;
            }
            current = cause;
        }
        return false;
    }

    private static int deploymentDecision(
            PrintStream out,
            CapabilityStudioStageAcceptanceAuthorityProvider.DeploymentAuthorityDecision decision) {
        if (decision == null || decision.status() == null) {
            invalid(out, "PROVIDER_CONFIGURATION");
            return EXIT_INVALID;
        }
        return switch (decision.status()) {
            case VERIFIED -> EXIT_ACCEPTED;
            case REJECTED -> {
                out.println("NOT_ACCEPTED outcome=REJECTED reasonCode="
                        + REASON_ADMISSION_LIFECYCLE_REJECTED);
                yield EXIT_NOT_ACCEPTED;
            }
            case UNAVAILABLE -> {
                out.println("NOT_ACCEPTED outcome=BLOCKED reasonCode="
                        + REASON_ADMISSION_LIFECYCLE_UNAVAILABLE);
                yield EXIT_NOT_ACCEPTED;
            }
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String sha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }
}
