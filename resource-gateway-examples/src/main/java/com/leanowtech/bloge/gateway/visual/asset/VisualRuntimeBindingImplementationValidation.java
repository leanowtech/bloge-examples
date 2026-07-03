package com.leanowtech.bloge.gateway.visual.asset;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stateless validation result for a runtime-plane implementation binding proposal.
 *
 * <p>This contract is the first control-plane step after a handoff bundle: it
 * checks that a runtime team's implementation evidence still points at the
 * exported operator contract before any future bind/supersede mutation exists.</p>
 *
 * @param schemaVersion validation response contract version
 * @param validatedAt server validation timestamp
 * @param valid true when no blocking validation diagnostics were found
 * @param bindable true when the proposal is valid and has no review warnings
 * @param state ready-to-bind, requires-review, or rejected
 * @param level UI/control-plane severity
 * @param message human-readable validation summary
 * @param operatorRef submitted operator reference
 * @param operatorFingerprint submitted operator contract fingerprint
 * @param sourceHandoffBundleFingerprint source handoff bundle fingerprint when supplied
 * @param contractFingerprint fingerprint from the submitted operator contract snapshot
 * @param currentCatalogFingerprint current catalog fingerprint for the operator when visible
 * @param currentCatalogState current catalog comparison state
 * @param implementation submitted implementation metadata
 * @param diagnostics structured validation diagnostics
 */
