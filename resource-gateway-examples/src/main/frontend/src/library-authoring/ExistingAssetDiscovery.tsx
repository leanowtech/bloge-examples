import { type MouseEvent, useState } from 'react';

import {
  discoverLibraryAuthoringAssets,
  type LibraryAuthoringDiscoveryMode,
} from '../api';
import { stageDslAuthorHandoff } from '../author/dslAuthorHandoff';
import { useI18n } from '../i18n/I18nProvider';
import type {
  VisualAuthoringFactProjection,
  VisualLibraryAuthoringDocument,
} from '../types';
import { presentRuntimeParity } from './readinessPresentation';

interface ExistingAssetDiscoveryProps {
  onStart: (document: VisualLibraryAuthoringDocument, source: string) => void;
}

const SOURCE_LABELS: Record<LibraryAuthoringDiscoveryMode, string> = {
  runtime: 'Runtime',
  dsl: 'BLOGE DSL',
  'capability-catalog': 'Capability',
  asyncapi: 'AsyncAPI',
  openapi: 'OpenAPI',
};

const DEMO_SOURCES: Record<Exclude<LibraryAuthoringDiscoveryMode, 'runtime'>, string> = {
  dsl: `graph supportRouting {
  input {
    ticketId: String
    priority: String
  }
  node classify : "support:classify-ticket" {
    input {
      ticketId = ctx.ticketId
      priority = ctx.priority
    }
  }
  transform response {
    route = coalesce(classify.output.route, "general")
  }
}`,
  'capability-catalog': `{
  "schemaVersion": "bloge.capabilityCatalog.v1",
  "catalogId": "support-capabilities",
  "displayName": "Support Capabilities",
  "blogeVersion": "1.0.0",
  "functions": [
    {
      "name": "support.normalize",
      "namespace": "support",
      "description": "Normalizes customer text.",
      "signatures": [
        {
          "label": "support.normalize(value)",
          "parameters": [{"name": "value", "type": "string"}],
          "returns": {"type": "string"}
        }
      ]
    }
  ]
}`,
  asyncapi: `asyncapi: '2.6.0'
info:
  title: Support Events
  version: 1.0.0
channels:
  support.ticket.created:
    subscribe:
      operationId: receiveTicketCreated
      message:
        name: TicketCreated
        payload:
          type: object
          properties:
            ticketId: {type: string}
            priority: {type: string}
          required: [ticketId, priority]`,
  openapi: `openapi: 3.0.3
info:
  title: Support API
  version: 1.0.0
servers:
  - url: https://support.example.test
paths:
  /tickets/{ticketId}:
    get:
      operationId: getTicket
      parameters:
        - in: path
          name: ticketId
          required: true
          schema: {type: string}
      responses:
        '200':
          description: Ticket
          content:
            application/json:
              schema:
                type: object
                properties:
                  ticketId: {type: string}
                  status: {type: string}
                required: [ticketId, status]`,
};

