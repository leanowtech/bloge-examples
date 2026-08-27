/** The supported authoring spine rollout states. */
export type Spine = 'off' | 'v1';

/** The lifecycle stage represented by a tool-oriented navigation coordinate. */
export type ToolStage = 'define' | 'wire' | 'publish' | 'feed' | 'decide' | 'prove';

/**
 * Identifies the tool and authoring position shown by the navigation shell.
 *
 * This is UI navigation state only. It must not be embedded in a GraphDraft
 * or Scenario protocol payload.
 */
export interface ToolCoordinate {
  toolId: string;
  toolName: string;
  stage: ToolStage;
  graphDraftId?: string;
  graphRevision?: number;
}

const TOOL_STAGES: readonly ToolStage[] = [
  'define',
  'wire',
  'publish',
  'feed',
  'decide',
  'prove',
];

const SPINE_BASE_URL = 'https://author-spine.invalid';

function isToolStage(value: string): value is ToolStage {
  return (TOOL_STAGES as readonly string[]).includes(value);
}

/**
 * Resolves the additive authoring-spine rollout flag.
 *
 * Exactly one lower-case `spine=v1` parameter enables the spine. Duplicate,
 * unknown, or differently-cased values deliberately fall back to `off`.
 */
export function resolveSpine(search: string): Spine {
  const spineParameters = [...new URLSearchParams(search).entries()]
    .filter(([key]) => key.toLowerCase() === 'spine');
  return spineParameters.length === 1
    && spineParameters[0][0] === 'spine'
    && spineParameters[0][1] === 'v1'
    ? 'v1'
    : 'off';
}

/**
 * Parses a tool navigation coordinate from a URL or path.
 *
 * A missing or blank `toolId`, or an unknown stage, means there is no object
 * spine and returns `null`. Missing stage/name values default to `define` and
 * the tool id respectively; malformed optional graph metadata is ignored.
 */
export function parseToolCoordinate(href: string): ToolCoordinate | null {
  try {
    const url = new URL(href, SPINE_BASE_URL);
    const toolId = url.searchParams.get('toolId')?.trim();
    if (!toolId) return null;

    const stageValue = url.searchParams.get('stage') ?? 'define';
    if (!isToolStage(stageValue)) return null;

    const toolName = url.searchParams.get('toolName')?.trim() || toolId;
    const graphDraftId = url.searchParams.get('graphDraftId')?.trim() || undefined;
    const revisionValue = url.searchParams.get('graphRevision');
    const graphRevision = revisionValue === null ? undefined : Number(revisionValue);

    return {
      toolId,
      toolName,
      stage: stageValue,
      ...(graphDraftId ? { graphDraftId } : {}),
      ...(graphRevision !== undefined && Number.isInteger(graphRevision) && graphRevision >= 0
        ? { graphRevision }
        : {}),
    };
  } catch {
    return null;
  }
}

/**
 * Writes a tool coordinate into a URL while preserving unrelated query keys
 * and the URL hash. Reapplying the same coordinate produces the same href.
 */
export function toolCoordinateHref(href: string, coordinate: ToolCoordinate): string {
  if (!coordinate.toolId.trim() || !coordinate.toolName.trim() || !isToolStage(coordinate.stage)) {
    throw new TypeError('A tool coordinate requires a non-blank id, name, and supported stage');
  }

  const url = new URL(href, SPINE_BASE_URL);
  const params = url.searchParams;
  params.set('toolId', coordinate.toolId.trim());
  params.set('toolName', coordinate.toolName.trim());
  params.set('stage', coordinate.stage);

  if (coordinate.graphDraftId?.trim()) params.set('graphDraftId', coordinate.graphDraftId.trim());
  else params.delete('graphDraftId');

  if (coordinate.graphRevision !== undefined && Number.isInteger(coordinate.graphRevision)
    && coordinate.graphRevision >= 0) {
    params.set('graphRevision', String(coordinate.graphRevision));
  } else {
    params.delete('graphRevision');
  }

  const serialized = `${url.pathname}${url.search}${url.hash}`;
  if (/^[a-z][a-z\d+.-]*:/i.test(href)) return url.toString();
  if (href.startsWith('//')) return `//${url.host}${serialized}`;
  return serialized;
}
