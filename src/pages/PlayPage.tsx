import { useEffect, useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { Check, Download, HardDrive, Plus, RefreshCw, ShieldCheck, Trash2, WifiOff, X } from "lucide-react";
import { useLocale, useT, type TranslationKey } from "@/i18n";
import { Button } from "@/components/ui/Button";
import { REVEAL_EASE, staggerItem } from "@/components/ui/Reveal";
import { Badge, Card, PageHeader, Skeleton } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/Feedback";
import { SearchInput } from "@/components/ui/Form";
import { Segmented } from "@/components/ui/Select";
import { Tooltip } from "@/components/ui/Tooltip";
import { useInstanceEditor } from "@/features/instances/InstanceEditor";
import { LaunchProgress } from "@/features/play/LaunchProgress";
import { useAsync } from "@/hooks/useAsync";
import { api } from "@/services/api";
import { errorMessage, isCancelled, reportError } from "@/services/errors";
import { EVENTS, on } from "@/services/events";
import { useAppStore } from "@/stores/appStore";
import { useSessionsStore } from "@/stores/sessionsStore";
import { toast, useUiStore } from "@/stores/uiStore";
import type { VersionSummary } from "@/types";
import { cn, formatBytes, formatDate, relativeTime } from "@/utils/format";
import { artFor } from "@/features/home/HeroBackdrop";
import { Page } from "@/layouts/Page";
import { VersionTypeIcon } from "@/components/GameIcons";

type Filter = "release" | "snapshot" | "legacy" | "installed";

function matchesFilter(v: VersionSummary, f: Filter): boolean {
  switch (f) {
    case "release":
      return v.releaseType === "release";
    case "snapshot":
      return v.releaseType === "snapshot";
    case "legacy":
      return v.releaseType === "old_beta" || v.releaseType === "old_alpha";
    case "installed":
      return v.installed;
  }
}

function DetailRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4 py-2 text-[13px]">
      <span className="shrink-0 text-muted">{label}</span>
      <span className="min-w-0 text-right font-semibold text-text" data-selectable>
        {children}
      </span>
    </div>
  );
}

