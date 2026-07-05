package com.leanowtech.bloge.gateway.visualadapter;

import com.leanowtech.bloge.gateway.resource.ParameterMapping;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceAuth;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceDescriptor;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceParameterMapping;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceRegistry;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceResponseProtocol;
import com.leanowtech.bloge.operators.http.HttpRequestInput;

import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * Resource-gateway adapter exposing runtime resource descriptors through the visual descriptor port.
 */
@Component
public class ResourceRegistryVisualAdapter implements VisualResourceRegistry {

    private final ResourceRegistry delegate;

    /**
     * @param delegate runtime resource descriptor registry
     */
    public ResourceRegistryVisualAdapter(ResourceRegistry delegate) {
        this.delegate = delegate;
    }

    @Override
    public VisualResourceDescriptor resolve(String resourceId) {
        return toVisual(delegate.resolve(resourceId));
    }

    @Override
    public boolean contains(String resourceId) {
        return delegate.contains(resourceId);
    }

    @Override
    public Collection<VisualResourceDescriptor> all() {
        return delegate.all().stream()
                .map(ResourceRegistryVisualAdapter::toVisual)
                .toList();
    }

    /**
     * Converts a runtime gateway descriptor into the visual descriptor shape.
     *
     * @param descriptor runtime descriptor
     * @return visual descriptor
     */
    public static VisualResourceDescriptor toVisual(ResourceDescriptor descriptor) {
        if (descriptor == null) {
            return null;
        }
        return new VisualResourceDescriptor(
                descriptor.resourceId(),
                descriptor.urlTemplate(),
                descriptor.method(),
                descriptor.defaultHeaders(),
                authToVisual(descriptor.authStrategy()),
                descriptor.defaultTimeout(),
                mappingToVisual(descriptor.parameterMapping()),
                protocolToVisual(descriptor.responseProtocol()),
                descriptor.payloadPath()
        );
    }

    /**
     * Converts a visual descriptor back to the runtime gateway descriptor shape.
     *
     * @param descriptor visual descriptor
     * @return runtime descriptor
     */
    public static ResourceDescriptor toGateway(VisualResourceDescriptor descriptor) {
        if (descriptor == null) {
            return null;
        }
        return new ResourceDescriptor(
                descriptor.resourceId(),
                descriptor.urlTemplate(),
                descriptor.method(),
                descriptor.defaultHeaders(),
                authToGateway(descriptor.authStrategy()),
                descriptor.defaultTimeout(),
                mappingToGateway(descriptor.parameterMapping()),
                protocolToGateway(descriptor.responseProtocol()),
                descriptor.payloadPath()
        );
    }

    private static VisualResourceParameterMapping mappingToVisual(ParameterMapping source) {
        if (source == null) {
            return VisualResourceParameterMapping.empty();
        }
        return new VisualResourceParameterMapping(
                source.pathExpressions(),
                source.queryExpressions(),
                source.headerExpressions(),
                source.cookieExpressions(),
                source.bodyExpression()
        );
    }

    private static ParameterMapping mappingToGateway(VisualResourceParameterMapping source) {
        if (source == null) {
            return ParameterMapping.empty();
        }
        return new ParameterMapping(
                source.pathExpressions(),
                source.queryExpressions(),
                source.headerExpressions(),
                source.cookieExpressions(),
                source.bodyExpression()
        );
    }

    private static VisualResourceAuth authToVisual(HttpRequestInput.HttpAuth source) {
        return switch (source) {
            case null -> null;
            case HttpRequestInput.BearerAuth bearer -> new VisualResourceAuth.Bearer(bearer.token());
            case HttpRequestInput.BasicAuth basic -> new VisualResourceAuth.Basic(basic.username(), basic.password());
            case HttpRequestInput.ApiKeyAuth apiKey -> new VisualResourceAuth.ApiKey(apiKey.headerName(),
                    apiKey.key());
        };
    }

    private static HttpRequestInput.HttpAuth authToGateway(VisualResourceAuth source) {
        return switch (source) {
            case null -> null;
            case VisualResourceAuth.Bearer bearer -> new HttpRequestInput.BearerAuth(bearer.token());
            case VisualResourceAuth.Basic basic -> new HttpRequestInput.BasicAuth(basic.username(), basic.password());
            case VisualResourceAuth.ApiKey apiKey -> new HttpRequestInput.ApiKeyAuth(apiKey.headerName(),
                    apiKey.key());
        };
    }

    private static VisualResourceResponseProtocol protocolToVisual(ResponseProtocol source) {
        return switch (source) {
            case null -> new VisualResourceResponseProtocol.HttpStatus();
            case ResponseProtocol.HttpStatus ignored -> new VisualResourceResponseProtocol.HttpStatus();
            case ResponseProtocol.BodyCode bodyCode -> new VisualResourceResponseProtocol.BodyCode(
                    bodyCode.codePath(), bodyCode.successValues(), bodyCode.messagePath());
            case ResponseProtocol.BodyFlag bodyFlag -> new VisualResourceResponseProtocol.BodyFlag(
                    bodyFlag.flagPath());
            case ResponseProtocol.StatusCodes statusCodes -> new VisualResourceResponseProtocol.StatusCodes(
                    statusCodes.successCodes());
            case ResponseProtocol.BlgeExpression expression -> new VisualResourceResponseProtocol.BlogeExpression(
                    expression.successExpr(), expression.messageExpr(), expression.payloadExpr());
        };
    }

    private static ResponseProtocol protocolToGateway(VisualResourceResponseProtocol source) {
        return switch (source) {
            case null -> new ResponseProtocol.HttpStatus();
            case VisualResourceResponseProtocol.HttpStatus ignored -> new ResponseProtocol.HttpStatus();
            case VisualResourceResponseProtocol.BodyCode bodyCode -> new ResponseProtocol.BodyCode(
                    bodyCode.codePath(), bodyCode.successValues(), bodyCode.messagePath());
            case VisualResourceResponseProtocol.BodyFlag bodyFlag -> new ResponseProtocol.BodyFlag(
                    bodyFlag.flagPath());
            case VisualResourceResponseProtocol.StatusCodes statusCodes -> new ResponseProtocol.StatusCodes(
                    statusCodes.successCodes());
            case VisualResourceResponseProtocol.BlogeExpression expression -> new ResponseProtocol.BlgeExpression(
                    expression.successExpr(), expression.messageExpr(), expression.payloadExpr());
        };
    }
}
