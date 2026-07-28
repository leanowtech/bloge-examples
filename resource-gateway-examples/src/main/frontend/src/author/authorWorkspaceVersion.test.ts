import { describe, expect, it } from 'vitest';

import {
  AUTHOR_WORKSPACE_VERSION_QUERY,
  authorWorkspaceEntryHref,
  resolveAuthorWorkspaceVersion,
} from './authorWorkspaceVersion';

describe('author workspace version', () => {
  it('uses the task-oriented v2 shell by default', () => {
    expect(resolveAuthorWorkspaceVersion('')).toBe('v2');
    expect(resolveAuthorWorkspaceVersion('?draftId=draft-1')).toBe('v2');
  });

  it('accepts an explicit v2 coordinate', () => {
    expect(resolveAuthorWorkspaceVersion(`?${AUTHOR_WORKSPACE_VERSION_QUERY}=v2`)).toBe('v2');
    expect(resolveAuthorWorkspaceVersion('?draftId=draft-1&authorWorkspace=v2')).toBe('v2');
  });

  it('opens legacy through its named coordinate and the former v1 alias', () => {
    expect(resolveAuthorWorkspaceVersion('?authorWorkspace=legacy')).toBe('v1');
    expect(resolveAuthorWorkspaceVersion('?authorWorkspace=v1')).toBe('v1');
  });

  it('fails closed to legacy for unknown or malformed explicit values', () => {
    expect(resolveAuthorWorkspaceVersion('?authorWorkspace=next')).toBe('v1');
    expect(resolveAuthorWorkspaceVersion('?authorWorkspace=V2')).toBe('v1');
    expect(resolveAuthorWorkspaceVersion('?authorWorkspace=')).toBe('v1');
  });

  it('builds canonical switch links without discarding deep-link context', () => {
    const search = '?authorWorkspace=v1&draftId=draft-7&nodeId=policy&runId=run-9';

    expect(authorWorkspaceEntryHref(search, 'v2'))
      .toBe('/author/?draftId=draft-7&nodeId=policy&runId=run-9');
    expect(authorWorkspaceEntryHref(search, 'v1'))
      .toBe('/author/?authorWorkspace=legacy&draftId=draft-7&nodeId=policy&runId=run-9');
    expect(authorWorkspaceEntryHref('', 'v2')).toBe('/author/');
  });
});