function VersionDetailsPanel({
  version,
  latestRelease,
  onChanged,
}: {
  version: VersionSummary;
  latestRelease: string | null;
  onChanged: () => void;
}) {
  const t = useT();
  const locale = useLocale();
  const taskId = `version:${version.id}`;
  const progress = useSessionsStore((s) => s.progress[taskId] ?? null);
  const clearProgress = useSessionsStore((s) => s.clearProgress);
  const openCreate = useInstanceEditor((s) => s.openCreate);
  const confirm = useUiStore((s) => s.confirm);
  const [localInstalling, setInstalling] = useState(false);
  // The task keeps running in the backend if the user navigates away; the
  // progress store reflects it when they come back.
  const installing = localInstalling || !!progress;
  const details = useAsync(() => api.getVersionDetails(version.id), [version.id, version.installed]);

  const install = async () => {
    setInstalling(true);
    try {
      await api.installVersion(version.id);
      toast.success(t("play.installDone", { version: version.id }));
      onChanged();
      await details.reload();
    } catch (e) {
      if (!isCancelled(e)) reportError(e);
    } finally {
      setInstalling(false);
      clearProgress(taskId);
    }
  };

  const remove = async () => {
    const ok = await confirm({
      title: t("play.delete"),
      message: t("play.deleteConfirm", { version: version.id }),
      confirmLabel: t("common.delete"),
      cancelLabel: t("common.cancel"),
      danger: true,
    });
    if (!ok) return;
    try {
      await api.deleteVersion(version.id);
      toast.success(t("play.deleted", { version: version.id }));
      onChanged();
    } catch (e) {
      reportError(e);
    }
  };

  const d = details.data;
  const art = artFor(version.id);
  return (
    <motion.div
      initial={{ opacity: 0, y: 10, filter: "blur(6px)" }}
      animate={{ opacity: 1, y: 0, filter: "blur(0px)", transitionEnd: { filter: "none" } }}
      transition={{ duration: 0.45, ease: REVEAL_EASE }}
      className="sticky top-6"
    >
      <Card className="relative overflow-hidden p-5">
        <img
          src={art.src}
          alt=""
          draggable={false}
          aria-hidden
          className="pointer-events-none absolute inset-x-0 top-0 h-[150px] w-full object-cover opacity-50 [mask-image:linear-gradient(to_bottom,black,transparent)]"
          style={{ objectPosition: art.position }}
        />
        <div className="relative flex items-start justify-between gap-4 pt-6">
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <Badge tone={version.releaseType === "release" ? "primary" : "warning"}>
                {t(`type.${version.releaseType}` as TranslationKey)}
              </Badge>
              {version.id === latestRelease && <Badge tone="primary">{t("play.latest")}</Badge>}
            </div>
            <h2 className="mt-2 flex items-center gap-3 truncate text-[30px] font-extrabold tracking-[-0.02em] text-text drop-shadow-[0_2px_12px_rgb(0_0_0/0.6)]" data-selectable>
              <VersionTypeIcon type={version.releaseType} size={30} className="drop-shadow-[0_4px_10px_rgb(0_0_0/0.5)]" />
              {version.id}
            </h2>
          </div>
          {version.installed && (
            <span className="mt-1 flex items-center gap-1.5 rounded-md bg-black/45 px-2 py-0.5 text-[12px] font-semibold text-primary backdrop-blur-md">
              <Check size={15} /> {t("play.status.installed")}
            </span>
          )}
        </div>

        <div className="relative mt-4 divide-y divide-line border-y border-line">
          <DetailRow label={t("play.detail.published")}>{formatDate(version.releaseTime, locale, true)}</DetailRow>
          {details.loading && !d ? (
            <div className="space-y-3 py-3">
              <Skeleton className="h-4 w-full" />
              <Skeleton className="h-4 w-4/5" />
              <Skeleton className="h-4 w-3/5" />
            </div>
          ) : details.error ? (
            <p className="py-3 text-[12.5px] text-error">{errorMessage(details.error)}</p>
          ) : d ? (
            <>
              <DetailRow label={t("play.detail.java")}>
                {t("play.detail.javaValue", { major: d.javaMajor, component: d.javaComponent })}
              </DetailRow>
              <DetailRow label={t("play.detail.assets")}>{d.assetIndex}</DetailRow>
              <DetailRow label={t("play.detail.libraries")}>{d.libraryCount}</DetailRow>
              <DetailRow label={t("play.detail.client")}>{formatBytes(d.clientSize)}</DetailRow>
              {d.diskSize !== null && <DetailRow label={t("play.detail.disk")}>{formatBytes(d.diskSize)}</DetailRow>}
              <DetailRow label={t("play.detail.status")}>
                {d.installed
                  ? `${t("play.status.installed")}${d.installedAt ? ` · ${formatDate(d.installedAt, locale)}` : ""}`
                  : t("play.status.notInstalled")}
              </DetailRow>
              <DetailRow label={t("play.detail.mainClass")}>
                <span className="font-mono text-[11.5px]">{d.mainClass}</span>
              </DetailRow>
            </>
          ) : null}
          {version.metadataSha1 && (
            <DetailRow label={t("play.detail.metadata")}>
              <span className="font-mono text-[11px] text-muted">sha1 {version.metadataSha1.slice(0, 12)}…</span>
            </DetailRow>
          )}
        </div>

        <AnimatePresence>
          {installing && (
            <motion.div
              initial={{ opacity: 0, height: 0 }}
              animate={{ opacity: 1, height: "auto" }}
              exit={{ opacity: 0, height: 0 }}
              className="overflow-hidden"
            >
              <div className="pt-5">
                <LaunchProgress stage={progress?.stage ?? "preparing"} progress={progress} />
              </div>
            </motion.div>
          )}
        </AnimatePresence>

        <div className="relative mt-5 flex flex-wrap gap-1.5">
          {installing ? (
            <Button variant="secondary" icon={<X size={15} />} onClick={() => void api.cancelTask(taskId)}>
              {t("common.cancel")}
            </Button>
          ) : version.installed ? (
            <Button variant="secondary" icon={<ShieldCheck size={15} />} onClick={() => void install()} className="px-3.5">
              {t("play.repair")}
            </Button>
          ) : (
            <Button variant="primary" icon={<Download size={15} />} onClick={() => void install()}>
              {t("play.install")}
            </Button>
          )}
          <Button variant="outline" icon={<Plus size={15} />} onClick={() => openCreate(version.id)} disabled={installing} className="px-3.5">
            {t("play.createInstance")}
          </Button>
          {version.installed && !installing && (
            <Tooltip content={t("common.delete")} className="ml-auto">
              <Button
                variant="danger"
                icon={<Trash2 size={15} />}
                aria-label={t("common.delete")}
                onClick={() => void remove()}
                className="w-10 px-0"
              />
            </Tooltip>
          )}
        </div>
      </Card>
    </motion.div>
  );
}

