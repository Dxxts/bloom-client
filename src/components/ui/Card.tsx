import type { HTMLAttributes, ReactNode } from "react";
import { cn } from "@/utils/format";

export function Card({ className, ...rest }: HTMLAttributes<HTMLDivElement>) {
  // Slightly translucent so the page's artwork tint carries through, as on Home.
  return <div className={cn("rounded-xl bg-surface/85 bg-gradient-to-b from-white/[0.03] to-transparent ring-1 ring-inset ring-white/[0.06] shadow-[inset_0_1px_0_rgb(255_255_255/0.05),0_14px_30px_-20px_rgb(0_0_0/0.9)]", className)} {...rest} />;
}

export function CardHeader({
  title,
  subtitle,
  icon,
  action,
  className,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  icon?: ReactNode;
  action?: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("flex items-start gap-3", className)}>
      {icon && <div className="mt-0.5 text-primary">{icon}</div>}
      <div className="min-w-0 flex-1">
        <h3 className="text-[12px] font-bold uppercase tracking-[0.1em] text-muted">{title}</h3>
        {subtitle && <p className="mt-1 text-[13px] text-faint">{subtitle}</p>}
      </div>
      {action}
    </div>
  );
}

export function Badge({
  children,
  tone = "neutral",
  className,
}: {
  children: ReactNode;
  tone?: "neutral" | "primary" | "warning" | "error" | "info";
  className?: string;
}) {
  return (
    <span
      className={cn(
        "inline-flex h-[22px] items-center gap-1 whitespace-nowrap rounded-md px-2 text-[11.5px] font-semibold",
        tone === "neutral" && "bg-white/[0.06] text-muted",
        tone === "primary" && "bg-primary-soft text-primary",
        tone === "warning" && "bg-warning/12 text-warning",
        tone === "info" && "bg-info/12 text-info",
        tone === "error" && "bg-error/12 text-error",
        className,
      )}
    >
      {children}
    </span>
  );
}

export function Skeleton({ className }: { className?: string }) {
  return <div className={cn("animate-pulse-soft rounded-lg bg-surface-hover", className)} aria-hidden />;
}

export function PageHeader({
  title,
  subtitle,
  actions,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <header className="mb-6 flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
      <div className="min-w-0">
        <h1 className="text-[30px] font-extrabold leading-[1.1] tracking-[-0.02em] text-text drop-shadow-[0_2px_12px_rgb(0_0_0/0.35)]">
          {title}
        </h1>
        {subtitle && <p className="mt-1.5 text-[13.5px] font-medium text-muted">{subtitle}</p>}
      </div>
      {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
    </header>
  );
}
