import { useState } from "react";
import { Activity, CheckCircle2, Cpu, FileDown, FolderOpen, MonitorCog, RotateCcw, XCircle } from "lucide-react";
import { useT } from "@/i18n";
import { Button } from "@/components/ui/Button";
import { Skeleton } from "@/components/ui/Card";
import { Input, SettingRow } from "@/components/ui/Form";
import { useAsync } from "@/hooks/useAsync";
import { api } from "@/services/api";
import { errorMessage, reportError } from "@/services/errors";
import { useAppStore } from "@/stores/appStore";
import { toast, useUiStore } from "@/stores/uiStore";
import type { MinecraftDiagnostics } from "@/types";
import { formatMemory } from "@/utils/format";
import { SettingsGroup, type SectionProps } from "./shared";

function InfoGrid({ rows }: { rows: [string, React.ReactNode][] }) {
  return (
    <dl className="divide-y divide-line">
      {rows.map(([k, v]) => (
        <div key={k} className="flex justify-between gap-6 px-5 py-2.5 text-[13px]">
          <dt className="shrink-0 text-muted">{k}</dt>
          <dd className="min-w-0 truncate text-right font-semibold text-text" data-selectable>
            {v}
          </dd>
        </div>
      ))}
    </dl>
  );
}

export function AdvancedSection({ draft, update }: SectionProps) {
  const t = useT();
  const info = useAppStore((s) => s.info);
  const replaceSettings = useAppStore((s) => s.replaceSettings);
  const system = useAsync(() => api.getSystemInfo(), []);
  const java = useAsync(() => api.listJavaRuntimes(), []);
  const [diag, setDiag] = useState<MinecraftDiagnostics | null>(null);
  const [diagRunning, setDiagRunning] = useState(false);
  const [exporting, setExporting] = useState(false);
  const confirm = useUiStore((s) => s.confirm);

  const runDiagnostics = async () => {
    setDiagRunning(true);
    try {
      setDiag(await api.runMinecraftDiagnostics());
    } catch (e) {
      reportError(e);
    } finally {
      setDiagRunning(false);
    }
  };

  const exportDiagnostics = async () => {
    setExporting(true);
    try {
      const path = await api.exportDiagnostics();
      if (path) toast.success(t("settings.advanced.exported", { path }));
    } catch (e) {
      reportError(e);
    } finally {
      setExporting(false);
    }
  };

  const reset = async () => {
    const ok = await confirm({
      title: t("settings.advanced.reset"),
      message: t("settings.advanced.resetConfirm"),
      confirmLabel: t("common.reset"),
      cancelLabel: t("common.cancel"),
      danger: true,
    });
    if (!ok) return;
    try {
      replaceSettings(await api.resetSettings());
      toast.success(t("settings.advanced.resetDone"));
    } catch (e) {
      reportError(e);
    }
  };

  const s = system.data;
  return (
    <div className="space-y-6">
      <SettingsGroup title={t("settings.advanced.system")}>
        {system.loading ? (
          <div className="space-y-2 p-5">
            <Skeleton className="h-4 w-full" />
            <Skeleton className="h-4 w-4/5" />
            <Skeleton className="h-4 w-3/5" />
          </div>
        ) : system.error ? (
          <p className="p-5 text-[13px] text-error">{errorMessage(system.error)}</p>
        ) : s ? (
          <InfoGrid
            rows={[
              [t("settings.sys.os"), `${s.osVersion || s.osName}`],
              [t("settings.sys.arch"), s.arch],
              [t("settings.sys.cpu"), s.cpuBrand],
              ["", t("settings.sys.cores", { logical: s.cpuCoresLogical, physical: s.cpuCoresPhysical ?? "?" })],
              [
                t("settings.sys.memory"),
                t("settings.sys.memoryValue", {
                  available: formatMemory(s.availableMemoryMb),
                  total: formatMemory(s.totalMemoryMb),
                }),
              ],
            ]}
          />
        ) : null}
      </SettingsGroup>

      <SettingsGroup title={t("settings.advanced.gpu")}>
        {s && s.gpus.length === 0 ? (
          <p className="px-5 py-4 text-[13px] text-muted">{t("settings.advanced.noGpu")}</p>
        ) : (
          <ul className="divide-y divide-line">
            {(s?.gpus ?? []).map((g, i) => (
              <li key={i} className="flex items-center gap-4 px-5 py-3">
                <MonitorCog size={18} className="shrink-0 text-faint" />
                <div className="min-w-0 flex-1">
                  <div className="truncate text-[13.5px] font-semibold text-text">{g.name}</div>
                  <div className="text-[12px] text-faint">
                    {[
                      g.vendor,
                      g.driverVersion && t("settings.sys.driver", { value: g.driverVersion }),
                      g.memoryMb && t("settings.sys.vram", { value: formatMemory(g.memoryMb) }),
                    ]
                      .filter(Boolean)
                      .join(" · ")}
                  </div>
                </div>
              </li>
            ))}
          </ul>
        )}
      </SettingsGroup>

      <SettingsGroup title={t("settings.advanced.javaDiag")}>
        <div className="divide-y divide-line">
          {(java.data ?? []).length === 0 ? (
            <p className="px-5 py-4 text-[13px] text-muted">{t("settings.java.empty")}</p>
          ) : (
            (java.data ?? []).map((j) => (
              <div key={j.path} className="flex items-center gap-3 px-5 py-2.5 text-[12.5px]">
                <Cpu size={14} className="text-faint" />
                <span className="w-24 shrink-0 font-semibold text-text">Java {j.major}</span>
                <span className="w-40 shrink-0 truncate text-muted">{j.vendor}</span>
                <span className="min-w-0 flex-1 truncate font-mono text-[11.5px] text-faint">{j.path}</span>
              </div>
            ))
          )}
        </div>
      </SettingsGroup>

      <SettingsGroup title={t("settings.advanced.mcDiag")}>
        <div className="px-5 py-3">
          <Button size="sm" variant="outline" icon={<Activity size={14} />} loading={diagRunning} onClick={() => void runDiagnostics()}>
            {t("settings.advanced.runDiag")}
          </Button>
        </div>
        {diag && (
          <div className="border-t border-line">
            {diag.services.map((r) => (
              <div key={r.url} className="flex items-center gap-3 px-5 py-2.5 text-[13px]">
                {r.ok ? <CheckCircle2 size={15} className="text-primary" /> : <XCircle size={15} className="text-error" />}
                <span className="w-44 shrink-0 font-semibold text-text">{r.name}</span>
                <span className="min-w-0 flex-1 truncate text-[12px] text-faint">{r.ok ? `${r.latencyMs} ms` : r.error}</span>
                <span className={r.ok ? "text-primary" : "text-error"}>
                  {r.ok ? t("settings.advanced.reachable") : t("settings.advanced.unreachable")}
                </span>
              </div>
            ))}
            <InfoGrid
              rows={[
                [t("settings.advanced.writable"), diag.dataDirWritable ? t("common.yes") : t("common.no")],
                [t("settings.advanced.freeSpace"), diag.freeSpaceMb !== null ? formatMemory(diag.freeSpaceMb) : t("common.unknown")],
                [t("settings.advanced.installedVersions"), String(diag.installedVersions)],
                [t("instances.title"), String(diag.instances)],
              ]}
            />
          </div>
        )}
      </SettingsGroup>

      <SettingsGroup title={t("settings.advanced.folders")}>
        <div className="flex flex-wrap gap-2 px-5 py-4">
          <Button size="sm" variant="outline" icon={<FolderOpen size={14} />} onClick={() => void api.openBloomFolder("data").catch(reportError)}>
            {t("settings.advanced.openData")}
          </Button>
          <Button size="sm" variant="outline" icon={<FolderOpen size={14} />} onClick={() => void api.openBloomFolder("instances").catch(reportError)}>
            {t("settings.advanced.openInstances")}
          </Button>
          <Button size="sm" variant="outline" icon={<FolderOpen size={14} />} onClick={() => void api.openBloomFolder("config").catch(reportError)}>
            {t("settings.advanced.openConfig")}
          </Button>
          <Button size="sm" variant="primary" icon={<FileDown size={14} />} loading={exporting} onClick={() => void exportDiagnostics()} className="ml-auto">
            {t("settings.advanced.export")}
          </Button>
        </div>
      </SettingsGroup>

      <SettingsGroup title={t("accounts.title")}>
        <SettingRow
          title={t("settings.advanced.msClientId")}
          description={`${t("settings.advanced.msClientIdHint")}${info?.microsoftClientIdSource === "build" ? " (build)" : ""}`}
          stacked
        >
          <Input
            value={draft.microsoftClientId ?? ""}
            placeholder={info?.microsoftClientIdSource === "build" ? "BLOOM_MS_CLIENT_ID" : "00000000-0000-0000-0000-000000000000"}
            onChange={(e) => update({ microsoftClientId: e.target.value || null })}
            className="font-mono text-[12.5px]"
            spellCheck={false}
          />
        </SettingRow>
      </SettingsGroup>

      <div className="flex justify-end">
        <Button variant="danger" icon={<RotateCcw size={15} />} onClick={() => void reset()}>
          {t("settings.advanced.reset")}
        </Button>
      </div>
    </div>
  );
}
