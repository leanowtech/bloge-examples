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

export interface AuthoringLinkDescriptor {
  schemaVersion: 'bloge.authoringLinkDescriptor.v1';
  resolution: 'READ_ONLY_SOURCE' | 'EXISTING_DRAFT';
  route: {
    path: '/author/';
    workspace: 'v2';
    authorMode: 'compose';
    query: Record<string, string>;
  };
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

/** Converts the service-resolved structured route into the current Web or VS Code host route. */
export function resolvedBusinessMirrorAuthorHref(
  descriptor: AuthoringLinkDescriptor,
  environment: WorkspaceRouteEnvironment,
): string {
  if (descriptor.schemaVersion !== 'bloge.authoringLinkDescriptor.v1'
    || descriptor.route.path !== '/author/'
    || descriptor.route.workspace !== 'v2'
    || descriptor.route.authorMode !== 'compose') {
    throw new Error('The Authoring Link Resolver returned an unsupported route.');
  }
  const params = new URLSearchParams();
  for (const key of PRESERVED_QUERY_KEYS) {
    const value = new URLSearchParams(environment.search ?? '').get(key)?.trim();
    if (value) params.set(key, value);
  }
  for (const [key, value] of Object.entries(descriptor.route.query)) {
    if (!/^[A-Za-z][A-Za-z0-9]*$/.test(key) || !value.trim()) {
      throw new Error('The Authoring Link Resolver returned an invalid query coordinate.');
    }
    if (key === 'returnUrl' || key === 'showcaseHref' || value.includes('/showcase')) {
      throw new Error('The Authoring Link Resolver returned a forbidden route.');
    }
    params.set(key, value);
  }
  params.set('authorWorkspace', 'v2');
  params.set('authorMode', 'compose');
  if (environment.vscode) params.set('workspaceRoute', 'author');
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
