package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable catalog and language snapshot shared by reference, preview, gate and compose.
 *
 * <p>The fingerprint includes the authenticated scope but the scope identifiers themselves are not
 * exposed through the reference response. Downstream compilation must use {@link #operators()} and
 * {@link #functions()} rather than reading the live catalog again.</p>
 *
 * @param schemaVersion context schema
 * @param languageVersion BLOGE language version
 * @param compilerProfile Resource Gateway compiler profile
 * @param supportedRootKinds accepted top-level definitions
 * @param libraries selected immutable library identities
 * @param operators complete authorized operator snapshot
 * @param functions complete authorized function snapshot
 * @param referenceVersion static bundle fingerprint
 * @param fingerprint canonical context fingerprint
 * @param scope authenticated scope used internally to validate projected drafts; never serialized
 */
public record DslAuthoringContext(
        String schemaVersion,
        String languageVersion,
        String compilerProfile,
        Set<String> supportedRootKinds,
        List<LibrarySnapshot> libraries,
        Map<String, OperatorDefinition> operators,
        Map<String, OperatorLibrary.BuiltInFunction> functions,
        String referenceVersion,
        String fingerprint,
        @JsonIgnore AuthoringScope scope
) {
    /** Selected library identity included in the authoring-context fingerprint. */
    public record LibrarySnapshot(String libraryId, String version, String contractFingerprint) { }

    /**
     * Internal scope carried with the frozen context so projection validation cannot fall back to
     * demo identities. It is intentionally absent from all MCP serialization surfaces.
     */
    public record AuthoringScope(String tenantId, String projectId, String environmentId) {
        /** Rejects incomplete scope material before it can weaken policy validation. */
        public AuthoringScope {
            if (tenantId == null || tenantId.isBlank()
                    || projectId == null || projectId.isBlank()
                    || environmentId == null || environmentId.isBlank()) {
                throw new IllegalArgumentException("DSL authoring scope must be complete");
            }
        }
    }

    /** Freezes the resolved catalog so later registry replacement cannot alter this compilation. */
    public DslAuthoringContext {
        supportedRootKinds = Set.copyOf(supportedRootKinds);
        libraries = List.copyOf(libraries);
        operators = Map.copyOf(operators);
        functions = Map.copyOf(functions);
        if (scope == null) throw new IllegalArgumentException("DSL authoring scope is required");
    }
}
