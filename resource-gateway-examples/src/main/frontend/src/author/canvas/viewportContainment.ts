export interface ViewportRect {
  left: number;
  right: number;
  top: number;
  bottom: number;
}

export interface ViewportTranslation {
  x: number;
  y: number;
}

export interface CanvasViewportTransform extends ViewportTranslation {
  zoom: number;
}

/** Returns the smallest translation that keeps one content union inside the visible canvas. */
export function containmentTranslation(
  viewport: ViewportRect,
  content: ViewportRect,
  inset = 8,
): ViewportTranslation {
  return {
    x: axisTranslation(
      viewport.left + inset,
      viewport.right - inset,
      content.left,
      content.right,
    ),
    y: axisTranslation(
      viewport.top + inset,
      viewport.bottom - inset,
      content.top,
      content.bottom,
    ),
  };
}

/**
 * Fits the measured semantic union with the smallest possible zoom reduction, then pans it into
 * view. React Flow's built-in fit only measures nodes, while edge labels may extend beyond them.
 */
export function containedViewportTransform(
  viewport: ViewportRect,
  content: ViewportRect,
  current: CanvasViewportTransform,
  minimumZoom: number,
  inset = 8,
): CanvasViewportTransform {
  const availableWidth = Math.max(1, viewport.right - viewport.left - (inset * 2));
  const availableHeight = Math.max(1, viewport.bottom - viewport.top - (inset * 2));
  const contentWidth = Math.max(1, content.right - content.left);
  const contentHeight = Math.max(1, content.bottom - content.top);
  const minimumScale = current.zoom > 0 ? Math.min(1, minimumZoom / current.zoom) : 1;
  const scale = Math.max(
    minimumScale,
    Math.min(1, availableWidth / contentWidth, availableHeight / contentHeight),
  );
  const screenCenter = {
    x: (viewport.left + viewport.right) / 2,
    y: (viewport.top + viewport.bottom) / 2,
  };
  const localCenter = {
    x: (viewport.right - viewport.left) / 2,
    y: (viewport.bottom - viewport.top) / 2,
  };
  const scaledContent = {
    left: screenCenter.x + ((content.left - screenCenter.x) * scale),
    right: screenCenter.x + ((content.right - screenCenter.x) * scale),
    top: screenCenter.y + ((content.top - screenCenter.y) * scale),
    bottom: screenCenter.y + ((content.bottom - screenCenter.y) * scale),
  };
  const translation = containmentTranslation(viewport, scaledContent, inset);
  return {
    x: localCenter.x - ((localCenter.x - current.x) * scale) + translation.x,
    y: localCenter.y - ((localCenter.y - current.y) * scale) + translation.y,
    zoom: current.zoom * scale,
  };
}

function axisTranslation(
  viewportStart: number,
  viewportEnd: number,
  contentStart: number,
  contentEnd: number,
): number {
  const minimum = viewportStart - contentStart;
  const maximum = viewportEnd - contentEnd;
  if (minimum > maximum) {
    return 0;
  }
  return Math.min(maximum, Math.max(minimum, 0));
}
