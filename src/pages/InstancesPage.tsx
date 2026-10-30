import { useNavigate } from "react-router";
import { motion } from "motion/react";
import { Boxes, CalendarClock, Clock3, Copy, FolderOpen, Play, Plus, Settings2, Square, Trash2 } from "lucide-react";
import { useLocale, useT, type TranslationKey } from "@/i18n";
import { Button } from "@/components/ui/Button";
import { staggerItem } from "@/components/ui/Reveal";
import { Badge, Card, PageHeader, Skeleton } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/Feedback";
import { IconButton } from "@/components/ui/IconButton";
import { InstanceIcon } from "@/features/instances/InstanceIcon";
import { useInstanceEditor } from "@/features/instances/InstanceEditor";
import { useLaunch } from "@/hooks/useLaunch";
import { api } from "@/services/api";
import { reportError } from "@/services/errors";
import { useInstancesStore } from "@/stores/instancesStore";
import { toast, useUiStore } from "@/stores/uiStore";
import type { Instance } from "@/types";
import { cn, formatDuration, relativeTime } from "@/utils/format";
import { artFor } from "@/features/home/HeroBackdrop";
import { EngineIcon } from "@/components/EngineIcon";
import { Page } from "@/layouts/Page";
import { LoaderIcon } from "@/components/GameIcons";
import { loaderName } from "@/features/home/PlayButton";

export async function confirmDeleteInstance(instance: Instance, t: ReturnType<typeof useT>): Promise<boolean> {
  const ok = await useUiStore.getState().confirm({
    title: t("instances.deleteTitle"),
    message: t("instances.deleteConfirm", { name: instance.name }),
    confirmLabel: t("common.delete"),
    cancelLabel: t("common.cancel"),
    danger: true,
  });
  if (!ok) return false;
  try {
    await api.deleteInstance(instance.id);
    toast.success(t("instances.deleted", { name: instance.name }));
    await useInstancesStore.getState().load();
    return true;
  } catch (e) {
    reportError(e);
    return false;
  }
}

