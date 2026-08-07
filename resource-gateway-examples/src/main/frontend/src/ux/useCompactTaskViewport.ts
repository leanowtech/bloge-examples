import { useEffect, useState } from 'react';

import { MOBILE_TASK_BREAKPOINT } from './responsiveTaskProjection';

const MOBILE_TASK_MEDIA = `(max-width: ${MOBILE_TASK_BREAKPOINT}px)`;

export function useCompactTaskViewport(): boolean {
  const [compact, setCompact] = useState(() => (
    typeof window !== 'undefined'
      && typeof window.matchMedia === 'function'
      && window.matchMedia(MOBILE_TASK_MEDIA).matches
  ));

  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return undefined;
    const media = window.matchMedia(MOBILE_TASK_MEDIA);
    const update = () => setCompact(media.matches);
    update();
    media.addEventListener?.('change', update);
    return () => media.removeEventListener?.('change', update);
  }, []);

  return compact;
}
