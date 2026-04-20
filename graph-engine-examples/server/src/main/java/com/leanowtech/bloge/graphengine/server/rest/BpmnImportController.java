package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.bpmn.api.BpmnTranslator;
import com.leanowtech.bloge.bpmn.api.ExpressionTranslationMode;
import com.leanowtech.bloge.bpmn.api.TranslationOptions;
import com.leanowtech.bloge.bpmn.api.TranslationResult;
import com.leanowtech.bloge.bpmn.diagnostic.TranslationDiagnostic;
import com.leanowtech.bloge.bpmn.mapping.OperatorMappingConfigLoader;
import com.leanowtech.bloge.bpmn.mapping.BlogeMappingTarget;
import com.leanowtech.bloge.bpmn.mapping.BpmnMappingSelector;
import com.leanowtech.bloge.bpmn.mapping.DefaultMappingRule;
import com.leanowtech.bloge.bpmn.mapping.OperatorMappingConfig;
import com.leanowtech.bloge.bpmn.mapping.OperatorMappingRule;
import com.leanowtech.bloge.bpmn.model.BpmnProcess;
import com.leanowtech.bloge.bpmn.parser.BpmnJsonParser;
import com.leanowtech.bloge.graphengine.server.rest.dto.ImportBpmnRequest;
import com.leanowtech.bloge.graphengine.server.rest.dto.ImportBpmnResponse;
import com.leanowtech.bloge.graphengine.service.GraphEngineServiceErrorCode;
import com.leanowtech.bloge.graphengine.service.GraphEngineServiceException;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for the single-direction BPMN import endpoint described in
 * the AI-native graph-engine architecture (section 5.2).
 *
 * <p>Translates BPMN 2.0 XML into BLOGE DSL source using the
 * {@code bloge-bpmn-transformer} pipeline. The generated DSL can then be fed
 * into the existing {@code POST /api/v1/graphs/{key}/versions} endpoint to
 * create a versioned graph definition.</p>
 */
@RestController
@Validated
public class BpmnImportController {

    private static final String JSON_DEFAULT_MAPPING_RESOURCE = "/bpmn-json-import-defaults.json";

    private final BpmnJsonParser jsonParser = new BpmnJsonParser();
    private final OperatorMappingConfigLoader mappingConfigLoader = new OperatorMappingConfigLoader();

    /**
     * Translates a BPMN 2.0 XML document into BLOGE DSL source.
     *
     * <p>The request body carries the raw XML, optional translation options, and
     * optional operator mapping rules. When translation succeeds (no errors),
     * the response contains the generated DSL source. When errors are present,
     * {@code success} is {@code false} and {@code dslSource} may still contain a
     * partial translation for diagnostic purposes.</p>
     *
     * @param request import request with BPMN XML and optional parameters
     * @return translation response with DSL source and diagnostics
     */
    @PostMapping("/api/v1/import/bpmn")
    public ResponseEntity<ImportBpmnResponse> importBpmn(@Valid @RequestBody ImportBpmnRequest request) {
        boolean jsonRequest = request.bpmnJson() != null && !request.bpmnJson().isBlank();
        OperatorMappingConfig mappingConfig = buildMappingConfig(request, jsonRequest);
        TranslationOptions options = buildTranslationOptions(request);

        BpmnTranslator translator = new BpmnTranslator(mappingConfig, options);

        TranslationResult<String> result;
        try {
            if (jsonRequest) {
                BpmnProcess process = jsonParser.parse(request.bpmnJson());
                result = translator.translateToDsl(process);
            } else {
                try (InputStream input = new ByteArrayInputStream(
                        request.bpmnXml().getBytes(StandardCharsets.UTF_8))) {
                    result = translator.translateToDsl(input);
                }
            }
        } catch (Exception e) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.VALIDATION_FAILED,
                    "Failed to parse BPMN " + (jsonRequest ? "JSON" : "XML") + ": " + e.getMessage(),
                    e
            );
        }

        List<ImportBpmnResponse.DiagnosticEntry> diagnostics = result.diagnostics().stream()
                .map(BpmnImportController::toDiagnosticEntry)
                .toList();

        boolean success = !result.hasErrors();
        ImportBpmnResponse response = new ImportBpmnResponse(
                result.result(),
                success,
                diagnostics
        );

        return ResponseEntity.ok(response);
    }

    private OperatorMappingConfig buildMappingConfig(ImportBpmnRequest request, boolean jsonRequest) {
        List<OperatorMappingRule> rules = new ArrayList<>();
        Map<String, DefaultMappingRule> defaults = new LinkedHashMap<>();
        if (jsonRequest) {
            OperatorMappingConfig jsonDefaults = loadJsonImportDefaults();
            rules.addAll(jsonDefaults.mappings());
            defaults.putAll(jsonDefaults.defaults());
        }

        if (request.operatorMappings() != null && !request.operatorMappings().isEmpty()) {
            List<OperatorMappingRule> requestRules = new ArrayList<>(request.operatorMappings().size());
            for (ImportBpmnRequest.OperatorMappingRuleEntry entry : request.operatorMappings()) {
                requestRules.add(new OperatorMappingRule(
                        new BpmnMappingSelector(
                                nullToEmpty(entry.taskDefinitionKey()),
                                nullToEmpty(entry.type())
                        ),
                        new BlogeMappingTarget(
                                nullToEmpty(entry.operatorRef()),
                                nullToEmpty(entry.lang())
                        ),
                        entry.inputMapping() != null ? entry.inputMapping() : Map.of()
                ));
            }
            rules.addAll(0, requestRules);
        }

        if (request.defaultMappings() != null && !request.defaultMappings().isEmpty()) {
            for (Map.Entry<String, ImportBpmnRequest.DefaultMappingEntry> entry
                    : request.defaultMappings().entrySet()) {
                defaults.put(entry.getKey(), new DefaultMappingRule(
                        nullToEmpty(entry.getValue().operatorRef()),
                        nullToEmpty(entry.getValue().lang())
                ));
            }
        }

        return new OperatorMappingConfig(rules, defaults);
    }

    private OperatorMappingConfig loadJsonImportDefaults() {
        try (InputStream inputStream = getClass().getResourceAsStream(JSON_DEFAULT_MAPPING_RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing JSON BPMN import defaults: " + JSON_DEFAULT_MAPPING_RESOURCE);
            }
            return mappingConfigLoader.load(inputStream);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load JSON BPMN import defaults", e);
        }
    }

    private static TranslationOptions buildTranslationOptions(ImportBpmnRequest request) {
        boolean strict = request.strictMode() != null && request.strictMode();
        boolean sourceComments = request.generateSourceComments() == null || request.generateSourceComments();
        boolean docComments = request.generateDocComments() == null || request.generateDocComments();
        return new TranslationOptions(sourceComments, docComments, strict, null, ExpressionTranslationMode.AUTO);
    }

    private static ImportBpmnResponse.DiagnosticEntry toDiagnosticEntry(TranslationDiagnostic diagnostic) {
        return new ImportBpmnResponse.DiagnosticEntry(
                diagnostic.severity().name(),
                diagnostic.code().name(),
                diagnostic.elementId(),
                diagnostic.location(),
                diagnostic.message(),
                diagnostic.suggestion()
        );
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
