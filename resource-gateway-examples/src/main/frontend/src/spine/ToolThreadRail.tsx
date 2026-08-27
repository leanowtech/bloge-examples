import { useI18n } from '../i18n/I18nProvider';
import { toolCoordinateHref, type ToolCoordinate, type ToolStage } from './authorSpine';

const STAGES: readonly ToolStage[] = ['define', 'wire', 'publish', 'feed', 'decide', 'prove'];

export interface ToolThreadRailProps {
  coordinate: ToolCoordinate | null;
}

/** Links the six tool lifecycle stages while keeping the coordinate in the URL only. */
export default function ToolThreadRail({ coordinate }: ToolThreadRailProps) {
  const { t } = useI18n();

  return (
    <nav className="spine-thread-rail" data-testid="tool-thread-rail" aria-label={t('Authoring stages')}>
      {coordinate ? (
        <ol>
          {STAGES.map((stage) => (
            <li key={stage}>
              <a
                data-tool-stage={stage}
                href={toolCoordinateHref(window.location.href, { ...coordinate, stage })}
                aria-current={coordinate.stage === stage ? 'step' : undefined}
              >
                {t(stageLabel(stage))}
              </a>
            </li>
          ))}
        </ol>
      ) : (
        <p>{t('Select a tool to continue')}</p>
      )}
    </nav>
  );
}

function stageLabel(stage: ToolStage): string {
  return stage.charAt(0).toUpperCase() + stage.slice(1);
}
