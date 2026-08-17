export interface ExactBusinessMirrorGraphSubject {
  graphName: string;
  graphRef: {
    id: string;
    revision: number;
    fingerprint: string;
  };
  packageId: string;
}

export interface WorkspaceRouteEnvironment {
  vscode: boolean;
  search?: string;
}

const PRESERVED_QUERY_KEYS = ['lang', 'sessionTenantId'] as const;

/** Builds an allowlisted exact Author coordinate for one Business Mirror source Graph. */
export function businessMirrorAuthorHref(
  subject: ExactBusinessMirrorGraphSubject,
  environment: WorkspaceRouteEnvironment,
): string {
  const params = new URLSearchParams();
  for (const key of PRESERVED_QUERY_KEYS) {
    const value = new URLSearchParams(environment.search ?? '').get(key)?.trim();
    if (value) params.set(key, value);
  }
  if (environment.vscode) params.set('workspaceRoute', 'author');
  params.set('authorWorkspace', 'v2');
  params.set('authorMode', 'compose');
  params.set('sourceKind', 'BUSINESS_MIRROR_LEGACY_GRAPH');
  params.set('sourceGraphName', required(subject.graphName, 'graphName'));
  params.set('sourceId', required(subject.graphRef.id, 'graphRef.id'));
  params.set('sourceRevision', positiveRevision(subject.graphRef.revision));
  params.set('sourceFingerprint', fingerprint(subject.graphRef.fingerprint));
  params.set('returnRoute', 'business-mirror');
  params.set('returnPackageId', required(subject.packageId, 'packageId'));
  params.set('returnTask', 'capabilities');
  params.set('returnAnchor', `graph:${required(subject.graphRef.id, 'graphRef.id')}`);
  const query = params.toString();
  return environment.vscode ? `?${query}` : `/author/?${query}`;
}

function required(value: string, name: string): string {
  const normalized = value.trim();
  if (!normalized) throw new Error(`${name} is required.`);
  return normalized;
}

function positiveRevision(value: number): string {
  if (!Number.isSafeInteger(value) || value < 1) {
    throw new Error('graphRef.revision must be a positive integer.');
  }
  return String(value);
}

function fingerprint(value: string): string {
  const normalized = value.trim();
  if (!/^sha256:[0-9a-f]{64}$/.test(normalized)) {
    throw new Error('graphRef.fingerprint must be an exact SHA-256 coordinate.');
  }
  return normalized;
}
