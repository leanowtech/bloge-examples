package com.leanowtech.bloge.gateway.visual.asset;

import com.leanowtech.bloge.gateway.visual.runtime.VisualExecutableLoweringIntegration;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeAdapterActivation;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeRolloutObservation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Queryable runtime evidence chain window.
 *
 * <p>Runtime evidence facts can grow independently from graph artifacts. This read
 * model gives browser and external control planes one bounded, filter-echoing
 * window instead of forcing them to fetch all adapter activations, rollout
 * observations, executable lowering integrations, and the implementation
 * binding records that anchor those downstream facts.</p>
 *
 * @param schemaVersion runtime evidence window contract version
 * @param generatedAt server timestamp when this window was derived
 * @param total evidence records after query filtering and before display limiting
 * @param unfilteredTotal evidence records before evidence filters
 * @param displayedCount returned mixed item count
 * @param itemLimit normalized maximum number of mixed item details returned
 * @param offset zero-based offset after query filtering
 * @param hasMore true when more filtered evidence records exist after the returned window
 * @param filter normalized evidence filter
 * @param kindCounts filtered counts by runtime evidence kind
 * @param operatorRefCounts filtered counts by operator reference
 * @param operatorLibraryIdCounts filtered counts by imported operator-library owner id
 * @param bindingIdCounts filtered counts by runtime binding id
 * @param activationIdCounts filtered counts by adapter activation id
 * @param stateCounts filtered counts by evidence state
 * @param levelCounts filtered counts by evidence level
 * @param rolloutSignalCounts filtered counts by rollout signal name
 * @param breachedRolloutSignalCounts filtered counts by breached rollout signal name
 * @param items mixed evidence item window in server sort order
 * @param implementationBindings implementation binding records present in the returned window
 * @param adapterActivations adapter activation records present in the returned window
 * @param rolloutObservations rollout observation records present in the returned window
 * @param executableLoweringIntegrations lowering integration records present in the returned window
 */
