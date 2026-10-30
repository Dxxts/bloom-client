import {
  Castle,
  Compass,
  Flame,
  Flower2,
  Gem,
  Leaf,
  Moon,
  Mountain,
  Pickaxe,
  Shield,
  Snowflake,
  Sprout,
  Star,
  Sword,
  TreePine,
  Zap,
  type LucideIcon,
} from "lucide-react";
import { cn } from "@/utils/format";

// Keys must match `ICONS` in src-tauri/src/instances/mod.rs.
export const INSTANCE_ICONS: Record<string, LucideIcon> = {
  sprout: Sprout,
  flower: Flower2,
  leaf: Leaf,
  trees: TreePine,
  mountain: Mountain,
  pickaxe: Pickaxe,
  sword: Sword,
  shield: Shield,
  gem: Gem,
  flame: Flame,
  snowflake: Snowflake,
  zap: Zap,
  compass: Compass,
  castle: Castle,
  star: Star,
  moon: Moon,
};

export function InstanceIcon({ icon, size = 44, className }: { icon: string; size?: number; className?: string }) {
  const Icon = INSTANCE_ICONS[icon] ?? Sprout;
  return (
    <div
      className={cn(
        "flex shrink-0 items-center justify-center border border-white/[0.06] bg-gradient-to-br from-surface-active to-surface-hover text-text",
        className,
      )}
      style={{ width: size, height: size, borderRadius: Math.round(size * 0.28) }}
      aria-hidden
    >
      <Icon size={Math.round(size * 0.46)} strokeWidth={1.9} />
    </div>
  );
}
