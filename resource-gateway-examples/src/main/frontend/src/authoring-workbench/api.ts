import { integrationRequestHeaders } from '../api';
import type {
  ApiResourceReceipt,
  ApiResourceSaveCommand,
  ApiResourceSpec,
  FixtureSetSummary,
  OpenApiPreview,
  SimulationRun,
  ApiConnectionView,
  LegacyAssetMigrationInventory,
  LegacyMigrationAssessment,
  LegacyApiResourceReauthorPreview,
} from './model';

export type AuthoringWorkbenchTransport = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

export interface StoredResponse<T> {
  value: T;
  strongEtag: string;
  replayed: boolean;
}

export interface AuthoringAvailability {
  schemaVersion: 'bloge.authoringAvailability.v1';
  apiResource: boolean;
  reusableFlow: boolean;
}

/** Reads the always-available deployment gate before rendering authoring actions. */
export async function readAuthoringAvailability(
  transport: AuthoringWorkbenchTransport = fetch,
): Promise<AuthoringAvailability> {
  return body(await transport('/api/authoring/availability'));
}

/** Lists safe committed Connections for visible selection on the API object page. */
export async function listApiConnections(
  transport: AuthoringWorkbenchTransport = fetch,
): Promise<ApiConnectionView[]> {
  return body(await transport('/api/authoring/connections', {
    headers: integrationRequestHeaders('API_RESOURCE_AUTHORING'),
  }));
}

/** Reads the payload-free compatibility inventory; this call never migrates an asset. */
export async function readLegacyAssetMigrationInventory(
  transport: AuthoringWorkbenchTransport = fetch,
): Promise<LegacyAssetMigrationInventory> {
  return body(await transport('/api/authoring/migrations/legacy-assets', {
    headers: integrationRequestHeaders('API_RESOURCE_AUTHORING'),
  }));
}

/** Reads deterministic assessment evidence; an optional ETag replays one exact inventory snapshot. */
export async function readLegacyMigrationAssessment(
  strongEtag: string | null = null,
  transport: AuthoringWorkbenchTransport = fetch,
): Promise<StoredResponse<LegacyMigrationAssessment>> {
  const headers = integrationRequestHeaders('API_RESOURCE_AUTHORING',
    strongEtag ? { 'If-Match': strongEtag } : {});
  const response = await transport('/api/authoring/migrations/legacy-assets/assessment', { headers });
  return storedResponse(response, false);
}

/** Reads one safe command preview; the author must still choose a Connection and save it explicitly. */
export async function readLegacyApiResourcePreview(
  resourceId: string,
  transport: AuthoringWorkbenchTransport = fetch,
): Promise<LegacyApiResourceReauthorPreview> {
  return body(await transport(
    `/api/authoring/migrations/legacy-assets/resources/${encodeURIComponent(resourceId)}:preview`, {
      headers: integrationRequestHeaders('API_RESOURCE_AUTHORING'),
    },
  ));
}

/** Previews inline OpenAPI operations without persistence or remote egress. */
export async function previewOpenApi(
  documentText: string,
  operationIds: string[] = [],
  transport: AuthoringWorkbenchTransport = fetch,
): Promise<OpenApiPreview> {
  const response = await transport('/api/authoring/resources:preview-openapi', {
    method: 'POST',
    headers: integrationRequestHeaders('API_RESOURCE_AUTHORING', { 'Content-Type': 'application/json' }),
    body: JSON.stringify({
      schemaVersion: 'bloge.openApiPreviewCommand.v1',
      source: { kind: 'INLINE', documentText },
      operationIds,
    }),
  });
  return body<OpenApiPreview>(response);
}

/** Lists payload-free Fixture summaries for one exact Resource revision. */
export async function listApiResourceFixtures(
  resource: Pick<ApiResourceSpec, 'resourceId' | 'revision' | 'fingerprint'>,
  transport: AuthoringWorkbenchTransport = fetch,
): Promise<FixtureSetSummary[]> {
  const query = new URLSearchParams({
    subjectKind: 'API_RESOURCE',
    subjectId: resource.resourceId,
    subjectRevision: String(resource.revision),
    subjectFingerprint: resource.fingerprint,
  });
  const response = await transport(`/api/authoring/fixture-sets?${query}`, {
    headers: integrationRequestHeaders('API_RESOURCE_AUTHORING'),
  });
  return body<FixtureSetSummary[]>(response);
}

/** Reads one committed API Resource without exposing Connection secret metadata. */
export async function readApiResource(
  resourceId: string,
  revision?: number,
  transport: AuthoringWorkbenchTransport = fetch,
): Promise<StoredResponse<ApiResourceSpec>> {
  const suffix = revision === undefined ? '' : `?revision=${revision}`;
  const response = await transport(`/api/authoring/resources/${encodeURIComponent(resourceId)}${suffix}`, {
    headers: integrationRequestHeaders('API_RESOURCE_AUTHORING'),
  });
  return storedResponse(response, false);
}

/** Saves the Resource and its private Default Fixture as one compound command. */
export async function saveApiResource(
  resourceId: string,
  command: ApiResourceSaveCommand,
  strongEtag: string | null,
  idempotencyKey: string,
  transport: AuthoringWorkbenchTransport = fetch,
): Promise<StoredResponse<ApiResourceReceipt>> {
  const response = await transport(`/api/authoring/resources/${encodeURIComponent(resourceId)}`, {
    method: 'PUT',
    headers: integrationRequestHeaders('API_RESOURCE_AUTHORING', {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
      ...(strongEtag ? { 'If-Match': strongEtag } : { 'If-None-Match': '*' }),
    }),
    body: JSON.stringify(command),
  });
  return storedResponse(response, true);
}

/** Executes the exact Default Fixture Case returned by the Resource save receipt. */
export async function simulateFixtureCase(
  fixtureSetId: string,
  revision: number,
  caseId: string,
  idempotencyKey: string,
  transport: AuthoringWorkbenchTransport = fetch,
): Promise<SimulationRun> {
  const response = await transport('/api/authoring/simulations', {
    method: 'POST',
    headers: integrationRequestHeaders('API_RESOURCE_AUTHORING', {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    }),
    body: JSON.stringify({
      schemaVersion: 'bloge.simulationRequest.v1',
      source: { kind: 'FIXTURE_CASE', fixtureSetId, revision, caseId },
      executionPolicy: {
        externalReads: { kind: 'DENY' },
        externalWrites: { kind: 'DENY' },
      },
    }),
  });
  return body<SimulationRun>(response);
}

async function storedResponse<T>(response: Response, includeReplay: boolean): Promise<StoredResponse<T>> {
  const value = await body<T>(response);
  const strongEtag = response.headers.get('ETag');
  if (!strongEtag) throw new Error('The server did not return a Resource ETag.');
  return {
    value,
    strongEtag,
    replayed: includeReplay && response.headers.get('Idempotency-Replayed') === 'true',
  };
}

async function body<T>(response: Response): Promise<T> {
  const text = await response.text();
  let payload: unknown = null;
  if (text) {
    try { payload = JSON.parse(text); } catch { payload = null; }
  }
  if (!response.ok) {
    const problem = payload as { detail?: string; title?: string; code?: string } | null;
    throw new Error(problem?.detail || problem?.title || problem?.code || response.statusText);
  }
  if (payload === null) throw new Error('The server returned an empty response.');
  return payload as T;
}
