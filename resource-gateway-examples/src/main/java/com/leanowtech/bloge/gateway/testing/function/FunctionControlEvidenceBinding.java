package com.leanowtech.bloge.gateway.testing.function;

/** Payload-free binding facts carried by one function-control run evidence. */
public record FunctionControlEvidenceBinding(
        FunctionInvocationSite site,
        String functionFingerprint,
        String runtimeFingerprint,
        FunctionControlMode mode,
        FunctionEvidenceCeiling evidenceCeiling,
        String downgradeReason
) {
    public FunctionControlEvidenceBinding {
        if (site == null || !valid(functionFingerprint) || !valid(runtimeFingerprint)
                || mode == null || evidenceCeiling == null || downgradeReason == null
                || downgradeReason.length() > FunctionValueSupport.MAX_STRING_LENGTH) {
            throw new FunctionControlException(FunctionControlException.Code.INVALID_INPUT);
        }
        downgradeReason = FunctionValueSupport.text(downgradeReason, false,
                FunctionControlException.Code.INVALID_INPUT);
    }

    private static boolean valid(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }
}
