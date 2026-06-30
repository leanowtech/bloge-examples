package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.operator.StreamingOperator;
import com.leanowtech.bloge.core.schema.CollectionSchema;
import com.leanowtech.bloge.core.schema.FieldDescriptor;
import com.leanowtech.bloge.core.schema.MapSchema;
import com.leanowtech.bloge.core.schema.OpaqueSchema;
import com.leanowtech.bloge.core.schema.SchemaDescriptor;
import com.leanowtech.bloge.core.schema.StreamSchema;
import com.leanowtech.bloge.core.schema.StructuredSchema;
import com.leanowtech.bloge.core.schema.TypedSchema;
import com.leanowtech.bloge.core.schema.UnionSchema;
import com.leanowtech.bloge.core.schema.ValidatedSchema;
import com.leanowtech.bloge.core.schema.VersionedSchema;
import com.leanowtech.bloge.core.spi.OperatorAnnotationDetails;
import com.leanowtech.bloge.core.spi.OperatorAnnotationIntrospector;
import com.leanowtech.bloge.core.spi.OperatorMetadata;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Projects registered Java BLOGE operators into visual operator definitions.
 */
@Service
public class JavaOperatorInventoryProjector {

    private static final Set<String> RESERVED_OPERATOR_REFS = Set.of(
            "httpResource",
            "bloge:decisionTable",
            "bloge:transform"
    );

    private final OperatorRegistry registry;

    /**
     * @param registryProvider optional runtime operator registry
     */
    @Autowired
    public JavaOperatorInventoryProjector(ObjectProvider<OperatorRegistry> registryProvider) {
        this(registryProvider == null ? null : registryProvider.getIfAvailable());
    }

    JavaOperatorInventoryProjector(OperatorRegistry registry) {
        this.registry = registry;
    }

    static JavaOperatorInventoryProjector empty() {
        return new JavaOperatorInventoryProjector((OperatorRegistry) null);
    }

    /**
     * @param registry runtime operator registry
     * @return projector bound to the supplied registry
     */
    public static JavaOperatorInventoryProjector forRegistry(OperatorRegistry registry) {
        return new JavaOperatorInventoryProjector(registry);
    }

    /**
     * @return all registry-discoverable Java operators projected to visual definitions
     */
    public List<OperatorDefinition> project() {
        if (registry == null) {
            return List.of();
        }
        List<String> names;
        try {
            names = registry.discover("*");
        } catch (RuntimeException ex) {
            return List.of();
        }
        List<OperatorDefinition> operators = new ArrayList<>();
        for (String name : names) {
            if (RESERVED_OPERATOR_REFS.contains(name)) {
                continue;
            }
            Optional<OperatorDefinition> operator = project(name);
            operator.ifPresent(operators::add);
        }
        return operators;
    }

