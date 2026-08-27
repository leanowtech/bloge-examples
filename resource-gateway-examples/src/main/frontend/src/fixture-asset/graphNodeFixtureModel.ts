import type { NodeFixture } from '../types';

/** Lifecycle shown on one simulation result node row. */
export type GraphNodeFixtureProvenance = 'sample' | 'pinned' | 'governed';

/** Three fidelity levels supported by the existing resource simulation runtime. */
export type ResourceFidelity =
  | 'OUTPUT_LEVEL'
  | 'PROTOCOL_DERIVED'
  | 'TRANSPORT_LEVEL';

/** Confidentiality values accepted by graph-node fixture governance. */
export type GraphNodeFixtureClassification =
  | 'PUBLIC'
  | 'INTERNAL'
  | 'CONFIDENTIAL'
  | 'RESTRICTED';

/** Exact immutable governed fixture coordinate needed by a reused capture. */
export interface GovernedGraphNodeFixtureRef {
  fixtureAssetId: string;
  revision: number;
  schemaFingerprint: string;
}

/**
 * Authoring view around the transported GraphDraft node fixture.
 *
 * Provenance is deliberately presentation state: it never changes the durable
 * GraphDraft wire contract or its server-side schema.
 */
export interface GraphNodeFixtureState extends NodeFixture {
  governedRef?: GovernedGraphNodeFixtureRef;
  resourceFidelity?: ResourceFidelity;
  schemaStale?: boolean;
}

/** Payload-free request sent to the only new Phase C backend endpoint. */
export interface GraphNodeFixturePromoteRequest {
  schemaVersion: 'bloge.graphNodeFixturePromote.v1';
  fixtureAssetId: string;
  classification: GraphNodeFixtureClassification;
  retentionDays: number;
  redactionPaths: string[];
}

/** Payload-free success response from graph-node promotion. */
export interface GraphNodeFixturePromotionReceipt {
  fixtureAssetId: string;
  revision: number;
  lifecycle: 'DRAFT' | 'ACTIVE' | string;
  assetRef: { kind?: string; id?: string; revision?: number; fingerprint?: string };
  schemaRef: { id?: string; revision?: number; fingerprint?: string };
  provenance: 'governed' | string;
}

/** Author inputs for constructing a safe promote request. */
export interface PromoteGraphNodeFixtureInput {
  fixtureAssetId: string;
  classification: GraphNodeFixtureClassification;
  retentionDays: number;
  redactionPaths?: readonly string[];
}

const CLASSIFICATIONS: ReadonlySet<string> = new Set([
  'PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED',
]);

/**
 * Derive visible provenance without requiring legacy fixture objects to change.
 *
 * @param fixture captured output and optional authoring metadata
 * @returns governed for an exact governed ref, pinned when expected input exists, otherwise sample
 */
export function provenanceOf(fixture?: NodeFixture | GraphNodeFixtureState): GraphNodeFixtureProvenance {
  if (!fixture) return 'sample';
  if ('governedRef' in fixture && isExactGovernedRef(fixture.governedRef)) return 'governed';
  if (fixture.expectedInput !== undefined) return 'pinned';
  return 'sample';
}

/**
 * Build and bound the graph-node promote payload at the browser boundary.
 *
 * <p>The backend repeats every check, but client-side validation gives immediate,
 * actionable feedback while preserving the same canonical ordering and rules.</p>
 *
 * @param input author-controlled identity, confidentiality, retention, and redaction choices
 * @returns a fresh, immutable-by-convention wire payload
 * @throws TypeError when an authoritative field would be rejected by the server
 */
export function promoteRequestFrom(
  input: PromoteGraphNodeFixtureInput,
): GraphNodeFixturePromoteRequest {
  const fixtureAssetId = String(input.fixtureAssetId ?? '').trim();
  const classification = String(input.classification).trim().toUpperCase();
  const rawPaths = [...(input.redactionPaths ?? [])];
  const redactionPaths = [...new Set(rawPaths.map((path) => path.trim()))].sort();

  if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$/.test(fixtureAssetId)) {
    throw new TypeError('A bounded graph-node fixture asset id is required.');
  }
  if (!CLASSIFICATIONS.has(classification)) {
    throw new TypeError('Classification must be PUBLIC, INTERNAL, CONFIDENTIAL, or RESTRICTED.');
  }
  if (!Number.isInteger(input.retentionDays) || input.retentionDays < 1 || input.retentionDays > 30) {
    throw new TypeError('Retention must be between 1 and 30 days.');
  }
  if (redactionPaths.length > 128 || redactionPaths.some((path) => !validRedactionPath(path))) {
    throw new TypeError('Redaction paths must be bounded non-root JSON Pointers.');
  }

  return {
    schemaVersion: 'bloge.graphNodeFixturePromote.v1',
    fixtureAssetId,
    classification: classification as GraphNodeFixtureClassification,
    retentionDays: input.retentionDays,
    redactionPaths,
  };
}

function validRedactionPath(path: string): boolean {
  if (!path || path.length > 512 || !path.startsWith('/') || path === '/') return false;
  for (let index = 0; index < path.length; index += 1) {
    if (path[index] === '~' && path[index + 1] !== '0' && path[index + 1] !== '1') return false;
  }
  return true;
}

function isExactGovernedRef(reference: GovernedGraphNodeFixtureRef | undefined): boolean {
  return Boolean(reference
    && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$/.test(reference.fixtureAssetId.trim())
    && Number.isInteger(reference.revision) && reference.revision > 0
    && reference.schemaFingerprint.trim());
}

/**
 * Project a payload-free promotion receipt into the reusable governed coordinate.
 *
 * @param nodeId owning draft-node identifier
 * @param receipt successful promote response
 * @returns a minimal exact reference attached only to authoring UI state
 * @throws TypeError when the receipt lacks the exactness needed for cross-graph reuse
 */
export function governedRefFromReceipt(
  nodeId: string,
  receipt: GraphNodeFixturePromotionReceipt,
): GovernedGraphNodeFixtureRef & { nodeId: string } {
  const schemaFingerprint = receipt.schemaRef?.fingerprint?.trim() ?? '';
  if (!nodeId.trim()) throw new TypeError('A non-blank node id is required.');
  if (!receipt.fixtureAssetId?.trim()) throw new TypeError('Governed fixture id is required.');
  if (!Number.isInteger(receipt.revision) || receipt.revision < 1) {
    throw new TypeError('Governed fixture revision must be positive.');
  }
  if (!schemaFingerprint) throw new TypeError('Governed schema fingerprint is required.');
  if (receipt.lifecycle !== 'DRAFT') throw new TypeError('Promotion must create a governed DRAFT.');
  if (receipt.provenance !== 'governed') throw new TypeError('Receipt provenance must be governed.');
  return {
    nodeId,
    fixtureAssetId: receipt.fixtureAssetId,
    revision: receipt.revision,
    schemaFingerprint,
  };
}

/**
 * Detect whether the governed coordinate was pinned against a different schema snapshot.
 *
 * @param fixture current authoring fixture state
 * @param currentOutputSchemaFingerprint latest output-schema fingerprint, when known
 * @returns true only on a concrete fingerprint difference
 */
export function fixtureSchemaStale(
  fixture?: NodeFixture | GraphNodeFixtureState,
  currentOutputSchemaFingerprint?: string,
): boolean {
  const candidate = fixture as GraphNodeFixtureState | undefined;
  const governedFingerprint = candidate?.governedRef?.schemaFingerprint;
  const current = currentOutputSchemaFingerprint?.trim();
  return Boolean(governedFingerprint && current && governedFingerprint !== current);
}