function InstanceCard({ instance, index }: { instance: Instance; index: number }) {
  const t = useT();
  const locale = useLocale();
  const navigate = useNavigate();
  const openEdit = useInstanceEditor((s) => s.openEdit);
  const load = useInstancesStore((s) => s.load);
  const { active, running, launch, stop, session } = useLaunch(instance);
  const art = artFor(instance.id);

  const duplicate = async () => {
    try {
      const copy = await api.duplicateInstance(instance.id);
      toast.success(t("instances.duplicated", { name: copy.name }));
      await load();
    } catch (e) {
      reportError(e);
    }
  };

  return (
    <motion.div layout {...staggerItem(index)} exit={{ opacity: 0, scale: 0.97 }}>
      <Card className="group flex h-full flex-col overflow-hidden hover:ring-white/[0.12] transition-[translate,box-shadow] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] hover:-translate-y-1 hover:shadow-[inset_0_1px_0_rgb(255_255_255/0.06),0_24px_40px_-20px_rgb(0_0_0/0.95)]">
        <button
          type="button"
          onClick={() => navigate(`/instances/${instance.id}`)}
          className="relative block h-[112px] w-full overflow-hidden text-left [mask-image:linear-gradient(to_bottom,black_40%,transparent)]"
          aria-label={instance.name}
        >
          <img
            src={art.src}
            alt=""
            draggable={false}
            className="absolute inset-0 h-full w-full object-cover transition-[scale] duration-700 ease-[cubic-bezier(0.22,1,0.36,1)] group-hover:scale-[1.06]"
            style={{ objectPosition: art.position }}
          />
          <div className="absolute inset-0 bg-black/35" />
          <div className="absolute right-3 top-3">
            {active ? (
              <Badge tone="primary" className="!bg-black/55 backdrop-blur-md">
                {t(`stage.${session!.stage}` as TranslationKey)}
              </Badge>
            ) : (
              <Badge
                tone={instance.installState === "installed" ? "neutral" : "warning"}
                className={cn("backdrop-blur-md", instance.installState === "installed" ? "!bg-black/45 text-text/85" : "!bg-black/55")}
              >
                {t(`instances.state.${instance.installState}` as TranslationKey)}
              </Badge>
            )}
          </div>
        </button>

        <div className="relative -mt-12 flex flex-1 flex-col px-5 pb-5">
          <button
            type="button"
            onClick={() => navigate(`/instances/${instance.id}`)}
            className="flex items-end gap-3.5 text-left"
          >
            <InstanceIcon icon={instance.icon} size={56} className="shadow-[0_8px_20px_-6px_rgb(0_0_0/0.8)]" />
            <div className="min-w-0 flex-1 pb-0.5">
              <div className="truncate text-[17px] font-extrabold tracking-tight text-text">{instance.name}</div>
              <div className="mt-0.5 flex items-center gap-1.5 text-[12.5px] font-medium text-muted">
                <EngineIcon engine={instance.engine} size={14} />
                Minecraft {instance.versionId}
                <span className="h-0.5 w-0.5 rounded-full bg-faint" aria-hidden />
                <LoaderIcon loader={instance.loader} size={13} />
                {loaderName(instance.loader)}
              </div>
            </div>
          </button>

          <div className="mt-3.5 flex flex-wrap gap-x-3 gap-y-1 text-[12px] font-medium text-faint">
            <span className="flex items-center gap-1.5">
              <CalendarClock size={13} />
              {instance.lastPlayedAt
                ? t("instances.lastPlayed", { time: relativeTime(instance.lastPlayedAt, t, locale) })
                : t("instances.neverPlayed")}
            </span>
            {instance.totalPlaySeconds > 0 && (
              <span className="flex items-center gap-1.5">
                <Clock3 size={13} />
                {t("instances.playtime", { duration: formatDuration(instance.totalPlaySeconds) })}
              </span>
            )}
          </div>

          <div className="mt-auto flex items-center gap-1 pt-4">
            {running ? (
              <Button variant="danger" size="sm" icon={<Square size={12} fill="currentColor" strokeWidth={0} />} onClick={() => void stop()} className="px-4">
                {t("common.stop")}
              </Button>
            ) : (
              <Button
                variant="primary"
                size="sm"
                icon={<Play size={13} fill="currentColor" strokeWidth={0} />}
                onClick={() => void launch()}
                disabled={active}
                loading={active}
                className="px-4 font-extrabold uppercase tracking-[0.08em]"
              >
                {t("home.play")}
              </Button>
            )}
            <div className="ml-auto flex gap-0.5 opacity-70 transition-opacity group-hover:opacity-100">
              <IconButton size="sm" label={t("common.edit")} icon={<Settings2 size={15} />} onClick={() => openEdit(instance)} />
              <IconButton size="sm" label={t("common.duplicate")} icon={<Copy size={15} />} onClick={() => void duplicate()} disabled={active} />
              <IconButton
                size="sm"
                label={t("common.openFolder")}
                icon={<FolderOpen size={15} />}
                onClick={() => void api.openInstanceFolder(instance.id).catch(reportError)}
              />
              <IconButton
                size="sm"
                tone="danger"
                label={t("common.delete")}
                icon={<Trash2 size={15} />}
                onClick={() => void confirmDeleteInstance(instance, t)}
                disabled={active}
              />
            </div>
          </div>
        </div>
      </Card>
    </motion.div>
  );
}

export function InstancesPage() {
  const t = useT();
  const instances = useInstancesStore((s) => s.instances);
  const loaded = useInstancesStore((s) => s.loaded);
  const openCreate = useInstanceEditor((s) => s.openCreate);

  return (
    <Page art={0}>
      <PageHeader
        title={t("instances.title")}
        subtitle={t("instances.subtitle")}
        actions={
          <Button variant="primary" icon={<Plus size={16} />} onClick={() => openCreate()}>
            {t("instances.new")}
          </Button>
        }
      />
      {!loaded ? (
        <div className="grid grid-cols-1 gap-4 @[640px]:grid-cols-2 @[1100px]:grid-cols-3">
          {Array.from({ length: 3 }, (_, i) => (
            <Skeleton key={i} className="h-[230px] rounded-xl" />
          ))}
        </div>
      ) : instances.length === 0 ? (
        <Card>
          <EmptyState
            icon={<Boxes size={24} />}
            title={t("instances.emptyTitle")}
            body={t("instances.emptyBody")}
            action={
              <Button variant="primary" icon={<Plus size={16} />} onClick={() => openCreate()}>
                {t("instances.new")}
              </Button>
            }
          />
        </Card>
      ) : (
        <div className="grid grid-cols-1 gap-4 @[640px]:grid-cols-2 @[1100px]:grid-cols-3">
          {instances.map((i, n) => (
            <InstanceCard key={i.id} instance={i} index={n} />
          ))}
        </div>
      )}
    </Page>
  );
}
