#!/usr/bin/env node

import { createHash } from "node:crypto";
import { access, readFile, readdir, writeFile } from "node:fs/promises";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const schemaRoot = join(here, "..", "..", "..", "..", "schemas", "resource-gateway-capability-studio");
const manifestPath = join(here, "valid-schema-set-manifest.json");
const buildIdentityPath = join(here, "valid-build-identity.json");
const challengePinPath = join(here, "valid-challenge-trust-pin.json");
const sandboxProfilePath = join(here, "..", "process-results", "valid-challenge-sandbox-profile.json");
const inventoryPath = join(here, "schema-set-inventory-v1.json");
const protocolAuthorityPath = join(here, "..", "protocol-compiler", "gate-a-protocol-authority-v1.json");
const protocolCompiledRoot = join(here, "..", "protocol-compiler", "compiled");
const protocolCompilationManifestPath = join(protocolCompiledRoot, "protocol-compilation-manifest-v1.json");

function canonicalJson(value) {
  if (Array.isArray(value)) {
    return `[${value.map(canonicalJson).join(",")}]`;
  }
  if (value !== null && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

function sha256(bytes) {
  return `sha256:${createHash("sha256").update(bytes).digest("hex")}`;
}

function commitment(domain, value) {
  return sha256(Buffer.concat([Buffer.from(domain, "ascii"), Buffer.from([0]), Buffer.from(canonicalJson(value), "utf8")]));
}

function typed(kind, value) {
  return { kind, algorithm: "SHA-256", value };
}

function documentFingerprint(domain, document, selfField) {
  const material = structuredClone(document);
  material[selfField] = null;
  return commitment(domain, material);
}

async function readJson(path) {
  return JSON.parse(await readFile(path, "utf8"));
}

async function writeJson(path, value) {
  await writeFile(path, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

async function exists(path) {
  try {
    await access(path);
    return true;
  } catch {
    return false;
  }
}

const protocolAuthorityBytes = await readFile(protocolAuthorityPath);
const protocolAuthority = JSON.parse(protocolAuthorityBytes.toString("utf8"));
const protocolSourceFingerprint = sha256(protocolAuthorityBytes);
const compilationManifest = await readJson(protocolCompilationManifestPath);
if (compilationManifest.sourceRawFingerprint !== protocolSourceFingerprint) {
  throw new Error("PROTOCOL_COMPILED_SOURCE_RAW_DRIFT");
}
for (const projection of compilationManifest.projections ?? []) {
  const projectionBytes = await readFile(join(protocolCompiledRoot, projection.path));
  if (sha256(projectionBytes) !== projection.rawFingerprint) {
    throw new Error(`PROTOCOL_COMPILED_PROJECTION_RAW_DRIFT ${projection.projectionId}`);
  }
  const projectionDocument = JSON.parse(projectionBytes.toString("utf8"));
  if (projectionDocument.sourceRawFingerprint !== protocolSourceFingerprint) {
    throw new Error(`PROTOCOL_COMPILED_PROJECTION_SOURCE_DRIFT ${projection.projectionId}`);
  }
}

const policy = protocolAuthority.schemaInventoryPolicy;
const gateSchemaNames = policy.gateASchemas;
if (!Array.isArray(gateSchemaNames)) throw new Error("SCHEMA_SET_POLICY_EXACT_GATE_SCHEMAS_MISSING");
const reviewerSchemas = [...policy.requiredReviewerSchemas];
const allSchemaFiles = (await readdir(schemaRoot)).filter((name) => name.endsWith("-v1.schema.json"));
const managedSchemaFiles = allSchemaFiles.filter((name) => name.startsWith("capability-studio-gate-a-") || name.startsWith("capability-studio-review"));
const expectedSourceNames = new Set([
  ...gateSchemaNames,
  ...reviewerSchemas,
]);
const missing = [...expectedSourceNames].filter((name) => !allSchemaFiles.includes(name));
const extras = managedSchemaFiles.filter((name) => !expectedSourceNames.has(name));
if (missing.length || extras.length) {
  throw new Error(`SCHEMA_SET_INVENTORY_DRIFT missing=${missing.join(",") || "-"} extra=${extras.join(",") || "-"}`);
}

function deriveSchemaEntry(sourcePath, sourceDocument, bytes) {
  const isReviewer = sourcePath.startsWith("capability-studio-review");
  const sourceStem = sourcePath
    .replace(/^capability-studio-/, "")
    .replace(/^gate-a-/, "")
    .replace(/-v1\.schema\.json$/, "");
  const schemaId = sourceDocument?.properties?.schemaVersion?.const
    ?? `capability-studio.${isReviewer ? sourceStem : `gate-a-${sourceStem}`}.v1`;
  const entryDirectory = isReviewer ? "META-INF/gate-a/schemas/reviewer" : "META-INF/gate-a/schemas";
  const entryPath = `${entryDirectory}/${sourceStem}.json`;
  if (sourceDocument?.properties?.schemaVersion?.const && sourceDocument.properties.schemaVersion.const !== schemaId) {
    throw new Error(`SCHEMA_SET_SCHEMA_ID_DRIFT source=${sourcePath}`);
  }
  return { sourcePath, schemaId, entryPath, rawFingerprint: sha256(bytes) };
}

const inventoryEntries = [];
for (const sourcePath of [...expectedSourceNames].sort()) {
  const bytes = await readFile(join(schemaRoot, sourcePath));
  inventoryEntries.push(deriveSchemaEntry(sourcePath, JSON.parse(bytes.toString("utf8")), bytes));
}
inventoryEntries.sort((left, right) => left.schemaId.localeCompare(right.schemaId) || left.entryPath.localeCompare(right.entryPath));
const schemaIds = inventoryEntries.map((entry) => entry.schemaId);
const entryPaths = inventoryEntries.map((entry) => entry.entryPath);
if (new Set(schemaIds).size !== schemaIds.length || new Set(entryPaths).size !== entryPaths.length) {
  throw new Error("SCHEMA_SET_INVENTORY_DUPLICATE_ID_OR_PATH");
}
const inventory = {
  inventoryVersion: "capability-studio.gate-a-schema-set-inventory.v1",
  sourceRawFingerprint: protocolSourceFingerprint,
  gateASchemas: gateSchemaNames,
  requiredReviewerSchemas: reviewerSchemas,
  entries: inventoryEntries,
};
await writeJson(inventoryPath, inventory);
const schemaFiles = inventoryEntries.slice().sort((left, right) =>
  left.schemaId.localeCompare(right.schemaId) || left.entryPath.localeCompare(right.entryPath));

const entries = [];
for (const schema of schemaFiles) {
  const bytes = await readFile(join(schemaRoot, schema.sourcePath));
  entries.push({
    schemaId: schema.schemaId,
    entryPath: schema.entryPath,
    rawFingerprint: typed("RAW_BYTES", sha256(bytes)),
  });
}
entries.sort((left, right) => left.schemaId.localeCompare(right.schemaId) || left.entryPath.localeCompare(right.entryPath));

const manifestMaterial = {
  schemaVersion: "capability-studio.gate-a-schema-set-manifest.v1",
  manifestFingerprint: null,
  entries,
};
const manifest = {
  ...manifestMaterial,
  manifestFingerprint: typed(
    "AGGREGATE_COMMITMENT",
    commitment("RG-CS-GATE-A-SCHEMA-SET-MANIFEST-v1", manifestMaterial),
  ),
};
await writeJson(manifestPath, manifest);

const manifestRawFingerprint = sha256(await readFile(manifestPath));
const buildIdentity = await readJson(buildIdentityPath);
buildIdentity.schemaSetManifestRef.rawFingerprint = typed("RAW_BYTES", manifestRawFingerprint);
buildIdentity.schemaSetFingerprint = manifest.manifestFingerprint;
buildIdentity.identityFingerprint = typed(
  "CANONICAL_DOCUMENT",
  documentFingerprint("RG-CS-GATE-A-BUILD-IDENTITY-v1", buildIdentity, "identityFingerprint"),
);
await writeJson(buildIdentityPath, buildIdentity);

const challengePin = await readJson(challengePinPath);
challengePin.expectedSchemaSetManifestRawFingerprint = typed("RAW_BYTES", manifestRawFingerprint);
challengePin.expectedChallengeSandboxProfileRawFingerprint = typed(
  "RAW_BYTES",
  sha256(await readFile(sandboxProfilePath)),
);
if (await exists(protocolAuthorityPath)) {
  challengePin.expectedProtocolAuthorityRawFingerprint = typed(
    "RAW_BYTES",
    sha256(await readFile(protocolAuthorityPath)),
  );
} else {
  console.warn("PROTOCOL_AUTHORITY_PENDING: protocol-compiler/gate-a-protocol-authority-v1.json is not present; preserved existing pin field.");
}
challengePin.challengeTrustPinFingerprint = typed(
  "CANONICAL_DOCUMENT",
  documentFingerprint(
    "RG-CS-GATE-A-CHALLENGE-TRUST-PIN-v1",
    challengePin,
    "challengeTrustPinFingerprint",
  ),
);
await writeJson(challengePinPath, challengePin);

console.log(`Generated ${relative(process.cwd(), manifestPath)} with ${entries.length} schemas from ${relative(process.cwd(), inventoryPath)}.`);
