export const MOBILE_TASK_BREAKPOINT = 520;

export type ResponsiveTaskSurface = 'SCENARIO_MATRIX' | 'SCENARIO_CASE' | 'SCENARIO_COVERAGE';
export type ScenarioTaskIntent = 'RUNNER' | 'EDITOR';
export type ScenarioEditorStep = 'GIVEN' | 'DEPENDENCIES' | 'THEN' | 'REVIEW';

export type ResponsiveTaskRegion =
  | 'TASK_SWITCH'
  | 'CASE_PICKER'
  | 'MATRIX_FILTERS'
  | 'MATRIX_RESULTS'
  | 'MATRIX_RUN_BAR'
  | 'RUN_SUMMARY'
  | 'STEP_NAV'
  | 'GIVEN_EDITOR'
  | 'DEPENDENCY_EDITOR'
  | 'ASSERTION_EDITOR'
  | 'REVIEW_EDITOR'
  | 'COVERAGE_SUMMARY';

export interface ResponsiveTaskContext {
  viewportWidth: number;
  pointer: 'FINE' | 'COARSE';
  surface: ResponsiveTaskSurface;
  intent: ScenarioTaskIntent;
  activeStep: ScenarioEditorStep;
}

export interface ResponsiveTaskProjection {
  layout: 'DESKTOP' | 'MOBILE_TASK';
  taskId: 'MATRIX_RUN' | 'CASE_RUN' | 'CASE_EDIT' | 'COVERAGE_REVIEW' | 'DESKTOP_FULL';
  intent: ScenarioTaskIntent;
  activeStep: ScenarioEditorStep;
  regions: ResponsiveTaskRegion[];
  maxPrimaryActions: number;
  complexEditingSupported: boolean;
}

/** Keeps responsive policy independent from JSX and CSS visibility side effects. */
export function projectResponsiveTask(context: ResponsiveTaskContext): ResponsiveTaskProjection {
  if (context.viewportWidth > MOBILE_TASK_BREAKPOINT) {
    return {
      layout: 'DESKTOP',
      taskId: 'DESKTOP_FULL',
      intent: context.intent,
      activeStep: context.activeStep,
      regions: [
        'MATRIX_FILTERS', 'MATRIX_RESULTS', 'MATRIX_RUN_BAR', 'CASE_PICKER', 'STEP_NAV',
        'GIVEN_EDITOR', 'DEPENDENCY_EDITOR', 'ASSERTION_EDITOR', 'REVIEW_EDITOR',
        'COVERAGE_SUMMARY',
      ],
      maxPrimaryActions: 3,
      complexEditingSupported: true,
    };
  }

  if (context.surface === 'SCENARIO_MATRIX') {
    return {
      layout: 'MOBILE_TASK',
      taskId: 'MATRIX_RUN',
      intent: 'RUNNER',
      activeStep: context.activeStep,
      regions: ['TASK_SWITCH', 'MATRIX_FILTERS', 'MATRIX_RESULTS', 'MATRIX_RUN_BAR'],
      maxPrimaryActions: 1,
      complexEditingSupported: false,
    };
  }

  if (context.surface === 'SCENARIO_COVERAGE') {
    return {
      layout: 'MOBILE_TASK',
      taskId: 'COVERAGE_REVIEW',
      intent: 'RUNNER',
      activeStep: context.activeStep,
      regions: ['TASK_SWITCH', 'COVERAGE_SUMMARY'],
      maxPrimaryActions: 1,
      complexEditingSupported: false,
    };
  }

  if (context.intent === 'RUNNER') {
    return {
      layout: 'MOBILE_TASK',
      taskId: 'CASE_RUN',
      intent: context.intent,
      activeStep: context.activeStep,
      regions: ['TASK_SWITCH', 'CASE_PICKER', 'RUN_SUMMARY'],
      maxPrimaryActions: 1,
      complexEditingSupported: false,
    };
  }

  return {
    layout: 'MOBILE_TASK',
    taskId: 'CASE_EDIT',
    intent: context.intent,
    activeStep: context.activeStep,
    regions: ['TASK_SWITCH', 'CASE_PICKER', 'STEP_NAV', editorRegion(context.activeStep)],
    maxPrimaryActions: 1,
    complexEditingSupported: true,
  };
}

export function projectionIncludes(
  projection: ResponsiveTaskProjection,
  region: ResponsiveTaskRegion,
): boolean {
  return projection.regions.includes(region);
}

function editorRegion(step: ScenarioEditorStep): ResponsiveTaskRegion {
  switch (step) {
    case 'GIVEN': return 'GIVEN_EDITOR';
    case 'DEPENDENCIES': return 'DEPENDENCY_EDITOR';
    case 'THEN': return 'ASSERTION_EDITOR';
    case 'REVIEW': return 'REVIEW_EDITOR';
  }
}
