import { useEffect } from 'react';

import AuthorCanvas from './AuthorCanvas';
import RehearsalWorkbench from './RehearsalWorkbench';
import Showcase from './Showcase';
import LibraryWorkbench from './library-authoring/LibraryWorkbench';
import I18nProvider, { useI18n } from './i18n/I18nProvider';
import LanguageSwitcher from './i18n/LanguageSwitcher';
import {
  authorWorkspaceEntryHref,
  resolveAuthorWorkspaceVersion,
} from './author/authorWorkspaceVersion';
import './styles.css';

type WorkspaceRoute = 'author' | 'libraries' | 'rehearsals' | 'showcase';

/** Top-level app shell shared by the authoring, rehearsal, and showcase routes. */
export default function App() {
  return (
    <I18nProvider>
      <AppShell />
    </I18nProvider>
  );
}

function AppShell() {
  const { t } = useI18n();
  const route: WorkspaceRoute = window.location.pathname.startsWith('/libraries')
    ? 'libraries'
    : window.location.pathname.startsWith('/showcase')
    ? 'showcase'
    : window.location.pathname.startsWith('/rehearsals')
      ? 'rehearsals'
      : 'author';
  const titleSource = route === 'libraries'
    ? 'Libraries'
    : route === 'rehearsals'
    ? 'Rehearsals'
    : route === 'showcase' ? 'Showcase' : 'Author';
  const title = t(titleSource);
  const authorWorkspaceVersion = resolveAuthorWorkspaceVersion(window.location.search);
  const authorHref = route === 'author'
    ? authorWorkspaceEntryHref(window.location.search, 'v2')
    : '/author/';
  const legacyAuthorHref = authorWorkspaceEntryHref(window.location.search, 'v1');
  const authorIsCurrent = route === 'author' && authorWorkspaceVersion === 'v2';
  const legacyAuthorIsCurrent = route === 'author' && authorWorkspaceVersion === 'v1';

  useEffect(() => {
    document.title = t('BLOGE Visual Canvas - {title}', { title });
  }, [t, title]);

  return (
    <div className={`app app-${route}`}>
      <header className="topbar">
        <div>
          <p className="eyebrow">BLOGE Visual Canvas</p>
          <h1>{title}</h1>
        </div>
        <div className="topbar-actions">
          <nav className="topbar-nav" aria-label={t('Workspace views')}>
            <a className={`topbar-link ${authorIsCurrent ? 'active' : ''}`} href={authorHref} aria-current={authorIsCurrent ? 'page' : undefined}>
              {t('Author')}
            </a>
            {route === 'author' && (
              <a
                className={`topbar-link ${legacyAuthorIsCurrent ? 'active' : ''}`}
                href={legacyAuthorHref}
                aria-current={legacyAuthorIsCurrent ? 'page' : undefined}
                title={t('Open the legacy Author workspace')}
              >
                {t('Legacy')}
              </a>
            )}
            <a
              className={`topbar-link ${route === 'libraries' ? 'active' : ''}`}
              href="/libraries/"
              aria-current={route === 'libraries' ? 'page' : undefined}
            >
              {t('Libraries')}
            </a>
            <a className={`topbar-link ${route === 'rehearsals' ? 'active' : ''}`} href="/rehearsals/" aria-current={route === 'rehearsals' ? 'page' : undefined}>
              {t('Rehearsals')}
            </a>
            <a className={`topbar-link ${route === 'showcase' ? 'active' : ''}`} href="/showcase/" aria-current={route === 'showcase' ? 'page' : undefined}>
              {t('Showcase')}
            </a>
          </nav>
          <LanguageSwitcher />
        </div>
      </header>
      {route === 'libraries'
        ? <LibraryWorkbench />
        : route === 'showcase'
        ? <Showcase />
        : route === 'rehearsals'
          ? <RehearsalWorkbench />
          : <AuthorCanvas workspaceVersion={authorWorkspaceVersion} />}
    </div>
  );
}