    private Optional<OperatorDefinition> project(String name) {
        try {
            OperatorMetadata metadata = registry.metadata(name);
            Object operator = registry.lookup(name);
            return Optional.of(project(name, metadata, operator));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private OperatorDefinition project(String name, OperatorMetadata metadata, Object operator) {
        OperatorAnnotationDetails details = OperatorAnnotationIntrospector.introspect(operator);
        SchemaProjection input = projectSchema(metadata.inputSchema(), "/operators/" + name + "/ports/inputs/input");
        SchemaProjection output = projectSchema(metadata.outputSchema(), "/operators/" + name + "/ports/outputs/output");
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        diagnostics.addAll(input.diagnostics());
        diagnostics.addAll(output.diagnostics());
        boolean streaming = operator instanceof StreamingOperator<?, ?> || metadata.outputSchema() instanceof StreamSchema;
        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                name,
                "1.0.0",
                new OperatorDefinition.Display(displayName(name), details.description(), tags(details, streaming)),
                new OperatorDefinition.Source(streaming ? "java-streaming-operator" : "java-operator",
                        "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("input",
                                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", input.schema()),
                                true,
                                inputDescription(metadata))),
                        List.of(new OperatorDefinition.Port("output",
                                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", output.schema()),
                                true,
                                outputDescription(metadata, streaming)))
                ),
                SchemaEnvelope.opaque(),
                capabilities(operator, streaming),
                OperatorDefinition.Policy.unrestricted(),
                new OperatorDefinition.Lowering("native", name, Map.of(
                        "javaClass", operator == null ? "" : operator.getClass().getName()
                )),
                diagnostics
        );
    }

    private static OperatorDefinition.Capabilities capabilities(Object operator, boolean streaming) {
        return new OperatorDefinition.Capabilities(
                effect(sideEffect(operator)),
                idempotency(idempotency(operator)),
                streaming,
                false
        );
    }

    private static SideEffectType sideEffect(Object operator) {
        if (operator instanceof Operator<?, ?> typed) {
            return typed.sideEffectType();
        }
        if (operator instanceof StreamingOperator<?, ?> streaming) {
            return streaming.sideEffectType();
        }
        return SideEffectType.MIXED;
    }

    private static Idempotency idempotency(Object operator) {
        if (operator instanceof Operator<?, ?> typed) {
            return typed.idempotency();
        }
        if (operator instanceof StreamingOperator<?, ?> streaming) {
            return streaming.idempotency();
        }
        return Idempotency.UNKNOWN;
    }

    private static String effect(SideEffectType sideEffect) {
        return switch (sideEffect == null ? SideEffectType.MIXED : sideEffect) {
            case READ_ONLY -> "READ_EXTERNAL";
            case WRITE -> "WRITE_EXTERNAL";
            case EXTERNAL_CALL, MIXED -> "EXTERNAL";
        };
    }

    private static String idempotency(Idempotency idempotency) {
        return switch (idempotency == null ? Idempotency.UNKNOWN : idempotency) {
            case IDEMPOTENT -> "IDEMPOTENT";
            case NOT_IDEMPOTENT -> "NON_IDEMPOTENT";
            case UNKNOWN -> "UNKNOWN";
        };
    }

    private static List<String> tags(OperatorAnnotationDetails details, boolean streaming) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        tags.add("java");
        if (streaming) {
            tags.add("streaming");
        }
        tags.addAll(details.tags());
        return List.copyOf(tags);
    }

    private static String inputDescription(OperatorMetadata metadata) {
        Class<?> input = metadata.inputClass();
        return input == null ? "Java operator input." : "Java input type: " + input.getName();
    }

    private static String outputDescription(OperatorMetadata metadata, boolean streaming) {
        Class<?> output = metadata.outputClass();
        String prefix = streaming ? "Java streaming output chunk type: " : "Java output type: ";
        return output == null ? "Java operator output." : prefix + output.getName();
    }

    private static String displayName(String operatorRef) {
        if (operatorRef == null || operatorRef.isBlank()) {
            return "";
        }
        String spaced = operatorRef.replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace('.', ' ')
                .replace('-', ' ')
                .replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static SchemaProjection projectSchema(SchemaDescriptor schema, String target) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        Map<String, Object> projected = projectSchema(schema == null ? OpaqueSchema.INSTANCE : schema,
                target, diagnostics);
        return new SchemaProjection(projected, diagnostics);
    }

    private static Map<String, Object> projectSchema(SchemaDescriptor schema,
                                                     String target,
                                                     List<VisualDiagnostic> diagnostics) {
        if (schema instanceof OpaqueSchema) {
            return SchemaEnvelope.opaque().schema();
        }
        if (schema instanceof TypedSchema typedSchema) {
            return typedSchema(typedSchema.type(), diagnostics, target);
        }
        if (schema instanceof StructuredSchema structuredSchema) {
            return structuredSchema(structuredSchema, diagnostics, target);
        }
        if (schema instanceof CollectionSchema collectionSchema) {
            return Map.of(
                    "type", "array",
                    "items", projectSchema(collectionSchema.elementSchema(), target + "/items", diagnostics)
            );
        }
        if (schema instanceof MapSchema mapSchema) {
            return mapSchema(mapSchema, diagnostics, target);
        }
        if (schema instanceof StreamSchema streamSchema) {
            return projectSchema(streamSchema.elementSchema(), target + "/element", diagnostics);
        }
        if (schema instanceof VersionedSchema versionedSchema) {
            Map<String, Object> projected = new LinkedHashMap<>(
                    projectSchema(versionedSchema.delegate(), target, diagnostics));
            projected.put("$comment", "schemaVersion=" + versionedSchema.schemaVersion());
            return projected;
        }
        if (schema instanceof ValidatedSchema validatedSchema) {
            return projectSchema(validatedSchema.expected(), target, diagnostics);
        }
        if (schema instanceof UnionSchema) {
            diagnostics.add(VisualDiagnostic.warning("visual.operator.javaSchema.unionOpaque",
                    "Java operator schema union cannot be represented by the supported visual schema subset; using opaque schema.",
                    target));
            return SchemaEnvelope.opaque().schema();
        }
        diagnostics.add(VisualDiagnostic.warning("visual.operator.javaSchema.unsupported",
                "Java operator schema '%s' cannot be represented by the supported visual schema subset; using opaque schema."
                        .formatted(schema.getClass().getSimpleName()),
                target));
        return SchemaEnvelope.opaque().schema();
    }

    private static Map<String, Object> structuredSchema(StructuredSchema schema,
                                                        List<VisualDiagnostic> diagnostics,
                                                        String target) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (FieldDescriptor field : schema.fields()) {
            String fieldTarget = target + "/properties/" + field.name();
            Map<String, Object> property = field.nested() == null
                    ? typedSchema(field.type(), diagnostics, fieldTarget)
                    : projectSchema(field.nested(), fieldTarget, diagnostics);
            if (!field.allowedValues().isEmpty()) {
                property = new LinkedHashMap<>(property);
                property.put("enum", field.allowedValues());
            }
            if (field.description() != null && !field.description().isBlank()) {
                property = new LinkedHashMap<>(property);
                property.put("description", field.description());
            }
            properties.put(field.name(), property);
            if (field.required()) {
                required.add(field.name());
            }
        }
        return SchemaEnvelope.object(properties, required).schema();
    }

    private static Map<String, Object> mapSchema(MapSchema schema,
                                                 List<VisualDiagnostic> diagnostics,
                                                 String target) {
        Map<String, Object> valueSchema = projectSchema(schema.valueSchema(), target + "/additionalProperties",
                diagnostics);
        Map<String, Object> projected = new LinkedHashMap<>();
        projected.put("type", "object");
        projected.put("additionalProperties", valueSchema);
        if (!(schema.keySchema() instanceof TypedSchema typedSchema)
                || !String.class.equals(typedSchema.type())) {
            diagnostics.add(VisualDiagnostic.warning("visual.operator.javaSchema.mapKeyOpaque",
                    "Java map key schema is not a plain string; visual authoring treats object keys as strings.",
                    target + "/propertyNames"));
        }
        return projected;
    }

    private static Map<String, Object> typedSchema(Class<?> type,
                                                   List<VisualDiagnostic> diagnostics,
                                                   String target) {
        Class<?> effective = type == null ? Object.class : type;
        if (String.class.equals(effective) || Character.class.equals(effective) || char.class.equals(effective)) {
            return Map.of("type", "string");
        }
        if (Boolean.class.equals(effective) || boolean.class.equals(effective)) {
            return Map.of("type", "boolean");
        }
        if (integerType(effective)) {
            return Map.of("type", "integer");
        }
        if (numberType(effective)) {
            return Map.of("type", "number");
        }
        if (Duration.class.equals(effective)) {
            return Map.of("type", "duration");
        }
        if (LocalDate.class.equals(effective)) {
            return Map.of("type", "string", "format", "date");
        }
        if (OffsetDateTime.class.equals(effective)
                || ZonedDateTime.class.equals(effective)
                || Date.class.isAssignableFrom(effective)) {
            return Map.of("type", "datetime");
        }
        if (Map.class.isAssignableFrom(effective)) {
            return Map.of("type", "object", "additionalProperties", true);
        }
        if (List.class.isAssignableFrom(effective) || effective.isArray()) {
            diagnostics.add(VisualDiagnostic.warning("visual.operator.javaSchema.collectionOpaque",
                    "Java collection type '%s' has no element schema; using opaque array items."
                            .formatted(effective.getName()),
                    target));
            return Map.of("type", "array", "items", SchemaEnvelope.opaque().schema());
        }
        if (Object.class.equals(effective)) {
            return SchemaEnvelope.opaque().schema();
        }
        diagnostics.add(VisualDiagnostic.warning("visual.operator.javaSchema.typedOpaque",
                "Java type '%s' has no field-level schema; using opaque object schema."
                        .formatted(effective.getName()),
                target));
        return SchemaEnvelope.opaque().schema();
    }

    private static boolean integerType(Class<?> type) {
        return Byte.class.equals(type)
                || byte.class.equals(type)
                || Short.class.equals(type)
                || short.class.equals(type)
                || Integer.class.equals(type)
                || int.class.equals(type)
                || Long.class.equals(type)
                || long.class.equals(type);
    }

    private static boolean numberType(Class<?> type) {
        return Float.class.equals(type)
                || float.class.equals(type)
                || Double.class.equals(type)
                || double.class.equals(type)
                || BigDecimal.class.equals(type)
                || Number.class.equals(type);
    }

    private record SchemaProjection(Map<String, Object> schema, List<VisualDiagnostic> diagnostics) {
        private SchemaProjection {
            schema = schema == null ? SchemaEnvelope.opaque().schema() : new LinkedHashMap<>(schema);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }
}
