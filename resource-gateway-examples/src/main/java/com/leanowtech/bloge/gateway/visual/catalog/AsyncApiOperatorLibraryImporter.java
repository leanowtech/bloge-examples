package com.leanowtech.bloge.gateway.visual.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Projects a practical AsyncAPI subset into visual operator-library definitions.
 */
@Service
public class AsyncApiOperatorLibraryImporter {

    private static final ObjectMapper ASYNCAPI_TEXT_MAPPER = new YAMLMapper();
    private static final TypeReference<Map<String, Object>> ASYNCAPI_MAP = new TypeReference<>() {
    };
    private static final Pattern SEMVER = Pattern.compile(
            "\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?");
    private static final Set<String> SUPPORTED_SOURCE_KINDS = Set.of(
            "event-source",
            "message-handler",
            "webhook"
    );

    /**
     * Projects an AsyncAPI document into an operator-library draft without storing it.
     *
     * @param request projection request
     * @return generated operator library and projection diagnostics
     */
    public AsyncApiOperatorLibraryImportResult project(AsyncApiOperatorLibraryImportRequest request) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (request == null) {
            diagnostics.add(VisualDiagnostic.error("visual.library.asyncapi.requestMissing",
                    "AsyncAPI operator-library import request is required.",
                    "/"));
            return result(null, diagnostics);
        }
        Map<String, Object> asyncApi = asyncApiDocument(request, diagnostics);
        if (asyncApi.isEmpty() && !hasErrors(diagnostics)) {
            diagnostics.add(VisualDiagnostic.error("visual.library.asyncapi.documentMissing",
                    "AsyncAPI document is required as asyncApi or asyncApiText.",
                    request.asyncApiText().isBlank() ? "/asyncApi" : "/asyncApiText"));
        }
        if (hasErrors(diagnostics)) {
            return result(null, diagnostics);
        }

