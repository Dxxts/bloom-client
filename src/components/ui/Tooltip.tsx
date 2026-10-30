import { useId, useRef, useState, type ReactElement, type ReactNode } from "react";
import { AnimatePresence, motion } from "motion/react";
import { cn } from "@/utils/format";

type Side = "top" | "bottom" | "right" | "left";

const positions: Record<Side, string> = {
  top: "bottom-full left-1/2 -translate-x-1/2 mb-2",
  bottom: "top-full left-1/2 -translate-x-1/2 mt-2",
  right: "left-full top-1/2 -translate-y-1/2 ml-2.5",
  left: "right-full top-1/2 -translate-y-1/2 mr-2.5",
};

const offsets: Record<Side, { x?: number; y?: number }> = {
  top: { y: 4 },
  bottom: { y: -4 },
  right: { x: -4 },
  left: { x: 4 },
};

/** Lightweight tooltip shown on hover and keyboard focus. */
export function Tooltip({
  content,
  side = "top",
  children,
  disabled,
  className,
}: {
  content: ReactNode;
  side?: Side;
  children: ReactElement;
  disabled?: boolean;
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const id = useId();

  const show = () => {
    if (disabled) return;
    timer.current = setTimeout(() => setOpen(true), 350);
  };
  const hide = () => {
    if (timer.current) clearTimeout(timer.current);
    setOpen(false);
  };

  return (
    <span
      className={cn("relative inline-flex", className)}
      onMouseEnter={show}
      onMouseLeave={hide}
      onFocus={show}
      onBlur={hide}
      aria-describedby={open ? id : undefined}
    >
      {children}
      <AnimatePresence>
        {open && !disabled && (
          <motion.span
            id={id}
            role="tooltip"
            initial={{ opacity: 0, ...offsets[side] }}
            animate={{ opacity: 1, x: 0, y: 0 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.12 }}
            className={cn(
              "pointer-events-none absolute z-50 whitespace-nowrap rounded-lg border border-line-strong bg-surface-active px-2.5 py-1.5 text-[12px] font-medium text-text shadow-xl shadow-black/40",
              positions[side],
            )}
          >
            {content}
          </motion.span>
        )}
      </AnimatePresence>
    </span>
  );
}
