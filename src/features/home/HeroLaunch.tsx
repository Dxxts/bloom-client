import type { ReactNode } from "react";
import { useNavigate } from "react-router";
import { AnimatePresence, motion } from "motion/react";
import { Clock3, FolderOpen, MemoryStick, Settings2, SquareTerminal } from "lucide-react";
import { GrassBlock, LoaderIcon } from "@/components/GameIcons";
import { useT } from "@/i18n";
import { Tooltip } from "@/components/ui/Tooltip";
import { useInstanceEditor } from "@/features/instances/InstanceEditor";
import { useLaunch } from "@/hooks/useLaunch";
import { api } from "@/services/api";
import { reportError } from "@/services/errors";
import { selectActiveAccount, useAccountsStore } from "@/stores/accountsStore";
import { useAppStore } from "@/stores/appStore";
import { useInstancesStore, useSelectedInstance } from "@/stores/instancesStore";
import { cn, formatBytes, formatDuration, formatMemory } from "@/utils/format";
import { HeroBackdrop, SlideDots } from "./HeroBackdrop";
import { PlayButton, glass, loaderName } from "./PlayButton";

function HeroAction({ label, icon, onClick }: { label: string; icon: ReactNode; onClick: () => void }) {
  return (
    <Tooltip content={label}>
      <button
        type="button"
        aria-label={label}
        onClick={onClick}
        className={cn(
          glass,
          "flex h-14 w-14 items-center justify-center rounded-xl text-text/75 transition-[background-color,color,transform,translate,scale] duration-150 hover:bg-white/[0.13] hover:text-text active:scale-95",
        )}
      >
        {icon}
      </button>
    </Tooltip>
  );
}

