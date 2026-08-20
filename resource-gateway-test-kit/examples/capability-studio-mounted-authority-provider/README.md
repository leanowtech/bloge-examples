# Mounted Capability Studio authority provider

This standalone module is the reference enterprise `ServiceLoader` provider for formal Capability
Studio stage acceptance. It mounts one deployment-owned authority bundle and delegates all three
authority dependencies, plus the binding fingerprint, to that immutable bundle.

## Build order

Install the Test Kit first, then build this module:

```bash
mvn -f resource-gateway-test-kit/pom.xml clean install
mvn -f resource-gateway-test-kit/examples/capability-studio-mounted-authority-provider/pom.xml clean verify
```

The module depends on `com.leanowtech.bloge:bloge-resource-gateway-test-kit:1.0.0` and targets
Java 25. The service descriptor is packaged in the resulting JAR without relocation or merging.

## Deployment contract

The provider reads exactly one JVM system property:

```text
-Dbloge.capabilityStudio.authorityBundleRoot=/absolute/path/to/authority-bundle
```

The value is converted to an absolute, normalized path and loaded once through
`CapabilityStudioMountedAuthorityBundle.load(root, Clock.systemUTC())`. The mount is deployment
owned and must be read-only to the provider process. The bundle is expected to contain the
enterprise-owned evidence resolver, issuer pins, owner authority, and binding fingerprint. This
reference module does not create keys or signatures, call a network service, select trust roots,
or provide a demo/default fallback.

Missing or blank configuration fails closed with the payload-free code
`RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE_ROOT_REQUIRED`. Bundle load failures use the stable
payload-free code `RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE_LOAD_FAILED`; paths and authority
contents are not included in the error text or `toString()` output.

## Run the two-stage gate

After installing the Test Kit and building this provider, use the existing deployment gate. Supply
the provider JAR and every immutable dependency it needs on the provider classpath:

```bash
mvn -f resource-gateway-test-kit/pom.xml clean install
mvn -f resource-gateway-test-kit/examples/capability-studio-mounted-authority-provider/pom.xml clean package

JAVA_TOOL_OPTIONS="-Dbloge.capabilityStudio.authorityBundleRoot=/absolute/path/to/authority-bundle" \
BLOGE_EXPECTED_TEST_KIT_FINGERPRINT="<64 lowercase hex>" \
BLOGE_EXPECTED_STAGE_RESULT_FINGERPRINT="<64 lowercase hex>" \
BLOGE_EXPECTED_PROVIDER_CLASSPATH_FINGERPRINTS="<64 lowercase hex>" \
BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT="sha256:<out-of-band-bundle-fingerprint>" \
JAVA_BIN="$(command -v java)" \
resource-gateway-test-kit/scripts/verify-capability-studio-stage-acceptance.sh \
  --test-kit-jar resource-gateway-test-kit/target/bloge-resource-gateway-test-kit-1.0.0-cli.jar \
  --provider-classpath \
    "resource-gateway-test-kit/examples/capability-studio-mounted-authority-provider/target/bloge-capability-studio-mounted-authority-provider-1.0.0.jar" \
  --stage-result <stage-acceptance-result-v2.json> \
  --conformance-output <provider-conformance-report.json>
```

The JVM that runs the gate also needs the deployment property. A successful local provider
conformance result is not a formal `ACCEPTED` result by itself. The deployment runner requires the
three ordered artifact pins `BLOGE_EXPECTED_TEST_KIT_FINGERPRINT`,
`BLOGE_EXPECTED_STAGE_RESULT_FINGERPRINT`, and `BLOGE_EXPECTED_PROVIDER_CLASSPATH_FINGERPRINTS`,
plus `BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT`, an exact out-of-band pin supplied by the
deployment Authority. The binding is compared with the Provider declaration in both JVM phases.
`JAVA_TOOL_OPTIONS` is deployment-controlled and must contain only the required bundle-root
property, with no unrelated JVM option or injection. Formal acceptance additionally requires
real externally signed evidence, organizational Owner approvals, target-environment attestation,
and deployment-level egress enforcement evidence. Those authorities are intentionally outside
this repository. This documentation-only update does not change the shell implementation; until
the runner reads the three artifact-pin names, using only them is `BLOCKED` and must not be reported
as `PASS`.
