import { useEffect, useState } from "react";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { Copy, Minus, Square, X } from "lucide-react";
import { useNavigate } from "react-router";
import { useT } from "@/i18n";
import { useInstancesStore } from "@/stores/instancesStore";
import { isActive, useSessionsStore } from "@/stores/sessionsStore";
import { cn } from "@/utils/format";
import { BloomMark } from "./BloomMark";

function WindowButton({
  label,
  onClick,
  children,
  danger,
}: {
  label: string;
  onClick: () => void;
  children: React.ReactNode;
  danger?: boolean;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={onClick}
      className={cn(
        "flex h-10 w-[46px] items-center justify-center text-muted transition-colors duration-100",
        danger ? "hover:bg-[#c42b1c] hover:text-white" : "hover:bg-white/[0.06] hover:text-text",
      )}
    >
      {children}
    </button>
  );
}

/** Custom title bar: drag region, live game status and window controls. */
export function Titlebar() {
  const t = useT();
  const navigate = useNavigate();
  const [maximized, setMaximized] = useState(false);
  const sessions = useSessionsStore((s) => s.sessions);
  const instances = useInstancesStore((s) => s.instances);

  useEffect(() => {
    const win = getCurrentWindow();
    let unlisten: (() => void) | undefined;
    void win.isMaximized().then(setMaximized);
    void win
      .onResized(() => {
        void win.isMaximized().then(setMaximized);
      })
      .then((u) => (unlisten = u));
    return () => unlisten?.();
  }, []);

  const active = Object.values(sessions).filter(isActive);
  const first = active[0];
  const firstName = first ? (instances.find((i) => i.id === first.instanceId)?.name ?? "Minecraft") : "";
  const win = getCurrentWindow();

  return (
    <div
      data-tauri-drag-region
      onDoubleClick={(e) => {
        if ((e.target as HTMLElement).hasAttribute("data-tauri-drag-region")) void win.toggleMaximize();
      }}
      className="relative z-30 flex h-10 shrink-0 items-center bg-bg pl-[22px]"
    >
      <div data-tauri-drag-region className="flex items-center gap-2.5">
        <BloomMark size={18} />
        <span data-tauri-drag-region className="text-[13px] font-bold tracking-tight text-text">
          Bloom <span className="font-semibold text-muted">Client</span>
        </span>
      </div>

      <div data-tauri-drag-region className="flex flex-1 justify-center">
        {first && (
          <button
            type="button"
            onClick={() => navigate(`/instances/${first.instanceId}?tab=console`)}
            className="flex h-7 items-center gap-2 rounded-full bg-primary-soft px-3 text-[12px] font-bold text-primary ring-1 ring-primary/25 transition-shadow hover:ring-primary/50"
          >
            <span className="relative flex h-2 w-2">
              <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-primary opacity-60" />
              <span className="relative inline-flex h-2 w-2 rounded-full bg-primary" />
            </span>
            {first.stage === "running"
              ? t("titlebar.gameRunning", { name: firstName })
              : t("titlebar.preparing", { name: firstName })}
            {active.length > 1 && <span className="text-primary/70">+{active.length - 1}</span>}
          </button>
        )}
      </div>

      <div className="flex">
        <WindowButton label={t("titlebar.minimize")} onClick={() => void win.minimize()}>
          <Minus size={16} strokeWidth={1.6} />
        </WindowButton>
        <WindowButton
          label={maximized ? t("titlebar.restore") : t("titlebar.maximize")}
          onClick={() => void win.toggleMaximize()}
        >
          {maximized ? <Copy size={13} strokeWidth={1.6} className="-scale-x-100" /> : <Square size={13} strokeWidth={1.6} />}
        </WindowButton>
        <WindowButton label={t("titlebar.close")} onClick={() => void win.close()} danger>
          <X size={17} strokeWidth={1.6} />
        </WindowButton>
      </div>
    </div>
  );
}
