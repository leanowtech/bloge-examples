import { exchangeCorrectnessApi } from '../../api';
import type {
  CorrectnessApiEnvelope,
  CorrectnessDeploymentCapabilities,
  CorrectnessPreflightReport,
  CorrectnessPreflightRequest,
  CorrectnessRunRequest,
  CorrectnessRunResponse,
  OutcomeCalibrationRequest,
  StoredCorrectnessGovernanceFeedback,
  CorrectnessWorkspaceCoordinate,
  CorrectnessWorkspaceProjection,
  IntegrationEnvelope,
  StoredCorrectnessEvidenceCompanion,
  StoredOutcomeCalibrationProposal,
} from '../model/domain';

export async function fetchCorrectnessCapabilities(): Promise<CorrectnessDeploymentCapabilities> {
  const envelope = await exchangeCorrectnessApi<
  IntegrationEnvelope<CorrectnessDeploymentCapabilities>
  >('/api/integration/capabilities', 'CORRECTNESS_READ');
  return envelope.payload;
}

export async function fetchCorrectnessWorkspace(
  coordinate: CorrectnessWorkspaceCoordinate,
): Promise<CorrectnessApiEnvelope<CorrectnessWorkspaceProjection>> {
  const query = new URLSearchParams({
    targetFingerprint: coordinate.targetFingerprint,
    definitionId: coordinate.definitionId ?? '',
    caseCursor: coordinate.caseCursor ?? '',
    caseLimit: String(coordinate.caseLimit ?? 100),
  });
  return exchangeCorrectnessApi(
    `/api/visual/correctness-workspaces/${encodeURIComponent(coordinate.targetKind)}`
      + `/${encodeURIComponent(coordinate.targetId)}?${query}`,
    'CORRECTNESS_READ',
  );
}

export async function preflightCorrectnessRun(
  request: CorrectnessPreflightRequest,
): Promise<CorrectnessApiEnvelope<CorrectnessPreflightReport>> {
  return exchangeCorrectnessApi('/api/visual/correctness-runs:preflight', 'TEST_EXECUTION', {
    method: 'POST',
    body: request,
  });
}

export async function executeCorrectnessRun(
  request: CorrectnessRunRequest,
): Promise<CorrectnessApiEnvelope<CorrectnessRunResponse>> {
  return exchangeCorrectnessApi('/api/visual/correctness-runs', 'TEST_EXECUTION', {
    method: 'POST',
    body: request,
  });
}

export async function fetchCorrectnessEvidence(
  suiteRunId: string,
): Promise<CorrectnessApiEnvelope<StoredCorrectnessEvidenceCompanion>> {
  return exchangeCorrectnessApi(
    `/api/visual/correctness-runs/${encodeURIComponent(suiteRunId)}/evidence-companion`,
    'GOVERNANCE_EVIDENCE_INGESTION',
  );
}

export async function createOutcomeCalibrationProposal(
  request: OutcomeCalibrationRequest,
): Promise<CorrectnessApiEnvelope<StoredOutcomeCalibrationProposal>> {
  return exchangeCorrectnessApi(
    '/api/visual/correctness-outcome-calibration-proposals',
    'CORRECTNESS_WRITE',
    { method: 'POST', body: request },
  );
}

export async function fetchCorrectnessGovernanceFeedback(
  publicationId: string,
): Promise<CorrectnessApiEnvelope<StoredCorrectnessGovernanceFeedback>> {
  return exchangeCorrectnessApi(
    `/api/visual/correctness-publications/${encodeURIComponent(publicationId)}`
      + '/governance-feedback',
    'CORRECTNESS_READ',
  );
}

export function publicationRef(
  projection: CorrectnessWorkspaceProjection,
): CorrectnessPreflightRequest['publicationRef'] | null {
  const exact = projection.lastPublication?.publicationRef;
  if (!exact || exact.revision !== 1) return null;
  return {
    publicationId: exact.id,
    revision: 1,
    fingerprint: exact.fingerprint,
  };
}

export function selectionIntent(
  mode: CorrectnessPreflightRequest['selection']['mode'],
  caseIds: string[],
  expectedSelectionFingerprint = '',
): CorrectnessPreflightRequest['selection'] {
  return {
    mode,
    caseIds: mode === 'ALL' ? [] : [...new Set(caseIds.map((value) => value.trim())
      .filter(Boolean))].sort(),
    expectedSelectionFingerprint,
  };
}

export function correctnessClientRequestId(): string {
  const random = globalThis.crypto?.randomUUID?.();
  if (random) return `correctness-${random}`;
  return `correctness-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 14)}`;
}
