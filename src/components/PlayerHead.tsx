import { useEffect } from "react";
import { useAccountsStore } from "@/stores/accountsStore";
import { cn } from "@/utils/format";
import { SteveHead } from "./GameIcons";

/**
 * Renders the face of a Minecraft skin (base layer + hat layer) straight
 * from the skin texture with CSS, so no image processing is needed.
 */
export function PlayerHead({
  uuid,
  size = 36,
  className,
}: {
  uuid: string | null;
  /** Kept for callers; the head itself is decorative. */
  name: string | null;
  size?: number;
  className?: string;
}) {
  const skin = useAccountsStore((s) => (uuid ? s.skins[uuid] : undefined));
  const loadSkin = useAccountsStore((s) => s.loadSkin);

  useEffect(() => {
    if (uuid) void loadSkin(uuid);
  }, [uuid, loadSkin]);

  const radius = Math.round(size * 0.24);
  // No skin (signed out, offline, or not loaded yet): Minecraft's default, Steve.
  if (!uuid || !skin) return <SteveHead size={size} className={className} />;

  const scale = size / 8;
  const layer = (x: number, y: number, inset = 0) => ({
    position: "absolute" as const,
    inset,
    backgroundImage: `url(${skin})`,
    backgroundSize: `${64 * scale}px auto`,
    backgroundPosition: `-${x * scale}px -${y * scale}px`,
    imageRendering: "pixelated" as const,
  });

  return (
    <div
      className={cn("relative shrink-0 overflow-hidden bg-surface-hover", className)}
      style={{ width: size, height: size, borderRadius: radius }}
      aria-hidden
    >
      <div style={layer(8, 8)} />
      <div style={layer(40, 8)} />
    </div>
  );
}
