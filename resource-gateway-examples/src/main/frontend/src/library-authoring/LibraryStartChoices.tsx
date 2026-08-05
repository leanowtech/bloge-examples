import { useState } from 'react';

import { useI18n } from '../i18n/I18nProvider';
import type { VisualLibraryAuthoringDocument } from '../types';
import ExistingAssetDiscovery from './ExistingAssetDiscovery';
import { LIBRARY_AUTHORING_EXAMPLES } from './examples';
import { createQuickLibraryDocument } from './model';
import {
  DEFAULT_SAMPLE_TEXT,
  type SampleInferenceLaunch,
} from './SampleInferenceReview';

export type LibraryStartChoice = 'quick' | 'samples' | 'discover' | 'advanced';

interface LibraryStartChoicesProps {
  initialChoice?: LibraryStartChoice;
  onStart: (
    document: VisualLibraryAuthoringDocument,
    source: string,
    inference?: SampleInferenceLaunch,
  ) => void;
}

export default function LibraryStartChoices({
  initialChoice = 'quick',
  onStart,
}: LibraryStartChoicesProps) {
  const { t } = useI18n();
  const [choice, setChoice] = useState<LibraryStartChoice>(initialChoice);
  const [libraryId, setLibraryId] = useState('team-operator-library');
  const [owner, setOwner] = useState('team-owner');
  const [sampleOperatorRef, setSampleOperatorRef] = useState('support:classify-ticket');
  const [sampleDirection, setSampleDirection] = useState<'INPUT' | 'OUTPUT'>('INPUT');
  const [samplePortName, setSamplePortName] = useState('request');
  const [sampleText, setSampleText] = useState(DEFAULT_SAMPLE_TEXT);
  const [advancedSource, setAdvancedSource] = useState('');
  const [advancedError, setAdvancedError] = useState('');

  const importAdvanced = () => {
    try {
      const candidate = JSON.parse(advancedSource) as VisualLibraryAuthoringDocument;
      if (candidate.schemaVersion !== 'bloge.visualLibraryAuthoring.v1'
          || !candidate.library?.id) {
        throw new Error('Expected bloge.visualLibraryAuthoring.v1 with library.id.');
      }
      setAdvancedError('');
      onStart(candidate, 'advanced-json');
    } catch (error) {
      setAdvancedError(error instanceof Error ? error.message : 'Invalid JSON source.');
    }
  };

  const startFromSamples = () => {
    const nextDocument = createQuickLibraryDocument(libraryId, owner);
    const defaultOperatorKey = Object.keys(nextDocument.operators ?? {})[0];
    const operatorKey = sampleOperatorRef.trim();
    const defaultOperator = nextDocument.operators?.[defaultOperatorKey];
    const operatorParts = operatorKey.split(':');
    const operatorName = operatorParts[operatorParts.length - 1];
    nextDocument.operators = {
      [operatorKey]: {
        ...defaultOperator,
        name: operatorName?.replace(/[-_.]+/g, ' ') || 'Inferred operator',
        description: 'Contract derived from representative business payloads.',
        input: {},
        output: {},
      },
    };
    onStart(nextDocument, 'samples', {
      operatorKey,
      direction: sampleDirection,
      portName: samplePortName.trim(),
      sampleText,
    });
  };

  return (
    <section className="library-start" data-testid="library-start">
      <header className="library-start-heading">
        <div>
          <p className="eyebrow">{t('Library Workbench')}</p>
          <h2>{t('Create an operator and function library')}</h2>
          <p>{t('Choose the closest starting point. You can inspect the generated canonical contract before commit.')}</p>
        </div>
        <a className="secondary compact" href="/author/">{t('Back to graph authoring')}</a>
      </header>

      <nav className="library-start-choices" aria-label={t('Library creation methods')}>
        <button
          type="button"
          className={choice === 'quick' ? 'active' : ''}
          aria-pressed={choice === 'quick'}
          onClick={() => setChoice('quick')}
          data-testid="library-start-choice:quick"
        >
          <strong>{t('Quick Create')}</strong>
          <span>{t('Start with a pure operator and structured fields.')}</span>
        </button>
        <button
          type="button"
          className={choice === 'samples' ? 'active' : ''}
          aria-pressed={choice === 'samples'}
          onClick={() => setChoice('samples')}
          data-testid="library-start-choice:samples"
        >
          <strong>{t('Infer from Samples')}</strong>
          <span>{t('Derive observed fields from representative JSON.')}</span>
        </button>
        <button
          type="button"
          className={choice === 'discover' ? 'active' : ''}
          aria-pressed={choice === 'discover'}
          onClick={() => setChoice('discover')}
          data-testid="library-start-choice:discover"
        >
          <strong>{t('Discover Existing Assets')}</strong>
          <span>{t('Start from DSL, API contracts, or runtime inventory.')}</span>
        </button>
        <button
          type="button"
          className={choice === 'advanced' ? 'active' : ''}
          aria-pressed={choice === 'advanced'}
          onClick={() => setChoice('advanced')}
          data-testid="library-start-choice:advanced"
        >
          <strong>{t('Advanced Import')}</strong>
          <span>{t('Open an existing progressive authoring JSON document.')}</span>
        </button>
      </nav>

      <section className="library-start-form" aria-live="polite">
        {choice === 'quick' && (
          <div className="library-quick-form">
            <label>
              <span>{t('Library id')}</span>
              <input
                value={libraryId}
                onChange={(event) => setLibraryId(event.target.value)}
                data-testid="library-quick-id"
              />
            </label>
            <label>
              <span>{t('Owner')}</span>
              <input
                value={owner}
                onChange={(event) => setOwner(event.target.value)}
                data-testid="library-quick-owner"
              />
            </label>
            <button
              type="button"
              className="primary"
              onClick={() => onStart(createQuickLibraryDocument(libraryId, owner), 'quick')}
              disabled={!libraryId.trim()}
              data-testid="library-quick-create"
            >
              {t('Create draft')}
            </button>
          </div>
        )}
        {choice === 'samples' && (
          <div className="library-samples-form" data-testid="library-samples-form">
            <div className="library-samples-fields">
              <label>
                <span>{t('Library id')}</span>
                <input value={libraryId} onChange={(event) => setLibraryId(event.target.value)} />
              </label>
              <label>
                <span>{t('Owner')}</span>
                <input value={owner} onChange={(event) => setOwner(event.target.value)} />
              </label>
              <label>
                <span>{t('Operator ref')}</span>
                <input
                  value={sampleOperatorRef}
                  onChange={(event) => setSampleOperatorRef(event.target.value)}
                  data-testid="library-samples-operator-ref"
                />
              </label>
              <label>
                <span>{t('Port name')}</span>
                <input
                  value={samplePortName}
                  onChange={(event) => setSamplePortName(event.target.value)}
                  data-testid="library-samples-port-name"
                />
              </label>
              <fieldset>
                <legend>{t('Port direction')}</legend>
                <div className="segmented-control">
                  {(['INPUT', 'OUTPUT'] as const).map((direction) => (
                    <button
                      key={direction}
                      type="button"
                      className={sampleDirection === direction ? 'active' : ''}
                      aria-pressed={sampleDirection === direction}
                      onClick={() => setSampleDirection(direction)}
                    >
                      {t(direction === 'INPUT' ? 'Input' : 'Output')}
                    </button>
                  ))}
                </div>
              </fieldset>
            </div>
            <label className="library-samples-source">
              <span>{t('Representative JSON')}</span>
              <textarea
                value={sampleText}
                onChange={(event) => setSampleText(event.target.value)}
                spellCheck={false}
                data-testid="library-samples-source"
              />
            </label>
            <button
              type="button"
              className="primary"
              onClick={startFromSamples}
              disabled={!libraryId.trim()
                || !sampleOperatorRef.trim()
                || !samplePortName.trim()
                || !sampleText.trim()}
              data-testid="library-samples-create"
            >
              {t('Create and analyze')}
            </button>
          </div>
        )}
        {choice === 'discover' && (
          <ExistingAssetDiscovery onStart={onStart} />
        )}
        {choice === 'advanced' && (
          <div className="library-advanced-import">
            <label>
              <span>{t('Authoring JSON')}</span>
              <textarea
                value={advancedSource}
                onChange={(event) => {
                  setAdvancedSource(event.target.value);
                  setAdvancedError('');
                }}
                spellCheck={false}
                placeholder='{"schemaVersion":"bloge.visualLibraryAuthoring.v1",...}'
                data-testid="library-advanced-source"
              />
            </label>
            {advancedError && <p className="library-inline-error">{advancedError}</p>}
            <button
              type="button"
              className="primary"
              onClick={importAdvanced}
              disabled={!advancedSource.trim()}
              data-testid="library-advanced-import"
            >
              {t('Open structured draft')}
            </button>
          </div>
        )}
      </section>

      <section className="library-start-examples" aria-label={t('Complete library examples')}>
        <header>
          <h3>{t('Complete examples')}</h3>
          <span>{t('Types, operators, functions, and test references included')}</span>
        </header>
        <div>
          {LIBRARY_AUTHORING_EXAMPLES.map((example) => (
            <article key={example.key}>
              <span>{example.domain} · {t('Design-only')}</span>
              <strong>{example.label}</strong>
              <p>{example.description}</p>
              <dl>
                <div>
                  <dt>{t('Operators')}</dt>
                  <dd>{Object.keys(example.document.operators ?? {}).length}</dd>
                </div>
                <div>
                  <dt>{t('Functions')}</dt>
                  <dd>{Object.keys(example.document.functions ?? {}).length}</dd>
                </div>
                <div>
                  <dt>{t('Types')}</dt>
                  <dd>{Object.keys(example.document.types ?? {}).length}</dd>
                </div>
              </dl>
              <button
                type="button"
                className="secondary compact"
                onClick={() => onStart(structuredClone(example.document), `example:${example.key}`)}
                data-testid={`library-start-example:${example.key}`}
              >
                {t('Open example')}
              </button>
            </article>
          ))}
        </div>
      </section>
    </section>
  );
}
