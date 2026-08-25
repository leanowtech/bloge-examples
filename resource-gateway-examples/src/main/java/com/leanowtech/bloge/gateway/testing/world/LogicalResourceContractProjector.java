package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceAuth;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceDescriptor;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceParameterMapping;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceResponseProtocol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Projects existing visual resource metadata into an explicitly unconfirmed logical draft. */
public final class LogicalResourceContractProjector {
    private static final List<String> UNKNOWN_FIELDS = List.of(
            "errorClassification", "idempotency", "retryability");

    private LogicalResourceContractProjector() {
    }

    /**
     * Projects structural schemas and a descriptor-backed success candidate without guessing
     * error, idempotency, or retry semantics. The result always requires human confirmation.
     */
    public static LogicalResourceContractProjection project(ResourceDesignContract design,
                                                            VisualResourceDescriptor descriptor) {
        if (design == null || descriptor == null
                || !design.resourceId().equals(descriptor.resourceId())) {
            throw LogicalResourceContractException.projectionInvalid();
        }
        try {
            ResponseSemantics semantics = new ResponseSemantics(
                    ResponseSemantics.SuccessCondition.projected(successCondition(descriptor.responseProtocol())),
                    ResponseSemantics.ErrorClassification.unknown(),
                    ResponseSemantics.Idempotency.UNKNOWN,
                    ResponseSemantics.Retryability.UNKNOWN);
            LogicalResourceContract draft = new LogicalResourceContract(
                    design.contractId(), design.requestSchema(), design.responseSchema(), semantics);
            return new LogicalResourceContractProjection(draft,
                    LogicalResourceContractProjection.ReviewStatus.REQUIRES_CONFIRMATION,
                    descriptorFingerprint(descriptor), UNKNOWN_FIELDS);
        } catch (LogicalResourceContractException exception) {
            throw LogicalResourceContractException.projectionInvalid();
        }
    }

    static String descriptorFingerprint(VisualResourceDescriptor descriptor) {
        if (descriptor == null) {
            throw LogicalResourceContractException.projectionInvalid();
        }
        return VisualBundleFingerprint.fromMaterial(descriptorMaterial(descriptor));
    }

    private static Map<String, Object> descriptorMaterial(VisualResourceDescriptor descriptor) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("resourceId", descriptor.resourceId());
        material.put("urlTemplate", descriptor.urlTemplate());
        material.put("method", descriptor.method());
        material.put("defaultHeaders", descriptor.defaultHeaders());
        material.put("auth", authMaterial(descriptor.authStrategy()));
        material.put("defaultTimeoutMillis", descriptor.defaultTimeout().toMillis());
        material.put("parameterMapping", mappingMaterial(descriptor.parameterMapping()));
        material.put("responseProtocol", responseProtocolMaterial(descriptor.responseProtocol()));
        material.put("payloadPath", normalized(descriptor.payloadPath()));
        material.put("externalWriteContract", externalWriteMaterial(descriptor.externalWriteContract()));
        @SuppressWarnings("unchecked")
        Map<String, Object> canonical = (Map<String, Object>)
                LogicalResourceContractCanonicalizer.canonicalValue(material);
        return canonical;
    }

    private static Map<String, Object> authMaterial(VisualResourceAuth auth) {
        if (auth == null) {
            return Map.of("type", "none");
        }
        if (auth instanceof VisualResourceAuth.ApiKey apiKey) {
            return Map.of("type", "apiKey", "headerName", normalized(apiKey.headerName()));
        }
        if (auth instanceof VisualResourceAuth.Basic) {
            return Map.of("type", "basic");
        }
        return Map.of("type", "bearer");
    }

    private static Map<String, Object> mappingMaterial(VisualResourceParameterMapping mapping) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("path", mapping.pathExpressions());
        material.put("query", mapping.queryExpressions());
        material.put("header", mapping.headerExpressions());
        material.put("cookie", mapping.cookieExpressions());
        material.put("body", normalized(mapping.bodyExpression()));
        return material;
    }

    private static Map<String, Object> responseProtocolMaterial(VisualResourceResponseProtocol protocol) {
        Map<String, Object> material = new LinkedHashMap<>();
        if (protocol instanceof VisualResourceResponseProtocol.BodyCode bodyCode) {
            material.put("type", "bodyCode");
            material.put("codePath", bodyCode.codePath());
            material.put("successValues", sortedValues(bodyCode.successValues()));
            material.put("messagePath", normalized(bodyCode.messagePath()));
        } else if (protocol instanceof VisualResourceResponseProtocol.BodyFlag bodyFlag) {
            material.put("type", "bodyFlag");
            material.put("flagPath", bodyFlag.flagPath());
        } else if (protocol instanceof VisualResourceResponseProtocol.StatusCodes statusCodes) {
            material.put("type", "statusCodes");
            material.put("successCodes", statusCodes.successCodes().stream().sorted().toList());
        } else if (protocol instanceof VisualResourceResponseProtocol.BlogeExpression expression) {
            material.put("type", "blgeExpression");
            material.put("successExpression", expression.successExpr());
            material.put("messageExpression", normalized(expression.messageExpr()));
            material.put("payloadExpression", normalized(expression.payloadExpr()));
        } else {
            material.put("type", "httpStatus");
        }
        return material;
    }

    private static String successCondition(VisualResourceResponseProtocol protocol) {
        if (protocol instanceof VisualResourceResponseProtocol.BodyCode bodyCode) {
            return "body." + bodyCode.codePath() + " in " + sortedValues(bodyCode.successValues());
        }
        if (protocol instanceof VisualResourceResponseProtocol.BodyFlag bodyFlag) {
            return "body." + bodyFlag.flagPath() + " == true";
        }
        if (protocol instanceof VisualResourceResponseProtocol.StatusCodes statusCodes) {
            return "http.status in " + statusCodes.successCodes().stream().sorted().toList();
        }
        if (protocol instanceof VisualResourceResponseProtocol.BlogeExpression expression) {
            return expression.successExpr();
        }
        return "http.status in 200..299";
    }

    private static List<Object> sortedValues(Iterable<?> source) {
        List<Object> values = new ArrayList<>();
        source.forEach(values::add);
        values.sort(Comparator.comparing(String::valueOf));
        return values;
    }

    private static Map<String, Object> externalWriteMaterial(
            VisualResourceDescriptor.ExternalWriteContract contract) {
        if (contract == null) {
            return Map.of();
        }
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", contract.schemaVersion());
        material.put("idempotencyKeyParam", contract.idempotencyKeyParam());
        material.put("idempotencyHeader", contract.idempotencyHeader());
        material.put("reconciliationLookupParam", contract.reconciliationLookupParam());
        material.put("reconcilerRef", contract.reconcilerRef());
        material.put("receiptIdHeader", contract.receiptIdHeader());
        material.put("transactionRefHeader", contract.transactionRefHeader());
        material.put("provider", contract.provider());
        material.put("proofReferenceHeader", contract.proofReferenceHeader());
        material.put("proofFingerprintHeader", contract.proofFingerprintHeader());
        material.put("failureResponseNotCommitted", contract.failureResponseNotCommitted());
        return material;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
