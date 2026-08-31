package com.leanowtech.bloge.gateway.visualadapter.authoring.flow;

import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
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

    private static SchemaEnvelope schema(String property) {
        return SchemaEnvelope.object(Map.of(property, Map.of("type", "string")), List.of(property));
    }
}
