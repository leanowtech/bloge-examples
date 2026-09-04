package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Deep application boundary for server-authoritative BLOGE DSL authoring support.
 *
 * <p>The facade first freezes the caller's exact language, catalog and scope context. Reference,
 * preview, gate and compose can therefore share one provenance model without exposing runtime URLs,
 * credentials, fixture values or library-authored prose to the coding Agent.</p>
 */
public final class AgentDslAuthoringSupport {
    static final int MAX_TOPICS = 20;
    static final int MAX_OPERATOR_REFS = 256;
    static final int MAX_FUNCTIONS = 256;
    static final int MAX_EXAMPLES = 16;
    static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final ThreadPoolExecutor PREVIEW_EXECUTOR = new ThreadPoolExecutor(
            4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16),
            Thread.ofVirtual().name("agent-dsl-preview-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    private final ObjectMapper mapper;
    private final DslReferenceBundleLoader.Bundle bundle;
    private final DslAuthoringContextResolver contexts;
    private final DslContractLens lens;
    private final CandidateCompiler compiler;
    private final Duration previewBudget;
    private final AgentTddAuthoringTelemetry telemetry;

    /**
     * Creates the support boundary over the authoritative scoped catalog and library registry.
     *
     * @param catalog visual operator authority
     * @param libraries authored library authority
     * @param mapper canonical protocol mapper
     */
    public AgentDslAuthoringSupport(VisualOperatorCatalog catalog,
                                    OperatorLibraryRegistry libraries,
                                    ObjectMapper mapper) {
        this(catalog, libraries, mapper, AgentTddAuthoringTelemetry.noop());
    }

    /**
     * Creates the support boundary with deployment telemetry.
     *
     * @param catalog visual operator authority
     * @param libraries authored library authority
     * @param mapper canonical protocol mapper
     * @param telemetry payload-free authoring telemetry
     */
    public AgentDslAuthoringSupport(VisualOperatorCatalog catalog,
                                    OperatorLibraryRegistry libraries,
                                    ObjectMapper mapper,
                                    AgentTddAuthoringTelemetry telemetry) {
        this(catalog, libraries, mapper, new DslAuthoringCompiler(mapper)::preview,
                DslAuthoringCompiler.PREVIEW_BUDGET, telemetry);
    }

    AgentDslAuthoringSupport(VisualOperatorCatalog catalog,
                             OperatorLibraryRegistry libraries,
                             ObjectMapper mapper,
                             CandidateCompiler compiler,
                             Duration previewBudget) {
        this(catalog, libraries, mapper, compiler, previewBudget, AgentTddAuthoringTelemetry.noop());
    }

    AgentDslAuthoringSupport(VisualOperatorCatalog catalog,
                             OperatorLibraryRegistry libraries,
                             ObjectMapper mapper,
                             CandidateCompiler compiler,
                             Duration previewBudget,
                             AgentTddAuthoringTelemetry telemetry) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.bundle = new DslReferenceBundleLoader(mapper).bundle();
        this.contexts = new DslAuthoringContextResolver(catalog, libraries, mapper, bundle);
        this.lens = new DslContractLens(mapper);
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.previewBudget = Objects.requireNonNull(previewBudget, "previewBudget");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        if (previewBudget.isZero() || previewBudget.isNegative()) {
            throw new IllegalArgumentException("previewBudget must be positive");
        }
    }

    /**
     * Returns a bounded, graph-only and payload-free reference for the authenticated authoring scope.
     *
     * @param request exact library and reference selectors
     * @param identity authenticated tenant, project and environment
     * @return server-owned syntax, contract lenses and context fingerprint
     */
    public DslReferenceSnapshot reference(DslReferenceRequest request, IntegrationRequestContext identity) {
        try {
            DslReferenceSnapshot snapshot = buildReference(request, identity);
            telemetry.referenceSucceeded(mapper.writeValueAsBytes(snapshot).length);
            return snapshot;
        } catch (AgentTddToolException failure) {
            telemetry.referenceFailed(true);
            throw failure;
        } catch (JsonProcessingException failure) {
            telemetry.referenceFailed(false);
            throw new IllegalStateException("DSL reference response cannot be encoded", failure);
        } catch (RuntimeException failure) {
            telemetry.referenceFailed(false);
            throw failure;
        }
    }

