package com.leanowtech.bloge.graphengine.server.config;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.leanowtech.bloge.core.runtime.registry.VersionRoutingPolicy;
import com.leanowtech.bloge.core.schema.SchemaDescriptor;
import com.leanowtech.bloge.core.schema.SchemaCompatibility;
import com.leanowtech.bloge.core.schema.SchemaDescriptorJsonCodec;

import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.io.IOException;
import java.util.Map;

/**
 * Shared Jackson polymorphic metadata used by the graph-engine server API.
 */
public final class GraphEngineServerJacksonSupport {
    private GraphEngineServerJacksonSupport() {
    }

    /**
     * Registers the server's polymorphic mix-ins on an {@link ObjectMapper}.
     *
     * @param objectMapper mapper to customize
     */
    public static void registerMixins(ObjectMapper objectMapper) {
        objectMapper.addMixIn(VersionRoutingPolicy.class, VersionRoutingPolicyMixin.class);
        objectMapper.addMixIn(SchemaCompatibility.class, SchemaCompatibilityMixin.class);
        objectMapper.addMixIn(SchemaDescriptor.class, SchemaDescriptorMixin.class);
    }

    /**
     * Registers the server's polymorphic mix-ins on a Spring Jackson builder.
     *
     * @param builder builder to customize
     */
    public static void customize(Jackson2ObjectMapperBuilder builder) {
        builder.mixIn(VersionRoutingPolicy.class, VersionRoutingPolicyMixin.class);
        builder.mixIn(SchemaCompatibility.class, SchemaCompatibilityMixin.class);
        builder.mixIn(SchemaDescriptor.class, SchemaDescriptorMixin.class);
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = VersionRoutingPolicy.Latest.class, name = "latest"),
            @JsonSubTypes.Type(value = VersionRoutingPolicy.Pinned.class, name = "pinned"),
            @JsonSubTypes.Type(value = VersionRoutingPolicy.Canary.class, name = "canary")
    })
    private interface VersionRoutingPolicyMixin {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = SchemaCompatibility.FullyCompatible.class, name = "fully-compatible"),
            @JsonSubTypes.Type(value = SchemaCompatibility.BackwardCompatible.class, name = "backward-compatible"),
            @JsonSubTypes.Type(value = SchemaCompatibility.BreakingChange.class, name = "breaking-change")
    })
    private interface SchemaCompatibilityMixin {
    }

    @JsonSerialize(using = SchemaDescriptorSerializer.class)
    @JsonDeserialize(using = SchemaDescriptorDeserializer.class)
    private interface SchemaDescriptorMixin {
    }

    private static final class SchemaDescriptorSerializer extends JsonSerializer<SchemaDescriptor> {
        @Override
        public void serialize(SchemaDescriptor value, JsonGenerator generator, SerializerProvider serializers)
                throws IOException {
            generator.writeObject(value == null ? null : value.toMap());
        }
    }

    private static final class SchemaDescriptorDeserializer extends JsonDeserializer<SchemaDescriptor> {
        @Override
        public SchemaDescriptor deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            Map<String, Object> value = parser.readValueAs(new TypeReference<>() {
            });
            return SchemaDescriptorJsonCodec.fromMap(value);
        }
    }
}
