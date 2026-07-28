import { describe, expect, it } from 'vitest';

import {
  AUTHOR_WORKSPACE_VERSION_QUERY,
  resolveAuthorWorkspaceVersion,
} from './authorWorkspaceVersion';

describe('author workspace version', () => {
  it('keeps the proven v1 shell as the default', () => {
    expect(resolveAuthorWorkspaceVersion('')).toBe('v1');
    expect(resolveAuthorWorkspaceVersion('?draftId=draft-1')).toBe('v1');
  });

  it('enables v2 only through the exact opt-in query', () => {
    expect(resolveAuthorWorkspaceVersion(`?${AUTHOR_WORKSPACE_VERSION_QUERY}=v2`)).toBe('v2');
    expect(resolveAuthorWorkspaceVersion('?draftId=draft-1&authorWorkspace=v2')).toBe('v2');
  });

  it('fails closed to v1 for unknown or malformed values', () => {
    expect(resolveAuthorWorkspaceVersion('?authorWorkspace=next')).toBe('v1');
    expect(resolveAuthorWorkspaceVersion('?authorWorkspace=V2')).toBe('v1');
    expect(resolveAuthorWorkspaceVersion('?authorWorkspace=')).toBe('v1');
  });
});
