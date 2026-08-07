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

    expect(projection).toMatchObject({ taskId: 'MATRIX_RUN', intent: 'RUNNER' });
    expect(projection.regions).toEqual([
      'TASK_SWITCH', 'MATRIX_FILTERS', 'MATRIX_RESULTS', 'MATRIX_RUN_BAR',
    ]);
  });

  it('retains the full editing surface above the mobile task breakpoint', () => {
    const projection = projectResponsiveTask({ ...base, viewportWidth: 840 });

    expect(projection.layout).toBe('DESKTOP');
    expect(projection.complexEditingSupported).toBe(true);
    expect(projectionIncludes(projection, 'GIVEN_EDITOR')).toBe(true);
    expect(projectionIncludes(projection, 'REVIEW_EDITOR')).toBe(true);
  });

  it('projects Library assets into review or bounded metadata editing', () => {
    expect(projectLibraryResponsiveTask({
      viewportWidth: 390,
      pointer: 'COARSE',
      intent: 'REVIEW',
      assetKind: 'operator',
    })).toMatchObject({
      taskId: 'LIBRARY_REVIEW',
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
