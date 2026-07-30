import { useRef } from 'react';

import useDialogFocusTrap from '../accessibility/useDialogFocusTrap';

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
            <span>Author Workspace v2</span>
            <h2 id="author-start-title">
              {section === 'menu' ? 'Start authoring' : section === 'examples'
                ? 'Load a complete example'
                : section === 'library' ? 'Import operator library' : 'Import BLOGE DSL'}
            </h2>
          </div>
          <div>
            {section !== 'menu' && (
              <button
                type="button"
                className="secondary compact"
                onClick={() => onSectionChange('menu')}
              >
                Back
              </button>
            )}
            <button
              type="button"
              className="secondary compact"
              aria-label="Close start dialog"
              onClick={onClose}
            >
              Close
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
              <strong>Load example</strong>
              <span>Start with a complete graph, Contract, test data, and expected result.</span>
            </button>
            <button
              type="button"
              data-testid="author-start-choice:dsl"
              onClick={() => onSectionChange('dsl')}
            >
              <strong>Import DSL</strong>
              <span>Visualize an existing BLOGE flow with best-effort schema inference.</span>
            </button>
            <a
              href="/libraries/"
              data-testid="author-start-choice:library"
            >
              <strong>Create operator library</strong>
              <span>Use the guided Workbench, examples, preview, and governed commit.</span>
            </a>
            <button type="button" data-testid="author-start-choice:blank" onClick={onBlankGraph}>
              <strong>Blank graph</strong>
              <span>Open the operator palette and build from an empty canvas.</span>
            </button>
          </div>
        )}
        {section === 'examples' && (
          <div className="author-start-examples">
            {examples.map((example) => (
              <article key={example.key} data-available={example.available}>
                <div>
                  <span>{example.domain}</span>
                  <strong>{example.label}</strong>
                  <small>{example.pattern}</small>
                  <p>{example.description}</p>
                </div>
                <dl>
                  <div><dt>Graph</dt><dd>{example.nodeCount} nodes / {example.edgeCount} edges</dd></div>
                  <div><dt>Contract</dt><dd>{example.inputFieldCount} in / {example.outputFieldCount} out</dd></div>
                </dl>
                <button
                  type="button"
                  className="primary compact"
                  {...(example === examples[0] ? { 'data-dialog-initial-focus': true } : {})}
                  data-testid={`author-start-example:${example.key}`}
                  disabled={!example.available}
                  title={example.available
                    ? `Load ${example.label}`
                    : `Missing ${example.missingOperatorRefs.join(', ')}`}
                  onClick={() => onLoadExample(example.key)}
                >
                  {example.available ? 'Load example' : `${example.missingOperatorRefs.length} missing`}
                </button>
              </article>
            ))}
          </div>
        )}
        {(section === 'library' || section === 'dsl') && (
          <p className="author-start-form-note">
            Complete the import form below. The canvas stays unchanged until validation succeeds.
          </p>
        )}
      </section>
    </div>
  );
}