    private DslReferenceSnapshot buildReference(
            DslReferenceRequest request,
            IntegrationRequestContext identity) {
        Objects.requireNonNull(request, "request");
        List<String> topics = normalize(request.topics(), MAX_TOPICS);
        List<String> operatorRefs = normalize(request.operatorRefs(), MAX_OPERATOR_REFS);
        DslAuthoringContext context = contexts.resolve(request.libraryRefs(), identity);

        Set<String> selectedTopicIds = new LinkedHashSet<>(topics.isEmpty() ? bundle.defaultTopics() : topics);
        Map<String, DslReferenceSnapshot.Topic> topicsById = bundle.topics().stream().collect(
                java.util.stream.Collectors.toMap(DslReferenceSnapshot.Topic::topicId, value -> value));
        if (!topicsById.keySet().containsAll(selectedTopicIds)) {
            throw new AgentTddToolException("DSL_REFERENCE_TOPIC_UNKNOWN",
                    "A requested DSL reference topic is not supported.",
                    Map.of("nextAction", "NARROW_REFERENCE_REQUEST"));
        }
        List<DslReferenceSnapshot.Topic> selectedTopics = selectedTopicIds.stream().sorted()
                .map(topicsById::get).toList();

        Set<String> selectedOperatorRefs = operatorRefs.isEmpty()
                ? context.operators().keySet() : Set.copyOf(operatorRefs);
        List<DslReferenceSnapshot.OperatorContract> operators = context.operators().values().stream()
                .filter(operator -> selectedOperatorRefs.contains(operator.operatorRef()))
                .sorted(Comparator.comparing(OperatorDefinition::operatorRef))
                .map(lens::operator).toList();
        List<DslReferenceSnapshot.FunctionContract> functions = context.functions().values().stream()
                .map(lens::function).sorted(Comparator.comparing(DslReferenceSnapshot.FunctionContract::name))
                .toList();
        if (functions.size() > MAX_FUNCTIONS) throw tooLarge();

        Set<String> exampleRefs = selectedTopics.stream().flatMap(topic -> topic.exampleRefs().stream())
                .collect(java.util.stream.Collectors.toSet());
        List<DslReferenceSnapshot.Example> examples = request.includeExamples()
                ? bundle.examples().stream()
                .filter(example -> exampleRefs.contains(example.exampleId()))
                .filter(example -> context.operators().keySet().containsAll(example.requiredOperatorRefs()))
                .sorted(Comparator.comparing(DslReferenceBundleLoader.BundleExample::exampleId))
                .limit(MAX_EXAMPLES + 1L)
                .map(example -> new DslReferenceSnapshot.Example(
                        example.exampleId(), example.intent(), example.source(), example.assertions(),
                        example.fingerprint()))
                .toList()
                : List.of();
        if (examples.size() > MAX_EXAMPLES) throw tooLarge();

        DslReferenceSnapshot snapshot = new DslReferenceSnapshot(
                "rg.dslReference.v1", context.languageVersion(), context.compilerProfile(),
                context.supportedRootKinds().stream().sorted().toList(), context.referenceVersion(),
                context.fingerprint(), selectedTopics, operators, functions, examples,
                new DslReferenceSnapshot.Limits(MAX_TOPICS, MAX_OPERATOR_REFS, MAX_FUNCTIONS,
                        MAX_EXAMPLES, MAX_RESPONSE_BYTES));
        try {
            if (mapper.writeValueAsBytes(snapshot).length > MAX_RESPONSE_BYTES) throw tooLarge();
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("DSL reference response cannot be encoded", failure);
        }
        return snapshot;
    }

