package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;

/**
 * Strict receipt-aware projection of one signed observation-ledger lifecycle v2 page.
 *
 * <p>Construction validates the authoritative Schema and complete unsigned cross-object shape,
 * including pairwise equality between each retirement and the retirement embedded in its exact
 * external archive request. It does not trust any claimed fingerprint, signature, authority,
 * failure domain, copy threshold, or retention policy. Use
 * {@link TestSuiteStabilityObservationLedgerLifecycleArchiveEvidenceVerifier} before advancing a
 * lifecycle checkpoint.</p>
 *
 * @param schemaVersion exact v2 response generation
 * @param lifecyclePageId deterministic page identity
 * @param pageFingerprint canonical complete v2 page identity
 * @param request exact request embedded in the page
 * @param scopeFingerprint payload-free producer scope identity
 * @param startingFloor floor represented by the request cursor
 * @param retirements ordered bounded retirement records
 * @param externalArchiveReceiptSets exact receipt set paired with each retirement
 * @param terminalFloor floor after this page's transitions
 * @param currentFloor snapshot current floor
 * @param head snapshot current ledger head
 * @param hasMore whether another pinned page is required
 * @param observedAt producer database snapshot time
 * @param attestation detached v2 page signature
 * @param rawResponse defensive schema-validated response
 */
