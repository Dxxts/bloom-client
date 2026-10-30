import { t } from "@/i18n";
import { toast } from "@/stores/uiStore";
import { ApiError } from "./api";

export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.code === "network" && /connect/i.test(e.message)) return `${t("error.offline")} ${e.message}`;
    return e.message;
  }
  if (e instanceof Error) return e.message;
  return t("error.generic");
}

export function isCancelled(e: unknown): boolean {
  return e instanceof ApiError && e.code === "cancelled";
}

/** Shows an error toast (cancellations are silent). */
export function reportError(e: unknown, title?: string): void {
  if (isCancelled(e)) return;
  toast.error(title ?? t("error.title"), errorMessage(e));
}
