#!/usr/bin/env node

import { readFile, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const vectors = JSON.parse(await readFile(join(here, "canonicalization-vectors-v1.json"), "utf8"));

const challenge = {
  schemaVersion: "capability-studio.gate-a-canonicalization-challenge.v1",
  revision: 1,
  contract: vectors.contract,
  vectors: vectors.vectors.map(({ id, domain = null, selfField = null, sourceText }) => ({
    id,
    domain,
    selfField,
    sourceText,
  })),
  profileRejections: vectors.profileRejections.map(({ id, objectKind, domain, selfField, fingerprintKind, sourceText }) => ({
    id,
    objectKind,
    domain,
    selfField,
    fingerprintKind,
    sourceText,
  })),
};

const oracle = {
  schemaVersion: "capability-studio.gate-a-canonicalization-oracle.v1",
  revision: 1,
  results: vectors.vectors.map((vector) => vector.expectedReason
    ? { id: vector.id, status: "REJECTED", reason: vector.expectedReason }
    : {
      id: vector.id,
      status: "PASS",
      canonical: vector.expectedCanonical,
      canonicalUtf8Hex: vector.expectedCanonicalUtf8Hex ?? Buffer.from(vector.expectedCanonical, "utf8").toString("hex"),
      documentFingerprint: vector.expectedDocumentFingerprint,
      rawFingerprint: vector.expectedRawFingerprint,
    }),
  profileResults: vectors.profileRejections.map((vector) => ({
    id: vector.id,
    status: "REJECTED",
    reason: vector.expectedReason,
  })),
};

await writeFile(join(here, "canonicalization-challenge-v1.json"), `${JSON.stringify(challenge, null, 2)}\n`);
await writeFile(join(here, "canonicalization-oracle-v1.json"), `${JSON.stringify(oracle, null, 2)}\n`);
console.log(`canonicalization challenge generated: ${challenge.vectors.length} vectors, ${challenge.profileRejections.length} profile rejections`);
