package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Frozen wire command for one reusable Tool or Solution DAG. */
public record ReusableFlowCommand(String schemaVersion, Flow flow) {
    public static final String SCHEMA_VERSION = "bloge.reusableFlowSaveCommand.v1";

    public ReusableFlowCommand {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        flow = Objects.requireNonNull(flow, "flow");
    }

    /** User-authored Flow content; server identity and revision fields are deliberately absent. */
    public record Flow(String displayName, Kind kind, String description, Contract contract,
                       Graph graph, Layout layout) {
        public Flow {
            displayName = Objects.requireNonNull(displayName, "displayName");
            kind = Objects.requireNonNull(kind, "kind");
            description = Objects.requireNonNull(description, "description");
            contract = Objects.requireNonNull(contract, "contract");
            graph = Objects.requireNonNull(graph, "graph");
            layout = Objects.requireNonNull(layout, "layout");
        }
    }

    public enum Kind { TOOL, SOLUTION }

    /** Stable input/output contract shared by save, simulation and publication. */
    public record Contract(SchemaEnvelope input, SchemaEnvelope output) {
        public Contract {
            input = copyEnvelope(Objects.requireNonNull(input, "input"));
            output = copyEnvelope(Objects.requireNonNull(output, "output"));
        }

        @Override public SchemaEnvelope input() { return copyEnvelope(input); }
        @Override public SchemaEnvelope output() { return copyEnvelope(output); }

        private static SchemaEnvelope copyEnvelope(SchemaEnvelope envelope) {
            return new SchemaEnvelope(envelope.format(), envelope.version(), envelope.schema());
        }
    }

    /** Mapping-defined DAG; business edges are derived from NODE_OUTPUT sources. */
    public record Graph(List<Node> nodes, Output output) {
        public Graph {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            output = Objects.requireNonNull(output, "output");
        }
    }

    /** One exact composable dependency and its complete input mappings. */
    public record Node(String nodeId, String label, ComposableRef use, List<Input> inputs) {
        public Node {
            nodeId = Objects.requireNonNull(nodeId, "nodeId");
            label = Objects.requireNonNull(label, "label");
            use = Objects.requireNonNull(use, "use");
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
        }
    }

    public record Input(String to, MappingSource from) {
        public Input {
            to = Objects.requireNonNull(to, "to");
            from = Objects.requireNonNull(from, "from");
        }
    }

    public record Output(String nodeId, String path) {
        public Output {
            nodeId = Objects.requireNonNull(nodeId, "nodeId");
            path = Objects.requireNonNull(path, "path");
        }
    }

    /** Layout is presentation state and is excluded from the content fingerprint. */
    public record Layout(Map<String, Position> nodes) {
        public Layout {
            nodes = nodes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(nodes));
        }
    }

    public record Position(double x, double y) { }

    /** Exact catalog coordinate that may be placed in a Flow. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ComposableRef.ApiResource.class, name = "API_RESOURCE"),
            @JsonSubTypes.Type(value = ComposableRef.FlowVersion.class, name = "FLOW_VERSION"),
            @JsonSubTypes.Type(value = ComposableRef.OperatorVersion.class, name = "OPERATOR_VERSION")
    })
    public sealed interface ComposableRef permits ComposableRef.ApiResource, ComposableRef.FlowVersion,
            ComposableRef.OperatorVersion {
        String id();
        int revision();
        String fingerprint();

        record ApiResource(String resourceId, int revision, String fingerprint) implements ComposableRef {
            public ApiResource {
                resourceId = Objects.requireNonNull(resourceId, "resourceId");
                fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
            }
            @Override public String id() { return resourceId; }
        }

        record FlowVersion(String publicationId, int revision, String fingerprint) implements ComposableRef {
            public FlowVersion {
                publicationId = Objects.requireNonNull(publicationId, "publicationId");
                fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
            }
            @Override public String id() { return publicationId; }
        }

        /** Exact operator revision placeable as one reusable-Flow DAG node. */
        record OperatorVersion(String libraryId, int revision, String operatorRef,
                               String fingerprint) implements ComposableRef {
            public OperatorVersion {
                libraryId = Objects.requireNonNull(libraryId, "libraryId");
                operatorRef = Objects.requireNonNull(operatorRef, "operatorRef");
                fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
            }
            @Override public String id() { return libraryId + ":" + operatorRef; }
        }
    }

    /** Single source of truth for a node input and its derived visual edge. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = MappingSource.FlowInput.class, name = "FLOW_INPUT"),
            @JsonSubTypes.Type(value = MappingSource.NodeOutput.class, name = "NODE_OUTPUT"),
            @JsonSubTypes.Type(value = MappingSource.Constant.class, name = "CONSTANT")
    })
    public sealed interface MappingSource
            permits MappingSource.FlowInput, MappingSource.NodeOutput, MappingSource.Constant {
        record FlowInput(String path) implements MappingSource {
            public FlowInput { path = Objects.requireNonNull(path, "path"); }
        }
        record NodeOutput(String nodeId, String path) implements MappingSource {
            public NodeOutput {
                nodeId = Objects.requireNonNull(nodeId, "nodeId");
                path = Objects.requireNonNull(path, "path");
            }
        }
        record Constant(JsonNode value) implements MappingSource {
            public Constant { value = value == null ? null : value.deepCopy(); }
            @Override public JsonNode value() { return value == null ? null : value.deepCopy(); }
        }
    }
}
