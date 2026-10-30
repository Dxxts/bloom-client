import { useEffect, useState } from "react";
import { CheckCircle2, Download, RefreshCw } from "lucide-react";
import { useT } from "@/i18n";
import { BloomMark } from "@/components/BloomMark";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { ProgressBar } from "@/components/ui/Feedback";
import { api } from "@/services/api";
import { errorMessage } from "@/services/errors";
import { EVENTS, on } from "@/services/events";
import { useAppStore } from "@/stores/appStore";
import type { UpdateInfo, UpdateProgress } from "@/types";
import { formatBytes, percent } from "@/utils/format";
import { SettingsGroup } from "./shared";

export function AboutSection() {
  const t = useT();
  const info = useAppStore((s) => s.info);
  const [update, setUpdate] = useState<UpdateInfo | null>(null);
  const [checking, setChecking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [installing, setInstalling] = useState(false);
  const [progress, setProgress] = useState<UpdateProgress | null>(null);

  useEffect(() => {
    const sub = on<UpdateProgress>(EVENTS.updateProgress, setProgress);
    return () => void sub.then((u) => u());
  }, []);

  const check = async () => {
    setChecking(true);
    setError(null);
    try {
      setUpdate(await api.checkForUpdates());
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setChecking(false);
    }
  };

  const install = async () => {
    setInstalling(true);
    setError(null);
    try {
      await api.installUpdate();
    } catch (e) {
      setError(errorMessage(e));
      setInstalling(false);
    }
  };

  return (
    <div className="space-y-6">
      <Card className="flex items-center gap-5 p-6">
        <BloomMark size={56} />
        <div>
          <h2 className="text-[22px] font-extrabold tracking-tight text-text">Bloom Client</h2>
          <p className="mt-0.5 text-[13px] text-muted">{t("settings.about.tagline")}</p>
        </div>
      </Card>

      <SettingsGroup title={t("settings.tab.about")}>
        <dl className="divide-y divide-line">
          {[
            [t("settings.about.version"), info?.version],
            [t("settings.about.platform"), `${info?.os} · ${info?.arch}`],
            [t("settings.about.schema"), `v${info?.schemaVersion}`],
          ].map(([k, v]) => (
            <div key={k} className="flex justify-between px-5 py-2.5 text-[13px]">
              <dt className="text-muted">{k}</dt>
              <dd className="font-mono font-semibold text-text">{v}</dd>
            </div>
          ))}
        </dl>
      </SettingsGroup>

      <SettingsGroup title={t("settings.about.updates")}>
        <div className="space-y-4 p-5">
          {!info?.updaterConfigured ? (
            <p className="text-[13px] text-muted">{t("settings.about.notConfigured")}</p>
          ) : (
            <>
              <div className="flex items-center gap-3">
                <Button
                  size="sm"
                  variant="outline"
                  icon={<RefreshCw size={14} className={checking ? "animate-spin" : ""} />}
                  onClick={() => void check()}
                  disabled={checking || installing}
                >
                  {t("settings.about.check")}
                </Button>
                {update && !update.available && (
                  <span className="flex items-center gap-1.5 text-[13px] text-primary">
                    <CheckCircle2 size={15} /> {t("settings.about.upToDate")}
                  </span>
                )}
              </div>
              {update?.available && (
                <div className="space-y-3 rounded-xl border border-primary/25 bg-primary-soft p-4">
                  <div className="flex items-center justify-between gap-4">
                    <div className="text-[14px] font-bold text-text">
                      {t("settings.about.available", { version: update.version ?? "" })}
                    </div>
                    <Button size="sm" variant="primary" icon={<Download size={14} />} loading={installing} onClick={() => void install()}>
                      {t("settings.about.install")}
                    </Button>
                  </div>
                  {update.notes && (
                    <div>
                      <div className="text-[12px] font-bold uppercase tracking-wide text-muted">{t("settings.about.notes")}</div>
                      <p className="mt-1 whitespace-pre-wrap text-[13px] leading-relaxed text-muted" data-selectable>
                        {update.notes}
                      </p>
                    </div>
                  )}
                  {installing && progress && (
                    <div className="space-y-1.5">
                      <div className="text-[12px] text-muted">
                        {t("settings.about.downloading")} {formatBytes(progress.downloaded)}
                        {progress.total ? ` / ${formatBytes(progress.total)}` : ""}
                      </div>
                      <ProgressBar
                        value={progress.total ? percent(progress.downloaded, progress.total) : undefined}
                        indeterminate={!progress.total}
                      />
                    </div>
                  )}
                </div>
              )}
            </>
          )}
          {error && <p className="text-[13px] text-error">{error}</p>}
        </div>
      </SettingsGroup>

      <p className="text-center text-[12px] text-faint">{t("settings.about.disclaimer")}</p>
    </div>
  );
}
