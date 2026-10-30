import { useState } from "react";
import { Eraser, FolderOpen, ShieldCheck, Trash } from "lucide-react";
import { useT, type TranslationKey } from "@/i18n";
import { Button } from "@/components/ui/Button";
import { Skeleton } from "@/components/ui/Card";
import { SettingRow, Slider, Toggle } from "@/components/ui/Form";
import { Select } from "@/components/ui/Select";
import { useAsync } from "@/hooks/useAsync";
import { api } from "@/services/api";
import { reportError } from "@/services/errors";
import { useAppStore } from "@/stores/appStore";
import { toast, useUiStore } from "@/stores/uiStore";
import type { LogLevel, StorageUsage } from "@/types";
import { formatBytes } from "@/utils/format";
import { DirectoryInput } from "./MinecraftSection";
import { SettingsGroup, type SectionProps } from "./shared";

const STORAGE_KEYS: (keyof Omit<StorageUsage, "verificationEntries">)[] = [
  "versions",
  "libraries",
  "assets",
  "runtimes",
  "metadata",
  "instances",
];

export function LauncherSection({ draft, update }: SectionProps) {
  const t = useT();
  const info = useAppStore((s) => s.info);
  const storage = useAsync(() => api.getStorageUsage(), []);
  const confirm = useUiStore((s) => s.confirm);
  const [busy, setBusy] = useState<string | null>(null);

  const run = async (key: string, fn: () => Promise<void>) => {
    setBusy(key);
    try {
      await fn();
      await storage.reload();
    } catch (e) {
      reportError(e);
    } finally {
      setBusy(null);
    }
  };

  const total = storage.data ? STORAGE_KEYS.reduce((sum, k) => sum + storage.data![k], 0) : 0;

  return (
    <div className="space-y-6">
      <SettingsGroup title={t("settings.tab.launcher")}>
        <SettingRow title={t("settings.dataDirectory")} description={t("settings.dataDirectoryHint")} stacked>
          <DirectoryInput
            value={draft.dataDirectory}
            placeholder={info?.defaultDataDir ?? ""}
            onChange={(v) => update({ dataDirectory: v })}
            label={t("settings.dataDirectory")}
          />
        </SettingRow>
        <SettingRow title={t("settings.concurrency")} description={t("settings.concurrencyHint")} stacked>
          <Slider
            label={t("settings.concurrency")}
            min={1}
            max={32}
            value={draft.downloadConcurrency}
            onChange={(v) => update({ downloadConcurrency: v })}
          />
        </SettingRow>
        <SettingRow title={t("settings.autoUpdate")} description={t("settings.autoUpdateHint")}>
          <Toggle checked={draft.autoUpdate} onChange={(v) => update({ autoUpdate: v })} label={t("settings.autoUpdate")} />
        </SettingRow>
        <SettingRow title={t("settings.logLevel")} description={t("settings.logLevelHint")}>
          <div className="flex items-center gap-2">
            <Button size="sm" variant="ghost" icon={<FolderOpen size={14} />} onClick={() => void api.openBloomFolder("logs").catch(reportError)}>
              {t("settings.openLogs")}
            </Button>
            <Select<LogLevel>
              value={draft.logLevel}
              onChange={(v) => update({ logLevel: v })}
              label={t("settings.logLevel")}
              className="w-36"
              align="right"
              options={(["error", "warn", "info", "debug", "trace"] as LogLevel[]).map((l) => ({ value: l, label: l }))}
            />
          </div>
        </SettingRow>
      </SettingsGroup>

      <SettingsGroup title={t("settings.cache")} description={t("settings.cacheHint")}>
        <div className="p-5">
          {storage.loading && !storage.data ? (
            <Skeleton className="h-28 w-full" />
          ) : storage.data ? (
            <>
              <div className="flex h-2.5 overflow-hidden rounded-full bg-surface-hover">
                {STORAGE_KEYS.map((k, i) => (
                  <div
                    key={k}
                    style={{
                      width: `${total ? (storage.data![k] / total) * 100 : 0}%`,
                      opacity: 1 - i * 0.13,
                    }}
                    className="h-full bg-primary"
                  />
                ))}
              </div>
              <dl className="mt-4 grid grid-cols-3 gap-x-6 gap-y-3">
                {STORAGE_KEYS.map((k, i) => (
                  <div key={k} className="flex items-center gap-2.5">
                    <span className="h-2.5 w-2.5 rounded-sm bg-primary" style={{ opacity: 1 - i * 0.13 }} />
                    <dt className="text-[12.5px] text-muted">{t(`settings.cache.${k}` as TranslationKey)}</dt>
                    <dd className="ml-auto font-mono text-[12px] font-semibold text-text">{formatBytes(storage.data![k])}</dd>
                  </div>
                ))}
              </dl>
            </>
          ) : null}
        </div>
        <div className="flex flex-wrap gap-2 border-t border-line px-5 py-3.5">
          <Button
            size="sm"
            variant="outline"
            icon={<Eraser size={14} />}
            loading={busy === "meta"}
            onClick={() =>
              void run("meta", async () => {
                await api.clearMetadataCache();
                toast.success(t("settings.cache.metadataCleared"));
              })
            }
          >
            {t("settings.cache.clearMetadata")}
          </Button>
          <Button
            size="sm"
            variant="outline"
            icon={<ShieldCheck size={14} />}
            loading={busy === "verify"}
            onClick={() =>
              void run("verify", async () => {
                await api.clearVerificationCache();
                toast.success(t("settings.cache.verificationCleared"));
              })
            }
          >
            {t("settings.cache.clearVerification")}
          </Button>
          <Button
            size="sm"
            variant="danger"
            icon={<Trash size={14} />}
            loading={busy === "prune"}
            onClick={() =>
              void run("prune", async () => {
                const ok = await confirm({
                  title: t("settings.cache.prune"),
                  message: t("settings.cache.pruneConfirm"),
                  confirmLabel: t("common.remove"),
                  cancelLabel: t("common.cancel"),
                  danger: true,
                });
                if (!ok) return;
                const freed = await api.pruneUnusedFiles();
                toast.success(t("settings.cache.pruned", { size: formatBytes(freed) }));
              })
            }
          >
            {t("settings.cache.prune")}
          </Button>
        </div>
      </SettingsGroup>
    </div>
  );
}
