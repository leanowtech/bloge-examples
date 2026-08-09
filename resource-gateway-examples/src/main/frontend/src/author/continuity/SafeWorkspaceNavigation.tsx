import {
  createContext,
  Fragment,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';

import { useI18n } from '../../i18n/I18nProvider';
import type { DraftLifecycleState } from './workspaceContinuity';

export interface WorkspaceNavigationGuard {
  lifecycle: DraftLifecycleState;
  flushRecovery: () => Promise<boolean>;
  save: () => Promise<boolean>;
  exportRecovery: () => void;
  discard: () => Promise<void>;
}

interface NavigationGuardRegistration {
  register: (guard: WorkspaceNavigationGuard) => () => void;
}

const NavigationGuardContext = createContext<NavigationGuardRegistration | null>(null);

export function SafeWorkspaceNavigationProvider({
  children,
  navigate = (href) => window.location.assign(href),
}: {
  children: ReactNode;
  navigate?: (href: string) => void;
}) {
  const { t } = useI18n();
  const guardRef = useRef<WorkspaceNavigationGuard | null>(null);
  const approvedDepartureRef = useRef(false);
  const [pendingHref, setPendingHref] = useState('');
  const [decisionBusy, setDecisionBusy] = useState(false);
  const [decisionError, setDecisionError] = useState('');

  const register = useCallback((guard: WorkspaceNavigationGuard) => {
    guardRef.current = guard;
    return () => {
      if (guardRef.current === guard) guardRef.current = null;
    };
  }, []);

  const requestNavigation = useCallback(async (href: string) => {
    const guard = guardRef.current;
    if (!guard || !requiresContinuityDecision(guard.lifecycle)) {
      navigate(href);
      return;
    }
    try {
      if (await guard.flushRecovery()) {
        approvedDepartureRef.current = true;
        navigate(href);
        return;
      }
    } catch {
      // The explicit decision surface below owns recovery from a failed flush.
    }
    setDecisionError('');
    setPendingHref(href);
  }, [navigate]);

  useEffect(() => {
    const interceptAnchor = (event: MouseEvent) => {
      if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
        return;
      }
      const target = event.target instanceof Element ? event.target.closest<HTMLAnchorElement>('a[href]') : null;
      if (!target || target.target === '_blank' || target.hasAttribute('download')) return;
      const url = new URL(target.href, window.location.href);
      if (url.origin !== window.location.origin || url.hash && url.pathname === window.location.pathname) return;
      event.preventDefault();
      void requestNavigation(`${url.pathname}${url.search}${url.hash}`);
    };
    document.addEventListener('click', interceptAnchor, true);
    return () => document.removeEventListener('click', interceptAnchor, true);
  }, [requestNavigation]);

  useEffect(() => {
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (approvedDepartureRef.current) {
        approvedDepartureRef.current = false;
        return;
      }
      if (!requiresContinuityDecision(guardRef.current?.lifecycle)) return;
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, []);

  const completeDecision = useCallback(async (
    action: (guard: WorkspaceNavigationGuard) => Promise<boolean>,
  ) => {
    const guard = guardRef.current;
    if (!guard || !pendingHref) return;
    setDecisionBusy(true);
    setDecisionError('');
    try {
      if (await action(guard)) {
        const href = pendingHref;
        setPendingHref('');
        approvedDepartureRef.current = true;
        navigate(href);
      } else {
        setDecisionError(t('The workspace is still not recoverable. Stay here or export a recovery package.'));
      }
    } catch (cause: unknown) {
      setDecisionError(t('The workspace could not be secured: {detail}', { detail: String(cause) }));
    } finally {
      setDecisionBusy(false);
    }
  }, [navigate, pendingHref, t]);

  const context = useMemo(() => ({ register }), [register]);

  return (
    <NavigationGuardContext.Provider value={context}>
      <Fragment>
        {children}
        {pendingHref && (
        <div className="rule-editor-backdrop workspace-leave-backdrop" role="presentation">
          <section
            className="workspace-leave-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="workspace-leave-title"
            data-testid="workspace-leave-dialog"
          >
            <span className="eyebrow">{t('Workspace safety')}</span>
            <h2 id="workspace-leave-title">{t('Secure this draft before leaving')}</h2>
            <p>{t('The latest changes are not yet recoverable outside this page.')}</p>
            {decisionError && <p className="error" role="alert">{decisionError}</p>}
            <div className="workspace-leave-actions">
              <button
                type="button"
                className="primary"
                disabled={decisionBusy}
                onClick={() => void completeDecision(async (guard) => guard.save())}
              >
                {t('Save and leave')}
              </button>
              <button
                type="button"
                className="secondary"
                disabled={decisionBusy}
                onClick={() => void completeDecision(async (guard) => {
                  guard.exportRecovery();
                  return true;
                })}
              >
                {t('Export and leave')}
              </button>
              <button
                type="button"
                className="danger"
                disabled={decisionBusy}
                onClick={() => void completeDecision(async (guard) => {
                  await guard.discard();
                  return true;
                })}
              >
                {t('Discard and leave')}
              </button>
              <button
                type="button"
                className="secondary"
                disabled={decisionBusy}
                onClick={() => {
                  setDecisionError('');
                  setPendingHref('');
                }}
              >
                {t('Stay')}
              </button>
            </div>
          </section>
        </div>
        )}
      </Fragment>
    </NavigationGuardContext.Provider>
  );
}

export function useWorkspaceNavigationGuard(guard: WorkspaceNavigationGuard): void {
  const context = useContext(NavigationGuardContext);
  useEffect(() => context?.register(guard), [context, guard]);
}

export function requiresContinuityDecision(lifecycle: DraftLifecycleState | undefined): boolean {
  return lifecycle === 'DIRTY'
    || lifecycle === 'SAVING'
    || lifecycle === 'CONFLICTED'
    || lifecycle === 'RECOVERABLE'
    || lifecycle === 'RECOVERABLE_OFFLINE'
    || lifecycle === 'RECOVERED';
}
