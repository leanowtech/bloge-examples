import { describe, expect, it } from 'vitest';

import { authorWorkspaceUrl, parseAuthorWorkspaceLocation } from './authorWorkspaceLocation';

describe('authorWorkspaceLocation', () => {
  it('defaults malformed workspace state to a blank Compose coordinate', () => {
    expect(parseAuthorWorkspaceLocation('?authorMode=unknown')).toEqual({
      mode: 'compose',
      selectedNodeId: '',
      target: '',
      workspaceView: '',
      scenarioId: '',
      runId: '',
      hasDeepLinkTarget: false,
    });
  });

  it('restores mode and selection from an integration deep link', () => {
    expect(parseAuthorWorkspaceLocation(
      '?authorWorkspace=v2&draftId=draft-7&nodeId=policy&authorMode=evidence'
      + '&target=graph&workspaceView=evidence&scenarioId=decline&runId=run-9',
    )).toEqual({
      mode: 'evidence',
      selectedNodeId: 'policy',
      target: 'graph',
      workspaceView: 'evidence',
      scenarioId: 'decline',
      runId: 'run-9',
      hasDeepLinkTarget: true,
    });
  });

  it('maps old Test and Review links onto the canonical Scenarios and Evidence modes', () => {
    expect(parseAuthorWorkspaceLocation('?authorMode=test').mode).toBe('scenarios');
    expect(parseAuthorWorkspaceLocation('?authorMode=review').mode).toBe('evidence');
    expect(parseAuthorWorkspaceLocation('?workspaceView=scenarios').mode).toBe('scenarios');
    expect(parseAuthorWorkspaceLocation('?operatorRef=risk%3Ascore').target)
      .toBe('operator:risk:score');
  });

  it('treats an exact Business Mirror source as a deep-link target', () => {
    expect(parseAuthorWorkspaceLocation(
      '?authorMode=compose&sourceKind=BUSINESS_MIRROR_LEGACY_GRAPH&sourceId=built-in%3Aloan',
    ).hasDeepLinkTarget).toBe(true);
  });

  it('updates workspace coordinates without dropping draft, run, or hash coordinates', () => {
    expect(authorWorkspaceUrl(
      'http://localhost/author/?authorWorkspace=v2&draftId=draft-7&runId=run-9#evidence',
      'scenarios',
      'policy',
      {
        target: 'graph',
        workspaceView: 'scenarios',
        scenarioId: 'decline',
      },
    )).toBe(
      '/author/?authorWorkspace=v2&draftId=draft-7&runId=run-9&authorMode=scenarios'
      + '&nodeId=policy&target=graph&workspaceView=scenarios&scenarioId=decline#evidence',
    );
  });

  it('removes a stale selection while retaining the selected mode', () => {
    expect(authorWorkspaceUrl(
      'http://localhost/author/?authorWorkspace=v2&nodeId=old',
      'contract',
      '',
      { target: 'graph', workspaceView: 'interface', scenarioId: '' },
    )).toBe(
      '/author/?authorWorkspace=v2&authorMode=contract&target=graph&workspaceView=interface',
    );
  });

  it('rewrites a legacy operatorRef into the canonical target coordinate', () => {
    expect(authorWorkspaceUrl(
      'http://localhost/author/?operatorRef=risk%3Ascore&workspaceView=scenarios',
      'scenarios',
      'score-node',
      {
        target: 'operator:risk:score',
        workspaceView: 'scenarios',
        scenarioId: 'golden',
      },
    )).toBe(
      '/author/?workspaceView=scenarios&authorMode=scenarios&nodeId=score-node'
      + '&target=operator%3Arisk%3Ascore&scenarioId=golden',
    );
  });
});
