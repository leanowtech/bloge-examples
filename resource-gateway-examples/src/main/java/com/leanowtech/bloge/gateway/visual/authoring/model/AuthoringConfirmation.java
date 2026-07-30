package com.leanowtech.bloge.gateway.visual.authoring.model;

/**
 * One explicit authoring decision bound to the evidence and confirmation that requested it.
 */
public record AuthoringConfirmation(
        String confirmationId,
        String evidenceFingerprint,
        String factId,
        String code,
        String authoringPath,
        String decision,
        boolean blocking,
        String decidedBy
) {
    public AuthoringConfirmation {
        confirmationId = normalized(confirmationId);
        evidenceFingerprint = normalized(evidenceFingerprint);
        factId = normalized(factId);
        code = normalized(code);
        authoringPath = normalized(authoringPath);
        decision = normalized(decision);
        decidedBy = normalized(decidedBy);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
