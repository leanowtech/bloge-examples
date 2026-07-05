import { useEffect, useMemo, useState } from 'react';

import { fetchGatewayScenarios } from './api';
import type { GatewayExampleScenario } from './types';

function previewJson(value: unknown): string {
  try {
    return JSON.stringify(value ?? {}, null, 2);
  } catch {
    return '{}';
  }
}

function conceptList(scenario: GatewayExampleScenario): string[] {
  return scenario.concepts ?? [];
}

/** Read-only scenario browser for the resource-gateway examples catalog. */
export default function Showcase() {
  const [scenarios, setScenarios] = useState<GatewayExampleScenario[]>([]);
  const [selectedGraphName, setSelectedGraphName] = useState('');
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const [errorMessage, setErrorMessage] = useState('');

  useEffect(() => {
    let cancelled = false;
    setStatus('loading');
    fetchGatewayScenarios()
      .then((loadedScenarios) => {
        if (cancelled) {
          return;
        }
        setScenarios(loadedScenarios);
        setSelectedGraphName((current) =>
          loadedScenarios.some((scenario) => scenario.graphName === current)
            ? current
            : loadedScenarios[0]?.graphName ?? '',
        );
        setStatus('ready');
      })
      .catch((error: unknown) => {
        if (cancelled) {
          return;
        }
        setErrorMessage(error instanceof Error ? error.message : 'Unable to load scenarios.');
        setStatus('error');
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const selectedScenario = useMemo(
    () => scenarios.find((scenario) => scenario.graphName === selectedGraphName) ?? scenarios[0],
    [scenarios, selectedGraphName],
  );
  const presets = selectedScenario?.samplePresets ?? [];
  const decisionRows = selectedScenario?.decisionTable?.rows ?? [];
  const decisionColumns = selectedScenario?.decisionTable?.columns ?? [];

  return (
    <main className="showcase" data-testid="react-showcase">
      <aside className="showcase-sidebar" aria-label="Gateway scenarios">
        <div className="showcase-sidebar-heading">
          <span>Scenarios</span>
          <strong>{scenarios.length}</strong>
        </div>
        {status === 'loading' && scenarios.length === 0 ? (
          <p className="showcase-status">Loading catalog...</p>
        ) : null}
        {status === 'error' ? (
          <p className="showcase-status error">{errorMessage}</p>
        ) : null}
        <div className="showcase-list">
          {scenarios.map((scenario) => (
            <button
              key={scenario.graphName}
              type="button"
              className="showcase-scenario-button"
              data-testid={`showcase-scenario:${scenario.graphName}`}
              aria-current={scenario.graphName === selectedScenario?.graphName ? 'true' : undefined}
              onClick={() => setSelectedGraphName(scenario.graphName)}
            >
              <strong>{scenario.title}</strong>
              <span>{scenario.pattern}</span>
              <code>{scenario.graphName}</code>
            </button>
          ))}
        </div>
      </aside>

      {selectedScenario ? (
        <section className="showcase-detail" data-testid="showcase-detail">
          <div className="showcase-header">
            <div>
              <p className="eyebrow">{selectedScenario.pattern}</p>
              <h2>{selectedScenario.title}</h2>
              <p>{selectedScenario.description}</p>
            </div>
            <div className="showcase-actions">
              <a className="link" href={selectedScenario.diagramPath ?? '#'}>
                Diagram JSON
              </a>
              <a className="link" href="/examples/gateway">
                Legacy runner
              </a>
            </div>
          </div>

          <div className="showcase-tags" aria-label="Scenario concepts">
            {conceptList(selectedScenario).map((concept) => (
              <span key={concept} className="showcase-chip">
                {concept}
              </span>
            ))}
          </div>

          <div className="showcase-grid">
            <section className="showcase-panel">
              <h3>Run</h3>
              <dl className="showcase-run">
                <div>
                  <dt>Mode</dt>
                  <dd>{selectedScenario.run?.mode ?? 'request'}</dd>
                </div>
                <div>
                  <dt>Method</dt>
                  <dd>{selectedScenario.run?.method ?? 'GET'}</dd>
                </div>
                <div>
                  <dt>Path</dt>
                  <dd>
                    <code>{selectedScenario.run?.pathTemplate ?? '/'}</code>
                  </dd>
                </div>
                <div>
                  <dt>Presets</dt>
                  <dd>{presets.length}</dd>
                </div>
              </dl>
            </section>

            <section className="showcase-panel">
              <h3>Sample Input</h3>
              <pre className="showcase-sample" data-testid="showcase-sample">
                {previewJson(selectedScenario.sampleInput)}
              </pre>
            </section>
          </div>

          {selectedScenario.decisionTable ? (
            <section className="showcase-decision-table" data-testid="showcase-decision-table">
              <h3>Decision Table</h3>
              <div className="showcase-metrics">
                <span>
                  Hit policy <strong>{selectedScenario.decisionTable.hitPolicy ?? 'unknown'}</strong>
                </span>
                <span>
                  Rows <strong>{decisionRows.length}</strong>
                </span>
                <span>
                  Columns <strong>{decisionColumns.length}</strong>
                </span>
              </div>
            </section>
          ) : null}
        </section>
      ) : (
        <section className="showcase-empty" data-testid="showcase-detail" role="status">
          <h2>
            {status === 'loading'
              ? 'Loading catalog'
              : status === 'error'
                ? 'Catalog unavailable'
                : 'No scenarios available'}
          </h2>
          <p>
            {status === 'loading'
              ? 'Reading the resource-gateway scenario catalog.'
              : status === 'error'
                ? errorMessage
                : 'The backend catalog returned an empty list.'}
          </p>
        </section>
      )}
    </main>
  );
}
