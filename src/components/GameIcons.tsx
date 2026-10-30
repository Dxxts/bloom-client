import { Anvil, FlaskConical, Hourglass, Scroll } from "lucide-react";
import { cn } from "@/utils/format";

/** Isometric grass block, drawn on a 16-unit pixel grid. */
export function GrassBlock({ size = 16, className }: { size?: number; className?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" className={cn("shrink-0", className)} aria-hidden="true">
      <path d="M1 4.5 8 8v7l-7-3.5Z" fill="#8a5a33" />
      <path d="M8 8l7-3.5v7L8 15Z" fill="#6b4426" />
      <path d="M2.6 9.8h1.2v1.2H2.6zM5.2 11.6h1.2v1.2H5.2zM10.6 11.8h1.2V13h-1.2zM12.8 9.6H14v1.2h-1.2z" fill="#5a381e" />
      <path d="M1 4.5 8 8v2.2l-1.8-.9v1l-1.8-.9V8.2l-1.8-.9v1L1 7.5Z" fill="#5fae3e" />
      <path d="M8 8l7-3.5v3l-1.6.8v-1l-1.8.9v1.2l-1.8.9v-1L8 10.2Z" fill="#4c9632" />
      <path d="M8 1l7 3.5L8 8 1 4.5Z" fill="#79c950" />
      <path d="M5.4 3.2 8 1.9l1.3.65L6.7 3.85ZM9.6 5.3l1.3-.65 1.3.65-1.3.65Z" fill="#92d965" />
    </svg>
  );
}

/** Microsoft's four-square mark, used next to "Sign in with Microsoft" actions. */
export function MicrosoftLogo({ size = 16, className }: { size?: number; className?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 23 23" className={cn("shrink-0", className)} aria-hidden="true">
      <path fill="#F25022" d="M1 1h10v10H1z" />
      <path fill="#7FBA00" d="M12 1h10v10H12z" />
      <path fill="#00A4EF" d="M1 12h10v10H1z" />
      <path fill="#FFB900" d="M12 12h10v10H12z" />
    </svg>
  );
}

// Steve's 8x8 face: H/h hair, S/s skin, N nose, M mouth, W eye white, E iris.
const STEVE_FACE = ["HhHHhHHh", "hHHhHHhH", "HsSSSSsH", "SSSSSSSS", "SWESSEWS", "SSsNNsSS", "SsMMMMsS", "SsMssMsS"];
const STEVE_COLORS: Record<string, string> = {
  H: "#2f200d",
  h: "#3b2a14",
  S: "#c09276",
  s: "#b07f66",
  N: "#9a654f",
  M: "#6a4030",
  W: "#ffffff",
  E: "#523d89",
};

/** Steve, Minecraft's default skin: shown whenever there is no skin to draw. */
export function SteveHead({ size = 36, className }: { size?: number; className?: string }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 8 8"
      shapeRendering="crispEdges"
      className={cn("shrink-0", className)}
      style={{ borderRadius: Math.round(size * 0.24) }}
      aria-hidden="true"
    >
      {STEVE_FACE.flatMap((row, y) =>
        [...row].map((c, x) => <rect key={`${x}-${y}`} x={x} y={y} width="1" height="1" fill={STEVE_COLORS[c]} />),
      )}
    </svg>
  );
}

const LOADER_ICONS: Record<string, { Icon: typeof Anvil; color: string }> = {
  fabric: { Icon: Scroll, color: "#d8cba8" },
  forge: { Icon: Anvil, color: "#9fb0c8" },
  neoforge: { Icon: Anvil, color: "#e2873f" },
};

/** Grass block for vanilla, a tinted glyph for each mod loader. */
export function LoaderIcon({ loader, size = 16, className }: { loader: string; size?: number; className?: string }) {
  const entry = LOADER_ICONS[loader];
  if (!entry) return <GrassBlock size={size} className={className} />;
  const { Icon, color } = entry;
  return <Icon size={size} strokeWidth={2.1} color={color} className={cn("shrink-0", className)} aria-hidden />;
}

/** Releases are grass blocks; snapshots are experiments; beta/alpha are history. */
export function VersionTypeIcon({ type, size = 16, className }: { type: string; size?: number; className?: string }) {
  if (type === "release") return <GrassBlock size={size} className={className} />;
  if (type === "snapshot")
    return <FlaskConical size={size} strokeWidth={2} className={cn("shrink-0 text-warning", className)} aria-hidden />;
  return <Hourglass size={size} strokeWidth={2} className={cn("shrink-0 text-muted", className)} aria-hidden />;
}
