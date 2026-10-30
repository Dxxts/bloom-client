import { useState } from "react";
import { Coffee, Download, FlaskConical, Plus, ScanSearch, Trash2 } from "lucide-react";
import { useT, type TranslationKey } from "@/i18n";
import { Button } from "@/components/ui/Button";
import { Badge, Skeleton } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/Feedback";
import { IconButton } from "@/components/ui/IconButton";
import { LaunchProgress } from "@/features/play/LaunchProgress";
import { useAsync } from "@/hooks/useAsync";
import { api } from "@/services/api";
import { isCancelled, reportError } from "@/services/errors";
import { useSessionsStore } from "@/stores/sessionsStore";
import { toast, useUiStore } from "@/stores/uiStore";
import type { JavaRuntime } from "@/types";
import { cn } from "@/utils/format";
import { SettingsGroup, type SectionProps } from "./shared";

// Runtime components published in Mojang's Java runtime manifest. The
// backend resolves them against the live manifest and reports if one is not
// available for this platform.
const MANAGED_COMPONENTS = [
  { component: "java-runtime-epsilon", major: 25 },
  { component: "java-runtime-delta", major: 21 },
  { component: "java-runtime-gamma", major: 17 },
  { component: "jre-legacy", major: 8 },
];

function ManagedInstall({ component, major, installed, onDone }: { component: string; major: number; installed: boolean; onDone: () => void }) {
  const t = useT();
  const taskId = `java:${component}`;
  const progress = useSessionsStore((s) => s.progress[taskId] ?? null);
  const [busy, setBusy] = useState(false);
  const running = busy || !!progress;

  const install = async () => {
    setBusy(true);
    try {
      await api.installJavaRuntime(component);
      toast.success(t("settings.java.installed", { component }));
      onDone();
    } catch (e) {
      if (!isCancelled(e)) reportError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="border-b border-line px-5 py-3.5 last:border-b-0">
      <div className="flex items-center gap-4">
        <div className="min-w-0 flex-1">
          <div className="text-[13.5px] font-semibold text-text">Java {major}</div>
          <div className="font-mono text-[11.5px] text-faint">{component}</div>
        </div>
        {installed && !running && <Badge tone="primary">{t("play.installed")}</Badge>}
        {running ? (
          <Button size="sm" variant="ghost" onClick={() => void api.cancelTask(taskId)}>
            {t("common.cancel")}
          </Button>
        ) : (
          <Button size="sm" variant={installed ? "ghost" : "outline"} icon={<Download size={14} />} onClick={() => void install()}>
            {installed ? t("play.repair") : t("settings.java.install")}
          </Button>
        )}
      </div>
      {running && (
        <div className="mt-3">
          <LaunchProgress stage={progress?.stage ?? "preparing"} progress={progress} compact />
        </div>
      )}
    </div>
  );
}

export function JavaSection({ draft, update }: SectionProps) {
  const t = useT();
  const runtimes = useAsync(() => api.listJavaRuntimes(), []);
  const [scanning, setScanning] = useState(false);
  const confirm = useUiStore((s) => s.confirm);

  const scan = async () => {
    setScanning(true);
    try {
      const found = await api.detectJavaRuntimes();
      runtimes.setData(found);
      toast.success(t("settings.java.found", { n: found.length }));
    } catch (e) {
      reportError(e);
    } finally {
      setScanning(false);
    }
  };

  const add = async () => {
    try {
      const rt = await api.addJavaRuntime();
      if (rt) await runtimes.reload();
    } catch (e) {
      reportError(e);
    }
  };

  const test = async (rt: JavaRuntime) => {
    try {
      const r = await api.testJavaRuntime(rt.path);
      toast.success(t("settings.java.testOk", { version: r.version, vendor: r.vendor || r.arch }));
    } catch (e) {
      reportError(e);
    }
  };

  const remove = async (rt: JavaRuntime) => {
    try {
      if (rt.source === "managed" && rt.component) {
        const ok = await confirm({
          title: t("settings.java.uninstall"),
          message: t("settings.java.uninstallConfirm", { component: rt.component }),
          confirmLabel: t("settings.java.uninstall"),
          cancelLabel: t("common.cancel"),
          danger: true,
        });
        if (!ok) return;
        await api.uninstallJavaRuntime(rt.component);
      } else {
        await api.removeJavaRuntime(rt.path);
      }
      if (draft.javaPath === rt.path) update({ javaPath: null });
      await runtimes.reload();
    } catch (e) {
      reportError(e);
    }
  };

  const installedComponents = new Set((runtimes.data ?? []).filter((r) => r.source === "managed").map((r) => r.component));

  return (
    <div className="space-y-6">
      <SettingsGroup title={t("settings.java.title")} description={t("settings.java.subtitle")}>
        <div className="flex gap-2 border-b border-line px-5 py-3">
          <Button size="sm" variant="outline" icon={<ScanSearch size={14} />} loading={scanning} onClick={() => void scan()}>
            {t("settings.java.scan")}
          </Button>
          <Button size="sm" variant="ghost" icon={<Plus size={14} />} onClick={() => void add()}>
            {t("settings.java.add")}
          </Button>
        </div>
        {runtimes.loading && !runtimes.data ? (
          <div className="space-y-2 p-4">
            <Skeleton className="h-12 w-full" />
            <Skeleton className="h-12 w-full" />
          </div>
        ) : (runtimes.data ?? []).length === 0 ? (
          <EmptyState icon={<Coffee size={22} />} title={t("settings.java.title")} body={t("settings.java.empty")} className="py-10" />
        ) : (
          <ul className="divide-y divide-line">
            {(runtimes.data ?? []).map((rt) => {
              const isDefault = draft.javaPath === rt.path;
              return (
                <li key={rt.path} className="flex items-center gap-4 px-5 py-3.5">
                  <div
                    className={cn(
                      "flex h-10 w-10 shrink-0 items-center justify-center rounded-xl font-mono text-[13px] font-bold",
                      isDefault ? "bg-primary text-primary-ink" : "bg-surface-hover text-text",
                    )}
                  >
                    {rt.major}
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="text-[13.5px] font-semibold text-text">Java {rt.version}</span>
                      <Badge tone={rt.source === "managed" ? "primary" : "neutral"}>
                        {t(`settings.java.source.${rt.source}` as TranslationKey)}
                      </Badge>
                      <span className="text-[12px] text-faint">
                        {rt.vendor} · {rt.arch}
                      </span>
                    </div>
                    <div className="truncate font-mono text-[11.5px] text-faint" title={rt.path} data-selectable>
                      {rt.path}
                    </div>
                  </div>
                  {isDefault ? (
                    <Badge tone="primary">{t("settings.java.inUse")}</Badge>
                  ) : (
                    <Button size="sm" variant="ghost" onClick={() => update({ javaPath: rt.path })}>
                      {t("settings.java.useGlobal")}
                    </Button>
                  )}
                  <IconButton size="sm" label={t("settings.java.test")} icon={<FlaskConical size={15} />} onClick={() => void test(rt)} />
                  <IconButton
                    size="sm"
                    tone="danger"
                    label={rt.source === "managed" ? t("settings.java.uninstall") : t("settings.java.forget")}
                    icon={<Trash2 size={15} />}
                    onClick={() => void remove(rt)}
                  />
                </li>
              );
            })}
          </ul>
        )}
      </SettingsGroup>

      <SettingsGroup title={t("settings.java.managedTitle")} description={t("settings.java.managedHint")}>
        {MANAGED_COMPONENTS.map((c) => (
          <ManagedInstall
            key={c.component}
            component={c.component}
            major={c.major}
            installed={installedComponents.has(c.component)}
            onDone={() => void runtimes.reload()}
          />
        ))}
      </SettingsGroup>
    </div>
  );
}
