package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceDescriptor;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaCompatibility;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Proven binding of one concrete provider/API version to a version-independent logical contract.
 *
 * <p>The constructor is deliberately private: a binding exists only after the provider output has
 * structurally satisfied the logical output shape. Caller-supplied fingerprints are never trusted.</p>
 */
public final class LogicalResourceBinding {
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private final String provider;
    private final String apiVersion;
    private final String resourceId;
    private final String descriptorFingerprint;
    private final String providerOutputFingerprint;
    private final String contractId;
    private final String contractFingerprint;

    private LogicalResourceBinding(String provider,
                                                 String apiVersion,
                                                 String resourceId,
                                                 String descriptorFingerprint,
                                                 String providerOutputFingerprint,
                                                 String contractId,
                                                 String contractFingerprint) {
        this.provider = provider;
        this.apiVersion = apiVersion;
        this.resourceId = resourceId;
        this.descriptorFingerprint = descriptorFingerprint;
        this.providerOutputFingerprint = providerOutputFingerprint;
        this.contractId = contractId;
        this.contractFingerprint = contractFingerprint;
    }

    /**
     * Registers a concrete resource implementation after a fail-closed structural proof.
     *
     * @throws LogicalResourceContractException when identity, semantics, or schema compatibility
     *         cannot be proven without inspecting payload data
     */
    public static LogicalResourceBinding bind(String provider,
                                                            String apiVersion,
                                                            ResourceDesignContract providerDesign,
                                                            VisualResourceDescriptor descriptor,
                                                            LogicalResourceContract contract) {
        if (invalid(provider) || invalid(apiVersion) || providerDesign == null || descriptor == null
                || contract == null || !providerDesign.resourceId().equals(descriptor.resourceId())) {
            throw LogicalResourceContractException.implementationUnknown();
        }
        if (contract.semantics().requiresReview()) {
            throw LogicalResourceContractException.confirmationRequired();
        }
        if (LogicalResourceContractCompatibility.schemaKnowledge(providerDesign.responseSchema())
                == LogicalResourceContractCompatibility.Knowledge.UNKNOWN
                || LogicalResourceContractCompatibility.schemaKnowledge(contract.internalOutputShape())
                == LogicalResourceContractCompatibility.Knowledge.UNKNOWN) {
            throw LogicalResourceContractException.implementationUnknown();
        }
        if (VisualSchemaCompatibility.schemaCompatibilityIssue(
                providerDesign.responseSchema().schema(), contract.internalOutputShape().schema()).isPresent()) {
            throw LogicalResourceContractException.implementationIncompatible();
        }
        String outputFingerprint = VisualBundleFingerprint.fromMaterial(Map.of(
                "format", providerDesign.responseSchema().format(),
                "version", providerDesign.responseSchema().version(),
                "schema", LogicalResourceContractCanonicalizer.canonicalValue(
                        providerDesign.responseSchema().schema())));
        return new LogicalResourceBinding(provider.trim(), apiVersion.trim(), descriptor.resourceId().trim(),
                LogicalResourceContractProjector.descriptorFingerprint(descriptor), outputFingerprint,
                contract.contractId(), contract.contractFingerprint());
    }

    /**
     * Restores an already-proven binding from a catalog record.
     *
     * <p>This factory does not establish provider/schema compatibility; that proof happened before
     * persistence. The catalog must verify its canonical record seal and nested identity before
     * calling this method. It still validates every persisted identity and fingerprint, and binds
     * the restored proof to the exact decoded logical contract.</p>
     */
    public static LogicalResourceBinding restorePersisted(String provider,
                                                           String apiVersion,
                                                           String resourceId,
                                                           String descriptorFingerprint,
                                                           String providerOutputFingerprint,
                                                           String contractId,
                                                           String contractFingerprint,
                                                           LogicalResourceContract exactContract) {
        if (invalid(provider) || invalid(apiVersion) || invalid(resourceId)
                || !validFingerprint(descriptorFingerprint)
                || !validFingerprint(providerOutputFingerprint)
                || invalid(contractId) || !validFingerprint(contractFingerprint)
                || exactContract == null || !contractId.trim().equals(exactContract.contractId())
                || !validFingerprint(exactContract.contractFingerprint())
                || !contractFingerprint.equals(exactContract.contractFingerprint())) {
            throw LogicalResourceContractException.implementationUnknown();
        }
        return new LogicalResourceBinding(provider.trim(), apiVersion.trim(), resourceId.trim(),
                descriptorFingerprint, providerOutputFingerprint, contractId.trim(), contractFingerprint);
    }

    public String provider() {
        return provider;
    }

    public String apiVersion() {
        return apiVersion;
    }

    public String resourceId() {
        return resourceId;
    }

    public String descriptorFingerprint() {
        return descriptorFingerprint;
    }

    public String providerOutputFingerprint() {
        return providerOutputFingerprint;
    }

    public String contractId() {
        return contractId;
    }

    public String contractFingerprint() {
        return contractFingerprint;
    }

    private static boolean invalid(String value) {
        return value == null || value.isBlank() || value.length() > 256;
    }

    private static boolean validFingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }
}
