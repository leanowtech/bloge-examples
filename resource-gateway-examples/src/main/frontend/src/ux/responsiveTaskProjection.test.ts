import { describe, expect, it } from 'vitest';

import {
  projectLibraryResponsiveTask,
  projectResponsiveTask,
  projectionIncludes,
  type ResponsiveTaskContext,
} from './responsiveTaskProjection';

const base: ResponsiveTaskContext = {
  viewportWidth: 390,
  pointer: 'COARSE',
  surface: 'SCENARIO_CASE',
  intent: 'RUNNER',
  activeStep: 'GIVEN',
};

describe('responsiveTaskProjection', () => {
  it('projects a compact Case runner without mounting the complex editors', () => {
    const projection = projectResponsiveTask(base);

    expect(projection).toMatchObject({
      layout: 'MOBILE_TASK',
      taskId: 'CASE_RUN',
      maxPrimaryActions: 1,
      complexEditingSupported: false,
    });
    expect(projection.regions).toEqual(['TASK_SWITCH', 'CASE_PICKER', 'RUN_SUMMARY']);
  });

  it('renders exactly one editor step in the mobile tab scope', () => {
    const projection = projectResponsiveTask({
      ...base,
      intent: 'EDITOR',
      activeStep: 'DEPENDENCIES',
    });

    expect(projection.taskId).toBe('CASE_EDIT');
    expect(projectionIncludes(projection, 'DEPENDENCY_EDITOR')).toBe(true);
    expect(projectionIncludes(projection, 'GIVEN_EDITOR')).toBe(false);
    expect(projectionIncludes(projection, 'ASSERTION_EDITOR')).toBe(false);
    expect(projectionIncludes(projection, 'REVIEW_EDITOR')).toBe(false);
  });

  it('keeps Matrix mobile projection bound to collection run instead of hidden Case edit', () => {
    const projection = projectResponsiveTask({
      ...base,
      surface: 'SCENARIO_MATRIX',
      intent: 'EDITOR',
    });

    expect(projection).toMatchObject({
      taskId: 'MATRIX_RUN',
      intent: 'RUNNER',
      continuityKey: 'SCENARIO_MATRIX',
      resultProjection: 'MOBILE_SUMMARY',
      focusStrategy: 'CASE_COORDINATE',
    });
    expect(projection.regions).toEqual([
      'TASK_SWITCH', 'MATRIX_FILTERS', 'MATRIX_RESULTS', 'MATRIX_RUN_BAR',
    ]);
  });

  it('retains the full editing surface above the compact task breakpoint', () => {
    const projection = projectResponsiveTask({ ...base, viewportWidth: 841 });

    expect(projection.layout).toBe('DESKTOP');
    expect(projection.complexEditingSupported).toBe(true);
    expect(projectionIncludes(projection, 'GIVEN_EDITOR')).toBe(true);
    expect(projectionIncludes(projection, 'REVIEW_EDITOR')).toBe(true);
  });

  it('keeps the Matrix continuity coordinate stable across 390 and 820 compact layouts', () => {
    const mobile = projectResponsiveTask({ ...base, surface: 'SCENARIO_MATRIX', viewportWidth: 390 });
    const tablet = projectResponsiveTask({ ...base, surface: 'SCENARIO_MATRIX', viewportWidth: 820 });

    expect(mobile.continuityKey).toBe(tablet.continuityKey);
    expect(mobile.focusStrategy).toBe(tablet.focusStrategy);
    expect(mobile.resultProjection).toBe('MOBILE_SUMMARY');
    expect(tablet.resultProjection).toBe('MOBILE_SUMMARY');
  });

  it('switches to the canonical desktop Matrix only once the shell can hold it', () => {
    const projection = projectResponsiveTask({
      ...base,
      surface: 'SCENARIO_MATRIX',
      viewportWidth: 1024,
    });

    expect(projection).toMatchObject({
      layout: 'DESKTOP',
      resultProjection: 'CANONICAL_TABLE',
      continuityKey: 'SCENARIO_MATRIX',
    });
  });

  it('projects Library assets into review or bounded metadata editing', () => {
    expect(projectLibraryResponsiveTask({
      viewportWidth: 390,
      pointer: 'COARSE',
      intent: 'REVIEW',
      assetKind: 'operator',
    })).toMatchObject({
      taskId: 'LIBRARY_REVIEW',
      continuityKey: 'LIBRARY_ASSET',
      focusStrategy: 'ASSET_COORDINATE',
      maxPrimaryActions: 1,
      lightEditingSupported: true,
      complexEditingSupported: false,
    });

    expect(projectLibraryResponsiveTask({
      viewportWidth: 390,
      pointer: 'COARSE',
      intent: 'LIGHT_EDIT',
      assetKind: 'operator',
    })).toMatchObject({ taskId: 'LIBRARY_LIGHT_EDIT', lightEditingSupported: true });
  });

  it('hands complex named-type editing to an exact desktop task', () => {
    const projection = projectLibraryResponsiveTask({
      viewportWidth: 390,
      pointer: 'COARSE',
      intent: 'LIGHT_EDIT',
      assetKind: 'type',
    });

    expect(projection.taskId).toBe('LIBRARY_COMPLEX_HANDOFF');
    expect(projection.lightEditingSupported).toBe(false);
    expect(projection.regions).not.toContain('LIGHT_EDITOR');
    expect(projection.regions).toContain('DESKTOP_HANDOFF');
  });
});
