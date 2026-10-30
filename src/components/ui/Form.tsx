import { forwardRef, useId, type InputHTMLAttributes, type ReactNode, type TextareaHTMLAttributes } from "react";
import { Search } from "lucide-react";
import { motion } from "motion/react";
import { cn } from "@/utils/format";

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement> & { invalid?: boolean }>(
  function Input({ className, invalid, ...rest }, ref) {
    return (
      <input
        ref={ref}
        className={cn(
          "h-10 w-full rounded-xl border bg-black/25 px-3.5 text-[13.5px] text-text placeholder:text-faint transition-[border-color,box-shadow] focus:border-primary/60 focus:shadow-[0_0_0_3px_rgb(101_217_154/0.12)] focus:outline-none",
          invalid ? "border-error/60" : "border-white/[0.09] hover:border-white/[0.16]",
          className,
        )}
        {...rest}
      />
    );
  },
);

export const TextArea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(function TextArea(
  { className, ...rest },
  ref,
) {
  return (
    <textarea
      ref={ref}
      className={cn(
        "w-full resize-none rounded-xl border border-white/[0.09] bg-black/25 px-3.5 py-2.5 font-mono text-[12.5px] leading-relaxed text-text placeholder:text-faint focus:border-primary/60 focus:outline-none",
        className,
      )}
      {...rest}
    />
  );
});

export function SearchInput({
  value,
  onChange,
  placeholder,
  className,
}: {
  value: string;
  onChange: (v: string) => void;
  placeholder: string;
  className?: string;
}) {
  return (
    <div className={cn("relative", className)}>
      <Search size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-faint" />
      <Input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        aria-label={placeholder}
        className="h-10 pl-9"
        type="search"
      />
    </div>
  );
}

export function Toggle({
  checked,
  onChange,
  disabled,
  label,
}: {
  checked: boolean;
  onChange: (v: boolean) => void;
  disabled?: boolean;
  label?: string;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={cn(
        "relative inline-flex h-6 w-[42px] shrink-0 items-center rounded-full border transition-colors duration-200 disabled:opacity-40",
        checked ? "border-primary bg-primary shadow-[0_0_12px_-2px_rgb(101_217_154/0.6)]" : "border-white/10 bg-white/[0.08]",
      )}
    >
      <motion.span
        layout
        transition={{ type: "spring", stiffness: 600, damping: 35 }}
        className={cn(
          "absolute h-[18px] w-[18px] rounded-full shadow-sm",
          checked ? "left-[20px] bg-primary-ink" : "left-[2px] bg-muted",
        )}
      />
    </button>
  );
}

export function Slider({
  value,
  min,
  max,
  step = 1,
  onChange,
  format,
  label,
}: {
  value: number;
  min: number;
  max: number;
  step?: number;
  onChange: (v: number) => void;
  format?: (v: number) => string;
  label: string;
}) {
  const fill = max > min ? ((value - min) / (max - min)) * 100 : 0;
  return (
    <div className="flex items-center gap-4">
      <input
        type="range"
        className="bloom-range flex-1"
        min={min}
        max={max}
        step={step}
        value={value}
        aria-label={label}
        onChange={(e) => onChange(Number(e.target.value))}
        style={{ ["--fill" as string]: `${fill}%` }}
      />
      <span className="w-20 shrink-0 text-right font-mono text-[13px] font-medium text-text tabular-nums">
        {format ? format(value) : value}
      </span>
    </div>
  );
}

export function Field({
  label,
  hint,
  children,
  error,
}: {
  label: ReactNode;
  hint?: ReactNode;
  children: ReactNode;
  error?: string | null;
}) {
  const id = useId();
  return (
    <div className="space-y-2">
      <label htmlFor={id} className="block text-[13px] font-semibold text-text">
        {label}
      </label>
      {children}
      {error ? (
        <p className="text-[12px] text-error">{error}</p>
      ) : (
        hint && <p className="text-[12px] text-faint">{hint}</p>
      )}
    </div>
  );
}

/** Horizontal settings row: label + description on the left, control on the right. */
export function SettingRow({
  title,
  description,
  children,
  stacked,
}: {
  title: ReactNode;
  description?: ReactNode;
  children: ReactNode;
  stacked?: boolean;
}) {
  return (
    <div
      className={cn(
        "border-b border-line px-5 py-4 last:border-b-0",
        stacked ? "space-y-3" : "flex items-center justify-between gap-8",
      )}
    >
      <div className="min-w-0">
        <div className="text-[13.5px] font-semibold text-text">{title}</div>
        {description && <div className="mt-0.5 text-[12.5px] leading-relaxed text-muted">{description}</div>}
      </div>
      <div className={cn(!stacked && "shrink-0")}>{children}</div>
    </div>
  );
}
