import type { Language } from "@/types";
import { en, type TranslationKey } from "./en";
import { it } from "./it";

const dictionaries: Record<Language, Record<TranslationKey, string>> = { en, it };

export type TFunction = (key: TranslationKey, vars?: Record<string, string | number>) => string;

export function translate(lang: Language, key: TranslationKey, vars?: Record<string, string | number>): string {
  const template = dictionaries[lang]?.[key] ?? en[key] ?? key;
  if (!vars) return template;
  return template.replace(/\{(\w+)\}/g, (m, name: string) => (name in vars ? String(vars[name]) : m));
}
