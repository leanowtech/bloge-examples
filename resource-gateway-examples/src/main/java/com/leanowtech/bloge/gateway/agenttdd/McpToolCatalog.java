package com.leanowtech.bloge.gateway.agenttdd;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical five-stage tool catalog for the Resource Gateway business-TDD operating surface.
 *
 * <p>Definitions live in one list so protocol discovery, authorization and application dispatch
 * cannot silently disagree about a tool's stable name or impact level.</p>
 */
@Component
public final class McpToolCatalog {
    private final Map<String, McpToolDefinition> definitions;

    /** Builds the frozen 1.4 catalog in workflow order. */
    public McpToolCatalog() {
        List<McpToolDefinition> values = new ArrayList<>();
        values.add(tool("rg.capability.list", "List capabilities", "List API, Feature and Tool assets.",
                McpToolImpact.READ, props("kind", string(), "cursor", string()), List.of()));
        values.add(tool("rg.library.get", "Get library", "Read one exact library contract.",
                McpToolImpact.READ, props("libraryId", string()), List.of("libraryId")));
        values.add(tool("rg.library.list", "List libraries", "List visible library contracts and speccing state.",
                McpToolImpact.READ, props("includeDeprecated", bool()), List.of()));
        values.add(tool("rg.contract.get", "Get business contract", "Read the business contract for an asset.",
                McpToolImpact.READ, props("assetRef", string()), List.of("assetRef")));
        values.add(tool("rg.tool.getInstruction", "Get tool instruction", "Read the Agent-facing tool contract.",
                McpToolImpact.READ, props("toolRef", string()), List.of("toolRef")));
        values.add(tool("rg.scenario.listCases", "List scenario cases", "Read golden and regression table rows.",
                McpToolImpact.READ, props("caseSetRef", string(), "lifecycle", string()), List.of("caseSetRef")));
        values.add(tool("rg.verdict.get", "Get verdict", "Read layered red-to-green status and business backlog.",
                McpToolImpact.READ, props("toolRef", string(), "goldenSetId", string()), List.of("toolRef")));
        values.add(tool("rg.evidence.get", "Get evidence", "Read a classification-filtered execution evidence lens.",
                McpToolImpact.READ, props("evidenceRef", string(), "view", string()), List.of("evidenceRef")));
        values.add(tool("rg.dsl.reference.get", "Get DSL reference",
                "Read the scoped BLOGE graph grammar, contracts and certified examples.",
                McpToolImpact.READ,
                props("libraryRefs", stringArray(), "topics", stringArray(),
                        "operatorRefs", stringArray(), "includeExamples", bool()),
                List.of("libraryRefs")));

        values.add(tool("rg.library.upsert", "Upsert library", "Compile and save a library authoring YAML document.",
                McpToolImpact.DRAFT_WRITE,
                props("libraryYaml", string(), "idempotencyKey", string()),
                List.of("libraryYaml", "idempotencyKey")));
        values.add(tool("rg.resource.declare", "Declare sandbox resource",
                "Register one allowlisted read-only resource descriptor and its visual contract.",
                McpToolImpact.DRAFT_WRITE,
                props("resourceId", string(), "method", enumString("GET", "HEAD", "OPTIONS"),
                        "urlTemplate", string(), "payloadSchema", businessObject(),
                        "idempotencyKey", string()),
                List.of("resourceId", "method", "urlTemplate", "payloadSchema", "idempotencyKey")));
        values.add(tool("rg.feature.compose", "Compose feature", "Create or replace a Feature graph draft.",
                McpToolImpact.DRAFT_WRITE, composeProperties(), List.of("featureRef", "graph", "libraryRefs",
                        "authoringContextFingerprint", "authoringReceiptFingerprint", "idempotencyKey")));
        values.add(tool("rg.tool.compose", "Compose tool", "Create or replace a Tool graph draft.",
                McpToolImpact.DRAFT_WRITE, composeProperties(), List.of("toolRef", "graph", "libraryRefs",
                        "authoringContextFingerprint", "authoringReceiptFingerprint", "idempotencyKey")));
        values.add(tool("rg.tool.setInstruction", "Set tool instruction", "Set Agent semantics; examples remain golden-derived.",
                McpToolImpact.DRAFT_WRITE,
                props("toolRef", string(), "instruction", instruction(), "idempotencyKey", string()),
                List.of("toolRef", "instruction", "idempotencyKey")));
        values.add(tool("rg.scenario.upsertCases", "Upsert scenarios", "Write cases; GOLDEN rows become human-review proposals.",
                McpToolImpact.DRAFT_WRITE,
                props("caseSetRef", string(), "toolRef", string(), "rows", caseRows(), "enumerateFrom", enumeration(),
                        "idempotencyKey", string()),
                List.of("caseSetRef", "rows", "idempotencyKey")));
        values.add(tool("rg.oracle.propose", "Propose oracle", "Propose a business-owned expected outcome.",
                McpToolImpact.PROPOSE,
                props("caseSetRef", string(), "caseId", string(), "expect", anyJson(), "oracleOwner", string(),
                        "idempotencyKey", string()),
                List.of("caseSetRef", "caseId", "expect", "oracleOwner", "idempotencyKey")));
        values.add(tool("rg.scenario.setDependencyBehavior", "Set dependency behavior",
                "Set a bounded RETURN, ERROR, DELAY, TIMEOUT, REPLAY, OBSERVE or MUST_NOT_CALL stub.",
                McpToolImpact.DRAFT_WRITE,
                props("caseSetRef", string(), "caseId", string(), "nodeId", string(), "behavior", behavior(),
                        "idempotencyKey", string()),
                List.of("caseSetRef", "caseId", "nodeId", "behavior", "idempotencyKey")));

        values.add(tool("rg.dsl.preview", "Preview DSL", "Compile with an explicit library contract context.",
                McpToolImpact.READ, previewProperties(), List.of("source", "libraryRefs",
                        "authoringContextFingerprint")));
        values.add(tool("rg.gate.check", "Check merge gate", "Evaluate compile, contract and honest-verdict gates.",
                McpToolImpact.READ, previewProperties(), List.of("source", "libraryRefs",
                        "authoringContextFingerprint")));

        values.add(tool("rg.feature.rehearse", "Rehearse feature", "Run fixture-only Feature rehearsal with zero egress.",
                McpToolImpact.EXECUTE,
                props("featureRef", string(), "libraryRefs", stringArray(), "cases", casesEnvelope()),
                List.of("featureRef", "libraryRefs", "cases")));
        values.add(tool("rg.tool.baseline", "Baseline tool", "Run multi-case, multi-round business baseline.",
                McpToolImpact.EXECUTE,
                props("toolRef", string(), "libraryRefs", stringArray(), "caseSetRef", string(), "cases", casesEnvelope(),
                        "rounds", integer(), "side", string()),
                List.of("toolRef", "libraryRefs", "caseSetRef")));
        values.add(tool("rg.simulate", "Simulate", "Run one side of the red-to-green line with honest evidence.",
                McpToolImpact.EXECUTE,
                props("toolRef", string(), "libraryRefs", stringArray(), "cases", casesEnvelope(),
                        "adhocFixtures", fixtureOverrides(), "side", enumString("RED", "GREEN")),
                List.of("toolRef", "libraryRefs", "cases")));

        values.add(tool("rg.fixture.promote", "Promote fixture", "Promote one server-captured output to governed fixture.",
                McpToolImpact.GOVERNED_WRITE,
                props("draftId", string(), "nodeId", string(), "outputPort", string(), "fixtureId", string(),
                        "category", string(), "retentionDays", integer(), "redactPaths", stringArray(),
                        "idempotencyKey", string()),
                List.of("draftId", "nodeId", "outputPort", "fixtureId", "category", "retentionDays", "idempotencyKey")));
        values.add(tool("rg.fixture.provide", "Provide sample fixture",
                "Validate one supplied sample against an operator output contract and persist it as a governed fixture.",
                McpToolImpact.GOVERNED_WRITE,
                props("operatorRef", string(), "outputPort", string(), "sampleValue", anyJson(),
                        "category", string(), "retentionDays", integer(), "redactPaths", stringArray(),
                        "idempotencyKey", string()),
                List.of("operatorRef", "outputPort", "sampleValue", "category", "retentionDays",
                        "idempotencyKey")));
        values.add(tool("rg.tool.publishSpec", "Publish specification", "Propose an immutable speccing artifact for review.",
                McpToolImpact.PROPOSE,
                props("toolRef", string(), "idempotencyKey", string()), List.of("toolRef", "idempotencyKey")));
        values.add(tool("rg.tool.publish", "Publish tool", "Publish an immutable executable tool after all gates pass.",
                McpToolImpact.GOVERNED_WRITE,
                props("toolRef", string(), "signoffRef", string(), "idempotencyKey", string()),
                List.of("toolRef", "signoffRef", "idempotencyKey")));
        values.add(tool("rg.readiness.get", "Get readiness", "Read publish gates and remaining limitations.",
                McpToolImpact.READ, props("toolRef", string()), List.of("toolRef")));

        LinkedHashMap<String, McpToolDefinition> indexed = new LinkedHashMap<>();
        values.forEach(value -> {
            if (indexed.put(value.name(), value) != null) {
                throw new IllegalStateException("Duplicate MCP tool " + value.name());
            }
        });
        definitions = java.util.Collections.unmodifiableMap(indexed);
    }

