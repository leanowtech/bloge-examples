/** Discovery metadata deliberately excludes business request/response payloads. */
export interface ReferenceCandidate {
  schemaVersion: 'bloge.referenceCandidate.v1';
  kind: string;
  id: string;
  displayName: string;
  description: string;
  revision: number;
  fingerprint: string;
  authority: string;
  scope: ReferenceScope;
  lifecycle: ReferenceLifecycle;
  owner: ReferenceOwner | null;
  labels: readonly string[];
  compatibility: ReferenceCompatibility;
  disabledReasonCode: string;
}

export interface ReferenceScope {
  tenantId: string;
  organizationId: string;
  projectId: string;
  environmentId: string;
  region: string;
}

export interface ReferenceOwner {
  stableId: string;
  displayName: string;
}

export type ReferenceLifecycle = 'DRAFT' | 'ACTIVE' | 'DEPRECATED' | 'SUPERSEDED';
export type ReferenceCompatibility = 'COMPATIBLE' | 'REVIEW' | 'INCOMPATIBLE' | 'UNKNOWN';

export interface ReferenceQuery {
  query: string;
  cursor: string | null;
  limit: number;
}

export interface ReferencePage {
  schemaVersion: 'bloge.referencePage.v1';
  items: readonly ReferenceCandidate[];
  nextCursor: string | null;
  queryFingerprint: string;
  catalogGeneration: number;
}

export type ReferenceErrorStatus = 'error' | 'unavailable';

export interface ReferenceQueryError extends Error {
  status: ReferenceErrorStatus;
  retryable?: boolean;
}

export type ReferenceLoadState = 'idle' | 'loading' | 'ready' | 'empty' | ReferenceErrorStatus;

export type ReferenceCandidateSearch = (
  query: ReferenceQuery,
  signal: AbortSignal,
) => Promise<ReferencePage>;

export interface ReferenceResolveCommand {
  schemaVersion: 'bloge.referenceResolveCommand.v1';
  kind: string;
  id: string;
  revision: number;
  fingerprint: string;
  intendedUse: string;
}

export interface ReferenceResolveResult {
  schemaVersion: 'bloge.referenceResolveResult.v1';
  status: 'RESOLVED' | 'NOT_FOUND' | 'DRIFTED' | 'FORBIDDEN';
  candidate: ReferenceCandidate | null;
  errorCode: string;
}
