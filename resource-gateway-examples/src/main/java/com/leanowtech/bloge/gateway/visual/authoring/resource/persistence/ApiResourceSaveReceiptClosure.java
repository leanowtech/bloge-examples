package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict, payload-free closure for the API Resource save receipt protocol.
 *
 * <p>The optional {@code defaultFixture} branch has a separate exact generated
 * authority check. Generic Resource/Connection readers validate its closed
 * metadata shape; the compound facade and Fixture store additionally bind it
 * to the full generated Fixture Set.</p>
 */
public final class ApiResourceSaveReceiptClosure {
    public static final String SCHEMA_VERSION = "bloge.apiResourceSaveReceipt.v1";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    private ApiResourceSaveReceiptClosure() { }

    /** Builds the canonical NONE-Fixture receipt from one exact staged authority. */
    public static CommandReceipt create(StagedApiResource staged) {
        return create(staged, null);
    }

    /** Builds a canonical receipt with an exact generated Default Fixture child. */
    public static CommandReceipt create(StagedApiResource staged, GeneratedDefaultFixture fixture) {
        if (staged == null) throw new IllegalArgumentException("staged Resource is required");
        ApiResourceSpec resource = staged.resource();
        ApiResourceConnectionSnapshot connection = staged.projections().connectionSnapshot();
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("schemaVersion", SCHEMA_VERSION);
        ObjectNode connectionBody = body.putObject("connection");
        connectionBody.put("connectionId", connection.connectionId());
        putRevision(connectionBody, connection.revision());
        ObjectNode resourceBody = body.putObject("resource");
        resourceBody.put("kind", "API_RESOURCE");
        resourceBody.put("resourceId", resource.resourceId());
        putRevision(resourceBody, resource.revision());
        resourceBody.put("fingerprint", resource.fingerprint());
        if (fixture != null) putDefaultFixture(body, resource, fixture);
        body.putObject("projections")
                .put("descriptor", "READY")
                .put("designContract", "READY")
                .put("operator", "READY");
        CommandReceipt receipt = new CommandReceipt(SCHEMA_VERSION, body,
                AuthoringFingerprints.of(body), staged.strongEtag());
        require(receipt, resource, connection);
        if (fixture != null) requireDefaultFixture(receipt, fixture);
        return receipt;
    }

    /** Validates a receipt against the exact Resource and Connection snapshot. */
    public static void require(CommandReceipt receipt, ApiResourceSpec resource,
                               ApiResourceConnectionSnapshot connection) {
        if (resource == null || connection == null) invalid();
        require(receipt, resource.resourceId(), connection.connectionId(), connection.revision());
        JsonNode reference = receipt.body().path("resource");
        if (reference.path("revision").asLong(-1) != resource.revision()
                || !resource.fingerprint().equals(reference.path("fingerprint").asText(null))) {
            invalid();
        }
    }

    /** Validates the exact schema shape and the child coordinate in a receipt. */
    public static void require(CommandReceipt receipt, String resourceId,
                               String connectionId, long connectionRevision) {
        if (receipt == null || !SCHEMA_VERSION.equals(receipt.schemaVersion())
                || !SCHEMA_VERSION.equals(receipt.body().path("schemaVersion").asText(null))
                || !AuthoringFingerprints.of(receipt.body()).equals(receipt.bodyFingerprint())) {
            throw new IllegalArgumentException("resource receipt fingerprint or schema drift");
        }
        JsonNode body = receipt.body();
        if (body.has("defaultFixture")) {
            exact(body, "schemaVersion", "connection", "resource", "defaultFixture", "projections");
            validateDefaultFixtureShape(body.get("defaultFixture"));
        } else {
            exact(body, "schemaVersion", "connection", "resource", "projections");
        }
        JsonNode schemaVersion = body.get("schemaVersion");
        if (schemaVersion == null || !schemaVersion.isTextual()
                || !SCHEMA_VERSION.equals(schemaVersion.asText())) invalid();

        JsonNode connection = body.get("connection");
        exact(connection, "connectionId", "revision");
        if (!identifier(connection.get("connectionId"))
                || !connectionId.equals(connection.get("connectionId").asText())
                || !revision(connection.get("revision"), connectionRevision)) invalid();

        JsonNode resource = body.get("resource");
        exact(resource, "kind", "resourceId", "revision", "fingerprint");
        JsonNode resourceKind = resource.get("kind");
        if (resourceKind == null || !resourceKind.isTextual() || !"API_RESOURCE".equals(resourceKind.asText())
                || !identifier(resource.get("resourceId")) || !resourceId.equals(resource.get("resourceId").asText())
                || !revision(resource.get("revision"), 0) || !fingerprint(resource.get("fingerprint"))) invalid();

        JsonNode projections = body.get("projections");
        exact(projections, "descriptor", "designContract", "operator");
        for (String projection : new String[]{"descriptor", "designContract", "operator"}) {
            JsonNode state = projections.get(projection);
            if (state == null || !state.isTextual() || !"READY".equals(state.asText())) invalid();
        }
    }

