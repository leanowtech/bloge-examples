package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.protocol.TestControlEnvelope;
import com.leanowtech.bloge.gateway.testing.protocol.TestControlHeaderCodec;
import com.leanowtech.bloge.gateway.testing.protocol.TestControlHeaders;
import com.leanowtech.bloge.gateway.testing.protocol.TestControlProtocolException;
import com.leanowtech.bloge.gateway.testing.protocol.TestControlProtocolReason;
import com.leanowtech.bloge.gateway.testing.protocol.TestInlineControl;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Admission adapter for the authenticated graph execution endpoint.
 *
 * <p>The adapter is deliberately limited to the transport boundary. It does not resolve scenario
 * or world-model assets and it does not alter execution-kernel semantics. After admission, the
 * existing service path still owns target, fixture, scope, compilation, execution and evidence
 * checks.</p>
 *
 * <p>Only a projection of the four headers is retained by the codec. Header values are copied into
 * a short-lived map, parsed, and never included in an exception or diagnostic.</p>
 */
@Component
public final class TestExecutionIngressAdapter {
    private static final String CONTROL_HEADER_PREFIX = "x-bloge-test-";
    private static final Set<String> CONTROL_HEADERS = Set.of(
            TestControlHeaderCodec.ENVELOPE_HEADER.toLowerCase(Locale.ROOT),
            TestControlHeaderCodec.FIDELITY_HEADER.toLowerCase(Locale.ROOT),
            TestControlHeaderCodec.SCOPE_HEADER.toLowerCase(Locale.ROOT),
            TestControlHeaderCodec.INLINE_HEADER.toLowerCase(Locale.ROOT));

    private final ObjectMapper objectMapper;

    public TestExecutionIngressAdapter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** Returns whether the request carries one of the protocol control headers. */
    public static boolean hasControlHeaders(HttpHeaders headers) {
        return headers != null && headers.keySet().stream()
                .filter(Objects::nonNull)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .anyMatch(CONTROL_HEADERS::contains);
    }

    /** Returns whether any caller-supplied test control header is present, including future headers. */
    public static boolean hasAnyTestControlHeaders(HttpHeaders headers) {
        return headers != null && headers.keySet().stream()
                .filter(Objects::nonNull)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .anyMatch(name -> name.startsWith(CONTROL_HEADER_PREFIX));
    }

    /**
     * Parses and admits the control plane after authentication has completed.
     *
     * @param request already-decoded business request; its context is never replaced
     * @param identity authenticated, server-resolved request identity
     * @param headers raw HTTP headers supplied by the authenticated request
     * @return a payload-free internal admission containing the effective request
     */
    public TestExecutionIngress admit(TestExecutionApiRequest request,
                                      IntegrationRequestContext identity,
                                      HttpHeaders headers) {
        validateIdentity(identity);
        if (request != null && !TestExecutionApiService.AUTHORIZED_PURPOSE.equals(request.executionPurpose())) {
            throw badRequest(identity, "RG.TEST.EXECUTION_PURPOSE_INVALID",
                    "executionPurpose must explicitly be GRAPH_CONTRACT_TEST.");
        }
        TestControlHeaders controls = parse(headers, identity);
        validateEnvelope(controls.envelope(), identity);

        TestInlineControl inline = controls.inline();
        boolean envelopeAsset = controls.envelope() != null
                && controls.envelope().assetReference() != null;
        boolean bodyFixture = request != null
                && (request.fixtureBundle() != null || request.fixtureBundleRef() != null);
        if ((request != null && request.fixtureBundle() != null && request.fixtureBundleRef() != null)
                || (envelopeAsset && (inline != null || bodyFixture))
                || (inline != null && bodyFixture)) {
            throw badRequest(identity, "RG.TEST.FIXTURE_SOURCE_AMBIGUOUS",
                    "Exactly one fixture source may be supplied to a graph execution request.");
        }
        if (inline == null) {
            if (request == null) {
                throw badRequest(identity, "RG.TEST.REQUEST_SCHEMA_VERSION_INVALID",
                        "A graph execution request body is required.");
            }
            return new TestExecutionIngress(request, controls.fidelityToken(), controls.scopeToken(),
                    controls.envelope());
        }
        FixtureBundle fixture = inlineFixture(inline, identity);
        if (request == null) {
            throw badRequest(identity, "RG.TEST.REQUEST_SCHEMA_VERSION_INVALID",
                    "A graph execution request body is required.");
        }
        TestExecutionApiRequest admitted = new TestExecutionApiRequest(
                request.schemaVersion(), request.target(), request.executionPurpose(), request.context(),
                fixture, null, request.verbosity(), request.metadata());
        return new TestExecutionIngress(admitted, controls.fidelityToken(), controls.scopeToken(),
                controls.envelope());
    }

