import { useEffect } from 'react';

import AuthorCanvas from './AuthorCanvas';
import RehearsalWorkbench from './RehearsalWorkbench';
import Showcase from './Showcase';
import {
  authorWorkspaceEntryHref,
  resolveAuthorWorkspaceVersion,
} from './author/authorWorkspaceVersion';
import './styles.css';

type WorkspaceRoute = 'author' | 'rehearsals' | 'showcase';

/** Top-level app shell shared by the authoring, rehearsal, and showcase routes. */
export default function App() {
  const route: WorkspaceRoute = window.location.pathname.startsWith('/showcase')
    ? 'showcase'
    : window.location.pathname.startsWith('/rehearsals')
      ? 'rehearsals'
      : 'author';
  const title = route === 'rehearsals'
    ? 'Rehearsals'
    : route === 'showcase' ? 'Showcase' : 'Author';
  const authorWorkspaceVersion = resolveAuthorWorkspaceVersion(window.location.search);
  const authorHref = route === 'author'
    ? authorWorkspaceEntryHref(window.location.search, 'v2')
    : '/author/';
  const legacyAuthorHref = authorWorkspaceEntryHref(window.location.search, 'v1');
  const authorIsCurrent = route === 'author' && authorWorkspaceVersion === 'v2';
  const legacyAuthorIsCurrent = route === 'author' && authorWorkspaceVersion === 'v1';

  useEffect(() => {
    document.title = `BLOGE Visual Canvas - ${title}`;
  }, [title]);

  return (
    <div className="app">
      <header className="topbar">
        <div>
          <p className="eyebrow">BLOGE Visual Canvas</p>
          <h1>{title}</h1>
        </div>
        <nav className="topbar-nav" aria-label="Workspace views">
          <a className={`topbar-link ${authorIsCurrent ? 'active' : ''}`} href={authorHref} aria-current={authorIsCurrent ? 'page' : undefined}>
            Author
          </a>
          {route === 'author' && (
            <a
              className={`topbar-link ${legacyAuthorIsCurrent ? 'active' : ''}`}
              href={legacyAuthorHref}
              aria-current={legacyAuthorIsCurrent ? 'page' : undefined}
              title="Open the legacy Author workspace"
            >
              Legacy
            </a>
          )}
          <a className={`topbar-link ${route === 'rehearsals' ? 'active' : ''}`} href="/rehearsals/" aria-current={route === 'rehearsals' ? 'page' : undefined}>
            Rehearsals
          </a>
          <a className={`topbar-link ${route === 'showcase' ? 'active' : ''}`} href="/showcase/" aria-current={route === 'showcase' ? 'page' : undefined}>
            Showcase
          </a>
        </nav>
      </header>
      {route === 'showcase'
        ? <Showcase />
        : route === 'rehearsals'
          ? <RehearsalWorkbench />
          : <AuthorCanvas workspaceVersion={authorWorkspaceVersion} />}
    </div>
  );
}
