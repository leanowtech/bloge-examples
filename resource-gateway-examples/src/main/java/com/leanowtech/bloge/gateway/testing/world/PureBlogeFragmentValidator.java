package com.leanowtech.bloge.gateway.testing.world;

/** The only public pure-fragment validation boundary. */
public final class PureBlogeFragmentValidator {
    public record ValidationResult(String fingerprint,
                                   int primitiveCount,
                                   int expressionDepth,
                                   String outputNodeId,
                                   java.util.List<String> findings) {
        public ValidationResult {
            findings = java.util.List.copyOf(findings == null ? java.util.List.of() : findings);
        }
    }

    public ValidationResult validate(BlogeFragmentRef fragment) {
        BlogeFragmentAdmission.Result result = BlogeFragmentAdmission.admit(fragment);
        return new ValidationResult(result.fingerprint(), result.primitiveCount(), result.expressionDepth(),
                result.outputNodeId(), result.findings());
    }
}
