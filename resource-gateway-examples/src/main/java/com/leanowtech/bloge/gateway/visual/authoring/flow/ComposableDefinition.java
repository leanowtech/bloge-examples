package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.util.Objects;

/** Exact catalog dependency plus the contracts needed to compile mappings. */
public record ComposableDefinition(ReusableFlowCommand.ComposableRef reference,
                                   SchemaEnvelope input, SchemaEnvelope output) {
    public ComposableDefinition {
        reference = Objects.requireNonNull(reference, "reference");
        input = copy(Objects.requireNonNull(input, "input"));
        output = copy(Objects.requireNonNull(output, "output"));
    }

    @Override public SchemaEnvelope input() { return copy(input); }
    @Override public SchemaEnvelope output() { return copy(output); }

    private static SchemaEnvelope copy(SchemaEnvelope envelope) {
        return new SchemaEnvelope(envelope.format(), envelope.version(), envelope.schema());
    }
}
