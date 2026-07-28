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
  const counts = Object.fromEntries(
    SEVERITY_LABELS.map((severity) => [
      severity,
      items.filter((item) => item.severity === severity).length,
    ]),
  ) as Record<AuthorDiagnosticSeverity, number>;
  const summary = items.length === 0
    ? 'No diagnostics'
    : counts.BLOCKING > 0
      ? `${counts.BLOCKING} blocking`
      : counts.ERROR > 0
        ? `${counts.ERROR} errors`
        : counts.WARNING > 0
          ? `${counts.WARNING} warnings`
          : `${counts.INFO} info`;

  return (
    <section
      className={`author-diagnostics-drawer ${open ? 'open' : 'collapsed'}`}
      data-testid="author-diagnostics-drawer"
      aria-label="Author diagnostics"
    >
      <button
        type="button"
        className="author-diagnostics-toggle"
        aria-expanded={open}
        onClick={onToggle}
      >
        <span>Diagnostics</span>
        <strong>{summary}</strong>
        <span aria-hidden="true">{open ? 'v' : '^'}</span>
      </button>
      {open && (
        <div className="author-diagnostics-body">
          <div className="author-diagnostics-filters" aria-label="Diagnostic severity summary">
            {SEVERITY_LABELS.map((severity) => (
              <span key={severity} data-severity={severity.toLowerCase()}>
                {severity} {counts[severity]}
              </span>
            ))}
          </div>
          {items.length > 0 ? (
            <ol>
              {items.map((item) => (
                <li key={item.id} data-severity={item.severity.toLowerCase()}>
                  <button type="button" onClick={() => onSelect(item)}>
                    <span>{item.severity}</span>
                    <strong>{item.code}</strong>
                    <small>{item.scope} · {item.source}</small>
                    <p>{item.message}</p>
                    {item.coordinate && <code>{item.coordinate}</code>}
                    {item.recommendedAction && <em>{item.recommendedAction}</em>}
                  </button>
                </li>
              ))}
            </ol>
          ) : (
            <p className="muted">Run or validate the graph to create review diagnostics.</p>
          )}
        </div>
      )}
    </section>
  );
}
