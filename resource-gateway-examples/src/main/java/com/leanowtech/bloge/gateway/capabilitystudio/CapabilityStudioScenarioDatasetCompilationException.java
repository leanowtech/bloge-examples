package com.leanowtech.bloge.gateway.capabilitystudio;

/** Stable fail-closed error for Dataset materialization and compilation. */
public final class CapabilityStudioScenarioDatasetCompilationException extends RuntimeException {

    private final String code;
    private final String path;

    CapabilityStudioScenarioDatasetCompilationException(String code, String path) {
        super(code + " at " + (path == null || path.isBlank() ? "/" : path));
        this.code = code;
        this.path = path == null ? "" : path;
    }

    public String code() {
        return code;
    }

    public String path() {
        return path;
    }
}
