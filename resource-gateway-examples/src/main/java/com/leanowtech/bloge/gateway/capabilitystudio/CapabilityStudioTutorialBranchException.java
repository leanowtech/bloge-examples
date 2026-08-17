package com.leanowtech.bloge.gateway.capabilitystudio;

/** Stable business error raised by the isolated tutorial branch authoring surface. */
final class CapabilityStudioTutorialBranchException extends RuntimeException {
    private final String code;
    private final String whatHappened;
    private final String impact;
    private final String recoveryAction;
    private final String field;
    private final int status;

    CapabilityStudioTutorialBranchException(
            String code,
            String whatHappened,
            String impact,
            String recoveryAction,
            String field,
            int status) {
        super(whatHappened);
        this.code = code;
        this.whatHappened = whatHappened;
        this.impact = impact;
        this.recoveryAction = recoveryAction;
        this.field = field;
        this.status = status;
    }

    String code() {
        return code;
    }

    String whatHappened() {
        return whatHappened;
    }

    String impact() {
        return impact;
    }

    String recoveryAction() {
        return recoveryAction;
    }

    String field() {
        return field;
    }

    int status() {
        return status;
    }
}
