import type { MessageDescriptor, MessageId } from '../../i18n/messageCatalog';
import type {
  CanvasPerceptualQualityReason,
  CanvasPerceptualQualityReport,
} from './canvasSemantics';
import type { CanvasLayoutCollision, CanvasLayoutQualityReport } from './layoutQuality';

export interface CanvasQualityPresentation {
  geometry: MessageDescriptor;
  perception: MessageDescriptor;
  reasons: MessageDescriptor[];
}

const REASON_MESSAGE_IDS: Record<CanvasPerceptualQualityReason['code'], MessageId> = {
  NODE_OVERLAPS: 'layout.quality.reason.nodeOverlaps',
  NODE_LABEL_COLLISIONS: 'layout.quality.reason.nodeLabelCollisions',
  LABEL_LABEL_COLLISIONS: 'layout.quality.reason.labelLabelCollisions',
  SMALL_GRAPH_ZOOM_FLOOR: 'layout.quality.reason.smallGraphZoom',
  TITLE_SIZE_FLOOR: 'layout.quality.reason.titleSize',
  OVERVIEW_FIELD_LABELS: 'layout.quality.reason.overviewFields',
  LABEL_DENSITY_HIGH: 'layout.quality.reason.labelDensity',
};

const PERCEPTION_MESSAGE_IDS: Record<
`${CanvasPerceptualQualityReport['geometryStatus']}:${CanvasPerceptualQualityReport['status']}`,
MessageId
> = {
  'PASS:PASS': 'layout.quality.perception.passPass',
  'PASS:REVIEW': 'layout.quality.perception.passReview',
  'REVIEW:PASS': 'layout.quality.perception.reviewPass',
  'REVIEW:REVIEW': 'layout.quality.perception.reviewReview',
};

export function presentCanvasQuality(
  geometry: CanvasLayoutQualityReport,
  perception: CanvasPerceptualQualityReport,
): CanvasQualityPresentation {
  const perceptual = presentCanvasPerceptualQuality(perception);
  return {
    geometry: {
      messageId: 'layout.quality.geometrySummary',
      params: {
        overlaps: geometry.nodeOverlaps,
        collisions: geometry.edgeLabelCollisions,
        pinned: geometry.pinnedNodes,
      },
    },
    perception: perceptual.perception,
    reasons: perceptual.reasons,
  };
}

export function presentCanvasPerceptualQuality(
  perception: CanvasPerceptualQualityReport,
): Pick<CanvasQualityPresentation, 'perception' | 'reasons'> {
  return {
    perception: {
      messageId: PERCEPTION_MESSAGE_IDS[`${perception.geometryStatus}:${perception.status}`],
      params: {
        titlePx: perception.effectiveTitleFontPx.toFixed(1),
        edgeLabels: perception.visibleEdgeLabels,
        density: perception.labelDensityPer100kPx.toFixed(1),
      },
    },
    reasons: perception.reasons.map(presentPerceptualReason),
  };
}

export function presentPerceptualReason(
  reason: CanvasPerceptualQualityReason,
): MessageDescriptor {
  return {
    messageId: REASON_MESSAGE_IDS[reason.code],
    params: reason.count === undefined ? undefined : { count: reason.count },
  };
}

export function presentLayoutCollision(collision: CanvasLayoutCollision): MessageDescriptor {
  return {
    messageId: 'layout.quality.edgeCollision',
    params: { edgeId: collision.edgeId, nodeId: collision.nodeId },
  };
}