export default function ExistingAssetDiscovery({ onStart }: ExistingAssetDiscoveryProps) {
  const { t, m } = useI18n();
  const [mode, setMode] = useState<LibraryAuthoringDiscoveryMode>('runtime');
  const [source, setSource] = useState('');
  const [projection, setProjection] = useState<VisualAuthoringFactProjection | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const chooseMode = (next: LibraryAuthoringDiscoveryMode) => {
    setMode(next);
    setSource(next === 'runtime' ? '' : DEMO_SOURCES[next]);
    setProjection(null);
    setError('');
  };

  const discover = async () => {
    setBusy(true);
    setError('');
    try {
      const request = discoveryRequest(mode, source);
      setProjection(await discoverLibraryAuthoringAssets(mode, request));
    } catch (failure) {
      setProjection(null);
      setError(t(failure instanceof Error ? failure.message : 'Discovery failed.'));
    } finally {
      setBusy(false);
    }
  };

  const stageDslHandoff = (event: MouseEvent<HTMLAnchorElement>) => {
    const result = stageDslAuthorHandoff(projection?.sourceId || 'inline.dsl', source);
    if (!result.accepted) {
      event.preventDefault();
      setError(result.message);
    }
  };

  const summary = projection?.summary;
  return (
    <div className="library-discovery" data-testid="library-discovery">
      <div className="library-discovery-source-tabs" role="tablist" aria-label={t('Discovery source')}>
        {(Object.keys(SOURCE_LABELS) as LibraryAuthoringDiscoveryMode[]).map((candidate) => (
          <button
            key={candidate}
            type="button"
            role="tab"
            aria-selected={mode === candidate}
            className={mode === candidate ? 'active' : ''}
            onClick={() => chooseMode(candidate)}
            data-testid={`library-discovery-mode:${candidate}`}
          >
            {t(SOURCE_LABELS[candidate])}
          </button>
        ))}
      </div>

      <div className="library-discovery-input">
        {mode === 'runtime' ? (
          <div className="library-discovery-runtime">
            <strong>{t('Process-local inventory')}</strong>
            <span>{t('Operators and expression functions visible to this Resource Gateway instance')}</span>
          </div>
        ) : (
          <label>
            <span>{t('{source} source', { source: t(SOURCE_LABELS[mode]) })}</span>
            <textarea
              value={source}
              onChange={(event) => setSource(event.target.value)}
              spellCheck={false}
              data-testid="library-discovery-source"
            />
          </label>
        )}
        <button
          type="button"
          className="primary"
          onClick={() => void discover()}
          disabled={busy || (mode !== 'runtime' && !source.trim())}
          data-testid="library-discovery-run"
        >
          {busy ? t('Scanning...') : t('Scan source')}
        </button>
      </div>

      {error && <p className="library-inline-error" role="alert">{error}</p>}

      {projection && summary && (
        <section className="library-discovery-result" data-testid="library-discovery-result">
          <header>
            <div>
              <span>{t(projection.sourceKind.replace(/_/g, ' '))}</span>
              <strong>{projection.sourceId || t('Unnamed source')}</strong>
            </div>
            <span
              className="library-discovery-status"
              data-state={summary.runtimeReady ? 'ready' : projection.accepted ? 'review' : 'blocked'}
            >
              {summary.runtimeReady ? t('Runtime ready') : projection.accepted ? t('Review required') : t('Blocked')}
            </span>
          </header>

          <dl className="library-discovery-summary">
            <div><dt>{t('Operators')}</dt><dd>{summary.operatorFactCount}</dd></div>
            <div><dt>{t('Functions')}</dt><dd>{summary.functionFactCount}</dd></div>
            <div><dt>{t('Graphs')}</dt><dd>{summary.graphFactCount}</dd></div>
            <div><dt>{t('Bound')}</dt><dd>{summary.boundCount}</dd></div>
            <div><dt>{t('Drifted')}</dt><dd>{summary.driftedCount}</dd></div>
            <div><dt>{t('Unresolved')}</dt><dd>{summary.unresolvedCount}</dd></div>
          </dl>

          <section className="library-discovery-facts">
            <header><h3>{t('Discovered assets')}</h3><span>{projection.facts.length}</span></header>
            <div className="library-discovery-fact-list">
              {projection.facts.slice(0, 12).map((fact) => (
                <div key={fact.factId}>
                  <span>{t(fact.assetKind)}</span>
                  <strong>{fact.assetRef}</strong>
                  <small>{t(fact.evidenceLevel)} / {t(fact.factKind)}</small>
                </div>
              ))}
            </div>
            {projection.facts.length > 12 && (
              <p>{t('{count} additional assets are retained in the projection.', { count: projection.facts.length - 12 })}</p>
            )}
          </section>

          {projection.runtimeParity.length > 0 && (
            <section className="library-discovery-parity">
              <header><h3>{t('Runtime parity')}</h3><span>{projection.runtimeParity.length}</span></header>
              <div className="library-discovery-table-wrap">
                <table>
                  <thead>
                    <tr><th>{t('Asset')}</th><th>{t('State')}</th><th>{t('Runtime')}</th></tr>
                  </thead>
                  <tbody>
                    {projection.runtimeParity.map((parity, index) => {
                      const presentation = presentRuntimeParity(parity);
                      return (
                        <tr key={`${parity.assetKind}:${parity.assetRef}:${parity.runtimeProfile}:${index}`}>
                          <td><span>{t(parity.assetKind)}</span><strong>{parity.assetRef}</strong></td>
                          <td data-state={parity.state}>
                            {m(presentation.state.messageId, presentation.state.params)}
                          </td>
                          <td>
                            {parity.runtimeProfile
                              || m(presentation.detail.messageId, presentation.detail.params)}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </section>
          )}

          {projection.reviewItems.length > 0 && (
            <section className="library-discovery-review">
              <header><h3>{t('Review queue')}</h3><span>{projection.reviewItems.length}</span></header>
              <ol>
                {projection.reviewItems.slice(0, 8).map((item, index) => (
                  <li key={`${item.code}:${item.assetRef}:${index}`} data-level={item.level}>
                    <strong>{item.assetRef || item.assetKind}</strong>
                    <p>{t(item.message)}</p>
                    <small>{t(item.action)}</small>
                  </li>
                ))}
              </ol>
            </section>
          )}

          <footer>
            {projection.authoringDocument && (
              <button
                type="button"
                className="primary"
                onClick={() => onStart(
                  structuredClone(projection.authoringDocument as VisualLibraryAuthoringDocument),
                  `discovery:${mode}`,
                )}
                data-testid="library-discovery-open-draft"
              >
                {t('Open structured draft')}
              </button>
            )}
            {mode === 'dsl' && (
              <a
                className="secondary compact"
                href="/author/"
                onClick={stageDslHandoff}
                data-testid="library-discovery-open-author"
              >
                {t('Open Graph Author')}
              </a>
            )}
            <code title={projection.projectionFingerprint}>
              {projection.projectionFingerprint.slice(0, 20)}
            </code>
          </footer>
        </section>
      )}
    </div>
  );
}

function discoveryRequest(
  mode: LibraryAuthoringDiscoveryMode,
  source: string,
): Record<string, unknown> {
  switch (mode) {
    case 'runtime':
      return {};
    case 'dsl':
      return {
        sourceId: 'support-routing.bloge',
        dsl: source,
        operatorLibraryIds: [],
        inlineLibraries: [],
        mode: 'preview',
        layout: {},
      };
    case 'capability-catalog':
      return {
        sourceId: 'support-capabilities.json',
        catalog: JSON.parse(source) as Record<string, unknown>,
      };
    case 'asyncapi':
      return {
        libraryId: 'support-events',
        displayName: 'Support Events',
        version: '1.0.0',
        owner: 'support-platform',
        status: 'ACTIVE',
        asyncApiText: source,
      };
    case 'openapi':
      return {
        resourceId: 'support.getTicket',
        operationId: 'getTicket',
        status: 'ACTIVE',
        openApiText: source,
      };
  }
}
