import {
  createHash,
  createPublicKey,
  verify as verifySignature,
} from "node:crypto";

type JsonObject = Record<string, unknown>;

export type VerificationOutcome =
  | "VERIFIED"
  | "INVALID"
  | "POLICY_REJECTED";

export interface VerificationResult {
  outcome: VerificationOutcome;
  reasonCode: string;
  observationId: string;
  observationFingerprint: string;
  unitId: string;
  reconciliation: string;
  keyId: string;
}

const FIXTURE_VERSION =
  "resourceGateway.authoritativeOutcomeObservationCompatibility.v1";
const OBSERVATION_VERSION =
  "resourceGateway.authoritativeOutcomeObservation.v1";
const KEY_VERSION =
  "toolStudio.resourceGateway.evidenceVerificationKey.v1";
const SEAL_VERSION = "bloge.visualRunEvidenceSeal.v1";
const NANOSECONDS_PER_SECOND = 1_000_000_000n;
const MAXIMUM_CLOCK_SKEW = 2n * 60n * NANOSECONDS_PER_SECOND;
const KEY_CREATION_SKEW = 5n * 60n * NANOSECONDS_PER_SECOND;
const MAXIMUM_ATTRIBUTION_WINDOW =
  365n * 24n * 60n * 60n * NANOSECONDS_PER_SECOND;
const MAXIMUM_OBSERVATION_BYTES = 4 * 1024 * 1024;
const MAXIMUM_ATTESTATION_BYTES = 16 * 1024;
const IDENTIFIER = /^[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}$/;
const FINGERPRINT = /^sha256:[a-f0-9]{64}$/;
const INSTANT =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3}(?:\d{3}(?:\d{3})?)?)?Z$/;

const FIXTURE_FIELDS = [
  "schemaVersion",
  "verificationTime",
  "verificationKey",
  "observation",
] as const;
const KEY_FIELDS = [
  "schemaVersion",
  "keyId",
  "algorithm",
  "encodedPublicKey",
  "createdAt",
  "state",
  "provider",
] as const;
const OBSERVATION_FIELDS = [
  "schemaVersion",
  "observationId",
  "revision",
  "observationFingerprint",
  "scope",
  "inventoryRef",
  "unitId",
  "scenarioCaseRef",
  "targetCapabilityRef",
  "outcomeDefinitionRef",
  "attributionPolicyRef",
  "authoritySetRef",
  "selectionProof",
  "subjectFingerprint",
  "attributionKeyFingerprint",
  "modelOutcomeFingerprint",
  "attributionWindow",
  "reconciledAt",
  "attestedAt",
  "authorityWatermarks",
  "authorityFacts",
  "reconciliation",
  "evidenceComplete",
  "observationSeal",
] as const;
const SCOPE_FIELDS = [
  "tenantId",
  "organizationId",
  "projectId",
  "environmentId",
  "region",
] as const;
const ARTIFACT_FIELDS = ["kind", "id", "revision", "fingerprint"] as const;
const SELECTION_FIELDS = [
  "cohortRef",
  "samplingFrameRef",
  "stratumId",
  "inclusionFingerprint",
  "selectedAt",
  "eligiblePopulationSize",
  "selectedPopulationSize",
  "sampleOrdinal",
  "selectionMode",
] as const;
const WINDOW_FIELDS = [
  "actionOccurredAt",
  "opensAt",
  "closesAt",
] as const;
const WATERMARK_FIELDS = [
  "authorityId",
  "watermarkRef",
  "eventTimeThrough",
  "publishedAt",
] as const;
const FACT_FIELDS = [
  "authorityId",
  "sourceRef",
  "subjectFingerprint",
  "attributionKeyFingerprint",
  "outcomeFingerprint",
  "occurredAt",
  "recordedAt",
  "evidenceComplete",
] as const;
const SEAL_FIELDS = [
  "schemaVersion",
  "materialFingerprint",
  "algorithm",
  "keyId",
  "signedAt",
  "signature",
] as const;

class VerificationFailure extends Error {
  readonly reasonCode: string;

  constructor(reasonCode: string) {
    super(reasonCode);
    this.reasonCode = reasonCode;
  }
}

