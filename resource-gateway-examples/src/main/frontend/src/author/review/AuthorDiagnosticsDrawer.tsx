import { useI18n } from '../../i18n/I18nProvider';
import type { AuthorDiagnosticItem, AuthorDiagnosticSeverity } from './authorDiagnostics';

interface AuthorDiagnosticsDrawerProps {
  open: boolean;
  items: AuthorDiagnosticItem[];
  onToggle: () => void;
  onSelect: (item: AuthorDiagnosticItem) => void;
}

const SEVERITY_LABELS: AuthorDiagnosticSeverity[] = ['BLOCKING', 'ERROR', 'WARNING', 'INFO'];

/** Scope-aware failure queue shared by validation, execution, assertions, and governance. */
export default function AuthorDiagnosticsDrawer({
  open,
  items,
  onToggle,
  onSelect,
}: AuthorDiagnosticsDrawerProps) {
  const { t } = useI18n();
  const counts = Object.fromEntries(
    SEVERITY_LABELS.map((severity) => [
      severity,
      items.filter((item) => item.severity === severity).length,
    ]),
  ) as Record<AuthorDiagnosticSeverity, number>;
  const summary = items.length === 0
    ? t('No diagnostics')
    : counts.BLOCKING > 0
      ? t('{count} blocking', { count: counts.BLOCKING })
      : counts.ERROR > 0
        ? t('{count} errors', { count: counts.ERROR })
        : counts.WARNING > 0
          ? t('{count} warnings', { count: counts.WARNING })
          : t('{count} info', { count: counts.INFO });

  return (
    <section
      className={`author-diagnostics-drawer ${open ? 'open' : 'collapsed'}`}
      data-testid="author-diagnostics-drawer"
      aria-label={t('Author diagnostics')}
    >
      <button
        type="button"
        className="author-diagnostics-toggle"
        aria-expanded={open}
        onClick={onToggle}
      >
        <span>{t('Diagnostics')}</span>
        <strong>{summary}</strong>
        <span aria-hidden="true">{open ? 'v' : '^'}</span>
      </button>
      {open && (
        <div className="author-diagnostics-body">
          <div className="author-diagnostics-filters" aria-label={t('Diagnostic severity summary')}>
            {SEVERITY_LABELS.map((severity) => (
              <span key={severity} data-severity={severity.toLowerCase()}>
                {t(severity)} {counts[severity]}
              </span>
            ))}
          </div>
          {items.length > 0 ? (
            <ol>
              {items.map((item) => (
                <li key={item.id} data-severity={item.severity.toLowerCase()}>
                  <button type="button" onClick={() => onSelect(item)}>
                    <span>{t(item.severity)}</span>
                    <strong>{item.code}</strong>
                    <small>{item.scope} · {item.source}</small>
                    <p>{item.message}</p>
                    {item.occurrenceCount > 1 && (
                      <small className="author-diagnostic-occurrences">
                        {t('{count} occurrences', { count: item.occurrenceCount })}
                        {item.coordinates.length > 1
                          ? t(' · {count} locations', { count: item.coordinates.length })
                          : ''}
                      </small>
                    )}
                    {item.coordinate && <code>{item.coordinate}</code>}
                    {item.recommendedAction && <em>{item.recommendedAction}</em>}
                  </button>
                </li>
              ))}
            </ol>
          ) : (
            <p className="muted">{t('Run or validate the graph to create review diagnostics.')}</p>
          )}
        </div>
      )}
    </section>
  );
}
