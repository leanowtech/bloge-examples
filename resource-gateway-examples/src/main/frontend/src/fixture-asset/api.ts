import { integrationRequestHeaders } from '../api';
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
  usageCount: number;
  lifecycle: string;
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
export async function fetchGovernedFixtureAssets(): Promise<GovernedFixtureAssetSummary[]> {
  const response = await transport('/api/visual/fixture-assets', {
    headers: integrationRequestHeaders('CORRECTNESS_READ'),
  });
  if (response.status === 404 || response.status === 405) return [];
  const payload = await safeJson(response);
  if (!response.ok) throw new Error(`Governed fixture catalogue unavailable (${response.status}).`);
  const rows = Array.isArray(payload)
    ? payload
    : isRecord(payload)
      ? (Array.isArray(payload.assets) ? payload.assets
        : Array.isArray(payload.items) ? payload.items
          : Array.isArray(payload.content) ? payload.content : [])
      : [];
  return rows.map(summaryFromUnknown).filter((row): row is GovernedFixtureAssetSummary => row !== null)
    .filter((row) => row.lifecycle === 'ACTIVE')
    .sort((left, right) => left.name.localeCompare(right.name)
      || left.fixtureAssetId.localeCompare(right.fixtureAssetId));
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
  const usageCount = quality.usageCount;
  return {
    fixtureAssetId,
    revision,
    name: stringField(descriptor.name) || fixtureAssetId,
    schemaFingerprint,
    usageCount: typeof usageCount === 'number' && Number.isInteger(usageCount) && usageCount >= 0
      ? usageCount : 0,
    lifecycle: stringField(descriptor.lifecycle),
  };
}

function stringField(value: unknown): string {
  return typeof value === 'string' ? value.trim() : '';
}
