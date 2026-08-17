import {
  parseCapabilityStudioDemoPack,
  parseScenarioDatasetProjection,
  isCapabilityStudioProtocolError,
  type CapabilityStudioModel,
  parseFeatureRehearsalProjection,
  type FeatureRehearsalPermission,
  type FeatureRehearsalProjection,
  type ScenarioDataset,
} from './domain';

export type CapabilityStudioFetcher = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;

export async function fetchFeatureRehearsal(
  caseId: string,
  permission: FeatureRehearsalPermission,
  fetcher: CapabilityStudioFetcher = fetch,
): Promise<FeatureRehearsalProjection> {
  if (!/^[A-Za-z0-9][A-Za-z0-9._:/#@-]*$/.test(caseId)) {
    throw new CapabilityStudioRequestError(
      'RG.CAPABILITY_STUDIO.INVALID_CASE_ID',
      'The selected scenario is not valid.',
      'The Feature rehearsal was not changed.',
      'Choose a scenario from the list and retry.',
      0,
      'caseId',
    );
  }
  const query = new URLSearchParams({ caseId, permission });
  const payload = await requestJson<unknown>(
    fetcher,
    `/api/capability-studio/feature-rehearsal?${query.toString()}`,
    undefined,
    {
      unchangedImpact: 'The Feature rehearsal was not changed.',
      invalidImpact: 'The Feature rehearsal response cannot be trusted or displayed.',
      invalidRecovery: 'Choose the scenario again and retry the rehearsal.',
    },
  );
  try {
    return parseFeatureRehearsalProjection(payload);
  } catch (error) {
    if (isCapabilityStudioProtocolError(error)) {
      throw new CapabilityStudioRequestError(error.code, error.message, error.impact, 'Choose the scenario again and retry the rehearsal.', 200);
    }
    throw error;
  }
}

export interface TutorialBehavior {
  dependencyId: string;
  dependencyName: string;
  condition: string;
  behavior: 'TIMEOUT';
  durationMs: number;
}

export interface TutorialBranchProjection {
  branchId: string;
  revision: number;
  fingerprint: string;
  canonicalBaselineFingerprint: string;
  behavior: TutorialBehavior;
}

export interface TutorialBranchPreflight {
  mode: 'ISOLATED';
  unresolvedDependencies: number;
  realExternalCallCount: number;
  fallbackToReal: false;
  branchId: string;
  revision: number;
  fingerprint: string;
}

export interface SaveTutorialBehaviorRequest {
  condition: string;
  behavior: 'TIMEOUT';
  durationMs: number;
  expectedRevision: number;
}

export class CapabilityStudioRequestError extends Error {
  constructor(
    readonly code: string,
    readonly whatHappened: string,
    readonly impact: string,
    readonly recoveryAction: string,
    readonly status: number,
    readonly field?: string,
  ) {
    super(whatHappened);
    this.name = 'CapabilityStudioRequestError';
  }
}

export async function fetchCapabilityStudioDemoPack(
  fetcher: CapabilityStudioFetcher = fetch,
): Promise<CapabilityStudioModel> {
  let response: Response;
  try {
    response = await fetcher('/api/capability-studio/demo-pack', {
      headers: { Accept: 'application/json' },
    });
  } catch (error) {
    throw new Error(error instanceof Error ? error.message : 'Network request failed.');
  }
  if (!response.ok) {
    throw new Error(`Capability Studio demo pack request failed with HTTP ${response.status}.`);
  }
  let payload: unknown;
  try {
    payload = await response.json();
  } catch (error) {
    throw new Error(error instanceof Error ? error.message : 'The response was not valid JSON.');
  }
  return parseCapabilityStudioDemoPack(payload);
}

export async function fetchScenarioDataset(
  fetcher: CapabilityStudioFetcher = fetch,
): Promise<ScenarioDataset> {
  const payload = await requestJson<unknown>(
    fetcher,
    '/api/capability-studio/scenario-dataset',
    undefined,
    {
      unchangedImpact: 'The scenario dataset was not loaded or changed.',
      invalidImpact: 'The scenario dataset cannot be trusted or displayed.',
      invalidRecovery: 'Reload the scenario dataset before continuing.',
    },
  );
  try {
    return parseScenarioDatasetProjection(payload);
  } catch (error) {
    if (isCapabilityStudioProtocolError(error)) {
      throw new CapabilityStudioRequestError(
        error.code,
        error.message,
        error.impact,
        'Reload the scenario dataset and retry.',
        200,
      );
    }
    throw error;
  }
}

export async function fetchTutorialBranch(
  fetcher: CapabilityStudioFetcher = fetch,
): Promise<TutorialBranchProjection> {
  return parseTutorialBranchProjection(
    await requestJson<unknown>(fetcher, '/api/capability-studio/tutorial-branch'),
  );
}

export async function saveTutorialBehavior(
  request: SaveTutorialBehaviorRequest,
  fetcher: CapabilityStudioFetcher = fetch,
): Promise<TutorialBranchProjection> {
  return parseTutorialBranchProjection(await requestJson<unknown>(fetcher, '/api/capability-studio/tutorial-branch/behaviors/compensation-history', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  }));
}

export async function preflightTutorialBranch(
  fetcher: CapabilityStudioFetcher = fetch,
): Promise<TutorialBranchPreflight> {
  return parseTutorialBranchPreflight(await requestJson<unknown>(fetcher, '/api/capability-studio/tutorial-branch/preflight', {
    method: 'POST',
  }));
}

