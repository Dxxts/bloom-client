import type { ReactNode } from "react";
import { AnimatePresence, motion } from "motion/react";
import { AlertTriangle, CheckCircle2, Info, X, XCircle } from "lucide-react";
import { useT } from "@/i18n";
import { useUiStore, type ToastKind } from "@/stores/uiStore";
import { cn } from "@/utils/format";
import { Button } from "./Button";
import { Modal } from "./Modal";

const toastIcons: Record<ToastKind, ReactNode> = {
  success: <CheckCircle2 size={18} className="text-primary" />,
  error: <XCircle size={18} className="text-error" />,
  warning: <AlertTriangle size={18} className="text-warning" />,
  info: <Info size={18} className="text-muted" />,
};

export function Toaster() {
  const toasts = useUiStore((s) => s.toasts);
  const dismiss = useUiStore((s) => s.dismissToast);
  return (
    <div className="pointer-events-none fixed bottom-5 right-5 z-[70] flex w-[360px] flex-col gap-2" aria-live="polite">
      <AnimatePresence initial={false}>
        {toasts.map((t) => (
          <motion.div
            key={t.id}
            layout
            initial={{ opacity: 0, x: 24 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: 24, transition: { duration: 0.15 } }}
            transition={{ type: "spring", stiffness: 420, damping: 32 }}
            className="pointer-events-auto flex items-start gap-3 rounded-xl bg-overlay/95 px-4 py-3 shadow-xl shadow-black/40 ring-1 ring-inset ring-white/10 backdrop-blur-xl"
            role={t.kind === "error" ? "alert" : "status"}
          >
            <div className="mt-px shrink-0">{toastIcons[t.kind]}</div>
            <div className="min-w-0 flex-1">
              <div className="text-[13px] font-semibold text-text">{t.title}</div>
              {t.message && <div className="mt-0.5 break-words text-[12.5px] leading-relaxed text-muted">{t.message}</div>}
              {t.action && (
                <button
                  type="button"
                  onClick={() => {
                    t.action?.onClick();
                    dismiss(t.id);
                  }}
                  className="mt-1.5 text-[12.5px] font-semibold text-primary hover:text-primary-hover"
                >
                  {t.action.label}
                </button>
              )}
            </div>
            <button
              type="button"
              aria-label="Dismiss"
              onClick={() => dismiss(t.id)}
              className="-mr-1 shrink-0 rounded-md p-1 text-faint transition-colors hover:text-text"
            >
              <X size={14} />
            </button>
          </motion.div>
        ))}
      </AnimatePresence>
    </div>
  );
}

/** Global confirmation and error dialogs driven by the UI store. */
export function DialogHost() {
  const t = useT();
  const confirm = useUiStore((s) => s.confirmRequest);
  const resolve = useUiStore((s) => s.resolveConfirm);
  const error = useUiStore((s) => s.errorDialog);
  const closeError = useUiStore((s) => s.closeError);

  return (
    <>
      <Modal
        open={!!confirm}
        onClose={() => resolve(false)}
        title={confirm?.title}
        width={440}
        footer={
          <>
            <Button variant="ghost" onClick={() => resolve(false)}>
              {confirm?.cancelLabel}
            </Button>
            <Button variant={confirm?.danger ? "danger" : "primary"} onClick={() => resolve(true)} data-autofocus>
              {confirm?.confirmLabel}
            </Button>
          </>
        }
      >
        <p className="pb-2 text-[13.5px] leading-relaxed text-muted">{confirm?.message}</p>
      </Modal>

      <Modal
        open={!!error}
        onClose={closeError}
        title={
          <span className="flex items-center gap-2.5">
            <XCircle size={19} className="text-error" />
            {error?.title}
          </span>
        }
        width={480}
        footer={
          <>
            {error?.action && (
              <Button
                variant="ghost"
                onClick={() => {
                  error.action?.onClick();
                  closeError();
                }}
              >
                {error.action.label}
              </Button>
            )}
            <Button variant="secondary" onClick={closeError} data-autofocus>
              {t("common.close")}
            </Button>
          </>
        }
      >
        <p className="whitespace-pre-wrap break-words pb-2 text-[13.5px] leading-relaxed text-muted" data-selectable>
          {error?.message}
        </p>
      </Modal>
    </>
  );
}

export function EmptyState({
  icon,
  title,
  body,
  action,
  className,
}: {
  icon: ReactNode;
  title: ReactNode;
  body?: ReactNode;
  action?: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("flex flex-col items-center justify-center px-6 py-14 text-center", className)}>
      <div className="mb-4 flex h-14 w-14 animate-float items-center justify-center rounded-2xl bg-white/[0.05] text-muted ring-1 ring-inset ring-white/[0.08] shadow-[inset_0_1px_0_rgb(255_255_255/0.06)]">
        {icon}
      </div>
      <h3 className="text-[16px] font-bold text-text">{title}</h3>
      {body && <p className="mt-1.5 max-w-md text-[13px] leading-relaxed text-muted">{body}</p>}
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}

export function ProgressBar({
  value,
  indeterminate,
  className,
}: {
  value?: number;
  indeterminate?: boolean;
  className?: string;
}) {
  return (
    <div
      className={cn("relative h-1.5 w-full overflow-hidden rounded-full bg-surface-hover", className)}
      role="progressbar"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={indeterminate ? undefined : Math.round(value ?? 0)}
    >
      {indeterminate ? (
        <div className="absolute inset-y-0 left-0 w-2/5 animate-shimmer rounded-full bg-gradient-to-r from-transparent via-primary to-transparent" />
      ) : (
        <motion.div
          className="absolute inset-y-0 left-0 rounded-full bg-primary"
          initial={false}
          animate={{ width: `${value ?? 0}%` }}
          transition={{ ease: "easeOut", duration: 0.25 }}
        />
      )}
    </div>
  );
}

export function Tabs<T extends string>({
  value,
  tabs,
  onChange,
  layoutId,
}: {
  value: T;
  tabs: { value: T; label: ReactNode; icon?: ReactNode }[];
  onChange: (v: T) => void;
  layoutId: string;
}) {
  return (
    <div role="tablist" className="flex gap-1 border-b border-white/[0.07]">
      {tabs.map((tab) => (
        <button
          key={tab.value}
          role="tab"
          type="button"
          aria-selected={tab.value === value}
          onClick={() => onChange(tab.value)}
          className={cn(
            "relative flex h-10 items-center gap-2 px-3.5 text-[13px] font-semibold transition-colors",
            tab.value === value ? "text-text" : "text-muted hover:text-text",
          )}
        >
          {tab.icon}
          {tab.label}
          {tab.value === value && (
            <motion.span
              layoutId={layoutId}
              className="absolute inset-x-2 -bottom-px h-[2px] rounded-full bg-primary"
              transition={{ type: "spring", stiffness: 400, damping: 34, mass: 0.8 }}
            />
          )}
        </button>
      ))}
    </div>
  );
}
