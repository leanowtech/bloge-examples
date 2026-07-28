import { describe, expect, it } from 'vitest';

import { authorWorkspaceUrl, parseAuthorWorkspaceLocation } from './authorWorkspaceLocation';

describe('authorWorkspaceLocation', () => {
  it('defaults malformed workspace state to a blank Compose coordinate', () => {
    expect(parseAuthorWorkspaceLocation('?authorMode=unknown')).toEqual({
      mode: 'compose',
      selectedNodeId: '',
      hasDeepLinkTarget: false,
    });
  });

  it('restores mode and selection from an integration deep link', () => {
    expect(parseAuthorWorkspaceLocation(
      '?authorWorkspace=v2&draftId=draft-7&nodeId=policy&authorMode=review',
    )).toEqual({
      mode: 'review',
      selectedNodeId: 'policy',
      hasDeepLinkTarget: true,
    });
  });

  it('updates workspace coordinates without dropping draft, run, or hash coordinates', () => {
    expect(authorWorkspaceUrl(
      'http://localhost/author/?authorWorkspace=v2&draftId=draft-7&runId=run-9#evidence',
      'test',
      'policy',
    )).toBe(
      '/author/?authorWorkspace=v2&draftId=draft-7&runId=run-9&authorMode=test&nodeId=policy#evidence',
    );
  });

  it('removes a stale selection while retaining the selected mode', () => {
    expect(authorWorkspaceUrl(
      'http://localhost/author/?authorWorkspace=v2&nodeId=old',
      'contract',
      '',
    )).toBe('/author/?authorWorkspace=v2&authorMode=contract');
  });
});
