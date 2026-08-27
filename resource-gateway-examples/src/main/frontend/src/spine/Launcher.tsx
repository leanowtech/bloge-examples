import { useState } from 'react';

import { useI18n } from '../i18n/I18nProvider';
import { toolCoordinateHref } from './authorSpine';
import './authorSpine.css';

const INTENTS = [
  {
    id: 'build-tool',
    title: 'Build a tool',
    description: 'Start a named tool from an empty authoring thread.',
  },
  {
    id: 'import-dsl-api',
    title: 'Import DSL or API',
    description: 'Bring an existing flow or API into the authoring workspace.',
  },
  {
    id: 'author-library',
    title: 'Author a library',
    description: 'Create reusable operators in the Library Workbench.',
  },
  {
    id: 'review-evidence',
    title: 'Review evidence',
    description: 'Inspect correctness evidence for a graph or tool.',
  },
  {
    id: 'run-examples',
    title: 'Run examples',
    description: 'Explore executable Gateway examples and outputs.',
  },
] as const;

const WORKSPACES = [
  ['capabilities', 'Capability Studio'],
  ['business-mirror', 'Business Mirror'],
  ['author', 'Author'],
  ['correctness', 'Correctness'],
  ['libraries', 'Libraries'],
  ['rehearsals', 'Rehearsals'],
  ['showcase', 'Run examples'],
] as const;

/** The spine's first screen: five explicit, executable authoring entry points. */
export default function Launcher() {
  const { t } = useI18n();
  const [toolName, setToolName] = useState('');
  const normalizedToolName = toolName.trim();
  const buildHref = normalizedToolName
    ? toolCoordinateHref('/author/?spine=v1', {
      toolId: `tool-${slugify(normalizedToolName)}`,
      toolName: normalizedToolName,
      stage: 'define',
    })
    : null;

  return (
    <main className="spine-launcher" data-testid="tool-spine-launcher">
      <header className="spine-launcher-header">
        <p className="eyebrow">BLOGE Visual Canvas</p>
        <h1>{t('Tool authoring spine')}</h1>
        <p>{t('Choose an executable starting point for your next tool.')}</p>
      </header>
      <details className="spine-all-workspaces">
        <summary>{t('All workspaces')}</summary>
        <nav data-testid="spine-all-workspaces" aria-label={t('All workspaces')}>
          {WORKSPACES.map(([route, label]) => (
            <a key={route} href={`/${route}/?spine=v1`}>{t(label)}</a>
          ))}
        </nav>
      </details>
      <section className="spine-intent-grid" aria-label={t('Tool authoring intents')}>
        {INTENTS.map((intent) => (
          <article
            key={intent.id}
            className="spine-intent-card"
            data-testid="spine-intent-card"
            data-intent={intent.id}
          >
            <h2>{t(intent.title)}</h2>
            <p>{t(intent.description)}</p>
            {intent.id === 'build-tool' ? (
              <div className="spine-build-form">
                <label htmlFor="spine-build-tool-name">{t('Tool name')}</label>
                <input
                  id="spine-build-tool-name"
                  data-testid="spine-build-tool-name"
                  value={toolName}
                  onChange={(event) => setToolName(event.target.value)}
                  placeholder={t('e.g. Loan Profile')}
                  autoComplete="off"
                />
                <a
                  className="spine-intent-link"
                  data-testid="spine-build-tool-link"
                  href={buildHref ?? undefined}
                  aria-disabled={buildHref ? 'false' : 'true'}
                  onClick={(event) => {
                    if (!buildHref) event.preventDefault();
                  }}
                >
                  {t('Create tool')}
                </a>
              </div>
            ) : (
              <a
                className="spine-intent-link"
                data-testid={`spine-intent-link-${intent.id}`}
                href={intentHref(intent.id)}
              >
                {t('Open workspace')}
              </a>
            )}
          </article>
        ))}
      </section>
    </main>
  );
}

function intentHref(intent: Exclude<(typeof INTENTS)[number]['id'], 'build-tool'>): string {
  const route = intent === 'author-library'
    ? '/libraries/'
    : intent === 'review-evidence'
      ? '/correctness/'
      : intent === 'run-examples'
        ? '/showcase/'
        : '/author/';
  return `${route}?spine=v1&intent=${intent}`;
}

function slugify(value: string): string {
  const normalized = value.normalize('NFKC').toLowerCase();
  const slug = normalized
    .replace(/[^\p{Letter}\p{Number}]+/gu, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 80);
  if (slug) return slug;

  let hash = 2_166_136_261;
  for (const character of normalized) {
    hash ^= character.codePointAt(0) ?? 0;
    hash = Math.imul(hash, 16_777_619);
  }
  return `name-${(hash >>> 0).toString(36)}`;
}
