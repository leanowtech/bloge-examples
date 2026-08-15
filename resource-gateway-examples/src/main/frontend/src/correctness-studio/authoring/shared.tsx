import { AlertTriangle, CheckCircle2, LoaderCircle, LockKeyhole } from 'lucide-react';
import { useEffect, useState } from 'react';

import { useI18n } from '../../i18n/I18nProvider';
import type { ExactAssetRef } from '../model/domain';

export type AssetLoadState = 'IDLE' | 'LOADING' | 'READY' | 'ERROR';

export function useExactAsset<T>(
  enabled: boolean,
  ref: ExactAssetRef | null,
  loader: (value: ExactAssetRef) => Promise<{ data: T }>,
) {
  const [state, setState] = useState<AssetLoadState>('IDLE');
  const [value, setValue] = useState<T | null>(null);
  const [error, setError] = useState('');
  const [epoch, setEpoch] = useState(0);
  const coordinate = ref ? `${ref.kind}:${ref.id}:${ref.revision}:${ref.fingerprint}` : '';

  useEffect(() => {
    if (!enabled || !ref) {
      setState('IDLE');
      setValue(null);
      setError('');
      return;
    }
    let active = true;
    setState('LOADING');
    setError('');
    loader(ref).then((response) => {
      if (!active) return;
      setValue(response.data);
      setState('READY');
    }).catch((cause: unknown) => {
      if (!active) return;
      setError(cause instanceof Error ? cause.message : String(cause));
      setState('ERROR');
    });
    return () => { active = false; };
  }, [coordinate, enabled, epoch, loader]);

  return { state, value, error, setValue, reload: () => setEpoch((current) => current + 1) };
}

export function AuthoringBoundary({ available, children }: {
  available: boolean;
  children: React.ReactNode;
}) {
  const { t } = useI18n();
  if (available) return <>{children}</>;
  return (
    <section className="correctness-authoring-boundary" data-testid="authoring-read-only">
      <LockKeyhole aria-hidden="true" size={18} />
      <div><strong>{t('Read-only projection')}</strong><span>{t('This deployment has not advertised the authoring command API.')}</span></div>
    </section>
  );
}

export function AssetState({ state, error }: { state: AssetLoadState; error: string }) {
  const { t } = useI18n();
  if (state === 'LOADING') {
    return <p className="correctness-authoring-state"><LoaderCircle className="spin" size={18} />{t('Loading exact revision')}</p>;
  }
  if (state === 'ERROR') {
    return <p className="correctness-authoring-state error" role="alert"><AlertTriangle size={18} />{error}</p>;
  }
  return null;
}

export function MutationState({ state }: { state: { tone: 'idle' | 'busy' | 'success' | 'error'; message: string } }) {
  if (!state.message) return null;
  return (
    <p className="correctness-mutation-state" data-tone={state.tone} role={state.tone === 'error' ? 'alert' : 'status'}>
      {state.tone === 'busy' && <LoaderCircle className="spin" size={16} />}
      {state.tone === 'success' && <CheckCircle2 size={16} />}
      {state.tone === 'error' && <AlertTriangle size={16} />}
      {state.message}
    </p>
  );
}

export function commandId(prefix: string, revision: number): string {
  const random = globalThis.crypto?.randomUUID?.() ?? Math.random().toString(36).slice(2);
  return `${prefix}:${revision}:${random}`;
}
