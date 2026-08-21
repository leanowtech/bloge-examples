#!/usr/bin/env node

import { createHash, createPrivateKey, createPublicKey, sign, verify } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import {
  canonicalize,
  documentFingerprint,
  parseStrictJson,
  rawSha256
} from "../canonicalization/reference-fingerprint.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const OUTPUT = resolve(HERE, "signed-review-count-guard");
const ZERO = "0".repeat(64);
const SEED = Buffer.from("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "hex");
const PKCS8_PREFIX = Buffer.from("302e020100300506032b657004220420", "hex");
const privateKey = createPrivateKey({
  key: Buffer.concat([PKCS8_PREFIX, SEED]),
  format: "der",
  type: "pkcs8"
});
const publicKey = createPublicKey(privateKey);
const publicKeyDer = publicKey.export({format: "der", type: "spki"});
const publicKeyBase64Url = publicKeyDer.subarray(publicKeyDer.length - 32).toString("base64url");

function typed(kind, value) {
  return {kind, algorithm: "SHA-256", value};
}

function syntheticRaw(hexByte) {
  return typed("RAW_BYTES", `sha256:${hexByte.repeat(64)}`);
}

function domainDigest(domain, value) {
  return createHash("sha256")
    .update(Buffer.from(domain, "ascii"))
    .update(Buffer.from([0]))
    .update(Buffer.from(canonicalize(value), "utf8"))
    .digest();
}

function finalizeDocument(document, selfField, domain) {
  document[selfField] = typed("CANONICAL_DOCUMENT", `sha256:${ZERO}`);
  const result = documentFingerprint(domain, document, selfField);
  document[selfField] = typed("CANONICAL_DOCUMENT", result.fingerprint);
  return Buffer.from(canonicalize(document), "utf8");
}

function materialize(name, bytes) {
  writeFileSync(resolve(OUTPUT, name), bytes);
  return typed("RAW_BYTES", rawSha256(bytes));
}

function generate() {
  mkdirSync(OUTPUT, {recursive: true});

  const snapshot = {
    schemaVersion: "capability-studio.reviewer-revocation-snapshot.v1",
    snapshotId: "revocation:gate-a-fixture-1",
    issuer: "issuer:gate-a-fixture",
    revision: 1,
    issuedAt: "2026-08-21T09:00:00Z",
    validUntil: "2026-08-22T09:00:00Z",
    revokedKeyIds: [],
    revokedAuthorityIds: []
  };
  const snapshotRaw = materialize(
    "reviewer-revocation-snapshot.json",
    finalizeDocument(snapshot, "reviewerRevocationSnapshotFingerprint", "RG-CS-REVIEWER-REVOCATION-SNAPSHOT-v1")
  );

  const admissionProfile = syntheticRaw("1");
  const candidate = syntheticRaw("2");
  const reviewerArtifact = syntheticRaw("3");
  const reviewedMaterialRoot = typed("AGGREGATE_COMMITMENT", `sha256:${"4".repeat(64)}`);

  const policy = {
    schemaVersion: "capability-studio.reviewer-trust-policy.v1",
    policyId: "policy:gate-a-fixture-1",
    issuer: "issuer:gate-a-fixture",
    revision: 1,
    signatureAlgorithm: "Ed25519",
    allowedAuthorities: ["authority:gate-a-fixture-review"],
    allowedKeys: [{keyId: "key:gate-a-fixture-1", publicKeyBase64Url}],
    reviewScope: "GATE_A_ACCEPTANCE",
    candidateSubject: candidate,
    admissionProfileRawFingerprint: admissionProfile,
    requiredCheckIds: [
      "INDEPENDENCE",
      "PROTOCOL_CONFORMANCE",
      "MATERIAL_CLOSURE",
      "TIME_ORDERING",
      "REPLAY_SEMANTICS",
      "ADMISSION_BINDING"
    ],
    notBefore: "2026-08-21T08:00:00Z",
    validUntil: "2026-08-22T09:00:00Z",
    revocationSnapshotRawFingerprint: snapshotRaw
  };
  materialize(
    "reviewer-trust-policy.json",
    finalizeDocument(policy, "reviewerTrustPolicyFingerprint", "RG-CS-REVIEWER-TRUST-POLICY-v1")
  );

  const reviewBody = {
    schemaVersion: "capability-studio.review-body.v1",
    gateId: "GATE-A",
    gateRevision: 1,
    reviewedMaterialRootFingerprint: reviewedMaterialRoot,
    reviewChecks: [
      {checkId: "INDEPENDENCE", status: "FINDING"},
      {checkId: "PROTOCOL_CONFORMANCE", status: "PASS"},
      {checkId: "MATERIAL_CLOSURE", status: "PASS"},
      {checkId: "TIME_ORDERING", status: "PASS"},
      {checkId: "REPLAY_SEMANTICS", status: "PASS"},
      {checkId: "ADMISSION_BINDING", status: "PASS"}
    ],
    findings: [{
      findingId: "F-001",
      checkId: "INDEPENDENCE",
      severity: "P0",
      status: "OPEN",
      reasonCode: "REVIEW_COUNT_CONSISTENCY_REJECTED",
      detail: "Fixture intentionally under-reports one open P0 finding."
    }],
    openP0: 0,
    openP1: 0,
    skippedCount: 0,
    reviewedAt: "2026-08-21T10:00:00Z"
  };
  const reviewBodyBytes = finalizeDocument(reviewBody, "reviewBodyFingerprint", "RG-CS-REVIEW-BODY-v1");
  const reviewBodyRaw = materialize("review-body.json", reviewBodyBytes);

  const envelope = {
    schemaVersion: "capability-studio.reviewer-authority-envelope.v1",
    authorityId: "authority:gate-a-fixture-review",
    issuer: "issuer:gate-a-fixture",
    keyId: "key:gate-a-fixture-1",
    signatureAlgorithm: "Ed25519",
    gateId: "GATE-A",
    gateRevision: 1,
    admissionProfileRawFingerprint: admissionProfile,
    candidateRawFingerprint: candidate,
    reviewerArtifactRawFingerprint: reviewerArtifact,
    reviewBodyRawFingerprint: reviewBodyRaw,
    reviewedMaterialRootFingerprint: reviewedMaterialRoot,
    openP0: 0,
    openP1: 0,
    skippedCount: 0,
    reviewScope: "GATE_A_ACCEPTANCE",
    reviewedAt: "2026-08-21T10:00:00Z",
    validUntil: "2026-08-22T09:00:00Z",
    revocationSnapshotRawFingerprint: snapshotRaw
  };
  const signaturePayload = domainDigest("RG-CS-REVIEW-ENVELOPE-SIGNING-v1", envelope);
  envelope.signature = sign(null, signaturePayload, privateKey).toString("base64url");
  const envelopeBytes = finalizeDocument(envelope, "envelopeFingerprint", "RG-CS-REVIEW-ENVELOPE-v1");
  materialize("reviewer-authority-envelope.json", envelopeBytes);

  const expectation = {
    fixtureId: "SIGNED_REVIEW_OPEN_P0_UNDER_REPORTED",
    structuralValidation: "PASS",
    signatureValidation: "PASS",
    expectedAdmissionConclusion: "FAIL",
    expectedReasonCode: "REVIEW_COUNT_CONSISTENCY_REJECTED",
    expectedExitCode: 2,
    fixtureOnlyPublicKeyBase64Url: publicKeyBase64Url
  };
  writeFileSync(resolve(OUTPUT, "expectation.json"), Buffer.from(canonicalize(expectation), "utf8"));
}

function verifyGenerated() {
  const policy = parseStrictJson(readFileSync(resolve(OUTPUT, "reviewer-trust-policy.json"), "utf8"));
  const bodyBytes = readFileSync(resolve(OUTPUT, "review-body.json"));
  const body = parseStrictJson(bodyBytes.toString("utf8"));
  const envelope = parseStrictJson(readFileSync(resolve(OUTPUT, "reviewer-authority-envelope.json"), "utf8"));
  const snapshotBytes = readFileSync(resolve(OUTPUT, "reviewer-revocation-snapshot.json"));
  const snapshot = parseStrictJson(snapshotBytes.toString("utf8"));

  const fingerprints = [
    [policy, "reviewerTrustPolicyFingerprint", "RG-CS-REVIEWER-TRUST-POLICY-v1"],
    [snapshot, "reviewerRevocationSnapshotFingerprint", "RG-CS-REVIEWER-REVOCATION-SNAPSHOT-v1"],
    [body, "reviewBodyFingerprint", "RG-CS-REVIEW-BODY-v1"],
    [envelope, "envelopeFingerprint", "RG-CS-REVIEW-ENVELOPE-v1"]
  ];
  for (const [document, selfField, domain] of fingerprints) {
    const actual = documentFingerprint(domain, document, selfField).fingerprint;
    if (document[selfField].value !== actual) {
      throw new Error(`${selfField} drifted`);
    }
  }

  if (policy.allowedKeys[0].publicKeyBase64Url !== publicKeyBase64Url) {
    throw new Error("fixture public key drifted");
  }
  if (envelope.reviewBodyRawFingerprint.value !== rawSha256(bodyBytes)) {
    throw new Error("review body raw fingerprint drifted");
  }
  if (envelope.revocationSnapshotRawFingerprint.value !== rawSha256(snapshotBytes)) {
    throw new Error("revocation snapshot raw fingerprint drifted");
  }
  const claims = structuredClone(envelope);
  delete claims.signature;
  delete claims.envelopeFingerprint;
  const digest = domainDigest("RG-CS-REVIEW-ENVELOPE-SIGNING-v1", claims);
  if (!verify(null, digest, publicKey, Buffer.from(envelope.signature, "base64url"))) {
    throw new Error("Ed25519 fixture signature did not verify");
  }
  const derivedOpenP0 = body.findings.filter(
    (finding) => finding.severity === "P0" && finding.status === "OPEN"
  ).length;
  if (derivedOpenP0 !== 1 || body.openP0 !== 0 || envelope.openP0 !== 0) {
    throw new Error("fixture no longer represents the intended count inconsistency");
  }
  process.stdout.write(JSON.stringify({status: "PASS", signature: "VALID", derivedOpenP0}) + "\n");
}

generate();
verifyGenerated();
