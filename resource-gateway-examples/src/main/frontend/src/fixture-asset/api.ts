import { integrationRequestHeaders } from '../api';
import {
  approveFixtureAsset,
  transitionFixtureAsset,
} from '../correctness-studio/api/correctnessAuthoringApi';
import type { StoredFixtureAsset } from '../correctness-studio/model/authoring';
import {
  governedRefFromReceipt,
  type GraphNodeFixturePromoteRequest,
  type GraphNodeFixturePromotionReceipt,
  type GovernedGraphNodeFixtureRef,
} from './graphNodeFixtureModel';

/** Metadata-only row returned by the governed fixture catalogue. */
export interface GovernedFixtureAssetSummary {
  fixtureAssetId: string;
  revision: number;
  name: string;
  schemaFingerprint: string;
  usageCount?: number;
  lifecycle: string;
  compatible?: boolean;
  currentSchemaFingerprint?: string;
}

/** Small lifecycle receipt used by the visual authoring controls after promotion. */
export interface GovernedFixtureLifecycleReceipt {
  revision: number;
  lifecycle: string;
}

/** Commands required to move a promoted fixture through the governed lifecycle. */
export interface FixtureAssetLifecycleActions {
  reviewReady: (fixtureAssetId: string, revision: number) => Promise<GovernedFixtureLifecycleReceipt>;
  approve: (
    fixtureAssetId: string,
    revision: number,
    comment: string,
    idempotencyKey: string,
  ) => Promise<GovernedFixtureLifecycleReceipt>;
  activate: (fixtureAssetId: string, revision: number) => Promise<GovernedFixtureLifecycleReceipt>;
}

/** HTTP transport seam for fixture authoring; production defaults to same-origin fetch. */
export type FixtureAssetTransport = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;

const defaultTransport: FixtureAssetTransport = (input, init) => fetch(input, init);
let transport = defaultTransport;

/** Installs a host transport for tests or an embedded authoring shell. */
export function setFixtureAssetTransport(next: FixtureAssetTransport): void {
  transport = next;
}

/** Restores the browser fetch transport. */
export function resetFixtureAssetTransport(): void {
  transport = defaultTransport;
}

/**
 * Posts a payload-free graph-node governance command and returns only its receipt.
 *
 * @param draftId durable graph draft identifier
 * @param nodeId exact captured node identifier
 * @param request bounded author governance choices
 * @returns server-issued payload-free promotion receipt
 * @throws Error with status/code-safe detail when transport or receipt fails
 */
export async function promoteGraphNodeFixture(
  draftId: string,
  nodeId: string,
  request: GraphNodeFixturePromoteRequest,
): Promise<GraphNodeFixturePromotionReceipt> {
  const response = await transport(
    `/api/visual/graphs/${encodeURIComponent(draftId)}/nodes/${encodeURIComponent(nodeId)}/fixtures:promote`,
    {
      method: 'POST',
      headers: integrationRequestHeaders('CORRECTNESS_FIXTURE_MATERIAL_WRITE', {
        'Content-Type': 'application/json',
      }),
      body: JSON.stringify(request),
    },
  );
  const payload = await safeJson(response);
  if (!response.ok) {
    throw new Error(`Governed fixture promotion failed (${response.status}).`);
  }
  if (!isRecord(payload)) {
    throw new Error('Governed fixture promotion returned an invalid receipt.');
  }
  return payload as unknown as GraphNodeFixturePromotionReceipt;
}

/**
 * Loads metadata-only ACTIVE fixture candidates for the picker.
 *
 * The collection endpoint is optional in older deployments. A 404/405 therefore
 * becomes an empty list so the UI does not pretend that a reusable catalogue exists.
 *
 * @returns sorted metadata rows with no material payload
 */
export async function fetchGovernedFixtureAssets(operatorRef?: string): Promise<GovernedFixtureAssetSummary[]> {
  const query = operatorRef?.trim() ? `?operatorRef=${encodeURIComponent(operatorRef.trim())}` : '';
  const response = await transport(`/api/visual/fixture-assets${query}`, {
    headers: integrationRequestHeaders('CORRECTNESS_READ'),
  });
  if (response.status === 404 || response.status === 405) return [];
  const payload = await safeJson(response);
  if (!response.ok) throw new Error(`Governed fixture catalogue unavailable (${response.status}).`);
  const rows = collectionRows(payload);
  const parsed = rows.map(summaryFromUnknown);
  if (parsed.some((row) => row === null)) {
    throw new Error('Governed fixture catalogue contains an invalid summary.');
  }
  return parsed.filter((row): row is GovernedFixtureAssetSummary => row !== null)
    .filter((row) => row.lifecycle === 'ACTIVE')
    .sort((left, right) => left.name.localeCompare(right.name)
      || left.fixtureAssetId.localeCompare(right.fixtureAssetId));
}

