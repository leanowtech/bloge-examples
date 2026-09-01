import { integrationRequestHeaders } from '../api';
import type { FixtureSetSummary, SimulationRun } from './model';
import type {
  FixtureSetCommand,
  FixtureSetSaveReceipt,
  FixtureShareCommand,
  FixtureShareReceipt,
  FixtureReviewCommand,
  FixtureReviewReceipt,
  FixtureSetView,
  FlowDraftRef,
  FlowVersionRef,
  LegacyReusableFlowReauthorPreview,
  ReusableFlowVersion,
  ReusableFlowCommand,
  ReusableFlowDraft,
  ReusableFlowPublishReceipt,
  ReusableFlowSaveReceipt,
} from './flowModel';
import type { AuthoringWorkbenchTransport, StoredResponse } from './api';

/** Fixture reads omit the validator when the parent object exclusively governs edits. */
export interface FixtureReadResponse {
  value: FixtureSetView;
  strongEtag: string | null;
  replayed: false;
}

/** Reads one fixture-free reusable Flow command projected from one exact legacy graph coordinate. */
export async function readLegacyReusableFlowPreview(
  sourceKind: LegacyReusableFlowReauthorPreview['source']['kind'],
  sourceId: string,
  sourceRevision: number,
  transport: AuthoringWorkbenchTransport = fetch,
): Promise<LegacyReusableFlowReauthorPreview> {
  const query = new URLSearchParams({ revision: String(sourceRevision) });
  return body(await transport(
    `/api/authoring/migrations/legacy-assets/flows/${sourceKind}/${encodeURIComponent(sourceId)}:preview?${query}`,
    { headers: integrationRequestHeaders('API_RESOURCE_AUTHORING') },
  ));
}

/** Reads one committed Tool/Solution draft for the unified object page. */
export async function readFlow(
  flowId: string, transport: AuthoringWorkbenchTransport = fetch,
): Promise<StoredResponse<ReusableFlowDraft>> {
  return storedResponse(await transport(`/api/authoring/flows/${encodeURIComponent(flowId)}`, {
    headers: integrationRequestHeaders('API_RESOURCE_AUTHORING'),
  }), false);
}

/** Saves one mapping-defined reusable Flow under an opaque strong precondition. */
export async function saveFlow(
  flowId: string, command: ReusableFlowCommand, strongEtag: string | null, idempotencyKey: string,
  transport: AuthoringWorkbenchTransport = fetch,
): Promise<StoredResponse<ReusableFlowSaveReceipt>> {
  return storedResponse(await transport(`/api/authoring/flows/${encodeURIComponent(flowId)}`, {
    method: 'PUT',
    headers: integrationRequestHeaders('API_RESOURCE_AUTHORING', {
      'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey,
      ...(strongEtag ? { 'If-Match': strongEtag } : { 'If-None-Match': '*' }),
    }),
    body: JSON.stringify(command),
  }), true);
}

/** Saves the whole-flow Fixture authored on the Flow object page. */
export async function saveFlowFixture(
  fixtureSetId: string, command: FixtureSetCommand, strongEtag: string | null, idempotencyKey: string,
  transport: AuthoringWorkbenchTransport = fetch,
): Promise<StoredResponse<FixtureSetSaveReceipt>> {
  return storedResponse(await transport(`/api/authoring/fixture-sets/${encodeURIComponent(fixtureSetId)}`, {
    method: 'PUT',
    headers: integrationRequestHeaders('API_RESOURCE_AUTHORING', {
      'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey,
      ...(strongEtag ? { 'If-Match': strongEtag } : { 'If-None-Match': '*' }),
    }),
    body: JSON.stringify(command),
  }), true);
}

/** Reads one exact private whole-flow Fixture revision and its strong ETag. */
export async function readFlowFixture(
  fixtureSetId: string, revision?: number, transport: AuthoringWorkbenchTransport = fetch,
): Promise<FixtureReadResponse> {
  const suffix = revision === undefined ? '' : `?revision=${revision}`;
  return fixtureReadResponse(await transport(
    `/api/authoring/fixture-sets/${encodeURIComponent(fixtureSetId)}${suffix}`,
    { headers: integrationRequestHeaders('API_RESOURCE_AUTHORING') },
  ));
}

/** Discovers payload-free Fixtures for one exact draft or immutable version Subject. */
export async function listFlowFixtures(
  subject: FlowDraftRef | FlowVersionRef,
  transport: AuthoringWorkbenchTransport = fetch,
): Promise<FixtureSetSummary[]> {
  const query = new URLSearchParams({
    subjectKind: subject.kind,
    subjectId: subject.kind === 'FLOW_DRAFT' ? subject.draftId : subject.publicationId,
    subjectRevision: String(subject.revision), subjectFingerprint: subject.fingerprint,
  });
  return body(await transport(`/api/authoring/fixture-sets?${query}`, {
    headers: integrationRequestHeaders('API_RESOURCE_AUTHORING'),
  }));
}

