import type { ReactNode } from "react";
import { motion } from "motion/react";

export const REVEAL_EASE = [0.22, 1, 0.36, 1] as const;

/**
 * Staggered fade-up for page sections: rises from below while a soft blur
 * clears. The filter is dropped once settled so it never creates a stacking
 * context that would trap dropdowns under the next section.
 */
export function Reveal({ index = 0, children, className }: { index?: number; children: ReactNode; className?: string }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 18, filter: "blur(8px)" }}
      animate={{ opacity: 1, y: 0, filter: "blur(0px)", transitionEnd: { filter: "none" } }}
      transition={{ duration: 0.6, delay: 0.04 + index * 0.06, ease: REVEAL_EASE }}
      className={className}
    >
      {children}
    </motion.div>
  );
}

/**
 * Motion props for the `index`-th item of a list that should cascade in after
 * the page sections. The delay is capped so long lists don't keep trickling.
 */
export function staggerItem(index: number, after = 0.14) {
  return {
    initial: { opacity: 0, y: 16, scale: 0.985 },
    animate: { opacity: 1, y: 0, scale: 1 },
    transition: { duration: 0.5, delay: after + Math.min(index, 12) * 0.04, ease: REVEAL_EASE },
  } as const;
}
