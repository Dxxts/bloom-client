import { useT, type TranslationKey } from "@/i18n";
import { ProgressBar } from "@/components/ui/Feedback";
import type { ProgressEvent, Stage } from "@/types";
import { cn, formatBytes, percent } from "@/utils/format";

/**
 * Percentage for a stage, computed only from real counters reported by the
 * backend. `determinate` is false while no total is known yet.
 */
export function progressValue(stage: Stage, progress: ProgressEvent | null) {
  const p = progress && progress.stage === stage ? progress : null;
  const useBytes = !!p && p.bytesTotal > 0 && stage === "downloading";
  const determinate = !!p && (useBytes || p.filesTotal > 0);
  const value = p ? (useBytes ? percent(p.bytesDone, p.bytesTotal) : percent(p.filesDone, p.filesTotal)) : 0;
  return { p, determinate, value };
}

/**
 * Progress for a launch or install. Percentages are computed only from real
 * counters reported by the backend; when no total is known yet the bar is
 * indeterminate instead of showing an invented number.
 */
export function LaunchProgress({
  stage,
  progress,
  message,
  compact,
}: {
  stage: Stage;
  progress: ProgressEvent | null;
  message?: string;
  compact?: boolean;
}) {
  const t = useT();
  const { p, determinate, value } = progressValue(stage, progress);
  // Generic stage messages ("Preparing...") duplicate the translated label.
  const detail = [p?.message, message].find((m) => m && !m.endsWith("..."));

  return (
    <div className={cn("w-full", compact ? "space-y-1.5" : "space-y-2")}>
      <div className="flex items-baseline justify-between gap-4">
        <div className="min-w-0">
          <span className="text-[13.5px] font-bold text-text">{t(`stage.${stage}` as TranslationKey)}</span>
          {detail && <span className="ml-2 truncate text-[12.5px] text-muted">{detail}</span>}
        </div>
        {determinate && (
          <span className="shrink-0 font-mono text-[12.5px] font-semibold tabular-nums text-primary">
            {Math.floor(value)}%
          </span>
        )}
      </div>
      <ProgressBar value={value} indeterminate={!determinate} />
      {p && !compact && (
        <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 font-mono text-[11.5px] text-faint">
          <span className="min-w-0 max-w-full truncate">{p.currentFile ?? ""}</span>
          <span className="flex flex-wrap gap-x-3 tabular-nums">
            {p.filesTotal > 0 && <span>{t("progress.files", { done: p.filesDone, total: p.filesTotal })}</span>}
            {stage === "downloading" && p.bytesTotal > 0 && (
              <span>{t("progress.bytes", { done: formatBytes(p.bytesDone), total: formatBytes(p.bytesTotal) })}</span>
            )}
            {stage === "downloading" && p.speedBps > 0 && (
              <span className="text-muted">{t("progress.speed", { speed: formatBytes(p.speedBps) })}</span>
            )}
          </span>
        </div>
      )}
    </div>
  );
}
