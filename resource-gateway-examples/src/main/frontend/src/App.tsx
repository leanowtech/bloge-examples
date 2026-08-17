import { Fragment, lazy, Suspense, useEffect, useState } from 'react';
import { LoaderCircle, Menu, X } from 'lucide-react';

import I18nProvider, { useI18n } from './i18n/I18nProvider';
import type { MessageId } from './i18n/messageCatalog';
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

type WorkspaceRoute = 'capabilities' | 'business-mirror' | 'author' | 'correctness' | 'libraries' | 'rehearsals' | 'showcase';

const loadCapabilityStudio = () => import('./capability-studio/CapabilityStudio');
const loadBusinessMirrorWorkspace = () => import('./business-mirror/BusinessMirrorWorkspace');
const loadAuthorCanvas = () => import('./AuthorCanvas');
const loadCorrectnessStudio = () => import('./correctness-studio/CorrectnessStudio');
const loadLibraryWorkbench = () => import('./library-authoring/LibraryWorkbench');
const loadRehearsalWorkbench = () => import('./RehearsalWorkbench');
const loadShowcase = () => import('./Showcase');

const CapabilityStudio = lazy(loadCapabilityStudio);
const BusinessMirrorWorkspace = lazy(loadBusinessMirrorWorkspace);
const AuthorCanvas = lazy(loadAuthorCanvas);
const CorrectnessStudio = lazy(loadCorrectnessStudio);
const LibraryWorkbench = lazy(loadLibraryWorkbench);
const RehearsalWorkbench = lazy(loadRehearsalWorkbench);
const Showcase = lazy(loadShowcase);

const ROUTE_PREFETCH: Record<WorkspaceRoute, () => Promise<unknown>> = {
  capabilities: loadCapabilityStudio,
  'business-mirror': loadBusinessMirrorWorkspace,
  author: loadAuthorCanvas,
  correctness: loadCorrectnessStudio,
  libraries: loadLibraryWorkbench,
  rehearsals: loadRehearsalWorkbench,
  showcase: loadShowcase,
};

const NAVIGATION_ROUTES: Array<{ route: WorkspaceRoute; label: string; titleId?: MessageId }> = [
  { route: 'capabilities', label: 'Capability Studio', titleId: 'app.capabilityStudio' },
  { route: 'business-mirror', label: 'Business Mirror' },
  { route: 'author', label: 'Author' },
  { route: 'correctness', label: 'Correctness' },
  { route: 'libraries', label: 'Libraries' },
  { route: 'rehearsals', label: 'Rehearsals' },
  { route: 'showcase', label: 'Run examples' },
];

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
  const { t, m } = useI18n();
  const [navigationOpen, setNavigationOpen] = useState(false);
  const vscodeHost = typeof globalThis.acquireVsCodeApi === 'function';
  const route = resolveWorkspaceRoute(window.location.pathname, window.location.search, vscodeHost);
  const titleEntry = NAVIGATION_ROUTES.find((entry) => entry.route === route);
  const title = titleEntry?.titleId ? m(titleEntry.titleId) : t(titleEntry?.label ?? 'Author');
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
            {NAVIGATION_ROUTES.map((entry) => {
              const current = entry.route === 'author' ? authorIsCurrent : entry.route === route;
              const href = entry.route === 'author'
                ? authorHref
                : workspaceEntryHref(entry.route, window.location.search, vscodeHost);
              return (
                <Fragment key={entry.route}>
                  <a
                    className={`topbar-link ${current ? 'active' : ''}`}
                    href={href}
                    aria-current={current ? 'page' : undefined}
                    onPointerEnter={prefetch(entry.route)}
                    onFocus={prefetch(entry.route)}
                  >
                    {entry.titleId ? m(entry.titleId) : t(entry.label)}
                  </a>
                  {entry.route === 'author' && route === 'author' && (
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
                </Fragment>
              );
            })}
          </nav>
          <div className="topbar-preferences">
            <DensitySwitcher />
            <LanguageSwitcher />
          </div>
        </div>
      </header>
      <Suspense fallback={<WorkspaceLoading />}>
        <HostReadySignal route={route} />
        {route === 'capabilities'
          ? <CapabilityStudio />
          : route === 'business-mirror'
          ? <BusinessMirrorWorkspace />
          : route === 'correctness'
          ? <CorrectnessStudio />
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
    if (requested === 'capabilities' || requested === 'business-mirror' || requested === 'author' || requested === 'correctness' || requested === 'libraries'
        || requested === 'rehearsals' || requested === 'showcase') {
      return requested;
    }
    return 'capabilities';
  }
  return pathname.startsWith('/capabilities')
    ? 'capabilities'
    : pathname.startsWith('/business-mirror')
    ? 'business-mirror'
    : pathname.startsWith('/author')
    ? 'author'
    : pathname.startsWith('/correctness')
    ? 'correctness'
    : pathname.startsWith('/libraries')
    ? 'libraries'
    : pathname.startsWith('/showcase')
    ? 'showcase'
    : pathname.startsWith('/rehearsals')
      ? 'rehearsals'
      : 'capabilities';
}

function workspaceEntryHref(
  route: WorkspaceRoute,
  search: string,
  vscodeHost: boolean,
  authorVersion: 'v1' | 'v2' = 'v2',
): string {
  if (!vscodeHost) {
    if (route === 'author') return authorWorkspaceEntryHref(search, authorVersion);
    if (route === 'capabilities') return '/capabilities/';
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
