package com.leanowtech.bloge.gateway.testing.world.access;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.protocol.TestAssetReference;
import com.leanowtech.bloge.gateway.testing.protocol.TestControlEnvelope;
import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.testing.world.Scenario;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogIntegrityException;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogKind;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogRevision;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogRepository;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogDependencyAbortException;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedResourceRef;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Resolves only exact World/Scenario references after payload-free authorization. */
public final class AuthorizedWorldAssetResolver {
    public static final String GRAPH_CONTRACT_TEST = "GRAPH_CONTRACT_TEST";

    private final GovernedCatalogRepository repository;
    private final GovernedAssetReadAuthorizer authorizer;

    public AuthorizedWorldAssetResolver(GovernedCatalogRepository repository) {
        this(repository, GovernedAssetReadAuthorizer.denyAll());
    }

    public AuthorizedWorldAssetResolver(GovernedCatalogRepository repository,
                                        GovernedAssetReadAuthorizer authorizer) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.authorizer = authorizer == null ? GovernedAssetReadAuthorizer.denyAll() : authorizer;
    }

    public ResolvedWorldAssetControl resolve(TestControlEnvelope envelope,
                                             IntegrationRequestContext trustedContext) {
        validateRequest(envelope, trustedContext);
        GovernedResourceRef primaryRef = exactRef(envelope, trustedContext);
        authorize(trustedContext, primaryRef);
        try {
            ResourceWorldModel[] dependency = new ResourceWorldModel[1];
            Optional<GovernedCatalogRevision> loaded = repository.findExact(primaryRef, worldRef -> {
                ResourceWorldModel world = resolveAuthorizedWorld(worldRef, trustedContext);
                dependency[0] = world;
                return world;
            });
            if (loaded.isEmpty()) {
                throw GovernedAssetAccessException.notFound();
            }
            if (!primaryRef.equals(loaded.get().ref())) {
                throw GovernedAssetAccessException.integrity();
            }
            Object exact = loaded.get().value();
            if (primaryRef.kind() == GovernedCatalogKind.RESOURCE_WORLD_MODEL) {
                if (!(exact instanceof ResourceWorldModel world)) {
                    throw GovernedAssetAccessException.integrity();
                }
                return ResolvedWorldAssetControl.world(primaryRef, world);
            }
            if (!(exact instanceof Scenario scenario) || dependency[0] == null) {
                throw GovernedAssetAccessException.integrity();
            }
            return ResolvedWorldAssetControl.scenario(primaryRef, scenario, dependency[0]);
        } catch (GovernedAssetAccessException exception) {
            throw exception;
        } catch (GovernedCatalogDependencyAbortException exception) {
            throw fromDependencyAbort(exception);
        } catch (GovernedCatalogIntegrityException exception) {
            throw GovernedAssetAccessException.integrity();
        } catch (RuntimeException exception) {
            throw GovernedAssetAccessException.integrity();
        }
    }

    private ResourceWorldModel resolveAuthorizedWorld(GovernedResourceRef worldRef,
                                                      IntegrationRequestContext trustedContext) {
        try {
            authorize(trustedContext, worldRef);
            return repository.findExact(worldRef, ignored -> {
                throw new GovernedCatalogIntegrityException();
            }).map(entry -> exactWorldDependency(entry, worldRef))
                    .orElseThrow(GovernedAssetAccessException::notFound);
        } catch (GovernedAssetAccessException exception) {
            throw dependencyAbort(exception);
        } catch (GovernedCatalogDependencyAbortException exception) {
            throw exception;
        } catch (GovernedCatalogIntegrityException exception) {
            throw GovernedCatalogDependencyAbortException.of(
                    GovernedCatalogDependencyAbortException.Code.INTEGRITY_FAILURE);
        } catch (RuntimeException exception) {
            throw GovernedCatalogDependencyAbortException.of(
                    GovernedCatalogDependencyAbortException.Code.INTEGRITY_FAILURE);
        }
    }

    private static GovernedCatalogDependencyAbortException dependencyAbort(
            GovernedAssetAccessException exception) {
        return GovernedCatalogDependencyAbortException.of(switch (exception.code()) {
            case ACCESS_DENIED -> GovernedCatalogDependencyAbortException.Code.ACCESS_DENIED;
            case REFERENCE_NOT_FOUND -> GovernedCatalogDependencyAbortException.Code.REFERENCE_NOT_FOUND;
            case INVALID_CONTEXT, INTEGRITY_FAILURE ->
                    GovernedCatalogDependencyAbortException.Code.INTEGRITY_FAILURE;
        });
    }

    private static GovernedAssetAccessException fromDependencyAbort(
            GovernedCatalogDependencyAbortException exception) {
        return switch (exception.code()) {
            case ACCESS_DENIED -> GovernedAssetAccessException.denied();
            case REFERENCE_NOT_FOUND -> GovernedAssetAccessException.notFound();
            case INTEGRITY_FAILURE -> GovernedAssetAccessException.integrity();
        };
    }

    private ResourceWorldModel exactWorldDependency(GovernedCatalogRevision entry,
                                                    GovernedResourceRef worldRef) {
        if (entry == null || !(entry.value() instanceof ResourceWorldModel world)
                || !worldRef.tenantId().equals(world.tenantId())
                || !worldRef.id().equals(world.worldModelId())
                || worldRef.revision() != world.revision()
                || !worldRef.fingerprint().equals(world.fingerprint())) {
            throw GovernedAssetAccessException.integrity();
        }
        return world;
    }

    private static GovernedResourceRef exactRef(TestControlEnvelope envelope,
                                                IntegrationRequestContext context) {
        TestAssetReference asset = envelope.assetReference();
        GovernedCatalogKind kind = envelope.referencesScenario()
                ? GovernedCatalogKind.SCENARIO : GovernedCatalogKind.RESOURCE_WORLD_MODEL;
        try {
            return new GovernedResourceRef(context.tenantId(), kind, asset.id(), asset.revision(),
                    asset.fingerprint());
        } catch (RuntimeException exception) {
            throw GovernedAssetAccessException.notFound();
        }
    }

    private void authorize(IntegrationRequestContext context, GovernedResourceRef ref) {
        try {
            authorizer.authorize(context, ref);
        } catch (GovernedAssetAccessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw GovernedAssetAccessException.denied();
        }
    }

    private static void validateRequest(TestControlEnvelope envelope,
                                        IntegrationRequestContext context) {
        if (envelope == null || context == null
                || isBlank(context.tenantId()) || isBlank(context.organizationId())
                || isBlank(context.projectId()) || isBlank(context.environmentId())
                || isBlank(context.region()) || isBlank(context.actorType())
                || isBlank(context.actorId()) || isBlank(context.purpose())
                || isBlank(context.correlationId())) {
            throw GovernedAssetAccessException.invalidContext();
        }
        if (!Set.of("test", "staging").contains(context.environmentId().toLowerCase(Locale.ROOT))) {
            throw GovernedAssetAccessException.invalidContext();
        }
        if (!GRAPH_CONTRACT_TEST.equals(context.purpose())
                || !GRAPH_CONTRACT_TEST.equals(envelope.purpose())
                || !context.correlationId().equals(envelope.correlationId())
                || (envelope.scenario() == null) == (envelope.worldModel() == null)) {
            throw GovernedAssetAccessException.invalidContext();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
