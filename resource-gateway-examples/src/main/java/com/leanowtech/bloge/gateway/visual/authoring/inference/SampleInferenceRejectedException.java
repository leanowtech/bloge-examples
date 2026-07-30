package com.leanowtech.bloge.gateway.visual.authoring.inference;

/**
 * Safe protocol rejection raised before any inference result is emitted.
 */
public final class SampleInferenceRejectedException extends RuntimeException {

    private final String code;
    private final int status;
    private final String authoringPath;

    public SampleInferenceRejectedException(String code,
                                            String message,
                                            int status,
                                            String authoringPath) {
        super(message == null ? "Sample inference request was rejected." : message);
        this.code = code == null || code.isBlank()
                ? "RG.AUTHORING.INFERENCE_INVALID" : code.trim();
        this.status = status;
        this.authoringPath = authoringPath == null || authoringPath.isBlank()
                ? "/" : authoringPath.trim();
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public String authoringPath() {
        return authoringPath;
    }
}