export function verifyFixture(value: unknown): VerificationResult {
  const coordinates = coordinatesFrom(value);
  try {
    const fixture = object(value, "OUTCOME_OBSERVATION_SCHEMA_INVALID");
    exactFields(
      fixture,
      FIXTURE_FIELDS,
      "OUTCOME_OBSERVATION_SCHEMA_INVALID",
    );
    requireText(fixture, "schemaVersion", FIXTURE_VERSION);
    const verificationTime = instant(
      fixture.verificationTime,
      "OUTCOME_OBSERVATION_VERIFICATION_TIME_INVALID",
    );
    const key = object(
      fixture.verificationKey,
      "OUTCOME_OBSERVATION_SCHEMA_INVALID",
    );
    exactFields(key, KEY_FIELDS, "OUTCOME_OBSERVATION_SCHEMA_INVALID");
    requireText(key, "schemaVersion", KEY_VERSION);
    verifyKeyShape(key);
    const observation = object(
      fixture.observation,
      "OUTCOME_OBSERVATION_SCHEMA_INVALID",
    );

    verifyShape(observation);
    const times = verifySemantics(observation);
    verifyFingerprint(observation);
    verifySeal(observation, key, times.attestedAt, verificationTime);
    return result("VERIFIED", "VERIFIED", coordinates);
  } catch (failure) {
    return result(
      failure instanceof VerificationFailure &&
        isPolicyReason(failure.reasonCode)
        ? "POLICY_REJECTED"
        : "INVALID",
      failure instanceof VerificationFailure
        ? failure.reasonCode
        : "OUTCOME_OBSERVATION_CLOSURE_INVALID",
      coordinates,
    );
  }
}

