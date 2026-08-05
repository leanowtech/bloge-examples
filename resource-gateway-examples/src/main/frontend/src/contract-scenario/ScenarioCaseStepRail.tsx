import { useI18n } from '../i18n/I18nProvider';

export interface ScenarioCaseStepRailProps {
  anchorPrefix: string;
  givenCount: number;
  dependencyCount: number;
  assertionCount: number;
  reviewState: 'NOT_RUN' | 'RUNNING' | 'PASSED' | 'FAILED' | 'BLOCKED';
}

/** Shared four-step task navigation used by Graph, Operator, and Function Case editors. */
export default function ScenarioCaseStepRail({
  anchorPrefix,
  givenCount,
  dependencyCount,
  assertionCount,
  reviewState,
}: ScenarioCaseStepRailProps) {
  const { t } = useI18n();
  const steps = [
    { id: 'given', label: 'Given', detail: t('{count} input fields', { count: givenCount }), ready: givenCount > 0 },
    { id: 'dependencies', label: 'Dependencies', detail: t('{count} controlled', { count: dependencyCount }), ready: true },
    { id: 'then', label: 'Then', detail: assertionCount > 0 ? t('{count} checks', { count: assertionCount }) : t('Needs oracle'), ready: assertionCount > 0 },
    { id: 'review', label: 'Review & run', detail: t(reviewStateLabel(reviewState)), ready: reviewState === 'PASSED' },
  ];
  return (
    <nav className="scenario-case-step-rail" aria-label={t('Case workflow')}>
      {steps.map((step, index) => (
        <a href={`#${anchorPrefix}-${step.id}`} data-ready={step.ready} key={step.id}>
          <b>{index + 1}</b>
          <span><strong>{t(step.label)}</strong><small>{step.detail}</small></span>
        </a>
      ))}
    </nav>
  );
}

function reviewStateLabel(value: ScenarioCaseStepRailProps['reviewState']): string {
  switch (value) {
    case 'NOT_RUN': return 'Not run';
    case 'RUNNING': return 'Running';
    case 'PASSED': return 'Checks passed';
    case 'FAILED': return 'Needs review';
    case 'BLOCKED': return 'Blocked';
  }
}
