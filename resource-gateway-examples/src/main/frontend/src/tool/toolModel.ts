import type { SchemaEnvelope } from '../types';
import type { ToolCoordinate } from '../spine/authorSpine';

/** The smallest draft projection accepted by the tool-signature seam. */
export interface ToolDraftLike {
  draftId?: string;
  revision?: number;
  graphName?: string;
  status?: string;
  inputSchema?: SchemaEnvelope;
  outputSchema?: SchemaEnvelope;
  nodes?: Array<{ id: string; operatorRef: string }>;
  publicationId?: string;
  publicationRevision?: number;
}

/** Lifecycle state derived from a server draft or an immutable publication receipt. */
export type ToolLifecycleState = 'draft' | 'published' | 'unknown';

/** Honest schema availability for the current draft projection. */
export type ToolSchemaState = 'typed' | 'opaque' | 'unknown';

/** Frozen I/O and lifecycle projection used when a graph is presented as a tool. */
export interface ToolSignature {
  toolId: string;
  toolName: string;
  input?: SchemaEnvelope;
  output?: SchemaEnvelope;
  state: ToolLifecycleState;
  schemaState: ToolSchemaState;
  publicationId?: string;
  publicationRevision?: number;
}

/** Optional publication metadata supplied by a publication response. */
export interface ToolPublicationMetadata {
  publicationId: string;
  publicationRevision: number;
}

/**
 * Project a graph draft into a tool signature.
 *
 * <p>Only the requested identity, graph input/output schemas, lifecycle state,
 * and existing publication metadata are copied. Nodes, operators, and other
 * graph protocol fields are intentionally excluded.</p>
 *
 * @param draft graph draft projection
 * @param identity tool identity, or a tool id when `toolName` is provided
 * @param toolNameOrPublication legacy display name or the immutable publication receipt
 * @param publication existing publication metadata, when available
 * @returns a minimal tool signature
 */
export function toolSignatureFromDraft(
  draft: ToolDraftLike | undefined,
  identity: ToolCoordinate | { toolId: string; toolName: string } | string,
  toolNameOrPublication?: string | ToolPublicationMetadata,
  publication?: ToolPublicationMetadata,
): ToolSignature {
  const suppliedPublication = typeof toolNameOrPublication === 'object'
    ? toolNameOrPublication
    : publication;
  const legacyToolName = typeof toolNameOrPublication === 'string' ? toolNameOrPublication : undefined;
  const resolvedIdentity = typeof identity === 'string'
    ? { toolId: identity, toolName: legacyToolName ?? identity }
    : { toolId: identity.toolId, toolName: identity.toolName };
  const state = suppliedPublication
    ? 'published'
    : draft?.status?.trim().toLowerCase() === 'published'
      ? 'published'
      : draft?.status?.trim().toLowerCase() === 'draft'
        ? 'draft'
        : 'unknown';
  const schemaState: ToolSchemaState = !draft
    ? 'unknown'
    : draft.inputSchema?.schema && draft.outputSchema?.schema ? 'typed' : 'opaque';
  const existingPublication = suppliedPublication ?? (
    draft?.publicationId && draft.publicationRevision !== undefined
      ? { publicationId: draft.publicationId, publicationRevision: draft.publicationRevision }
      : undefined
  );
  const signature: ToolSignature = {
    toolId: resolvedIdentity.toolId,
    toolName: resolvedIdentity.toolName,
    ...(draft?.inputSchema ? { input: draft.inputSchema } : {}),
    ...(draft?.outputSchema ? { output: draft.outputSchema } : {}),
    state,
    schemaState,
  };
  if (state === 'published' && existingPublication) {
    signature.publicationId = existingPublication.publicationId;
    signature.publicationRevision = existingPublication.publicationRevision;
  }
  return signature;
}

/**
 * Return the existing catalog reference for a valid immutable publication.
 *
 * @param publicationId non-empty publication identifier
 * @param publicationRevision positive integer revision
 * @returns the pre-existing `publication:<id>` operator reference
 * @throws {@link TypeError} when the id or revision is invalid
 */
export function publicationOperatorRef(publicationId: string, publicationRevision: number): string {
  if (typeof publicationId !== 'string' || publicationId.trim() === '') {
    throw new TypeError('publicationId must not be blank');
  }
  if (!Number.isInteger(publicationRevision) || publicationRevision < 1) {
    throw new TypeError('publicationRevision must be a positive integer');
  }
  return `publication:${publicationId.trim()}`;
}
