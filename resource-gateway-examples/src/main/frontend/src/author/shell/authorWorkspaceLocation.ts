import type { AuthorMode } from './authorWorkspaceState';

export type AuthorWorkspaceView = 'interface' | 'scenarios' | 'compatibility' | 'evidence';

const AUTHOR_MODES = new Set<AuthorMode>(['compose', 'contract', 'scenarios', 'evidence']);
const WORKSPACE_VIEWS = new Set<AuthorWorkspaceView>([
  'interface',
  'scenarios',
  'compatibility',
  'evidence',
]);
const LEGACY_MODE_ALIASES: Record<string, AuthorMode> = {
  test: 'scenarios',
  review: 'evidence',
};

export interface AuthorWorkspaceLocation {
  mode: AuthorMode;
  selectedNodeId: string;
  target: string;
  workspaceView: AuthorWorkspaceView | '';
  scenarioId: string;
  runId: string;
  hasDeepLinkTarget: boolean;
}

export interface AuthorWorkspaceUrlCoordinate {
  target?: string;
  workspaceView?: AuthorWorkspaceView | '';
  scenarioId?: string;
  runId?: string;
}

/**
 * Reads shareable workspace coordinates without treating malformed URL state as domain data.
 *
 * Unknown modes fail closed to Compose. The deep-link bit lets the shell suppress the first-use
 * dialog while the existing draft/run loader restores the authoritative graph snapshot.
 */
export function parseAuthorWorkspaceLocation(search: string): AuthorWorkspaceLocation {
  const params = new URLSearchParams(search);
  const rawMode = params.get('authorMode')?.trim().toLowerCase() ?? '';
  const requestedMode = AUTHOR_MODES.has(rawMode as AuthorMode)
    ? rawMode as AuthorMode
    : LEGACY_MODE_ALIASES[rawMode];
  const rawView = params.get('workspaceView')?.trim().toLowerCase() ?? '';
  const workspaceView = WORKSPACE_VIEWS.has(rawView as AuthorWorkspaceView)
    ? rawView as AuthorWorkspaceView
    : '';
  const mode = requestedMode || modeForWorkspaceView(workspaceView) || 'compose';
  return {
    mode,
    selectedNodeId: params.get('nodeId')?.trim() ?? '',
    target: params.get('target')?.trim() ?? '',
    workspaceView,
    scenarioId: params.get('scenarioId')?.trim() ?? '',
    runId: params.get('runId')?.trim() ?? '',
    hasDeepLinkTarget: ['draftId', 'runId', 'operatorRef', 'gateIssueId', 'nodeId', 'scenarioId']
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
  coordinate: AuthorWorkspaceUrlCoordinate = {},
): string {
  const url = new URL(href);
  url.searchParams.set('authorMode', mode);
  if (selectedNodeId.trim()) {
    url.searchParams.set('nodeId', selectedNodeId.trim());
  } else {
    url.searchParams.delete('nodeId');
  }
  setOptionalCoordinate(url, 'target', coordinate.target);
  setOptionalCoordinate(url, 'workspaceView', coordinate.workspaceView);
  setOptionalCoordinate(url, 'scenarioId', coordinate.scenarioId);
  setOptionalCoordinate(url, 'runId', coordinate.runId, true);
  return `${url.pathname}${url.search}${url.hash}`;
}

function modeForWorkspaceView(view: AuthorWorkspaceView | ''): AuthorMode | '' {
  if (view === 'interface' || view === 'compatibility') {
    return 'contract';
  }
  if (view === 'scenarios') {
    return 'scenarios';
  }
  if (view === 'evidence') {
    return 'evidence';
  }
  return '';
}

function setOptionalCoordinate(
  url: URL,
  key: string,
  value: string | undefined,
  preserveWhenUndefined = false,
): void {
  if (value === undefined && preserveWhenUndefined) {
    return;
  }
  const normalized = value?.trim() ?? '';
  if (normalized) {
    url.searchParams.set(key, normalized);
  } else {
    url.searchParams.delete(key);
  }
}
