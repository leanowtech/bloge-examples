package com.leanowtech.bloge.gateway.testing.world.persistence;

/** Fixed, payload-free abort used to carry a guarded dependency decision through catalog decode. */
public final class GovernedCatalogDependencyAbortException extends RuntimeException {
    public enum Code {
        ACCESS_DENIED("RG.WORLD.CATALOG.DEPENDENCY_ACCESS_DENIED"),
        REFERENCE_NOT_FOUND("RG.WORLD.CATALOG.DEPENDENCY_NOT_FOUND"),
        INTEGRITY_FAILURE("RG.WORLD.CATALOG.DEPENDENCY_INTEGRITY");

        private final String value;

        Code(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    private final Code code;

    private GovernedCatalogDependencyAbortException(Code code) {
        super(code.value());
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public static GovernedCatalogDependencyAbortException of(Code code) {
        if (code == null) {
            throw new IllegalArgumentException("RG.WORLD.CATALOG.DEPENDENCY_CODE_REQUIRED");
        }
        return new GovernedCatalogDependencyAbortException(code);
    }
}
