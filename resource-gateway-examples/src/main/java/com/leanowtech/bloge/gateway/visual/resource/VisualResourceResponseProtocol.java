package com.leanowtech.bloge.gateway.visual.resource;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Set;

/**
 * Visual-owned response protocol used by resource descriptor previews.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = VisualResourceResponseProtocol.HttpStatus.class, name = "httpStatus"),
        @JsonSubTypes.Type(value = VisualResourceResponseProtocol.BodyCode.class, name = "bodyCode"),
        @JsonSubTypes.Type(value = VisualResourceResponseProtocol.BodyFlag.class, name = "bodyFlag"),
        @JsonSubTypes.Type(value = VisualResourceResponseProtocol.StatusCodes.class, name = "statusCodes"),
        @JsonSubTypes.Type(value = VisualResourceResponseProtocol.BlogeExpression.class, name = "blgeExpression")
})
public sealed interface VisualResourceResponseProtocol permits
        VisualResourceResponseProtocol.HttpStatus,
        VisualResourceResponseProtocol.BodyCode,
        VisualResourceResponseProtocol.BodyFlag,
        VisualResourceResponseProtocol.StatusCodes,
        VisualResourceResponseProtocol.BlogeExpression {

    /**
     * Success is determined by standard 2xx HTTP status.
     */
    record HttpStatus() implements VisualResourceResponseProtocol {}

    /**
     * Success is determined by matching a body code field.
     *
     * @param codePath path to the code field
     * @param successValues accepted success values
     * @param messagePath optional error message path
     */
    record BodyCode(String codePath,
                    Set<Object> successValues,
                    String messagePath) implements VisualResourceResponseProtocol {
        public BodyCode {
            if (codePath == null || codePath.isBlank()) {
                throw new IllegalArgumentException("codePath must not be blank");
            }
            successValues = successValues == null ? Set.of() : Set.copyOf(successValues);
        }
    }

    /**
     * Success is determined by a boolean flag in the response body.
     *
     * @param flagPath path to the boolean flag
     */
    record BodyFlag(String flagPath) implements VisualResourceResponseProtocol {
        public BodyFlag {
            if (flagPath == null || flagPath.isBlank()) {
                throw new IllegalArgumentException("flagPath must not be blank");
            }
        }
    }

    /**
     * Success is determined by an explicit HTTP status allowlist.
     *
     * @param successCodes accepted HTTP status codes
     */
    record StatusCodes(Set<Integer> successCodes) implements VisualResourceResponseProtocol {
        public StatusCodes {
            successCodes = successCodes == null ? Set.of() : Set.copyOf(successCodes);
        }
    }

    /**
     * Success and payload extraction are determined by BLOGE expressions.
     *
     * @param successExpr boolean success expression
     * @param messageExpr optional error message expression
     * @param payloadExpr optional payload extraction expression
     */
    record BlogeExpression(String successExpr,
                           String messageExpr,
                           String payloadExpr) implements VisualResourceResponseProtocol {
        public BlogeExpression {
            if (successExpr == null || successExpr.isBlank()) {
                throw new IllegalArgumentException("successExpr must not be blank");
            }
        }
    }
}
