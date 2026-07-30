import { useState } from 'react';

import type { VisualLibraryAuthoringDocument } from '../types';
import { LIBRARY_AUTHORING_EXAMPLES } from './examples';
import { createQuickLibraryDocument } from './model';

interface LibraryStartChoicesProps {
  onStart: (document: VisualLibraryAuthoringDocument, source: string) => void;
}

type StartChoice = 'quick' | 'samples' | 'discover' | 'advanced';

export default function LibraryStartChoices({ onStart }: LibraryStartChoicesProps) {
  const [choice, setChoice] = useState<StartChoice>('quick');
  const [libraryId, setLibraryId] = useState('team-operator-library');
  const [owner, setOwner] = useState('team-owner');
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

  return (
    <main className="library-start" data-testid="library-start">
      <header className="library-start-heading">
        <div>
          <p className="eyebrow">Library Workbench</p>
          <h2>Create an operator and function library</h2>
          <p>Choose the closest starting point. You can inspect the generated canonical contract before commit.</p>
        </div>
        <a className="secondary compact" href="/author/">Back to graph authoring</a>
      </header>

      <nav className="library-start-choices" aria-label="Library creation methods">
        <button
          type="button"
          className={choice === 'quick' ? 'active' : ''}
          aria-pressed={choice === 'quick'}
          onClick={() => setChoice('quick')}
          data-testid="library-start-choice:quick"
        >
          <strong>Quick Create</strong>
          <span>Start with a pure operator and structured fields.</span>
        </button>
        <button
          type="button"
          className={choice === 'samples' ? 'active' : ''}
          aria-pressed={choice === 'samples'}
          onClick={() => setChoice('samples')}
          data-testid="library-start-choice:samples"
        >
          <strong>Infer from Samples</strong>
          <span>Derive observed fields from representative JSON.</span>
        </button>
        <button
          type="button"
          className={choice === 'discover' ? 'active' : ''}
          aria-pressed={choice === 'discover'}
          onClick={() => setChoice('discover')}
          data-testid="library-start-choice:discover"
        >
          <strong>Discover Existing Assets</strong>
          <span>Start from DSL, API contracts, or runtime inventory.</span>
        </button>
        <button
          type="button"
          className={choice === 'advanced' ? 'active' : ''}
          aria-pressed={choice === 'advanced'}
          onClick={() => setChoice('advanced')}
          data-testid="library-start-choice:advanced"
        >
          <strong>Advanced Import</strong>
          <span>Open an existing progressive authoring JSON document.</span>
        </button>
      </nav>

      <section className="library-start-form" aria-live="polite">
        {choice === 'quick' && (
          <div className="library-quick-form">
            <label>
              <span>Library id</span>
              <input
                value={libraryId}
                onChange={(event) => setLibraryId(event.target.value)}
                data-testid="library-quick-id"
              />
            </label>
            <label>
              <span>Owner</span>
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
              Create draft
            </button>
          </div>
        )}
        {choice === 'samples' && (
          <div className="library-stage-handoff" data-testid="library-samples-handoff">
            <strong>Sample inference is the next guided step.</strong>
            <p>Open a complete example now; inferred facts will remain reviewable before becoming declared schema.</p>
          </div>
        )}
        {choice === 'discover' && (
          <div className="library-stage-handoff" data-testid="library-discover-handoff">
            <strong>Existing DSL import is available in Author.</strong>
            <p>Render the topology there, then return here to enrich the reusable operator contracts.</p>
            <a className="primary compact" href="/author/">Open DSL import</a>
          </div>
        )}
        {choice === 'advanced' && (
          <div className="library-advanced-import">
            <label>
              <span>Authoring JSON</span>
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
              Open structured draft
            </button>
          </div>
        )}
      </section>

      <section className="library-start-examples" aria-label="Complete library examples">
        <header>
          <h3>Complete examples</h3>
          <span>Types, operators, functions, and test references included</span>
        </header>
        <div>
          {LIBRARY_AUTHORING_EXAMPLES.map((example) => (
            <article key={example.key}>
              <span>{example.domain}</span>
              <strong>{example.label}</strong>
              <p>{example.description}</p>
              <dl>
                <div>
                  <dt>Operators</dt>
                  <dd>{Object.keys(example.document.operators ?? {}).length}</dd>
                </div>
                <div>
                  <dt>Functions</dt>
                  <dd>{Object.keys(example.document.functions ?? {}).length}</dd>
                </div>
                <div>
                  <dt>Types</dt>
                  <dd>{Object.keys(example.document.types ?? {}).length}</dd>
                </div>
              </dl>
              <button
                type="button"
                className="secondary compact"
                onClick={() => onStart(structuredClone(example.document), `example:${example.key}`)}
                data-testid={`library-start-example:${example.key}`}
              >
                Open example
              </button>
            </article>
          ))}
        </div>
      </section>
    </main>
  );
}