public record TestSuiteStabilityObservationLedgerLifecycleArchivePage(
        String schemaVersion,
        String lifecyclePageId,
        String pageFingerprint,
        TestSuiteStabilityObservationLedgerLifecycleRequest request,
        String scopeFingerprint,
        TestSuiteStabilityObservationLedgerLifecyclePage.Floor startingFloor,
        List<TestSuiteStabilityObservationLedgerLifecyclePage.Retirement> retirements,
        List<ExternalArchiveReceiptSet> externalArchiveReceiptSets,
        TestSuiteStabilityObservationLedgerLifecyclePage.Floor terminalFloor,
        TestSuiteStabilityObservationLedgerLifecyclePage.Floor currentFloor,
        TestSuiteStabilityObservationLedgerLifecyclePage.Head head,
        boolean hasMore,
        Instant observedAt,
        Attestation attestation,
        JsonNode rawResponse
) implements TestSuiteStabilityObservationLedgerLifecyclePageView {
    /**
     * One independently signed external immutable-storage acknowledgement.
     *
     * @param schemaVersion exact receipt generation
     * @param receiptFingerprint canonical signed-material fingerprint
     * @param requestFingerprint exact archive request identity
     * @param trustDomain configured archive trust domain
     * @param archiveSetId configured archive-set identity
     * @param authorityId independent authority identity
     * @param failureDomain independent failure-domain identity
     * @param keyId pinned Ed25519 key identity
     * @param objectId deterministic immutable object identity
     * @param retirementId exact retirement identity
     * @param retirementFingerprint complete retirement fingerprint
     * @param segmentId nested compact archive identity
     * @param segmentFingerprint nested compact archive fingerprint
     * @param retentionPolicyFingerprint immutable retention-policy revision
     * @param retainUntil authority-enforced immutable deadline
     * @param storedAt external object commit time
     * @param issuedAt receipt issue time
     * @param expiresAt exclusive receipt-admission deadline
     * @param retentionMode fixed compliance retention mode
     * @param externallyDurable external failure-domain assertion
     * @param writeOnce immutable identity assertion
     * @param deleteBeforeRetentionDenied early-delete denial assertion
     * @param algorithm detached signature algorithm
     * @param signature Base64 detached signature over the receipt fingerprint
     */
    public record ExternalArchiveReceipt(
            String schemaVersion,
            String receiptFingerprint,
            String requestFingerprint,
            String trustDomain,
            String archiveSetId,
            String authorityId,
            String failureDomain,
            String keyId,
            String objectId,
            String retirementId,
            String retirementFingerprint,
            String segmentId,
            String segmentFingerprint,
            String retentionPolicyFingerprint,
            Instant retainUntil,
            Instant storedAt,
            Instant issuedAt,
            Instant expiresAt,
            String retentionMode,
            boolean externallyDurable,
            boolean writeOnce,
            boolean deleteBeforeRetentionDenied,
            String algorithm,
            String signature
    ) {
        /** Rejects incomplete receipt shape without trusting canonical or signature material. */
        public ExternalArchiveReceipt {
            schemaVersion = normalized(schemaVersion);
            receiptFingerprint = normalized(receiptFingerprint);
            requestFingerprint = normalized(requestFingerprint);
            trustDomain = normalized(trustDomain);
            archiveSetId = normalized(archiveSetId);
            authorityId = normalized(authorityId);
            failureDomain = normalized(failureDomain);
            keyId = normalized(keyId);
            objectId = normalized(objectId);
            retirementId = normalized(retirementId);
            retirementFingerprint = normalized(retirementFingerprint);
            segmentId = normalized(segmentId);
            segmentFingerprint = normalized(segmentFingerprint);
            retentionPolicyFingerprint = normalized(retentionPolicyFingerprint);
            retentionMode = normalized(retentionMode);
            algorithm = normalized(algorithm);
            signature = normalized(signature);
            boolean signatureShape;
            try {
                signatureShape = Base64.getDecoder().decode(signature).length == 64;
            } catch (IllegalArgumentException malformed) {
                signatureShape = false;
            }
            if (!TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_EXTERNAL_ARCHIVE_RECEIPT_V1
                    .equals(schemaVersion)
                    || !fingerprint(receiptFingerprint) || !fingerprint(requestFingerprint)
                    || !identifier(trustDomain) || !identifier(archiveSetId)
                    || !identifier(authorityId) || !identifier(failureDomain)
                    || !identifier(keyId) || !validObjectId(objectId)
                    || !TestSuiteStabilityObservationLedgerLifecyclePage
                    .validRetirementId(retirementId)
                    || !fingerprint(retirementFingerprint) || !archiveId(segmentId)
                    || !fingerprint(segmentFingerprint)
                    || !fingerprint(retentionPolicyFingerprint)
                    || retainUntil == null || storedAt == null || issuedAt == null
                    || expiresAt == null || issuedAt.getNano() != 0
                    || expiresAt.getNano() != 0 || storedAt.isAfter(issuedAt)
                    || !retainUntil.isAfter(storedAt) || !expiresAt.isAfter(issuedAt)
                    || Duration.between(issuedAt, expiresAt).compareTo(Duration.ofSeconds(60)) > 0
                    || !"COMPLIANCE".equals(retentionMode) || !externallyDurable
                    || !writeOnce || !deleteBeforeRetentionDenied
                    || !"Ed25519".equals(algorithm) || !signatureShape) {
                throw new IllegalArgumentException("External archive receipt is incomplete");
            }
        }
    }

    /**
     * Exact challenge-bound request plus ordered external receipt topology committed locally.
     *
     * @param schemaVersion exact receipt-set generation
     * @param receiptSetId deterministic receipt-set identity
     * @param requestFingerprint exact challenge-bound request identity
     * @param trustDomain request trust domain
     * @param archiveSetId request archive-set identity
     * @param retirementId embedded exact retirement identity
     * @param retirementFingerprint embedded complete retirement fingerprint
     * @param segmentId embedded archive identity
     * @param segmentFingerprint embedded archive fingerprint
     * @param retentionPolicyFingerprint embedded policy revision
     * @param retainUntil requested minimum immutable deadline
     * @param challenge unpadded base64url request entropy
     * @param requestedAt request creation time
     * @param requestExpiresAt exclusive request-admission deadline
     * @param requiredCopies committed independent copy threshold
     * @param receipts authority-id-sorted full signed receipts
     * @param confirmedAt local confirmation time
     * @param receiptSetFingerprint complete set fingerprint
     * @param rawValue defensive exact receipt-set JSON
     */
    public record ExternalArchiveReceiptSet(
            String schemaVersion,
            String receiptSetId,
            String requestFingerprint,
            String trustDomain,
            String archiveSetId,
            String retirementId,
            String retirementFingerprint,
            String segmentId,
            String segmentFingerprint,
            String retentionPolicyFingerprint,
            Instant retainUntil,
            String challenge,
            Instant requestedAt,
            Instant requestExpiresAt,
            int requiredCopies,
            List<ExternalArchiveReceipt> receipts,
            Instant confirmedAt,
            String receiptSetFingerprint,
            JsonNode rawValue
    ) {
        /** Rejects malformed pair bindings, admission windows, ordering, and copy topology. */
        public ExternalArchiveReceiptSet {
            schemaVersion = normalized(schemaVersion);
            receiptSetId = normalized(receiptSetId);
            requestFingerprint = normalized(requestFingerprint);
            trustDomain = normalized(trustDomain);
            archiveSetId = normalized(archiveSetId);
            retirementId = normalized(retirementId);
            retirementFingerprint = normalized(retirementFingerprint);
            segmentId = normalized(segmentId);
            segmentFingerprint = normalized(segmentFingerprint);
            retentionPolicyFingerprint = normalized(retentionPolicyFingerprint);
            challenge = normalized(challenge);
            receipts = receipts == null ? List.of() : List.copyOf(receipts);
            receiptSetFingerprint = normalized(receiptSetFingerprint);
            boolean topology = requiredCopies >= 1 && receipts.size() >= requiredCopies
                    && receipts.size() <= 16;
            HashSet<String> authorities = new HashSet<>();
            HashSet<String> domains = new HashSet<>();
            String previousAuthority = "";
            for (ExternalArchiveReceipt receipt : receipts) {
                if (receipt == null || !requestFingerprint.equals(receipt.requestFingerprint())
                        || !trustDomain.equals(receipt.trustDomain())
                        || !archiveSetId.equals(receipt.archiveSetId())
                        || !retirementId.equals(receipt.retirementId())
                        || !retirementFingerprint.equals(receipt.retirementFingerprint())
                        || !segmentId.equals(receipt.segmentId())
                        || !segmentFingerprint.equals(receipt.segmentFingerprint())
                        || !retentionPolicyFingerprint.equals(
                        receipt.retentionPolicyFingerprint())
                        || receipt.retainUntil().isBefore(retainUntil)
                        || !authorities.add(receipt.authorityId())
                        || !domains.add(receipt.failureDomain())
                        || previousAuthority.compareTo(receipt.authorityId()) >= 0) {
                    topology = false;
                    break;
                }
                previousAuthority = receipt.authorityId();
            }
            if (!TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_EXTERNAL_ARCHIVE_RECEIPT_SET_V1
                    .equals(schemaVersion) || !validReceiptSetId(receiptSetId)
                    || !fingerprint(requestFingerprint) || !identifier(trustDomain)
                    || !identifier(archiveSetId)
                    || !TestSuiteStabilityObservationLedgerLifecyclePage
                    .validRetirementId(retirementId)
                    || !fingerprint(retirementFingerprint) || !archiveId(segmentId)
                    || !fingerprint(segmentFingerprint)
                    || !fingerprint(retentionPolicyFingerprint) || retainUntil == null
                    || !challenge.matches("[A-Za-z0-9_-]{43}")
                    || requestedAt == null || requestExpiresAt == null
                    || requestedAt.getNano() != 0 || requestExpiresAt.getNano() != 0
                    || !requestExpiresAt.isAfter(requestedAt)
                    || Duration.between(requestedAt, requestExpiresAt)
                    .compareTo(Duration.ofSeconds(60)) > 0
                    || !topology || confirmedAt == null
                    || confirmedAt.isBefore(requestedAt)
                    || !confirmedAt.isBefore(requestExpiresAt)
                    || receipts.stream().anyMatch(receipt ->
                    receipt.issuedAt().isBefore(requestedAt)
                            || confirmedAt.isBefore(receipt.issuedAt())
                            || !confirmedAt.isBefore(receipt.expiresAt()))
                    || !fingerprint(receiptSetFingerprint)
                    || rawValue == null || !rawValue.isObject()) {
                throw new IllegalArgumentException("External archive receipt set is incomplete");
            }
            rawValue = rawValue.deepCopy();
        }
    }

    /**
     * Ordered v2 signature reference over one retirement and its external receipt set.
     *
     * @param retirementGeneration contiguous retirement generation
     * @param retirementId exact retirement identity
     * @param retirementFingerprint exact complete retirement fingerprint
     * @param receiptSetId exact external receipt-set identity
     * @param receiptSetFingerprint exact complete receipt-set fingerprint
     * @param requiredCopies committed independent copy threshold
     * @param receiptCount exact number of retained signed receipts
     */
    public record ArchiveRef(
            long retirementGeneration,
            String retirementId,
            String retirementFingerprint,
            String receiptSetId,
            String receiptSetFingerprint,
            int requiredCopies,
            int receiptCount
    ) {
        /** Rejects incomplete bounded archive references. */
        public ArchiveRef {
            retirementId = normalized(retirementId);
            retirementFingerprint = normalized(retirementFingerprint);
            receiptSetId = normalized(receiptSetId);
            receiptSetFingerprint = normalized(receiptSetFingerprint);
            if (retirementGeneration < 1
                    || !TestSuiteStabilityObservationLedgerLifecyclePage
                    .validRetirementId(retirementId)
                    || !fingerprint(retirementFingerprint) || !validReceiptSetId(receiptSetId)
                    || !fingerprint(receiptSetFingerprint) || requiredCopies < 1
                    || receiptCount < requiredCopies || receiptCount > 16) {
                throw new IllegalArgumentException("Lifecycle archive reference is incomplete");
            }
        }
    }

    /**
     * Detached v2 signature manifest over the page and ordered archive-proof closure.
     *
     * @param schemaVersion exact v2 attestation generation
     * @param lifecyclePageId deterministic page identity
     * @param requestFingerprint exact request identity
     * @param pageFingerprint canonical complete v2 page identity
     * @param scopeFingerprint exact-suite scope identity
     * @param startingFloorFingerprint cursor floor pin
     * @param terminalFloorFingerprint page-derived terminal floor pin
     * @param currentFloorFingerprint snapshot current floor pin
     * @param headFingerprint snapshot current head pin
     * @param archiveRefs ordered retirement and receipt-set closure
     * @param signedAt signing time used for key-lifecycle policy
     * @param keyId lifecycle verification-key identity
     * @param algorithm detached signature algorithm
     * @param signature Base64 detached signature
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
            List<ArchiveRef> archiveRefs,
            Instant signedAt,
            String keyId,
            String algorithm,
            String signature
    ) {
        /** Rejects incomplete or non-verifiable v2 page signatures. */
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
            archiveRefs = archiveRefs == null ? List.of() : List.copyOf(archiveRefs);
            keyId = normalized(keyId);
            algorithm = normalized(algorithm);
            signature = normalized(signature);
            if (!TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_LIFECYCLE_ATTESTATION_V2
                    .equals(schemaVersion)
                    || !TestSuiteStabilityObservationLedgerLifecyclePage.pageId(lifecyclePageId)
                    || !fingerprint(requestFingerprint) || !fingerprint(pageFingerprint)
                    || !fingerprint(scopeFingerprint) || !fingerprint(startingFloorFingerprint)
                    || !fingerprint(terminalFloorFingerprint)
                    || !fingerprint(currentFloorFingerprint) || !fingerprint(headFingerprint)
                    || archiveRefs.size() > 10 || signedAt == null
                    || Instant.EPOCH.equals(signedAt) || keyId.isBlank()
                    || !"Ed25519".equals(algorithm) || signature.isBlank()) {
                throw new IllegalArgumentException(
                        "Receipt-aware lifecycle attestation is incomplete");
            }
        }
    }

    /** Freezes collections and verifies unsigned v2 cross-object response shape. */
    public TestSuiteStabilityObservationLedgerLifecycleArchivePage {
        schemaVersion = normalized(schemaVersion);
        lifecyclePageId = normalized(lifecyclePageId);
        pageFingerprint = normalized(pageFingerprint);
        scopeFingerprint = normalized(scopeFingerprint);
        retirements = retirements == null ? List.of() : List.copyOf(retirements);
        externalArchiveReceiptSets = externalArchiveReceiptSets == null
                ? List.of() : List.copyOf(externalArchiveReceiptSets);
        if (!TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_LIFECYCLE_RESPONSE_V2
                .equals(schemaVersion)
                || !TestSuiteStabilityObservationLedgerLifecyclePage.pageId(lifecyclePageId)
                || !fingerprint(pageFingerprint) || request == null
                || !fingerprint(scopeFingerprint) || startingFloor == null
                || terminalFloor == null || currentFloor == null || head == null
                || retirements.size() > request.maximumRetirements()
                || retirements.size() != externalArchiveReceiptSets.size()
                || observedAt == null || Instant.EPOCH.equals(observedAt)
                || attestation == null || rawResponse == null || !rawResponse.isObject()
                || !startingFloor.suiteRef().matches(request)
                || !terminalFloor.suiteRef().matches(request)
                || !currentFloor.suiteRef().matches(request) || !head.suiteRef().matches(request)
                || !TestSuiteStabilityObservationLedgerLifecyclePage.sameScope(
                scopeFingerprint, startingFloor, terminalFloor, currentFloor, head)
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
            throw new IllegalArgumentException("Complete lifecycle v2 page is inconsistent");
        }
        JsonNode rawPage = rawResponse.path("page");
        long expectedGeneration = startingFloor.retirementGeneration() + 1;
        List<ArchiveRef> refs = new ArrayList<>();
        for (int index = 0; index < retirements.size(); index++) {
            TestSuiteStabilityObservationLedgerLifecyclePage.Retirement retirement =
                    retirements.get(index);
            ExternalArchiveReceiptSet receiptSet = externalArchiveReceiptSets.get(index);
            if (retirement == null || receiptSet == null
                    || retirement.retirementGeneration() != expectedGeneration++
                    || !scopeFingerprint.equals(retirement.scopeFingerprint())
                    || !retirement.suiteRef().matches(request)
                    || !retirement.retirementId().equals(receiptSet.retirementId())
                    || !retirement.retirementFingerprint().equals(
                    receiptSet.retirementFingerprint())
                    || !retirement.archive().segmentId().equals(receiptSet.segmentId())
                    || !retirement.archive().segmentFingerprint().equals(
                    receiptSet.segmentFingerprint())
                    || !retirement.retentionPolicyFingerprint().equals(
                    receiptSet.retentionPolicyFingerprint())
                    || receiptSet.confirmedAt().isAfter(observedAt)
                    || !canonicallyEqual(rawPage.path("retirements").path(index),
                    receiptSet.rawValue().path("request").path("retirement"))) {
                throw new IllegalArgumentException(
                        "Lifecycle retirement and external proof are inconsistent");
            }
            refs.add(new ArchiveRef(retirement.retirementGeneration(),
                    retirement.retirementId(), retirement.retirementFingerprint(),
                    receiptSet.receiptSetId(), receiptSet.receiptSetFingerprint(),
                    receiptSet.requiredCopies(), receiptSet.receipts().size()));
        }
        if (!refs.equals(attestation.archiveRefs())
                || hasMore != (terminalFloor.retirementGeneration()
                < currentFloor.retirementGeneration())
                || !hasMore && !terminalFloor.equals(currentFloor)) {
            throw new IllegalArgumentException("Lifecycle v2 page closure is inconsistent");
        }
        rawResponse = rawResponse.deepCopy();
    }

    /**
     * Decodes one v2 response after strict authoritative Schema validation.
     *
     * @param response complete server response
     * @return immutable receipt-aware typed projection
     */
    public static TestSuiteStabilityObservationLedgerLifecycleArchivePage from(
            JsonNode response) {
        TestingProtocolSchemaValidator.require(
                response, "testSuiteStabilityObservationLedgerLifecyclePageResponseV2");
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
            throw new IllegalArgumentException("Lifecycle v2 request fingerprint is inconsistent");
        }
        List<TestSuiteStabilityObservationLedgerLifecyclePage.Retirement> retirements =
                new ArrayList<>();
        page.path("retirements").forEach(value -> retirements.add(
                TestSuiteStabilityObservationLedgerLifecyclePage.retirement(value)));
        List<ExternalArchiveReceiptSet> receiptSets = new ArrayList<>();
        page.path("externalArchiveReceiptSets").forEach(value ->
                receiptSets.add(receiptSet(value)));
        Attestation attestation = attestation(response.path("attestation"));
        if (!response.path("pageFingerprint").asText().equals(
                page.path("pageFingerprint").asText())) {
            throw new IllegalArgumentException(
                    "Lifecycle v2 response page fingerprint is inconsistent");
        }
        return new TestSuiteStabilityObservationLedgerLifecycleArchivePage(
                response.path("schemaVersion").asText(),
                response.path("lifecyclePageId").asText(),
                response.path("pageFingerprint").asText(), request,
                page.path("scopeFingerprint").asText(),
                TestSuiteStabilityObservationLedgerLifecyclePage.floor(
                        page.path("startingFloor")),
                retirements, receiptSets,
                TestSuiteStabilityObservationLedgerLifecyclePage.floor(
                        page.path("terminalFloor")),
                TestSuiteStabilityObservationLedgerLifecyclePage.floor(
                        page.path("currentFloor")),
                TestSuiteStabilityObservationLedgerLifecyclePage.head(page.path("head")),
                page.path("hasMore").asBoolean(), instant(page, "observedAt"),
                attestation, response.deepCopy());
    }

    /** @return outer lifecycle-signing key identity used by the shared verifier */
    @Override
    public String outerKeyId() {
        return attestation.keyId();
    }

    private static ExternalArchiveReceiptSet receiptSet(JsonNode value) {
        JsonNode request = value.path("request");
        JsonNode retirement = request.path("retirement");
        JsonNode evidence = retirement.path("evidence");
        JsonNode archive = evidence.path("archiveSegment");
        List<ExternalArchiveReceipt> receipts = new ArrayList<>();
        value.path("receipts").forEach(receipt -> receipts.add(receipt(receipt)));
        return new ExternalArchiveReceiptSet(
                value.path("schemaVersion").asText(), value.path("receiptSetId").asText(),
                request.path("requestFingerprint").asText(),
                request.path("trustDomain").asText(), request.path("archiveSetId").asText(),
                evidence.path("retirementId").asText(),
                retirement.path("retirementFingerprint").asText(),
                archive.path("segmentId").asText(), archive.path("segmentFingerprint").asText(),
                evidence.path("retentionPolicyFingerprint").asText(),
                instant(request, "retainUntil"), request.path("challenge").asText(),
                instant(request, "requestedAt"), instant(request, "expiresAt"),
                value.path("requiredCopies").asInt(), receipts,
                instant(value, "confirmedAt"), value.path("receiptSetFingerprint").asText(),
                value.deepCopy());
    }

    private static ExternalArchiveReceipt receipt(JsonNode value) {
        return new ExternalArchiveReceipt(
                value.path("schemaVersion").asText(),
                value.path("receiptFingerprint").asText(),
                value.path("requestFingerprint").asText(),
                value.path("trustDomain").asText(), value.path("archiveSetId").asText(),
                value.path("authorityId").asText(), value.path("failureDomain").asText(),
                value.path("keyId").asText(), value.path("objectId").asText(),
                value.path("retirementId").asText(),
                value.path("retirementFingerprint").asText(),
                value.path("segmentId").asText(), value.path("segmentFingerprint").asText(),
                value.path("retentionPolicyFingerprint").asText(),
                instant(value, "retainUntil"), instant(value, "storedAt"),
                instant(value, "issuedAt"), instant(value, "expiresAt"),
                value.path("retentionMode").asText(),
                value.path("externallyDurable").asBoolean(),
                value.path("writeOnce").asBoolean(),
                value.path("deleteBeforeRetentionDenied").asBoolean(),
                value.path("algorithm").asText(), value.path("signature").asText());
    }

    private static Attestation attestation(JsonNode value) {
        List<ArchiveRef> refs = new ArrayList<>();
        value.path("archiveRefs").forEach(ref -> refs.add(new ArchiveRef(
                ref.path("retirementGeneration").asLong(),
                ref.path("retirementId").asText(),
                ref.path("retirementFingerprint").asText(),
                ref.path("receiptSetId").asText(),
                ref.path("receiptSetFingerprint").asText(),
                ref.path("requiredCopies").asInt(), ref.path("receiptCount").asInt())));
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

    private static boolean canonicallyEqual(JsonNode left, JsonNode right) {
        return EvidenceVerificationSupport.sha256(left)
                .equals(EvidenceVerificationSupport.sha256(right));
    }

    private static Instant instant(JsonNode value, String field) {
        try {
            return Instant.parse(value.path(field).asText());
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("Lifecycle v2 timestamp is invalid");
        }
    }

    private static boolean fingerprint(String value) {
        return normalized(value).matches("sha256:[0-9a-f]{64}");
    }

    private static boolean identifier(String value) {
        return normalized(value).matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    }

    private static boolean archiveId(String value) {
        return normalized(value).matches("stability-observation-archive-[0-9a-f]{64}");
    }

    private static boolean validObjectId(String value) {
        return normalized(value).matches("stability-observation-worm-[0-9a-f]{64}");
    }

    private static boolean validReceiptSetId(String value) {
        return normalized(value).matches(
                "stability-observation-external-archive-receipts-[0-9a-f]{64}");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
