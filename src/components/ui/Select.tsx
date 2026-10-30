import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { AnimatePresence, motion } from "motion/react";
import { Check, ChevronDown } from "lucide-react";
import { cn } from "@/utils/format";

export interface SelectOption<T extends string> {
  value: T;
  label: ReactNode;
  description?: ReactNode;
  disabled?: boolean;
  badge?: ReactNode;
  /** Shown before the label, in the list and in the closed select. */
  icon?: ReactNode;
  /** Options with the same group are listed under one header. */
  group?: string;
  groupLabel?: ReactNode;
}

/** Accessible custom listbox with keyboard support. */
export function Select<T extends string>({
  value,
  options,
  onChange,
  label,
  placeholder,
  className,
  buttonClassName,
  align = "left",
  maxHeight = 280,
}: {
  value: T | null;
  options: SelectOption<T>[];
  onChange: (v: T) => void;
  label: string;
  placeholder?: ReactNode;
  className?: string;
  buttonClassName?: string;
  align?: "left" | "right";
  maxHeight?: number;
}) {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const root = useRef<HTMLDivElement>(null);
  const list = useRef<HTMLUListElement>(null);
  const id = useId();
  const selected = options.find((o) => o.value === value);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
  }, [open]);

  useEffect(() => {
    if (open) {
      const idx = Math.max(0, options.findIndex((o) => o.value === value));
      setActive(idx);
      requestAnimationFrame(() => {
        list.current?.querySelector<HTMLElement>(`[data-index="${idx}"]`)?.scrollIntoView({ block: "nearest" });
      });
    }
  }, [open, options, value]);

  const move = (dir: 1 | -1) => {
    if (!options.length) return;
    let next = active;
    for (let i = 0; i < options.length; i++) {
      next = (next + dir + options.length) % options.length;
      if (!options[next].disabled) break;
    }
    setActive(next);
    list.current?.querySelector<HTMLElement>(`[data-index="${next}"]`)?.scrollIntoView({ block: "nearest" });
  };

  const choose = (idx: number) => {
    const o = options[idx];
    if (!o || o.disabled) return;
    onChange(o.value);
    setOpen(false);
  };

  return (
    <div ref={root} className={cn("relative", className)}>
      <button
        type="button"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={id}
        aria-label={label}
        onClick={() => setOpen((o) => !o)}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown" || e.key === "ArrowUp") {
            e.preventDefault();
            if (!open) setOpen(true);
            else move(e.key === "ArrowDown" ? 1 : -1);
          } else if ((e.key === "Enter" || e.key === " ") && open) {
            e.preventDefault();
            choose(active);
          } else if (e.key === "Escape") {
            setOpen(false);
          }
        }}
        className={cn(
          "flex h-10 w-full items-center justify-between gap-2 rounded-xl border border-white/[0.09] bg-black/25 px-3.5 text-left text-[13.5px] text-text transition-colors hover:border-white/[0.16]",
          open && "border-primary/60",
          buttonClassName,
        )}
      >
        <span className="flex min-w-0 flex-1 items-center gap-2 truncate">
          {selected?.icon}
          <span className="truncate">{selected ? selected.label : <span className="text-faint">{placeholder}</span>}</span>
        </span>
        <ChevronDown size={15} className={cn("shrink-0 text-muted transition-transform", open && "rotate-180")} />
      </button>
      <AnimatePresence>
        {open && (
          <motion.ul
            ref={list}
            id={id}
            role="listbox"
            aria-label={label}
            initial={{ opacity: 0, y: -4 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -4 }}
            transition={{ duration: 0.12 }}
            style={{ maxHeight }}
            className={cn(
              "absolute z-40 mt-1.5 min-w-full overflow-y-auto rounded-xl border border-line-strong bg-overlay p-1 shadow-2xl shadow-black/50",
              align === "right" ? "right-0" : "left-0",
            )}
          >
            {options.map((o, idx) => [
              o.group !== undefined && o.group !== options[idx - 1]?.group && (
                <li
                  key={`group-${o.group}`}
                  role="presentation"
                  className="flex items-center gap-2 px-3 pb-1 pt-2.5 text-[10.5px] font-bold uppercase tracking-[0.08em] text-faint first:pt-1.5"
                >
                  {o.groupLabel ?? o.group}
                </li>
              ),
              <li
                key={o.value}
                role="option"
                aria-selected={o.value === value}
                aria-disabled={o.disabled}
                data-index={idx}
                onMouseEnter={() => setActive(idx)}
                onClick={() => choose(idx)}
                className={cn(
                  "flex items-center gap-2.5 rounded-lg px-3 py-2 text-[13px]",
                  o.disabled ? "cursor-not-allowed opacity-45" : "cursor-pointer",
                  idx === active && !o.disabled && "bg-surface-hover",
                )}
              >
                {o.icon}
                <div className="min-w-0 flex-1">
                  <div className="truncate text-text">{o.label}</div>
                  {o.description && <div className="truncate text-[11.5px] text-faint">{o.description}</div>}
                </div>
                {o.badge}
                {o.value === value && <Check size={14} className="shrink-0 text-primary" />}
              </li>,
            ])}
          </motion.ul>
        )}
      </AnimatePresence>
    </div>
  );
}

/** Segmented control for small sets of mutually exclusive options. */
export function Segmented<T extends string>({
  value,
  options,
  onChange,
  label,
  layoutId,
}: {
  value: T;
  options: { value: T; label: ReactNode }[];
  onChange: (v: T) => void;
  label: string;
  layoutId: string;
}) {
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex rounded-xl bg-black/25 p-1 ring-1 ring-inset ring-white/[0.08]">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          role="radio"
          aria-checked={o.value === value}
          onClick={() => onChange(o.value)}
          className={cn(
            "relative h-8 rounded-lg px-3.5 text-[12.5px] font-semibold transition-colors",
            o.value === value ? "text-text" : "text-muted hover:text-text",
          )}
        >
          {o.value === value && (
            <motion.span
              layoutId={layoutId}
              className="absolute inset-0 rounded-lg bg-white/[0.1] shadow-[inset_0_1px_0_rgb(255_255_255/0.08),0_1px_3px_rgb(0_0_0/0.4)]"
              transition={{ type: "spring", stiffness: 400, damping: 34, mass: 0.8 }}
            />
          )}
          <span className="relative">{o.label}</span>
        </button>
      ))}
    </div>
  );
}
