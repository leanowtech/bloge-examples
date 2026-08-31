package com.leanowtech.bloge.gateway.visualadapter.authoring.flow;

import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowVersion;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.StoredApiResource;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiResourceComposableCatalogTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant-a", "project-a", "test");
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    @Test
    void resolvesOnlyTheExactCommittedApiResourceRevisionAndFingerprint() {
        ApiResourceCommitStore resources = mock(ApiResourceCommitStore.class);
        ApiResourceSpec resource = mock(ApiResourceSpec.class);
        StoredApiResource stored = mock(StoredApiResource.class);
        ApiResourceCommand.Contract contract = new ApiResourceCommand.Contract(
                schema("customerId"), schema("tier"));
        when(resource.resourceId()).thenReturn("customer.profile");
        when(resource.revision()).thenReturn(3);
        when(resource.fingerprint()).thenReturn(FINGERPRINT);
        when(resource.contract()).thenReturn(contract);
        when(stored.resource()).thenReturn(resource);
        when(resources.findRevision(SCOPE, "customer.profile", 3)).thenReturn(Optional.of(stored));
        ApiResourceComposableCatalog catalog = new ApiResourceComposableCatalog(resources);

        ReusableFlowCommand.ComposableRef.ApiResource exact =
                new ReusableFlowCommand.ComposableRef.ApiResource("customer.profile", 3, FINGERPRINT);
        assertThat(catalog.resolve(SCOPE, exact)).hasValueSatisfying(definition -> {
            assertThat(definition.reference()).isEqualTo(exact);
            assertThat(definition.input()).isEqualTo(contract.input());
            assertThat(definition.output()).isEqualTo(contract.output());
        });
        assertThat(catalog.resolve(SCOPE, new ReusableFlowCommand.ComposableRef.ApiResource(
                "customer.profile", 3, "sha256:" + "b".repeat(64)))).isEmpty();
        assertThat(catalog.resolve(SCOPE, new ReusableFlowCommand.ComposableRef.FlowVersion(
                "published-flow", 1, FINGERPRINT))).isEmpty();
    }

    @Test
    void resolvesOnlyTheExactImmutableFlowVersionCoordinate() {
        ApiResourceCommitStore resources = mock(ApiResourceCommitStore.class);
        ReusableFlowPublicationStore publications = mock(ReusableFlowPublicationStore.class);
        ReusableFlowVersion version = flowVersion();
        when(publications.findVersion(SCOPE, "publication-tool", 2)).thenReturn(Optional.of(version));
        ApiResourceComposableCatalog catalog = new ApiResourceComposableCatalog(resources, publications);

        ReusableFlowCommand.ComposableRef.FlowVersion exact =
                new ReusableFlowCommand.ComposableRef.FlowVersion(
                        "publication-tool", 2, version.fingerprint());
        assertThat(catalog.resolve(SCOPE, exact)).hasValueSatisfying(definition -> {
            assertThat(definition.reference()).isEqualTo(exact);
            assertThat(definition.input()).isEqualTo(version.contract().input());
            assertThat(definition.output()).isEqualTo(version.contract().output());
        });
        assertThat(catalog.resolve(SCOPE, new ReusableFlowCommand.ComposableRef.FlowVersion(
                "publication-tool", 2, "sha256:" + "9".repeat(64)))).isEmpty();
    }

    private static ReusableFlowVersion flowVersion() {
        return new ReusableFlowVersion(ReusableFlowVersion.SCHEMA_VERSION, "publication-tool", 2,
                "sha256:" + "8".repeat(64), new ReusableFlowVersion.Source(
                "draft-tool", 3, "sha256:" + "7".repeat(64)), "tool", "Tool",
                ReusableFlowCommand.Kind.TOOL, "description",
                new ReusableFlowCommand.Contract(schema("id"), schema("result")),
                new ReusableFlowCommand.Graph(List.of(new ReusableFlowCommand.Node(
                        "node", "Node", new ReusableFlowCommand.ComposableRef.ApiResource(
                        "customer", 1, "sha256:" + "6".repeat(64)), List.of())),
                        new ReusableFlowCommand.Output("node", "$")),
                java.time.Instant.parse("2026-09-01T00:00:00Z"), "alice",
                ReusableFlowVersion.Status.PUBLISHED);
    }

    private static SchemaEnvelope schema(String property) {
        return SchemaEnvelope.object(Map.of(property, Map.of("type", "string")), List.of(property));
    }
}