function verifyShape(observation: JsonObject): void {
  exactFields(
    observation,
    OBSERVATION_FIELDS,
    "OUTCOME_OBSERVATION_SCHEMA_INVALID",
  );
  requireText(observation, "schemaVersion", OBSERVATION_VERSION);
  identifier(observation.observationId);
  identifier(observation.unitId);
  positiveInteger(observation.revision);
  fingerprint(observation.observationFingerprint);
  exactFields(
    object(observation.scope, "OUTCOME_OBSERVATION_SCHEMA_INVALID"),
    SCOPE_FIELDS,
    "OUTCOME_OBSERVATION_SCHEMA_INVALID",
  );
  for (const field of SCOPE_FIELDS) {
    identifier(
      object(observation.scope, "OUTCOME_OBSERVATION_SCHEMA_INVALID")[field],
    );
  }
  const references: Array<[string, string]> = [
    ["inventoryRef", "DOMAIN_FIDELITY_INVENTORY"],
    ["scenarioCaseRef", "SCENARIO_CASE"],
    ["targetCapabilityRef", "CAPABILITY"],
    ["outcomeDefinitionRef", "OUTCOME_DEFINITION"],
    ["attributionPolicyRef", "OUTCOME_ATTRIBUTION_POLICY"],
    ["authoritySetRef", "OUTCOME_AUTHORITY_SET"],
  ];
  for (const [field, kind] of references) {
    artifactRef(observation[field], kind);
  }
  fingerprint(observation.subjectFingerprint);
  fingerprint(observation.attributionKeyFingerprint);
  fingerprint(observation.modelOutcomeFingerprint);

  const selection = object(
    observation.selectionProof,
    "OUTCOME_OBSERVATION_SCHEMA_INVALID",
  );
  exactFields(
    selection,
    SELECTION_FIELDS,
    "OUTCOME_OBSERVATION_SCHEMA_INVALID",
  );
  artifactRef(selection.cohortRef, "OUTCOME_CALIBRATION_COHORT");
  artifactRef(selection.samplingFrameRef, "OUTCOME_SAMPLING_FRAME");
  identifier(selection.stratumId);
  fingerprint(selection.inclusionFingerprint);
  instant(selection.selectedAt, "OUTCOME_COHORT_SELECTION_INVALID");
  positiveInteger(selection.eligiblePopulationSize);
  positiveInteger(selection.selectedPopulationSize);
  positiveInteger(selection.sampleOrdinal);
  oneOf(selection.selectionMode, [
    "CENSUS",
    "HASH_PARTITION",
    "STRATIFIED_RANDOM",
  ]);

  const window = object(
    observation.attributionWindow,
    "OUTCOME_OBSERVATION_SCHEMA_INVALID",
  );
  exactFields(window, WINDOW_FIELDS, "OUTCOME_OBSERVATION_SCHEMA_INVALID");
  for (const field of WINDOW_FIELDS) {
    instant(window[field], "OUTCOME_ATTRIBUTION_TIME_INVALID");
  }
  instant(observation.reconciledAt, "OUTCOME_RECONCILIATION_TIME_INVALID");
  instant(observation.attestedAt, "OUTCOME_ATTESTATION_TIME_INVALID");
  oneOf(observation.reconciliation, [
    "MATCH",
    "MISMATCH",
    "PENDING",
    "CENSORED",
    "CONFLICT",
  ]);
  if (typeof observation.evidenceComplete !== "boolean") {
    fail("OUTCOME_OBSERVATION_SCHEMA_INVALID");
  }

  const watermarks = array(
    observation.authorityWatermarks,
    "OUTCOME_OBSERVATION_SCHEMA_INVALID",
  );
  if (watermarks.length < 1 || watermarks.length > 64) {
    fail("OUTCOME_OBSERVATION_SCHEMA_INVALID");
  }
  for (const value of watermarks) {
    const watermark = object(value, "OUTCOME_OBSERVATION_SCHEMA_INVALID");
    exactFields(
      watermark,
      WATERMARK_FIELDS,
      "OUTCOME_OBSERVATION_SCHEMA_INVALID",
    );
    identifier(watermark.authorityId);
    artifactRef(
      watermark.watermarkRef,
      "AUTHORITATIVE_OUTCOME_SOURCE_WATERMARK",
    );
    instant(
      watermark.eventTimeThrough,
      "OUTCOME_AUTHORITY_WATERMARK_INVALID",
    );
    instant(watermark.publishedAt, "OUTCOME_AUTHORITY_WATERMARK_INVALID");
  }

  const facts = array(
    observation.authorityFacts,
    "OUTCOME_OBSERVATION_SCHEMA_INVALID",
  );
  if (facts.length > 1_024) {
    fail("OUTCOME_OBSERVATION_SCHEMA_INVALID");
  }
  for (const value of facts) {
    const fact = object(value, "OUTCOME_OBSERVATION_SCHEMA_INVALID");
    exactFields(fact, FACT_FIELDS, "OUTCOME_OBSERVATION_SCHEMA_INVALID");
    identifier(fact.authorityId);
    artifactRef(fact.sourceRef, "AUTHORITATIVE_OUTCOME_SOURCE_RECORD");
    fingerprint(fact.subjectFingerprint);
    fingerprint(fact.attributionKeyFingerprint);
    fingerprint(fact.outcomeFingerprint);
    instant(fact.occurredAt, "OUTCOME_ATTRIBUTION_CLOSURE_INVALID");
    instant(fact.recordedAt, "OUTCOME_ATTRIBUTION_CLOSURE_INVALID");
    if (typeof fact.evidenceComplete !== "boolean") {
      fail("OUTCOME_OBSERVATION_SCHEMA_INVALID");
    }
  }

  const seal = object(
    observation.observationSeal,
    "OUTCOME_OBSERVATION_SCHEMA_INVALID",
  );
  exactFields(seal, SEAL_FIELDS, "OUTCOME_OBSERVATION_SCHEMA_INVALID");
  requireText(seal, "schemaVersion", SEAL_VERSION);
  fingerprint(seal.materialFingerprint);
  requireText(seal, "algorithm", "Ed25519");
  identifier(seal.keyId);
  instant(seal.signedAt, "OUTCOME_OBSERVATION_SEAL_TIME_INVALID");
  if (
    decodeBase64(
      seal.signature,
      "OUTCOME_OBSERVATION_SCHEMA_INVALID",
    ).length !== 64
  ) {
    fail("OUTCOME_OBSERVATION_SCHEMA_INVALID");
  }
}

function verifyKeyShape(key: JsonObject): void {
  identifier(key.keyId);
  if (text(key.algorithm).length === 0) {
    fail("OUTCOME_OBSERVATION_SCHEMA_INVALID");
  }
  if (
    decodeBase64(
      key.encodedPublicKey,
      "OUTCOME_OBSERVATION_SCHEMA_INVALID",
    ).length === 0
  ) {
    fail("OUTCOME_OBSERVATION_SCHEMA_INVALID");
  }
  if (
    instant(key.createdAt, "OUTCOME_OBSERVATION_SCHEMA_INVALID") === 0n ||
    text(key.state).length === 0
  ) {
    fail("OUTCOME_OBSERVATION_SCHEMA_INVALID");
  }
  text(key.provider);
}

