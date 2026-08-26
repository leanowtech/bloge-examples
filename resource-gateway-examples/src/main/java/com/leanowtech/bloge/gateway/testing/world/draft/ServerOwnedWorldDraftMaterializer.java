package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.testing.world.BlogeFragmentRef;
import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.testing.world.WorldSlice;
import com.leanowtech.bloge.gateway.testing.world.WorldFragmentTestKit;

import java.util.ArrayList;
import java.util.List;

/** Concrete materializer that creates and admits an executable World slice. */
public final class ServerOwnedWorldDraftMaterializer implements WorldDraftMaterializer {
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private final WorldDraftWorldRuntimeAdmitter runtime;

    public ServerOwnedWorldDraftMaterializer(WorldDraftWorldRuntimeAdmitter runtime) {
        if (runtime == null) throw invalid();
        this.runtime = runtime;
    }

    @Override
    public MaterializedDraft materialize(MaterializationRequest request) {
        if (request == null || request.baseWorld().slices().isEmpty()) throw invalid();
        String candidateDigest = com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint
                .fromMaterial(java.util.Map.of("candidateId", request.candidate().candidateId())).substring(7);
        BlogeFragmentRef fragment = BlogeFragmentRef.frozen(
                "world-draft-" + candidateDigest + ".bloge",
                "graph worldDraft { transform result { value = ctx.__draft_response } }", "result");
        List<WorldSlice> slices = new ArrayList<>(request.baseWorld().slices());
        WorldSlice original = slices.get(0);
        WorldSlice.Registration registration = new WorldSlice.Registration(
                original.tenantId(), original.provider(), original.apiVersion(),
                original.logicalContractId(), original.contractFingerprint(),
                original.bindingFingerprint(), true);
        slices.set(0, WorldSlice.register(registration, original.contract(), original.binding(),
                fragment, original.worldStateSpec()));
        ResourceWorldModel draftWorld = new ResourceWorldModel(request.baseWorld().worldModelId(),
                request.baseWorld().tenantId(), Math.addExact(request.baseWorld().revision(), 1), slices);
        WorldDraftRule rule = new WorldDraftRule(request.candidate().schemaFingerprint(),
                request.redactedPayload().ref().requestFingerprint(),
                request.redactedPayload().ref().responseFingerprint(), new WorldDraftFragmentRef(fragment),
                request.redactedPayload().ref());
        ResourceWorldModel admitted;
        try {
            admitted = runtime.admit(draftWorld, rule, request.redactedRequest(),
                    request.redactedResponse(), request.access());
        } catch (WorldDraftCandidateException failure) { throw failure; }
        catch (RuntimeException failure) { throw invalid(); }
        if (admitted == null || !admitted.fingerprint().equals(draftWorld.fingerprint())) throw invalid();
        return new MaterializedDraft(request.candidate(), admitted, rule, java.util.Optional.empty(),
                WorldDraftProvenance.of(request.candidate(), rule), false);
    }

    /** BLOGE-backed admission that compiles and executes the generated pure fragment. */
    public static WorldDraftWorldRuntimeAdmitter bloge(WorldFragmentTestKit kit) {
        if (kit == null) throw invalid();
        return (world, rule, request, response, access) -> {
            if (world == null || rule == null || access == null || rule.fragment() == null
                    || !rule.inputFingerprint().equals(com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint.of(MAPPER, request))
                    || !rule.responseFingerprint().equals(com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint.of(MAPPER, response))) throw invalid();
            if (world.slices().stream().noneMatch(slice -> slice.behavior().equals(rule.fragment().blogeFragment()))) {
                throw invalid();
            }
            java.util.Map<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("__draft_response", response);
            Object executed = kit.execute(rule.fragment().blogeFragment(), envelope);
            if (!(executed instanceof java.util.Map<?, ?> result)
                    || !rule.responseFingerprint().equals(com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint.of(MAPPER, result.get("value")))) {
                throw invalid();
            }
            return world;
        };
    }

    private static WorldDraftCandidateException invalid() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.MATERIALIZATION_INVALID);
    }
}
