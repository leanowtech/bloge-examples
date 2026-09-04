package com.leanowtech.bloge.gateway.agenttdd;

import java.util.List;
import java.util.Map;

/**
 * Versioned, scoped and payload-free BLOGE graph authoring reference returned to coding Agents.
 *
 * @param schemaVersion reference wire schema
 * @param languageVersion BLOGE language dependency version
 * @param compilerProfile Resource Gateway compiler profile
 * @param supportedRootKinds top-level definitions accepted by this authoring path
 * @param referenceVersion fingerprint of the server-owned static reference bundle
 * @param authoringContextFingerprint fingerprint binding language, scope, libraries and contracts
 * @param topics selected syntax topics
 * @param operators selected authorized operator contract lenses
 * @param functions callable function signatures available in this context
 * @param examples selected server-certified examples
 * @param limits advertised request and response limits
 */
public record DslReferenceSnapshot(
        String schemaVersion,
        String languageVersion,
        String compilerProfile,
        List<String> supportedRootKinds,
        String referenceVersion,
        String authoringContextFingerprint,
        List<Topic> topics,
        List<OperatorContract> operators,
        List<FunctionContract> functions,
        List<Example> examples,
        Limits limits
) {
    /** One stable grammar or authoring topic. */
    public record Topic(String topicId, String title, List<Rule> rules, List<String> exampleRefs) { }

    /** One server-owned syntax rule with no user-authored material. */
    public record Rule(String ruleId, String summary) { }

    /** Minimal operator contract required to author a graph without leaking runtime configuration. */
    public record OperatorContract(
            String operatorRef,
            String archetype,
            String effect,
            List<PortContract> inputs,
            List<PortContract> outputs,
            Map<String, Object> configSchema,
            String contractFingerprint,
            String bindingState
    ) { }

    /** Named port with a content-addressed structural schema reference. */
    public record PortContract(String name, boolean required, String schemaRef) { }

    /** Callable name and structural signature without library-authored prose or examples. */
    public record FunctionContract(String name, String signature, String contractFingerprint) { }

    /** Server-owned example certified for the current language and visible contract set. */
    public record Example(
            String exampleId,
            String intent,
            String source,
            List<String> assertions,
            String exampleFingerprint
    ) { }

    /** Hard limits used to reject rather than silently truncate reference material. */
    public record Limits(
            int maxTopics,
            int maxOperatorRefs,
            int maxFunctions,
            int maxExamples,
            int maxResponseBytes
    ) { }
}
