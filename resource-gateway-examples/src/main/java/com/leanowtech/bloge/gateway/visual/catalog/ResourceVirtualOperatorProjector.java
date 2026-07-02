package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Projects resource descriptors into schema-aware virtual canvas operators.
 */
@Component
public class ResourceVirtualOperatorProjector {

    /**
     * Creates a virtual operator for one resource descriptor.
     *
     * @param descriptor resource descriptor
     * @param contract optional visual contract
     * @return operator definition
     */
    public OperatorDefinition project(ResourceDescriptor descriptor,
                                      Optional<ResourceDesignContract> contract) {
        ResourceDesignContract design = contract.orElse(null);
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (design == null) {
            diagnostics.add(VisualDiagnostic.warning(
                    "visual.resource.contractMissing",
                    "Resource has no visual design contract; schema constraints are opaque.",
                    "/operators/resource:" + descriptor.resourceId()));
        } else if (ResourceDesignContract.STATUS_DEPRECATED.equals(design.status())) {
            diagnostics.add(VisualDiagnostic.warning(
                    "visual.operator.lifecycle.deprecated",
                    "Resource design contract '%s' for resource '%s' is deprecated; existing drafts can still be reviewed, but production promotion should migrate to an active resource contract."
                            .formatted(design.contractId(), descriptor.resourceId()),
                    "/resource-design-contracts/" + design.contractId(),
                    Map.of(
                            "contractId", design.contractId(),
                            "resourceId", descriptor.resourceId(),
                            "contractStatus", design.status()
                    )));
        }

        SchemaEnvelope requestSchema = design == null ? SchemaEnvelope.opaque() : design.requestSchema();
        SchemaEnvelope responseSchema = design == null ? SchemaEnvelope.opaque() : design.responseSchema();
        String displayName = design == null ? readableName(descriptor.resourceId()) : design.displayName();
        String description = design == null ? descriptor.urlTemplate() : design.description();
        List<String> tags = design == null ? List.of("resource") : withResourceTag(design.tags());

        Map<String, Object> loweringParameters = new LinkedHashMap<>();
        loweringParameters.put("resourceId", descriptor.resourceId());
        loweringParameters.put("payloadPath", descriptor.payloadPath() == null ? "" : descriptor.payloadPath());
        loweringParameters.put("method", descriptor.method());

        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                "resource:" + descriptor.resourceId(),
                "1.0.0",
                new OperatorDefinition.Display(displayName, description, tags),
                new OperatorDefinition.Source(
                        "resource-descriptor",
                        descriptor.resourceId(),
                        descriptor.method(),
                        descriptor.urlTemplate(),
                        true
                ),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("params", requestSchema, true,
                                "Parameters passed to the resource descriptor.")),
                        List.of(new OperatorDefinition.Port("payload", responseSchema, true,
                                "Payload extracted by the resource response protocol."))
                ),
                resourceConfigSchema(),
                new OperatorDefinition.Capabilities(effectFor(descriptor.method()), idempotencyFor(descriptor.method()),
                        false, false, descriptor.authStrategy() != null),
                new OperatorDefinition.Lowering("resource-descriptor", "httpResource", loweringParameters),
                diagnostics
        );
    }

    private static SchemaEnvelope resourceConfigSchema() {
        return SchemaEnvelope.object(Map.of(
                "timeout", Map.of("type", "string", "description", "Optional BLOGE duration literal such as 3s."),
                "retryAttempts", Map.of("type", "integer"),
                "fallback", Map.of("type", "boolean")
        ), List.of());
    }

    private static String effectFor(String method) {
        return "GET".equalsIgnoreCase(method) ? "READ_EXTERNAL" : "WRITE_EXTERNAL";
    }

    private static String idempotencyFor(String method) {
        return switch (method == null ? "" : method.toUpperCase()) {
            case "GET", "HEAD", "PUT", "DELETE" -> "IDEMPOTENT";
            default -> "UNKNOWN";
        };
    }

    private static List<String> withResourceTag(List<String> tags) {
        if (tags.contains("resource")) {
            return tags;
        }
        return java.util.stream.Stream.concat(tags.stream(), java.util.stream.Stream.of("resource")).toList();
    }

    private static String readableName(String resourceId) {
        return resourceId.replace('.', ' ').replace('-', ' ');
    }
}
