import type { RemediationAction } from './remediationAction';
import { localizeRehearsalText } from '../i18n/generatedProductText';
import { useI18n } from '../i18n/I18nProvider';

export default function RemediationActionList({
  actions,
  onInvoke,
}: {
  actions: RemediationAction[];
  onInvoke: (action: RemediationAction) => void;
}) {
  const { t } = useI18n();
  if (actions.length === 0) {
    return null;
  }
  return (
    <section className="remediation-action-list" aria-label={t('Recommended next actions')}>
      <header>
        <span>{t('Next actions')}</span>
        <strong>{t('{count} paths to a trusted result', { count: actions.length })}</strong>
      </header>
      <div>
        {actions.map((action, index) => (
          <article key={action.id} data-severity={action.severity.toLowerCase()}>
            <div className="remediation-action-order">{index + 1}</div>
            <div className="remediation-action-copy">
              <strong>{t(action.actionLabel)}</strong>
              <p>{localizeRehearsalText(t, action.rootCause)}</p>
              <small><b>{t('Business impact')}</b> {t(action.businessImpact)}</small>
              <small>
                <b>{t('Responsible')}</b> {localizeRehearsalText(t, action.owner)} · {t(action.requiredRole)}
              </small>
              {!action.available && (
                <small className="remediation-action-unavailable">
                  {t(action.unavailableReason)}
                </small>
              )}
              <details>
                <summary>{t('Audit and technical details')}</summary>
                <span>{t(action.auditRequirement)}</span>
                {action.expiresAt && <span>{t('Expires {date}', { date: action.expiresAt })}</span>}
                <code>{action.technicalCode}</code>
                {action.technicalCoordinate && <code>{action.technicalCoordinate}</code>}
              </details>
            </div>
            {action.available && (action.navigation === 'EXTERNAL' || action.navigation === 'AUTHOR') && (
              <a
                className={index === 0 ? 'primary compact' : 'secondary compact'}
                href={action.deepLink}
              >
                {t(action.actionLabel)}
              </a>
            )}
            {action.available && action.navigation !== 'EXTERNAL' && action.navigation !== 'AUTHOR' && (
              <button
                type="button"
                className={index === 0 ? 'primary compact' : 'secondary compact'}
                onClick={() => onInvoke(action)}
              >
                {t(action.actionLabel)}
              </button>
            )}
          </article>
        ))}
      </div>
    </section>
  );
}
