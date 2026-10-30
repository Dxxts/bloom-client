import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router";
import { AnimatePresence, motion } from "motion/react";
import { Check, ChevronDown, LogIn, Play, Plus, Square, X } from "lucide-react";
import { useT, type TranslationKey } from "@/i18n";
import { InstanceIcon } from "@/features/instances/InstanceIcon";
import { useInstanceEditor } from "@/features/instances/InstanceEditor";
import { progressValue } from "@/features/play/LaunchProgress";
import type { LaunchControls } from "@/hooks/useLaunch";
import { reportError } from "@/services/errors";
import { useInstancesStore } from "@/stores/instancesStore";
import type { Account, Instance } from "@/types";
import { cn } from "@/utils/format";
import { EngineIcon } from "@/components/EngineIcon";
import { Tooltip } from "@/components/ui/Tooltip";

const loaderNames: Record<string, string> = {
  vanilla: "Vanilla",
  fabric: "Fabric",
  forge: "Forge",
  neoforge: "NeoForge",
};

export const loaderName = (loader: string) => loaderNames[loader] ?? loader;

const shell = "relative flex h-14 overflow-hidden rounded-xl";
/** Frosted surface shared by the hero's secondary controls. */
export const glass = "bg-white/[0.07] ring-1 ring-inset ring-white/10 backdrop-blur-xl";
const cta =
  "bloom-cta !shadow-[inset_0_1px_0_rgb(255_255_255/0.3),0_1px_2px_rgb(0_0_0/0.4),0_10px_30px_-10px_rgb(101_217_154/0.6)] hover:!shadow-[inset_0_1px_0_rgb(255_255_255/0.35),0_1px_2px_rgb(0_0_0/0.4),0_14px_38px_-10px_rgb(101_217_154/0.75)]";

/** Live session clock (H:MM:SS) counting up from a unix timestamp in seconds. */
function Uptime({ since }: { since: number | null }) {
  const [now, setNow] = useState(() => Date.now() / 1000);
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now() / 1000), 1000);
    return () => clearInterval(id);
  }, []);
  if (since === null) return null;
  const s = Math.max(0, Math.floor(now - since));
  const pad = (n: number) => String(n).padStart(2, "0");
  return (
    <span className="pl-2 font-mono text-[13px] tabular-nums text-text/50">
      {Math.floor(s / 3600)}:{pad(Math.floor((s % 3600) / 60))}:{pad(s % 60)}
    </span>
  );
}