function verifySemantics(observation: JsonObject): {
  attestedAt: bigint;
} {
  const selection = object(
    observation.selectionProof,
    "OUTCOME_COHORT_SELECTION_INVALID",
  );
  const window = object(
    observation.attributionWindow,
    "OUTCOME_ATTRIBUTION_TIME_INVALID",
  );
  const actionAt = instant(
    window.actionOccurredAt,
    "OUTCOME_ATTRIBUTION_TIME_INVALID",
  );
  const opensAt = instant(
    window.opensAt,
    "OUTCOME_ATTRIBUTION_TIME_INVALID",
  );
  const closesAt = instant(
    window.closesAt,
    "OUTCOME_ATTRIBUTION_TIME_INVALID",
  );
  const selectedAt = instant(
    selection.selectedAt,
    "OUTCOME_COHORT_SELECTION_INVALID",
  );
  const reconciledAt = instant(
    observation.reconciledAt,
    "OUTCOME_RECONCILIATION_TIME_INVALID",
  );
  const attestedAt = instant(
    observation.attestedAt,
    "OUTCOME_ATTESTATION_TIME_INVALID",
  );
  const eligible = integer(selection.eligiblePopulationSize);
  const selected = integer(selection.selectedPopulationSize);
  const ordinal = integer(selection.sampleOrdinal);
  if (
    selectedAt >= actionAt ||
    opensAt < actionAt ||
    closesAt <= opensAt ||
    closesAt - opensAt > MAXIMUM_ATTRIBUTION_WINDOW ||
    reconciledAt < actionAt ||
    attestedAt < reconciledAt ||
    selected > eligible ||
    ordinal > selected ||
    (selection.selectionMode === "CENSUS" && selected !== eligible)
  ) {
    fail("OUTCOME_COHORT_SELECTION_INVALID");
  }

  const authorities = new Set<string>();
  const watermarkRefs = new Set<string>();
  let previousAuthority = "";
  let minimumEventTime: bigint | undefined;
  for (const raw of array(
    observation.authorityWatermarks,
    "OUTCOME_AUTHORITY_WATERMARK_INVALID",
  )) {
    const watermark = object(raw, "OUTCOME_AUTHORITY_WATERMARK_INVALID");
    const authority = text(watermark.authorityId);
    const through = instant(
      watermark.eventTimeThrough,
      "OUTCOME_AUTHORITY_WATERMARK_INVALID",
    );
    const published = instant(
      watermark.publishedAt,
      "OUTCOME_AUTHORITY_WATERMARK_INVALID",
    );
    const reference = artifactIdentity(watermark.watermarkRef);
    if (
      authorities.has(authority) ||
      watermarkRefs.has(reference) ||
      authority <= previousAuthority ||
      through > published ||
      published > reconciledAt
    ) {
      fail("OUTCOME_AUTHORITY_WATERMARK_INVALID");
    }
    authorities.add(authority);
    watermarkRefs.add(reference);
    previousAuthority = authority;
    minimumEventTime =
      minimumEventTime === undefined || through < minimumEventTime
        ? through
        : minimumEventTime;
  }

  const sourceRefs = new Set<string>();
  const outcomes = new Set<string>();
  let previousFact:
    | {
        authorityId: string;
        occurredAt: bigint;
        sourceFingerprint: string;
      }
    | undefined;
  let evidenceComplete = true;
  for (const raw of array(
    observation.authorityFacts,
    "OUTCOME_ATTRIBUTION_CLOSURE_INVALID",
  )) {
    const fact = object(raw, "OUTCOME_ATTRIBUTION_CLOSURE_INVALID");
    const authority = text(fact.authorityId);
    const occurredAt = instant(
      fact.occurredAt,
      "OUTCOME_ATTRIBUTION_CLOSURE_INVALID",
    );
    const recordedAt = instant(
      fact.recordedAt,
      "OUTCOME_ATTRIBUTION_CLOSURE_INVALID",
    );
    const reference = artifactIdentity(fact.sourceRef);
    const source = object(
      fact.sourceRef,
      "OUTCOME_ATTRIBUTION_CLOSURE_INVALID",
    );
    const order = {
      authorityId: authority,
      occurredAt,
      sourceFingerprint: text(source.fingerprint),
    };
    if (
      !authorities.has(authority) ||
      sourceRefs.has(reference) ||
      (previousFact !== undefined &&
        compareFactOrder(previousFact, order) >= 0) ||
      fact.subjectFingerprint !== observation.subjectFingerprint ||
      fact.attributionKeyFingerprint !==
        observation.attributionKeyFingerprint ||
      occurredAt < opensAt ||
      occurredAt > closesAt ||
      recordedAt < occurredAt ||
      recordedAt > reconciledAt
    ) {
      fail("OUTCOME_ATTRIBUTION_CLOSURE_INVALID");
    }
    sourceRefs.add(reference);
    previousFact = order;
    outcomes.add(text(fact.outcomeFingerprint));
    evidenceComplete &&= fact.evidenceComplete === true;
  }

  const derived =
    minimumEventTime !== undefined && minimumEventTime < closesAt
      ? "PENDING"
      : outcomes.size === 0
        ? "CENSORED"
        : outcomes.size > 1
          ? "CONFLICT"
          : outcomes.has(text(observation.modelOutcomeFingerprint))
            ? "MATCH"
            : "MISMATCH";
  if (derived !== observation.reconciliation) {
    fail("OUTCOME_RECONCILIATION_DERIVATION_INVALID");
  }
  if (evidenceComplete !== observation.evidenceComplete) {
    fail("OUTCOME_EVIDENCE_COMPLETENESS_INVALID");
  }
  return { attestedAt };
}

