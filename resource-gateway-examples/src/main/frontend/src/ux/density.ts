export type UiDensity = 'comfortable' | 'compact';

export const DEFAULT_UI_DENSITY: UiDensity = 'comfortable';
export const UI_DENSITY_STORAGE_KEY = 'bloge.visual.density';

export function normalizeUiDensity(value: string | null | undefined): UiDensity | null {
  return value === 'comfortable' || value === 'compact' ? value : null;
}

export function readStoredUiDensity(): UiDensity {
  try {
    return normalizeUiDensity(window.localStorage?.getItem(UI_DENSITY_STORAGE_KEY))
      ?? DEFAULT_UI_DENSITY;
  } catch {
    return DEFAULT_UI_DENSITY;
  }
}

export function writeStoredUiDensity(density: UiDensity): void {
  try {
    window.localStorage?.setItem(UI_DENSITY_STORAGE_KEY, density);
  } catch {
    // The current-session density remains usable when storage is unavailable.
  }
}
