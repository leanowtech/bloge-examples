package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict, payload-free closure for the API Resource save receipt protocol. */
public final class ApiResourceSaveReceiptClosure {
    public static final String SCHEMA_VERSION = "bloge.apiResourceSaveReceipt.v1";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    private ApiResourceSaveReceiptClosure() { }

    /** Validates the exact schema shape and the child coordinate in a receipt. */
    public static void require(CommandReceipt receipt, String resourceId,
                               String connectionId, long connectionRevision) {
        if (receipt == null || !SCHEMA_VERSION.equals(receipt.schemaVersion())
                || !SCHEMA_VERSION.equals(receipt.body().path("schemaVersion").asText(null))
                || !AuthoringFingerprints.of(receipt.body()).equals(receipt.bodyFingerprint())) {
            throw new IllegalArgumentException("resource receipt fingerprint or schema drift");
        }
        JsonNode body = receipt.body();
        exact(body, true, "schemaVersion", "connection", "resource", "projections", "defaultFixture");
        JsonNode schemaVersion = body.get("schemaVersion");
        if (schemaVersion == null || !schemaVersion.isTextual()
                || !SCHEMA_VERSION.equals(schemaVersion.asText())) invalid();

        JsonNode connection = body.get("connection");
        exact(connection, false, "connectionId", "revision");
        if (!identifier(connection.get("connectionId"))
                || !connectionId.equals(connection.get("connectionId").asText())
                || !revision(connection.get("revision"), connectionRevision)) invalid();

        JsonNode resource = body.get("resource");
        exact(resource, false, "kind", "resourceId", "revision", "fingerprint");
        JsonNode resourceKind = resource.get("kind");
        if (resourceKind == null || !resourceKind.isTextual() || !"API_RESOURCE".equals(resourceKind.asText())
                || !identifier(resource.get("resourceId")) || !resourceId.equals(resource.get("resourceId").asText())
                || !revision(resource.get("revision"), 0) || !fingerprint(resource.get("fingerprint"))) invalid();

        JsonNode projections = body.get("projections");
        exact(projections, false, "descriptor", "designContract", "operator");
        for (String projection : new String[]{"descriptor", "designContract", "operator"}) {
            JsonNode state = projections.get(projection);
            if (state == null || !state.isTextual() || !"READY".equals(state.asText())) invalid();
        }

        JsonNode fixture = body.get("defaultFixture");
        if (fixture != null && !fixture.isMissingNode()) {
            exact(fixture, false, "fixtureSetId", "revision", "fingerprint", "cases");
            JsonNode cases = fixture.get("cases");
            if (!identifier(fixture.get("fixtureSetId")) || !revision(fixture.get("revision"), 0)
                    || !fingerprint(fixture.get("fingerprint")) || cases == null || !cases.isArray()
                    || cases.isEmpty()) invalid();
            for (JsonNode entry : cases) {
                exact(entry, false, "exampleName", "caseId");
                if (!identifier(entry.get("exampleName")) || !identifier(entry.get("caseId"))) invalid();
            }
        }
    }

    private static boolean revision(JsonNode value, long exact) {
        return value != null && value.isIntegralNumber() && value.asLong() >= 1
                && (exact == 0 || value.asLong() == exact);
    }

    private static boolean identifier(JsonNode value) {
        return value != null && value.isTextual() && IDENTIFIER.matcher(value.asText()).matches();
    }

    private static boolean fingerprint(JsonNode value) {
        return value != null && value.isTextual() && FINGERPRINT.matcher(value.asText()).matches();
    }

    private static void exact(JsonNode node, boolean optionalDefaultFixture, String... names) {
        if (node == null || !node.isObject()) invalid();
        Set<String> expected = Set.of(names);
        Set<String> actual = new HashSet<>();
        Iterator<String> fields = node.fieldNames();
        fields.forEachRemaining(actual::add);
        if (optionalDefaultFixture) {
            actual.remove("defaultFixture");
            expected = new HashSet<>(expected);
            expected.remove("defaultFixture");
        }
        if (!actual.equals(expected)) invalid();
    }

    private static void invalid() { throw new IllegalArgumentException("resource receipt closure is invalid"); }
}
