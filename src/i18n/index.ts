import { useCallback } from "react";
import { useAppStore } from "@/stores/appStore";
import { translate, type TFunction } from "./translate";

export { translate };
export type { TFunction };
export type { TranslationKey } from "./en";

/** Returns a translation function bound to the current language setting. */
export function useT(): TFunction {
  const lang = useAppStore((s) => s.settings?.language ?? "en");
  return useCallback<TFunction>((key, vars) => translate(lang, key, vars), [lang]);
}

export function useLocale(): string {
  const lang = useAppStore((s) => s.settings?.language ?? "en");
  return lang === "it" ? "it-IT" : "en-US";
}

/** Translation outside React (event handlers in stores). */
export function t(...args: Parameters<TFunction>): string {
  const lang = useAppStore.getState().settings?.language ?? "en";
  return translate(lang, ...args);
}
