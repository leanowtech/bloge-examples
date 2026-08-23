#!/usr/bin/env node

import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, unlinkSync, writeFileSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import {
  canonicalize,
  documentFingerprint,
  rawSha256
} from "../canonicalization/reference-fingerprint.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const TRUST_BUILD = resolve(HERE, "../trust-build");
const RUN_MATERIAL = join(HERE, "run-material");
const CHALLENGE_INPUT = join(HERE, "challenge-input");
const PROVIDER_FIXTURE = join(HERE, "provider-fixture");
const PROTOCOL_SOURCE = join(HERE, "../protocol-compiler/gate-a-protocol-authority-v1.json");
const PROTOCOL_COMPILED = join(HERE, "../protocol-compiler/compiled/launch-contract-v1.json");
const PROTOCOL_REPLAY_COMPILED = join(HERE, "../protocol-compiler/compiled/replay-vector-registry-v1.json");
const PROTOCOL_MANIFEST = join(HERE, "../protocol-compiler/compiled/protocol-compilation-manifest-v1.json");
const RESULTS = new Map();

const DOMAINS = Object.freeze({
  command: "RG-CS-GATE-A-PROCESS-COMMAND-v1",
  invocation: "RG-CS-GATE-A-A1-INVOCATION-v1",
  harnessInvocation: "RG-CS-GATE-A-HARNESS-INVOCATION-v1",
  bootstrapResponse: "RG-CS-GATE-A-A1-BOOTSTRAP-RESPONSE-v1",
  harnessTranscript: "RG-CS-GATE-A-HARNESS-PROCESS-TRANSCRIPT-v1",
  transcript: "RG-CS-GATE-A-PROCESS-TRANSCRIPT-v1",
  request: "RG-CS-GATE-A-CANDIDATE-CHALLENGE-REQUEST-v1",
  response: "RG-CS-GATE-A-CANDIDATE-CHALLENGE-RESPONSE-v1",
  operationResult: "RG-CS-GATE-A-OPERATION-RESULT-v1",
  a0Result: "RG-CS-GATE-A0-RESULT-v1",
  replayResult: "RG-CS-GATE-A1-REPLAY-RESULT-v1",
  independentResult: "RG-CS-GATE-A1-REPORT-v1",
  independentEnvelope: "RG-CS-GATE-A1-INDEPENDENT-PROOF-ENVELOPE-v1",
  envelope: "RG-CS-GATE-A1-PROOF-ENVELOPE-v1",
  materialRoot: "RG-CS-GATE-A-RUN-MATERIAL-ROOT-v1",
  challengeInputRoot: "RG-CS-GATE-A-CHALLENGE-INPUT-ROOT-v1",
  providerMaterialization: "RG-CS-GATE-A-PROVIDER-MATERIALIZATION-OBSERVATION-v1",
  materialAggregate: "RG-CS-GATE-A-PROCESS-MATERIAL-AGGREGATE-v1",
  evidenceAggregate: "RG-CS-GATE-A-TEST-EVIDENCE-AGGREGATE-v1",
  abnormalAttempt: "RG-CS-GATE-A-ABNORMAL-ATTEMPT-v1"
});

let TEST_IDS = [];
const SPECIAL_TEST_IDS = new Set([
  "VERIFIER_DIGEST_MUTATION_REJECTED",
  "REGISTRY_MUTATION_REJECTED",
  "VERIFIER_TCK_MISMATCH_REJECTED"
]);

function clone(value) {
  return structuredClone(value);
}

function compareAscii(left, right) {
  if (left === right) return 0;
  return left < right ? -1 : 1;
}

function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function createStoredZip(entries) {
  const localParts = [];
  const centralParts = [];
  let localOffset = 0;
  for (const entry of entries) {
    const name = Buffer.from(entry.path, "ascii");
    const bytes = Buffer.from(entry.bytes);
    const checksum = crc32(bytes);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0, 6);
    local.writeUInt16LE(0, 8);
    local.writeUInt16LE(0, 10);
    local.writeUInt16LE(0x21, 12);
    local.writeUInt32LE(checksum, 14);
    local.writeUInt32LE(bytes.length, 18);
    local.writeUInt32LE(bytes.length, 22);
    local.writeUInt16LE(name.length, 26);
    local.writeUInt16LE(0, 28);
    localParts.push(local, name, bytes);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(0x0314, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0, 8);
    central.writeUInt16LE(0, 10);
    central.writeUInt16LE(0, 12);
    central.writeUInt16LE(0x21, 14);
    central.writeUInt32LE(checksum, 16);
    central.writeUInt32LE(bytes.length, 20);
    central.writeUInt32LE(bytes.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt16LE(0, 30);
    central.writeUInt16LE(0, 32);
    central.writeUInt16LE(0, 34);
    central.writeUInt16LE(0, 36);
    central.writeUInt32LE(0, 38);
    central.writeUInt32LE(localOffset, 42);
    centralParts.push(central, name);
    localOffset += local.length + name.length + bytes.length;
  }
  const centralBytes = Buffer.concat(centralParts);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(0, 4);
  end.writeUInt16LE(0, 6);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralBytes.length, 12);
  end.writeUInt32LE(localOffset, 16);
  end.writeUInt16LE(0, 20);
  return Buffer.concat([...localParts, centralBytes, end]);
}

function readJson(file, trustBuild = false) {
  const path = join(trustBuild ? TRUST_BUILD : HERE, file);
  return JSON.parse(readFileSync(path, "utf8"));
}

function load(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function fingerprint(kind, value) {
  return { kind, algorithm: "SHA-256", value };
}

function rawFingerprint(bytes) {
  return fingerprint("RAW_BYTES", rawSha256(bytes));
}

function documentValue(domain, value, selfField) {
  assertNoUndefined(value, domain);
  return fingerprint("CANONICAL_DOCUMENT", documentFingerprint(domain, value, selfField).fingerprint);
}

function setDocumentFingerprint(value, domain, selfField) {
  value[selfField] = documentValue(domain, value, selfField);
  return value;
}

function jsonBytes(value) {
  assertNoUndefined(value, "json");
  return Buffer.from(canonicalize(value), "utf8");
}

function loadLaunchContracts() {
  const sourceBytes = readFileSync(PROTOCOL_SOURCE);
  const projectionBytes = readFileSync(PROTOCOL_COMPILED);
  const source = JSON.parse(sourceBytes);
  const projection = JSON.parse(projectionBytes);
  const manifest = JSON.parse(readFileSync(PROTOCOL_MANIFEST));
  const pin = JSON.parse(readFileSync(join(TRUST_BUILD, "valid-challenge-trust-pin.json")));
  const sourceRaw = rawSha256(sourceBytes);
  if (JSON.stringify(pin.expectedProtocolAuthorityRawFingerprint) !== JSON.stringify(rawFingerprint(sourceBytes))) {
    throw new Error("Protocol authority pin drift");
  }
  if (projection.projectionId !== "LAUNCH_CONTRACT" || projection.authorityRevision !== source.revision) {
    throw new Error("Protocol launch projection identity drift");
  }
  if (projection.sourceRawFingerprint !== sourceRaw || manifest.sourceRawFingerprint !== sourceRaw) {
    throw new Error("Protocol launch source fingerprint drift");
  }
  const manifestEntry = manifest.projections.find((item) => item.projectionId === "LAUNCH_CONTRACT");
  if (!manifestEntry || manifestEntry.path !== "launch-contract-v1.json" ||
      manifestEntry.rawFingerprint !== rawSha256(projectionBytes)) {
    throw new Error("Protocol launch projection raw fingerprint drift");
  }
  if (!Buffer.from(canonicalize(projection.content)).equals(Buffer.from(canonicalize(source.launchContracts)))) {
    throw new Error("Protocol launch projection content drift");
  }
  return Object.fromEntries(projection.content.map((item) => [item.launchKind, item]));
}

function loadReplayVectorRegistry() {
  const sourceBytes = readFileSync(PROTOCOL_SOURCE);
  const projectionBytes = readFileSync(PROTOCOL_REPLAY_COMPILED);
  const source = JSON.parse(sourceBytes);
  const projection = JSON.parse(projectionBytes);
  const manifest = JSON.parse(readFileSync(PROTOCOL_MANIFEST));
  const pin = JSON.parse(readFileSync(join(TRUST_BUILD, "valid-challenge-trust-pin.json")));
  const sourceRaw = rawSha256(sourceBytes);
  if (JSON.stringify(pin.expectedProtocolAuthorityRawFingerprint) !== JSON.stringify(rawFingerprint(sourceBytes))) {
    throw new Error("Protocol authority pin drift");
  }
  const entry = manifest.projections.find((item) => item.projectionId === "REPLAY_VECTOR_REGISTRY");
  if (projection.projectionId !== "REPLAY_VECTOR_REGISTRY" || projection.authorityRevision !== source.revision ||
      projection.sourceRawFingerprint !== sourceRaw || manifest.sourceRawFingerprint !== sourceRaw ||
      !entry || entry.path !== "replay-vector-registry-v1.json" || entry.rawFingerprint !== rawSha256(projectionBytes)) {
    throw new Error("Protocol replay projection fingerprint drift");
  }
  const expectedContent = { inputSets: source.inputSets, replayVectors: source.replayVectors };
  if (!Buffer.from(canonicalize(projection.content)).equals(Buffer.from(canonicalize(expectedContent)))) {
    throw new Error("Protocol replay projection content drift");
  }
  const vectors = projection.content.replayVectors;
  if (vectors.length !== 9 || vectors.some((item, index) => item.ordinal !== index + 1 || item.mutationRecipe.variantId !== item.testId)) {
    throw new Error("Protocol replay vector registry drift");
  }
  return projection.content;
}

function assertNoUndefined(value, path, seen = new Set()) {
  if (value === undefined) throw new Error(`undefined canonical value at ${path}`);
  if (value === null || typeof value !== "object") return;
  if (seen.has(value)) throw new Error(`cyclic canonical value at ${path}`);
  seen.add(value);
  if (Array.isArray(value)) value.forEach((item, index) => assertNoUndefined(item, `${path}[${index}]`, seen));
  else for (const [key, item] of Object.entries(value)) assertNoUndefined(item, `${path}.${key}`, seen);
  seen.delete(value);
}

function rawRef(uri, bytes) {
  return { uri, rawFingerprint: rawFingerprint(bytes) };
}

function treeRef(uri, value) {
  return { uri, fingerprint: value };
}

function materialPath(relativePath) {
  if (!relativePath || relativePath.startsWith("/") || relativePath.includes("..")) {
    throw new Error(`unsafe material path: ${relativePath}`);
  }
  return join(RUN_MATERIAL, relativePath);
}

function writeBytes(relativePath, bytes) {
  if (RESULTS.has(relativePath)) throw new Error(`duplicate material path: ${relativePath}`);
  const path = materialPath(relativePath);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, bytes, { flag: "wx" });
  const record = { relativePath, bytes: Buffer.from(bytes), rawFingerprint: rawFingerprint(bytes) };
  RESULTS.set(relativePath, record);
  return record;
}

function writeExclusive(path, bytes) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, bytes, { flag: "wx" });
}

function writeJson(relativePath, value) {
  return writeBytes(relativePath, jsonBytes(value));
}

function writeText(relativePath, text) {
  return writeBytes(relativePath, Buffer.from(text, "utf8"));
}

function refFor(record) {
  return rawRef(`run-material/${record.relativePath}`, record.bytes);
}

function replaceAtPath(target, path, value) {
  const tokens = path
    .replace(/^\$\./, "")
    .replace(/^\$/, "")
    .split(".")
    .flatMap((token) => token.split(/\[|\]/).filter(Boolean));
  let cursor = target;
  for (let index = 0; index < tokens.length - 1; index++) cursor = cursor[tokens[index]];
  cursor[tokens[tokens.length - 1]] = clone(value);
}

