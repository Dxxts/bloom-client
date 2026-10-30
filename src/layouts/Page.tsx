import { Children, isValidElement, type ReactNode } from "react";
import { Reveal } from "@/components/ui/Reveal";
import { PageAmbient } from "@/features/home/HeroBackdrop";
import { cn } from "@/utils/format";

/**
 * Shared page shell: the same width, padding, artwork tint and staggered
 * entrance as Home, so every section reads as part of one launcher. `art`
 * picks the hero slide used for the tint; `narrow` suits form-like pages.
 * Each child fades up in turn; `footer` (e.g. a sticky save bar) is rendered
 * unwrapped so its sticky positioning keeps the whole page as its container.
 */
export function Page({
  art,
  narrow,
  className,
  footer,
  children,
}: {
  art: number;
  narrow?: boolean;
  className?: string;
  footer?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="relative isolate min-h-full">
      <PageAmbient index={art} />
      <div className={cn("@container relative mx-auto p-6", narrow ? "max-w-[960px]" : "max-w-[1560px]", className)}>
        {Children.toArray(children).map((child, i) => (
          <Reveal key={isValidElement(child) ? child.key : i} index={i}>
            {child}
          </Reveal>
        ))}
        {footer}
      </div>
    </div>
  );
}
