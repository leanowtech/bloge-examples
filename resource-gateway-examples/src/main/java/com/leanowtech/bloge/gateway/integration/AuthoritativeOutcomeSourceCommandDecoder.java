package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeConnectorControlCommand;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict bounded post-authentication decoder for outcome source control commands. */
@Component
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror", name = "enabled", havingValue = "true")
public final class AuthoritativeOutcomeSourceCommandDecoder {
    /** Largest admitted command body. */
    public static final int MAXIMUM_REQUEST_BYTES =
            AuthoritativeOutcomeConnectorControlCommand.MAXIMUM_CANONICAL_BYTES + 16 * 1024;
    private static final Set<String> FIELDS = Set.of(
            "schemaVersion", "commandId", "revision", "commandFingerprint", "scope",
            "connectorId", "connectorGeneration", "commandType", "streamId",
            "eventTimeRange", "baselinePageFingerprint", "baselineCursorRef",
            "reasonCode", "requestedAt", "expiresAt", "authoritySeal");
    private final ObjectMapper strictMapper;

    /** Creates one duplicate-, unknown-, and trailing-token-rejecting mapper. */
    public AuthoritativeOutcomeSourceCommandDecoder(ObjectMapper mapper) {
        strictMapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** Decodes one exact command only after request authentication. */
    public AuthoritativeOutcomeConnectorControlCommand decode(
            byte[] value, IntegrationRequestContext identity) {
        IntegrationRequestContext exact = Objects.requireNonNull(identity, "identity");
        if (value == null || value.length == 0 || value.length > MAXIMUM_REQUEST_BYTES) {
            throw invalid(exact);
        }
        try {
            JsonNode tree = strictMapper.readTree(value);
            if (tree == null || !tree.isObject()) {
                throw invalid(exact);
            }
            HashSet<String> fields = new HashSet<>();
            tree.fieldNames().forEachRemaining(fields::add);
            if (!FIELDS.equals(fields)
                    || !AuthoritativeOutcomeConnectorControlCommand.SCHEMA_VERSION.equals(
                    tree.path("schemaVersion").textValue())) {
                throw invalid(exact);
            }
            return strictMapper.treeToValue(
                    tree, AuthoritativeOutcomeConnectorControlCommand.class);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (IOException | IllegalArgumentException malformed) {
            throw invalid(exact);
        }
    }

    private static IntegrationProblemException invalid(IntegrationRequestContext identity) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                "RG.MIRROR.OUTCOME_SOURCE.REQUEST_MALFORMED",
                "The outcome source command does not match the bounded public protocol.",
                identity.correlationId(), Map.of(
                        "currentSchemaVersion",
                        AuthoritativeOutcomeConnectorControlCommand.SCHEMA_VERSION,
                        "maximumBytes", MAXIMUM_REQUEST_BYTES)));
    }
}