function deriveNegative(base, oldNegative, mutationPath) {
  const result = clone(base);
  const tokens = mutationPath
    .replace(/^\$\./, "")
    .split(".")
    .flatMap((token) => token.split(/\[|\]/).filter(Boolean));
  let oldCursor = oldNegative;
  let exists = true;
  for (const token of tokens) {
    if (oldCursor === null || oldCursor === undefined || !(token in oldCursor)) {
      exists = false;
      break;
    }
    oldCursor = oldCursor[token];
  }
  if (exists) replaceAtPath(result, mutationPath, oldCursor);
  else {
    let cursor = result;
    for (let index = 0; index < tokens.length - 1; index++) cursor = cursor[tokens[index]];
    delete cursor[tokens.at(-1)];
  }
  return result;
}

function updateJson(name, value, trustBuild = false) {
  const path = join(trustBuild ? TRUST_BUILD : HERE, name);
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function childSlug(testId) {
  return testId.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
}

function challengeId(testId, ordinal, family) {
  return `CHALLENGE-${family}-${String(ordinal).padStart(2, "0")}-${childSlug(testId).slice(0, 32).toUpperCase()}`;
}

function childTerminal(test, override = null) {
  if (override) return override;
  return {
    terminal: test.expectedTerminal === "ACCEPTED" ? "ACCEPTED" :
      test.expectedTerminal === "NOT_ACCEPTED" ? "NOT_ACCEPTED" :
        test.expectedTerminal === "INCOMPLETE" ? "INCOMPLETE" : "INVALID",
    exitCode: test.expectedExitCode,
    reasonCode: test.expectedReasonCode
  };
}

function replayVector(testId) {
  const vector = REPLAY_REGISTRY.replayVectors.find((item) => item.testId === testId);
  if (!vector) throw new Error(`Unknown replay vector ${testId}`);
  return vector;
}

function operationFor(testId) {
  return replayVector(testId).operation;
}

function buildA0Result(template, resultId, terminal, reasonCode) {
  const result = clone(template);
  result.resultId = resultId;
  result.candidateArtifactRef.rawFingerprint = CANDIDATE_RAW;
  result.challengeTrustPinRef.rawFingerprint = CHALLENGE_PIN_RAW;
  result.challengeInputRootRef.fingerprint = clone(CHALLENGE_PIN.expectedChallengeInputRootFingerprint);
  result.terminal = terminal === "ACCEPTED" || terminal === "NOT_ACCEPTED" ? "INVALID" : terminal;
  result.reasonCode = result.terminal === "INVALID" ? "A0_INVALID" :
    result.terminal === "UNAVAILABLE" ? "A0_UNAVAILABLE" : "A0_INCOMPLETE";
  result.resultFingerprint = null;
  return setDocumentFingerprint(result, DOMAINS.a0Result, "resultFingerprint");
}

function buildCandidateResponse(test, challenge, terminal, a0Template) {
  const operation = operationFor(test.testId);
  if (operation === "LEGACY_STAGE_ACCEPTANCE_V2") {
    const response = clone(LEGACY_RESPONSE);
    response.challengeId = challenge;
    response.operation = operation;
    response.tckVectorId = test.testId;
    response.observedTerminal = terminal.terminal;
    response.closedReasonCode = terminal.reasonCode;
    response.authorityProviderFqcn = PROVIDER_IDENTITY.providerClass;
    response.authorityProviderCodeSourceRawFingerprint = PROVIDER_RAW;
    response.authorityProviderClassRawFingerprint = PROVIDER_CLASS_RAW;
    response.candidateCodeSourceRawFingerprint = CANDIDATE_RAW;
    response.operationResult.terminal = terminal.terminal;
    response.operationResult.reasonCode = terminal.reasonCode;
    response.operationResult.authorityBindingComplete = terminal.terminal === "ACCEPTED";
    response.operationResultFingerprint = commitment(
      DOMAINS.operationResult,
      response.operationResult,
      "CANONICAL_DOCUMENT"
    );
    response.responseFingerprint = null;
    return setDocumentFingerprint(response, DOMAINS.response, "responseFingerprint");
  }

  const response = clone(TYPED_RESPONSE);
  response.challengeId = challenge;
  response.operation = operation;
  response.tckVectorId = test.testId;
  response.observedTerminal = terminal.terminal;
  response.closedReasonCode = terminal.terminal === "INVALID" ? "A0_INVALID" :
    terminal.terminal === "UNAVAILABLE" ? "A0_UNAVAILABLE" : "A0_INCOMPLETE";
  response.candidateCodeSourceRawFingerprint = CANDIDATE_RAW;
  response.operationResult = buildA0Result(
    a0Template,
    `A0-${String(challenge).replace(/[^A-Z0-9]+/g, "-").slice(0, 54)}`,
    terminal.terminal,
    terminal.reasonCode
  );
  response.operationResultFingerprint = documentValue(DOMAINS.operationResult, response.operationResult, null);
  response.responseFingerprint = null;
  return setDocumentFingerprint(response, DOMAINS.response, "responseFingerprint");
}

function buildCandidateRequest(test, challenge) {
  const request = clone(REQUEST);
  const vector = replayVector(test.testId);
  const inputSet = REPLAY_REGISTRY.inputSets.find((item) => item.inputSetId === vector.inputSetId);
  if (!inputSet) throw new Error(`Unknown replay input set ${vector.inputSetId}`);
  request.challengeId = challenge;
  request.candidateRawFingerprint = CANDIDATE_RAW;
  request.replayProfileRawFingerprint = clone(CHALLENGE_PIN.expectedReplayProfileRawFingerprint);
  request.fixtureRootRef = {
    uri: inputSet.fixtureRootUri,
    fingerprint: fingerprint("TREE_COMMITMENT", inputSet.fixtureRootFingerprint)
  };
  request.inputExactRefs = inputSet.exactRefs.map((item) => ({
    inputRole: item.role,
    exactRef: { uri: item.uri, rawFingerprint: fingerprint("RAW_BYTES", item.rawFingerprint) }
  }));
  request.operation = vector.operation;
  request.tckVectorId = test.testId;
  request.requestFingerprint = null;
  return setDocumentFingerprint(request, DOMAINS.request, "requestFingerprint");
}

function makeClasspath(launchKind, codeSourceRaw) {
  if (launchKind === "CANDIDATE_CHILD") {
    return [
      {
        ordinal: 1,
        role: "TCK_PROVIDER",
        artifactPath: "/work/provider.jar",
        rawFingerprint: PROVIDER_RAW
      },
      {
        ordinal: 2,
        role: "CANDIDATE",
        artifactPath: "/opt/candidate.jar",
        rawFingerprint: codeSourceRaw
      }
    ];
  }
  return [{ ordinal: 1, role: "APPLICATION", artifactPath: applicationPath(launchKind), rawFingerprint: codeSourceRaw }];
}

function applicationPath(launchKind) {
  const argumentsTemplate = LAUNCH_CONTRACTS[launchKind].argumentTemplate;
  if (argumentsTemplate[0] === "-jar") return argumentsTemplate[1];
  return argumentsTemplate[1].split(":").at(-1);
}

function makeOrigins(launchKind, codeSourceRaw) {
  if (launchKind === "CANDIDATE_CHILD") {
    return [
      {
        role: "CANDIDATE_CHALLENGE_CLI",
        binaryName: "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli",
        classRawFingerprint: fingerprint("RAW_BYTES", "sha256:7272727272727272727272727272727272727272727272727272727272727272"),
        codeSourceRawFingerprint: codeSourceRaw
      },
      {
        role: "CANDIDATE_SPI",
        binaryName: "com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider",
        classRawFingerprint: SPI_CLASS_RAW,
        codeSourceRawFingerprint: codeSourceRaw
      },
      {
        role: "TCK_PROVIDER",
        binaryName: PROVIDER_IDENTITY.providerClass,
        classRawFingerprint: PROVIDER_CLASS_RAW,
        codeSourceRawFingerprint: PROVIDER_RAW
      }
    ];
  }
  return [{
    role: launchKind === "CONFORMANCE_HARNESS" ? "HARNESS_MAIN" : "A1_MAIN",
    binaryName: launchKind === "CONFORMANCE_HARNESS"
      ? "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAConformanceHarness"
      : "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAIndependentVerifier",
    classRawFingerprint: fingerprint("RAW_BYTES", launchKind === "CONFORMANCE_HARNESS"
      ? "sha256:3131313131313131313131313131313131313131313131313131313131313131"
      : "sha256:3232323232323232323232323232323232323232323232323232323232323232"),
    codeSourceRawFingerprint: codeSourceRaw
  }];
}

function makeObservation(runId, launchKind, codeSourceRaw, stdoutLength, startInstant, pid) {
  const observation = clone(OBSERVATION);
  observation.runId = runId;
  observation.launchKind = launchKind;
  observation.sandboxProfileRawFingerprint = SANDBOX_RAW;
  observation.effectiveClasspath = makeClasspath(launchKind, codeSourceRaw);
  observation.admittedClassOrigins = makeOrigins(launchKind, codeSourceRaw);
  observation.environmentNames = [];
  observation.jvmInputArguments = [];
  observation.processTree.rootProcess.pid = pid;
  observation.processTree.rootProcess.startInstant = startInstant;
  observation.processTree.descendantsBeforeTermination = [];
  observation.processTree.descendantsAfterTermination = [];
  observation.processTree.terminationAction = "NONE";
  observation.processTree.processTreeQuiescent = true;
  for (const stream of ["stdoutCapture", "stderrCapture"]) {
    observation[stream].limitBytes = launchKind === "CANDIDATE_CHILD" ? 8192 : 1048576;
    observation[stream].observedBytes = stream === "stdoutCapture" ? stdoutLength : 0;
    observation[stream].complete = true;
    observation[stream].overflow = false;
    observation[stream].leakScan = "PASS";
    observation[stream].scannerId = "GATE_A_OUTPUT_HYGIENE";
    observation[stream].scannerRevision = 1;
    observation[stream].scannerProfileRawFingerprint = SANDBOX_RAW;
    observation[stream].quarantineStatus = "NOT_REQUIRED";
  }
  return observation;
}

function makeCodeSource(path, raw, fileKey, size = 1024) {
  return {
    artifactPath: path,
    rawFingerprint: raw,
    fileSize: size,
    fileKey
  };
}

function setCodeSourceObservation(transcript, codeSource) {
  transcript.codeSource = clone(codeSource);
  transcript.codeSourceObservation.preRead.resolvedPath = codeSource.artifactPath;
  transcript.codeSourceObservation.postRead.resolvedPath = codeSource.artifactPath;
  for (const phase of ["preRead", "postRead"]) {
    transcript.codeSourceObservation[phase].fileKey = codeSource.fileKey;
    transcript.codeSourceObservation[phase].owner = "uid:1000";
    transcript.codeSourceObservation[phase].group = "gid:1000";
    transcript.codeSourceObservation[phase].linkCount = 1;
    transcript.codeSourceObservation[phase].posixMode = "0644";
    transcript.codeSourceObservation[phase].fileSize = codeSource.fileSize;
    transcript.codeSourceObservation[phase].readRawFingerprint = codeSource.rawFingerprint;
  }
}

function makeCommand(runId, launchKind, context) {
  const command = clone(PROCESS_COMMAND);
  const contract = LAUNCH_CONTRACTS[launchKind];
  const values = { runId, ...context };
  const expand = (template) => template.replaceAll(/\{([A-Za-z][A-Za-z0-9]*)\}/g, (_, name) => {
    if (values[name] === undefined || values[name] === null) throw new Error(`Unbound launch template ${name}`);
    return values[name];
  });
  command.runId = runId;
  command.executable = contract.executable;
  command.arguments = contract.argumentTemplate.map(expand);
  command.environment = [...contract.environmentNames];
  command.workingDirectory = expand(contract.workingDirectoryTemplate);
  command.commandFingerprint = null;
  return setDocumentFingerprint(command, DOMAINS.command, "commandFingerprint");
}

function makeTranscript(template, runId, commandRecord, observationRecord, stdoutRecord, stderrRecord, codeSource, start, end, launchKind, exitCode = 0) {
  const transcript = clone(template);
  transcript.runId = runId;
  transcript.commandRef = refFor(commandRecord);
  transcript.processState = "COMPLETED";
  transcript.exitCode = exitCode;
  transcript.startedAt = start;
  transcript.endedAt = end;
  transcript.timedOut = false;
  transcript.cancelled = false;
  setCodeSourceObservation(transcript, codeSource);
  transcript.processObservationRef = refFor(observationRecord);
  transcript.stdoutRef = refFor(stdoutRecord);
  transcript.stderrRef = refFor(stderrRecord);
  transcript.transcriptFingerprint = null;
  return setDocumentFingerprint(
    transcript,
    launchKind === "CONFORMANCE_HARNESS" ? DOMAINS.harnessTranscript : DOMAINS.transcript,
    "transcriptFingerprint"
  );
}

function writeChild({ runId, ordinal, test, family, overrideTerminal = null, parentOffset = 0, a0Template }) {
  const childRunId = `${runId}-CHILD-${String(ordinal).padStart(2, "0")}`;
  const folder = `runs/${runId}/producer/children/${String(ordinal).padStart(2, "0")}-${childSlug(test.testId)}`;
  const challenge = challengeId(test.testId, ordinal, family);
  const terminal = childTerminal(test, overrideTerminal);
  const request = buildCandidateRequest(test, challenge);
  const response = buildCandidateResponse(test, challenge, terminal, a0Template);
  const requestRecord = writeJson(`${folder}/request.json`, request);
  const responseRecord = writeJson(`${folder}/response.json`, response);
  if (test.allowedExtraMaterial?.includes("a0-candidate-result.json")) {
    writeJson(`${folder}/a0-candidate-result.json`, response.operationResult);
  }
  const stdoutRecord = writeBytes(`${folder}/stdout`, Buffer.concat([responseRecord.bytes, Buffer.from("\n", "ascii")]));
  const stderrRecord = writeText(`${folder}/stderr`, "");
  const childDir = folder.split("/").at(-1);
  const command = makeCommand(childRunId, "CANDIDATE_CHILD", { outerRunId: runId, childDir });
  const commandRecord = writeJson(`${folder}/command.json`, command);
  const start = `2026-08-21T10:${String(parentOffset).padStart(2, "0")}:${String(ordinal).padStart(2, "0")}Z`;
  const end = `2026-08-21T10:${String(parentOffset).padStart(2, "0")}:${String(ordinal + 1).padStart(2, "0")}Z`;
  const observation = makeObservation(childRunId, "CANDIDATE_CHILD", CANDIDATE_RAW, stdoutRecord.bytes.length, start, 5000 + parentOffset * 100 + ordinal);
  const observationRecord = writeJson(`${folder}/process-observation.json`, observation);
  const codeSource = makeCodeSource("/opt/candidate.jar", CANDIDATE_RAW, `dev:1:ino:${10000 + parentOffset * 100 + ordinal}`);
  const transcript = makeTranscript(PROCESS_TRANSCRIPT, childRunId, commandRecord, observationRecord, stdoutRecord, stderrRecord, codeSource, start, end, "CANDIDATE_CHILD", terminal.exitCode);
  const transcriptRecord = writeJson(`${folder}/process-transcript.json`, transcript);
  return {
    runId: childRunId,
    folder,
    test,
    challenge,
    request,
    response,
    requestRecord,
    responseRecord,
    stdoutRecord,
    stderrRecord,
    commandRecord,
    observationRecord,
    transcriptRecord,
    terminal,
    transcript,
    commandFingerprint: command.commandFingerprint,
    requestFingerprint: request.requestFingerprint,
    responseFingerprint: response.responseFingerprint
  };
}

function makeInvocation(template, runId, commandRecord, purpose, domain) {
  const invocation = clone(template);
  invocation.runId = runId;
  if ("runPurpose" in invocation) invocation.runPurpose = purpose;
  invocation.challengeTrustPinRawFingerprint = CHALLENGE_PIN_RAW;
  if ("candidateRawFingerprint" in invocation) invocation.candidateRawFingerprint = CANDIDATE_RAW;
  if ("verifierRawFingerprint" in invocation) invocation.verifierRawFingerprint = VERIFIER_RAW;
  if ("harnessRawFingerprint" in invocation) invocation.harnessRawFingerprint = HARNESS_RAW;
  invocation.commandRef = refFor(commandRecord);
  invocation.invocationFingerprint = null;
  return setDocumentFingerprint(invocation, domain, "invocationFingerprint");
}

function makeBootstrapResponse(runId, codeSourceRaw, status = "READY") {
  const response = clone(BOOTSTRAP_RESPONSE);
  response.runId = runId;
  response.bootstrapStatus = status;
  response.reasonCode = status === "READY" ? "BOOTSTRAP_READY" :
    status === "REJECTED" ? "BOOTSTRAP_REJECTED" : "BOOTSTRAP_UNAVAILABLE";
  response.actualCodeSource.rawFingerprint = codeSourceRaw;
  response.checkedArtifacts[0].rawFingerprint = codeSourceRaw;
  response.checkedArtifacts[1].rawFingerprint = PROVIDER_RAW;
  response.responseFingerprint = null;
  return setDocumentFingerprint(response, DOMAINS.bootstrapResponse, "responseFingerprint");
}

function writeOuter({ runId, purpose, launchKind, codeSourceRaw, result, stdoutResponse = null, materializeProvider = launchKind === "A1_VERIFIER", outcome = null, parentOffset, harness = false, processState = "COMPLETED", transcriptExitCode = null, startedAt = null, endedAt = null }) {
  const root = `runs/${runId}`;
  const start = startedAt ?? `2026-08-21T11:${String(parentOffset).padStart(2, "0")}:00Z`;
  const end = endedAt ?? `2026-08-21T11:${String(parentOffset).padStart(2, "0")}:30Z`;
  const artifactPath = applicationPath(launchKind);
  const command = makeCommand(runId, launchKind, { outerRunId: runId, runPurpose: purpose });
  const commandRecord = writeJson(`${root}/command.json`, command);
  const invocation = makeInvocation(
    harness ? HARNESS_INVOCATION : A1_INVOCATION,
    runId,
    commandRecord,
    purpose,
    harness ? DOMAINS.harnessInvocation : DOMAINS.invocation
  );
  const invocationRecord = writeJson(`${root}/producer/invocation.json`, invocation);
  const providerMaterializationRecord = materializeProvider
    ? writeJson(`${root}/producer/provider-materialization.json`, providerMaterializationDocument(`PROVIDER-MATERIALIZATION-${runId}`, runId))
    : null;
  let response = clone(result ?? makeBootstrapResponse(runId, codeSourceRaw));
  if (response.messageVersion === "resource-gateway.capability-studio.gate-a.replay-verification-result.v1") {
    if (providerMaterializationRecord) response.providerMaterializationRef = refMaterial(providerMaterializationRecord);
    response.startedAt = start;
    response.endedAt = end;
    response.resultFingerprint = null;
    response = setDocumentFingerprint(response, DOMAINS.replayResult, "resultFingerprint");
  }
  const responseRecord = writeJson(`${root}/response.json`, response);
  const stdoutBytes = stdoutResponse
    ? Buffer.concat([jsonBytes(stdoutResponse), Buffer.from("\n", "ascii")])
    : Buffer.concat([responseRecord.bytes, Buffer.from("\n", "ascii")]);
  const stdoutRecord = writeBytes(`${root}/stdout`, stdoutBytes);
  const stderrRecord = writeText(`${root}/stderr`, "");
  const observation = makeObservation(runId, launchKind, codeSourceRaw, stdoutRecord.bytes.length, start, 7000 + parentOffset);
  const observationRecord = writeJson(`${root}/process-observation.json`, observation);
  const codeSource = makeCodeSource(artifactPath, codeSourceRaw, `dev:1:ino:${20000 + parentOffset}`, harness ? 3072 : 2048);
  const derivedOutcome = outcome ?? (result?.messageVersion === "resource-gateway.capability-studio.gate-a.replay-verification-result.v1"
    ? {
      terminal: result.terminal,
      exitCode: result.terminal === "VERIFIED" ? 0 : result.terminal === "INVALID" ? 2 : 3,
      reasonCode: result.reasonCode
    }
    : { terminal: "VERIFIED", exitCode: 0, reasonCode: "BOOTSTRAP_READY" });
  const transcriptTemplate = harness ? HARNESS_TRANSCRIPT : PROCESS_TRANSCRIPT;
  let transcript = makeTranscript(transcriptTemplate, runId, commandRecord, observationRecord, stdoutRecord, stderrRecord, codeSource, start, end, launchKind, derivedOutcome.exitCode);
  if (processState !== "COMPLETED" || transcriptExitCode !== null) {
    transcript.processState = processState;
    transcript.exitCode = transcriptExitCode ?? transcript.exitCode;
    if (processState !== "COMPLETED") transcript.endedAt = transcript.startedAt;
    transcript.transcriptFingerprint = null;
    transcript = setDocumentFingerprint(
      transcript,
      harness ? DOMAINS.harnessTranscript : DOMAINS.transcript,
      "transcriptFingerprint"
    );
  }
  if (harness) {
    transcript.invocationRef = refFor(invocationRecord);
    transcript.transcriptFingerprint = null;
    transcript = setDocumentFingerprint(transcript, DOMAINS.harnessTranscript, "transcriptFingerprint");
  }
  const transcriptRecord = writeJson(`${root}/process-transcript.json`, transcript);
  return {
    runId,
    purpose,
    commandRecord,
    invocationRecord,
    responseRecord,
    stdoutRecord,
    stderrRecord,
    observationRecord,
    transcriptRecord,
    transcript,
    command,
    invocation,
    response,
    providerMaterializationRecord,
    codeSource,
    terminal: derivedOutcome.terminal,
    exitCode: derivedOutcome.exitCode,
    reasonCode: derivedOutcome.reasonCode
  };
}

function rawOrNull(record) {
  return record ? rawFingerprint(record.bytes) : null;
}

function writeAbnormalAttempt(policy, ordinal) {
  const event = policy.event;
  const slug = event.replaceAll("_", "-");
  const attemptId = `ABNORMAL-${slug}-001`;
  const runId = `RUN-ABNORMAL-${slug}-001`;
  const folder = `abnormal-attempts/${attemptId}`;
  const start = `2026-08-21T13:${String(ordinal).padStart(2, "0")}:00Z`;
  const end = `2026-08-21T13:${String(ordinal).padStart(2, "0")}:30Z`;
  const commandRecord = writeJson(`${folder}/command.json`, makeCommand(runId, "A1_VERIFIER", {
    outerRunId: runId,
    runPurpose: `ABNORMAL_${event}`
  }));
  const stdoutRecord = writeText(`${folder}/stdout`, "");
  const stderrRecord = writeText(`${folder}/stderr`, "");
  const observation = makeObservation(runId, "A1_VERIFIER", VERIFIER_RAW, 0, start, 9000 + ordinal);
  if (event === "STDOUT_TRUNCATED") {
    observation.stdoutCapture.complete = false;
    observation.stdoutCapture.overflow = true;
  }
  if (event === "PROCESS_TREE_RESIDUE") {
    observation.processTree.descendantsAfterTermination = [clone(observation.processTree.rootProcess)];
    observation.processTree.descendantsAfterTermination[0].pid += 100;
    observation.processTree.processTreeQuiescent = false;
    observation.processTree.terminationAction = "GRACEFUL_THEN_FORCED";
  }
  const observationRecord = writeJson(`${folder}/process-observation.json`, observation);
  const codeSource = makeCodeSource("/opt/gate-a-verifier.jar", VERIFIER_RAW, `dev:1:ino:${30000 + ordinal}`);
  const transcript = makeTranscript(
    PROCESS_TRANSCRIPT,
    runId,
    commandRecord,
    observationRecord,
    stdoutRecord,
    stderrRecord,
    codeSource,
    start,
    end,
    "A1_VERIFIER",
    policy.a1ExitCode
  );
  transcript.processState = policy.event === "CHILD_CANCELLED" ? "CANCELLED" : policy.event === "CHILD_TIMEOUT" ? "FAILED" : "UNAVAILABLE";
  transcript.timedOut = false;
  transcript.cancelled = policy.event === "CHILD_CANCELLED";
  transcript.endedAt = start;
  transcript.transcriptFingerprint = null;
  setDocumentFingerprint(transcript, DOMAINS.transcript, "transcriptFingerprint");
  const transcriptRecord = writeJson(`${folder}/process-transcript.json`, transcript);
  const formedMaterialRefs = [commandRecord, stdoutRecord, stderrRecord, observationRecord, transcriptRecord]
    .map((record) => refMaterial(record));
  const attempt = {
    messageVersion: "resource-gateway.capability-studio.gate-a.abnormal-attempt.v1",
    attemptId,
    runId,
    event,
    firstErrorReason: event,
    a1ExitCode: policy.a1ExitCode,
    observedProcessState: transcript.processState,
    observedExitCode: transcript.exitCode,
    observedTimedOut: transcript.timedOut,
    observedCancelled: transcript.cancelled,
    codeSourceObservationAvailable: event !== "CODESOURCE_OBSERVATION_UNAVAILABLE",
    stopRemainingSlots: policy.stopRemainingSlots,
    drainStreams: policy.drainStreams,
    reapProcessTree: policy.reapProcessTree,
    formedMaterialPolicy: policy.formedMaterialPolicy,
    remainingMaterialPolicy: policy.remainingMaterialPolicy,
    resultPolicy: policy.resultPolicy,
    stdoutPolicy: policy.stdoutPolicy,
    harnessProjection: policy.harnessProjection,
    commandRef: formedMaterialRefs[0],
    processObservationRef: formedMaterialRefs[3],
    processTranscriptRef: formedMaterialRefs[4],
    formedMaterialRefs,
    semanticResultRef: null,
    laterMaterialRefs: [],
    attemptFingerprint: null
  };
  setDocumentFingerprint(attempt, DOMAINS.abnormalAttempt, "attemptFingerprint");
  return attempt;
}

function updateAbnormalAttemptFixtures(abnormalTransitions) {
  const mutationPaths = [
    "$.stopRemainingSlots",
    "$.drainStreams",
    "$.reapProcessTree",
    "$.formedMaterialPolicy",
    "$.resultPolicy",
    "$.a1ExitCode"
  ];
  const invalidValues = [false, false, false, "ALLOW_LATER_MATERIAL", "ALLOW_REPLAY_RESULT", 2];
  const expectations = readJson("negative-fixture-expectations.json");
  abnormalTransitions.forEach((policy, index) => {
    const attempt = writeAbnormalAttempt(policy, index + 1);
    const stem = policy.event.toLowerCase().replaceAll("_", "-");
    const validName = `valid-abnormal-attempt-${stem}.json`;
    const invalidName = `invalid-abnormal-attempt-${stem}.json`;
    updateJson(validName, attempt);
    const invalid = clone(attempt);
    const tokens = mutationPaths[index].replace("$.", "").split(".");
    invalid[tokens[0]] = invalidValues[index];
    updateJson(invalidName, invalid);
    expectations[invalidName] = {
      base: validName,
      schema: "capability-studio-gate-a-abnormal-attempt-v1.schema.json",
      mutationPath: mutationPaths[index],
      expectedKeyword: "const",
      expectedPath: mutationPaths[index],
      expectedMessageContains: index === 5 ? String(policy.a1ExitCode) : index === 3 ? "PRESERVE_ATTEMPT_ONLY" : index === 4 ? "NO_REPLAY_RESULT" : "True"
    };
  });
  const proof = clone(readJson("valid-replay-proof-envelope.json"));
  delete proof.messageVersion;
  setDocumentFingerprint(proof, DOMAINS.envelope, "envelopeFingerprint");
  updateJson("invalid-replay-proof-envelope-missing-message-version.json", proof);
  expectations["invalid-replay-proof-envelope-missing-message-version.json"] = {
    base: "valid-replay-proof-envelope.json",
    schema: "capability-studio-gate-a-replay-proof-envelope-v1.schema.json",
    mutationPath: "$.messageVersion",
    derivedMutationPaths: ["$.envelopeFingerprint"],
    expectedKeyword: "required",
    expectedPath: "$",
    expectedMessageContains: "messageVersion"
  };
  updateJson("negative-fixture-expectations.json", expectations);
}

function rootEntries(rootRelative) {
  const rootPath = join(RUN_MATERIAL, rootRelative);
  const entries = [];
  function visit(path) {
    for (const name of readdirSync(path, { withFileTypes: true }).sort((a, b) => compareAscii(a.name, b.name))) {
      const child = join(path, name.name);
      if (name.isDirectory()) visit(child);
      else {
        const bytes = readFileSync(child);
        entries.push({
          relativePath: relative(rootPath, child).split("\\").join("/"),
          kind: "FILE",
          byteLength: bytes.length,
          rawFingerprint: rawFingerprint(bytes)
        });
      }
    }
  }
  visit(rootPath);
  return entries.sort((a, b) => compareAscii(a.relativePath, b.relativePath));
}

function scopedRootEntries(relativeRoots) {
  return relativeRoots.flatMap((rootRelative) => rootEntries(rootRelative).map((entry) => ({
    ...entry,
    relativePath: `${rootRelative}/${entry.relativePath}`
  }))).sort((a, b) => compareAscii(a.relativePath, b.relativePath));
}

function treeFingerprint(entries, domain = DOMAINS.materialRoot) {
  const canonical = canonicalize(entries);
  return fingerprint("TREE_COMMITMENT", rawSha256(Buffer.concat([
    Buffer.from(domain, "ascii"),
    Buffer.from([0]),
    Buffer.from(canonical, "utf8")
  ])));
}

function prepareChallengeInputAuthority() {
  rmSync(CHALLENGE_INPUT, { recursive: true, force: true });
  const inputsRoot = join(CHALLENGE_INPUT, "inputs");
  const documents = [
    ["formal-evidence-run-manifest.json", {
      schemaVersion: "capability-studio.gate-a.formal-evidence-run-manifest.v1",
      runId: "FIXTURE-GATE-A-001",
      evidenceBundleId: "FIXTURE-EVIDENCE-BUNDLE-001",
      expectedTerminal: "INCOMPLETE",
      evidenceIds: ["EVIDENCE-001"]
    }],
    ["formal-evidence-bundle.json", {
      schemaVersion: "capability-studio.gate-a.formal-evidence-bundle.v1",
      bundleId: "FIXTURE-EVIDENCE-BUNDLE-001",
      evidence: [{ evidenceId: "EVIDENCE-001", kind: "FORMAL_INPUT_TREE", status: "PRESENT" }]
    }]
  ];
  const entries = [];
  const refs = [];
  const roles = ["FORMAL_EVIDENCE_MANIFEST", "FORMAL_EVIDENCE_BUNDLE"];
  for (let index = 0; index < documents.length; index++) {
    const [name, document] = documents[index];
    const bytes = jsonBytes(document);
    writeExclusive(join(inputsRoot, name), bytes);
    const raw = rawFingerprint(bytes);
    entries.push({ relativePath: name, kind: "FILE", byteLength: bytes.length, rawFingerprint: raw });
    refs.push({ inputRole: roles[index], exactRef: { uri: `inputs/${name}`, rawFingerprint: raw } });
  }
  entries.sort((a, b) => compareAscii(a.relativePath, b.relativePath));
  const rootFingerprint = treeFingerprint(entries, DOMAINS.challengeInputRoot);
  writeExclusive(join(inputsRoot, "challenge-root.tree"), jsonBytes({
    rootKind: "GATE_A_CHALLENGE_INPUT",
    entries,
    rootFingerprint
  }));
  return { treeUri: "inputs/challenge-root.tree", inputExactRefs: refs, rootFingerprint };
}

function providerMaterializationDocument(materializationId, runId = "RUN-PROVIDER-MATERIALIZATION-001") {
  const sourceRef = {
    uri: "provider-fixture/gate-a-tck-provider.jar",
    rawFingerprint: PROVIDER_RAW
  };
  const observation = {
    messageVersion: "resource-gateway.capability-studio.gate-a.provider-materialization-observation.v1",
    runId,
    materializationId,
    sourceJarRef: sourceRef,
    destinationPath: "/work/provider.jar",
    createMode: "CREATE_NEW",
    sourcePreReadRawFingerprint: PROVIDER_RAW,
    materializedRawFingerprint: PROVIDER_RAW,
    sourcePostReadRawFingerprint: PROVIDER_RAW,
    providerIdentityRawFingerprint: PROVIDER_IDENTITY_RAW,
    jarEntryPaths: clone(PROVIDER_AUTHORITY.entryPaths),
    serviceDescriptor: {
      path: PROVIDER_IDENTITY.serviceDescriptorPath,
      rawFingerprint: clone(PROVIDER_IDENTITY.serviceDescriptorFingerprint)
    },
    implementationClass: {
      path: PROVIDER_IDENTITY.implementationClassEntryPath,
      rawFingerprint: clone(PROVIDER_IDENTITY.implementationClassRawFingerprint)
    },
    destinationCreateReceipt: clone(PROVIDER_AUTHORITY.destinationReceipt.create),
    destinationOpenReadReceipt: clone(PROVIDER_AUTHORITY.destinationReceipt.openRead),
    destinationDeleteReceipt: clone(PROVIDER_AUTHORITY.destinationReceipt.delete),
    scratchBeforeCount: 0,
    scratchAfterCount: 0,
    deletionStatus: "VERIFIED",
    residualPaths: [],
    observationFingerprint: null
  };
  return setDocumentFingerprint(observation, DOMAINS.providerMaterialization, "observationFingerprint");
}

function prepareProviderAuthority() {
  rmSync(PROVIDER_FIXTURE, { recursive: true, force: true });
  const descriptorPath = PROVIDER_IDENTITY.serviceDescriptorPath;
  const classPath = PROVIDER_IDENTITY.implementationClassEntryPath;
  const descriptorBytes = Buffer.from(`${PROVIDER_IDENTITY.providerClass}\n`, "utf8");
  const classBytes = Buffer.from(
    "yv66vgAAAEUAHgoAAgADBwAEDAAFAAYBABBqYXZhL2xhbmcvT2JqZWN0AQAGPGluaXQ+AQADKClWBwAIAQA1Y29tL2xlYW5vd3RlY2gvYmxvZ2UvZ2F0ZXRja3Byb3ZpZGVyL0dhdGVBVGNrUHJvdmlkZXIHAAoBAFVjb20vbGVhbm93dGVjaC9ibG9nZS9nYXRld2F5L3Rlc3RraXQvQ2FwYWJpbGl0eVN0dWRpb1N0YWdlQWNjZXB0YW5jZUF1dGhvcml0eVByb3ZpZGVyAQAEQ29kZQEAEGV2aWRlbmNlUmVzb2x2ZXIBAGooKUxjb20vbGVhbm93dGVjaC9ibG9nZS9nYXRld2F5L3Rlc3RraXQvQ2FwYWJpbGl0eVN0dWRpb1N0YWdlQWNjZXB0YW5jZUF1dGhvcml0eVZlcmlmaWVyJEV2aWRlbmNlUmVzb2x2ZXI7AQAUZXZpZGVuY2VJc3N1ZXJQb2xpY3kBAG4oKUxjb20vbGVhbm93dGVjaC9ibG9nZS9nYXRld2F5L3Rlc3RraXQvQ2FwYWJpbGl0eVN0dWRpb1N0YWdlQWNjZXB0YW5jZUF1dGhvcml0eVZlcmlmaWVyJEV2aWRlbmNlSXNzdWVyUG9saWN5OwEADm93bmVyQXV0aG9yaXR5AQBoKClMY29tL2xlYW5vd3RlY2gvYmxvZ2UvZ2F0ZXdheS90ZXN0a2l0L0NhcGFiaWxpdHlTdHVkaW9TdGFnZUFjY2VwdGFuY2VBdXRob3JpdHlWZXJpZmllciRPd25lckF1dGhvcml0eTsBAAxJbm5lckNsYXNzZXMHABQBAGZjb20vbGVhbm93dGVjaC9ibG9nZS9nYXRld2F5L3Rlc3RraXQvQ2FwYWJpbGl0eVN0dWRpb1N0YWdlQWNjZXB0YW5jZUF1dGhvcml0eVZlcmlmaWVyJEV2aWRlbmNlUmVzb2x2ZXIHABYBAFVjb20vbGVhbm93dGVjaC9ibG9nZS9nYXRld2F5L3Rlc3RraXQvQ2FwYWJpbGl0eVN0dWRpb1N0YWdlQWNjZXB0YW5jZUF1dGhvcml0eVZlcmlmaWVyAQAQRXZpZGVuY2VSZXNvbHZlcgcAGQEAamNvbS9sZWFub3d0ZWNoL2Jsb2dlL2dhdGV3YXkvdGVzdGtpdC9DYXBhYmlsaXR5U3R1ZGlvU3RhZ2VBY2NlcHRhbmNlQXV0aG9yaXR5VmVyaWZpZXIkRXZpZGVuY2VJc3N1ZXJQb2xpY3kBABRFdmlkZW5jZUlzc3VlclBvbGljeQcAHAEAZGNvbS9sZWFub3d0ZWNoL2Jsb2dlL2dhdGV3YXkvdGVzdGtpdC9DYXBhYmlsaXR5U3R1ZGlvU3RhZ2VBY2NlcHRhbmNlQXV0aG9yaXR5VmVyaWZpZXIkT3duZXJBdXRob3JpdHkBAA5Pd25lckF1dGhvcml0eQAxAAcAAgABAAkAAAAEAAEABQAGAAEACwAAABEAAQABAAAABSq3AAGxAAAAAAABAAwADQABAAsAAAAOAAEAAQAAAAIBsAAAAAAAAQAOAA8AAQALAAAADgABAAEAAAACAbAAAAAAAAEAEAARAAEACwAAAA4AAQABAAAAAgGwAAAAAAABABIAAAAaAAMAEwAVABcGCQAYABUAGgYJABsAFQAdBgk=",
    "base64"
  );
  const entries = [
    { path: "META-INF/MANIFEST.MF", bytes: Buffer.from("Manifest-Version: 1.0\r\nCreated-By: Gate-A-D0\r\n\r\n", "ascii") },
    { path: descriptorPath, bytes: descriptorBytes },
    { path: classPath, bytes: classBytes }
  ].sort((a, b) => compareAscii(a.path, b.path));
  const jarBytes = createStoredZip(entries);
  writeExclusive(join(PROVIDER_FIXTURE, "gate-a-tck-provider.jar"), jarBytes);

  // D0 has no real /work mount. Exercise the parent-owned operation in a private
  // fixture directory, then record normalized identity fields for production replay.
  const d0Scratch = join(PROVIDER_FIXTURE, "d0-scratch");
  const d0Destination = join(d0Scratch, "provider.jar");
  mkdirSync(d0Scratch, { recursive: true });
  if (existsSync(d0Destination)) throw new Error("D0 destination was not absent before CREATE_NEW");
  writeFileSync(d0Destination, jarBytes, { flag: "wx", mode: 0o644 });
  const created = statSync(d0Destination);
  if (created.nlink !== 1 || (created.mode & 0o022) !== 0) throw new Error("D0 destination identity is unsafe");
  const openedBytes = readFileSync(d0Destination);
  const openedBefore = statSync(d0Destination);
  const openedAfter = statSync(d0Destination);
  if (!openedBytes.equals(jarBytes)
    || openedBefore.dev !== openedAfter.dev
    || openedBefore.ino !== openedAfter.ino
    || openedBefore.size !== openedAfter.size
    || openedBefore.nlink !== openedAfter.nlink
    || openedAfter.nlink !== 1) {
    throw new Error("D0 destination FSTAT_READ_FSTAT receipt is unstable");
  }
  const destinationIdentity = {
    resolvedPath: "/work/provider.jar",
    fileKey: "dev:1:ino:40001",
    owner: "uid:1000",
    group: "gid:1000",
    linkCount: 1,
    posixMode: "0644",
    fileSize: jarBytes.length,
    readRawFingerprint: rawFingerprint(jarBytes)
  };
  unlinkSync(d0Destination);
  if (existsSync(d0Destination)) throw new Error("D0 destination remained after DELETE");
  rmSync(d0Scratch, { recursive: true, force: true });

  PROVIDER_IDENTITY.serviceDescriptorFingerprint = rawFingerprint(descriptorBytes);
  PROVIDER_IDENTITY.implementationClassRawFingerprint = rawFingerprint(classBytes);
  PROVIDER_IDENTITY.identityFingerprint = null;
  setDocumentFingerprint(
    PROVIDER_IDENTITY,
    "RG-CS-GATE-A-TCK-PROVIDER-IDENTITY-v1",
    "identityFingerprint"
  );
  updateJson("valid-tck-provider-identity.json", PROVIDER_IDENTITY, true);
  PROVIDER_IDENTITY_RAW = rawFingerprint(readFileSync(join(TRUST_BUILD, "valid-tck-provider-identity.json")));
  PROVIDER_RAW = rawFingerprint(jarBytes);
  PROVIDER_CLASS_RAW = rawFingerprint(classBytes);
  CHALLENGE_PIN.expectedTckProviderRawFingerprint = PROVIDER_RAW;
  const authority = {
    entryPaths: entries.map((entry) => entry.path),
    destinationReceipt: {
      create: {
        path: "/work/provider.jar",
        createMode: "CREATE_NEW",
        createNew: true,
        preExisting: false,
        outcome: "CREATED",
        identity: destinationIdentity
      },
      openRead: {
        path: "/work/provider.jar",
        openMode: "READ_NOFOLLOW",
        readMode: "FSTAT_READ_FSTAT",
        byteLength: jarBytes.length,
        rawFingerprint: rawFingerprint(jarBytes),
        preRead: destinationIdentity,
        postRead: destinationIdentity
      },
      delete: {
        path: "/work/provider.jar",
        deleteMode: "UNLINK",
        outcome: "DELETED",
        missingAfterDelete: true,
        residualPaths: []
      }
    }
  };
  PROVIDER_AUTHORITY = authority;
  const template = providerMaterializationDocument("PROVIDER-MATERIALIZATION-TEMPLATE-001");
  authority.templateObservation = template;
  const templateBytes = jsonBytes(template);
  writeExclusive(join(PROVIDER_FIXTURE, "provider-materialization-observation.json"), templateBytes);
  authority.templateObservationRef = {
    uri: "provider-fixture/provider-materialization-observation.json",
    rawFingerprint: rawFingerprint(templateBytes)
  };
  return authority;
}

function writeRootManifest(runId) {
  const entries = rootEntries(`runs/${runId}`);
  const rootFingerprint = treeFingerprint(entries);
  const manifest = { rootKind: "GATE_A_RUN_MATERIAL", runId, entries, rootFingerprint };
  const record = writeJson(`runs/${runId}/material-root.tree`, manifest);
  return { entries, rootFingerprint, record };
}

function refMaterial(record) {
  return rawRef(`run-material/${record.relativePath}`, record.bytes);
}

function replayTestRun(templateTest, child) {
  const test = clone(templateTest);
  const vector = replayVector(child.test.testId);
  test.mutationVector = vector.mutationVector;
  test.expectedMechanism = vector.expectedMechanism;
  test.expectedTerminal = vector.expectedTerminal;
  test.expectedExitCode = vector.expectedExitCode;
  test.expectedReasonCode = vector.expectedReasonCode;
  delete test.processObservationRawFingerprint;
  test.targetArtifactRawFingerprint = CANDIDATE_RAW;
  test.inputPinRawFingerprint = CHALLENGE_PIN_RAW;
  test.harnessRawFingerprint = HARNESS_RAW;
  test.commandRecordRef = refMaterial(child.commandRecord);
  test.requestRef = refMaterial(child.requestRecord);
  test.responseRef = refMaterial(child.responseRecord);
  test.processTranscriptRef = refMaterial(child.transcriptRecord);
  test.derivedPinRef = child.derivedPinRecord ? refMaterial(child.derivedPinRecord) : null;
  test.derivedVerifierArtifactRef = child.derivedVerifierArtifactRecord
    ? refMaterial(child.derivedVerifierArtifactRecord)
    : null;
  test.derivedCandidateArtifactRef = child.derivedCandidateArtifactRecord
    ? refMaterial(child.derivedCandidateArtifactRecord)
    : null;
  test.commandFingerprint = child.commandFingerprint;
  test.requestFingerprint = child.requestFingerprint;
  test.responseFingerprint = child.responseFingerprint;
  test.stdoutRawFingerprint = rawFingerprint(child.stdoutRecord.bytes);
  test.stderrRawFingerprint = rawFingerprint(child.stderrRecord.bytes);
  test.transcriptRawFingerprint = rawFingerprint(child.transcriptRecord.bytes);
  return test;
}

function buildReplayResult(template, resultId, children, terminal, reasonCode, failedIndex = -1) {
  const result = clone(template);
  result.resultId = resultId;
  result.challengeTrustPinRawFingerprint = CHALLENGE_PIN_RAW;
  result.candidateCodeSource.rawFingerprint = CANDIDATE_RAW;
  result.verifierCodeSource.rawFingerprint = VERIFIER_RAW;
  result.replayProfileRef.rawFingerprint = clone(CHALLENGE_PIN.expectedReplayProfileRawFingerprint);
  result.tckDefinitionRef.rawFingerprint = clone(CHALLENGE_PIN.expectedTckDefinitionRawFingerprint);
  result.providerMaterializationRef = clone(PROVIDER_AUTHORITY.templateObservationRef);
  result.testRuns = result.testRuns.map((test, index) => {
    const mapped = replayTestRun(test, children[index]);
    if (index === failedIndex) {
      mapped.status = "FAIL";
      mapped.skipped = false;
      mapped.processExitCode = children[index].transcript.exitCode;
      mapped.observedTerminal = children[index].response.observedTerminal;
      mapped.closedReasonCode = children[index].response.closedReasonCode;
    }
    return mapped;
  });
  result.testCount = 9;
  result.passedCount = failedIndex < 0 ? 9 : 8;
  result.failedCount = failedIndex < 0 ? 0 : 1;
  result.skippedCount = 0;
  result.scratchBeforeCount = 0;
  result.scratchAfterCount = 0;
  result.terminal = terminal;
  result.reasonCode = reasonCode;
  result.resultFingerprint = null;
  return setDocumentFingerprint(result, DOMAINS.replayResult, "resultFingerprint");
}

function buildReplayEnvelope(template, resultRecord, producer, root, envelopeId) {
  const envelope = clone(template);
  envelope.envelopeId = envelopeId;
  envelope.challengeTrustPinRawFingerprint = CHALLENGE_PIN_RAW;
  envelope.replayProfileRawFingerprint = clone(CHALLENGE_PIN.expectedReplayProfileRawFingerprint);
  envelope.replayResultRef = refMaterial(resultRecord);
  envelope.producerProcessTranscriptRef = refMaterial(producer.transcriptRecord);
  envelope.producerMaterialRootRef = treeRef(`run-material/runs/${producer.runId}/material-root.tree`, root.rootFingerprint);
  envelope.expectedProducerCodeSourceRawFingerprint = producer.codeSource.rawFingerprint;
  envelope.observedProducerCodeSourceRawFingerprint = producer.transcript.codeSource.rawFingerprint;
  envelope.observedProcessState = producer.transcript.processState;
  envelope.observedExitCode = producer.exitCode;
  envelope.observedTerminal = producer.terminal;
  envelope.createdAt = "2026-08-21T11:59:00Z";
  envelope.envelopeFingerprint = null;
  return setDocumentFingerprint(envelope, DOMAINS.envelope, "envelopeFingerprint");
}

function buildIndependentEnvelope(template, resultRecord, harness, root, transcriptRecord = harness.transcriptRecord) {
  const envelope = clone(template);
  envelope.challengeTrustPinRawFingerprint = CHALLENGE_PIN_RAW;
  envelope.harnessProfileRawFingerprint = clone(CHALLENGE_PIN.expectedHarnessProfileRawFingerprint);
  envelope.independentResultRef = refMaterial(resultRecord);
  envelope.harnessProcessTranscriptRef = refMaterial(transcriptRecord);
  envelope.harnessMaterialRootRef = treeRef(
    `run-material/runs/${harness.runId}/material-root.tree`,
    root.rootFingerprint
  );
  envelope.expectedHarnessCodeSourceRawFingerprint = HARNESS_RAW;
  envelope.observedHarnessCodeSourceRawFingerprint = harness.transcript.codeSource.rawFingerprint;
  envelope.observedProcessState = "COMPLETED";
  envelope.observedExitCode = 0;
  envelope.createdAt = "2026-08-21T12:30:00Z";
  envelope.envelopeFingerprint = null;
  return setDocumentFingerprint(envelope, DOMAINS.independentEnvelope, "envelopeFingerprint");
}

function outerRecord(outer) {
  return {
    runId: outer.runId,
    runPurpose: outer.purpose,
    commandRecordRef: refMaterial(outer.commandRecord),
    invocationRecordRef: refMaterial(outer.invocationRecord),
    responseRef: refMaterial(outer.responseRecord),
    processTranscriptRef: refMaterial(outer.transcriptRecord),
    derivedPinRef: outer.derivedPinRecord ? refMaterial(outer.derivedPinRecord) : null,
    derivedVerifierArtifactRef: outer.derivedVerifierArtifactRecord ? refMaterial(outer.derivedVerifierArtifactRecord) : null,
    derivedCandidateArtifactRef: outer.derivedCandidateArtifactRecord ? refMaterial(outer.derivedCandidateArtifactRecord) : null,
    commandRawFingerprint: rawFingerprint(outer.commandRecord.bytes),
    invocationRawFingerprint: rawFingerprint(outer.invocationRecord.bytes),
    responseRawFingerprint: rawFingerprint(outer.responseRecord.bytes),
    stdoutRawFingerprint: rawFingerprint(outer.stdoutRecord.bytes),
    stderrRawFingerprint: rawFingerprint(outer.stderrRecord.bytes),
    processObservationRawFingerprint: rawFingerprint(outer.observationRecord.bytes),
    transcriptRawFingerprint: rawFingerprint(outer.transcriptRecord.bytes),
    processExitCode: outer.exitCode,
    observedTerminal: outer.terminal,
    closedReasonCode: outer.reasonCode,
    startedAt: outer.transcript.startedAt,
    endedAt: outer.transcript.endedAt,
    timedOut: false,
    cancelled: false
  };
}

function independentTestRun(templateTest, child) {
  const test = clone(templateTest);
  test.targetArtifactRawFingerprint = SPECIAL_TEST_IDS.has(test.testId) ? VERIFIER_RAW : CANDIDATE_RAW;
  test.inputPinRawFingerprint = CHALLENGE_PIN_RAW;
  test.harnessRawFingerprint = HARNESS_RAW;
  test.commandRecordRef = refMaterial(child.commandRecord);
  test.requestRef = refMaterial(child.requestRecord);
  test.responseRef = refMaterial(child.responseRecord);
  test.processTranscriptRef = refMaterial(child.transcriptRecord);
  test.derivedPinRef = child.derivedPinRecord ? refMaterial(child.derivedPinRecord) : null;
  test.derivedVerifierArtifactRef = child.derivedVerifierArtifactRecord
    ? refMaterial(child.derivedVerifierArtifactRecord)
    : null;
  test.derivedCandidateArtifactRef = child.derivedCandidateArtifactRecord
    ? refMaterial(child.derivedCandidateArtifactRecord)
    : null;
  test.commandFingerprint = child.commandFingerprint;
  test.requestFingerprint = child.requestFingerprint;
  test.responseFingerprint = child.responseFingerprint;
  test.stdoutRawFingerprint = rawFingerprint(child.stdoutRecord.bytes);
  test.stderrRawFingerprint = rawFingerprint(child.stderrRecord.bytes);
  test.processObservationRawFingerprint = rawFingerprint(child.observationRecord.bytes);
  test.transcriptRawFingerprint = rawFingerprint(child.transcriptRecord.bytes);
  return test;
}

function specialOuterProjection(test, outer) {
  return {
    test,
    commandRecord: outer.commandRecord,
    requestRecord: outer.invocationRecord,
    responseRecord: outer.responseRecord,
    transcriptRecord: outer.transcriptRecord,
    stdoutRecord: outer.stdoutRecord,
    stderrRecord: outer.stderrRecord,
    observationRecord: outer.observationRecord,
    derivedPinRecord: outer.derivedPinRecord,
    derivedVerifierArtifactRecord: outer.derivedVerifierArtifactRecord,
    derivedCandidateArtifactRecord: outer.derivedCandidateArtifactRecord,
    commandFingerprint: outer.command.commandFingerprint,
    requestFingerprint: outer.invocation.invocationFingerprint,
    responseFingerprint: outer.response.responseFingerprint
  };
}

function materialAggregate(outers, tests) {
  const process = (record) => ({
    runId: record.runId,
    commandRawFingerprint: rawFingerprint(record.commandRecord.bytes),
    invocationRawFingerprint: rawFingerprint(record.invocationRecord.bytes),
    responseRawFingerprint: rawFingerprint(record.responseRecord.bytes),
    processObservationRawFingerprint: rawFingerprint(record.observationRecord.bytes),
    stdoutRawFingerprint: rawFingerprint(record.stdoutRecord.bytes),
    stderrRawFingerprint: rawFingerprint(record.stderrRecord.bytes),
    transcriptRawFingerprint: rawFingerprint(record.transcriptRecord.bytes),
    derivedPinRawFingerprint: rawOrNull(record.derivedPinRecord),
    derivedVerifierArtifactRawFingerprint: rawOrNull(record.derivedVerifierArtifactRecord),
    derivedCandidateArtifactRawFingerprint: rawOrNull(record.derivedCandidateArtifactRecord)
  });
  const child = (record) => ({
    testId: record.test.testId,
    commandRawFingerprint: rawFingerprint(record.commandRecord.bytes),
    requestRawFingerprint: rawFingerprint(record.requestRecord.bytes),
    responseRawFingerprint: rawFingerprint(record.responseRecord.bytes),
    processObservationRawFingerprint: rawFingerprint(record.observationRecord.bytes),
    stdoutRawFingerprint: rawFingerprint(record.stdoutRecord.bytes),
    stderrRawFingerprint: rawFingerprint(record.stderrRecord.bytes),
    transcriptRawFingerprint: rawFingerprint(record.transcriptRecord.bytes),
    derivedPinRawFingerprint: null,
    derivedVerifierArtifactRawFingerprint: null,
    derivedCandidateArtifactRawFingerprint: null
  });
  return {
    verificationProcessRuns: outers.map(process),
    testRuns: tests.map(child)
  };
}

function commitment(domain, value, kind = "AGGREGATE_COMMITMENT") {
  return fingerprint(kind, rawSha256(Buffer.concat([
    Buffer.from(domain, "ascii"),
    Buffer.from([0]),
    Buffer.from(canonicalize(value), "utf8")
  ])));
}

function updateNegativeFixtures(baseName, baseValue, names, paths) {
  for (let index = 0; index < names.length; index++) {
    const name = names[index];
    const oldNegative = readJson(name);
    updateJson(name, deriveNegative(baseValue, oldNegative, paths[index]));
  }
}

function updateRunMaterialAttackManifest() {
  const vectors = readJson("run-material-attack-vectors.json");
  const additions = [
    {
      vectorId: "CHILD_LAUNCH_REQUEST_ORDINAL_STITCH",
      source: "run-material/runs/RUN-A1-REPLAY-VERIFIED-001/producer/children/01-placeholder-rejected/command.json",
      expectedCode: "A1_LAUNCH_ARGUMENTS_DRIFT"
    },
    {
      vectorId: "OUTER_LAUNCH_ARGUMENT_STITCH",
      source: "run-material/runs/RUN-A1-REPLAY-VERIFIED-001/command.json",
      expectedCode: "A1_LAUNCH_ARGUMENTS_DRIFT"
    },
    {
      vectorId: "PROVIDER_DESTINATION_IDENTITY_REPLACEMENT",
      source: "run-material/runs/RUN-A1-REPLAY-VERIFIED-001/producer/provider-materialization.json",
      expectedCode: "A1_PROVIDER_DESTINATION_IDENTITY_DRIFT"
    },
    {
      vectorId: "PROTOCOL_SOURCE_PROJECTION_REBOUND",
      source: "valid-challenge-sandbox-profile.json",
      expectedCode: "A1_PROTOCOL_AUTHORITY_PIN_DRIFT"
    },
    {
      vectorId: "REPLAY_VECTOR_RECIPE_INPUTSET_REBOUND",
      source: "valid-challenge-sandbox-profile.json",
      expectedCode: "A1_REPLAY_VECTOR_REGISTRY_DRIFT"
    },
    {
      vectorId: "REPLAY_PROOF_MISSING_MESSAGE_VERSION",
      source: "run-material/reports/A1-REPORT-GENERATED-001/derived/replay-proof-envelope.json",
      expectedCode: "A1_MISSING_MESSAGE_VERSION"
    },
    {
      vectorId: "MATERIAL_FILE_BUDGET",
      source: "valid-replay-proof-envelope.json",
      expectedCode: "A1_MATERIAL_FILE_SIZE_LIMIT_EXCEEDED"
    },
    {
      vectorId: "MATERIAL_TREE_ENTRY_BUDGET",
      source: "valid-replay-proof-envelope.json",
      expectedCode: "A1_MATERIAL_TREE_ENTRY_COUNT_LIMIT_EXCEEDED"
    },
    {
      vectorId: "MATERIAL_TREE_TOTAL_BUDGET",
      source: "valid-replay-proof-envelope.json",
      expectedCode: "A1_MATERIAL_TREE_TOTAL_BYTES_LIMIT_EXCEEDED"
    },
    {
      vectorId: "MATERIAL_PARENT_DIRECTORY_SYMLINK",
      source: "valid-replay-proof-envelope.json",
      expectedCode: "A1_MATERIAL_SYMLINK_REJECTED"
    },
    {
      vectorId: "MATERIAL_PARENT_DIRECTORY_REPLACEMENT",
      source: "valid-replay-proof-envelope.json",
      expectedCode: "A1_TREE_ENTRY_SET_DRIFT"
    },
    {
      vectorId: "MATERIAL_POST_INVENTORY_ENTRY_DRIFT",
      source: "valid-replay-proof-envelope.json",
      expectedCode: "A1_TREE_ENTRY_SET_DRIFT"
    }
  ];
  for (const addition of additions) {
    const index = vectors.findIndex((vector) => vector.vectorId === addition.vectorId);
    if (index < 0) vectors.push(addition);
    else vectors[index] = addition;
  }
  updateJson("run-material-attack-vectors.json", vectors);
}

function main() {
  PROVIDER_AUTHORITY = prepareProviderAuthority();
  CHALLENGE_INPUT_AUTHORITY = prepareChallengeInputAuthority();
  CHALLENGE_PIN.expectedProtocolAuthorityRawFingerprint = rawFingerprint(readFileSync(PROTOCOL_SOURCE));
  CHALLENGE_PIN.expectedChallengeInputRootFingerprint = clone(CHALLENGE_INPUT_AUTHORITY.rootFingerprint);
  CHALLENGE_PIN.challengeTrustPinFingerprint = null;
  setDocumentFingerprint(CHALLENGE_PIN, "RG-CS-GATE-A-CHALLENGE-TRUST-PIN-v1", "challengeTrustPinFingerprint");
  updateJson("valid-challenge-trust-pin.json", CHALLENGE_PIN, true);
  CHALLENGE_PIN_RAW = rawFingerprint(readFileSync(join(TRUST_BUILD, "valid-challenge-trust-pin.json")));
  LAUNCH_CONTRACTS = loadLaunchContracts();
  REPLAY_REGISTRY = loadReplayVectorRegistry();
  const abnormalTransitions = load(PROTOCOL_SOURCE).abnormalTransitions;
  TEST_IDS = REPLAY_REGISTRY.replayVectors.map((item) => item.testId);
  const projectedInputSet = REPLAY_REGISTRY.inputSets.find((item) => item.inputSetId === REPLAY_REGISTRY.replayVectors[0].inputSetId);
  if (!projectedInputSet || projectedInputSet.fixtureRootUri !== CHALLENGE_INPUT_AUTHORITY.treeUri ||
      projectedInputSet.fixtureRootFingerprint !== CHALLENGE_INPUT_AUTHORITY.rootFingerprint.value ||
      !Buffer.from(canonicalize(projectedInputSet.exactRefs)).equals(Buffer.from(canonicalize(CHALLENGE_INPUT_AUTHORITY.inputExactRefs.map((item) => ({
        role: item.inputRole,
        uri: item.exactRef.uri,
        rawFingerprint: item.exactRef.rawFingerprint.value
      })))))) {
    throw new Error("Protocol replay input projection drift");
  }
  REQUEST.fixtureRootRef.fingerprint = clone(CHALLENGE_INPUT_AUTHORITY.rootFingerprint);
  REQUEST.inputExactRefs = clone(CHALLENGE_INPUT_AUTHORITY.inputExactRefs);
  REQUEST.requestFingerprint = null;
  setDocumentFingerprint(REQUEST, DOMAINS.request, "requestFingerprint");

  rmSync(RUN_MATERIAL, { recursive: true, force: true });
  mkdirSync(RUN_MATERIAL, { recursive: true });

  const replayChildren = [];
  const unavailableChildren = [];
  for (let index = 0; index < TEST_IDS.length; index++) {
    const test = REPLAY_REGISTRY.replayVectors[index];
    replayChildren.push(writeChild({
      runId: "RUN-A1-REPLAY-VERIFIED-001",
      ordinal: index + 1,
      test,
      family: "REPLAY",
      parentOffset: 1,
      a0Template: A0_RESULT
    }));
    unavailableChildren.push(writeChild({
      runId: "RUN-A1-REPLAY-UNAVAILABLE-001",
      ordinal: index + 1,
      test,
      family: "REPLAY",
      parentOffset: 2,
      overrideTerminal: index === 0 ? { terminal: "UNAVAILABLE", exitCode: 3, reasonCode: "A0_UNAVAILABLE" } : null,
      a0Template: A0_RESULT
    }));
  }

  const replayVerified = buildReplayResult(REPLAY_RESULT, "A1-REPLAY-GENERATED-VERIFIED-001", replayChildren, "VERIFIED", "A1_REPLAY_VERIFIED");
  const replayUnavailable = buildReplayResult(REPLAY_RESULT_UNAVAILABLE, "A1-REPLAY-GENERATED-UNAVAILABLE-001", unavailableChildren, "UNAVAILABLE", "A1_REPLAY_UNAVAILABLE", 0);
  const replayProducer = writeOuter({
    runId: "RUN-A1-REPLAY-VERIFIED-001",
    purpose: "NORMAL_A1",
    launchKind: "A1_VERIFIER",
    codeSourceRaw: VERIFIER_RAW,
    result: replayVerified,
    parentOffset: 10,
    startedAt: "2026-08-21T10:00:00Z",
    endedAt: "2026-08-21T10:02:00Z"
  });
  const unavailableProducer = writeOuter({
    runId: "RUN-A1-REPLAY-UNAVAILABLE-001",
    purpose: "NORMAL_A1",
    launchKind: "A1_VERIFIER",
    codeSourceRaw: VERIFIER_RAW,
    result: replayUnavailable,
    parentOffset: 11,
    startedAt: "2026-08-21T10:00:00Z",
    endedAt: "2026-08-21T10:03:00Z"
  });
  const verifiedResultRecord = replayProducer.responseRecord;
  const unavailableResultRecord = unavailableProducer.responseRecord;

  // A separate root keeps attack-only bytes out of every valid producer tree.
  const parentUnavailableProducer = writeOuter({
    runId: "RUN-A1-PARENT-UNAVAILABLE-ATTACK-001",
    purpose: "NORMAL_A1",
    launchKind: "A1_VERIFIER",
    codeSourceRaw: VERIFIER_RAW,
    result: replayUnavailable,
    outcome: { terminal: "UNAVAILABLE", exitCode: 3, reasonCode: "A1_REPLAY_UNAVAILABLE" },
    parentOffset: 12,
    processState: "UNAVAILABLE",
    transcriptExitCode: 255,
    startedAt: "2026-08-21T10:04:00Z",
    endedAt: "2026-08-21T10:04:00Z"
  });

  const verifiedRoot = writeRootManifest(replayProducer.runId);
  const unavailableRoot = writeRootManifest(unavailableProducer.runId);
  const parentUnavailableRoot = writeRootManifest(parentUnavailableProducer.runId);

  const proof = buildReplayEnvelope(REPLAY_ENVELOPE, verifiedResultRecord, replayProducer, verifiedRoot, "A1-ENVELOPE-GENERATED-VERIFIED-001");
  const unavailableProof = buildReplayEnvelope(REPLAY_ENVELOPE_UNAVAILABLE, unavailableResultRecord, unavailableProducer, unavailableRoot, "A1-ENVELOPE-GENERATED-UNAVAILABLE-001");
  const parentUnavailableAttack = clone(unavailableProof);
  parentUnavailableAttack.replayResultRef = refMaterial(parentUnavailableProducer.responseRecord);
  parentUnavailableAttack.producerProcessTranscriptRef = refMaterial(parentUnavailableProducer.transcriptRecord);
  parentUnavailableAttack.producerMaterialRootRef = treeRef(
    `run-material/runs/${parentUnavailableProducer.runId}/material-root.tree`,
    parentUnavailableRoot.rootFingerprint
  );
  parentUnavailableAttack.envelopeFingerprint = null;
  setDocumentFingerprint(parentUnavailableAttack, DOMAINS.envelope, "envelopeFingerprint");

  const independentNormal = REPLAY_REGISTRY.replayVectors;
  const allIndependent = TCK.tests;
  const independentNormalChildren = independentNormal.map((test, index) => writeChild({
      runId: "RUN-A1-INDEPENDENT-NORMAL-001",
      ordinal: index + 1,
      test,
      family: "INDEPENDENT",
      parentOffset: 30,
      a0Template: A0_RESULT
    }));
  const normalIndependentReplay = buildReplayResult(
    REPLAY_RESULT,
    "A1-REPLAY-INDEPENDENT-NORMAL-001",
    independentNormalChildren,
    "VERIFIED",
    "A1_REPLAY_VERIFIED"
  );

  const outerSpecs = [
    ["RUN-A1-INDEPENDENT-NORMAL-001", "NORMAL_A1", 20, "VERIFIED", 0, "A1_REPLAY_VERIFIED", normalIndependentReplay, true],
    ["RUN-A1-INDEPENDENT-DIGEST-001", "WRONG_VERIFIER_DIGEST_A1", 21, "INVALID", 2, "VERIFIER_DIGEST_MUTATION", null, false],
    ["RUN-A1-INDEPENDENT-REGISTRY-001", "REGISTRY_MUTATION_A1", 22, "INVALID", 2, "REGISTRY_MUTATION", null, false],
    ["RUN-A1-INDEPENDENT-TCK-001", "TCK_MISMATCH_A1", 23, "INVALID", 2, "VERIFIER_TCK_MISMATCH", null, false],
    ["RUN-A1-INDEPENDENT-PROVIDER-001", "PROVIDER_COLLISION_A1", 24, "INVALID", 2, "PROVIDER_NAMESPACE_COLLISION", null, true]
  ];
  const independentOuter = outerSpecs.map(([runId, purpose, offset, terminal, exitCode, reasonCode, replayResult, materializeProvider], index) => writeOuter({
    runId,
    purpose,
    launchKind: "A1_VERIFIER",
    codeSourceRaw: VERIFIER_RAW,
    result: replayResult ?? makeBootstrapResponse(runId, VERIFIER_RAW, "REJECTED"),
    materializeProvider,
    outcome: { terminal, exitCode, reasonCode },
    parentOffset: offset,
    startedAt: index === 0 ? "2026-08-21T10:29:00Z" : `2026-08-21T10:${String(31 + index).padStart(2, "0")}:00Z`,
    endedAt: index === 0 ? "2026-08-21T10:31:00Z" : `2026-08-21T10:${String(31 + index).padStart(2, "0")}:30Z`
  }));
  const normalIndependentRoot = writeRootManifest(independentOuter[0].runId);
  const normalByTestId = new Map(independentNormalChildren.map((child) => [child.test.testId, child]));
  const specialOuterByTestId = new Map([
    ["VERIFIER_DIGEST_MUTATION_REJECTED", independentOuter[1]],
    ["REGISTRY_MUTATION_REJECTED", independentOuter[2]],
    ["VERIFIER_TCK_MISMATCH_REJECTED", independentOuter[3]]
  ]);
  const reportRoot = "reports/A1-REPORT-GENERATED-001";
  writeJson(`${reportRoot}/derived/replay-proof-envelope.json`, buildReplayEnvelope(
    REPLAY_ENVELOPE,
    independentOuter[0].responseRecord,
    independentOuter[0],
    normalIndependentRoot,
    "A1-ENVELOPE-INDEPENDENT-NORMAL-001"
  ));
  independentOuter[1].derivedVerifierArtifactRecord = writeBytes(`${reportRoot}/derived/decoy-verifier.jar`, Buffer.from("decoy-verifier-v1\n", "ascii"));
  independentOuter[2].derivedPinRecord = writeBytes(`${reportRoot}/derived/registry.pin`, Buffer.from("registry-mutation-pin-v1\n", "ascii"));
  independentOuter[2].derivedVerifierArtifactRecord = writeBytes(`${reportRoot}/derived/mutated-registry.jar`, Buffer.from("mutated-registry-v1\n", "ascii"));
  independentOuter[3].derivedPinRecord = writeBytes(`${reportRoot}/derived/tck.pin`, Buffer.from("tck-mismatch-pin-v1\n", "ascii"));
  independentOuter[4].derivedPinRecord = writeBytes(`${reportRoot}/derived/provider.pin`, Buffer.from("provider-collision-pin-v1\n", "ascii"));
  independentOuter[4].derivedCandidateArtifactRecord = writeBytes(`${reportRoot}/derived/colliding-candidate.jar`, Buffer.from("colliding-candidate-v1\n", "ascii"));

  const independentTests = allIndependent.map((test) => normalByTestId.get(test.testId)
    ?? specialOuterProjection(test, specialOuterByTestId.get(test.testId)));

  const processAggregate = materialAggregate(independentOuter, independentTests);
  writeJson(`${reportRoot}/derived/process-material-aggregate.json`, {
    aggregate: processAggregate,
    aggregateFingerprint: commitment(DOMAINS.materialAggregate, processAggregate)
  });
  const evidenceAggregateValue = independentTests.map((test) => ({
    testId: test.test.testId,
    evidenceRawFingerprint: rawFingerprint(test.transcriptRecord.bytes)
  }));
  writeJson(`${reportRoot}/derived/test-evidence-aggregate.json`, {
    entries: evidenceAggregateValue,
    aggregateFingerprint: commitment(DOMAINS.evidenceAggregate, evidenceAggregateValue)
  });

  const independentRunRoots = [
    ...independentOuter.map((outer) => `runs/${outer.runId}`),
    `${reportRoot}/derived`
  ];
  const independentRootEntries = scopedRootEntries(independentRunRoots);
  const independentRootFingerprint = treeFingerprint(independentRootEntries);
  writeJson(`${reportRoot}/material-root.tree`, {
    rootKind: "GATE_A_INDEPENDENT_REPORT_MATERIAL",
    runId: "A1-REPORT-GENERATED-001",
    scopeRoots: independentRunRoots,
    entries: independentRootEntries,
    rootFingerprint: independentRootFingerprint
  });

  const independent = clone(INDEPENDENT_RESULT);
  independent.resultId = "A1-REPORT-GENERATED-001";
  independent.challengeTrustPinRawFingerprint = CHALLENGE_PIN_RAW;
  independent.candidateRawFingerprint = CANDIDATE_RAW;
  independent.candidateSpiRawFingerprint = clone(CHALLENGE_PIN.expectedCandidateSpiArtifactRawFingerprint);
  independent.verifierRawFingerprint = VERIFIER_RAW;
  independent.verifierCodeSource.rawFingerprint = VERIFIER_RAW;
  independent.harnessRawFingerprint = HARNESS_RAW;
  independent.harnessCodeSource.rawFingerprint = HARNESS_RAW;
  independent.providerRawFingerprint = PROVIDER_RAW;
  independent.replayProfileRawFingerprint = clone(CHALLENGE_PIN.expectedReplayProfileRawFingerprint);
  independent.harnessProfileRawFingerprint = clone(CHALLENGE_PIN.expectedHarnessProfileRawFingerprint);
  independent.schemaSetRawFingerprint = SCHEMA_SET_MANIFEST_RAW;
  independent.tckRawFingerprint = clone(CHALLENGE_PIN.expectedTckDefinitionRawFingerprint);
  independent.runMaterialRootFingerprint = independentRootFingerprint;
  independent.verificationProcessRuns = independent.verificationProcessRuns.map((entry, index) => {
    return outerRecord(independentOuter[index]);
  });
  independent.testRuns = independent.testRuns.map((test, index) => independentTestRun(test, independentTests[index]));
  independent.mandatoryGuards.providerNamespaceCollision.runRef = refMaterial(independentOuter[4].transcriptRecord);
  const independentTranscripts = [
    ...independentOuter.map((outer) => outer.transcript),
    ...independentNormalChildren.map((child) => child.transcript)
  ];
  independent.startedAt = independentTranscripts.map((transcript) => transcript.startedAt).sort(compareAscii)[0];
  independent.endedAt = independentTranscripts.map((transcript) => transcript.endedAt).sort(compareAscii).at(-1);
  independent.resultFingerprint = null;
  setDocumentFingerprint(independent, DOMAINS.independentResult, "resultFingerprint");

  const harness = writeOuter({
    runId: "RUN-HARNESS-INDEPENDENT-001",
    purpose: "HARNESS_RUN",
    launchKind: "CONFORMANCE_HARNESS",
    codeSourceRaw: HARNESS_RAW,
    result: independent,
    outcome: { terminal: "VERIFIED", exitCode: 0, reasonCode: "HARNESS_REPORT_COMPLETE" },
    parentOffset: 29,
    harness: true,
    startedAt: "2026-08-21T10:40:00Z",
    endedAt: "2026-08-21T10:41:00Z"
  });
  const harnessRoot = writeRootManifest(harness.runId);
  const independentProof = buildIndependentEnvelope(
    INDEPENDENT_ENVELOPE,
    harness.responseRecord,
    harness,
    harnessRoot
  );

  const bootstrapAttackHarness = writeOuter({
    runId: "RUN-HARNESS-BOOTSTRAP-ATTACK-001",
    purpose: "HARNESS_RUN",
    launchKind: "CONFORMANCE_HARNESS",
    codeSourceRaw: HARNESS_RAW,
    result: independent,
    stdoutResponse: makeBootstrapResponse("RUN-HARNESS-BOOTSTRAP-ATTACK-001", HARNESS_RAW),
    parentOffset: 28,
    harness: true,
    startedAt: "2026-08-21T10:42:00Z",
    endedAt: "2026-08-21T10:42:30Z"
  });
  const bootstrapAttackRoot = writeRootManifest(bootstrapAttackHarness.runId);
  const bootstrapAttackProof = buildIndependentEnvelope(
    INDEPENDENT_ENVELOPE,
    bootstrapAttackHarness.responseRecord,
    bootstrapAttackHarness,
    bootstrapAttackRoot
  );

  const generatedA0 = replayChildren.at(-1).response.operationResult;
  updateJson("valid-candidate-replay-result.json", generatedA0);
  updateJson("valid-candidate-challenge-request.json", REQUEST);
  updateJson("valid-provider-materialization-observation.json", PROVIDER_AUTHORITY.templateObservation);
  const invalidProviderScratch = clone(PROVIDER_AUTHORITY.templateObservation);
  invalidProviderScratch.scratchBeforeCount = 1;
  updateJson("invalid-provider-materialization-scratch-before.json", invalidProviderScratch);
  const invalidProviderDeclarationOnly = clone(PROVIDER_AUTHORITY.templateObservation);
  delete invalidProviderDeclarationOnly.destinationOpenReadReceipt;
  updateJson("invalid-provider-materialization-missing-receipt.json", invalidProviderDeclarationOnly);
  const invalidProviderIdentityReplacement = clone(PROVIDER_AUTHORITY.templateObservation);
  invalidProviderIdentityReplacement.destinationOpenReadReceipt.preRead.fileKey = "dev:1:ino:49999";
  invalidProviderIdentityReplacement.destinationOpenReadReceipt.postRead.fileKey = "dev:1:ino:49999";
  invalidProviderIdentityReplacement.observationFingerprint = null;
  setDocumentFingerprint(invalidProviderIdentityReplacement, DOMAINS.providerMaterialization, "observationFingerprint");
  updateJson("invalid-provider-materialization-identity-replacement.json", invalidProviderIdentityReplacement);
  updateJson("valid-replay-verification-result.json", replayProducer.response);
  updateJson("valid-replay-verification-result-invalid.json", buildReplayResult(REPLAY_RESULT_INVALID, "A1-REPLAY-GENERATED-INVALID-001", replayChildren, "INVALID", "A1_REPLAY_INVALID", 0));
  updateJson("valid-replay-verification-result-unavailable.json", unavailableProducer.response);
  updateJson("valid-replay-proof-envelope.json", proof);
  updateJson("valid-replay-proof-envelope-unavailable.json", unavailableProof);
  updateJson("invalid-replay-proof-envelope-parent-unavailable.json", parentUnavailableAttack);
  updateJson("valid-independent-verification-result.json", independent);
  updateJson("valid-independent-proof-envelope.json", independentProof);
  updateJson("invalid-independent-proof-envelope-harness-bootstrap.json", bootstrapAttackProof);

  updateNegativeFixtures(
    "valid-candidate-replay-result.json",
    generatedA0,
    ["invalid-candidate-replay-adapter-drift.json", "invalid-candidate-replay-obligation-drift.json"],
    ["$.adapterResults[0].adapterKind", "$.obligationResults[0].obligationId"]
  );
  updateNegativeFixtures(
    "valid-independent-verification-result.json",
    independent,
    [
      "invalid-independent-missing-process-observation-fingerprint.json",
      "invalid-independent-process-observation-fingerprint-reused.json",
      "invalid-independent-review-guard-intrusion.json",
      "invalid-independent-wrong-exit.json",
      "invalid-independent-wrong-mechanism.json",
      "invalid-independent-wrong-terminal.json",
      "invalid-provider-guard-accepted.json"
    ],
    [
      "$.verificationProcessRuns[0].processObservationRawFingerprint",
      "$.testRuns[0].processObservationRawFingerprint",
      "$.mandatoryGuards.reviewCountConsistency",
      "$.testRuns[0].processExitCode",
      "$.testRuns[0].expectedMechanism",
      "$.testRuns[0].observedTerminal",
      "$.mandatoryGuards.providerNamespaceCollision.observedOutcome"
    ]
  );
  const elevenChildRuns = clone(independent);
  elevenChildRuns.testRuns = elevenChildRuns.testRuns.slice(0, 11);
  updateJson("invalid-independent-report-eleven-child-runs.json", elevenChildRuns);
  const reusedObservation = clone(independent);
  reusedObservation.testRuns[0].processObservationRawFingerprint = clone(
    reusedObservation.verificationProcessRuns[0].processObservationRawFingerprint
  );
  updateJson("invalid-independent-process-observation-fingerprint-reused.json", reusedObservation);
  updateAbnormalAttemptFixtures(abnormalTransitions);
  updateRunMaterialAttackManifest();

  const fileCount = RESULTS.size;
  const runCount = new Set([...RESULTS.keys()].filter((path) => path.startsWith("runs/")).map((path) => path.split("/")[1])).size;
  console.log(`Generated run-material: ${runCount} runs, ${fileCount} files`);
}

const REQUEST = readJson("valid-candidate-challenge-request.json");
const TYPED_RESPONSE = readJson("valid-candidate-challenge-response.json");
const LEGACY_RESPONSE = readJson("valid-candidate-challenge-response-legacy.json");
const A0_RESULT = readJson("valid-candidate-replay-result.json");
const PROCESS_COMMAND = readJson("valid-process-command.json");
const OBSERVATION = readJson("valid-process-observation.json");
const PROCESS_TRANSCRIPT = readJson("valid-process-transcript.json");
const HARNESS_TRANSCRIPT = readJson("valid-harness-process-transcript.json");
const A1_INVOCATION = readJson("valid-a1-invocation.json");
const HARNESS_INVOCATION = readJson("valid-harness-invocation.json");
const BOOTSTRAP_RESPONSE = readJson("valid-a1-bootstrap-response.json");
const REPLAY_RESULT = readJson("valid-replay-verification-result.json");
const REPLAY_RESULT_INVALID = readJson("valid-replay-verification-result-invalid.json");
const REPLAY_RESULT_UNAVAILABLE = readJson("valid-replay-verification-result-unavailable.json");
const REPLAY_ENVELOPE = readJson("valid-replay-proof-envelope.json");
const REPLAY_ENVELOPE_UNAVAILABLE = readJson("valid-replay-proof-envelope-unavailable.json");
const INDEPENDENT_RESULT = readJson("valid-independent-verification-result.json");
const INDEPENDENT_ENVELOPE = readJson("valid-independent-proof-envelope.json");
const TCK = readJson("valid-tck.json");
const PROVIDER_IDENTITY = readJson("valid-tck-provider-identity.json", true);
const CHALLENGE_PIN = readJson("valid-challenge-trust-pin.json", true);
let CHALLENGE_PIN_RAW = rawFingerprint(readFileSync(join(TRUST_BUILD, "valid-challenge-trust-pin.json")));
const SCHEMA_SET_MANIFEST_RAW = rawFingerprint(readFileSync(join(TRUST_BUILD, "valid-schema-set-manifest.json")));
let CHALLENGE_INPUT_AUTHORITY;
let PROVIDER_AUTHORITY;
let PROVIDER_IDENTITY_RAW;
const SANDBOX_PROFILE_BYTES = readFileSync(join(HERE, "valid-challenge-sandbox-profile.json"));
const SANDBOX_RAW = rawFingerprint(SANDBOX_PROFILE_BYTES);
const CANDIDATE_RAW = clone(CHALLENGE_PIN.expectedImplementationCandidateRawFingerprint);
const VERIFIER_RAW = clone(CHALLENGE_PIN.expectedIndependentVerifierRawFingerprint);
const HARNESS_RAW = clone(CHALLENGE_PIN.expectedConformanceHarnessRawFingerprint);
let PROVIDER_RAW = clone(CHALLENGE_PIN.expectedTckProviderRawFingerprint);
let PROVIDER_CLASS_RAW = clone(PROVIDER_IDENTITY.implementationClassRawFingerprint);
const SPI_CLASS_RAW = clone(CHALLENGE_PIN.expectedCandidateSpiClassRawFingerprint);
let LAUNCH_CONTRACTS;
let REPLAY_REGISTRY;

if (process.argv.includes("--abnormal-only")) {
  LAUNCH_CONTRACTS = Object.fromEntries(load(PROTOCOL_SOURCE).launchContracts.map((item) => [item.launchKind, item]));
  updateAbnormalAttemptFixtures(load(PROTOCOL_SOURCE).abnormalTransitions);
  console.log("Generated abnormal-transition fixtures: 6 positive, 6 negative");
} else {
  main();
}
