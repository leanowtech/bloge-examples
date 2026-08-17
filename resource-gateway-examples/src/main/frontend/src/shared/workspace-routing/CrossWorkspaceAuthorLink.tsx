import { AlertTriangle, Layers3, LoaderCircle, RefreshCw } from 'lucide-react';
import { useEffect, useState } from 'react';

import { resolveBusinessMirrorAuthorLink } from '../../api';
import {
  resolvedBusinessMirrorAuthorHref,
  type AuthoringLinkDescriptor,
  type ExactBusinessMirrorGraphSubject,
} from './businessMirrorAuthorLink';
import type { GuidedAuthoringTelemetry } from '../guided-telemetry/guidedTelemetry';

interface CrossWorkspaceAuthorLinkProps {
  subject: ExactBusinessMirrorGraphSubject;
  label: string;
  resolvingLabel: string;
  failedLabel: string;
  retryLabel: string;
  telemetry: GuidedAuthoringTelemetry;
}

type ResolutionState =
  | { kind: 'resolving' }
  | { kind: 'resolved'; descriptor: AuthoringLinkDescriptor; href: string }
  | { kind: 'failed'; detail: string };

/** Pre-resolves an exact Author link so ordinary anchor navigation remains continuity-guarded. */
export default function CrossWorkspaceAuthorLink({
  subject,
  label,
  resolvingLabel,
  failedLabel,
  retryLabel,
  telemetry,
}: CrossWorkspaceAuthorLinkProps) {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<ResolutionState>({ kind: 'resolving' });
  const subjectKey = `${subject.graphRef.id}:${subject.graphRef.revision}:${subject.graphRef.fingerprint}`;
  useEffect(() => {
    let active = true;
    setState({ kind: 'resolving' });
    resolveBusinessMirrorAuthorLink(subject).then((descriptor) => {
      if (!active) return;
      const href = resolvedBusinessMirrorAuthorHref(descriptor, {
        vscode: typeof globalThis.acquireVsCodeApi === 'function',
        search: window.location.search,
      });
      telemetry.record('CROSS_WORKSPACE_LINK_RESOLVED', {
        targetWorkspace: 'AUTHOR', resolutionKind: 'EXACT', outcome: 'SUCCESS',
      });
      setState({ kind: 'resolved', descriptor, href });
    }).catch((cause: unknown) => {
      if (!active) return;
      telemetry.record('CROSS_WORKSPACE_LINK_RESOLVED', {
        targetWorkspace: 'AUTHOR', resolutionKind: 'EXACT', outcome: 'FAILED',
      });
      setState({ kind: 'failed', detail: cause instanceof Error ? cause.message : String(cause) });
    });
    return () => { active = false; };
  }, [attempt, subjectKey, telemetry]);

  if (state.kind === 'resolved') {
    return (
      <a
        className="business-mirror-secondary-link"
        data-remediation-anchor="business-mirror.capabilities.executable"
        data-author-resolution={state.descriptor.resolution}
        href={state.href}
      >
        <Layers3 aria-hidden="true" size={16} />
        {label}
      </a>
    );
  }
  if (state.kind === 'failed') {
    return (
      <div
        className="business-mirror-author-link-error"
        data-remediation-anchor="business-mirror.capabilities.executable"
        role="alert"
      >
        <AlertTriangle aria-hidden="true" size={16} />
        <span>{failedLabel}<small>{state.detail}</small></span>
        <button type="button" onClick={() => setAttempt((value) => value + 1)}>
          <RefreshCw aria-hidden="true" size={15} />{retryLabel}
        </button>
      </div>
    );
  }
  return (
    <span
      className="business-mirror-secondary-link disabled"
      data-remediation-anchor="business-mirror.capabilities.executable"
      role="status"
    >
      <LoaderCircle aria-hidden="true" className="spin" size={16} />
      {resolvingLabel}
    </span>
  );
}