/** Submits a promoted fixture for an accountable review without copying its material. */
export async function reviewReadyGovernedFixture(
  fixtureAssetId: string,
  revision: number,
): Promise<GovernedFixtureLifecycleReceipt> {
  return lifecycleReceipt((await transitionFixtureAsset(fixtureAssetId, revision, 'review-ready')).data);
}

/** Records reviewer approval using the existing four-eyes command endpoint. */
export async function approveGovernedFixture(
  fixtureAssetId: string,
  revision: number,
  comment: string,
  idempotencyKey: string,
): Promise<GovernedFixtureLifecycleReceipt> {
  return lifecycleReceipt((await approveFixtureAsset(
    fixtureAssetId,
    revision,
    comment,
    idempotencyKey,
  )).data.stored);
}

/** Activates an approved fixture so it becomes eligible for metadata-only reuse. */
export async function activateGovernedFixture(
  fixtureAssetId: string,
  revision: number,
): Promise<GovernedFixtureLifecycleReceipt> {
  return lifecycleReceipt((await transitionFixtureAsset(fixtureAssetId, revision, 'activate')).data);
}

/** Validates a receipt and returns its exact UI-only coordinate. */
export function governedReferenceFromPromotion(
  nodeId: string,
  receipt: GraphNodeFixturePromotionReceipt,
): GovernedGraphNodeFixtureRef & { nodeId: string } {
  return governedRefFromReceipt(nodeId, receipt);
}

async function safeJson(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text.trim()) return null;
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return null;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function collectionRows(value: unknown): unknown[] {
  if (Array.isArray(value)) return value;
  if (!isRecord(value)) throw new Error('Governed fixture catalogue returned an invalid envelope.');
  if (Object.prototype.hasOwnProperty.call(value, 'data')) {
    if (Array.isArray(value.data)) return value.data;
    throw new Error('Governed fixture catalogue returned an invalid data payload.');
  }
  if (Array.isArray(value.assets)) return value.assets;
  if (Array.isArray(value.items)) return value.items;
  if (Array.isArray(value.content)) return value.content;
  throw new Error('Governed fixture catalogue returned an invalid collection shape.');
}

function summaryFromUnknown(value: unknown): GovernedFixtureAssetSummary | null {
  if (!isRecord(value)) return null;
  const descriptor = isRecord(value.descriptor) ? value.descriptor : value;
  const schema = isRecord(descriptor.schemaRef) ? descriptor.schemaRef : {};
  const quality = isRecord(descriptor.quality) ? descriptor.quality : {};
  const fixtureAssetId = stringField(descriptor.fixtureAssetId);
  const schemaFingerprint = stringField(schema.fingerprint);
  const revision = descriptor.revision;
  if (!fixtureAssetId || !schemaFingerprint || typeof revision !== 'number'
    || !Number.isInteger(revision) || revision < 1) return null;
  const usageCount = value.usageCount ?? quality.usageCount;
  const compatible = value.compatibleWithOperatorRef;
  return {
    fixtureAssetId,
    revision,
    name: stringField(descriptor.name) || fixtureAssetId,
    schemaFingerprint,
    usageCount: typeof usageCount === 'number' && Number.isInteger(usageCount) && usageCount >= 0
      ? usageCount : 0,
    lifecycle: stringField(descriptor.lifecycle),
    ...(typeof compatible === 'boolean' ? { compatible } : {}),
    ...(stringField(value.currentSchemaFingerprint)
      ? { currentSchemaFingerprint: stringField(value.currentSchemaFingerprint) } : {}),
  };
}

function lifecycleReceipt(stored: StoredFixtureAsset): GovernedFixtureLifecycleReceipt {
  return {
    revision: stored.descriptor.revision,
    lifecycle: stored.descriptor.lifecycle,
  };
}

function stringField(value: unknown): string {
  return typeof value === 'string' ? value.trim() : '';
}