    /** Requires that a NONE command produced no Fixture receipt branch. */
    public static void requireNoDefaultFixture(CommandReceipt receipt) {
        if (receipt == null || receipt.body().has("defaultFixture")) invalid();
    }

    /** Binds the optional receipt branch to one exact generated Fixture authority. */
    public static void requireDefaultFixture(CommandReceipt receipt, GeneratedDefaultFixture generated) {
        if (receipt == null || generated == null) invalid();
        if (!(generated.view().subject() instanceof FixtureSubjectRef.ApiResource)) invalid();
        FixtureSubjectRef.ApiResource subject = (FixtureSubjectRef.ApiResource) generated.view().subject();
        JsonNode resource = receipt.body().get("resource");
        if (resource == null || !subject.resourceId().equals(resource.path("resourceId").asText(null))
                || subject.revision() != resource.path("revision").asInt(-1)
                || !subject.fingerprint().equals(resource.path("fingerprint").asText(null))) invalid();
        JsonNode fixture = receipt.body().get("defaultFixture");
        validateDefaultFixtureShape(fixture);
        if (!generated.view().fixtureSetId().equals(fixture.path("fixtureSetId").asText(null))
                || generated.view().revision() != fixture.path("revision").asInt(-1)
                || !generated.view().fingerprint().equals(fixture.path("fingerprint").asText(null))
                || fixture.path("cases").size() != generated.caseMappings().size()) invalid();
        for (int index = 0; index < generated.caseMappings().size(); index++) {
            GeneratedDefaultFixture.CaseMapping mapping = generated.caseMappings().get(index);
            JsonNode actual = fixture.path("cases").path(index);
            if (!mapping.exampleName().equals(actual.path("exampleName").asText(null))
                    || !mapping.caseId().equals(actual.path("caseId").asText(null))) invalid();
        }
    }

    private static void putDefaultFixture(ObjectNode body, ApiResourceSpec resource,
                                          GeneratedDefaultFixture generated) {
        if (!FixtureSubjectRef.apiResource(resource.ref()).equals(generated.view().subject())) invalid();
        ObjectNode fixture = body.putObject("defaultFixture");
        fixture.put("fixtureSetId", generated.view().fixtureSetId());
        putRevision(fixture, generated.view().revision());
        fixture.put("fingerprint", generated.view().fingerprint());
        var cases = fixture.putArray("cases");
        for (GeneratedDefaultFixture.CaseMapping mapping : generated.caseMappings()) {
            cases.addObject().put("exampleName", mapping.exampleName()).put("caseId", mapping.caseId());
        }
    }

    private static void validateDefaultFixtureShape(JsonNode fixture) {
        exact(fixture, "fixtureSetId", "revision", "fingerprint", "cases");
        if (!identifier(fixture.get("fixtureSetId")) || !revision(fixture.get("revision"), 0)
                || !fingerprint(fixture.get("fingerprint")) || !fixture.get("cases").isArray()
                || fixture.get("cases").isEmpty()) invalid();
        Set<String> examples = new HashSet<>();
        Set<String> cases = new HashSet<>();
        for (JsonNode mapping : fixture.get("cases")) {
            exact(mapping, "exampleName", "caseId");
            if (!identifier(mapping.get("exampleName")) || !identifier(mapping.get("caseId"))
                    || !examples.add(mapping.get("exampleName").asText())
                    || !cases.add(mapping.get("caseId").asText())) invalid();
        }
    }

    private static boolean revision(JsonNode value, long exact) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 1
                && (exact == 0 || value.asLong() == exact);
    }

    private static void putRevision(ObjectNode body, long revision) {
        if (revision <= Integer.MAX_VALUE) body.put("revision", (int) revision);
        else body.put("revision", revision);
    }

    private static boolean identifier(JsonNode value) {
        return value != null && value.isTextual() && IDENTIFIER.matcher(value.asText()).matches();
    }

    private static boolean fingerprint(JsonNode value) {
        return value != null && value.isTextual() && FINGERPRINT.matcher(value.asText()).matches();
    }

    private static void exact(JsonNode node, String... names) {
        if (node == null || !node.isObject()) invalid();
        Set<String> expected = Set.of(names);
        Set<String> actual = new HashSet<>();
        Iterator<String> fields = node.fieldNames();
        fields.forEachRemaining(actual::add);
        if (!actual.equals(expected)) invalid();
    }

    private static void invalid() { throw new IllegalArgumentException("resource receipt closure is invalid"); }
}
