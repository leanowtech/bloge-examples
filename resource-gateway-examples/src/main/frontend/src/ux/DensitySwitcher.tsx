import { AlignJustify, Rows3 } from 'lucide-react';

import { useI18n } from '../i18n/I18nProvider';
import { useDensity } from './DensityProvider';
import type { UiDensity } from './density';

const OPTIONS: Array<{
  density: UiDensity;
  label: string;
  Icon: typeof Rows3;
}> = [
  { density: 'comfortable', label: 'Comfortable', Icon: Rows3 },
  { density: 'compact', label: 'Compact', Icon: AlignJustify },
];

export default function DensitySwitcher() {
  const { t } = useI18n();
  const { density, setDensity } = useDensity();

  return (
    <div className="density-switcher" role="group" aria-label={t('Interface density')}>
      {OPTIONS.map(({ density: option, label, Icon }) => {
        const selected = density === option;
        const localizedLabel = t(label);
        return (
          <button
            key={option}
            type="button"
            className={selected ? 'active' : ''}
            aria-pressed={selected}
            aria-label={localizedLabel}
            title={localizedLabel}
            data-testid={`density-option:${option}`}
            onClick={() => setDensity(option)}
          >
            <Icon aria-hidden="true" size={15} strokeWidth={2} />
            <span>{localizedLabel}</span>
          </button>
        );
      })}
    </div>
  );
}
