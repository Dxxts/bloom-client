import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from "react";
import { cn } from "@/utils/format";
import { Tooltip } from "./Tooltip";

interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  label: string;
  icon: ReactNode;
  tooltipSide?: "top" | "bottom" | "left" | "right";
  tone?: "default" | "danger" | "primary";
  size?: "sm" | "md";
}

export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton(
  { label, icon, tooltipSide = "top", tone = "default", size = "md", className, ...rest },
  ref,
) {
  return (
    <Tooltip content={label} side={tooltipSide}>
      <button
        ref={ref}
        type="button"
        aria-label={label}
        className={cn(
          "inline-flex items-center justify-center rounded-lg transition-[background-color,color,transform,translate,scale] duration-150 active:scale-95 disabled:opacity-40",
          size === "sm" ? "h-7 w-7" : "h-9 w-9",
          tone === "danger" && "text-muted hover:bg-error/12 hover:text-error",
          tone === "primary" && "text-primary hover:bg-primary-soft",
          tone === "default" && "text-muted hover:bg-surface-hover hover:text-text",
          className,
        )}
        {...rest}
      >
        {icon}
      </button>
    </Tooltip>
  );
});
