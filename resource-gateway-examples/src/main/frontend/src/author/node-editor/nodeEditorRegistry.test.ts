import { describe, expect, it } from 'vitest';

import {
  BUILT_IN_NODE_EDITOR_KINDS,
  resolveNodeEditor,
} from './nodeEditorRegistry';

describe('nodeEditorRegistry', () => {
  it('defines one stable editor route for every built-in visual kind', () => {
    expect(BUILT_IN_NODE_EDITOR_KINDS).toEqual([
      'decision-table',
      'transform',
      'foreach',
      'resource',
      'http',
      'streaming',
      'design',
      'generic',
    ]);

    for (const visualKind of BUILT_IN_NODE_EDITOR_KINDS) {
      const definition = resolveNodeEditor(visualKind);
      expect(definition.visualKind).toBe(visualKind);
      expect(definition.tabs.map((tab) => tab.id)).toContain(definition.defaultTab);
      expect(new Set(definition.tabs.map((tab) => tab.id)).size).toBe(definition.tabs.length);
      expect(definition.primaryTask).not.toBe('');
    }
  });

  it('routes rule and mapping operators directly to their domain editor', () => {
    expect(resolveNodeEditor('decision-table')).toMatchObject({
      defaultTab: 'rules',
      primaryTask: 'Edit decision rules',
    });
    expect(resolveNodeEditor('transform')).toMatchObject({
      defaultTab: 'mapping',
      primaryTask: 'Map output fields',
    });
  });

  it('fails safely to the generic editor for an unknown catalog hint', () => {
    expect(resolveNodeEditor('future-operator')).toEqual(resolveNodeEditor('generic'));
    expect(resolveNodeEditor(undefined)).toEqual(resolveNodeEditor('generic'));
  });
});