/** Reads the latest immutable server-owned version for a Flow, when one exists. */
export async function readLatestFlowVersion(
  flowId: string, transport: AuthoringWorkbenchTransport = fetch,
): Promise<ReusableFlowVersion | null> {
  const response = await transport(
    `/api/authoring/flows/${encodeURIComponent(flowId)}/versions/latest`,
    { headers: integrationRequestHeaders('API_RESOURCE_AUTHORING') },
  );
  if (response.status === 404) return null;
  return body(response);
}

/** Publishes one exact validated Flow draft into the reusable composition catalog. */
export async function publishFlow(
  flowId: string, source: FlowDraftRef, idempotencyKey: string,
  transport: AuthoringWorkbenchTransport = fetch,
): Promise<ReusableFlowPublishReceipt> {
  return body(await transport(`/api/authoring/flows/${encodeURIComponent(flowId)}:publish`, {
    method: 'POST',
    headers: integrationRequestHeaders('API_RESOURCE_AUTHORING', {
      'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey,
    }),
    body: JSON.stringify({ schemaVersion: 'bloge.reusableFlowPublishCommand.v1', source }),
  }));
}

/** Runs one exact saved whole-flow Fixture Case with external effects denied. */
export async function simulateFlowFixture(
  fixtureSetId: string, revision: number, caseId: string, idempotencyKey: string,
  transport: AuthoringWorkbenchTransport = fetch,
): Promise<SimulationRun> {
  return body(await transport('/api/authoring/simulations', {
    method: 'POST',
    headers: integrationRequestHeaders('API_RESOURCE_AUTHORING', {
      'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey,
    }),
    body: JSON.stringify({
      schemaVersion: 'bloge.simulationRequest.v1',
      source: { kind: 'FIXTURE_CASE', fixtureSetId, revision, caseId },
      executionPolicy: { externalReads: { kind: 'DENY' }, externalWrites: { kind: 'DENY' } },
    }),
  }));
}

/** Reads one independent Fixture object while preserving its exact revision and strong ETag. */
export async function readFixtureSet(
  fixtureSetId: string, revision?: number, transport: AuthoringWorkbenchTransport = fetch,
): Promise<FixtureReadResponse> {
  return readFlowFixture(fixtureSetId, revision, transport);
}

/** Updates one editable standalone Fixture object under its opaque strong precondition. */
export async function saveFixtureSet(
  fixtureSetId: string, command: FixtureSetCommand, strongEtag: string, idempotencyKey: string,
  transport: AuthoringWorkbenchTransport = fetch,
): Promise<StoredResponse<FixtureSetSaveReceipt>> {
  return saveFlowFixture(fixtureSetId, command, strongEtag, idempotencyKey, transport);
}

/** Derives protected material and submits one exact private Fixture revision for review. */
export async function shareFixtureSet(
  fixtureSetId: string, command: FixtureShareCommand, strongEtag: string, idempotencyKey: string,
  transport: AuthoringWorkbenchTransport = fetch,
): Promise<StoredResponse<FixtureShareReceipt>> {
  return storedResponse(await transport(
    `/api/authoring/fixture-sets/${encodeURIComponent(fixtureSetId)}:share`,
    {
      method: 'POST',
      headers: integrationRequestHeaders('CORRECTNESS_FIXTURE_MATERIAL_WRITE', {
        'Content-Type': 'application/json', 'If-Match': strongEtag, 'Idempotency-Key': idempotencyKey,
      }),
      body: JSON.stringify(command),
    },
  ), true);
}

/** Completes independent protected-material review for one exact pending Fixture revision. */
export async function reviewFixtureSet(
  fixtureSetId: string, command: FixtureReviewCommand, strongEtag: string, idempotencyKey: string,
  transport: AuthoringWorkbenchTransport = fetch,
): Promise<StoredResponse<FixtureReviewReceipt>> {
  return storedResponse(await transport(
    `/api/authoring/fixture-sets/${encodeURIComponent(fixtureSetId)}:review`,
    {
      method: 'POST',
      headers: integrationRequestHeaders('CORRECTNESS_REVIEW', {
        'Content-Type': 'application/json', 'If-Match': strongEtag, 'Idempotency-Key': idempotencyKey,
      }),
      body: JSON.stringify(command),
    },
  ), true);
}

/** Runs one exact Case from an independently addressed Fixture object. */
export async function simulateFixtureSetCase(
  fixtureSetId: string, revision: number, caseId: string, idempotencyKey: string,
  transport: AuthoringWorkbenchTransport = fetch,
): Promise<SimulationRun> {
  return simulateFlowFixture(fixtureSetId, revision, caseId, idempotencyKey, transport);
}

async function storedResponse<T>(response: Response, includeReplay: boolean): Promise<StoredResponse<T>> {
  const value = await body<T>(response);
  const strongEtag = response.headers.get('ETag');
  if (!strongEtag) throw new Error('The server did not return a strong ETag.');
  return { value, strongEtag, replayed: includeReplay && response.headers.get('Idempotency-Replayed') === 'true' };
}

async function fixtureReadResponse(response: Response): Promise<FixtureReadResponse> {
  return { value: await body<FixtureSetView>(response), strongEtag: response.headers.get('ETag'), replayed: false };
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