function verifyFingerprint(observation: JsonObject): void {
  if (
    observation.observationFingerprint !==
    sha256(producerFingerprintMaterial(observation), MAXIMUM_OBSERVATION_BYTES)
  ) {
    fail("OUTCOME_OBSERVATION_FINGERPRINT_INVALID");
  }
}

function verifySeal(
  observation: JsonObject,
  key: JsonObject,
  attestedAt: bigint,
  verificationTime: bigint,
): void {
  const seal = object(
    observation.observationSeal,
    "OUTCOME_OBSERVATION_SCHEMA_INVALID",
  );
  if (seal.keyId !== key.keyId) {
    fail("OUTCOME_OBSERVATION_KEY_ID_MISMATCH");
  }
  if (key.algorithm !== "Ed25519" || seal.algorithm !== key.algorithm) {
    fail("OUTCOME_OBSERVATION_SIGNATURE_ALGORITHM_REJECTED");
  }
  if (!["ACTIVE", "RETIRED"].includes(text(key.state))) {
    fail("OUTCOME_OBSERVATION_KEY_POLICY_REJECTED");
  }
  const keyCreatedAt = instant(
    key.createdAt,
    "OUTCOME_OBSERVATION_KEY_POLICY_REJECTED",
  );
  const signedAt = instant(
    seal.signedAt,
    "OUTCOME_OBSERVATION_SEAL_TIME_INVALID",
  );
  if (
    attestedAt < keyCreatedAt - KEY_CREATION_SKEW ||
    attestedAt > verificationTime + MAXIMUM_CLOCK_SKEW
  ) {
    fail("OUTCOME_OBSERVATION_KEY_POLICY_REJECTED");
  }
  if (
    signedAt < attestedAt - MAXIMUM_CLOCK_SKEW ||
    signedAt > attestedAt + MAXIMUM_CLOCK_SKEW
  ) {
    fail("OUTCOME_OBSERVATION_SEAL_TIME_INVALID");
  }
  const materialFingerprint = sha256(
    attestationMaterial(observation),
    MAXIMUM_ATTESTATION_BYTES,
  );
  if (materialFingerprint !== seal.materialFingerprint) {
    fail("OUTCOME_OBSERVATION_ATTESTATION_MATERIAL_INVALID");
  }
  try {
    const publicKey = createPublicKey({
      key: decodeBase64(
        key.encodedPublicKey,
        "OUTCOME_OBSERVATION_SIGNATURE_MATERIAL_INVALID",
      ),
      format: "der",
      type: "spki",
    });
    const verified = verifySignature(
      null,
      Buffer.from(materialFingerprint, "utf8"),
      publicKey,
      decodeBase64(
        seal.signature,
        "OUTCOME_OBSERVATION_SIGNATURE_MATERIAL_INVALID",
      ),
    );
    if (!verified) {
      fail("OUTCOME_OBSERVATION_SIGNATURE_INVALID");
    }
  } catch (failure) {
    if (failure instanceof VerificationFailure) {
      throw failure;
    }
    fail("OUTCOME_OBSERVATION_SIGNATURE_MATERIAL_INVALID");
  }
}