async function requestJson<T>(
  fetcher: CapabilityStudioFetcher,
  input: string,
  init?: RequestInit,
  context: RequestErrorContext = tutorialBranchRequestContext,
): Promise<T> {
  let response: Response;
  try {
    response = await fetcher(input, {
      ...init,
      headers: { Accept: 'application/json', ...init?.headers },
    });
  } catch (error) {
    throw new CapabilityStudioRequestError(
      'RG.CAPABILITY_STUDIO.NETWORK_UNAVAILABLE',
      error instanceof Error ? error.message : 'The request could not reach Capability Studio.',
      context.unchangedImpact,
      'Check that the local demo service is running, then retry.',
      0,
    );
  }
  const payload = await parseResponseBody(response);
  if (!response.ok) {
    const error = isObject(payload) ? payload : {};
    throw new CapabilityStudioRequestError(
      stringField(error.code) ?? `RG.CAPABILITY_STUDIO.HTTP_${response.status}`,
      stringField(error.whatHappened) ?? `Capability Studio rejected the request with HTTP ${response.status}.`,
      stringField(error.impact) ?? context.unchangedImpact,
      stringField(error.recoveryAction) ?? 'Review the highlighted value and retry.',
      response.status,
      stringField(error.field),
    );
  }
  if (!isObject(payload)) {
    throw new CapabilityStudioRequestError(
      'RG.CAPABILITY_STUDIO.INVALID_RESPONSE',
      'Capability Studio returned an invalid response.',
      context.invalidImpact,
      context.invalidRecovery,
      response.status,
    );
  }
  return payload as T;
}

interface RequestErrorContext {
  unchangedImpact: string;
  invalidImpact: string;
  invalidRecovery: string;
}

const tutorialBranchRequestContext: RequestErrorContext = {
  unchangedImpact: 'The tutorial branch was not changed.',
  invalidImpact: 'The result cannot be trusted or displayed.',
  invalidRecovery: 'Reload the tutorial branch before continuing.',
};

async function parseResponseBody(response: Response): Promise<unknown> {
  try {
    return await response.json();
  } catch {
    return undefined;
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function stringField(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function parseTutorialBranchProjection(value: unknown): TutorialBranchProjection {
  const source = responseObject(value);
  const behavior = responseObject(source.behavior);
  const parsed: TutorialBranchProjection = {
    branchId: requiredString(source.branchId, 'branchId'),
    revision: requiredPositiveInteger(source.revision, 'revision'),
    fingerprint: requiredFingerprint(source.fingerprint, 'fingerprint'),
    canonicalBaselineFingerprint: requiredFingerprint(
      source.canonicalBaselineFingerprint,
      'canonicalBaselineFingerprint',
    ),
    behavior: {
      dependencyId: requiredString(behavior.dependencyId, 'behavior.dependencyId'),
      dependencyName: requiredString(behavior.dependencyName, 'behavior.dependencyName'),
      condition: requiredString(behavior.condition, 'behavior.condition'),
      behavior: requiredLiteral(behavior.behavior, 'TIMEOUT', 'behavior.behavior'),
      durationMs: requiredIntegerInRange(behavior.durationMs, 100, 30_000, 'behavior.durationMs'),
    },
  };
  return parsed;
}

function parseTutorialBranchPreflight(value: unknown): TutorialBranchPreflight {
  const source = responseObject(value);
  return {
    mode: requiredLiteral(source.mode, 'ISOLATED', 'mode'),
    unresolvedDependencies: requiredIntegerInRange(source.unresolvedDependencies, 0, 0, 'unresolvedDependencies'),
    realExternalCallCount: requiredIntegerInRange(source.realExternalCallCount, 0, 0, 'realExternalCallCount'),
    fallbackToReal: requiredLiteral(source.fallbackToReal, false, 'fallbackToReal'),
    branchId: requiredString(source.branchId, 'branchId'),
    revision: requiredPositiveInteger(source.revision, 'revision'),
    fingerprint: requiredFingerprint(source.fingerprint, 'fingerprint'),
  };
}

function responseObject(value: unknown): Record<string, unknown> {
  if (!isObject(value)) throw invalidResponse('Expected an object response.');
  return value;
}

function requiredString(value: unknown, field: string): string {
  const parsed = stringField(value);
  if (!parsed) throw invalidResponse(`Missing ${field}.`);
  return parsed;
}

function requiredFingerprint(value: unknown, field: string): string {
  const parsed = requiredString(value, field);
  if (!/^sha256:[0-9a-f]{64}$/.test(parsed)) throw invalidResponse(`Invalid ${field}.`);
  return parsed;
}

function requiredPositiveInteger(value: unknown, field: string): number {
  return requiredIntegerInRange(value, 1, Number.MAX_SAFE_INTEGER, field);
}

function requiredIntegerInRange(value: unknown, minimum: number, maximum: number, field: string): number {
  if (!Number.isInteger(value) || (value as number) < minimum || (value as number) > maximum) {
    throw invalidResponse(`Invalid ${field}.`);
  }
  return value as number;
}

function requiredLiteral<T extends string | boolean>(value: unknown, expected: T, field: string): T {
  if (value !== expected) throw invalidResponse(`Invalid ${field}.`);
  return expected;
}

function invalidResponse(detail: string): CapabilityStudioRequestError {
  return new CapabilityStudioRequestError(
    'RG.CAPABILITY_STUDIO.INVALID_RESPONSE',
    `Capability Studio returned an invalid response. ${detail}`,
    'The result cannot be trusted or displayed.',
    'Reload the tutorial branch before continuing.',
    200,
  );
}
