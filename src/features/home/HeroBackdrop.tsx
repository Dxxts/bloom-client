import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import blossomRiver from "@/assets/hero/blossom-river.webp";
import savanna from "@/assets/hero/savanna.webp";
import village from "@/assets/hero/village.webp";
import violetGrove from "@/assets/hero/violet-grove.webp";
import { cn } from "@/utils/format";

/**
 * In-game screenshots; `position` keeps each focal point clear of the
 * left-hand text (savanna.webp is mirrored so its subject sits on the right).
 */
export const HERO_SLIDES = [
  { src: blossomRiver, position: "50% 42%" },
  { src: savanna, position: "60% 30%" },
  { src: village, position: "70% 38%" },
  { src: violetGrove, position: "50% 55%" },
];

const INTERVAL_MS = 9000;

/** Rotating slide index, paused while `paused` is true. */
export function useHeroSlide(paused: boolean) {
  const [index, setIndex] = useState(0);
  useEffect(() => {
    // Warm the cache so crossfades never flash an unloaded image.
    for (const s of HERO_SLIDES) new Image().src = s.src;
  }, []);
  useEffect(() => {
    if (paused) return;
    const id = setInterval(() => setIndex((i) => (i + 1) % HERO_SLIDES.length), INTERVAL_MS);
    return () => clearInterval(id);
  }, [paused, index]);
  return [index, setIndex] as const;
}

/** Crossfading screenshot backdrop with a slow push-in. */
export function HeroBackdrop({ index }: { index: number }) {
  const slide = HERO_SLIDES[index];

  return (
    <div className="absolute inset-0">
      <AnimatePresence initial={false}>
        <motion.img
          key={slide.src}
          src={slide.src}
          alt=""
          draggable={false}
          initial={{ opacity: 0, scale: 1.08 }}
          animate={{ opacity: 1, scale: 1.0 }}
          exit={{ opacity: 0 }}
          transition={{ opacity: { duration: 1.2, ease: "easeInOut" }, scale: { duration: 12, ease: "linear" } }}
          className="absolute inset-0 h-full w-full object-cover"
          style={{ objectPosition: slide.position }}
        />
      </AnimatePresence>
    </div>
  );
}

/** Soft, blurred copy of the current slide that tints the page behind the hero. */
export function HeroAmbient({ index }: { index: number }) {
  return (
    <div
      aria-hidden
      className="pointer-events-none absolute inset-x-0 top-0 h-[720px] overflow-hidden [mask-image:linear-gradient(to_bottom,black_30%,transparent)]"
    >
      <AnimatePresence initial={false}>
        <motion.img
          key={HERO_SLIDES[index].src}
          src={HERO_SLIDES[index].src}
          alt=""
          initial={{ opacity: 0 }}
          animate={{ opacity: 0.28 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 1.6, ease: "easeInOut" }}
          className="absolute inset-0 h-full w-full scale-110 object-cover blur-[90px] saturate-150"
        />
      </AnimatePresence>
    </div>
  );
}

/** Stable artwork for something with an id (e.g. an instance banner). */
export function artFor(id: string) {
  // FNV-1a: spreads similar UUIDs better than a plain polynomial hash.
  let h = 0x811c9dc5;
  for (let i = 0; i < id.length; i++) h = Math.imul(h ^ id.charCodeAt(i), 0x01000193);
  return HERO_SLIDES[(h >>> 0) % HERO_SLIDES.length];
}

/** Static, fainter variant of {@link HeroAmbient} that tints the top of a page. */
export function PageAmbient({ index }: { index: number }) {
  return (
    <div
      aria-hidden
      className="pointer-events-none absolute inset-x-0 top-0 h-[560px] overflow-hidden [mask-image:linear-gradient(to_bottom,black_20%,transparent)]"
    >
      <motion.img
        src={HERO_SLIDES[index % HERO_SLIDES.length].src}
        alt=""
        draggable={false}
        initial={{ opacity: 0, scale: 1.25 }}
        animate={{ opacity: 0.28, scale: 1.1 }}
        transition={{ duration: 1.6, ease: [0.22, 1, 0.36, 1] }}
        className="absolute inset-0 h-full w-full object-cover blur-[90px] saturate-150"
      />
    </div>
  );
}

export function SlideDots({ index, onSelect, label }: { index: number; onSelect: (i: number) => void; label: string }) {
  return (
    <div className="flex items-center gap-1.5" role="tablist" aria-label={label}>
      {HERO_SLIDES.map((s, i) => (
        <button
          key={s.src}
          type="button"
          role="tab"
          aria-selected={i === index}
          aria-label={`${label} ${i + 1}`}
          onClick={() => onSelect(i)}
          className="group flex h-5 items-center px-0.5"
        >
          <span
            className={cn(
              "block h-1.5 rounded-full transition-all duration-300",
              i === index ? "w-6 bg-white/90" : "w-1.5 bg-white/35 group-hover:bg-white/60",
            )}
          />
        </button>
      ))}
    </div>
  );
}
