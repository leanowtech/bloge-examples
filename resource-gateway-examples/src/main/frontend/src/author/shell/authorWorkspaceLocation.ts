import type { AuthorMode } from './authorWorkspaceState';

const AUTHOR_MODES = new Set<AuthorMode>(['compose', 'contract', 'test', 'review']);

export interface AuthorWorkspaceLocation {
  mode: AuthorMode;
  selectedNodeId: string;
  hasDeepLinkTarget: boolean;
}

/**
 * Reads shareable workspace coordinates without treating malformed URL state as domain data.
 *
 * Unknown modes fail closed to Compose. The deep-link bit lets the shell suppress the first-use
 * dialog while the existing draft/run loader restores the authoritative graph snapshot.
 */
export function parseAuthorWorkspaceLocation(search: string): AuthorWorkspaceLocation {
  const params = new URLSearchParams(search);
  const requestedMode = params.get('authorMode')?.trim().toLowerCase() as AuthorMode | undefined;
  return {
    mode: requestedMode && AUTHOR_MODES.has(requestedMode) ? requestedMode : 'compose',
    selectedNodeId: params.get('nodeId')?.trim() ?? '',
    hasDeepLinkTarget: ['draftId', 'runId', 'operatorRef', 'gateIssueId', 'nodeId']
      .some((key) => Boolean(params.get(key)?.trim())),
  };
}

/**
 * Projects ephemeral mode and selection into a URL while preserving every integration deep-link
 * coordinate. Panel sizes and open/closed state intentionally remain local UI preferences.
 */
export function authorWorkspaceUrl(
  href: string,
  mode: AuthorMode,
  selectedNodeId: string,
): string {
  const url = new URL(href);
  url.searchParams.set('authorMode', mode);
  if (selectedNodeId.trim()) {
    url.searchParams.set('nodeId', selectedNodeId.trim());
  } else {
    url.searchParams.delete('nodeId');
  }
  return `${url.pathname}${url.search}${url.hash}`;
}