    /** @return all definitions in stable workflow order */
    public List<McpToolDefinition> all() {
        return List.copyOf(definitions.values());
    }

    /** @return exact definition or a protocol-safe not-found failure */
    public McpToolDefinition require(String name) {
        McpToolDefinition definition = definitions.get(name == null ? "" : name.trim());
        if (definition == null) {
            throw new McpProtocolException(-32601, "Unknown MCP tool");
        }
        return definition;
    }

    private static McpToolDefinition tool(String name,
                                          String title,
                                          String description,
                                          McpToolImpact impact,
                                          Map<String, Object> properties,
                                          List<String> required) {
        return new McpToolDefinition(name, title, description, impact,
                schema(properties, required), envelopeSchema(name));
    }

    private static Map<String, Object> composeProperties() {
        return props("featureRef", string(), "toolRef", string(), "graph", graphSource(),
                "libraryRefs", stringArray(), "authoringContextFingerprint", string(),
                "authoringReceiptFingerprint", string(), "idempotencyKey", string());
    }

    private static Map<String, Object> previewProperties() {
        return props("source", Map.of("oneOf", List.of(string(), graphSource())),
                "libraryRefs", stringArray(), "authoringContextFingerprint", string());
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }

    private static Map<String, Object> envelopeSchema(String name) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "object");
        envelope.put("properties", props("ok", bool(), "data", outputData(name),
                "diagnostics", arrayOf(diagnostic()), "error", error()));
        envelope.put("required", List.of("ok", "diagnostics"));
        envelope.put("additionalProperties", false);
        envelope.put("if", Map.of("properties", Map.of("ok", Map.of("const", true)),
                "required", List.of("ok")));
        envelope.put("then", Map.of("required", List.of("data")));
        envelope.put("else", Map.of("required", List.of("error")));
        return Map.copyOf(envelope);
    }

    private static Map<String, Object> props(Object... entries) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put((String) entries[index], entries[index + 1]);
        }
        return Map.copyOf(values);
    }

    private static Map<String, Object> string() { return Map.of("type", "string"); }
    private static Map<String, Object> bool() { return Map.of("type", "boolean"); }
    private static Map<String, Object> integer() { return Map.of("type", "integer"); }
    private static Map<String, Object> businessObject() {
        return Map.of("type", "object", "additionalProperties", true,
                "description", "Business JSON constrained by the referenced runtime contract.");
    }
    private static Map<String, Object> anyJson() {
        return Map.of("oneOf", List.of(
                Map.of("type", "object", "additionalProperties", true),
                Map.of("type", "array", "items", true),
                string(),
                Map.of("type", "number"),
                bool(),
                Map.of("type", "null")),
                "description", "Any JSON value constrained by the referenced runtime contract.");
    }
    private static Map<String, Object> structuredObject(Map<String, Object> properties, List<String> required) {
        return schema(properties, required);
    }
    private static Map<String, Object> arrayOf(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }
    private static Map<String, Object> stringArray() {
        return Map.of("type", "array", "items", string(), "uniqueItems", true);
    }

    private static Map<String, Object> enumString(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    private static Map<String, Object> graphSource() {
        return structuredObject(props("sourceId", string(), "dsl", string()), List.of("dsl"));
    }

    private static Map<String, Object> instruction() {
        return structuredObject(props("name", string(), "title", string(), "description", string(), "whenToUse", string(),
                "inputs", arrayOf(businessObject()), "outputs", businessObject(), "errors", arrayOf(businessObject())),
                List.of("name", "title", "description", "whenToUse", "inputs", "outputs", "errors"));
    }

    private static Map<String, Object> caseRow() {
        return structuredObject(props("caseId", string(), "category", enumString(
                        "GOLDEN", "REGRESSION", "NEGATIVE", "BOUNDARY", "FAULT", "SECURITY"),
                "layer", enumString("unit", "contract", "integration", "smoke"),
                "given", businessObject(), "stubs", businessObject(), "expect", anyJson(),
                "intent", string(), "oracleOwner", string(), "sourceRunRef", string(),
                "lifecycle", enumString("DRAFT", "ACTIVE", "STALE", "RETIRED"),
                "qualityState", enumString("DESIGNED_NOT_RUN", "READY", "STALE", "BLOCKED"),
                "proposedOracle", businessObject(), "enumeration", enumerationProvenance()),
                List.of("caseId", "given", "stubs"));
    }

    /** Describes only the server-owned provenance emitted by decision-table enumeration. */
    private static Map<String, Object> enumerationProvenance() {
        return structuredObject(props(
                "enumerationMode", enumString("per-rule", "combinatorial", "opaque"),
                "enumerationRule", string(),
                "boundaryInput", string(),
                "reason", enumString("AUTHOR_SAMPLES_REQUIRED")),
                List.of("enumerationMode"));
    }

    private static Map<String, Object> caseRows() { return arrayOf(caseRow()); }

    private static Map<String, Object> casesEnvelope() {
        return Map.of("oneOf", List.of(
                structuredObject(props("caseSetRef", Map.of("type", "string", "minLength", 1)),
                        List.of("caseSetRef")),
                structuredObject(props("rows", Map.of(
                        "type", "array", "items", caseRow(), "minItems", 1)), List.of("rows"))));
    }

    private static Map<String, Object> enumeration() {
        return structuredObject(props("decisionTableRef", string(), "mode", enumString("per-rule", "combinatorial"),
                "maxCases", integer(), "oracleOwner", string(), "authorSamples", businessObject()),
                List.of("decisionTableRef", "mode", "maxCases"));
    }

    private static Map<String, Object> behavior() {
        return structuredObject(props("behavior", enumString(
                        "RETURN", "ERROR", "DELAY", "TIMEOUT", "REPLAY", "OBSERVE", "MUST_NOT_CALL"),
                "value", anyJson(), "expectedInput", anyJson(), "afterMillis", integer(),
                "errorCode", string(), "errorType", string(), "errorMessage", string(), "replayRef", string()),
                List.of("behavior"));
    }

    private static Map<String, Object> fixtureOverrides() {
        return arrayOf(structuredObject(props("nodeId", string(), "value", anyJson()),
                List.of("nodeId", "value")));
    }

    private static Map<String, Object> diagnostic() {
        return structuredObject(props("level", string(), "code", string(), "target", string(),
                "line", integer(), "column", integer()), List.of("level", "code", "target"));
    }

    private static Map<String, Object> honestVerdict() {
        return structuredObject(props("dimensions", arrayOf(structuredObject(props(
                "name", string(), "status", string(), "proves", string(),
                "doesNotProve", string(), "limitation", string()), List.of("name", "status")))),
                List.of("dimensions"));
    }

    /** Strict payload-free schema for a server-owned DSL authoring receipt. */
    private static Map<String, Object> dslAuthoringReceiptData() {
        Map<String, Object> context = structuredObject(props(
                "fingerprint", string(), "status", enumString("CURRENT"),
                "languageVersion", string(), "compilerProfile", string()),
                List.of("fingerprint", "status", "languageVersion", "compilerProfile"));
        Map<String, Object> stage = structuredObject(props(
                "phase", enumString("CONTEXT", "PARSE", "RESOLVE", "TYPE_CHECK", "SEMANTIC_COMPILE",
                        "LINT", "PROJECT", "ROUND_TRIP", "GATE"),
                "status", enumString("PASS", "FAIL", "NOT_RUN")), List.of("phase", "status"));
        Map<String, Object> span = structuredObject(props(
                "known", bool(), "startLine", integer(), "startColumn", integer(),
                "endLine", integer(), "endColumn", integer()),
                List.of("known", "startLine", "startColumn", "endLine", "endColumn"));
        Map<String, Object> fixHint = structuredObject(props(
                "kind", string(), "candidate", string(), "reasonCode", string()),
                List.of("kind", "candidate", "reasonCode"));
        Map<String, Object> authoringDiagnostic = structuredObject(props(
                "level", enumString("ERROR", "WARNING", "INFO"), "phase", string(), "code", string(),
                "target", string(), "span", span, "safeSummary", string(),
                "expectedKinds", stringArray(), "referenceRefs", stringArray(),
                "fixHints", arrayOf(fixHint), "resolutionClass", enumString(
                        "AGENT_CAN_REVISE", "HUMAN_OR_PLATFORM_REQUIRED", "PLATFORM_MAINTAINER"),
                "blocking", bool(), "retryable", bool(), "diagnosticFingerprint", string()),
                List.of("level", "phase", "code", "target", "span", "safeSummary",
                        "expectedKinds", "referenceRefs", "fixHints", "resolutionClass",
                        "blocking", "retryable", "diagnosticFingerprint"));
        Map<String, Object> projection = structuredObject(props(
                "schemaVersion", string(), "status", enumString("PROJECTED", "REPAIR_REQUIRED"),
                "nodeCount", integer(), "edgeCount", integer(), "unsupportedSyntaxCount", integer(),
                "missingOperatorCount", integer(), "missingFunctionCount", integer(),
                "sourceSemanticFingerprint", string()),
                List.of("schemaVersion", "status", "nodeCount", "edgeCount", "unsupportedSyntaxCount",
                        "missingOperatorCount", "missingFunctionCount", "sourceSemanticFingerprint"));
        Map<String, Object> roundTrip = structuredObject(props(
                "status", string(), "sourceSemanticFingerprint", string(),
                "regeneratedSemanticFingerprint", string(), "driftKinds", stringArray()),
                List.of("status", "sourceSemanticFingerprint", "regeneratedSemanticFingerprint", "driftKinds"));
        Map<String, Object> phaseCount = structuredObject(props(
                "phase", string(), "count", integer()), List.of("phase", "count"));
        Map<String, Object> summary = structuredObject(props(
                "total", integer(), "truncated", bool(), "byPhase", arrayOf(phaseCount)),
                List.of("total", "truncated", "byPhase"));
        return props(
                "authoringContext", context, "stages", arrayOf(stage),
                "technicalAcceptance", enumString("ACCEPTED", "REVISE", "REFETCH_REFERENCE", "PLATFORM_DEFECT"),
                "projection", projection, "roundTrip", roundTrip,
                "authoringDiagnostics", arrayOf(authoringDiagnostic), "diagnosticSummary", summary,
                "nextAction", string(), "authoringReceiptFingerprint", string());
    }

    /** Complete strict output schema for preview and gate responses. */
    private static Map<String, Object> dslAuthoringOutput() {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>(dslAuthoringReceiptData());
        properties.put("accepted", bool());
        properties.put("compileAccepted", bool());
        properties.put("rewriteGate", structuredObject(props(
                "allowed", bool(), "decision", enumString("ALLOW", "BLOCK")),
                List.of("allowed", "decision")));
        properties.put("speccing", bool());
        properties.put("executable", bool());
        properties.put("libraryRefs", stringArray());
        properties.put("honestVerdict", honestVerdict());
        return structuredObject(Map.copyOf(properties), List.of(
                "accepted", "compileAccepted", "speccing", "executable", "libraryRefs",
                "authoringContext", "stages", "technicalAcceptance", "projection", "roundTrip",
                "authoringDiagnostics", "diagnosticSummary", "nextAction",
                "authoringReceiptFingerprint", "honestVerdict"));
    }

    /** Strict, payload-free authoring reference returned by the DSL reference tool. */
    private static Map<String, Object> dslReference() {
        Map<String, Object> rule = structuredObject(
                props("ruleId", string(), "summary", string()), List.of("ruleId", "summary"));
        Map<String, Object> topic = structuredObject(props(
                "topicId", string(), "title", string(), "rules", arrayOf(rule),
                "exampleRefs", stringArray()), List.of("topicId", "title", "rules", "exampleRefs"));
        Map<String, Object> port = structuredObject(props(
                "name", string(), "required", bool(), "schemaRef", string()),
                List.of("name", "required", "schemaRef"));
        Map<String, Object> operator = structuredObject(props(
                "operatorRef", string(), "archetype", string(), "effect", string(),
                "inputs", arrayOf(port), "outputs", arrayOf(port), "configSchema", businessObject(),
                "contractFingerprint", string(), "bindingState", string()),
                List.of("operatorRef", "archetype", "effect", "inputs", "outputs", "configSchema",
                        "contractFingerprint", "bindingState"));
        Map<String, Object> function = structuredObject(props(
                "name", string(), "signature", string(), "contractFingerprint", string()),
                List.of("name", "signature", "contractFingerprint"));
        Map<String, Object> example = structuredObject(props(
                "exampleId", string(), "intent", string(), "source", string(),
                "assertions", stringArray(), "exampleFingerprint", string()),
                List.of("exampleId", "intent", "source", "assertions", "exampleFingerprint"));
        Map<String, Object> limits = structuredObject(props(
                "maxTopics", integer(), "maxOperatorRefs", integer(), "maxFunctions", integer(),
                "maxExamples", integer(), "maxResponseBytes", integer()),
                List.of("maxTopics", "maxOperatorRefs", "maxFunctions", "maxExamples", "maxResponseBytes"));
        return structuredObject(props(
                "schemaVersion", string(), "languageVersion", string(), "compilerProfile", string(),
                "supportedRootKinds", stringArray(), "referenceVersion", string(),
                "authoringContextFingerprint", string(), "topics", arrayOf(topic),
                "operators", arrayOf(operator), "functions", arrayOf(function),
                "examples", arrayOf(example), "limits", limits),
                List.of("schemaVersion", "languageVersion", "compilerProfile", "supportedRootKinds",
                        "referenceVersion", "authoringContextFingerprint", "topics", "operators",
                        "functions", "examples", "limits"));
    }

    private static Map<String, Object> counts() {
        return structuredObject(props("pass", integer(), "fail", integer()), List.of("pass", "fail"));
    }

    private static Map<String, Object> executionLayerSummary() {
        return structuredObject(props("unit", counts(), "contract", counts(),
                "integration", counts(), "smoke", counts()), List.of());
    }

    private static Map<String, Object> verdictLayerMatrix() {
        Map<String, Object> cell = structuredObject(
                props("red", counts(), "green", counts()), List.of("red", "green"));
        return structuredObject(props("unit", cell, "contract", cell,
                "integration", cell, "smoke", cell), List.of());
    }

    private static Map<String, Object> executionCase() {
        return structuredObject(props("caseId", string(), "layer", string(), "category", string(),
                "oracleOwner", string(), "verdict", string(), "oracle", structuredObject(
                        props("invariant", string(), "held", bool()), List.of("invariant", "held")),
                "schemaConformant", bool(), "mockedNodeIds", stringArray(),
                "realNodeIds", stringArray(), "diagnostics", arrayOf(diagnostic()),
                "realExternalCalls", integer(), "reasonCode", string()),
                List.of("caseId", "layer", "category", "verdict", "realExternalCalls"));
    }

    /** Strict payload-free schema for platform-owned real-integration evidence. */
    private static Map<String, Object> attestation() {
        Map<String, Object> observedCase = structuredObject(props(
                "caseId", string(), "executionSucceeded", bool(), "oracleHeld", bool(),
                "allDependenciesCalled", bool(), "realExternalCalls", integer()),
                List.of("caseId", "executionSucceeded", "oracleHeld",
                        "allDependenciesCalled", "realExternalCalls"));
        Map<String, Object> dependency = structuredObject(props(
                "nodeId", string(), "operatorRef", string(), "resourceId", string(),
                "realCalled", bool(), "realCallCount", integer()),
                List.of("nodeId", "operatorRef", "resourceId", "realCalled", "realCallCount"));
        return structuredObject(props(
                "toolRef", string(), "status", enumString(
                        "ABSENT", "ATTESTED", "FAILED", "RECOVERY_REQUIRED"),
                "reasonCode", string(), "environment", string(), "goldenSetId", string(),
                "evidenceFingerprint", string(), "contractFingerprint", string(),
                "implementationFingerprint", string(), "draftRevision", integer(),
                "caseSetRef", string(), "caseSetRevision", integer(),
                "cases", arrayOf(observedCase), "dependencies", arrayOf(dependency),
                "realExternalCalls", integer()),
                List.of("status", "environment", "cases", "dependencies", "realExternalCalls"));
    }

    private static Map<String, Object> error() {
        return structuredObject(props("code", string(), "message", string(), "retryable", bool(),
                "details", businessObject()), List.of("code", "message"));
    }

    private static Map<String, Object> outputData(String name) {
        return switch (name) {
            case "rg.capability.list" -> structuredObject(props("capabilities", arrayOf(businessObject()),
                    "nextCursor", string()), List.of("capabilities"));
            case "rg.library.get" -> structuredObject(props("library", businessObject(),
                    "operators", arrayOf(businessObject()), "speccing", bool()), List.of("library", "operators"));
            case "rg.library.list" -> structuredObject(props("libraries", arrayOf(businessObject())), List.of("libraries"));
            case "rg.contract.get" -> structuredObject(props("assetRef", string(), "kind", string(),
                    "inputs", arrayOf(businessObject()), "outputs", arrayOf(businessObject()), "effect", string(),
                    "owner", string(), "bindingRef", string(), "sourceKind", string(), "runtimeState", string(),
                    "speccing", bool(), "inputSchema", businessObject(),
                    "outputSchema", businessObject(), "revision", integer()),
                    List.of("assetRef", "kind"));
            case "rg.tool.getInstruction" -> structuredObject(props("toolRef", string(), "name", string(),
                    "title", string(), "description", string(), "whenToUse", string(),
                    "inputs", arrayOf(businessObject()), "outputs", businessObject(),
                    "errors", arrayOf(businessObject()), "examples", arrayOf(businessObject())),
                    List.of("toolRef", "name", "title", "description", "whenToUse",
                            "inputs", "outputs", "errors", "examples"));
            case "rg.scenario.listCases" -> structuredObject(props("caseSetRef", string(), "toolRef", string(),
                    "revision", integer(), "rows", caseRows()),
                    List.of("caseSetRef", "rows"));
            case "rg.verdict.get" -> structuredObject(props("toolRef", string(), "state", string(),
                    "goldenSetId", string(), "byLayer", verdictLayerMatrix(),
                    "businessBacklog", arrayOf(businessObject()), "honestVerdict", honestVerdict(),
                    "baseline", businessObject(), "evidenceRef", string(), "latest", businessObject()),
                    List.of("toolRef", "state"));
            case "rg.evidence.get" -> structuredObject(props("toolRef", string(), "operation", string(),
                    "result", businessObject()), List.of("toolRef", "operation", "result"));
            case "rg.dsl.reference.get" -> dslReference();
            case "rg.library.upsert" -> structuredObject(props("libraryId", string(), "version", string(),
                    "operators", arrayOf(businessObject()), "functions", arrayOf(businessObject()),
                    "types", stringArray(), "canonicalFingerprint", string(),
                    "honestVerdict", honestVerdict()), List.of("libraryId", "version"));
            case "rg.resource.declare" -> structuredObject(props("resourceId", string(), "registered", bool(),
                    "host", string(), "method", string(), "contractId", string()),
                    List.of("resourceId", "registered", "host", "method", "contractId"));
            case "rg.feature.compose", "rg.tool.compose" -> structuredObject(props("assetRef", string(),
                    "assetKind", string(), "revision", integer(), "speccing", bool(), "executable", bool(),
                    "libraryRefs", stringArray(), "authoringContextFingerprint", string(),
                    "authoringReceiptFingerprint", string(), "honestVerdict", honestVerdict()),
                    List.of("assetRef", "revision", "speccing", "executable",
                            "authoringContextFingerprint", "authoringReceiptFingerprint"));
            case "rg.tool.setInstruction" -> structuredObject(props("toolRef", string(), "revision", integer(),
                    "instructionFingerprint", string(), "examplesDerivedFromGolden", bool(),
                    "honestVerdict", honestVerdict()), List.of("toolRef", "revision"));
            case "rg.scenario.upsertCases" -> structuredObject(props("caseSetRef", string(), "revision", integer(),
                    "rows", caseRows(), "proposed", arrayOf(businessObject()), "enumeratedCount", integer(),
                    "honestVerdict", honestVerdict()),
                    List.of("caseSetRef", "revision", "rows"));
            case "rg.oracle.propose" -> structuredObject(props("caseSetRef", string(), "caseId", string(),
                    "proposalStatus", string(), "revision", integer(), "awaiting", string(),
                    "honestVerdict", honestVerdict()), List.of("caseSetRef", "caseId"));
            case "rg.scenario.setDependencyBehavior" -> structuredObject(props("caseSetRef", string(),
                    "caseId", string(), "nodeId", string(), "behavior", behavior(), "revision", integer(),
                    "honestVerdict", honestVerdict()),
                    List.of("caseSetRef", "caseId", "nodeId"));
            case "rg.dsl.preview", "rg.gate.check" -> dslAuthoringOutput();
            case "rg.feature.rehearse", "rg.simulate", "rg.tool.baseline" -> structuredObject(props(
                    "toolRef", string(), "goldenSetId", string(), "evidenceFingerprint", string(), "draftRevision", integer(),
                    "caseSetRevision", integer(),
                    "side", enumString("RED", "GREEN"), "byLayer", executionLayerSummary(),
                    "cases", arrayOf(executionCase()), "realExternalCalls", integer(),
                    "honestVerdict", honestVerdict(), "evidenceRef", string(), "status", string(),
                    "caseSetRef", string(), "rounds", arrayOf(businessObject()),
                    "businessFingerprintStable", bool(), "remainingLimitations", stringArray(),
                    "attestation", attestation()),
                    List.of("goldenSetId", "side", "realExternalCalls"));
            case "rg.fixture.promote", "rg.fixture.provide" -> structuredObject(props(
                    "fixtureId", string(), "revision", integer(),
                    "lifecycle", string(), "scope", string(), "schemaRef", businessObject(),
                    "lineageRef", businessObject(),
                    "sourceKind", string()), List.of("fixtureId", "revision", "scope"));
            case "rg.tool.publishSpec" -> structuredObject(props("toolRef", string(), "proposalStatus", string(),
                    "revision", integer(), "awaiting", string()), List.of("toolRef", "proposalStatus", "revision"));
            case "rg.tool.publish" -> structuredObject(props("toolRef", string(), "publicationId", string(),
                    "artifactKind", string(), "goldenSetId", string(), "signoffRef", string()),
                    List.of("toolRef", "publicationId", "artifactKind"));
            case "rg.readiness.get" -> structuredObject(props("toolRef", string(), "state", string(),
                    "publishable", bool(), "goldenSetId", string(), "gates", businessObject(),
                    "attestation", attestation(), "remainingLimitations", stringArray()),
                    List.of("toolRef", "state", "publishable", "attestation"));
            default -> businessObject();
        };
    }
}
