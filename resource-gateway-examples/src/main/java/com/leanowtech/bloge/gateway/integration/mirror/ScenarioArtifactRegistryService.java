package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Authenticated application boundary for registering and resolving ScenarioPack artifacts.
 *
 * <p>Registration order is assertion, optional checkpoint, case, then pack. Every parent revision
 * is accepted only after all referenced children already exist with the exact fingerprint in the
 * same full enterprise scope. Because the registry is append-only, this creates a durable closure
 * without a mutable "latest" pointer.</p>
 */
@Service
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public class ScenarioArtifactRegistryService {
    private final ScenarioArtifactRepository artifacts;
    private final ObjectMapper mapper;
    private final MirrorSessionCheckpointIntegrityService checkpointIntegrity;
    private final Clock clock;

    /** Creates the authenticated registry boundary with the trusted server clock. */
    @Autowired
    public ScenarioArtifactRegistryService(
            ScenarioArtifactRepository artifacts,
            ObjectMapper mapper,
            MirrorSessionCheckpointIntegrityService checkpointIntegrity) {
        this(artifacts, mapper, checkpointIntegrity, Clock.systemUTC());
    }

    /** Full constructor for deterministic application-service tests. */
    public ScenarioArtifactRegistryService(
            ScenarioArtifactRepository artifacts,
            ObjectMapper mapper,
            MirrorSessionCheckpointIntegrityService checkpointIntegrity,
            Clock clock) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.checkpointIntegrity = Objects.requireNonNull(
                checkpointIntegrity, "checkpointIntegrity");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Registers one independently sealed handling assertion. */
    @Transactional
    public CaseHandlingAssertion register(
            CaseHandlingAssertion assertion,
            IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope =
                MirrorPlanIntegrationService.requireMirrorIdentity(identity);
        try {
            ScenarioPackIntegrity.verifyAssertion(mapper, assertion);
            requireScope(scope, assertion.scope());
            requireRegistrationTime(
                    assertion.createdAt(), assertion.provenance(), identity);
            return artifacts.create(assertion);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (IllegalArgumentException conflict) {
            throw conflict(
                    identity,
                    "RG.MIRROR.SCENARIO_ASSERTION_REGISTRATION_INVALID",
                    "Handling assertion registration failed exact integrity or idempotency checks.");
        } catch (RuntimeException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.SCENARIO_ARTIFACT_STORE_UNAVAILABLE");
        }
    }

    /** Registers one ScenarioCase after resolving its assertion and checkpoint closure. */
    @Transactional
    public ScenarioCase register(
            ScenarioCase scenarioCase,
            IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope =
                MirrorPlanIntegrationService.requireMirrorIdentity(identity);
        try {
            ScenarioPackIntegrity.verifyCase(mapper, scenarioCase);
            requireScope(scope, scenarioCase.scope());
            requireRegistrationTime(
                    scenarioCase.createdAt(),
                    scenarioCase.provenance(),
                    identity);
            for (MirrorArtifactRef ref : scenarioCase.assertionRefs()) {
                CaseHandlingAssertion assertion = requireAssertion(scope, ref, identity);
                requireScope(scope, assertion.scope());
            }
            if (scenarioCase.sessionCheckpointRef() != null) {
                MirrorSessionCheckpointBundle checkpoint =
                        requireCheckpoint(
                                scope,
                                scenarioCase.sessionCheckpointRef(),
                                identity);
                if (!checkpoint.checkpoint().sessionExpiresAt()
                        .isAfter(clock.instant())) {
                    throw conflict(
                            identity,
                            "RG.MIRROR.SCENARIO_CHECKPOINT_EXPIRED",
                            "Scenario checkpoint expired before case registration.");
                }
            }
            return artifacts.create(scenarioCase);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (IllegalArgumentException conflict) {
            throw conflict(
                    identity,
                    "RG.MIRROR.SCENARIO_CASE_REGISTRATION_INVALID",
                    "ScenarioCase registration failed exact closure or idempotency checks.");
        } catch (RuntimeException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.SCENARIO_ARTIFACT_STORE_UNAVAILABLE");
        }
    }

    /** Registers one ScenarioPack only after its complete scenario-artifact closure exists. */
    @Transactional
    public ScenarioPack register(
            ScenarioPack pack, IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope =
                MirrorPlanIntegrationService.requireMirrorIdentity(identity);
        try {
            ScenarioPackIntegrity.verify(mapper, pack);
            requireScope(scope, pack.scope());
            requireRegistrationTime(
                    pack.createdAt(), pack.provenance(), identity);
            Set<MirrorArtifactRef> admittedAssertions =
                    Set.copyOf(pack.assertionRefs());
            Set<MirrorArtifactRef> checkpoints = new HashSet<>();
            Map<MirrorArtifactRef, MirrorPlan.ExecutionServices> servicesByPlan =
                    new HashMap<>();
            for (MirrorArtifactRef caseRef : pack.caseRefs()) {
                ScenarioCase scenarioCase =
                        requireCase(scope, caseRef, identity);
                requireScope(scope, scenarioCase.scope());
                if (!pack.targetCapabilityRef().equals(
                        scenarioCase.targetCapabilityRef())
                        || !admittedAssertions.containsAll(
                        scenarioCase.assertionRefs())) {
                    throw conflict(
                            identity,
                            "RG.MIRROR.SCENARIO_PACK_CLOSURE_INVALID",
                            "ScenarioPack target or assertion closure is inconsistent.");
                }
                MirrorPlan.ExecutionServices previous =
                        servicesByPlan.putIfAbsent(
                                scenarioCase.mirrorPlanRef(),
                                scenarioCase.executionServices());
                if (previous != null
                        && !previous.equals(
                        scenarioCase.executionServices())) {
                    throw conflict(
                            identity,
                            "RG.MIRROR.SCENARIO_EXECUTION_SERVICES_DRIFT",
                            "Cases sharing a MirrorPlan must share execution services.");
                }
                if (scenarioCase.sessionCheckpointRef() != null) {
                    if (!checkpoints.add(
                            scenarioCase.sessionCheckpointRef())) {
                        throw conflict(
                                identity,
                                "RG.MIRROR.SCENARIO_SESSION_NOT_ISOLATED",
                                "Each stateful case requires a unique Session checkpoint.");
                    }
                    MirrorSessionCheckpointBundle checkpoint =
                            requireCheckpoint(
                                    scope,
                                    scenarioCase.sessionCheckpointRef(),
                                    identity);
                    if (!pack.stateModelRefs().contains(
                            checkpoint.checkpoint().stateModelRef())
                            || !pack.writeEffectRefs().equals(
                            checkpoint.checkpoint().writeEffectRefs())) {
                        throw conflict(
                                identity,
                                "RG.MIRROR.SCENARIO_STATE_CLOSURE_INVALID",
                                "Checkpoint state model and write effects must match the pack.");
                    }
                }
            }
            for (MirrorArtifactRef assertionRef : pack.assertionRefs()) {
                requireAssertion(scope, assertionRef, identity);
            }
            return artifacts.create(pack);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (IllegalArgumentException conflict) {
            throw conflict(
                    identity,
                    "RG.MIRROR.SCENARIO_PACK_REGISTRATION_INVALID",
                    "ScenarioPack registration failed exact closure or idempotency checks.");
        } catch (RuntimeException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.SCENARIO_ARTIFACT_STORE_UNAVAILABLE");
        }
    }

    /** Registers one signed payload-free checkpoint for later stateful case binding. */
    @Transactional
    public MirrorSessionCheckpointBundle register(
            MirrorSessionCheckpointBundle checkpoint,
            IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope =
                MirrorPlanIntegrationService.requireMirrorIdentity(identity);
        if (checkpoint == null
                || checkpointIntegrity.verify(checkpoint)
                != MirrorSessionCheckpointIntegrityService.Verification.VERIFIED
                || !scope.equals(checkpoint.checkpoint().scope())
                || !checkpoint.checkpoint().sessionExpiresAt()
                .isAfter(clock.instant())) {
            throw conflict(
                    identity,
                    "RG.MIRROR.SCENARIO_CHECKPOINT_REGISTRATION_INVALID",
                    "Checkpoint registration requires a live, signed, same-scope bundle.");
        }
        try {
            return artifacts.create(checkpoint);
        } catch (IllegalArgumentException conflict) {
            throw conflict(
                    identity,
                    "RG.MIRROR.SCENARIO_CHECKPOINT_REGISTRATION_INVALID",
                    "Checkpoint registration failed exact integrity or idempotency checks.");
        } catch (RuntimeException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.SCENARIO_ARTIFACT_STORE_UNAVAILABLE");
        }
    }

    /** Resolves one exact pack in the authenticated scope. */
    public ScenarioPack findPack(
            String packId,
            long revision,
            String fingerprint,
            IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope =
                MirrorPlanIntegrationService.requireMirrorIdentity(identity);
        try {
            return requirePack(
                    scope,
                    new MirrorArtifactRef(
                            "SCENARIO_PACK",
                            packId,
                            revision,
                            fingerprint),
                    identity);
        } catch (IllegalArgumentException invalid) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.MIRROR.SCENARIO_PACK_REF_INVALID",
                    "An exact ScenarioPack id, revision, and fingerprint are required.",
                    identity.correlationId(),
                    Map.of()));
        }
    }

    ScenarioPack requirePack(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef ref,
            IntegrationRequestContext identity) {
        ScenarioPack value;
        try {
            value = artifacts.findPack(scope, ref.id(), ref.revision())
                    .orElseThrow(() -> notFound(
                            identity,
                            "RG.MIRROR.SCENARIO_PACK_NOT_FOUND",
                            "ScenarioPack was not found in the authorized scope."));
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.SCENARIO_ARTIFACT_STORE_UNAVAILABLE");
        }
        requireExact(ref, ScenarioPackIntegrity.reference(value), identity);
        return value;
    }

    ScenarioCase requireCase(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef ref,
            IntegrationRequestContext identity) {
        try {
            ScenarioCase value = artifacts.findCase(
                            scope, ref.id(), ref.revision())
                    .orElseThrow(() -> notFound(
                            identity,
                            "RG.MIRROR.SCENARIO_CASE_NOT_FOUND",
                            "ScenarioCase was not found in the authorized scope."));
            requireExact(ref, ScenarioPackIntegrity.reference(value), identity);
            return value;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.SCENARIO_ARTIFACT_STORE_UNAVAILABLE");
        }
    }

    CaseHandlingAssertion requireAssertion(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef ref,
            IntegrationRequestContext identity) {
        try {
            CaseHandlingAssertion value = artifacts.findAssertion(
                            scope, ref.id(), ref.revision())
                    .orElseThrow(() -> notFound(
                            identity,
                            "RG.MIRROR.SCENARIO_ASSERTION_NOT_FOUND",
                            "Handling assertion was not found in the authorized scope."));
            requireExact(ref, ScenarioPackIntegrity.reference(value), identity);
            return value;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.SCENARIO_ARTIFACT_STORE_UNAVAILABLE");
        }
    }

    MirrorSessionCheckpointBundle requireCheckpoint(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef ref,
            IntegrationRequestContext identity) {
        try {
            MirrorSessionCheckpointBundle value = artifacts.findCheckpoint(
                            scope, ref.id(), ref.revision())
                    .orElseThrow(() -> notFound(
                            identity,
                            "RG.MIRROR.SCENARIO_CHECKPOINT_NOT_FOUND",
                            "Session checkpoint was not found in the authorized scope."));
            requireExact(ref, ScenarioPackIntegrity.reference(value), identity);
            return value;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.SCENARIO_ARTIFACT_STORE_UNAVAILABLE");
        }
    }

    private static void requireScope(
            CapabilitySnapshot.Scope expected,
            CapabilitySnapshot.Scope actual) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "scenario artifact scope does not match authenticated scope");
        }
    }

    private void requireRegistrationTime(
            Instant createdAt,
            ArtifactProvenance provenance,
            IntegrationRequestContext identity) {
        Instant now = clock.instant();
        if (createdAt.isAfter(now)
                || provenance.approvedAt() != null
                && provenance.approvedAt().isAfter(now)
                || provenance.expiresAt() != null
                && !provenance.expiresAt().isAfter(now)
                || !provenance.revocationRef().isBlank()) {
            throw conflict(
                    identity,
                    "RG.MIRROR.SCENARIO_LIFECYCLE_INVALID",
                    "Scenario artifact is future-dated, expired, or revoked.");
        }
    }

    private static void requireExact(
            MirrorArtifactRef expected,
            MirrorArtifactRef actual,
            IntegrationRequestContext identity) {
        if (!expected.equals(actual)) {
            throw conflict(
                    identity,
                    "RG.MIRROR.SCENARIO_ARTIFACT_STALE",
                    "Scenario artifact fingerprint differs from the reviewed reference.");
        }
    }

    private static IntegrationProblemException conflict(
            IntegrationRequestContext identity,
            String code,
            String title) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException notFound(
            IntegrationRequestContext identity,
            String code,
            String title) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity, String code) {
        return new IntegrationProblemException(
                IntegrationProblem.serviceUnavailable(
                        code,
                        "Scenario artifact registry is unavailable.",
                        identity.correlationId(),
                        Map.of()));
    }
}