    /**
     * Resolves the same immutable context used by the reference response for downstream compilation.
     *
     * @param libraryRefs explicit authoring libraries
     * @param identity authenticated scope
     * @return frozen authoring context
     */
    public DslAuthoringContext resolveContext(List<String> libraryRefs, IntegrationRequestContext identity) {
        return contexts.resolve(libraryRefs, identity);
    }

    /**
     * Compiles one candidate against the exact context previously returned by
     * {@link #reference(DslReferenceRequest, IntegrationRequestContext)}.
     *
     * @param request candidate source and authoring-context fingerprint
     * @param identity authenticated tenant, project and environment
     * @return bounded payload-free authoring receipt
     */
    public DslPreviewReceipt preview(DslPreviewRequest request, IntegrationRequestContext identity) {
        long started = System.nanoTime();
        try {
            DslPreviewReceipt receipt = compilePreview(request, identity);
            telemetry.previewCompleted(receipt, System.nanoTime() - started);
            return receipt;
        } catch (AgentTddToolException failure) {
            telemetry.previewRejected(failure.code(), System.nanoTime() - started);
            throw failure;
        }
    }

    private DslPreviewReceipt compilePreview(
            DslPreviewRequest request,
            IntegrationRequestContext identity) {
        Objects.requireNonNull(request, "request");
        if (request.authoringContextFingerprint().isBlank()) {
            throw new AgentTddToolException("DSL_AUTHORING_CONTEXT_REQUIRED",
                    "Fetch the current DSL reference before previewing source.",
                    Map.of("nextAction", "REFETCH_DSL_REFERENCE"), true);
        }
        Future<DslPreviewReceipt> task;
        try {
            task = PREVIEW_EXECUTOR.submit(() -> {
                DslAuthoringContext context = contexts.resolve(request.libraryRefs(), identity);
                if (!context.fingerprint().equals(request.authoringContextFingerprint())) {
                    throw new AgentTddToolException("DSL_AUTHORING_CONTEXT_STALE",
                            "The DSL authoring context changed after the reference was fetched.",
                            Map.of("nextAction", "REFETCH_DSL_REFERENCE"), true);
                }
                return compiler.preview(request, context);
            });
        } catch (RejectedExecutionException saturated) {
            throw new AgentTddToolException("DSL_PREVIEW_CAPACITY_EXCEEDED",
                    "The DSL preview capacity is temporarily exhausted.",
                    Map.of("nextAction", "RETRY_WITH_BACKOFF"), true);
        }
        try {
            return task.get(previewBudget.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            task.cancel(true);
            PREVIEW_EXECUTOR.purge();
            throw new AgentTddToolException("DSL_PREVIEW_TIMEOUT",
                    "The DSL preview exceeded its bounded execution time.",
                    Map.of("nextAction", "NARROW_DSL_SOURCE"), true);
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            PREVIEW_EXECUTOR.purge();
            Thread.currentThread().interrupt();
            throw new AgentTddToolException("DSL_PREVIEW_INTERRUPTED",
                    "The DSL preview was interrupted before completion.",
                    Map.of("nextAction", "RETRY_WITH_BACKOFF"), true);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof AgentTddToolException expected) throw expected;
            throw new IllegalStateException("DSL preview failed inside the authoring boundary", failed.getCause());
        }
    }

    private static List<String> normalize(List<String> values, int maximum) {
        List<String> normalized = values == null ? List.of() : values.stream()
                .filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank())
                .distinct().sorted().toList();
        if (values != null && (values.size() > maximum || normalized.size() != values.size())) {
            throw tooLarge();
        }
        return normalized;
    }

    private static AgentTddToolException tooLarge() {
        return new AgentTddToolException("DSL_REFERENCE_TOO_LARGE",
                "The requested DSL reference exceeds its safe size limit.",
                Map.of("nextAction", "NARROW_REFERENCE_REQUEST"));
    }

    /** Internal compiler seam used only to prove timeout behavior without replacing production stages. */
    @FunctionalInterface
    interface CandidateCompiler {
        DslPreviewReceipt preview(DslPreviewRequest request, DslAuthoringContext context);
    }
}
