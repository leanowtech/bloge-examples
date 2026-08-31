import { integrationRequestHeaders } from '../api';
import type { FixtureSetSummary, SimulationRun } from './model';
import type {
  FixtureSetCommand,
  FixtureSetSaveReceipt,
  FixtureSetView,
  FlowDraftRef,
  ReusableFlowCommand,
  ReusableFlowDraft,
  ReusableFlowPublishReceipt,
  ReusableFlowSaveReceipt,
} from './flowModel';
import type { AuthoringWorkbenchTransport, StoredResponse } from './api';

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
): Promise<StoredResponse<FixtureSetView>> {
  const suffix = revision === undefined ? '' : `?revision=${revision}`;
  return storedResponse(await transport(
    `/api/authoring/fixture-sets/${encodeURIComponent(fixtureSetId)}${suffix}`,
    { headers: integrationRequestHeaders('API_RESOURCE_AUTHORING') },
  ), false);
}

/** Discovers payload-free Fixtures for one exact Flow draft revision. */
export async function listFlowDraftFixtures(
  subject: FlowDraftRef, transport: AuthoringWorkbenchTransport = fetch,
): Promise<FixtureSetSummary[]> {
  const query = new URLSearchParams({
    subjectKind: 'FLOW_DRAFT', subjectId: subject.draftId,
    subjectRevision: String(subject.revision), subjectFingerprint: subject.fingerprint,
  });
  return body(await transport(`/api/authoring/fixture-sets?${query}`, {
    headers: integrationRequestHeaders('API_RESOURCE_AUTHORING'),
  }));
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

async function storedResponse<T>(response: Response, includeReplay: boolean): Promise<StoredResponse<T>> {
  const value = await body<T>(response);
  const strongEtag = response.headers.get('ETag');
  if (!strongEtag) throw new Error('The server did not return a strong ETag.');
  return { value, strongEtag, replayed: includeReplay && response.headers.get('Idempotency-Replayed') === 'true' };
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
