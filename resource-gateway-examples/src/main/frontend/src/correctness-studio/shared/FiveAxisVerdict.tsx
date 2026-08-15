import {
  AlertTriangle,
  CheckCircle2,
  CircleDashed,
  Clock3,
  MinusCircle,
} from 'lucide-react';

import { useI18n } from '../../i18n/I18nProvider';
import {
  presentCorrectnessVerdict,
  type CorrectnessVerdictInput,
  type CorrectnessVerdictTone,
} from '../model/verdictPresentationPolicy';

interface FiveAxisVerdictProps {
  verdict: CorrectnessVerdictInput;
  compact?: boolean;
}

const AXES = ['execution', 'assertions', 'coverage', 'evidence', 'gate'] as const;

export default function FiveAxisVerdict({ verdict, compact = false }: FiveAxisVerdictProps) {
  const { m, t } = useI18n();
  const view = presentCorrectnessVerdict(verdict);

  return (
    <section
      className="correctness-verdict"
      data-compact={compact}
      data-tone={view.tone}
      aria-label={t('Correctness verdict')}
    >
      <div className="correctness-verdict-summary" role="status">
        <VerdictIcon tone={view.tone} />
        <div>
          <strong>{m(view.primary.messageId, view.primary.params)}</strong>
          <span>{m(view.detail.messageId, view.detail.params)}</span>
        </div>
        <small>{t('Proof level')}: {t(view.proofLevel)}</small>
      </div>
      <div className="correctness-verdict-axes">
        {AXES.map((axisName) => {
          const axis = view.axes[axisName];
          return (
            <div className="correctness-verdict-axis" data-tone={axis.tone} key={axisName}>
              <VerdictIcon tone={axis.tone} />
              <span>{m(axis.label.messageId, axis.label.params)}</span>
              <strong>{m(axis.value.messageId, axis.value.params)}</strong>
            </div>
          );
        })}
      </div>
    </section>
  );
}

function VerdictIcon({ tone }: { tone: CorrectnessVerdictTone }) {
  if (tone === 'passed') return <CheckCircle2 aria-hidden="true" size={18} />;
  if (tone === 'failed' || tone === 'stale') {
    return <AlertTriangle aria-hidden="true" size={18} />;
  }
  if (tone === 'running') return <Clock3 aria-hidden="true" size={18} />;
  if (tone === 'warning') return <CircleDashed aria-hidden="true" size={18} />;
  return <MinusCircle aria-hidden="true" size={18} />;
}
