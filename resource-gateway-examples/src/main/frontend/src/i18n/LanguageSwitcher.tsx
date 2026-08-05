import { useI18n } from './I18nProvider';
import type { Locale } from './i18n';

const OPTIONS: Array<{ locale: Locale; shortLabel: string; sourceLabel: string }> = [
  { locale: 'en', shortLabel: 'EN', sourceLabel: 'English' },
  { locale: 'zh-CN', shortLabel: '中文', sourceLabel: 'Chinese' },
];

export default function LanguageSwitcher() {
  const { locale, setLocale, t } = useI18n();
  return (
    <div className="locale-switcher" role="group" aria-label={t('Language')}>
      {OPTIONS.map((option) => (
        <button
          key={option.locale}
          type="button"
          className={locale === option.locale ? 'active' : ''}
          aria-pressed={locale === option.locale}
          aria-label={t(option.sourceLabel)}
          data-testid={`locale-option:${option.locale}`}
          onClick={() => setLocale(option.locale)}
        >
          {option.shortLabel}
        </button>
      ))}
    </div>
  );
}
