import { describe, expect, it } from 'vitest';

import {
  parseToolCoordinate,
  resolveSpine,
  toolCoordinateHref,
  type ToolCoordinate,
} from './authorSpine';

describe('resolveSpine', () => {
  it('enables only one exact spine=v1 query value', () => {
    expect(resolveSpine('?spine=v1')).toBe('v1');
  });

  it.each([
    '',
    '?spine=V1',
    '?spine=v2',
    '?SPINE=v1',
    '?spine=v1%20',
    '?spine=v1&spine=v1',
    '?spine=v1&spine=off',
    '?spine=v1&SPINE=v1',
  ])('fails closed for %s', (search) => {
    expect(resolveSpine(search)).toBe('off');
  });
});

describe('parseToolCoordinate', () => {
  it('parses the coordinate and optional graph position from a href', () => {
    expect(parseToolCoordinate(
      '/author?toolId=loan&toolName=Loan%20Tool&stage=wire&graphDraftId=draft-7&graphRevision=3#node=foo',
    )).toEqual({
      toolId: 'loan',
      toolName: 'Loan Tool',
      stage: 'wire',
      graphDraftId: 'draft-7',
      graphRevision: 3,
    });
  });

  it('defaults the stage and display name when only toolId is present', () => {
    expect(parseToolCoordinate('/author?toolId=loan')).toEqual({
      toolId: 'loan',
      toolName: 'loan',
      stage: 'define',
    });
  });

  it.each(['/author?stage=wire', '/author?toolId=', '/author?toolId=%20'])
    ('returns null when the toolId is missing or blank: %s', (href) => {
      expect(parseToolCoordinate(href)).toBeNull();
    });

  it('rejects an unknown stage instead of creating an invalid coordinate', () => {
    expect(parseToolCoordinate('/author?toolId=loan&stage=inspect')).toBeNull();
  });
});

describe('toolCoordinateHref', () => {
  const coordinate: ToolCoordinate = {
    toolId: 'loan profile',
    toolName: 'Loan Profile',
    stage: 'publish',
    graphDraftId: 'draft 2',
    graphRevision: 4,
  };

  it('replaces coordinate keys while preserving unrelated query, path, and hash', () => {
    const href = toolCoordinateHref(
      '/author?spine=v1&toolId=old&toolName=Old&stage=wire&graphDraftId=old&graphRevision=1&other=x#node=selected',
      coordinate,
    );

    expect(href).toBe(
      '/author?spine=v1&toolId=loan+profile&toolName=Loan+Profile&stage=publish&graphDraftId=draft+2&graphRevision=4&other=x#node=selected',
    );
  });

  it('removes stale optional coordinate keys and is idempotent', () => {
    const href = toolCoordinateHref(
      '/wire?toolId=old&toolName=old&stage=wire&graphDraftId=old&graphRevision=5&keep=yes#h',
      { toolId: 'loan', toolName: 'Loan', stage: 'define' },
    );

    expect(href).toBe('/wire?toolId=loan&toolName=Loan&stage=define&keep=yes#h');
    expect(toolCoordinateHref(href, { toolId: 'loan', toolName: 'Loan', stage: 'define' })).toBe(href);
  });
});