public record VisualRuntimeEvidenceWindow(
        String schemaVersion,
        Instant generatedAt,
        int total,
        int unfilteredTotal,
        int displayedCount,
        int itemLimit,
        int offset,
        boolean hasMore,
        Filter filter,
        Map<String, Integer> kindCounts,
        Map<String, Integer> operatorRefCounts,
        Map<String, Integer> operatorLibraryIdCounts,
        Map<String, Integer> bindingIdCounts,
        Map<String, Integer> activationIdCounts,
        Map<String, Integer> stateCounts,
        Map<String, Integer> levelCounts,
        Map<String, Integer> rolloutSignalCounts,
        Map<String, Integer> breachedRolloutSignalCounts,
        List<Item> items,
        List<VisualRuntimeBindingImplementationBinding> implementationBindings,
        List<VisualRuntimeAdapterActivation> adapterActivations,
        List<VisualRuntimeRolloutObservation> rolloutObservations,
        List<VisualExecutableLoweringIntegration> executableLoweringIntegrations
) {
    public static final String SCHEMA_VERSION = "bloge.visualRuntimeEvidenceWindow.v1";
    public static final int DEFAULT_ITEM_LIMIT = 50;
    public static final int MAX_ITEM_LIMIT = 200;

    public VisualRuntimeEvidenceWindow {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
        total = Math.max(0, total);
        unfilteredTotal = Math.max(total, unfilteredTotal);
        itemLimit = normalizeLimit(itemLimit);
        offset = Math.max(0, offset);
        filter = filter == null ? Filter.all() : filter;
        kindCounts = immutableCounts(kindCounts);
        operatorRefCounts = immutableCounts(operatorRefCounts);
        operatorLibraryIdCounts = immutableCounts(operatorLibraryIdCounts);
        bindingIdCounts = immutableCounts(bindingIdCounts);
        activationIdCounts = immutableCounts(activationIdCounts);
        stateCounts = immutableCounts(stateCounts);
        levelCounts = immutableCounts(levelCounts);
        rolloutSignalCounts = immutableCounts(rolloutSignalCounts);
        breachedRolloutSignalCounts = immutableCounts(breachedRolloutSignalCounts);
        items = items == null ? List.of() : List.copyOf(items);
        implementationBindings = implementationBindings == null ? List.of() : List.copyOf(implementationBindings);
        adapterActivations = adapterActivations == null ? List.of() : List.copyOf(adapterActivations);
        rolloutObservations = rolloutObservations == null ? List.of() : List.copyOf(rolloutObservations);
        executableLoweringIntegrations = executableLoweringIntegrations == null
                ? List.of()
                : List.copyOf(executableLoweringIntegrations);
        displayedCount = items.size();
        hasMore = offset + displayedCount < total;
    }

    /**
     * Builds a mixed evidence chain window from submitted runtime evidence facts.
     */
    public static VisualRuntimeEvidenceWindow from(List<VisualRuntimeBindingImplementationBinding> implementationBindings,
                                                   List<VisualRuntimeAdapterActivation> adapterActivations,
                                                   List<VisualRuntimeRolloutObservation> rolloutObservations,
                                                   List<VisualExecutableLoweringIntegration> executableLoweringIntegrations,
                                                   int itemLimit,
                                                   int offset,
                                                   String operatorRef,
                                                   String bindingId,
                                                   String activationId,
                                                   String lifecycleState,
                                                   String rolloutState,
                                                   String rolloutSignal,
                                                   boolean breachedOnly) {
        return from(
                implementationBindings,
                adapterActivations,
                rolloutObservations,
                executableLoweringIntegrations,
                Map.of(),
                itemLimit,
                offset,
                "",
                operatorRef,
                "",
                bindingId,
                activationId,
                lifecycleState,
                rolloutState,
                rolloutSignal,
                breachedOnly);
    }

    /**
     * Builds a mixed evidence chain window from submitted runtime evidence facts.
     */
    public static VisualRuntimeEvidenceWindow from(List<VisualRuntimeBindingImplementationBinding> implementationBindings,
                                                   List<VisualRuntimeAdapterActivation> adapterActivations,
                                                   List<VisualRuntimeRolloutObservation> rolloutObservations,
                                                   List<VisualExecutableLoweringIntegration> executableLoweringIntegrations,
                                                   int itemLimit,
                                                   int offset,
                                                   String evidenceKind,
                                                   String operatorRef,
                                                   String bindingId,
                                                   String activationId,
                                                   String lifecycleState,
                                                   String rolloutState,
                                                   String rolloutSignal,
                                                   boolean breachedOnly) {
        return from(
                implementationBindings,
                adapterActivations,
                rolloutObservations,
                executableLoweringIntegrations,
                Map.of(),
                itemLimit,
                offset,
                evidenceKind,
                operatorRef,
                "",
                bindingId,
                activationId,
                lifecycleState,
                rolloutState,
                rolloutSignal,
                breachedOnly);
    }

    /**
     * Builds a mixed evidence chain window with catalog operator-library ownership context.
     */
    public static VisualRuntimeEvidenceWindow from(List<VisualRuntimeBindingImplementationBinding> implementationBindings,
                                                   List<VisualRuntimeAdapterActivation> adapterActivations,
                                                   List<VisualRuntimeRolloutObservation> rolloutObservations,
                                                   List<VisualExecutableLoweringIntegration> executableLoweringIntegrations,
                                                   Map<String, String> operatorLibraryIdsByOperatorRef,
                                                   int itemLimit,
                                                   int offset,
                                                   String evidenceKind,
                                                   String operatorRef,
                                                   String operatorLibraryId,
                                                   String bindingId,
                                                   String activationId,
                                                   String lifecycleState,
                                                   String rolloutState,
                                                   String rolloutSignal,
                                                   boolean breachedOnly) {
        List<Entry> generated = generate(
                implementationBindings,
                adapterActivations,
                rolloutObservations,
                executableLoweringIntegrations,
                operatorLibraryIdsByOperatorRef);
        Filter filter = new Filter(evidenceKind, operatorRef, operatorLibraryId, bindingId, activationId,
                lifecycleState, rolloutState, rolloutSignal, breachedOnly);
        List<Entry> filtered = filter.usesRolloutSignalFilter()
                ? filterByRolloutSignalChain(generated, filter)
                : generated.stream().filter(filter::matches).toList();
        filtered = filtered.stream()
                .sorted(VisualRuntimeEvidenceWindow::compareEntries)
                .toList();
        int normalizedLimit = normalizeLimit(itemLimit);
        int normalizedOffset = Math.max(0, offset);
        List<Entry> window = filtered.stream()
                .skip(normalizedOffset)
                .limit(normalizedLimit)
                .toList();
        return new VisualRuntimeEvidenceWindow(
                SCHEMA_VERSION,
                Instant.now(),
                filtered.size(),
                generated.size(),
                Math.min(Math.max(0, filtered.size() - normalizedOffset), normalizedLimit),
                normalizedLimit,
                normalizedOffset,
                false,
                filter,
                countBy(filtered, Entry::kind),
                countBy(filtered, Entry::operatorRef),
                countBy(filtered, Entry::operatorLibraryId),
                countBy(filtered, Entry::bindingId),
                countBy(filtered, Entry::activationId),
                countBy(filtered, Entry::state),
                countBy(filtered, Entry::level),
                rolloutSignalCounts(filtered, false),
                rolloutSignalCounts(filtered, true),
                window.stream().map(Entry::item).toList(),
                window.stream()
                        .map(Entry::implementationBinding)
                        .filter(record -> record != null)
                        .toList(),
                window.stream()
                        .map(Entry::adapterActivation)
                        .filter(record -> record != null)
                        .toList(),
                window.stream()
                        .map(Entry::rolloutObservation)
                        .filter(record -> record != null)
                        .toList(),
                window.stream()
                        .map(Entry::executableLoweringIntegration)
                        .filter(record -> record != null)
                        .toList()
        );
    }

    private static List<Entry> generate(List<VisualRuntimeBindingImplementationBinding> implementationBindings,
                                        List<VisualRuntimeAdapterActivation> adapterActivations,
                                        List<VisualRuntimeRolloutObservation> rolloutObservations,
                                        List<VisualExecutableLoweringIntegration> executableLoweringIntegrations,
                                        Map<String, String> operatorLibraryIdsByOperatorRef) {
        ArrayList<Entry> entries = new ArrayList<>();
        for (VisualRuntimeBindingImplementationBinding binding : safeList(implementationBindings)) {
            if (binding != null) {
                entries.add(Entry.from(binding, operatorLibraryId(binding.operatorRef(),
                        operatorLibraryIdsByOperatorRef)));
            }
        }
        for (VisualRuntimeAdapterActivation activation : safeList(adapterActivations)) {
            if (activation != null) {
                entries.add(Entry.from(activation, operatorLibraryId(activation.operatorRef(),
                        operatorLibraryIdsByOperatorRef)));
            }
        }
        for (VisualRuntimeRolloutObservation observation : safeList(rolloutObservations)) {
            if (observation != null) {
                entries.add(Entry.from(observation, operatorLibraryId(observation.operatorRef(),
                        operatorLibraryIdsByOperatorRef)));
            }
        }
        for (VisualExecutableLoweringIntegration integration : safeList(executableLoweringIntegrations)) {
            if (integration != null) {
                entries.add(Entry.from(integration, operatorLibraryId(integration.operatorRef(),
                        operatorLibraryIdsByOperatorRef)));
            }
        }
        return entries;
    }

    private static List<Entry> filterByRolloutSignalChain(List<Entry> generated, Filter filter) {
        List<Entry> rolloutMatches = generated.stream()
                .filter(entry -> entry.rolloutObservation() != null)
                .filter(filter::matchesRolloutSignalChainRoot)
                .toList();
        Set<String> activationIds = new LinkedHashSet<>();
        Set<String> bindingIds = new LinkedHashSet<>();
        Set<String> operatorRefs = new LinkedHashSet<>();
        for (Entry entry : rolloutMatches) {
            if (!entry.activationId().isBlank()) {
                activationIds.add(entry.activationId());
            }
            if (!entry.bindingId().isBlank()) {
                bindingIds.add(entry.bindingId());
            }
            if (!entry.operatorRef().isBlank()) {
                operatorRefs.add(entry.operatorRef());
            }
        }
        return generated.stream()
                .filter(entry -> {
                    if (entry.rolloutObservation() != null) {
                        return rolloutMatches.contains(entry) && filter.matchesEvidenceKind(entry);
                    }
                    return filter.matchesIdentityAndKindState(entry)
                            && chainMatches(entry, activationIds, bindingIds, operatorRefs);
                })
                .toList();
    }

    private static boolean chainMatches(Entry entry,
                                        Set<String> activationIds,
                                        Set<String> bindingIds,
                                        Set<String> operatorRefs) {
        return (!entry.activationId().isBlank() && activationIds.contains(entry.activationId()))
                || (!entry.bindingId().isBlank() && bindingIds.contains(entry.bindingId()))
                || (!entry.operatorRef().isBlank() && operatorRefs.contains(entry.operatorRef()));
    }

    private static int compareEntries(Entry left, Entry right) {
        int byUpdatedAt = right.updatedAt().compareTo(left.updatedAt());
        if (byUpdatedAt != 0) {
            return byUpdatedAt;
        }
        return safe(left.operatorRef()).compareTo(safe(right.operatorRef()))
                != 0 ? safe(left.operatorRef()).compareTo(safe(right.operatorRef()))
                : compareEntryIdentity(left, right);
    }

    private static int compareEntryIdentity(Entry left, Entry right) {
        int byBinding = safe(left.bindingId()).compareTo(safe(right.bindingId()));
        if (byBinding != 0) {
            return byBinding;
        }
        int byActivation = safe(left.activationId()).compareTo(safe(right.activationId()));
        if (byActivation != 0) {
            return byActivation;
        }
        int byKind = kindOrder(left.kind()) - kindOrder(right.kind());
        if (byKind != 0) {
            return byKind;
        }
        return safe(left.evidenceId()).compareTo(safe(right.evidenceId()));
    }

    private static int kindOrder(String kind) {
        return switch (normalize(kind)) {
            case "implementation-binding" -> 0;
            case "adapter-activation" -> 1;
            case "rollout-observation" -> 2;
            case "executable-lowering-integration" -> 3;
            default -> 9;
        };
    }

    private static String operatorLibraryId(String operatorRef, Map<String, String> operatorLibraryIdsByOperatorRef) {
        if (operatorRef == null || operatorLibraryIdsByOperatorRef == null) {
            return "";
        }
        return safe(operatorLibraryIdsByOperatorRef.get(operatorRef));
    }

    private static Map<String, Integer> rolloutSignalCounts(List<Entry> entries, boolean breachedOnly) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (Entry entry : entries == null ? List.<Entry>of() : entries) {
            VisualRuntimeRolloutObservation observation = entry.rolloutObservation();
            if (observation == null) {
                continue;
            }
            for (VisualRuntimeRolloutObservation.RolloutSignal signal : observation.rolloutSignals()) {
                if (signal == null || (breachedOnly && !signal.breached())) {
                    continue;
                }
                increment(counts, normalizeSignal(signal.name()));
            }
        }
        return counts;
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static <T> Map<String, Integer> countBy(List<T> items, Function<T, String> classifier) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (T item : items == null ? List.<T>of() : items) {
            if (item != null) {
                increment(counts, classifier.apply(item));
            }
        }
        return counts;
    }

    private static void increment(Map<String, Integer> counts, String value) {
        String key = safe(value);
        if (!key.isBlank()) {
            counts.merge(key, 1, Integer::sum);
        }
    }

    private static Map<String, Integer> immutableCounts(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Integer> copy = new LinkedHashMap<>();
        counts.forEach((key, value) -> {
            String normalizedKey = safe(key);
            if (!normalizedKey.isBlank()) {
                copy.put(normalizedKey, Math.max(0, value == null ? 0 : value));
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    private static int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_ITEM_LIMIT;
        }
        return Math.min(limit, MAX_ITEM_LIMIT);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeSignal(String value) {
        return normalize(value).replace('_', '-');
    }

    private static String normalizeEvidenceKind(String value) {
        String normalized = normalize(value).replace('_', '-');
        return switch (normalized) {
            case "all", "runtime-evidence" -> "";
            case "binding", "implementation" -> "implementation-binding";
            case "activation", "adapter" -> "adapter-activation";
            case "rollout" -> "rollout-observation";
            case "integration", "lowering" -> "executable-lowering-integration";
            default -> normalized;
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Normalized runtime evidence filter.
     */
    public record Filter(
            String evidenceKind,
            String operatorRef,
            String operatorLibraryId,
            String bindingId,
            String activationId,
            String lifecycleState,
            String rolloutState,
            String rolloutSignal,
            boolean breachedOnly,
            boolean filtered
    ) {
        public Filter(String evidenceKind,
                      String operatorRef,
                      String operatorLibraryId,
                      String bindingId,
                      String activationId,
                      String lifecycleState,
                      String rolloutState,
                      String rolloutSignal,
                      boolean breachedOnly) {
            this(
                    normalizeEvidenceKind(evidenceKind),
                    safe(operatorRef),
                    safe(operatorLibraryId),
                    safe(bindingId),
                    safe(activationId),
                    normalize(lifecycleState),
                    normalize(rolloutState),
                    normalizeSignal(rolloutSignal),
                    breachedOnly,
                    !normalizeEvidenceKind(evidenceKind).isBlank()
                            || !safe(operatorRef).isBlank()
                            || !safe(operatorLibraryId).isBlank()
                            || !safe(bindingId).isBlank()
                            || !safe(activationId).isBlank()
                            || !normalize(lifecycleState).isBlank()
                            || !normalize(rolloutState).isBlank()
                            || !normalizeSignal(rolloutSignal).isBlank()
                            || breachedOnly
            );
        }

        public Filter {
            evidenceKind = normalizeEvidenceKind(evidenceKind);
            operatorRef = safe(operatorRef);
            operatorLibraryId = safe(operatorLibraryId);
            bindingId = safe(bindingId);
            activationId = safe(activationId);
            lifecycleState = normalize(lifecycleState);
            rolloutState = normalize(rolloutState);
            rolloutSignal = normalizeSignal(rolloutSignal);
            filtered = !evidenceKind.isBlank()
                    || !operatorRef.isBlank()
                    || !operatorLibraryId.isBlank()
                    || !bindingId.isBlank()
                    || !activationId.isBlank()
                    || !lifecycleState.isBlank()
                    || !rolloutState.isBlank()
                    || !rolloutSignal.isBlank()
                    || breachedOnly;
        }

        static Filter all() {
            return new Filter("", "", "", "", "", "", "", "", false);
        }

        boolean usesRolloutSignalFilter() {
            return !rolloutSignal.isBlank() || breachedOnly;
        }

        boolean matches(Entry entry) {
            if (!matchesIdentityAndKindState(entry)) {
                return false;
            }
            if (entry.rolloutObservation() == null || !usesRolloutSignalFilter()) {
                return true;
            }
            return matchesRolloutSignal(entry.rolloutObservation());
        }

        boolean matchesIdentityAndKindState(Entry entry) {
            if (entry == null) {
                return false;
            }
            if (!matchesEvidenceKind(entry)) {
                return false;
            }
            if (!operatorRef.isBlank() && !entry.operatorRef().equals(operatorRef)) {
                return false;
            }
            if (!operatorLibraryId.isBlank() && !entry.operatorLibraryId().equals(operatorLibraryId)) {
                return false;
            }
            if (!bindingId.isBlank() && !entry.bindingId().equals(bindingId)) {
                return false;
            }
            if (!activationId.isBlank() && !entry.activationId().equals(activationId)) {
                return false;
            }
            if (entry.rolloutObservation() == null) {
                return lifecycleState.isBlank() || entry.state().equals(lifecycleState);
            }
            return rolloutState.isBlank() || entry.state().equals(rolloutState);
        }

        boolean matchesRolloutSignalChainRoot(Entry entry) {
            if (entry == null || entry.rolloutObservation() == null) {
                return false;
            }
            if (!operatorRef.isBlank() && !entry.operatorRef().equals(operatorRef)) {
                return false;
            }
            if (!operatorLibraryId.isBlank() && !entry.operatorLibraryId().equals(operatorLibraryId)) {
                return false;
            }
            if (!bindingId.isBlank() && !entry.bindingId().equals(bindingId)) {
                return false;
            }
            if (!activationId.isBlank() && !entry.activationId().equals(activationId)) {
                return false;
            }
            if (!rolloutState.isBlank() && !entry.state().equals(rolloutState)) {
                return false;
            }
            return matchesRolloutSignal(entry.rolloutObservation());
        }

        boolean matchesEvidenceKind(Entry entry) {
            return entry != null && (evidenceKind.isBlank() || evidenceKind.equals(entry.kind()));
        }

        private boolean matchesRolloutSignal(VisualRuntimeRolloutObservation observation) {
            return observation.rolloutSignals().stream()
                    .filter(signal -> signal != null && !normalizeSignal(signal.name()).isBlank())
                    .anyMatch(signal -> {
                        boolean signalMatches = rolloutSignal.isBlank()
                                || normalizeSignal(signal.name()).equals(rolloutSignal);
                        boolean breachMatches = !breachedOnly || signal.breached();
                        return signalMatches && breachMatches;
                    });
        }
    }

    /**
     * Mixed evidence item in the returned window.
     */
    public record Item(
            String kind,
            String evidenceId,
            String state,
            String level,
            String operatorRef,
            String operatorLibraryId,
            String bindingId,
            String activationId,
            Instant updatedAt,
            boolean breachedRollout
    ) {
        public Item {
            kind = normalize(kind);
            evidenceId = safe(evidenceId);
            state = normalize(state);
            level = normalize(level);
            operatorRef = safe(operatorRef);
            operatorLibraryId = safe(operatorLibraryId);
            bindingId = safe(bindingId);
            activationId = safe(activationId);
            updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
        }
    }

    private record Entry(
            String kind,
            String evidenceId,
            String state,
            String level,
            String operatorRef,
            String operatorLibraryId,
            String bindingId,
            String activationId,
            Instant updatedAt,
            Item item,
            VisualRuntimeBindingImplementationBinding implementationBinding,
            VisualRuntimeAdapterActivation adapterActivation,
            VisualRuntimeRolloutObservation rolloutObservation,
            VisualExecutableLoweringIntegration executableLoweringIntegration
    ) {
        static Entry from(VisualRuntimeBindingImplementationBinding binding, String operatorLibraryId) {
            String kind = "implementation-binding";
            Item item = new Item(kind, binding.bindingId(), binding.state(), binding.level(),
                    binding.operatorRef(), operatorLibraryId, binding.bindingId(), "",
                    binding.updatedAt(), false);
            return new Entry(kind, binding.bindingId(), item.state(), item.level(), item.operatorRef(),
                    item.operatorLibraryId(),
                    item.bindingId(), item.activationId(), item.updatedAt(), item, binding, null, null, null);
        }

        static Entry from(VisualRuntimeAdapterActivation activation, String operatorLibraryId) {
            String kind = "adapter-activation";
            Item item = new Item(kind, activation.activationId(), activation.state(), activation.level(),
                    activation.operatorRef(), operatorLibraryId, activation.bindingId(), activation.activationId(),
                    activation.updatedAt(), false);
            return new Entry(kind, activation.activationId(), item.state(), item.level(), item.operatorRef(),
                    item.operatorLibraryId(),
                    item.bindingId(), item.activationId(), item.updatedAt(), item, null, activation, null, null);
        }

        static Entry from(VisualRuntimeRolloutObservation observation, String operatorLibraryId) {
            String kind = "rollout-observation";
            boolean breached = observation.rolloutSignals().stream()
                    .anyMatch(signal -> signal != null && signal.breached());
            Item item = new Item(kind, observation.observationId(), observation.state(), observation.level(),
                    observation.operatorRef(), operatorLibraryId, observation.bindingId(), observation.activationId(),
                    observation.updatedAt(), breached);
            return new Entry(kind, observation.observationId(), item.state(), item.level(), item.operatorRef(),
                    item.operatorLibraryId(),
                    item.bindingId(), item.activationId(), item.updatedAt(), item, null, null, observation, null);
        }

        static Entry from(VisualExecutableLoweringIntegration integration, String operatorLibraryId) {
            String kind = "executable-lowering-integration";
            Item item = new Item(kind, integration.integrationId(), integration.state(), integration.level(),
                    integration.operatorRef(), operatorLibraryId, integration.bindingId(), integration.activationId(),
                    integration.updatedAt(), false);
            return new Entry(kind, integration.integrationId(), item.state(), item.level(), item.operatorRef(),
                    item.operatorLibraryId(),
                    item.bindingId(), item.activationId(), item.updatedAt(), item, null, null, null, integration);
        }
    }
}
