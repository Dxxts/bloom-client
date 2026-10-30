import { useEffect, useRef, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { AnimatePresence, motion } from "motion/react";
import { X } from "lucide-react";
import { cn } from "@/utils/format";

export function Modal({
  open,
  onClose,
  title,
  description,
  children,
  footer,
  width = 520,
  dismissible = true,
}: {
  open: boolean;
  onClose: () => void;
  title: ReactNode;
  description?: ReactNode;
  children?: ReactNode;
  footer?: ReactNode;
  width?: number;
  dismissible?: boolean;
}) {
  const panel = useRef<HTMLDivElement>(null);
  // Keep the latest callback without re-running the focus effect on every
  // parent render (inline handlers would otherwise steal focus while typing).
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!open) return;
    const previous = document.activeElement as HTMLElement | null;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && dismissible) onCloseRef.current();
      if (e.key === "Tab" && panel.current) {
        // Keep focus inside the dialog.
        const focusable = panel.current.querySelectorAll<HTMLElement>(
          'button:not([disabled]), [href], input:not([disabled]), textarea, [tabindex]:not([tabindex="-1"])',
        );
        if (!focusable.length) return;
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener("keydown", onKey);
    requestAnimationFrame(() => {
      const target =
        panel.current?.querySelector<HTMLElement>("[data-autofocus]") ??
        panel.current?.querySelector<HTMLElement>("input, textarea, button");
      target?.focus();
    });
    return () => {
      document.removeEventListener("keydown", onKey);
      previous?.focus?.();
    };
  }, [open, dismissible]);

  return createPortal(
    <AnimatePresence>
      {open && (
        <motion.div
          className="fixed inset-0 z-[60] flex items-center justify-center p-6"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.22, ease: [0.22, 1, 0.36, 1] }}
        >
          <div className="absolute inset-0 bg-black/60 backdrop-blur-[3px]" onClick={dismissible ? onClose : undefined} />
          <motion.div
            ref={panel}
            role="dialog"
            aria-modal="true"
            initial={{ opacity: 0, y: 24, scale: 0.95, filter: "blur(6px)" }}
            animate={{ opacity: 1, y: 0, scale: 1, filter: "blur(0px)", transitionEnd: { filter: "none" } }}
            exit={{ opacity: 0, y: 12, scale: 0.97, filter: "blur(4px)", transition: { duration: 0.18 } }}
            transition={{ type: "spring", stiffness: 300, damping: 28, mass: 0.9 }}
            style={{ width }}
            className="relative flex max-h-[calc(100vh-96px)] max-w-full flex-col rounded-2xl bg-overlay bg-gradient-to-b from-white/[0.03] to-transparent shadow-[inset_0_1px_0_rgb(255_255_255/0.06),0_30px_80px_-20px_rgb(0_0_0/0.8)] ring-1 ring-inset ring-white/10"
          >
            <div className="flex items-start gap-4 px-6 pb-2 pt-5">
              <div className="min-w-0 flex-1">
                <h2 className="text-[18px] font-extrabold tracking-tight text-text">{title}</h2>
                {description && <p className="mt-1 text-[13px] leading-relaxed text-muted">{description}</p>}
              </div>
              {dismissible && (
                <button
                  type="button"
                  onClick={onClose}
                  aria-label="Close"
                  className="-mr-2 rounded-lg p-1.5 text-muted transition-colors hover:bg-white/[0.07] hover:text-text"
                >
                  <X size={17} />
                </button>
              )}
            </div>
            {children && <div className={cn("min-h-0 flex-1 overflow-y-auto px-6 py-3")}>{children}</div>}
            {footer && <div className="flex justify-end gap-2 border-t border-line px-6 py-4">{footer}</div>}
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>,
    document.body,
  );
}
