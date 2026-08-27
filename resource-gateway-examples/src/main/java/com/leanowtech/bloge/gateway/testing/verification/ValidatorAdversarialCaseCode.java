package com.leanowtech.bloge.gateway.testing.verification;

/** Stable, version-independent identifiers for the six mandatory negative controls. */
public enum ValidatorAdversarialCaseCode {
    REDACTOR_MISSED_CREDENTIAL_OR_FREE_TEXT_IDENTITY(
            "redactor.missed-credential-or-free-text-identity", "redactor", "redaction.adversarial-secret-identity"),
    IMPACT_ANALYSIS_MISSED_OBSERVED_ONLY(
            "impact-analysis.missed-observed-only", "impact-analysis", "impact.adversarial-observed-only"),
    MIGRATION_GUESSED_UNTAGGED_CONTRACT(
            "migration.guessed-untagged-contract", "migration", "migration.adversarial-untagged-contract"),
    FIDELITY_COMPARATOR_ALLOWED_BREAKING_SCHEMA(
            "fidelity-comparator.allowed-breaking-schema", "fidelity-comparator", "fidelity.adversarial-breaking-schema"),
    MUTATION_EVALUATOR_MARKED_SURVIVOR_KILLED(
            "mutation-evaluator.marked-survivor-killed", "mutation-evaluator", "mutation.adversarial-survivor"),
    APPROVAL_REFERENCED_WRONG_CANDIDATE_VERSION(
            "approval.referenced-wrong-candidate-version", "candidate-approval", "approval.adversarial-candidate-version");

    private final String code;
    private final String validatorId;
    private final String expectedRejectionCode;

    ValidatorAdversarialCaseCode(String code, String validatorId, String expectedRejectionCode) {
        this.code = code;
        this.validatorId = validatorId;
        this.expectedRejectionCode = expectedRejectionCode;
    }

    public String code() {
        return code;
    }

    public String validatorId() {
        return validatorId;
    }

    public String expectedRejectionCode() {
        return expectedRejectionCode;
    }
}
