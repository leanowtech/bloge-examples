import { useContext, useMemo, type ReactNode } from 'react';

import {
  I18nContext,
  type I18nContextValue,
} from '../i18n/I18nProvider';
import { CORRECTNESS_TRANSLATIONS } from './locales';

export default function CorrectnessI18nProvider({ children }: { children: ReactNode }) {
  const parent = useContext(I18nContext);
  const value = useMemo<I18nContextValue>(() => ({
    ...parent,
    t: (source, values = {}) => {
      const template = CORRECTNESS_TRANSLATIONS[parent.locale]?.[source];
      if (!template) return parent.t(source, values);
      return template.replace(/\{([A-Za-z][A-Za-z0-9]*)\}/g, (match, key: string) => (
        Object.prototype.hasOwnProperty.call(values, key) ? String(values[key]) : match
      ));
    },
  }), [parent]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}
