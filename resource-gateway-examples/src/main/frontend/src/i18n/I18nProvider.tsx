import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';

import {
  readStoredLocale,
  replaceLocaleQuery,
  resolveInitialLocale,
  translate,
  translateRegisteredDynamic,
  writeStoredLocale,
  type Locale,
  type TranslationValues,
} from './i18n';
import { translateMessage, type MessageId } from './messageCatalog';

export interface I18nContextValue {
  locale: Locale;
  setLocale: (locale: Locale) => void;
  t: (source: string, values?: TranslationValues) => string;
  d: (source: string, values?: TranslationValues) => string;
  m: (id: MessageId, values?: TranslationValues) => string;
}

const DEFAULT_CONTEXT: I18nContextValue = {
  locale: 'en',
  setLocale: () => undefined,
  t: (source, values) => translate('en', source, values),
  d: (source, values) => translateRegisteredDynamic('en', source, values),
  m: (id, values) => translateMessage('en', id, values),
};

export const I18nContext = createContext<I18nContextValue>(DEFAULT_CONTEXT);

export default function I18nProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<Locale>(() => resolveInitialLocale({
    search: window.location.search,
    storedLocale: readStoredLocale(),
    browserLocales: navigator.languages?.length ? navigator.languages : [navigator.language],
  }));

  useEffect(() => {
    document.documentElement.lang = locale;
    writeStoredLocale(locale);
  }, [locale]);

  const setLocale = useCallback((nextLocale: Locale) => {
    setLocaleState(nextLocale);
    document.documentElement.lang = nextLocale;
    writeStoredLocale(nextLocale);
    replaceLocaleQuery(nextLocale);
  }, []);

  const value = useMemo<I18nContextValue>(() => ({
    locale,
    setLocale,
    t: (source, values) => translate(locale, source, values),
    d: (source, values) => translateRegisteredDynamic(locale, source, values),
    m: (id, values) => translateMessage(locale, id, values),
  }), [locale, setLocale]);

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nContextValue {
  return useContext(I18nContext);
}
