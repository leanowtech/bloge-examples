package com.leanowtech.bloge.gateway.visualadapter;

import com.leanowtech.bloge.gateway.resource.ParameterMapping;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceAuth;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceDescriptor;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceParameterMapping;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceResponseProtocol;
import com.leanowtech.bloge.operators.http.HttpRequestInput;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the gateway resource descriptor adapter used to keep {@code gateway.visual} generic.
 */
class ResourceRegistryVisualAdapterTest {

    @Test
    void exposesGatewayDescriptorsAsVisualDescriptors() {
        ResourceDescriptor descriptor = gatewayDescriptor(
                new HttpRequestInput.ApiKeyAuth("X-Api-Key", "secret"),
                new ResponseProtocol.BodyCode("code", java.util.Set.of("OK"), "message")
        );
        ResourceRegistryVisualAdapter adapter = new ResourceRegistryVisualAdapter(new SingleResourceRegistry(
                descriptor));

        VisualResourceDescriptor visual = adapter.resolve("orders.search");

        assertThat(visual.resourceId()).isEqualTo("orders.search");
        assertThat(visual.defaultHeaders()).containsEntry("Accept", "application/json");
        assertThat(visual.authStrategy()).isEqualTo(new VisualResourceAuth.ApiKey("X-Api-Key", "secret"));
        assertThat(visual.parameterMapping().pathExpressions()).containsEntry("tenantId", "ctx.params.tenantId");
        assertThat(visual.parameterMapping().queryExpressions()).containsEntry("q", "ctx.params.query");
        assertThat(visual.parameterMapping().headerExpressions()).containsEntry("X-Trace", "ctx.params.trace");
        assertThat(visual.parameterMapping().cookieExpressions()).containsEntry("SESSION", "ctx.params.session");
        assertThat(visual.parameterMapping().bodyExpression()).isEqualTo("ctx.params.body");
        assertThat(visual.responseProtocol()).isEqualTo(new VisualResourceResponseProtocol.BodyCode(
                "code", java.util.Set.of("OK"), "message"));
        assertThat(visual.externalWriteContract()).isNotNull();
        assertThat(visual.externalWriteContract().conformant()).isTrue();
        assertThat(visual.externalWriteContract().reconcilerRef()).isEqualTo("orders.status");
    }

    @Test
    void convertsVisualDescriptorsBackToGatewayDescriptors() {
        VisualResourceDescriptor visual = new VisualResourceDescriptor(
                "orders.search",
                "https://orders.example.test/{tenantId}/orders",
                "post",
                Map.of("Accept", "application/json"),
                new VisualResourceAuth.Basic("user", "password"),
                Duration.ofSeconds(7),
                new VisualResourceParameterMapping(
                        Map.of("tenantId", "ctx.params.tenantId"),
                        Map.of("q", "ctx.params.query"),
                        Map.of("X-Trace", "ctx.params.trace"),
                        Map.of("SESSION", "ctx.params.session"),
                        "ctx.params.body"
                ),
                new VisualResourceResponseProtocol.BlogeExpression(
                        "ctx.statusCode == 200",
                        "ctx.body.message",
                        "ctx.body.data"
                ),
                "data"
        );

        ResourceDescriptor gateway = ResourceRegistryVisualAdapter.toGateway(visual);

        assertThat(gateway.method()).isEqualTo("POST");
        assertThat(gateway.authStrategy()).isEqualTo(new HttpRequestInput.BasicAuth("user", "password"));
        assertThat(gateway.parameterMapping().cookieExpressions()).containsEntry("SESSION", "ctx.params.session");
        assertThat(gateway.responseProtocol()).isEqualTo(new ResponseProtocol.BlgeExpression(
                "ctx.statusCode == 200",
                "ctx.body.message",
                "ctx.body.data"
        ));
    }

    private static ResourceDescriptor gatewayDescriptor(HttpRequestInput.HttpAuth auth,
                                                        ResponseProtocol responseProtocol) {
        return new ResourceDescriptor(
                "orders.search",
                "https://orders.example.test/{tenantId}/orders",
                "POST",
                Map.of("Accept", "application/json"),
                auth,
                Duration.ofSeconds(7),
                new ParameterMapping(
                        Map.of("tenantId", "ctx.params.tenantId"),
                        Map.of("q", "ctx.params.query"),
                        Map.of("X-Trace", "ctx.params.trace"),
                        Map.of("SESSION", "ctx.params.session"),
                        "ctx.params.body"
                ),
                responseProtocol,
                "data",
                new ResourceDescriptor.ExternalWriteContract(
                        ResourceDescriptor.ExternalWriteContract.SCHEMA_VERSION,
                        "idempotencyKey", "Idempotency-Key", "lookupRef", "orders.status",
                        "X-Commit-Receipt", "X-Transaction-Id", "orders", "", "", false)
        );
    }

    private record SingleResourceRegistry(ResourceDescriptor descriptor) implements ResourceRegistry {
        @Override
        public ResourceDescriptor resolve(String resourceId) {
            return descriptor;
        }

        @Override
        public boolean contains(String resourceId) {
            return descriptor.resourceId().equals(resourceId);
        }

        @Override
        public Collection<ResourceDescriptor> all() {
            return java.util.List.of(descriptor);
        }
    }
}
