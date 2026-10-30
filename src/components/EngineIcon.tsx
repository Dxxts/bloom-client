import type { EngineKind } from "@/types";
import { cn } from "@/utils/format";

const TEETH = Array.from({ length: 12 }, (_, i) => i * 30);

/** Gear with an "R": the Rust engine (Bloom Native World Engine). */
function RustIcon({ size }: { size: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <g fill="#DE7A4B">
        {TEETH.map((deg) => (
          <rect key={deg} x="10.9" y="1.6" width="2.2" height="3.4" rx="0.5" transform={`rotate(${deg} 12 12)`} />
        ))}
      </g>
      <circle cx="12" cy="12" r="7.6" stroke="#DE7A4B" strokeWidth="2" />
      <path
        d="M9.3 16.2V7.9h3.6a2.35 2.35 0 0 1 0 4.7H9.3m3.2 0 2.5 3.6"
        stroke="#DE7A4B"
        strokeWidth="1.9"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

/** Steaming cup: the Java engine (Minecraft's own renderer). */
function JavaIcon({ size }: { size: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M9.2 3.6c-1.3 1.3 1.3 2.3 0 3.8M12.6 2.8c-1.3 1.4 1.3 2.5 0 4.1" stroke="#E76F00" strokeWidth="1.7" strokeLinecap="round" />
      <path
        d="M4.8 10.4h11.4v3.8a4.6 4.6 0 0 1-4.6 4.6H9.4a4.6 4.6 0 0 1-4.6-4.6v-3.8Z"
        stroke="#5382A1"
        strokeWidth="1.9"
        strokeLinejoin="round"
      />
      <path d="M16.2 11.8h1.4a2.1 2.1 0 0 1 0 4.2h-1.9M3.8 21.2h14" stroke="#5382A1" strokeWidth="1.9" strokeLinecap="round" />
    </svg>
  );
}

export function EngineIcon({ engine, size = 16, className }: { engine: EngineKind; size?: number; className?: string }) {
  return (
    <span className={cn("inline-flex shrink-0 items-center justify-center", className)} title={engine === "rust" ? "Rust" : "Java"}>
      {engine === "rust" ? <RustIcon size={size} /> : <JavaIcon size={size} />}
    </span>
  );
}