function producerFingerprintMaterial(observation: JsonObject): JsonObject {
  const selection = object(
    observation.selectionProof,
    "OUTCOME_OBSERVATION_CLOSURE_INVALID",
  );
  return {
    schemaVersion: observation.schemaVersion,
    observationId: observation.observationId,
    revision: observation.revision,
    observationFingerprint: "",
    scope: ordered(
      object(observation.scope, "OUTCOME_OBSERVATION_CLOSURE_INVALID"),
      SCOPE_FIELDS,
    ),
    inventoryRef: orderedArtifact(observation.inventoryRef),
    unitId: observation.unitId,
    scenarioCaseRef: orderedArtifact(observation.scenarioCaseRef),
    targetCapabilityRef: orderedArtifact(observation.targetCapabilityRef),
    outcomeDefinitionRef: orderedArtifact(observation.outcomeDefinitionRef),
    attributionPolicyRef: orderedArtifact(observation.attributionPolicyRef),
    authoritySetRef: orderedArtifact(observation.authoritySetRef),
    selectionProof: {
      cohortRef: orderedArtifact(selection.cohortRef),
      samplingFrameRef: orderedArtifact(selection.samplingFrameRef),
      stratumId: selection.stratumId,
      inclusionFingerprint: selection.inclusionFingerprint,
      selectedAt: selection.selectedAt,
      eligiblePopulationSize: selection.eligiblePopulationSize,
      selectedPopulationSize: selection.selectedPopulationSize,
      sampleOrdinal: selection.sampleOrdinal,
      selectionMode: selection.selectionMode,
    },
    subjectFingerprint: observation.subjectFingerprint,
    attributionKeyFingerprint: observation.attributionKeyFingerprint,
    modelOutcomeFingerprint: observation.modelOutcomeFingerprint,
    attributionWindow: ordered(
      object(
        observation.attributionWindow,
        "OUTCOME_OBSERVATION_CLOSURE_INVALID",
      ),
      WINDOW_FIELDS,
    ),
    reconciledAt: observation.reconciledAt,
    attestedAt: observation.attestedAt,
    authorityWatermarks: array(
      observation.authorityWatermarks,
      "OUTCOME_OBSERVATION_CLOSURE_INVALID",
    ).map((raw) => {
      const value = object(raw, "OUTCOME_OBSERVATION_CLOSURE_INVALID");
      return {
        authorityId: value.authorityId,
        watermarkRef: orderedArtifact(value.watermarkRef),
        eventTimeThrough: value.eventTimeThrough,
        publishedAt: value.publishedAt,
      };
    }),
    authorityFacts: array(
      observation.authorityFacts,
      "OUTCOME_OBSERVATION_CLOSURE_INVALID",
    ).map((raw) => {
      const value = object(raw, "OUTCOME_OBSERVATION_CLOSURE_INVALID");
      return {
        authorityId: value.authorityId,
        sourceRef: orderedArtifact(value.sourceRef),
        subjectFingerprint: value.subjectFingerprint,
        attributionKeyFingerprint: value.attributionKeyFingerprint,
        outcomeFingerprint: value.outcomeFingerprint,
        occurredAt: value.occurredAt,
        recordedAt: value.recordedAt,
        evidenceComplete: value.evidenceComplete,
      };
    }),
    reconciliation: observation.reconciliation,
    evidenceComplete: observation.evidenceComplete,
    observationSeal: {
      schemaVersion: SEAL_VERSION,
      materialFingerprint: "",
      algorithm: "",
      keyId: "",
      signedAt: "1970-01-01T00:00:00Z",
      signature: "",
    },
  };
}

function attestationMaterial(observation: JsonObject): JsonObject {
  return {
    domain: "RESOURCE_GATEWAY_AUTHORITATIVE_OUTCOME_OBSERVATION_V1",
    schemaVersion: observation.schemaVersion,
    observationId: observation.observationId,
    revision: observation.revision,
    inventoryRef: orderedArtifact(observation.inventoryRef),
    unitId: observation.unitId,
    reconciledAt: observation.reconciledAt,
    attestedAt: observation.attestedAt,
    observationFingerprint: observation.observationFingerprint,
  };
}

function orderedArtifact(value: unknown): JsonObject {
  return ordered(
    object(value, "OUTCOME_OBSERVATION_CLOSURE_INVALID"),
    ARTIFACT_FIELDS,
  );
}

function ordered(
  source: JsonObject,
  fields: readonly string[],
): JsonObject {
  return Object.fromEntries(fields.map((field) => [field, source[field]]));
}

function sha256(value: unknown, maximumBytes: number): string {
  const bytes = Buffer.from(JSON.stringify(canonical(value)), "utf8");
  if (bytes.length > maximumBytes) {
    fail("OUTCOME_OBSERVATION_CANONICAL_SIZE_INVALID");
  }
  return `sha256:${createHash("sha256").update(bytes).digest("hex")}`;
}

