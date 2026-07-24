package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.CaseHandlingAssertion;
import com.leanowtech.bloge.gateway.integration.mirror.CompiledScenarioRehearsalPlan;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCheckpointBundle;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioArtifactRegistryService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioCase;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioPack;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalCompileRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalEvidenceBundle;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalExecutionRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalLegalHoldCommand;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalPurgeCommand;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRetentionService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRetentionState;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRuntimeService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalWorkbookSeed;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Protected strict transport for governed ScenarioPack registration and rehearsal compilation.
 */
@RestController
@RequestMapping("/api/mirror/scenarios")
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public final class ScenarioRehearsalController {
    private final ScenarioArtifactRegistryService artifacts;
    private final ScenarioRehearsalIntegrationService rehearsals;
    private final ScenarioRehearsalRuntimeService runtime;
    private final ScenarioRehearsalRetentionService retention;
    private final IntegrationRequestAuthenticator authenticator;
    private final ScenarioArtifactRequestDecoder decoder;

    /** Creates the protected scenario transport. */
    public ScenarioRehearsalController(
            ScenarioArtifactRegistryService artifacts,
            ScenarioRehearsalIntegrationService rehearsals,
            ScenarioRehearsalRuntimeService runtime,
            ScenarioRehearsalRetentionService retention,
            IntegrationRequestAuthenticator authenticator,
            ScenarioArtifactRequestDecoder decoder) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.rehearsals = Objects.requireNonNull(rehearsals, "rehearsals");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.retention = Objects.requireNonNull(
                retention, "retention");
        this.authenticator = Objects.requireNonNull(
                authenticator, "authenticator");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /** Registers one exact handling-assertion revision. */
    @PostMapping("/assertions")
    public IntegrationEnvelope<CaseHandlingAssertion> registerAssertion(
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticateWrite(headers);
        CaseHandlingAssertion value = artifacts.register(
                decoder.decodeAssertion(request, identity), identity);
        return IntegrationEnvelope.of(
                "CASE_HANDLING_ASSERTION",
                value.schemaVersion(),
                value);
    }

    /** Registers one signed payload-free Session checkpoint. */
    @PostMapping("/checkpoints")
    public IntegrationEnvelope<MirrorSessionCheckpointBundle>
    registerCheckpoint(
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticateWrite(headers);
        MirrorSessionCheckpointBundle value = artifacts.register(
                decoder.decodeCheckpoint(request, identity), identity);
        return IntegrationEnvelope.of(
                "MIRROR_SESSION_CHECKPOINT_BUNDLE",
                value.schemaVersion(),
                value);
    }

    /** Registers one ScenarioCase after its assertion/checkpoint closure exists. */
    @PostMapping("/cases")
    public IntegrationEnvelope<ScenarioCase> registerCase(
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticateWrite(headers);
        ScenarioCase value = artifacts.register(
                decoder.decodeCase(request, identity), identity);
        return IntegrationEnvelope.of(
                "SCENARIO_CASE", value.schemaVersion(), value);
    }

    /** Registers one complete ScenarioPack revision. */
    @PostMapping("/packs")
    public IntegrationEnvelope<ScenarioPack> registerPack(
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticateWrite(headers);
        ScenarioPack value = artifacts.register(
                decoder.decodePack(request, identity), identity);
        return IntegrationEnvelope.of(
                "SCENARIO_PACK", value.schemaVersion(), value);
    }

    /** Reads one exact registered ScenarioPack revision. */
    @GetMapping("/packs/{packId}")
    public IntegrationEnvelope<ScenarioPack> findPack(
            @PathVariable String packId,
            @RequestParam long revision,
            @RequestParam String fingerprint,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers,
                IntegrationOperation.MIRROR_SCENARIO_ARTIFACT_READ);
        ScenarioPack value = artifacts.findPack(
                packId, revision, fingerprint, identity);
        return IntegrationEnvelope.of(
                "SCENARIO_PACK", value.schemaVersion(), value);
    }

    /** Compiles one exact registered pack into a payload-free execution license. */
    @PostMapping("/packs/{packId}/compiled-plans")
    public IntegrationEnvelope<CompiledScenarioRehearsalPlan> compile(
            @PathVariable String packId,
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers,
                IntegrationOperation.MIRROR_REHEARSAL_PLAN_COMPILE);
        ScenarioRehearsalCompileRequest command =
                decoder.decodeCompileRequest(request, identity);
        CompiledScenarioRehearsalPlan value = rehearsals.compile(
                packId,
                command.revision(),
                command.fingerprint(),
                identity);
        return IntegrationEnvelope.of(
                "COMPILED_SCENARIO_REHEARSAL_PLAN",
                value.schemaVersion(),
                value);
    }

    /** Reads one exact compiler-issued execution license. */
    @GetMapping("/compiled-plans/{planId}")
    public IntegrationEnvelope<CompiledScenarioRehearsalPlan> findCompiledPlan(
            @PathVariable String planId,
            @RequestParam long revision,
            @RequestParam String fingerprint,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers,
                IntegrationOperation.MIRROR_REHEARSAL_PLAN_READ);
        CompiledScenarioRehearsalPlan value = rehearsals.find(
                planId, revision, fingerprint, identity);
        return IntegrationEnvelope.of(
                "COMPILED_SCENARIO_REHEARSAL_PLAN",
                value.schemaVersion(),
                value);
    }

    /** Executes one exact compiled plan without accepting runtime payload overrides. */
    @PostMapping("/runs")
    public IntegrationEnvelope<ScenarioRehearsalEvidenceBundle> execute(
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers,
                IntegrationOperation.MIRROR_REHEARSAL_EXECUTE);
        ScenarioRehearsalEvidenceBundle value = runtime.execute(
                decoder.decodeExecutionRequest(request, identity),
                identity);
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_EVIDENCE_BUNDLE",
                value.schemaVersion(),
                value);
    }

    /** Reads one independently verified signed Scenario rehearsal aggregate. */
    @GetMapping("/runs/{runId}/evidence")
    public IntegrationEnvelope<ScenarioRehearsalEvidenceBundle> evidence(
            @PathVariable String runId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers,
                IntegrationOperation.MIRROR_REHEARSAL_EVIDENCE_READ);
        ScenarioRehearsalEvidenceBundle value =
                runtime.evidence(runId, identity);
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_EVIDENCE_BUNDLE",
                value.schemaVersion(),
                value);
    }

    /** Projects one verified Scenario closure into a deterministic ANEKE workbook seed. */
    @GetMapping("/runs/{runId}/workbook-seed")
    public IntegrationEnvelope<ScenarioRehearsalWorkbookSeed>
    workbookSeed(
            @PathVariable String runId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_WORKBOOK_READ);
        ScenarioRehearsalWorkbookSeed value =
                runtime.workbookSeed(runId, identity);
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_WORKBOOK_SEED",
                value.schemaVersion(),
                value);
    }

    /** Reads one verified retention projection and its latest signed lifecycle event. */
    @GetMapping("/runs/{runId}/retention")
    public IntegrationEnvelope<ScenarioRehearsalRetentionState>
    retention(
            @PathVariable String runId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_RETENTION_READ);
        ScenarioRehearsalRetentionState value =
                retention.find(runId, identity);
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_RETENTION_STATE",
                value.schemaVersion(), value);
    }

    /** Places one independent legal hold on retained Scenario aggregate evidence. */
    @PostMapping("/runs/{runId}/retention/holds")
    public IntegrationEnvelope<ScenarioRehearsalRetentionState>
    placeHold(
            @PathVariable String runId,
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_LEGAL_HOLD);
        ScenarioRehearsalLegalHoldCommand command =
                decoder.decodeLegalHoldCommand(
                        request, identity);
        ScenarioRehearsalRetentionState value =
                retention.placeHold(
                        runId, command, identity);
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_RETENTION_STATE",
                value.schemaVersion(), value);
    }

    /** Releases one exact legal hold without changing other active holds. */
    @PostMapping("/runs/{runId}/retention/hold-releases")
    public IntegrationEnvelope<ScenarioRehearsalRetentionState>
    releaseHold(
            @PathVariable String runId,
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_LEGAL_HOLD);
        ScenarioRehearsalLegalHoldCommand command =
                decoder.decodeLegalHoldCommand(
                        request, identity);
        ScenarioRehearsalRetentionState value =
                retention.releaseHold(
                        runId, command, identity);
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_RETENTION_STATE",
                value.schemaVersion(), value);
    }

    /** Deletes eligible aggregate evidence and returns the signed deletion proof state. */
    @PostMapping("/runs/{runId}/retention/purge")
    public IntegrationEnvelope<ScenarioRehearsalRetentionState>
    purge(
            @PathVariable String runId,
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_RETENTION_ADMIN);
        ScenarioRehearsalPurgeCommand command =
                decoder.decodePurgeCommand(
                        request, identity);
        ScenarioRehearsalRetentionState value =
                retention.purge(runId, command, identity);
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_RETENTION_STATE",
                value.schemaVersion(), value);
    }

    private IntegrationRequestContext authenticateWrite(
            HttpHeaders headers) {
        return authenticator.authenticate(
                headers,
                IntegrationOperation.MIRROR_SCENARIO_ARTIFACT_WRITE);
    }
}
