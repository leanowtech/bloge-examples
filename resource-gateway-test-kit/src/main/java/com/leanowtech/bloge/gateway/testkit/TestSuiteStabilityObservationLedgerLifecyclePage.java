package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Strict payload-free projection of one signed observation-ledger floor lifecycle page.
 *
 * <p>Construction validates the authoritative Schema and cross-object shape. It does not trust
 * canonical fingerprints, retirement signatures, page signatures, or producer continuity facts;
 * use {@link TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier} before advancing a
 * lifecycle checkpoint.</p>
 *
 * @param schemaVersion exact response generation
 * @param lifecyclePageId deterministic page identity
 * @param pageFingerprint canonical complete page identity
 * @param request exact request embedded in the page
 * @param scopeFingerprint payload-free producer scope identity
 * @param startingFloor floor represented by the request cursor
 * @param retirements ordered bounded retirement records
 * @param terminalFloor floor after this page's transitions
 * @param currentFloor snapshot current floor
 * @param head snapshot current ledger head
 * @param hasMore whether another pinned page is required
 * @param observedAt producer database snapshot time
 * @param attestation detached page signature
 * @param rawResponse defensive schema-validated response
 */
public record TestSuiteStabilityObservationLedgerLifecyclePage(
        String schemaVersion,
        String lifecyclePageId,
        String pageFingerprint,
        TestSuiteStabilityObservationLedgerLifecycleRequest request,
        String scopeFingerprint,
        Floor startingFloor,
        List<Retirement> retirements,
        Floor terminalFloor,
        Floor currentFloor,
        Head head,
        boolean hasMore,
        Instant observedAt,
        Attestation attestation,
        JsonNode rawResponse
) implements TestSuiteStabilityObservationLedgerLifecyclePageView {
    /**
     * Immutable exact suite coordinate repeated throughout lifecycle material.
     *
     * @param suiteId stable suite identity
     * @param revision exact immutable suite revision
     * @param fingerprint exact suite content fingerprint
     */
    public record SuiteRef(String suiteId, long revision, String fingerprint) {
        /** Validates one complete immutable suite coordinate. */
        public SuiteRef {
            suiteId = normalized(suiteId);
            fingerprint = normalized(fingerprint);
            if (suiteId.isBlank() || suiteId.length() > 255 || revision < 1
                    || !validFingerprint(fingerprint)) {
                throw new IllegalArgumentException("Lifecycle suite reference is incomplete");
            }
        }

        boolean matches(TestSuiteStabilityObservationLedgerLifecycleRequest value) {
            return suiteId.equals(value.suiteId()) && revision == value.revision()
                    && fingerprint.equals(value.fingerprint());
        }
    }

    /**
     * Producer-authoritative first retained ledger coordinate and retirement-chain tip.
     *
     * @param schemaVersion exact floor generation
     * @param scopeFingerprint payload-free exact-suite scope identity
     * @param suiteRef exact immutable suite coordinate
     * @param floorSequence first active compact-observation sequence
     * @param previousObservationId observation immediately before the floor
     * @param previousEntryFingerprint entry immediately before the floor
     * @param floorObservationId first active compact-observation identity
     * @param floorEntryFingerprint first active entry fingerprint
     * @param coverageFrom first active entry append time
     * @param retirementGeneration latest applied retirement generation
     * @param latestRetirementId latest retirement identity; blank at rollout
     * @param latestRetirementFingerprint latest complete retirement fingerprint; blank at rollout
     * @param updatedAt producer database floor update time
     * @param floorFingerprint canonical complete floor fingerprint
     */
    public record Floor(
            String schemaVersion,
            String scopeFingerprint,
            SuiteRef suiteRef,
            long floorSequence,
            String previousObservationId,
            String previousEntryFingerprint,
            String floorObservationId,
            String floorEntryFingerprint,
            Instant coverageFrom,
            long retirementGeneration,
            String latestRetirementId,
            String latestRetirementFingerprint,
            Instant updatedAt,
            String floorFingerprint
    ) {
        /** Validates rollout or retired floor shape without trusting its fingerprint. */
        public Floor {
            schemaVersion = normalized(schemaVersion);
            scopeFingerprint = normalized(scopeFingerprint);
            previousObservationId = normalized(previousObservationId);
            previousEntryFingerprint = normalized(previousEntryFingerprint);
            floorObservationId = normalized(floorObservationId);
            floorEntryFingerprint = normalized(floorEntryFingerprint);
            latestRetirementId = normalized(latestRetirementId);
            latestRetirementFingerprint = normalized(latestRetirementFingerprint);
            floorFingerprint = normalized(floorFingerprint);
            boolean rollout = floorSequence == 1 && retirementGeneration == 0
                    && previousObservationId.isBlank() && previousEntryFingerprint.isBlank()
                    && latestRetirementId.isBlank() && latestRetirementFingerprint.isBlank();
            boolean retired = floorSequence > 1 && retirementGeneration > 0
                    && validObservationId(previousObservationId)
                    && validFingerprint(previousEntryFingerprint)
                    && validRetirementId(latestRetirementId)
                    && validFingerprint(latestRetirementFingerprint);
            if (!TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_FLOOR_V1
                    .equals(schemaVersion) || !validFingerprint(scopeFingerprint)
                    || suiteRef == null || (!rollout && !retired)
                    || !validObservationId(floorObservationId)
                    || !validFingerprint(floorEntryFingerprint)
                    || coverageFrom == null || Instant.EPOCH.equals(coverageFrom)
                    || updatedAt == null || updatedAt.isBefore(coverageFrom)
                    || !validFingerprint(floorFingerprint)) {
                throw new IllegalArgumentException("Observation lifecycle floor is incomplete");
            }
        }
    }

    /**
     * Producer-authoritative current committed compact-observation ledger head.
     *
     * @param schemaVersion exact head generation
     * @param scopeFingerprint payload-free exact-suite scope identity
     * @param suiteRef exact immutable suite coordinate
     * @param coverageFrom current active-floor coverage start
     * @param latestSequence latest committed active sequence
     * @param latestObservationId latest compact-observation identity
     * @param latestEntryFingerprint latest active entry fingerprint
     * @param updatedAt producer database head update time
     * @param headFingerprint canonical complete head fingerprint
     */
    public record Head(
            String schemaVersion,
            String scopeFingerprint,
            SuiteRef suiteRef,
            Instant coverageFrom,
            long latestSequence,
            String latestObservationId,
            String latestEntryFingerprint,
            Instant updatedAt,
            String headFingerprint
    ) {
        /** Validates one complete current-head shape without trusting its fingerprint. */
        public Head {
            schemaVersion = normalized(schemaVersion);
            scopeFingerprint = normalized(scopeFingerprint);
            latestObservationId = normalized(latestObservationId);
            latestEntryFingerprint = normalized(latestEntryFingerprint);
            headFingerprint = normalized(headFingerprint);
            if (!TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_HEAD_V1.equals(schemaVersion)
                    || !validFingerprint(scopeFingerprint) || suiteRef == null
                    || coverageFrom == null || Instant.EPOCH.equals(coverageFrom)
                    || latestSequence < 1 || !validObservationId(latestObservationId)
                    || !validFingerprint(latestEntryFingerprint) || updatedAt == null
                    || updatedAt.isBefore(coverageFrom)
                    || !validFingerprint(headFingerprint)) {
                throw new IllegalArgumentException("Observation lifecycle head is incomplete");
            }
        }
    }

    /**
     * Minimal producer entry coordinate needed to prove archive continuity.
     *
     * @param sequence contiguous ledger sequence
     * @param scopeFingerprint payload-free exact-suite scope identity
     * @param previousObservationId predecessor observation; blank only at sequence one
     * @param observationId signed compact-observation identity
     * @param entryFingerprint canonical complete entry fingerprint
     * @param appendedAt producer database append time
     */
    public record EntryRef(
            long sequence,
            String scopeFingerprint,
            String previousObservationId,
            String observationId,
            String entryFingerprint,
            Instant appendedAt
    ) {
        /** Validates one ledger entry coordinate. */
        public EntryRef {
            scopeFingerprint = normalized(scopeFingerprint);
            previousObservationId = normalized(previousObservationId);
            observationId = normalized(observationId);
            entryFingerprint = normalized(entryFingerprint);
            boolean predecessor = sequence == 1 ? previousObservationId.isBlank()
                    : validObservationId(previousObservationId);
            if (sequence < 1 || !validFingerprint(scopeFingerprint) || !predecessor
                    || !validObservationId(observationId) || !validFingerprint(entryFingerprint)
                    || appendedAt == null || Instant.EPOCH.equals(appendedAt)) {
                throw new IllegalArgumentException("Archive entry coordinate is incomplete");
            }
        }
    }

    /**
     * Bounded local archive segment carried by one signed retirement.
     *
     * @param schemaVersion exact archive generation
     * @param segmentId deterministic archive identity
     * @param scopeFingerprint payload-free exact-suite scope identity
     * @param suiteRef exact immutable suite coordinate
     * @param retirementGeneration owning retirement generation
     * @param previousObservationId observation before the retired prefix
     * @param previousEntryFingerprint entry before the retired prefix
     * @param retiredEntries complete bounded retired prefix
     * @param successorEntry immediate surviving entry duplicated for closure
     * @param archivedAt producer database archive time
     * @param segmentFingerprint canonical complete archive fingerprint
     */
    public record Archive(
            String schemaVersion,
            String segmentId,
            String scopeFingerprint,
            SuiteRef suiteRef,
            long retirementGeneration,
            String previousObservationId,
            String previousEntryFingerprint,
            List<EntryRef> retiredEntries,
            EntryRef successorEntry,
            Instant archivedAt,
            String segmentFingerprint
    ) {
        /** Validates one bounded contiguous archive coordinate chain. */
        public Archive {
            schemaVersion = normalized(schemaVersion);
            segmentId = normalized(segmentId);
            scopeFingerprint = normalized(scopeFingerprint);
            previousObservationId = normalized(previousObservationId);
            previousEntryFingerprint = normalized(previousEntryFingerprint);
            retiredEntries = retiredEntries == null ? List.of() : List.copyOf(retiredEntries);
            segmentFingerprint = normalized(segmentFingerprint);
            boolean rollout = retirementGeneration == 1
                    && previousObservationId.isBlank() && previousEntryFingerprint.isBlank();
            boolean continued = retirementGeneration > 1
                    && validObservationId(previousObservationId)
                    && validFingerprint(previousEntryFingerprint);
            if (!TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_ARCHIVE_V1
                    .equals(schemaVersion) || !archiveId(segmentId)
                    || !validFingerprint(scopeFingerprint) || suiteRef == null
                    || (!rollout && !continued) || retiredEntries.isEmpty()
                    || retiredEntries.size() > 100 || successorEntry == null
                    || archivedAt == null || Instant.EPOCH.equals(archivedAt)
                    || !validFingerprint(segmentFingerprint)
                    || !closed(retiredEntries, successorEntry, scopeFingerprint,
                    previousObservationId, archivedAt)) {
                throw new IllegalArgumentException("Lifecycle archive is inconsistent");
            }
        }
    }

    /**
     * Detached signature manifest over one exact floor-retirement evidence object.
     *
     * @param schemaVersion exact retirement-attestation generation
     * @param retirementId deterministic signed retirement identity
     * @param evidenceFingerprint canonical retirement evidence fingerprint
     * @param archiveSegmentFingerprint exact nested archive fingerprint
     * @param previousFloorFingerprint exact predecessor floor pin
     * @param pinnedHeadFingerprint exact head authorized by the retirement
     * @param signedAt signing time used for key-lifecycle policy
     * @param keyId verification-key identity
     * @param algorithm detached signature algorithm
     * @param signature Base64-encoded detached signature
     */
    public record RetirementAttestation(
            String schemaVersion,
            String retirementId,
            String evidenceFingerprint,
            String archiveSegmentFingerprint,
            String previousFloorFingerprint,
            String pinnedHeadFingerprint,
            Instant signedAt,
            String keyId,
            String algorithm,
            String signature
    ) {
        /** Rejects incomplete or non-verifiable retirement signatures. */
        public RetirementAttestation {
            schemaVersion = normalized(schemaVersion);
            retirementId = normalized(retirementId);
            evidenceFingerprint = normalized(evidenceFingerprint);
            archiveSegmentFingerprint = normalized(archiveSegmentFingerprint);
            previousFloorFingerprint = normalized(previousFloorFingerprint);
            pinnedHeadFingerprint = normalized(pinnedHeadFingerprint);
            keyId = normalized(keyId);
            algorithm = normalized(algorithm);
            signature = normalized(signature);
            if (!TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_RETIREMENT_ATTESTATION_V1
                    .equals(schemaVersion) || !validRetirementId(retirementId)
                    || !validFingerprint(evidenceFingerprint)
                    || !validFingerprint(archiveSegmentFingerprint)
                    || !validFingerprint(previousFloorFingerprint)
                    || !validFingerprint(pinnedHeadFingerprint)
                    || signedAt == null || Instant.EPOCH.equals(signedAt) || keyId.isBlank()
                    || !"Ed25519".equals(algorithm) || signature.isBlank()) {
                throw new IllegalArgumentException(
                        "Lifecycle retirement attestation is incomplete");
            }
        }
    }

    /**
     * Complete signed retirement and its transition coordinates.
     *
     * @param evidenceFingerprint canonical retirement evidence fingerprint
     * @param retirementId deterministic retirement identity
     * @param scopeFingerprint payload-free exact-suite scope identity
     * @param suiteRef exact immutable suite coordinate
     * @param retirementGeneration contiguous retirement generation
     * @param previousFloor exact floor before this transition
     * @param pinnedHead exact committed head authorized for this transition
     * @param archive complete bounded retired prefix and immediate successor
     * @param cutoffExclusive exclusive entry-append cutoff
     * @param minimumRetainedEntries minimum suffix required by retention policy
     * @param maximumRetiredEntries maximum bounded retired prefix
     * @param retentionPolicyFingerprint immutable retention-policy identity
     * @param retiredAt producer database retirement time
     * @param attestationFingerprint canonical retirement attestation fingerprint
     * @param attestation detached retirement signature
     * @param retirementFingerprint canonical complete retirement fingerprint
     */
    public record Retirement(
            String evidenceFingerprint,
            String retirementId,
            String scopeFingerprint,
            SuiteRef suiteRef,
            long retirementGeneration,
            Floor previousFloor,
            Head pinnedHead,
            Archive archive,
            Instant cutoffExclusive,
            int minimumRetainedEntries,
            int maximumRetiredEntries,
            String retentionPolicyFingerprint,
            Instant retiredAt,
            String attestationFingerprint,
            RetirementAttestation attestation,
            String retirementFingerprint
    ) {
        /** Validates complete cross-object retirement shape without trusting signatures. */
        public Retirement {
            evidenceFingerprint = normalized(evidenceFingerprint);
            retirementId = normalized(retirementId);
            scopeFingerprint = normalized(scopeFingerprint);
            retentionPolicyFingerprint = normalized(retentionPolicyFingerprint);
            attestationFingerprint = normalized(attestationFingerprint);
            retirementFingerprint = normalized(retirementFingerprint);
            if (!validFingerprint(evidenceFingerprint) || !validRetirementId(retirementId)
                    || !validFingerprint(scopeFingerprint) || suiteRef == null
                    || retirementGeneration < 1 || previousFloor == null || pinnedHead == null
                    || archive == null || cutoffExclusive == null || retiredAt == null
                    || cutoffExclusive.isAfter(retiredAt) || minimumRetainedEntries < 1
                    || maximumRetiredEntries < 1 || maximumRetiredEntries > 100
                    || archive.retiredEntries().size() > maximumRetiredEntries
                    || !validFingerprint(retentionPolicyFingerprint)
                    || !validFingerprint(attestationFingerprint) || attestation == null
                    || !validFingerprint(retirementFingerprint)
                    || retirementGeneration != previousFloor.retirementGeneration() + 1
                    || retirementGeneration != archive.retirementGeneration()
                    || !scopeFingerprint.equals(previousFloor.scopeFingerprint())
                    || !scopeFingerprint.equals(pinnedHead.scopeFingerprint())
                    || !scopeFingerprint.equals(archive.scopeFingerprint())
                    || !suiteRef.equals(previousFloor.suiteRef())
                    || !suiteRef.equals(pinnedHead.suiteRef())
                    || !suiteRef.equals(archive.suiteRef())
                    || !retirementId.equals(attestation.retirementId())
                    || !evidenceFingerprint.equals(attestation.evidenceFingerprint())
                    || !archive.segmentFingerprint().equals(
                    attestation.archiveSegmentFingerprint())
                    || !previousFloor.floorFingerprint().equals(
                    attestation.previousFloorFingerprint())
                    || !pinnedHead.headFingerprint().equals(
                    attestation.pinnedHeadFingerprint())) {
                throw new IllegalArgumentException("Lifecycle retirement closure is inconsistent");
            }
        }
    }

    /**
     * Ordered compact identity of one page retirement.
     *
     * @param retirementGeneration contiguous retirement generation
     * @param retirementId deterministic retirement identity
     * @param retirementFingerprint canonical complete retirement fingerprint
     */
    public record RetirementRef(
            long retirementGeneration,
            String retirementId,
            String retirementFingerprint
    ) {
        /** Validates one complete ordered retirement reference. */
        public RetirementRef {
            retirementId = normalized(retirementId);
            retirementFingerprint = normalized(retirementFingerprint);
            if (retirementGeneration < 1 || !validRetirementId(retirementId)
                    || !validFingerprint(retirementFingerprint)) {
                throw new IllegalArgumentException("Lifecycle retirement reference is incomplete");
            }
        }
    }

    /**
     * Detached signature manifest over one exact lifecycle page and ordered closure.
     *
     * @param schemaVersion exact lifecycle-attestation generation
     * @param lifecyclePageId deterministic lifecycle-page identity
     * @param requestFingerprint exact request identity
     * @param pageFingerprint canonical complete page fingerprint
     * @param scopeFingerprint payload-free exact-suite scope identity
     * @param startingFloorFingerprint cursor floor pin
     * @param terminalFloorFingerprint page-derived terminal floor pin
     * @param currentFloorFingerprint snapshot current-floor pin
     * @param headFingerprint snapshot committed-head pin
     * @param retirementRefs complete ordered retirement closure
     * @param signedAt signing time used for key-lifecycle policy
     * @param keyId verification-key identity
     * @param algorithm detached signature algorithm
     * @param signature Base64-encoded detached signature
     */
    public record Attestation(
            String schemaVersion,
            String lifecyclePageId,
            String requestFingerprint,
            String pageFingerprint,
            String scopeFingerprint,
            String startingFloorFingerprint,
            String terminalFloorFingerprint,
            String currentFloorFingerprint,
            String headFingerprint,
            List<RetirementRef> retirementRefs,
            Instant signedAt,
            String keyId,
            String algorithm,
            String signature
    ) {
        /** Rejects incomplete or non-verifiable page signatures. */
        public Attestation {
            schemaVersion = normalized(schemaVersion);
            lifecyclePageId = normalized(lifecyclePageId);
            requestFingerprint = normalized(requestFingerprint);
            pageFingerprint = normalized(pageFingerprint);
            scopeFingerprint = normalized(scopeFingerprint);
            startingFloorFingerprint = normalized(startingFloorFingerprint);
            terminalFloorFingerprint = normalized(terminalFloorFingerprint);
            currentFloorFingerprint = normalized(currentFloorFingerprint);
            headFingerprint = normalized(headFingerprint);
            retirementRefs = retirementRefs == null ? List.of() : List.copyOf(retirementRefs);
            keyId = normalized(keyId);
            algorithm = normalized(algorithm);
            signature = normalized(signature);
            if (!TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_LIFECYCLE_ATTESTATION_V1
                    .equals(schemaVersion) || !pageId(lifecyclePageId)
                    || !validFingerprint(requestFingerprint) || !validFingerprint(pageFingerprint)
                    || !validFingerprint(scopeFingerprint)
                    || !validFingerprint(startingFloorFingerprint)
                    || !validFingerprint(terminalFloorFingerprint)
                    || !validFingerprint(currentFloorFingerprint)
                    || !validFingerprint(headFingerprint) || retirementRefs.size() > 10
                    || signedAt == null || Instant.EPOCH.equals(signedAt) || keyId.isBlank()
                    || !"Ed25519".equals(algorithm) || signature.isBlank()) {
                throw new IllegalArgumentException(
                        "Lifecycle page attestation is incomplete");
            }
        }
    }

    /** Freezes collections and verifies unsigned cross-object response shape. */
    public TestSuiteStabilityObservationLedgerLifecyclePage {
        schemaVersion = normalized(schemaVersion);
        lifecyclePageId = normalized(lifecyclePageId);
        pageFingerprint = normalized(pageFingerprint);
        scopeFingerprint = normalized(scopeFingerprint);
        retirements = retirements == null ? List.of() : List.copyOf(retirements);
        if (!TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_LIFECYCLE_RESPONSE_V1
                .equals(schemaVersion) || !pageId(lifecyclePageId)
                || !validFingerprint(pageFingerprint) || request == null
                || !validFingerprint(scopeFingerprint) || startingFloor == null
                || terminalFloor == null || currentFloor == null || head == null
                || retirements.size() > request.maximumRetirements()
                || observedAt == null || Instant.EPOCH.equals(observedAt)
                || attestation == null || rawResponse == null || !rawResponse.isObject()
                || !startingFloor.suiteRef().matches(request)
                || !terminalFloor.suiteRef().matches(request)
                || !currentFloor.suiteRef().matches(request) || !head.suiteRef().matches(request)
                || !sameScope(scopeFingerprint, startingFloor, terminalFloor, currentFloor, head)
                || request.afterRetirementGeneration()
                != startingFloor.retirementGeneration()
                || (!request.expectedCurrentFloorFingerprint().isBlank()
                && !request.expectedCurrentFloorFingerprint().equals(
                currentFloor.floorFingerprint()))
                || (!request.expectedHeadFingerprint().isBlank()
                && !request.expectedHeadFingerprint().equals(head.headFingerprint()))
                || head.latestSequence() < currentFloor.floorSequence()
                || !head.coverageFrom().equals(currentFloor.coverageFrom())
                || observedAt.isBefore(currentFloor.updatedAt())
                || observedAt.isBefore(head.updatedAt())
                || !lifecyclePageId.equals(attestation.lifecyclePageId())
                || !request.requestFingerprint().equals(attestation.requestFingerprint())
                || !pageFingerprint.equals(attestation.pageFingerprint())
                || !scopeFingerprint.equals(attestation.scopeFingerprint())
                || !startingFloor.floorFingerprint().equals(
                attestation.startingFloorFingerprint())
                || !terminalFloor.floorFingerprint().equals(
                attestation.terminalFloorFingerprint())
                || !currentFloor.floorFingerprint().equals(
                attestation.currentFloorFingerprint())
                || !head.headFingerprint().equals(attestation.headFingerprint())
                || attestation.signedAt().isBefore(observedAt)) {
            throw new IllegalArgumentException("Complete lifecycle page is inconsistent");
        }
        long expectedGeneration = startingFloor.retirementGeneration() + 1;
        for (Retirement retirement : retirements) {
            if (retirement.retirementGeneration() != expectedGeneration++
                    || !scopeFingerprint.equals(retirement.scopeFingerprint())
                    || !retirement.suiteRef().matches(request)) {
                throw new IllegalArgumentException(
                        "Lifecycle retirement order is inconsistent");
            }
        }
        List<RetirementRef> refs = retirements.stream().map(value ->
                new RetirementRef(value.retirementGeneration(), value.retirementId(),
                        value.retirementFingerprint())).toList();
        if (!refs.equals(attestation.retirementRefs())
                || hasMore != (terminalFloor.retirementGeneration()
                < currentFloor.retirementGeneration())
                || !hasMore && !terminalFloor.equals(currentFloor)) {
            throw new IllegalArgumentException("Lifecycle page closure is inconsistent");
        }
        rawResponse = rawResponse.deepCopy();
    }

    /**
     * Decodes one response after strict authoritative Schema validation.
     *
     * @param response complete server response
     * @return immutable typed projection
     */
    public static TestSuiteStabilityObservationLedgerLifecyclePage from(JsonNode response) {
        TestingProtocolSchemaValidator.require(
                response, "testSuiteStabilityObservationLedgerLifecyclePageResponse");
        JsonNode page = response.path("page");
        JsonNode requestJson = page.path("request");
        JsonNode requestSuite = requestJson.path("suiteRef");
        var request = new TestSuiteStabilityObservationLedgerLifecycleRequest(
                requestSuite.path("suiteId").asText(), requestSuite.path("revision").asLong(),
                requestSuite.path("fingerprint").asText(),
                requestJson.path("afterRetirementGeneration").asLong(),
                requestJson.path("maximumRetirements").asInt(),
                requestJson.path("expectedCurrentFloorFingerprint").asText(),
                requestJson.path("expectedHeadFingerprint").asText());
        if (!request.requestFingerprint().equals(page.path("requestFingerprint").asText())) {
            throw new IllegalArgumentException("Lifecycle request fingerprint is inconsistent");
        }
        List<Retirement> retirements = new ArrayList<>();
        page.path("retirements").forEach(value -> retirements.add(retirement(value)));
        Attestation attestation = attestation(response.path("attestation"));
        if (!response.path("pageFingerprint").asText().equals(
                page.path("pageFingerprint").asText())) {
            throw new IllegalArgumentException("Lifecycle response page fingerprint is inconsistent");
        }
        return new TestSuiteStabilityObservationLedgerLifecyclePage(
                response.path("schemaVersion").asText(),
                response.path("lifecyclePageId").asText(),
                response.path("pageFingerprint").asText(), request,
                page.path("scopeFingerprint").asText(), floor(page.path("startingFloor")),
                retirements, floor(page.path("terminalFloor")),
                floor(page.path("currentFloor")), head(page.path("head")),
                page.path("hasMore").asBoolean(), instant(page, "observedAt"),
                attestation, response.deepCopy());
    }

    /** @return outer lifecycle-signing key identity used by the shared verifier */
    @Override
    public String outerKeyId() {
        return attestation.keyId();
    }

    static Retirement retirement(JsonNode value) {
        JsonNode evidence = value.path("evidence");
        JsonNode archiveJson = evidence.path("archiveSegment");
        List<EntryRef> retiredEntries = new ArrayList<>();
        archiveJson.path("retiredEntries").forEach(entry ->
                retiredEntries.add(entry(entry)));
        Archive archive = new Archive(
                archiveJson.path("schemaVersion").asText(),
                archiveJson.path("segmentId").asText(),
                archiveJson.path("scopeFingerprint").asText(),
                suiteRef(archiveJson.path("suiteRef")),
                archiveJson.path("retirementGeneration").asLong(),
                archiveJson.path("previousObservationId").asText(),
                archiveJson.path("previousEntryFingerprint").asText(),
                retiredEntries, entry(archiveJson.path("successorEntry")),
                instant(archiveJson, "archivedAt"),
                archiveJson.path("segmentFingerprint").asText());
        JsonNode signature = value.path("attestation");
        RetirementAttestation attestation = new RetirementAttestation(
                signature.path("schemaVersion").asText(),
                signature.path("retirementId").asText(),
                signature.path("evidenceFingerprint").asText(),
                signature.path("archiveSegmentFingerprint").asText(),
                signature.path("previousFloorFingerprint").asText(),
                signature.path("pinnedHeadFingerprint").asText(),
                instant(signature, "signedAt"), signature.path("keyId").asText(),
                signature.path("algorithm").asText(), signature.path("signature").asText());
        return new Retirement(
                value.path("evidenceFingerprint").asText(),
                evidence.path("retirementId").asText(),
                evidence.path("scopeFingerprint").asText(),
                suiteRef(evidence.path("suiteRef")),
                evidence.path("retirementGeneration").asLong(),
                floor(evidence.path("previousFloor")), head(evidence.path("pinnedHead")),
                archive, instant(evidence, "cutoffExclusive"),
                evidence.path("minimumRetainedEntries").asInt(),
                evidence.path("maximumRetiredEntries").asInt(),
                evidence.path("retentionPolicyFingerprint").asText(),
                instant(evidence, "retiredAt"),
                value.path("attestationFingerprint").asText(), attestation,
                value.path("retirementFingerprint").asText());
    }

    private static Attestation attestation(JsonNode value) {
        List<RetirementRef> refs = new ArrayList<>();
        value.path("retirementRefs").forEach(ref -> refs.add(new RetirementRef(
                ref.path("retirementGeneration").asLong(),
                ref.path("retirementId").asText(),
                ref.path("retirementFingerprint").asText())));
        return new Attestation(
                value.path("schemaVersion").asText(), value.path("lifecyclePageId").asText(),
                value.path("requestFingerprint").asText(),
                value.path("pageFingerprint").asText(),
                value.path("scopeFingerprint").asText(),
                value.path("startingFloorFingerprint").asText(),
                value.path("terminalFloorFingerprint").asText(),
                value.path("currentFloorFingerprint").asText(),
                value.path("headFingerprint").asText(), refs,
                instant(value, "signedAt"), value.path("keyId").asText(),
                value.path("algorithm").asText(), value.path("signature").asText());
    }

    static Floor floor(JsonNode value) {
        return new Floor(
                value.path("schemaVersion").asText(), value.path("scopeFingerprint").asText(),
                suiteRef(value.path("suiteRef")), value.path("floorSequence").asLong(),
                value.path("previousObservationId").asText(),
                value.path("previousEntryFingerprint").asText(),
                value.path("floorObservationId").asText(),
                value.path("floorEntryFingerprint").asText(),
                instant(value, "coverageFrom"), value.path("retirementGeneration").asLong(),
                value.path("latestRetirementId").asText(),
                value.path("latestRetirementFingerprint").asText(),
                instant(value, "updatedAt"), value.path("floorFingerprint").asText());
    }

    static Head head(JsonNode value) {
        return new Head(
                value.path("schemaVersion").asText(), value.path("scopeFingerprint").asText(),
                suiteRef(value.path("suiteRef")), instant(value, "coverageFrom"),
                value.path("latestSequence").asLong(),
                value.path("latestObservationId").asText(),
                value.path("latestEntryFingerprint").asText(),
                instant(value, "updatedAt"), value.path("headFingerprint").asText());
    }

    static SuiteRef suiteRef(JsonNode value) {
        return new SuiteRef(value.path("suiteId").asText(), value.path("revision").asLong(),
                value.path("fingerprint").asText());
    }

    static EntryRef entry(JsonNode value) {
        return new EntryRef(
                value.path("sequence").asLong(), value.path("scopeFingerprint").asText(),
                value.path("previousObservationId").asText(),
                value.path("observation").path("evidence").path("observationId").asText(),
                value.path("entryFingerprint").asText(), instant(value, "appendedAt"));
    }

    private static boolean closed(
            List<EntryRef> entries,
            EntryRef successor,
            String scopeFingerprint,
            String predecessor,
            Instant archivedAt) {
        long sequence = entries.getFirst().sequence();
        String previous = predecessor;
        for (EntryRef entry : entries) {
            if (entry.sequence() != sequence || !scopeFingerprint.equals(entry.scopeFingerprint())
                    || !previous.equals(entry.previousObservationId())
                    || entry.appendedAt().isAfter(archivedAt)) {
                return false;
            }
            previous = entry.observationId();
            sequence++;
        }
        return successor.sequence() == sequence
                && scopeFingerprint.equals(successor.scopeFingerprint())
                && previous.equals(successor.previousObservationId())
                && !successor.appendedAt().isAfter(archivedAt);
    }

    static boolean sameScope(
            String scope,
            Floor starting,
            Floor terminal,
            Floor current,
            Head head) {
        return scope.equals(starting.scopeFingerprint())
                && scope.equals(terminal.scopeFingerprint())
                && scope.equals(current.scopeFingerprint())
                && scope.equals(head.scopeFingerprint());
    }

    static Instant instant(JsonNode value, String field) {
        try {
            return Instant.parse(value.path(field).asText());
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("Lifecycle timestamp is invalid");
        }
    }

    static boolean validObservationId(String value) {
        return normalized(value).matches("stability-observation-[0-9a-f]{64}");
    }

    static boolean validRetirementId(String value) {
        return normalized(value).matches("stability-observation-retirement-[0-9a-f]{64}");
    }

    static boolean archiveId(String value) {
        return normalized(value).matches("stability-observation-archive-[0-9a-f]{64}");
    }

    static boolean pageId(String value) {
        return normalized(value).matches("stability-observation-lifecycle-page-[0-9a-f]{64}");
    }

    static boolean validFingerprint(String value) {
        return normalized(value).matches("sha256:[0-9a-f]{64}");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