function canonical(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(canonical);
  }
  if (value !== null && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value as JsonObject)
        .sort(([left], [right]) =>
          left < right ? -1 : left > right ? 1 : 0,
        )
        .map(([key, child]) => [key, canonical(child)]),
    );
  }
  return value;
}

function decodeBase64(value: unknown, reasonCode: string): Buffer {
  const candidate = typeof value === "string" ? value : "";
  if (
    candidate.length === 0 ||
    !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(
      candidate,
    )
  ) {
    fail(reasonCode);
  }
  const decoded = Buffer.from(candidate, "base64");
  if (decoded.toString("base64") !== candidate) {
    fail(reasonCode);
  }
  return decoded;
}

function artifactRef(value: unknown, expectedKind: string): void {
  const reference = object(value, "OUTCOME_OBSERVATION_SCHEMA_INVALID");
  exactFields(
    reference,
    ARTIFACT_FIELDS,
    "OUTCOME_OBSERVATION_SCHEMA_INVALID",
  );
  requireText(reference, "kind", expectedKind);
  identifier(reference.id);
  positiveInteger(reference.revision);
  fingerprint(reference.fingerprint);
}

function artifactIdentity(value: unknown): string {
  const reference = object(
    value,
    "OUTCOME_ATTRIBUTION_CLOSURE_INVALID",
  );
  return `${text(reference.kind)}\u0000${text(reference.id)}\u0000${integer(
    reference.revision,
  )}\u0000${text(reference.fingerprint)}`;
}

function exactFields(
  value: JsonObject,
  fields: readonly string[],
  reasonCode: string,
): void {
  const actual = Object.keys(value).sort();
  const expected = [...fields].sort();
  if (
    actual.length !== expected.length ||
    actual.some((field, index) => field !== expected[index])
  ) {
    fail(reasonCode);
  }
}

function object(value: unknown, reasonCode: string): JsonObject {
  if (
    value === null ||
    typeof value !== "object" ||
    Array.isArray(value)
  ) {
    fail(reasonCode);
  }
  return value as JsonObject;
}

function array(value: unknown, reasonCode: string): unknown[] {
  if (!Array.isArray(value)) {
    fail(reasonCode);
  }
  return value;
}

function text(value: unknown): string {
  if (typeof value !== "string") {
    fail("OUTCOME_OBSERVATION_SCHEMA_INVALID");
  }
  return value;
}

function requireText(
  value: JsonObject,
  field: string,
  expected: string,
): void {
  if (value[field] !== expected) {
    fail("OUTCOME_OBSERVATION_SCHEMA_INVALID");
  }
}

function identifier(value: unknown): string {
  const candidate = text(value);
  if (!IDENTIFIER.test(candidate)) {
    fail("OUTCOME_OBSERVATION_SCHEMA_INVALID");
  }
  return candidate;
}

function fingerprint(value: unknown): string {
  const candidate = text(value);
  if (!FINGERPRINT.test(candidate)) {
    fail("OUTCOME_OBSERVATION_SCHEMA_INVALID");
  }
  return candidate;
}

function integer(value: unknown): number {
  if (
    typeof value !== "number" ||
    !Number.isSafeInteger(value)
  ) {
    fail("OUTCOME_OBSERVATION_SCHEMA_INVALID");
  }
  return value;
}

function positiveInteger(value: unknown): number {
  const candidate = integer(value);
  if (candidate < 1) {
    fail("OUTCOME_OBSERVATION_SCHEMA_INVALID");
  }
  return candidate;
}

function oneOf(value: unknown, allowed: readonly string[]): string {
  const candidate = text(value);
  if (!allowed.includes(candidate)) {
    fail("OUTCOME_OBSERVATION_SCHEMA_INVALID");
  }
  return candidate;
}

function instant(value: unknown, reasonCode: string): bigint {
  const candidate = typeof value === "string" ? value : "";
  const match = INSTANT.exec(candidate);
  if (match === null) {
    fail(reasonCode);
  }
  const [date, clock] = candidate.slice(0, -1).split("T");
  const [yearText, monthText, dayText] = date.split("-");
  const [hourText, minuteText, secondAndFraction] = clock.split(":");
  const [secondText, fraction = ""] = secondAndFraction.split(".");
  const year = Number(yearText);
  const month = Number(monthText);
  const day = Number(dayText);
  const hour = Number(hourText);
  const minute = Number(minuteText);
  const second = Number(secondText);
  const maximumDay = daysInMonth(year, month);
  if (
    maximumDay === 0 ||
    day < 1 ||
    day > maximumDay ||
    hour > 23 ||
    minute > 59 ||
    second > 59 ||
    !canonicalFraction(fraction)
  ) {
    fail(reasonCode);
  }
  const epochDays = daysFromCivil(year, month, day);
  const epochSeconds =
    BigInt(epochDays) * 86_400n +
    BigInt(hour * 3_600 + minute * 60 + second);
  const nanoseconds = BigInt(fraction.padEnd(9, "0") || "0");
  return epochSeconds * NANOSECONDS_PER_SECOND + nanoseconds;
}

