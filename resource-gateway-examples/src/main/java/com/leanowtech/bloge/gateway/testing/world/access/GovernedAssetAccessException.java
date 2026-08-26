package com.leanowtech.bloge.gateway.testing.world.access;

/** Payload-free failure for governed reference admission and exact resolution. */
public final class GovernedAssetAccessException extends IllegalStateException {
    public enum Code {
        ACCESS_DENIED("RG.TEST.GOVERNED_ASSET.ACCESS_DENIED"),
        INVALID_CONTEXT("RG.TEST.GOVERNED_ASSET.INVALID_CONTEXT"),
        REFERENCE_NOT_FOUND("RG.TEST.GOVERNED_ASSET.REFERENCE_NOT_FOUND"),
        INTEGRITY_FAILURE("RG.TEST.GOVERNED_ASSET.INTEGRITY_FAILURE");

        private final String value;

        Code(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    private final Code code;

    private GovernedAssetAccessException(Code code) {
        super(code.value());
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public String errorCode() {
        return code.value();
    }

    public static GovernedAssetAccessException denied() {
        return new GovernedAssetAccessException(Code.ACCESS_DENIED);
    }

    public static GovernedAssetAccessException invalidContext() {
        return new GovernedAssetAccessException(Code.INVALID_CONTEXT);
    }

    public static GovernedAssetAccessException notFound() {
        return new GovernedAssetAccessException(Code.REFERENCE_NOT_FOUND);
    }

    public static GovernedAssetAccessException integrity() {
        return new GovernedAssetAccessException(Code.INTEGRITY_FAILURE);
    }
}
