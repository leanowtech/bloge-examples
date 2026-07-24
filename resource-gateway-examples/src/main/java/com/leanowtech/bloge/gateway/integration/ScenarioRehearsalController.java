package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.CaseHandlingAssertion;
import com.leanowtech.bloge.gateway.integration.mirror.CompiledScenarioRehearsalPlan;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCheckpointBundle;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioArtifactRegistryService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioCase;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioPack;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalCompileRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalIntegrationService;
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
    private final IntegrationRequestAuthenticator authenticator;
    private final ScenarioArtifactRequestDecoder decoder;

    /** Creates the protected scenario transport. */
    public ScenarioRehearsalController(
            ScenarioArtifactRegistryService artifacts,
            ScenarioRehearsalIntegrationService rehearsals,
            IntegrationRequestAuthenticator authenticator,
            ScenarioArtifactRequestDecoder decoder) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.rehearsals = Objects.requireNonNull(rehearsals, "rehearsals");
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

    private IntegrationRequestContext authenticateWrite(
            HttpHeaders headers) {
        return authenticator.authenticate(
                headers,
                IntegrationOperation.MIRROR_SCENARIO_ARTIFACT_WRITE);
    }
}
