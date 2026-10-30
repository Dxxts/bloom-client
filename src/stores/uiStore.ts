import type { ReactNode } from "react";
import { create } from "zustand";

export type ToastKind = "success" | "error" | "info" | "warning";

export interface Toast {
  id: number;
  kind: ToastKind;
  title: string;
  message?: string;
  action?: { label: string; onClick: () => void };
}

interface ConfirmRequest {
  title: string;
  message: ReactNode;
  confirmLabel: string;
  cancelLabel: string;
  danger?: boolean;
  resolve: (ok: boolean) => void;
}

interface ErrorDialogRequest {
  title: string;
  message: string;
  action?: { label: string; onClick: () => void };
}

interface UiState {
  toasts: Toast[];
  confirmRequest: ConfirmRequest | null;
  errorDialog: ErrorDialogRequest | null;
  pushToast: (t: Omit<Toast, "id">) => void;
  dismissToast: (id: number) => void;
  confirm: (req: Omit<ConfirmRequest, "resolve">) => Promise<boolean>;
  resolveConfirm: (ok: boolean) => void;
  showError: (req: ErrorDialogRequest) => void;
  closeError: () => void;
}

let nextId = 1;
const timers = new Map<number, ReturnType<typeof setTimeout>>();

export const useUiStore = create<UiState>((set, get) => ({
  toasts: [],
  confirmRequest: null,
  errorDialog: null,

  pushToast: (t) => {
    const id = nextId++;
    set((s) => ({ toasts: [...s.toasts.slice(-3), { ...t, id }] }));
    timers.set(
      id,
      setTimeout(() => get().dismissToast(id), t.kind === "error" ? 8000 : 4500),
    );
  },

  dismissToast: (id) => {
    const timer = timers.get(id);
    if (timer) clearTimeout(timer);
    timers.delete(id);
    set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) }));
  },

  confirm: (req) =>
    new Promise<boolean>((resolve) => {
      get().confirmRequest?.resolve(false);
      set({ confirmRequest: { ...req, resolve } });
    }),

  resolveConfirm: (ok) => {
    get().confirmRequest?.resolve(ok);
    set({ confirmRequest: null });
  },

  showError: (req) => set({ errorDialog: req }),
  closeError: () => set({ errorDialog: null }),
}));

export const toast = {
  success: (title: string, message?: string) => useUiStore.getState().pushToast({ kind: "success", title, message }),
  info: (title: string, message?: string) => useUiStore.getState().pushToast({ kind: "info", title, message }),
  warning: (title: string, message?: string) => useUiStore.getState().pushToast({ kind: "warning", title, message }),
  error: (title: string, message?: string) => useUiStore.getState().pushToast({ kind: "error", title, message }),
};