/** Dropdown listing every instance, opened from the Play button's caret. */
function InstanceMenu({ current, onClose }: { current: Instance | null; onClose: () => void }) {
  const t = useT();
  const instances = useInstancesStore((s) => s.instances);
  const select = useInstancesStore((s) => s.select);
  const openCreate = useInstanceEditor((s) => s.openCreate);
  const list = useRef<HTMLDivElement>(null);

  useEffect(() => {
    list.current?.querySelector<HTMLElement>('[aria-selected="true"], button')?.focus();
  }, []);

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
    e.preventDefault();
    const items = Array.from(list.current?.querySelectorAll<HTMLElement>("button") ?? []);
    const i = items.indexOf(document.activeElement as HTMLElement);
    items[(i + (e.key === "ArrowDown" ? 1 : -1) + items.length) % items.length]?.focus();
  };

  return (
    <motion.div
      ref={list}
      role="listbox"
      aria-label={t("home.switchInstance")}
      onKeyDown={onKeyDown}
      initial={{ opacity: 0, y: -6, scale: 0.98 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      exit={{ opacity: 0, y: -6, scale: 0.98 }}
      transition={{ duration: 0.14, ease: "easeOut" }}
      className="absolute left-0 top-full z-40 mt-2 w-[320px] origin-top-left overflow-hidden rounded-xl border border-line-strong bg-overlay p-1.5 shadow-2xl shadow-black/60"
    >
      <div className="px-2.5 pb-1.5 pt-1 text-[11px] font-bold uppercase tracking-[0.1em] text-faint">
        {t("home.switchInstance")}
      </div>
      <div className="max-h-[260px] overflow-y-auto">
        {instances.map((i) => {
          const selected = i.id === current?.id;
          return (
            <button
              key={i.id}
              type="button"
              role="option"
              aria-selected={selected}
              onClick={() => {
                void select(i.id).catch(reportError);
                onClose();
              }}
              className={cn(
                "flex w-full items-center gap-3 rounded-lg px-2.5 py-2 text-left outline-none transition-colors hover:bg-surface-hover focus-visible:bg-surface-hover",
                selected && "bg-white/[0.03]",
              )}
            >
              <InstanceIcon icon={i.icon} size={34} />
              <div className="min-w-0 flex-1">
                <div className="truncate text-[13.5px] font-bold text-text">{i.name}</div>
                <div className="flex items-center gap-1.5 truncate text-[12px] text-muted">
                  <EngineIcon engine={i.engine} size={13} />
                  {i.versionId} · {loaderName(i.loader)}
                </div>
              </div>
              {selected ? (
                <Check size={15} className="shrink-0 text-primary" />
              ) : (
                i.installState !== "installed" && (
                  <span className="shrink-0 text-[11px] font-semibold text-warning">
                    {t(`instances.state.${i.installState}` as TranslationKey)}
                  </span>
                )
              )}
            </button>
          );
        })}
      </div>
      <div className="mx-1 my-1.5 h-px bg-line" />
      <button
        type="button"
        onClick={() => {
          onClose();
          openCreate();
        }}
        className="flex w-full items-center gap-3 rounded-lg px-2.5 py-2 text-left text-[13px] font-semibold text-muted outline-none transition-colors hover:bg-surface-hover hover:text-text focus-visible:bg-surface-hover"
      >
        <span className="flex h-[34px] w-[34px] items-center justify-center rounded-[10px] border border-dashed border-line-strong">
          <Plus size={16} />
        </span>
        {t("home.newInstance")}
      </button>
    </motion.div>
  );
}

/**
 * The launcher's main call to action. A split button: the large left side
 * launches (or signs in / creates an instance), the caret switches instance.
 */
export function PlayButton({
  instance,
  account,
  controls,
}: {
  instance: Instance | null;
  account: Account | null;
  controls: LaunchControls;
}) {
  const t = useT();
  const navigate = useNavigate();
  const openCreate = useInstanceEditor((s) => s.openCreate);
  const [menu, setMenu] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const { session, progress, active, running, launch, stop } = controls;
  const preparing = active && !running;

  useEffect(() => {
    if (!menu) return;
    const onDown = (e: MouseEvent) => {
      if (!root.current?.contains(e.target as Node)) setMenu(false);
    };
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setMenu(false);
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [menu]);

  const caret = (
    <button
      type="button"
      aria-haspopup="listbox"
      aria-expanded={menu}
      aria-label={t("home.switchInstance")}
      title={t("home.switchInstance")}
      onClick={() => setMenu((m) => !m)}
      className="relative flex w-12 shrink-0 items-center justify-center border-l border-black/20 shadow-[inset_1px_0_0_rgb(255_255_255/0.18)] transition-colors hover:bg-black/10"
    >
      <ChevronDown size={18} strokeWidth={2.6} className={cn("transition-transform duration-200", menu && "rotate-180")} />
    </button>
  );

  let body: React.ReactNode;

  if (!instance) {
    body = (
      <motion.button
        type="button"
        whileHover={{ y: -1 }}
        whileTap={{ scale: 0.98 }}
        onClick={() => openCreate()}
        className={cn(shell, cta, "items-center gap-3 px-8")}
      >
        <Plus size={20} strokeWidth={2.6} />
        <span className="text-[16px] font-extrabold uppercase tracking-[0.1em]">{t("home.createInstance")}</span>
      </motion.button>
    );
  } else if (running) {
    body = (
      <div className="flex gap-2">
        <div className={cn(shell, glass, "items-center gap-3 px-5")}>
          <span className="relative flex h-2 w-2 shrink-0">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-primary opacity-60" />
            <span className="relative inline-flex h-2 w-2 rounded-full bg-primary" />
          </span>
          <span className="text-[15px] font-bold text-text">{t("home.running")}</span>
          <Uptime since={session?.startedAt ?? null} />
        </div>
        <Tooltip content={t("home.stop")}>
          <button
            type="button"
            aria-label={t("home.stop")}
            onClick={() => void stop()}
            className="flex h-14 w-14 items-center justify-center rounded-xl bg-error/15 text-error ring-1 ring-inset ring-error/30 backdrop-blur-xl transition-[background-color,color,transform,translate,scale] duration-150 hover:bg-error hover:text-white active:scale-95"
          >
            <Square size={15} fill="currentColor" strokeWidth={0} />
          </button>
        </Tooltip>
      </div>
    );
  } else if (preparing && session) {
    const { determinate, value } = progressValue(session.stage, progress);
    body = (
      <div className={cn(shell, glass, "w-[320px] text-text")}>
        <motion.div
          className="absolute inset-y-0 left-0 bg-primary/20"
          initial={false}
          animate={{ width: determinate ? `${value}%` : "100%" }}
          transition={{ ease: "easeOut", duration: 0.3 }}
        />
        {!determinate && (
          <div className="absolute inset-y-0 left-0 w-1/3 animate-shimmer bg-gradient-to-r from-transparent via-primary/25 to-transparent" />
        )}
        <div className="relative flex min-w-0 flex-1 items-center gap-3 px-5">
          <div className="min-w-0 flex-1 text-left">
            <div className="truncate text-[10.5px] font-bold uppercase tracking-[0.1em] text-primary">
              {t("home.preparingLabel")}
            </div>
            <div className="truncate text-[14.5px] font-bold">{t(`stage.${session.stage}` as TranslationKey)}</div>
          </div>
          {determinate && (
            <span className="font-mono text-[15px] font-semibold tabular-nums text-text">{Math.floor(value)}%</span>
          )}
        </div>
        <button
          type="button"
          aria-label={t("home.cancel")}
          title={t("home.cancel")}
          onClick={() => void stop()}
          className="relative flex w-12 shrink-0 items-center justify-center border-l border-white/10 text-muted transition-colors hover:bg-white/5 hover:text-text"
        >
          <X size={18} />
        </button>
      </div>
    );
  } else {
    // Development builds can play offline (BloomDev) without signing in.
    const needsAccount = !account && !import.meta.env.DEV;
    // Launching installs or repairs files itself, so this is a hint, not a warning.
    const hint =
      instance.installState === "installed"
        ? null
        : t(instance.installState === "incomplete" ? "home.repairOnLaunch" : "home.downloadOnLaunch");
    body = (
      <motion.div
        whileHover={{ y: -1 }}
        transition={{ type: "spring", stiffness: 500, damping: 30 }}
        className={cn(shell, cta, "group")}
      >
        <span
          aria-hidden
          className="pointer-events-none absolute inset-y-0 left-0 w-1/3 animate-sheen bg-gradient-to-r from-transparent via-white/20 to-transparent"
        />
        <motion.button
          type="button"
          whileTap={{ scale: 0.97 }}
          onClick={() => (needsAccount ? navigate("/accounts") : void launch())}
          className="relative flex min-w-[210px] flex-1 items-center justify-center gap-3 pl-7 pr-8"
        >
          {needsAccount ? (
            <LogIn size={21} strokeWidth={2.6} />
          ) : (
            <Play
              size={21}
              fill="currentColor"
              strokeWidth={0}
              className="transition-transform duration-200 group-hover:scale-110"
            />
          )}
          <span className="min-w-0 text-left">
            <span className="block text-[19px] font-extrabold uppercase leading-none tracking-[0.12em]">
              {needsAccount ? t("home.signInToPlay") : t("home.play")}
            </span>
            {hint && <span className="mt-1 block truncate text-[11.5px] font-semibold text-white/80">{hint}</span>}
          </span>
        </motion.button>
        {caret}
      </motion.div>
    );
  }

  const canSwitch = !!instance && !active;
  return (
    <div ref={root} className="relative">
      {body}
      <AnimatePresence>{menu && canSwitch && <InstanceMenu current={instance} onClose={() => setMenu(false)} />}</AnimatePresence>
    </div>
  );
}
