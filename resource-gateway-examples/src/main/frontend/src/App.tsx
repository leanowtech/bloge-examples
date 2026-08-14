import { lazy, Suspense, useEffect, useState } from 'react';
import { LoaderCircle, Menu, X } from 'lucide-react';

import I18nProvider, { useI18n } from './i18n/I18nProvider';
import LanguageSwitcher from './i18n/LanguageSwitcher';
import DensityProvider from './ux/DensityProvider';
import DensitySwitcher from './ux/DensitySwitcher';
import { SafeWorkspaceNavigationProvider } from './author/continuity/SafeWorkspaceNavigation';
import {
  authorWorkspaceEntryHref,
  resolveAuthorWorkspaceVersion,
} from './author/authorWorkspaceVersion';
import { signalHostWorkspaceReady } from './host/hostLifecycle';
import './styles/tokens.css';
import './styles.css';
import './styles/responsive.css';

type WorkspaceRoute = 'business-mirror' | 'author' | 'libraries' | 'rehearsals' | 'showcase';

const loadBusinessMirrorWorkspace = () => import('./business-mirror/BusinessMirrorWorkspace');
const loadAuthorCanvas = () => import('./AuthorCanvas');
const loadLibraryWorkbench = () => import('./library-authoring/LibraryWorkbench');
const loadRehearsalWorkbench = () => import('./RehearsalWorkbench');
const loadShowcase = () => import('./Showcase');

const BusinessMirrorWorkspace = lazy(loadBusinessMirrorWorkspace);
const AuthorCanvas = lazy(loadAuthorCanvas);
const LibraryWorkbench = lazy(loadLibraryWorkbench);
const RehearsalWorkbench = lazy(loadRehearsalWorkbench);
const Showcase = lazy(loadShowcase);

const ROUTE_PREFETCH: Record<WorkspaceRoute, () => Promise<unknown>> = {
  'business-mirror': loadBusinessMirrorWorkspace,
  author: loadAuthorCanvas,
  libraries: loadLibraryWorkbench,
  rehearsals: loadRehearsalWorkbench,
  showcase: loadShowcase,
};

/** Top-level app shell shared by the authoring, rehearsal, and showcase routes. */
export default function App() {
  return (
    <I18nProvider>
      <DensityProvider>
        <SafeWorkspaceNavigationProvider>
          <AppShell />
        </SafeWorkspaceNavigationProvider>
      </DensityProvider>
    </I18nProvider>
  );
}

