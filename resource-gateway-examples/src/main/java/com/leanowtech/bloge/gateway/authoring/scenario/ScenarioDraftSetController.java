package com.leanowtech.bloge.gateway.authoring.scenario;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Authenticated HTTP authoring surface for durable Scenario draft sets.
 *
 * <p>Read and write deliberately reuse separate governed test-suite purposes. The controller is
 * absent from production profiles, while publication and execution remain separate endpoints with
 * their own permissions.</p>
 */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/api/visual/scenario-draft-sets")
public class ScenarioDraftSetController {

    private final ScenarioDraftSetAuthoringService service;
    private final IntegrationRequestAuthenticator authenticator;

    /**
     * @param service Scenario authoring application service
     * @param authenticator integration workload authenticator
     */
    public ScenarioDraftSetController(
            ScenarioDraftSetAuthoringService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    /** Validates a local draft against the current exact target without storing it. */
    @PostMapping("/validate")
    public ScenarioValidationReport validate(
            @RequestBody ScenarioDraftSet draftSet,
            @RequestHeader HttpHeaders headers) {
        return service.validate(draftSet, context(headers, IntegrationOperation.TEST_SUITE_WRITE));
    }

    /** Creates or updates one draft set under optimistic concurrency. */
    @PutMapping("/{scenarioDraftSetId}")
    public StoredScenarioDraftSet save(
            @PathVariable String scenarioDraftSetId,
            @RequestParam long expectedRevision,
            @RequestBody ScenarioDraftSet draftSet,
            @RequestHeader HttpHeaders headers) {
        return service.save(scenarioDraftSetId, expectedRevision, draftSet,
                context(headers, IntegrationOperation.TEST_SUITE_WRITE));
    }

    /** Reads the current revision in the authenticated enterprise scope. */
    @GetMapping("/{scenarioDraftSetId}")
    public StoredScenarioDraftSet find(
            @PathVariable String scenarioDraftSetId,
            @RequestHeader HttpHeaders headers) {
        return service.find(scenarioDraftSetId,
                context(headers, IntegrationOperation.TEST_SUITE_READ));
    }

    /** Reads retained immutable history, newest first. */
    @GetMapping("/{scenarioDraftSetId}/revisions")
    public List<StoredScenarioDraftSet> revisions(
            @PathVariable String scenarioDraftSetId,
            @RequestHeader HttpHeaders headers) {
        return service.revisions(scenarioDraftSetId,
                context(headers, IntegrationOperation.TEST_SUITE_READ));
    }

    /** Queries one exact bounded Matrix page from the server-side Scenario row index. */
    @PostMapping("/{scenarioDraftSetId}/matrix/query")
    public ScenarioTablePage queryMatrix(
            @PathVariable String scenarioDraftSetId,
            @RequestBody ScenarioTablePageQuery query,
            @RequestHeader HttpHeaders headers) {
        return service.queryPage(scenarioDraftSetId, query,
                context(headers, IntegrationOperation.TEST_SUITE_READ));
    }

    /** Applies a source-bound group of Matrix edits as one all-or-nothing Scenario revision. */
    @PostMapping("/{scenarioDraftSetId}/matrix/bulk-edits")
    public ScenarioBulkEditResult bulkEditMatrix(
            @PathVariable String scenarioDraftSetId,
            @RequestBody ScenarioBulkEditCommand command,
            @RequestHeader HttpHeaders headers) {
        return service.bulkEdit(scenarioDraftSetId, command,
                context(headers, IntegrationOperation.TEST_SUITE_WRITE));
    }

    /** Returns semantic Contract drift and exact Scenario impact for one retained revision. */
    @GetMapping("/{scenarioDraftSetId}/compatibility")
    public ContractCompatibilityReport compatibility(
            @PathVariable String scenarioDraftSetId,
            @RequestParam long revision,
            @RequestHeader HttpHeaders headers) {
        return service.compatibility(
                scenarioDraftSetId,
                revision,
                context(headers, IntegrationOperation.TEST_SUITE_READ));
    }

    /** Returns the server-authoritative Contract coordinate for one retained Graph draft. */
    @GetMapping("/targets/graphs/{draftId}/contract")
    public ScenarioContractProjection projectGraphContract(
            @PathVariable String draftId,
            @RequestHeader HttpHeaders headers) {
        return service.projectGraphContract(
                draftId,
                context(headers, IntegrationOperation.TEST_SUITE_READ));
    }

    /** Returns the server-authoritative Contract coordinate for one catalog Operator. */
    @GetMapping("/targets/operators/{operatorRef}/contract")
    public ScenarioContractProjection projectOperatorContract(
            @PathVariable String operatorRef,
            @RequestHeader HttpHeaders headers) {
        return service.projectOperatorContract(
                operatorRef,
                context(headers, IntegrationOperation.TEST_SUITE_READ));
    }

    private IntegrationRequestContext context(
            HttpHeaders headers,
            IntegrationOperation operation) {
        return authenticator.authenticate(headers, operation);
    }
}