export function PlayPage() {
  const t = useT();
  const locale = useLocale();
  const showSnapshots = useAppStore((s) => s.settings?.showSnapshots ?? false);
  const [filter, setFilter] = useState<Filter>(showSnapshots ? "snapshot" : "release");
  const [query, setQuery] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const list = useAsync(() => api.listVersions(false), []);
  // Installs started elsewhere (e.g. by a launch) change the installed state.
  useEffect(() => {
    const sub = on(EVENTS.instancesChanged, () => void list.reload());
    return () => void sub.then((u) => u());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const refresh = async () => {
    setRefreshing(true);
    try {
      list.setData(await api.listVersions(true));
    } catch (e) {
      reportError(e);
    } finally {
      setRefreshing(false);
    }
  };

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (list.data?.versions ?? []).filter((v) => matchesFilter(v, filter) && (!q || v.id.toLowerCase().includes(q)));
  }, [list.data, filter, query]);

  const selected =
    list.data?.versions.find((v) => v.id === selectedId) ??
    filtered[0] ??
    null;

  return (
    <Page art={2}>
      <PageHeader
        title={t("play.title")}
        subtitle={t("play.subtitle")}
        actions={
          <Button
            variant="secondary"
            icon={<RefreshCw size={15} className={refreshing ? "animate-spin" : ""} />}
            onClick={() => void refresh()}
            disabled={refreshing}
          >
            {t("play.refreshMetadata")}
          </Button>
        }
      />

      {list.data?.offline && (
        <div className="mb-4 flex items-center gap-2.5 rounded-xl bg-warning/[0.08] px-4 py-2.5 text-[13px] text-warning ring-1 ring-inset ring-warning/25">
          <WifiOff size={15} /> {t("play.offline")}
        </div>
      )}

      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <Segmented
          value={filter}
          onChange={setFilter}
          label={t("play.title")}
          layoutId="version-filter"
          options={[
            { value: "release", label: t("play.releases") },
            { value: "snapshot", label: t("play.snapshots") },
            { value: "legacy", label: t("play.legacy") },
            { value: "installed", label: t("play.installed") },
          ]}
        />
        <div className="flex items-center gap-3">
          {list.data && list.data.fetchedAt > 0 && (
            <span className="text-[12px] text-faint">
              {t("play.updatedAt", { time: relativeTime(list.data.fetchedAt, t, locale) })}
            </span>
          )}
          <SearchInput value={query} onChange={setQuery} placeholder={t("play.searchPlaceholder")} className="w-56" />
        </div>
      </div>

      <div className="grid grid-cols-[minmax(0,1fr)_380px] items-start gap-5">
        <Card className="overflow-hidden">
          {list.loading && !list.data ? (
            <div className="space-y-2 p-4">
              {Array.from({ length: 8 }, (_, i) => (
                <Skeleton key={i} className="h-11 w-full" />
              ))}
            </div>
          ) : list.error && !list.data ? (
            <EmptyState
              icon={<WifiOff size={22} />}
              title={t("error.title")}
              body={errorMessage(list.error)}
              action={<Button onClick={() => void list.reload()}>{t("common.retry")}</Button>}
            />
          ) : filtered.length === 0 ? (
            <EmptyState icon={<HardDrive size={22} />} title={t("play.noVersions")} />
          ) : (
            <ul className="max-h-[calc(100vh-250px)] divide-y divide-line overflow-y-auto">
              {filtered.map((v, n) => (
                <motion.li key={v.id} {...(n < 24 ? staggerItem(n, 0.1) : {})}>
                  <button
                    type="button"
                    onClick={() => setSelectedId(v.id)}
                    className={cn(
                      "relative flex w-full items-center gap-4 px-5 py-3 text-left transition-colors",
                      selected?.id === v.id ? "bg-white/[0.06]" : "hover:bg-white/[0.03]",
                    )}
                  >
                    {selected?.id === v.id && (
                      <motion.span
                        layoutId="version-accent"
                        transition={{ type: "spring", stiffness: 520, damping: 40 }}
                        className="absolute inset-y-2 left-0 w-[3px] rounded-r-full bg-primary shadow-[0_0_10px_rgb(101_217_154/0.8)]"
                        aria-hidden
                      />
                    )}
                    <span
                      className={cn(
                        "h-2 w-2 shrink-0 rounded-full",
                        v.installed ? "bg-primary shadow-[0_0_8px_rgb(101_217_154/0.7)]" : "bg-white/15",
                      )}
                    />
                    <VersionTypeIcon type={v.releaseType} size={16} />
                    <span className="min-w-0 flex-1 truncate text-[14px] font-semibold text-text">{v.id}</span>
                    {v.id === list.data?.latestRelease && <Badge tone="primary">{t("play.latest")}</Badge>}
                    {v.id === list.data?.latestSnapshot && <Badge tone="warning">{t("play.latest")}</Badge>}
                    {v.installed && <Badge>{t("play.installed")}</Badge>}
                    <span className="w-28 shrink-0 text-right text-[12.5px] text-faint">
                      {formatDate(v.releaseTime, locale)}
                    </span>
                  </button>
                </motion.li>
              ))}
            </ul>
          )}
        </Card>

        {selected ? (
          <VersionDetailsPanel
            key={selected.id}
            version={selected}
            latestRelease={list.data?.latestRelease ?? null}
            onChanged={() => void list.reload()}
          />
        ) : (
          <Card className="p-6 text-[13px] text-muted">{t("play.selectVersion")}</Card>
        )}
      </div>
    </Page>
  );
}
