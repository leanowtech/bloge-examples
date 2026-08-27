import type { SchemaEnvelope } from '../types';

/** The smallest draft projection accepted by the tool-signature seam. */
export interface ToolDraftLike {
  graphName?: string;
  status?: string;
  inputSchema?: SchemaEnvelope;
  outputSchema?: SchemaEnvelope;
  nodes?: Array<{ id: string; operatorRef: string }>;
  publicationId?: string;
  publicationRevision?: number;
}

/** Frozen I/O and lifecycle projection used when a graph is presented as a tool. */
export interface ToolSignature {
  toolId: string;
  toolName: string;
  input?: SchemaEnvelope;
  output?: SchemaEnvelope;
  state: 'draft' | 'published';
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
 * @param toolName display name when the second argument is a string
 * @param publication existing publication metadata, when available
 * @returns a minimal tool signature
 */
export function toolSignatureFromDraft(
  draft: ToolDraftLike,
  identity: { toolId: string; toolName: string } | string,
  toolName?: string,
  publication?: ToolPublicationMetadata,
): ToolSignature {
  const resolvedIdentity = typeof identity === 'string'
    ? { toolId: identity, toolName: toolName ?? identity }
    : identity;
  const state = draft.status?.trim().toLowerCase() === 'published' ? 'published' : 'draft';
  const existingPublication = publication ?? (
    draft.publicationId && draft.publicationRevision !== undefined
      ? { publicationId: draft.publicationId, publicationRevision: draft.publicationRevision }
      : undefined
  );
  const signature: ToolSignature = {
    toolId: resolvedIdentity.toolId,
    toolName: resolvedIdentity.toolName,
    input: draft.inputSchema,
    output: draft.outputSchema,
    state,
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
