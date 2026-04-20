package com.leanowtech.bloge.graphengine.service;

/**
 * Stable error codes raised by the product-layer graph-engine service facade.
 */
public enum GraphEngineServiceErrorCode {
    NOT_FOUND,
    VALIDATION_FAILED,
    INVALID_STATE,
    RUNTIME_UNAVAILABLE,
    UNSUPPORTED_EXECUTION_MODE,
    DUPLICATE_BUSINESS_KEY,
    CONFLICT,
    ACCESS_DENIED
}
