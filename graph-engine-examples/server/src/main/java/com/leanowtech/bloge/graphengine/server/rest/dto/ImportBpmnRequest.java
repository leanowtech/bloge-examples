package com.leanowtech.bloge.graphengine.server.rest.dto;

import jakarta.validation.constraints.AssertTrue;

import java.util.List;
import java.util.Map;

/**
 * HTTP payload for the {@code POST /api/v1/import/bpmn} endpoint.
 *
 * <p>Accepts either a BPMN 2.0 XML document or a supported JSON BPMN document,
 * plus optional translation parameters. The server translates the source into BLOGE DSL
 * using the {@code bloge-bpmn-transformer} pipeline and returns the generated DSL
 * together with any translation diagnostics.</p>
 *
 * @param bpmnXml              raw BPMN 2.0 XML content, optional when {@code bpmnJson} is used
 * @param bpmnJson             raw JSON BPMN content, optional when {@code bpmnXml} is used
 * @param strictMode           when {@code true}, warnings are promoted to hard
 *                             errors so CI pipelines can fail on lossy
 *                             translations; defaults to {@code false}
 * @param generateSourceComments when {@code true}, the generated DSL includes
 *                               source-mapping comments; defaults to {@code true}
 * @param generateDocComments  when {@code true}, the generated DSL includes
 *                             documentation comments; defaults to {@code true}
 * @param operatorMappings     explicit operator mapping rules that bind BPMN
 *                             task selectors to BLOGE operator references; may
 *                             be {@code null} or empty
 * @param defaultMappings      fallback mapping rules keyed by BPMN task type
 *                             (e.g. {@code "scriptTask"}); may be {@code null}
 *                             or empty
 */
public record ImportBpmnRequest(
        String bpmnXml,
        String bpmnJson,
        Boolean strictMode,
        Boolean generateSourceComments,
        Boolean generateDocComments,
        List<OperatorMappingRuleEntry> operatorMappings,
        Map<String, DefaultMappingEntry> defaultMappings
) {

    /**
     * Request validation hook ensuring the caller provides exactly one payload format.
     *
     * @return {@code true} when exactly one of {@code bpmnXml} or {@code bpmnJson} is non-blank
     */
    @AssertTrue(message = "Exactly one of bpmnXml or bpmnJson must be provided")
    public boolean hasExactlyOnePayloadFormat() {
        boolean hasXml = bpmnXml != null && !bpmnXml.isBlank();
        boolean hasJson = bpmnJson != null && !bpmnJson.isBlank();
        return hasXml ^ hasJson;
    }

    /**
     * One explicit operator mapping rule that binds a BPMN task selector to a
     * BLOGE operator target.
     *
     * @param taskDefinitionKey BPMN task definition key selector
     * @param type              BPMN element type selector (e.g. {@code "serviceTask"})
     * @param operatorRef       target BLOGE operator reference name
     * @param lang              optional scripting language for script tasks
     * @param inputMapping      static input parameter mappings
     */
    public record OperatorMappingRuleEntry(
            String taskDefinitionKey,
            String type,
            String operatorRef,
            String lang,
            Map<String, String> inputMapping
    ) {
    }

    /**
     * A fallback mapping rule applied when no explicit rule matches a BPMN task
     * of the given type.
     *
     * @param operatorRef default BLOGE operator reference name
     * @param lang        optional scripting language
     */
    public record DefaultMappingEntry(
            String operatorRef,
            String lang
    ) {
    }
}