    private TestControlHeaders parse(HttpHeaders headers,
                                     IntegrationRequestContext identity) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        try {
            if (headers != null) {
                headers.forEach((name, values) -> copy.put(name,
                        values == null ? null : List.copyOf(values)));
            }
            return TestControlHeaderCodec.parseHeaders(copy);
        } catch (TestControlProtocolException malformed) {
            throw invalidControls(identity, malformed.reasonCode());
        } catch (NullPointerException | IllegalArgumentException malformedCollection) {
            throw invalidControls(identity, TestControlProtocolReason.INVALID_INPUT);
        }
    }

    private void validateIdentity(IntegrationRequestContext identity) {
        if (identity == null) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.TEST.AUTHENTICATED_CONTEXT_REQUIRED",
                    "An authenticated test execution context is required.", "", Map.of()));
        }
        identity.requireComplete();
        if (identity.projectId().isBlank() || identity.region().isBlank()) {
            throw badRequest(identity, "RG.TEST.ENTERPRISE_SCOPE_REQUIRED",
                    "Project and region are required for governed test assets.");
        }
        if (!Set.of("test", "staging").contains(canonicalEnvironment(identity.environmentId()))) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.ENVIRONMENT_FORBIDDEN",
                    "Caller-driven execution control is restricted to test and staging identities.",
                    identity.correlationId(), Map.of()));
        }
    }

    static String canonicalEnvironment(String environmentId) {
        return environmentId == null ? "" : environmentId.trim().toLowerCase(Locale.ROOT);
    }

    private static IntegrationProblemException invalidControls(
            IntegrationRequestContext identity,
            TestControlProtocolReason reason) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                "RG.TEST.CONTROL_HEADERS_INVALID",
                "The test control headers are invalid.", identity.correlationId(),
                Map.of("reason", reason.name())));
    }

    private void validateEnvelope(TestControlEnvelope envelope,
                                  IntegrationRequestContext identity) {
        if (envelope == null) {
            return;
        }
        if (!TestExecutionApiService.AUTHORIZED_PURPOSE.equals(envelope.purpose())) {
            throw badRequest(identity, "RG.TEST.CONTROL_PURPOSE_MISMATCH",
                    "The test control purpose does not match the graph execution endpoint.");
        }
        if (!identity.correlationId().equals(envelope.correlationId())) {
            throw badRequest(identity, "RG.TEST.CONTROL_CORRELATION_MISMATCH",
                    "The test control correlation does not match the authenticated request.");
        }
    }

    private FixtureBundle inlineFixture(TestInlineControl inline,
                                        IntegrationRequestContext identity) {
        JsonNode root = inline.payload();
        if (!root.isObject() || root.size() != 1
                || !root.has("fixtureBundle")
                || !root.get("fixtureBundle").isObject()) {
            throw badRequest(identity, "RG.TEST.INLINE_FIXTURE_INVALID",
                    "Inline test control must contain exactly one fixtureBundle object.");
        }
        try {
            return objectMapper.treeToValue(root.get("fixtureBundle"), FixtureBundle.class);
        } catch (JsonProcessingException | IllegalArgumentException invalidFixture) {
            throw badRequest(identity, "RG.TEST.INLINE_FIXTURE_INVALID",
                    "The inline fixtureBundle is not a valid fixture contract.");
        }
    }

    private static IntegrationProblemException badRequest(IntegrationRequestContext identity,
                                                           String code,
                                                           String title) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity.correlationId(), Map.of()));
    }
}
