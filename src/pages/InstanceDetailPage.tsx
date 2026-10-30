import { useEffect, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router";
import { AnimatePresence, motion } from "motion/react";
import {
  AlertTriangle,
  ArrowLeft,
  CheckCircle2,
  Clock3,
  FileText,
  FolderOpen,
  Gauge,
  History,
  Info,
  Play,
  Settings2,
  Square,
  SquareTerminal,
  Trash2,
  X,
} from "lucide-react";
import { useLocale, useT, type TranslationKey } from "@/i18n";
import { Button } from "@/components/ui/Button";
import { Badge, Card, CardHeader, Skeleton } from "@/components/ui/Card";
import { EmptyState, Tabs } from "@/components/ui/Feedback";
import { Modal } from "@/components/ui/Modal";
import { Tooltip } from "@/components/ui/Tooltip";
import { Spinner } from "@/components/ui/Spinner";
import { GameConsole } from "@/features/instances/GameConsole";
import { InstanceForm, inputFromInstance } from "@/features/instances/InstanceForm";
import { InstanceIcon } from "@/features/instances/InstanceIcon";
import { LaunchProgress } from "@/features/play/LaunchProgress";
import { useAsync } from "@/hooks/useAsync";
import { useLaunch } from "@/hooks/useLaunch";
import { api } from "@/services/api";
import { errorMessage, reportError } from "@/services/errors";
import { useInstancesStore } from "@/stores/instancesStore";
import { toast } from "@/stores/uiStore";
import type { Instance, InstanceInput, LaunchRecord } from "@/types";
import { cn, formatDate, formatDuration, relativeTime } from "@/utils/format";
import { confirmDeleteInstance } from "./InstancesPage";
import { EngineIcon } from "@/components/EngineIcon";
import { glass } from "@/features/home/PlayButton";
import { Page } from "@/layouts/Page";
import { HERO_SLIDES, artFor } from "@/features/home/HeroBackdrop";
import { GrassBlock, LoaderIcon } from "@/components/GameIcons";
import { loaderName } from "@/features/home/PlayButton";

type Tab = "overview" | "settings" | "console" | "history";

function Overview({ instance, history }: { instance: Instance; history: LaunchRecord[] | null }) {
  const t = useT();
  const locale = useLocale();
  const folders: { sub?: string; label: TranslationKey }[] = [
    { label: "instances.folder.root" },
    { sub: "saves", label: "instances.folder.saves" },
    { sub: "screenshots", label: "instances.folder.screenshots" },
    { sub: "resourcepacks", label: "instances.folder.resourcepacks" },
    { sub: "mods", label: "instances.folder.mods" },
    { sub: "logs", label: "instances.folder.logs" },
  ];
  const rows: [string, React.ReactNode][] = [
    [
      t("editor.version"),
      <span className="inline-flex items-center gap-1.5">
        <GrassBlock size={14} />
        Minecraft {instance.versionId}
      </span>,
    ],
    [
      t("instances.loader"),
      <span className="inline-flex items-center gap-1.5">
        <LoaderIcon loader={instance.loader} size={14} />
        {loaderName(instance.loader)}
      </span>,
    ],
    [t("instances.created_at"), formatDate(instance.createdAt, locale)],
    [
      t("home.lastSession"),
      instance.lastPlayedAt ? relativeTime(instance.lastPlayedAt, t, locale) : t("common.never"),
    ],
    [t("instances.totalPlaytime"), formatDuration(instance.totalPlaySeconds)],
  ];
  return (
    <div className="grid grid-cols-[minmax(0,1fr)_320px] gap-5">
      <div className="space-y-5">
        <Card className="p-5">
          <CardHeader title={t("instances.info")} icon={<Info size={16} />} />
          <div className="mt-3 divide-y divide-line">
            {rows.map(([k, v]) => (
              <div key={k} className="flex justify-between gap-4 py-2.5 text-[13px]">
                <span className="text-muted">{k}</span>
                <span className="font-semibold text-text">{v}</span>
              </div>
            ))}
            <div className="flex justify-between gap-4 py-2.5 text-[13px]">
              <span className="shrink-0 text-muted">{t("instances.directory")}</span>
              <span className="truncate font-mono text-[11.5px] text-muted" data-selectable title={instance.directory}>
                {instance.directory}
              </span>
            </div>
          </div>
        </Card>
        <Card className="p-5">
          <CardHeader title={t("instances.tab.history")} icon={<History size={16} />} />
          <div className="mt-3">
            {!history ? (
              <Skeleton className="h-12 w-full" />
            ) : history.length === 0 ? (
              <p className="text-[13px] text-muted">{t("history.empty")}</p>
            ) : (
              <HistoryList records={history.slice(0, 4)} />
            )}
          </div>
        </Card>
      </div>
      <Card className="h-fit p-5">
        <CardHeader title={t("instances.folders")} icon={<FolderOpen size={16} />} />
        <div className="mt-3 space-y-1">
          {folders.map((f) => (
            <button
              key={f.label}
              type="button"
              onClick={() => void api.openInstanceFolder(instance.id, f.sub).catch(reportError)}
              className="flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left text-[13px] font-semibold text-text transition-colors hover:bg-white/[0.06]"
            >
              <FolderOpen size={15} className="text-faint" />
              {t(f.label)}
            </button>
          ))}
        </div>
      </Card>
    </div>
  );
}

function HistoryList({ records }: { records: LaunchRecord[] }) {
  const t = useT();
  const locale = useLocale();
  const [viewing, setViewing] = useState<LaunchRecord | null>(null);
  const log = useAsync(() => api.readLaunchLog(viewing!.id, 3000), [viewing?.id], !!viewing);
  return (
    <>
      <ul className="divide-y divide-line">
        {records.map((r) => (
          <li key={r.id} className="flex items-center gap-3 py-2.5">
            <div
              className={cn(
                "flex h-8 w-8 shrink-0 items-center justify-center rounded-lg",
                r.crashed ? "bg-error/12 text-error" : r.endedAt === null ? "bg-primary-soft text-primary" : "bg-white/[0.05] text-muted",
              )}
            >
              {r.crashed ? <AlertTriangle size={15} /> : r.endedAt === null ? <Gauge size={15} /> : <CheckCircle2 size={15} />}
            </div>
            <div className="min-w-0 flex-1">
              <div className="text-[13px] font-semibold text-text">{formatDate(r.startedAt, locale, true)}</div>
              <div className="text-[12px] text-faint">
                Minecraft {r.versionId}
                {r.accountName ? ` · ${r.accountName}` : ""}
                {r.endedAt !== null && ` · ${formatDuration(r.endedAt - r.startedAt)}`}
                {r.exitCode !== null && r.exitCode !== 0 && ` · ${t("history.exitCode", { code: r.exitCode })}`}
                {r.endedAt === null && ` · ${t("history.running")}`}
              </div>
            </div>
            {r.logFile && (
              <Button size="sm" variant="ghost" icon={<FileText size={14} />} onClick={() => setViewing(r)}>
                {t("history.viewLog")}
              </Button>
            )}
          </li>
        ))}
      </ul>
      <Modal
        open={!!viewing}
        onClose={() => setViewing(null)}
        width={820}
        title={viewing ? t("history.logTitle", { date: formatDate(viewing.startedAt, locale, true) }) : ""}
      >
        {log.loading ? (
          <div className="flex justify-center py-10 text-muted">
            <Spinner size={22} />
          </div>
        ) : log.error ? (
          <p className="py-4 text-[13px] text-error">{errorMessage(log.error)}</p>
        ) : (
          <pre
            className="mb-3 max-h-[60vh] overflow-auto rounded-xl bg-[#08090b] p-4 font-mono text-[11.5px] leading-relaxed text-[#b9bcc4]"
            data-selectable
          >
            {(log.data ?? []).join("\n")}
          </pre>
        )}
      </Modal>
    </>
  );
}

function SettingsTab({ instance }: { instance: Instance }) {
  const t = useT();
  const load = useInstancesStore((s) => s.load);
  const [value, setValue] = useState<InstanceInput>(inputFromInstance(instance));
  const [saving, setSaving] = useState(false);
  useEffect(() => setValue(inputFromInstance(instance)), [instance]);
  const dirty = JSON.stringify(value) !== JSON.stringify(inputFromInstance(instance));

  const save = async () => {
    setSaving(true);
    try {
      const updated = await api.updateInstance(instance.id, value);
      toast.success(t("instances.updated", { name: updated.name }));
      await load();
    } catch (e) {
      reportError(e);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="max-w-[640px]">
      <Card className="p-6">
        <InstanceForm value={value} onChange={setValue} />
      </Card>
      <AnimatePresence>
        {dirty && (
          <motion.div
            initial={{ opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: 12 }}
            className="sticky bottom-5 mt-4 flex items-center justify-between rounded-xl bg-overlay/90 px-4 py-3 shadow-xl shadow-black/40 ring-1 ring-inset ring-white/10 backdrop-blur-xl"
          >
            <span className="text-[13px] font-semibold text-text">{t("settings.unsaved")}</span>
            <div className="flex gap-2">
              <Button size="sm" variant="ghost" onClick={() => setValue(inputFromInstance(instance))}>
                {t("settings.discard")}
              </Button>
              <Button size="sm" variant="primary" loading={saving} onClick={() => void save()}>
                {t("common.save")}
              </Button>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

export function InstanceDetailPage() {
  const t = useT();
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();
  const [params, setParams] = useSearchParams();
  const instances = useInstancesStore((s) => s.instances);
  const loaded = useInstancesStore((s) => s.loaded);
  const instance = instances.find((i) => i.id === id) ?? null;
  const tab = (params.get("tab") as Tab) || "overview";
  const { session, progress, active, running, launch, stop } = useLaunch(instance);
  const art = artFor(id ?? "");
  const history = useAsync(
    async () => (await api.listLaunchHistory(200)).filter((r) => r.instanceId === id),
    [id, instance?.lastPlayedAt, session?.stage],
  );

  if (!instance) {
    return loaded ? (
      <div className="p-7">
        <EmptyState
          icon={<Info size={22} />}
          title={t("notFound.title")}
          action={<Button onClick={() => navigate("/instances")}>{t("instances.back")}</Button>}
        />
      </div>
    ) : null;
  }

  return (
    <Page art={HERO_SLIDES.indexOf(art)}>
      <button
        type="button"
        onClick={() => navigate("/instances")}
        className="mb-4 flex items-center gap-1.5 text-[12.5px] font-semibold text-muted transition-colors hover:text-text"
      >
        <ArrowLeft size={14} /> {t("instances.back")}
      </button>

      <section className="relative mb-5 overflow-hidden rounded-xl bg-surface shadow-[0_30px_60px_-30px_rgb(0_0_0/0.9)] ring-1 ring-inset ring-white/[0.08]">
        <motion.img
          src={art.src}
          alt=""
          draggable={false}
          initial={{ opacity: 0, scale: 1.12 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ opacity: { duration: 0.8 }, scale: { duration: 2.4, ease: [0.22, 1, 0.36, 1] } }}
          className="absolute inset-0 h-full w-full object-cover"
          style={{ objectPosition: art.position }}
        />
        <div className="absolute inset-0 bg-gradient-to-r from-[#0b0c10]/90 via-[#0b0c10]/55 to-[#0b0c10]/10" />
        <div className="absolute inset-x-0 bottom-0 h-3/4 bg-gradient-to-t from-[#0b0c10]/85 to-transparent" />
        <div className="absolute inset-0 rounded-xl shadow-[inset_0_1px_0_rgb(255_255_255/0.1)]" />

        <div className="relative flex flex-wrap items-end gap-x-5 gap-y-4 p-6 pt-20">
          <InstanceIcon icon={instance.icon} size={72} className="shadow-[0_10px_30px_-8px_rgb(0_0_0/0.9)]" />
          <div className="min-w-0 flex-1">
            <h1 className="truncate text-[34px] font-extrabold leading-[1.05] tracking-[-0.02em] text-text drop-shadow-[0_2px_16px_rgb(0_0_0/0.55)]">
              {instance.name}
            </h1>
            <div className="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1 text-[13px] font-semibold text-text/75 drop-shadow-[0_1px_6px_rgb(0_0_0/0.6)]">
              <EngineIcon engine={instance.engine} size={15} />
              Minecraft {instance.versionId}
              <span className="h-1 w-1 rounded-full bg-text/35" aria-hidden />
              <span className="flex items-center gap-1.5">
                <LoaderIcon loader={instance.loader} size={14} />
                {loaderName(instance.loader)}
              </span>
              {instance.totalPlaySeconds > 0 && (
                <>
                  <span className="h-1 w-1 rounded-full bg-text/35" aria-hidden />
                  <span className="flex items-center gap-1">
                    <Clock3 size={12} /> {formatDuration(instance.totalPlaySeconds)}
                  </span>
                </>
              )}
              <Badge
                tone={instance.installState === "installed" ? "primary" : "warning"}
                className="ml-1 !bg-black/45 backdrop-blur-md"
              >
                {t(`instances.state.${instance.installState}` as TranslationKey)}
              </Badge>
            </div>
          </div>
          <div className="flex items-center gap-2">
            {running ? (
              <Button
                variant="danger"
                size="lg"
                icon={<Square size={13} fill="currentColor" strokeWidth={0} />}
                onClick={() => void stop()}
                className="backdrop-blur-xl"
              >
                {t("home.stop")}
              </Button>
            ) : active ? (
              <Button variant="secondary" size="lg" icon={<X size={16} />} onClick={() => void stop()} className="backdrop-blur-xl">
                {t("home.cancel")}
              </Button>
            ) : (
              <Button
                variant="primary"
                size="lg"
                icon={<Play size={17} fill="currentColor" strokeWidth={0} />}
                onClick={() => void launch()}
                className="px-8 font-extrabold uppercase tracking-[0.1em]"
              >
                {t("home.play")}
              </Button>
            )}
            <Tooltip content={t("common.openFolder")}>
              <button
                type="button"
                aria-label={t("common.openFolder")}
                onClick={() => void api.openInstanceFolder(instance.id).catch(reportError)}
                className={cn(glass, "flex h-12 w-12 items-center justify-center rounded-xl text-text/75 transition-colors hover:bg-white/[0.13] hover:text-text")}
              >
                <FolderOpen size={18} />
              </button>
            </Tooltip>
            <Tooltip content={t("common.delete")}>
              <button
                type="button"
                aria-label={t("common.delete")}
                disabled={active}
                onClick={() => void confirmDeleteInstance(instance, t).then((ok) => ok && navigate("/instances"))}
                className={cn(glass, "flex h-12 w-12 items-center justify-center rounded-xl text-text/75 transition-colors hover:bg-error hover:text-white hover:ring-error disabled:pointer-events-none disabled:opacity-40")}
              >
                <Trash2 size={18} />
              </button>
            </Tooltip>
          </div>
        </div>
      </section>

      <AnimatePresence initial={false}>
        {active && !running && session && (
          <motion.div
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: "auto" }}
            exit={{ opacity: 0, height: 0 }}
            className="overflow-hidden"
          >
            <Card className="mb-5 p-5">
              <LaunchProgress stage={session.stage} progress={progress} message={session.message} />
            </Card>
          </motion.div>
        )}
      </AnimatePresence>

      <div className="mb-5">
        <Tabs
          value={tab}
          onChange={(v) => setParams({ tab: v }, { replace: true })}
          layoutId="instance-tabs"
          tabs={[
            { value: "overview", label: t("instances.tab.overview"), icon: <Info size={15} /> },
            { value: "settings", label: t("instances.tab.settings"), icon: <Settings2 size={15} /> },
            { value: "console", label: t("instances.tab.console"), icon: <SquareTerminal size={15} /> },
            { value: "history", label: t("instances.tab.history"), icon: <History size={15} /> },
          ]}
        />
      </div>

      {tab === "overview" && <Overview instance={instance} history={history.data} />}
      {tab === "settings" && <SettingsTab instance={instance} />}
      {tab === "console" && <GameConsole instanceId={instance.id} />}
      {tab === "history" && (
        <Card className="p-5">
          {!history.data ? (
            <Skeleton className="h-24 w-full" />
          ) : history.data.length === 0 ? (
            <EmptyState icon={<History size={22} />} title={t("history.empty")} />
          ) : (
            <HistoryList records={history.data} />
          )}
        </Card>
      )}
    </Page>
  );
}
