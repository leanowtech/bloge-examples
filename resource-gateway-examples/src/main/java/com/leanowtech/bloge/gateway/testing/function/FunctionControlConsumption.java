package com.leanowtech.bloge.gateway.testing.function;

/** Payload-free consumption fact for one compiled control rule. */
public record FunctionControlConsumption(
        String ruleId,
        long minimum,
        long maximum,
        long used,
        String status
) {
    public FunctionControlConsumption {
        ruleId = FunctionValueSupport.text(ruleId, true,
                FunctionControlException.Code.INVALID_INPUT);
        if (minimum < 0 || maximum < minimum || used < 0 || used > maximum
                || status == null || status.isBlank()) {
            throw new FunctionControlException(FunctionControlException.Code.INVALID_INPUT);
        }
        status = FunctionValueSupport.text(status, true,
                FunctionControlException.Code.INVALID_INPUT);
    }
}