function canonicalFraction(fraction: string): boolean {
  if (fraction === "") {
    return true;
  }
  if (![3, 6, 9].includes(fraction.length) || /^0+$/.test(fraction)) {
    return false;
  }
  const nanoseconds = Number(fraction.padEnd(9, "0"));
  const expectedLength =
    nanoseconds % 1_000_000 === 0
      ? 3
      : nanoseconds % 1_000 === 0
        ? 6
        : 9;
  return fraction.length === expectedLength;
}

function daysInMonth(year: number, month: number): number {
  if (month < 1 || month > 12) {
    return 0;
  }
  if (month === 2) {
    return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
      ? 29
      : 28;
  }
  return [4, 6, 9, 11].includes(month) ? 30 : 31;
}

function daysFromCivil(year: number, month: number, day: number): number {
  const adjustedYear = year - (month <= 2 ? 1 : 0);
  const era = Math.floor(adjustedYear / 400);
  const yearOfEra = adjustedYear - era * 400;
  const adjustedMonth = month + (month > 2 ? -3 : 9);
  const dayOfYear =
    Math.floor((153 * adjustedMonth + 2) / 5) + day - 1;
  const dayOfEra =
    yearOfEra * 365 +
    Math.floor(yearOfEra / 4) -
    Math.floor(yearOfEra / 100) +
    dayOfYear;
  return era * 146_097 + dayOfEra - 719_468;
}

function compareFactOrder(
  left: {
    authorityId: string;
    occurredAt: bigint;
    sourceFingerprint: string;
  },
  right: {
    authorityId: string;
    occurredAt: bigint;
    sourceFingerprint: string;
  },
): number {
  if (left.authorityId !== right.authorityId) {
    return left.authorityId < right.authorityId ? -1 : 1;
  }
  if (left.occurredAt !== right.occurredAt) {
    return left.occurredAt < right.occurredAt ? -1 : 1;
  }
  return left.sourceFingerprint === right.sourceFingerprint
    ? 0
    : left.sourceFingerprint < right.sourceFingerprint
      ? -1
      : 1;
}

function coordinatesFrom(value: unknown): Omit<
  VerificationResult,
  "outcome" | "reasonCode"
> {
  const fixture =
    value !== null && typeof value === "object" && !Array.isArray(value)
      ? (value as JsonObject)
      : {};
  const observation =
    fixture.observation !== null &&
    typeof fixture.observation === "object" &&
    !Array.isArray(fixture.observation)
      ? (fixture.observation as JsonObject)
      : {};
  const seal =
    observation.observationSeal !== null &&
    typeof observation.observationSeal === "object" &&
    !Array.isArray(observation.observationSeal)
      ? (observation.observationSeal as JsonObject)
      : {};
  return {
    observationId:
      typeof observation.observationId === "string"
        ? observation.observationId
        : "",
    observationFingerprint:
      typeof observation.observationFingerprint === "string"
        ? observation.observationFingerprint
        : "",
    unitId:
      typeof observation.unitId === "string" ? observation.unitId : "",
    reconciliation:
      typeof observation.reconciliation === "string"
        ? observation.reconciliation
        : "",
    keyId: typeof seal.keyId === "string" ? seal.keyId : "",
  };
}

function result(
  outcome: VerificationOutcome,
  reasonCode: string,
  coordinates: Omit<VerificationResult, "outcome" | "reasonCode">,
): VerificationResult {
  return { outcome, reasonCode, ...coordinates };
}

function isPolicyReason(reasonCode: string): boolean {
  return (
    reasonCode === "OUTCOME_OBSERVATION_KEY_POLICY_REJECTED" ||
    reasonCode === "OUTCOME_OBSERVATION_SIGNATURE_ALGORITHM_REJECTED"
  );
}

function fail(reasonCode: string): never {
  throw new VerificationFailure(reasonCode);
}
