import { useRef } from 'react';

import useDialogFocusTrap from '../accessibility/useDialogFocusTrap';
import { useI18n } from '../../i18n/I18nProvider';

export type StartImportSection = 'menu' | 'examples' | 'library' | 'dsl';

export interface StartExample {
  key: string;
  label: string;
  domain: string;
  description: string;
  pattern: string;
  nodeCount: number;
  edgeCount: number;
  inputFieldCount: number;
  outputFieldCount: number;
  available: boolean;
  missingOperatorRefs: string[];
}

interface StartImportDialogProps {
  open: boolean;
  section: StartImportSection;
  examples: StartExample[];
  onSectionChange: (section: StartImportSection) => void;
  onLoadExample: (key: string) => void;
  onBlankGraph: () => void;
  onClose: () => void;
}

/** First-use and import chooser for Author Workspace v2. */
export default function StartImportDialog({
  open,
  section,
  examples,
  onSectionChange,
  onLoadExample,
  onBlankGraph,
  onClose,
}: StartImportDialogProps) {
  const { t } = useI18n();
  const dialogRef = useRef<HTMLElement>(null);
  useDialogFocusTrap({
    open,
    dialogRef,
    onDismiss: onClose,
    initialFocusKey: section,
  });

  if (!open) {
    return null;
  }

  return (
    <div className="author-start-backdrop" role="presentation" data-testid="author-start-backdrop">
      <section
        ref={dialogRef}
        className={`author-start-dialog section-${section}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby="author-start-title"
        tabIndex={-1}
        data-testid="author-start-dialog"
      >
        <header>
          <div>
            <span>{t('Author Workspace v2')}</span>
            <h2 id="author-start-title">
              {t(section === 'menu' ? 'Start authoring' : section === 'examples'
                ? 'Load a complete example'
                : section === 'library' ? 'Import operator library' : 'Import BLOGE DSL')}
            </h2>
          </div>
          <div>
            {section !== 'menu' && (
              <button
                type="button"
                className="secondary compact"
                onClick={() => onSectionChange('menu')}
              >
                {t('Back')}
              </button>
            )}
            <button
              type="button"
              className="secondary compact"
              aria-label={t('Close start dialog')}
              onClick={onClose}
            >
              {t('Close')}
            </button>
          </div>
        </header>
        {section === 'menu' && (
          <div className="author-start-options">
            <button
              type="button"
              data-dialog-initial-focus
              data-testid="author-start-choice:examples"
              onClick={() => onSectionChange('examples')}
            >
              <strong>{t('Load example')}</strong>
              <span>{t('Start with a complete graph, Contract, test data, and expected result.')}</span>
            </button>
            <button
              type="button"
              data-testid="author-start-choice:dsl"
              onClick={() => onSectionChange('dsl')}
            >
              <strong>{t('Import DSL')}</strong>
              <span>{t('Visualize an existing BLOGE flow with best-effort schema inference.')}</span>
            </button>
            <a
              href="/libraries/"
              data-testid="author-start-choice:library"
            >
              <strong>{t('Create operator library')}</strong>
              <span>{t('Use the guided Workbench, examples, preview, and governed commit.')}</span>
            </a>
            <button type="button" data-testid="author-start-choice:blank" onClick={onBlankGraph}>
              <strong>{t('Blank graph')}</strong>
              <span>{t('Open the operator palette and build from an empty canvas.')}</span>
            </button>
          </div>
        )}
        {section === 'examples' && (
          <div className="author-start-examples">
            {examples.map((example) => (
              <article key={example.key} data-available={example.available}>
                <div>
                  <span>{t(example.domain)}</span>
                  <strong>{t(example.label)}</strong>
                  <small>{t(example.pattern)}</small>
                  <p>{t(example.description)}</p>
                </div>
                <dl>
                  <div><dt>{t('Graph')}</dt><dd>{t('{nodes} nodes / {edges} edges', { nodes: example.nodeCount, edges: example.edgeCount })}</dd></div>
                  <div><dt>{t('Contract')}</dt><dd>{t('{inputs} in / {outputs} out', { inputs: example.inputFieldCount, outputs: example.outputFieldCount })}</dd></div>
                </dl>
                <button
                  type="button"
                  className="primary compact"
                  {...(example === examples[0] ? { 'data-dialog-initial-focus': true } : {})}
                  data-testid={`author-start-example:${example.key}`}
                  disabled={!example.available}
                  title={example.available
                    ? `${t('Load example')}: ${t(example.label)}`
                    : t('Missing {operators}', { operators: example.missingOperatorRefs.join(', ') })}
                  onClick={() => onLoadExample(example.key)}
                >
                  {example.available ? t('Load example') : t('{count} missing', { count: example.missingOperatorRefs.length })}
                </button>
              </article>
            ))}
          </div>
        )}
        {(section === 'library' || section === 'dsl') && (
          <p className="author-start-form-note">
            {t('Complete the import form below. The canvas stays unchanged until validation succeeds.')}
          </p>
        )}
      </section>
    </div>
  );
}