        List<ProjectionCandidate> candidates = projectionCandidates(asyncApi, diagnostics);
        List<AsyncApiOperationSummary> availableOperations = operationSummaries(candidates, asyncApi);
        boolean selectionApplied = hasSelection(request);
        if (candidates.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.library.asyncapi.operationsMissing",
                    "AsyncAPI document must declare channels with publish/subscribe operations or root operations.",
                    "/asyncApi"));
        }
        if (hasErrors(diagnostics)) {
            return result(null, diagnostics, availableOperations, List.of(), selectionApplied);
        }

        candidates = selectedCandidates(request, candidates);
        List<AsyncApiOperationSummary> selectedOperations = operationSummaries(candidates, asyncApi);
        if (candidates.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.library.asyncapi.selectionMissing",
                    "No AsyncAPI operation/message matched the requested selector.",
                    selectionTarget(request)));
            return result(null, diagnostics, availableOperations, selectedOperations, selectionApplied);
        }

        String libraryVersion = libraryVersion(request, asyncApi);
        List<OperatorDefinition> operators = new ArrayList<>();
        Set<String> operatorRefs = new LinkedHashSet<>();
        for (ProjectionCandidate candidate : candidates) {
            operators.add(operatorFrom(candidate, asyncApi, libraryVersion, operatorRefs, diagnostics));
        }
        if (hasErrors(diagnostics)) {
            return result(null, diagnostics, availableOperations, selectedOperations, selectionApplied);
        }
        OperatorLibrary library = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                libraryId(request, asyncApi),
                displayName(request, asyncApi),
                libraryVersion,
                owner(request, asyncApi),
                request.status(),
                operators
        );
        return result(library, diagnostics, availableOperations, selectedOperations, selectionApplied);
    }

    /**
     * Discovers importable operations/messages in an AsyncAPI document without storing or projecting a library.
     *
     * @param request discovery request containing {@code asyncApi} or {@code asyncApiText}
     * @return operation/message summaries and parse/discovery diagnostics
     */
    public AsyncApiOperationDiscoveryResult discoverOperations(AsyncApiOperatorLibraryImportRequest request) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (request == null) {
            diagnostics.add(VisualDiagnostic.error("visual.library.asyncapi.requestMissing",
                    "AsyncAPI operator-library discovery request is required.",
                    "/"));
            return operationResult(List.of(), diagnostics);
        }
        Map<String, Object> asyncApi = asyncApiDocument(request, diagnostics);
        if (asyncApi.isEmpty() && !hasErrors(diagnostics)) {
            diagnostics.add(VisualDiagnostic.error("visual.library.asyncapi.documentMissing",
                    "AsyncAPI document is required as asyncApi or asyncApiText.",
                    request.asyncApiText().isBlank() ? "/asyncApi" : "/asyncApiText"));
        }
        if (hasErrors(diagnostics)) {
            return operationResult(List.of(), diagnostics);
        }

        List<ProjectionCandidate> candidates = projectionCandidates(asyncApi, diagnostics);
        if (candidates.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.library.asyncapi.operationsMissing",
                    "AsyncAPI document must declare channels with publish/subscribe operations or root operations.",
                    "/asyncApi"));
            return operationResult(List.of(), diagnostics);
        }

        return operationResult(operationSummaries(candidates, asyncApi), diagnostics);
    }

    private static AsyncApiOperatorLibraryImportResult result(OperatorLibrary library,
                                                              List<VisualDiagnostic> diagnostics) {
        return result(library, diagnostics, List.of(), List.of(), false);
    }

    private static AsyncApiOperatorLibraryImportResult result(OperatorLibrary library,
                                                              List<VisualDiagnostic> diagnostics,
                                                              List<AsyncApiOperationSummary> availableOperations,
                                                              List<AsyncApiOperationSummary> selectedOperations,
                                                              boolean selectionApplied) {
        OperatorLibraryValidationResult validation = new OperatorLibraryValidationResult(
                diagnostics.stream().noneMatch(VisualDiagnostic::error),
                diagnostics,
                OperatorLibraryImpactReview.empty(),
                library == null ? OperatorLibraryProfile.empty() : OperatorLibraryProfile.from(library, diagnostics)
        );
        return new AsyncApiOperatorLibraryImportResult(library, validation,
                availableOperations,
                selectedOperations,
                Math.max(0, availableOperations.size() - selectedOperations.size()),
                selectionApplied);
    }

    private static AsyncApiOperationDiscoveryResult operationResult(List<AsyncApiOperationSummary> operations,
                                                                    List<VisualDiagnostic> diagnostics) {
        return new AsyncApiOperationDiscoveryResult(
                operations,
                new VisualValidationResult(diagnostics.stream().noneMatch(VisualDiagnostic::error), diagnostics)
        );
    }

    private static List<AsyncApiOperationSummary> operationSummaries(List<ProjectionCandidate> candidates,
                                                                     Map<String, Object> asyncApi) {
        return candidates.stream()
                .map(candidate -> operationSummary(candidate, asyncApi))
                .toList();
    }

    private static AsyncApiOperationSummary operationSummary(ProjectionCandidate candidate,
                                                             Map<String, Object> asyncApi) {
        SourceKindProjection sourceKind = sourceKindProjection(candidate);
        PayloadProjection payload = payloadProjection(candidate, asyncApi);
        String projectionLevel = "BLOCKED".equals(sourceKind.level())
                ? "BLOCKED"
                : payload.level();
        String projectionMessage = "BLOCKED".equals(sourceKind.level())
                ? sourceKind.message()
                : payload.message();
        return new AsyncApiOperationSummary(
                operationId(candidate),
                candidate.channelName(),
                candidate.address(),
                candidate.operationKind(),
                messageName(candidate),
                displayName(candidate),
                sourceKind.sourceKind(),
                payload.hasPayload(),
                payload.payloadType(),
                tags(candidate, sourceKind.sourceKind()),
                projectionLevel,
                projectionMessage
        );
    }

    private static Map<String, Object> asyncApiDocument(AsyncApiOperatorLibraryImportRequest request,
                                                        List<VisualDiagnostic> diagnostics) {
        if (request.asyncApi() != null && !request.asyncApi().isEmpty()) {
            return request.asyncApi();
        }
        if (request.asyncApiText() == null || request.asyncApiText().isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = ASYNCAPI_TEXT_MAPPER.readValue(request.asyncApiText(), ASYNCAPI_MAP);
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException e) {
            diagnostics.add(VisualDiagnostic.error("visual.library.asyncapi.documentMalformed",
                    "AsyncAPI document must be valid JSON or YAML: " + e.getOriginalMessage(),
                    "/asyncApiText"));
            return Map.of();
        }
    }

    private static List<ProjectionCandidate> projectionCandidates(Map<String, Object> asyncApi,
                                                                  List<VisualDiagnostic> diagnostics) {
        List<ProjectionCandidate> candidates = new ArrayList<>();
        candidates.addAll(channelOperationCandidates(asyncApi, diagnostics));
        candidates.addAll(rootOperationCandidates(asyncApi, diagnostics));
        return candidates;
    }

    private static List<ProjectionCandidate> selectedCandidates(AsyncApiOperatorLibraryImportRequest request,
                                                                List<ProjectionCandidate> candidates) {
        if (!hasSelection(request)) {
            return candidates;
        }
        List<AsyncApiOperationSelection> selections = selections(request);
        return candidates.stream()
                .filter(candidate -> selections.stream().anyMatch(selection -> matchesSelection(candidate, selection)))
                .toList();
    }

    private static boolean hasSelection(AsyncApiOperatorLibraryImportRequest request) {
        if (request.selections().stream().anyMatch(AsyncApiOperatorLibraryImporter::hasSelection)) {
            return true;
        }
        return !normalizedSelector(request.operationId()).isBlank()
                || !normalizedSelector(request.channel()).isBlank()
                || !normalizedAction(request.action()).isBlank()
                || !normalizedSelector(request.messageName()).isBlank();
    }

    private static boolean hasSelection(AsyncApiOperationSelection selection) {
        return !normalizedSelector(selection.operationId()).isBlank()
                || !normalizedSelector(selection.channel()).isBlank()
                || !normalizedAction(selection.action()).isBlank()
                || !normalizedSelector(selection.messageName()).isBlank();
    }

    private static List<AsyncApiOperationSelection> selections(AsyncApiOperatorLibraryImportRequest request) {
        List<AsyncApiOperationSelection> explicit = request.selections().stream()
                .filter(AsyncApiOperatorLibraryImporter::hasSelection)
                .toList();
        if (!explicit.isEmpty()) {
            return explicit;
        }
        AsyncApiOperationSelection legacy = new AsyncApiOperationSelection(
                request.operationId(),
                request.channel(),
                request.action(),
                request.messageName()
        );
        return hasSelection(legacy) ? List.of(legacy) : List.of();
    }

    private static boolean matchesSelection(ProjectionCandidate candidate, AsyncApiOperationSelection selection) {
        String operationId = normalizedSelector(selection.operationId());
        String channel = normalizedSelector(selection.channel());
        String action = normalizedAction(selection.action());
        String messageName = normalizedSelector(selection.messageName());
        return (operationId.isBlank() || normalizedSelector(operationId(candidate)).equals(operationId))
                && (channel.isBlank()
                || normalizedSelector(candidate.channelName()).equals(channel)
                || normalizedSelector(candidate.address()).equals(channel))
                && (action.isBlank() || normalizedAction(candidate.operationKind()).equals(action))
                && (messageName.isBlank() || normalizedSelector(messageName(candidate)).equals(messageName));
    }

    private static String selectionTarget(AsyncApiOperatorLibraryImportRequest request) {
        if (request.selections().stream().anyMatch(AsyncApiOperatorLibraryImporter::hasSelection)) {
            return "/selections";
        }
        if (!normalizedSelector(request.operationId()).isBlank()) {
            return "/operationId";
        }
        if (!normalizedSelector(request.channel()).isBlank()) {
            return "/channel";
        }
        if (!normalizedAction(request.action()).isBlank()) {
            return "/action";
        }
        if (!normalizedSelector(request.messageName()).isBlank()) {
            return "/messageName";
        }
        return "/asyncApi";
    }

    private static List<ProjectionCandidate> channelOperationCandidates(Map<String, Object> asyncApi,
                                                                        List<VisualDiagnostic> diagnostics) {
        Object rawChannels = asyncApi.get("channels");
        if (!(rawChannels instanceof Map<?, ?> channels)) {
            return List.of();
        }
        List<ProjectionCandidate> candidates = new ArrayList<>();
        objectMap(channels).forEach((channelName, rawChannel) -> {
            if (!(rawChannel instanceof Map<?, ?> rawChannelMap)) {
                return;
            }
            Map<String, Object> channel = objectMap(rawChannelMap);
            String address = string(channel.get("address"));
            if (address.isBlank()) {
                address = channelName;
            }
            addChannelOperation(asyncApi, candidates, channelName, address, channel, "subscribe", diagnostics);
            addChannelOperation(asyncApi, candidates, channelName, address, channel, "publish", diagnostics);
        });
        return candidates;
    }

    private static void addChannelOperation(Map<String, Object> asyncApi,
                                            List<ProjectionCandidate> candidates,
                                            String channelName,
                                            String address,
                                            Map<String, Object> channel,
                                            String operationKind,
                                            List<VisualDiagnostic> diagnostics) {
        Object rawOperation = channel.get(operationKind);
        if (!(rawOperation instanceof Map<?, ?> operation)) {
            return;
        }
        Map<String, Object> operationMap = objectMap(operation);
        List<Map<String, Object>> messages = messages(asyncApi, operationMap.get("message"), diagnostics);
        if (messages.isEmpty()) {
            messages = messages(asyncApi, channel.get("messages"), diagnostics);
        }
        if (messages.isEmpty()) {
            messages = List.of(Map.of());
        }
        for (Map<String, Object> message : messages) {
            candidates.add(new ProjectionCandidate(channelName, address, operationKind, operationMap, channel,
                    message, "/asyncApi/channels/" + channelName + "/" + operationKind));
        }
    }

    private static List<ProjectionCandidate> rootOperationCandidates(Map<String, Object> asyncApi,
                                                                     List<VisualDiagnostic> diagnostics) {
        Object rawOperations = asyncApi.get("operations");
        if (!(rawOperations instanceof Map<?, ?> operations)) {
            return List.of();
        }
        List<ProjectionCandidate> candidates = new ArrayList<>();
        objectMap(operations).forEach((operationId, rawOperation) -> {
            if (!(rawOperation instanceof Map<?, ?> operation)) {
                return;
            }
            Map<String, Object> operationMap = objectMap(operation);
            Map<String, Object> channel = channelForOperation(asyncApi, operationMap);
            String channelName = channelName(operationMap.get("channel"));
            String address = string(channel.get("address"));
            if (address.isBlank()) {
                address = channelName.isBlank() ? operationId : channelName;
            }
            String operationKind = switch (string(operationMap.get("action")).toLowerCase(Locale.ROOT)) {
                case "receive" -> "subscribe";
                case "send" -> "publish";
                default -> string(operationMap.get("action"));
            };
            if (operationKind.isBlank()) {
                operationKind = "subscribe";
            }
            List<Map<String, Object>> messages = messages(asyncApi, operationMap.get("messages"), diagnostics);
            if (messages.isEmpty()) {
                messages = messages(asyncApi, channel.get("messages"), diagnostics);
            }
            if (messages.isEmpty()) {
                messages = List.of(Map.of());
            }
            for (Map<String, Object> message : messages) {
                candidates.add(new ProjectionCandidate(channelName.isBlank() ? operationId : channelName, address,
                        operationKind, withOperationId(operationMap, operationId), channel, message,
                        "/asyncApi/operations/" + operationId));
            }
        });
        return candidates;
    }

    private static Map<String, Object> withOperationId(Map<String, Object> operationMap, String operationId) {
        if (!string(operationMap.get("operationId")).isBlank()) {
            return operationMap;
        }
        Map<String, Object> copy = new LinkedHashMap<>(operationMap);
        copy.put("operationId", operationId);
        return copy;
    }

    private static Map<String, Object> channelForOperation(Map<String, Object> asyncApi,
                                                           Map<String, Object> operationMap) {
        Object rawChannel = operationMap.get("channel");
        Object resolved = resolveMaybeRef(asyncApi, rawChannel, new ArrayList<>());
        return resolved instanceof Map<?, ?> map ? objectMap(map) : Map.of();
    }

    private static String channelName(Object rawChannel) {
        if (rawChannel instanceof Map<?, ?> map) {
            String ref = string(objectMap(map).get("$ref"));
            if (!ref.isBlank()) {
                return pointerTail(ref);
            }
            return string(objectMap(map).get("address"));
        }
        return "";
    }

    private static List<Map<String, Object>> messages(Map<String, Object> asyncApi,
                                                      Object rawMessages,
                                                      List<VisualDiagnostic> diagnostics) {
        Object resolved = resolveMaybeRef(asyncApi, rawMessages, new ArrayList<>());
        if (resolved instanceof List<?> list) {
            List<Map<String, Object>> messages = new ArrayList<>();
            for (Object item : list) {
                messages.addAll(messages(asyncApi, item, diagnostics));
            }
            return messages;
        }
        if (!(resolved instanceof Map<?, ?> rawMap)) {
            return List.of();
        }
        Map<String, Object> map = objectMap(rawMap);
        Object oneOf = map.get("oneOf");
        if (oneOf instanceof List<?> alternatives) {
            List<Map<String, Object>> messages = new ArrayList<>();
            for (Object item : alternatives) {
                messages.addAll(messages(asyncApi, item, diagnostics));
            }
            return messages;
        }
        if (messageLike(map)) {
            return List.of(map);
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        map.forEach((ignored, value) -> {
            Object item = resolveMaybeRef(asyncApi, value, new ArrayList<>());
            if (item instanceof Map<?, ?> itemMap) {
                messages.add(objectMap(itemMap));
            }
        });
        return messages;
    }

    private static boolean messageLike(Map<String, Object> map) {
        return map.containsKey("payload")
                || map.containsKey("name")
                || map.containsKey("title")
                || map.containsKey("summary")
                || map.containsKey("description")
                || map.containsKey("x-bloge-source-kind");
    }

    private static OperatorDefinition operatorFrom(ProjectionCandidate candidate,
                                                   Map<String, Object> asyncApi,
                                                   String libraryVersion,
                                                   Set<String> operatorRefs,
                                                   List<VisualDiagnostic> diagnostics) {
        String sourceKind = sourceKind(candidate, diagnostics);
        SchemaEnvelope payload = payloadSchema(candidate, asyncApi, diagnostics);
        String baseName = firstNonBlank(
                string(candidate.message().get("name")),
                string(candidate.message().get("title")),
                string(candidate.operation().get("operationId")),
                candidate.channelName()
        );
        String operatorRef = uniqueOperatorRef("asyncapi:" + identifierToken(baseName), operatorRefs);
        List<String> tags = tags(candidate, sourceKind);
        OperatorDefinition.Ports ports = switch (sourceKind) {
            case "message-handler" -> new OperatorDefinition.Ports(
                    List.of(new OperatorDefinition.Port("message", payload, true, "Message payload.")),
                    List.of(new OperatorDefinition.Port("ack", acknowledgementSchema(), true,
                            "Message handling acknowledgement."))
            );
            case "webhook" -> new OperatorDefinition.Ports(
                    List.of(),
                    List.of(new OperatorDefinition.Port("request", payload, true, "Webhook request payload."))
            );
            default -> new OperatorDefinition.Ports(
                    List.of(),
                    List.of(new OperatorDefinition.Port("event", payload, true, "Event payload."))
            );
        };
        Map<String, Object> loweringParameters = loweringParameters(sourceKind, candidate);
        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                operatorRef,
                libraryVersion,
                new OperatorDefinition.Display(displayName(candidate), description(candidate), tags),
                source(sourceKind, candidate),
                ports,
                SchemaEnvelope.opaque(),
                new OperatorDefinition.Capabilities("READ_EXTERNAL", "IDEMPOTENT", false, false, false),
                new OperatorDefinition.Lowering(sourceKind, "", loweringParameters),
                List.of()
        );
    }

    private static String sourceKind(ProjectionCandidate candidate, List<VisualDiagnostic> diagnostics) {
        SourceKindProjection projection = sourceKindProjection(candidate);
        if ("BLOCKED".equals(projection.level())) {
            diagnostics.add(VisualDiagnostic.error("visual.library.asyncapi.sourceKindUnsupported",
                    projection.message(),
                    candidate.target() + "/x-bloge-source-kind"));
        }
        return projection.sourceKind();
    }

    private static SourceKindProjection sourceKindProjection(ProjectionCandidate candidate) {
        String explicit = firstNonBlank(
                string(candidate.operation().get("x-bloge-source-kind")),
                string(candidate.message().get("x-bloge-source-kind")),
                string(candidate.channel().get("x-bloge-source-kind"))
        ).trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (!explicit.isBlank()) {
            if (SUPPORTED_SOURCE_KINDS.contains(explicit)) {
                return new SourceKindProjection(explicit, "READY",
                        "Ready to project as a runtime-blocked %s operator.".formatted(explicit));
            }
            return new SourceKindProjection(explicit, "BLOCKED",
                    "AsyncAPI x-bloge-source-kind '%s' is unsupported; use one of %s."
                            .formatted(explicit, SUPPORTED_SOURCE_KINDS));
        }
        if (hasHttpBinding(candidate.operation()) || hasHttpBinding(candidate.channel())) {
            return new SourceKindProjection("webhook", "READY",
                    "Ready to project as a runtime-blocked webhook operator.");
        }
        if ("publish".equalsIgnoreCase(candidate.operationKind())
                || "send".equalsIgnoreCase(candidate.operationKind())) {
            return new SourceKindProjection("message-handler", "READY",
                    "Ready to project as a runtime-blocked message-handler operator.");
        }
        return new SourceKindProjection("event-source", "READY",
                "Ready to project as a runtime-blocked event-source operator.");
    }

    private static boolean hasHttpBinding(Map<String, Object> value) {
        Object rawBindings = value.get("bindings");
        if (!(rawBindings instanceof Map<?, ?> bindings)) {
            return false;
        }
        return objectMap(bindings).containsKey("http");
    }

    private static OperatorDefinition.Source source(String sourceKind, ProjectionCandidate candidate) {
        if (!"webhook".equals(sourceKind)) {
            return new OperatorDefinition.Source(sourceKind, "", "", "", false);
        }
        return new OperatorDefinition.Source(sourceKind, "", webhookMethod(candidate), webhookPath(candidate),
                false);
    }

    private static Map<String, Object> loweringParameters(String sourceKind, ProjectionCandidate candidate) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        switch (sourceKind) {
            case "message-handler" -> parameters.put("channel", firstNonBlank(candidate.address(),
                    candidate.channelName()));
            case "webhook" -> {
                parameters.put("method", webhookMethod(candidate));
                parameters.put("path", webhookPath(candidate));
            }
            default -> parameters.put("eventType", eventType(candidate));
        }
        return parameters;
    }

    private static String eventType(ProjectionCandidate candidate) {
        return firstNonBlank(
                string(candidate.message().get("x-bloge-event-type")),
                string(candidate.message().get("name")),
                string(candidate.message().get("title")),
                string(candidate.operation().get("operationId")),
                candidate.address(),
                candidate.channelName()
        );
    }

    private static String webhookMethod(ProjectionCandidate candidate) {
        return firstNonBlank(
                string(candidate.operation().get("x-bloge-webhook-method")),
                httpBindingValue(candidate.operation(), "method"),
                httpBindingValue(candidate.channel(), "method"),
                "POST"
        ).toUpperCase(Locale.ROOT);
    }

    private static String webhookPath(ProjectionCandidate candidate) {
        String path = firstNonBlank(
                string(candidate.operation().get("x-bloge-webhook-path")),
                candidate.address(),
                candidate.channelName()
        );
        if (path.isBlank()) {
            return "/asyncapi/webhook";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String httpBindingValue(Map<String, Object> value, String key) {
        Object rawBindings = value.get("bindings");
        if (!(rawBindings instanceof Map<?, ?> bindings)) {
            return "";
        }
        Object rawHttp = objectMap(bindings).get("http");
        if (!(rawHttp instanceof Map<?, ?> http)) {
            return "";
        }
        return string(objectMap(http).get(key));
    }

    private static SchemaEnvelope payloadSchema(ProjectionCandidate candidate,
                                                Map<String, Object> asyncApi,
                                                List<VisualDiagnostic> diagnostics) {
        Object rawPayload = candidate.message().get("payload");
        if (rawPayload == null) {
            diagnostics.add(VisualDiagnostic.warning("visual.library.asyncapi.payloadMissing",
                    "AsyncAPI message '%s' has no payload schema; generated operator uses an opaque object schema."
                            .formatted(displayName(candidate)),
                    candidate.target() + "/message/payload"));
            return SchemaEnvelope.opaque();
        }
        Object resolved = resolveMaybeRef(asyncApi, rawPayload, new ArrayList<>());
        if (!(resolved instanceof Map<?, ?> schema)) {
            diagnostics.add(VisualDiagnostic.warning("visual.library.asyncapi.payloadUnsupported",
                    "AsyncAPI message '%s' payload schema is not an object; generated operator uses an opaque object schema."
                            .formatted(displayName(candidate)),
                    candidate.target() + "/message/payload"));
            return SchemaEnvelope.opaque();
        }
        return new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", normalizeSchema(objectMap(schema)));
    }

    private static PayloadProjection payloadProjection(ProjectionCandidate candidate,
                                                       Map<String, Object> asyncApi) {
        Object rawPayload = candidate.message().get("payload");
        if (rawPayload == null) {
            return new PayloadProjection(false, "opaque", "WARNING",
                    "Message has no payload schema; projection will use an opaque object schema.");
        }
        Object resolved = resolveMaybeRef(asyncApi, rawPayload, new ArrayList<>());
        if (!(resolved instanceof Map<?, ?> schema)) {
            return new PayloadProjection(true, "opaque", "WARNING",
                    "Message payload schema is not an object; projection will use an opaque object schema.");
        }
        return new PayloadProjection(true, schemaType(objectMap(schema)), "READY",
                "Ready to project into a runtime-blocked operator-library draft.");
    }

    private static Map<String, Object> normalizeSchema(Map<String, Object> schema) {
        Map<String, Object> copy = new LinkedHashMap<>();
        schema.forEach((key, value) -> copy.put(key, deepCopyValue(value)));
        if (!copy.containsKey("type") && copy.containsKey("properties")) {
            copy.put("type", "object");
        }
        return copy;
    }

    private static SchemaEnvelope acknowledgementSchema() {
        return SchemaEnvelope.object(Map.of(
                "accepted", Map.of("type", "boolean"),
                "messageId", Map.of("type", "string")
        ), List.of("accepted"));
    }

    private static String displayName(ProjectionCandidate candidate) {
        return firstNonBlank(
                string(candidate.message().get("title")),
                string(candidate.message().get("name")),
                string(candidate.operation().get("summary")),
                string(candidate.operation().get("operationId")),
                candidate.channelName()
        );
    }

    private static String operationId(ProjectionCandidate candidate) {
        return string(candidate.operation().get("operationId"));
    }

    private static String messageName(ProjectionCandidate candidate) {
        return firstNonBlank(
                string(candidate.message().get("name")),
                string(candidate.message().get("title"))
        );
    }

    private static String description(ProjectionCandidate candidate) {
        return firstNonBlank(
                string(candidate.message().get("description")),
                string(candidate.operation().get("description")),
                "Projected from AsyncAPI channel '%s'.".formatted(candidate.channelName())
        );
    }

    private static List<String> tags(ProjectionCandidate candidate, String sourceKind) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        tags.add("asyncapi");
        tags.add(sourceKind);
        tags.addAll(tags(candidate.operation().get("tags")));
        tags.addAll(tags(candidate.message().get("tags")));
        return List.copyOf(tags);
    }

    private static List<String> tags(Object rawTags) {
        if (!(rawTags instanceof List<?> list)) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                String name = string(objectMap(map).get("name"));
                if (!name.isBlank()) {
                    tags.add(name);
                }
            } else if (item != null && !String.valueOf(item).isBlank()) {
                tags.add(String.valueOf(item).trim());
            }
        }
        return tags;
    }

    private static String uniqueOperatorRef(String base, Set<String> operatorRefs) {
        String candidate = base;
        int index = 2;
        while (!operatorRefs.add(candidate)) {
            candidate = base + "-" + index;
            index += 1;
        }
        return candidate;
    }

    private static String libraryId(AsyncApiOperatorLibraryImportRequest request, Map<String, Object> asyncApi) {
        if (!request.libraryId().isBlank()) {
            return request.libraryId();
        }
        return namespaceToken(firstNonBlank(infoString(asyncApi, "title"), "asyncapi"), "-")
                .toLowerCase(Locale.ROOT) + "-operators";
    }

    private static String displayName(AsyncApiOperatorLibraryImportRequest request, Map<String, Object> asyncApi) {
        if (!request.displayName().isBlank()) {
            return request.displayName();
        }
        String title = infoString(asyncApi, "title");
        return title.isBlank() ? "AsyncAPI operators" : title + " operators";
    }

    private static String libraryVersion(AsyncApiOperatorLibraryImportRequest request, Map<String, Object> asyncApi) {
        String version = firstNonBlank(request.version(), infoString(asyncApi, "version"));
        return SEMVER.matcher(version).matches() ? version : "1.0.0";
    }

    private static String owner(AsyncApiOperatorLibraryImportRequest request, Map<String, Object> asyncApi) {
        if (!request.owner().isBlank()) {
            return request.owner();
        }
        Object rawInfo = asyncApi.get("info");
        if (!(rawInfo instanceof Map<?, ?> info)) {
            return "";
        }
        Object rawContact = objectMap(info).get("contact");
        if (!(rawContact instanceof Map<?, ?> contact)) {
            return "";
        }
        return firstNonBlank(string(objectMap(contact).get("name")), string(objectMap(contact).get("email")));
    }

    private static String infoString(Map<String, Object> asyncApi, String key) {
        Object rawInfo = asyncApi.get("info");
        if (!(rawInfo instanceof Map<?, ?> info)) {
            return "";
        }
        return string(objectMap(info).get(key));
    }

    private static Object resolveMaybeRef(Map<String, Object> root,
                                          Object value,
                                          List<String> referenceStack) {
        if (value instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>(list.size());
            for (Object item : list) {
                resolved.add(resolveMaybeRef(root, item, referenceStack));
            }
            return resolved;
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            return value;
        }
        Map<String, Object> map = objectMap(rawMap);
        String ref = string(map.get("$ref"));
        if (ref.isBlank()) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((key, item) -> resolved.put(key, resolveMaybeRef(root, item, referenceStack)));
            return resolved;
        }
        if (referenceStack.contains(ref)) {
            return map;
        }
        Optional<Object> target = resolveJsonPointer(root, ref);
        if (target.isEmpty()) {
            return map;
        }
        referenceStack.add(ref);
        Object resolvedTarget = resolveMaybeRef(root, deepCopyValue(target.get()), referenceStack);
        referenceStack.remove(referenceStack.size() - 1);
        if (!(resolvedTarget instanceof Map<?, ?> resolvedMap)) {
            return resolvedTarget;
        }
        Map<String, Object> merged = objectMap(resolvedMap);
        map.forEach((key, item) -> {
            if (!"$ref".equals(key)) {
                merged.put(key, resolveMaybeRef(root, item, referenceStack));
            }
        });
        return merged;
    }

    private static Optional<Object> resolveJsonPointer(Map<String, Object> root, String ref) {
        if (!ref.startsWith("#/")) {
            return Optional.empty();
        }
        Object current = root;
        String[] segments = ref.substring(2).split("/");
        for (String rawSegment : segments) {
            String segment = rawSegment.replace("~1", "/").replace("~0", "~");
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                return Optional.empty();
            }
            current = map.get(segment);
        }
        return Optional.ofNullable(current);
    }

    private static String pointerTail(String ref) {
        if (ref == null || ref.isBlank()) {
            return "";
        }
        int index = ref.lastIndexOf('/');
        String tail = index < 0 ? ref : ref.substring(index + 1);
        return tail.replace("~1", "/").replace("~0", "~");
    }

    private static Map<String, Object> objectMap(Map<?, ?> rawMap) {
        Map<String, Object> map = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> map.put(String.valueOf(key), value));
        return map;
    }

    private static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), deepCopyValue(item)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(deepCopyValue(item));
            }
            return copy;
        }
        return value;
    }

    private static String schemaType(Map<String, Object> schema) {
        Object rawType = schema.get("type");
        if (rawType instanceof List<?> types) {
            return types.stream()
                    .map(AsyncApiOperatorLibraryImporter::string)
                    .filter(value -> !value.isBlank() && !"null".equals(value))
                    .findFirst()
                    .orElse("opaque");
        }
        String type = string(rawType);
        if (!type.isBlank()) {
            return type;
        }
        if (schema.containsKey("properties")) {
            return "object";
        }
        if (schema.containsKey("items") || schema.containsKey("prefixItems")) {
            return "array";
        }
        return "opaque";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String normalizedSelector(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizedAction(String value) {
        return switch (normalizedSelector(value)) {
            case "receive" -> "subscribe";
            case "send" -> "publish";
            default -> normalizedSelector(value);
        };
    }

    private static boolean hasErrors(List<VisualDiagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(VisualDiagnostic::error);
    }

    private static String identifierToken(String value) {
        String token = namespaceToken(value, "_");
        return token.isBlank() ? "message" : token.replace('-', '_').replace('.', '_').replace(':', '_');
    }

    private static String namespaceToken(String value, String separator) {
        String normalized = value == null ? "" : value.trim()
                .replaceAll("[^A-Za-z0-9]+", separator)
                .replaceAll(Pattern.quote(separator) + "+", separator)
                .replaceAll("^" + Pattern.quote(separator) + "|" + Pattern.quote(separator) + "$", "");
        if (normalized.isBlank()) {
            normalized = "asyncapi";
        }
        if (!Character.isLetter(normalized.charAt(0)) && normalized.charAt(0) != '_') {
            normalized = "a" + separator + normalized;
        }
        return normalized;
    }

    private record ProjectionCandidate(
            String channelName,
            String address,
            String operationKind,
            Map<String, Object> operation,
            Map<String, Object> channel,
            Map<String, Object> message,
            String target
    ) {
    }

    private record SourceKindProjection(
            String sourceKind,
            String level,
            String message
    ) {
    }

    private record PayloadProjection(
            boolean hasPayload,
            String payloadType,
            String level,
            String message
    ) {
    }
}
