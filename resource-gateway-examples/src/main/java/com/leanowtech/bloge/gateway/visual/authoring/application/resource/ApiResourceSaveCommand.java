package com.leanowtech.bloge.gateway.visual.authoring.application.resource;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;

import java.util.List;

/** Frozen compound Resource-save command; capability support is facade-owned. */
public record ApiResourceSaveCommand(String schemaVersion, Connection connection,
                                     ApiResourceCommand resource, DefaultFixture defaultFixture) {
    public static final String SCHEMA_VERSION = "bloge.apiResourceSaveCommand.v1";

    /** Keeps credential-bearing nested commands out of diagnostics. */
    @Override public String toString() {
        return "ApiResourceSaveCommand[schemaVersion=" + schemaVersion + ", connection="
                + (connection == null ? "null" : connection.mode()) + ", resource="
                + (resource == null ? "null" : ApiResourceCommand.class.getSimpleName())
                + ", defaultFixture=" + (defaultFixture == null ? "null" : defaultFixture.kind()) + "]";
    }

    /** Existing or nested-create Connection selection. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "mode")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Connection.Existing.class, name = "EXISTING"),
            @JsonSubTypes.Type(value = Connection.Create.class, name = "CREATE")
    })
    public sealed interface Connection permits Connection.Existing, Connection.Create {
        /** @return stable command discriminator */
        default String mode() { return this instanceof Existing ? "EXISTING" : "CREATE"; }
        /** Selects one committed Connection. */
        record Existing(String connectionId) implements Connection { }
        /** Creates a Connection in the future composite coordinator. */
        record Create(ApiConnectionCommand command) implements Connection {
            /** Never expands a credential-bearing nested command. */
            @Override public String toString() { return "Create[command=ApiConnectionCommand]"; }
        }
        /** @return existing Connection selection */
        static Connection existing(String connectionId) { return new Existing(connectionId); }
        /** @return nested Connection creation */
        static Connection create(ApiConnectionCommand command) { return new Create(command); }
    }

    /** Optional default-Fixture behavior. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = DefaultFixture.None.class, name = "NONE"),
            @JsonSubTypes.Type(value = DefaultFixture.FromExamples.class, name = "FROM_EXAMPLES")
    })
    public sealed interface DefaultFixture permits DefaultFixture.None, DefaultFixture.FromExamples {
        /** @return stable command discriminator */
        default String kind() { return this instanceof None ? "NONE" : "FROM_EXAMPLES"; }
        /** Saves no default Fixture Set. */
        record None() implements DefaultFixture { }
        /** Future Fixture Set generation from named Resource examples. */
        record FromExamples(String displayName, List<String> exampleNames) implements DefaultFixture {
            public FromExamples {
                exampleNames = exampleNames == null ? List.of() : List.copyOf(exampleNames);
            }
        }
        /** @return no-default-Fixture selection */
        static DefaultFixture none() { return new None(); }
        /** @return generation request retained for the future Fixture module */
        static DefaultFixture fromExamples(String displayName, List<String> exampleNames) {
            return new FromExamples(displayName, exampleNames);
        }
    }
}
