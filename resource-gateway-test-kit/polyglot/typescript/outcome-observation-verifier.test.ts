import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { verifyFixture } from "./outcome-observation-verifier.ts";

const fixturePath = fileURLToPath(
  new URL(
    "../../../docs/schemas/resource-gateway-mirror/" +
      "authoritative-outcome-observation-stage1-v1.fixture.json",
    import.meta.url,
  ),
);

async function fixture(): Promise<Record<string, unknown>> {
  return JSON.parse(await readFile(fixturePath, "utf8")) as Record<
    string,
    unknown
  >;
}

function copy<T>(value: T): T {
  return structuredClone(value);
}

test("verifies the server-produced observation without Java dependencies", async () => {
  const result = verifyFixture(await fixture());

  assert.deepEqual(result, {
    outcome: "VERIFIED",
    reasonCode: "VERIFIED",
    observationId: "outcome-refund-boundary",
    observationFingerprint:
      "sha256:5a12fd6db51178921b8098a0e61c04cd0dfc33524c410a25cc9e77494574534d",
    unitId: "refund-boundary",
    reconciliation: "MATCH",
    keyId: "memory-ed25519:2598df82-59b9-4954-a5fd-ee08f7c50e89",
  });
});

test("rejects a producer claim that disagrees with the authority closure", async () => {
  const value = await fixture();
  const observation = copy(value.observation) as Record<string, unknown>;
  observation.reconciliation = "MISMATCH";
  value.observation = observation;

  assert.equal(
    verifyFixture(value).reasonCode,
    "OUTCOME_RECONCILIATION_DERIVATION_INVALID",
  );
});

test("rejects content-address, authority-identity, and signature tampering", async () => {
  const original = await fixture();

  const fingerprintTamper = copy(original);
  (
    fingerprintTamper.observation as Record<string, unknown>
  ).observationFingerprint =
    "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
  assert.equal(
    verifyFixture(fingerprintTamper).reasonCode,
    "OUTCOME_OBSERVATION_FINGERPRINT_INVALID",
  );

  const authorityTamper = copy(original);
  const authorityObservation = authorityTamper.observation as Record<
    string,
    unknown
  >;
  const facts = authorityObservation.authorityFacts as Array<
    Record<string, unknown>
  >;
  facts[0].subjectFingerprint =
    "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
  assert.equal(
    verifyFixture(authorityTamper).reasonCode,
    "OUTCOME_ATTRIBUTION_CLOSURE_INVALID",
  );

  const signatureTamper = copy(original);
  const signedObservation = signatureTamper.observation as Record<
    string,
    unknown
  >;
  const seal = signedObservation.observationSeal as Record<string, unknown>;
  const signature = String(seal.signature);
  seal.signature = `${signature.startsWith("A") ? "B" : "A"}${signature.slice(
    1,
  )}`;
  assert.equal(
    verifyFixture(signatureTamper).reasonCode,
    "OUTCOME_OBSERVATION_SIGNATURE_INVALID",
  );
});

test("rejects unknown fields instead of silently accepting protocol drift", async () => {
  const value = await fixture();
  (value.observation as Record<string, unknown>).producerSaysValid = true;

  assert.equal(
    verifyFixture(value).reasonCode,
    "OUTCOME_OBSERVATION_SCHEMA_INVALID",
  );
});

test("rejects a Java-noncanonical instant spelling", async () => {
  const value = await fixture();
  value.verificationTime = "2026-07-26T04:00:00.000Z";

  assert.equal(
    verifyFixture(value).reasonCode,
    "OUTCOME_OBSERVATION_VERIFICATION_TIME_INVALID",
  );
});

test("retired keys still verify historical evidence", async () => {
  const value = await fixture();
  (value.verificationKey as Record<string, unknown>).state = "RETIRED";

  assert.equal(verifyFixture(value).outcome, "VERIFIED");
});