public record VisualRuntimeBindingImplementationValidation(
        String schemaVersion,
        Instant validatedAt,
        boolean valid,
        boolean bindable,
        String state,
        String level,
        String message,
        String operatorRef,
        String operatorFingerprint,
        String sourceHandoffBundleFingerprint,
        String contractFingerprint,
        String currentCatalogFingerprint,
        String currentCatalogState,
        ImplementationMetadata implementation,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.visualRuntimeBindingImplementationValidation.v1";
    public static final String REQUEST_SCHEMA_VERSION = "bloge.visualRuntimeBindingImplementationBinding.v1";

    /**
     * Creates a normalized validation result.
     */
    public VisualRuntimeBindingImplementationValidation {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        validatedAt = validatedAt == null ? Instant.now() : validatedAt;
        state = state == null || state.isBlank() ? "rejected" : state.trim().toLowerCase(Locale.ROOT);
        level = level == null || level.isBlank() ? "error" : level.trim().toLowerCase(Locale.ROOT);
        message = message == null ? "" : message;
        operatorRef = operatorRef == null ? "" : operatorRef.trim();
        operatorFingerprint = operatorFingerprint == null ? "" : operatorFingerprint.trim();
        sourceHandoffBundleFingerprint = sourceHandoffBundleFingerprint == null
                ? ""
                : sourceHandoffBundleFingerprint.trim();
        contractFingerprint = contractFingerprint == null ? "" : contractFingerprint.trim();
        currentCatalogFingerprint = currentCatalogFingerprint == null ? "" : currentCatalogFingerprint.trim();
        currentCatalogState = currentCatalogState == null || currentCatalogState.isBlank()
                ? "unknown"
                : currentCatalogState.trim().toLowerCase(Locale.ROOT);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        valid = diagnostics.stream().noneMatch(VisualDiagnostic::error);
        bindable = valid && diagnostics.stream().noneMatch(VisualRuntimeBindingImplementationValidation::warning);
    }

    /**
     * Submitted implementation binding proposal.
     *
     * @param schemaVersion request contract version
     * @param operatorRef operator being implemented
     * @param operatorFingerprint fingerprint of the operator contract being implemented
     * @param sourceHandoffBundleFingerprint handoff bundle fingerprint that carried this contract
     * @param sourceRequirementKeys runtime-binding requirement keys covered by this proposal
     * @param operatorContract handoff operator contract snapshot
     * @param implementation implementation metadata and evidence
     */
    public record Request(
            String schemaVersion,
            String operatorRef,
            String operatorFingerprint,
            String sourceHandoffBundleFingerprint,
            List<String> sourceRequirementKeys,
            VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot operatorContract,
            ImplementationMetadata implementation
    ) {
        public Request {
            schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                    ? REQUEST_SCHEMA_VERSION
                    : schemaVersion.trim();
            operatorRef = operatorRef == null ? "" : operatorRef.trim();
            operatorFingerprint = operatorFingerprint == null ? "" : operatorFingerprint.trim();
            sourceHandoffBundleFingerprint = sourceHandoffBundleFingerprint == null
                    ? ""
                    : sourceHandoffBundleFingerprint.trim();
            sourceRequirementKeys = normalizeStrings(sourceRequirementKeys);
        }
    }

    /**
     * Runtime implementation metadata submitted by an implementation owner.
     *
     * @param bindingId caller-supplied implementation binding id
     * @param adapterKind runtime adapter kind such as native, remote-worker, ai-tool, webhook, or message-handler
     * @param entrypoint executable adapter entrypoint
     * @param runtimeOwner owning team or service
     * @param capabilities implementation capability labels
     * @param testEvidence test or certification evidence
     * @param policyEvidence governance, secret, egress, or approval evidence
     * @param rollbackTarget previous adapter or deployment target for rollback
     * @param notes optional implementation notes
     */
    public record ImplementationMetadata(
            String bindingId,
            String adapterKind,
            String entrypoint,
            String runtimeOwner,
            List<String> capabilities,
            List<Evidence> testEvidence,
            List<Evidence> policyEvidence,
            String rollbackTarget,
            String notes
    ) {
        public ImplementationMetadata {
            bindingId = bindingId == null ? "" : bindingId.trim();
            adapterKind = adapterKind == null ? "" : adapterKind.trim().toLowerCase(Locale.ROOT);
            entrypoint = entrypoint == null ? "" : entrypoint.trim();
            runtimeOwner = runtimeOwner == null ? "" : runtimeOwner.trim();
            capabilities = normalizeStrings(capabilities);
            testEvidence = testEvidence == null ? List.of() : List.copyOf(testEvidence);
            policyEvidence = policyEvidence == null ? List.of() : List.copyOf(policyEvidence);
            rollbackTarget = rollbackTarget == null ? "" : rollbackTarget.trim();
            notes = notes == null ? "" : notes;
        }
    }

    /**
     * External evidence item attached to an implementation proposal.
     *
     * @param kind evidence kind such as test, certification, approval, or policy
     * @param ref external evidence identifier or URL
     * @param summary human-readable evidence summary
     */
    public record Evidence(String kind, String ref, String summary) {
        public Evidence {
            kind = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
            ref = ref == null ? "" : ref.trim();
            summary = summary == null ? "" : summary;
        }
    }

    /**
     * Creates a rejected result when the request body is absent.
     *
     * @return validation result
     */
    public static VisualRuntimeBindingImplementationValidation missingRequest() {
        return new VisualRuntimeBindingImplementationValidation(
                SCHEMA_VERSION,
                Instant.now(),
                false,
                false,
                "rejected",
                "error",
                "Runtime binding implementation validation requires a request body.",
                "",
                "",
                "",
                "",
                "",
                "unknown",
                null,
                List.of(VisualDiagnostic.error(
                        "visual.runtimeBindingImplementation.requestMissing",
                        "Runtime binding implementation validation requires a request body.",
                        "/"))
        );
    }

    /**
     * Validates one implementation proposal against a submitted handoff contract and current catalog state.
     *
     * @param request submitted implementation binding proposal
     * @param currentOperator current catalog operator when visible
     * @return validation result
     */
    public static VisualRuntimeBindingImplementationValidation from(Request request,
                                                                    OperatorDefinition currentOperator) {
        if (request == null) {
            return missingRequest();
        }
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (!REQUEST_SCHEMA_VERSION.equals(request.schemaVersion())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeBindingImplementation.schemaVersionUnsupported",
                    "Runtime binding implementation request schemaVersion '%s' is not supported; expected '%s'."
                            .formatted(request.schemaVersion(), REQUEST_SCHEMA_VERSION),
                    "/schemaVersion",
                    Map.of("actual", request.schemaVersion(), "expected", REQUEST_SCHEMA_VERSION)));
        }
        VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot contract = request.operatorContract();
        if (contract == null) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeBindingImplementation.operatorContractMissing",
                    "Runtime binding implementation validation requires a handoff operator contract snapshot.",
                    "/operatorContract"));
        }
        if (request.operatorRef().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeBindingImplementation.operatorRefMissing",
                    "Runtime binding implementation validation requires operatorRef.",
                    "/operatorRef"));
        }
        if (request.operatorFingerprint().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeBindingImplementation.operatorFingerprintMissing",
                    "Runtime binding implementation validation requires operatorFingerprint.",
                    "/operatorFingerprint"));
        }
        if (contract != null) {
            addContractDiagnostics(request, contract, currentOperator, diagnostics);
        }
        addImplementationDiagnostics(request.implementation(), contract, diagnostics);
        String contractFingerprint = contract == null ? "" : contract.fingerprint();
        String currentFingerprint = currentOperator == null ? "" : currentOperator.fingerprint();
        String catalogState = catalogState(contractFingerprint, currentOperator);
        String state = validationState(diagnostics);
        String level = validationLevel(state);
        return new VisualRuntimeBindingImplementationValidation(
                SCHEMA_VERSION,
                Instant.now(),
                true,
                true,
                state,
                level,
                validationMessage(state, request.operatorRef(), diagnostics.size()),
                request.operatorRef(),
                request.operatorFingerprint(),
                request.sourceHandoffBundleFingerprint(),
                contractFingerprint,
                currentFingerprint,
                catalogState,
                request.implementation(),
                diagnostics
        );
    }

    private static void addContractDiagnostics(
            Request request,
            VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot contract,
            OperatorDefinition currentOperator,
            List<VisualDiagnostic> diagnostics) {
        if (!request.operatorRef().isBlank() && !request.operatorRef().equals(contract.operatorRef())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeBindingImplementation.operatorRefMismatch",
                    "Submitted operatorRef '%s' does not match handoff contract operatorRef '%s'."
                            .formatted(request.operatorRef(), contract.operatorRef()),
                    "/operatorRef",
                    Map.of("actual", request.operatorRef(), "expected", contract.operatorRef())));
        }
        if (!request.operatorFingerprint().isBlank()
                && !request.operatorFingerprint().equals(contract.fingerprint())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeBindingImplementation.fingerprintMismatch",
                    "Submitted operatorFingerprint '%s' does not match handoff contract fingerprint '%s'."
                            .formatted(request.operatorFingerprint(), contract.fingerprint()),
                    "/operatorFingerprint",
                    Map.of("actual", request.operatorFingerprint(), "expected", contract.fingerprint())));
        }
        if (currentOperator == null) {
            diagnostics.add(VisualDiagnostic.warning(
                    "visual.runtimeBindingImplementation.catalogMissing",
                    "Current catalog does not contain the submitted operatorRef; validation can only use the handoff contract snapshot.",
                    "/operatorRef",
                    Map.of("operatorRef", request.operatorRef())));
            return;
        }
        if (!contract.fingerprint().equals(currentOperator.fingerprint())) {
            diagnostics.add(VisualDiagnostic.warning(
                    "visual.runtimeBindingImplementation.catalogFingerprintDrift",
                    "Current catalog fingerprint '%s' differs from submitted handoff contract fingerprint '%s'."
                            .formatted(currentOperator.fingerprint(), contract.fingerprint()),
                    "/operatorContract/fingerprint",
                    Map.of(
                            "current", currentOperator.fingerprint(),
                            "submitted", contract.fingerprint())));
        }
        if (contract.runtimeReadiness() != null && contract.runtimeReadiness().executable()) {
            diagnostics.add(VisualDiagnostic.warning(
                    "visual.runtimeBindingImplementation.contractAlreadyExecutable",
                    "Submitted operator contract is already runtime-executable; implementation binding may be unnecessary.",
                    "/operatorContract/runtimeReadiness"));
        }
    }

    private static void addImplementationDiagnostics(
            ImplementationMetadata implementation,
            VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot contract,
            List<VisualDiagnostic> diagnostics) {
        if (implementation == null) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeBindingImplementation.metadataMissing",
                    "Runtime binding implementation metadata is required.",
                    "/implementation"));
            return;
        }
        if (implementation.adapterKind().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeBindingImplementation.adapterKindMissing",
                    "Runtime binding implementation requires adapterKind.",
                    "/implementation/adapterKind"));
        }
        if (implementation.entrypoint().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeBindingImplementation.entrypointMissing",
                    "Runtime binding implementation requires entrypoint.",
                    "/implementation/entrypoint"));
        }
        if (implementation.runtimeOwner().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.runtimeBindingImplementation.runtimeOwnerMissing",
                    "Runtime binding implementation requires runtimeOwner.",
                    "/implementation/runtimeOwner"));
        }
        if (implementation.testEvidence().isEmpty()) {
            diagnostics.add(VisualDiagnostic.warning(
                    "visual.runtimeBindingImplementation.testEvidenceMissing",
                    "Runtime binding implementation has no test evidence; binding should require review.",
                    "/implementation/testEvidence"));
        }
        if (implementation.rollbackTarget().isBlank()) {
            diagnostics.add(VisualDiagnostic.warning(
                    "visual.runtimeBindingImplementation.rollbackTargetMissing",
                    "Runtime binding implementation has no rollbackTarget; production binding should require review.",
                    "/implementation/rollbackTarget"));
        }
        if (policyEvidenceRequired(contract) && implementation.policyEvidence().isEmpty()) {
            diagnostics.add(VisualDiagnostic.warning(
                    "visual.runtimeBindingImplementation.policyEvidenceMissing",
                    "Runtime binding implementation affects policy, secrets, or side effects but has no policy evidence.",
                    "/implementation/policyEvidence"));
        }
    }

    private static boolean policyEvidenceRequired(
            VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot contract) {
        if (contract == null) {
            return false;
        }
        OperatorDefinition.Capabilities capabilities = contract.capabilities();
        if (capabilities != null
                && (capabilities.requiresSecrets()
                || !"PURE".equals(capabilities.effect())
                || "NON_IDEMPOTENT".equals(capabilities.idempotency()))) {
            return true;
        }
        OperatorDefinition.Policy policy = contract.policy();
        return policy != null
                && (!policy.tenants().isEmpty()
                || !policy.namespaces().isEmpty()
                || !policy.environments().isEmpty());
    }

    private static String catalogState(String contractFingerprint, OperatorDefinition currentOperator) {
        if (currentOperator == null) {
            return "missing";
        }
        if (contractFingerprint == null || contractFingerprint.isBlank()) {
            return "unknown";
        }
        return contractFingerprint.equals(currentOperator.fingerprint()) ? "current" : "drifted";
    }

    private static String validationState(List<VisualDiagnostic> diagnostics) {
        if (diagnostics.stream().anyMatch(VisualDiagnostic::error)) {
            return "rejected";
        }
        if (diagnostics.stream().anyMatch(VisualRuntimeBindingImplementationValidation::warning)) {
            return "requires-review";
        }
        return "ready-to-bind";
    }

    private static String validationLevel(String state) {
        return switch (state) {
            case "ready-to-bind" -> "success";
            case "requires-review" -> "warning";
            default -> "error";
        };
    }

    private static String validationMessage(String state, String operatorRef, int diagnosticCount) {
        String target = operatorRef == null || operatorRef.isBlank() ? "operator" : operatorRef;
        return switch (state) {
            case "ready-to-bind" -> "Runtime binding implementation for %s is ready to bind.".formatted(target);
            case "requires-review" -> "Runtime binding implementation for %s requires review: %d diagnostic(s)."
                    .formatted(target, diagnosticCount);
            default -> "Runtime binding implementation for %s was rejected: %d diagnostic(s)."
                    .formatted(target, diagnosticCount);
        };
    }

    private static boolean warning(VisualDiagnostic diagnostic) {
        return diagnostic != null && "WARNING".equalsIgnoreCase(diagnostic.level());
    }

    private static List<String> normalizeStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