function AppShell() {
  const { t } = useI18n();
  const [navigationOpen, setNavigationOpen] = useState(false);
  const vscodeHost = typeof globalThis.acquireVsCodeApi === 'function';
  const route = resolveWorkspaceRoute(window.location.pathname, window.location.search, vscodeHost);
  const titleSource = route === 'business-mirror'
    ? 'Business Mirror'
    : route === 'libraries'
    ? 'Libraries'
    : route === 'rehearsals'
    ? 'Rehearsals'
    : route === 'showcase' ? 'Run examples' : 'Author';
  const title = t(titleSource);
  const authorWorkspaceVersion = resolveAuthorWorkspaceVersion(window.location.search);
  const authorHref = !vscodeHost && route !== 'author'
    ? '/author/'
    : workspaceEntryHref('author', window.location.search, vscodeHost, 'v2');
  const legacyAuthorHref = workspaceEntryHref('author', window.location.search, vscodeHost, 'v1');
  const authorIsCurrent = route === 'author' && authorWorkspaceVersion === 'v2';
  const legacyAuthorIsCurrent = route === 'author' && authorWorkspaceVersion === 'v1';
  const prefetch = (target: WorkspaceRoute) => () => {
    if (target !== route) void ROUTE_PREFETCH[target]();
  };

  useEffect(() => {
    document.title = t('BLOGE Visual Canvas - {title}', { title });
  }, [t, title]);

  return (
    <div className={`app app-${route}`}>
      <header className="topbar">
        <div className="topbar-brand">
          <p className="eyebrow">BLOGE Visual Canvas</p>
          <h1>{title}</h1>
        </div>
        <button
          type="button"
          className="topbar-nav-toggle"
          aria-controls="workspace-navigation"
          aria-expanded={navigationOpen}
          aria-label={navigationOpen ? t('Close workspace navigation') : t('Open workspace navigation')}
          title={navigationOpen ? t('Close workspace navigation') : t('Open workspace navigation')}
          onClick={() => setNavigationOpen((open) => !open)}
        >
          {navigationOpen
            ? <X aria-hidden="true" size={20} />
            : <Menu aria-hidden="true" size={20} />}
        </button>
        <div className="topbar-actions">
          <nav
            id="workspace-navigation"
            className="topbar-nav"
            aria-label={t('Workspace views')}
            data-open={navigationOpen}
          >
            <a
              className={`topbar-link ${route === 'business-mirror' ? 'active' : ''}`}
              href={workspaceEntryHref('business-mirror', window.location.search, vscodeHost)}
              aria-current={route === 'business-mirror' ? 'page' : undefined}
              onPointerEnter={prefetch('business-mirror')}
              onFocus={prefetch('business-mirror')}
            >
              {t('Business Mirror')}
            </a>
            <a
              className={`topbar-link ${authorIsCurrent ? 'active' : ''}`}
              href={authorHref}
              aria-current={authorIsCurrent ? 'page' : undefined}
              onPointerEnter={prefetch('author')}
              onFocus={prefetch('author')}
            >
              {t('Author')}
            </a>
            {route === 'author' && (
              <a
                className={`topbar-link ${legacyAuthorIsCurrent ? 'active' : ''}`}
                href={legacyAuthorHref}
                aria-current={legacyAuthorIsCurrent ? 'page' : undefined}
                title={t('Open the legacy Author workspace')}
                onPointerEnter={prefetch('author')}
                onFocus={prefetch('author')}
              >
                {t('Legacy')}
              </a>
            )}
            <a
              className={`topbar-link ${route === 'libraries' ? 'active' : ''}`}
              href={workspaceEntryHref('libraries', window.location.search, vscodeHost)}
              aria-current={route === 'libraries' ? 'page' : undefined}
              onPointerEnter={prefetch('libraries')}
              onFocus={prefetch('libraries')}
            >
              {t('Libraries')}
            </a>
            <a
              className={`topbar-link ${route === 'rehearsals' ? 'active' : ''}`}
              href={workspaceEntryHref('rehearsals', window.location.search, vscodeHost)}
              aria-current={route === 'rehearsals' ? 'page' : undefined}
              onPointerEnter={prefetch('rehearsals')}
              onFocus={prefetch('rehearsals')}
            >
              {t('Rehearsals')}
            </a>
            <a
              className={`topbar-link ${route === 'showcase' ? 'active' : ''}`}
              href={workspaceEntryHref('showcase', window.location.search, vscodeHost)}
              aria-current={route === 'showcase' ? 'page' : undefined}
              onPointerEnter={prefetch('showcase')}
              onFocus={prefetch('showcase')}
            >
              {t('Run examples')}
            </a>
          </nav>
          <div className="topbar-preferences">
            <DensitySwitcher />
            <LanguageSwitcher />
          </div>
        </div>
      </header>
      <Suspense fallback={<WorkspaceLoading />}>
        <HostReadySignal route={route} />
        {route === 'business-mirror'
          ? <BusinessMirrorWorkspace />
          : route === 'libraries'
          ? <LibraryWorkbench />
          : route === 'showcase'
          ? <Showcase />
          : route === 'rehearsals'
            ? <RehearsalWorkbench />
            : <AuthorCanvas workspaceVersion={authorWorkspaceVersion} />}
      </Suspense>
    </div>
  );
}

function resolveWorkspaceRoute(pathname: string, search: string, vscodeHost: boolean): WorkspaceRoute {
  if (vscodeHost) {
    const requested = new URLSearchParams(search).get('workspaceRoute');
    if (requested === 'business-mirror' || requested === 'author' || requested === 'libraries'
        || requested === 'rehearsals' || requested === 'showcase') {
      return requested;
    }
    return 'business-mirror';
  }
  return pathname.startsWith('/author')
    ? 'author'
    : pathname.startsWith('/libraries')
    ? 'libraries'
    : pathname.startsWith('/showcase')
    ? 'showcase'
    : pathname.startsWith('/rehearsals')
      ? 'rehearsals'
      : 'business-mirror';
}

function workspaceEntryHref(
  route: WorkspaceRoute,
  search: string,
  vscodeHost: boolean,
  authorVersion: 'v1' | 'v2' = 'v2',
): string {
  if (!vscodeHost) {
    if (route === 'author') return authorWorkspaceEntryHref(search, authorVersion);
    if (route === 'business-mirror') return '/business-mirror/';
    return `/${route}/`;
  }
  const params = new URLSearchParams(search);
  params.set('workspaceRoute', route);
  if (route === 'author' && authorVersion === 'v1') params.set('authorWorkspace', 'legacy');
  else params.delete('authorWorkspace');
  return `?${params.toString()}`;
}

function HostReadySignal({ route }: { route: WorkspaceRoute }) {
  useEffect(() => {
    let secondFrame = 0;
    const firstFrame = window.requestAnimationFrame(() => {
      secondFrame = window.requestAnimationFrame(() => signalHostWorkspaceReady(route));
    });
    return () => {
      window.cancelAnimationFrame(firstFrame);
      if (secondFrame) window.cancelAnimationFrame(secondFrame);
    };
  }, [route]);
  return null;
}

function WorkspaceLoading() {
  const { t } = useI18n();
  return (
    <main className="workspace-loading" aria-busy="true" aria-live="polite">
      <LoaderCircle aria-hidden="true" size={20} />
      <span>{t('Loading workspace...')}</span>
    </main>
  );
}
