#!/usr/bin/env node

import { createHash, createPrivateKey, sign } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { canonicalize, documentFingerprint, rawSha256 } from "../canonicalization/reference-fingerprint.mjs";

const [directory, mode] = process.argv.slice(2);
const MODES = [
  "baseline",
  "underreport",
  "underreport-open-p1",
  "underreport-skipped-count",
  "check-mismatch",
  "duplicate-finding-id",
  "finding-order-drift",
  "candidate-binding-drift",
  "body-envelope-reviewed-at-drift",
  "revocation-issued-after-review",
  "expired-review",
  "expired-policy",
  "expired-revocation"
];
if (!directory || !MODES.includes(mode)) {
  process.stderr.write(`usage: prepare-signed-review-count.mjs <material-root> ${MODES.join("|")}\n`);
  process.exit(64);
}

const root = resolve(directory);
const bodyPath = resolve(root, "review/body.json");
const envelopePath = resolve(root, "review/envelope.json");
const body = JSON.parse(readFileSync(bodyPath, "utf8"));
const envelope = JSON.parse(readFileSync(envelopePath, "utf8"));
const seed = Buffer.from("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "hex");
const privateKey = createPrivateKey({
  key: Buffer.concat([Buffer.from("302e020100300506032b657004220420", "hex"), seed]),
  format: "der",
  type: "pkcs8"
});

function typed(kind, value) {
  return {kind, algorithm: "SHA-256", value};
}

function finalize(document, field, domain) {
  document[field] = null;
  document[field] = typed("CANONICAL_DOCUMENT", documentFingerprint(domain, document, field).fingerprint);
  return Buffer.from(canonicalize(document), "utf8");
}

function signingDigest(value) {
  const claims = structuredClone(value);
  delete claims.signature;
  delete claims.envelopeFingerprint;
  return createHash("sha256")
    .update(Buffer.from("RG-CS-REVIEW-ENVELOPE-SIGNING-v1", "ascii"))
    .update(Buffer.from([0]))
    .update(Buffer.from(canonicalize(claims), "utf8"))
    .digest();
}

if (mode !== "body-envelope-reviewed-at-drift") {
  body.reviewedAt = "2026-08-21T09:30:00Z";
  envelope.reviewedAt = "2026-08-21T09:30:00Z";
}

if (mode === "check-mismatch") {
  body.reviewChecks[0].status = "PASS";
}
if (mode === "duplicate-finding-id") {
  body.findings.push({
    ...structuredClone(body.findings[0]),
    status: "RESOLVED",
    detail: "A second signed finding deliberately reuses F-001."
  });
}
if (mode === "finding-order-drift") {
  body.findings = [
    {...structuredClone(body.findings[0]), findingId: "F-002", detail: "Second finding deliberately appears first."},
    {...structuredClone(body.findings[0]), findingId: "F-001", detail: "First finding deliberately appears second."}
  ];
}
if (mode === "body-envelope-reviewed-at-drift") {
  body.reviewedAt = "2026-08-21T10:30:00Z";
}
if (mode === "underreport-open-p1") {
  body.findings[0].severity = "P1";
}
if (mode === "underreport-skipped-count") {
  body.reviewChecks[1].status = "SKIPPED";
}
if (["expired-review", "expired-policy", "expired-revocation"].includes(mode)) {
  body.reviewedAt = "2026-08-21T09:00:00Z";
  envelope.reviewedAt = "2026-08-21T09:00:00Z";
  envelope.validUntil = "2026-08-21T09:31:00Z";
}

const openP0 = mode === "underreport"
  ? 0
  : body.findings.filter((finding) => finding.severity === "P0" && finding.status === "OPEN").length;
const openP1 = body.findings.filter((finding) => finding.severity === "P1" && finding.status === "OPEN").length;
const skippedCount = body.reviewChecks.filter((check) => check.status === "SKIPPED").length;
body.openP0 = openP0;
body.openP1 = mode === "underreport-open-p1" ? 0 : openP1;
body.skippedCount = mode === "underreport-skipped-count" ? 0 : skippedCount;
const bodyBytes = finalize(body, "reviewBodyFingerprint", "RG-CS-REVIEW-BODY-v1");
writeFileSync(bodyPath, bodyBytes);

if (mode === "revocation-issued-after-review") {
  const revocationPath = resolve(root, "review/revocation.json");
  const policyPath = resolve(root, "review/policy.json");
  const revocation = JSON.parse(readFileSync(revocationPath, "utf8"));
  revocation.issuedAt = "2026-08-21T11:00:00Z";
  const revocationBytes = finalize(
    revocation,
    "reviewerRevocationSnapshotFingerprint",
    "RG-CS-REVIEWER-REVOCATION-SNAPSHOT-v1"
  );
  writeFileSync(revocationPath, revocationBytes);
  const revocationRef = typed("RAW_BYTES", rawSha256(revocationBytes));
  const policy = JSON.parse(readFileSync(policyPath, "utf8"));
  policy.revocationSnapshotRawFingerprint = revocationRef;
  const policyBytes = finalize(
    policy,
    "reviewerTrustPolicyFingerprint",
    "RG-CS-REVIEWER-TRUST-POLICY-v1"
  );
  writeFileSync(policyPath, policyBytes);
  envelope.revocationSnapshotRawFingerprint = revocationRef;
}
if (mode === "expired-policy") {
  const policyPath = resolve(root, "review/policy.json");
  const policy = JSON.parse(readFileSync(policyPath, "utf8"));
  policy.validUntil = "2026-08-21T09:31:00Z";
  const policyBytes = finalize(
    policy,
    "reviewerTrustPolicyFingerprint",
    "RG-CS-REVIEWER-TRUST-POLICY-v1"
  );
  writeFileSync(policyPath, policyBytes);
}
if (mode === "expired-revocation") {
  const revocationPath = resolve(root, "review/revocation.json");
  const policyPath = resolve(root, "review/policy.json");
  const revocation = JSON.parse(readFileSync(revocationPath, "utf8"));
  revocation.validUntil = "2026-08-21T09:31:00Z";
  const revocationBytes = finalize(
    revocation,
    "reviewerRevocationSnapshotFingerprint",
    "RG-CS-REVIEWER-REVOCATION-SNAPSHOT-v1"
  );
  writeFileSync(revocationPath, revocationBytes);
  const revocationRef = typed("RAW_BYTES", rawSha256(revocationBytes));
  const policy = JSON.parse(readFileSync(policyPath, "utf8"));
  policy.revocationSnapshotRawFingerprint = revocationRef;
  const policyBytes = finalize(
    policy,
    "reviewerTrustPolicyFingerprint",
    "RG-CS-REVIEWER-TRUST-POLICY-v1"
  );
  writeFileSync(policyPath, policyBytes);
  envelope.revocationSnapshotRawFingerprint = revocationRef;
}
if (mode === "candidate-binding-drift") {
  envelope.candidateRawFingerprint = typed("RAW_BYTES", `sha256:${"5".repeat(64)}`);
}

envelope.openP0 = openP0;
envelope.openP1 = body.openP1;
envelope.skippedCount = body.skippedCount;
envelope.reviewBodyRawFingerprint = typed("RAW_BYTES", rawSha256(bodyBytes));
envelope.signature = sign(null, signingDigest(envelope), privateKey).toString("base64url");
const envelopeBytes = finalize(envelope, "envelopeFingerprint", "RG-CS-REVIEW-ENVELOPE-v1");
writeFileSync(envelopePath, envelopeBytes);

process.stdout.write(JSON.stringify({status: "PASS", mode, openP0, openP1: body.openP1, skippedCount: body.skippedCount}) + "\n");
