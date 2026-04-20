package com.leanowtech.bloge.gateway.resource;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Set;

/**
 * Defines how an HTTP response should be interpreted to determine success or failure.
 *
 * <p>Covers 85%+ of real-world API response conventions with four structural variants,
 * plus a custom bloge expression fallback for the remaining edge cases.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ResponseProtocol.HttpStatus.class, name = "httpStatus"),
        @JsonSubTypes.Type(value = ResponseProtocol.BodyCode.class, name = "bodyCode"),
        @JsonSubTypes.Type(value = ResponseProtocol.BodyFlag.class, name = "bodyFlag"),
        @JsonSubTypes.Type(value = ResponseProtocol.StatusCodes.class, name = "statusCodes"),
        @JsonSubTypes.Type(value = ResponseProtocol.BlgeExpression.class, name = "blgeExpression")
})
public sealed interface ResponseProtocol permits
        ResponseProtocol.HttpStatus,
        ResponseProtocol.BodyCode,
        ResponseProtocol.BodyFlag,
        ResponseProtocol.StatusCodes,
        ResponseProtocol.BlgeExpression {

    /**
     * Success is determined solely by the HTTP status code being in the 2xx range.
     *
     * <p>Suitable for RESTful APIs that follow standard HTTP semantics.
     */
    record HttpStatus() implements ResponseProtocol {}

    /**
     * Success is determined by a numeric or string code field in the response body.
     *
     * <p>Common in Chinese-style APIs that always return HTTP 200 and embed an error code
     * such as {@code "errcode": 0} or {@code "code": "SUCCESS"}.
     *
     * @param codePath       dot-notation path to the code field (e.g. "errcode" or "code")
     * @param successValues  set of values that indicate success (e.g. {@code Set.of(0)} or
     *                       {@code Set.of("200", "SUCCESS")})
     * @param messagePath    dot-notation path to the error message field (e.g. "errmsg")
     */
    record BodyCode(
        String codePath,
        Set<Object> successValues,
        String messagePath
    ) implements ResponseProtocol {
        public BodyCode {
            if (codePath == null || codePath.isBlank()) {
                throw new IllegalArgumentException("codePath must not be blank");
            }
            successValues = successValues == null ? Set.of() : Set.copyOf(successValues);
        }
    }

    /**
     * Success is determined by a boolean field in the response body.
     *
     * <p>Suitable for APIs that include a {@code "success": true/false} flag.
     *
     * @param flagPath dot-notation path to the boolean field (e.g. "success")
     */
    record BodyFlag(
        String flagPath
    ) implements ResponseProtocol {
        public BodyFlag {
            if (flagPath == null || flagPath.isBlank()) {
                throw new IllegalArgumentException("flagPath must not be blank");
            }
        }
    }

    /**
     * Success is determined by matching the HTTP status code against an explicit set
     * of accepted codes.
     *
     * <p>Useful when non-standard codes (e.g. 204, 206, 302) should also be treated
     * as success.
     *
     * @param successCodes the set of HTTP status codes that indicate success
     */
    record StatusCodes(
        Set<Integer> successCodes
    ) implements ResponseProtocol {
        public StatusCodes {
            successCodes = successCodes == null ? Set.of() : Set.copyOf(successCodes);
        }
    }

    /**
     * Success is determined by evaluating a custom bloge DSL expression.
     *
     * <p>Covers the ~15% of edge cases where structural variants don't suffice.
     * The expressions have access to a context containing {@code statusCode},
     * {@code body} (parsed JSON), and {@code headers}.
     *
     * @param successExpr bloge expression that evaluates to a boolean
     *                    (e.g. {@code "ctx.statusCode == 200 && ctx.body.errno == 0"})
     * @param messageExpr bloge expression for extracting an error message
     *                    (e.g. {@code "ctx.body.errmsg"})
     * @param payloadExpr bloge expression for extracting the payload
     *                    (e.g. {@code "ctx.body.data"})
     */
    record BlgeExpression(
        String successExpr,
        String messageExpr,
        String payloadExpr
    ) implements ResponseProtocol {
        public BlgeExpression {
            if (successExpr == null || successExpr.isBlank()) {
                throw new IllegalArgumentException("successExpr must not be blank");
            }
        }
    }
}