/** Cinematic launch area: artwork, selected instance and the Play button. */
export function HeroLaunch({
  slide,
  onSlide,
  onHover,
}: {
  slide: number;
  onSlide: (i: number) => void;
  onHover: (hovering: boolean) => void;
}) {
  const t = useT();
  const navigate = useNavigate();
  const loaded = useInstancesStore((s) => s.loaded);
  const instance = useSelectedInstance();
  const account = useAccountsStore(selectActiveAccount);
  const settings = useAppStore((s) => s.settings);
  const openEdit = useInstanceEditor((s) => s.openEdit);
  const controls = useLaunch(instance);
  const { session, progress, active, running } = controls;
  const preparing = active && !running;

  const maxRam = instance?.maxRamMb ?? settings?.maxRamMb;
  // Avoid repeating the version when the instance is named after it.
  const meta: [string, ReactNode][] = instance
    ? (
        [
          [
            instance.name === instance.versionId
              ? "Minecraft: Java Edition"
              : t("home.versionLabel", { version: instance.versionId }),
            <GrassBlock size={15} />,
          ],
          [
            `${loaderName(instance.loader)}${instance.loaderVersion ? ` ${instance.loaderVersion}` : ""}`,
            <LoaderIcon loader={instance.loader} size={15} />,
          ],
          maxRam !== undefined
            ? [t("home.memoryChip", { value: formatMemory(maxRam) }), <MemoryStick size={14} className="text-text/55" />]
            : null,
          instance.totalPlaySeconds > 0
            ? [
                t("home.totalPlaytime", { duration: formatDuration(instance.totalPlaySeconds) }),
                <Clock3 size={14} className="text-text/55" />,
              ]
            : null,
        ] as ([string, ReactNode] | null)[]
      ).filter((m): m is [string, ReactNode] => !!m)
    : [];
  const p = progress && session && progress.stage === session.stage ? progress : null;

  return (
    <section
      onPointerEnter={() => onHover(true)}
      onPointerLeave={() => onHover(false)}
      className="relative rounded-xl"
    >
      <div className="absolute inset-0 overflow-hidden rounded-xl bg-surface shadow-[0_30px_60px_-30px_rgb(0_0_0/0.9)] ring-1 ring-white/[0.08]">
        <HeroBackdrop index={slide} />
        <div className="absolute inset-0 bg-gradient-to-r from-[#0b0c10]/90 via-[#0b0c10]/50 to-[#0b0c10]/0" />
        <div className="absolute inset-x-0 bottom-0 h-3/4 bg-gradient-to-t from-[#0b0c10]/90 via-[#0b0c10]/35 to-transparent" />
        <div className="absolute inset-x-0 top-0 h-24 bg-gradient-to-b from-[#0b0c10]/45 to-transparent" />
        <div className="absolute inset-0 rounded-xl shadow-[inset_0_1px_0_rgb(255_255_255/0.1)]" />
      </div>
      <div className="absolute right-5 top-5 z-10 rounded-full bg-black/30 px-2 py-0.5 backdrop-blur-md">
        <SlideDots index={slide} onSelect={onSlide} label="Artwork" />
      </div>

      <div className="relative flex min-h-[360px] flex-col justify-between gap-8 p-7 @[900px]:min-h-[420px]">
        {instance ? (
          <motion.div
            key={instance.id}
            initial={{ opacity: 0, x: -8 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{ duration: 0.25, ease: "easeOut" }}
            className="max-w-[620px]"
          >
            <h1 className="line-clamp-1 break-all text-[36px] font-extrabold leading-[1.05] tracking-[-0.02em] text-text drop-shadow-[0_2px_16px_rgb(0_0_0/0.55)] @[700px]:text-[44px]">
              {instance.name}
            </h1>
            <p className="mt-2.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-[13.5px] font-semibold text-text/75 drop-shadow-[0_1px_6px_rgb(0_0_0/0.6)]">
              {meta.map(([m, icon], i) => (
                <span key={m} className="flex items-center gap-2">
                  {i > 0 && <span className="h-1 w-1 rounded-full bg-text/35" aria-hidden />}
                  <span className="flex items-center gap-1.5">
                    {icon}
                    {m}
                  </span>
                </span>
              ))}
            </p>
          </motion.div>
        ) : (
          <div className="max-w-[460px]">
            <h1 className="text-[34px] font-extrabold leading-[1.1] tracking-tight text-text">{t("home.tagline")}</h1>
            {loaded && <p className="mt-3 text-[14px] text-text/65">{t("home.noInstances")}</p>}
          </div>
        )}

        <div className="space-y-3">
          <div className="flex flex-wrap items-center gap-2">
            <PlayButton instance={instance} account={account} controls={controls} />
            {instance && !preparing && (
              <div className="flex gap-2">
                <HeroAction
                  label={t("home.instanceSettings")}
                  icon={<Settings2 size={19} />}
                  onClick={() => openEdit(instance)}
                />
                <HeroAction
                  label={t("common.openFolder")}
                  icon={<FolderOpen size={19} />}
                  onClick={() => void api.openInstanceFolder(instance.id).catch(reportError)}
                />
                {session && (
                  <HeroAction
                    label={t("home.viewConsole")}
                    icon={<SquareTerminal size={19} />}
                    onClick={() => navigate(`/instances/${instance.id}?tab=console`)}
                  />
                )}
              </div>
            )}
          </div>

          <AnimatePresence initial={false}>
            {preparing && p && (
              <motion.div
                initial={{ opacity: 0, y: -4 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0 }}
                className="flex max-w-[560px] flex-wrap items-center gap-x-4 gap-y-1 font-mono text-[11.5px] text-text/60"
              >
                {p.currentFile && <span className="min-w-0 max-w-[260px] truncate">{p.currentFile}</span>}
                {p.filesTotal > 0 && (
                  <span className="tabular-nums">{t("progress.files", { done: p.filesDone, total: p.filesTotal })}</span>
                )}
                {p.stage === "downloading" && p.bytesTotal > 0 && (
                  <span className="tabular-nums">
                    {t("progress.bytes", { done: formatBytes(p.bytesDone), total: formatBytes(p.bytesTotal) })}
                  </span>
                )}
                {p.stage === "downloading" && p.speedBps > 0 && (
                  <span className="tabular-nums text-primary/80">{t("progress.speed", { speed: formatBytes(p.speedBps) })}</span>
                )}
              </motion.div>
            )}
          </AnimatePresence>
        </div>
      </div>
    </section>
  );
}
