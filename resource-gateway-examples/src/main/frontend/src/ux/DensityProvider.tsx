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
  DEFAULT_UI_DENSITY,
  readStoredUiDensity,
  writeStoredUiDensity,
  type UiDensity,
} from './density';

interface DensityContextValue {
  density: UiDensity;
  setDensity: (density: UiDensity) => void;
}

const DensityContext = createContext<DensityContextValue>({
  density: DEFAULT_UI_DENSITY,
  setDensity: () => undefined,
});

export default function DensityProvider({ children }: { children: ReactNode }) {
  const [density, setDensityState] = useState<UiDensity>(readStoredUiDensity);

  const setDensity = useCallback((nextDensity: UiDensity) => {
    setDensityState(nextDensity);
    document.documentElement.dataset.density = nextDensity;
    writeStoredUiDensity(nextDensity);
  }, []);

  useEffect(() => {
    document.documentElement.dataset.density = density;
    writeStoredUiDensity(density);
    return () => {
      delete document.documentElement.dataset.density;
    };
  }, [density]);

  const value = useMemo(() => ({ density, setDensity }), [density, setDensity]);
  return <DensityContext.Provider value={value}>{children}</DensityContext.Provider>;
}

export function useDensity(): DensityContextValue {
  return useContext(DensityContext);
}
